(ns manor.hiring-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [manor.actions :as a]
            [manor.engine :as e]
            [manor.test-support :as support]
            [manor.io.llm :as llm]
            [manor.io.store :as store]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.calendar :as calendar]
            [manor.mechanics.conversation :as conversation]
            [manor.mechanics.hiring :as h]
            [manor.mechanics.items.actions :as item-actions]
            [manor.mechanics.items.judge :as item-judge]
            [manor.mechanics.items.work :as item-work]
            [manor.mechanics.people :as people]
            [manor.mechanics.positions :as positions]
            [manor.mechanics.space :as space]
            [manor.narrate :as n]
            [manor.prompt :as prompt]
            [manor.ruleset :as ruleset]
            [manor.ruleset.actions :as ruleset-actions]
            [manor.world :as w]))

(use-fixtures :once support/with-runs)

(defn first-person [world] (first (sort-by :created (vals (h/pool world)))))
(defn action [tool] (first (filter #(= tool (:name %)) h/actions)))
(defn effect [world actor tool args]
  (let [spec (action tool)
        decoded (a/decode-args spec args)]
    (is (nil? (:error decoded)))
    (is (nil? (a/check spec world actor (:args decoded))))
    (reduce #(w/apply-stamped %1 (w/stamp %1 (assoc %2 :actor actor)))
            world ((:effect spec) world actor (:args decoded)))))

(defn policy [{:keys [world agent-id used]}]
  (let [p (first-person world)]
    (cond
      (and (= agent-id "recommender") (= 1 (:round world)) (not (some #{"recommend"} used)))
      {:tool :recommend :args {:position "housemaid" :for "employer"}}
      (and (= agent-id "employer") (= 2 (:round world)))
      {:tool :send-for :args {:candidate (:id p)}}
      :else {:tool :pass})))

(defn scenario []
  (h/install
   {:name :hiring-fixture :roles {} :agent-brief (fn [_ _] h/rules)
    :on-spawn positions/starting-events
    :init (fn [_]
            {:agents [{:name "Employer" :role :mistress :location "gate"}
                      {:name "Recommender" :role :shopkeeper :location "gate"}
                      {:name "Bystander" :role :none :location "gate"}]
             :space {:locations [{:id "gate" :name "Gate"} {:id "room" :name "Bedroom" :kind :bedroom}]
                     :routes [["gate" "room" 0]]}
             :state {:positions (positions/read-catalogue "settings/england-1904/positions.edn") :setting "England, 1904."
                     :meeting-place "gate"}})
    :actions h/actions :adjudicators h/adjudicators :bridges h/bridges
    :offline-stubs h/offline-stubs :mcp-servers people/mcp-servers
    :reference (fn [world _] (h/reference world)) :observe h/observe :mock-policy policy}))

(defn start
  ([] (start {} {}))
  ([changes opts]
   (with-redefs [e/load-scenario (constantly (merge (scenario) changes))]
     (e/start-run :hiring-fixture (merge {:test? true :llm :mock :offline? true :gm :off
                                          :max-cost 0.20 :run-id (str (gensym "hiring-"))} opts)))))
(defn rounds! [run n]
  (e/auto! run n)
  (is (= :idle (e/wait-idle run 60000))))
(defn replay! [run]
  (is (= (e/world run) (w/replay (store/read-events (:dir run))))))

(deftest appearance-is-structurally-private
  (let [record {:name "Secret Name" :age 21 :sex "female" :position "housemaid"
                :persona "private-persona" :manner "private-manner" :origin "private-origin"
                :temperament [{:axis "openness" :level "high" :text "private-temperament"}]
                :faculties [{:faculty "Sight" :size "small" :text "private-description"}
                            {:faculty "Acquisitiveness" :size "large" :text "private-faculty"}]
                :circumstances [{:label "private-circumstance"}]}
        req (people/appearance-request record)
        data (json/read-value (get-in req [:messages 1 :content]) json/keyword-keys-object-mapper)]
    (is (= {:age 21 :sex "female" :position "housemaid" :small-senses ["Sight"]} data))
    (is (= data (get-in req [:ctx :record])))
    (is (not (str/includes? (pr-str (dissoc req :check)) "private-")))
    (doseq [bad ["" "  " (apply str (repeat 601 "x"))]]
      (is (:error ((:check req) {:tool-calls [{:id "a" :name "appearance" :arguments {:appearance bad}}]}))))
    (is (= {:appearance "Plain clothes."}
           (:verdict ((:check req) {:tool-calls [{:id "a" :name "appearance" :arguments {:appearance "Plain clothes."}}]}))))))

(deftest recommendation-arrival-privacy-and-default-model
  (let [secret "private-candidate-sentinel"
        polish (fn [_] {:tool :polish :args {:persona (str "You keep " secret " to yourself.")
                                             :circumstances [secret]
                                             :manner "You are civil." :appearance secret}})
        run (start {:adjudicators (assoc-in h/adjudicators [:people/polish :mock] polish)}
                   {:model "default-test-model" :judge-model "other-judge-model"})]
    (try
      (rounds! run 1)
      (let [world (e/world run) p (first-person world)]
        (is (= :pooled (:status p)))
        (is (= [secret] (get-in p [:record :circumstances])))
        (is (= "lodgings outside the mapped area" (:candidate-home p)))
        (is (= (:candidate-home p) (get-in p [:record :candidate-home])))
        (is (not (contains? p :lives)))
        (is (= (:candidate-home p) (get-in (h/observe world "employer") [:candidates 0 :candidate-home])))
        (let [events (e/events run)
              generated (first (filter #(= :people/generate (get-in % [:data :bridge])) events))
              reputation (first (filter #(= :people/reputation (get-in % [:data :bridge])) events))]
          (is (true? (get-in generated [:data :args :candidate])))
          (is (not (contains? (get-in generated [:data :result :people 0]) :circumstances)))
          (is (= (:record p) (get-in reputation [:data :args :person]))))
        (is (nil? (:agent-id p)))
        (is (= #{"recommender" "employer"} (:known-to p)))
        (is (seq (get-in world [:state :reputations "recommender" (:id p)])))
        (is (empty? (:reputations (h/observe world "employer"))))
        (is (empty? (:candidates (h/observe world "bystander"))))
        (is (not (str/includes? (:appearance p) secret)))
        (doseq [id ["employer" "bystander" "recommender"]]
          (is (not (str/includes? (pr-str (prompt/agent-view (:scenario run) world id)) secret)))
          (is (not (str/includes? (pr-str (filter #(w/visible-to? % id) (e/events run))) secret))))
        (let [calls (filter #(str/starts-with? (:agent %) "adjudicator/people") (store/read-llm (:dir run)))]
          (is (= 2 (count calls)))
          (is (every? #(= "default-test-model" (:model %)) calls)))
        (is (str/includes? (h/send-for-reason world "bystander" {:candidate (:id p)}) "no current")))
      (rounds! run 2)
      (let [world (e/world run) p (first-person world) id (:agent-id p)]
        (is (= :here (:status p)))
        (is (= :none (:role (w/agent world id))))
        (is (nil? (:model (w/agent world id))))
        (is (= "gate" (space/location-of world id)))
        (is (str/includes? (:persona (w/agent world id)) secret))
        (is (= [secret] (:circumstances (w/agent world id))))
        (is (= (:appearance p) (:appearance (w/agent world id))))
        (doseq [viewer ["employer" "bystander" "recommender"]]
          (is (not (str/includes? (pr-str (filter #(w/visible-to? % viewer) (e/events run))) secret)))))
      (replay! run)
      (finally (e/stop-run run)))))

(deftest self-recommendation-is-rejected-without-pausing
  (let [run (start {:mock-policy (fn [{:keys [world agent-id used] :as ctx}]
                                   (if (and (= "recommender" agent-id) (= 2 (:round world))
                                            (not (some #{"recommend"} used)))
                                     {:tool :recommend :args {:position "housemaid" :for agent-id}}
                                     (policy ctx)))} {})]
    (try
      (rounds! run 1)
      (let [world (e/world run)
            args {:position "housemaid" :for "recommender"}
            result {:candidate (first-person world) :new? false :reputation {}}
            reason (h/recommend-reason world "recommender" args)]
        (is (= "ask someone else here to recommend to you, not yourself" reason))
        (is (nil? (h/recommendation-events world "recommender" args result)))
        (testing "event construction remains duplicate-safe even if validation is bypassed"
          (with-redefs [h/recommend-reason (constantly nil)]
            (let [events (h/recommendation-events world "recommender" args result)
                  recommended (second events)]
              (is (= [:people/reputation-known :people/recommended] (mapv :type events)))
              (is (every? #(= #{"recommender"} (:visible-to %)) events))
              (is (= #{"recommender"} (get-in recommended [:data :knowers])))))))
      (rounds! run 1)
      (let [events (e/events run)
            calls (filter #(and (= "recommender" (:agent %)) (= 2 (:round %)))
                          (store/read-llm (:dir run)))]
        (is (= "error: ask someone else here to recommend to you, not yourself"
               (get-in (first calls) [:replies 0 :content])))
        (is (not-any? #(and (= 2 (:round %)) (= :bridge/called (:type %))) events))
        (is (not-any? #(#{:action/forfeited :run/pause-requested} (:type %)) events)))
      (replay! run)
      (finally (e/stop-run run)))))

(deftest reputation-selects-written-circumstances-and-basic-readings
  (let [stubs (assoc h/offline-stubs :people/reputation
                     (fn [{:keys [person knower]} _]
                       {:person (:name person) :knower knower :relation "neighbour"
                        :acquaintance "They know one another."
                        :knows {:temperament [(first (:temperament person))]
                                :faculties [(first (:faculties person))]
                                :circumstances [(first (:circumstances person))]}}))
        run (start {} {:bridge-stubs stubs})]
    (try
      (rounds! run 1)
      (let [world (e/world run) p (first-person world)
            r (get-in world [:state :reputations "recommender" (:id p)])
            calls (filter #(= "recommender" (:agent %)) (store/read-llm (:dir run)))
            reply (get-in (first calls) [:replies 0 :content])]
        (is (= [(first (get-in p [:record :circumstances]))] (get-in r [:knows :circumstances])))
        (is (= #{:axis :level :measures} (set (keys (first (get-in r [:knows :temperament]))))))
        (is (= #{:faculty :size :means} (set (keys (first (get-in r [:knows :faculties]))))))
        (is (str/includes? reply (first (get-in r [:knows :circumstances]))))
        (is (str/includes? reply (get-in r [:knows :temperament 0 :level])))
        (is (empty? (:reputations (h/observe world "employer"))))
        (is (empty? (:reputations (h/observe world "bystander")))))
      (rounds! run 2)
      (replay! run)
      (finally (e/stop-run run)))))

(deftest query-refresh-preserves-turn-limits
  (let [recommend {:tool :recommend :args {:position "housemaid" :for "employer"}}
        run (start {:mock-policy (fn [{:keys [world agent-id step]}]
                                   (if (and (= "recommender" agent-id) (= 1 (:round world)))
                                     (get [recommend
                                           {:tool :say :args {:text "I know someone."}}
                                           {:tool :say :args {:text "Another word."}}
                                           recommend recommend
                                           {:tool :send-for :args {:candidate (:id (first-person world))}}]
                                          step {:tool :pass})
                                     {:tool :pass}))}
                   {:max-steps 6 :max-queries 2})]
    (try
      (rounds! run 2)
      (let [events (e/events run)
            calls (filterv #(and (= "recommender" (:agent %)) (= 1 (:round %)))
                           (store/read-llm (:dir run)))
            replies (mapv #(get-in % [:replies 0 :content]) calls)]
        (is (= 6 (count calls)))
        (is (= 2 (count (filter #(= :people/recommended (:type %)) events))))
        (is (= 1 (count (filter #(= :agent/said (:type %)) events))))
        (is (= "rejected: you can use say or whisper only once per turn; take your turn-ending action now, and use them again next turn"
               (replies 2)))
        (is (= "error: query limit reached for this turn (2); take your action now, and query again next turn"
               (replies 4)))
        (is (= "done" (replies 5)) "send-for became callable after the query")
        (is (= :here (:status (first-person (e/world run)))))
        (is (= 1 (:round (first (filter #(= :people/sent-for (:type %)) events)))))
        (is (some #(and (= "user" (:role %)) (str/includes? (:content %) "`send-for`"))
                  (:replies (calls 1))) "reminders name the refreshed turn-ending tools")
        (is (not-any? #(#{:action/forfeited :run/pause-requested} (:type %)) events)))
      (replay! run)
      (finally (e/stop-run run)))))

(deftest walk-interview-offer-and-accept-in-one-round
  (let [hire (fn [{:keys [world agent-id used] :as ctx}]
               (let [p (first-person world)
                     id (:agent-id p)]
                 (cond
                   (and (= "employer" agent-id) (= 2 (:round world)) (not (some #{"walk"} used)))
                   {:tool :walk :args {:to "room"}}
                   (and (= "employer" agent-id) (= 3 (:round world)))
                   (cond
                     (not (some #{"walk"} used)) {:tool :walk :args {:to "gate"}}
                     (not (some #{"converse"} used)) {:tool :converse :args {:with [id] :text "Shall we agree the terms?"}}
                     (not (some #{"engage"} used)) {:tool :engage :args {:who id :position "housemaid" :terms "Twelve pounds." :bedroom "room"}}
                     :else {:tool :pass})
                   (and (= id agent-id) (get-in world [:state :place-offers id]))
                   {:tool :accept-place :args {:conditions "Sunday afternoons free."}}
                   :else (policy ctx))))
        run (start {:actions (conj h/actions (first (filter #(= :walk (:name %)) ruleset-actions/actions)))
                    :mock-policy hire} {})]
    (try
      (rounds! run 3)
      (let [world (e/world run) p (first-person world) id (:agent-id p)
            interview (filterv #(and (= "employer" (:agent %)) (= 3 (:round %)) (nil? (:conversation %)))
                               (store/read-llm (:dir run)))
            tools (mapv (comp set :tools) interview)
            events (e/events run)
            milestones (filter #(or (= :people/offered (:type %))
                                    (and (= :people/post-changed (:type %)) (= id (get-in % [:data :id]))
                                         (= :housemaid (get-in % [:data :position])))
                                    (and (= :people/candidate-updated (:type %))
                                         (= :engaged (get-in % [:data :changes :status])))) events)]
        (is (= 4 (count interview)))
        (is (not (contains? (first tools) "engage")) "the candidate is elsewhere at turn start")
        (is (contains? (second tools) "engage") "walking makes engage available")
        (is (= (:tool-digests (second interview)) (:tool-digests (nth interview 2 nil)))
            "an interview with no availability change keeps tool schemas and order identical")
        (is (= (:tool-digests (first interview))
               (take (count (:tools (first interview))) (:tool-digests (second interview))))
            "newly available tools leave the existing cacheable prefix unchanged")
        (is (= ["walk" "converse" "engage" "pass"]
               (mapv #(get-in % [:response :tool_calls 0 :function :name])
                     (filter #(and (= "employer" (:agent %)) (= 3 (:round %)) (nil? (:conversation %)))
                             (store/read-llm (:dir run))))))
        (is (= [:people/offered :people/post-changed :people/candidate-updated] (mapv :type milestones)))
        (is (every? #(= 3 (:round %)) milestones))
        (is (= ["accept-place" "pass"]
               (mapv #(get-in % [:response :tool_calls 0 :function :name])
                     (filter #(and (= id (:agent %)) (= 3 (:round %)) (nil? (:conversation %)))
                             (store/read-llm (:dir run))))))
        (is (= :engaged (:status p)))
        (is (= :housemaid (:role (w/agent world id))))
        (is (= "Twelve pounds." (get-in world [:state :posts id :terms])))
        (is (= "Sunday afternoons free." (get-in world [:state :posts id :conditions])))
        (is (= "room" (get-in world [:state :bedrooms id])))
        (is (empty? (h/tick (:scenario run) (assoc world :round 99))))
        (is (not-any? #(#{:action/forfeited :run/pause-requested} (:type %)) events))
        (is (not-any? #(str/starts-with? (:content %) "error:")
                      (mapcat :replies (store/read-llm (:dir run))))))
      (replay! run)
      (finally (e/stop-run run)))))

(deftest declining-removes-stale-offer-tools-within-the-turn
  (let [run (start {:mock-policy
                    (fn [{:keys [world agent-id used] :as ctx}]
                      (let [id (:agent-id (first-person world))]
                        (cond
                          (and (= "employer" agent-id) (= 3 (:round world)) (not (some #{"engage"} used)))
                          {:tool :engage :args {:who id :position "housemaid"}}
                          (and (= id agent-id) (not (some #{"decline-place"} used)))
                          {:tool :decline-place :args {}}
                          :else (policy ctx))))} {})]
    (try
      (rounds! run 3)
      (let [world (e/world run) id (:agent-id (first-person world))
            calls (filterv #(and (= id (:agent %)) (nil? (:conversation %))) (store/read-llm (:dir run)))]
        (is (= 2 (count calls)))
        (is (every? (set (:tools (first calls))) ["accept-place" "decline-place"]))
        (is (not-any? (set (:tools (second calls))) ["accept-place" "decline-place"]))
        (is (nil? (get-in world [:state :place-offers id])))
        (is (= :here (:status (first-person world))))
        (is (not-any? #(#{:action/forfeited :run/pause-requested} (:type %)) (e/events run))))
      (replay! run)
      (finally (e/stop-run run)))))

(deftest offers-haggling-bedrooms-and-conditions
  (let [run (start)]
    (try
      (rounds! run 3)
      (let [world (e/world run) p (first-person world) id (:agent-id p)
            offered (effect world "employer" :engage {:who id :position "housemaid" :terms "Ten pounds."})
            declined (effect offered id :decline-place {:reason "Twelve, please."})
            replaced (-> declined
                         (effect "bystander" :engage {:who id :position "footman" :terms "Twelve pounds." :bedroom "room"})
                         (effect "employer" :engage {:who id :position "housemaid" :terms "Fourteen pounds." :bedroom "room"}))
            accepted (effect replaced id :accept-place {:conditions "Sunday afternoons free."})
            post (get-in accepted [:state :posts id])]
        (is (= :none (:role (w/agent offered id))))
        (is (nil? (get-in declined [:state :place-offers id])))
        (is (= "employer" (:employer post)))
        (is (= "Fourteen pounds." (:terms post)))
        (is (= "Sunday afternoons free." (:conditions post)))
        (is (= "household" (:household post)))
        (is (= "room" (get-in accepted [:state :bedrooms id])))
        (is (= :housemaid (:role (w/agent accepted id))))
        (is (= :engaged (:status (h/candidate accepted (:id p)))))
        (is (= post (get-in (h/observe accepted "employer") [:posts id])))
        (is (= post (get-in (h/observe accepted id) [:posts id])))
        (is (nil? (get-in (h/observe accepted "bystander") [:posts id])))
        (is (a/check (action :accept-place) accepted id {}))
        (is (a/check (action :send-away) accepted "employer" {:who id}))
        (is (empty? (h/tick (:scenario run) (assoc accepted :round 99))))
        (is (h/engage-reason world "employer" {:who id :position "nonexistent"}))
        (is (h/engage-reason (assoc-in world [:state :positions :gm] {:title "GM"}) "employer" {:who id :position "gm"}))
        (is (h/recommend-reason (assoc-in world [:state :positions :gm] {:title "GM"}) "employer" {:position "gm" :for "employer"}))
        (is (h/engage-reason world "employer" {:who id :position "housemaid" :bedroom "gate"})))
      (finally (e/stop-run run)))))

(deftest departure-return-memory-and-conversation-grace
  (let [run (start)]
    (try
      (rounds! run 3)
      (let [world (e/world run) p (first-person world) id (:agent-id p)
            noted (w/apply-event world {:type :agent/noted :actor id :round 3 :data {:text "Remember the first visit."}})
            away (effect noted "employer" :send-away {:who id})
            summoned (effect away "employer" :send-for {:candidate (:id p)})
            returned (reduce w/apply-event (assoc summoned :round 4) (h/tick (:scenario run) (assoc summoned :round 4)))
            talking (w/apply-event returned {:type :conversation/started :actor "employer" :round 6
                                             :data {:members ["employer" id]}})]
        (is (= :away (:status (w/agent away id))))
        (is (nil? (space/location-of away id)))
        (is (not (contains? (space/present away "gate") id)))
        (is (= (:notes (w/agent noted id)) (:notes (w/agent returned id))))
        (is (= :active (:status (w/agent returned id))))
        (is (= 4 (count (w/active-agents returned))))
        (is (empty? (h/tick (:scenario run) (assoc talking :round 29))))
        (is (some #(= :people/departed (:type %)) (h/tick (:scenario run) (assoc talking :round 30))))
        (let [event (first (h/departure returned id "takes leave"))]
          (is (= {:id id :reason "takes leave" :towards (:candidate-home p) :off-map? true} (:data event))))
        (testing "legacy records never use their ungrounded lives text as a destination"
          (let [legacy (-> returned
                           (update-in [:state :pool (:id p)] dissoc :candidate-home)
                           (assoc-in [:state :pool (:id p) :lives] "invented cottage, attic-landing"))]
            (is (= "home" (get-in (first (h/departure legacy id "takes leave")) [:data :towards])))))
        (is (= :away (:status (w/agent (effect returned id :take-leave {}) id))))
        (let [retired (w/apply-event away {:type :agent/retired :data {:id id}})]
          (is (h/send-for-reason retired "employer" {:candidate (:id p)}))
          (is (= :retired (:status (w/agent (w/apply-event retired {:type :people/returned :data {:id id :at "gate"}}) id))))))
      (rounds! run 24)
      (let [world (e/world run) id (:agent-id (first-person world))
            departure-round (:round (first (filter #(= :people/departed (:type %)) (e/events run))))]
        (is (= :away (:status (w/agent world id))))
        (is (= 27 departure-round))
        (is (not-any? #(and (= id (:actor %)) (> (:round %) departure-round) (= :llm/usage (:type %))) (e/events run))))
      (replay! run)
      (finally (e/stop-run run)))))

(deftest held-candidates-stay-until-they-can-move
  (doseq [[faculty released-level] [[:movement :normal]
                                   [:movement :impeded]
                                   [:consciousness :normal]]]
    (testing (str faculty " disabled, then " released-level)
      (let [run (start)]
        (try
          (rounds! run 3)
          (let [id (:agent-id (first-person (e/world run)))
                name (:name (w/agent (e/world run) id))
                reason (str name " cannot leave: "
                            (if (= :consciousness faculty)
                              "they are unconscious; revive them first"
                              "their movement is disabled; restore their movement first"))]
            @(e/afflict! run id {:faculty faculty :level :disabled})
            (let [world (e/world run)]
              (is (= reason (a/check (action :take-leave) world id {})))
              (is (= reason (a/check (action :send-away) world "employer" {:who id}))))
            (rounds! run 24)
            (let [world (e/world run)]
              (is (= :here (:status (first-person world))))
              (is (w/active? world id))
              (is (= "gate" (space/location-of world id)))
              (is (not-any? #(= :people/departed (:type %)) (e/events run))))
            (replay! run)
            @(e/afflict! run id {:faculty faculty :level released-level})
            ;; Send-away tests the departing candidate, not the employer's movement.
            @(e/afflict! run "employer" {:faculty :movement :level :disabled})
            (let [world (e/world run)]
              (is (nil? (a/check (action :take-leave) world id {})))
              (is (nil? (a/check (action :send-away) world "employer" {:who id}))))
            (rounds! run 1)
            (let [world (e/world run)]
              (is (= :away (:status (first-person world))))
              (is (= :away (:status (w/agent world id))))
              (is (nil? (space/location-of world id)))
              (is (= [28] (map :round (filter #(= :people/departed (:type %)) (e/events run)))))))
          (replay! run)
          (finally (e/stop-run run)))))))

(deftest candidate-grace-is-a-full-game-day
  (let [run (start)]
    (try
      (rounds! run 3)
      (let [world (e/world run) p (first-person world) id (:agent-id p)]
        (doseq [minutes [17 30 60 1440]]
          (let [world (assoc world :clock {:start "1904-10-15T22:30" :minutes-per-round minutes})
                wait (long (Math/ceil (/ 1440 minutes)))
                due (+ (:arrived p) wait)
                tick #(h/tick (:scenario run) (assoc world :round %))]
            (is (= wait (h/candidate-wait-rounds world)))
            (is (empty? (tick (dec due))))
            (is (= :people/departed (:type (first (tick due)))))))
        (let [world (assoc world :clock {:start "1904-10-15T22:30" :minutes-per-round 30})
              talking (w/apply-event world {:type :conversation/started :actor "employer" :round 10
                                             :data {:members ["employer" id]}})]
          (is (empty? (h/tick (:scenario run) (assoc world :round 6))) "crossing midnight does not end the grace")
          (is (empty? (h/tick (:scenario run) (assoc talking :round 57))))
          (is (= :people/departed (:type (first (h/tick (:scenario run) (assoc talking :round 58))))))))
      (finally (e/stop-run run)))))

(deftest candidate-can-wait-elsewhere-and-be-hired-later
  (let [init (:init (scenario))
        hire (fn [{:keys [world agent-id used] :as ctx}]
               (let [id (:agent-id (first-person world))]
                 (cond
                   (and (= id agent-id) (= 4 (:round world)) (not (some #{"walk"} used)))
                   {:tool :walk :args {:to "room"}}
                   (and (= "employer" agent-id) (= 10 (:round world)))
                   (cond
                     (not (some #{"walk"} used)) {:tool :walk :args {:to "room"}}
                     (not (some #{"converse"} used)) {:tool :converse :args {:with [id] :text "At last, shall we settle the terms?"}}
                     (not (some #{"engage"} used)) {:tool :engage :args {:who id :position "housemaid"}}
                     :else {:tool :pass})
                   (and (= id agent-id) (get-in world [:state :place-offers id]))
                   {:tool :accept-place :args {}}
                   :else (policy ctx))))
        run (start {:init (fn [opts] (assoc (init opts) :clock {:start "1904-10-15T22:30" :minutes-per-round 30}))
                    :actions (conj h/actions (first (filter #(= :walk (:name %)) ruleset-actions/actions)))
                    :mock-policy hire} {})]
    (try
      (rounds! run 9)
      (let [world (e/world run) id (:agent-id (first-person world))]
        (is (= :here (:status (first-person world))))
        (is (= :active (:status (w/agent world id))))
        (is (= "room" (space/location-of world id))))
      (rounds! run 1)
      (let [world (e/world run) id (:agent-id (first-person world))]
        (is (= :engaged (:status (first-person world))))
        (is (= :housemaid (:role (w/agent world id))))
        (is (empty? (filter #(#{:people/departed :action/forfeited :run/pause-requested} (:type %)) (e/events run)))))
      (replay! run)
      (finally (e/stop-run run)))))

(deftest pool-fifo-protection-and-reuse
  (let [run (start)]
    (try
      (rounds! run 1)
      (let [world (e/world run) p (first-person world)
            full (assoc-in world [:state :pool]
                           (into {} (for [i (range 40)] [(str i) (assoc p :id (str i) :created i
                                                                        :record (assoc (:record p) :name (str "Person " i))
                                                                        :seeks [:footman] :status :pooled)])))
            protected (-> full
                          (assoc-in [:state :pool "0" :status] :sent-for)
                          (assoc-in [:state :pool "1" :status] :here)
                          (assoc-in [:state :pool "2" :status] :engaged))
            generated (assoc p :id "new" :created 50)
            events (h/recommendation-events protected "recommender" {:for "employer" :position "housemaid"}
                                            {:candidate generated :new? true :reputation {}})
            after (reduce w/apply-event protected events)]
        (is (= "3" (:id (h/eviction protected))))
        (is (= 40 (count (h/pool after))))
        (is (nil? (h/candidate after "3")))
        (is (every? #(h/candidate after %) ["0" "1" "2" (str "candidate-" (inc (:seq protected)))]))
        (is (h/send-for-reason after "employer" {:candidate "3"}))
        (is (h/recommend-reason (update-in protected [:state :pool] update-vals #(assoc % :status :engaged))
                                "recommender" {:position "housemaid" :for "employer"}))
        (let [calls (atom [])
              result (h/recommend! world "recommender" {:position "housemaid"}
                                   {:bridge (fn [& args] (swap! calls conj args))
                                    :adjudicate (fn [& args] (swap! calls conj args))})]
          (is (empty? @calls))
          (is (= (:id p) (get-in result [:candidate :id])))))
      (finally (e/stop-run run)))))

(deftest failed-generation-or-polish-leaves-no-partial-person
  (doseq [changes [{:offline-stubs (assoc h/offline-stubs :people/generate (fn [& _] (throw (ex-info "offline failure" {}))))}
                   {:adjudicators (assoc-in h/adjudicators [:people/appearance :mock] (constantly {:tool :appearance :args {:appearance ""}}))}
                   {:adjudicators (assoc-in h/adjudicators [:people/polish :mock] (constantly {:tool :polish :args {:persona ""}}))}]]
    (let [run (start changes {})]
      (try
        (rounds! run 1)
        (is (empty? (h/pool (e/world run))))
        (is (some #(= :adjudication/failed (:type %)) (e/events run)))
        (is (= 3 (count (w/active-agents (e/world run)))))
        (replay! run)
        (finally (e/stop-run run))))))

(deftest composed-query-stops-at-the-run-budget
  (let [calls (atom [])
        mock (llm/mock (fn [ctx]
                         (case (:task ctx)
                           :people/appearance {:tool :appearance :args {:appearance "Plain working clothes."}}
                           (policy ctx))))
        paid-mock (reify llm/LLM
                    (complete [_ request]
                      (swap! calls conj (get-in request [:ctx :task]))
                      (cond-> (llm/complete mock request)
                        (= :people/appearance (get-in request [:ctx :task])) (assoc-in [:usage :cost] 0.10))))
        run (start {} {:llm-impl paid-mock :max-cost 0.05})]
    (try
      (rounds! run 1)
      (is (= 0.10 (w/total-cost (e/world run))))
      (is (some #{:people/appearance} @calls))
      (is (not-any? #{:people/polish} @calls))
      (is (empty? (h/pool (e/world run))))
      (is (= :manual (:mode (e/status run))))
      (replay! run)
      (finally (e/stop-run run)))))

(deftest references-use-only-the-writers-knowledge
  (let [run (start)]
    (try
      (rounds! run 1)
      (let [p (first-person (e/world run))
            world (assoc (e/world run) :clock {:start "1904-10-15T08:00" :minutes-per-round 30})
            spec (h/reference-activity (first (filter #(= :spend-time (:name %)) (item-actions/actions))))
            args {:doing "writing a character" :candidate (:id p) :hours 0.5}
            events ((:effect spec) world "recommender" args)
            job (get-in (first events) [:data])
            request (item-judge/judge-request {} world job {:minutes 30 :luck 50})
            done (item-work/work-done world job {:minutes 30 :luck 50}
                                      {:made [{:name "Written character" :desc "A letter of reference." :aspects {} :held true :consumable false}]})]
        (is (nil? (a/check spec world "recommender" args)))
        (is (a/check spec world "bystander" args))
        (testing "ordinary activities accept an omitted or empty candidate"
          (let [ordinary {:doing "checking the estate ledgers" :hours 0.5}
                expected ((:effect spec) world "recommender" ordinary)]
            (is (not-any? #(contains? (:data %) :reference) expected))
            (doseq [raw [ordinary (assoc ordinary :candidate "")]]
              (let [{:keys [args error]} (a/decode-args spec raw)]
                (is (nil? error))
                (is (nil? (a/check spec world "recommender" args)))
                (is (= expected ((:effect spec) world "recommender" args)))))
            (is (a/check spec world "recommender" (assoc ordinary :candidate "none")))))
        (is (= (:id p) (get-in job [:reference :candidate-id])))
        (is (= #{:candidate-id :name :seeks :reputation} (set (keys (:reference job)))))
        (is (= (get-in world [:state :reputations "recommender" (:id p)]) (get-in job [:reference :reputation])))
        (is (not (str/includes? (pr-str (:messages request)) "sends wages home")))
        (is (= (:id p) (get-in done [:data :made 0 :candidate-id])))
        (is (= "recommender" (get-in done [:data :made 0 :holder]))))
      (finally (e/stop-run run)))))

(deftest pending-arrivals-respect-cap
  (let [run (start)]
    (try
      (rounds! run 2)
      (let [world (assoc-in (e/world run) [:state :hiring-max-agents] 3)]
        (is (empty? (h/tick (:scenario run) (assoc world :round 3))))
        (is (= :sent-for (:status (first-person world))))
        (is (some #(= :people/arrived (:type %))
                  (h/tick (:scenario run) (-> world (assoc :round 3)
                                              (w/apply-event {:type :agent/retired :data {:id "bystander"}}))))))
      (finally (e/stop-run run)))))

(deftest returning-candidates-must-still-be-alive-away-and-mobile
  (let [run (start)]
    (try
      (rounds! run 3)
      (let [world (e/world run) p (first-person world) id (:agent-id p)
            summoned (-> world
                         (effect "employer" :send-away {:who id})
                         (effect "employer" :send-for {:candidate (:id p)}))
            due (assoc summoned :round 4)]
        (doseq [faculty [:movement :consciousness]]
          (let [held (assoc-in due [:agents id :physical faculty] {:level :disabled})]
            (is (empty? (h/tick (:scenario run) held)))
            (is (empty? (:events (h/arrival (:scenario run) held "gate" {:id id}))))))
        (let [impeded (assoc-in due [:agents id :physical :movement] {:level :impeded})
              events (h/tick (:scenario run) impeded)
              arrived (reduce w/apply-event impeded events)]
          (is (some #(= :people/returned (:type %)) events))
          (is (= :active (:status (w/agent arrived id))))
          (is (= 5 (count (:agents arrived))))
          (is (= :here (:status (first-person arrived)))))
        (let [dead (w/apply-event due {:type :person/died :data {:person id :cause "illness"}})]
          (is (empty? (h/tick (:scenario run) dead)))
          (is (= "that person cannot return"
                 (h/send-for-reason (assoc-in dead [:state :pool (:id p) :status] :away) "employer" {:candidate (:id p)})))
          (is (= dead (w/apply-event dead {:type :people/returned :data {:id id :at "gate"}})))
          (is (= dead (w/apply-event dead {:type :people/departed :data {:id id}})))))
      (replay! run)
      (finally (e/stop-run run)))))

(deftest recommending-in-a-conversation
  (testing "anyone may recommend on their line, but only to someone in the conversation"
    (let [run (start)]
      (try
        (let [world (e/world run)
              conv {:members ["employer" "recommender"]}
              recommend (first (ruleset/speaking-actions world "recommender" conv))]
          (is (= [:recommend :engage :accept-place :decline-place] (mapv :name (ruleset/speaking-actions world "recommender" conv))))
          (is (nil? (a/check recommend world "recommender" {:position "housemaid" :for "employer"})))
          (is (= "recommend to someone in this conversation"
                 (a/check recommend world "recommender" {:position "housemaid" :for "bystander"}))))
        (finally (e/stop-run run)))))
  (testing "asked in a conversation, the recommender answers with a real candidate, who can then be sent for"
    (let [policy (fn [{:keys [world agent-id used]}]
                   (cond
                     (and (= "employer" agent-id) (= 1 (:round world)) (not (some #{"converse"} used)))
                     {:tool :converse :args {:with ["recommender"] :text "Do you know of a housemaid?"}}
                     (and (= "employer" agent-id) (= 2 (:round world)))
                     {:tool :send-for :args {:candidate (:id (first-person world))}}
                     :else {:tool :pass}))
          conversation (fn [{:keys [agent-id used]}]
                         (cond
                           (not= "recommender" agent-id) {:tool :end-conversation}
                           (some #{"recommend"} used) {:tool :reply :args {:text "I know just the girl."}}
                           :else {:tool :recommend :args {:position "housemaid" :for "employer"}}))
          run (start {:mock-policy policy :mock-conversation conversation
                      :speaking-actions ruleset/speaking-actions} {})]
      (try
        (rounds! run 3)
        (let [evs (e/events run)
              started (:id (first (filter #(= :conversation/started (:type %)) evs)))
              ended (:id (first (filter #(= :conversation/ended (:type %)) evs)))
              recommended (first (filter #(= :people/recommended (:type %)) evs))]
          (is (< started (:id recommended) ended) "recommended during the conversation")
          (is (= #{"employer" "recommender"} (:visible-to recommended)))
          (is (= 1 (count (filter #(= :people/sent-for (:type %)) evs))))
          (is (= :here (:status (first-person (e/world run)))))
          (is (not-any? #(= :action/forfeited (:type %)) evs)))
        (replay! run)
        (finally (e/stop-run run))))))

(deftest leaving-with-a-return-time
  (let [init (:init (scenario))
        run (start {:init (fn [opts] (assoc (init opts) :clock {:start "1904-10-15T08:00" :minutes-per-round 30}))
                    :calendar {:lookahead-days 30}} {})
        leave (action :take-leave)
        back {:return-date "1904-10-15" :return-time "11:00"}]
    (try
      (rounds! run 3)
      (let [world (e/world run)
            p (first-person world)
            id (:agent-id p)]
        (testing "a return time is checked"
          (doseq [[args reason] [[{:return-date "1904-10-15"} "give both return-date and return-time, or neither"]
                                 [{:return-date "15 October" :return-time "11:00"} "give return-date like \"1904-10-15\" and return-time like \"07:00\""]
                                 [{:return-date "1904-10-15" :return-time "08:30"} "the return time must be later than now"]
                                 [{:return-date "1904-10-30" :return-time "08:00"} "say you'll be back within 7 days"]]]
            (is (= reason (a/check leave world id args)))))
        (let [events ((:effect leave) world id back)
              left (reduce w/apply-event world events)
              departed (first (filter #(= :people/departed (:type %)) events))]
          (testing "they go, and are expected back at the meeting place"
            (is (= :away (:status (w/agent left id))))
            (is (= {:status :returning :arrival 7 :return-at "1904-10-15T11:00"}
                   (select-keys (h/candidate left (:id p)) [:status :arrival :return-at])))
            (is (str/ends-with? (n/describe departed (n/names left))
                                "meaning to be back at Gate on Saturday 15 October at 11:00.")))
          (testing "their calendar and the calendars of those who know them say so"
            (is (str/includes? (calendar/character-section left id) "You mean to come back to the Gate (gate) about the housemaid place."))
            (doseq [knower ["employer" "recommender"]]
              (is (str/includes? (calendar/character-section left knower)
                                 (str (:name p) " (" id ") means to come back to the Gate (gate) about the housemaid place."))))
            (is (not (str/includes? (calendar/character-section left "bystander") "come back"))))
          (testing "sending for them sooner brings them next round and clears the entries"
            (let [sent (effect left "employer" :send-for {:candidate (:id p)})]
              (is (= :sent-for (:status (h/candidate sent (:id p)))))
              (is (not (str/includes? (calendar/character-section sent id) "come back")))
              (is (not (str/includes? (calendar/character-section sent "employer") "come back")))))
          (e/emit! run events)))
      (rounds! run 3)
      (is (= :away (:status (w/agent (e/world run) (:agent-id (first-person (e/world run)))))) "not before 11:00")
      (rounds! run 1)
      (let [world (e/world run)
            p (first-person world)
            id (:agent-id p)]
        (testing "at 11:00 they are back, a candidate again"
          (is (= 7 (:round world)))
          (is (= {:status :here :arrived 7} (select-keys p [:status :arrived])))
          (is (nil? (:return-at p)))
          (is (= "gate" (space/location-of world id)))
          (is (str/starts-with? (:situation (w/agent world id)) "You have come back about the housemaid place"))
          (is (nil? (h/engage-reason world "employer" {:who id :position "housemaid"})))))
      (replay! run)
      (finally (e/stop-run run)))))

(deftest striking-the-bargain-in-a-conversation
  (let [policy (fn [{:keys [world agent-id used] :as ctx}]
                 (let [id (:agent-id (first-person world))]
                   (if (and (= "employer" agent-id) (= 3 (:round world)) (not (some #{"converse"} used)))
                     {:tool :converse :args {:with [id] :text "Will you take the place?"}}
                     (policy ctx))))
        conversation (fn [{:keys [world agent-id used]}]
                       (let [p (first-person world)
                             id (:agent-id p)]
                         (cond
                           (and (= "employer" agent-id) (= :here (:status p)) (not (some #{"engage"} used)))
                           {:tool :engage :args {:who id :position "housemaid" :terms "Twelve pounds."}}
                           (and (= id agent-id) (get-in world [:state :place-offers id]))
                           {:tool :accept-place :args {:conditions "Sundays free."}}
                           :else {:tool :reply :args {:text "Very good."}})))
        run (start {:mock-policy policy :mock-conversation conversation
                    :speaking-actions ruleset/speaking-actions} {})]
    (try
      (rounds! run 3)
      (let [world (e/world run)
            p (first-person world)
            id (:agent-id p)
            evs (e/events run)
            started (:id (first (filter #(= :conversation/started (:type %)) evs)))
            ended (:id (first (filter #(= :conversation/ended (:type %)) evs)))
            within? #(< started (:id %) ended)]
        (testing "the offer is made and accepted on lines of the conversation"
          (is (within? (first (filter #(= :people/offered (:type %)) evs))))
          (is (within? (first (filter #(and (= :people/post-changed (:type %)) (= :housemaid (get-in % [:data :position]))) evs)))))
        (is (= :engaged (:status p)))
        (is (= :housemaid (:role (w/agent world id))))
        (is (= "Sundays free." (get-in world [:state :posts id :conditions])))
        (is (not-any? #(#{:action/forfeited :run/pause-requested} (:type %)) evs)))
      (replay! run)
      (finally (e/stop-run run)))))
