(ns manor.scenarios.claudeworth
  "Claudeworth Manor: an earl, a countess, a private secretary and the
  village architect, living at a manor beside a village and an abbey (phase
  1 of docs/claudeworth-design.md: daily life, before any building).

  The world is in resources/worlds/claudeworth.edn (run option :world-file
  overrides it): the map, the clock, the agents and their personas, the
  setting (:meta :setting) and what each role is (:roles). This namespace
  holds only the rules, and briefs and tool docs say nothing a world file
  could make false. Areas are the locations' :domains. `walk` (free) goes
  anywhere in the current area; `travel` (a turn) goes to another area.
  `sleep` and `spend-time` occupy an agent for several rounds at no model
  cost; `rouse` wakes someone early. :architect? false leaves Mr Pugh out.

    clojure -M:dev:run claudeworth --mock --test
    clojure -M:run claudeworth --test --max-cost 0.25"
  (:require [clojure.string :as str]
            [manor.builtins :as b]
            [manor.clock :as clock]
            [manor.space :as space]
            [manor.world :as w]
            [manor.worldfile :as worldfile])
  (:import (java.time LocalTime)))

(def max-pastime-hours 4)
(def max-sleep-hours 12)

;; ---------------------------------------------------------------------------
;; Helpers

(defn- loc-name [world loc] (:name (space/location world loc) loc))

(defn- now
  "The time of day of the current round, as a LocalTime."
  ^LocalTime [world]
  (.toLocalTime (clock/time-of (:clock world) (:round world))))

(defn- open-now? [world [open close]]
  (let [t (now world)]
    (and (not (.isBefore t (LocalTime/parse open))) (.isBefore t (LocalTime/parse close)))))

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
    :doc "Do nothing this turn."
    :effect (fn [world actor _] [{:type :agent/passed :visible-to (space/here world actor)}])}

   {:name :walk
    :kind :free
    :doc "Walk to any place listed under `walkable` in your view (use its id). You arrive at once, so you can act there this turn."
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
                :visible-to (into (space/here world actor) (space/present world to))}])}

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
              [(b/occupy world actor "asleep" (clock/round-at (:clock world) (:round world) until))])}

   {:name :spend-time
    :doc (str "Spend from half an hour to " max-pastime-hours " hours on a pastime here. activity is a short phrase, "
              "at most 80 characters, that reads after \"is\", e.g. \"reading the newspapers\". You get no turns until it's over.")
    :args [:map [:activity [:string {:min 3 :max 80}]] [:hours [:double {:min 0.5 :max max-pastime-hours}]]]
    :effect (fn [world actor {:keys [activity hours]}]
              [(b/occupy world actor activity
                         (+ (:round world) (clock/rounds-for (:clock world) (* 60 hours))))])}

   {:name :rouse
    :kind :free
    :doc "Wake someone here who is asleep, or interrupt someone busy (use their id)."
    :args [:map [:who :string]]
    :valid? (fn [world actor {:keys [who]}]
              (cond (not (space/together? world actor who)) (str who " is not here")
                    (not (w/busy world who)) (str (:name (w/agent world who)) " is not asleep or busy")))
    :effect (fn [world actor {:keys [who]}] [(b/free world who actor)])}])

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

(defn observe
  "Your bedroom, opening hours where you are, every place by area, where
  you can walk from here, and the journeys to other areas."
  [world id]
  (let [here (space/location-of world id)
        hours (:hours (space/location world here))]
    (cond-> {:you {:bedroom (bedroom-of world id)}}
      hours (assoc :here {:open (str/join "-" hours) :open-now? (open-now? world hours)})
      true (assoc :places (places world))
      here (assoc :walkable (vec (sort (walkable world here)))
                  :journeys (journeys world here)))))

;; ---------------------------------------------------------------------------
;; Roles. Who's who and the setting come from the world file (:meta :setting
;; and :roles, kept in :state at :init); the rules are written here, with
;; their numbers taken from the world and the actions.

(defn rules
  "How actions work, for every role."
  [world]
  (let [turn (duration (:minutes-per-round (:clock world)))]
    (str "Each turn is " turn " of game time, and you take one turn-ending action per turn. "
         "`walk` takes you at once to any place under `walkable` in your view, and doesn't end your turn: walk, then act there. "
         "`travel` goes to another area: use a place id from `places`, or an area name. It takes your turn; `journeys` "
         "in your view shows how long each journey takes, and a longer one leaves you travelling until you arrive. "
         "You're only heard by people in the same place (`say`: a few sentences, at most " b/speech-limit
         " characters); `whisper` is private. "
         "Sleep at night in a bedroom (`sleep` until a time, e.g. \"07:00\"). You get no turns while asleep, and others can `rouse` you. "
         "Use `spend-time` for longer pastimes (half an hour to " max-pastime-hours " hours). `note` keeps private notes. "
         "Opening hours are shown in your view where a place has them. "
         "Nothing can be built yet: alterations can be talked over and planned, but not commissioned.")))

(defn- setting [world] (get-in world [:state :setting]))

(defn- about [world role] (get-in world [:state :roles role :about]))

(defn agent-brief
  "The setting, what this agent's role is, and the rules."
  [world agent-id]
  (str/join "\n\n" (remove str/blank? [(about world (:role (w/agent world agent-id))) (setting world) (rules world)])))

(defn gm-brief [world]
  (str/join "\n\n" (remove str/blank? [(setting world) (about world :gm)
                                       "The engine keeps time and runs the world. Intervene only when asked, and lightly; otherwise call done."])))

(def roles
  {:earl {:brief agent-brief}
   :countess {:brief agent-brief}
   :secretary {:brief agent-brief}
   :architect {:brief agent-brief}
   :gm {:brief gm-brief}})

;; ---------------------------------------------------------------------------
;; Init

(defn problems
  "The scenario's own checks on a world file: bedrooms, opening hours, the
  setting, and an :about for every role an agent has."
  [{:keys [locations agents meta] :as wf}]
  (let [by-id (into {} (map (juxt :id identity)) locations)]
    (concat
     (for [{:keys [name bedroom]} agents
           :when (not= :bedroom (:kind (by-id bedroom)))]
       (str "agent " (pr-str name) ": :bedroom " (pr-str bedroom)
            (if (by-id bedroom) " is not a bedroom (:kind :bedroom)" " is not a location")))
     (for [{:keys [id hours]} locations
           :when (and (some? hours)
                      (not (and (vector? hours) (= 2 (count hours)) (every? clock/hh-mm? hours))))]
       (str "location " (pr-str id) ": :hours must be two times like [\"09:00\" \"18:00\"], not " (pr-str hours)))
     (when-not (string? (:setting meta)) [":meta :setting must be a paragraph about the place"])
     (for [role (distinct (map :role agents))
           :when (not (string? (get-in wf [:roles role :about])))]
       (str ":roles " role " needs an :about paragraph")))))

(defn init
  "The world file, its agents (without Mr Pugh if :architect? is false), and
  in :state everyone's bedroom, the setting and the roles' :about text."
  [{:keys [world-file] :as opts}]
  (let [source (or world-file "worlds/claudeworth.edn")
        {wf :world-file :as loaded} (worldfile/load-world source)]
    (when-let [ps (seq (problems wf))]
      (throw (ex-info (str "world file " source " has problems:\n- " (str/join "\n- " ps)) {:problems (vec ps)})))
    (let [agents (cond->> (:agents loaded)
                   (false? (:architect? opts)) (remove :optional?))]
      (assoc loaded
             :agents (vec agents)
             :state {:bedrooms (into {} (map (juxt (comp b/slug :name) :bedroom)) agents)
                     :setting (get-in wf [:meta :setting])
                     :roles (:roles wf)}))))

;; ---------------------------------------------------------------------------
;; Mock policy: a scripted day, so the scenario runs with no model calls.

(def mock-day
  "Agent id -> time of day -> the tool calls of that turn, in order (free
  ones first). Turns not listed pass. Breakfast, the earl's visit to Mr Pugh,
  the countess at the abbey, Mr Hale's letters, Mr Pugh's drawing board,
  bed, and Mr Hale rousing the earl at 06:30."
  {"lord-claudeworth"
   {"08:00" [[:walk {:to "dining-room"}]
             [:say {:text "I have made up my mind: this house is too small for us, and I mean to enlarge it. I shall see Pugh, the architect, this morning."}]]
    "08:30" [[:spend-time {:activity "breakfasting" :hours 1}]]
    "09:30" [[:travel {:to "architects-office"}]]
    "10:00" [[:say {:text "Good morning, Mr Pugh. I want Claudeworth made a proper seat: a library to begin with. What do you advise?"}]]
    "10:30" [[:travel {:to "carriage-sweep"}]]
    "11:00" [[:walk {:to "study"}] [:spend-time {:activity "attending to estate business" :hours 2}]]
    "13:00" [[:walk {:to "dining-room"}] [:spend-time {:activity "lunching" :hours 1}]]
    "14:00" [[:walk {:to "study"}] [:spend-time {:activity "reading the newspapers" :hours 4}]]
    "18:00" [[:walk {:to "drawing-room"}]
             [:say {:text "Pugh is keen. He talks a great deal about proportion, but I think he is our man."}]]
    "18:30" [[:walk {:to "dining-room"}] [:spend-time {:activity "dining" :hours 2}]]
    "20:30" [[:walk {:to "drawing-room"}] [:spend-time {:activity "smoking a cigar over a novel" :hours 2}]]
    "22:30" [[:walk {:to "earls-bedroom"}] [:sleep {:until "07:30"}]]
    "07:00" [[:say {:text "Thank you, Hale. Another day of it, then."}]]}

   "lady-claudeworth"
   {"08:00" [[:walk {:to "dining-room"}]
             [:say {:text "Enlarge it? Then I expect to be consulted on every room, Claudeworth."}]]
    "08:30" [[:spend-time {:activity "breakfasting" :hours 1}]]
    "09:30" [[:walk {:to "drawing-room"}] [:spend-time {:activity "going over the household accounts" :hours 4}]]
    "13:30" [[:travel {:to "abbey-church"}]]
    "14:00" [[:spend-time {:activity "sitting quietly in the abbey church" :hours 2}]]
    "16:00" [[:travel {:to "carriage-sweep"}]]
    "16:30" [[:walk {:to "drawing-room"}] [:spend-time {:activity "writing letters" :hours 1.5}]]
    "18:00" [[:say {:text "I hope Mr Pugh understands that the drawing room is mine to decide."}]]
    "18:30" [[:walk {:to "dining-room"}] [:spend-time {:activity "dining" :hours 2}]]
    "20:30" [[:walk {:to "drawing-room"}] [:spend-time {:activity "reading a novel" :hours 2}]]
    "22:30" [[:walk {:to "countess-bedroom"}] [:sleep {:until "07:30"}]]}

   "hale"
   {"08:00" [[:walk {:to "dining-room"}] [:say {:text "Good morning, my lord. Good morning, my lady."}]]
    "08:30" [[:spend-time {:activity "breakfasting" :hours 1}]]
    "09:30" [[:walk {:to "study"}] [:spend-time {:activity "writing letters" :hours 2}]]
    "11:30" [[:spend-time {:activity "answering the post" :hours 1.5}]]
    "13:00" [[:walk {:to "dining-room"}] [:spend-time {:activity "lunching" :hours 1}]]
    "14:00" [[:walk {:to "study"}] [:spend-time {:activity "writing letters" :hours 2}]]
    "16:00" [[:spend-time {:activity "filing correspondence" :hours 2}]]
    "18:00" [[:spend-time {:activity "copying letters" :hours 2}]]
    "20:00" [[:spend-time {:activity "reading" :hours 2}]]
    "22:00" [[:walk {:to "secretarys-room"}] [:sleep {:until "06:30"}]]
    "06:30" [[:walk {:to "earls-bedroom"}] [:rouse {:who "lord-claudeworth"}]
             [:say {:text "Good morning, my lord. It is half past six."}]]}

   "pugh"
   {"08:00" [[:walk {:to "architects-lodgings"}] [:spend-time {:activity "breakfasting" :hours 1}]]
    "09:00" [[:walk {:to "architects-office"}] [:pass {}]]
    "10:00" [[:say {:text "An honour, my lord. A library on the garden side would answer admirably, provided we mind the proportions."}]]
    "10:30" [[:spend-time {:activity "at his drawing board" :hours 2}]]
    "12:30" [[:walk {:to "architects-lodgings"}] [:spend-time {:activity "lunching" :hours 1}]]
    "13:30" [[:walk {:to "architects-office"}] [:spend-time {:activity "at his drawing board" :hours 4}]]
    "17:30" [[:spend-time {:activity "tidying the office" :hours 0.5}]]
    "18:00" [[:walk {:to "architects-lodgings"}] [:spend-time {:activity "reading by the fire" :hours 4}]]
    "22:00" [[:sleep {:until "07:30"}]]}})

(defn mock-policy [{:keys [world agent-id role used]}]
  (if (= role :gm)
    {:tool :done}
    (let [[tool args] (get-in mock-day [agent-id (str (now world)) (count used)])]
      (if tool {:tool tool :args args} {:tool :pass}))))

;; ---------------------------------------------------------------------------

(def scenario
  {:name :claudeworth
   :doc "Claudeworth Manor, 1904: a family, a secretary and the village architect, living from day to day."
   :init init
   :roles roles
   :actions actions
   :exclude-actions #{:move}
   :observe observe
   :mock-policy mock-policy})
