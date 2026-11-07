(ns manor.engine
  "The imperative shell around the pure world.

  A run owns: the world (an atom, changed only by `emit!`), the in-memory
  event list, a status map for UIs, listeners, and a single-thread executor.
  Rounds and operator commands are both submitted to that executor, so
  operator commands always land between rounds, never in the middle of one.

  A round:
    1. :round/started
    2. travellers who are due arrive, occupied agents who are due are
       freed, and the knocked out who are due come to (manor.mechanics.physical);
       the scenario's :hibernate puts agents out of play or wakes
       them; work on items that ends now is judged and its results applied
       (manor.mechanics.items); run act-bridges the operator approved
    3. run world systems (ticks, possibly fed by bridges), then batched calendar GM review
    4. for each schedule group: activate its agents, resolve their actions
    5. call the GM if anything triggers it
    6. :round/ended, then check pause conditions

  Whenever the run stops running rounds (a pause, the end of `auto!`'s
  rounds, a manual step, `pause!`, `stop-run`) it writes
  <run-dir>/transcript.md (see manor.tools.transcript), on the executor so it
  sees whole rounds.

  This namespace is the engine's interface: making, loading and branching
  runs, run control and operator commands. Its parts are in manor.engine.*:
  run (state and `emit!`), models (making and recording calls), judges
  (checked narrow calls), resolve (applying actions and queries), turn (a
  turn's model calls), talk (conversations), seat (one seat's turn), work
  (jobs and the steward) and round."
  (:require [clojure.string :as str]
            [manor.actions :as a]
            [manor.clock :as clock]
            [manor.engine.round :as round]
            [manor.engine.run :as run]
            [manor.io.llm :as llm]
            [manor.io.mcp :as mcp]
            [manor.io.pi-llm :as pi-llm]
            [manor.io.store :as store]
            [manor.io.worldfile :as worldfile]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.calendar :as calendar]
            [manor.mechanics.conversation :as conversation]
            [manor.mechanics.conversation.judge :as conversation-judge]
            [manor.mechanics.goals :as goals]
            [manor.mechanics.inner :as inner]
            [manor.mechanics.relations :as relations]
            [manor.mechanics.items :as items]
            [manor.mechanics.items.actions :as item-actions]
            [manor.mechanics.items.activity-check :as activity-check]
            [manor.mechanics.items.find :as item-find]
            [manor.mechanics.items.judge :as item-judge]
            [manor.mechanics.items.made-review :as made-review]
            [manor.mechanics.items.steward :as item-steward]
            [manor.mechanics.physical :as physical]
            [manor.mechanics.physical.changes :as physical-changes]
            [manor.mechanics.positions :as positions]
            [manor.mechanics.space :as space]
            [manor.mechanics.visits :as visits]
            [manor.world :as w])
  (:import (java.util.concurrent ExecutorService Future TimeUnit)))

(def default-opts
  {:seed 1
   :llm :openrouter                 ; :openrouter, :pi (through pi's model layer: manor.io.pi-llm) or :mock
   :model "deepseek/deepseek-v4.1-flash"
   :gm-model nil                    ; defaults to :model
   :judge-model nil                 ; narrow adjudicators (work and scenario queries); defaults to :model
   :gm :llm                         ; who fills the GM seat: :llm, :human or :off
   :reasoning {:effort "none"}
   :tool-choice "auto"              ; or "required"; DeepSeek's own endpoint rejects "required" with reasoning on
   :max-tokens 32000                 ; includes reasoning tokens
   :max-steps 20                    ; model calls per agent turn (a cost guard, not game time)
   :max-queries 5                   ; bridge/adjudication queries per agent turn
   :gm-max-actions 4
   :event-window 90                 ; recent events shown to each agent
   :gm-check-in-every 0             ; 0 = only when triggered
   :goals-every 8                  ; rounds between asking each agent to review their goals; 0 = never
   :max-cost 5.0                    ; USD; pauses the run when reached
   :max-rounds nil
   :start nil                       ; e.g. "1904-10-15T21:00": start the scenario's clock then
   :auto-delay-ms 0
   :offline? false                  ; use scenario :offline-stubs for bridges
   :addressed-as? true              ; positions' forms of address in prompts (manor.mechanics.positions)
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
            (a/merge-actions (b/agent-actions) (goals/actions) [conversation/converse] (space/actions)
                             (when (:items scenario) (item-actions/actions (:items scenario)))
                             (b/gm-actions scenario) (when (:calendar scenario) (calendar/actions)) (:actions scenario)))))

;; ---------------------------------------------------------------------------
;; Making a run

(defn- conversation-mock
  "`policy`, with speaking turns (ctx :conversation) sent to the scenario's
  :mock-conversation, or else manor.mechanics.conversation/mock-default."
  [scenario policy]
  (let [converse (:mock-conversation scenario (constantly nil))]
    (fn [ctx]
      (if (:conversation ctx)
        (or (converse ctx) (conversation/mock-default ctx))
        (policy ctx)))))

(defn- mock-llm
  "The scenario policy handles characters. Work uses :mock-judge (or the
  default), and the conversation judge :mock-conversation-judge (or
  manor.mechanics.conversation.judge/mock-judge); internal adjudication
  queries use their registered :mock, never a live provider; speaking turns
  in a conversation use :mock-conversation, falling back to
  manor.mechanics.conversation/mock-default. Character policies need not
  know about these calls."
  [scenario]
  (let [policy (conversation-mock scenario (or (:mock-policy scenario) (constantly nil)))
        judge (or (:mock-judge scenario) item-judge/mock-judge)]
    (llm/mock (fn [ctx]
                (case (:role ctx)
                  :judge (judge ctx)
                  :made-review (made-review/mock-review ctx)
                  :steward ((or (:mock-steward scenario) item-steward/mock-steward) ctx)
                  :conversation-judge ((or (:mock-conversation-judge scenario) conversation-judge/mock-judge) ctx)
                  :adjudicator (if-let [mock (get-in scenario [:adjudicators (:task ctx) :mock])]
                                 (mock ctx)
                                 (throw (ex-info "adjudicator needs a mock responder" {:task (:task ctx)})))
                  (policy ctx))))))

(defn- make-llm [scenario opts ctx]
  (or (:llm-impl opts)
      (case (:llm opts)
        :mock (mock-llm scenario)
        :openrouter (llm/openrouter)
        :pi (pi-llm/pi-llm ctx))))

(defn- make-run [id dir scenario opts]
  (let [scenario (cond-> scenario (:items scenario) (update :adjudicators #(merge (item-find/adjudicators (:items scenario)) activity-check/adjudicators %)))
        opts (cond-> opts
               (:offline? opts) (update :bridge-stubs #(merge (:offline-stubs scenario) %)))]
    {:id id
     :dir dir
     :scenario scenario
     :opts opts
     :actions (all-actions scenario)
     :llm (make-llm scenario opts {:run-id id :run-dir dir})
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

;; ---------------------------------------------------------------------------
;; A run's world, events and status (manor.engine.run)

(defn world [run] (run/world run))
(defn events [run] (run/events run))
(defn status [run] (run/status run))

(defn listen! [run k f] (run/listen! run k f))
(defn unlisten! [run k] (run/unlisten! run k))

(defn emit!
  "Stamp, apply, persist and broadcast events. The only way the world changes."
  [run events]
  (run/emit! run events))

;; ---------------------------------------------------------------------------
;; Run control

(defn- submit! ^Future [run f]
  (.submit ^ExecutorService (:exec run)
           ^Callable (fn []
                       (try (f)
                            (catch Throwable t
                              (run/set-status! run assoc :state :idle :mode :manual :error (ex-message t))
                              (run/write-transcript! run)
                              (throw t))))))

(defn- do-round! [run]
  (run/set-status! run assoc :state :running :error nil)
  (let [reasons (round/run-round! run)
        {:keys [mode remaining]} @(:status run)]
    (run/set-status! run assoc :state :idle)
    (cond
      (and reasons (or (= mode :auto) (seq (:pause-requests @(:world run)))))
      (do (run/emit! run [{:type :run/paused :visible-to :gm :data {:reasons (vec reasons)}}])
          (run/set-status! run assoc :mode :manual :remaining nil :pause-reasons (vec reasons)))

      (= mode :auto)
      (let [remaining (some-> remaining dec)]
        (if (and remaining (<= remaining 0))
          (run/set-status! run assoc :mode :manual :remaining nil)
          (do (run/set-status! run assoc :remaining remaining)
              (future
                (Thread/sleep (long (:auto-delay-ms (:opts run))))
                (when (= :auto (:mode @(:status run)))
                  (submit! run #(do-round! run))))))))
    (when-not (= :auto (:mode @(:status run)))
      (run/write-transcript! run))))

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
     (run/set-status! run assoc :mode :auto :remaining n :pause-reasons nil)
     (submit! run #(do-round! run)))))

(defn pause!
  "Stop after the current round, then write the transcript."
  [run]
  (run/set-status! run assoc :mode :manual :remaining nil)
  (when-not (.isShutdown ^ExecutorService (:exec run))
    (submit! run #(run/write-transcript! run))))

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
                    (run/emit! run (mapv (fn [e] (cond-> e (not (:actor e)) (assoc :actor "operator"))) evs))
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

(defn- affliction
  "The operator's `afflict!` change as manor.mechanics.physical.changes takes it."
  [{:keys [faculty impediment value died] :as change}]
  (cond faculty {:physical [(select-keys change [:faculty :level :cause :minutes])]}
        impediment {:impediments [{:key impediment :value (or value "")}]}
        died {:died died}
        :else (throw (ex-info "afflict! takes :faculty, :impediment or :died" {:change change}))))

(defn afflict!
  "Change `id`'s physical status (manor.mechanics.physical), with one of:
    {:faculty :movement :level :disabled :cause \"bound hand and foot\"}  ; :level :normal clears it
    {:faculty :consciousness :level :disabled :minutes 60}              ; comes to in an hour
    {:impediment :hand :value \"a cut finger\"}                          ; \"\" (or no value) removes it
    {:died \"struck on the head\"}                                       ; or true, with no cause
  Everyone present who can see sees it. If they're busy, they're interrupted
  by :by (default: themselves), unless it knocks them out or kills them.
  Throws if they aren't a living participant or the change isn't allowed."
  [run id change]
  (let [c (affliction change)]
    (when-let [p (physical-changes/problem @(:world run) id c)]
      (throw (ex-info (str "afflict!: " p) {:id id :change change})))
    (operator! run #(physical-changes/change-events % id c (or (:by change) id)))))

(defn announce!
  "Operator announcement to everyone, or to a set of agent ids."
  ([run text] (announce! run text :all))
  ([run text to]
   (operator! run (fn [_] [{:type :gm/announced :data {:text text} :visible-to to}]))))

(defn add-calendar-entry!
  "Record an occasion (see manor.mechanics.calendar/Entry). IDs stay reserved."
  [run entry]
  (operator! run #(calendar/command-events % :add entry)))

(defn update-calendar-entry!
  "Edit an entry's fields. Setting :date replaces :at, and vice versa."
  [run id changes]
  (operator! run #(calendar/command-events % :update (assoc changes :id id))))

(defn cancel-calendar-entry! [run id]
  (operator! run #(calendar/command-events % :cancel {:id id})))

(defn schedule-visit!
  "Arrange a visit by someone in the world (see manor.mechanics.visits):
  {:who \"mrs-bell\" :date \"1904-10-16\" :time \"15:00\" :stay \"to tea\"
   :reason \"...\" :bedroom \"...\" :known-to [\"lord-claudeworth\"]}. The guest is
  told at once in their calendar; :known-to (default nobody) get the host's
  entry. The time must be at least the next round's. Throws if it can't be arranged."
  [run spec]
  (operator! run #(visits/operator-events % spec)))

(defn cancel-visit!
  "Cancel a visit not yet begun, with its calendar entries."
  [run id]
  (operator! run #(visits/operator-cancel-events % id)))

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
  "Throw if a starting agent's :aspects or :impediments aren't well formed
  (see items/Aspects and items/Impediments), its :physical either (see
  physical/Starting), its :inner doesn't fit the scenario's (see inner/problem),
  or its :relations aren't well formed (see relations/shape-problem; whom they
  name is checked once the cast is spawned, by `check-relations!`). A
  scenario's :relations need its :inner: the same judges change both."
  [scenario agents]
  (when (and (:relations scenario) (not (:inner scenario)))
    (throw (ex-info "the scenario has :relations without :inner" {})))
  (when-let [ps (seq (for [{:keys [name aspects inner] :as a} agents
                           p [(when (contains? a :aspects) (items/aspects-problem aspects))
                              (when (contains? a :impediments) (items/impediments-problem (:impediments a)))
                              (when (contains? a :physical) (physical/shape-problem (:physical a)))
                              (when (contains? a :inner) (inner/problem (:inner scenario) inner))
                              (when (contains? a :relations)
                                (if (:relations scenario)
                                  (relations/shape-problem (:relations a))
                                  "this scenario has no relations"))]
                           :when p]
                       (str "agent " (pr-str name) ": " p)))]
    (throw (ex-info (str "bad :agents from :init: " (str/join "; " ps)) {:problems (vec ps)}))))

(defn- check-relations!
  "Throw if a starting agent's :relations name anyone but other people of
  the spawned cast, or hold too many entries (see relations/problem).
  `holders` are [[name id relations] ...]."
  [scenario world holders]
  (when-let [ps (seq (for [[name id rs] holders
                           :let [p (relations/problem (:relations scenario) world id rs)]
                           :when p]
                       (str "agent " (pr-str name) ": " p)))]
    (throw (ex-info (str "bad :agents from :init: " (str/join "; " ps)) {:problems (vec ps)}))))

(defn- spawn-at-start
  "A starting agent's events. One marked :away? (see manor.io.worldfile) is
  spawned with everything, then away from the start, all of it :system so
  nobody sees them go."
  [scenario world spec]
  (let [spawned (b/spawn-events scenario world spec)]
    (if (and (:away? spec) (not= :dead (:status spec)))
      (let [id (get-in (first spawned) [:data :agent :id])]
        (conj (mapv #(assoc % :visible-to :system) spawned)
              {:type :people/departed :visible-to :system
               :data {:id id :reason "away at the start" :towards (or (:lives spec) "elsewhere")}}))
      spawned)))

(defn- init!
  "Build the starting world from the scenario's :init. A :world-file in the
  result (see manor.io.worldfile) is saved as <run-dir>/world-start.edn. The
  run option :start overrides the clock's start time. If the scenario has
  :items, the starting items are added after the agents. Authored visits
  (:visits, see manor.mechanics.visits/authored-events) are made last, after
  the cast and the calendar. Starting relations are given once the whole
  cast is spawned, since they name other people."
  [run]
  (let [{:keys [scenario opts]} run
        {:keys [state agents clock world-file items] :as init} ((:init scenario) opts)
        _ (when-let [ps (and clock (clock/problems clock))]
            (throw (ex-info (str "bad clock from :init: " (str/join "; " ps)) {:clock clock})))
        clock (with-start scenario clock (:start opts))]
    (when world-file
      (store/write-text! (:dir run) "world-start.edn" (worldfile/->edn-string world-file)))
    (run/emit! run [{:type :world/initialized :visible-to :gm
                     :data (cond-> {:scenario (:name scenario) :seed (:seed opts) :state state}
                             clock (assoc :clock clock))}])
    (run/emit! run (space/init-events (:space init)))
    (check-agents! scenario agents)
    (let [spawn! (fn [spec]
                   (let [evs (spawn-at-start scenario @(:world run) (into {} (remove (comp nil? val)) spec))]
                     (run/emit! run evs)
                     [(:name spec) (get-in (first evs) [:data :agent :id]) (:relations spec)]))
          spawned (mapv spawn! (cons {:name "GM" :role :gm :model (:gm-model opts) :controller (:gm opts)} agents))
          holders (filterv #(seq (nth % 2)) spawned)]
      (when (seq holders)
        (check-relations! scenario @(:world run) holders)
        (run/emit! run (relations/starting-events (:relations scenario) (map rest holders)))))
    (when (:items scenario)
      (check-items! @(:world run) items)
      (run/emit! run (items/init-events items)))
    (when (:calendar scenario)
      (run/emit! run (calendar/init-events @(:world run) (:calendar scenario) (:calendar init))))
    (when (some? (:visits init))
      (run/emit! run (visits/authored-events @(:world run) (:visits init))))))

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
  MCP servers and its model client, if that holds a process (:pi)."
  [run]
  (pause! run)
  (submit! run #(do (mcp/close-all! (:mcp run))
                    (when (instance? java.io.Closeable (:llm run)) (.close ^java.io.Closeable (:llm run)))))
  (.shutdown ^ExecutorService (:exec run)))
