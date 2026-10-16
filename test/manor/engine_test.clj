(ns manor.engine-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [manor.actions :as a]
            [manor.engine :as e]
            [manor.llm :as llm]
            [manor.narrate :as n]
            [manor.prompt :as prompt]
            [manor.store :as store]
            [manor.world :as w]))

;; All runs here use {:test? true}, so they live under test-runs/.
(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

(defn- test-id [base] (str (gensym (str base "-"))))

(defn- mock-village [& [opts]]
  (e/start-run :village (merge {:llm :mock :offline? true :test? true :run-id (test-id "village")} opts)))

(defn- rounds! [run n]
  (e/auto! run n)
  (is (= :idle (e/wait-idle run 60000)))
  run)

(defn- of-type [run t] (filter #(= t (:type %)) (e/events run)))

(deftest mock-run-and-replay
  (let [run (rounds! (mock-village) 6)
        world (e/world run)]
    (testing "rounds ran"
      (is (= 6 (:round world))))
    (testing "the GM spawned a newcomer when the mayor asked"
      (is (= :active (:status (w/agent world "eva")))))
    (testing "the act bridge is waiting for approval"
      (is (= 1 (count (:pending-bridges world)))))
    (testing "replaying the log rebuilds the same world"
      (is (= world (w/replay (store/read-events (:dir run)))))
      (is (= world (e/world (e/load-run (:id run) {:test? true})))))
    (e/stop-run run)))

(deftest private-information-stays-private
  (let [run (rounds! (mock-village) 3)
        world (e/world run)
        evs (e/events run)
        bruno-prompt (str/join "\n" (map :content (prompt/agent-messages (:scenario run) world evs "bruno" {})))]
    (testing "Bruno does not see Ana's private events"
      (is (str/includes? bruno-prompt "Bruno planted"))
      (is (not (str/includes? bruno-prompt "Ana planted"))))
    (testing "Bruno's view has only his own holdings"
      (let [view ((:observe (:scenario run)) world "bruno")]
        (is (= (get-in world [:state :holdings "bruno"]) (get-in view [:you :holdings])))
        (is (not (str/includes? (pr-str view) "\"ana\" {:coins")))))
    (testing "usage bookkeeping never reaches a prompt"
      (is (every? #(not (w/visible-to? % "bruno")) (of-type run :llm/usage)))
      (is (every? #(not (w/visible-to? % :gm)) (of-type run :llm/usage))))
    (e/stop-run run)))

(deftest act-bridge-needs-approval
  (let [run (rounds! (mock-village) 3)
        [req-id] (keys (:pending-bridges (e/world run)))]
    (is (some? req-id))
    (testing "nothing is written before approval"
      (is (not (.exists (io/file (:dir run) "outbox" "letters.md")))))
    @(e/approve! run req-id)
    (rounds! run 1)
    (testing "approved bridge ran at the next round boundary"
      (is (.exists (io/file (:dir run) "outbox" "letters.md")))
      (is (seq (of-type run :village/letter-sent)))
      (is (empty? (:pending-bridges (e/world run)))))
    (e/stop-run run)))

(deftest rejected-action-gets-feedback-and-retry
  (let [calls (atom [])
        scripted (reify llm/LLM
                   (complete [_ {:keys [messages ctx]}]
                     (swap! calls conj messages)
                     (let [[tool args] (cond
                                         (not= "ana" (:agent-id ctx)) ["pass" {}]
                                         (zero? (:step ctx)) ["plant" {:grain 20}]
                                         :else ["plant" {:grain 2}])]
                       {:message {:role "assistant" :content nil
                                  :tool_calls [{:id "c1" :type "function"
                                                :function {:name tool :arguments (prompt/->json args)}}]}
                        :tool-calls [{:id "c1" :name tool :arguments (prompt/->json args)}]
                        :usage {:prompt-tokens 10 :completion-tokens 5 :cost 0.001}})))
        run (rounds! (mock-village {:llm-impl scripted}) 1)
        world (e/world run)]
    (testing "the second attempt was applied"
      (is (= 2 (get-in world [:state :fields "ana" :planted]))))
    (testing "the model was told why the first attempt failed"
      (is (some (fn [msgs] (some #(and (= "tool" (:role %)) (str/includes? (:content %) "rejected: you only have"))
                                 msgs))
                @calls)))
    (testing "usage is tracked per agent"
      (is (= 2 (get-in world [:usage "ana" :calls]))))
    (e/stop-run run)))

(deftest simultaneous-turns-resolve-conflicts
  (let [run (rounds! (e/start-run :prize {:llm :mock :test? true :run-id (test-id "prize")}) 1)
        world (e/world run)]
    (testing "both proposed a claim; the first in schedule order won"
      (is (= "p1" (get-in world [:state :winner])))
      (is (= 1 (count (of-type run :prize/claimed)))))
    (testing "the loser was told, privately"
      (let [[rej] (of-type run :action/rejected)]
        (is (= "p2" (:actor rej)))
        (is (= #{"p2"} (:visible-to rej)))))
    (e/stop-run run)))

(deftest human-seat
  (let [run (e/start-run :prize {:llm :mock :test? true :run-id (test-id "prize-human")})]
    @(e/update-agent! run "p2" {:controller :human})
    (e/step! run)
    (is (= :awaiting-human (e/wait-idle run 10000)))
    (testing "invalid input is reported and the seat keeps waiting"
      (e/act! run "p2" "fly" {})
      (Thread/sleep 200)
      (is (str/includes? (:feedback @(:input run)) "no tool called fly")))
    (e/act! run "p2" "pass" {})
    (is (= :idle (e/wait-idle run 10000)))
    (is (seq (filter #(and (= :agent/passed (:type %)) (= "p2" (:actor %))) (e/events run))))
    (e/stop-run run)))

(deftest branching
  (let [run (rounds! (mock-village) 5)
        branch (e/branch-run run 3)]
    (testing "a branch of a test run is also a test run"
      (is (= "test-runs" (.getName (.getParentFile (:dir branch))))))
    (testing "the branch starts at the end of round 3"
      (is (= 3 (:round (e/world branch))))
      (is (= (e/world branch)
             (w/replay (take-while #(not (and (= :round/started (:type %)) (= 4 (get-in % [:data :round]))))
                                   (e/events run))))))
    (testing "the branch can continue"
      (rounds! branch 1)
      (is (= 4 (:round (e/world branch)))))
    (e/stop-run run)
    (e/stop-run branch)))

(deftest run-directories
  (testing "test runs live under test-runs/"
    (let [run (mock-village)]
      (is (= "test-runs" (.getName (.getParentFile (:dir run)))))
      (testing "an existing run directory is never reused"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already exists"
                              (mock-village {:run-id (:id run)}))))
      (e/stop-run run))))

(deftest operator-commands
  (let [run (mock-village)]
    @(e/spawn! run {:role :merchant :name "Rui" :model "openai/gpt-5-mini"})
    @(e/retire! run "bruno" "moved to Porto")
    (let [world (e/world run)]
      (is (= "openai/gpt-5-mini" (:model (w/agent world "rui"))))
      (is (= 60 (get-in world [:state :holdings "rui" :coins])))
      (is (= :retired (:status (w/agent world "bruno")))))
    (e/stop-run run)))

(deftest action-specs-become-tools
  (let [run (mock-village)
        farmer-tools (set (map (comp name :name) (a/for-role (:actions run) :farmer)))
        gm-tools (set (map (comp name :name) (a/for-role (:actions run) :gm)))]
    (is (contains? farmer-tools "plant"))
    (is (not (contains? farmer-tools "set-tax")))
    (is (not (contains? farmer-tools "spawn-agent")))
    (is (contains? gm-tools "spawn-agent"))
    (is (not (contains? gm-tools "plant")))
    (is (= {:type "object" :properties {:grain {:type "integer" :minimum 1 :maximum 30}} :required [:grain]}
           (get-in (a/->tool (first (filter #(= :plant (:name %)) (:actions run)))) [:function :parameters])))
    (e/stop-run run)))

(defn- hamlet [] (e/start-run :hamlet {:llm :mock :test? true :run-id (test-id "hamlet")}))

(defn- rounds-acted
  "Rounds in which `id` made a model call."
  [run id]
  (set (map :round (filter #(and (= :llm/usage (:type %)) (= id (:actor %))) (e/events run)))))

(deftest occupied-agents-skip-turns
  (let [run (rounds! (hamlet) 22)
        world (e/world run)]
    (testing "Ada goes to bed in round 2 (21:30) and next acts at 07:00 (round 21), at no cost in between"
      (is (= #{1 2 21} (rounds-acted run "ada")))
      (is (= (set (range 1 23)) (rounds-acted run "ben"))))
    (testing "she was freed at the start of round 21, seen by those present"
      (let [[freed] (of-type run :agent/freed)]
        (is (= 21 (:round freed)))
        (is (= #{"ada"} (:visible-to freed)))
        (is (= "Ada is no longer asleep." (n/describe freed (n/names world))))))
    (testing "and went back to sleep until the next morning"
      (is (= {:activity "asleep" :until 69} (w/busy world "ada"))))
    (testing "replay rebuilds occupation"
      (is (= world (w/replay (store/read-events (:dir run))))))
    (e/stop-run run)))

(deftest occupation-is-visible-and-can-be-interrupted
  (let [run (rounds! (hamlet) 3)
        _ @(e/place! run "ben" "bedroom")
        world (e/world run)
        rouse (first (filter #(= :rouse (:name %)) (:actions run)))]
    (testing "people present see what someone is doing"
      (is (= "asleep" (:doing (first (:people-here (:location (prompt/agent-view (:scenario run) world "ben"))))))))
    (testing "rousing frees them early, and the rouser is named"
      (is (nil? (a/check rouse world "ben" {:who "ada"})))
      (let [[freed] (a/effect-events rouse world "ben" {:who "ada"})]
        (is (= {:activity "asleep" :by "ben"} (:data freed)))
        (is (= #{"ada" "ben"} (:visible-to freed)))
        (is (nil? (w/busy (w/apply-event world freed) "ada")))
        (is (= "Ada is no longer asleep (interrupted by Ben)." (n/describe freed (n/names world))))))
    (testing "only someone who is busy can be roused"
      (is (= "ben is not busy" (a/check rouse world "ada" {:who "ben"}))))
    (e/stop-run run)))

(deftest prompts-with-a-clock
  (let [run (rounds! (hamlet) 3)
        world (e/world run)
        evs (e/events run)
        user-msg (:content (second (prompt/agent-messages (:scenario run) world evs "ben" {})))
        gm-msg (:content (second (prompt/gm-messages (:scenario run) world evs ["test"] {})))
        view (prompt/agent-view (:scenario run) world "ben")]
    (testing "the header and event lines use the time, not rounds"
      (is (str/starts-with? user-msg "# Saturday 15 October 1904, 22:00"))
      (is (str/includes? user-msg "[21:00] Ada goes from Hall to Bedroom."))
      (is (not (str/includes? user-msg "--- Round")))
      (is (not (re-find #"\[r\d" user-msg))))
    (testing "the view has the time and exits in minutes"
      (is (= "Saturday 15 October 1904, 22:00" (:time view)))
      (is (not (contains? view :round)))
      (is (= #{0} (set (map :travel-minutes (get-in view [:location :exits]))))))
    (testing "the GM still sees round markers, and the round number"
      (is (str/includes? gm-msg "(round 3)"))
      (is (str/includes? gm-msg "--- Round 2 begins ---")))
    (e/stop-run run)))

(deftest scenarios-can-exclude-actions
  (let [sc (e/load-scenario :hamlet)
        names (set (map :name (e/all-actions (assoc sc :exclude-actions #{:move :say}))))]
    (is (not (names :move)))
    (is (not (names :say)))
    (is (names :pass))
    (is (names :sleep))
    (is ((set (map :name (e/all-actions sc))) :move))))

(deftest argument-decoding
  (let [offer (first (filter #(= :offer (:name %)) (:actions (e/load-scenario :village))))
        accept (first (filter #(= :accept (:name %)) (:actions (e/load-scenario :village))))]
    (testing "snake_case and camelCase keys are accepted"
      (is (= {:to "clara" :want-grain 2 :give-coins 10}
             (:args (a/decode-args offer "{\"to\": \"clara\", \"want_grain\": 2, \"giveCoins\": 10}")))))
    (testing "unknown keys are dropped"
      (is (= {} (:args (a/decode-args (first (filter #(= :harvest (:name %)) (:actions (e/load-scenario :village))))
                                      "{\"no_parameter\": \"true\"}")))))
    (testing "a failed decode names the expected and ignored parameters"
      (let [{:keys [error]} (a/decode-args accept "{\"offer\": \"offer-1\"}")]
        (is (str/includes? error "expected parameters: offer-id"))
        (is (str/includes? error "ignored unknown keys: offer"))))))
