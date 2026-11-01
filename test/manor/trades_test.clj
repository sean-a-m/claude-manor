(ns manor.trades-test
  "Tradespeople (docs/trades-design.md): `recommend` with a `trade`, the
  trade judge, contacts, and `send-for` with a contact and a job, on the
  production Claudeworth mechanics and the visits test world
  (test/fixtures/visits.edn: start 1904-10-15 20:00, half-hour rounds; the
  earl at the post office, Hale in the drawing room, Carter the blacksmith
  at his forge)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [manor.actions :as a]
            [manor.engine :as e]
            [manor.io.store :as store]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.calendar :as calendar]
            [manor.mechanics.space :as space]
            [manor.mechanics.trades :as trades]
            [manor.mechanics.trades.judge :as judge]
            [manor.mechanics.visits :as visits]
            [manor.prompt :as prompt]
            [manor.ruleset.mock :as mock]
            [manor.ruleset.rules :as rules]
            [manor.scenarios.claudeworth :as cw]
            [manor.test-support :as support]
            [manor.world :as w]))

(use-fixtures :once support/with-runs)
(use-fixtures :each (partial support/with-structured-attempts 4))

(def world-file "test/fixtures/visits.edn")

(defn- start-with [scenario day]
  (with-redefs [e/load-scenario (constantly (assoc scenario :mock-policy #(mock/scripted-policy day %)))]
    (e/start-run :claudeworth {:test? true :llm :mock :offline? true :gm :off :max-cost 0.20
                               :world-file world-file :run-id (str (gensym "trades-"))})))

(defn- until! [run r]
  (let [n (- r (:round (e/world run)))]
    (when (pos? n)
      (e/auto! run n)
      (is (= :idle (e/wait-idle run 60000))))
    (is (= r (:round (e/world run))))))

(defn- with-judge [scenario mock] (assoc-in scenario [:adjudicators :trades/tradesperson :mock] mock))
(defn- of-type [run type] (filter #(= type (:type %)) (e/events run)))
(defn- replay! [run] (is (= (e/world run) (w/replay (store/read-events (:dir run))))))
(defn- action [run tool] (first (filter #(= tool (:name %)) (:actions run))))
(defn- judge-calls [run] (filter #(= "adjudicator/trades/tradesperson" (:agent %)) (store/read-llm (:dir run))))
(defn- judge-input [call] (json/read-value (:content (second (:messages call))) json/keyword-keys-object-mapper))
(defn- replies-to [run agent tool]
  (for [call (store/read-llm (:dir run)) :when (= agent (:agent call))
        tc (get-in call [:response :tool_calls]) :when (= tool (get-in tc [:function :name]))
        r (:replies call) :when (= (:id tc) (:tool_call_id r))]
    (:content r)))

(defn- check [run world actor tool args]
  (let [spec (action run tool)
        decoded (a/decode-args spec args)]
    (or (:error decoded) (a/check spec world actor (:args decoded)))))

(def tuner-day
  "Hale goes down to the post office and recommends a piano tuner to the
  earl, who sends for him; Hale asks again for one later."
  {"hale" {"20:00" [[:travel {:to "post-office"}]]
           "20:30" [[:recommend {:trade "a piano tuner" :for "lord-claudeworth"}] [:pass {}]]}
   "lord-claudeworth" {"20:00" [[:pass {}]]
                       "20:30" [[:pass {}]]
                       "21:00" [[:send-for {:contact "contact-1" :job "tune the drawing-room piano"}]]}})

(deftest a-piano-tuner-from-elsewhere-is-recommended-sent-for-and-goes-home
  (let [run (start-with cw/scenario tuner-day)]
    (try
      (until! run 2)
      (let [world (e/world run)
            c (trades/contact world "contact-1")
            tuner (w/agent world (:agent-id c))
            [call] (judge-calls run)
            input (judge-input call)]
        (testing "the judge saw the recommender, the trade and the people on the map outside the household"
          (is (= "Hale" (get-in input [:recommender :name])))
          (is (= "a piano tuner" (:trade input)))
          (is (= #{"mrs-bell" "carter"} (set (map :id (:local input)))))
          (is (= [] (:contacts input))))
        (testing "someone new is made an away tradesman, and the contact is known to both"
          (is (= {:trade "piano tuner" :based "Ely" :known-to #{"hale" "lord-claudeworth"}}
                 (select-keys c [:trade :based :known-to])))
          (is (= {:status :away :role :tradesman :lives "Ely"} (select-keys tuner [:status :role :lives])))
          (is (every? #(= :system (:visible-to %)) (filter #(= :tradesman (get-in % [:data :agent :role])) (of-type run :agent/spawned)))))
        (testing "only Hale learns what he knows of him; the reply says how to send for him"
          (is (some? (get-in world [:state :reputations "hale" "contact-1"])))
          (is (nil? (get-in world [:state :reputations "lord-claudeworth" "contact-1"])))
          (is (str/includes? (first (replies-to run "hale" "recommend")) "send-for with contact \"contact-1\"")))
        (testing "both see the contact in their view"
          (doseq [id ["hale" "lord-claudeworth"]]
            (is (= [{:id "contact-1" :name (:name tuner) :trade "piano tuner" :based "Ely"}]
                   (:contacts (prompt/agent-view (:scenario run) world id)))))))
      (until! run 3)
      (let [world (e/world run)
            id (:agent-id (trades/contact world "contact-1"))
            v (visits/visit world "visit-1")]
        (testing "send-for makes a visit by :sent-for, expected three hours on, in the earl's calendar"
          (is (= {:guest id :host "lord-claudeworth" :by :sent-for :stay "tune the drawing-room piano" :status :scheduled
                  :at "1904-10-16T00:00"}
                 (select-keys v [:guest :host :by :stay :status :at])))
          (is (str/includes? (calendar/character-section world "lord-claudeworth") "sent for by you: tune the drawing-room piano"))
          (is (true? (:sent-for (first (:contacts (prompt/agent-view (:scenario run) world "lord-claudeworth"))))))
          (is (some? (check run world "lord-claudeworth" :send-for {:contact "contact-1" :job "again"})))))
      (until! run 9)
      (let [world (e/world run)
            id (:agent-id (trades/contact world "contact-1"))]
        (testing "he arrives at the meeting place at midnight, knowing the job and who sent for him"
          (is (= "carriage-sweep" (space/location-of world id)))
          (is (= {:job "tune the drawing-room piano" :sent-for-by "Lord Claudeworth"}
                 (select-keys (get-in (prompt/agent-view (:scenario run) world id) [:you :visit]) [:job :sent-for-by])))))
      (until! run 13)
      (let [world (e/world run)
            id (:agent-id (trades/contact world "contact-1"))]
        (testing "two hours without a conversation or work, he goes home"
          (is (= :away (:status (w/agent world id))))
          (is (= :ended (:status (visits/visit world "visit-1"))))))
      (is (empty? (of-type run :action/forfeited)))
      (replay! run)
      (finally (e/stop-run run)))))

(def smith-day
  {"hale" {"20:00" [[:travel {:to "post-office"}]]
           "20:30" [[:recommend {:trade "a blacksmith to mend the gate" :for "lord-claudeworth"}]
                    [:recommend {:trade "the blacksmith again" :for "lord-claudeworth"}] [:pass {}]]}
   "lord-claudeworth" {"20:00" [[:pass {}]]
                       "20:30" [[:pass {}]]
                       "21:00" [[:send-for {:contact "contact-1" :job "mend the hinge of the drive gate"}]]}})

(deftest the-village-blacksmith-is-recommended-and-told
  (let [run (start-with cw/scenario smith-day)]
    (try
      (until! run 2)
      (let [world (e/world run)]
        (testing "someone on the map gets one local contact, however often recommended"
          (is (= [{:id "contact-1" :agent-id "carter" :local? true :trade "blacksmith"}]
                 (map #(select-keys % [:id :agent-id :local? :trade]) (vals (trades/contacts world)))))
          (is (= 2 (count (of-type run :contacts/recommended))))
          (is (empty? (filter #(pos? (:round %)) (of-type run :agent/spawned))))
          (is (str/includes? (first (replies-to run "hale" "recommend")) "if they're willing"))))
      (until! run 3)
      (let [world (e/world run)
            [sent] (of-type run :trades/sent-for)]
        (testing "Carter is told at once, has it in his calendar and is kept awake"
          (is (= #{"lord-claudeworth" "carter"} (:visible-to sent)))
          (is (str/includes? (calendar/character-section world "carter") "sent for by Lord Claudeworth: mend the hinge"))
          (is (visits/keeps-awake? world "carter"))))
      (until! run 4)
      (testing "and woken from his routine to see to it"
        (is (not (b/hibernating? (e/world run) "carter"))))
      (replay! run)
      (finally (e/stop-run run)))))

(deftest checks-on-recommend-and-send-for
  (let [run (start-with cw/scenario {})
        world (e/world run)]
    (try
      (is (= "give position or trade, not both"
             (check run world "lord-claudeworth" :recommend {:position "cook" :trade "a sweep" :for "hale"})))
      (is (str/starts-with? (check run world "lord-claudeworth" :recommend {:for "hale"}) "give position"))
      (is (= "the person asking must be here"
             (check run world "lord-claudeworth" :recommend {:trade "a sweep" :for "hale"})))
      (is (str/starts-with? (check run world "lord-claudeworth" :send-for {}) "give candidate"))
      (is (= "give candidate or contact, not both"
             (check run world "lord-claudeworth" :send-for {:candidate "candidate-1" :contact "contact-1"})))
      (let [known (-> world
                      (assoc-in [:state :contacts "contact-1"] {:id "contact-1" :agent-id "carter" :local? true
                                                                :trade "blacksmith" :known-to #{"lord-claudeworth"}}))]
        (is (str/starts-with? (check run known "hale" :send-for {:contact "contact-1" :job "x"}) "you have no recommendation"))
        (is (= "say in job what they're wanted for" (check run known "lord-claudeworth" :send-for {:contact "contact-1"})))
        (is (nil? (check run known "lord-claudeworth" :send-for {:contact "contact-1" :job "shoe the pony"}))))
      (is (str/includes? (rules/rules world) "### Tradespeople"))
      (finally (e/stop-run run)))))

(deftest impossible-recommends-nobody
  (let [run (start-with (with-judge cw/scenario (fn [_] {:tool :tradesperson :args {:impossible "There are no telephones here yet."}}))
                        {"hale" {"20:00" [[:travel {:to "post-office"}]]
                                 "20:30" [[:recommend {:trade "a telephone engineer" :for "lord-claudeworth"}] [:pass {}]]}})]
    (try
      (until! run 2)
      (is (empty? (trades/contacts (e/world run))))
      (is (= ["No recommendation was made: There are no telephones here yet."] (replies-to run "hale" "recommend")))
      (finally (e/stop-run run)))))

(deftest the-judge-sees-contacts-only-by-name-trade-and-home
  (let [run (start-with cw/scenario tuner-day)]
    (try
      (until! run 2)
      (let [world (e/world run)
            {:keys [ctx check]} (judge/request world "lord-claudeworth" {:trade "piano tuner" :recent []})
            listed (first (:contacts ctx))]
        (is (= #{:id :name :trade :based :known-as} (set (keys listed))))
        (is (= {:existing "contact-1"} (:verdict (check {:tool-calls [{:id "1" :name "tradesperson" :arguments {:existing "contact-1"}}]}))))
        (is (= "existing must be the id of someone listed in local or contacts"
               (:error (check {:tool-calls [{:id "1" :name "tradesperson" :arguments {:existing "hale"}}]}))))
        (is (some? (:error (check {:tool-calls [{:id "1" :name "tradesperson" :arguments {:existing "contact-1" :impossible "x"}}]})))))
      (finally (e/stop-run run)))))
