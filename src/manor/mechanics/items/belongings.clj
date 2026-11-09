(ns manor.mechanics.items.belongings
  "Checked first-arrival descriptions and ordinary item events. No inventory
  exists until the caller materialises them for an arrived person."
  (:require [clojure.string :as str]
            [manor.actions :as a]
            [manor.mechanics.items :as items]
            [manor.world :as w]))

(def max-roots 4)
(def max-objects 6)

(def ^:private object-fields
  [[:name [:string {:min 1 :max items/max-name}]]
   [:desc [:string {:min 1 :max items/max-desc}]]
   ;; Keep values through decoding so the ordinary aspect checks see bad lengths.
   [:aspects {:optional true} [:map-of {:max items/max-aspects} :keyword :any]]
   [:consumable {:optional true} :boolean]
   [:candidate-reference? {:optional true} :boolean]
   [:container {:optional true} [:enum :open :closed]]])

(def schema
  "At most one level of contents. Child container/contents fields are decoded
  too, so `problem` can reject rather than silently drop a nested container."
  [:vector {:max max-roots}
   (conj (into [:map] object-fields)
         [:contents {:optional true}
          [:vector {:max max-objects}
           (conj (into [:map] object-fields) [:contents {:optional true} [:vector :any]])]])])

(defn normalise-arguments
  "Normalise model argument names on objects, not the prose or aspect keys.
  Malformed shapes are left for the normal argument decoder to reject."
  [objects]
  (if-not (vector? objects)
    objects
    (mapv (fn [object]
            (if-not (map? object)
              object
              (let [object (update-keys object a/kebab-key)]
                (cond-> object
                  (contains? object :contents) (update :contents normalise-arguments)))))
          objects)))

(defn- all-objects [objects]
  (mapcat #(cons % (:contents %)) objects))

(defn problem
  "Why decoded descriptions are invalid, or nil. Only structure is checked;
  the writer, not code, judges the plausibility of the possessions."
  [objects sent-for?]
  (let [all (all-objects objects)
        children (mapcat :contents objects)
        references (filter :candidate-reference? all)
        aspect-problem (some #(when (:aspects %) (items/aspects-problem (:aspects %))) all)]
    (cond
      (> (count all) max-objects) (str "at most " max-objects " belongings in total")
      (some #(or (contains? % :container) (contains? % :contents)) children)
      "belongings may have only one level of contents, without child containers"
      (some #(and (contains? % :contents) (not (:container %))) objects)
      "contents require a container"
      (and (not sent-for?) (some #(contains? % :candidate-reference?) all))
      "candidate-reference? is only available for someone sent for"
      (> (count references) 1) "at most one reference marked candidate-reference?"
      (some #(str/blank? %) (mapcat (juxt :name :desc) all)) "belonging names and descriptions must not be blank"
      aspect-problem aspect-problem
      (some #(some str/blank? (vals (:aspects %))) all) "belonging aspects must not be blank")))

(defn descriptions
  "Trim a checked answer for private pending storage."
  [objects]
  (mapv (fn [object]
          (cond-> (-> object (update :name str/trim) (update :desc str/trim))
            (:aspects object) (update :aspects #(update-vals % str/trim))
            (contains? object :contents) (update :contents descriptions)))
        objects))

(defn arrival-events
  "Materialise checked descriptions once, after the caller's spawn events.
  Roots are carried and owned by `id`; children belong inside their new parent.
  Every allocation sees prior additions, including ids retained in items-gone.
  Only the owner sees these events; normal item views govern later discovery."
  [world id objects {:keys [candidate-id]}]
  (letfn [(add-object [[world events] object parent]
            (let [fields (cond-> (assoc (select-keys object [:name :desc :aspects :container]) :owner id)
                           parent (assoc :in parent)
                           (nil? parent) (assoc :holder id)
                           (contains? object :consumable) (assoc :consumable? (:consumable object))
                           (and candidate-id (:candidate-reference? object)) (assoc :candidate-id candidate-id))
                  event (items/added world fields #{id})
                  item-id (get-in event [:data :item :id])]
              (reduce #(add-object %1 %2 item-id)
                      [(w/apply-event world event) (conj events event)]
                      (:contents object))))]
    (second (reduce #(add-object %1 %2 nil) [world []] objects))))
