(ns prompt-capture
  "A record of what the household scenarios send models, to diff before and
  after a change that should (or shouldn't) change prompts. Free: mock and
  offline, no port.

    (require 'prompt-capture)
    (prompt-capture/capture! \"before\")   ; prompt-baseline/before/...
    ;; change the code, then
    (prompt-capture/capture! \"after\")
    ;; and in a shell: diff -r prompt-baseline/before prompt-baseline/after

  For each of `scenarios`, a mock day (`day-rounds` rounds, seed 1, under
  test-runs/) writes every model request, its messages and full tool
  definitions, to prompt-baseline/<label>/<scenario>/round-NN.md: calls in a
  round sorted by agent and then by their text, since agents in one schedule
  group and jobs judged together call at once. `rendered/` holds prompts a
  mock day may never build (the judges', the writers', every tool), rendered
  against the Claudeworth world at its start, and Lodge's for the rules
  without an architect; `rendered/engine/` the judges', steward's, finder's,
  GM's and turn reminders' prompts that a mock day may miss; `rendered/palace/`
  the same as `rendered/` against the palace, for reading what another
  setting sends. Nothing nondeterministic is written:
  no times, run ids or costs."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jsonista.core :as json]
            [manor.actions :as a]
            [manor.engine :as e]
            [manor.io.llm :as llm]
            [manor.io.store :as store]
            [manor.mechanics.find-person :as find-person]
            [manor.mechanics.hiring :as hiring]
            [manor.mechanics.people :as people]
            [manor.mechanics.physical :as physical]
            [manor.mechanics.visits :as visits]
            [manor.mechanics.visits.judge :as visits-judge]
            [manor.prompt :as prompt]
            [manor.ruleset.rules :as rules]
            [manor.mechanics.shop :as shop]
            [manor.mechanics.items.judge :as item-judge]
            [manor.mechanics.items.steward :as item-steward]
            [manor.mechanics.items.find :as item-find]
            [manor.mechanics.conversation.judge :as conversation-judge]
            [manor.engine.turn :as turn]))

(def scenarios [:claudeworth :lodge :claudeworth-medium :palace])

(def day-rounds 48)

(def root "prompt-baseline")

(def ^:private pretty (json/object-mapper {:pretty true}))

;; ---------------------------------------------------------------------------
;; Writing requests as text

(defn- message-text [{:keys [role content tool_calls tool_call_id]}]
  (str "### " role (when tool_call_id (str " (" tool_call_id ")")) "\n"
       (or content "")
       (when (seq tool_calls)
         (str (when content "\n") (json/write-value-as-string tool_calls pretty)))
       "\n"))

(defn- tool-text [tool]
  (json/write-value-as-string tool pretty))

(defn request-text
  "A request (its :messages and full :tools) as text, under `title`."
  [title {:keys [messages tools]}]
  (str "## " title "\n\n"
       (str/join "\n" (map message-text messages))
       (when (seq tools)
         (str "\n### tools\n" (str/join "\n" (map tool-text tools)) "\n"))))

(defn- write! [label & parts]
  (let [f (apply io/file root label (butlast parts))]
    (io/make-parents f)
    (spit f (last parts))))

;; ---------------------------------------------------------------------------
;; A mock day

(defrecord ToolRecorder [inner tools]
  llm/LLM
  (complete [_ request]
    (let [defs (:tools request)]
      (swap! tools merge (zipmap (map :hash (llm/tool-digests defs)) defs)))
    (llm/complete inner request)))

(defn- day-calls
  "Every call of `scenario`'s mock day, from llm.edn, with :tools as full
  definitions (llm.edn keeps only digests)."
  [scenario]
  (let [run (e/start-run scenario {:llm :mock :offline? true :test? true :seed 1
                                   :run-id (str "prompt-capture-" (name scenario) "-" (System/nanoTime))})
        tools (atom {})
        run (update run :llm #(->ToolRecorder % tools))]
    (try
      (e/auto! run day-rounds)
      (let [status (e/wait-idle run 600000)]
        (when-not (= :idle status)
          (throw (ex-info (str (name scenario) ": the mock day ended " status) {}))))
      (let [status (e/status run)
            round (:round (e/world run))]
        (when (or (:error status) (seq (:pause-reasons status))
                  (not= day-rounds round)
                  (some #(= :run/paused (:type %)) (e/events run)))
          (throw (ex-info (str (name scenario) ": incomplete or paused mock day")
                          {:status status :round round :expected-rounds day-rounds}))))
      (mapv (fn [call] (assoc call :tools (mapv #(get @tools (:hash %)) (:tool-digests call))))
            (store/read-llm (:dir run)))
      (finally (e/stop-run run)))))

(defn- capture-day!
  "Write `scenario`'s mock day under <label>/<scenario>/, a file a round."
  [label scenario]
  (let [calls (day-calls scenario)]
    (doseq [[round calls] (group-by :round calls)]
      (write! label (name scenario) (format "round-%02d.md" round)
              (str/join "\n" (->> calls
                                  (map (fn [c] [(:agent c) (request-text (:agent c) c)]))
                                  (sort-by identity)
                                  (map second)))))
    (count calls)))

;; ---------------------------------------------------------------------------
;; Prompts rendered directly

(defn- start-world
  "A scenario's world at its start (the run is made and stopped at once)."
  [scenario]
  (let [run (e/start-run scenario {:llm :mock :offline? true :test? true :seed 1
                                   :run-id (str "prompt-capture-" (name scenario) "-" (System/nanoTime))})]
    (try [run (e/world run)]
         (finally (e/stop-run run)))))

(defn- all-tools
  "Every tool of a run, its actions as a/->tool makes them."
  [run world]
  (str/join "\n" (map #(tool-text (a/->tool % world)) (:actions run))))

(def ^:private judge-brief rules/judge-brief)

(defn- rendered
  "Name -> text: every prompt that phase 1 of docs/settings-design.md moves."
  []
  (let [[run world] (start-world :claudeworth)
        [lodge-run lodge-world] (start-world :lodge)
        tool #(tool-text (a/->tool (first (filter (comp #{%} :name) (:actions run))) world))]
    {"agent-preamble.md" (prompt/agent-preamble world)
     "rules-claudeworth.md" (rules/rules world)
     "rules-claudeworth-no-find-at-shops.md" (rules/rules (assoc-in world [:state :find-everywhere?] false))
     "rules-claudeworth-no-visits.md" (rules/rules (update world :state dissoc :visit-config))
     "rules-lodge.md" (rules/rules lodge-world)
     "judge-brief.md" (judge-brief world)
     "judge-brief-lodge.md" (judge-brief lodge-world)
     "physical-rules.md" (physical/rules world)
     "find-person-instructions.md" (find-person/instructions world)
     "find-person-request.md" (request-text "find-person" (find-person/seek-request world "hale" {:who "the housemaid" :recent []}))
     "hiring-rules.md" (hiring/rules world)
     "hiring-rules-beside-visits.md" (hiring/rules-beside-visits world)
     "visits-rules.md" (visits/rules world)
     "invitation-judge-instructions.md" (visits-judge/instructions world)
     "invitation-request.md" (request-text "invitee" (visits-judge/invitation-request
                                                      world "lord-claudeworth"
                                                      {:person "my brother, Captain Arthur Vane" :reason "to shoot" :recent []}))
     "polish-instructions.md" (people/instructions world)
     "polish-request.md" (request-text "polish" (people/polish-request world
                                                 {:setting "England, 1904." :position "housemaid"
                                                  :record {:name "Ann Lee" :age 20 :sex "female" :position "housemaid"
                                                           :temperament [{:axis "openness" :level "high"}]
                                                           :faculties [{:group "Senses" :faculty "Sight" :size "small"}]}}))
     "appearance-request.md" (request-text "appearance" (people/appearance-request world
                                                         {:age 20 :sex "female" :position "housemaid"
                                                          :faculties [{:faculty "Sight" :size "small"}]}))
     "shop-instructions.md" (shop/instructions world)
     "shop-request.md" (request-text "appraise" (shop/request world "briggs"
                                                              {:goods [{:item "tin of silver polish" :amount "1"}
                                                                       {:item "sack of flour" :amount "1" :deliver true}]}))
     "tools-docs.md" (str/join "\n" (map tool [:find-person :recommend :invite :schedule-visit :quote-goods :buy :send-up
                                               :consult-references :price-list :draw-plans :show-plans :commission]))
     "tools-claudeworth.md" (all-tools run world)
     "tools-lodge.md" (all-tools lodge-run lodge-world)}))

(defn- rendered-palace
  "Name -> text: the prompts of `rendered`, against the palace at its start."
  []
  (let [[run world] (start-world :palace)]
    {"agent-preamble.md" (prompt/agent-preamble world)
     "rules.md" (rules/rules world)
     "judge-brief.md" (judge-brief world)
     "physical-rules.md" (physical/rules world)
     "find-person-request.md" (request-text "find-person" (find-person/seek-request world "sumuru-wanxia" {:who "the maid" :recent []}))
     "hiring-rules.md" (hiring/rules world)
     "hiring-rules-beside-visits.md" (hiring/rules-beside-visits world)
     "visits-rules.md" (visits/rules world)
     "invitation-request.md" (request-text "invitee" (visits-judge/invitation-request
                                                      world "sumuru-wanxia"
                                                      {:person "my younger brother, a guard of the Imperial Bodyguard" :reason "to hear news of home" :recent []}))
     "polish-request.md" (request-text "polish" (people/polish-request world
                                                 {:setting (get-in world [:state :setting]) :position "palace maid"
                                                  :record {:name "Wang Xiuying" :age 15 :sex "female" :position "palace maid"
                                                           :temperament [{:axis "openness" :level "high"}]
                                                           :faculties [{:group "Senses" :faculty "Sight" :size "small"}]}}))
     "appearance-request.md" (request-text "appearance" (people/appearance-request world
                                                         {:age 15 :sex "female" :position "palace maid"
                                                          :faculties [{:faculty "Sight" :size "small"}]}))
     "shop-request.md" (request-text "appraise" (shop/request world "han-baoshan"
                                                              {:goods [{:item "skein of red silk thread" :amount "2"}
                                                                       {:item "basket of charcoal" :amount "1" :deliver true}]}))
     "tools.md" (all-tools run world)
     "gm-request.md" (request-text "gm" {:messages (prompt/gm-messages (:scenario run) world [] ["a test"] (:opts run))
                                         :tools (mapv #(a/->tool % world) (a/for-role (:actions run) :gm))})}))

(defn- rendered-engine
  "Name -> text: the judges', the steward's, the GM's and the turn's prompts
  that a mock day doesn't always build, against Claudeworth at its start."
  []
  (let [[run world] (start-world :claudeworth)
        scenario (:scenario run)
        inner (:inner scenario)
        act {:id "job-x" :act? true :actor "mrs-finch" :place "kitchen"
             :doing "taking him by the collar" :people ["bert"]}]
    {"scene-judge-act.md" (request-text "judge" (item-judge/judge-request scenario world act {:luck 50 :recent []}))
     "steward-no-inner.md" (request-text "steward" (item-steward/steward-request world "kitchen" [] [] nil []))
     "steward-inner.md" (request-text "steward" (item-steward/steward-request world "kitchen" [] [] inner []))
     "finder.md" (request-text "finder" (item-find/finder-request world "mrs-finch" {:what "a blue umbrella"}))
     "conversation-judge.md" (request-text "react" (conversation-judge/judge-request
                                                    inner world {:initiator "mrs-finch" :members ["mrs-finch" "bert"]} []))
     "gm-request.md" (request-text "gm" {:messages (prompt/gm-messages scenario world [] ["a test"] (:opts run))
                                         :tools (mapv #(a/->tool % world) (a/for-role (:actions run) :gm))})
     "turn-reminders.md" (str/join "\n\n" [(turn/turn-reminder world 2 8 ["pass" "walk"])
                                          (turn/turn-reminder world 7 8 ["pass" "walk"])
                                          (turn/turn-reminder world 2 8 ["sleep"])
                                          (turn/turn-reminder world 7 8 ["sleep" "walk"])
                                          (#'turn/nudge world {:finish-reason "length"})
                                          (#'turn/nudge world {:finish-reason "stop"})])}))

;; ---------------------------------------------------------------------------

(defn capture!
  "Write a capture under prompt-baseline/<label>/ (replacing one there),
  and return the number of calls of each mock day."
  [label]
  (let [dir (io/file root label)]
    (when (.exists dir)
      (doseq [f (reverse (file-seq dir))] (.delete f))))
  (doseq [[file text] (rendered)]
    (write! label "rendered" file text))
  (doseq [[file text] (rendered-engine)]
    (write! label "rendered" "engine" file text))
  (doseq [[file text] (rendered-palace)]
    (write! label "rendered" "palace" file text))
  (into {} (for [s scenarios] [s (capture-day! label s)])))
