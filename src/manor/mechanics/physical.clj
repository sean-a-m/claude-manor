(ns manor.mechanics.physical
  "Physical status (docs/physical-status-design.md): fixed faculties that can
  be hindered, open impediments, and death. Code enforces the levels; what
  happens in a struggle is a judge's decision, recorded here.

  World shape, on the agent:
    :physical {:movement      {:level :disabled :cause \"bound hand and foot\" :since 31}
               :consciousness {:level :disabled :cause \"struck on the head\" :since 31 :until 35}}
    :impediments {:hand \"a cut finger, bound in a rag\"}   ; keyed phrases, see items/Impediments
    :status :dead :died {:round 40 :cause \"...\"}

  No entry means normal. :cause is the judge's phrase, shown but never read
  by code; :since is the round it was set; :until (consciousness only) the
  round they come to. While unconscious, movement, grasp and speech count as
  disabled whatever their own entries say. The dead aren't :active, so they
  drop out of turns, `present`, conversations and hibernation, but stay
  where they died, shown to others with dead: true.

  This namespace holds the data, accessors, reasons, views (the sightless
  one too), reducers, narration, muffled speech and the perception filter
  `run/emit!` applies. Events with
  their audience, and the interrupts a change causes, are in
  manor.mechanics.physical.changes."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [manor.clock :as clock]
            [manor.narrate :as n]
            [manor.world :as w]))

(def faculties
  "Each faculty, in the order they're shown, with the levels it can be set
  to besides normal."
  (array-map :movement [:impeded :disabled]
             :grasp [:impeded :disabled]
             :speech [:impeded :disabled]
             :sight [:disabled]
             :hearing [:disabled]
             :consciousness [:disabled]))

(def implied-by-unconsciousness
  "The faculties that count as disabled while someone is unconscious."
  #{:movement :grasp :speech})

(def impeded-walks
  "How many walks someone with impeded movement may take in a turn."
  3)

(def max-cause 200)

;; ---------------------------------------------------------------------------
;; Accessors

(defn entry
  "`id`'s own entry for `faculty`, {:level :cause :since}, or nil when normal."
  [world id faculty]
  (get-in world [:agents id :physical faculty]))

(defn unconscious? [world id]
  (= :disabled (:level (entry world id :consciousness))))

(defn level
  "`id`'s level of `faculty`: :impeded, :disabled, or nil for normal. While
  they're unconscious, movement, grasp and speech are :disabled."
  [world id faculty]
  (if (and (implied-by-unconsciousness faculty) (unconscious? world id))
    :disabled
    (:level (entry world id faculty))))

(defn can?
  "Can `id` use `faculty` at all: is it not disabled?"
  [world id faculty]
  (not= :disabled (level world id faculty)))

(defn impeded? [world id faculty]
  (= :impeded (level world id faculty)))

(defn helpless?
  "May others take what `id` holds without a struggle: are they dead,
  unconscious or unable to grasp?"
  [world id]
  (or (w/dead? world id) (not (can? world id :grasp))))

(defn held-in-place?
  "Must code leave `id` where they are (no routine moves them): is their
  movement disabled, or are they unconscious?"
  [world id]
  (not (can? world id :movement)))

(defn sees?
  "Can `id` see what's around them: are they conscious, with sight?"
  [world id]
  (w/sees? world id))

(defn dead-at
  "Ids of the dead lying at `loc-id`."
  [world loc-id]
  (if loc-id
    (set (keep #(when (and (= :dead (:status %)) (= loc-id (:location %))) (:id %)) (vals (:agents world))))
    #{}))

(defn until-round
  "The round someone knocked out now for `minutes` comes to: at least the
  next one. Without a clock, the next round."
  [world minutes]
  (+ (:round world) (if-let [c (:clock world)] (clock/rounds-for c minutes) 1)))

;; ---------------------------------------------------------------------------
;; Reasons: what an action's :valid? says, repeating the status and its cause

(defn- described
  "\"your grasp is disabled (wrists tied)\", or \"you are unconscious\" for a
  faculty that's disabled only by unconsciousness."
  [world id faculty]
  (let [{:keys [level cause]} (entry world id faculty)]
    (if (or (= faculty :consciousness) (and (not= :disabled level) (unconscious? world id)))
      (str "you are unconscious" (when-let [c (:cause (entry world id :consciousness))] (str " (" c ")")))
      (str "your " (name faculty) " is " (name level) (when cause (str " (" cause ")"))))))

(defn disabled-reason
  "Why `id` can't do what needs `faculty`, or nil if it isn't disabled:
  \"your grasp is disabled (wrists tied), so you can't \" and `cant`."
  [world id faculty cant]
  (when-not (can? world id faculty)
    (str (described world id faculty) ", so you can't " cant)))

(defn movement-reason
  "Why `id` can't go somewhere `travel` rounds away (0 for next door), or
  nil: disabled movement keeps them where they are; impeded, they can go
  next door but not travel."
  [world id travel]
  (or (disabled-reason world id :movement "move from where you are")
      (when (and (pos? travel) (impeded? world id :movement))
        (str (described world id :movement) ", so you can only go a few steps nearby, not travel"))))

(defn walks-per-turn
  "An action's :per-turn for walking: `impeded-walks` with movement impeded,
  else no limit."
  [world id]
  (when (impeded? world id :movement) impeded-walks))

(defn speech-reason
  "Why `id` can't speak at all, or nil."
  [world id]
  (disabled-reason world id :speech "speak"))

(defn hearing-reason
  "Why nobody can speak to `id`, or nil: \"Hale can't hear you (deafened by
  the blast)\"."
  [world id]
  (when-not (can? world id :hearing)
    (str (:name (w/agent world id) id) " can't hear you"
         (when-let [c (:cause (entry world id :hearing))] (str " (" c ")")))))

(defn sight-reason
  "Why `id` can't do what needs sight, or nil: \"your sight is disabled
  (blindfolded), so you can't \" and `cant`."
  [world id cant]
  (disabled-reason world id :sight cant))

;; ---------------------------------------------------------------------------
;; Views: everyone present sees everyone's statuses, and their own under :you

(defn- shown-level [faculty {:keys [level cause]}]
  (str (if (= :consciousness faculty) "unconscious" (name level)) (when cause (str ": " cause))))

(defn shown
  "Agent `a`'s statuses as they and everyone present see them, in faculty
  order: {:movement \"disabled: bound hand and foot\" :consciousness
  \"unconscious\"}, or nil if they have none."
  [a]
  (let [physical (:physical a)]
    (when (seq physical)
      (apply array-map (mapcat (fn [f] (when-let [e (get physical f)] [f (shown-level f e)])) (keys faculties))))))

(defn with-status
  "`person` (a view of agent `a`) with their :physical statuses,
  :impediments and, if they're dead, dead: true."
  [person a]
  (cond-> person
    (seq (:physical a)) (assoc :physical (shown a))
    (seq (:impediments a)) (assoc :impediments (:impediments a))
    (= :dead (:status a)) (assoc :dead true)))

(defn judge-view
  "A person's hindered faculties for the work judge and steward: levels,
  optional causes, and when each was set, as a short time (or round).
  Agent-facing views keep `shown`'s phrases, without timestamps."
  [world id]
  (let [entries (:physical (w/agent world id))]
    (when (seq entries)
      (into (array-map)
            (for [faculty (keys faculties) :let [e (get entries faculty)] :when e]
              [faculty (cond-> (select-keys e [:level :cause])
                         (:since e) (assoc :set_at (if-let [c (:clock world)]
                                                    (clock/short-label c (:since e) (:round world))
                                                    (str "round " (:since e)))))])))))

(def cant-see
  "What someone who can't see is told in their view, in place of the people
  and things around them."
  "You can't see: who and what is here is hidden from you.")

(defn view-by-sight
  "`view` (an agent's, see manor.prompt/agent-view) as `id` has it: as it is
  if they can see; otherwise only the place's id and name, no people or
  things here, and :cant-see in their place. What they carry, the exits and
  where they last saw people stay: they know those."
  [world id view]
  (if (or (can? world id :sight) (nil? (get-in view [:location :here])))
    view
    (-> view
        (update-in [:location :here] select-keys [:id :name])
        (update :location dissoc :people-here)
        (assoc-in [:location :cant-see] cant-see))))

(def rules
  "What the levels stop people doing, for a scenario's rules."
  (str "Your body can be hindered. Under `physical` you may see that your movement, grasp or speech is impeded or disabled, "
       "or that you can't see or hear. Impeded movement keeps you to a few steps within the house; disabled, you can't move from where you are. "
       "With your grasp disabled you can't pick up, hand over or put things in; your speech impeded, others hear only muffled noises; "
       "disabled, you can't speak at all. Unable to see, you don't see who or what is around you, but you can still `find` things by touch "
       "and `take` what you know is there; unable to hear, you can't hold a conversation and nobody can whisper to you. "
       "`impediments` are smaller hurts that make work harder. "
       "Others see all of these on you, and you on them, and someone marked `dead` is dead. "
       "You can `take` what's held by someone here who is dead, unconscious or can't grasp."))

;; ---------------------------------------------------------------------------
;; World files and starting agents

(def Starting
  "An agent's starting :physical in a world file: faculties with a level
  and an optional cause; consciousness may say in how many :minutes they
  come to."
  (into [:map {:closed true}]
        (for [[f levels] faculties]
          [f {:optional true}
           (cond-> [:map {:closed true}
                    [:level (into [:enum] levels)]
                    [:cause {:optional true} [:string {:min 1 :max max-cause}]]]
             (= :consciousness f) (conj [:minutes {:optional true} pos-int?]))])))

(defn shape-problem
  "Why a starting :physical isn't well formed (see `Starting`), readably, or nil."
  [physical]
  (when-let [e (m/explain Starting physical)]
    (pr-str {:physical (me/humanize e)})))

(def Died
  "A dead agent's :died in a world file."
  [:map {:closed true} [:cause {:optional true} [:string {:min 1 :max max-cause}]]])

(defn starting-entries
  "A starting :physical as entries set in this round, :minutes turned into :until."
  [world physical]
  (into {} (for [[f {:keys [minutes] :as e}] physical]
             [f (cond-> (assoc (dissoc e :minutes) :since (:round world))
                  minutes (assoc :until (until-round world minutes)))])))

;; ---------------------------------------------------------------------------
;; Perception: who of an event's audience perceives it

(defn- perceives? [world id sense done-to]
  (and (not (unconscious? world id))
       (or (nil? sense) (contains? done-to id) (can? world id sense))))

(defn perceivable
  "`event` with its audience, when that's a set of ids, left without those
  who can't perceive it: anyone unconscious, and those lacking the sense its
  type is perceived by (w/perceived-by), except the people it was done to
  (w/done-to). The actor is always kept. :all, :gm and :system are left
  alone. `run/emit!` applies it before stamping, so the stored audience is
  the trimmed one."
  [world event]
  (let [v (:visible-to event)]
    (if (set? v)
      (let [sense (w/perceived-by event)
            done-to (set (w/done-to event))]
        (assoc event :visible-to (set (filter #(or (= % (:actor event)) (perceives? world % sense done-to)) v))))
      event)))

;; ---------------------------------------------------------------------------
;; Muffled speech

(defn spoken
  "The events of speech `event` by `actor` (something said, whispered or
  called out): just `event`, unless `actor`'s speech is impeded. Then its
  words reach only the speaker, marked :muffled (the GM sees every event),
  and the rest of its audience gets a :person/muffled event instead, whose
  :sound (:speech, :whisper or :call) says what it sounded like."
  [world actor sound event]
  (if (impeded? world actor :speech)
    (let [audience (:visible-to event)]
      [(-> event (assoc :visible-to #{actor}) (assoc-in [:data :muffled] true))
       {:type :person/muffled :actor actor
        :visible-to (if (set? audience) (disj audience actor) audience)
        :data (merge {:sound sound} (select-keys (:data event) [:at :to :conversation]))}])
    [event]))

(defmethod w/perceived-by :person/muffled [_] :hearing)

(defmethod n/describe :person/muffled [{:keys [actor data]} names]
  (case (:sound data)
    :whisper (str (n/who names actor) " makes muffled noises to " (n/who names (:to data)) ", as if trying to whisper.")
    :call (str (n/who names actor) " gives a muffled cry from the " (str/lower-case (n/place names (:at data))) ".")
    (str (n/who names actor) (when-let [at (:at data)] (str " (" (n/place names at) ")"))
         " makes muffled noises, as if trying to speak.")))

;; ---------------------------------------------------------------------------
;; Reducers

(defn- set-faculty
  "Agent `a` after one change {:faculty :level :cause :until} in `round`: a
  :level of :normal removes the entry, and being knocked out ends any
  occupation."
  [a {:keys [faculty level cause until]} round]
  (let [physical (if (= :normal level)
                   (dissoc (:physical a) faculty)
                   (assoc (:physical a) faculty (cond-> {:level level :since round}
                                                  cause (assoc :cause cause)
                                                  until (assoc :until until))))]
    (cond-> (if (seq physical) (assoc a :physical physical) (dissoc a :physical))
      (and (= :consciousness faculty) (= :disabled level)) (dissoc :busy))))

(defmethod w/apply-event :person/physical-changed [world {:keys [data]}]
  (reduce (fn [world change] (update-in world [:agents (:person data)] set-faculty change (:round world)))
          world (:changes data)))

(defmethod w/apply-event :person/came-to [world {:keys [data]}]
  (update-in world [:agents (:person data)] set-faculty {:faculty :consciousness :level :normal} (:round world)))

(defmethod w/apply-event :person/died [world {:keys [data]}]
  (update-in world [:agents (:person data)]
             #(-> % (assoc :status :dead :died (cond-> {:round (:round world)} (:cause data) (assoc :cause (:cause data))))
                  (dissoc :busy))))

(defn- set-impediment [a {:keys [key to]}]
  (let [impediments (if to (assoc (:impediments a) key to) (dissoc (:impediments a) key))]
    (if (seq impediments) (assoc a :impediments impediments) (dissoc a :impediments))))

(defmethod w/apply-event :person/impediments-changed [world {:keys [data]}]
  (reduce (fn [world change] (update-in world [:agents (:person data)] set-impediment change))
          world (:changes data)))

(doseq [t [:person/physical-changed :person/came-to :person/died :person/impediments-changed]]
  (defmethod w/perceived-by t [_] :sight))

;; ---------------------------------------------------------------------------
;; Narration

(defn- faculty-change-text [{:keys [faculty level cause]}]
  (str (cond (= :normal level) (str (name faculty) " normal again")
             (= :consciousness faculty) "knocked unconscious"
             :else (str (name faculty) " " (name level)))
       (when cause (str " (" cause ")"))))

(defmethod n/describe :person/physical-changed [{:keys [data]} names]
  (str (n/who names (:person data)) ": " (str/join "; " (map faculty-change-text (:changes data))) "."))

(defmethod n/describe :person/came-to [{:keys [data]} names]
  (str (n/who names (:person data)) " comes to."))

(defmethod n/describe :person/died [{:keys [data]} names]
  (str (n/who names (:person data)) " dies" (when-let [c (:cause data)] (str ": " c)) "."))

(defmethod n/describe :person/impediments-changed [{:keys [data]} names]
  (str (n/who names (:person data)) ": "
       (str/join "; " (for [{:keys [key from to]} (:changes data)]
                        (or to (str "no longer " (or from (str/replace (name key) "-" " "))))))
       "."))
