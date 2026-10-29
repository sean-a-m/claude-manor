(ns manor.mechanics.shop
  "A general store: a clerk obtains checked offers; customers
  accept them. A line may be quoted for delivery at a shop that sends goods
  out (manor.mechanics.deliveries), and there the clerk can send
  up goods left waiting at the shop. No catalogue, bulk counters, or
  model-owned money."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [malli.json-schema :as mjs]
            [manor.actions :as a]
            [manor.clock :as clock]
            [manor.mechanics.adjudication :as adjudication]
            [manor.mechanics.items :as items]
            [manor.mechanics.positions :as positions]
            [manor.mechanics.space :as space]
            [manor.narrate :as n]
            [manor.prompts :as prompts]
            [manor.mechanics.building :as building]
            [manor.mechanics.deliveries :as deliveries]
            [manor.mechanics.money :as money]
            [manor.settings :as settings]
            [manor.world :as w]))

(def max-unit-price 240000) ; bound untrusted arithmetic, not a price list
(def max-quantity 20)
;; Goods lines in one quote-goods call (one appraisal) and quotes in one buy.
(def max-lines 10)
;; A goods line's amount, as the customer put it: "a stone", "2 lb", "3".
(def max-amount 40)
;; A parcel's :amount aspect, "20 × " + unit, stays within items/max-aspect.
(def max-unit 30)

(defn- text-schema [limit] [:string {:min 1 :max limit}])

(def appraisal-schema
  [:map
   [:available :boolean]
   [:reason {:optional true} (text-schema 300)]
   [:item {:optional true} (text-schema items/max-name)]
   [:qty {:optional true} [:int {:min 1 :max max-quantity}]]
   [:unit {:optional true} (text-schema max-unit)]
   [:desc {:optional true} (text-schema items/max-desc)]
   [:condition {:optional true} [:string {:min 1 :max items/max-aspect :json-schema/maxLength items/advised-aspect}]]
   [:consumable {:optional true} :boolean]
   [:unit-price {:optional true} [:int {:min 1 :max max-unit-price}]]
   [:lead-hours {:optional true} [:int {:min 1 :max deliveries/max-lead-hours}]]])

(defn- lines-schema [n]
  [:map [:lines [:vector {:min n :max n} appraisal-schema]]])

(defn instructions [world]
  (prompts/render world "judges/shop-appraiser.md" {:max-lead-hours deliveries/max-lead-hours :max-quantity max-quantity
                                                        :money (prompts/render world "money-pricing.md" {})}))

(declare check)

(defn request
  "Only the actual store's engine-owned profile and the purchase request reach
  the model. No world dump, customer wealth, notes, or dialogue history."
  [world clerk args]
  (let [loc (space/location world (space/location-of world clerk))
        data {:setting (get-in world [:state :setting])
              :date (clock/world-label world (:round world))
              :store (select-keys loc [:name :store-profile :delivery-profile :desc :detail])
              :request (mapv #(cond-> (select-keys % [:item :amount]) (:deliver %) (assoc :deliver true)) (:goods args))}
        n (count (:goods args))
        delivered (set (keep-indexed #(when (:deliver %2) %1) (:goods args)))]
    {:messages [{:role "system" :content (instructions world)}
                {:role "user" :content (json/write-value-as-string data)}]
     :tools [{:type "function" :function {:name "appraise" :description "For each requested line, offer plausible stock (portable over the counter, or sent up for a delivery line) or refuse with a reason."
                                        :parameters (mjs/transform (lines-schema n))}}]
     :ctx {:request (:request data)}
     :check #(check n delivered %)}))

(defn- checked-args
  "One line's appraisal; an available line needs its qty and unit, and an
  available delivery line lead-hours too."
  [deliver? raw]
  (let [{:keys [args error]} (a/decode-args {:args appraisal-schema} raw)
        fields (cond (not (:available args)) [:reason]
                     deliver? [:item :qty :unit :desc :condition :consumable :unit-price :lead-hours]
                     :else [:item :qty :unit :desc :condition :consumable :unit-price])]
    (cond
      error {:error error}
      (some #(or (nil? (get args %)) (and (string? (get args %)) (str/blank? (get args %)))) fields)
      {:error (str "provide non-blank " (str/join ", " (map name fields)))}
      :else (select-keys args (cons :available fields)))))

(defn- checked-lines
  "Each line checked on its own; there must be exactly `n`. `delivered` holds
  the indexes of lines asked for delivery."
  [n delivered raw]
  (let [lines (get-in (a/decode-args {:args [:map [:lines [:sequential :any]]]} raw) [:args :lines])]
    (if-not (= n (count lines))
      {:error (str "give lines: exactly " n " appraisal" (when (not= 1 n) "s") ", one per requested line, in order")}
      (let [checked (vec (map-indexed #(if (map? %2) (checked-args (contains? delivered %1) %2) {:error "each line must be an object"}) lines))]
        (if-let [i (first (keep-indexed #(when (:error %2) %1) checked))]
          {:error (str "line " (inc i) ": " (:error (nth checked i)))}
          {:lines checked})))))

(defn check
  "The checked appraisal of `n` requested lines, those whose indexes are in
  `delivered` asked for delivery."
  ([n response] (check n #{} response))
  ([n delivered response]
   (adjudication/answer "appraise" response (partial checked-lines n delivered))))

(defn mock-appraisal
  "A deterministic free responder, not a production inventory. Tests needing
  particular outcomes override this adapter or provide a scripted LLM. The
  scripted clerk's amounts are plain numbers (\"2\"), taken as the qty;
  any other amount is one unit."
  [{:keys [request]}]
  {:tool :appraise
   :args {:lines (for [{:keys [item amount deliver]} request]
                   (cond-> {:available true :item item :qty (or (parse-long amount) 1) :unit "one"
                            :desc (str "An ordinary " item ".")
                            :condition "new" :consumable false :unit-price 18}
                     deliver (assoc :lead-hours 2)))}})

(def adjudicators {:shop/appraise {:request request :mock mock-appraisal}})

(defn service-reason [world clerk]
  (let [loc (space/location world (space/location-of world clerk))]
    (cond
      (not (and (w/active? world clerk) (positions/capable? world clerk :shop-clerk))) "only someone who keeps a shop can quote goods"
      (not (contains? (:services loc) :general-store)) "go to a general store first"
      (str/blank? (:store-profile loc)) "this store needs a store profile"
      (not (building/open-now? world (:hours loc))) (str "the " (:name loc) " is closed; it is open " (str/join "-" (:hours loc))))))

(defn quote-reason [world clerk {:keys [for goods]}]
  (or (service-reason world clerk)
      (when (some (comp str/blank? :item) goods) "describe the goods you want to quote")
      (when (some (comp str/blank? :amount) goods) "give the amount of each line, as the customer asked for it")
      (let [loc (space/location world (space/location-of world clerk))]
        (when (and (some :deliver goods) (not (deliveries/delivers? loc)))
          (str "the " (:name loc) " doesn't send goods out; quote those lines without deliver")))
      (when-not (and (w/active? world for) (space/together? world clerk for)
                     (not= :gm (:role (w/agent world for))))
        "the customer must be an active character present at the counter")))

(defmethod w/apply-event :shop/goods-quoted [world {:keys [data]}]
  (assoc-in world [:state :quotes (:id data)] data))

(defmethod w/apply-event :shop/bought [world {:keys [data]}]
  (-> world
      (update-in [:state :accounts (:account data)] - (:price data))
      (update-in [:state :quotes] dissoc (:quote-id data))))

(defmethod n/describe :shop/goods-quoted [{:keys [actor data]} names]
  (str (n/who names actor) " offers " (n/who names (:buyer data)) " " (:qty data) " " (:item data)
       " (" (:unit data) " each) for " (money/display (get names [:money]) (:price data))
       (when-let [{:keys [lead-hours to]} (:deliver data)]
         (str ", to be delivered at the " (n/place names to) " within " lead-hours
              (if (= 1 lead-hours) " hour" " hours") " of ordering"))
       "; quote " (:id data) "."))

(defmethod n/describe :shop/goods-refused [{:keys [actor data]} names]
  (str (n/who names actor) " cannot offer " (:item data) " to " (n/who names (:buyer data)) ": " (:reason data)))

(def amount deliveries/amount)

(defmethod n/describe :shop/bought [{:keys [actor data]} names]
  (str (n/who names actor) " buys " (:item data) " (" (amount (:qty data) (:unit data)) ") for "
       (money/display (get names [:money]) (:price data)) " at the " (n/place names (:location data))
       (if (= "leave" (:hand-over data))
         ", and leaves the goods there to be collected or sent up."
         ", and takes the goods away.")))

(defn offer-events
  "An offer or a refusal for each requested line. A quote's id is the
  sequence number its event will get."
  [world clerk {:keys [for goods] :as args} result]
  ;; Recheck against the current world before recording offers.
  (when-not (quote-reason world clerk args)
    (let [audience (space/here world clerk)]
      (vec (map-indexed
            (fn [i [{:keys [item deliver]} line]]
              (if (:available line)
                {:type :shop/goods-quoted :visible-to audience
                 :data (cond-> (merge (select-keys line [:item :qty :unit :desc :condition :consumable :unit-price])
                                      {:id (str "quote-" (+ (:seq world) 1 i)) :buyer for :clerk clerk
                                       :location (space/location-of world clerk)
                                       :price (* (:qty line) (:unit-price line))})
                         deliver (assoc :deliver {:lead-hours (:lead-hours line) :to (deliveries/delivery-place world)}))}
                {:type :shop/goods-refused :visible-to audience
                 :data {:buyer for :item item :reason (:reason line)}}))
            (map vector goods (:lines result)))))))

(defn offers-reply
  "What the clerk is told: each offer or refusal as it was announced."
  [world _ _ {:keys [events]}]
  (let [names (n/names world)]
    (if (seq events)
      (str/join "\n" (map #(n/describe % names) events))
      "No offers were made: the customer or the shop is no longer ready.")))

(defn- quote-problem [world buyer quote]
  (cond
    (nil? quote) "no such unspent quote; ask the shopkeeper for an offer"
    (not= buyer (:buyer quote)) "this quote is for another customer"
    (not= (:location quote) (space/location-of world buyer)) "return to the store that made this offer"
    :else (or (service-reason world (:clerk quote))
              (when-not (space/together? world buyer (:clerk quote)) "the shopkeeper must be here"))))

(defn buy-reason [world buyer {:keys [quote-ids from]}]
  (let [quotes (map #(get-in world [:state :quotes %]) quote-ids)
        total (reduce + (keep :price quotes))]
    (cond
      (not (and (w/active? world buyer) (not= :gm (:role (w/agent world buyer))))) "the buyer must be an active character"
      (not (apply distinct? quote-ids)) "give each quote-id once"
      :else (or (first (keep (fn [[id quote]] (some->> (quote-problem world buyer quote) (str id ": ")))
                             (map vector quote-ids quotes)))
                (when-let [reason (money/spend-reason world buyer from total)]
                  (str (if (next quote-ids) "these cost " "this costs ") (money/display (money/spec world) total)
                       "; " reason))))))

(defn- parcel
  "The item a counter quote's goods make: owned by the paying account, its
  :amount aspect the amount bought (where work can later say what's left)
  and its :state the appraised condition. Taken, it's in the buyer's hands;
  left, it lies at the shop, waiting for the buyer and kept by the clerk."
  [world buyer quote hand-over owner]
  (cond-> {:name (:item quote) :desc (:desc quote) :owner owner
           :aspects {:amount (amount (:qty quote) (:unit quote)) :state (:condition quote)}}
    (= "take" hand-over) (assoc :holder buyer)
    (= "leave" hand-over) (assoc :place (:location quote)
                                 :waiting {:for buyer :keeper (:clerk quote) :at (:location quote) :since (:round world)})
    (:consumable quote) (assoc :consumable? true)))

(defn buy-events
  "For each quote, the purchase and one parcel of its goods (see `parcel`),
  taken by the buyer or left at the shop as `hand-over` says. A delivery
  quote becomes an order instead, paid now and delivered later, whatever
  `hand-over` says."
  [world buyer {:keys [quote-ids hand-over from]}]
  (let [audience (space/here world buyer)
        account (money/account buyer from)]
    (second
     (reduce (fn [[world evs] quote-id]
               (let [quote (get-in world [:state :quotes quote-id])
                     new (if (:deliver quote)
                           [(deliveries/ordered world buyer quote-id quote audience account)]
                           [{:type :shop/bought :visible-to audience
                             :data (assoc (select-keys quote [:location :item :unit :qty :price])
                                          :quote-id quote-id :hand-over hand-over :account account)}
                            (items/added world (parcel world buyer quote hand-over account) audience
                                         :amount (amount (:qty quote) (:unit quote)))])]
                 [(reduce w/apply-event world new) (into evs new)]))
             [world []]
             quote-ids))))

(defn- buy-reply
  "What the buyer is told, from the world after buying: where the counter
  goods are now, by `hand-over`, and whether any were ordered for delivery
  (the quotes that became orders)."
  [world _ {:keys [quote-ids hand-over]}]
  (let [ordered (count (filter (comp (set quote-ids) :quote-id) (vals (deliveries/orders world))))]
    (str "Paid: "
         (str/join "; " (cond-> []
                          (< ordered (count quote-ids))
                          (conj (if (= "leave" hand-over)
                                  "the goods are left at the shop for you (waiting-at-shops in your view)"
                                  "the goods are in your hands (carrying in your view)"))
                          (pos? ordered) (conj "goods for delivery are under orders in your view")))
         ".")))

(defn quotes-here
  "The unspent quotes made for `buyer` at the shop where they are."
  [world buyer]
  (let [here (space/location-of world buyer)]
    (filter #(and (= buyer (:buyer %)) (= here (:location %))) (vals (get-in world [:state :quotes])))))

(defn- not-waiting-here
  "Why the item `id` can't be sent up from `shop`, or nil: it must be shown
  as waiting there (see items/waiting?), with nothing in it."
  [world shop id]
  (let [it (items/item world id)]
    (cond
      (not (and it (= shop (get-in it [:waiting :at])) (items/waiting? world it)))
      (str id " isn't waiting at this shop; send up only goods marked waiting-for someone in your view")
      (seq (items/contents world id)) (str "take what's in " id " out first"))))

(defn send-up-reason
  "Why `clerk` can't send up the goods `ids`, or nil: they serve at an open
  shop that sends goods out, and each is waiting there, named once."
  [world clerk {ids :items}]
  (let [loc (space/location world (space/location-of world clerk))]
    (or (service-reason world clerk)
        (when-not (deliveries/delivers? loc)
          (str "the " (:name loc) " doesn't send goods out; hand them over when they're collected"))
        (when-not (apply distinct? ids) "give each item once")
        (first (keep #(not-waiting-here world (:id loc) %) ids)))))

(defn send-up-events
  "The goods become orders, due within `within-hours` (deliveries/sent-up)."
  [world clerk {ids :items :keys [within-hours]}]
  [(deliveries/sent-up world clerk ids within-hours)])

(defn- send-up-reply
  "What the clerk is told: the goods are with the courier, and when and
  where they'll be delivered."
  [world _ {ids :items}]
  (let [o (get (deliveries/orders world) (deliveries/sent-up-id (first ids)))]
    (str "Sent up: the goods are with the shop's courier now, to be delivered at the "
         (:name (space/location world (:to o))) " by " (clock/short-label (:clock world) (:due o) (:round world))
         " (see orders in your view). There is nothing for you to pack or carry.")))

(defn waiting-here
  "The goods shown as waiting at the shop where `clerk` is (see
  items/waiting?), in id order."
  [world clerk]
  (let [here (space/location-of world clerk)]
    (items/items-where world #(and (= here (get-in % [:waiting :at])) (items/waiting? world %)))))

(def actions
  [{:name :quote-goods :kind :query :roles :all :needs :shop-clerk
    :doc (prompts/doc "docs/shop/quote-goods.md" {:max-lines max-lines})
    :args [:map [:for :string]
           [:goods [:vector {:min 1 :max max-lines}
                    [:map [:item (text-schema 120)] [:amount (text-schema max-amount)]
                     [:deliver {:optional true} :boolean]]]]]
    :valid? quote-reason :adjudicate :shop/appraise :on-result offer-events :reply offers-reply}
   {:name :buy :kind :free :roles :all
    :doc (prompts/doc "docs/shop/buy.md" {:max-lines max-lines})
    :args [:map [:quote-ids [:vector {:min 1 :max max-lines} :string]]
           [:hand-over [:enum "take" "leave"]]
           [:from {:optional true} [:enum "household" "own"]]]
    :valid? buy-reason :effect buy-events :reply buy-reply}
   {:name :send-up :kind :free :roles :all :needs :shop-clerk
    :doc (prompts/doc "docs/shop/send-up.md" {:max-lines max-lines :send-up-max-hours deliveries/send-up-max-hours})
    :args [:map [:items [:vector {:min 1 :max max-lines} :string]]
           [:within-hours [:int {:min 1 :max deliveries/send-up-max-hours}]]]
    :valid? send-up-reason :effect send-up-events :reply send-up-reply}])

(defn waiting-at-shops
  "The goods `id` bought and left at shops, still waiting for them there
  (see items/waiting-for): each item's name, amount and shop, as `orders`
  gives places."
  [world id]
  (vec (for [it (items/waiting-for world id)]
         (cond-> {:item (:name it) :at (get-in it [:waiting :at])}
           (get-in it [:aspects :amount]) (assoc :amount (get-in it [:aspects :amount]))))))

(defn observe [world id]
  (let [loc (space/location world (space/location-of world id))
        offers (for [quote (sort-by :id (vals (get-in world [:state :quotes])))
                     :when (or (= id (:buyer quote)) (= id (:clerk quote)))]
                 (assoc (select-keys quote [:id :buyer :clerk :location :item :unit :qty :deliver])
                        :price (money/display (money/spec world) (:price quote))))
        orders (deliveries/observe world id)
        waiting (waiting-at-shops world id)]
    (cond-> {:offers (vec offers)}
      (seq orders) (assoc :orders orders)
      (seq waiting) (assoc :waiting-at-shops waiting)
      (contains? (:services loc) :general-store)
      (assoc :here (cond-> {:service (str "Ask the shopkeeper for portable goods and a quote; accept with buy. "
                                          "Goods marked waiting-for someone are paid for and kept here for them until collected"
                                          (if (deliveries/delivers? loc) ", or sent up by the shopkeeper with send-up." "."))
                            :store-profile (:store-profile loc)}
                     (deliveries/delivers? loc) (assoc :delivery-profile (:delivery-profile loc)))))))

;; Mock choreography markers are translated into real tools here. Neither the
;; engine nor the real clerk sees them; test fixtures own their own scripts.
;; :request-goods takes one line {:item :amount :deliver?} or a list {:goods [...]};
;; :accept-offer takes {:item} or {:items [...]}, and an optional :hand-over
;; ("take" unless it says "leave"); :ask-send-up {:item} asks for goods left
;; at the shop to be sent up, which the clerk does on their next turn.
(defn- request-lines [args] (or (:goods args) [args]))

(defn- asked-to-send-up
  "The ids of goods waiting at `clerk`'s shop whose buyer's day asks, at any
  time, for them to be sent up."
  [day world clerk]
  (vec (for [it (waiting-here world clerk)
             :when (some (fn [[tool args]] (and (= :ask-send-up tool) (= (:name it) (:item args))))
                         (apply concat (vals (get day (get-in it [:waiting :for])))))]
         (:id it))))

(defn scripted-clerk [day time world clerk used]
  (let [requests (for [customer (sort-by :id (w/active-agents world))
                       [tool args] (get-in day [(:id customer) time])
                       :when (and (= tool :request-goods)
                                  (nil? (quote-reason world clerk {:for (:id customer)})))]
                   {:for (:id customer) :goods (vec (request-lines args))})
        args (nth (vec requests) (count (filter #{"quote-goods"} used)) nil)
        sends (asked-to-send-up day world clerk)]
    (cond
      args {:tool :quote-goods :args args}
      (and (seq sends) (nil? (send-up-reason world clerk {:items sends})))
      {:tool :send-up :args {:items sends :within-hours 1}}
      :else {:tool :pass})))

(defn scripted-customer
  "A scripted customer's marker as a real call: :request-goods says what
  they want, :accept-offer buys their quotes for those items (from the
  account in its :from, if given), :ask-send-up asks for goods to be sent
  up to the setting's home."
  [world buyer tool args]
  (let [home (settings/parameter world :home)]
    (case tool
      :request-goods [:say {:text (str "Could I buy "
                                       (str/join " and " (for [{:keys [amount item deliver]} (request-lines args)]
                                                           (str amount " " item (when deliver (str ", sent up to " home)))))
                                       ", please?")}]
      :accept-offer (let [quotes (sort-by :id (vals (get-in world [:state :quotes])))
                          ids (vec (keep (fn [item] (:id (first (filter #(and (= buyer (:buyer %)) (= item (:item %))) quotes))))
                                         (or (:items args) [(:item args)])))]
                      (if (seq ids)
                        [:buy (cond-> {:quote-ids ids :hand-over (:hand-over args "take")} (:from args) (assoc :from (:from args)))]
                        [:pass {}]))
      :ask-send-up [:say {:text (str "Would you send the " (:item args) " up to " home ", please?")}]
      [tool args])))

(defn problems [{:keys [locations] :as wf}]
  (concat
   (deliveries/problems wf)
   (for [loc locations
        :when (and (set? (:services loc)) (contains? (:services loc) :general-store))
        :when (not (and (string? (:store-profile loc)) (not (str/blank? (:store-profile loc)))
                         (<= (count (:store-profile loc)) 2000) (:hours loc)))]
    (str "general store " (:id loc) " needs :hours and a non-blank :store-profile (at most 2000 characters)"))))
