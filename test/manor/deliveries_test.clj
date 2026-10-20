(ns manor.deliveries-test
  "Orders and deliveries on a tiny, test-owned world: a shop that sends goods
  out, a house with a front door (the delivery place) and a hall."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [manor.clock :as clock]
            [manor.engine :as e]
            [manor.items :as items]
            [manor.narrate :as n]
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

(defn policy [{:keys [world agent-id used]}]
  (cond
    (and (= "clerk" agent-id) (= 1 (:round world)) (not (some #{"quote-goods"} used)))
    {:tool :quote-goods :args shopping}
    (and (= "customer" agent-id) (= 2 (:round world)) (seq (get-in world [:state :quotes])))
    {:tool :buy :args {:quote-ids (vec (sort (keys (get-in world [:state :quotes]))))}}
    :else {:tool :pass}))

(defn scenario [shop-extra]
  {:name :delivery-fixture
   :roles (into {} (for [role [:secretary :shopkeeper :bystander :gm]] [role {:brief (fn [_ _] "Test role.")}]))
   :init (fn [_]
           {:agents agents
            :clock {:start "1904-10-15T10:00" :minutes-per-round 30}
            :space {:locations [(merge {:id "counter" :name "Counter" :desc "A counter." :services #{:general-store}
                                        :store-profile profile :hours ["08:00" "18:00"]}
                                       shop-extra)
                                {:id "front-door" :name "Front door" :desc "The front door of the house."}
                                {:id "hall" :name "Hall" :desc "A hall."}]
                    :routes [["front-door" "hall" 0] ["counter" "front-door" 1]]}
            :state {:setting "Rural England, 1904." :household {:funds 600}
                    :positions {:shopkeeper {:capabilities #{:shop-clerk}}}
                    :quotes {} :meeting-place "front-door"}
            :items []})
   :actions shop/actions :items {}
   :adjudicators (assoc-in shop/adjudicators [:shop/appraise :mock] appraisal)
   :systems [deliveries/arrivals]
   :observe shop/observe :mock-policy policy})

(defn start
  ([] (start {:delivery-profile delivery-profile}))
  ([shop-extra]
   (with-redefs [e/load-scenario (constantly (scenario shop-extra))]
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
          (is (= (str "A delivery from the Counter is left at the Front door: side of mutton (1 side, " (:id parcel) ").")
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
