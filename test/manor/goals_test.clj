(ns manor.goals-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [manor.actions :as a]
            [manor.engine :as e]
            [manor.test-support :as support]
            [manor.io.llm :as llm]
            [manor.io.store :as store]
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
