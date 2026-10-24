(ns manor.mechanics.items.views
  "What can be seen of items: the replies of `look` and `open`, and the
  item parts of each agent's view and the GM's."
  (:require [manor.mechanics.items :as items]
            [manor.mechanics.physical :as physical]
            [manor.mechanics.space :as space]
            [manor.world :as w]))

(defn- owner-name
  "An owner as shown: an agent's name, or the label as it is."
  [world owner]
  (or (:name (w/agent world owner)) owner))

(defn- look-item [world it]
  (cond-> (select-keys it [:id :name :desc :aspects :candidate-id])
    (:owner it) (assoc :owner (owner-name world (:owner it)))
    (:fixed? it) (assoc :fixed? true)
    (items/store? it) (assoc :keeps (get-in it [:store :keeps]))))

(defn- as-seen
  "`it` as `shape` shows it, and if it's a container, what can be seen of
  its inside: an open one's :contents, each shown the same way, or :closed."
  [world shape it]
  (case (:container it)
    :open (assoc (shape it) :contents (mapv #(as-seen world shape %) (items/contents world (:id it))))
    :closed (assoc (shape it) :closed true)
    (shape it)))

(defn opened
  "What `open` shows of a container: its name and aspects, and its contents
  in detail, inner containers as the room would see them."
  [world box]
  (merge (select-keys box [:name :aspects])
         {:contents (mapv #(as-seen world (partial look-item world) %) (items/contents world (:id box)))}))

(defn- look-person [world id]
  (let [a (w/agent world id)
        carried (items/by-name (items/held-by world id))]
    (cond-> (physical/with-status (select-keys a [:name :appearance :aspects]) a)
      (:busy a) (assoc :doing (get-in a [:busy :activity]))
      (seq carried) (assoc :carrying (mapv #(select-keys % [:id :name :desc :aspects :candidate-id]) carried)))))

(defn look
  "What `actor` sees on looking around: the place (with its :aspects and
  :detail, if it has them), the items lying here in detail (with what's in
  open containers), and the other people here, the dead among them, with
  their statuses and what they carry. Empty parts are left out."
  [world actor]
  (let [here (space/location-of world actor)
        loc (space/location world here)
        lying (items/by-name (items/items-at world here))
        stores (items/stores-for world here)
        people (sort (into (disj (space/present world here) actor) (physical/dead-at world here)))]
    (cond-> {:place (select-keys loc [:name :aspects :detail])}
      (seq lying) (assoc :items (mapv #(as-seen world (partial look-item world) %) lying))
      (seq stores) (assoc :stores (mapv #(assoc (items/store-view here %) :keeps (get-in % [:store :keeps])) stores))
      (seq people) (assoc :people (mapv #(look-person world %) people)))))

(defn- carrying-names [world id] (mapv :name (items/by-name (items/held-by world id))))

(defn add-to-view
  "The item parts of an agent's view: what they carry (with its aspects),
  the items in their place (names only, with what's in open containers and
  closed ones marked), and what the people here carry. The agent's own
  :carrying is always there, empty when they hold nothing, so a claim to have
  handed them something can be checked against it; the rest is left out
  when empty."
  [view world agent-id]
  (let [carried (items/by-name (items/held-by world agent-id))
        here (when (get-in view [:location :here]) (space/location-of world agent-id))
        lying (items/by-name (remove items/store? (items/items-at world here)))
        stores (items/stores-for world here)]
    (cond-> (assoc-in view [:you :carrying] (mapv #(select-keys % [:id :name :aspects]) carried))
      (seq stores) (assoc-in [:location :here :stores] (mapv #(items/store-view here %) stores))
      (seq lying) (assoc-in [:location :here :items] (mapv #(as-seen world (fn [it] (select-keys it [:id :name])) %) lying))
      (get-in view [:location :people-here])
      (update-in [:location :people-here]
                 (fn [people]
                   (mapv #(let [c (carrying-names world (:id %))] (cond-> % (seq c) (assoc :carrying c)))
                         people))))))

(defn gm-view
  "Every item, for the GM."
  [world]
  (vec (items/all world)))
