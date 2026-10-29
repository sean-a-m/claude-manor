(ns manor.scenarios.claudeworth
  "Claudeworth Manor: an earl, a countess, a private secretary and the
  village architect, living at and enlarging a manor beside a village and an abbey.

  The world is in resources/worlds/claudeworth.edn (run option :world-file
  overrides it): the map, the clock, the agents with their personas and
  situations, the things in the house (:items, see manor.mechanics.items), the setting
  (:meta :setting), and the positions they hold: the shared catalogue it
  names (:meta :positions) and its own :roles over it (see manor.mechanics.positions).
  This namespace holds only the rules, and briefs and tool docs say nothing
  a world file could make false. Areas are the locations' :domains. `walk` (free) goes
  anywhere in the current area; `travel` (a turn) goes to another area.
  `sleep` and `spend-time` occupy an agent for several rounds without turns;
  activities are judged when they end. `rouse` asks someone busy whether to stop. :leave-out leaves
  any of the world file's characters out of a run, by id (such as pugh). `find` works
  everywhere unless :find-everywhere? is false, which refuses it at shops,
  the public house and other places of trade (`find-allowed?`).
  An agent with a :routine in the world file (Mr Pugh) hibernates whenever
  nobody else is near: the routine says where they are and what they're doing,
  and they make no model calls until someone comes into their area, or a
  visit they're invited to keeps them in play that day (manor.mechanics.visits).

    clojure -M:dev:run claudeworth --mock --test
    clojure -M:run claudeworth --test --max-cost 0.25"
  (:require [clojure.string :as str]
            [manor.clock :as clock]
            [manor.io.worldfile :as worldfile]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.conversation :as conversation]
            [manor.mechanics.find-person :as find-person]
            [manor.mechanics.following :as following]
            [manor.mechanics.hiring :as hiring]
            [manor.mechanics.items :as items]
            [manor.mechanics.items.views :as item-views]
            [manor.mechanics.people :as people]
            [manor.mechanics.physical :as physical]
            [manor.mechanics.positions :as positions]
            [manor.mechanics.space :as space]
            [manor.mechanics.visits :as visits]
            [manor.narrate :as narrate]
            [manor.prompts :as prompts]
            [manor.settings :as settings]
            [manor.mechanics.building :as building]
            [manor.mechanics.deliveries :as deliveries]
            [manor.scenarios.claudeworth.holidays :as holidays]
            [manor.mechanics.shop :as shop]
            [manor.tools.scene-ruleset :as scene]
            [manor.world :as w])
  (:import (java.time LocalTime)))

(def max-sleep-hours 12)
(def max-call 300)
(def max-calls 5)

;; ---------------------------------------------------------------------------
;; Helpers

(defn- loc-name [world loc] (:name (space/location world loc) loc))

(defn- now
  "The time of day of the current round, as a LocalTime."
  ^LocalTime [world]
  (.toLocalTime (clock/time-of (:clock world) (:round world))))

(def open-now? building/open-now?)

(defn- bedroom-of [world id] (get-in world [:state :bedrooms id]))

(defn- duration
  "\"half an hour\", \"an hour\", \"2 hours\" or \"45 minutes\"."
  [minutes]
  (cond (= 30 minutes) "half an hour"
        (= 60 minutes) "an hour"
        (zero? (mod minutes 60)) (str (quot minutes 60) " hours")
        :else (str minutes " minutes")))

(defn- examples
  "Up to six ids, comma-separated."
  [ids]
  (str/join ", " (take 6 (sort ids))))

;; Areas are the locations' :domains: the house, the village, the abbey.

(defn- area-of [world loc] (some-> (:domain (space/location world loc)) name))

(defn- area-names [world]
  (into (sorted-set) (keep (comp #(some-> % name) :domain)) (vals (get-in world [:space :locations]))))

(defn- in-area [world area]
  (for [[id l] (get-in world [:space :locations]) :when (= area (some-> (:domain l) name))] id))

(defn- way-in
  "The nearest place of `area` from `from`: the first place of that area on
  the quickest path to it, and the journey's length in rounds."
  [world from area]
  (when-let [[d target] (first (sort (for [id (in-area world area)
                                           :let [d (space/distance world from id)]
                                           :when d]
                                       [d id])))]
    [(first (filter #(= area (area-of world %)) (space/path world from target))) d]))

(defn- walkable [world loc] (disj (space/area world loc) loc))

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
      hours (update :here merge {:open (str/join "-" hours) :open-now? (open-now? world hours)}))))

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
        (contains? (area-names world) to) (first (way-in world (space/location-of world actor) to))))

;; ---------------------------------------------------------------------------
;; Actions. Docs and reasons describe mechanics, or look names up in the
;; world: nothing here should become false if the map changes.

(def actions
  [;; overrides the built-in, which everyone sees: here only people present notice
   {:name :pass
    :doc "Do nothing (more) this turn."
    :effect (fn [world actor _] [{:type :agent/passed :visible-to (space/here world actor)}])}

   {:name :walk
    :kind :free
    :doc "Walk to any place listed under `walkable` in your view (use its id). You arrive at once, so you can act there this turn; the reply shows the place, who is there and what is there."
    :args [:map [:to :string]]
    :per-turn physical/walks-per-turn
    :valid? (fn [world actor {:keys [to]}]
              (let [here (space/location-of world actor)]
                (cond (nil? (space/location world to))
                      (str to " is not a place. Use a place id: " (examples (walkable world here))
                           (when (and (contains? (area-names world) to) (not= to (area-of world here)))
                             (str "; to go to the " to ", use travel")))
                      (= to here) (str "you are already in " (loc-name world to))
                      (not (contains? (space/area world here) to))
                      (str (loc-name world to) " is not within walking distance of here; use travel")
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
    :doc "Go somewhere beyond walking distance: a place id from `places` in your view, or an area name (a key of `places`) for its nearest place. Shorter journeys arrive within this turn; longer ones leave you travelling until you arrive. `journeys` in your view shows how long each takes."
    :args [:map [:to :string]]
    :valid? (fn [world actor {:keys [to]}]
              (let [here (space/location-of world actor)
                    dest (destination world actor to)]
                (cond (and (nil? (space/location world to)) (= to (area-of world here)))
                      (str "you are already in the " to "; use walk to go to a particular place, e.g. "
                           (examples (walkable world here)))
                      (nil? dest)
                      (str to " is not a place. Use a place id: "
                           (examples (remove (space/area world here) (keys (get-in world [:space :locations]))))
                           ", or an area name: " (str/join ", " (area-names world)))
                      (= dest here) (str "you are already in " (loc-name world dest))
                      (contains? (space/area world here) dest)
                      (str (loc-name world dest) " is close by; use walk, which doesn't use up your turn")
                      (nil? (space/distance world here dest)) (str "there is no way to " (loc-name world dest) " from here")
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
    :doc (str "Go to sleep in a bedroom until a time of day, e.g. \"07:00\" (at most " max-sleep-hours
              " hours away). You get no turns while asleep.")
    :args [:map [:until :string]]
    :valid? (fn [world actor {:keys [until]}]
              (let [c (:clock world)
                    round (:round world)
                    bed (bedroom-of world actor)]
                (cond (not= :bedroom (:kind (space/location world (space/location-of world actor))))
                      (str "go to a bedroom first"
                           (when bed (str "; yours is " (loc-name world bed) " (" bed ")")))
                      (not (clock/hh-mm? until)) "until must be a time of day like \"07:00\""
                      (> (- (clock/round-at c round until) round) (clock/rounds-for c (* max-sleep-hours 60)))
                      (str until " is more than " max-sleep-hours " hours away; sleep until an earlier time"))))
    :effect (fn [world actor {:keys [until]}]
              [(b/occupy world actor "asleep" (clock/round-at (:clock world) (:round world) until) {:asleep? true})])}

   {:name :rouse
    :kind :free
    :doc "Interrupt someone here who is asleep or busy (use their id). They get a turn and decide whether to stop."
    :args [:map [:who :string]]
    :valid? (fn [world actor {:keys [who]}]
              (cond (not (space/together? world actor who)) (str who " is not here")
                    (w/dead? world who) (str (:name (w/agent world who)) " is dead")
                    (physical/unconscious? world who)
                    (str (:name (w/agent world who)) " is unconscious: rousing won't bring them round")
                    (not (w/busy world who)) (str (:name (w/agent world who)) " is not asleep or busy")
                    (:interrupted-by (w/busy world who)) (str (:name (w/agent world who)) " has already been interrupted")))
    :effect (fn [world actor {:keys [who]}] [(b/interrupt world who actor)])}])

;; ---------------------------------------------------------------------------
;; Views

(defn- places
  "Every place by area, largest area first: {:house [{:id :name} ...] ...},
  with opening hours where a place has them."
  [world]
  (->> (vals (get-in world [:space :locations]))
       (sort-by :id)
       (group-by #(:domain % :elsewhere))
       (sort-by (comp - count val))
       (reduce (fn [m [domain locs]]
                 (assoc m domain (mapv #(cond-> (select-keys % [:id :name])
                                          (:hours %) (assoc :open (str/join "-" (:hours %))))
                                       locs)))
               (array-map))))

(defn- journeys
  "For each other area: its nearest place, how long the journey takes, and
  when you would arrive."
  [world here]
  (let [c (:clock world)
        round (:round world)]
    (vec (for [area (area-names world)
               :when (not= area (area-of world here))
               :let [[place d] (way-in world here area)]
               :when place]
           {:to area :place place :minutes (* d (:minutes-per-round c))
            :arrive (clock/short-label c (+ (max 1 round) d) round)}))))

(defn- house-bedrooms
  "The house's bedrooms and all active assigned sleepers, guests included.
   [{:id :name :sleeps [names]}]."
  [world]
  (let [sleepers (group-by #(bedroom-of world (:id %)) (w/active-agents world))]
    (vec (for [l (sort-by :id (vals (get-in world [:space :locations])))
               :when (and (= :bedroom (:kind l)) (= :house (:domain l)))]
           {:id (:id l) :name (:name l) :sleeps (mapv :name (get sleepers (:id l)))}))))

(defn reference
  "What rarely changes, for the system prompt: every place by area, and
  hiring's meeting place and positions."
  [world _]
  {:places (places world)
   :hiring (hiring/reference world)})

(defn observe
  "Your bedroom, the house's bedrooms if you're of the household or a guest
  from elsewhere, your visit if you're a guest and the people you've met who
  are away (manor.mechanics.visits), opening hours where you are, where you
  can walk from here, and the journeys to other areas. Every place by area
  is in `reference`."
  [world id]
  (let [here (space/location-of world id)
        hours (:hours (space/location world here))]
    (cond-> (-> (merge-with merge (building/observe world id) (shop/observe world id))
                (update :you assoc :bedroom (bedroom-of world id))
                (assoc :hiring (hiring/observe world id))
                (visits/add-to-view world id)
                (following/add-to-view world id))
      (or (positions/household-member? world id) (visits/from-elsewhere? world id))
      (assoc-in [:household :bedrooms] (house-bedrooms world))
      hours (update :here merge {:open (str/join "-" hours) :open-now? (open-now? world hours)})
      here (assoc :walkable (vec (sort (walkable world here)))
                  :journeys (journeys world here)))))

;; ---------------------------------------------------------------------------
;; Hibernation (design decision 11): someone nobody needs, like the village
;; architect on a day without clients, is out of play and their day is
;; summarised by their :routine instead of played.

(defn- routine-now
  "The entry of `id`'s routine in force now, {:from :at :doing}, or nil if
  they have none. Each entry holds from its time until the next one; before
  the first, the last (from the night before) holds."
  [world id]
  (when-let [entries (seq (get-in world [:state :routines id]))]
    (let [t (now world)]
      (or (last (remove #(.isAfter (LocalTime/parse (:from %)) t) entries))
          (last entries)))))

(defn- where-routine-has-them?
  "Is `id` in the place their routine puts them now? Never for someone without a routine."
  [world id]
  (= (:at (routine-now world id)) (space/location-of world id)))

(defn- company?
  "Is anyone else in play (not hibernating) at one of `places`, or on their
  way there? Someone just where their routine has them isn't company, so two
  people who share a workplace (the publican and his barmaid) don't keep each
  other in play once their callers have gone."
  [world id places]
  (some #(and (not= id (:id %))
              (not (b/hibernating? world (:id %)))
              (not (where-routine-has-them? world (:id %)))
              (contains? places (or (get-in % [:travel :to]) (:location %))))
        (w/active-agents world)))

(defn- watched
  "The places where company keeps `id` in play, with their routine putting
  them `at`: that place and the one they're in; or, when they're away from
  the routine's area (visiting the house, say), that place and the whole area
  they're in, so they don't vanish when their host steps into the next room."
  [world id at]
  (let [here (space/location-of world id)]
    (if (= (area-of world here) (area-of world at))
      (hash-set here at)
      (into (hash-set here at) (in-area world (area-of world here))))))

(defn hibernate
  "Where `id`'s routine puts them now, {:activity :location}, as long as
  nobody else is in the place they're in or the place it puts them in
  (when visiting another area: anywhere in that area), and no visit keeps
  them in play (an invited guest, on the day); else nil. So people
  elsewhere in the village don't wake them, nobody sees them taken off, and
  anyone arriving finds them where their routine has them."
  [world id]
  (when-let [{:keys [at doing]} (routine-now world id)]
    (when-not (or (visits/keeps-awake? world id) (company? world id (watched world id at)))
      {:activity doing :location at})))

;; ---------------------------------------------------------------------------
;; Roles. Who's who and the setting come from the world file (:meta :setting
;; and :roles, kept in :state at :init); the rules are templates under
;; resources/prompts/rules/, with their numbers taken from the world and the actions.

(defn- find-everywhere?
  "Is `find` allowed at places of trade too? Yes unless :find-everywhere? was false."
  [world]
  (not (false? (get-in world [:state :find-everywhere?]))))

(defn find-allowed?
  "Why `find` is refused at `place`, or nil: only at places with services
  (shops, the public house, the trades), and only when :find-everywhere? is
  false, so that goods for sale are bought rather than found."
  [world _actor place]
  (when (and (not (find-everywhere? world)) (seq (:services (space/location world place))))
    "this isn't your house to search; ask for it here instead"))

(defn- section
  "A section of the rules, or a part of one chosen in code: the template
  rules/sections/`file`.md, filled from `params`."
  ([world file] (section world file {}))
  ([world file params] (prompts/render world (str "rules/sections/" file ".md") params)))

(defn rules
  "How actions work, for every role: a section for each subject, so a model
  (and whoever reads the prompt) can find what applies. Each section is a
  template under resources/prompts/rules/sections/; this chooses which
  apply and passes their numbers."
  [world]
  (let [turn (duration (:minutes-per-round (:clock world)))
        max-queries (get-in world [:state :max-queries] 2)
        architect? (some #(contains? (:services %) :architect) (vals (get-in world [:space :locations])))
        visits? (visits/config world)]
    (prompts/render world
     "rules/how-things-work.md"
     {:sections
      (str/join
       "\n\n"
       (remove nil?
               [(section world "turns" {:turn turn :max-queries max-queries
                                        :counted (section world (if architect? "turns-counted-architect" "turns-counted"))})
                (section world "getting-about")
                (section world "speaking" {:speech-limit b/speech-limit :max-others conversation/max-others
                                           :passes conversation/passes})
                (section world "sleep")
                (section world "work" {:max-work-hours items/max-work-hours})
                (section world "things" {:find-for-sale (section world (if (find-everywhere? world)
                                                                          "things-find-everywhere"
                                                                          "things-find-not-at-trade"))})
                (section world "people" {:physical (physical/rules world)})
                (section world "shopping" {:paid (section world (if architect? "shopping-paid-commissions" "shopping-paid"))
                                           :spending-tools (section world (if architect? "shopping-spending-tools-commissions" "shopping-spending-tools"))
                                           :money (prompts/render world "money.md" {})})
                (when architect?
                  (section world "building" {:sizes (str/join ", " (map :size (get-in world [:state :architect-prices :sizes])))
                                             :fee-percent building/fee-percent}))
                (section world "hiring" {:hiring ((if visits? hiring/rules-beside-visits hiring/rules) world)})
                (when visits? (section world "invitations" {:visits (visits/rules world)}))]))})))

(defn- setting [world] (get-in world [:state :setting]))

(defn- judge-brief
  "The setting, and that everyday things are to hand."
  [world]
  (let [locations (vals (get-in world [:space :locations]))
        mechanics (cond-> []
                    (some #(contains? (:services %) :architect) locations)
                    (conj "judges/brief-architect.md")
                    (some deliveries/delivers? locations)
                    (conj "judges/brief-deliveries.md"))]
    (prompts/render world "judges/brief.md"
                    {:setting (or (setting world) "")
                     :mechanics (apply str (map #(str "\n\n" (prompts/render world % {})) mechanics))})))

(defn- about [world role] (get-in world [:state :positions role :about]))

(defn- position-line
  "\"Your position: Private secretary. You live in. You work at: Study
  (study).\" from the position's title, :sleeps and :workplace."
  [world {:keys [title sleeps workplace]}]
  (when title
    (str "Your position: " title "."
         (case sleeps :in-house " You live in." :out " You live out." nil)
         (when (seq workplace)
           (str " You work at: " (str/join ", " (for [id workplace] (str (loc-name world id) " (" id ")"))) ".")))))

(defn agent-brief
  "What this agent's position is and its duties, the setting, and the
  rules. The position is read from the world, so a new one needs no code."
  [world agent-id]
  (let [p (positions/position world agent-id)]
    (str/join "\n\n" (remove str/blank? [(str/join "\n" (remove nil? [(position-line world p) (:about p)]))
                                         (setting world) (rules world)]))))

(defn gm-brief [world]
  (str/join "\n\n" (remove str/blank? [(setting world) (about world :gm)
                                       "This slice has no income credit action: scheduled expectations may be recorded in a calendar when available, but never narrate money arriving without an engine-recorded credit."
                                       "The engine keeps time and runs the world. Intervene only when asked, and lightly; otherwise call done."])))

(def roles
  "Only the GM has a role of its own: everyone else gets `agent-brief`, which
  reads their position from the world."
  {:gm {:brief gm-brief}})

;; ---------------------------------------------------------------------------
;; Init

(defn problems
  "The scenario's own checks on a world file: bedrooms, opening hours, the
  setting, the positions (its catalogue and :roles), with an :about for every
  position an agent holds, and where they work."
  [{:keys [locations agents meta] :as wf}]
  (let [by-id (into {} (map (juxt :id identity)) locations)
        table (positions/table wf {})]
    (concat
     (building/problems wf)
     (shop/problems wf)
     (for [{:keys [name bedroom]} agents
           :when (not= :bedroom (:kind (by-id bedroom)))]
       (str "agent " (pr-str name) ": :bedroom " (pr-str bedroom)
            (if (by-id bedroom) " is not a bedroom (:kind :bedroom)" " is not a location")))
     (for [{:keys [id hours]} locations
           :when (and (some? hours)
                      (not (and (vector? hours) (= 2 (count hours)) (every? clock/hh-mm? hours))))]
       (str "location " (pr-str id) ": :hours must be two times like [\"09:00\" \"18:00\"], not " (pr-str hours)))
     (for [{:keys [name routine]} agents
           {:keys [from at doing] :as entry} routine
           :when (not (and (clock/hh-mm? from) (by-id at) (string? doing) (not (str/blank? doing))))]
       (str "agent " (pr-str name) ": :routine entry " (pr-str entry)
            " needs :from (a time like \"09:00\"), :at (a location id) and :doing (what they're doing there)"))
     (when-not (string? (:setting meta)) [":meta :setting must be a paragraph about the place"])
     (mapcat (fn [[id entry]] (positions/entry-problems id entry)) (sort-by key table))
     (for [role (distinct (map :role agents)) :when (not (contains? table role))]
       (str "position " role " has no :about paragraph: add it to :roles or the catalogue"))
     (for [{:keys [id] :as loc} locations
           :when (deliveries/delivers? loc)
           :let [courier (or (:courier loc) (:courier-position (settings/for-worldfile wf)))]
           :when (not (contains? table courier))]
       (str "location " (pr-str id) ": its courier's position " courier " is not in the positions catalogue"))
     (for [[id {:keys [workplace]}] (sort-by key table)
           place workplace
           :when (not (by-id place))]
       (str "position " id ": :workplace names unknown location " (pr-str place))))))

(defn init
  "The world file, its agents (without any whose id is in :leave-out, and
  without anything they hold), its items and authored visits (dropping
  left-out guests and removing left-out ids from :known-to), and in :state
  the positions (without forms of address if :addressed-as? is false), the setting, the
  query limit the rules quote and :find-everywhere? (default true).
  Everyone's post, bedroom and routine are set as they arrive (`on-spawn`)."
  [{:keys [world-file leave-out] :as opts}]
  (let [source (or world-file "worlds/claudeworth.edn")
        {wf :world-file :as loaded} (worldfile/load-world source)]
    (when-let [ps (seq (problems wf))]
      (throw (ex-info (str "world file " source " has problems:\n- " (str/join "\n- " ps)) {:problems (vec ps)})))
    (let [left-out (set (filter (set leave-out) (map (comp b/slug :name) (:agents loaded))))
          agents (remove #(left-out (b/slug (:name %))) (:agents loaded))
          ids (set (map (comp b/slug :name) agents))
          table (positions/table wf opts)]
      (assoc (cond-> loaded
               (:visits loaded)
               (update :visits #(mapv (fn [v]
                                       (cond-> v
                                         (vector? (:known-to v))
                                         (update :known-to (fn [ids] (filterv (complement left-out) ids)))))
                                     (remove (comp left-out :who) %))))
             :agents (vec agents)
             :items (filterv #(or (nil? (:holder %)) (ids (:holder %))) (:items loaded))
             :state (merge (building/initial-state wf agents table opts)
                           {:positions table
                            :setting-pack (settings/for-worldfile wf)
                            :posts {} :bedrooms {} :routines {}
                            :setting (get-in wf [:meta :setting])
                            :quotes {}
                            :max-queries (:max-queries opts 2)
                            :find-everywhere? (not (false? (:find-everywhere? opts)))})))))

;; ---------------------------------------------------------------------------
;; Mock policy: a scripted day, so the scenario runs with no model calls.

(def mock-day
  "Agent id -> time of day -> the tool calls of that turn, in order (free
  ones first). Turns not listed pass. Breakfast (where the countess draws the
  earl into a conversation, see `mock-conversation`), the earl's visit to Mr Pugh,
  the countess moving the candlesticks, visiting the abbey and buying a
  duster to give the housemaid, Mr Hale's errands (silver polish, left at
  the shop for Mr Briggs to send up, and a sack of flour ordered for
  delivery, which Nellie puts away) and secretarial work,
  Nellie the housemaid's round of the house,
  Mr Pugh's drawing board, bed, and Mr Hale rousing the earl at 06:30."
  {"lord-claudeworth"
   {"08:00" [[:walk {:to "dining-room"}]
             [:say {:text "I have made up my mind: this house is too small for us, and I mean to enlarge it. I shall see Pugh, the architect, this morning."}]]
    "08:30" [[:spend-time {:doing "breakfasting" :hours 1}]]
    "09:30" [[:travel {:to "architects-office"}]]
    "10:00" [[:say {:text "Good morning, Mr Pugh. I want Claudeworth made a proper seat: a library to begin with. What do you advise?"}]]
    "10:30" [[:say {:text "A library beside the study, then. Please draw it for me."}]]
    "11:00" [[:commission {:plan-id "library"}]]
    "11:30" [[:travel {:to "carriage-sweep"}]]
    "12:00" [[:walk {:to "study"}] [:spend-time {:doing "attending to estate business" :hours 1}]]
    "13:00" [[:walk {:to "dining-room"}] [:spend-time {:doing "lunching" :hours 1}]]
    "14:00" [[:walk {:to "study"}] [:spend-time {:doing "reading the newspapers" :hours 4}]]
    "18:00" [[:walk {:to "drawing-room"}]
             [:say {:text "Pugh is keen. He talks a great deal about proportion, but I think he is our man."}]]
    "18:30" [[:walk {:to "dining-room"}] [:spend-time {:doing "dining" :hours 2}]]
    "20:30" [[:walk {:to "drawing-room"}] [:spend-time {:doing "smoking a cigar over a novel" :hours 2}]]
    "22:30" [[:walk {:to "earls-bedroom"}] [:sleep {:until "07:30"}]]
    "07:00" [[:say {:text "Thank you, Hale. Another day of it, then."}]]}

   "lady-claudeworth"
   {"08:00" [[:walk {:to "dining-room"}]
             [:converse {:with ["lord-claudeworth"]
                         :text "Enlarge it? Then I expect to be consulted on every room, Claudeworth."}]]
    "08:30" [[:spend-time {:doing "breakfasting" :hours 1}]]
    "09:30" [[:open {:container "sideboard"}] [:take {:item "silver-candlesticks"}] [:walk {:to "drawing-room"}]
             [:put-down {:item "silver-candlesticks"}] [:look {}]
             [:call {:text "Nellie! The candlesticks are in the drawing room when you have a moment." :to "nellie"}]
             [:spend-time {:doing "going over the household accounts" :hours 4}]]
    "13:30" [[:travel {:to "abbey-church"}]]
    "14:00" [[:spend-time {:doing "sitting quietly in the abbey church" :hours 1.5}]]
    "15:30" [[:travel {:to "general-store"}]]
    "16:00" [[:request-goods {:item "feather duster" :amount "1"}]]
    "16:30" [[:accept-offer {:item "feather duster"}]]
    "17:00" [[:travel {:to "carriage-sweep"}]]
    "17:30" [[:walk {:to "drawing-room"}] [:give {:item "feather-duster" :to "nellie"}]
             [:spend-time {:doing "writing letters" :hours 1.5}]]
    "19:00" [[:say {:text "I hope Mr Pugh understands that the drawing room is mine to decide."}]]
    "19:30" [[:walk {:to "dining-room"}] [:spend-time {:doing "dining" :hours 2}]]
    "21:30" [[:walk {:to "drawing-room"}] [:spend-time {:doing "reading a novel" :hours 1}]]
    "22:30" [[:walk {:to "countess-bedroom"}] [:sleep {:until "07:30"}]]}

   "hale"
   {"08:00" [[:walk {:to "dining-room"}] [:say {:text "Good morning, my lord. Good morning, my lady."}]]
    "08:30" [[:spend-time {:doing "breakfasting" :hours 1}]]
    "09:30" [[:walk {:to "study"}] [:spend-time {:doing "writing letters" :hours 2}]]
    "11:30" [[:travel {:to "general-store"}]]
    "12:00" [[:request-goods {:goods [{:item "tin of silver polish" :amount "1"}
                                      {:item "sack of flour" :amount "1" :deliver true}]}]]
    "12:30" [[:ask-send-up {:item "tin of silver polish"}]
             [:accept-offer {:items ["tin of silver polish" "sack of flour"] :hand-over "leave"}]]
    "13:00" [[:travel {:to "carriage-sweep"}]]
    "13:30" [[:walk {:to "dining-room"}] [:spend-time {:doing "lunching" :hours 0.5}]]
    "14:00" [[:walk {:to "study"}] [:spend-time {:doing "filing correspondence" :hours 4}]]
    "18:00" [[:spend-time {:doing "copying letters" :hours 2}]]
    "20:00" [[:spend-time {:doing "reading" :hours 2}]]
    "22:00" [[:walk {:to "secretarys-room"}] [:sleep {:until "06:30"}]]
    "06:30" [[:walk {:to "earls-bedroom"}] [:rouse {:who "lord-claudeworth"}]
             [:say {:text "Good morning, my lord. It is half past six."}]]}

   "nellie"
   {"08:00" [[:walk {:to "kitchen"}] [:say {:text "Morning. Fires, then the dining room."}]]
    "09:00" [[:walk {:to "dining-room"}]
             [:spend-time {:on ["family-portrait"] :doing "dusting the dining room and the portrait" :hours 1}]]
    "10:00" [[:walk {:to "drawing-room"}]
             [:spend-time {:on ["silver-candlesticks"] :doing "polishing the silver candlesticks" :hours 1.5}]]
    "11:30" [[:take {:item "silver-candlesticks"}] [:walk {:to "dining-room"}]
             [:put-in {:item "silver-candlesticks" :container "sideboard"}] [:walk {:to "kitchen"}]
             [:spend-time {:on ["kitchen-range" "housemaids-cupboard"] :doing "blacking the kitchen range" :hours 2}]]
    "13:30" [[:walk {:to "entrance-hall"}]
             [:spend-time {:on ["entrance-hall" "stags-head"] :doing "dusting the hall and the stag's head" :hours 1}]]
    "14:30" [[:walk {:to "carriage-sweep"}] [:take {:item "sack-of-flour"}] [:walk {:to "kitchen"}]
             [:walk {:to "pantry"}] [:put-in {:item "sack-of-flour" :container "pantry-shelves"}]
             [:walk {:to "drawing-room"}]]
    "18:00" [[:spend-time {:on ["china-cabinet"] :doing "dusting the china cabinet and the glass with her new duster" :hours 1}]]
    "21:30" [[:walk {:to "maids-room"}] [:sleep {:until "06:30"}]]}

   "pugh"
   {"08:00" [[:walk {:to "architects-lodgings"}] [:spend-time {:doing "breakfasting" :hours 1}]]
    "09:00" [[:walk {:to "architects-office"}] [:pass {}]]
    "10:00" [[:consult-references {:query "library study"}] [:price-list {}]
             [:say {:text "An honour, my lord. A library on the garden side would answer admirably, provided we mind the proportions."}]]
    "10:30" [[:draw-plans {:for "lord-claudeworth" :name "Library" :floor "ground"
                           :desc "A panelled library with shelves and a reading table." :connects-to ["study"] :size "large"}]
             [:show-plans {:plan-id "library"}]
             [:spend-time {:doing "at his drawing board" :hours 2}]]
    "12:30" [[:walk {:to "architects-lodgings"}] [:spend-time {:doing "lunching" :hours 1}]]
    "13:30" [[:walk {:to "architects-office"}] [:spend-time {:doing "at his drawing board" :hours 4}]]
    "17:30" [[:spend-time {:doing "tidying the office" :hours 0.5}]]
    "18:00" [[:walk {:to "architects-lodgings"}] [:spend-time {:doing "reading by the fire" :hours 4}]]
    "22:00" [[:sleep {:until "07:30"}]]}})

(defn- scripted-done
  "How many of `script`'s calls are done, given `used` (the tools called this
  turn, in order). Calls off the script, like a walk to bed, don't count."
  [script used]
  (reduce (fn [i tool] (if (= tool (some-> (get script i) first name)) (inc i) i)) 0 used))

(defn- way-to-bed
  "The call that takes `id` to their bedroom: a walk, or a travel if it's in
  another area (which takes the turn). nil if they're there already."
  [world id]
  (let [here (space/location-of world id)
        bed (bedroom-of world id)]
    (when (and bed (not= here bed))
      [(if (contains? (space/area world here) bed) :walk :travel) {:to bed}])))

(defn- remove-absent-cast
  "Scripted calls to someone not in the run (see :leave-out) are dropped,
  such as the countess's gift to the housemaid, and her call for her."
  [world script]
  (remove (fn [[tool {:keys [to]}]] (and (#{:give :call} tool) to (not (w/agent world to)))) script))

(defn scripted-policy
  "The next call of `day` (agent id -> time -> tool calls) for this turn.
  A scripted `sleep` away from your bedroom goes there first, e.g. after a
  late :start. Tests can supply fixed choreography independently of mock-day."
  [day {:keys [world agent-id role used]}]
  (cond
    (= role :gm) {:tool :done}
    (and (positions/capable? world agent-id :shop-clerk)
         (nil? (get-in day [agent-id (str (now world))]))) (shop/scripted-clerk day (str (now world)) world agent-id used)
    :else
    (let [time (str (now world))
          client-plan (first (sort-by :id (filter #(= "lord-claudeworth" (:for %)) (vals (get-in world [:state :plans])))))
          script (cond
                   (and (= agent-id "lord-claudeworth") (= time "18:00")
                        (space/location world (:id client-plan)))
                   (into [[:walk {:to (:id client-plan)}]] (get-in day [agent-id time]))
                   :else (get-in day [agent-id time]))
          script (remove-absent-cast world script)
          ;; A late start may already put someone in their bedroom.
          script (if (some #(= :sleep (first %)) script)
                   (remove (fn [[tool {:keys [to]}]]
                             (and (= :walk tool) (= to (bedroom-of world agent-id))
                                  (= to (space/location-of world agent-id)))) script)
                   script)
          ;; With no architect in the run, no plan is drawn to commission or show.
          script (if client-plan script (remove (fn [[tool]] (#{:commission :show-plans} tool)) script))
          script (mapv (fn [[tool args]]
                         [tool (if (and (#{:commission :show-plans} tool) client-plan)
                                 (assoc args :plan-id (:id client-plan)) args)]) script)
          script (mapv (fn [[tool args]] (shop/scripted-customer world agent-id tool args)) script)
          [tool args] (get script (scripted-done script used))
          [tool args] (or (when (= :sleep tool) (way-to-bed world agent-id)) [tool args])]
      (if tool {:tool tool :args args}
          {:tool (if (:interrupted-by (w/busy world agent-id)) :carry-on :pass)}))))

(defn mock-policy
  "The bundled scenario's demonstration day."
  [ctx]
  (scripted-policy mock-day ctx))

(defn mock-conversation
  "Speaking turns of the demonstration day: at breakfast the earl answers the
  countess once, and she ends it. Other turns take the default (the
  initiator ends it, the others stay silent)."
  [{:keys [agent-id conversation]}]
  (when (and (= "lord-claudeworth" agent-id) (= 1 (:pass conversation)))
    {:tool :reply :args {:text "Every room, my dear. I would not dream of choosing a wallpaper without you."}}))

;; ---------------------------------------------------------------------------

(defn- hiring-action [action-name] (first (filter #(= action-name (:name %)) hiring/actions)))

(defn- to-a-member
  "Hiring's `action-name` for a line in a conversation, with the person its
  argument `k` names limited to the conversation's `members`: `refusal` otherwise."
  [action-name k refusal members]
  (update (hiring-action action-name) :valid?
          (fn [valid?]
            (fn [world actor args]
              (or (when-not (some #{(get args k)} members) refusal)
                  (valid? world actor args))))))

(defn speaking-actions
  "Tools for a line, before speaking: anyone can recommend a candidate to
  someone in the conversation, offer a candidate in it a place, or accept or
  decline a place offered; an open counter's clerk can check goods, and
  send up goods waiting there if the shop sends goods out; a customer with
  quotes made for them at this shop can buy."
  [world speaker {:keys [members]}]
  (cond-> [(to-a-member :recommend :for "recommend to someone in this conversation" members)
           (to-a-member :engage :who "offer a place to someone in this conversation" members)
           (hiring-action :accept-place)
           (hiring-action :decline-place)]
    (not (shop/service-reason world speaker))
    (into (filter #(= :quote-goods (:name %)) shop/actions))
    (and (not (shop/service-reason world speaker))
         (deliveries/delivers? (space/location world (space/location-of world speaker)))
         (seq (shop/waiting-here world speaker)))
    (into (filter #(= :send-up (:name %)) shop/actions))
    (seq (shop/quotes-here world speaker))
    (into (filter #(= :buy (:name %)) shop/actions))))

(defn- speaking-actions-and-following
  "`speaking-actions`, after `follow` and `follow-stop`: anyone can start or
  stop following someone while they talk, so agreeing to come along takes
  them along."
  [world speaker conv]
  (into following/actions (speaking-actions world speaker conv)))

(def scenario
  (visits/install
   (deliveries/install
    (hiring/install
     {:name :claudeworth
      :doc "Claudeworth Manor, 1904: a family and their servants, a village architect, shops, a public house and cottage trades, living from day to day."
      :scene scene/hooks
      :init (comp holidays/add-to init)
      :calendar {:lookahead-days 30}
      :roles roles
      :agent-brief agent-brief
      :on-spawn positions/starting-events
      :actions (vec (concat actions [find-person/action] following/actions building/actions shop/actions hiring/actions))
      :adjudicators (merge shop/adjudicators hiring/adjudicators find-person/adjudicators)
      :bridges (merge building/bridges hiring/bridges)
      :offline-stubs (merge building/offline-stubs hiring/offline-stubs)
      :mcp-servers (merge building/mcp-servers people/mcp-servers)
      :systems [building/construction]
      :items {:steward {:at "02:00" :idle-hours 4 :max-per-call 8}
              :find-allowed? find-allowed?
              :act-on? true
              :judge-brief judge-brief
              :aspect-keys {:cleanliness "how clean or dirty it is" :repair "damage, wear or needed repairs"
                            :state "its present condition, where no other key fits"
                            :amount "how much there is of a single thing"
                            :stock "a store's general level of supplies" :lacking "what a store has run out of"
                            :dress "what a person is wearing, where it differs from usual"
                            :hair "how a person's hair is done, where it differs from usual"
                            :grime "dirt or stains on a person, where they show"}}
      :inner {:keys {:mood "their spirits and emotions now"
                     :body "how their body is, as only they know it"
                     :mind "what they're preoccupied with, anticipating or dreading"}
              :open 2}
      :exclude-actions #{:move :work-on}
      :speaking-actions speaking-actions-and-following
      :hibernate hibernate
      :reference reference
      :observe observe
      :mock-policy mock-policy
      :mock-conversation mock-conversation}
     "carriage-sweep"))))
