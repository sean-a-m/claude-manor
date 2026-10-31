(ns manor.deliveries-test
  "Orders and deliveries on a tiny, test-owned world: a shop that sends goods
  out, a house with a front door (the delivery place) and a hall."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [manor.actions :as a]
            [manor.clock :as clock]
            [manor.engine :as e]
            [manor.test-support :as support]
            [manor.io.store :as store]
            [manor.mechanics.hiring :as hiring]
            [manor.mechanics.items :as items]
            [manor.mechanics.items.judge :as item-judge]
            [manor.mechanics.items.steward :as item-steward]
            [manor.mechanics.positions :as positions]
            [manor.mechanics.space :as space]
            [manor.narrate :as n]
            [manor.prompt :as prompt]
            [manor.ruleset :as ruleset]
            [manor.ruleset.actions :as ruleset-actions]
            [manor.ruleset.hibernation :as hibernation]
            [manor.mechanics.deliveries :as deliveries]
            [manor.mechanics.shop :as shop]
            [manor.world :as w]))

(use-fixtures :once support/with-runs)

(def profile "A tiny rural English butcher's and grocer's, 1904. Meat and household groceries over the counter.")
(def delivery-profile "Larger orders are sent up to houses in the neighbourhood.")

(def agents [{:name "Customer" :role :secretary :location "counter"}
             {:name "Clerk" :role :shopkeeper :location "counter"}
             {:name "Butler" :role :bystander :location "hall"}])

(def shopping {:for "customer" :goods [{:item "tea" :amount "1"}
                                       {:item "side of mutton" :amount "1" :deliver true}]})

(defn appraisal
  "Tea over the counter; anything asked for delivery comes in two hours."
  [{:keys [request]}]
  {:tool :appraise
   :args {:lines (for [{:keys [item deliver]} request]
                   (cond-> {:available true :item item :qty 1 :unit (if deliver "1 side" "1 lb")
                            :desc (str "Good " item ".") :condition "fresh"
                            :consumable true :unit-price (if deliver 120 30)}
                     deliver (assoc :lead-hours 2)))}})

(defn policy
  "The mutton is ordered in round 2 (due in round 6) and again in round 9 (due in round 13)."
  [{:keys [world agent-id used]}]
  (cond
    (and (= "clerk" agent-id) (#{1 8} (:round world)) (not (some #{"quote-goods"} used)))
    {:tool :quote-goods :args shopping}
    (and (= "customer" agent-id) (#{2 9} (:round world)) (seq (get-in world [:state :quotes])))
    {:tool :buy :args {:quote-ids (vec (sort (keys (get-in world [:state :quotes])))) :hand-over "take"}}
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
             :state (merge {:setting "Rural England, 1904." :accounts {"household" 600}
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

(deftest private-orders-and-sent-up-goods-keep-their-owner
  (let [base (:init (scenario {:delivery-profile delivery-profile}))
        own-policy (fn [{:keys [world agent-id] :as ctx}]
                     (let [reply (policy ctx)
                           waiting (shop/waiting-here world agent-id)]
                       (cond
                         (= :buy (:tool reply)) (update reply :args assoc :from "own" :hand-over "leave")
                         (and (= "clerk" agent-id) (= 3 (:round world)) (seq waiting))
                         {:tool :send-up :args {:items (mapv :id waiting) :within-hours 1}}
                         :else reply)))
        run (start {:delivery-profile delivery-profile}
                   {:init (fn [opts] (assoc-in (base opts) [:state :accounts "customer"] 200))
                    :mock-policy own-policy})]
    (try
      (rounds! run 3)
      (let [world (e/world run)]
        (is (= {"household" 600 "customer" 50} (get-in world [:state :accounts])))
        (is (= ["customer" "customer"] (map :owner (vals (deliveries/orders world)))))
        (is (= "customer" (get-in (first (of-type run :deliveries/ordered)) [:data :account]))))
      (rounds! run 3)
      (let [world (e/world run)
            goods (items/items-at world "front-door")]
        (is (= #{"tea" "side of mutton"} (set (map :name goods))))
        (is (every? #(= "customer" (:owner %)) goods))
        (is (= {"household" 600 "customer" 50} (get-in world [:state :accounts])))
        (is (= world (w/replay (store/read-events (:dir run))))))
      (finally (e/stop-run run)))))

(deftest an-order-is-paid-now-and-delivered-later
  (let [run (start)]
    (try
      (rounds! run 2)
      (let [world (e/world run)
            [order] (vals (deliveries/orders world))
            [ordered] (of-type run :deliveries/ordered)
            names (n/names world)]
        (testing "one buy: tea handed over, the mutton ordered, both paid for"
          (is (= (- 600 150) (get-in world [:state :accounts "household"])))
          (is (empty? (get-in world [:state :quotes])))
          (is (= ["tea"] (map :name (items/held-by world "customer"))))
          (is (= {:item "side of mutton" :buyer "customer" :clerk "clerk" :location "counter" :to "front-door" :due 6}
                 (select-keys order [:item :buyer :clerk :location :to :due]))))
        (testing "the appraiser sees the delivery profile and which lines are for delivery"
          (let [sent (->> (store/read-llm (:dir run)) (filter #(= "adjudicator/shop/appraise" (:agent %)))
                          first :messages (map :content) (str/join "\n"))]
            (is (str/includes? sent delivery-profile))
            (is (str/includes? sent "{\"item\":\"side of mutton\",\"amount\":\"1\",\"deliver\":true}"))
            (is (str/includes? sent "{\"item\":\"tea\",\"amount\":\"1\"}"))))
        (testing "the quote and the order say when and where"
          (is (str/includes? (n/describe (first (filter #(get-in % [:data :deliver]) (of-type run :shop/goods-quoted))) names)
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
            (is (= ["loose mutton"] (map :name (get (item-steward/steward-candidates later {:idle-hours 4 :max-per-call 8}) "front-door"))))))
        (is (= world (w/replay (store/read-events (:dir run)))))
        (is (not-any? #(re-find #"^(error:|rejected:)" (:content % ""))
                      (mapcat :replies (store/read-llm (:dir run))))))
      (finally (e/stop-run run)))))

(deftest a-delivery-quote-is-ordered-whatever-the-hand-over
  (let [leave (fn [{:keys [world agent-id] :as ctx}]
                (if (and (= "customer" agent-id) (= 2 (:round world)) (seq (get-in world [:state :quotes])))
                  {:tool :buy :args {:quote-ids (vec (sort (keys (get-in world [:state :quotes])))) :hand-over "leave"}}
                  (policy ctx)))
        run (start {:delivery-profile delivery-profile} {:mock-policy leave})]
    (try
      (rounds! run 2)
      (let [world (e/world run)]
        (is (= ["side of mutton"] (map :item (vals (deliveries/orders world)))))
        (is (= [["tea" "counter"]] (map (juxt :name :place) (items/items-where world :waiting))))
        (is (= ["tea"] (map :item (:waiting-at-shops (shop/observe world "customer")))))
        (is (= (- 600 150) (get-in world [:state :accounts "household"])))
        (is (some #{"Paid: the goods are left at the shop for you (waiting-at-shops in your view); goods for delivery are under orders in your view."}
                  (map :content (mapcat :replies (filter #(= "customer" (:agent %)) (store/read-llm (:dir run)))))))
        (is (= world (w/replay (store/read-events (:dir run))))))
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
  (let [line {:available true :item "side of mutton" :qty 1 :unit "1 side" :desc "A side." :condition "fresh"
              :consumable true :unit-price 120}]
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

(deftest held-couriers-stay-until-they-can-move
  (doseq [[faculty released-level] [[:movement :normal]
                                   [:movement :impeded]
                                   [:consciousness :normal]]]
    (testing (str faculty " disabled, then " released-level)
      (let [run (start)]
        (try
          (rounds! run 6)
          (let [id (:agent-id (courier (e/world run)))]
            @(e/afflict! run id {:faculty faculty :level :disabled})
            (rounds! run 3)
            (let [world (e/world run)]
              (is (w/active? world id))
              (is (= "front-door" (space/location-of world id)))
              (is (= 6 (:arrived (courier world))))
              (is (empty? (of-type run :people/departed)))
              (is (= world (w/replay (store/read-events (:dir run))))))
            @(e/afflict! run id {:faculty faculty :level released-level})
            (rounds! run 1)
            (let [world (e/world run)]
              (is (= :away (:status (w/agent world id))))
              (is (nil? (space/location-of world id)))
              (is (= [10] (map :round (of-type run :people/departed))))
              (is (= world (w/replay (store/read-events (:dir run)))))))
          (finally (e/stop-run run)))))))

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

(deftest a-courier-staying-as-a-guest-keeps-their-situation
  (let [run (start)]
    (try
      (rounds! run 6)
      (let [world (e/world run)
            later (-> world
                      (assoc-in [:state :orders "order-late"] (assoc (:data (first (of-type run :deliveries/ordered)))
                                                                     :id "order-late" :due 7))
                      (assoc :round 7))
            guest (assoc-in later [:state :visits "visit-1"] {:id "visit-1" :guest "pat-ellis-0" :status :here})
            updated (fn [events] (filter #(and (= :agent/updated (:type %)) (= "pat-ellis-0" (get-in % [:data :id]))) events))]
        (is (= :active (:status (w/agent world "pat-ellis-0"))))
        (is (seq (updated (deliveries/tick (:scenario run) later {}))) "a courier who is here is given the new delivery")
        (let [events (deliveries/tick (:scenario run) guest {})]
          (is (some #(= :deliveries/arrived (:type %)) events))
          (is (empty? (updated events)) "a guest keeps the visit's situation")))
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
        (is (= [line] (map :sent-up-by (:orders (shop/observe world "customer")))))
        (is (= [line] (map :sent-up-by (:orders (shop/observe world "clerk")))))
        (is (not (contains? (:here (shop/observe world "clerk")) :orders-sent-up-by)) "named only on orders, not at the counter")
        (is (not-any? #(str/starts-with? (:agent %) "adjudicator/people") (store/read-llm (:dir run)))
            "no model call until a delivery is due"))
      (finally (e/stop-run run)))))

(deftest the-clerk-and-the-judges-know-an-order-is-in-hand
  (let [run (start)]
    (try
      (rounds! run 2)
      (let [world (e/world run)
            [told] (of-type run :deliveries/in-hand)
            line "Pat Ellis 0, the Counter's errand boy"
            json-of (fn [content] (json/read-value (or (second (re-find #"(?s)```json\n(.*)\n```" content)) content)
                                                   json/keyword-keys-object-mapper))
            job {:id "job-9" :actor "clerk" :items [] :doing "packing the mutton for the house"
                 :place "counter" :started 2 :due 3}
            judged (item-judge/judge-request (:scenario run) world job {:minutes 30 :luck 50})
            finder ((get-in (:scenario run) [:adjudicators :items/find :request]) world "clerk" {:what "the mutton"} [])]
        (testing "buying tells the clerk alone that the order is in hand"
          (is (w/visible-to? told "clerk"))
          (is (not (w/visible-to? told "customer")))
          (is (= (str "Customer's order from the Counter (side of mutton (1 side) by 12:30) is in hand: it's made up and "
                      "taken to the Front door by " line " when due (orders in your view). "
                      "There is nothing for you to make up, pack or carry.")
                 (n/describe told (n/names world)))))
        (testing "the work judge sees the worker's orders"
          (is (= [{:goods "side of mutton (1 side)" :shop "Counter" :buyer "Customer" :to "Front door" :due "12:30" :sent_up_by line}]
                 (:orders (json-of (:content (second (:messages judged)))))))
          (is (nil? (-> (item-judge/judge-request (:scenario run) world (assoc job :actor "butler") {:minutes 30 :luck 50})
                        :messages second :content json-of :orders))))
        (testing "the finder sees the searcher's orders and is told they're never found"
          (is (= ["side of mutton (1 side)"] (map :goods (get-in (json-of (:content (second (:messages finder)))) [:searcher :orders]))))
          (is (str/includes? (:content (first (:messages finder))) "until then they are nowhere to be found")))
        (is (= world (w/replay (store/read-events (:dir run))))))
      (finally (e/stop-run run)))))

(defn- butler-goes-out
  "The butler walks to the front door in round 7, the round after the mutton comes."
  [{:keys [world agent-id used] :as ctx}]
  (if (and (= "butler" agent-id) (= 7 (:round world)) (not (some #{"walk"} used)))
    {:tool :walk :args {:to "front-door"}}
    (policy ctx)))

(deftest a-courier-waits-out-of-play-until-someone-comes
  (let [run (start {:delivery-profile delivery-profile}
                   {:hibernate hibernation/hibernate :on-spawn positions/starting-events :mock-policy butler-goes-out
                    :actions (into shop/actions (filter #(= :walk (:name %)) ruleset-actions/actions))})]
    (try
      (rounds! run 6)
      (is (:hibernating? (w/busy (e/world run) "pat-ellis-0")) "alone at the door on arriving")
      (rounds! run 1)
      (let [calls (filter #(= "pat-ellis-0" (:agent %)) (store/read-llm (:dir run)))]
        (testing "no turns until the butler comes out, then one"
          (is (= #{7} (set (map :round calls)))))
        (is (= (e/world run) (w/replay (store/read-events (:dir run))))))
      (finally (e/stop-run run)))))

(deftest returning-couriers-are-not-moved-while-disabled-or-dead
  (let [run (start)]
    (try
      (rounds! run 9)
      (let [world (e/world run) id (:agent-id (courier world))
            due (assoc world :round 13)
            returned (fn [world] (filter #(= :people/returned (:type %)) (deliveries/tick (:scenario run) world {})))]
        (is (= :away (:status (w/agent world id))))
        (doseq [faculty [:movement :consciousness]]
          (is (empty? (returned (assoc-in due [:agents id :physical faculty] {:level :disabled})))))
        (is (= [id] (map #(get-in % [:data :id]) (returned (assoc-in due [:agents id :physical :movement] {:level :impeded})))))
        (is (empty? (returned (w/apply-event due {:type :person/died :data {:person id :cause "illness"}})))))
      (is (= (e/world run) (w/replay (store/read-events (:dir run)))))
      (finally (e/stop-run run)))))

;; ---------------------------------------------------------------------------
;; send-up: goods left waiting at the counter become orders

(def tea {:for "customer" :goods [{:item "tea" :amount "1"}]})

(defn- leave-tea
  "The clerk quotes tea in round 1, the customer buys it in round 2 and
  leaves it at the counter; then `then`."
  [then]
  (fn [{:keys [world agent-id used] :as ctx}]
    (cond
      (and (= "clerk" agent-id) (= 1 (:round world)) (not (some #{"quote-goods"} used)))
      {:tool :quote-goods :args tea}
      (and (= "customer" agent-id) (= 2 (:round world)) (seq (get-in world [:state :quotes])))
      {:tool :buy :args {:quote-ids (vec (keys (get-in world [:state :quotes]))) :hand-over "leave"}}
      :else (then ctx))))

(def send-up-tea
  "The clerk sends the tea up in round 3, within the hour."
  (leave-tea (fn [{:keys [world agent-id used]}]
               (if (and (= "clerk" agent-id) (= 3 (:round world)) (not (some #{"send-up"} used)))
                 {:tool :send-up :args {:items ["tea"] :within-hours 1}}
                 {:tool :pass}))))

(deftest goods-waiting-at-the-shop-are-sent-up
  (let [run (start {:delivery-profile delivery-profile} {:mock-policy send-up-tea})]
    (try
      (rounds! run 3)
      (let [world (e/world run)
            [sent] (of-type run :deliveries/sent-up)
            order (get (deliveries/orders world) "order-tea")
            replies (map :content (mapcat :replies (filter #(= "clerk" (:agent %)) (store/read-llm (:dir run)))))]
        (testing "the tea becomes an order carrying the item, already paid for, due within the hour"
          (is (= {:id "order-tea" :item-id "tea" :location "counter" :clerk "clerk" :buyer "customer"
                  :item "tea" :desc "Good tea." :aspects {:amount "1 lb" :state "fresh"} :consumable true
                  :owner "household" :to "front-door" :due 5}
                 order))
          (is (= (- 600 30) (get-in world [:state :accounts "household"])) "paid once, at the buy"))
        (testing "it's gone from the counter and from the customer's waiting goods"
          (is (nil? (items/item world "tea")))
          (is (= {:name "tea" :place "counter" :round 3 :fate "sent up by the shop's courier"}
                 (get-in world [:items-gone "tea"])))
          (is (empty? (items/items-where world :waiting)))
          (is (not (contains? (shop/observe world "customer") :waiting-at-shops))))
        (testing "the counter sees it sent up, and the buyer and clerk find it under orders"
          (is (= "Clerk sends up tea (1 lb) for Customer from the Counter, to be delivered at the Front door by 12:00."
                 (n/describe sent (n/names world))))
          (is (w/visible-to? sent "customer"))
          (is (not (w/visible-to? sent "butler")))
          (doseq [id ["customer" "clerk"]]
            (is (= [{:item "tea" :amount "1 lb" :from "counter" :buyer "customer" :to "front-door" :due "12:00"
                     :sent-up-by "Pat Ellis 0, the Counter's errand boy"}]
                   (:orders (shop/observe world id)))))
          (is (nil? (:orders (shop/observe world "butler")))))
        (testing "the clerk is told it's with the courier, with nothing to pack or carry"
          (is (some #{(str "Clerk sends up tea (1 lb) for Customer from the Counter, to be delivered at the Front door by 12:00.\n"
                           "The shop's courier takes them up when due (orders in your view); there is nothing for you to make up, pack or carry.")}
                    replies))))
      (rounds! run 2)
      (let [world (e/world run)
            [arrived] (of-type run :deliveries/arrived)
            [parcel] (items/items-where world #(= "front-door" (:place %)))]
        (testing "at the due round a parcel with the item's aspects is left at the front door, and the house hears it"
          (is (= 5 (:round arrived)))
          (is (empty? (deliveries/orders world)))
          (is (= {:id "tea-2" :name "tea" :desc "Good tea." :owner "household" :consumable? true
                  :aspects {:amount "1 lb" :state "fresh"} :order-id "order-tea"}
                 (select-keys parcel [:id :name :desc :owner :consumable? :aspects :order-id :waiting])))
          (is (= "Pat Ellis 0 (pat-ellis-0) brings a delivery from the Counter and leaves it at the Front door: tea (1 lb, tea-2)."
                 (n/describe arrived (n/names world))))
          (is (w/visible-to? arrived "butler")))
        (is (= world (w/replay (store/read-events (:dir run)))))
        (is (not-any? #(= :action/forfeited (:type %)) (e/events run)))
        (is (not-any? #(re-find #"^(error:|rejected:)" (:content % ""))
                      (mapcat :replies (store/read-llm (:dir run))))))
      (finally (e/stop-run run)))))

(defn- send-up-action [] (first (filter #(= :send-up (:name %)) shop/actions)))

(defn- with-goods
  "`world` with items added as they are."
  [world goods]
  (reduce w/apply-event world (items/init-events goods)))

(def ^:private kept {:for "customer" :keeper "clerk" :at "counter" :since 1})

(deftest send-up-refusals
  (let [run (start)]
    (try
      (let [world (with-goods (e/world run)
                    [{:id "tea" :name "tea" :desc "Tea." :place "counter" :waiting kept}
                     {:id "soap" :name "soap" :desc "Soap." :place "counter"}
                     {:id "moved" :name "moved" :desc "Moved." :place "front-door" :waiting kept}
                     {:id "theirs" :name "theirs" :desc "Kept at the door." :place "front-door" :waiting (assoc kept :at "front-door")}
                     {:id "basket" :name "basket" :desc "A basket." :place "counter" :container :open :waiting kept}
                     {:id "egg" :name "egg" :desc "An egg." :in "basket"}])
            reason (fn [world actor ids] (shop/send-up-reason world actor {:items ids :within-hours 1}))]
        (is (nil? (reason world "clerk" ["tea"])))
        (testing "only the clerk, at a shop that sends goods out, open or not"
          (is (= "only someone who keeps a shop can send goods up" (reason world "customer" ["tea"])))
          (is (nil? (reason (assoc world :round 20) "clerk" ["tea"])))
          (is (= "go to your shop first" (reason (assoc-in world [:agents "clerk" :location] "hall") "clerk" ["tea"])))
          (is (= "the Counter doesn't send goods out; hand them over when they're collected"
                 (reason (update-in world [:space :locations "counter"] dissoc :delivery-profile) "clerk" ["tea"]))))
        (testing "only goods waiting at this shop, each once, with nothing in them"
          (doseq [id ["goose-12" "soap" "moved" "theirs"]]
            (is (= (str id " isn't waiting at this shop; send up only goods marked waiting-for someone in your view")
                   (reason world "clerk" [id]))
                id))
          (is (= "give each item once" (reason world "clerk" ["tea" "tea"])))
          (is (= "take what's in basket out first" (reason world "clerk" ["basket"]))))
        (testing "within-hours is 1 to 3"
          (doseq [h [0 4]]
            (is (:error (a/decode-args (send-up-action) {:items ["tea"] :within-hours h}))))
          (is (= {:items ["tea"] :within-hours 3}
                 (:args (a/decode-args (send-up-action) {"items" ["tea"] "within_hours" 3})))))
        (testing "a late send-up comes at the next opening"
          (let [late (assoc world :round 16)
                {:keys [due]} (get-in (deliveries/sent-up late "clerk" ["tea"] 3) [:data :orders 0])]
            (is (= "1904-10-15T17:30" (str (clock/time-of (:clock world) 16))))
            (is (= "1904-10-16T08:00" (str (clock/time-of (:clock world) due)))))))
      (finally (e/stop-run run)))))

(defn- send-sugar
  "The clerk sends a sack of sugar, and a carriage the shop can't supply, up
  for the butler in round 1, without the butler there."
  [{:keys [world agent-id used] :as ctx}]
  (if (and (= "clerk" agent-id) (= 1 (:round world)) (not (some #{"send-up"} used)))
    {:tool :send-up :args {:for "butler" :goods [{:item "sugar" :amount "1½ stone"} {:item "carriage" :amount "1"}]}}
    {:tool :pass}))

(defn- appraise-sugar
  "Sugar comes in two hours at 4s; there's no carriage to be had."
  [{:keys [request]}]
  {:tool :appraise
   :args {:lines (for [{:keys [item deliver]} request]
                   (if (= "carriage" item)
                     {:available false :reason "We don't deal in carriages."}
                     {:available true :item item :qty 3 :unit "7 lb" :desc "Loaf sugar in a sack." :condition "dry"
                      :consumable true :unit-price 16 :lead-hours (when deliver 2)}))}})

(defn- sending-sugar [funds]
  (let [base (:init (scenario {:delivery-profile delivery-profile}))]
    (start {:delivery-profile delivery-profile}
           {:init (fn [opts] (assoc-in (base opts) [:state :accounts "household"] funds))
            :mock-policy send-sugar
            :adjudicators (merge hiring/adjudicators
                                 (assoc-in shop/adjudicators [:shop/send-up :mock] appraise-sugar))})))

(deftest a-shopkeeper-sends-new-goods-up-on-the-household-account
  (let [run (sending-sugar 600)]
    (try
      (rounds! run 1)
      (let [world (e/world run)
            [sent] (of-type run :deliveries/sent-out)
            [refused] (of-type run :shop/goods-refused)
            names (n/names world)]
        (testing "the goods are charged to the household at once and ordered for the butler"
          (is (= (- 600 48) (get-in world [:state :accounts "household"])))
          (is (= (str "Clerk sends up sugar (3 × 7 lb) for 4s by 12:00 for Butler from the Counter, "
                      "charged to the household account, to be delivered at the Front door.")
                 (n/describe sent names)))
          (is (= [{:item "sugar" :amount "3 × 7 lb" :from "counter" :buyer "butler" :to "front-door" :due "12:00"
                   :sent-up-by "Pat Ellis 0, the Counter's errand boy"}]
                 (:orders (shop/observe world "butler")))))
        (testing "the butler is told, though not at the shop"
          (is (w/visible-to? sent "butler"))
          (is (w/visible-to? sent "clerk")))
        (testing "a line the shop can't supply is refused"
          (is (= "Clerk cannot offer carriage to Butler: We don't deal in carriages." (n/describe refused names)))))
      (rounds! run 4)
      (let [world (e/world run)
            [sugar] (filter #(= "sugar" (:name %)) (items/items-at world "front-door"))]
        (is (= {:owner "household" :aspects {:amount "3 × 7 lb" :state "dry"}} (select-keys sugar [:owner :aspects])))
        (is (= world (w/replay (store/read-events (:dir run))))))
      (finally (e/stop-run run)))))

(deftest goods-the-household-cant-cover-are-not-sent
  (let [run (sending-sugar 30)]
    (try
      (rounds! run 1)
      (let [world (e/world run)
            reasons (map #(get-in % [:data :reason]) (of-type run :shop/goods-refused))]
        (is (empty? (of-type run :deliveries/sent-out)))
        (is (= 30 (get-in world [:state :accounts "household"])))
        (is (some #{"the household account can't cover 4s"} reasons) "without the balance"))
      (finally (e/stop-run run)))))

(deftest a-shopkeeper-can-quote-and-send-up-on-one-line
  (let [ask (fn [{:keys [world agent-id used]}]
              (if (and (= "customer" agent-id) (= 1 (:round world)) (not (some #{"converse"} used)))
                {:tool :converse :args {:with ["clerk"] :text "Tea, please, and send a sack of sugar up to the house."}}
                {:tool :pass}))
        line (fn [{:keys [agent-id used]}]
               (cond
                 (not= "clerk" agent-id) {:tool :end-conversation}
                 (not (some #{"quote-goods"} used)) {:tool :quote-goods :args {:for "customer" :goods [{:item "tea" :amount "1"}]}}
                 (not (some #{"send-up"} used)) {:tool :send-up :args {:for "customer" :goods [{:item "sugar" :amount "1½ stone"}]}}
                 :else {:tool :reply :args {:text "There's your tea, and the sugar goes up by noon."}}))
        run (start {:delivery-profile delivery-profile}
                   {:mock-policy ask :speaking-actions ruleset/speaking-actions :mock-conversation line})]
    (try
      (rounds! run 1)
      (let [evs (e/events run)
            id-of (fn [t] (:id (first (filter #(= t (:type %)) evs))))
            replies (->> (store/read-llm (:dir run)) (filter #(and (= "clerk" (:agent %)) (:conversation %)))
                         (mapcat :messages) (filter #(= "tool" (:role %))) (map :content))]
        (is (< (id-of :conversation/started) (id-of :shop/goods-quoted) (id-of :deliveries/sent-out) (id-of :conversation/ended))
            "quoted and sent up on the clerk's line")
        (is (not-any? #(str/includes? (str %) "query limit") replies))
        (is (= (e/world run) (w/replay (store/read-events (:dir run))))))
      (finally (e/stop-run run)))))

(deftest sending-up-on-a-line
  (let [send-up? (fn [world speaker] (some #(= :send-up (:name %)) (ruleset/speaking-actions world speaker {})))
        ask (leave-tea (fn [{:keys [world agent-id used]}]
                         (if (and (= "customer" agent-id) (= 3 (:round world)) (not (some #{"converse"} used)))
                           {:tool :converse :args {:with ["clerk"] :text "Would you send my tea up to the house?"}}
                           {:tool :pass})))
        line (fn [{:keys [world agent-id used]}]
               (cond
                 (not= "clerk" agent-id) {:tool :end-conversation}
                 (and (seq (shop/waiting-here world "clerk")) (not (some #{"send-up"} used)))
                 {:tool :send-up :args {:items (mapv :id (shop/waiting-here world "clerk")) :within-hours 2}}
                 :else {:tool :reply :args {:text "The boy will have it up directly."}}))
        run (start {:delivery-profile delivery-profile}
                   {:mock-policy ask :speaking-actions ruleset/speaking-actions :mock-conversation line})]
    (try
      (rounds! run 2)
      (testing "send-up is a speaking-turn tool for the keeper of a delivering shop, open or not"
        (let [world (e/world run)]
          (is (send-up? world "clerk"))
          (is (not (send-up? world "customer")))
          (is (not (send-up? (update-in world [:space :locations "counter"] dissoc :delivery-profile) "clerk")))
          (is (send-up? (assoc world :round 20) "clerk"))))
      (rounds! run 1)
      (let [evs (e/events run)
            id-of (fn [t] (:id (first (filter #(= t (:type %)) evs))))
            calls (filter #(and (= "clerk" (:agent %)) (:conversation %)) (store/read-llm (:dir run)))
            world (e/world run)]
        (is (< (id-of :conversation/started) (id-of :deliveries/sent-up) (id-of :conversation/ended))
            "sent up on the clerk's line")
        (is (some #(some #{"send-up"} (:tools %)) calls))
        (is (= [{:item "tea" :due "13:00"}] (map #(select-keys % [:item :due]) (:orders (shop/observe world "customer")))))
        (is (empty? (shop/waiting-here world "clerk")))
        (is (not-any? #(= :action/forfeited (:type %)) evs))
        (is (= world (w/replay (store/read-events (:dir run))))))
      (finally (e/stop-run run)))))

(defn- of-the-household
  "The fixture's init with the customer and the butler of the household and
  the clerk keeping the counter."
  [init]
  (fn [opts]
    (-> (init opts)
        (assoc-in [:state :posts] {"customer" {:position :secretary :household "household"}
                                   "butler" {:position :bystander :household "household"}})
        (assoc-in [:state :positions :shopkeeper :workplace] ["counter"]))))

(def ^:private book-day
  "Round 1 the clerk quotes tea and the mutton for delivery; round 2 the
  customer buys both, leaving the tea; round 3 the clerk sends the tea up
  and sugar the house asked for."
  (fn [{:keys [world agent-id used] :as ctx}]
    (cond
      (and (= "clerk" agent-id) (= 3 (:round world)) (not (some #{"send-up"} used)))
      {:tool :send-up :args {:items ["tea"] :within-hours 1 :goods [{:item "sugar" :amount "1"}] :for "customer"}}
      (= :buy (:tool (policy ctx))) (assoc-in (policy ctx) [:args :hand-over] "leave")
      (and (= "clerk" agent-id) (not= 1 (:round world))) {:tool :pass}
      :else (policy ctx))))

(deftest the-order-books-show-what-was-bought-and-where-it-is
  (let [base (:init (scenario {:delivery-profile delivery-profile}))
        run (start {:delivery-profile delivery-profile} {:init (of-the-household base) :mock-policy book-day})
        book #(get-in (shop/observe (e/world run) %) [:household :orders])
        statuses (fn [entries] (into {} (for [{:keys [goods status]} entries, g goods] [g status])))]
    (try
      (testing "before anything is bought the books are shown, empty"
        (is (= [] (book "customer")) (= [] (book "butler")))
        (is (= [] (:shop-orders (shop/observe (e/world run) "clerk"))))
        (is (nil? (get-in (shop/observe (e/world run) "clerk") [:household :orders])) "the clerk isn't of the household")
        (is (nil? (:shop-orders (shop/observe (e/world run) "customer")))))
      (rounds! run 2)
      (let [world (e/world run)]
        (testing "a purchase is in the household's book for everyone of it, and in the shop's"
          (is (= [{:from "Counter" :for "Customer" :bought "10:30"
                   :goods ["tea (1 lb), 2s 6d"] :total "2s 6d"
                   :status "paid for, waiting at the shop to be collected or sent up"}
                  {:from "Counter" :for "Customer" :bought "10:30"
                   :goods ["side of mutton (1 side), 10s"] :total "10s"
                   :status "to be delivered at the Front door by 12:30"
                   :sent-up-by "Pat Ellis 0, the Counter's errand boy"}]
                 (book "customer") (book "butler") (:shop-orders (shop/observe world "clerk"))))
          (is (nil? (:orders (shop/observe world "customer"))) "not listed twice")
          (is (nil? (:orders (shop/observe world "clerk")))))
        (testing "the work judge, the finder and the conversation judge see the books"
          (let [json-of (fn [content] (json/read-value (or (second (re-find #"(?s)```json\n(.*)\n```" content)) content)
                                                       json/keyword-keys-object-mapper))
                job {:id "job-9" :actor "butler" :items [] :doing "writing out an order for the butcher"
                     :place "hall" :started 2 :due 3}
                scene #(json-of (:content (second (:messages (item-judge/judge-request (:scenario run) world (assoc job :actor %)
                                                                                         {:minutes 30 :luck 50})))))
                finder ((get-in (:scenario run) [:adjudicators :items/find :request]) world "butler" {:what "the mutton"} [])
                talk ((get-in (:scenario run) [:order-books]) world ["butler" "clerk"])]
            (is (= (book "butler") (:household_orders (scene "butler"))))
            (is (nil? (:shop_orders (scene "butler"))))
            (is (= (book "butler") (:shop_orders (scene "clerk"))))
            (is (nil? (:orders (scene "clerk"))) "the clerk's order is in their shop's book")
            (is (= (book "butler") (get-in (json-of (:content (second (:messages finder)))) [:searcher :household_orders])))
            (is (= #{:household_orders :shop_orders} (set (keys talk)))))))
      (rounds! run 1)
      (let [world (e/world run)]
        (testing "goods sent up are to be delivered, and new goods sent up say who sent them"
          (is (= {"tea (1 lb), 2s 6d" "to be delivered at the Front door by 12:00"
                  "side of mutton (1 side), 10s" "to be delivered at the Front door by 12:30"
                  "sugar (one), 1s 6d" "sent up by Clerk, to be delivered at the Front door by 13:00"}
                 (statuses (book "customer"))))))
      (rounds! run 4)
      (let [world (e/world run)]
        (testing "delivered goods stay in the book, marked delivered"
          (is (= {"tea (1 lb), 2s 6d" "delivered at the Front door at 12:00"
                  "side of mutton (1 side), 10s" "delivered at the Front door at 12:30"
                  "sugar (one), 1s 6d" "delivered at the Front door at 13:00"}
                 (statuses (book "customer"))))
          (is (= (book "customer") (:shop-orders (shop/observe world "clerk")))))
        (is (= world (w/replay (store/read-events (:dir run))))))
      (finally (e/stop-run run)))))
