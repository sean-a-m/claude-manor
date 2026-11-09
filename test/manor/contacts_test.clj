(ns manor.contacts-test
  "Contacts (docs/sending-for-design.md, docs/trades-design.md): `recommend`
  with `wanted`, the recommendation judge, contacts, and `send-for` with a
  contact and what about, on the
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
            [manor.mechanics.contacts :as contacts]
            [manor.mechanics.contacts.judge :as judge]
            [manor.mechanics.space :as space]
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
                               :world-file world-file :run-id (str (gensym "contacts-"))})))

(defn- until! [run r]
  (let [n (- r (:round (e/world run)))]
    (when (pos? n)
      (e/auto! run n)
      (is (= :idle (e/wait-idle run 60000))))
    (is (= r (:round (e/world run))))))

(defn- with-judge [scenario mock] (assoc-in scenario [:adjudicators :contacts/recommendation :mock] mock))
(defn- of-type [run type] (filter #(= type (:type %)) (e/events run)))
(defn- replay! [run] (is (= (e/world run) (w/replay (store/read-events (:dir run))))))
(defn- action [run tool] (first (filter #(= tool (:name %)) (:actions run))))
(defn- judge-calls [run] (filter #(= "adjudicator/contacts/recommendation" (:agent %)) (store/read-llm (:dir run))))
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
  earl, who sends for him about what he wanted him for."
  {"hale" {"20:00" [[:travel {:to "post-office"}]]
           "20:30" [[:recommend {:wanted "a piano tuner for the drawing-room piano" :for "lord-claudeworth"}] [:pass {}]]}
   "lord-claudeworth" {"20:00" [[:pass {}]]
                       "20:30" [[:pass {}]]
                       "21:00" [[:send-for {:id "contact-1"}]]}})

(deftest a-piano-tuner-from-elsewhere-is-recommended-sent-for-and-goes-home
  (let [run (start-with cw/scenario tuner-day)]
    (try
      (until! run 2)
      (let [world (e/world run)
            c (contacts/contact world "contact-1")
            tuner (w/agent world (:agent-id c))
            [call] (judge-calls run)
            input (judge-input call)]
        (testing "the judge saw the recommender, what's wanted and the people on the map outside the household"
          (is (= "Hale" (get-in input [:recommender :name])))
          (is (= "a piano tuner for the drawing-room piano" (:wanted input)))
          (is (= #{"mrs-bell" "carter"} (set (map :id (:local input)))))
          (is (= [] (:contacts input))))
        (testing "someone new is made an away caller, and the contact is known to both, wanted by the asker"
          (is (= {:line "piano tuner for the drawing-room piano" :based "Ely" :known-to #{"hale" "lord-claudeworth"}
                  :wanted {"lord-claudeworth" "a piano tuner for the drawing-room piano"}}
                 (select-keys c [:line :based :known-to :wanted])))
          (is (= {:status :away :role :caller :lives "Ely"} (select-keys tuner [:status :role :lives])))
          (is (every? #(= :system (:visible-to %)) (filter #(= :caller (get-in % [:data :agent :role])) (of-type run :agent/spawned)))))
        (testing "only Hale learns what he knows of him; the reply says how to send for him"
          (is (some? (get-in world [:state :reputations "hale" "contact-1"])))
          (is (nil? (get-in world [:state :reputations "lord-claudeworth" "contact-1"])))
          (is (str/includes? (first (replies-to run "hale" "recommend")) "send-for with id \"contact-1\"")))
        (testing "both see the contact in their view, the asker with what he wanted"
          (is (= [{:id "contact-1" :name (:name tuner) :line "piano tuner for the drawing-room piano" :based "Ely"}]
                 (:contacts (prompt/agent-view (:scenario run) world "hale"))))
          (is (= [{:id "contact-1" :name (:name tuner) :line "piano tuner for the drawing-room piano" :based "Ely"
                   :wanted "a piano tuner for the drawing-room piano"}]
                 (:contacts (prompt/agent-view (:scenario run) world "lord-claudeworth"))))))
      (until! run 3)
      (let [world (e/world run)
            id (:agent-id (contacts/contact world "contact-1"))
            v (visits/visit world "visit-1")]
        (testing "send-for without about makes a visit by :sent-for about what was wanted, two hours on, in the earl's calendar"
          (is (= {:guest id :host "lord-claudeworth" :by :sent-for :stay "a piano tuner for the drawing-room piano" :status :scheduled
                  :at "1904-10-15T23:00"}
                 (select-keys v [:guest :host :by :stay :status :at])))
          (is (= "a piano tuner for the drawing-room piano" (:about (:data (first (of-type run :contacts/sent-for))))))
          (is (str/includes? (calendar/character-section world "lord-claudeworth") "sent for by you: a piano tuner for the drawing-room piano"))
          (is (true? (:sent-for (first (:contacts (prompt/agent-view (:scenario run) world "lord-claudeworth"))))))
          (is (some? (check run world "lord-claudeworth" :send-for {:id "contact-1" :about "again"})))))
      (until! run 7)
      (let [world (e/world run)
            id (:agent-id (contacts/contact world "contact-1"))]
        (testing "he arrives at the meeting place at 23:00, knowing what about and who sent for him"
          (is (= "carriage-sweep" (space/location-of world id)))
          (is (= {:job "a piano tuner for the drawing-room piano" :sent-for-by "Lord Claudeworth"}
                 (select-keys (get-in (prompt/agent-view (:scenario run) world id) [:you :visit]) [:job :sent-for-by])))))
      (until! run 11)
      (let [world (e/world run)
            id (:agent-id (contacts/contact world "contact-1"))]
        (testing "two hours without a conversation or work, he goes home"
          (is (= :away (:status (w/agent world id))))
          (is (= :ended (:status (visits/visit world "visit-1"))))))
      (is (empty? (of-type run :action/forfeited)))
      (replay! run)
      (finally (e/stop-run run)))))

(def smith-day
  {"hale" {"20:00" [[:travel {:to "post-office"}]]
           "20:30" [[:recommend {:wanted "a blacksmith to mend the gate" :for "lord-claudeworth"}]
                    [:recommend {:wanted "the blacksmith again" :for "lord-claudeworth"}] [:pass {}]]}
   "lord-claudeworth" {"20:00" [[:pass {}]]
                       "20:30" [[:pass {}]]
                       "21:00" [[:send-for {:id "contact-1" :about "mend the hinge of the drive gate"}]]}})

(deftest the-village-blacksmith-is-recommended-and-told
  (let [run (start-with cw/scenario smith-day)]
    (try
      (until! run 2)
      (let [world (e/world run)]
        (testing "someone on the map gets one local contact, however often recommended"
          (is (= [{:id "contact-1" :agent-id "carter" :local? true :line "blacksmith"}]
                 (map #(select-keys % [:id :agent-id :local? :line]) (vals (contacts/contacts world)))))
          (is (= {"lord-claudeworth" "the blacksmith again"} (:wanted (contacts/contact world "contact-1")))
              "the latest recommendation to the asker wins")
          (is (= 2 (count (of-type run :contacts/recommended))))
          (is (empty? (filter #(pos? (:round %)) (of-type run :agent/spawned))))
          (is (str/includes? (first (replies-to run "hale" "recommend")) "if they're willing"))))
      (until! run 3)
      (let [world (e/world run)
            [sent] (of-type run :contacts/sent-for)]
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
      (is (= "give position or wanted, not both"
             (check run world "lord-claudeworth" :recommend {:position "cook" :wanted "a sweep" :for "hale"})))
      (is (str/starts-with? (check run world "lord-claudeworth" :recommend {:for "hale"}) "give wanted"))
      (is (some? (check run world "lord-claudeworth" :recommend {:wanted (apply str (repeat 121 "x")) :for "hale"})))
      (is (= "the person asking must be here"
             (check run world "lord-claudeworth" :recommend {:wanted "a sweep" :for "hale"})))
      (is (str/starts-with? (check run world "lord-claudeworth" :send-for {:id "contact-9"}) "nobody of that id has been recommended to you"))
      (let [known (-> world
                      (assoc-in [:state :contacts "contact-1"] {:id "contact-1" :agent-id "carter" :local? true
                                                                :line "blacksmith" :known-to #{"lord-claudeworth"}}))]
        (is (str/starts-with? (check run known "hale" :send-for {:id "contact-1" :about "x"}) "you have no recommendation"))
        (is (str/starts-with? (check run known "lord-claudeworth" :send-for {:id "contact-1"}) "say in about what they're wanted for"))
        (is (nil? (check run known "lord-claudeworth" :send-for {:id "contact-1" :about "shoe the pony"})))
        (is (nil? (check run (assoc-in known [:state :contacts "contact-1" :wanted "lord-claudeworth"] "a smith")
                         "lord-claudeworth" :send-for {:id "contact-1"}))
            "about defaults to what was wanted")
        (is (some? (check run known "lord-claudeworth" :send-for {:id "contact-1" :about (apply str (repeat 301 "x"))}))))
      (is (str/includes? (rules/rules world) "### Tradespeople"))
      (finally (e/stop-run run)))))

(deftest a-line-offers-the-turns-recommend-and-send-for
  (let [run (start-with cw/scenario {})
        world (e/world run)
        conv {:id "c" :initiator "lord-claudeworth" :members ["lord-claudeworth" "hale"]}
        line (into {} (map (juxt :name identity)) ((:speaking-actions (:scenario run)) world "hale" conv))]
    (try
      (testing "recommend is contacts' (with wanted), only limited to the conversation's members"
        (is (= (dissoc (action run :recommend) :valid?) (dissoc (:recommend line) :valid?)))
        (is (some #{:wanted} (map first (rest (a/args-schema (:recommend line))))))
        (is (= "recommend to someone in this conversation"
               (a/check (:recommend line) world "hale" {:wanted "a sweep" :for "mrs-bell"}))))
      (testing "send-for is the turn's, and free"
        (is (= (action run :send-for) (:send-for line)))
        (is (= :free (a/kind (:send-for line)))))
      (finally (e/stop-run run)))))

(def tuner-talk-day
  "Hale goes down to the post office; the earl asks him there about a piano
  tuner, and passes after."
  {"hale" {"20:00" [[:travel {:to "post-office"}]]}
   "lord-claudeworth" {"20:00" [[:pass {}]]
                       "20:30" [[:converse {:with ["hale"] :text "Do you know of a piano tuner, Hale?"}] [:pass {}]]}})

(defn- tuner-talk
  "Hale recommends a tuner on his line; the earl sends for him on his last word."
  [{:keys [agent-id used conversation]}]
  (cond
    (= "hale" agent-id)
    (if (or (some #{"recommend"} used) (not= 1 (:pass conversation)))
      {:tool :reply :args {:text "I know of one in Ely, my lord."}}
      {:tool :recommend :args {:wanted "a piano tuner" :for "lord-claudeworth"}})
    (and (= "lord-claudeworth" agent-id) (:final? conversation))
    (if (some #{"send-for"} used)
      {:tool :end-conversation}
      {:tool :send-for :args {:id "contact-1" :about "tune the drawing-room piano"}})
    :else {:tool :reply :args {:text "Go on."}}))

(deftest a-caller-recommended-and-sent-for-in-a-conversation
  (let [run (start-with (assoc cw/scenario :mock-conversation tuner-talk) tuner-talk-day)]
    (try
      (until! run 2)
      (let [world (e/world run)
            evs (e/events run)
            started (:id (first (of-type run :conversation/started)))
            ended (:id (first (of-type run :conversation/ended)))
            [recommended] (of-type run :contacts/recommended)
            [sent] (of-type run :contacts/sent-for)]
        (is (< started (:id recommended) (:id sent) ended) "both on lines of the conversation")
        (is (= "piano tuner" (:line (contacts/contact world "contact-1"))))
        (is (= {:host "lord-claudeworth" :by :sent-for :stay "tune the drawing-room piano" :status :scheduled}
               (select-keys (visits/visit world "visit-1") [:host :by :stay :status])))
        (is (some #(and (= :agent/passed (:type %)) (= "lord-claudeworth" (:actor %))) evs)
            "the earl's turn goes on after the conversation")
        (is (empty? (of-type run :action/forfeited))))
      (replay! run)
      (finally (e/stop-run run)))))

(deftest impossible-recommends-nobody
  (let [run (start-with (with-judge cw/scenario (fn [_] {:tool :recommendation :args {:impossible "There are no telephones here yet."}}))
                        {"hale" {"20:00" [[:travel {:to "post-office"}]]
                                 "20:30" [[:recommend {:wanted "a telephone engineer" :for "lord-claudeworth"}] [:pass {}]]}})]
    (try
      (until! run 2)
      (is (empty? (contacts/contacts (e/world run))))
      (is (= ["No recommendation was made: There are no telephones here yet."] (replies-to run "hale" "recommend")))
      (finally (e/stop-run run)))))

(deftest the-judge-sees-contacts-only-by-name-line-and-home
  (let [run (start-with cw/scenario tuner-day)]
    (try
      (until! run 2)
      (let [world (e/world run)
            {:keys [ctx check]} (judge/request world "lord-claudeworth" {:wanted "piano tuner" :recent []})
            listed (first (:contacts ctx))
            tuner (:agent-id (contacts/contact world "contact-1"))
            placed (w/apply-event world {:type :people/post-changed :data {:id tuner :position :footman :household :household}})]
        (is (= #{:id :name :line :based :known-as} (set (keys listed))))
        (is (= [] (judge/contacts-listed placed)) "a contact who now holds a post isn't listed")
        (is (= {:existing "contact-1"} (:verdict (check {:tool-calls [{:id "1" :name "recommendation" :arguments {:existing "contact-1"}}]}))))
        (is (= "existing must be the id of someone listed in local or contacts"
               (:error (check {:tool-calls [{:id "1" :name "recommendation" :arguments {:existing "hale"}}]}))))
        (is (some? (:error (check {:tool-calls [{:id "1" :name "recommendation" :arguments {:existing "contact-1" :impossible "x"}}]})))))
      (finally (e/stop-run run)))))
