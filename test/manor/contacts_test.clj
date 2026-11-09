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
            [manor.mechanics.physical :as physical]
            [manor.mechanics.space :as space]
            [manor.mechanics.visits :as visits]
            [manor.mechanics.works :as works]
            [manor.mechanics.works.judge :as works-judge]
            [manor.narrate :as n]
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

(deftest a-piano-tuner-from-elsewhere-is-recommended-sent-for-and-stays-until-he-takes-leave
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
          (is (= {:about "a piano tuner for the drawing-room piano" :sent-for-by "Lord Claudeworth"}
                 (select-keys (get-in (prompt/agent-view (:scenario run) world id) [:you :visit]) [:about :sent-for-by]))))
        (testing "his situation, from situations/sent-for.md, says who sent for him, what about and his home"
          (let [situation (:situation (w/agent world id))]
            (is (str/includes? situation "Lord Claudeworth has sent for you"))
            (is (str/includes? situation "about this: a piano tuner for the drawing-room piano"))
            (is (str/includes? situation ", of Ely."))
            (is (str/includes? situation "`take-leave`"))))
        (testing "the arrival, seen by the earl, says what he came about"
          (let [arrived (first (filter #(= id (get-in % [:data :guest])) (of-type run :visits/arrived)))]
            (is (contains? (:visible-to arrived) "lord-claudeworth"))
            (is (str/includes? (n/describe arrived (n/names world))
                               "sent for by Lord Claudeworth about this: a piano tuner for the drawing-room piano")))))
      (until! run 13)
      (let [world (e/world run)
            id (:agent-id (contacts/contact world "contact-1"))]
        (testing "hours later with nothing to do, he's still here: nothing sends him home"
          (is (= :active (:status (w/agent world id))))
          (is (= :here (:status (visits/visit world "visit-1")))))
        (testing "take-leave sends him home and ends the visit"
          (is (nil? (check run world id :take-leave {})))
          (e/emit! run ((:effect (action run :take-leave)) world id {}))
          (is (= :away (:status (w/agent (e/world run) id))))
          (is (= :ended (:status (visits/visit (e/world run) "visit-1"))))))
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
      (is (= "the person asking must be here"
             (check run world "lord-claudeworth" :recommend {:position "cook" :wanted "a sweep" :for "hale"}))
          "position is no argument of recommend's, and is dropped")
      (is (str/includes? (check run world "lord-claudeworth" :recommend {:position "cook" :for "hale"}) "wanted"))
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

;; ---------------------------------------------------------------------------
;; Engaging someone sent for (phase 1.2b)

(def footman-day
  "Hale recommends a footman to the earl at the post office; the earl sends
  for him and goes home to meet him at the carriage sweep."
  {"hale" {"20:00" [[:travel {:to "post-office"}]]
           "20:30" [[:recommend {:wanted "a footman wanting a place" :for "lord-claudeworth"}] [:pass {}]]}
   "lord-claudeworth" {"20:00" [[:pass {}]]
                       "20:30" [[:pass {}]]
                       "21:00" [[:send-for {:id "contact-1"}]]
                       "21:30" [[:travel {:to "carriage-sweep"}]]}})

(defn- act
  "`world` after `actor` does `tool` with `args`, checked as the engine
  checks it; throws with the reason if refused."
  [run world actor tool args]
  (let [spec (action run tool)]
    (when-let [r (check run world actor tool args)] (throw (ex-info r {:tool tool})))
    (reduce w/apply-event world (a/effect-events spec world actor (:args (a/decode-args spec args))))))

(defn- act!
  "As `act`, emitted on the run."
  [run actor tool args]
  (let [spec (action run tool)
        world (e/world run)]
    (when-let [r (check run world actor tool args)] (throw (ex-info r {:tool tool})))
    (e/emit! run (a/effect-events spec world actor (:args (a/decode-args spec args))))))

(def only-sent-for "only someone sent for, who holds no post, can be offered a place or sent away")

(deftest someone-sent-for-is-engaged-accepts-and-is-one-of-the-household
  (let [run (start-with cw/scenario footman-day)]
    (try
      (until! run 7)
      (let [world (e/world run)
            id (:agent-id (contacts/contact world "contact-1"))
            earl "lord-claudeworth"]
        (is (= "carriage-sweep" (space/location-of world id) (space/location-of world earl)))
        (testing "engage is offered and checks as for a candidate, refusing established agents"
          (is ((:available? (action run :engage)) world earl))
          (is (nil? (check run world earl :engage {:who id :position "footman" :bedroom "blue-room"})))
          (is (= "use a position id from the catalogue" (check run world earl :engage {:who id :position "caller"})))
          (is (str/starts-with? (str (check run world earl :engage {:who id :position "footman" :bedroom "post-office"})) "bedroom must"))
          (is (= only-sent-for (check run world earl :engage {:who "carter" :position "footman"})) "a villager")
          (is (= only-sent-for (check run world earl :engage {:who "hale" :position "footman"})) "one of the household")
          (is (= only-sent-for (check run world earl :engage {:who "captain-hart" :position "footman"})) "an invited guest")
          (is (= "you cannot offer yourself a place" (check run world id :engage {:who id :position "footman"})))
          (is (str/ends-with? (check run world "hale" :engage {:who id :position "footman"}) "must be present")))
        (testing "engage on a speaking turn is the turn's, for the contact too"
          (let [line (into {} (map (juxt :name identity))
                           ((:speaking-actions (:scenario run)) world earl {:id "c" :initiator earl :members [earl id]}))]
            (is (nil? (a/check (:engage line) world earl {:who id :position "footman"})))
            (is (= "nobody has offered you a place" (a/check (:accept-place line) world id {})))))
        (testing "a contact declines; the offer closes and he stays on his visit"
          (let [offered (act run world earl :engage {:who id :position "footman"})
                declined (act run offered id :decline-place {:reason "Too little."})]
            (is (nil? (get-in declined [:state :place-offers id])))
            (is (= :here (:status (visits/visit declined "visit-1"))))
            (is (= "nobody has offered you a place" (check run declined id :accept-place {}))))))
      (act! run "lord-claudeworth" :engage {:who (:agent-id (contacts/contact (e/world run) "contact-1"))
                                            :position "footman" :terms "Twenty-four pounds a year." :bedroom "blue-room"})
      (let [id (:agent-id (contacts/contact (e/world run) "contact-1"))]
        (act! run id :accept-place {:conditions "Sundays off."})
        (let [world (e/world run)]
          (testing "accepting gives the post and bedroom, closes the offer and ends the visit"
            (is (= {:position :footman :household "household" :terms "Twenty-four pounds a year." :conditions "Sundays off."
                    :employer "lord-claudeworth"}
                   (select-keys (get-in world [:state :posts id]) [:position :household :terms :conditions :employer])))
            (is (= :footman (:role (w/agent world id))))
            (is (= "blue-room" (get-in world [:state :bedrooms id])))
            (is (nil? (get-in world [:state :place-offers id])))
            (is (= :ended (:status (visits/visit world "visit-1")))))
          (testing "he's no longer a visitor: no take-leave, not kept awake as one, not sent for"
            (is (not (visits/from-elsewhere? world id)))
            (is (not ((:available? (action run :take-leave)) world id)))
            (is (not (visits/keeps-awake? world id)))
            (is (nil? (get-in (prompt/agent-view (:scenario run) world id) [:you :visit])))
            (is (nil? (:sent-for (first (:contacts (prompt/agent-view (:scenario run) world "lord-claudeworth")))))))
          (testing "nor can he be engaged or sent away again"
            (is (= only-sent-for (check run world "lord-claudeworth" :engage {:who id :position "cook"})))
            (is (= only-sent-for (check run world "lord-claudeworth" :send-away {:who id}))))))
      (is (empty? (of-type run :action/forfeited)))
      (replay! run)
      (finally (e/stop-run run)))))

(deftest someone-sent-for-a-place-is-given-a-work-or-sent-away
  (let [run (start-with cw/scenario footman-day)]
    (try
      (until! run 7)
      (let [world (e/world run)
            id (:agent-id (contacts/contact world "contact-1"))
            earl "lord-claudeworth"]
        (testing "a contact recommended for a place can be ordered a work and accept it"
          (let [planned (w/apply-event world (works/planned world earl {:kind "install" :request "a range"}
                                                             (:args (works-judge/mock-judge {:input {:kind "install" :request "a range"}}))))
                ordered (act run planned earl :order-work {:id "work-1" :worker id :price "£3"})
                accepted (act run ordered id :accept-work {:id "work-1"})]
            (is (= :accepted (:status (works/work-order accepted "work-1"))))))
        (testing "send-away sends him home, ending his visit"
          (is ((:available? (action run :send-away)) world earl))
          (is (= only-sent-for (check run world earl :send-away {:who "hale"})))
          (let [gone (act run world earl :send-away {:who id})
                [departed] (filter #(= :people/departed (:type %))
                                   (a/effect-events (action run :send-away) world earl {:who id}))]
            (is (= :away (:status (w/agent gone id))))
            (is (= :ended (:status (visits/visit gone "visit-1"))))
            (is (= {:reason "sent away" :towards "Ely"} (select-keys (:data departed) [:reason :towards])))))
        (testing "but not while he's held in place"
          (with-redefs [physical/held-in-place? (fn [_ who] (= id who))]
            (is (str/includes? (str (check run world earl :send-away {:who id})) "cannot leave")))))
      (finally (e/stop-run run)))))
