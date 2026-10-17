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
                              ;; :in \"sideboard\"      ;   or :in (the id of a container item)
                              :owner \"household\"      ; optional: an agent id or any label; not enforced
                              :fixed? true            ; optional: furniture, can't be taken
                              :consumable? true       ; optional: food, fuel, soap: work may use it up
                              :container :open}}      ; optional: holds things; :open shows them to
                                                      ;   the room, :closed only to `open`
    :items-gone {\"beef-stock\" \"Beef stock\"}       ; id -> name of every item used up

  A carried item goes wherever its holder goes, and things in a container go
  wherever it goes, with no events. The world has :items once the first item
  is added, so worlds without items are unchanged. Used-up items keep their
  names under :items-gone, so older lines still read, and their ids are never
  given out again.

  Work on items (`work-on`, worlds with a clock only) is a job:
    :item-jobs {\"job-41\" {:id \"job-41\" :actor \"hale\" :items [\"china-cabinet\"]
                           :doing \"dusting the china cabinet\" :place \"drawing-room\"
                           :started 12 :due 15}}
  The worker is occupied until :due, with the job id in their :busy :job.
  Nothing changes until the work is over: at the start of the round it
  ends, the engine asks a judge (a narrow model call, never seen by the
  characters) what the time worked did to the items still in reach, checks
  the answer and records it as :item/work-done: new conditions, consumable
  items used up, and at most two things made from them.

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
(def max-condition 80)
(def max-name 60)
(def max-summary 300)
(def max-work-items 6)
(def max-work-hours 4)
(def max-made 2)
(def default-work-hours 0.5)

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
   [:in {:optional true} :string]
   [:owner {:optional true} :string]
   [:fixed? {:optional true} :boolean]
   [:consumable? {:optional true} :boolean]
   [:container {:optional true} [:enum :open :closed]]])

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
        (concat (:items-gone world) (map (juxt :id :name) (all world)))))

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
  (assoc-in world [:items (get-in data [:item :id])] (:item data)))

(defmethod w/apply-event :item/taken [world {:keys [actor data]}]
  (update-in world [:items (:item data)] #(-> % (dissoc :place :in) (assoc :holder actor))))

(defmethod w/apply-event :item/put-down [world {:keys [data]}]
  (update-in world [:items (:item data)] #(-> % (dissoc :holder :in) (assoc :place (:place data)))))

(defmethod w/apply-event :item/put-in [world {:keys [data]}]
  (update-in world [:items (:item data)] #(-> % (dissoc :place :holder) (assoc :in (:into data)))))

(defmethod w/apply-event :item/given [world {:keys [data]}]
  (assoc-in world [:items (:item data) :holder] (:to data)))

(defmethod w/apply-event :item/work-begun [world {:keys [data]}]
  (assoc-in world [:item-jobs (:id data)] data))

(defn- used-up [world {id :item item-name :name}]
  (-> world (update :items dissoc id) (assoc-in [:items-gone id] item-name)))

(defmethod w/apply-event :item/work-done [world {:keys [data]}]
  (as-> world world
    (reduce (fn [world {:keys [item to]}] (assoc-in world [:items item :condition] to)) world (:changes data))
    (reduce used-up world (:used-up data))
    (reduce (fn [world it] (assoc-in world [:items (:id it)] it)) world (:made data))
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
  (str (n/who names actor) " starts " (:doing data) " (" (:id data) ", on " (str/join ", " (:items data))
       ", until " (n/at names (:due data)) ")."))

(defmethod n/describe :item/work-done [{:keys [actor data]} names]
  (let [{:keys [doing stopped-early? summary changes used-up made]} data
        opener (work-opener (n/who names actor) doing stopped-early?)]
    (str opener
         (when (and summary (not= summary opener)) (str " " summary))
         (when (seq changes)
           (str " (" (str/join "; " (for [{:keys [item to]} changes] (str (get names [:item item] item) ": " to)))
                ")."))
         (when (seq used-up)
           (str " Used up: " (str/join "; " (map :name used-up)) "."))
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
;; Actions, for every role but the GM: all free but `work-on`. `look` and
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
  (cond-> (select-keys it [:id :name :desc :condition])
    (:owner it) (assoc :owner (owner-name world (:owner it)))
    (:fixed? it) (assoc :fixed? true)))

(defn- as-seen
  "`it` as `shape` shows it, and if it's a container, what can be seen of
  its inside: an open one's :contents, each shown the same way, or :closed."
  [world shape it]
  (case (:container it)
    :open (assoc (shape it) :contents (mapv #(as-seen world shape %) (contents world (:id it))))
    :closed (assoc (shape it) :closed true)
    (shape it)))

(defn opened
  "What `open` shows of a container: its name and condition, and its contents
  in detail, inner containers as the room would see them."
  [world box]
  {:name (:name box)
   :condition (:condition box)
   :contents (mapv #(as-seen world (partial look-item world) %) (contents world (:id box)))})

(defn- look-person [world id]
  (let [a (w/agent world id)
        carried (by-name (held-by world id))]
    (cond-> {:name (:name a)}
      (:busy a) (assoc :doing (get-in a [:busy :activity]))
      (seq carried) (assoc :carrying (mapv #(select-keys % [:id :name :desc :condition]) carried)))))

(defn look
  "What `actor` sees on looking around: the place (with its :detail, if it
  has one), the items lying here in detail (with what's in open containers),
  and the other people here with what they carry. Empty parts are left out."
  [world actor]
  (let [here (space/location-of world actor)
        {loc-name :name :keys [detail]} (space/location world here)
        lying (by-name (items-at world here))
        people (sort (disj (space/present world here) actor))]
    (cond-> {:place (cond-> {:name loc-name} detail (assoc :detail detail))}
      (seq lying) (assoc :items (mapv #(as-seen world (partial look-item world) %) lying))
      (seq people) (assoc :people (mapv #(look-person world %) people)))))

(defn- work-reason
  "Why `actor` can't work on `id`, or nil: it must be an item lying here (in
  a container or not) or carried by them, or the place they're in."
  [world actor id]
  (let [it (item world id)
        holder (:holder it)
        here (space/location-of world actor)]
    (cond
      (= actor holder) nil
      (and holder (space/together? world actor holder)) (str (:name (w/agent world holder)) " is holding the " (:name it))
      (and it (reachable? world actor it)) nil
      (= id here) nil
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

(defn actions []
  [{:name :look
    :kind :free
    :doc (str "Examine this place and its things closely: the room's detail, and the descriptions, conditions and owners of "
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

   {:name :work-on
    :kind :final
    :doc (str "Spend time working on things here or that you carry (item ids, at most " max-work-items "; "
              "working on a container includes what's in it). "
              "To work on the room itself (sweeping, airing, laying the fire), include this place's id. "
              "doing is a short phrase that reads after \"is\", e.g. \"dusting the shelves\". "
              "hours is how long you'll spend (half an hour to " max-work-hours ", default half an hour); "
              "if the job needs longer, only part of it gets done. "
              "You get no turns until it's over, and the results show when you finish.")
    :args [:map
           [:items [:vector {:min 1 :max max-work-items} :string]]
           [:doing [:string {:min 3 :max 120}]]
           [:hours {:optional true} [:double {:min 0.5 :max max-work-hours}]]]
    :available? (fn [world _] (some? (:clock world)))
    :valid? work-on-reason
    :effect work-on-events}])

;; ---------------------------------------------------------------------------
;; Views

(defn- carrying-names [world id] (mapv :name (by-name (held-by world id))))

(defn add-to-view
  "The item parts of an agent's view: what they carry (with its condition),
  the items in their place (names only, with what's in open containers and
  closed ones marked), and what the people here carry. Each is left out when
  empty."
  [view world agent-id]
  (let [carried (by-name (held-by world agent-id))
        here (when (get-in view [:location :here]) (space/location-of world agent-id))
        lying (by-name (items-at world here))]
    (cond-> view
      (seq carried) (assoc-in [:you :carrying] (mapv #(select-keys % [:id :name :condition]) carried))
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
;; one after another: for each it asks the judge (the request is built here)
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
  not, or carried by the worker), each followed by everything inside it."
  [world {:keys [items place actor]}]
  (->> items
       (keep #(let [it (item world %)
                    o (some->> it (outermost world))]
                (when (and it (or (= actor (:holder o)) (and place (= place (:place o))))) it)))
       (mapcat #(cons % (within world (:id %))))
       distinct))

(defn- audience
  "The people at the job's place, and the worker wherever they are."
  [world {:keys [actor place]}]
  (conj (space/present world place) actor))

(defn work-ended
  "Event: `job` ends with nothing done. `reason` is :retired (the GM sees
  it), :nothing-in-reach or :no-time (the worker and the people at the
  place see it)."
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
  "What the judge made (`made`), as new items with fresh ids: consumable,
  owned like the first thing used up, and in the container the judge named,
  else carried by the worker if they carried that first thing, else lying at
  the job's place."
  [world {:keys [actor place]} [first-used] made]
  (let [carried? (= actor (:holder (outermost world first-used)))]
    (second (reduce (fn [[world its] {:keys [in] :as m}]
                      (let [it (new-item world (cond-> (assoc (select-keys m [:name :desc :condition]) :consumable? true)
                                                 (:owner first-used) (assoc :owner (:owner first-used))
                                                 in (assoc :in in)
                                                 (and (not in) carried?) (assoc :holder actor)
                                                 (not (or in carried?)) (assoc :place place)))]
                        [(assoc-in world [:items (:id it)] it) (conj its it)]))
                    [world []]
                    made))))

(defn work-done
  "Event: the result of `job`, seen by the worker and the people at the
  place. `verdict` is the judge's checked answer, {:summary :changes
  :used-up [ids] :made [..]}, or nil for no changes and a neutral summary.
  The data has :used-up [{:item :name}] and :made (the new items) only when
  there are some."
  [world job {:keys [minutes stopped-early? luck]} verdict]
  (let [gone (keep #(item world %) (:used-up verdict))]
    {:type :item/work-done :actor (:actor job) :visible-to (audience world job)
     :data (cond-> {:job (:id job) :actor (:actor job) :place (:place job) :doing (:doing job)
                    :minutes minutes :stopped-early? stopped-early? :luck luck
                    :summary (or (:summary verdict) (neutral-summary world job stopped-early?))
                    :changes (vec (:changes verdict))}
             (seq gone) (assoc :used-up (mapv #(hash-map :item (:id %) :name (:name %)) gone))
             (and (seq gone) (seq (:made verdict))) (assoc :made (made-items world job gone (:made verdict))))}))

(defn judge-failed
  "Event, for the operator only: why the judge's answer about `job` wasn't used."
  [job reason]
  {:type :item/judge-failed :visible-to :system :data {:job (:id job) :reason reason}})

;; ---------------------------------------------------------------------------
;; The judge: what the prompt holds, its one tool, and the checks on its answer

(def ^:private judge-schema
  [:map
   [:changes {:optional true} [:vector [:map [:item :string] [:condition [:string {:min 1 :max max-condition}]]]]]
   [:used-up {:optional true} [:vector :string]]
   [:made {:optional true} [:vector {:max max-made}
                            [:map
                             [:name [:string {:min 1 :max max-name}]]
                             [:desc [:string {:min 1 :max max-desc}]]
                             [:condition [:string {:min 1 :max max-condition}]]
                             [:in {:optional true} :string]]]]
   [:summary [:string {:min 1 :max max-summary}]]])

(def ^:private judge-tool
  {:type "function"
   :function {:name "judge"
              :description (str "Give your judgement: the new condition of each item the work changed "
                                "(its id, and a phrase of at most " max-condition " characters), the ids of consumable "
                                "items it used up, at most " max-made " things it made from them, and a summary "
                                "of at most " max-summary " characters.")
              :parameters (mjs/transform judge-schema)}})

(def judge-instructions
  (str "You judge the results of work in a simulation. Someone has spent time working on some things. "
       "Decide what that much work, by this person, realistically did to these things, and answer by calling the `judge` tool. "
       "The time may be too short for the whole job: then only part of it is done.

- Change conditions, each a short phrase of at most " max-condition " characters saying what state the thing is in now.
- Leave out any item the work left unchanged.
- An item's nature can't change: work can't turn a vase into gold. If the task is impossible or absurd, change nothing and say so in the summary.
- Only things marked `consumable` can be used up, and only once they are gone: food eaten or cooked into something else, fuel burnt, an ingredient used up. List their ids in `used-up`. Something partly used is not used up: change its condition to say what's left (\"about 17 lb left\").
- `made` is for at most " max-made " things the work really produced from what it used up (a carved joint leaves \"Beef bone and trimmings\"; the bone simmered makes \"Beef stock\"), each with a name, a description and a condition. Give `in`, the id of a listed `container`, to put one there (the stock in the saucepan). Nothing is made without something used up.
- A thing listed with `in` is inside another of the things listed. The work may or may not have touched it: leave it out if it didn't.
- Luck is a roll from 1 to 100, and 50 is ordinary: low luck means mishaps (a slip, a breakage), high luck means it went unusually well.
- The summary is one or two sentences, past tense, third person, for the people present, at most " max-summary " characters."))

(def ^:private pretty (json/object-mapper {:pretty true}))

(defn- judged-items
  "The items in reach as the judge sees them, with :in only when the
  container is among them, and :consumable and :container when true."
  [reach]
  (let [ids (set (map :id reach))]
    (mapv #(cond-> (select-keys % [:id :name :desc :condition])
             (ids (:in %)) (assoc :in (:in %))
             (:consumable? %) (assoc :consumable true)
             (:container %) (assoc :container true))
          reach)))

(defn judge-request
  "The judge's request for `job`: {:messages :tools}. It holds only the
  scenario's :judge-brief (under :items), the time, the worker's name and
  persona, the place, what they were doing, the minutes worked and whether
  they stopped early, the luck roll, and each item in reach (`reach`) with
  its id, name, description and condition, the container it's in when
  that's in reach too, and whether it's consumable or a container."
  [scenario world job {:keys [minutes stopped-early? luck]} reach]
  (let [worker (w/agent world (:actor job))
        brief (when-let [f (get-in scenario [:items :judge-brief])] (f world))]
    {:tools [judge-tool]
     :messages
     [{:role "system"
       :content (str judge-instructions (when-not (str/blank? brief) (str "\n\n## The setting\n" brief)))}
      {:role "user"
       :content (str "# The work\n\n"
                     "- Time: " (clock/label (:clock world) (:round world)) "\n"
                     "- Worker: " (:name worker) (when-not (str/blank? (:persona worker)) (str ". " (:persona worker))) "\n"
                     "- Place: " (:name (space/location world (:place job)) (:place job)) "\n"
                     "- Doing: " (:doing job) "\n"
                     "- Time worked: " minutes " minutes" (if stopped-early? "; they stopped early" ", as planned") "\n"
                     "- Luck: " luck "\n\n"
                     "## The things worked on\n```json\n"
                     (json/write-value-as-string (judged-items reach) pretty)
                     "\n```\n\nCall `judge` now.")}]}))

(defn- ids-or-none [items] (if (seq items) (str/join ", " (map :id items)) "none"))

(defn- use-up-problem
  "Why the judge can't use up the ids `gone`, or nil: each must be a
  consumable item in `reach`, not fixed, and everything in it used up too."
  [reach gone]
  (let [usable (filter #(and (:consumable? %) (not (:fixed? %))) reach)
        bad (remove (set (map :id usable)) gone)
        gone? (set gone)
        [box left] (first (for [it reach
                                :when (gone? (:id it))
                                :let [left (remove #(gone? (:id %)) (filter #(= (:id it) (:in %)) reach))]
                                :when (seq left)]
                            [it left]))]
    (cond
      (seq bad) (str "you may use up only consumable items among these: " (ids-or-none usable)
                     "; not " (str/join ", " bad))
      box (str "the " (:name box) " (" (:id box) ") still holds " (ids-or-none left)
               ": use those up too, or don't use it up"))))

(defn- made-problem
  "Why the judge can't make `made`, or nil: only from something used up,
  and an :in must be a container in `reach` that isn't used up."
  [reach gone made]
  (let [boxes (filter #(and (:container %) (not ((set gone) (:id %)))) reach)
        bad-in (remove (set (map :id boxes)) (keep :in made))]
    (cond
      (and (seq made) (empty? gone))
      "things are made only from consumable items the work used up: list those in used-up, or make nothing"
      (seq bad-in) (str "in must be the id of a container among these: " (ids-or-none boxes)
                        "; not " (str/join ", " (distinct bad-in))))))

(defn- verdict
  "The judge's tool arguments, checked: {:summary :changes [{:item :from :to}]},
  with :used-up [ids] and :made [{:name :desc :condition :in}] when there are
  some, or {:error reason}. Only items in `reach` may change, and only their
  condition; only consumable ones may be used up (see `use-up-problem`), and
  things made need something used up (see `made-problem`). Other keys are
  dropped, and unchanged conditions, or changes to things used up, left out."
  [arguments reach]
  (let [{:keys [args error]} (a/decode-args {:args judge-schema} arguments)
        by-id (into {} (map (juxt :id identity)) reach)
        strays (remove by-id (map :item (:changes args)))
        gone (vec (distinct (:used-up args)))
        made (vec (:made args))]
    (if-let [problem (or error
                         (when (seq strays)
                           (str "you may change only these items: " (str/join ", " (keys by-id))
                                "; not " (str/join ", " (distinct strays))))
                         (use-up-problem reach gone)
                         (made-problem reach gone made))]
      {:error problem}
      (cond-> {:summary (:summary args)
               :changes (vec (for [{:keys [item condition]} (:changes args)
                                   :let [from (:condition (by-id item))]
                                   :when (not= from condition)
                                   :when (not (some #{item} gone))]
                               {:item item :from from :to condition}))}
        (seq gone) (assoc :used-up gone)
        (seq made) (assoc :made made)))))

(defn judgement
  "Read a judge response: {:verdict v :replies [...]} or {:error reason
  :replies [...]}, where :replies are the messages to send back (a reply to
  each tool call, or a reminder if it made none). Only the first `judge`
  call counts."
  [response reach]
  (adjudication/answer "judge" response #(verdict % reach)))

(defn mock-judge
  "The judge in mock runs, a mock policy for calls with role :judge: every
  item in reach is \"just seen to\", and the summary only says the work is over."
  [{:keys [world job items stopped-early?]}]
  {:tool :judge
   :args {:changes (mapv (fn [it] {:item (:id it) :condition "just seen to"}) items)
          :summary (neutral-summary world job stopped-early?)}})
