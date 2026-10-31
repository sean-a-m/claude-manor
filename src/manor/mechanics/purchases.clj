(ns manor.mechanics.purchases
  "The order books: a record of every purchase at a shop, kept by the
  reducers of the events that make one (:shop/bought, :deliveries/ordered,
  :deliveries/sent-out) and that move its goods on (:deliveries/sent-up,
  :deliveries/arrived), so the people who keep a book can see what was
  really bought and where its goods are now, whatever was said about it.

  Two books are shown, each even when empty, so an empty one means nothing
  was bought:
  - the household's (`household.orders`): what was bought on the
    household's account, for everyone of the household;
  - a shop's (`shop-orders`): its sales and sends, for whoever keeps it
    (their position's :workplace).
  A book shows its latest few entries and every delivery still to come. An
  entry is one purchase's lines bought together: the shop, who it was for,
  when, the goods with their prices, and where the goods are now.

    [:state :purchases]
    [{:id \"quote-41\" :shop \"general-store\" :buyer \"bessie\" :clerk \"briggs\"
      :account \"household\" :item \"Currants\" :amount \"3 × 1 lb\" :price 15
      :bought 9 :status :ordered :order-id \"order-41\" :due 12 :to \"carriage-sweep\"}]

  :status is :taken (carried away), :left (waiting at the shop as item
  :item-id), :ordered (to be delivered at :to by :due) or :delivered (at
  :to, in round :delivered). See docs/deliveries-design.md."
  (:require [clojure.string :as str]
            [manor.clock :as clock]
            [manor.mechanics.items :as items]
            [manor.mechanics.money :as money]
            [manor.mechanics.positions :as positions]
            [manor.mechanics.space :as space]
            [manor.world :as w]))

;; Records kept in state; deliveries still to come are never dropped.
(def max-records 60)
;; Entries a book shows besides the deliveries still to come.
(def shown 6)

(defn amount
  "What `qty` of `unit` comes to: \"1 tin\", or \"20 × 1 lb\"."
  [qty unit]
  (if (= 1 qty) unit (str qty " × " unit)))

(defn records [world] (get-in world [:state :purchases] []))

(defn courier-line
  "Who sends `shop`'s orders up: \"Stephen Dobbs, the General store's
  carrier\", or nil before its courier is named (manor.mechanics.deliveries)."
  [world shop]
  (when-let [{:keys [person position]} (get-in world [:state :couriers shop])]
    (str (:name person) ", the " (:name (space/location world shop) shop) "'s "
         (str/lower-case (get-in world [:state :positions position :title] (name position))))))

(defn- pending? [r] (= :ordered (:status r)))

(defn- trimmed
  "`rs` within max-records, the oldest settled records dropped first."
  [rs]
  (let [excess (- (count rs) max-records)]
    (if (pos? excess)
      (let [old (set (take excess (keep-indexed #(when-not (pending? %2) %1) rs)))]
        (vec (keep-indexed #(when-not (old %1) %2) rs)))
      rs)))

(defn- add [world new-records]
  (update-in world [:state :purchases] #(trimmed (into (or % []) new-records))))

(defn- update-records [world pred f]
  (update-in world [:state :purchases] (fn [rs] (mapv #(if (pred %) (f %) %) (or rs [])))))

;; ---------------------------------------------------------------------------
;; Recording, for the reducers

(defn bought
  "The world with the counter purchase `data` (:shop/bought's) recorded:
  taken away, or left at the shop as the item :item-id."
  [world {:keys [quote-id location buyer clerk account item qty unit price hand-over item-id]}]
  (add world [(cond-> {:id quote-id :shop location :buyer buyer :clerk clerk :account account
                       :item item :amount (amount qty unit) :price price :bought (:round world)
                       :status (if (= "leave" hand-over) :left :taken)}
                item-id (assoc :item-id item-id))]))

(defn ordered
  "The world with `orders` (deliveries/ordered's or sent-out's data, each
  with its price and account) recorded as bought now, to be delivered.
  `sent-out?` marks goods the shopkeeper sent up without the customer."
  [world orders sent-out?]
  (add world (for [{:keys [id quote-id location buyer clerk account item qty unit price due to]} orders]
               (cond-> {:id (or quote-id id) :shop location :buyer buyer :clerk clerk :account account
                        :item item :amount (amount qty unit) :price price :bought (:round world)
                        :status :ordered :order-id id :due due :to to}
                 sent-out? (assoc :sent-out? true)))))

(defn sent-up
  "The world with the goods of `orders` (deliveries/sent-up's: goods left
  waiting at the shop, each order with its :item-id) recorded as to be
  delivered."
  [world orders]
  (reduce (fn [world {:keys [id item-id due to]}]
            (update-records world #(and item-id (= item-id (:item-id %)) (= :left (:status %)))
                            #(assoc % :status :ordered :order-id id :due due :to to)))
          world orders))

(defn arrived
  "The world with the orders `order-ids` recorded as delivered at `place` now."
  [world order-ids place]
  (let [ids (set order-ids)]
    (update-records world #(and (pending? %) (ids (:order-id %)))
                    #(assoc % :status :delivered :delivered (:round world) :to place))))

;; ---------------------------------------------------------------------------
;; Who keeps which book

(defn household? [world id] (positions/household-member? world id))

(defn kept-shops
  "The shops `id` keeps: the places of their position's :workplace that
  are shops, if they can serve at one."
  [world id]
  (when (positions/capable? world id :shop-clerk)
    (filterv #(contains? (:services (space/location world %)) :general-store)
             (:workplace (positions/position world id)))))

(defn in-books?
  "Is the order `o` (raw, from [:state :orders]) in a book `id` sees: the
  household's, when it's on the household's account, or their shop's?"
  [world id o]
  (boolean (or (and (= positions/household (or (:account o) (:owner o))) (household? world id))
               (some #{(:location o)} (kept-shops world id)))))

(defn book-for
  "Where `id` finds an order on `account` from `shop` in their view:
  \"household.orders\", \"shop-orders\" or \"orders\"."
  [world id account shop]
  (cond (and (= positions/household account) (household? world id)) "household.orders"
        (some #{shop} (kept-shops world id)) "shop-orders"
        :else "orders"))

;; ---------------------------------------------------------------------------
;; The books

(defn- still-waiting? [world r]
  (when-let [it (and (:item-id r) (items/item world (:item-id r)))]
    (items/waiting? world it)))

(defn- status
  "Where a record's goods are now, in words."
  [world r]
  (let [at #(clock/short-label (:clock world) % (:round world))
        place #(:name (space/location world %) %)]
    (case (:status r)
      :taken "taken away at the counter"
      :left (if (still-waiting? world r)
              "paid for, waiting at the shop to be collected or sent up"
              "collected from the shop")
      :ordered (str (when (:sent-out? r) (str "sent up by " (:name (w/agent world (:clerk r)) (:clerk r)) ", "))
                    "to be delivered at the " (place (:to r)) " by " (at (:due r)))
      :delivered (str "delivered at the " (place (:to r)) " at " (at (:delivered r))))))

(defn- entry
  "One entry: records bought together, with the same status."
  [world [r :as rs] own-purse?]
  (let [spec (money/spec world)]
    (cond-> {:from (:name (space/location world (:shop r)) (:shop r))
             :for (:name (w/agent world (:buyer r)) (:buyer r))
             :bought (clock/short-label (:clock world) (:bought r) (:round world))
             :goods (mapv #(str (:item %) " (" (:amount %) "), " (money/display spec (:price %))) rs)
             :total (money/display spec (reduce + (map :price rs)))
             :status (status world r)}
      (and (pending? r) (courier-line world (:shop r))) (assoc :sent-up-by (courier-line world (:shop r)))
      own-purse? (assoc :paid "from the buyer's own purse"))))

(defn- book
  "The entries for `rs`, oldest first: the latest `shown`, and every one
  with a delivery still to come. With `purses?`, entries not paid from the
  household's account say so."
  [world rs purses?]
  (let [groups (vec (partition-by (juxt :shop :buyer :bought :account #(status world %)) rs))
        start (- (count groups) shown)]
    (vec (keep-indexed (fn [i g]
                         (when (or (>= i start) (some pending? g))
                           (entry world g (and purses? (not= positions/household (:account (first g)))))))
                       groups))))

(defn household-book
  "The household's book: what was bought on its account."
  [world]
  (book world (filter #(= positions/household (:account %)) (records world)) false))

(defn shop-book
  "The book of the `shops`: their sales and sends, on any account."
  [world shops]
  (let [shops (set shops)]
    (book world (filter #(shops (:shop %)) (records world)) true)))

(defn observe
  "The books `id` keeps, for their view: {:household {:orders [...]}} if
  they're of the household, and {:shop-orders [...]} if they keep a shop."
  [world id]
  (let [shops (kept-shops world id)]
    (cond-> {}
      (household? world id) (assoc :household {:orders (household-book world)})
      (seq shops) (assoc :shop-orders (shop-book world shops)))))

(defn judge-books
  "The books of `ids`, for a judge's scene: :household_orders if any of them
  is of the household, :shop_orders for the shops any of them keeps."
  [world ids]
  (let [shops (distinct (mapcat #(kept-shops world %) ids))]
    (cond-> {}
      (some #(household? world %) ids) (assoc :household_orders (household-book world))
      (seq shops) (assoc :shop_orders (shop-book world shops)))))
