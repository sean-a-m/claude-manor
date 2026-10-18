(ns manor.items
  "Items: objects with a description and a few aspects that lie in a place or
  are carried by an agent. An opt-in engine module, like manor.space: a
  scenario turns it on with the key :items (a map, possibly empty), and its
  :init may return starting :items.

  World shape:
    :items {\"china-cabinet\" {:id \"china-cabinet\" :name \"China cabinet\"
                              :desc \"A glass-fronted walnut cabinet.\"   ; 1-400 chars, never changes
                              :aspects {:cleanliness \"dusty\"            ; optional: at most 4, keys
                                        :repair \"the glass cracked\"}    ;   [a-z-], values 1-80 chars
                              :place \"drawing-room\"   ; exactly one of :place (a location id)
                              ;; :holder \"hale\"       ;   or :holder (an agent id)
                              ;; :in \"sideboard\"      ;   or :in (the id of a container item)
                              :owner \"household\"      ; optional: an agent id or any label; not enforced
                              :fixed? true            ; optional: furniture, can't be taken
                              :consumable? true       ; optional: food, fuel, soap: work may use it up
                              ;; :drawn-from \"pantry\" ; optional: a non-consumable drawn from stock
                              :container :open        ; optional: holds things; :open shows them to
                                                      ;   the room, :closed only to `open`
                              :touched 12}}           ; set by the reducers: the round it was last
                                                      ;   taken, put down or in, given or changed
    :items-gone {\"beef-stock\" {:name \"Beef stock\" :place \"kitchen\"
                                :round 12 :fate \"eaten at supper\"}}

  A carried item goes wherever its holder goes, and things in a container go
  wherever it goes, with no events. The world has :items once the first item
  is added, so worlds without items are unchanged. Used-up items keep their
  names under :items-gone, so older lines still read, and their ids are never
  given out again.

  Timed activities (`spend-time`, with `work-on` as an alias; clock required) are jobs:
    :item-jobs {\"job-41\" {:id \"job-41\" :actor \"hale\" :items [\"china-cabinet\"]
                           :doing \"dusting the china cabinet\" :place \"drawing-room\"
                           :started 12 :due 15}}
  The worker is occupied until :due, with the job id in their :busy :job.
  Nothing changes until the work is over: at the start of the round it
  ends, the engine asks a judge (a narrow model call, never seen by the
  characters) what the activity did to its local scene (place, focus,
  other visible items and stores in scope), checks the answer and records it as
  :item/work-done: changed aspects, consumable items used up, and at most
  two things made or drawn from stores, and things absorbed into stock.
  Locations have :aspects too (the room itself:
  floor, hearth, air), which only work changes.

  Code outside this namespace never reads (:items world) directly: it uses
  the accessors below, and only the reducers here write items. That keeps
  faster lookups (an index kept by the same reducers, say) a change inside
  this namespace."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [malli.core :as m]
            [malli.error :as me]
            [malli.json-schema :as mjs]
            [manor.actions :as a]
            [manor.adjudication :as adjudication]
            [manor.builtins :as b]
            [manor.clock :as clock]
            [manor.narrate :as n]
            [manor.rng :as rng]
            [manor.space :as space]
            [manor.world :as w]))

(def max-desc 400)
(def max-aspect 80)
(def max-aspects 4)
(def max-name 60)
(def max-summary 300)
(def max-work-items 6)
(def max-work-hours 4)
(def max-made 2)
(def default-work-hours 0.5)

(defn aspect-key?
  "Is `k` an aspect's key: a plain keyword of at most 24 lower-case letters
  and hyphens, starting with a letter (:cleanliness, :glass-eye)?"
  [k]
  (and (keyword? k) (nil? (namespace k)) (boolean (re-matches #"^[a-z][a-z-]{0,23}$" (name k)))))

(def Aspects
  "What's notable about an item or a place: at most `max-aspects` keyed
  facets, each a short phrase ({:cleanliness \"dusty\" :repair \"one glass eye loose\"})."
  [:map-of {:max max-aspects}
   [:fn {:error/message "should be a key of lower-case letters and hyphens, at most 24 characters"} aspect-key?]
   [:string {:min 1 :max max-aspect}]])

(def Item
  "An item as written in a world file or returned by :init (holder and owner
  are agent names in a world file, agent ids elsewhere)."
  [:map {:closed true}
   [:id [:re #"^[a-z0-9]+(-[a-z0-9]+)*$"]]
   [:name [:string {:min 1 :max max-name}]]
   [:desc [:string {:min 1 :max max-desc}]]
   [:aspects {:optional true} Aspects]
   [:place {:optional true} :string]
   [:holder {:optional true} :string]
   [:in {:optional true} :string]
   [:owner {:optional true} :string]
   [:fixed? {:optional true} :boolean]
   [:consumable? {:optional true} :boolean]
   [:drawn-from {:optional true} :string]
   [:container {:optional true} [:enum :open :closed]]
   [:store {:optional true} [:map {:closed true}
                            [:keeps [:string {:min 1 :max 200}]]
                            [:serves {:optional true} [:vector :string]]]]])

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

(defn store?
  "Does this item stand for untracked supplies?"
  [it]
  (map? (:store it)))

(defn stores-for
  "Stores at `place` or serving it, in id order."
  [world place]
  (if place
    (items-where world #(and (store? %) (or (= place (:place %))
                                           (some #{place} (get-in % [:store :serves])))))
    []))

(defn- store-view [place it]
  (cond-> (select-keys it [:id :name :aspects])
    (not= place (:place it)) (assoc :at (:place it))))

(defn held-by [world agent-id] (items-where world #(= agent-id (:holder %))))

(defn jobs
  "Work in progress, in order of :due, then id."
  [world]
  (sort-by (juxt :due :id) (vals (:item-jobs world))))

(defn job [world id] (get-in world [:item-jobs id]))

(defn names
  "Item id -> name, as [:item id] keys for manor.narrate/names, used-up
  items included."
  [world]
  (into {} (map (fn [[id item-name]] [[:item id] item-name]))
        (concat (map (fn [[id fate]] [id (:name fate)]) (:items-gone world))
                (map (juxt :id :name) (all world)))))

(defn- by-name [items] (sort-by (juxt :name :id) items))

(defn contents
  "The items directly inside the container `id`, by name."
  [world id]
  (by-name (items-where world #(= id (:in %)))))

(defn within
  "Every item inside the container `id` at any depth, each followed by its
  own contents."
  [world id]
  (mapcat #(cons % (within world (:id %))) (contents world id)))

(defn outermost
  "The item that `it` is ultimately inside, or `it` itself if it's in
  nothing: the one that lies at a place or is held."
  [world it]
  (loop [it it seen #{}]
    (if-let [c (when-not (seen (:id it)) (some->> (:in it) (item world)))]
      (recur c (conj seen (:id it)))
      it)))

(defn place-of
  "The place where `it` lies, directly or inside containers; nil if it's carried."
  [world it]
  (:place (outermost world it)))

(defn reachable?
  "Can `actor` get at `it`: does it (or the container it's in) lie where they
  are, or do they hold it?"
  [world actor it]
  (let [o (outermost world it)
        here (space/location-of world actor)]
    (boolean (or (= actor (:holder o)) (and here (= here (:place o)))))))

(defn visible-at
  "The items someone at `place` can see: those lying there, each followed by
  the contents of open containers at any depth; never what's in closed ones."
  [world place]
  (letfn [(seen [it] (cons it (when (= :open (:container it)) (mapcat seen (contents world (:id it))))))]
    (mapcat seen (by-name (items-at world place)))))

;; ---------------------------------------------------------------------------
;; Checks

(defn- label [it] (pr-str (or (:id it) (:name it))))

(defn- loops?
  "Does following :in from `it` (through `by-id`) come back round?"
  [by-id it]
  (loop [it it seen #{}]
    (cond (nil? (:in it)) false
          (seen (:id it)) true
          :else (recur (by-id (:in it)) (conj seen (:id it))))))

(defn problems
  "Everything wrong with a list of items, as readable strings naming the item.
  `places` and `holders` are the known location ids and holders (agent names
  in a world file, agent ids otherwise); `existing` is the items already in
  the world, by id, which an :in may also name."
  [items {:keys [places holders existing]}]
  (let [by-id (merge existing (into {} (map (juxt :id identity)) items))]
    (vec (concat
          (for [[id k] (frequencies (keep :id items)) :when (> k 1)]
            (str "item " (pr-str id) " is defined " k " times"))
          (mapcat
           (fn [it]
             (let [where (str "item " (label it) ": ")
                   box (by-id (:in it))]
               (if-let [e (m/explain Item it)]
                 [(str where (pr-str (me/humanize e)))]
                 (concat
                  (when-not (= 1 (count (filter #(contains? it %) [:place :holder :in])))
                    [(str where "give exactly one of :place, :holder and :in")])
                  (when (and (:place it) (not (contains? places (:place it))))
                    [(str where "unknown location " (pr-str (:place it)))])
                  (when (and (store? it) (not (:fixed? it)))
                    [(str where "a store must be :fixed?")])
                  (when (and (store? it) (:consumable? it))
                    [(str where "a store cannot be :consumable?")])
                  (for [place (get-in it [:store :serves]) :when (not (contains? places place))]
                    (str where "store serves unknown location " (pr-str place)))
                  (when (and (:holder it) (not (contains? holders (:holder it))))
                    [(str where "unknown holder " (pr-str (:holder it)))])
                  (when (and (:in it) (nil? box))
                    [(str where "unknown container " (pr-str (:in it)))])
                  (when (and box (not (:container box)))
                    [(str where (pr-str (:in it)) " is not a container")])
                  (when (and box (loops? by-id it))
                    [(str where "its containers go round in a loop")])))))
           items)))))

;; ---------------------------------------------------------------------------
;; Events

(defn init-events
  "Events adding the starting items (from a scenario's :init), visible to the GM."
  [items]
  (for [it items]
    {:type :item/added :data {:item it} :visible-to :gm}))

(defn fresh-id
  "An item id from `item-name` that no item has or had: \"umbrella\", then
  \"umbrella-2\"."
  [world item-name]
  (let [base (b/slug item-name)
        taken? #(or (item world %) (contains? (:items-gone world) %))]
    (first (remove taken? (cons base (map #(str base "-" %) (iterate inc 2)))))))

(defn- new-item
  "`fields` (an item's keys without :id) with a fresh id; throws if they
  don't make a valid item."
  [world fields]
  (let [it (assoc fields :id (fresh-id world (:name fields)))]
    (when-let [ps (seq (problems [it] {:places (set (keys (get-in world [:space :locations])))
                                      :holders (set (keys (:agents world)))
                                      :existing (:items world)}))]
      (throw (ex-info (str "bad new item: " (str/join "; " ps)) {:item it})))
    it))

(defn added
  "An :item/added event for a new item with a fresh id. `fields` are the
  item's keys without :id; throws if they don't make a valid item. An
  `:amount` (\"20 × 1 lb\") is shown after the name when the line is read."
  [world fields visible-to & {:keys [amount]}]
  {:type :item/added :visible-to visible-to
   :data (cond-> {:item (new-item world fields)} amount (assoc :amount amount))})

(defn added-events
  "`added` events for several new items, each with its own fresh id."
  [world fields-list visible-to]
  (second (reduce (fn [[world evs] fields]
                    (let [e (added world fields visible-to)]
                      [(w/apply-event world e) (conj evs e)]))
                  [world []]
                  fields-list)))

(defmethod w/apply-event :item/added [world {:keys [data]}]
  (assoc-in world [:items (get-in data [:item :id])] (assoc (:item data) :touched (:round world))))

(defn- touch
  "The item `id` marked as handled or changed this round."
  [world id]
  (assoc-in world [:items id :touched] (:round world)))

(defmethod w/apply-event :item/taken [world {:keys [actor data]}]
  (-> world
      (update-in [:items (:item data)] #(-> % (dissoc :place :in) (assoc :holder actor)))
      (touch (:item data))))

(defmethod w/apply-event :item/put-down [world {:keys [data]}]
  (-> world
      (update-in [:items (:item data)] #(-> % (dissoc :holder :in) (assoc :place (:place data))))
      (touch (:item data))))

(defmethod w/apply-event :item/put-in [world {:keys [data]}]
  (-> world
      (update-in [:items (:item data)] #(-> % (dissoc :place :holder) (assoc :in (:into data))))
      (touch (:item data))))

(defmethod w/apply-event :item/given [world {:keys [data]}]
  (-> world
      (assoc-in [:items (:item data) :holder] (:to data))
      (touch (:item data))))

(defmethod w/apply-event :item/work-begun [world {:keys [data]}]
  (assoc-in world [:item-jobs (:id data)] data))

(defn absorbable?
  "May this thing return to ambient stock? Never fixed things or stores."
  [it]
  (boolean (and it (not (:fixed? it)) (not (store? it))
                (or (:consumable? it) (:drawn-from it)))))

(defn- remove-things
  "Remove a batch, recording fates from the pre-removal world so descendants
  retain their place even when their container is removed first."
  [world used absorbed]
  (reduce (fn [after {id :item :keys [name fate into]}]
            (let [it (item world id)
                  outer (outermost world it)]
              (-> after
                  (update :items dissoc id)
                  (assoc-in [:items-gone id]
                            {:name name :place (or (:place outer) (space/location-of world (:holder outer)))
                             :round (:round world)
                             :fate (if into (str "put away in the " (:name (item world into))) fate)}))))
          world (concat used absorbed)))

(defn- set-aspect
  "The thing at `path` with aspect `k` set to `to`, or removed if `to` is
  nil; a thing with no aspects left has no :aspects."
  [world path k to]
  (update-in world path (fn [thing]
                          (let [aspects (if to (assoc (:aspects thing) k to) (dissoc (:aspects thing) k))]
                            (if (seq aspects) (assoc thing :aspects aspects) (dissoc thing :aspects))))))

(defn- changed
  "Apply one change of an aspect: of an item (which is then touched) or of a place."
  [world {:keys [item place aspect to]}]
  (if item
    (touch (set-aspect world [:items item] aspect to) item)
    (set-aspect world [:space :locations place] aspect to)))

(declare in-reach)

(defmethod w/apply-event :item/work-done [world {:keys [data]}]
  (as-> world world
    (reduce touch world (keep :id (in-reach world (job world (:job data)))))
    (reduce changed world (:changes data))
    (remove-things world (:used-up data) (:absorbed data))
    (reduce (fn [world it] (touch (assoc-in world [:items (:id it)] it) (:id it))) world (:made data))
    (update world :item-jobs dissoc (:job data))))

(defmethod w/apply-event :item/work-ended [world {:keys [data]}]
  (update world :item-jobs dissoc (:job data)))

;; ---------------------------------------------------------------------------
;; Narration: names are looked up when the line is written.

(defn- lower-first [s] (str (str/lower-case (subs s 0 1)) (subs s 1)))

(defn- the [names id]
  (str "the " (lower-first (get names [:item id] id))))

(defn- a-or-an [s]
  (str (if (re-find #"(?i)^[aeiou]" s) "an " "a ") (lower-first s)))

(defmethod n/describe :item/added [{:keys [data]} names]
  (let [{:keys [holder place in] item-name :name} (:item data)
        {:keys [amount]} data
        named (str item-name (when amount (str " (" amount ")")))]
    (cond
      holder (str (n/who names holder) " now has "
                  (if amount (lower-first named) (a-or-an item-name)) ".")
      in (str "Item added to " (the names in) ": " named ".")
      :else (str "Item added in " (n/place names place) ": " named "."))))

(defmethod n/describe :item/taken [{:keys [actor data]} names]
  (if-let [box (:from-item data)]
    (str (n/who names actor) " takes " (the names (:item data)) " from " (the names box) ".")
    (str (n/who names actor) " picks up " (the names (:item data)) ".")))

(defmethod n/describe :item/put-in [{:keys [actor data]} names]
  (str (n/who names actor) " puts " (the names (:item data)) " in " (the names (:into data)) "."))

(defmethod n/describe :item/put-down [{:keys [actor data]} names]
  (str (n/who names actor) " puts down " (the names (:item data)) "."))

(defmethod n/describe :item/given [{:keys [actor data]} names]
  (str (n/who names actor) " gives " (the names (:item data)) " to " (n/who names (:to data)) "."))

(defn- work-opener
  "\"Hale finishes dusting the china cabinet.\", or \"leaves off\" when they stopped early."
  [worker-name doing stopped-early?]
  (str worker-name (if stopped-early? " leaves off " " finishes ") doing "."))

(defmethod n/describe :item/work-begun [{:keys [actor data]} names]
  (str (n/who names actor) " starts " (:doing data) " (" (:id data) (when (seq (:items data)) (str ", on " (str/join ", " (:items data))))
       ", until " (n/at names (:due data)) ")."))

(defn- aspect-name [k] (str/replace (name k) "-" " "))

(defn- change-text
  "The new value (\"swept\"), or \"no longer lacking\" for an aspect removed:
  values are whole phrases, so the key isn't repeated."
  [{:keys [aspect to]}]
  (or to (str "no longer " (aspect-name aspect))))

(defn- changes-text
  "\"Drawing room: swept; Piano: dusted\": the changes, thing by thing, in
  the order they come."
  [names changes]
  (let [thing (fn [{:keys [item place]}] (if item [:item item] [:loc place]))
        by-thing (group-by thing changes)]
    (str/join "; " (for [k (distinct (map thing changes))]
                     (str (get names k (second k)) ": " (str/join ", " (map change-text (by-thing k))))))))

(defmethod n/describe :item/work-done [{:keys [actor data]} names]
  (let [{:keys [doing stopped-early? summary changes used-up absorbed made fetched]} data
        fetched-items (filter #(some #{(:id %)} fetched) made)
        made (remove #(some #{(:id %)} fetched) made)
        opener (work-opener (n/who names actor) doing stopped-early?)]
    (str opener
         (when (and summary (not= summary opener)) (str " " summary))
         (when (seq changes)
           (str " (" (changes-text names changes) ")."))
         (when (seq used-up)
           (str " Used up: " (str/join "; " (map :name used-up)) "."))
         (when (seq absorbed)
           (str " Put away: " (str/join "; " (for [{:keys [name into]} absorbed]
                                              (str name " (in the " (get names [:item into] into) ")"))) "."))
         (when (seq fetched-items)
           (str " Fetched: " (str/join "; " (map :name fetched-items)) "."))
         (when (seq made)
           (str " Made: " (str/join "; " (for [it made] (str (:name it) (when (:in it) (str " in " (the names (:in it)))))))
                ".")))))

(defmethod n/describe :item/work-ended [{:keys [actor data]} names]
  (if (= :retired (:reason data))
    (str (n/who names actor) "'s work (" (:doing data) ", " (:job data) ") is dropped.")
    (str (n/who names actor) " leaves off " (:doing data) " before getting anywhere.")))

(defmethod n/describe :item/judge-failed [{:keys [data]} _]
  (str "The judge's answer for " (:job data) " was not used: " (:reason data)))

;; ---------------------------------------------------------------------------
;; Actions, for every role but the GM: all free but timed activities. `look` and
;; `open` change nothing: they only reply.

(defn- listing
  "\"china-cabinet (China cabinet), piano (Piano)\", or `none`."
  [items none]
  (if (seq items)
    (str/join ", " (map #(str (:id %) " (" (:name %) ")") (by-name items)))
    none))

(defn- here-list
  "The items `actor` can see here, for reasons: never what's in closed containers."
  [world actor]
  (str "items here: " (listing (visible-at world (space/location-of world actor)) "none")))

(defn- carried-list [world actor]
  (str "you carry: " (listing (held-by world actor) "nothing")))

(defn- take-reason
  "Why `actor` can't take the item `id`, or nil: it must lie here, directly or
  in a container (at any depth, open or closed), and not be fixed. Something
  inside what another person holds is just \"not here\", so nothing hidden leaks."
  [world actor {id :item}]
  (let [it (item world id)
        holder (:holder it)]
    (cond
      (nil? (space/location-of world actor)) "you can't pick things up while travelling"
      (= actor holder) (str "you are already carrying the " (:name it))
      (and holder (space/together? world actor holder))
      (str (:name (w/agent world holder)) " is holding the " (:name it))
      (not (and it (reachable? world actor it))) (str id " is not here; " (here-list world actor))
      (:fixed? it) (str "the " (:name it) " is fixed in place"))))

(defn- container-reason
  "Why `actor` can't get at the container `id`, or nil."
  [world actor id verb]
  (let [box (item world id)]
    (cond
      (not (and box (reachable? world actor box))) (str id " is not here; " (here-list world actor))
      (not (:container box)) (str "the " (:name box) " is not something you can " verb))))

(defn- put-in-reason
  "Why `actor` can't put the item `id` in the container `to`, or nil: the
  item is carried or could be taken here, the container is here or carried,
  and the item isn't the container or around it."
  [world actor {id :item to :container}]
  (let [it (item world id)]
    (or (when-not (and it (= actor (:holder it))) (take-reason world actor {:item id}))
        (container-reason world actor to "put things in")
        (cond
          (or (= id to) (some #(= to (:id %)) (within world id)))
          (str "you can't put the " (:name it) " inside itself")
          (= to (:in it)) (str "the " (:name it) " is already in the " (:name (item world to)))))))

(defn- open-reason [world actor {id :container}]
  (if (space/location-of world actor)
    (container-reason world actor id "open")
    "you can't open things while travelling"))

(defn- carrying-reason [world actor id]
  (when-not (= actor (:holder (item world id)))
    (str "you are not carrying " id "; " (carried-list world actor))))

(defn- give-reason [world actor {id :item to :to}]
  (or (carrying-reason world actor id)
      (cond (= to actor) "you already have it; give it to someone else here"
            (not (some #(= to (:id %)) (w/active-agents world))) (str "no one has the id " to)
            (not (space/together? world actor to)) (str to " is not here"))))

(defn- owner-name
  "An owner as shown: an agent's name, or the label as it is."
  [world owner]
  (or (:name (w/agent world owner)) owner))

(defn- look-item [world it]
  (cond-> (select-keys it [:id :name :desc :aspects])
    (:owner it) (assoc :owner (owner-name world (:owner it)))
    (:fixed? it) (assoc :fixed? true)
    (store? it) (assoc :keeps (get-in it [:store :keeps]))))

(defn- as-seen
  "`it` as `shape` shows it, and if it's a container, what can be seen of
  its inside: an open one's :contents, each shown the same way, or :closed."
  [world shape it]
  (case (:container it)
    :open (assoc (shape it) :contents (mapv #(as-seen world shape %) (contents world (:id it))))
    :closed (assoc (shape it) :closed true)
    (shape it)))

(defn opened
  "What `open` shows of a container: its name and aspects, and its contents
  in detail, inner containers as the room would see them."
  [world box]
  (merge (select-keys box [:name :aspects])
         {:contents (mapv #(as-seen world (partial look-item world) %) (contents world (:id box)))}))

(defn- look-person [world id]
  (let [a (w/agent world id)
        carried (by-name (held-by world id))]
    (cond-> {:name (:name a)}
      (:busy a) (assoc :doing (get-in a [:busy :activity]))
      (seq carried) (assoc :carrying (mapv #(select-keys % [:id :name :desc :aspects]) carried)))))

(defn look
  "What `actor` sees on looking around: the place (with its :aspects and
  :detail, if it has them), the items lying here in detail (with what's in
  open containers), and the other people here with what they carry. Empty
  parts are left out."
  [world actor]
  (let [here (space/location-of world actor)
        loc (space/location world here)
        lying (by-name (items-at world here))
        stores (stores-for world here)
        people (sort (disj (space/present world here) actor))]
    (cond-> {:place (select-keys loc [:name :aspects :detail])}
      (seq lying) (assoc :items (mapv #(as-seen world (partial look-item world) %) lying))
      (seq stores) (assoc :stores (mapv #(assoc (store-view here %) :keeps (get-in % [:store :keeps])) stores))
      (seq people) (assoc :people (mapv #(look-person world %) people)))))

(defn- work-reason
  "Why `actor` can't name `id` as focus: items here or carried, the place
  they're in, or a store serving it are allowed."
  [world actor id]
  (let [it (item world id)
        holder (:holder it)
        here (space/location-of world actor)]
    (cond
      (= actor holder) nil
      (and holder (space/together? world actor holder)) (str (:name (w/agent world holder)) " is holding the " (:name it))
      (and it (reachable? world actor it)) nil
      (= id here) nil
      (some #(= id (:id %)) (stores-for world here)) nil
      :else
      (str id " is neither here nor carried by you; this place: " here " (" (:name (space/location world here)) "); "
           (here-list world actor) "; " (carried-list world actor)))))

(defn place-worked?
  "Did `job` name its place among its items: work on the room itself?"
  [{:keys [items place]}]
  (boolean (some #{place} items)))

(defn- work-on-reason [world actor {ids :items}]
  (cond (nil? (:clock world)) "there is no game time to work in"
        (nil? (space/location-of world actor)) "you can't work on things while travelling"
        :else (some #(work-reason world actor %) (distinct ids))))

(defn- work-on-events
  "The job (seen by the GM) and the occupation that carries its id (seen by
  everyone here). Job ids come from the world's event count, so they are
  deterministic."
  [world actor {ids :items :keys [doing hours]}]
  (let [id (str "job-" (inc (:seq world)))
        due (+ (:round world) (clock/rounds-for (:clock world) (* 60 (or hours default-work-hours))))]
    [{:type :item/work-begun :visible-to :gm
      :data {:id id :actor actor :items (vec (distinct ids)) :doing doing
             :place (space/location-of world actor) :started (:round world) :due due}}
     (assoc-in (b/occupy world actor doing due) [:data :job] id)]))

(defn- timed-activity
  "One activity, with work-on's old argument spelling retained as an alias."
  [action-name]
  (let [alias? (= :work-on action-name)
        activity-args (fn [args] (if alias? args (assoc args :items (:on args))))]
    {:name action-name
     :kind :final
     :doc (str (when alias? "Alias for spend-time; items means on. ")
               "Spend time on an activity here, work or pastime. doing reads after \"is\", e.g. \"resting in the garden\". "
               "hours is half an hour to " max-work-hours ", default half an hour. "
               "Optionally name up to " max-work-items " things with " (if alias? "items" "on")
               ": items here or carried, this place's id, or stores in scope. Working on a container includes its contents. "
               "You get no turns until it ends; results show when you finish.")
     :args [:map
            [(if alias? :items :on) (if alias? {} {:optional true})
             [:vector {:min (if alias? 1 0) :max max-work-items} :string]]
            [:doing [:string {:min 3 :max 120}]]
            [:hours {:optional true} [:double {:min 0.5 :max max-work-hours}]]]
     :available? (fn [world _] (some? (:clock world)))
     :valid? (fn [world actor args] (work-on-reason world actor (activity-args args)))
     :effect (fn [world actor args] (work-on-events world actor (activity-args args)))}))

(defn actions []
  [{:name :look
    :kind :free
    :doc (str "Examine this place and its things closely: the room's detail, and the descriptions, aspects and owners of "
              "the items here and what the people here carry. "
              "Use it when you need to inspect the state of something or see it in detail; "
              "your view already lists who is here and what items are here, so don't look routinely on arrival.")
    :valid? (fn [world actor _]
              (when-not (space/location-of world actor) "you can't look around while travelling"))
    :reply (fn [world actor _] (look world actor))}

   {:name :take
    :kind :free
    :doc "Pick up an item in your place and carry it (use its id from `items` in your view)."
    :args [:map [:item :string]]
    :valid? take-reason
    :effect (fn [world actor {id :item}]
              (let [it (item world id)]
                [{:type :item/taken :data (cond-> {:item id :from-place (place-of world it)}
                                            (:in it) (assoc :from-item (:in it)))
                  :visible-to (space/here world actor)}]))}

   {:name :open
    :kind :free
    :doc (str "Open a container here (use its id) to see what's in it, in detail. "
              "Containers marked `closed` in your view show their contents only this way.")
    :args [:map [:container :string]]
    :valid? open-reason
    :reply (fn [world _ {id :container}] (opened world (item world id)))}

   {:name :put-in
    :kind :free
    :doc "Put an item (use its id) that you carry, or could pick up here, into a container here (its id)."
    :args [:map [:item :string] [:container :string]]
    :valid? put-in-reason
    :effect (fn [world actor {id :item to :container}]
              [{:type :item/put-in :data {:item id :into to} :visible-to (space/here world actor)}])}

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
              [{:type :item/given :data {:item id :to to} :visible-to (space/here world actor)}])}

   (timed-activity :spend-time)
   (timed-activity :work-on)])

;; ---------------------------------------------------------------------------
;; Views

(defn- carrying-names [world id] (mapv :name (by-name (held-by world id))))

(defn add-to-view
  "The item parts of an agent's view: what they carry (with its aspects),
  the items in their place (names only, with what's in open containers and
  closed ones marked), and what the people here carry. Each is left out when
  empty."
  [view world agent-id]
  (let [carried (by-name (held-by world agent-id))
        here (when (get-in view [:location :here]) (space/location-of world agent-id))
        lying (by-name (remove store? (items-at world here)))
        stores (stores-for world here)]
    (cond-> view
      (seq stores) (assoc-in [:location :here :stores] (mapv #(store-view here %) stores))
      (seq carried) (assoc-in [:you :carrying] (mapv #(select-keys % [:id :name :aspects]) carried))
      (seq lying) (assoc-in [:location :here :items] (mapv #(as-seen world (fn [it] (select-keys it [:id :name])) %) lying))
      (get-in view [:location :people-here])
      (update-in [:location :people-here]
                 (fn [people]
                   (mapv #(let [c (carrying-names world (:id %))] (cond-> % (seq c) (assoc :carrying c)))
                         people))))))

(defn gm-view
  "Every item, for the GM."
  [world]
  (vec (all world)))

;; ---------------------------------------------------------------------------
;; Finishing work. The engine takes the jobs that end as each round starts,
;; in independent batches: for each it asks the judge (the request is built here)
;; about the items still in reach, checks the answer, and emits the result.

(defn- minutes-of [world rounds]
  (* (max 0 rounds) (get-in world [:clock :minutes-per-round])))

(defn- job-outcome
  "How `job` stands as the round starts, or nil while it's still going on."
  [world {:keys [id actor started due]}]
  (let [round (:round world)]
    (cond
      (not (w/active? world actor)) {:outcome :dropped :minutes 0}
      (not= id (:job (w/busy world actor)))
      {:outcome :stopped :minutes (minutes-of world (- round 1 started)) :stopped-early? true}
      (<= due round) {:outcome :finished :minutes (minutes-of world (- due started)) :stopped-early? false})))

(defn ending-jobs
  "The jobs that end as this round starts, in order of :due then id, each
  {:job job :outcome o :minutes m :stopped-early? bool :luck 1-100}:
    :dropped   the worker has been retired
    :stopped   the worker's occupation is no longer this job (they stopped
               after being interrupted, or something replaced it): judged on
               the rounds before this one
    :finished  :due has come: judged on the whole time
  Call it on the world before occupations are released, so the worker of a
  job that is due still holds it."
  [world]
  (vec (for [job (jobs world)
             :let [o (job-outcome world job)]
             :when o]
         (assoc o :job job :luck (inc (rng/roll world [:judge (:id job)] 100))))))

(defn in-reach
  "The job's items still to hand (lying at the job's place, in a container or
  not, or carried by the worker), or stores in scope, each followed by everything inside it."
  [world {:keys [items place actor]}]
  (->> items
       (keep #(let [it (item world %)
                    o (some->> it (outermost world))]
                (when (and it (or (= actor (:holder o))
                                  (and place (= place (:place o)))
                                  (some (fn [store] (= (:id it) (:id store))) (stores-for world place))))
                  it)))
       (mapcat #(cons % (within world (:id %))))
       distinct))

(defn focus-things
  "Named things still in reach, including the place when named."
  [world {:keys [place] :as job}]
  (let [loc (when (place-worked? job) (space/location world place))]
    (into (if loc [(assoc (select-keys loc [:id :name :desc :aspects]) :room? true)] [])
          (in-reach world job))))

(defn scene
  "The activity's local scene: place, named focus (including container contents),
  at most twelve other visible items, stores in scope, and people present."
  [world {:keys [place] :as job}]
  (let [focus (focus-things world job)
        named (set (map :id focus))]
    {:place (assoc (select-keys (space/location world place) [:id :name :desc :aspects]) :room? true)
     :focus focus
     :also-here (vec (take 12 (remove #(or (named (:id %)) (store? %)) (visible-at world place))))
     :stores (vec (stores-for world place))
     :people (mapv (fn [id]
                     (cond-> {:name (:name (w/agent world id))}
                       (w/busy world id) (assoc :doing (:activity (w/busy world id)))))
                   (sort (space/present world place)))}))

(defn judged-things
  "Everything the scene judge may change, once each: place, focus, other
  visible items (at most twelve) and stores in scope."
  [world job]
  (let [{:keys [place focus also-here stores]} (scene world job)]
    (->> (concat [place] focus also-here stores)
         (reduce (fn [its it] (if (some #(= (:id it) (:id %)) its) its (conj its it))) [])
         vec)))

(defn job-scope
  "Places and stores a job can affect, including the places of named focus
  items (a store may serve several places). Tagged keys avoid id collisions."
  [world {:keys [place actor] :as job}]
  (into #{[:place place]}
        (concat (map #(vector :store (:id %)) (stores-for world place))
                (for [it (in-reach world job)]
                  [:place (or (place-of world it)
                              (space/location-of world (:holder (outermost world it)))
                              (space/location-of world actor))]))))

(defn empty-activity?
  "An unnamed activity with no place aspects, items here, or stores in scope
  needs only a neutral ending, not a judge call."
  [world {:keys [place items]}]
  (and (empty? items)
       (empty? (:aspects (space/location world place)))
       (empty? (items-at world place))
       (empty? (stores-for world place))))

(defn- audience
  "The people at the job's place, and the worker wherever they are."
  [world {:keys [actor place]}]
  (conj (space/present world place) actor))

(defn work-ended
  "Event: `job` ends with nothing done. `reason` is :retired (the GM sees
  it), or :no-time (the worker and people at the place see it)."
  [world job reason]
  {:type :item/work-ended :actor (:actor job)
   :visible-to (if (= :retired reason) :gm (audience world job))
   :data {:job (:id job) :place (:place job) :doing (:doing job) :reason reason}})

(defn neutral-summary
  "\"Hale finishes dusting the china cabinet.\": the summary when the judge
  gives none."
  [world {:keys [actor doing]} stopped-early?]
  (work-opener (:name (w/agent world actor)) doing stopped-early?))

(defn- made-items
  "New things with fresh ids. A named place-bound focus leaves them at the
  job's place; otherwise they go to the worker, unless `in` names a container."
  [world {:keys [actor place] :as job} [first-used] made]
  (let [place-bound? (or (place-worked? job) (some #(place-of world %) (in-reach world job)))]
    (second
     (reduce (fn [[world its] {:keys [in from consumable] :as m}]
               (let [it (new-item world
                                  (cond-> (assoc (select-keys m [:name :desc :aspects]) :consumable? consumable)
                                    (:owner first-used) (assoc :owner (:owner first-used))
                                    (and from (not consumable)) (assoc :drawn-from from)
                                    in (assoc :in in)
                                    (and (not in) (not place-bound?)) (assoc :holder actor)
                                    (and (not in) place-bound?) (assoc :place place)))]
                 [(assoc-in world [:items (:id it)] it) (conj its it)]))
             [world []] made))))

(defn- removal-data
  "Checked ids and fates as named event data; default fates describe the activity."
  [world doing {:keys [used-up absorbed fates]}]
  (cond-> {}
    (seq used-up) (assoc :used-up (mapv #(hash-map :item % :name (:name (item world %))
                                                 :fate (or (get fates %) (str "used up while " doing))) used-up))
    (seq absorbed) (assoc :absorbed (mapv (fn [{:keys [thing into]}]
                                          {:item thing :name (:name (item world thing)) :into into}) absorbed))))

(defn work-done
  "A checked activity result. All removals retain names and fates; new items
  are allocated on this world so ids remain deterministic across batches."
  [world job {:keys [minutes stopped-early? luck]} verdict]
  (let [gone (keep #(item world %) (:used-up verdict))
        made (made-items world job gone (:made verdict))
        fetched (mapv :id (keep (fn [[entry it]] (when (:from entry) it)) (map vector (:made verdict) made)))]
    {:type :item/work-done :actor (:actor job) :visible-to (audience world job)
     :data (cond-> (merge {:job (:id job) :actor (:actor job) :place (:place job) :doing (:doing job)
                          :minutes minutes :stopped-early? stopped-early? :luck luck
                          :summary (or (:summary verdict) (neutral-summary world job stopped-early?))
                          :changes (vec (:changes verdict))}
                         (removal-data world (:doing job) verdict))
             (seq made) (assoc :made made)
             (seq fetched) (assoc :fetched fetched))}))

(defn judge-failed
  "Event, for the operator only: why the judge's answer about `job` wasn't used."
  [job reason]
  {:type :item/judge-failed :visible-to :system :data {:job (:id job) :reason reason}})

;; ---------------------------------------------------------------------------
;; The judge: what the prompt holds, its one tool, and the checks on its answer

(def ^:private judge-schema
  [:map
   [:changes {:optional true} [:vector [:map [:thing :string] [:aspect :string] [:value [:string {:max max-aspect}]]]]]
   [:used-up {:optional true} [:vector :string]]
   [:absorbed {:optional true} [:vector [:map [:thing :string] [:into :string]]]]
   [:fates {:optional true} [:map-of :keyword :string]]
   [:made {:optional true} [:vector {:max max-made}
                            [:map
                             [:name [:string {:min 1 :max max-name}]]
                             [:desc [:string {:min 1 :max max-desc}]]
                             ;; value lengths are checked by `made-problem`: decoding drops map-of entries that don't fit
                             [:aspects {:optional true} [:map-of {:max max-aspects} :keyword :string]]
                             [:consumable :boolean]
                             [:from {:optional true} :string]
                             [:in {:optional true} :string]]]]
   [:summary [:string {:min 1 :max max-summary}]]])

(def ^:private judge-tool
  {:type "function"
   :function {:name "judge"
              :description (str "Give your judgement: each aspect the work changed (the thing's id, the aspect's key, "
                                "and its new value: a phrase of at most " max-aspect " characters, or \"\" to remove it), "
                                "the ids of consumable items it used up and their fates, things absorbed into stores, "
                                "at most " max-made " things made or drawn from stores, "
                                "and a summary of at most " max-summary " characters.")
              :parameters (mjs/transform judge-schema)}})

(def judge-instructions
  (str "You judge the results of an activity in a simulation. Someone has spent time on work or a pastime. "
       "Decide what that activity, by this person, realistically changed in the scene, and answer by calling the `judge` tool. "
       "The time may be too short for the whole job: then only part of it is done.

- Most activities change little, and a pastime often changes nothing. Empty changes, used-up, absorbed and made lists are normal, successful answers. Resting in the garden need not change anything. Do not invent events to make the activity eventful, even for an unusual luck roll.
- A store holds everything it `keeps` unless its aspects say otherwise. The listed items are notable things, not everything available.
- You may change aspects of the place, focus, also_here and stores. Only consumables in focus or also_here may be used up. People and recent lines are context, not things to change.
- A thing's `aspects` say what's notable about its state, each under its own key: cleanliness, repair, state, amount, stock and the like. A change sets one aspect of one thing: `thing` (its id), `aspect` (the key) and `value` (a short phrase of at most " max-aspect " characters saying how it is now).
- Change only the aspects the activity affected, and leave the others alone: polishing a cabinet changes its cleanliness, not its repair. Use a key the thing already has where one fits; a new key is lower-case letters and hyphens, at most 24 characters. A thing has at most " max-aspects " aspects. To remove an aspect that no longer applies, give it the value \"\".
- Leave out anything the work left unchanged.
- The `place` entry is the place itself: its aspects describe the room as a whole (floor, hearth, air), not the things in it, which have their own entries.
- An item's nature can't change: work can't turn a vase into gold. If the task is impossible or absurd, change nothing and say so in the summary.
- Only things marked `consumable` can be used up, and only once they are gone: food eaten or cooked into something else, fuel burnt, an ingredient used up. List their ids in `used-up`; give their fates in `fates` (id to short phrase), otherwise the fate is \"used up while <doing>\". Something partly used is not used up: change its `amount` to say what's left (\"about 17 lb left\").
- `made` is for at most " max-made " things really produced from what was used up, or fetched from a store in scope with `from` (that store's id; no use-up needed). Give each a name, desc, optional aspects and a `consumable` flag. Only promote something that now matters individually, not every utensil used. Give `in` to put it in a listed container; otherwise it goes to the worker's hands if no place-bound focus was named, else the job's place.
- `absorbed: [{thing, into}]` puts a thing from focus or also_here away in a store in scope (`into`, its id). Only unfixed, non-store things marked `consumable` or `drawn_from` can be absorbed. A container can go only with all its contents absorbed too. Do not absorb something still individually important.
- A thing listed with `in` is inside another of the things listed. The work may or may not have touched it: leave it out if it didn't.
- Luck is a roll from 1 to 100, and 50 is ordinary: low luck can mean mishaps (a slip, a breakage), high luck can mean it went unusually well. The roll alone does not require a change.
- The summary is one or two sentences, past tense, third person, for the people present, at most " max-summary " characters."))

(def ^:private pretty (json/object-mapper {:pretty true}))

(defn- judged-json
  "The judged things as the judge sees them: the room marked :room, and
  items with :in only when the container is among them, and :consumable
  and :container when true."
  [things]
  (let [ids (set (map :id things))]
    (mapv #(cond-> (select-keys % [:id :name :desc :aspects])
             (:room? %) (assoc :room true)
             (ids (:in %)) (assoc :in (:in %))
             (:consumable? %) (assoc :consumable true)
             (:drawn-from %) (assoc :drawn_from (:drawn-from %))
             (:container %) (assoc :container true))
          things)))

(defn judge-request
  "The scene judge's request, including only the worker's shared recent lines
  supplied by the engine through prompt/scene-recent."
  [scenario world job {:keys [minutes stopped-early? luck recent]}]
  (let [worker (w/agent world (:actor job))
        brief (when-let [f (get-in scenario [:items :judge-brief])] (f world))
        suggested (get-in scenario [:items :aspect-keys])
        {:keys [place focus also-here stores people]} (scene world job)
        scene-json {:place (dissoc place :room?)
                    :focus (judged-json focus)
                    :also_here (judged-json also-here)
                    :stores (mapv #(assoc (store-view (:place job) %) :keeps (get-in % [:store :keeps])) stores)
                    :people people :recent (vec (take-last 10 recent))}]
    {:tools [judge-tool]
     :messages
     [{:role "system"
       :content (str judge-instructions
                     (when (seq suggested)
                       (str "\n\n## Suggested aspect keys\n"
                            (str/join "\n" (for [[k gloss] (sort-by key suggested)] (str "- " (name k) ": " gloss)))))
                     (when-not (str/blank? brief) (str "\n\n## The setting\n" brief)))}
      {:role "user"
       :content (str "# The activity\n\n"
                     "- Time: " (clock/label (:clock world) (:round world)) "\n"
                     "- Worker: " (:name worker) (when-not (str/blank? (:persona worker)) (str ". " (:persona worker))) "\n"
                     "- Place: " (:name (space/location world (:place job)) (:place job)) "\n"
                     "- Doing: " (:doing job) "\n"
                     "- Time worked: " minutes " minutes" (if stopped-early? "; they stopped early" ", as planned") "\n"
                     "- Luck: " luck "\n\n"
                     "## The scene\n```json\n"
                     (json/write-value-as-string scene-json pretty)
                     "\n```\n\nCall `judge` now.")}]}))

(defn- ids-or-none [items] (if (seq items) (str/join ", " (map :id items)) "none"))

(defn- decoded-changes
  "The judge's changes as [{:thing :aspect :to}]: the aspect's name as a
  key, normalised like argument names (\"glass_eye\" is :glass-eye), and a
  blank value as a nil :to, removing the aspect. When the same aspect of a
  thing comes more than once, the last counts."
  [changes]
  (let [cs (for [{:keys [thing aspect value]} changes]
             {:thing thing :aspect (a/kebab-key aspect) :to (when-not (str/blank? value) value)})
        last-of (into {} (map (juxt (juxt :thing :aspect) identity)) cs)]
    (map last-of (distinct (map (juxt :thing :aspect) cs)))))

(defn- aspects-after
  "Each judged thing's aspects, by id, once `changes` are made."
  [things changes]
  (reduce (fn [m {:keys [thing aspect to]}]
            (if to (assoc-in m [thing aspect] to) (update m thing dissoc aspect)))
          (into {} (map (juxt :id #(or (:aspects %) {}))) things)
          changes))

(defn- key-problem
  "Why the aspect keys `ks` won't do, or nil."
  [ks]
  (when-let [bad (seq (distinct (remove aspect-key? ks)))]
    (str "an aspect's key is lower-case letters and hyphens, at most 24 characters, starting with a letter "
         "(cleanliness, glass-eye); not " (str/join ", " (map #(pr-str (name %)) bad)))))

(defn- change-problem
  "Why the judge can't make `changes`, or nil: each names one of the judged
  `things`, with a well-formed key, and no thing ends up with more than
  `max-aspects` aspects (counting those it has, less those cleared)."
  [things changes]
  (let [by-id (into {} (map (juxt :id identity)) things)
        strays (distinct (remove by-id (map :thing changes)))
        after (aspects-after things changes)
        crowded (first (filter #(> (count (after %)) max-aspects) (distinct (map :thing changes))))]
    (cond
      (seq strays) (str "you may change only these things: " (ids-or-none things) "; not " (str/join ", " strays))
      (key-problem (map :aspect changes)) (key-problem (map :aspect changes))
      crowded (str "the " (:name (by-id crowded)) " (" crowded ") would have " (count (after crowded)) " aspects ("
                   (str/join ", " (map name (keys (after crowded)))) "); a thing has at most " max-aspects
                   ": change one it has instead, or remove one with the value \"\""))))

(defn- use-up-problem
  "Why the judge can't use up `gone`: only consumables in the scene, not fixed,
  with all real descendants used up too, including any hidden or beyond the cap."
  [world reach gone]
  (let [usable (filter #(and (:consumable? %) (not (:fixed? %))) reach)
        bad (remove (set (map :id usable)) gone)
        gone? (set gone)
        shown? (set (map :id reach))
        [box left] (first (for [it reach
                               :when (gone? (:id it))
                               :let [left (remove #(gone? (:id %)) (within world (:id it)))]
                               :when (seq left)]
                           [it left]))]
    (cond
      (seq bad) (str "you may use up only consumable items among these: " (ids-or-none usable)
                     "; not " (str/join ", " bad))
      (some #(not (shown? (:id %))) left)
      (str "the " (:name box) " (" (:id box) ") still holds things not shown here; leave it, or name it with `on` next time")
      box (str "the " (:name box) " (" (:id box) ") still holds " (ids-or-none left)
               ": use those up too, or don't use it up"))))

(defn- made-problem
  "Why the judge can't make `made`, or nil: from a store or something used up,
  an :in must be a container in `reach` that isn't used up, and aspects
  need well-formed keys and values of 1 to `max-aspect` characters."
  [reach stores gone made]
  (let [boxes (filter #(and (:container %) (not ((set gone) (:id %)))) reach)
        bad-in (remove (set (map :id boxes)) (keep :in made))
        bad-value (first (for [{item-name :name :keys [aspects]} made
                               [k v] aspects
                               :when (not (<= 1 (count v) max-aspect))]
                           (str "made " (pr-str item-name) ": its " (name k) " is " (count v) " characters; give it 1 to " max-aspect)))]
    (cond
      (and (some #(not (:from %)) made) (empty? gone))
      "things are made only from consumable items the work used up: list those in used-up, or make nothing"
      (some #(and (:from %) (not ((set (map :id stores)) (:from %)))) made)
      (str "from must name a store in scope: " (ids-or-none stores))
      (seq bad-in) (str "in must be the id of a container among these: " (ids-or-none boxes)
                        "; not " (str/join ", " (distinct bad-in)))
      :else (or (key-problem (mapcat (comp keys :aspects) made)) bad-value))))

(defn- made-entries
  "The judge's `made` entries with their aspect keys normalised, and no
  :aspects when there are none."
  [made]
  (mapv #(let [aspects (update-keys (:aspects %) a/kebab-key)]
           (cond-> (dissoc % :aspects) (seq aspects) (assoc :aspects aspects)))
        made))

(defn- absorption-problem
  "Reject invalid removals without revealing unseen container contents."
  [world reach stores gone absorbed]
  (let [by-id (into {} (map (juxt :id identity)) reach)
        store-ids (set (map :id stores))
        ids (map :thing absorbed)
        removing (set ids)]
    (cond
      (not= (count ids) (count removing)) "absorb each thing only once"
      (some (set gone) ids) "a thing cannot be both used up and absorbed"
      (some #(not (absorbable? (by-id %))) ids) "absorb only unfixed consumable or drawn-from things in focus or also_here"
      (some #(not (store-ids (:into %))) absorbed) (str "into must name a store in scope: " (ids-or-none stores))
      (some (fn [id] (some #(not (removing (:id %))) (within world id))) ids)
      "a container still holds things; absorb all its contents too, or leave it")))

(defn- checked-verdict
  "Check decoded effects against this narrow scene. The steward may use up
  any absorbable candidate, but may change only candidates, never the room."
  [world args things stores steward?]
  (let [changes (decoded-changes (:changes args))
        by-id (into {} (map (juxt :id identity)) things)
        gone (vec (distinct (:used-up args)))
        absorbed (:absorbed args)
        removed (into (set gone) (map :thing absorbed))
        made (made-entries (:made args))
        fates (update-keys (:fates args) name)
        usable (if steward? (map #(assoc % :consumable? (absorbable? %)) things) things)]
    (if-let [problem (or (change-problem things changes)
                         (use-up-problem world usable gone)
                         (absorption-problem world things stores gone absorbed)
                         (made-problem (remove #(some #{(:id %)} (map :thing absorbed)) things) stores gone made)
                         (when (some #(not ((set gone) %)) (keys fates)) "fates may name only used-up things")
                         (when (some str/blank? (vals fates)) "a fate must not be blank"))]
      {:error problem}
      (cond-> {:summary (:summary args)
               :changes (vec (for [{:keys [thing aspect to]} changes
                                   :let [from (get-in by-id [thing :aspects aspect])]
                                   :when (and (not= from to) (not (removed thing)))]
                               (if (:room? (by-id thing))
                                 {:place thing :aspect aspect :from from :to to}
                                 {:item thing :aspect aspect :from from :to to})))}
        (seq gone) (assoc :used-up gone)
        (seq fates) (assoc :fates fates)
        (seq absorbed) (assoc :absorbed (vec absorbed))
        (seq made) (assoc :made made)))))

(defn- verdict [world arguments things]
  (let [{:keys [args error]} (a/decode-args {:args judge-schema} arguments)
        place (:id (first (filter :room? things)))]
    (if error {:error error}
        (checked-verdict world args things (stores-for world place) false))))

(defn judgement
  "Read a judge response: {:verdict v :replies [...]} or {:error reason
  :replies [...]}, where :replies are the messages to send back (a reply to
  each tool call, or a reminder if it made none). Only the first `judge`
  call counts. `things` are the judged things (see `judged-things`)."
  [response world things]
  (adjudication/answer "judge" response #(verdict world % things)))

(defn mock-judge
  "The judge in mock runs, a mock policy for calls with role :judge: every
  focused thing gets the cleanliness \"just seen to\". No focus means no
  changes: a quiet pastime is a normal result. The summary only says it ended."
  [{:keys [world job stopped-early?]}]
  {:tool :judge
   :args {:changes (mapv (fn [t] {:thing (:id t) :aspect "cleanliness" :value "just seen to"}) (focus-things world job))
          :summary (neutral-summary world job stopped-early?)}})

;; ---------------------------------------------------------------------------
;; The nightly steward sees only old, loose, absorbable things at one place.

(defn steward-day
  "The scheduled day due now, if its :at has been reached since the run's
  clock start and hasn't run yet. An already-past time on a partial first
  day is skipped; starting exactly at :at runs immediately."
  [world {:keys [at]}]
  (when (and at (:clock world))
    (let [now (clock/time-of (:clock world) (:round world))
          time (java.time.LocalTime/parse at)
          today (.atTime (.toLocalDate now) time)
          target (if (.isAfter today now) (.minusDays today 1) today)
          day (str (.toLocalDate target))]
      (when (and (not (.isBefore target (clock/time-of (:clock world) 1)))
                 (not= day (:items-steward-day world)))
        day))))

(defn steward-candidates
  "Old, visible, uncarried absorbable things, grouped by place in place-id
  order, oldest first (id breaks ties), capped per place per night."
  [world {:keys [idle-hours max-per-call]}]
  (into (sorted-map)
        (keep (fn [place]
                (let [candidates (->> (visible-at world place)
                                      (filter #(and (absorbable? %)
                                                    (>= (minutes-of world (- (:round world) (max 1 (or (:touched %) 0))))
                                                        (* 60 idle-hours))))
                                      (sort-by (juxt #(or (:touched %) 0) :id))
                                      (take max-per-call)
                                      vec)]
                  (when (seq candidates) [place candidates]))))
        (sort (keys (get-in world [:space :locations])))))

(defmethod w/apply-event :items/steward-ran [world {:keys [data]}]
  (assoc world :items-steward-day (:day data)))

(defmethod w/apply-event :items/stewarded [world {:keys [data]}]
  (remove-things (reduce changed world (:changes data)) (:used-up data) (:absorbed data)))

(defmethod n/describe :items/steward-ran [{:keys [data]} _]
  (str "The nightly steward ran for " (:day data) "."))

(defmethod n/describe :items/stewarded [{:keys [data]} names]
  (str "Steward at " (n/place names (:place data)) ": " (:summary data)))

(defmethod n/describe :items/steward-failed [{:keys [data]} _]
  (str "The steward's answer at " (:place data) " was not used: " (:reason data)))

(def ^:private steward-schema
  (into [:map] (remove #(= :made (first %)) (rest judge-schema))))

(def steward-instructions
  "You are the simulation's nightly steward. Decide the fates of only the listed idle candidates, calling `steward`.
- Empty answers are normal. Leave anything still individually important.
- For each candidate, you may absorb it into a listed store in scope (`absorbed: [{thing, into}]`), use it up (`used-up: [id]`, with a short fate in `fates: {id: phrase}`), change its aspects (`changes: [{thing, aspect, value}]`), or leave it.
- Absorb a container only with all its contents absorbed too; use one up only with all its contents used up too. Otherwise leave it.
- Change only candidates, never the place or stores. Aspect keys are lower-case letters and hyphens, at most 24 characters; values are 1–80 characters, or empty to remove. Keep at most four aspects per thing.
- Never make anything. A store holds everything it keeps unless its aspects say otherwise. Do not invent events. Give a short third-person summary, at most 300 characters, for the GM only.")

(defn steward-request
  "One place's narrow nightly request; no people, private lines or other rooms."
  [world place candidates]
  {:tools [{:type "function" :function {:name "steward" :description "Resolve only the listed idle candidates. Never make anything."
                                      :parameters (mjs/transform steward-schema)}}]
   :messages [{:role "system" :content steward-instructions}
              {:role "user" :content (str "## The idle scene\n```json\n"
                                           (json/write-value-as-string
                                            {:place (select-keys (space/location world place) [:id :name :desc :aspects])
                                             :stores (mapv #(assoc (store-view place %) :keeps (get-in % [:store :keeps]))
                                                           (stores-for world place))
                                             :candidates (judged-json candidates)} pretty)
                                           "\n```\n\nCall `steward` now.")}]})

(defn stewardship
  "Check the steward against only its candidates and this place's stores."
  [response world place candidates]
  (adjudication/answer "steward" response
                       (fn [arguments]
                         (let [{:keys [args error]} (a/decode-args {:args steward-schema} arguments)]
                           (if error {:error error}
                               (checked-verdict world args candidates (stores-for world place) true))))))

(defn stewarded
  "A checked nightly result, GM-only; no character sees it happen."
  [world place verdict]
  {:type :items/stewarded :visible-to :gm
   :data (merge {:place place :changes (vec (:changes verdict))
                 :summary (or (:summary verdict) "Everything was left as it was.")}
                (removal-data world "the nightly tidying" verdict))})

(defn mock-steward
  "Put candidates into the first store in scope, except containers whose
  descendants aren't all candidates; with no store leave everything alone."
  [{:keys [world place candidates]}]
  (let [store (first (stores-for world place))
        ids (set (map :id candidates))
        can-absorb? (fn [it] (and (absorbable? it)
                                  (every? #(and (ids (:id %)) (absorbable? %)) (within world (:id it)))))
        absorbed (when store (for [it candidates :when (can-absorb? it)] {:thing (:id it) :into (:id store)}))]
    {:tool :steward :args {:absorbed (vec absorbed) :summary (if (seq absorbed) "Idle things were put away." "Everything was left as it was.")}}))
