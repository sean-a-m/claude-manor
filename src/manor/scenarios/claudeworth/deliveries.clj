(ns manor.scenarios.claudeworth.deliveries
  "Orders sent up from a shop: a delivery quote bought at the counter becomes
  an order, paid for at once, and at its due round the goods are left at the
  delivery place (hiring's meeting place), brought by the shop's courier.
  Goods bought and left waiting at the shop can be sent up too (`sent-up`):
  each becomes an order carrying the item itself. A shop delivers when its
  location has a :delivery-profile. Each shop's courier
  is named at the start (a free people-server record, so the shop and its
  customers know who sends orders up), written and spawned at its first
  delivery, and comes back for every delivery after. See docs/deliveries-design.md."
  (:require [clojure.string :as str]
            [manor.clock :as clock]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.hiring :as hiring]
            [manor.mechanics.items :as items]
            [manor.mechanics.physical :as physical]
            [manor.mechanics.positions :as positions]
            [manor.mechanics.space :as space]
            [manor.mechanics.visits :as visits]
            [manor.narrate :as n]
            [manor.settings :as settings]
            [manor.scenarios.claudeworth.building :as building]
            [manor.scenarios.claudeworth.money :as money]
            [manor.world :as w]))

(def max-lead-hours 120)
;; Goods waiting at a shop are sent up within at most this many hours.
(def send-up-max-hours 3)
(def max-profile 400)
;; A courier goes home once this many rounds pass without a conversation.
(def courier-leave-after 2)
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

(defn order-amount
  "How much an order is: its quantity of units, or for goods sent up from
  the shop, the item's :amount aspect (nil if it has none)."
  [o]
  (if (:unit o) (amount (:qty o) (:unit o)) (get-in o [:aspects :amount])))

(defn- goods-label
  "An order's goods as lines name them: \"tea (1 lb)\", or the name alone."
  [o]
  (str (:item o) (when-let [a (order-amount o)] (str " (" a ")"))))

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

;; The order's id is the engine's: no tool takes it, so it's never shown, or
;; characters take it up as a reference number ("order-312") in their talk.
(defmethod n/describe :deliveries/ordered [{:keys [actor data]} names]
  (str (n/who names actor) " orders " (:item data) " (" (amount (:qty data) (:unit data)) ") for "
       (money/display (get names [:money]) (:price data)) " at the " (n/place names (:location data)) ", to be delivered at the "
       (n/place names (:to data)) " by " (:due-label data) "."))

(defn sent-up-id
  "The id of the order goods waiting at a shop become when sent up."
  [item-id]
  (str "order-" item-id))

(defn sent-up
  "The event for `clerk` sending up `item-ids`, goods waiting at their shop:
  each becomes an order (its id from the item's) for the person the goods
  wait for, carrying the item's name, description, aspects, owner and
  consumability, due within `within-hours` at the delivery place. The goods
  are paid for already, so it has no price."
  [world clerk item-ids within-hours]
  (let [shop (space/location-of world clerk)
        place (delivery-place world)
        due (due-round world within-hours (:hours (space/location world shop)))]
    {:type :deliveries/sent-up :visible-to (space/here world clerk)
     :data {:location shop :to place :due-label (clock/short-label (:clock world) due (:round world))
            :orders (vec (for [id item-ids
                               :let [it (items/item world id)]]
                           (cond-> {:id (sent-up-id id) :item-id id :location shop :clerk clerk
                                    :buyer (get-in it [:waiting :for]) :item (:name it) :desc (:desc it)
                                    :consumable (boolean (:consumable? it)) :to place :due due}
                             (:aspects it) (assoc :aspects (:aspects it))
                             (:owner it) (assoc :owner (:owner it)))))}}))

(defmethod w/apply-event :deliveries/sent-up [world {:keys [data]}]
  (-> world
      (items/remove-items (map :item-id (:orders data)) "sent up by the shop's courier")
      (update-in [:state :orders] merge (into {} (map (juxt :id identity)) (:orders data)))))

(defmethod n/describe :deliveries/sent-up [{:keys [actor data]} names]
  (str (n/who names actor) " sends up " (str/join ", " (map goods-label (:orders data)))
       " for " (str/join " and " (map #(n/who names %) (distinct (map :buyer (:orders data)))))
       " from the " (n/place names (:location data)) ", to be delivered at the "
       (n/place names (:to data)) " by " (:due-label data) "."))

(defn hearers
  "Everyone within walking distance of `place`: the whole house hears the bell."
  [world place]
  (set (mapcat #(space/present world %) (space/area world place))))

(defn couriers [world] (get-in world [:state :couriers] {}))

(defn- due-by-shop
  "Orders due by this round, grouped by shop, in shop-id order."
  [world]
  (sort-by key (group-by :location (filter #(<= (:due %) (:round world)) (vals (orders world))))))

(defn courier-position [world shop]
  (or (:courier (space/location world shop)) (settings/parameter world :courier-position)))

(defn delivering-shops
  "Ids of the shops that send goods out, in id order."
  [world]
  (sort (keep #(when (delivers? %) (:id %)) (vals (get-in world [:space :locations])))))

(defn compose
  "{shop person} for the couriers that need work: a free record for each
  delivering shop with no courier yet, and the written person (two model
  calls) for each shop with orders due whose courier isn't written. nil,
  calling nothing, when there's no such work."
  [world services]
  (let [due (set (map key (due-by-shop world)))
        work (filter #(let [c (get (couriers world) %)]
                        (or (nil? c) (and (due %) (not (hiring/written? (:person c))))))
                     (delivering-shops world))]
    (when (seq work)
      (reduce (fn [made shop]
                (let [p (or (:person (get (couriers world) shop))
                            (hiring/person-record world services
                                                  {:position (courier-position world shop) :area (hiring/place-area world shop)
                                                   :seed (str (:seed world) "/courier/" shop)
                                                   :taken-names (concat (hiring/taken-names world) (map :name (vals made)))}))]
                  (assoc made shop (if (due shop) (hiring/write-person services p) p))))
              {} work))))

(defmethod w/apply-event :deliveries/courier-updated [world {:keys [data]}]
  (update-in world [:state :couriers (:shop data)] merge (:changes data)))

(defn- courier-updated [shop changes]
  {:type :deliveries/courier-updated :visible-to :system :data {:shop shop :changes changes}})

(defn- shop-name [world shop] (:name (space/location world shop) shop))

(defn courier-line
  "Who sends `shop`'s orders up: \"Stephen Dobbs, the General store's carrier\", or nil."
  [world shop]
  (when-let [{:keys [person position]} (get (couriers world) shop)]
    (str (:name person) ", the " (shop-name world shop) "'s "
         (str/lower-case (get-in world [:state :positions position :title] (name position))))))

(defn- situation [world shop due]
  (str "You carry for the " (shop-name world shop) ". You have brought up "
       (str/join ", " (map goods-label due))
       ", ordered by " (str/join " and " (distinct (map #(:name (w/agent world (:buyer %)) (:buyer %)) due)))
       ", and left it at the " (shop-name world (delivery-place world))
       ". Speak with whoever comes if you like; you go back to the shop once nobody needs you."))

(defn- waiting
  "A courier's routine at the delivery place, so a scenario with :hibernate
  keeps them out of play while nobody else is there."
  [world shop]
  [{:from "00:00" :at (delivery-place world) :doing (str "waiting by the delivery from the " (shop-name world shop))}])

(defn- courier-events
  "Events bringing `shop`'s courier to the delivery place with `due`: already
  here (a courier staying as a guest keeps their guest's situation), back
  from the shop, or spawned the first time. None when the courier
  isn't written yet, or there's no room for one. A courier who arrives with
  nobody else there is put out of play at once (the scenario's :hibernate)."
  [scenario world shop due]
  (let [c (get (couriers world) shop)
        agent (w/agent world (:agent-id c))
        place (delivery-place world)
        text (situation world shop due)
        arrived (courier-updated shop {:arrived (:round world)})
        clerk (:clerk (first due))
        evs (cond
              (not (hiring/written? (:person c))) []
              (visits/visiting? world (:id agent)) [arrived]
              (= :active (:status agent))
              [arrived {:type :agent/updated :visible-to :system :data {:id (:id agent) :changes {:situation text}}}]
              (not (hiring/room-for-arrival? world)) []
              (hiring/can-arrive? world (:id agent))
              (conj (:events (hiring/arrival scenario world place {:id (:id agent) :situation text})) arrived)
              (nil? agent)
              (let [{:keys [id events]} (hiring/arrival scenario world place
                                                        {:spawn (merge (hiring/spawn-spec (:person c))
                                                                       {:role (:position c) :situation text
                                                                        :household nil :routine (waiting world shop)})})
                    world' (reduce w/apply-event world events)]
                (vec (concat
                      events
                      [(positions/post-changed world' id (:position c)
                                               {:household nil :employer clerk :visible-to #{id clerk}
                                                :terms (str "carries for the " (shop-name world shop) " (" shop ")")})
                       (courier-updated shop {:agent-id id :arrived (:round world)})])))
              :else [])
        world' (reduce w/apply-event world evs)
        id (:agent-id (get (couriers world') shop))
        rest (when (and (seq evs) (:hibernate scenario) (w/active? world' id)
                        (not (physical/held-in-place? world' id)) (not (w/busy world' id)))
               (when-let [{:keys [activity]} ((:hibernate scenario) world' id)]
                 [(b/hibernate world' id activity)]))]
    (into evs rest)))

(defn- arrival
  "The events of one shop's due orders arriving at the delivery place: the
  courier, a parcel for each order (with the aspects of goods sent up, or
  the amount and condition quoted), which the nightly steward leaves alone
  (:order-id), and the announcement."
  [scenario world shop due]
  (let [place (delivery-place world)
        brought (courier-events scenario world shop due)
        world (reduce w/apply-event world brought)
        courier (:agent-id (get (couriers world) shop))
        courier (when (and courier (w/active? world courier)
                           (= (:round world) (:arrived (get (couriers world) shop))))
                  courier)
        parcels (items/added-events
                 world
                 (for [o due]
                   (cond-> {:name (:item o) :desc (:desc o) :place place :owner (:owner o "household") :order-id (:id o)}
                     (:unit o) (assoc :aspects {:amount (amount (:qty o) (:unit o)) :state (:condition o)})
                     (:aspects o) (assoc :aspects (:aspects o))
                     (:consumable o) (assoc :consumable? true)))
                 :gm)]
    (-> brought
        (into parcels)
        (conj {:type :deliveries/arrived :visible-to (hearers world place)
               :data (cond-> {:shop shop :at place :orders (mapv :id due)
                              :goods (mapv (fn [o e] (cond-> {:id (get-in e [:data :item :id]) :name (:item o)}
                                                       (order-amount o) (assoc :amount (order-amount o))))
                                           due parcels)}
                       courier (assoc :courier courier))}))))

(defmethod w/apply-event :deliveries/arrived [world {:keys [data]}]
  (update-in world [:state :orders] #(apply dissoc % (:orders data))))

(defmethod n/describe :deliveries/arrived [{:keys [data]} names]
  (let [goods (str/join ", " (for [{:keys [id name amount]} (:goods data)]
                               (str name " (" (str/join ", " (remove nil? [amount id])) ")")))]
    (if-let [courier (:courier data)]
      (str (n/who names courier) " (" courier ") brings a delivery from the " (n/place names (:shop data))
           " and leaves it at the " (n/place names (:at data)) ": " goods ".")
      (str "A delivery from the " (n/place names (:shop data)) " is left at the " (n/place names (:at data)) ": " goods "."))))

(defn- departures
  "Couriers going home: idle for `courier-leave-after` rounds. A courier
  who has come as a guest (manor.mechanics.visits) decides for himself."
  [world]
  (for [[shop {:keys [agent-id arrived]}] (sort-by key (couriers world))
        :when (and agent-id (w/active? world agent-id) (not (visits/visiting? world agent-id))
                   (not (physical/held-in-place? world agent-id))
                   (>= (hiring/idle-rounds world agent-id arrived) courier-leave-after))]
    {:type :people/departed :visible-to (space/here world agent-id)
     :data {:id agent-id :reason "the delivery is made" :towards (str "the " (shop-name world shop))}}))

(defn tick
  "What compose made is recorded; then every order due by this round arrives,
  a shop at a time in id order; then idle couriers go home."
  [scenario world {:keys [result]}]
  (let [made (for [[shop p] (sort-by key result)]
                (courier-updated shop {:person p :position (courier-position world shop)}))
        world (reduce w/apply-event world made)
        [world evs] (reduce (fn [[world evs] [shop due]]
                              (let [new (arrival scenario world shop (sort-by :id due))]
                                [(reduce w/apply-event world new) (into evs new)]))
                            [world (vec made)]
                            (due-by-shop world))]
    (into evs (departures world))))

(defn install
  "Add the deliveries system to a scenario; its couriers spawn through the
  scenario's roles and :on-spawn."
  [scenario]
  (update scenario :systems
          #(conj (vec (remove (fn [s] (= :deliveries/arrivals (:name s))) %))
                 {:name :deliveries/arrivals :compose compose
                  :tick (fn [world res] (tick scenario world res))})))

(defn observe
  "The viewer's pending orders, ordered or sent up, as buyer or as the
  shop's clerk, without the orders' ids, which no tool takes."
  [world id]
  (vec (for [o (sort-by :id (vals (orders world)))
             :when (or (= id (:buyer o)) (= id (:clerk o)))]
         (cond-> (merge (cond-> {:item (:item o)} (order-amount o) (assoc :amount (order-amount o)))
                        {:from (:location o) :buyer (:buyer o) :to (:to o)
                         :due (clock/short-label (:clock world) (:due o) (:round world))})
           (courier-line world (:location o)) (assoc :sent-up-by (courier-line world (:location o)))))))

(defn problems
  "World-file checks for delivering shops. Whether a :courier names a known
  position is for the scenario, which has the positions table."
  [{:keys [locations]}]
  (concat
   (for [loc locations
         :when (contains? loc :delivery-profile)
         :when (not (and (string? (:delivery-profile loc)) (not (str/blank? (:delivery-profile loc)))
                         (<= (count (:delivery-profile loc)) max-profile)
                         (contains? (:services loc) :general-store)))]
     (str "location " (:id loc) ": :delivery-profile must be non-blank text of at most " max-profile
          " characters, on a shop with :services #{:general-store}"))
   (for [loc locations
         :when (and (contains? loc :courier) (not (keyword? (:courier loc))))]
     (str "location " (:id loc) ": :courier must be a position id, such as :errand-boy"))))
