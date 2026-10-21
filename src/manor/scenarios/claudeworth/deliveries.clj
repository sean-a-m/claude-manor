(ns manor.scenarios.claudeworth.deliveries
  "Orders sent up from a shop: a delivery quote bought at the counter becomes
  an order, paid for at once, and at its due round the goods are left at the
  delivery place (hiring's meeting place), brought by the shop's courier. A
  shop delivers when its location has a :delivery-profile. Each shop's courier
  is named at the start (a free people-server record, so the shop and its
  customers know who sends orders up), written and spawned at its first
  delivery, and comes back for every delivery after. See docs/deliveries-design.md."
  (:require [clojure.string :as str]
            [manor.clock :as clock]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.hiring :as hiring]
            [manor.mechanics.items :as items]
            [manor.mechanics.positions :as positions]
            [manor.mechanics.space :as space]
            [manor.narrate :as n]
            [manor.scenarios.claudeworth.building :as building]
            [manor.scenarios.claudeworth.money :as money]
            [manor.world :as w]))

(def max-lead-hours 120)
(def max-profile 400)
;; A courier goes home once this many rounds pass without a conversation.
(def courier-leave-after 2)
;; The position of a shop's courier unless its location names one in :courier.
(def default-courier :errand-boy)

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

;; The order's id is the engine's: no tool takes it, so it's never shown, or
;; characters take it up as a reference number ("order-312") in their talk.
(defmethod n/describe :deliveries/ordered [{:keys [actor data]} names]
  (str (n/who names actor) " orders " (:item data) " (" (amount (:qty data) (:unit data)) ") for "
       (money/display (:price data)) " at the " (n/place names (:location data)) ", to be delivered at the "
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

(defn courier-position [world shop] (or (:courier (space/location world shop)) default-courier))

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

(defn- room? [world]
  (< (count (w/active-agents world)) (get-in world [:state :hiring-max-agents] b/default-max-agents)))

(defn- shop-name [world shop] (:name (space/location world shop) shop))

(defn courier-line
  "Who sends `shop`'s orders up: \"Stephen Dobbs, the General store's carrier\", or nil."
  [world shop]
  (when-let [{:keys [person position]} (get (couriers world) shop)]
    (str (:name person) ", the " (shop-name world shop) "'s "
         (str/lower-case (get-in world [:state :positions position :title] (name position))))))

(defn- situation [world shop due]
  (str "You carry for the " (shop-name world shop) ". You have brought up "
       (str/join ", " (for [o due] (str (:item o) " (" (amount (:qty o) (:unit o)) ")")))
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
  here, back from the shop, or spawned the first time. None when the courier
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
              (= :active (:status agent))
              [arrived {:type :agent/updated :visible-to :system :data {:id (:id agent) :changes {:situation text}}}]
              (not (room? world)) []
              (= :away (:status agent))
              [{:type :people/returned :visible-to (conj (space/present world place) (:id agent)) :data {:id (:id agent) :at place}}
               {:type :agent/updated :visible-to :system :data {:id (:id agent) :changes {:situation text}}}
               arrived]
              (nil? agent)
              (let [spawned (b/spawn-events scenario world (merge (hiring/spawn-spec (:person c))
                                                                  {:role (:position c) :location place :situation text
                                                                   :household nil :routine (waiting world shop)}))
                    id (get-in (first spawned) [:data :agent :id])
                    world' (reduce w/apply-event world spawned)]
                (vec (concat
                      ;; The spawn holds a private persona and traits, so it is never a public event.
                      (map #(assoc % :visible-to :system) spawned)
                      [(positions/post-changed world' id (:position c)
                                               {:household nil :employer clerk :visible-to #{id clerk}
                                                :terms (str "carries for the " (shop-name world shop) " (" shop ")")})
                       (courier-updated shop {:agent-id id :arrived (:round world)})])))
              :else [])
        world' (reduce w/apply-event world evs)
        id (:agent-id (get (couriers world') shop))
        rest (when (and (seq evs) (:hibernate scenario) (w/active? world' id) (not (w/busy world' id)))
               (when-let [{:keys [activity]} ((:hibernate scenario) world' id)]
                 [(b/hibernate world' id activity)]))]
    (into evs rest)))

(defn- arrival
  "The events of one shop's due orders arriving at the delivery place: the
  courier, a parcel for each order, which the nightly steward leaves alone
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
                   (cond-> {:name (:item o) :desc (:desc o) :place place :owner "household" :order-id (:id o)
                            :aspects {:amount (amount (:qty o) (:unit o)) :state (:condition o)}}
                     (:consumable o) (assoc :consumable? true)))
                 :gm)]
    (-> brought
        (into parcels)
        (conj {:type :deliveries/arrived :visible-to (hearers world place)
               :data (cond-> {:shop shop :at place :orders (mapv :id due)
                              :goods (mapv (fn [o e] {:id (get-in e [:data :item :id]) :name (:item o)
                                                      :amount (amount (:qty o) (:unit o))})
                                           due parcels)}
                       courier (assoc :courier courier))}))))

(defmethod w/apply-event :deliveries/arrived [world {:keys [data]}]
  (update-in world [:state :orders] #(apply dissoc % (:orders data))))

(defmethod n/describe :deliveries/arrived [{:keys [data]} names]
  (let [goods (str/join ", " (for [{:keys [id name amount]} (:goods data)] (str name " (" amount ", " id ")")))]
    (if-let [courier (:courier data)]
      (str (n/who names courier) " (" courier ") brings a delivery from the " (n/place names (:shop data))
           " and leaves it at the " (n/place names (:at data)) ": " goods ".")
      (str "A delivery from the " (n/place names (:shop data)) " is left at the " (n/place names (:at data)) ": " goods "."))))

(defn- departures
  "Couriers going home: idle for `courier-leave-after` rounds."
  [world]
  (for [[shop {:keys [agent-id arrived]}] (sort-by key (couriers world))
        :when (and agent-id (w/active? world agent-id)
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
  "The viewer's pending orders, as buyer or as the shop's clerk, without the
  orders' ids, which no tool takes."
  [world id]
  (vec (for [o (sort-by :id (vals (orders world)))
             :when (or (= id (:buyer o)) (= id (:clerk o)))]
         (cond-> {:item (:item o) :amount (amount (:qty o) (:unit o)) :from (:location o)
                  :buyer (:buyer o) :to (:to o) :due (clock/short-label (:clock world) (:due o) (:round world))}
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
