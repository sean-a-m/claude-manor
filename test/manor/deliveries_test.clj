(ns manor.deliveries-test
  "Orders and deliveries on a tiny, test-owned world: a shop that sends goods
  out, a house with a front door (the delivery place) and a hall."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [manor.clock :as clock]
            [manor.engine :as e]
            [manor.hiring :as hiring]
            [manor.items :as items]
            [manor.narrate :as n]
            [manor.positions :as positions]
            [manor.prompt :as prompt]
            [manor.scenarios.claudeworth :as cw]
            [manor.scenarios.deliveries :as deliveries]
            [manor.scenarios.shop :as shop]
            [manor.store :as store]
            [manor.world :as w]))

(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

(def profile "A tiny rural English butcher's and grocer's, 1904. Meat and household groceries over the counter.")
(def delivery-profile "Larger orders are sent up to houses in the neighbourhood.")

(def agents [{:name "Customer" :role :secretary :location "counter"}
             {:name "Clerk" :role :shopkeeper :location "counter"}
             {:name "Butler" :role :bystander :location "hall"}])

(def shopping {:for "customer" :goods [{:item "tea" :qty 1}
                                       {:item "side of mutton" :qty 1 :deliver true}]})

(defn appraisal
  "Tea over the counter; anything asked for delivery comes in two hours."
  [{:keys [request]}]
  {:tool :appraise
   :args {:lines (for [{:keys [item deliver]} request]
                   (cond-> {:available true :item item :unit (if deliver "1 side" "1 lb")
                            :desc (str "Good " item ".") :condition "fresh"
                            :consumable true :unit-price-pence (if deliver 120 30)}
                     deliver (assoc :lead-hours 2)))}})

(defn policy
  "The mutton is ordered in round 2 (due in round 6) and again in round 9 (due in round 13)."
  [{:keys [world agent-id used]}]
  (cond
    (and (= "clerk" agent-id) (#{1 8} (:round world)) (not (some #{"quote-goods"} used)))
    {:tool :quote-goods :args shopping}
    (and (= "customer" agent-id) (#{2 9} (:round world)) (seq (get-in world [:state :quotes])))
    {:tool :buy :args {:quote-ids (vec (sort (keys (get-in world [:state :quotes]))))}}
    :else {:tool :pass}))

(def errand-boy {:title "Errand boy" :ages [13 18] :sex :male :about "You carry goods out for a tradesman."})

(defn scenario [shop-extra]
  (deliveries/install
  {:name :delivery-fixture
   :roles (into {} (for [role [:secretary :shopkeeper :bystander :errand-boy :gm]] [role {:brief (fn [_ _] "Test role.")}]))
   :init (fn [_]
           {:agents agents
            :clock {:start "1904-10-15T10:00" :minutes-per-round 30}
            :space {:locations [(merge {:id "counter" :name "Counter" :desc "A counter." :services #{:general-store}
                                        :store-profile profile :hours ["08:00" "18:00"]}
                                       (dissoc shop-extra :hiring-max-agents))
                                {:id "front-door" :name "Front door" :desc "The front door of the house."}
                                {:id "hall" :name "Hall" :desc "A hall."}]
                    :routes [["front-door" "hall" 0] ["counter" "front-door" 1]]}
            :state (merge {:setting "Rural England, 1904." :household {:funds 600}
                           :positions {:shopkeeper {:capabilities #{:shop-clerk}} :errand-boy errand-boy}
                           :quotes {} :meeting-place "front-door"}
                          (select-keys shop-extra [:hiring-max-agents]))
            :items []})
   :actions shop/actions :items {}
   :adjudicators (merge hiring/adjudicators (assoc-in shop/adjudicators [:shop/appraise :mock] appraisal))
   :bridges hiring/bridges :offline-stubs hiring/offline-stubs
   :observe shop/observe :mock-policy policy}))

(defn start
  ([] (start {:delivery-profile delivery-profile}))
  ([shop-extra] (start shop-extra {}))
  ([shop-extra overrides]
   (with-redefs [e/load-scenario (constantly (deliveries/install (merge (scenario shop-extra) overrides)))]
     (e/start-run :delivery-fixture {:test? true :llm :mock :offline? true :gm :off
                                     :run-id (str (gensym "deliveries-"))}))))

(defn rounds! [run n]
  (e/auto! run n)
  (is (= :idle (e/wait-idle run 60000)))
  run)

(defn- of-type [run t] (filter #(= t (:type %)) (e/events run)))

(deftest an-order-is-paid-now-and-delivered-later
  (let [run (start)]
    (try
      (rounds! run 2)
      (let [world (e/world run)
            [order] (vals (deliveries/orders world))
            [ordered] (of-type run :deliveries/ordered)
            names (n/names world)]
        (testing "one buy: tea handed over, the mutton ordered, both paid for"
          (is (= (- 600 150) (get-in world [:state :household :funds])))
          (is (empty? (get-in world [:state :quotes])))
          (is (= ["tea"] (map :name (items/held-by world "customer"))))
          (is (= {:item "side of mutton" :buyer "customer" :clerk "clerk" :location "counter" :to "front-door" :due 6}
                 (select-keys order [:item :buyer :clerk :location :to :due]))))
        (testing "the appraiser sees the delivery profile and which lines are for delivery"
          (let [sent (->> (store/read-llm (:dir run)) (filter #(= "adjudicator/shop/appraise" (:agent %)))
                          first :messages (map :content) (str/join "\n"))]
            (is (str/includes? sent delivery-profile))
            (is (str/includes? sent "{\"item\":\"side of mutton\",\"qty\":1,\"deliver\":true}"))
            (is (str/includes? sent "{\"item\":\"tea\",\"qty\":1}"))))
        (testing "the quote and the order say when and where"
          (is (str/includes? (n/describe (first (filter #(get-in % [:data :deliver]) (of-type run :claudeworth/goods-quoted))) names)
                             "to be delivered at the Front door within 2 hours of ordering"))
          (is (str/includes? (n/describe ordered names) "to be delivered at the Front door by 12:30")))
        (testing "the order's id is the engine's: never in its narration or a view"
          (is (not (str/includes? (n/describe ordered names) (:id order))))
          (is (not-any? :id (:orders (shop/observe world "customer"))))
          (is (not-any? :id (:orders (shop/observe world "clerk")))))
        (testing "only the counter knows of the order"
          (is (w/visible-to? ordered "customer"))
          (is (w/visible-to? ordered "clerk"))
          (is (not (w/visible-to? ordered "butler")))
          (is (= ["side of mutton"] (map :item (:orders (shop/observe world "customer")))))
          (is (= 1 (count (:orders (shop/observe world "clerk")))))
          (is (nil? (:orders (shop/observe world "butler"))))))
      (rounds! run 3)
      (is (seq (deliveries/orders (e/world run))) "not yet due at round 5")
      (rounds! run 1)
      (let [world (e/world run)
            [arrived] (of-type run :deliveries/arrived)
            [parcel] (items/items-where world #(= "front-door" (:place %)))]
        (testing "at the due round the goods are left at the front door"
          (is (empty? (deliveries/orders world)))
          (is (= 6 (:round arrived)))
          (is (= {:name "side of mutton" :owner "household" :consumable? true
                  :aspects {:amount "1 side" :state "fresh"}}
                 (select-keys parcel [:name :owner :consumable? :aspects])))
          (is (str/starts-with? (:order-id parcel) "order-")))
        (testing "the house hears it, the village doesn't"
          (is (w/visible-to? arrived "butler"))
          (is (not (w/visible-to? arrived "clerk")))
          (is (= (str "Pat Ellis 0 (pat-ellis-0) brings a delivery from the Counter and leaves it at the Front door: side of mutton (1 side, " (:id parcel) ").")
                 (n/describe arrived (n/names world)))))
        (testing "the nightly steward leaves delivered goods alone"
          (let [loose (items/added world {:name "loose mutton" :desc "Mutton." :place "front-door" :consumable? true} :gm)
                later (assoc (w/apply-event world loose) :round 40)]
            (is (= ["loose mutton"] (map :name (get (items/steward-candidates later {:idle-hours 4 :max-per-call 8}) "front-door"))))))
        (is (= world (w/replay (store/read-events (:dir run)))))
        (is (not-any? #(re-find #"^(error:|rejected:)" (:content % ""))
                      (mapcat :replies (store/read-llm (:dir run))))))
      (finally (e/stop-run run)))))

(deftest a-shop-without-a-delivery-profile-refuses-deliver-lines
  (let [run (start {})]
    (try
      (let [world (e/world run)]
        (is (= "the Counter doesn't send goods out; quote those lines without deliver"
               (shop/quote-reason world "clerk" shopping)))
        (is (nil? (shop/quote-reason world "clerk" (update shopping :goods (partial take 1)))))
        (is (nil? (get-in (shop/observe world "customer") [:here :delivery-profile]))))
      (finally (e/stop-run run)))))

(defn- response [lines] {:tool-calls [{:id "answer" :name "appraise" :arguments {:lines lines}}]})

(deftest a-delivery-line-needs-a-lead-time
  (let [line {:available true :item "side of mutton" :unit "1 side" :desc "A side." :condition "fresh"
              :consumable true :unit-price-pence 120}]
    (is (str/includes? (:error (shop/check 1 #{0} (response [line]))) "lead-hours"))
    (is (:error (shop/check 1 #{0} (response [(assoc line :lead-hours 0)]))))
    (is (:error (shop/check 1 #{0} (response [(assoc line :lead-hours (inc deliveries/max-lead-hours))]))))
    (is (= 3 (get-in (shop/check 1 #{0} (response [(assoc line :lead-hours 3)])) [:verdict :lines 0 :lead-hours])))
    (testing "a counter line keeps no lead time"
      (is (nil? (get-in (shop/check 1 (response [(assoc line :lead-hours 3)])) [:verdict :lines 0 :lead-hours]))))))

(deftest due-times-fall-within-opening-hours
  (let [c {:start "1904-10-15T10:00" :minutes-per-round 30}
        at (fn [round lead] (str (clock/time-of c (deliveries/due-round {:clock c :round round} lead ["08:00" "18:00"]))))]
    (is (= "1904-10-15T12:00" (at 1 2)))
    (is (= "1904-10-15T10:30" (at 1 0.1)) "at least next round")
    (testing "a delivery due at night comes at the next opening"
      (is (= "1904-10-16T08:00" (at 11 10))))))

(deftest delivery-profiles-are-checked
  (is (empty? (deliveries/problems {:locations [{:id "shop" :services #{:general-store} :delivery-profile delivery-profile}]})))
  (is (seq (deliveries/problems {:locations [{:id "shop" :services #{:general-store} :delivery-profile " "}]})))
  (is (seq (deliveries/problems {:locations [{:id "green" :delivery-profile delivery-profile}]}))))

(defn- courier [world] (get (deliveries/couriers world) "counter"))

(deftest a-shop-has-one-courier-who-comes-and-goes
  (let [run (start)]
    (try
      (rounds! run 6)
      (let [world (e/world run)
            {:keys [agent-id position]} (courier world)
            boy (w/agent world agent-id)]
        (testing "the first delivery makes and spawns the shop's courier at the delivery place"
          (is (= "pat-ellis-0" agent-id))
          (is (= :errand-boy position (:role boy)))
          (is (= "front-door" (:location boy)))
          (is (str/includes? (:situation boy) "You carry for the Counter. You have brought up side of mutton (1 side), ordered by Customer"))
          (is (= {:position :errand-boy :employer "clerk" :terms "carries for the Counter (counter)"}
                 (select-keys (get-in world [:state :posts agent-id]) [:position :employer :terms]))))
        (testing "the courier's persona and record stay private"
          (is (= [:system] (map :visible-to (filter #(= agent-id (get-in % [:data :agent :id])) (of-type run :agent/spawned)))))
          (let [butler (str/join "\n" (map :content (prompt/agent-messages (:scenario run) world (e/events run) "butler" {})))]
            (is (str/includes? butler "pat-ellis-0"))
            (is (not (str/includes? butler (:persona boy)))))
          (is (w/visible-to? (last (of-type run :people/post-changed)) "clerk"))
          (is (not (w/visible-to? (last (of-type run :people/post-changed)) "butler")))))
      (rounds! run 1)
      (is (w/active? (e/world run) "pat-ellis-0") "still here the round after")
      (rounds! run 1)
      (testing "nobody talked to them, so they go home two rounds after arriving"
        (is (= :away (:status (w/agent (e/world run) "pat-ellis-0"))))
        (is (= [8] (map :round (of-type run :people/departed)))))
      (rounds! run 5)
      (let [world (e/world run)]
        (testing "the next delivery brings the same courier back"
          (is (= [13] (map :round (of-type run :people/returned))))
          (is (= "front-door" (:location (w/agent world "pat-ellis-0"))))
          (is (= 1 (count (filter #(= :people/generate (get-in % [:data :bridge])) (of-type run :bridge/called)))))
          (is (= 2 (count (filter :courier (map :data (of-type run :deliveries/arrived)))))))
        (is (= world (w/replay (store/read-events (:dir run))))))
      (finally (e/stop-run run)))))

(deftest a-conversation-keeps-the-courier-a-round-longer
  (let [run (start)]
    (try
      (rounds! run 6)
      (let [scenario (:scenario run)
            world (e/world run)
            at (fn [world round] (deliveries/tick scenario (assoc world :round round) {}))]
        (is (empty? (at world 7)))
        (is (= ["pat-ellis-0"] (map (comp :id :data) (at world 8))))
        (let [talked (assoc-in world [:agents "pat-ellis-0" :last-conversation-round] 7)]
          (is (empty? (at talked 8)))
          (is (= ["pat-ellis-0"] (map (comp :id :data) (at talked 9))))))
      (finally (e/stop-run run)))))

(deftest with-no-room-the-goods-come-without-a-courier
  (let [run (start {:delivery-profile delivery-profile :hiring-max-agents 3})]
    (try
      (rounds! run 6)
      (let [world (e/world run)
            [arrived] (of-type run :deliveries/arrived)]
        (is (nil? (:courier (:data arrived))))
        (is (str/starts-with? (n/describe arrived (n/names world)) "A delivery from the Counter is left at the Front door"))
        (is (seq (items/items-where world #(= "front-door" (:place %)))))
        (testing "the person made is kept, and spawned at a later delivery once there's room"
          (is (some? (:person (courier world))))
          (is (nil? (:agent-id (courier world))))
          (let [later (-> world
                          (assoc-in [:state :hiring-max-agents] 10)
                          (assoc-in [:state :orders "order-late"] (assoc (:data (first (of-type run :deliveries/ordered)))
                                                                        :id "order-late" :due 7))
                          (assoc :round 7))]
            (is (nil? (deliveries/compose later nil)) "no new person: the shop has one")
            (is (some #(= :agent/spawned (:type %)) (deliveries/tick (:scenario run) later {}))))))
      (finally (e/stop-run run)))))

(deftest compose-names-couriers-at-the-start-and-writes-them-when-due
  (let [run (start)]
    (try
      (let [world (assoc-in (e/world run) [:space :locations "back-shop"]
                            {:id "back-shop" :name "Back shop" :desc "Another shop." :services #{:general-store}
                             :store-profile profile :hours ["08:00" "18:00"] :delivery-profile delivery-profile})
            asked (atom [])
            written (atom 0)
            services {:bridge (fn [id args] (swap! asked conj (:seed args)) ((get hiring/offline-stubs id) args nil))
                      :adjudicate (fn [task _]
                                    (swap! written inc)
                                    (case task
                                      :people/appearance {:appearance "A boy in a cap."}
                                      :people/polish {:persona "You run errands." :manner "You seem quick." :circumstances ["Has a sister."]}))}
            named (deliveries/compose world services)]
        (testing "every delivering shop gets a named record, with no model call"
          (is (= #{"counter" "back-shop"} (set (keys named))))
          (is (zero? @written))
          (is (not-any? hiring/written? (vals named)))
          (is (= 2 (count (distinct @asked))) "each shop has its own seed")
          (is (= 2 (count (distinct (map :name (vals named))))) "and its own name"))
        (let [world (reduce (fn [w [shop p]] (assoc-in w [:state :couriers shop] {:person p :position :errand-boy})) world named)]
          (is (nil? (deliveries/compose world services)) "nothing due, nothing to do")
          (let [due (-> world (assoc :round 1)
                        (assoc-in [:state :orders "order-1"] {:id "order-1" :location "counter" :due 1}))
                made (deliveries/compose due services)]
            (testing "an order due writes only that shop's courier, from the same record"
              (is (= ["counter"] (keys made)))
              (is (= "You run errands." (get-in made ["counter" :persona])))
              (is (= (get-in named ["counter" :name]) (get-in made ["counter" :name])))
              (is (= 2 @written))
              (is (= 2 (count @asked)) "no second record")))))
      (finally (e/stop-run run)))))

(deftest the-courier-is-named-before-any-delivery
  (let [run (start)]
    (try
      (rounds! run 2)
      (let [world (e/world run)
            line "Pat Ellis 0, the Counter's errand boy"]
        (is (= line (deliveries/courier-line world "counter")))
        (is (nil? (w/agent world "pat-ellis-0")) "named, but not yet an agent")
        (is (= line (get-in (shop/observe world "clerk") [:here :orders-sent-up-by])))
        (is (= [line] (map :sent-up-by (:orders (shop/observe world "customer")))))
        (is (nil? (get-in (shop/observe world "butler") [:here :orders-sent-up-by])) "only shown at the shop")
        (is (not-any? #(str/starts-with? (:agent %) "adjudicator/people") (store/read-llm (:dir run)))
            "no model call until a delivery is due"))
      (finally (e/stop-run run)))))

(defn- butler-goes-out
  "The butler walks to the front door in round 7, the round after the mutton comes."
  [{:keys [world agent-id used] :as ctx}]
  (if (and (= "butler" agent-id) (= 7 (:round world)) (not (some #{"walk"} used)))
    {:tool :walk :args {:to "front-door"}}
    (policy ctx)))

(deftest a-courier-waits-out-of-play-until-someone-comes
  (let [run (start {:delivery-profile delivery-profile}
                   {:hibernate cw/hibernate :on-spawn positions/starting-events :mock-policy butler-goes-out
                    :actions (into shop/actions (filter #(= :walk (:name %)) cw/actions))})]
    (try
      (rounds! run 6)
      (is (:hibernating? (w/busy (e/world run) "pat-ellis-0")) "alone at the door on arriving")
      (rounds! run 1)
      (let [calls (filter #(= "pat-ellis-0" (:agent %)) (store/read-llm (:dir run)))]
        (testing "no turns until the butler comes out, then one"
          (is (= #{7} (set (map :round calls)))))
        (is (= (e/world run) (w/replay (store/read-events (:dir run))))))
      (finally (e/stop-run run)))))
