(ns manor.mechanics.items
  "Items: objects with a description and a few aspects that lie in a place or
  are carried by an agent. An opt-in engine module, like manor.mechanics.space: a
  scenario turns it on with the key :items (a map, possibly empty), and its
  :init may return starting :items.

  World shape:
    :items {\"china-cabinet\" {:id \"china-cabinet\" :name \"China cabinet\"
                              :desc \"A glass-fronted walnut cabinet.\"   ; 1-400 chars, never changes
                              :aspects {:cleanliness \"dusty\"            ; optional: at most 4, keys
                                        :repair \"the glass cracked\"}    ;   [a-z-], values 1-200 chars (advise 80)
                              :place \"drawing-room\"   ; exactly one of :place (a location id)
                              ;; :holder \"hale\"       ;   or :holder (an agent id)
                              ;; :in \"sideboard\"      ;   or :in (the id of a container item)
                              :owner \"household\"      ; optional: an agent id or any label; not enforced
                              :fixed? true            ; optional: furniture, can't be taken
                              :consumable? true       ; optional: food, fuel, soap: work may use it up
                              ;; :drawn-from \"pantry\" ; optional: a non-consumable drawn from stock
                              :container :open        ; optional: holds things; :open shows them to
                                                      ;   the room, :closed only to `open`
                              ;; :waiting {:for \"thorne\" :keeper \"garland\" :at \"poulterer\" :since 12}
                              ;;   optional: goods kept for someone; see `waiting?`
                              :touched 12}}           ; set by the reducers: the round it was last
                                                      ;   taken, put down or in, given or changed
    :items-gone {\"beef-stock\" {:name \"Beef stock\" :place \"kitchen\"
                                :round 12 :fate \"eaten at supper\"}}

  A carried item goes wherever its holder goes, and things in a container go
  wherever it goes, with no events. The world has :items once the first item
  is added, so worlds without items are unchanged. Used-up items keep their
  names under :items-gone, so older lines still read, and their ids are never
  given out again.

  Goods left at a shop for someone are ordinary items with a :waiting
  marker: who they're for, who keeps them, where and since when. They're
  shown as waiting only while they're there (see `waiting?`); the reducers
  drop the marker once they're taken, given, put down or packed away from it.

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
  Putting things in a store also starts a quick job, due next round with no
  occupation or time worked; put-ins by one worker into one store in a round
  share that job.
  Locations have :aspects too (the room itself:
  floor, hearth, air), which only work changes. So do people, under
  [:agents id :aspects]: how they differ now from their fixed :appearance
  (dress, hair, dirt), which work changes and the nightly steward may
  remove from sleepers.

  Code outside the items module never reads (:items world) directly: it
  uses the accessors below, and only the reducers here write items, those
  for the steward's and finding's events too, so replay needs only this
  namespace. That keeps faster lookups (an index kept by the same reducers,
  say) a change inside this namespace.

  The module's other parts are in manor.mechanics.items.*: views (what
  `look`, `open` and an agent's view show), actions, work (jobs that end),
  judge (what the judge is asked and the checks on its answer), steward
  (the nightly tidy) and find."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.space :as space]
            [manor.narrate :as n]
            [manor.world :as w]))

(def max-desc 400)

(def max-aspect 200)

(def advised-aspect 80)

(def max-aspects 4)

(def max-name 60)

(def max-summary 300)

(def max-work-items 6)

(def max-work-hours 4)

(def max-doing 400)

(def advised-doing 200)

(def max-made 2)

(def default-work-hours 0.5)

(defn aspect-key?
  "Is `k` an aspect's key: a plain keyword of at most 24 lower-case letters
  and hyphens, starting with a letter (:cleanliness, :glass-eye)?"
  [k]
  (and (keyword? k) (nil? (namespace k)) (boolean (re-matches #"^[a-z][a-z-]{0,23}$" (name k)))))

(def Aspects
  "What's notable about an item, a place or a person: at most `max-aspects`
  keyed facets, each a short phrase ({:cleanliness \"dusty\" :repair \"one glass eye loose\"})."
  [:map-of {:max max-aspects}
   [:fn {:error/message "should be a key of lower-case letters and hyphens, at most 24 characters"} aspect-key?]
   [:string {:min 1 :max max-aspect}]])

(defn aspects-problem
  "Why `aspects` aren't well formed (see `Aspects`), readably, or nil."
  [aspects]
  (when-let [e (m/explain Aspects aspects)]
    (pr-str {:aspects (me/humanize e)})))

(def max-impediments 3)

(def Impediments
  "A person's open physical hindrances, a subcategory of their aspects
  (manor.mechanics.physical): keyed phrases as aspects are, at most
  `max-impediments` of them ({:hand \"a cut finger, bound in a rag\"})."
  [:map-of {:max max-impediments}
   [:fn {:error/message "should be a key of lower-case letters and hyphens, at most 24 characters"} aspect-key?]
   [:string {:min 1 :max max-aspect}]])

(defn impediments-problem
  "Why `impediments` aren't well formed (see `Impediments`), readably, or nil."
  [impediments]
  (when-let [e (m/explain Impediments impediments)]
    (pr-str {:impediments (me/humanize e)})))

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
   [:candidate-id {:optional true} :string]
   [:order-id {:optional true} :string]
   [:container {:optional true} [:enum :open :closed]]
   [:waiting {:optional true} [:map {:closed true}
                               [:for :string]
                               [:keeper :string]
                               [:at :string]
                               [:since :int]]]
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

(defn store-view [place it]
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

(defn by-name [items] (sort-by (juxt :name :id) items))

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

(defn waiting?
  "Is `it` shown as waiting for someone: has it a :waiting marker, and does
  it (or the container it's in) lie at the marker's :at, or is it held by
  the :keeper while they're there?"
  [world it]
  (boolean (when-let [{:keys [at keeper]} (:waiting it)]
             (let [o (outermost world it)]
               (or (= at (:place o))
                   (and (= keeper (:holder o)) (= at (space/location-of world keeper))))))))

(defn waiting-for
  "The items shown as waiting for `agent-id` (see `waiting?`), in id order."
  [world agent-id]
  (items-where world #(and (= agent-id (get-in % [:waiting :for])) (waiting? world %))))

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
                  (when-let [{:keys [at] :as waiting} (:waiting it)]
                    (concat
                     (for [k [:for :keeper] :when (not (contains? holders (k waiting)))]
                       (str where "waiting " (name k) " unknown person " (pr-str (k waiting))))
                     (when-not (contains? places at)
                       [(str where "waiting at unknown location " (pr-str at))])))
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

(defn new-item
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

(defn- settle-waiting
  "The world with the :waiting marker dropped from the item `id`, and from
  everything inside it, that no longer shows as waiting where it's been
  moved (see `waiting?`): taken or given to someone other than the keeper,
  or put down or in something away from the shop."
  [world id]
  (reduce (fn [world it]
            (cond-> world
              (and (:waiting it) (not (waiting? world it))) (update-in [:items (:id it)] dissoc :waiting)))
          world
          (cons (item world id) (within world id))))

(defmethod w/apply-event :item/taken [world {:keys [actor data]}]
  (-> world
      (update-in [:items (:item data)] #(-> % (dissoc :place :in) (assoc :holder actor)))
      (settle-waiting (:item data))
      (touch (:item data))))

(defmethod w/apply-event :item/put-down [world {:keys [data]}]
  (-> world
      (update-in [:items (:item data)] #(-> % (dissoc :holder :in) (assoc :place (:place data))))
      (settle-waiting (:item data))
      (touch (:item data))))

(defmethod w/apply-event :item/put-in [world {:keys [data]}]
  (-> world
      (update-in [:items (:item data)] #(-> % (dissoc :place :holder) (assoc :in (:into data))))
      (settle-waiting (:item data))
      (touch (:item data))))

(defmethod w/apply-event :item/given [world {:keys [data]}]
  (-> world
      (assoc-in [:items (:item data) :holder] (:to data))
      (settle-waiting (:item data))
      (touch (:item data))))

(doseq [t [:item/taken :item/put-down :item/put-in :item/given :item/found :item/not-found]]
  (defmethod w/perceived-by t [_] :sight))

(defmethod w/done-to :item/given [{:keys [data]}] [(:to data)])
(defmethod w/done-to :item/taken [{:keys [data]}] (some-> (:from-person data) vector))

(defmethod w/apply-event :item/work-begun [world {:keys [data]}]
  (assoc-in world [:item-jobs (:id data)] data))

(defmethod w/apply-event :item/work-extended [world {:keys [data]}]
  (update-in world [:item-jobs (:job data)] merge (select-keys data [:items :doing])))

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

(defn remove-items
  "The world without the items `ids`, each recorded under :items-gone with
  `fate` (\"sent up by the shop's courier\"), so older lines still read its
  name. For reducers: the items must have nothing in them."
  [world ids fate]
  (remove-things world (for [id ids] {:item id :name (:name (item world id)) :fate fate}) []))

(defn- set-aspect
  "The thing at `path` with aspect `k` set to `to`, or removed if `to` is
  nil; a thing with no aspects left has no :aspects."
  [world path k to]
  (update-in world path (fn [thing]
                          (let [aspects (if to (assoc (:aspects thing) k to) (dissoc (:aspects thing) k))]
                            (if (seq aspects) (assoc thing :aspects aspects) (dissoc thing :aspects))))))

(defn- changed
  "Apply one change of an aspect: of an item (which is then touched), of a
  place or of a person."
  [world {:keys [item place person aspect to]}]
  (cond
    item (touch (set-aspect world [:items item] aspect to) item)
    person (set-aspect world [:agents person] aspect to)
    :else (set-aspect world [:space :locations place] aspect to)))

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

(defmethod w/perceived-by :item/work-done [_] :sight)
(defmethod w/done-to :item/work-done [{:keys [data]}] (:on data))

(defmethod w/apply-event :item/work-done [world {:keys [data]}]
  (as-> world world
    (reduce touch world (keep :id (in-reach world (job world (:job data)))))
    (reduce changed world (:changes data))
    (remove-things world (:used-up data) (:absorbed data))
    (reduce (fn [world it] (touch (assoc-in world [:items (:id it)] it) (:id it))) world (:made data))
    (update world :item-jobs dissoc (:job data))))

(defmethod w/apply-event :item/work-ended [world {:keys [data]}]
  (update world :item-jobs dissoc (:job data)))

(defn- moved
  "Move one thing as an act's `moved` says: into someone's hands (:holder)
  or down at a place (:place)."
  [world {:keys [item holder place]}]
  (-> world
      (update-in [:items item] #(if holder
                                  (-> % (dissoc :place :in) (assoc :holder holder))
                                  (-> % (dissoc :holder :in) (assoc :place place))))
      (settle-waiting item)
      (touch item)))

;; An immediate act on people (manor.mechanics.items.actions `act-on`): its
;; event is built in manor.mechanics.items.work, its statuses follow as their
;; own events (manor.mechanics.physical)
(defmethod w/apply-event :person/acted-on [world {:keys [data]}]
  (as-> world world
    (reduce touch world (filter #(item world %) (:with data)))
    (reduce changed world (:changes data))
    (reduce moved world (:moved data))))

(defmethod w/perceived-by :person/acted-on [_] :sight)
(defmethod w/done-to :person/acted-on [{:keys [data]}] (:on data))

;; The steward's and finding's events (manor.mechanics.items.steward, .find)
(defmethod w/apply-event :items/steward-ran [world {:keys [data]}]
  (assoc world :items-steward-day (:day data)))

(defmethod w/apply-event :items/stewarded [world {:keys [data]}]
  (remove-things (reduce changed world (:changes data)) (:used-up data) (:absorbed data)))

(defmethod w/apply-event :item/found [world {:keys [data]}]
  (touch (assoc-in world [:items (get-in data [:item :id])] (:item data)) (get-in data [:item :id])))

(defmethod w/apply-event :item/not-found [world {:keys [data]}]
  (if-let [path (:path data)]
    (cond-> (assoc-in world (conj path :aspects) (:aspects data))
      (= :items (first path)) (touch (second path)))
    world))

;; ---------------------------------------------------------------------------
;; Narration: names are looked up when the line is written.

(defn lower-first [s] (str (str/lower-case (subs s 0 1)) (subs s 1)))

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
  (let [{box :from-item person :from-person} data]
    (cond
      (and box person) (str (n/who names actor) " takes " (the names (:item data)) " from " (the names box)
                            " " (n/who names person) " holds.")
      person (str (n/who names actor) " takes " (the names (:item data)) " from " (n/who names person) ".")
      box (str (n/who names actor) " takes " (the names (:item data)) " from " (the names box) ".")
      :else (str (n/who names actor) " picks up " (the names (:item data)) "."))))

(defmethod n/describe :item/put-in [{:keys [actor data]} names]
  (str (n/who names actor) " puts " (the names (:item data)) " in " (the names (:into data)) "."))

(defmethod n/describe :item/put-down [{:keys [actor data]} names]
  (str (n/who names actor) " puts down " (the names (:item data)) "."))

(defmethod n/describe :item/given [{:keys [actor data]} names]
  (str (n/who names actor) " gives " (the names (:item data)) " to " (n/who names (:to data)) "."))

(defn work-opener
  "\"Hale finishes dusting the china cabinet.\", or \"leaves off\" when they stopped early."
  [worker-name doing stopped-early?]
  (str worker-name (if stopped-early? " leaves off " " finishes ") doing "."))

(defmethod n/describe :item/work-begun [{:keys [actor data]} names]
  (str (n/who names actor) " starts " (:doing data) " (" (:id data)
       (when-let [on (seq (concat (:items data) (:people data)))] (str ", on " (str/join ", " on)))
       ", until " (n/at names (:due data)) ")."))

(defmethod n/describe :item/work-extended [{:keys [actor data]} names]
  (str (n/who names actor) " adds to " (:doing data) " (" (:job data) ")."))

(defn- aspect-name [k] (str/replace (name k) "-" " "))

(defn- change-text
  "The new value (\"swept\"), or \"no longer lacking\" for an aspect removed:
  values are whole phrases, so the key isn't repeated. A person's removed
  aspect reads by its old value: \"no longer spattered with grease\"."
  [{:keys [person aspect from to]}]
  (or to (str "no longer " (if (and person from) from (aspect-name aspect)))))

(defn- changes-text
  "\"Drawing room: swept; Piano: dusted; Ada: hair dressed high\": the
  changes, thing by thing, in the order they come."
  [names changes]
  (let [thing (fn [{:keys [item place person]}] (cond item [:item item] person person :else [:loc place]))
        thing-name (fn [k] (if (vector? k) (get names k (second k)) (n/who names k)))
        by-thing (group-by thing changes)]
    (str/join "; " (for [k (distinct (map thing changes))]
                     (str (thing-name k) ": " (str/join ", " (map change-text (by-thing k))))))))

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

(defn- moved-text
  "\"the jewel case to Will\", \"the poker put down\"."
  [names {:keys [item holder]}]
  (str (the names item) (if holder (str " to " (n/who names holder)) " put down")))

(defmethod n/describe :person/acted-on [{:keys [actor data]} names]
  (let [{:keys [on doing summary changes moved]} data]
    (str (n/who names actor) " acts on " (str/join ", " (map #(n/who names %) on)) ": " doing "."
         (when summary (str " " summary))
         (when (seq changes) (str " (" (changes-text names changes) ")."))
         (when (seq moved) (str " Moved: " (str/join "; " (map #(moved-text names %) moved)) ".")))))

(defmethod n/describe :item/work-ended [{:keys [actor data]} names]
  (if (= :retired (:reason data))
    (str (n/who names actor) "'s work (" (:doing data) ", " (:job data) ") is dropped.")
    (str (n/who names actor) " leaves off " (:doing data) " before getting anywhere.")))

(defmethod n/describe :item/judge-failed [{:keys [data]} _]
  (str "The judge's answer for " (:job data) " was not used: " (:reason data)))

;; The steward's and finding's events
(defmethod n/describe :items/steward-ran [{:keys [data]} _]
  (str "The nightly steward ran for " (:day data) "."))

(defmethod n/describe :items/stewarded [{:keys [data]} names]
  (str "Steward at " (n/place names (:place data)) ": " (:summary data)
       (when (seq (:changes data)) (str " (" (changes-text names (:changes data)) ")."))))

(defmethod n/describe :items/steward-failed [{:keys [data]} _]
  (str "The steward's answer at " (:place data) " was not used: " (:reason data)))

(defmethod n/describe :item/found [{:keys [actor data]} names]
  (str (n/who names actor) " looks for " (:what data) " and finds it"
       (when (:from data) (str " in " (the names (:from data)))) "."))

(defmethod n/describe :item/not-found [{:keys [actor data]} names]
  (str (n/who names actor) " looks for " (:what data) ": there's none"
       (if (:from data) (str " in " (the names (:from data))) " here") "."))
