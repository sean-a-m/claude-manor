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
  without an architect. Nothing nondeterministic is written: no times, run
  ids or costs."
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
            [manor.scenarios.claudeworth :as cw]
            [manor.scenarios.claudeworth.shop :as shop]))

(def scenarios [:claudeworth :lodge :claudeworth-medium])

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

(def ^:private judge-brief @#'cw/judge-brief)

(defn- rendered
  "Name -> text: every prompt that phase 1 of docs/settings-design.md moves."
  []
  (let [[run world] (start-world :claudeworth)
        [lodge-run lodge-world] (start-world :lodge)
        tool #(tool-text (a/->tool (first (filter (comp #{%} :name) (:actions run))) world))]
    {"agent-preamble.md" (prompt/agent-preamble world)
     "rules-claudeworth.md" (cw/rules world)
     "rules-claudeworth-no-find-at-shops.md" (cw/rules (assoc-in world [:state :find-everywhere?] false))
     "rules-claudeworth-no-visits.md" (cw/rules (update world :state dissoc :visit-config))
     "rules-lodge.md" (cw/rules lodge-world)
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
  (into {} (for [s scenarios] [s (capture-day! label s)])))
