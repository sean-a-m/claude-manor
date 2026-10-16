(ns manor.engine
  "The imperative shell around the pure world.

  A run owns: the world (an atom, changed only by `emit!`), the in-memory
  event list, a status map for UIs, listeners, and a single-thread executor.
  Rounds and operator commands are both submitted to that executor, so
  operator commands always land between rounds, never in the middle of one.

  A round:
    1. :round/started
    2. travellers who are due arrive; run act-bridges the operator approved
    3. run world systems (ticks, possibly fed by bridges)
    4. for each schedule group: activate its agents, resolve their actions
    5. call the GM if anything triggers it
    6. :round/ended, then check pause conditions"
  (:require [clojure.string :as str]
            [manor.actions :as a]
            [manor.bridge :as bridge]
            [manor.builtins :as b]
            [manor.llm :as llm]
            [manor.mcp :as mcp]
            [manor.prompt :as prompt]
            [manor.space :as space]
            [manor.store :as store]
            [manor.world :as w])
  (:import (java.util.concurrent ExecutorService Future TimeUnit)))

(def default-opts
  {:seed 1
   :llm :openrouter                 ; :openrouter or :mock
   :model "deepseek/deepseek-v4.1-flash"
   :gm-model nil                    ; defaults to :model
   :gm :llm                         ; who fills the GM seat: :llm, :human or :off
   :reasoning {:effort "low"}
   :max-tokens 4000                 ; includes reasoning tokens
   :max-steps 4                     ; model calls per agent turn
   :max-queries 2                   ; query-bridge calls per agent turn
   :gm-max-actions 4
   :event-window 30                 ; recent events shown to each agent
   :gm-check-in-every 0             ; 0 = only when triggered
   :max-cost 1.0                    ; USD; pauses the run when reached
   :max-rounds nil
   :auto-delay-ms 0
   :offline? false                  ; use scenario :offline-stubs for bridges
   :test? false})                   ; store under test-runs/ (disposable) instead of runs/

(def ^:private non-data-opts [:bridge-stubs :llm-impl])

;; ---------------------------------------------------------------------------
;; Scenarios

(defn load-scenario
  "Scenarios live in manor.scenarios.<name> as a var called `scenario`."
  [scenario-key]
  (let [ns-sym (symbol (str "manor.scenarios." (name scenario-key)))]
    (require ns-sym)
    @(ns-resolve ns-sym 'scenario)))

(defn all-actions [scenario]
  (a/merge-actions (b/agent-actions) (space/actions) (b/gm-actions scenario) (:actions scenario)))

;; ---------------------------------------------------------------------------
;; Run plumbing

(defn- make-llm [scenario opts]
  (or (:llm-impl opts)
      (case (:llm opts)
        :mock (llm/mock (:mock-policy scenario))
        :openrouter (llm/openrouter))))

(defn- make-run [id dir scenario opts]
  (let [opts (cond-> opts
               (:offline? opts) (update :bridge-stubs #(merge (:offline-stubs scenario) %)))]
  {:id id
   :dir dir
   :scenario scenario
   :opts opts
   :actions (all-actions scenario)
   :llm (make-llm scenario opts)
   :mcp (mcp/pool (mcp/server-configs (:mcp-servers scenario) (mcp/local-config) (:mcp-servers opts))
                  {:run-id id :run-dir dir})
   :world (atom (w/new-world))
   :events (atom [])
   :status (atom {:state :idle :mode :manual :acting #{}})
   :listeners (atom {})
   :last-calls (atom {})
   :input (atom nil)
   :exec (java.util.concurrent.ThreadPoolExecutor.
          1 1 0 TimeUnit/MILLISECONDS (java.util.concurrent.LinkedBlockingQueue.))}))

(defn world [run] @(:world run))
(defn events [run] @(:events run))
(defn status [run] @(:status run))

(defn- notify! [run msg]
  (doseq [f (vals @(:listeners run))]
    (try (f msg) (catch Throwable _))))

(defn listen! [run k f] (swap! (:listeners run) assoc k f))
(defn unlisten! [run k] (swap! (:listeners run) dissoc k))

(defn- set-status! [run f & args]
  (apply swap! (:status run) f args)
  (notify! run {:kind :status :status @(:status run)}))

(defn emit!
  "Stamp, apply, persist and broadcast events. The only way the world changes."
  [run events]
  (locking (:world run)
    (doseq [e events]
      (let [e (w/stamp @(:world run) e)]
        (swap! (:world run) w/apply-stamped e)
        (swap! (:events run) conj e)
        (store/append-event! (:dir run) e)
        (notify! run {:kind :event :event e})))))

(defn- bridge-ctx [run] {:run-id (:id run) :run-dir (str (:dir run)) :opts (:opts run) :mcp (:mcp run)})

(defn- record-bridge-cost!
  "Bridges that spend money (e.g. an MCP server running its own model)
  report a cost; count it towards the run's usage, under the caller."
  [run who bridge cost]
  (when (and cost (pos? cost))
    (emit! run [{:type :llm/usage :actor who :visible-to :system
                 :data {:agent who :model (str "bridge " (subs (str bridge) 1)) :cost cost}}])))

(defn- action-named [run n] (get (a/by-name (:actions run)) (name n)))

;; ---------------------------------------------------------------------------
;; Resolving actions

(defn- action-events
  "Events for a valid action. An :act action becomes a pending bridge request."
  [action world actor args]
  (if (= :act (a/kind action))
    (let [bargs ((or (:bridge-args action) (fn [_ _ args] args)) world actor args)]
      [{:type :bridge/requested :actor actor :visible-to #{actor}
        :data {:req-id (str "req-" (inc (:seq world)))
               :bridge (:bridge action) :action (:name action)
               :args args :bridge-args bargs}}])
    (a/effect-events action world actor args)))

(defn- resolve!
  "Check and apply an action against the current world, atomically.
  Returns {:ok true} or {:rejected reason}."
  [run actor action args]
  (locking (:world run)
    (let [world @(:world run)]
      (if-let [reason (a/check action world actor args)]
        {:rejected reason}
        (do (emit! run (action-events action world actor args))
            {:ok true})))))

(defn- run-query!
  "Run a query bridge during a turn. The result goes back to the caller and
  is recorded as an event only the caller (and the operator) can see."
  [run actor action args]
  (let [world @(:world run)]
    (if-let [reason (a/check action world actor args)]
      {:error reason}
      (let [bargs ((or (:bridge-args action) (fn [_ _ args] args)) world actor args)
            {:keys [result error cost] :as res} (bridge/call (:scenario run) (:opts run) (bridge-ctx run)
                                                             (:bridge action) bargs)]
        (locking (:world run)
          (emit! run [{:type :bridge/called :actor actor :visible-to #{actor}
                       :data {:bridge (:bridge action) :args bargs :result result :error error}}])
          (record-bridge-cost! run actor (:bridge action) cost)
          (when (and result (:on-result action))
            (emit! run (mapv #(cond-> % (not (:actor %)) (assoc :actor actor))
                             ((:on-result action) @(:world run) actor args result)))))
        res))))

;; ---------------------------------------------------------------------------
;; Activations

(defn- record-call! [run agent-id request response]
  (let [entry {:agent agent-id
               :round (:round @(:world run))
               :model (:model request)
               :messages (:messages request)
               :tools (mapv #(get-in % [:function :name]) (:tools request))
               :response (:message response)
               :finish-reason (:finish-reason response)
               :usage (:usage response)}]
    (store/append-llm! (:dir run) entry)
    (swap! (:last-calls run) assoc agent-id entry)
    (emit! run [{:type :llm/usage :actor agent-id :visible-to :system
                 :data (assoc (:usage response) :agent agent-id :model (:model request))}])))

(defn- handle-call
  "Process one tool call within a turn. `commit` either applies a turn-ending
  action now (sequential turns, GM) or only checks it and defers it
  (simultaneous groups). Returns the updated turn state."
  [run agent-id by-name {:keys [commit max-finals max-queries]} st {:keys [id name arguments]}]
  (let [reply (fn [st text] (update st :replies conj {:role "tool" :tool_call_id id :content text}))
        action (get by-name name)]
    (cond
      (:done? st) (reply st "ignored: your turn is already over")
      (nil? action) (reply st (str "error: there is no tool called " name))
      :else
      (let [{:keys [args error]} (a/decode-args action arguments)]
        (if error
          (reply st (str "error: " error))
          (case (a/kind action)
            :free
            (let [{:keys [rejected]} (resolve! run agent-id action args)]
              (-> st (update :used conj name) (reply (if rejected (str "rejected: " rejected) "ok"))))

            :query
            (if (>= (:queries st) max-queries)
              (reply st "error: query limit reached for this turn; take your action now")
              (let [{:keys [result error]} (run-query! run agent-id action args)]
                (-> st
                    (update :queries inc)
                    (update :used conj name)
                    (reply (if error (str "error: " error) (prompt/->json result))))))

            (:final :act)
            (let [{:keys [rejected]} (commit action args)]
              (if rejected
                (reply st (str "rejected: " rejected))
                (let [st (-> st
                             (update :finals conj {:action action :args args})
                             (update :used conj name)
                             (reply (if (= :act (a/kind action))
                                      "requested; it will happen if the operator approves"
                                      "done")))]
                  (cond-> st
                    (or (= "done" name) (>= (count (:finals st)) max-finals))
                    (assoc :done? true)))))))))))

(defn- activate-llm [run snapshot agent-id {:keys [role reasons max-finals commit]}]
  (let [{:keys [scenario opts]} run
        gm? (= role :gm)
        actions (a/available (a/for-role (:actions run) role) snapshot agent-id)
        by-name (a/by-name actions)
        tools (mapv a/->tool actions)
        agent (w/agent snapshot agent-id)
        model (or (:model agent) (when gm? (:gm-model opts)) (:model opts))
        base (if gm?
               (prompt/gm-messages scenario snapshot @(:events run) reasons opts)
               (prompt/agent-messages scenario snapshot @(:events run) agent-id opts))
        max-steps (if gm? (+ 2 (:gm-max-actions opts)) (:max-steps opts))
        call-opts {:commit commit :max-finals max-finals :max-queries (:max-queries opts)}]
    (loop [messages base, step 0, st {:finals [] :queries 0 :used [] :nudged false}]
      (if (or (:done? st) (>= step max-steps))
        st
        (let [request {:model model :messages messages :tools tools
                       :max-tokens (:max-tokens opts) :reasoning (:reasoning opts)}
              response (llm/complete (:llm run)
                                     (assoc request :ctx {:world @(:world run) :agent-id agent-id :role role
                                                          :step step :used (:used st) :reasons reasons}))
              _ (record-call! run agent-id request response)
              calls (:tool-calls response)]
          (if (empty? calls)
            (if (:nudged st)
              st
              (recur (conj messages (:message response)
                           {:role "user" :content "You must respond by calling one of the tools."})
                     (inc step) (assoc st :nudged true)))
            (let [st' (reduce (partial handle-call run agent-id by-name call-opts)
                              (assoc st :replies []) calls)]
              (recur (into (conj messages (:message response)) (:replies st'))
                     (inc step) (dissoc st' :replies)))))))))

(defn- activate-human
  "A human holds this seat: wait for `act!` input. Free and query actions
  are applied and the wait continues; a turn-ending action finishes."
  [run agent-id {:keys [role max-finals commit]}]
  (let [by-name (a/by-name (a/available (a/for-role (:actions run) role) @(:world run) agent-id))
        call-opts {:commit commit :max-finals max-finals :max-queries (:max-queries (:opts run))}]
    (loop [st {:finals [] :queries 0 :used []} feedback nil]
      (let [p (promise)]
        (reset! (:input run) {:agent agent-id :promise p :tools (sort (keys by-name)) :feedback feedback})
        (set-status! run assoc :state :awaiting-human :awaiting agent-id)
        (let [{:keys [action args]} @p
              st' (handle-call run agent-id by-name call-opts (assoc st :replies [])
                               {:id "human" :name action :arguments args})
              fb (:content (last (:replies st')))
              st' (dissoc st' :replies)]
          (if (:done? st')
            (do (reset! (:input run) nil)
                (set-status! run assoc :state :running :awaiting nil :last-human-feedback fb)
                st')
            (recur st' fb)))))))

(defn- activate
  "One agent's turn. Never throws: failures forfeit the turn and ask the
  run to pause so the operator can look."
  [run snapshot agent-id {:keys [role] :as turn}]
  (set-status! run update :acting conj agent-id)
  (try
    (let [controller (:controller (w/agent snapshot agent-id))
          st (if (= :human controller)
               (activate-human run agent-id turn)
               (activate-llm run snapshot agent-id turn))]
      (when (and (empty? (:finals st)) (not= role :gm))
        (emit! run [{:type :action/forfeited :actor agent-id :visible-to #{agent-id}
                     :data {:reason "no valid action taken"}}]))
      (assoc st :agent-id agent-id))
    (catch Throwable t
      (emit! run [{:type :action/forfeited :actor agent-id :visible-to #{agent-id}
                   :data {:reason "the turn failed"}}
                  {:type :run/pause-requested :visible-to :system
                   :data {:reason (str "error during " agent-id "'s turn: " (ex-message t))}}])
      {:agent-id agent-id :finals []})
    (finally
      (set-status! run update :acting disj agent-id))))

;; ---------------------------------------------------------------------------
;; Rounds

(defn- schedule
  "Groups of agents to act this round. Travellers are skipped."
  [run world]
  (let [ids (mapv :id (remove :travel (w/active-agents world)))
        s (or (:schedule (:scenario run)) :round-robin)]
    (cond
      (fn? s) (s world)
      (= s :simultaneous) [ids]
      :else (mapv vector ids))))

(defn- run-group!
  "Agents in a group act on the same snapshot. A group of one commits its
  action immediately; a larger group's actions are checked during the turn
  and resolved afterwards in schedule order, so conflicts are rejected."
  [run ids]
  (let [ids (filter #(and (w/active? @(:world run) %) (not (space/in-transit? @(:world run) %))) ids)
        snapshot @(:world run)
        simultaneous? (> (count ids) 1)
        turn (fn [id]
               (let [commit (if simultaneous?
                              (fn [action args]
                                (when-let [r (a/check action @(:world run) id args)] {:rejected r}))
                              (fn [action args] (resolve! run id action args)))]
                 {:role (:role (w/agent snapshot id)) :max-finals 1 :commit commit}))
        results (if simultaneous?
                  (mapv deref (mapv #(future (activate run snapshot % (turn %))) ids))
                  (mapv #(activate run snapshot % (turn %)) ids))]
    (when simultaneous?
      (doseq [{:keys [agent-id finals]} results
              {:keys [action args]} finals]
        (when-let [reason (:rejected (resolve! run agent-id action args))]
          (emit! run [{:type :action/rejected :actor agent-id :visible-to #{agent-id}
                       :data {:action (name (:name action)) :reason reason}}]))))))

(defn- run-approved-bridges! [run]
  (doseq [[req-id {:keys [bridge bridge-args action args requester status]}] (:pending-bridges @(:world run))
          :when (= status :approved)]
    (let [{:keys [result error cost]} (bridge/call (:scenario run) (:opts run) (bridge-ctx run) bridge bridge-args)
          on-result (:on-result (action-named run action))]
      (locking (:world run)
        (emit! run [{:type :bridge/completed :actor requester :visible-to #{requester}
                     :data {:req-id req-id :bridge bridge :result result :error error}}])
        (record-bridge-cost! run requester bridge cost)
        (when (and result on-result)
          (emit! run (on-result @(:world run) requester args result)))))))

(defn- run-systems! [run]
  (doseq [{:keys [name bridge bridge-args tick]} (:systems (:scenario run))]
    (let [res (when bridge
                (let [bargs (bridge-args @(:world run))
                      r (bridge/call (:scenario run) (:opts run) (bridge-ctx run) bridge bargs)]
                  (emit! run [{:type :bridge/called :visible-to :gm
                               :data {:system name :bridge bridge :args bargs
                                      :result (:result r) :error (:error r)}}])
                  (record-bridge-cost! run "system" bridge (:cost r))
                  r))]
      (locking (:world run)
        (emit! run (tick @(:world run) res))))))

(defn- gm-reasons [run world]
  (let [gm (w/gm world)
        every (:gm-check-in-every (:opts run))]
    (when (and gm (= :active (:status gm)) (not= :off (:controller gm)))
      (seq (concat
            (when (seq (get-in world [:gm :inbox])) ["participants sent you requests"])
            (when (and (pos? every) (>= (- (:round world) (get-in world [:gm :last-intervention])) every))
              ["scheduled check-in"])
            (keep #(% world) (:gm-triggers (:scenario run))))))))

(defn- maybe-run-gm! [run]
  (when-let [reasons (gm-reasons run @(:world run))]
    (let [gm-id (:id (w/gm @(:world run)))]
      (emit! run [{:type :gm/called :actor gm-id :visible-to :gm :data {:reasons reasons}}])
      (activate run @(:world run) gm-id
                {:role :gm :reasons reasons :max-finals (:gm-max-actions (:opts run))
                 :commit (fn [action args] (resolve! run gm-id action args))})
      (emit! run [{:type :gm/intervened :actor gm-id :visible-to :gm :data {:reasons reasons}}]))))

(defn- pause-reasons [run]
  (let [world @(:world run)
        {:keys [max-cost max-rounds]} (:opts run)
        pause-when (:pause-when (:scenario run))]
    (seq (concat (:pause-requests world)
                 (when (and max-cost (>= (w/total-cost world) max-cost))
                   [(format "cost limit $%.2f reached" (double max-cost))])
                 (when (and max-rounds (>= (:round world) max-rounds)) ["max rounds reached"])
                 (when-let [r (and pause-when (pause-when world))] [r])
                 (when (empty? (w/active-agents world)) ["no active participants"])))))

(defn run-round!
  "Run one full round on the calling thread. Returns pause reasons, if any."
  [run]
  (let [round (inc (:round @(:world run)))]
    (emit! run [{:type :round/started :data {:round round} :visible-to :all}])
    (locking (:world run) (emit! run (space/arrivals @(:world run))))
    (run-approved-bridges! run)
    (run-systems! run)
    (doseq [group (schedule run @(:world run))]
      (run-group! run group))
    (maybe-run-gm! run)
    (emit! run [{:type :round/ended :data {:round round} :visible-to :all}])
    (pause-reasons run)))

;; ---------------------------------------------------------------------------
;; Run control

(defn- submit! ^Future [run f]
  (.submit ^ExecutorService (:exec run)
           ^Callable (fn []
                       (try (f)
                            (catch Throwable t
                              (set-status! run assoc :state :idle :mode :manual :error (ex-message t))
                              (throw t))))))

(defn- do-round! [run]
  (set-status! run assoc :state :running :error nil)
  (let [reasons (run-round! run)
        {:keys [mode remaining]} @(:status run)]
    (set-status! run assoc :state :idle)
    (cond
      (and reasons (or (= mode :auto) (seq (:pause-requests @(:world run)))))
      (do (emit! run [{:type :run/paused :visible-to :gm :data {:reasons (vec reasons)}}])
          (set-status! run assoc :mode :manual :remaining nil :pause-reasons (vec reasons)))

      (= mode :auto)
      (let [remaining (some-> remaining dec)]
        (if (and remaining (<= remaining 0))
          (set-status! run assoc :mode :manual :remaining nil)
          (do (set-status! run assoc :remaining remaining)
              (future
                (Thread/sleep (long (:auto-delay-ms (:opts run))))
                (when (= :auto (:mode @(:status run)))
                  (submit! run #(do-round! run))))))))))

(defn step!
  "Run one round (queued behind anything already running)."
  [run]
  (submit! run #(do-round! run)))

(defn auto!
  "Run rounds continuously until paused, a pause condition fires, or `n`
  rounds have run."
  ([run] (auto! run nil))
  ([run n]
   (when-not (= :auto (:mode @(:status run)))
     (set-status! run assoc :mode :auto :remaining n :pause-reasons nil)
     (submit! run #(do-round! run)))))

(defn pause!
  "Stop after the current round."
  [run]
  (set-status! run assoc :mode :manual :remaining nil))

(defn idle? [run]
  (let [{:keys [state mode]} @(:status run)]
    (and (= state :idle) (= mode :manual))))

(defn wait-idle
  "Block until the run is idle and not in auto mode, or a human seat is
  waiting for input (for the REPL and tests). Returns :idle,
  :awaiting-human or :timeout."
  ([run] (wait-idle run 600000))
  ([run timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (Thread/sleep 50)
       (cond
         (= :awaiting-human (:state @(:status run))) :awaiting-human
         (let [^java.util.concurrent.ThreadPoolExecutor ex (:exec run)]
           (and (idle? run) (.isEmpty (.getQueue ex)) (zero? (.getActiveCount ex)))) :idle
         (> (System/currentTimeMillis) deadline) :timeout
         :else (recur))))))

;; ---------------------------------------------------------------------------
;; Operator commands (applied between rounds)

(defn- operator! [run events-fn]
  (submit! run #(locking (:world run)
                  (let [evs (events-fn @(:world run))]
                    (emit! run (mapv (fn [e] (cond-> e (not (:actor e)) (assoc :actor "operator"))) evs))
                    evs))))

(defn spawn!
  "Add an agent. spec: {:role :farmer :name \"Eva\" :persona \"...\" :model \"...\" :controller :human}"
  [run spec]
  (operator! run #(b/spawn-events (:scenario run) % spec)))

(defn retire! [run id reason]
  (operator! run (fn [_] [{:type :agent/retired :data {:id id :reason reason} :visible-to :all}])))

(defn update-agent!
  "Change an agent's settings, e.g. {:model \"...\"} or {:controller :human}."
  [run id changes]
  (operator! run (fn [_] [{:type :agent/updated :data {:id id :changes changes} :visible-to :gm}])))

(defn place!
  "Put an agent at a location directly (also ends any journey)."
  [run id loc-id]
  (operator! run (fn [world]
                   (when (space/location world loc-id)
                     [{:type :agent/placed :data {:id id :to loc-id}
                       :visible-to (conj (space/present world loc-id) id)}]))))

(defn announce!
  "Operator announcement to everyone, or to a set of agent ids."
  ([run text] (announce! run text :all))
  ([run text to]
   (operator! run (fn [_] [{:type :gm/announced :data {:text text} :visible-to to}]))))

(defn approve! [run req-id]
  (operator! run (fn [world]
                   (when (get-in world [:pending-bridges req-id])
                     [{:type :bridge/approved :data {:req-id req-id} :visible-to :gm}]))))

(defn deny! [run req-id]
  (operator! run (fn [world]
                   (when-let [{:keys [requester]} (get-in world [:pending-bridges req-id])]
                     [{:type :bridge/denied :data {:req-id req-id}
                       :visible-to (if requester #{requester} :gm)}]))))

(defn act!
  "Give input for a human-held seat that is waiting.
  `args` is a map (or a JSON string)."
  [run agent-id action args]
  (let [{:keys [agent promise]} @(:input run)]
    (if (= agent agent-id)
      (do (deliver promise {:action (name action) :args (or args {})}) :sent)
      {:error (str "not waiting for " agent-id)})))

;; ---------------------------------------------------------------------------
;; Creating, loading and branching runs

(defn- init! [run]
  (let [{:keys [scenario opts]} run
        {:keys [state agents] :as init} ((:init scenario) opts)]
    (emit! run [{:type :world/initialized :visible-to :gm
                 :data {:scenario (:name scenario) :seed (:seed opts) :state state}}])
    (emit! run (space/init-events (:space init)))
    (doseq [spec (cons {:name "GM" :role :gm :model (:gm-model opts) :controller (:gm opts)} agents)]
      (emit! run (b/spawn-events scenario @(:world run) (into {} (remove (comp nil? val)) spec))))))

(defn start-run
  "Create and initialise a new run of `scenario-key` (e.g. :village)."
  ([scenario-key] (start-run scenario-key {}))
  ([scenario-key opts]
   (let [scenario (load-scenario scenario-key)
         opts (merge default-opts (:defaults scenario) opts)
         id (or (:run-id opts) (store/new-run-id scenario-key))
         dir (store/run-dir id (:test? opts))
         _ (when (.exists dir)
             (throw (ex-info (str "run directory already exists: " dir) {:dir (str dir)})))
         run (make-run id dir scenario opts)]
     (store/write-config! dir {:scenario scenario-key :opts (apply dissoc opts :run-id non-data-opts)})
     (init! run)
     run)))

(defn load-run
  "Load a run from disk by replaying its events. `opts` override the saved
  ones; pass {:test? true} to load from test-runs/."
  ([id] (load-run id {}))
  ([id opts]
   (let [dir (store/run-dir id (:test? opts))
         {:keys [scenario] saved :opts} (store/read-config dir)
         sc (load-scenario scenario)
         run (make-run id dir sc (merge default-opts (:defaults sc) saved opts))
         evs (store/read-events dir)]
     (reset! (:events run) evs)
     (reset! (:world run) (w/replay evs))
     run)))

(defn branch-run
  "Copy `run` up to the end of `round` into a new run (next to the original,
  so test runs stay under test-runs/) and load it."
  ([run round] (branch-run run round {}))
  ([run round opts]
   (let [evs @(:events run)
         end (some (fn [[i e]] (when (and (= :round/ended (:type e)) (= round (get-in e [:data :round]))) i))
                   (map-indexed vector evs))
         kept (if end (subvec evs 0 (inc end)) (throw (ex-info (str "round " round " has not ended") {})))
         id (str (:id run) "-r" round "-" (System/currentTimeMillis))
         test? (:test? (:opts run))
         dir (store/run-dir id test?)]
     (store/write-config! dir (store/read-config (:dir run)))
     (doseq [e kept] (store/append-event! dir e))
     (load-run id (assoc opts :test? test?)))))

(defn stop-run
  "Stop after the current round, then close the run's MCP servers."
  [run]
  (pause! run)
  (submit! run #(mcp/close-all! (:mcp run)))
  (.shutdown ^ExecutorService (:exec run)))
