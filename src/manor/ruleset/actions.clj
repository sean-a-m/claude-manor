(ns manor.ruleset.actions
  "The ruleset's own actions: `walk` (free) anywhere in the current area,
  `call` to everyone within walking distance, `travel` (a turn) to another
  area, `sleep` in a bedroom, and `rouse` someone asleep or busy; and `pass`,
  noticed only by the people present. Docs and reasons describe mechanics,
  or look names up in the world: nothing here should become false if the
  map changes."
  (:require [clojure.string :as str]
            [manor.clock :as clock]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.building :as building]
            [manor.mechanics.following :as following]
            [manor.mechanics.items.views :as item-views]
            [manor.mechanics.physical :as physical]
            [manor.mechanics.space :as space]
            [manor.narrate :as narrate]
            [manor.prompts :as prompts]
            [manor.ruleset.places :as places]
            [manor.world :as w]))

(def max-sleep-hours 12)
(def max-call 300)
(def max-calls 5)

(defn arrival
  "What `walk` replies about the place `id` has walked into: what their view
  would show of it (the place, its opening hours, who is there and what they
  are doing or carrying, and the things there), so they needn't `look`.
  Someone who can't see gets only the place's name and :cant-see
  (manor.mechanics.physical/view-by-sight)."
  [world id]
  (let [here (space/location-of world id)
        hours (:hours (space/location world here))
        loc (:location (physical/view-by-sight world id (item-views/add-to-view {:location (space/view world id)} world id)))]
    (cond-> (select-keys loc [:here :people-here :cant-see])
      hours (update :here merge {:open (str/join "-" hours) :open-now? (building/open-now? world hours)}))))

(defn- hearers
  "Everyone within walking distance of `id`'s place, and `id`."
  [world id]
  (conj (set (mapcat #(space/present world %) (space/area world (space/location-of world id)))) id))

(defn- answers-calls?
  "Would a call stop `id` to answer: are they busy but awake, and not yet interrupted?"
  [world id]
  (let [busy (w/busy world id)]
    (and busy (not= "asleep" (:activity busy)) (not (:hibernating? busy)) (not (:interrupted-by busy)))))

(defn- call-events
  "The call, heard by everyone within walking distance; and if it names
  someone who hears it and is busy but awake, their interruption. With the
  caller's speech impeded, it's a muffled cry heard only in the room
  (manor.mechanics.physical/spoken)."
  [world actor {:keys [text to]}]
  (let [heard-by (if (physical/impeded? world actor :speech) (space/here world actor) (hearers world actor))]
    (cond-> (physical/spoken world actor :call
                             {:type :agent/called :data (cond-> {:text text :at (space/location-of world actor)} to (assoc :to to))
                              :visible-to heard-by})
      (and to (heard-by to) (physical/can? world to :hearing) (answers-calls? world to)) (conj (b/interrupt world to actor)))))

(defmethod w/perceived-by :agent/called [_] :hearing)

(defmethod narrate/describe :agent/called [{:keys [actor data]} names]
  (str (narrate/who names actor) " calls" (when-let [to (:to data)] (str " for " (narrate/who names to)))
       " from the " (str/lower-case (narrate/place names (:at data))) ": \"" (:text data) "\"" (narrate/muffled data)))

(defn- destination
  "Where `travel` to `to` goes: the place itself, or for an area name the
  nearest place of that area. nil if neither."
  [world actor to]
  (cond (space/location world to) to
        (contains? (places/area-names world) to) (first (places/way-in world (space/location-of world actor) to))))

(def actions
  [;; overrides the built-in, which everyone sees: here only people present notice
   {:name :pass
    :doc (prompts/doc "docs/pass.md")
    :effect (fn [world actor _] [{:type :agent/passed :visible-to (space/here world actor)}])}

   {:name :walk
    :kind :free
    :doc (prompts/doc "docs/walk.md")
    :args [:map [:to :string]]
    :per-turn physical/walks-per-turn
    :valid? (fn [world actor {:keys [to]}]
              (let [here (space/location-of world actor)]
                (cond (nil? (space/location world to))
                      (str to " is not a place. Use a place id: " (places/examples (places/walkable world here))
                           (when (and (contains? (places/area-names world) to) (not= to (places/area-of world here)))
                             (str "; to go to the " to ", use travel")))
                      (= to here) (str "you are already in " (places/loc-name world to))
                      (not (contains? (space/area world here) to))
                      (str (places/loc-name world to) " is not within walking distance of here; use travel")
                      :else (physical/movement-reason world actor 0))))
    :effect (fn [world actor {:keys [to]}]
              (following/along world actor 0
                               [{:type :agent/moved :data {:from (space/location-of world actor) :to to}
                                 :visible-to (into (space/here world actor) (space/present world to))}]))
    :reply (fn [world actor _] (arrival world actor))}

   {:name :call
    :kind :free :per-turn max-calls
    :doc (prompts/doc "docs/call.md" {:max-call max-call})
    :args [:map [:text [:string {:min 1 :max max-call}]] [:to {:optional true} :string]]
    :available? (fn [world actor] (physical/can? world actor :speech))
    :valid? (fn [world actor {:keys [to]}]
              (or (physical/speech-reason world actor)
                  (cond (nil? (space/location-of world actor)) "you can't call out while travelling"
                        (= to actor) "you can't call for yourself"
                        (and to (not (some #(= to (:id %)) (w/active-agents world)))) (str "no one has the id " to))))
    :effect call-events}

   {:name :travel
    :kind :final
    :doc (prompts/doc "docs/travel.md")
    :args [:map [:to :string]]
    :valid? (fn [world actor {:keys [to]}]
              (let [here (space/location-of world actor)
                    dest (destination world actor to)]
                (cond (and (nil? (space/location world to)) (= to (places/area-of world here)))
                      (str "you are already in the " to "; use walk to go to a particular place, e.g. "
                           (places/examples (places/walkable world here)))
                      (nil? dest)
                      (str to " is not a place. Use a place id: "
                           (places/examples (remove (space/area world here) (keys (get-in world [:space :locations]))))
                           ", or an area name: " (str/join ", " (places/area-names world)))
                      (= dest here) (str "you are already in " (places/loc-name world dest))
                      (contains? (space/area world here) dest)
                      (str (places/loc-name world dest) " is close by; use walk, which doesn't use up your turn")
                      (nil? (space/distance world here dest)) (str "there is no way to " (places/loc-name world dest) " from here")
                      :else (physical/movement-reason world actor 1))))
    :effect (fn [world actor {:keys [to]}]
              (let [here (space/location-of world actor)
                    dest (destination world actor to)
                    d (space/distance world here dest)]
                (following/along world actor d
                                 (if (<= d 1)
                                   [{:type :agent/moved :data {:from here :to dest}
                                     :visible-to (into (space/here world actor) (space/present world dest))}]
                                   [{:type :agent/departed :data {:from here :to dest :arrives (+ (:round world) d)}
                                     :visible-to (space/here world actor)}]))))}

   {:name :sleep
    :doc (prompts/doc "docs/sleep.md" {:max-sleep-hours max-sleep-hours})
    :args [:map [:until :string]]
    :valid? (fn [world actor {:keys [until]}]
              (let [c (:clock world)
                    round (:round world)
                    bed (places/bedroom-of world actor)
                    here (space/location-of world actor)]
                (cond (not (or (= :bedroom (:kind (space/location world here)))
                               (and bed (= bed here))))
                      (str "go to a bedroom first"
                           (when bed (str "; yours is " (places/loc-name world bed) " (" bed ")")))
                      (not (clock/hh-mm? until)) "until must be a time of day like \"07:00\""
                      (> (- (clock/round-at c round until) round) (clock/rounds-for c (* max-sleep-hours 60)))
                      (str until " is more than " max-sleep-hours " hours away; sleep until an earlier time"))))
    :effect (fn [world actor {:keys [until]}]
              [(b/occupy world actor "asleep" (clock/round-at (:clock world) (:round world) until) {:asleep? true})])}

   {:name :rouse
    :kind :free
    :doc (prompts/doc "docs/rouse.md")
    :args [:map [:who :string]]
    :valid? (fn [world actor {:keys [who]}]
              (cond (not (space/together? world actor who)) (str who " is not here")
                    (w/dead? world who) (str (:name (w/agent world who)) " is dead")
                    (physical/unconscious? world who)
                    (str (:name (w/agent world who)) " is unconscious: rousing won't bring them round")
                    (not (w/busy world who)) (str (:name (w/agent world who)) " is not asleep or busy")
                    (:interrupted-by (w/busy world who)) (str (:name (w/agent world who)) " has already been interrupted")))
    :effect (fn [world actor {:keys [who]}] [(b/interrupt world who actor)])}])

