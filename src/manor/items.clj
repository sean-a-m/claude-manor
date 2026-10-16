(ns manor.items
  "Items: objects with a description and a condition that lie in a place or
  are carried by an agent. An opt-in engine module, like manor.space: a
  scenario turns it on with the key :items (a map, possibly empty), and its
  :init may return starting :items.

  World shape:
    :items {\"china-cabinet\" {:id \"china-cabinet\" :name \"China cabinet\"
                              :desc \"A glass-fronted walnut cabinet.\"   ; 1-400 chars, never changes
                              :condition \"dusty, the glass smeared\"     ; 1-80 chars
                              :place \"drawing-room\"   ; exactly one of :place (a location id)
                              ;; :holder \"hale\"       ;   or :holder (an agent id)
                              :owner \"household\"      ; optional: an agent id or any label; not enforced
                              :fixed? true}}            ; optional: furniture, can't be taken

  A carried item goes wherever its holder goes, with no events. The world has
  :items once the first item is added, so worlds without items are unchanged.

  Code outside this namespace never reads (:items world) directly: it uses
  the accessors below, and only the reducers here write items. That keeps
  faster lookups (an index kept by the same reducers, say) a change inside
  this namespace."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [manor.builtins :as b]
            [manor.narrate :as n]
            [manor.space :as space]
            [manor.world :as w]))

(def max-desc 400)
(def max-condition 80)
(def max-name 60)

(def Item
  "An item as written in a world file or returned by :init (holder and owner
  are agent names in a world file, agent ids elsewhere)."
  [:map {:closed true}
   [:id [:re #"^[a-z0-9]+(-[a-z0-9]+)*$"]]
   [:name [:string {:min 1 :max max-name}]]
   [:desc [:string {:min 1 :max max-desc}]]
   [:condition [:string {:min 1 :max max-condition}]]
   [:place {:optional true} :string]
   [:holder {:optional true} :string]
   [:owner {:optional true} :string]
   [:fixed? {:optional true} :boolean]])

;; ---------------------------------------------------------------------------
;; Accessors. For now they scan the map; with tens of items that's trivial.

(defn enabled?
  "Does the world have items?"
  [world]
  (contains? world :items))

(defn all
  "Every item, by id."
  [world]
  (sort-by :id (vals (:items world))))

(defn item [world id] (get-in world [:items id]))

(defn items-where [world pred] (filter pred (all world)))

(defn items-at
  "Items lying at `place` (not those carried by people there)."
  [world place]
  (if place (items-where world #(= place (:place %))) []))

(defn held-by [world agent-id] (items-where world #(= agent-id (:holder %))))

(defn names
  "Item id -> name, as [:item id] keys for manor.narrate/names."
  [world]
  (into {} (map (fn [it] [[:item (:id it)] (:name it)])) (all world)))

(defn- by-name [items] (sort-by (juxt :name :id) items))

;; ---------------------------------------------------------------------------
;; Checks

(defn- label [it] (pr-str (or (:id it) (:name it))))

(defn problems
  "Everything wrong with a list of items, as readable strings naming the item.
  `places` and `holders` are the known location ids and holders (agent names
  in a world file, agent ids otherwise)."
  [items {:keys [places holders]}]
  (vec (concat
        (for [[id k] (frequencies (keep :id items)) :when (> k 1)]
          (str "item " (pr-str id) " is defined " k " times"))
        (mapcat
         (fn [it]
           (let [where (str "item " (label it) ": ")]
             (if-let [e (m/explain Item it)]
               [(str where (pr-str (me/humanize e)))]
               (concat
                (when (= (contains? it :place) (contains? it :holder))
                  [(str where "give exactly one of :place and :holder")])
                (when (and (:place it) (not (contains? places (:place it))))
                  [(str where "unknown location " (pr-str (:place it)))])
                (when (and (:holder it) (not (contains? holders (:holder it))))
                  [(str where "unknown holder " (pr-str (:holder it)))])))))
         items))))

;; ---------------------------------------------------------------------------
;; Events

(defn init-events
  "Events adding the starting items (from a scenario's :init), visible to the GM."
  [items]
  (for [it items]
    {:type :item/added :data {:item it} :visible-to :gm}))

(defn fresh-id
  "An unused item id from `item-name`: \"umbrella\", then \"umbrella-2\"."
  [world item-name]
  (let [base (b/slug item-name)]
    (first (remove #(item world %) (cons base (map #(str base "-" %) (iterate inc 2)))))))

(defn added
  "An :item/added event for a new item with a fresh id. `fields` are the
  item's keys without :id; throws if they don't make a valid item."
  [world fields visible-to]
  (let [it (assoc fields :id (fresh-id world (:name fields)))]
    (when-let [ps (seq (problems [it] {:places (set (keys (get-in world [:space :locations])))
                                      :holders (set (keys (:agents world)))}))]
      (throw (ex-info (str "bad new item: " (str/join "; " ps)) {:item it})))
    {:type :item/added :data {:item it} :visible-to visible-to}))

(defn added-events
  "`added` events for several new items, each with its own fresh id."
  [world fields-list visible-to]
  (second (reduce (fn [[world evs] fields]
                    (let [e (added world fields visible-to)]
                      [(w/apply-event world e) (conj evs e)]))
                  [world []]
                  fields-list)))

(defmethod w/apply-event :item/added [world {:keys [data]}]
  (assoc-in world [:items (get-in data [:item :id])] (:item data)))

(defmethod w/apply-event :item/taken [world {:keys [actor data]}]
  (update-in world [:items (:item data)] #(-> % (dissoc :place) (assoc :holder actor))))

(defmethod w/apply-event :item/put-down [world {:keys [data]}]
  (update-in world [:items (:item data)] #(-> % (dissoc :holder) (assoc :place (:place data)))))

(defmethod w/apply-event :item/given [world {:keys [data]}]
  (assoc-in world [:items (:item data) :holder] (:to data)))

;; ---------------------------------------------------------------------------
;; Narration: names are looked up when the line is written.

(defn- the [names id]
  (let [s (get names [:item id] id)]
    (str "the " (str/lower-case (subs s 0 1)) (subs s 1))))

(defn- a-or-an [s]
  (str (if (re-find #"(?i)^[aeiou]" s) "an " "a ") (str/lower-case (subs s 0 1)) (subs s 1)))

(defmethod n/describe :item/added [{:keys [data]} names]
  (let [{:keys [holder place] item-name :name} (:item data)]
    (if holder
      (str (n/who names holder) " now has " (a-or-an item-name) ".")
      (str "Item added in " (n/place names place) ": " item-name "."))))

(defmethod n/describe :item/taken [{:keys [actor data]} names]
  (str (n/who names actor) " picks up " (the names (:item data)) "."))

(defmethod n/describe :item/put-down [{:keys [actor data]} names]
  (str (n/who names actor) " puts down " (the names (:item data)) "."))

(defmethod n/describe :item/given [{:keys [actor data]} names]
  (str (n/who names actor) " gives " (the names (:item data)) " to " (n/who names (:to data)) "."))

;; ---------------------------------------------------------------------------
;; Actions: free, for every role but the GM

(defn- listing
  "\"china-cabinet (China cabinet), piano (Piano)\", or `none`."
  [items none]
  (if (seq items)
    (str/join ", " (map #(str (:id %) " (" (:name %) ")") (by-name items)))
    none))

(defn- here-list [world actor]
  (str "items here: " (listing (items-at world (space/location-of world actor)) "none")))

(defn- carried-list [world actor]
  (str "you carry: " (listing (held-by world actor) "nothing")))

(defn- take-reason [world actor {id :item}]
  (let [it (item world id)
        here (space/location-of world actor)
        holder (:holder it)]
    (cond
      (nil? here) "you can't pick things up while travelling"
      (= actor holder) (str "you are already carrying the " (:name it))
      (and holder (space/together? world actor holder))
      (str (:name (w/agent world holder)) " is holding the " (:name it))
      (not= here (:place it)) (str id " is not here; " (here-list world actor))
      (:fixed? it) (str "the " (:name it) " is fixed in place"))))

(defn- carrying-reason [world actor id]
  (when-not (= actor (:holder (item world id)))
    (str "you are not carrying " id "; " (carried-list world actor))))

(defn- give-reason [world actor {id :item to :to}]
  (or (carrying-reason world actor id)
      (cond (= to actor) "you already have it; give it to someone else here"
            (not (some #(= to (:id %)) (w/active-agents world))) (str "no one has the id " to)
            (not (space/together? world actor to)) (str to " is not here"))))

(defn actions []
  [{:name :take
    :kind :free
    :doc "Pick up an item in your place and carry it (use its id from `items` in your view)."
    :args [:map [:item :string]]
    :valid? take-reason
    :effect (fn [world actor {id :item}]
              [{:type :item/taken :data {:item id :from-place (:place (item world id))}
                :visible-to (space/here world actor)}])}

   {:name :put-down
    :kind :free
    :doc "Put down an item you carry (use its id), leaving it where you are."
    :args [:map [:item :string]]
    :valid? (fn [world actor {id :item}]
              (or (carrying-reason world actor id)
                  (when-not (space/location-of world actor) "you can't put things down while travelling")))
    :effect (fn [world actor {id :item}]
              [{:type :item/put-down :data {:item id :place (space/location-of world actor)}
                :visible-to (space/here world actor)}])}

   {:name :give
    :kind :free
    :doc "Give an item you carry (use its id) to someone in the same place (use their id)."
    :args [:map [:item :string] [:to :string]]
    :valid? give-reason
    :effect (fn [world actor {id :item to :to}]
              [{:type :item/given :data {:item id :to to} :visible-to (space/here world actor)}])}])

;; ---------------------------------------------------------------------------
;; Views

(defn- carrying-names [world id] (mapv :name (by-name (held-by world id))))

(defn add-to-view
  "The item parts of an agent's view: what they carry (with its condition),
  the items in their place (names only), and what the people here carry.
  Each is left out when empty."
  [view world agent-id]
  (let [carried (by-name (held-by world agent-id))
        here (when (get-in view [:location :here]) (space/location-of world agent-id))
        lying (by-name (items-at world here))]
    (cond-> view
      (seq carried) (assoc-in [:you :carrying] (mapv #(select-keys % [:id :name :condition]) carried))
      (seq lying) (assoc-in [:location :here :items] (mapv #(select-keys % [:id :name]) lying))
      (get-in view [:location :people-here])
      (update-in [:location :people-here]
                 (fn [people]
                   (mapv #(let [c (carrying-names world (:id %))] (cond-> % (seq c) (assoc :carrying c)))
                         people))))))

(defn gm-view
  "Every item, for the GM."
  [world]
  (vec (all world)))
