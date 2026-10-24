(ns manor.mechanics.physical.changes
  "Changing someone's physical status (manor.mechanics.physical): the events,
  seen by everyone present who can see (and the person), and the interrupts a
  change causes. A change to someone busy (asleep, working, hibernating)
  interrupts them, so they get a turn to decide whether to stop, unless it
  makes them unconscious or dead, which ends their occupation instead."
  (:require [clojure.string :as str]
            [manor.actions :as a]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.items :as items]
            [manor.mechanics.physical :as physical]
            [manor.mechanics.space :as space]
            [manor.world :as w]))

(defn- audience
  "Everyone at `id`'s place, and `id`; only `id` while they're travelling."
  [world id]
  (if (space/location-of world id) (space/here world id) #{id}))

(defn physical-changed
  "Event: `id`'s faculties change, each {:faculty :level :cause? :minutes?}.
  A :level of :normal clears the faculty; :minutes (consciousness only) say
  when they come to."
  [world id changes]
  {:type :person/physical-changed :actor id :visible-to (audience world id)
   :data {:person id
          :changes (mapv (fn [{:keys [faculty level cause minutes]}]
                           (cond-> {:faculty faculty :level level}
                             (and cause (not= :normal level)) (assoc :cause cause)
                             (and minutes (= :consciousness faculty) (= :disabled level))
                             (assoc :until (physical/until-round world minutes))))
                         changes)}})

(defn impediments-changed
  "Event: `id`'s impediments change, each {:key :value}; a blank value
  removes it."
  [world id changes]
  {:type :person/impediments-changed :actor id :visible-to (audience world id)
   :data {:person id
          :changes (mapv (fn [{:keys [key value]}]
                           (let [k (a/kebab-key key)]
                             {:key k :from (get-in (w/agent world id) [:impediments k])
                              :to (when-not (str/blank? value) value)}))
                         changes)}})

(defn died
  "Event: `id` dies, of `cause` (a phrase, or nil)."
  [world id cause]
  {:type :person/died :actor id :visible-to (audience world id)
   :data (cond-> {:person id} cause (assoc :cause cause))})

(defn interrupts
  "Interruptions by `by` for those of `ids` who are busy and not yet
  interrupted, on the world after a change: anyone it knocked out or killed
  is no longer busy."
  [world ids by]
  (for [id (distinct ids)
        :let [busy (w/busy world id)]
        :when (and busy (not (:interrupted-by busy)) (w/active? world id))]
    (b/interrupt world id by)))

(defn change-events
  "Events making `change` to `id`, then their interruption by `by` if
  they're still busy. `change` has any of :physical [{:faculty :level
  :cause? :minutes?}], :impediments [{:key :value}] and :died (a cause, or
  true). Check it first with `problem`."
  [world id {:keys [physical impediments] death :died} by]
  (let [evs (cond-> []
              (seq physical) (conj (physical-changed world id physical))
              (seq impediments) (conj (impediments-changed world id impediments))
              death (conj (died world id (when (string? death) death))))]
    (into evs (interrupts (reduce w/apply-event world evs) [id] by))))

(defn judged-events
  "Events making the judged `changes` ([[id change] ...], see
  `change-events`), person by person, each on the world after the ones
  before, with interruptions by `by`."
  [world changes by]
  (second (reduce (fn [[world evs] [id change]]
                    (let [more (change-events world id change by)]
                      [(reduce w/apply-event world more) (into evs more)]))
                  [world []] changes)))

(defn problem
  "Why `change` (see `change-events`) can't be made to `id`, or nil: they
  must be taking part (alive, for a status or death, while the dead may
  have impediments), each faculty's level one it allows, and their
  impediments well formed afterwards."
  [world id {:keys [physical impediments] death :died}]
  (let [after (:impediments (w/agent (w/apply-event world (impediments-changed world id impediments)) id))]
    (cond
      (and (or (seq physical) death) (not (w/active? world id))) (str "no living participant with id " id)
      (not (or (w/active? world id) (w/dead? world id))) (str "no participant with id " id)
      :else
      (or (first (for [{:keys [faculty level minutes]} physical
                       :let [levels (get physical/faculties faculty)]
                       :when (or (nil? levels) (not (some #{level} (conj levels :normal)))
                                 (and minutes (not= :consciousness faculty)))]
                   (cond (nil? levels) (str "no faculty " faculty "; one of " (str/join ", " (map name (keys physical/faculties))))
                         minutes "only consciousness takes minutes"
                         :else (str (name faculty) " can be " (str/join ", " (map name (conj levels :normal)))))))
          (when (seq after) (items/impediments-problem after))))))

(defn comings-to
  "Events for everyone whose unconsciousness ends by the current round."
  [world]
  (for [a (w/active-agents world)
        :let [{:keys [until]} (get-in a [:physical :consciousness])]
        :when (and until (<= until (:round world)))]
    {:type :person/came-to :actor (:id a) :visible-to (audience world (:id a)) :data {:person (:id a)}}))
