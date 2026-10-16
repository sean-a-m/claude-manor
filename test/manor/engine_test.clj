(ns manor.engine-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [manor.actions :as a]
            [manor.builtins :as b]
            [manor.clock :as clock]
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
    (is (contains? farmer-tools "pray"))
    (is (not (contains? farmer-tools "request-gm")))
    (is (= [{:type :gm/requested :actor "ana" :data {:text "help"} :visible-to #{"ana"}}]
           (a/effect-events (first (filter #(= :pray (:name %)) (:actions run))) (e/world run) "ana" {:text "help"})))
    (is (= {:type "object" :properties {:grain {:type "integer" :minimum 1 :maximum 30}} :required [:grain]}
           (get-in (a/->tool (first (filter #(= :plant (:name %)) (:actions run)))) [:function :parameters])))
    (e/stop-run run)))

(defn- hamlet [& [opts]] (e/start-run :hamlet (merge {:llm :mock :test? true :run-id (test-id "hamlet")} opts)))

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
    (testing "rousing interrupts them, and the rouser is named"
      (is (nil? (a/check rouse world "ben" {:who "ada"})))
      (let [[freed] (a/effect-events rouse world "ben" {:who "ada"})]
        (is (= {:activity "asleep" :by "ben"} (:data freed)))
        (is (= #{"ada" "ben"} (:visible-to freed)))
        (is (= "ben" (:interrupted-by (w/busy (w/apply-event world freed) "ada"))))
        (is (= "Ben interrupts Ada (asleep)." (n/describe freed (n/names world))))
        (is (= "Ada has already been interrupted"
               (a/check rouse (w/apply-event world freed) "ben" {:who "ada"})))))
    (testing "only someone who is busy can be roused"
      (is (= "ben is not busy" (a/check rouse world "ada" {:who "ben"}))))
    (e/stop-run run)))

(deftest interrupted-turn-decisions
  (doseq [decision [:carry-on :pass :note :bad :undecodable :query]]
    (let [seen (atom [])
          policy (fn [{:keys [agent-id world step]}]
                   (swap! seen conj [agent-id (:round world) (w/busy world agent-id)])
                   (cond
                     (= agent-id "ben") {:tool :rouse :args {:who "ada"}}
                     (= decision :bad) {:tool :whisper :args {:to "nobody" :text "hello"}}
                     (= decision :undecodable) {:tool :note :args {:text 1}}
                     (= decision :note) (if (zero? step) {:tool :note :args {:text "awake"}} {:tool :pass})
                     (= decision :query) {:tool :test-query}
                     :else {:tool decision}))
          run (hamlet {:llm-impl (llm/mock policy) :max-steps 1})
          query {:name :test-query :kind :query :roles :all :args [:map]
                 :bridge :test/query}
          run (-> run (update :actions conj query)
                  (assoc-in [:opts :bridge-stubs :test/query]
                            (fn [_ _] (is (nil? (w/busy (e/world run) "ada"))) {:ok true})))]
      @(e/place! run "ben" "bedroom")
      @(e/place! run "ada" "bedroom")
      (e/emit! run [(b/occupy (e/world run) "ada" "asleep" 8)])
      (rounds! run 1)
      (is (= #{"ben"} (set (map first @seen))))
      (is (= "ben" (:interrupted-by (w/busy (e/world run) "ada"))))
      (is (= {:activity "asleep" :until "Sun 00:30" :interrupted-by "Ben"}
             (get-in (prompt/agent-view (:scenario run) (e/world run) "ada") [:you :busy])))
      ;; No further interruptions: Ben also becomes busy, and must not get a turn.
      (e/emit! run [(b/occupy (e/world run) "ben" "reading" 8)])
      (reset! seen [])
      (rounds! run 1)
      (is (= ["ada"] (mapv first @seen)))
      (if (#{:pass :note :query} decision)
        (let [freed (first (of-type run :agent/freed))]
          (is (nil? (w/busy (e/world run) "ada")))
          (is (= "ben" (get-in freed [:data :by])))
          (is (< (:id freed) (:id (last (e/events run))))))
        (do (is (= {:activity "asleep" :until 8} (w/busy (e/world run) "ada")))
            (is (empty? (of-type run :agent/freed)))
            (is (= 1 (count (of-type run :agent/resumed))))))
      (is (= (e/world run) (w/replay (e/events run))))
      (e/stop-run run))))

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

(deftest briefs-can-be-functions-of-the-world
  (let [run (rounds! (hamlet) 2)
        world (e/world run)
        evs (e/events run)
        sc (-> (:scenario run)
               (assoc-in [:roles :resident :brief] (fn [world id] (str "Brief for " id " in round " (:round world) ".")))
               (assoc-in [:roles :gm :brief] (fn [world] (str "GM brief in round " (:round world) "."))))]
    (is (str/includes? (:content (first (prompt/agent-messages sc world evs "ben" {})))
                       "## Your role\nBrief for ben in round 2."))
    (is (str/includes? (:content (first (prompt/gm-messages sc world evs ["test"] {})))
                       "## This world\nGM brief in round 2."))
    (testing "string briefs are unchanged"
      (is (str/includes? (:content (first (prompt/agent-messages (:scenario run) world evs "ben" {})))
                         (str "## Your role\n" (get-in run [:scenario :roles :resident :brief])))))
    (e/stop-run run)))

(deftest the-start-option
  (let [run (hamlet {:start "1904-10-15T23:00"})]
    (testing "the clock starts at :start and keeps the scenario's minutes per round"
      (is (= {:start "1904-10-15T23:00" :minutes-per-round 30} (:clock (e/world run))))
      (is (= "Saturday 15 October 1904, 23:00" (:time (prompt/agent-view (:scenario run) (e/world run) "ada")))))
    (rounds! run 4)
    (testing "rounds count from there: Ada goes up at 23:00 and sleeps from 23:30 until 07:00"
      (let [[slept] (filter #(= :agent/occupied (:type %)) (e/events run))]
        (is (= 2 (:round slept)))
        (is (= 17 (get-in slept [:data :until])))
        (is (= "Sunday 16 October 1904, 07:00" (clock/label (:clock (e/world run)) 17)))))
    (testing "replay, loading and branching keep the clock"
      (is (= (e/world run) (w/replay (store/read-events (:dir run)))))
      (is (= (e/world run) (e/world (e/load-run (:id run) {:test? true}))))
      (let [b (e/branch-run run 2)]
        (is (= (:clock (e/world run)) (:clock (e/world b))))
        (e/stop-run b)))
    (e/stop-run run))
  (testing "a bad :start, or a scenario without a clock, fails at start"
    (is (thrown-with-msg? Exception #"bad :start option: :start must look like \"1904-10-15T08:00\", not \"23:00\""
                          (hamlet {:start "23:00"})))
    (is (thrown-with-msg? Exception #"the :start option needs a scenario with a clock; village has none"
                          (mock-village {:start "1904-10-15T23:00"})))))

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

(deftest length-rejections-say-how-to-fix-the-call
  (let [say (first (filter #(= :say (:name %)) (b/agent-actions)))
        text (fn [n] {:text (apply str (repeat n "a"))})]
    (testing "say and whisper tell models their limit, and only that one"
      (is (str/includes? (get-in (a/->tool say) [:function :description]) "at most 1000 characters"))
      (is (= {:type "string" :minLength 1} (get-in (a/->tool say) [:function :parameters :properties :text]))))
    (testing "up to 1500 characters is accepted, as slack"
      (is (= (text 1500) (:args (a/decode-args say (text 1500))))))
    (testing "longer text is told its length and how much to cut, down to the advised limit"
      (let [{:keys [error]} (a/decode-args say (text 1600))]
        (is (str/includes? error "text is 1600 characters; the limit is 1000 - shorten it by at least 600"))
        (is (str/includes? error "expected parameters: text"))))
    (testing "without an advised limit, the schema's own; too short, and collections, likewise"
      (let [act {:name :x :args [:map [:word [:string {:min 3 :max 5}]] [:ids {:optional true} [:vector {:max 2} :string]]]}]
        (is (str/includes? (:error (a/decode-args act {:word "abcdefgh"}))
                           "word is 8 characters; the limit is 5 - shorten it by at least 3"))
        (is (str/includes? (:error (a/decode-args act {:word "a"}))
                           "word is 1 characters; the minimum is 3 - lengthen it by at least 2"))
        (is (str/includes? (:error (a/decode-args act {:word "abc" :ids ["a" "b" "c"]}))
                           "ids has 3 items; the limit is 2 - remove at least 1"))
        (testing "other errors are still humanized"
          (is (str/includes? (:error (a/decode-args act {:word 7})) "should be a string")))
        (testing "and the tool schema keeps its maxLength"
          (is (= {:type "string" :minLength 3 :maxLength 5}
                 (get-in (a/->tool act) [:function :parameters :properties :word]))))))))

(deftest notes-advise-400-characters-and-accept-500
  (let [note (first (filter #(= :note (:name %)) (b/agent-actions)))
        text (fn [n] {:text (apply str (repeat n "a"))})
        tool (a/->tool note)]
    (is (str/includes? (get-in tool [:function :description]) "at most 400 characters"))
    (is (= {:type "string" :minLength 1} (get-in tool [:function :parameters :properties :text])))
    (is (= (text 500) (:args (a/decode-args note (text 500)))))
    (is (str/includes? (:error (a/decode-args note (text 501)))
                       "text is 501 characters; the limit is 400 - shorten it by at least 101"))))

(deftest the-default-gm-view
  (testing "other scenarios' state is shown whole"
    (let [run (mock-village)
          world (e/world run)]
      (is (= (:state world) (:state (prompt/default-gm-view world))))
      (e/stop-run run)))
  (testing "Claudeworth's setting and the GM's own :about are left to the GM brief"
    (let [run (e/start-run :claudeworth {:llm :mock :test? true :run-id (test-id "claudeworth")})
          world (e/world run)
          state (:state (prompt/default-gm-view world))
          gm-msgs (prompt/gm-messages (:scenario run) world (e/events run) ["test"] {})
          occurrences (fn [s sub] (count (re-seq (re-pattern (java.util.regex.Pattern/quote sub)) s)))]
      (is (not (contains? state :setting)))
      (is (not (contains? (:roles state) :gm)))
      (is (= (get-in world [:state :roles :architect]) (get-in state [:roles :architect])))
      (is (= (get-in world [:state :bedrooms]) (:bedrooms state)))
      (is (= 1 (occurrences (str/join "\n" (map :content gm-msgs)) (get-in world [:state :setting]))))
      (e/stop-run run)))
  (testing ":roles goes when only the GM's entry was in it"
    (is (= {:x 1} (:state (prompt/default-gm-view {:state {:x 1 :setting "s" :roles {:gm {:about "a"}}}}))))))

(defn- scripted
  "A model that calls the tool `(policy ctx)` returns, as [tool args], and
  keeps the messages of every call in `calls`."
  [calls policy]
  (reify llm/LLM
    (complete [_ {:keys [messages ctx]}]
      (swap! calls conj {:agent (:agent-id ctx) :step (:step ctx) :messages messages})
      (let [[tool args] (policy ctx)
            call {:id (str "c" (:step ctx)) :name tool :arguments (prompt/->json args)}]
        {:message {:role "assistant" :content nil
                   :tool_calls [{:id (:id call) :type "function"
                                 :function {:name tool :arguments (:arguments call)}}]}
         :tool-calls [call]
         :usage {:prompt-tokens 10 :completion-tokens 5 :cost 0.001}}))))

(defn- walker
  "The earl walks back and forth `walks` times, then says good morning;
  everyone else passes."
  [walks]
  (fn [{:keys [agent-id role step]}]
    (cond (= role :gm) ["done" {}]
          (not= "lord-claudeworth" agent-id) ["pass" {}]
          (< step walks) ["walk" {:to (if (even? step) "dining-room" "earls-bedroom")}]
          :else ["say" {:text "Good morning."}])))

(defn- earls-calls [calls] (filterv #(= "lord-claudeworth" (:agent %)) @calls))

(defn- last-message [call] (:content (peek (:messages call))))

(defn- claudeworth-run [llm-impl opts]
  (e/start-run :claudeworth (merge {:llm-impl llm-impl :offline? true :test? true :run-id (test-id "claudeworth")}
                                   opts)))

(deftest at-most-ten-calls-per-turn
  (is (= 10 (:max-steps e/default-opts))))

(deftest free-actions-get-a-reminder-to-end-the-turn
  (let [calls (atom [])
        run (rounds! (claudeworth-run (scripted calls (walker 3)) {}) 1)
        earl (earls-calls calls)]
    (testing "the earl walked three times, then spoke, and kept his turn"
      (is (= 4 (count earl)))
      (is (= 1 (count (filter #(= "lord-claudeworth" (:actor %)) (of-type run :agent/said)))))
      (is (empty? (of-type run :action/forfeited))))
    (testing "after two calls he was reminded, once, naming his turn-ending actions"
      (is (not= "user" (:role (peek (:messages (earl 1))))))
      (let [reminder (last-message (earl 2))]
        (is (str/includes? reminder "free tools, queries and rejected calls don't end it"))
        (is (str/includes? reminder "one of your 10 calls this turn (2 used)"))
        (is (str/includes? reminder "turn-ending action: `say`, `commission`, `travel`, `buy`, `pass`, `sleep`, `spend-time` or `whisper`."))
        (is (str/includes? reminder "`pass` is a fine way to wait"))
        (is (not (str/includes? reminder "`walk`"))))
      (is (= 1 (count (filter #(and (= "user" (:role %)) (str/includes? (:content %) "turn isn't over"))
                              (:messages (earl 3)))))))
    (testing "the last call's replies are recorded"
      (let [logged (filter #(= "lord-claudeworth" (:agent %)) (store/read-llm (:dir run)))]
        (is (= 4 (count logged)))
        (is (= [{:role "tool" :tool_call_id "c3" :content "done"}] (:replies (last logged))))
        (testing "and every call's replies are what the next call was sent"
          (is (= (:replies (first logged)) (drop (inc (count (:messages (first logged)))) (:messages (second logged))))))))
    (testing "replay is unaffected"
      (is (= (e/world run) (w/replay (store/read-events (:dir run))))))
    (e/stop-run run)))

(deftest the-last-call-is-announced
  (let [calls (atom [])
        run (rounds! (claudeworth-run (scripted calls (walker 99)) {:max-steps 4}) 1)
        earl (earls-calls calls)
        logged (filter #(= "lord-claudeworth" (:agent %)) (store/read-llm (:dir run)))]
    (is (= 4 (count earl)))
    (testing "before the last call"
      (is (str/starts-with? (last-message (earl 3)) "This is your last call this turn."))
      (is (str/includes? (last-message (earl 3)) "or you lose your turn"))
      (is (str/includes? (last-message (earl 3)) "`pass` is a fine way to wait")))
    (testing "a turn of free actions only is still forfeited, and its last replies are logged"
      (is (= ["lord-claudeworth"] (map :actor (of-type run :action/forfeited))))
      (is (= [{:role "tool" :tool_call_id "c3" :content "ok"}] (:replies (last logged)))))
    (e/stop-run run)))
