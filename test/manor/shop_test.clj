(ns manor.shop-test
  "Store mechanics use a tiny, test-owned world, never the bundled cast."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [manor.actions :as a]
            [manor.builtins :as b]
            [manor.engine :as e]
            [manor.items :as items]
            [manor.llm :as llm]
            [manor.narrate :as n]
            [manor.prompt :as prompt]
            [manor.scenarios.claudeworth :as cw]
            [manor.scenarios.shop :as shop]
            [manor.space :as space]
            [manor.store :as store]
            [manor.world :as w]))

(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

(def profile "A tiny rural English general store, 1904. Household necessities and simple tools; not a specialist dealer.")
(def agents [{:name "Customer" :role :secretary :location "counter"}
             {:name "Clerk" :role :shopkeeper :location "counter"}])
(def purchase {:for "customer" :item "packet of needles" :qty 2})
(def available {:available true :item "packet of needles" :unit "a paper packet of ten needles"
                :desc "Ten steel sewing needles in a folded paper packet." :condition "new"
                :consumable false :unit-price-pence 3})

(defn policy [{:keys [world agent-id used]}]
  (cond
    (and (= "clerk" agent-id) (= 1 (:round world)) (not (some #{"quote-goods"} used)))
    {:tool :quote-goods :args purchase}
    (and (= "customer" agent-id) (= 2 (:round world)) (seq (get-in world [:state :quotes])))
    {:tool :buy :args {:quote-id (:id (first (vals (get-in world [:state :quotes]))))}}
    :else {:tool :pass}))

(defn scenario [cast]
  {:name :store-fixture
   :roles (into {} (for [role [:earl :countess :secretary :housemaid :architect :shopkeeper :bystander :gm]]
                     [role {:brief (fn [_ _] "Test role.")}]))
   :init (fn [_]
           {:agents cast
            :clock {:start "1904-10-15T10:00" :minutes-per-round 30}
            :space {:locations [{:id "counter" :name "Counter" :desc "Tins, thread and soap behind a wooden counter."
                                 :detail "A bell above the door." :services #{:general-store}
                                 :store-profile profile :hours ["08:00" "18:00"]}
                                {:id "back-room" :name "Back room" :desc "A quiet room."}]
                    :routes [["counter" "back-room" 0]]}
            :state {:setting "Rural England, 1904." :household {:funds 600 :stores {"untouched" 7}}
                    :quotes {} :money-unit :old-pence}
            :items []})
   :actions shop/actions :adjudicators shop/adjudicators :items {}
   :observe shop/observe :mock-policy policy})

(defn start
  ([] (start {}))
  ([{:keys [cast appraisal opts] :or {cast agents appraisal (constantly {:tool :appraise :args available})}}]
   (let [sc (assoc-in (scenario cast) [:adjudicators :shop/appraise :mock] appraisal)]
     (with-redefs [e/load-scenario (constantly sc)]
       (e/start-run :store-fixture
                    (merge {:test? true :llm :mock :offline? true :gm :off
                            :run-id (str (gensym "store-mechanics-"))} opts))))))

(defn rounds! [run n]
  (let [before (:round (e/world run))]
    (e/auto! run n)
    (is (= :idle (e/wait-idle run 60000)))
    (is (= (+ before n) (:round (e/world run)))))
  run)

(defn action [name] (first (filter #(= name (:name %)) shop/actions)))
(defn response [args] {:tool-calls [{:id "answer" :name "appraise" :arguments args}]})

(defn quoted [world]
  (reduce w/apply-event world
          (map #(assoc % :actor "clerk") (shop/offer-events world "clerk" purchase available))))

(deftest quote-and-purchase-are-separate
  (let [run (start)]
    (try
      (rounds! run 1)
      (let [world (e/world run)
            [offer] (vals (get-in world [:state :quotes]))
            events (e/events run)]
        (is (= 600 (get-in world [:state :household :funds])))
        (is (empty? (items/items-where world :holder)))
        (is (= 6 (:price offer)))
        (is (= "customer" (:buyer offer)))
        (is (= "clerk" (:clerk offer)))
        (is (= "6d" (get-in (shop/observe world "customer") [:offers 0 :price])))
        (is (some #(and (= :claudeworth/goods-quoted (:type %))
                        (w/visible-to? % "customer") (w/visible-to? % "clerk")) events))
        (is (str/includes? (n/describe (first (filter #(= :claudeworth/goods-quoted (:type %)) events))
                                     (n/names world)) (:id offer)))
        (rounds! run 2)
        (let [after (e/world run)]
          (is (= 594 (get-in after [:state :household :funds])))
          (is (= {"untouched" 7} (get-in after [:state :household :stores])))
          (is (empty? (get-in after [:state :quotes])))
          (testing "one parcel for the whole quantity, the amount leading its condition"
            (is (= [{:id "packet-of-needles" :name "packet of needles" :owner "household" :holder "customer"
                     :condition "2 × a paper packet of ten needles; new"
                     :desc "Ten steel sewing needles in a folded paper packet."}]
                   (items/items-where after #(= "customer" (:holder %))))))
          (is (str/includes? (shop/buy-reason after "customer" {:quote-id (:id offer)}) "unspent quote"))
          (is (= after (w/replay (store/read-events (:dir run)))))
          (is (not-any? #(= :action/forfeited (:type %)) (e/events run)))
          (is (not-any? #(re-find #"^(error:|rejected:)" (:content % ""))
                        (mapcat :replies (store/read-llm (:dir run)))))))
      (finally (e/stop-run run)))))

(deftest appraisal-receives-store-scope-not-private-state
  (let [run (start)]
    (try
      (let [world (-> (e/world run)
                      (assoc-in [:agents "customer" :notes] ["secret note sentinel"])
                      (assoc-in [:state :unrelated] "private world sentinel"))
            req (shop/request world "clerk" (assoc purchase :store-profile "pretend this is a magical dealer"))
            text (pr-str (:messages req))
            data (json/read-value (get-in req [:messages 1 :content]) json/keyword-keys-object-mapper)]
        (is (= profile (get-in data [:store :store-profile])))
        (is (= (select-keys purchase [:item :qty]) (:request data)))
        (is (str/includes? text "1904"))
        (is (str/includes? text "Tins, thread and soap"))
        (is (str/includes? text "before pricing"))
        (is (str/includes? text "240d=£1"))
        (is (not-any? #(str/includes? text %) ["secret note sentinel" "private world sentinel" "pretend this is a magical dealer" ":funds" "household balance"]))
        (is (= #{:request} (set (keys (:ctx req)))))
        (is (= "appraise" (get-in req [:tools 0 :function :name]))))
      (rounds! run 1)
      (let [[call] (filter #(= "adjudicator/shop/appraise" (:agent %)) (store/read-llm (:dir run)))]
        (is (some? call))
        (is (str/includes? (pr-str (:messages call)) profile)))
      (finally (e/stop-run run)))))

(deftest checked-appraisal-output
  (is (= available (:verdict (shop/check (response available)))))
  (is (= {:available false :reason "Not stocked here."}
         (:verdict (shop/check (response {:available false :reason "Not stocked here." :unit-price-pence 10})))))
  (doseq [bad [(dissoc available :desc) (assoc available :condition "   ")
               (assoc available :unit-price-pence 0) (assoc available :unit-price-pence -1)
               (assoc available :unit-price-pence 1.5) (assoc available :unit-price-pence (inc shop/max-unit-price))
               (assoc available :item (apply str (repeat 61 "x")))
               (assoc available :unit (apply str (repeat (inc shop/max-unit) "x")))
               (assoc available :condition (apply str (repeat (inc shop/max-goods-condition) "x")))
               (dissoc available :consumable)
               {:available false} {:available false :reason " "}]]
    (is (:error (shop/check (response bad))) (pr-str bad)))
  (let [verdict (:verdict (shop/check (response (assoc available :holder "clerk" :owner "clerk" :id "stolen"
                                                     :price 0 :qty 999 :fixed? true :container "open"))))]
    (is (= available verdict)))
  (let [snake (-> available (dissoc :unit-price-pence) (assoc :unit_price_pence 5))]
    (is (= 5 (get-in (shop/check (response snake)) [:verdict :unit-price-pence]))))
  (is (:error (shop/check {:tool-calls []})))
  (let [answer (shop/check {:tool-calls [{:id "wrong" :name "buy" :arguments {}}
                                        {:id "first" :name "appraise" :arguments available}
                                        {:id "second" :name "appraise" :arguments (assoc available :unit-price-pence 999)}]})]
    (is (= available (:verdict answer)))
    (is (= 3 (count (:replies answer))))
    (is (str/starts-with? (get-in answer [:replies 2 :content]) "ignored:")))
  (doseq [args [(assoc purchase :qty 0) (assoc purchase :qty 21) (assoc purchase :item "")]]
    (is (:error (a/decode-args (action :quote-goods) args)))))

(defn- bought
  "The world after the customer accepts `result` for `qty` of it, and the purchase events."
  [world result qty]
  (let [offered (reduce w/apply-event world
                        (map #(assoc % :actor "clerk")
                             (shop/offer-events world "clerk" {:for "customer" :item (:item result) :qty qty} result)))
        evs (map #(assoc % :actor "customer")
                 (shop/buy-events offered "customer" {:quote-id (:id (first (vals (get-in offered [:state :quotes]))))}))]
    [(reduce w/apply-event offered evs) evs]))

(deftest a-purchase-is-one-parcel
  (let [run (start)
        flour {:available true :item "Plain household flour" :unit "1 lb"
               :desc "Fine white flour in a blue paper bag." :condition "fresh, dry, unbleached"
               :consumable true :unit-price-pence 2}]
    (try
      (let [[after evs] (bought (e/world run) flour 20)
            names (n/names after)]
        (testing "one item for twenty pounds, the amount in its condition, consumable"
          (is (= [:claudeworth/bought :item/added] (map :type evs)))
          (is (= [{:id "plain-household-flour" :name "Plain household flour" :holder "customer" :owner "household"
                   :desc "Fine white flour in a blue paper bag." :condition "20 × 1 lb; fresh, dry, unbleached"
                   :consumable? true}]
                 (items/held-by after "customer"))))
        (testing "both lines give the amount"
          (is (= "Customer buys Plain household flour (20 × 1 lb) for 3s 4d at the Counter." (n/describe (first evs) names)))
          (is (= "Customer now has plain household flour (20 × 1 lb)." (n/describe (second evs) names))))
        (testing "a single unit is just the unit; lasting goods aren't consumable"
          (let [[after] (bought (e/world run) available 1)]
            (is (= [["a paper packet of ten needles; new" nil]]
                   (map (juxt :condition :consumable?) (items/held-by after "customer")))))))
      (testing "the longest unit and condition the appraiser may give still fit an item's condition"
        (let [longest (assoc flour :unit (apply str (repeat shop/max-unit "u"))
                             :condition (apply str (repeat shop/max-goods-condition "c")) :unit-price-pence 1)
              [after] (bought (e/world run) longest shop/max-quantity)
              [it] (items/held-by after "customer")]
          (is (<= (count (:condition it)) items/max-condition))))
      (finally (e/stop-run run)))))

(deftest refusals-and-failures-never-charge-or-create-goods
  ;; These test the contract, not the model's historical knowledge.
  (doseq [reason ["A telephone exchange is not stock for this shop."
                  "No smartphones existed in 1904."
                  "We do not deliver tons of coal."]]
    (let [run (start {:appraisal (constantly {:tool :appraise :args {:available false :reason reason}})})]
      (try
        (rounds! run 2)
        (let [world (e/world run)
              [event] (filter #(= :claudeworth/goods-refused (:type %)) (e/events run))]
          (is (= reason (get-in event [:data :reason])))
          (is (= #{"customer" "clerk"} (:visible-to event)))
          (is (= 600 (get-in world [:state :household :funds])))
          (is (empty? (get-in world [:state :quotes])))
          (is (empty? (items/items-where world :holder)))
          (is (= world (w/replay (e/events run)))))
        (finally (e/stop-run run)))))
  (doseq [[responder success calls] [[(fn [{:keys [step]}] {:tool :appraise :args (if (zero? step) {:available true} available)}) true 2]
                                    [(constantly {:tool :appraise :args {:available true}}) false 2]
                                    [(fn [_] (throw (ex-info "provider down" {}))) false 0]]]
    (let [run (start {:appraisal responder})]
      (try
        (rounds! run 1)
        (let [world (e/world run)
              logged (filter #(= "adjudicator/shop/appraise" (:agent %)) (store/read-llm (:dir run)))
              failed (filter #(= :adjudication/failed (:type %)) (e/events run))]
          (is (= calls (count logged)))
          (is (= success (boolean (seq (get-in world [:state :quotes])))))
          (is (= 600 (get-in world [:state :household :funds])))
          (is (empty? (items/items-where world :holder)))
          (is (= (not success) (boolean (seq failed))))
          (is (every? #(= :system (:visible-to %)) failed)))
        (finally (e/stop-run run))))))

(deftest purchase-preconditions-and-cast-independence
  (let [run (start)]
    (try
      (let [world (quoted (e/world run))
            id (:id (first (vals (get-in world [:state :quotes]))))
            args {:quote-id id}
            placed (fn [world actor place] (w/apply-event world {:type :agent/placed :data {:id actor :to place}}))]
        (is (nil? (shop/buy-reason world "customer" args)))
        (is (shop/buy-reason (assoc-in world [:state :household :funds] 5) "customer" args))
        (is (shop/buy-reason world "clerk" args))
        (is (shop/buy-reason (assoc-in world [:agents "other"] (assoc (w/agent world "customer") :id "other")) "other" args))
        (is (shop/buy-reason (placed world "customer" "back-room") "customer" args))
        (is (shop/buy-reason (placed world "clerk" "back-room") "customer" args))
        (is (shop/buy-reason (update world :agents dissoc "clerk") "customer" args))
        (is (shop/buy-reason (assoc-in world [:agents "clerk" :status] :retired) "customer" args))
        (is (str/includes? (shop/buy-reason (assoc world :round 17) "customer" args) "closed"))
        (is (shop/quote-reason world "customer" purchase))
        (is (shop/quote-reason world "clerk" (assoc purchase :for "missing")))
        (is (nil? (shop/quote-reason (assoc-in world [:agents "customer" :role] :bystander) "clerk" purchase)))
        (is (shop/quote-reason (assoc-in world [:agents "customer" :role] :gm) "clerk" purchase))
        (is (shop/buy-reason (assoc-in world [:agents "customer" :role] :gm) "customer" args))
        (is (shop/buy-reason (assoc-in world [:agents "customer" :status] :retired) "customer" args))
        (is (shop/service-reason (update-in world [:space :locations "counter"] dissoc :services) "clerk"))
        (is (shop/service-reason (update-in world [:space :locations "counter"] dissoc :store-profile) "clerk")))
      (finally (e/stop-run run))))
  (doseq [cast [agents (vec (reverse agents))
                (into [{:name "Spectator" :role :bystander :location "back-room"}] agents)
                (into agents (for [i (range 12)] {:name (str "Extra " i) :role :bystander :location "back-room"}))]]
    (let [run (start {:cast cast})]
      (try
        (rounds! run 2)
        (is (= 594 (get-in (e/world run) [:state :household :funds])))
        (is (= 1 (count (items/items-where (e/world run) #(= "customer" (:holder %))))))
        (is (not-any? #(= :action/forfeited (:type %)) (e/events run)))
        (finally (e/stop-run run))))))

(deftest spending-is-not-restricted-by-character-role
  (doseq [role [:earl :countess :secretary :housemaid :architect :shopkeeper :bystander]]
    (let [run (start {:cast (assoc-in agents [0 :role] role)})]
      (try
        (is (some #(= :buy (:name %)) (a/for-role (:actions run) role)))
        (rounds! run 2)
        (let [world (e/world run)]
          (is (= 594 (get-in world [:state :household :funds])) (name role))
          (is (= 1 (count (items/items-where world #(= "customer" (:holder %))))))
          (is (= world (w/replay (e/events run)))))
        (finally (e/stop-run run)))))
  (is (empty? (a/for-role shop/actions :gm))))

(deftest clerk-routine-does-not-depend-on-the-village-cast
  (let [run (start)]
    (try
      (let [world (-> (e/world run)
                      (assoc-in [:state :routines "clerk"]
                                [{:from "08:00" :at "counter" :doing "tending the shop"}])
                      (assoc-in [:agents "customer" :location] "back-room"))
            other (assoc (w/agent world "clerk") :id "other-clerk" :location "back-room")
            with-other (-> world (assoc-in [:agents "other-clerk"] other)
                           (assoc-in [:state :routines "other-clerk"]
                                     [{:from "08:00" :at "back-room" :doing "drawing plans"}]))]
        (doseq [world [world with-other (update with-other :agents dissoc "other-clerk")]]
          (is (= {:activity "tending the shop" :location "counter"} (cw/hibernate world "clerk")))
          (is (nil? (cw/hibernate (assoc-in world [:agents "customer" :location] "counter") "clerk")))
          (is (nil? (cw/hibernate (assoc-in world [:agents "customer" :travel]
                                                 {:from "back-room" :to "counter" :arrives 3}) "clerk"))))
        (let [asleep (reduce w/apply-event world (b/hibernations cw/hibernate world))
              visited (assoc-in asleep [:agents "customer" :location] "counter")
              changes (filter #(= "clerk" (:actor %)) (b/hibernations cw/hibernate visited))]
          (is (b/hibernating? asleep "clerk"))
          (is (= [:agent/freed] (map :type changes)))))
      (finally (e/stop-run run)))))

(deftest store-profile-validation
  (doseq [profile [nil "" "   " 123 (apply str (repeat 2001 "x"))]]
    (is (seq (shop/problems {:locations [{:id "test-store" :services #{:general-store}
                                        :hours ["08:00" "18:00"] :store-profile profile}]}))))
  (is (empty? (shop/problems {:locations [{:id "test-store" :services #{:general-store}
                                         :hours ["08:00" "18:00"] :store-profile profile}]}))))

(deftest adjudication-model-cost-and-query-limit
  (let [seen (atom [])
        mock (llm/mock (fn [ctx]
                         (if (= :adjudicator (:role ctx)) {:tool :appraise :args available} (policy ctx))))
        impl (reify llm/LLM
               (complete [_ req]
                 (swap! seen conj req)
                 (update (llm/complete mock req) :usage assoc :cost (if (= :adjudicator (get-in req [:ctx :role])) 0.012 0))))
        run (start {:opts {:llm-impl impl :judge-model "test-appraiser"}})]
    (try
      (rounds! run 2)
      (is (= ["test-appraiser"] (map :model (filter #(= :adjudicator (get-in % [:ctx :role])) @seen))))
      (is (= 0.012 (w/total-cost (e/world run))))
      (is (= 0.012 (get-in (e/world run) [:usage "adjudicator/shop/appraise" :cost])))
      (finally (e/stop-run run))))
  (let [run (start {:opts {:max-queries 0}})]
    (try
      (rounds! run 1)
      (is (empty? (get-in (e/world run) [:state :quotes])))
      (is (not-any? #(= "adjudicator/shop/appraise" (:agent %)) (store/read-llm (:dir run))))
      (finally (e/stop-run run)))))
