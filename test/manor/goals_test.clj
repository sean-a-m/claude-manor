(ns manor.goals-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [manor.actions :as a]
            [manor.engine :as e]
            [manor.test-support :as support]
            [manor.io.llm :as llm]
            [manor.io.store :as store]
            [manor.io.worldfile :as wf]
            [manor.mechanics.goals :as goals]
            [manor.prompt :as prompt]
            [manor.world :as w]))

;; All runs here use {:test? true}, so they live under test-runs/.
(use-fixtures :once support/with-runs)

(defn- test-id [base] (str (gensym (str base "-"))))

(defn- call [tool args]
  (let [c {:id "c1" :name tool :arguments (prompt/->json args)}]
    {:message {:role "assistant" :content nil
               :tool_calls [{:id "c1" :type "function" :function (select-keys c [:name :arguments])}]}
     :tool-calls [c]
     :usage {:prompt-tokens 10 :completion-tokens 5 :cost 0.001}}))

(def ^:private review-asked "Time to review your goals")

(defn- user-text [messages] (:content (second messages)))

(deftest agents-review-their-goals-on-schedule
  (let [prompts (atom [])
        ;; ana sets goals whenever her turn asks her to; everyone then waits
        scripted (reify llm/LLM
                   (complete [_ {:keys [messages ctx]}]
                     (swap! prompts conj [(:agent-id ctx) (:step ctx) (user-text messages)])
                     (if (and (= "ana" (:agent-id ctx)) (zero? (:step ctx))
                              (str/includes? (user-text messages) review-asked))
                       (call "set-goals" {:goals [(str "Bring in the harvest by round " (+ 5 (:round (:world ctx))))
                                                  "Make peace with the miller"]})
                       (call "pass" {}))))
        run (e/start-run :village {:llm :mock :llm-impl scripted :offline? true :test? true
                                   :goals-every 2 :run-id (test-id "goals")})]
    (e/auto! run 4)
    (is (= :idle (e/wait-idle run 60000)))
    (let [world (e/world run)
          sets (filter #(= :agent/goals-set (:type %)) (e/events run))
          turn-prompts (filter (fn [[_ step]] (zero? step)) @prompts)]
      (testing "the review is asked on the first turn and then every two rounds"
        (is (= [1 3] (map :round sets)))
        (is (every? #(= #{"ana"} (:visible-to %)) sets)))
      (testing "the latest list is kept on the agent"
        (is (= ["Bring in the harvest by round 8" "Make peace with the miller"] (goals/goals world "ana")))
        (is (= 3 (get-in world [:agents "ana" :goals-reviewed]))))
      (testing "only she sees her goals, under Your goals"
        (is (some (fn [[id _ text]] (and (= "ana" id) (str/includes? text "## Your goals\nLast reviewed")
                                         (str/includes? text "- Make peace with the miller")))
                  turn-prompts))
        (is (not-any? (fn [[id _ text]] (and (not= "ana" id) (str/includes? text "miller")))
                      @prompts)))
      (testing "someone who hasn't set goals sees an empty list and is asked again until they do"
        (is (every? (fn [[_ _ text]] (and (str/includes? text "## Your goals\n(none yet)")
                                          (str/includes? text review-asked)))
                    (filter (fn [[id]] (not= "ana" id)) turn-prompts))))
      (testing "replay rebuilds the goals"
        (is (= world (w/replay (store/read-events (:dir run)))))))
    (e/stop-run run)))

(deftest goals-every-zero-never-asks
  (let [texts (atom [])
        scripted (reify llm/LLM
                   (complete [_ {:keys [messages]}]
                     (swap! texts conj (user-text messages))
                     (call "pass" {})))
        run (e/start-run :village {:llm :mock :llm-impl scripted :offline? true :test? true
                                   :goals-every 0 :run-id (test-id "goals-off")})]
    (e/auto! run 2)
    (is (= :idle (e/wait-idle run 60000)))
    (is (seq @texts))
    (is (not-any? #(str/includes? % review-asked) @texts))
    (e/stop-run run)))

(deftest set-goals-keeps-at-most-ten
  (let [action (first (goals/actions))
        n-goals (fn [n] {:goals (mapv #(str "goal " %) (range n))})]
    (is (:args (a/decode-args action (n-goals 10))))
    (is (:args (a/decode-args action (n-goals 0))) "an empty list clears them")
    (is (str/includes? (:error (a/decode-args action (n-goals 11))) "the limit is 10"))))

(deftest world-files-give-starting-goals
  (let [hamlet (edn/read-string (slurp "test/fixtures/hamlet.edn"))
        with-goals #(assoc-in hamlet [:agents 0 :goals] %)]
    (testing "they're checked"
      (is (empty? (wf/problems (with-goals ["Mend the gate."]))))
      (is (seq (wf/problems (with-goals [""]))))
      (is (seq (wf/problems (with-goals "Mend the gate."))))
      (is (seq (wf/problems (with-goals (mapv #(str "goal " %) (range 11)))))))
    (let [dir (doto (store/run-dir (test-id "goals-file") true) .mkdirs)
          file (io/file dir "hamlet.edn")
          _ (spit file (pr-str (with-goals ["Mend the gate." "Learn the fiddle."])))
          texts (atom [])
          scripted (reify llm/LLM
                     (complete [_ {:keys [messages]}]
                       (swap! texts conj (user-text messages))
                       (call "pass" {})))
          run (e/start-run :hamlet {:llm :mock :llm-impl scripted :test? true :run-id (test-id "goals-start")
                                    :world-file (str file)})]
      (try
        (let [world (e/world run)]
          (testing "they start on the agent, counted as reviewed as the run starts"
            (is (= ["Mend the gate." "Learn the fiddle."] (goals/goals world "ada")))
            (is (= 0 (get-in world [:agents "ada" :goals-reviewed])))
            (is (empty? (goals/goals world "ben"))))
          (testing "the export writes them back"
            (is (= ["Mend the gate." "Learn the fiddle."]
                   (:goals (first (:agents (wf/export world (edn/read-string (slurp file))))))))))
        (e/step! run)
        (is (= :idle (e/wait-idle run 60000)))
        (testing "Ada sees them and isn't asked to review them yet; Ben, with none, is"
          (is (some #(and (str/includes? % "## Your goals\nLast reviewed") (str/includes? % "- Mend the gate.")
                          (not (str/includes? % review-asked)))
                    @texts))
          (is (some #(and (str/includes? % "(none yet)") (str/includes? % review-asked)) @texts)))
        (finally (e/stop-run run))))))
