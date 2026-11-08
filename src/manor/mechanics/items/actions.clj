(ns manor.mechanics.items.actions
  "Item actions, for every role but the GM: all free but timed activities
  and `act-on`, an immediate act on other people that the work judge
  decides within the turn. `look` and `open` change nothing: they only
  reply."
  (:require [clojure.string :as str]
            [manor.clock :as clock]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.items :as items]
            [manor.mechanics.items.find :as item-find]
            [manor.mechanics.items.views :as item-views]
            [manor.mechanics.items.work :as item-work]
            [manor.mechanics.physical :as physical]
            [manor.mechanics.physical.changes :as physical-changes]
            [manor.mechanics.space :as space]
            [manor.narrate :as n]
            [manor.prompts :as prompts]
            [manor.world :as w]))

(defn- listing
  "\"china-cabinet (China cabinet), piano (Piano)\", or `none`."
  [items none]
  (if (seq items)
    (str/join ", " (map #(str (:id %) " (" (:name %) ")") (items/by-name items)))
    none))

(defn- here-list
  "The items `actor` can see here, for reasons: never what's in closed
  containers, and nothing if they can't see."
  [world actor]
  (if (physical/can? world actor :sight)
    (str "items here: " (listing (items/visible-at world (space/location-of world actor)) "none"))
    "you can't see what's here"))

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
  nothing hidden leaks. With their grasp disabled, they can't take anything.
  `fixed-hint` (the scenario's :items :fixed-hint), if given, ends the
  refusal of a fixed thing, saying how it could be moved."
  ([world actor args] (take-reason nil world actor args))
  ([fixed-hint world actor {id :item}]
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
      (:fixed? it) (str "the " (:name it) " is fixed in place" (when fixed-hint (str "; " fixed-hint)))))))

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
  [fixed-hint world actor {id :item to :container}]
  (let [it (items/item world id)]
    (or (grasp-reason world actor "put things in anything")
        (when-not (and it (= actor (:holder it))) (take-reason fixed-hint world actor {:item id}))
        (container-reason world actor to "put things in")
        (cond
          (or (= id to) (some #(= to (:id %)) (items/within world id)))
          (str "you can't put the " (:name it) " inside itself")
          (= to (:in it)) (str "the " (:name it) " is already in the " (:name (items/item world to)))))))

(defn- open-reason [world actor {id :container}]
  (if (space/location-of world actor)
    (or (grasp-reason world actor "open things")
        (physical/sight-reason world actor "see inside things")
        (container-reason world actor id "open"))
    "you can't open things while travelling"))

(defn- carrying-reason [world actor id]
  (when-not (= actor (:holder (items/item world id)))
    (str "you are not carrying " id "; " (carried-list world actor))))

(defn- carried-or-packed?
  "Does `actor` carry the item `id`, or something it's inside?"
  [world actor id]
  (some->> (items/item world id) (items/outermost world) :holder (= actor)))

(defn- give-reason
  "Why `actor` can't give the item `id` to `to`, or nil: they carry it, or
  something it's in, and `to` is someone else here."
  [world actor {id :item to :to}]
  (or (grasp-reason world actor "hand things over")
      (when-not (carried-or-packed? world actor id) (carrying-reason world actor id))
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
  "The people here, for reasons: \"people here: you (ada), Ben (ben)\";
  only yourself if you can't see."
  [world actor]
  (str "people here: you (" actor ")"
       (if (physical/can? world actor :sight)
         (str/join (for [id (sort (disj (space/present world (space/location-of world actor)) actor))]
                     (str ", " (:name (w/agent world id)) " (" id ")")))
         "; you can't see who else is here")))

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
  "One activity, with work-on's old argument spelling retained as an alias.
  With `act-on?`, its doc points to `act-on` for what's done at once."
  [action-name act-on?]
  (let [alias? (= :work-on action-name)
        activity-args (fn [args] (if alias? args (assoc args :items (:on args))))]
    {:name action-name
     :kind :final
     :doc (fn [world]
            (prompts/render world "docs/items/spend-time.md"
                            {:alias (if alias? (str (prompts/render world "docs/items/work-on-alias.md" {}) " ") "")
                             :advised-doing items/advised-doing :max-work-hours items/max-work-hours
                             :max-work-items items/max-work-items :on (if alias? "items" "on")
                             :act-on (if act-on? (str (prompts/render world "docs/items/spend-time-act-on.md" {}) " ") "")}))
     :args [:map
            [(if alias? :items :on) (if alias? {} {:optional true})
             [:vector {:min (if alias? 1 0) :max items/max-work-items} :string]]
            [:doing [:string {:min 3 :max items/max-doing :json-schema/maxLength items/advised-doing}]]
            [:hours {:optional true} [:double {:min 0.5 :max items/max-work-hours}]]]
     :available? (fn [world _] (some? (:clock world)))
     :admission :items/activity-check
     :valid? (fn [world actor args] (work-on-reason world actor (activity-args args)))
     :effect (fn [world actor args] (work-on-events world actor (activity-args args)))}))

(def max-act-on
  "How many people an act may be done to."
  3)

(def max-act-with
  "How many things an act may use."
  2)

(defn- on-reason
  "Why `actor` can't act on `id`, or nil: someone else here, alive or dead."
  [world actor id]
  (cond (= id actor) "name only other people with on, not yourself"
        (not (or (w/active? world id) (w/dead? world id))) (str "no one has the id " id "; " (people-list world actor))
        (not (space/together? world actor id)) (str (:name (w/agent world id)) " is not here; " (people-list world actor))))

(defn- with-reason
  "Why `actor` can't use the item `id` in an act, or nil: it must be here or carried."
  [world actor id]
  (let [it (items/item world id)]
    (when-not (and it (items/reachable? world actor it))
      (str id " is neither here nor carried by you; " (here-list world actor) "; " (carried-list world actor)))))

(defn- act-on-reason [world actor {:keys [on with]}]
  (or (when-not (w/active? world actor) "only living participants can act on people")
      (physical/disabled-reason world actor :consciousness "act")
      (when (nil? (space/location-of world actor)) "you can't act on people while travelling")
      (some #(on-reason world actor %) (distinct on))
      (some #(with-reason world actor %) (distinct with))))

(defn- act-on-events
  "The act's events, from its judgement (see manor.engine.resolve): what
  item-work/result-events gives, then the interruption of anyone acted on
  who is still busy (asleep, working), as with a change to their body."
  [world actor _ {:keys [job work verdict] :as result}]
  (let [evs (item-work/result-events world job (item-work/acted-on world job work verdict) result)]
    (into evs (physical-changes/interrupts (reduce w/apply-event world evs) (:people job) actor))))

(defn- act-on-reply
  "The act's summary, or without one (a failed judgement) its narration."
  [world _ _ {:keys [events]}]
  (let [acted (first (filter #(= :person/acted-on (:type %)) events))]
    (or (get-in acted [:data :summary]) (n/describe acted (n/names world)))))

(def act-on
  "Doing something to other people here at once: judged by the work judge
  within the turn (manor.engine.resolve, :judge-job), it ends the turn with
  no occupation. A scenario offers it with its :items config's :act-on?."
  {:name :act-on
   :kind :final
   :doc (prompts/doc "docs/items/act-on.md" {:max-act-on max-act-on :max-act-with max-act-with :advised-doing items/advised-doing})
   :args [:map
          [:on [:vector {:min 1 :max max-act-on} :string]]
          [:with {:optional true} [:vector {:max max-act-with} :string]]
          [:doing [:string {:min 3 :max items/max-doing :json-schema/maxLength items/advised-doing}]]]
   :valid? act-on-reason
   :judge-job (fn [world actor args]
                (let [job (item-work/act-job world actor args)]
                  {:job job :work (item-work/act-work world job)}))
   :on-result act-on-events
   :reply act-on-reply})

(declare find-events find-reply)

(defn actions
  "The item actions, configured by a scenario's :items: `find-allowed?`
  (fn [world actor place]) => nil or why not, and `act-on?` to offer
  `act-on`, and `fixed-hint`, the end of take's refusal of a fixed thing."
  ([] (actions {}))
  ([{:keys [find-allowed? act-on? fixed-hint]}]
   (cond->
    [{:name :find
      :kind :query :per-turn 1
      :doc (prompts/doc "docs/items/find.md")
      :args [:map [:what [:string {:min 1 :max 60}]]]
      :available? (fn [world actor] (some? (space/location-of world actor)))
      :valid? (fn [world actor _]
                (if-let [place (space/location-of world actor)]
                  (when find-allowed? (find-allowed? world actor place))
                  "you can't find things while travelling"))
      :adjudicate :items/find :on-result item-find/find-events :reply item-find/find-reply}

     {:name :look
      :kind :free
      :doc (prompts/doc "docs/items/look.md")
      :valid? (fn [world actor _]
                (if (space/location-of world actor)
                  (physical/sight-reason world actor "look around")
                  "you can't look around while travelling"))
      :reply (fn [world actor _] (item-views/look world actor))}

     {:name :take
      :kind :free
      :doc (prompts/doc "docs/items/take.md")
      :args [:map [:item :string]]
      :valid? (partial take-reason fixed-hint)
      :effect (fn [world actor {id :item}]
                (let [it (items/item world id)
                      from (held-by-helpless world actor it)]
                  [{:type :item/taken :data (cond-> {:item id :from-place (items/place-of world it)}
                                              (:in it) (assoc :from-item (:in it))
                                              from (assoc :from-person from))
                    :visible-to (space/here world actor)}]))}

     {:name :open
      :kind :free
      :doc (prompts/doc "docs/items/open.md")
      :args [:map [:container :string]]
      :valid? open-reason
      :reply (fn [world _ {id :container}] (item-views/opened world (items/item world id)))}

     {:name :put-in
      :kind :free
      :doc (prompts/doc "docs/items/put-in.md")
      :args [:map [:item :string] [:container :string]]
      :valid? (partial put-in-reason fixed-hint)
      :effect put-in-events}

     {:name :put-down
      :kind :free
      :doc (prompts/doc "docs/items/put-down.md")
      :args [:map [:item :string]]
      :valid? (fn [world actor {id :item}]
                (or (carrying-reason world actor id)
                    (when-not (space/location-of world actor) "you can't put things down while travelling")))
      :effect (fn [world actor {id :item}]
                [{:type :item/put-down :data {:item id :place (space/location-of world actor)}
                  :visible-to (space/here world actor)}])}

     {:name :give
      :kind :free
      :doc (prompts/doc "docs/items/give.md")
      :args [:map [:item :string] [:to :string]]
      :valid? give-reason
      :effect (fn [world actor {id :item to :to}]
                (let [box (:in (items/item world id))]
                  [{:type :item/given :data (cond-> {:item id :to to} box (assoc :from-item box))
                    :visible-to (space/here world actor)}]))}

     (timed-activity :spend-time act-on?)
     (timed-activity :work-on act-on?)]
    act-on? (conj act-on))))
