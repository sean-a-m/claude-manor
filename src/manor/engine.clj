(ns manor.engine
  "The imperative shell around the pure world.

  A run owns: the world (an atom, changed only by `emit!`), the in-memory
  event list, a status map for UIs, listeners, and a single-thread executor.
  Rounds and operator commands are both submitted to that executor, so
  operator commands always land between rounds, never in the middle of one.

  A round:
    1. :round/started
    2. travellers who are due arrive, and occupied agents who are due are
       freed; the scenario's :hibernate puts agents out of play or wakes
       them; work on items that ends now is judged and its results applied
       (manor.items); run act-bridges the operator approved
    3. run world systems (ticks, possibly fed by bridges)
    4. for each schedule group: activate its agents, resolve their actions
    5. call the GM if anything triggers it
    6. :round/ended, then check pause conditions

  Whenever the run stops running rounds (a pause, the end of `auto!`'s
  rounds, a manual step, `pause!`, `stop-run`) it writes
  <run-dir>/transcript.md (see manor.transcript), on the executor so it
  sees whole rounds."
  (:require [clojure.string :as str]
            [manor.actions :as a]
            [manor.bridge :as bridge]
            [manor.builtins :as b]
            [manor.clock :as clock]
            [manor.items :as items]
            [manor.llm :as llm]
            [manor.mcp :as mcp]
            [manor.prompt :as prompt]
            [manor.space :as space]
            [manor.store :as store]
            [manor.transcript :as transcript]
            [manor.world :as w]
            [manor.worldfile :as worldfile])
  (:import (java.util.concurrent ExecutorService Future TimeUnit)))

(def default-opts
  {:seed 1
   :llm :openrouter                 ; :openrouter or :mock
   :model "deepseek/deepseek-v4.1-flash"
   :gm-model nil                    ; defaults to :model
   :judge-model nil                 ; the judge of work on items (manor.items); defaults to :model
   :gm :llm                         ; who fills the GM seat: :llm, :human or :off
   :reasoning {:effort "low"}
   :max-tokens 4000                 ; includes reasoning tokens
   :max-steps 10                    ; model calls per agent turn (a cost guard, not game time)
   :max-queries 2                   ; query-bridge calls per agent turn
   :gm-max-actions 4
   :event-window 30                 ; recent events shown to each agent
   :gm-check-in-every 0             ; 0 = only when triggered
   :max-cost 1.0                    ; USD; pauses the run when reached
   :max-rounds nil
   :start nil                       ; e.g. "1904-10-15T21:00": start the scenario's clock then
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

(defn all-actions
  "Built-in and scenario actions (with the item actions if the scenario has
  :items), minus the scenario's :exclude-actions."
  [scenario]
  (let [excluded (set (:exclude-actions scenario))]
    (remove #(excluded (:name %))
            (a/merge-actions (b/agent-actions) (space/actions) (when (:items scenario) (items/actions))
                             (b/gm-actions scenario) (:actions scenario)))))

;; ---------------------------------------------------------------------------
;; Run plumbing

(defn- mock-llm
  "The mock LLM: the scenario's :mock-policy, except that judge calls (role
  :judge) go to its :mock-judge, or else manor.items/mock-judge, so mock
  policies needn't know about the judge."
  [scenario]
  (let [policy (or (:mock-policy scenario) (constantly nil))
        judge (or (:mock-judge scenario) items/mock-judge)]
    (llm/mock (fn [ctx] (if (= :judge (:role ctx)) (judge ctx) (policy ctx))))))

(defn- make-llm [scenario opts]
  (or (:llm-impl opts)
      (case (:llm opts)
        :mock (mock-llm scenario)
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
   :transcript-at (atom nil)         ; event count when transcript.md was last written
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

(defn- export-map!
  "Runs started from a world file keep their live map (and items) in
  <run-dir>/map.edn, in the same format. Output only: nothing reads it
  during the run."
  [run]
  (when-let [start (store/read-world-start (:dir run))]
    (store/write-text! (:dir run) "map.edn" (worldfile/->edn-string (worldfile/export @(:world run) start)))))

(defn emit!
  "Stamp, apply, persist and broadcast events. The only way the world changes."
  [run events]
  (locking (:world run)
    (doseq [e events]
      (let [e (w/stamp @(:world run) e)]
        (swap! (:world run) w/apply-stamped e)
        (swap! (:events run) conj e)
        (store/append-event! (:dir run) e)
        (notify! run {:kind :event :event e})))
    (when (some #(#{"space" "item"} (namespace (:type %))) events)
      (export-map! run))))

(defn- write-transcript!
  "Write <run-dir>/transcript.md if events were added since it was last
  written, or since the run was started or loaded. Call it on the executor,
  between rounds. Never throws: a failure is printed and the run goes on."
  [run]
  (let [n (count @(:events run))]
    (when-not (= n @(:transcript-at run))
      (try (transcript/write! (:dir run))
           (reset! (:transcript-at run) n)
           (catch Throwable t
             (binding [*out* *err*]
               (println (str "transcript of " (:id run) " not written: " (ex-message t)))))))))

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

(defn- stop-interrupted!
  "An accepted action other than carry-on ends an interrupted occupation first."
  [run actor action]
  (when (not= :carry-on (:name action))
    (when-let [by (:interrupted-by (w/busy @(:world run) actor))]
      (emit! run [(b/free @(:world run) actor by)]))))

(defn- resolve!
  "Check and apply an action against the current world, atomically.
  Returns {:ok true :world world-after} or {:rejected reason}."
  [run actor action args]
  (locking (:world run)
    (let [world @(:world run)]
      (if-let [reason (a/check action world actor args)]
        {:rejected reason}
        (do (stop-interrupted! run actor action)
            (emit! run (action-events action @(:world run) actor args))
            {:ok true :world @(:world run)})))))

(defn- free-reply
  "The tool result for an accepted free action: its :reply (a string as is,
  anything else as JSON), computed from the world after its effect, or \"ok\"."
  [action world actor args]
  (if-let [f (:reply action)]
    (let [data (f world actor args)]
      (if (string? data) data (prompt/->json data)))
    "ok"))

(defn- run-query!
  "Run a query bridge during a turn. The result goes back to the caller and
  is recorded as an event only the caller (and the operator) can see."
  [run actor action args]
  (let [world @(:world run)]
    (if-let [reason (a/check action world actor args)]
      {:error reason}
      (let [_ (stop-interrupted! run actor action)
            world @(:world run)
            bargs ((or (:bridge-args action) (fn [_ _ args] args)) world actor args)
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

(defn- record-usage! [run agent-id request response]
  (emit! run [{:type :llm/usage :actor agent-id :visible-to :system
               :data (assoc (:usage response) :agent agent-id :model (:model request))}]))

(defn- record-call!
  "Log a model call to llm.edn. `replies` are the messages the engine added
  after the response (tool replies and any reminder), so a turn's last call
  has its replies too. The character's private :thoughts, if the model
  returned any, are kept here only; they're never sent back to the model."
  [run agent-id request response replies]
  (let [entry {:agent agent-id
               :round (:round @(:world run))
               :model (:model request)
               :messages (:messages request)
               :tools (mapv #(get-in % [:function :name]) (:tools request))
               :response (:message response)
               :thoughts (:thoughts response)
               :finish-reason (:finish-reason response)
               :usage (:usage response)
               :replies replies}]
    (store/append-llm! (:dir run) entry)
    (swap! (:last-calls run) assoc agent-id entry)))

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
            (let [{:keys [rejected world]} (resolve! run agent-id action args)]
              (-> st
                  (update :used conj name)
                  (reply (if rejected
                           (str "rejected: " rejected)
                           (free-reply action world agent-id args)))))

            :query
            (if (>= (:queries st) max-queries)
              (reply st (str "error: query limit reached for this turn (" max-queries "); take your action now, and query again next turn"))
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

(def ^:private early-reminder-at
  "Calls into a turn, with no turn-ending action yet, after which an agent is
  first reminded to take one. It is reminded again before its last call."
  2)

(defn- or-list [names]
  (let [names (map #(str "`" % "`") names)]
    (if (next names)
      (str (str/join ", " (butlast names)) " or " (last names))
      (first names))))

(defn- turn-reminder
  "A reminder for an agent that has made `calls-made` calls this turn without
  a turn-ending action, or nil: once `early-reminder-at` calls have passed,
  and again before the last call. `final-names` are the turn-ending actions
  it has."
  [calls-made max-steps final-names]
  (let [wait (when (some #{"pass"} final-names)
               " If you're unsure what to do, `pass` is a fine way to wait and see.")]
    (cond
      (>= calls-made max-steps) nil
      (= calls-made (dec max-steps))
      (str "This is your last call this turn. Finish now with one turn-ending action ("
           (or-list final-names) "), or you lose your turn." wait)
      (= calls-made early-reminder-at)
      (str "Your turn isn't over: free tools, queries and rejected calls don't end it, but each uses one of your "
           max-steps " calls this turn (" calls-made " used). When you're ready, finish with one turn-ending action: "
           (or-list final-names) "." wait))))

(defn- handle-response
  "The turn state after one model response, with :replies (the messages to
  send back). A response without tool calls is told to call one; a second
  ends the turn."
  [run agent-id by-name call-opts st response]
  (let [calls (:tool-calls response)]
    (cond
      (seq calls) (reduce (partial handle-call run agent-id by-name call-opts) (assoc st :replies []) calls)
      (:nudged st) (assoc st :replies [] :done? true)
      :else (assoc st :nudged true
                   :replies [{:role "user" :content "You must respond by calling one of the tools."}]))))

(defn- add-reminder
  "Append `text` to the replies as a user message, merged into one already
  there."
  [replies text]
  (let [lst (peek replies)]
    (cond (nil? text) replies
          (= "user" (:role lst)) (conj (pop replies) (update lst :content str " " text))
          :else (conj replies {:role "user" :content text}))))

(defn- activate-llm [run snapshot agent-id {:keys [role reasons max-finals commit]}]
  (let [{:keys [scenario opts]} run
        gm? (= role :gm)
        actions (a/available (a/for-role (:actions run) role) snapshot agent-id)
        by-name (a/by-name actions)
        tools (mapv a/->tool actions)
        final-names (map (comp name :name) (filter a/ends-turn? actions))
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
              _ (record-usage! run agent-id request response)
              st' (try (handle-response run agent-id by-name call-opts st response)
                       (catch Throwable t
                         (record-call! run agent-id request response [])
                         (throw t)))
              ;; the GM's turn has no forfeit, so only participants are reminded
              remind? (and (not gm?) (not (:done? st')) (empty? (:finals st')))
              replies (cond-> (:replies st')
                        remind? (add-reminder (turn-reminder (inc step) max-steps final-names)))]
          (record-call! run agent-id request response replies)
          (recur (into (conj messages (:message response)) replies)
                 (inc step) (dissoc st' :replies)))))))

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
                     :data {:reason "no valid action taken"}}])
        (when (:interrupted-by (w/busy @(:world run) agent-id))
          (emit! run [(b/resume @(:world run) agent-id)])))
      (assoc st :agent-id agent-id))
    (catch Throwable t
      (emit! run [{:type :action/forfeited :actor agent-id :visible-to #{agent-id}
                   :data {:reason "the turn failed"}}
                  {:type :run/pause-requested :visible-to :system
                   :data {:reason (str "error during " agent-id "'s turn: " (ex-message t))}}])
      (when (:interrupted-by (w/busy @(:world run) agent-id))
        (emit! run [(b/resume @(:world run) agent-id)]))
      {:agent-id agent-id :finals []})
    (finally
      (set-status! run update :acting disj agent-id))))

;; ---------------------------------------------------------------------------
;; Rounds

(defn- schedule
  "Groups of agents to act this round. Travellers and occupied agents are skipped."
  [run world]
  (let [ids (mapv :id (remove #(or (:travel %) (and (:busy %) (not (get-in % [:busy :interrupted-by])))) (w/active-agents world)))
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
  (let [ids (filter #(and (w/active? @(:world run) %)
                          (not (space/in-transit? @(:world run) %))
                          (let [busy (w/busy @(:world run) %)]
                            (or (nil? busy) (:interrupted-by busy))))
                    ids)
        snapshot @(:world run)
        simultaneous? (> (count ids) 1)
        turn (fn [id]
               (let [commit (if simultaneous?
                              (fn [action args]
                                (if-let [r (a/check action @(:world run) id args)]
                                  {:rejected r}
                                  (stop-interrupted! run id action)))
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

(defn- ask-judge
  "Ask the judge what `job` did to the items in `reach`: {:summary :changes},
  or {:failure reason}. A bad answer is sent back with the reason and asked
  once more. Each call is recorded in llm.edn as agent \"judge\", and its
  usage counts towards the run's cost."
  [run world job work reach]
  (let [{:keys [scenario opts]} run
        {:keys [messages tools]} (items/judge-request scenario world job work reach)
        model (or (:judge-model opts) (:model opts))]
    (try
      (loop [messages messages, attempt 0]
        (let [request {:model model :messages messages :tools tools
                       :max-tokens (:max-tokens opts) :reasoning (:reasoning opts)}
              response (llm/complete (:llm run)
                                     (assoc request :ctx (assoc (select-keys work [:minutes :stopped-early? :luck])
                                                                :role :judge :step attempt :world world
                                                                :job job :items reach)))
              _ (record-usage! run "judge" request response)
              {:keys [verdict error replies]} (items/judgement response reach)]
          (record-call! run "judge" request response replies)
          (cond verdict verdict
                (zero? attempt) (recur (into (conj messages (:message response)) replies) 1)
                :else {:failure (str "a second bad answer: " error)})))
      (catch Throwable t
        {:failure (str "the judge call failed: " (ex-message t))}))))

(defn- finish-job!
  "Apply the end of one job (see manor.items/ending-jobs): dropped, ended
  with nothing in reach or no time worked, or judged. The model call is made
  outside the world lock; the result is built from the world as it is then,
  so a later job's judge sees this one's result."
  [run {:keys [job outcome minutes] :as work}]
  (let [world @(:world run)
        reach (items/in-reach world job)]
    (cond
      (= :dropped outcome) (emit! run [(items/work-ended world job :retired)])
      (empty? reach) (emit! run [(items/work-ended world job :nothing-in-reach)])
      (zero? minutes) (emit! run [(items/work-ended world job :no-time)])
      :else
      (let [{:keys [failure] :as verdict} (ask-judge run world job work reach)]
        (locking (:world run)
          (emit! run (cond-> [(items/work-done @(:world run) job work (when-not failure verdict))]
                       failure (conj (items/judge-failed job failure)))))))))

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
    (doseq [work (locking (:world run)
                   ;; decided before releases, so a worker whose job is due still holds it
                   (let [ending (items/ending-jobs @(:world run))]
                     (emit! run (space/arrivals @(:world run)))
                     (emit! run (b/releases @(:world run)))
                     (when-let [hibernate (:hibernate (:scenario run))]
                       (emit! run (b/hibernations hibernate @(:world run))))
                     ending))]
      (finish-job! run work))
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
                              (write-transcript! run)
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
                  (submit! run #(do-round! run))))))))
    (when-not (= :auto (:mode @(:status run)))
      (write-transcript! run))))

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
  "Stop after the current round, then write the transcript."
  [run]
  (set-status! run assoc :mode :manual :remaining nil)
  (when-not (.isShutdown ^ExecutorService (:exec run))
    (submit! run #(write-transcript! run))))

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

(defn- with-start
  "The scenario's `clock` with its :start replaced by the run option `start`,
  if given. Throws if the scenario has no clock or `start` is not a time."
  [scenario clock start]
  (cond
    (nil? start) clock
    (nil? clock) (throw (ex-info (str "the :start option needs a scenario with a clock; "
                                      (name (:name scenario)) " has none")
                                 {:start start}))
    :else (let [c (assoc clock :start start)]
            (when-let [ps (clock/problems c)]
              (throw (ex-info (str "bad :start option: " (str/join "; " ps)) {:start start})))
            c)))

(defn- check-items!
  "Throw if the starting items don't fit the world: ids, fields, places and holders."
  [world items]
  (when-let [ps (seq (items/problems items {:places (set (keys (get-in world [:space :locations])))
                                            :holders (set (keys (:agents world)))}))]
    (throw (ex-info (str "bad :items from :init: " (str/join "; " ps)) {:problems (vec ps)}))))

(defn- init!
  "Build the starting world from the scenario's :init. A :world-file in the
  result (see manor.worldfile) is saved as <run-dir>/world-start.edn. The
  run option :start overrides the clock's start time. If the scenario has
  :items, the starting items are added after the agents."
  [run]
  (let [{:keys [scenario opts]} run
        {:keys [state agents clock world-file items] :as init} ((:init scenario) opts)
        _ (when-let [ps (and clock (clock/problems clock))]
            (throw (ex-info (str "bad clock from :init: " (str/join "; " ps)) {:clock clock})))
        clock (with-start scenario clock (:start opts))]
    (when world-file
      (store/write-text! (:dir run) "world-start.edn" (worldfile/->edn-string world-file)))
    (emit! run [{:type :world/initialized :visible-to :gm
                 :data (cond-> {:scenario (:name scenario) :seed (:seed opts) :state state}
                         clock (assoc :clock clock))}])
    (emit! run (space/init-events (:space init)))
    (doseq [spec (cons {:name "GM" :role :gm :model (:gm-model opts) :controller (:gm opts)} agents)]
      (emit! run (b/spawn-events scenario @(:world run) (into {} (remove (comp nil? val)) spec))))
    (when (:items scenario)
      (check-items! @(:world run) items)
      (emit! run (items/init-events items)))))

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
     (reset! (:transcript-at run) (count @(:events run)))
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
     (reset! (:transcript-at run) (count evs))
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
     (when-let [start (store/read-world-start (:dir run))]
       (store/write-text! dir "world-start.edn" (worldfile/->edn-string start)))
     (doseq [e kept] (store/append-event! dir e))
     (load-run id (assoc opts :test? test?)))))

(defn stop-run
  "Stop after the current round, write the transcript, then close the run's
  MCP servers."
  [run]
  (pause! run)
  (submit! run #(mcp/close-all! (:mcp run)))
  (.shutdown ^ExecutorService (:exec run)))
