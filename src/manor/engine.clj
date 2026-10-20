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
            [clojure.set :as set]
            [manor.actions :as a]
            [manor.bridge :as bridge]
            [manor.builtins :as b]
            [manor.clock :as clock]
            [manor.conversation :as conversation]
            [manor.items :as items]
            [manor.llm :as llm]
            [manor.mcp :as mcp]
            [manor.narrate :as narrate]
            [manor.positions :as positions]
            [manor.prompt :as prompt]
            [manor.space :as space]
            [manor.store :as store]
            [manor.transcript :as transcript]
            [manor.world :as w]
            [manor.worldfile :as worldfile])
  (:import (java.time Instant)
           (java.util.concurrent ExecutorService Future TimeUnit)))

(def default-opts
  {:seed 1
   :llm :openrouter                 ; :openrouter or :mock
   :model "deepseek/deepseek-v4.1-flash"
   :gm-model nil                    ; defaults to :model
   :judge-model nil                 ; narrow adjudicators (work and scenario queries); defaults to :model
   :gm :llm                         ; who fills the GM seat: :llm, :human or :off
   :reasoning {:effort "low"}
   :tool-choice "auto"              ; or "required"; DeepSeek's own endpoint rejects "required" with reasoning on
   :max-tokens 16000                 ; includes reasoning tokens
   :max-steps 10                    ; model calls per agent turn (a cost guard, not game time)
   :max-queries 2                   ; bridge/adjudication queries per agent turn
   :gm-max-actions 4
   :event-window 30                 ; recent events shown to each agent
   :gm-check-in-every 0             ; 0 = only when triggered
   :max-cost 5.0                    ; USD; pauses the run when reached
   :max-rounds nil
   :start nil                       ; e.g. "1904-10-15T21:00": start the scenario's clock then
   :auto-delay-ms 0
   :offline? false                  ; use scenario :offline-stubs for bridges
   :addressed-as? true              ; positions' forms of address in prompts (manor.positions)
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
            (a/merge-actions (b/agent-actions) [conversation/converse] (space/actions)
                             (when (:items scenario) (items/actions (:items scenario)))
                             (b/gm-actions scenario) (:actions scenario)))))

;; ---------------------------------------------------------------------------
;; Run plumbing

(defn- conversation-mock
  "`policy`, with speaking turns (ctx :conversation) sent to the scenario's
  :mock-conversation, or else manor.conversation/mock-default."
  [scenario policy]
  (let [converse (:mock-conversation scenario (constantly nil))]
    (fn [ctx]
      (if (:conversation ctx)
        (or (converse ctx) (conversation/mock-default ctx))
        (policy ctx)))))

(defn- mock-llm
  "The scenario policy handles characters. Work uses :mock-judge (or the
  default); internal adjudication queries use their registered :mock, never
  a live provider; speaking turns in a conversation use :mock-conversation,
  falling back to manor.conversation/mock-default. Character policies need
  not know about these calls."
  [scenario]
  (let [policy (conversation-mock scenario (or (:mock-policy scenario) (constantly nil)))
        judge (or (:mock-judge scenario) items/mock-judge)]
    (llm/mock (fn [ctx]
                (case (:role ctx)
                  :judge (judge ctx)
                  :steward ((or (:mock-steward scenario) items/mock-steward) ctx)
                  :adjudicator (if-let [mock (get-in scenario [:adjudicators (:task ctx) :mock])]
                                 (mock ctx)
                                 (throw (ex-info "adjudicator needs a mock responder" {:task (:task ctx)})))
                  (policy ctx))))))

(defn- make-llm [scenario opts]
  (or (:llm-impl opts)
      (case (:llm opts)
        :mock (mock-llm scenario)
        :openrouter (llm/openrouter))))

(defn- make-run [id dir scenario opts]
  (let [scenario (cond-> scenario (:items scenario) (update :adjudicators #(merge items/adjudicators %)))
        opts (cond-> opts
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
    (when (some #(#{"space" "item" "items"} (namespace (:type %))) events)
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
  Returns {:ok true :world world-after} or {:rejected reason}. With
  `keep-occupation?` (a speaking turn in a conversation) an interrupted
  occupation goes on: the agent still decides on their own turn."
  ([run actor action args] (resolve! run actor action args false))
  ([run actor action args keep-occupation?]
   (locking (:world run)
     (let [world @(:world run)]
       (if-let [reason (a/check action world actor args)]
         {:rejected reason}
         (do (when-not keep-occupation? (stop-interrupted! run actor action))
             (let [events (action-events action @(:world run) actor args)]
               (emit! run events)
               (when (some #(= :agent/moved (:type %)) events)
                 (when-let [hibernate (:hibernate (:scenario run))]
                   (emit! run (b/wakings hibernate @(:world run))))))
             {:ok true :world @(:world run)}))))))

(defn- reply-text [data]
  (if (string? data) data (prompt/->json data)))

(defn- free-reply
  "The tool result for an accepted free action: its :reply (a string as is,
  anything else as JSON), computed from the world after its effect, or \"ok\"."
  [action world actor args]
  (if-let [f (:reply action)]
    (reply-text (f world actor args))
    "ok"))

(declare ask-structured!)

(defn- query-services
  "Engine-owned I/O for a composed query. Raw intermediate records are operator-only;
  only the query's checked projection is returned to its caller."
  [run actor]
  (let [budget! #(when-let [cap (get-in run [:opts :max-cost])]
                  (when (>= (w/total-cost @(:world run)) cap)
                    (throw (ex-info "The run's cost limit has been reached." {}))))]
    {:bridge (fn [bridge-name args]
               (budget!)
               (let [{:keys [result error cost]} (bridge/call (:scenario run) (:opts run) (bridge-ctx run) bridge-name args)]
                 (emit! run [{:type :bridge/called :actor actor :visible-to :system
                              :data {:bridge bridge-name :args args :result result :error error}}])
                 (record-bridge-cost! run actor bridge-name cost)
                 (when error (throw (ex-info error {})))
                 result))
     :adjudicate (fn [task args]
                   (budget!)
                   (let [spec (get-in run [:scenario :adjudicators task])
                         {:keys [messages tools ctx check result] :as request} ((:request spec) @(:world run) actor args)]
                     (if (contains? request :result)
                       result
                       (let [{:keys [verdict failure]}
                             (ask-structured! run (str "adjudicator/" (subs (str task) 1))
                                              {:messages messages :tools tools :ctx (assoc ctx :role :adjudicator :task task)
                                               :model (get-in run [:opts :model]) :before-call budget!}
                                              (or check (:check spec)))]
                         (when failure (throw (ex-info failure {})))
                         verdict))))}))

(defn- run-query!
  "Run a bridge or internal adjudication query during a turn. Only checked
  adjudication results reach :on-result; technical failures have no effects.
  Speaking-turn queries keep the caller's occupation, even if interrupted."
  ([run actor action args] (run-query! run actor action args false))
  ([run actor action args keep-occupation?]
   (let [world @(:world run)]
     (if-let [reason (a/check action world actor args)]
       {:error reason}
       (let [_ (when-not keep-occupation? (stop-interrupted! run actor action))
             world @(:world run)
             task (:adjudicate action)
             spec (get-in run [:scenario :adjudicators task])
             composed (:query-fn action)
             bargs (when-not (or task composed) ((or (:bridge-args action) (fn [_ _ args] args)) world actor args))
             {:keys [result error cost] :as res}
             (cond
               composed (try {:result (composed world actor args (query-services run actor))}
                             (catch Exception e {:error "The enquiry failed; try again later." :failure (ex-message e)}))
               task
               (let [{:keys [messages tools ctx result check] :as request} ((:request spec) world actor args)]
                 (if (contains? request :result)
                   {:result result}
                   (let [{:keys [verdict failure]}
                         (ask-structured! run (str "adjudicator/" (subs (str task) 1))
                                          {:messages messages :tools tools
                                           :ctx (assoc ctx :role :adjudicator :task task)}
                                          (or check (:check spec)))]
                     (if failure
                       {:error (or (:failure-reply spec) "The enquiry failed; try again later.") :failure failure}
                       {:result verdict}))))
               :else (bridge/call (:scenario run) (:opts run) (bridge-ctx run) (:bridge action) bargs))]
         (locking (:world run)
           (if (or task composed)
             (when error
               (emit! run [{:type :adjudication/failed :visible-to :system
                            :data {:task (or task (:name action)) :caller actor :reason (:failure res)}}]))
             (do (emit! run [{:type :bridge/called :actor actor :visible-to #{actor}
                             :data {:bridge (:bridge action) :args bargs :result result :error error}}])
                 (record-bridge-cost! run actor (:bridge action) cost)))
           (let [before (count @(:events run))]
             (when (and result (:on-result action))
               (emit! run (mapv #(cond-> % (not (:actor %)) (assoc :actor actor))
                                ((:on-result action) @(:world run) actor args result))))
             (cond
               (and error (:failure-reply spec)) (assoc res :reply error)
               (and result (:reply action))
               (assoc res :reply (reply-text ((:reply action) @(:world run) actor args
                                             (assoc result :events (subvec @(:events run) before)))))
               :else res))))))))

;; ---------------------------------------------------------------------------
;; Activations

(defn- record-usage! [run agent-id request response]
  (emit! run [{:type :llm/usage :actor agent-id :visible-to :system
               :data (assoc (:usage response) :agent agent-id :model (:model request))}]))

(defn- complete!
  "Make a model call. The response gets :at, the instant (ISO-8601, UTC) the
  call was sent."
  [run request]
  (let [at (str (Instant/now))]
    (assoc (llm/complete (:llm run) request) :at at)))

(defn- record-call!
  "Log a model call to llm.edn. `replies` are the messages the engine added
  after the response (tool replies and any reminder), so a turn's last call
  has its replies too. The character's private :thoughts, if the model
  returned any, are kept here only; they're never sent back to the model.
  :at is when the call was sent (see `complete!`), :tool-digests fingerprint
  the tools sent (`llm/tool-digests`), and :provider and :generation-id are
  there when the provider gives them. `extra` is merged into the entry,
  e.g. a speaking turn's :conversation."
  ([run agent-id request response replies] (record-call! run agent-id request response replies nil))
  ([run agent-id request response replies extra]
   (let [entry (merge (cond-> {:agent agent-id
                               :round (:round @(:world run))
                               :at (:at response)
                               :model (:model request)
                               :messages (:messages request)
                               :tools (mapv #(get-in % [:function :name]) (:tools request))
                               :tool-digests (llm/tool-digests (:tools request))
                               :response (:message response)
                               :thoughts (:thoughts response)
                               :finish-reason (:finish-reason response)
                               :usage (:usage response)
                               :replies replies}
                        (:provider response) (assoc :provider (:provider response))
                        (:generation-id response) (assoc :generation-id (:generation-id response)))
                      extra)]
     (store/append-llm! (:dir run) entry)
     (swap! (:last-calls run) assoc agent-id entry))))

(defn- conj-reply
  "Add the model's reply to the conversation, unless it has neither content
  nor tool calls (e.g. all reasoning, cut off by `:max-tokens`): providers
  reject such an assistant message (DeepSeek: HTTP 400)."
  [messages {:keys [content tool_calls] :as message}]
  (if (or (not (str/blank? content)) (seq tool_calls))
    (conj messages message)
    messages))

(defn- ask-structured!
  "Shared narrow model-call lifecycle: checked answer, one correction attempt,
  usage/transcript logging and safe failure. The domain owns prompts/checks."
  [run identity {:keys [messages tools ctx label defer-records? model before-call] :or {label "adjudicator"}} check]
  (let [records (atom [])
        record! (fn [f & args]
                  (if defer-records?
                    (swap! records conj #(apply f args))
                    (apply f args)))
        opts (:opts run)
        model (or model (:judge-model opts) (:model opts))]
    (let [result
          (try
            (loop [messages messages, attempt 0]
              (when before-call (before-call))
              (let [request {:model model :messages messages :tools tools
                             :max-tokens (:max-tokens opts) :reasoning (:reasoning opts)
                             :tool-choice (:tool-choice opts)}
                    response (complete! run (assoc request :ctx (assoc ctx :step attempt)))
                    _ (record! record-usage! run identity request response)
                    {:keys [verdict error replies]}
                    (try (check response)
                         (catch Throwable t
                           (record! record-call! run identity request response [])
                           (throw t)))]
                (record! record-call! run identity request response replies)
                (cond verdict {:verdict verdict}
                      (zero? attempt) (recur (into (conj-reply messages (:message response)) replies) 1)
                      :else {:failure (str "a second bad answer: " error)})))
            (catch Throwable t
              {:failure (str "the " label " call failed: " (ex-message t))}))]
      (cond-> result defer-records? (assoc :records @records)))))

(defn- limit-reached
  "Why `action` can't be used again this turn, or nil: its :per-turn uses
  (counted under its limit key, in the turn state's :uses) are spent."
  [by-name st action]
  (let [k (a/limit-key action)
        n (:per-turn action)]
    (when (and n (>= (get-in st [:uses k] 0) n))
      (let [sharing (sort (keep #(when (= k (a/limit-key %)) (name (:name %))) (vals by-name)))]
        (str "you can use " (str/join " or " sharing) " only "
             (if (= 1 n) "once" (str n " times")) " per turn; take your turn-ending action now, and use "
             (if (next sharing) "them" "it") " again next turn")))))

(defn- use-up
  "The turn state after an accepted use of an action with a :per-turn limit."
  [st action]
  (cond-> st (:per-turn action) (update-in [:uses (a/limit-key action)] (fnil inc 0))))

(declare run-conversation!)

(defn- handle-call
  "Process one tool call within a turn. `commit` either applies a turn-ending
  action now (sequential turns, GM) or only checks it and defers it
  (simultaneous groups). Returns the updated turn state."
  [run agent-id by-name {:keys [commit max-finals max-queries keep-occupation?]} st {:keys [id name arguments]}]
  (let [reply (fn [st text] (update st :replies conj {:role "tool" :tool_call_id id :content text}))
        action (get by-name name)]
    (cond
      (:done? st) (reply st "ignored: your turn is already over")
      (nil? action) (reply st (str "error: there is no tool called " name))
      :else
      (let [{:keys [args error]} (a/decode-args action arguments)
            spent (when-not error (limit-reached by-name st action))]
        (cond
          error (reply st (str "error: " error))
          spent (reply st (str "rejected: " spent))
          :else
          (case (a/kind action)
            :free
            (let [{:keys [rejected world]} (resolve! run agent-id action args keep-occupation?)]
              (-> st
                  (update :used conj name)
                  (cond-> (not rejected) (use-up action))
                  (reply (if rejected
                           (str "rejected: " rejected)
                           (free-reply action world agent-id args)))))

            :converse
            (let [{:keys [rejected summary]} (run-conversation! run agent-id action args)]
              (-> st
                  (update :used conj name)
                  (cond-> (not rejected) (use-up action))
                  (reply (if rejected (str "rejected: " rejected) summary))))

            :query
            (if (>= (:queries st) max-queries)
              (reply st (str "error: query limit reached for this turn (" max-queries "); take your action now, and query again next turn"))
              (let [{:keys [result error] :as response} (run-query! run agent-id action args keep-occupation?)]
                (-> st
                    (update :queries inc)
                    (update :used conj name)
                    (cond-> (or (not error) (:reply response)) (use-up action))
                    (reply (or (:reply response) (if error (str "error: " error) (prompt/->json result)))))))

            (:final :act)
            (let [{:keys [rejected]} (commit action args)]
              (if rejected
                (reply st (str "rejected: " rejected))
                (let [st (-> st
                             (use-up action)
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
      (str "Your turn isn't over: free tools, queries and rejected calls don't end it. "
           "When you're ready, finish with one turn-ending action: " (or-list final-names) "." wait))))

(defn- nudge
  "The reply to a response without tool calls. One cut off by `:max-tokens`
  (usually all reasoning) is told to think briefly."
  [response]
  (if (= "length" (:finish-reason response))
    "You ran out of room while thinking, before calling a tool. Think briefly, then call one of the tools."
    "You must respond by calling one of the tools."))

(defn- handle-text-as
  "A response with text and no tool call, taken as a call of `tool` with
  that text (a speaking turn's line). The engine's reply goes back as a
  user message, since the model made no tool call to answer."
  [run agent-id by-name call-opts st tool text]
  (let [st (handle-call run agent-id by-name call-opts (assoc st :replies [])
                        {:id "text" :name tool :arguments {:text text}})]
    (update st :replies (partial mapv (fn [{:keys [content] :as m}]
                                        (if (= "text" (:tool_call_id m))
                                          {:role "user" :content (str "(Your text was taken as `" tool "`.) " content)}
                                          m))))))

(defn- handle-response
  "The turn state after one model response, with :replies (the messages to
  send back). A response without tool calls is told to call one; a second
  ends the turn. With call-opts :text-as (a tool name), its text, if any,
  is taken as a call of that tool instead."
  [run agent-id by-name call-opts st response]
  (let [calls (:tool-calls response)
        text (some-> (:content response) str/trim not-empty)]
    (cond
      (seq calls) (reduce (partial handle-call run agent-id by-name call-opts) (assoc st :replies []) calls)
      (and text (:text-as call-opts)) (handle-text-as run agent-id by-name call-opts st (:text-as call-opts) text)
      (:nudged st) (assoc st :replies [] :done? true)
      :else (assoc st :nudged true
                   :replies [{:role "user" :content (nudge response)}]))))

(defn- add-reminder
  "Append `text` to the replies as a user message, merged into one already
  there."
  [replies text]
  (let [lst (peek replies)]
    (cond (nil? text) replies
          (= "user" (:role lst)) (conj (pop replies) (update lst :content str " " text))
          :else (conj replies {:role "user" :content text}))))

(defn- run-calls
  "The model calls of one turn (or speaking turn), until it's done or
  `max-steps` calls are made. `messages` start the conversation with the
  model, `actions` are its initial tools. Optional `refresh-actions` (fn [])
  supplies the next step's actions from the live world. `remind` (fn
  [turn-state calls-made next-actions]) may add a reminder after a call.
  `ctx` goes to the LLM with each call and `log` into each llm.edn entry.
  Returns the turn state."
  [run agent-id {:keys [model messages actions refresh-actions max-steps call-opts remind ctx log]}]
  (let [{:keys [opts]} run]
    (loop [messages messages, actions actions, step 0, st {:finals [] :queries 0 :used [] :nudged false}]
      (if (or (:done? st) (>= step max-steps))
        st
        (let [by-name (a/by-name actions)
              tools (mapv a/->tool actions)
              request {:model model :messages messages :tools tools
                       :max-tokens (:max-tokens opts) :reasoning (:reasoning opts)
                       :tool-choice (:tool-choice opts)}
              response (complete! run
                                  (assoc request :ctx (merge {:world @(:world run) :agent-id agent-id
                                                              :step step :used (:used st)}
                                                             ctx)))
              _ (record-usage! run agent-id request response)
              st' (try (handle-response run agent-id by-name call-opts st response)
                       (catch Throwable t
                         (record-call! run agent-id request response [] log)
                         (throw t)))
              next-actions (if (and refresh-actions (not (:done? st')))
                             (refresh-actions)
                             actions)
              replies (cond-> (:replies st')
                        (not (:done? st')) (add-reminder (remind st' (inc step) next-actions)))]
          (record-call! run agent-id request response replies log)
          (recur (into (conj-reply messages (:message response)) replies)
                 next-actions (inc step) (dissoc st' :replies)))))))

(defn- turn-actions
  "The actions `agent-id` has on this turn. A conversation isn't offered
  in a simultaneous group, where the others are in their own turns, nor to
  a human seat."
  [run world agent-id {:keys [role simultaneous?]}]
  (cond->> (a/available (a/for-role (:actions run) role (positions/capabilities world agent-id)) world agent-id)
    (or simultaneous? (= :human (:controller (w/agent world agent-id))))
    (remove #(= :converse (a/kind %)))))

(defn- activate-llm [run snapshot agent-id {:keys [role reasons max-finals commit] :as turn}]
  (let [{:keys [scenario opts]} run
        gm? (= role :gm)
        actions (turn-actions run snapshot agent-id turn)
        agent (w/agent snapshot agent-id)
        max-steps (if gm? (+ 2 (:gm-max-actions opts)) (:max-steps opts))]
    (run-calls run agent-id
               {:model (or (:model agent) (when gm? (:gm-model opts)) (:model opts))
                :messages (if gm?
                            (prompt/gm-messages scenario snapshot @(:events run) reasons opts)
                            (prompt/agent-messages scenario snapshot @(:events run) agent-id opts))
                :actions actions
                :refresh-actions #(turn-actions run @(:world run) agent-id turn)
                :max-steps max-steps
                :call-opts {:commit commit :max-finals max-finals :max-queries (:max-queries opts)}
                ;; the GM's turn has no forfeit, so only participants are reminded
                :remind (fn [st calls-made next-actions]
                          (when (and (not gm?) (empty? (:finals st)))
                            (turn-reminder calls-made max-steps
                                           (map (comp name :name) (filter a/ends-turn? next-actions)))))
                :ctx {:role role :reasons reasons}})))

(defn- extra-speaking-actions
  "Scenario tools for this line: only available free/query actions, never
  replacements for the conversation's tools or note."
  [scenario world speaker conv]
  (let [extras (->> ((:speaking-actions scenario (constantly [])) world speaker conv)
                    (filter #(#{:free :query} (a/kind %)))
                    (remove #(#{:reply :stay-silent :leave :end-conversation :note} (:name %))))]
    (vec (a/available extras world speaker))))

(defn- speaking-turn-steps
  "Three calls for a line and retries; one more when a query is offered."
  [query?]
  (if query? 4 3))

(defn- speaking-reminder
  "The reminder before a speaking turn's last call."
  [max-steps st calls-made actions]
  (when (and (empty? (:finals st)) (= calls-made (dec max-steps)))
    (str "This is your last call for this line. Answer now with "
         (or-list (map (comp name :name) (filter a/ends-turn? actions))) ".")))

(defn- speaking-turn!
  "One member's turn to speak in conversation `conv`. `turn` is {:pass
  :final? :in} (see manor.prompt/conversation-messages). Returns :spoke,
  :silent, :left or :ended, or :failed if the call failed (the run is then
  asked to pause). A member who takes no action is silent; an initiator who
  takes none ends it. Nobody's occupation is touched."
  [run conv speaker turn]
  (let [{:keys [scenario opts]} run
        world @(:world run)
        extra-actions (extra-speaking-actions scenario world speaker conv)
        query? (some #(= :query (a/kind %)) extra-actions)
        max-steps (speaking-turn-steps query?)
        actions (into (cond-> (conversation/speaking-actions conv speaker)
                        (action-named run :note) (conj (action-named run :note)))
                      extra-actions)
        initiator? (= speaker (:initiator conv))]
    (set-status! run update :acting conj speaker)
    (try
      (let [st (run-calls run speaker
                          {:model (or (:model (w/agent world speaker)) (:model opts))
                           :messages (prompt/conversation-messages scenario world @(:events run) speaker opts conv
                                                                  (assoc turn :extra-actions extra-actions))
                           :actions actions
                           :max-steps max-steps
                           :call-opts {:commit (fn [action args] (resolve! run speaker action args true))
                                       :max-finals 1 :max-queries (if query? 1 0) :keep-occupation? true
                                       ;; models often write the line as plain text, not as a reply
                                       :text-as "reply"}
                           :remind (partial speaking-reminder max-steps)
                           :ctx {:role (:role (w/agent world speaker))
                                 :conversation (assoc (select-keys conv [:id :initiator :members])
                                                      :pass (:pass turn) :final? (boolean (:final? turn)))}
                           :log {:conversation {:id (:id conv) :pass (if (:final? turn) :last-word (:pass turn))}}})
            outcome (conversation/outcome (some-> (first (:finals st)) :action :name name))]
        (cond
          (and initiator? (= :silent outcome)) :ended
          (and (= :silent outcome) (empty? (:finals st)))
          (do (emit! run [(conversation/silent @(:world run) speaker conv)]) :silent)
          :else outcome))
      (catch Throwable t
        (emit! run [{:type :run/pause-requested :visible-to :system
                     :data {:reason (str "error during " speaker "'s line in " (:id conv) ": " (ex-message t))}}])
        :failed)
      (finally
        (set-status! run update :acting disj speaker)))))

(defn- cost-reached? [run]
  (let [max-cost (:max-cost (:opts run))]
    (boolean (and max-cost (>= (w/total-cost @(:world run)) max-cost)))))

(defn- converse!
  "Run the speaking turns of `conv` after the initiator's opening line, and
  return how it ended (a key of manor.conversation/endings): up to
  `conversation/passes` passes, the initiator first (who opened pass 1
  already), then the initiator's last word. It ends early when the
  initiator ends it, when everyone else has left, when nobody else speaks
  in a pass, at the cost limit, or when a call fails."
  [run {:keys [initiator members] :as conv}]
  (loop [pass 1, queue (vec (rest members)), in members, answered? false]
    (cond
      (cost-reached? run) :cost-limit
      (empty? queue)
      (cond (not answered?) :silence
            (< pass conversation/passes) (recur (inc pass) in in false)
            :else (case (speaking-turn! run conv initiator {:pass pass :final? true :in in})
                    :failed :error
                    :ended :ended
                    :finished))
      :else
      (let [speaker (first queue)
            outcome (speaking-turn! run conv speaker {:pass pass :in in})
            in' (if (= :left outcome) (vec (remove #{speaker} in)) in)]
        (case outcome
          :failed :error
          :ended :ended
          (if (= [initiator] in')
            :everyone-left
            (recur pass (subvec queue 1) in' (or answered? (and (not= speaker initiator) (= :spoke outcome))))))))))

(defn- conversation-summary
  "The initiator's tool reply: visible events during the conversation and its ending."
  [run {:keys [id initiator]} ending]
  (let [names (narrate/names @(:world run))
        lines (->> @(:events run)
                   (drop-while #(not (and (= :conversation/started (:type %))
                                          (= id (get-in % [:data :conversation])))))
                   rest
                   (take-while #(not (and (= :conversation/ended (:type %))
                                          (= id (get-in % [:data :conversation])))))
                   (filter #(w/visible-to? % initiator))
                   (remove #(or (= :llm/usage (:type %)) (= :system (:visible-to %)))))]
    (str "The conversation is over: " (conversation/endings ending) ".\n"
         (str/join "\n" (map #(narrate/describe % names) lines))
         "\nCarry on with your turn.")))

(defn- run-conversation!
  "Start and run a conversation for `actor`'s `converse` call. Returns
  {:rejected reason} or {:summary text}, the tool reply."
  [run actor action {:keys [with text] :as args}]
  (let [world @(:world run)]
    (if-let [reason (a/check action world actor args)]
      {:rejected reason}
      (let [_ (stop-interrupted! run actor action)
            world @(:world run)
            conv {:id (conversation/new-id world) :initiator actor
                  :members (conversation/members world actor with)}]
        (locking (:world run)
          (emit! run [(conversation/started @(:world run) actor conv)])
          (emit! run [(conversation/said @(:world run) actor conv text)]))
        (let [ending (converse! run conv)]
          (emit! run [(conversation/ended @(:world run) conv ending)])
          {:summary (conversation-summary run conv ending)})))))

(defn- activate-human
  "A human holds this seat: wait for `act!` input. Free and query actions
  are applied and the wait continues; a turn-ending action finishes."
  [run agent-id {:keys [role max-finals commit]}]
  (let [by-name (a/by-name (turn-actions run @(:world run) agent-id {:role role}))
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
  "Groups of active agents in spawn order, or the scenario's custom groups.
  Eligibility is checked when each group runs, allowing mid-round wakings."
  [run world]
  (let [ids (mapv :id (w/active-agents world))
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
                 {:role (:role (w/agent snapshot id)) :max-finals 1 :commit commit
                  :simultaneous? simultaneous?}))
        results (if simultaneous?
                  (mapv deref (mapv #(future (activate run snapshot % (turn %))) ids))
                  (mapv #(activate run snapshot % (turn %)) ids))]
    (when simultaneous?
      (doseq [{:keys [agent-id finals]} results
              {:keys [action args]} finals]
        (when-let [reason (:rejected (resolve! run agent-id action args))]
          (emit! run [{:type :action/rejected :actor agent-id :visible-to #{agent-id}
                       :data {:action (name (:name action)) :reason reason}}]))))
    results))

(defn- ask-judge
  "Ask the judge what `job` did to the judged `things` (see
  items/judged-things): {:summary :changes}, or {:failure reason}. A bad
  answer is sent back with the reason and asked once more. Each call is
  recorded in llm.edn as agent \"judge\", and its usage counts towards the
  run's cost."
  [run world job work things]
  (let [work (assoc work :recent (prompt/scene-recent world (events run) job))
        request (assoc (items/judge-request (:scenario run) world job work)
                       :label "judge" :defer-records? true
                       :ctx (assoc (select-keys work [:minutes :stopped-early? :luck])
                                   :role :judge :world world :job job :things things
                                   :focus (items/focus-things world job)))
        {:keys [verdict] :as result} (ask-structured! run "judge" request #(items/judgement % world things))]
    (assoc result :verdict verdict)))

(defn- job-result
  "Prepare a job's ending on a snapshot, with model calls and their records
  deferred from the world. Applying results stays on the round thread."
  [run world {:keys [job outcome minutes] :as work}]
  (cond
    (= :dropped outcome) {:ended :retired}
    (and (zero? minutes) (not (:quick? job))) {:ended :no-time}
    :else (ask-judge run world job work (items/judged-things world job))))

(defn- finish-job!
  "Record and apply a prepared ending in job order. Build new item ids on
  the live world, so independent jobs making identically named things agree
  with sequential judging too."
  [run {:keys [job] :as work} {:keys [ended verdict failure records]}]
  (doseq [record! records] (record!))
  (let [world @(:world run)]
    (emit! run (if ended
                 [(items/work-ended world job ended)]
                 (cond-> [(items/work-done world job work verdict)]
                   failure (conj (items/judge-failed job failure)))))))

(defn- independent-prefix
  "The longest consecutive prefix with disjoint scopes. Never move a job
  past a conflicting predecessor: it must see that predecessor's result."
  [scope xs]
  (loop [batch [] used #{} [x & more] xs]
    (if (and x (empty? (set/intersection used (scope x))))
      (recur (conj batch x) (into used (scope x)) more)
      batch)))

(defn- finish-jobs!
  "Judge independent jobs concurrently on the pre-batch world, then emit
  all records and endings in the original due/id order, outside model calls."
  [run works]
  (loop [works (seq works)]
    (when works
      (let [world @(:world run)
            batch (independent-prefix #(items/job-scope world (:job %)) works)
            results (mapv #(future (job-result run world %)) batch)]
        (doseq [[work result] (map vector batch results)]
          (finish-job! run work @result))
        (recur (seq (drop (count batch) works)))))))

(defn- run-steward!
  "After jobs finish, tidy only idle leftovers and take off what sleepers
  wouldn't keep on overnight, before bridges/systems or characters can
  create new work: one call per place with candidates or sleepers. The
  marker is emitted even with none."
  [run]
  (let [world @(:world run)
        config (get-in run [:scenario :items :steward])]
    (when-let [day (items/steward-day world config)]
      (emit! run [{:type :items/steward-ran :visible-to :gm :data {:day day}}])
      (let [all-candidates (items/steward-candidates world config)
            all-sleepers (items/steward-sleepers world)]
        (doseq [place (sort (distinct (concat (keys all-candidates) (keys all-sleepers))))]
          (let [candidates (get all-candidates place [])
                sleepers (get all-sleepers place [])
                request (assoc (items/steward-request world place candidates sleepers)
                               :label "steward" :ctx {:role :steward :world world :place place
                                                      :candidates candidates :sleepers sleepers})
                {:keys [verdict failure]} (ask-structured! run "steward" request
                                                           #(items/stewardship % world place candidates sleepers))]
            (emit! run (cond-> [(items/stewarded @(:world run) place verdict)]
                         failure (conj {:type :items/steward-failed :visible-to :system
                                        :data {:place place :reason failure}})))))))))

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

(defn- composed!
  "A system's :compose, given the composed-query services: {:result r}, or
  {:error msg} if it threw (recorded for the operator)."
  [run system-name compose]
  (try {:result (compose @(:world run) (query-services run "system"))}
       (catch Exception e
         (emit! run [{:type :adjudication/failed :visible-to :system
                      :data {:task system-name :caller "system" :reason (ex-message e)}}])
         {:error (ex-message e)})))

(defn- run-systems! [run]
  (doseq [{:keys [name bridge bridge-args compose tick]} (:systems (:scenario run))]
    (let [res (cond
                compose (composed! run name compose)
                bridge
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
    (finish-jobs! run (locking (:world run)
                   ;; decided before releases, so a worker whose job is due still holds it
                   (let [ending (items/ending-jobs @(:world run))]
                     (emit! run (space/arrivals @(:world run)))
                     (let [released (b/releases @(:world run))]
                       (emit! run released)
                       (when-let [hibernate (:hibernate (:scenario run))]
                         (emit! run (b/hibernations hibernate @(:world run) (set (map :actor released))))))
                     ending)))
    (run-steward! run)
    (run-approved-bridges! run)
    (run-systems! run)
    (let [acted (reduce (fn [acted group]
                          (into acted (map :agent-id (run-group! run group))))
                        #{} (schedule run @(:world run)))]
      ;; One fixed trailing pass, only for default schedules. A later waking
      ;; during this pass waits until next round.
      (when-not (fn? (:schedule (:scenario run)))
        (let [late (for [{:keys [id travel busy]} (w/active-agents @(:world run))
                         :when (and (not (contains? acted id)) (not travel)
                                    (or (nil? busy) (:interrupted-by busy)))]
                     id)]
          (doseq [id (vec late)]
            (run-group! run [id])))))
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

(defn change-post!
  "Put `id` in the post of `position`, a position of the run's
  [:state :positions]. Their :role becomes `position`, so their brief and
  tools follow it. opts: :household (default: the household of their present
  post) and :terms (free text). They see the change. Throws if there's no
  such participant or position."
  ([run id position] (change-post! run id position {}))
  ([run id position opts]
   (let [world @(:world run)]
     (when-not (and (w/active? world id) (not= :gm (:role (w/agent world id))))
       (throw (ex-info (str "no active participant with id " id) {:id id})))
     (when-not (contains? (get-in world [:state :positions]) position)
       (throw (ex-info (str "no position " position " in this run") {:position position})))
     (operator! run (fn [world]
                      [(positions/post-changed world id position
                                               {:household (if (contains? opts :household)
                                                               (:household opts)
                                                               (:household (positions/post world id)))
                                                :terms (:terms opts)
                                                :visible-to #{id}})])))))

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

(defn- check-agents!
  "Throw if a starting agent's :aspects aren't well formed (see items/Aspects)."
  [agents]
  (when-let [ps (seq (for [{:keys [name aspects] :as a} agents
                           :when (contains? a :aspects)
                           :let [p (items/aspects-problem aspects)]
                           :when p]
                       (str "agent " (pr-str name) ": " p)))]
    (throw (ex-info (str "bad :agents from :init: " (str/join "; " ps)) {:problems (vec ps)}))))

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
    (check-agents! agents)
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
