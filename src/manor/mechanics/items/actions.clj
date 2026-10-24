(ns manor.mechanics.items.actions
  "Item actions, for every role but the GM: all free but timed activities.
  `look` and `open` change nothing: they only reply."
  (:require [clojure.string :as str]
            [manor.clock :as clock]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.items :as items]
            [manor.mechanics.items.find :as item-find]
            [manor.mechanics.items.views :as item-views]
            [manor.mechanics.physical :as physical]
            [manor.mechanics.space :as space]
            [manor.world :as w]))

(defn- listing
  "\"china-cabinet (China cabinet), piano (Piano)\", or `none`."
  [items none]
  (if (seq items)
    (str/join ", " (map #(str (:id %) " (" (:name %) ")") (items/by-name items)))
    none))

(defn- here-list
  "The items `actor` can see here, for reasons: never what's in closed containers."
  [world actor]
  (str "items here: " (listing (items/visible-at world (space/location-of world actor)) "none")))

(defn- carried-list [world actor]
  (str "you carry: " (listing (items/held-by world actor) "nothing")))

(defn- held-by-helpless
  "The person here who holds `it`, or the thing it's in, and can't keep hold
  of it (dead, unconscious or unable to grasp, see manor.mechanics.physical),
  or nil."
  [world actor it]
  (let [holder (:holder (items/outermost world it))]
    (when (and holder (not= actor holder) (space/together? world actor holder) (physical/helpless? world holder))
      holder)))

(defn- grasp-reason
  "Why `actor` can't take hold of things, or nil."
  [world actor cant]
  (physical/disabled-reason world actor :grasp cant))

(defn- take-reason
  "Why `actor` can't take the item `id`, or nil: it must lie here, directly or
  in a container (at any depth, open or closed), and not be fixed, or be
  held (or be in something held) by someone here who can't keep hold of it.
  Something inside what anyone else keeps hold of is just \"not here\", so
  nothing hidden leaks. With their grasp disabled, they can't take anything."
  [world actor {id :item}]
  (let [it (items/item world id)
        holder (:holder it)]
    (cond
      (nil? (space/location-of world actor)) "you can't pick things up while travelling"
      (not (physical/can? world actor :grasp)) (grasp-reason world actor "pick things up")
      (= actor holder) (str "you are already carrying the " (:name it))
      (and it (held-by-helpless world actor it)) nil
      (and holder (space/together? world actor holder))
      (str (:name (w/agent world holder)) " is holding the " (:name it))
      (not (and it (items/reachable? world actor it))) (str id " is not here; " (here-list world actor))
      (:fixed? it) (str "the " (:name it) " is fixed in place"))))

(defn- container-reason
  "Why `actor` can't get at the container `id`, or nil."
  [world actor id verb]
  (let [box (items/item world id)]
    (cond
      (not (and box (items/reachable? world actor box))) (str id " is not here; " (here-list world actor))
      (not (:container box)) (str "the " (:name box) " is not something you can " verb))))

(defn- put-in-reason
  "Why `actor` can't put the item `id` in the container `to`, or nil: the
  item is carried or could be taken here, the container is here or carried,
  and the item isn't the container or around it."
  [world actor {id :item to :container}]
  (let [it (items/item world id)]
    (or (grasp-reason world actor "put things in anything")
        (when-not (and it (= actor (:holder it))) (take-reason world actor {:item id}))
        (container-reason world actor to "put things in")
        (cond
          (or (= id to) (some #(= to (:id %)) (items/within world id)))
          (str "you can't put the " (:name it) " inside itself")
          (= to (:in it)) (str "the " (:name it) " is already in the " (:name (items/item world to)))))))

(defn- open-reason [world actor {id :container}]
  (if (space/location-of world actor)
    (or (grasp-reason world actor "open things") (container-reason world actor id "open"))
    "you can't open things while travelling"))

(defn- carrying-reason [world actor id]
  (when-not (= actor (:holder (items/item world id)))
    (str "you are not carrying " id "; " (carried-list world actor))))

(defn- give-reason [world actor {id :item to :to}]
  (or (grasp-reason world actor "hand things over")
      (carrying-reason world actor id)
      (cond (= to actor) "you already have it; give it to someone else here"
            (not (some #(= to (:id %)) (w/active-agents world))) (str "no one has the id " to)
            (not (space/together? world actor to)) (str to " is not here"))))

(defn- person-id?
  "Does `id` in `on` name a person: not an item or the place, but someone
  taking part (never the GM)?"
  [world actor id]
  (and (nil? (items/item world id))
       (not= id (space/location-of world actor))
       (some #(= id (:id %)) (w/active-agents world))))

(defn- people-list
  "The people here, for reasons: \"people here: you (ada), Ben (ben)\"."
  [world actor]
  (str "people here: you (" actor ")"
       (str/join (for [id (sort (disj (space/present world (space/location-of world actor)) actor))]
                   (str ", " (:name (w/agent world id)) " (" id ")")))))

(defn- work-reason
  "Why `actor` can't name `id` as focus: items here or carried, the place
  they're in, a store serving it, or a person here (themselves included)
  are allowed."
  [world actor id]
  (let [it (items/item world id)
        holder (:holder it)
        here (space/location-of world actor)]
    (cond
      (= actor holder) nil
      (and holder (space/together? world actor holder)) (str (:name (w/agent world holder)) " is holding the " (:name it))
      (and it (items/reachable? world actor it)) nil
      (= id here) nil
      (some #(= id (:id %)) (items/stores-for world here)) nil
      (person-id? world actor id)
      (when-not (or (= id actor) (space/together? world actor id))
        (str (:name (w/agent world id)) " is not here; " (people-list world actor)))
      :else
      (str id " is neither here nor carried by you; this place: " here " (" (:name (space/location world here)) "); "
           (here-list world actor) "; " (carried-list world actor) "; " (people-list world actor)))))

(defn- work-on-reason [world actor {ids :items}]
  (cond (nil? (:clock world)) "there is no game time to work in"
        (nil? (space/location-of world actor)) "you can't work on things while travelling"
        :else (some #(work-reason world actor %) (distinct ids))))

(defn- work-on-events
  "The job (seen by the GM) and the occupation that carries its id (seen by
  everyone here). The people named go under :people, the things under
  :items. Job ids come from the world's event count, so they are
  deterministic."
  [world actor {ids :items :keys [doing hours]}]
  (let [id (str "job-" (inc (:seq world)))
        due (+ (:round world) (clock/rounds-for (:clock world) (* 60 (or hours items/default-work-hours))))
        {people true things false} (group-by #(boolean (person-id? world actor %)) (distinct ids))]
    [{:type :item/work-begun :visible-to :gm
      :data (cond-> {:id id :actor actor :items (vec things) :doing doing
                     :place (space/location-of world actor) :started (:round world) :due due}
              (seq people) (assoc :people (vec people)))}
     (assoc-in (b/occupy world actor doing due) [:data :job] id)]))

(defn- put-in-events
  "Move at once; a store also gets one unoccupied quick job per worker and
  round. Further put-ins extend its focus, rather than adding judge calls."
  [world actor {id :item to :container}]
  (let [store (items/item world to)
        pending (first (filter #(and (:quick? %) (= actor (:actor %))
                                     (= to (:store %)) (= (:round world) (:started %))) (items/jobs world)))
        focus (vec (distinct (concat (:items pending) [id to])))
        doing (str "putting " (str/join " and " (map #(str "the " (items/lower-first (:name (items/item world %)))) (remove #{to} focus)))
                   " away in the " (items/lower-first (:name store)))]
    (cond-> [{:type :item/put-in :data {:item id :into to} :visible-to (space/here world actor)}]
      (items/store? store)
      (conj (if pending
              {:type :item/work-extended :visible-to :gm
               :data {:job (:id pending) :items focus :doing doing}}
              {:type :item/work-begun :visible-to :gm
               :data {:id (str "job-" (+ 2 (:seq world))) :actor actor :items focus :doing doing
                      :place (space/location-of world actor) :started (:round world) :due (inc (:round world))
                      :quick? true :store to}})))))

(defn- timed-activity
  "One activity, with work-on's old argument spelling retained as an alias."
  [action-name]
  (let [alias? (= :work-on action-name)
        activity-args (fn [args] (if alias? args (assoc args :items (:on args))))]
    {:name action-name
     :kind :final
     :doc (str (when alias? "Alias for spend-time; items means on. ")
               "Spend time on an activity here, work or pastime. doing reads after \"is\", e.g. \"resting in the garden\". "
               "Keep doing to at most " items/advised-doing " characters. "
               "hours is half an hour to " items/max-work-hours ", default half an hour. "
               "Optionally name up to " items/max-work-items " things or people with " (if alias? "items" "on")
               ": items here or carried, this place's id, stores in scope, or the ids of people here, yourself included. "
               "Working on a container includes its contents. "
               "What you do to someone takes effect only if they're still with you when you finish: they may walk away, "
               "or rouse you to stop you early, and then only the time you worked counts. "
               "You get no turns until it ends; results show when you finish.")
     :args [:map
            [(if alias? :items :on) (if alias? {} {:optional true})
             [:vector {:min (if alias? 1 0) :max items/max-work-items} :string]]
            [:doing [:string {:min 3 :max items/max-doing :json-schema/maxLength items/advised-doing}]]
            [:hours {:optional true} [:double {:min 0.5 :max items/max-work-hours}]]]
     :available? (fn [world _] (some? (:clock world)))
     :valid? (fn [world actor args] (work-on-reason world actor (activity-args args)))
     :effect (fn [world actor args] (work-on-events world actor (activity-args args)))}))

(declare find-events find-reply)

(defn actions
  ([] (actions {}))
  ([{:keys [find-allowed?]}]
   [{:name :find
     :kind :query :per-turn 1
     :doc (str "Look for a particular thing you need to pick up or hand over, or whose presence is in doubt. "
               "Everyday things you only use in your work need no finding: just name them in what you're doing.")
     :args [:map [:what [:string {:min 1 :max 60}]]]
     :available? (fn [world actor] (some? (space/location-of world actor)))
     :valid? (fn [world actor _]
               (if-let [place (space/location-of world actor)]
                 (when find-allowed? (find-allowed? world actor place))
                 "you can't find things while travelling"))
     :adjudicate :items/find :on-result item-find/find-events :reply item-find/find-reply}

    {:name :look
     :kind :free
     :doc (str "Examine this place and its things closely: the room's detail, and the descriptions, aspects and owners of "
               "the items here and what the people here carry. "
               "Use it when you need to inspect the state of something or see it in detail; "
               "your view already lists who is here and what items are here, so don't look routinely on arrival.")
     :valid? (fn [world actor _]
               (when-not (space/location-of world actor) "you can't look around while travelling"))
     :reply (fn [world actor _] (item-views/look world actor))}

    {:name :take
     :kind :free
     :doc "Pick up an item in your place and carry it (use its id from `items` in your view)."
     :args [:map [:item :string]]
     :valid? take-reason
     :effect (fn [world actor {id :item}]
               (let [it (items/item world id)
                     from (held-by-helpless world actor it)]
                 [{:type :item/taken :data (cond-> {:item id :from-place (items/place-of world it)}
                                             (:in it) (assoc :from-item (:in it))
                                             from (assoc :from-person from))
                   :visible-to (space/here world actor)}]))}

    {:name :open
     :kind :free
     :doc (str "Open a container here (use its id) to see what's in it, in detail. "
               "Containers marked `closed` in your view show their contents only this way.")
     :args [:map [:container :string]]
     :valid? open-reason
     :reply (fn [world _ {id :container}] (item-views/opened world (items/item world id)))}

    {:name :put-in
     :kind :free
     :doc "Put an item (use its id) that you carry, or could pick up here, into a container here (its id)."
     :args [:map [:item :string] [:container :string]]
     :valid? put-in-reason
     :effect put-in-events}

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
     :doc (str "Give an item you carry (use its id) to someone in the same place (use their id). "
               "Saying you hand something over doesn't: only this does, and what you're given appears under `carrying`.")
     :args [:map [:item :string] [:to :string]]
     :valid? give-reason
     :effect (fn [world actor {id :item to :to}]
               [{:type :item/given :data {:item id :to to} :visible-to (space/here world actor)}])}

    (timed-activity :spend-time)
    (timed-activity :work-on)]))
