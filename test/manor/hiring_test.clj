(ns manor.hiring-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [manor.actions :as a]
            [manor.builtins :as b]
            [manor.conversation :as conversation]
            [manor.engine :as e]
            [manor.hiring :as h]
            [manor.items :as items]
            [manor.llm :as llm]
            [manor.people :as people]
            [manor.positions :as positions]
            [manor.prompt :as prompt]
            [manor.scenarios.claudeworth :as claudeworth]
            [manor.space :as space]
            [manor.store :as store]
            [manor.world :as w]))

(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

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
             :state {:positions (positions/read-catalogue "positions/england-1904.edn") :setting "England, 1904."}})
    :actions h/actions :adjudicators h/adjudicators :bridges h/bridges
    :offline-stubs h/offline-stubs :mcp-servers people/mcp-servers
    :reference (fn [world _] (h/reference world)) :observe h/observe :mock-policy policy}
   "gate"))

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
    (doseq [bad ["" "  " (apply str (repeat 301 "x"))]]
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
        (let [events (e/events run)
              generated (first (filter #(= :people/generate (get-in % [:data :bridge])) events))
              reputation (first (filter #(= :people/reputation (get-in % [:data :bridge])) events))]
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
                     :else {:tool :engage :args {:who id :position "housemaid" :terms "Twelve pounds." :bedroom "room"}})
                   (and (= id agent-id) (get-in world [:state :place-offers id]))
                   {:tool :accept-place :args {:conditions "Sunday afternoons free."}}
                   :else (policy ctx))))
        run (start {:actions (conj h/actions (first (filter #(= :walk (:name %)) claudeworth/actions)))
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
        (is (= 3 (count interview)))
        (is (not (contains? (first tools) "engage")) "the candidate is elsewhere at turn start")
        (is (contains? (second tools) "engage") "walking makes engage available")
        (is (= (:tool-digests (second interview)) (:tool-digests (nth interview 2 nil)))
            "an interview with no availability change keeps tool schemas and order identical")
        (is (= (:tool-digests (first interview))
               (take (count (:tools (first interview))) (:tool-digests (second interview))))
            "newly available tools leave the existing cacheable prefix unchanged")
        (is (= ["walk" "converse" "engage"]
               (mapv #(get-in % [:response :tool_calls 0 :function :name])
                     (filter #(and (= "employer" (:agent %)) (= 3 (:round %)) (nil? (:conversation %)))
                             (store/read-llm (:dir run))))))
        (is (= [:people/offered :people/post-changed :people/candidate-updated] (mapv :type milestones)))
        (is (every? #(= 3 (:round %)) milestones))
        (is (= ["accept-place"]
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
                          (and (= "employer" agent-id) (= 3 (:round world)))
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
        (is (empty? (h/tick (:scenario run) (assoc talking :round 8))))
        (is (some #(= :people/departed (:type %)) (h/tick (:scenario run) (assoc talking :round 9))))
        (is (= :away (:status (w/agent (effect returned id :take-leave {}) id))))
        (let [retired (w/apply-event away {:type :agent/retired :data {:id id}})]
          (is (h/send-for-reason retired "employer" {:candidate (:id p)}))
          (is (= :retired (:status (w/agent (w/apply-event retired {:type :people/returned :data {:id id :at "gate"}}) id))))))
      (rounds! run 4)
      (let [world (e/world run) id (:agent-id (first-person world))
            departure-round (:round (first (filter #(= :people/departed (:type %)) (e/events run))))]
        (is (= :away (:status (w/agent world id))))
        (is (= 6 departure-round))
        (is (not-any? #(and (= id (:actor %)) (> (:round %) departure-round) (= :llm/usage (:type %))) (e/events run))))
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
            spec (h/reference-activity (first (filter #(= :spend-time (:name %)) (items/actions))))
            args {:doing "writing a character" :candidate (:id p) :hours 0.5}
            events ((:effect spec) world "recommender" args)
            job (get-in (first events) [:data])
            request (items/judge-request {} world job {:minutes 30 :luck 50})
            done (items/work-done world job {:minutes 30 :luck 50}
                                 {:made [{:name "Written character" :desc "A letter of reference." :aspects {} :held true :consumable false}]})]
        (is (nil? (a/check spec world "recommender" args)))
        (is (a/check spec world "bystander" args))
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
