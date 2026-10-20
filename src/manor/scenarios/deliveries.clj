(ns manor.scenarios.deliveries
  "Orders sent up from a shop: a delivery quote bought at the counter becomes
  an order, paid for at once, and at its due round the goods are left at the
  delivery place (hiring's meeting place). A shop delivers when its location
  has a :delivery-profile. See docs/deliveries-design.md."
  (:require [clojure.string :as str]
            [manor.clock :as clock]
            [manor.items :as items]
            [manor.money :as money]
            [manor.narrate :as n]
            [manor.scenarios.building :as building]
            [manor.space :as space]
            [manor.world :as w]))

(def max-lead-hours 120)
(def max-profile 400)

(defn delivers?
  "Does the shop at `loc` (a location) send goods out?"
  [loc]
  (not (str/blank? (:delivery-profile loc))))

(defn delivery-place [world] (get-in world [:state :meeting-place]))

(defn due-round
  "The round an order placed now with `lead-hours` arrives: at least next
  round, and within the shop's opening `hours`, else at its next opening."
  [world lead-hours [open :as hours]]
  (let [c (:clock world)
        due (+ (:round world) (clock/rounds-for c (* 60 lead-hours)))]
    (if (or (nil? hours) (building/open-now? (assoc world :round due) hours))
      due
      (clock/round-at c due open))))

(defn amount
  "What `qty` of `unit` comes to: \"1 tin\", or \"20 × 1 lb\"."
  [qty unit]
  (if (= 1 qty) unit (str qty " × " unit)))

(defn orders [world] (get-in world [:state :orders] {}))

(defn ordered
  "The event for buying delivery quote `quote-id`: paid now, delivered at the
  due round to the delivery place."
  [world buyer quote-id quote audience]
  (let [shop (space/location world (:location quote))
        due (due-round world (get-in quote [:deliver :lead-hours]) (:hours shop))]
    {:type :deliveries/ordered :visible-to audience
     :data (merge (select-keys quote [:location :clerk :item :unit :qty :price :desc :condition :consumable])
                  {:id (str/replace quote-id #"^quote-" "order-") :quote-id quote-id :buyer buyer
                   :due due :to (get-in quote [:deliver :to])
                   :due-label (clock/short-label (:clock world) due (:round world))})}))

(defmethod w/apply-event :deliveries/ordered [world {:keys [data]}]
  (-> world
      (update-in [:state :household :funds] - (:price data))
      (update-in [:state :quotes] dissoc (:quote-id data))
      (assoc-in [:state :orders (:id data)] (dissoc data :due-label))))

(defmethod n/describe :deliveries/ordered [{:keys [actor data]} names]
  (str (n/who names actor) " orders " (:item data) " (" (amount (:qty data) (:unit data)) ") for "
       (money/display (:price data)) " at the " (n/place names (:location data)) ", to be delivered at the "
       (n/place names (:to data)) " by " (:due-label data) "; order " (:id data) "."))

(defn hearers
  "Everyone within walking distance of `place`: the whole house hears the bell."
  [world place]
  (set (mapcat #(space/present world %) (space/area world place))))

(defn- arrival
  "The events of one shop's due orders arriving at the delivery place: a
  parcel for each, which the nightly steward leaves alone (:order-id), and
  the announcement."
  [world shop due]
  (let [place (delivery-place world)
        audience (hearers world place)
        parcels (items/added-events
                 world
                 (for [o due]
                   (cond-> {:name (:item o) :desc (:desc o) :place place :owner "household" :order-id (:id o)
                            :aspects {:amount (amount (:qty o) (:unit o)) :state (:condition o)}}
                     (:consumable o) (assoc :consumable? true)))
                 :gm)]
    (conj parcels
          {:type :deliveries/arrived :visible-to audience
           :data {:shop shop :at place :orders (mapv :id due)
                  :goods (mapv (fn [o e] {:id (get-in e [:data :item :id]) :name (:item o)
                                          :amount (amount (:qty o) (:unit o))})
                               due parcels)}})))

(defmethod w/apply-event :deliveries/arrived [world {:keys [data]}]
  (update-in world [:state :orders] #(apply dissoc % (:orders data))))

(defmethod n/describe :deliveries/arrived [{:keys [data]} names]
  (str "A delivery from the " (n/place names (:shop data)) " is left at the " (n/place names (:at data)) ": "
       (str/join ", " (for [{:keys [id name amount]} (:goods data)] (str name " (" amount ", " id ")"))) "."))

(defn tick
  "Every order due by this round arrives, a shop at a time in id order."
  [world _]
  (let [due (filter #(<= (:due %) (:round world)) (vals (orders world)))]
    (second
     (reduce (fn [[world evs] [shop due]]
               (let [new (arrival world shop (sort-by :id due))]
                 [(reduce w/apply-event world new) (into evs new)]))
             [world []]
             (sort-by key (group-by :location due))))))

(def arrivals {:name :deliveries/arrivals :tick tick})

(defn observe
  "The viewer's pending orders, as buyer or as the shop's clerk."
  [world id]
  (vec (for [o (sort-by :id (vals (orders world)))
             :when (or (= id (:buyer o)) (= id (:clerk o)))]
         {:id (:id o) :item (:item o) :amount (amount (:qty o) (:unit o)) :from (:location o)
          :buyer (:buyer o) :to (:to o) :due (clock/short-label (:clock world) (:due o) (:round world))})))

(defn problems
  "World-file checks for delivering shops."
  [{:keys [locations]}]
  (for [loc locations
        :when (contains? loc :delivery-profile)
        :when (not (and (string? (:delivery-profile loc)) (not (str/blank? (:delivery-profile loc)))
                        (<= (count (:delivery-profile loc)) max-profile)
                        (contains? (:services loc) :general-store)))]
    (str "location " (:id loc) ": :delivery-profile must be non-blank text of at most " max-profile
         " characters, on a shop with :services #{:general-store}")))
