(ns manor.scenarios.claudeworth
  "Claudeworth Manor: an earl, a countess, a private secretary and the
  village architect, living at and enlarging a manor beside a village and an abbey.

  The world is in resources/worlds/claudeworth.edn (run option :world-file
  overrides it): the map, the clock, the agents with their personas and
  situations, the things in the house (:items, see manor.items), the setting
  (:meta :setting), and the positions they hold: the shared catalogue it
  names (:meta :positions) and its own :roles over it (see manor.positions).
  This namespace holds only the rules, and briefs and tool docs say nothing
  a world file could make false. Areas are the locations' :domains. `walk` (free) goes
  anywhere in the current area; `travel` (a turn) goes to another area.
  `sleep` and `spend-time` occupy an agent for several rounds without turns;
  activities are judged when they end. `rouse` asks someone busy whether to stop. :architect? false leaves Mr Pugh out,
  and :housemaid? false leaves the housemaid, Nellie, out.
  An agent with a :routine in the world file (Mr Pugh) hibernates whenever
  nobody else is near: the routine says where they are and what they're doing,
  and they make no model calls until someone comes into their area.

    clojure -M:dev:run claudeworth --mock --test
    clojure -M:run claudeworth --test --max-cost 0.25"
  (:require [clojure.string :as str]
            [manor.builtins :as b]
            [manor.clock :as clock]
            [manor.conversation :as conversation]
            [manor.items :as items]
            [manor.narrate :as narrate]
            [manor.positions :as positions]
            [manor.scenarios.building :as building]
            [manor.scenarios.shop :as shop]
            [manor.space :as space]
            [manor.world :as w]
            [manor.worldfile :as worldfile])
  (:import (java.time LocalTime)))

(def max-sleep-hours 12)
(def max-call 300)

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
  are doing or carrying, and the things there), so they needn't `look`."
  [world id]
  (let [here (space/location-of world id)
        hours (:hours (space/location world here))
        loc (:location (items/add-to-view {:location (space/view world id)} world id))]
    (cond-> (select-keys loc [:here :people-here])
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
  someone who hears it and is busy but awake, their interruption."
  [world actor {:keys [text to]}]
  (let [heard-by (hearers world actor)]
    (cond-> [{:type :claudeworth/called :data (cond-> {:text text :at (space/location-of world actor)} to (assoc :to to))
              :visible-to heard-by}]
      (and to (heard-by to) (answers-calls? world to)) (conj (b/interrupt world to actor)))))

(defmethod narrate/describe :claudeworth/called [{:keys [actor data]} names]
  (str (narrate/who names actor) " calls" (when-let [to (:to data)] (str " for " (narrate/who names to)))
       " from the " (str/lower-case (narrate/place names (:at data))) ": \"" (:text data) "\""))

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
    :valid? (fn [world actor {:keys [to]}]
              (let [here (space/location-of world actor)]
                (cond (nil? (space/location world to))
                      (str to " is not a place. Use a place id: " (examples (walkable world here))
                           (when (and (contains? (area-names world) to) (not= to (area-of world here)))
                             (str "; to go to the " to ", use travel")))
                      (= to here) (str "you are already in " (loc-name world to))
                      (not (contains? (space/area world here) to))
                      (str (loc-name world to) " is not within walking distance of here; use travel"))))
    :effect (fn [world actor {:keys [to]}]
              [{:type :agent/moved :data {:from (space/location-of world actor) :to to}
                :visible-to (into (space/here world actor) (space/present world to))}])
    :reply (fn [world actor _] (arrival world actor))}

   {:name :call
    :kind :free :per-turn 1 :limit-key :speech
    :doc (str "Call out, so that everyone within walking distance (the whole house, say) hears you wherever they are: "
              "a short call, at most " max-call " characters. Name someone with to (their id) to call for them; "
              "if they hear you and are busy, but not asleep, they may stop to answer. Shares its limit with say and whisper.")
    :args [:map [:text [:string {:min 1 :max max-call}]] [:to {:optional true} :string]]
    :valid? (fn [world actor {:keys [to]}]
              (cond (nil? (space/location-of world actor)) "you can't call out while travelling"
                    (= to actor) "you can't call for yourself"
                    (and to (not (some #(= to (:id %)) (w/active-agents world)))) (str "no one has the id " to)))
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
                      (nil? (space/distance world here dest)) (str "there is no way to " (loc-name world dest) " from here"))))
    :effect (fn [world actor {:keys [to]}]
              (let [here (space/location-of world actor)
                    dest (destination world actor to)
                    d (space/distance world here dest)]
                (if (<= d 1)
                  [{:type :agent/moved :data {:from here :to dest}
                    :visible-to (into (space/here world actor) (space/present world dest))}]
                  [{:type :agent/departed :data {:from here :to dest :arrives (+ (:round world) d)}
                    :visible-to (space/here world actor)}])))}

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
  "The house's bedrooms, and who sleeps in each (anyone active whose
  bedroom it is): [{:id :name :sleeps [names]}]."
  [world]
  (let [sleepers (group-by #(bedroom-of world (:id %)) (w/active-agents world))]
    (vec (for [l (sort-by :id (vals (get-in world [:space :locations])))
               :when (and (= :bedroom (:kind l)) (= :house (:domain l)))]
           {:id (:id l) :name (:name l) :sleeps (mapv :name (get sleepers (:id l)))}))))

(defn observe
  "Your bedroom, the house's bedrooms if you're of the household, opening
  hours where you are, every place by area, where you can walk from here,
  and the journeys to other areas."
  [world id]
  (let [here (space/location-of world id)
        hours (:hours (space/location world here))]
    (cond-> (-> (merge-with merge (building/observe world id) (shop/observe world id))
                (update :you assoc :bedroom (bedroom-of world id)))
      (positions/household-member? world id) (assoc-in [:household :bedrooms] (house-bedrooms world))
      hours (update :here merge {:open (str/join "-" hours) :open-now? (open-now? world hours)})
      true (assoc :places (places world))
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

(defn- company?
  "Is anyone else in play (not hibernating) at one of `places`, or on their way there?"
  [world id places]
  (some #(and (not= id (:id %))
              (not (b/hibernating? world (:id %)))
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
  (when visiting another area: anywhere in that area); else nil. So people
  elsewhere in the village don't wake them, nobody sees them taken off, and
  anyone arriving finds them where their routine has them."
  [world id]
  (when-let [{:keys [at doing]} (routine-now world id)]
    (when-not (company? world id (watched world id at))
      {:activity doing :location at})))

;; ---------------------------------------------------------------------------
;; Roles. Who's who and the setting come from the world file (:meta :setting
;; and :roles, kept in :state at :init); the rules are written here, with
;; their numbers taken from the world and the actions.

(defn rules
  "How actions work, for every role."
  [world]
  (let [turn (duration (:minutes-per-round (:clock world)))
        max-queries (get-in world [:state :max-queries] 2)
        architect? (some #(contains? (:services %) :architect) (vals (get-in world [:space :locations])))]
    (str "Each turn is " turn " of game time, and you take one turn-ending action per turn. "
         "`walk` takes you at once to any place under `walkable` in your view, and doesn't end your turn: walk, then act there. Its reply shows the place, who is there and what is there, so you needn't `look` on arriving. "
         "`travel` goes to another area: use a place id from `places`, or an area name. It takes your turn; `journeys` "
         "in your view shows how long each journey takes, and a longer one leaves you travelling until you arrive. "
         "You're only heard by people in the same place (`say`: a few sentences, at most " b/speech-limit
         " characters); `whisper` is private. `call` is heard everywhere within walking distance, so you can summon someone instead of searching the house room by room; whether it's proper for you to call someone depends on who you both are. `last-seen` in your view says where you last saw each person. "
         "You can speak once a turn, with `say`, `whisper` or `call`, and it doesn't end your turn: speak, then take your action. "
         "To talk something over with people here, `converse` draws up to " conversation/max-others " of them into a conversation within your turn: "
         "you each speak in turn, up to " conversation/passes " times, so nobody waits a turn for each answer, and then you carry on with your turn. "
         "Sleep at night in a bedroom (`sleep` until a time, e.g. \"07:00\"). Someone who lives in needs a bed in the house: members of the household see the house's bedrooms, and who sleeps in each, under `household.bedrooms` in their view, and whether to share a room or make do is for you to decide. You get no turns while asleep or busy, unless someone uses `rouse` to interrupt you. On that turn, `carry-on` ignores them; any other accepted action means you stop. "
         "`spend-time` spends time on work or pastimes (`spend-time {doing, hours, on?}`, half an hour to " items/max-work-hours " hours); optionally name things here or carried, the room itself, or stores in scope with `on`. Describe everyday tools and supplies in `doing`; they need no item ids. Carry things such as dishes with `take` and `walk`; they are used up where they are eaten, not while being carried. Results show when you finish. `note` keeps private notes. "
         "Things in your place are under `items` in your view and what you carry under `carrying`; `look` shows them in detail, with their descriptions, owners and aspects (how clean they are, what needs mending, and so on), along with the room and what the people here carry, so use it to examine something closely, not routinely on arrival. The room's own aspects (its floor, hearth and air) and the stores in scope are under `here` in your view. Supplies are kept in stores (shelves, cupboards), listed under `here.stores`: a store has everything it keeps unless its `stock` or `lacking` says otherwise; `look` shows what it keeps. "
         "The house has the ordinary things a house like it keeps: everyday things (cloths, paper, pans, salt, matches and the like) can be assumed to be to hand unless you've been told otherwise. The things listed in your view are the notable ones, not everything there is. "
         "Use `find` only for something you must pick up or hand over, or whose presence is in doubt; everyday things you merely use need no finding. "
         "Some things hold others: what's in an open one is listed under its `contents`, and one marked `closed` shows what's inside only when you `open` it. "
         "You can `take` things out of either, and `put-in` puts something into one. "
         "`look`, `open`, `take`, `put-in`, `put-down` and `give` don't end your turn. "
         "If you are waiting on someone or nothing needs doing, `pass` is the way to wait a turn; you act again next turn. "
         "Opening hours are shown in your view where a place has them. "
         (if architect? "Purchases and commissions" "Purchases")
         " are paid from the household account (`household` in your view). There is no engine-enforced spending restriction by role: whether you should spend depends on your station, instructions and relationships. At an open shop, ask the shopkeeper with `say` for portable goods and quantities; there is no fixed catalogue. The shopkeeper checks availability and quotes firm prices, a whole shopping list in one `quote-goods`. Accept offers made for you with `buy {quote-ids}`, from `offers` in your view: one `buy` can take the whole list. Payment is separate from asking; no bargaining, bulk counters or delivery. Prices are old sterling: £1 = 20 shillings = 240 pence (12d = 1s). "
         (when architect?
           (str "Building is arranged at the architect's office while it is open, not at the house: talk at home is for deciding what you want. "
                "At the office you can search reference books (`consult-references`), ask for the `price-list` and have plans drawn (`draw-plans`); any character can `commission` a plan they hold, but should judge whether they have the social authority to do so. `show-plans` shares plans with everyone present, anywhere. "
                "Plans are a special case: they are not things, so they are never carried, given, fetched, copied, lost or searched for. Once plans are drawn, the architect and the client both hold them, and anyone they are shown to holds them too; `plans` in your view lists the plans you hold, and those are all you need. "))
         (if architect? "A reference lookup, the price list, each draft, each `quote-goods`" "Each `quote-goods`")
         " (however many goods it lists) and `find` all count towards your " max-queries " queries a turn, even if asked for at once; once you reach " max-queries ", take your action and query again next turn. "
         (when architect?
           (str "Only one room is built at a time: `commission` is refused while a room is being built (see `building` for the work in hand and when it finishes), so the next room must wait until the present one is finished. "
                "Commissioning pays from the household; the room appears at the completion time in `building`. The architect earns "
                building/fee-percent "% on commissions for plans he drew.")))))

(defn- setting [world] (get-in world [:state :setting]))

(defn- judge-brief
  "The setting, and that everyday things are to hand."
  [world]
  (str (setting world)
       "\n\nThe house has the ordinary things a house like it keeps: assume the everyday things the work needs (cloths, paper, pans, salt, matches and the like) are to hand unless what you're shown says otherwise. The things listed are the notable ones, not everything there is."
       "\n\nArchitect's plans are kept outside this judgement and are never things: never make plans, drawings or copies of plans. Drawing or copying plans changes nothing here."))

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
                                       "This slice has no rents or other income: never narrate money arriving on its own, and promise no future payments."
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
     (for [[id {:keys [workplace]}] (sort-by key table)
           place workplace
           :when (not (by-id place))]
       (str "position " id ": :workplace names unknown location " (pr-str place))))))

(defn- optional-agent-kept?
  "Whether an agent marked :optional? is in this run. The marker names the run
  option that decides it: Mr Pugh (:architect?) and the housemaid (:housemaid?)
  are in unless their option is false. Agents without a marker are
  always in."
  [opts agent]
  (case (:optional? agent)
    :architect? (not (false? (:architect? opts)))
    :housemaid? (not (false? (:housemaid? opts)))
    true))

(defn init
  "The world file, its agents (without Mr Pugh if :architect? is false,
  without the housemaid if :housemaid? is false, and without anything they
  hold), its items, and in :state the positions (without forms of address if
  :addressed-as? is false), the setting and the query limit the rules quote.
  Everyone's post, bedroom and routine are set as they arrive (`on-spawn`)."
  [{:keys [world-file] :as opts}]
  (let [source (or world-file "worlds/claudeworth.edn")
        {wf :world-file :as loaded} (worldfile/load-world source)]
    (when-let [ps (seq (problems wf))]
      (throw (ex-info (str "world file " source " has problems:\n- " (str/join "\n- " ps)) {:problems (vec ps)})))
    (let [agents (filter #(optional-agent-kept? opts %) (:agents loaded))
          ids (set (map (comp b/slug :name) agents))
          table (positions/table wf opts)]
      (assoc loaded
             :agents (vec agents)
             :items (filterv #(or (nil? (:holder %)) (ids (:holder %))) (:items loaded))
             :state (merge (building/initial-state wf agents table opts)
                           {:positions table
                            :posts {} :bedrooms {} :routines {}
                            :setting (get-in wf [:meta :setting])
                            :quotes {}
                            :max-queries (:max-queries opts 2)})))))

;; ---------------------------------------------------------------------------
;; Mock policy: a scripted day, so the scenario runs with no model calls.

(def mock-day
  "Agent id -> time of day -> the tool calls of that turn, in order (free
  ones first). Turns not listed pass. Breakfast (where the countess draws the
  earl into a conversation, see `mock-conversation`), the earl's visit to Mr Pugh,
  the countess moving the candlesticks, visiting the abbey and buying a
  duster to give the housemaid, Mr Hale's errands and secretarial work,
  Nellie the housemaid's round of the house (unless :housemaid? is false),
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
    "16:00" [[:request-goods {:item "feather duster" :qty 1}]]
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
    "12:00" [[:request-goods {:item "tin of silver polish" :qty 1}]]
    "12:30" [[:accept-offer {:item "tin of silver polish"}]]
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
    "14:30" [[:walk {:to "drawing-room"}]]
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
  "Scripted calls that need someone left out of the run are dropped: the
  countess's gift to the housemaid, and her call for her, when there is no
  housemaid (see :housemaid?)."
  [world script]
  (remove (fn [[tool {:keys [to]}]] (and (#{:give :call} tool) to (not (w/agent world to)))) script))

(defn scripted-policy
  "The next call of `day` (agent id -> time -> tool calls) for this turn.
  A scripted `sleep` away from your bedroom goes there first, e.g. after a
  late :start. Tests can supply fixed choreography independently of mock-day."
  [day {:keys [world agent-id role used]}]
  (cond
    (= role :gm) {:tool :done}
    (positions/capable? world agent-id :shop-clerk) (shop/scripted-clerk day (str (now world)) world agent-id used)
    :else
    (let [time (str (now world))
          architect (building/architect-here world agent-id)
          client-plan (first (sort-by :id (filter #(= "lord-claudeworth" (:for %)) (vals (get-in world [:state :plans])))))
          clerk? (and (= agent-id "lord-claudeworth") (nil? architect))
          script (cond
                   (and clerk? (= time "10:00"))
                   [[:consult-references {:query "library study"}] [:price-list {}] [:pass {}]]
                   (and clerk? (= time "10:30"))
                   [[:draw-plans {:name "Library" :floor "ground" :size "large"
                                  :desc "A panelled library with shelves and a reading table." :connects-to ["study"]}]
                    [:pass {}]]
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

(def scenario
  {:name :claudeworth
   :doc "Claudeworth Manor, 1904: a family and their servants, a village architect, a general-store keeper and a butcher, living from day to day."
   :init init
   :roles roles
   :agent-brief agent-brief
   :on-spawn positions/starting-events
   :actions (into (into actions building/actions) shop/actions)
   :adjudicators shop/adjudicators
   :bridges building/bridges
   :offline-stubs building/offline-stubs
   :mcp-servers building/mcp-servers
   :systems [building/construction]
   :items {:steward {:at "02:00" :idle-hours 4 :max-per-call 8}
           :find-allowed? (fn [world _ place]
                            (when (seq (:services (space/location world place)))
                              "this isn't your house to search; ask for it here instead"))
           :judge-brief judge-brief
           :aspect-keys {:cleanliness "how clean" :repair "damage and needed repairs"
                         :state "lit, hot, cooking, carved" :amount "how much remains, or parcel quantity"
                         :stock "general level of supplies" :lacking "notable absences"}}
   :exclude-actions #{:move :work-on}
   :hibernate hibernate
   :observe observe
   :mock-policy mock-policy
   :mock-conversation mock-conversation})
