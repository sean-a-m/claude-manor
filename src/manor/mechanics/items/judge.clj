(ns manor.mechanics.items.judge
  "The judge: what the prompt holds, its one tool, and the checks on its
  answer. The steward and finding share its checks on things made and its
  JSON helpers."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [malli.json-schema :as mjs]
            [manor.actions :as a]
            [manor.clock :as clock]
            [manor.mechanics.adjudication :as adjudication]
            [manor.mechanics.inner :as inner]
            [manor.mechanics.items :as items]
            [manor.mechanics.items.work :as item-work]
            [manor.mechanics.physical :as physical]
            [manor.mechanics.physical.changes :as physical-changes]
            [manor.mechanics.space :as space]
            [manor.world :as w]))

(def max-physical
  "Enough entries to change every faculty of the worker and six named people."
  (* (inc items/max-work-items) (count physical/faculties)))

(def physical-entries
  "The judge's answer about bodies (manor.mechanics.physical): statuses set
  or cleared, impediments, and deaths."
  [[:physical {:optional true}
    [:vector {:max max-physical}
     [:map
      [:person :string]
      [:faculty (into [:enum] (keys physical/faculties))]
      [:level [:enum :normal :impeded :disabled]]
      [:cause {:optional true} [:string {:min 1 :max physical/max-cause :json-schema/maxLength items/advised-aspect}]]
      [:minutes {:optional true} [:int {:min 1}]]]]]
   [:impediments {:optional true}
    [:vector {:max max-physical} [:map [:person :string] [:key :string] [:value [:string {:max items/max-aspect :json-schema/maxLength items/advised-aspect}]]]]]
   [:died {:optional true}
    [:vector {:max (inc items/max-work-items)} [:map [:person :string] [:cause {:optional true} [:string {:min 1 :max physical/max-cause :json-schema/maxLength items/advised-aspect}]]]]]])

(def moved-entry
  "An act's things moved: each to a judged person's id, or \"place\"."
  [:moved {:optional true} [:vector [:map [:item :string] [:to :string]]]])

(def judge-schema
  [:map
   [:changes {:optional true} [:vector [:map [:thing :string] [:aspect :string] [:value [:string {:max items/max-aspect :json-schema/maxLength items/advised-aspect}]]]]]
   [:used-up {:optional true} [:vector :string]]
   [:absorbed {:optional true} [:vector [:map [:thing :string] [:into :string]]]]
   [:fates {:optional true} [:map-of :keyword :string]]
   [:made {:optional true} [:vector {:max items/max-made}
                            [:map
                             [:name [:string {:min 1 :max items/max-name}]]
                             [:desc [:string {:min 1 :max items/max-desc}]]
                             ;; value lengths are checked by `made-problem`: decoding drops map-of entries that don't fit
                             [:aspects {:optional true} [:map-of {:max items/max-aspects} :keyword :string]]
                             [:consumable :boolean]
                             [:from {:optional true} :string]
                             [:held {:optional true} :boolean]
                             [:in {:optional true} :string]]]]
   [:summary [:string {:min 1 :max items/max-summary}]]])

(def ^:private not-in-acts
  "What an immediate act can't do: use up, absorb or make things."
  #{:used-up :absorbed :fates :made})

(defn answer-schema
  "The judge's answer: `judge-schema` with `physical-entries` before the
  summary, and with inner state (the scenario's :inner, see
  manor.mechanics.inner) an `inner` list beside `changes`. For an
  immediate act (`act?`), nothing used up, absorbed or made, and `moved`
  instead. The steward's schema comes from it."
  ([inner-config] (answer-schema inner-config false))
  ([inner-config act?]
   (let [[m changes & more] judge-schema
         summary (last more)
         more (cond->> (butlast more) act? (remove #(not-in-acts (first %))))]
     (into [m changes] (concat (when inner-config [inner/answer-entry]) more physical-entries
                               (when act? [moved-entry]) [summary])))))

(defn- judge-tool [inner-config act?]
  {:type "function"
   :function {:name "judge"
              :description (str "Give your judgement: each aspect the " (if act? "act" "work") " changed (the thing's or person's id, the aspect's key, "
                                "and its new value: a phrase of at most " items/advised-aspect " characters, or \"\" to remove it), "
                                (when inner-config
                                  "each part of a judged person's inner state it changed (`inner`: their id, the key, and the new value, or \"\" to clear it), ")
                                (if act?
                                  "the statuses, impediments and deaths of judged people it caused or ended, the things it moved, "
                                  (str "the ids of consumable items it used up and their fates, things absorbed into stores, "
                                       "at most " items/max-made " things made or drawn from stores, "
                                       "any statuses, impediments and deaths of judged people it caused or ended, "))
                                "and a summary of at most " items/max-summary " characters.")
              :parameters (mjs/transform (answer-schema inner-config act?))}})

(def judge-instructions
  (str "You judge the results of an activity in a simulation. Someone has spent time on work or a pastime. "
       "Decide what that activity, by this person, realistically changed in the scene, and answer by calling the `judge` tool. "
       "The time may be too short for the whole job: then only part of it is done.

- Most activities change little, and a pastime often changes nothing. Empty changes, used-up, absorbed and made lists are normal, successful answers. Resting in the garden need not change anything. Do not invent events to make the activity eventful, even for an unusual luck roll.
- A store holds everything it `keeps` unless its aspects say otherwise. The listed items are notable things, not everything available.
- You may change aspects of the place, focus, also_here, stores and the people marked `person`. Only consumables in focus or also_here may be used up. Other people and recent lines are context, not things to change.
- A person's aspects say how they are now, where it shows and lasts beyond the activity: dress, hair, hands and face, dirt and wet. Never moods or feelings, and never hurts or hindrances, which are impediments and statuses (below). Their `appearance` is how they usually look; aspects record departures from it, and an aspect that only restates it should be removed. Most work leaves the worker as they were. Change another person only when the activity was done to them. People can't be used up, absorbed or made, and nothing can be put in them.
- A thing's `aspects` say what's notable about its state, each under its own key: cleanliness, repair, state, amount, stock and the like. A change sets one aspect of one thing: `thing` (its id), `aspect` (the key) and `value` (a short phrase of at most " items/advised-aspect " characters saying how it is now).
- Change only the aspects the activity affected, and leave the others alone: polishing a cabinet changes its cleanliness, not its repair. Use a key the thing already has where one fits; a new key is lower-case letters and hyphens, at most 24 characters. A thing has at most " items/max-aspects " aspects. To remove an aspect that no longer applies, give it the value \"\".
- Leaving any thing untouched is always a valid answer. Leave out anything the work left unchanged.
- An activity happens in one place and you cannot move things (only an immediate act can, below). Taking or carrying something elsewhere is not using it up: leave it where it is; the person carries things with `take` and movement actions.
- When leftovers or supplies are put away into a store in scope (leftovers set in the pantry, groceries shelved), absorb them into that store: they join its stock and get a put-away fate. Adjust the store's aspects if its stock or lacking changes. Never describe a thing as moved or put away in its aspects while leaving it where it is. If something isn't put away or consumed, leave it as it is.
- The `place` entry is the place itself: its aspects describe the room as a whole (floor, hearth, air), not the things in it, which have their own entries.
- `Carrying` lists the notable things the worker holds. Their clothes and the everyday things work needs (a needle, a cloth, a basket) are never listed there, but they are to hand. Sometimes an activity assumes a particular notable thing that someone said they brought, handed over or set aside (a parcel, packets of goods, a letter) that is neither in the scene nor carried: only such a thing doesn't exist. Never write as if it was counted, used, moved or set out, in the summary or in aspects; do the rest of the work and say in the summary that it wasn't there. A store still holds the everyday supplies it keeps, and work may draw on them as usual.
- An item's nature can't change: work can't turn a vase into gold. If the task is impossible or absurd, change nothing and say so in the summary.
- Only things marked `consumable` can be used up, and only once they are gone: food eaten or cooked into something else, fuel burnt, an ingredient used up. List their ids in `used-up`; give their fates in `fates` (id to short phrase), otherwise the fate is \"used up while <doing>\". Something partly used is not used up: change its `amount` to say what's left (\"about 17 lb left\").
- `made` is for at most " items/max-made " things really produced from what was used up or everyday supplies (no source or use-up needed for the activity's direct product: a letter, a list, plans, a dish), or fetched from a store in scope with `from` (that store's id). Keep `from` for drawing from a store, not making a work product. Give each a name, desc, optional aspects and a `consumable` flag: writing and drawing products are not consumable; dishes are. Make a thing only if someone will carry, hand over, serve or keep it; otherwise describe the result as an aspect (the room's table \"laid for dinner\"). Do not promote every utensil used.
- A made entry may give `held`: true puts it in the worker's hands, false at the job's place. Things the worker would naturally keep about them (a letter, a list, a note) are held; dishes and large things aren't. `in` puts it in a listed container and takes precedence. Without either, it goes to the worker's hands if no place-bound focus was named, else the job's place.
- A quick job records putting things away, already moved into the store: judge it even at zero minutes. You may absorb them into stock, clear or change the store's lacking or stock, change affected aspects, or leave things alone.
- `absorbed: [{thing, into}]` puts a thing from focus or also_here away in a store in scope (`into`, its id). Only unfixed, non-store things marked `consumable` or `drawn_from` can be absorbed. A container can go only with all its contents absorbed too. Do not absorb something still individually important.
- A thing listed with `in` is inside another of the things listed. The work may or may not have touched it: leave it out if it didn't.
- Luck is a roll from 1 to 100, and 50 is ordinary: low luck can mean mishaps (a slip, a breakage), high luck can mean it went unusually well. The roll alone does not require a change.
- People in `people` may show `physical`, the statuses of their faculties with a cause, and `impediments`, smaller physical hurts (a cut finger, a sprained wrist); someone marked `dead` is dead. Take them into account: someone bound can't polish the silver, and a cut hand makes fine work slower. Movement impeded keeps them to a few steps nearby; disabled, they can't move. Grasp impeded makes handling things awkward; disabled, they can't pick up or hand over anything. Speech impeded, they're heard only as muffled noises; disabled, not at all. Sight or hearing disabled, they can't see or hear. Consciousness disabled is unconscious.
- Most activities change none of these: leave `physical`, `impediments` and `died` out. A mishap on low luck may leave an impediment (a cut finger, a scald), and care given to someone may ease or clear one.
- `physical: [{person, faculty, level, cause?, minutes?}]` sets a judged person's faculty: level `normal` clears it; movement, grasp and speech can be `impeded` or `disabled`, sight, hearing and consciousness only `disabled`. `cause` is a short phrase (\"bound hand and foot with curtain cord\"), at most " items/advised-aspect " characters. For someone knocked unconscious, `minutes` says how long until they come to; without it they stay out until someone revives them.
- `impediments: [{person, key, value}]` sets a judged person's impediment as aspects are set: a key (hand, ankle, face), a short phrase, or \"\" to remove it. A person has at most " items/max-impediments " impediments.
- `died: [{person, cause?}]` records that a judged person was killed. Death is final; the dead can still have aspects and impediments changed, and what they carry moved.
- An immediate act, marked so in the activity, is something done to people at once, in a few minutes: taking hold of someone, binding or gagging them, striking or searching them, freeing or reviving them. It may set or clear any faculty, add or remove impediments, or kill, as the scene warrants, and nothing is used up, absorbed or made. It may move what the actor or the people acted on carry (their `carrying`): `moved: [{item, to}]`, with `to` a judged person's id (into their hands) or \"place\" (dropped here).
- The summary is one or two sentences, past tense, third person, for the people present, at most " items/max-summary " characters."))

(def pretty (json/object-mapper {:pretty true}))

(defn judged-json
  "The judged things as the judge sees them: the room marked :room, and
  items with :in only when the container is among them, and :consumable
  and :container when true."
  [things]
  (let [ids (set (map :id things))]
    (mapv #(cond-> (select-keys % [:id :name :desc :aspects])
             (:room? %) (assoc :room true)
             (ids (:in %)) (assoc :in (:in %))
             (:consumable? %) (assoc :consumable true)
             (:drawn-from %) (assoc :drawn_from (:drawn-from %))
             (:container %) (assoc :container true))
          things)))

(defn- people-json
  "The scene's people as the judge sees them: judged ones with their id,
  appearance and aspects, marked :person, and for an act what they carry;
  others by name only; everyone with their physical statuses, impediments
  and whether they're dead. With inner state (`inner-config`), everyone's is
  shown, and the judged people's temperament, persona and situation too."
  [world inner-config people]
  (mapv (fn [{:keys [id person?] :as p}]
          (cond-> (select-keys p (if person?
                                   [:id :name :appearance :aspects :physical :impediments :dead :doing :carrying]
                                   [:name :physical :impediments :dead :doing]))
            (and inner-config person?) (merge (select-keys (w/agent world id) [:temperament :persona :situation]))
            inner-config (assoc :inner (inner/judge-view inner-config world id))
            person? (assoc :person true)))
        people))

(defn judge-request
  "The scene judge's request, including only the worker's shared recent lines
  supplied by the engine through prompt/scene-recent."
  [scenario world job {:keys [minutes stopped-early? luck recent]}]
  (let [act? (:act? job)
        worker (w/agent world (:actor job))
        brief (when-let [f (get-in scenario [:items :judge-brief])] (f world))
        suggested (get-in scenario [:items :aspect-keys])
        inner-config (:inner scenario)
        {:keys [place focus also-here stores people]} (item-work/scene world job)
        scene-json {:place (dissoc place :room?)
                    :focus (judged-json focus)
                    :also_here (judged-json also-here)
                    :stores (mapv #(assoc (items/store-view (:place job) %) :keeps (get-in % [:store :keeps])) stores)
                    :people (people-json world inner-config people) :recent (vec (take-last 10 recent))}
        scene-json (cond-> scene-json (:reference job) (assoc :reference (:reference job)))]
    {:tools [(judge-tool inner-config act?)]
     :messages
     [{:role "system"
       :content (str judge-instructions
                     (when (seq suggested)
                       (str "\n\n## Suggested aspect keys\n"
                            (str/join "\n" (for [[k gloss] (sort-by key suggested)] (str "- " (name k) ": " gloss)))))
                     (when inner-config (str "\n\n" (inner/instructions inner-config)))
                     (when-not (str/blank? brief) (str "\n\n## The setting\n" brief)))}
      {:role "user"
       :content (str "# The activity\n\n"
                     "- Time: " (if-let [c (:clock world)] (clock/label c (:round world)) (str "round " (:round world))) "\n"
                     (when act? "- An immediate act, a few minutes: done to the people named, judged at once\n")
                     (if act? "- Actor: " "- Worker: ") (:name worker) (when-not (str/blank? (:persona worker)) (str ". " (:persona worker))) "\n"
                     "- Place: " (:name (space/location world (:place job)) (:place job)) "\n"
                     "- Doing: " (:doing job) "\n"
                     (when act? (str "- On: " (str/join ", " (map #(str (:name (w/agent world %)) " (" % ")") (:people job))) "\n"))
                     "- Carrying: " (let [held (items/by-name (items/held-by world (:actor job)))]
                                      (if (seq held) (str/join ", " (map #(str (:name %) " (" (:id %) ")") held)) "nothing"))
                     "\n"
                     (when (:quick? job) "- Quick job: putting things away; no occupation or time needed\n")
                     (when-not act? (str "- Time worked: " minutes " minutes" (if stopped-early? "; they stopped early" ", as planned") "\n"))
                     "- Luck: " luck "\n\n"
                     "## The scene\n```json\n"
                     (json/write-value-as-string scene-json pretty)
                     "\n```\n\nCall `judge` now.")}]}))

(defn- ids-or-none [items] (if (seq items) (str/join ", " (map :id items)) "none"))

(defn- decoded-changes
  "The judge's changes as [{:thing :aspect :to}]: the aspect's name as a
  key, normalised like argument names (\"glass_eye\" is :glass-eye), and a
  blank value as a nil :to, removing the aspect. When the same aspect of a
  thing comes more than once, the last counts."
  [changes]
  (let [cs (for [{:keys [thing aspect value]} changes]
             {:thing thing :aspect (a/kebab-key aspect) :to (when-not (str/blank? value) value)})
        last-of (into {} (map (juxt (juxt :thing :aspect) identity)) cs)]
    (map last-of (distinct (map (juxt :thing :aspect) cs)))))

(defn- aspects-after
  "Each judged thing's aspects, by id, once `changes` are made."
  [things changes]
  (reduce (fn [m {:keys [thing aspect to]}]
            (if to (assoc-in m [thing aspect] to) (update m thing dissoc aspect)))
          (into {} (map (juxt :id #(or (:aspects %) {}))) things)
          changes))

(defn- key-problem
  "Why the aspect keys `ks` won't do, or nil."
  [ks]
  (when-let [bad (seq (distinct (remove items/aspect-key? ks)))]
    (str "an aspect's key is lower-case letters and hyphens, at most 24 characters, starting with a letter "
         "(cleanliness, glass-eye); not " (str/join ", " (map #(pr-str (name %)) bad)))))

(defn- change-problem
  "Why the judge can't make `changes`, or nil: each names one of the judged
  `things`, with a well-formed key, and no thing ends up with more than
  `max-aspects` aspects (counting those it has, less those cleared)."
  [things changes]
  (let [by-id (into {} (map (juxt :id identity)) things)
        strays (distinct (remove by-id (map :thing changes)))
        after (aspects-after things changes)
        crowded (first (filter #(> (count (after %)) items/max-aspects) (distinct (map :thing changes))))]
    (cond
      (seq strays) (str "you may change only these things: " (ids-or-none things) "; not " (str/join ", " strays))
      (key-problem (map :aspect changes)) (key-problem (map :aspect changes))
      crowded (str "the " (:name (by-id crowded)) " (" crowded ") would have " (count (after crowded)) " aspects ("
                   (str/join ", " (map name (keys (after crowded)))) "); a thing has at most " items/max-aspects
                   ": change one it has instead, or remove one with the value \"\""))))

(defn- use-up-problem
  "Why the judge can't use up `gone`: only consumables in the scene, not fixed,
  with all real descendants used up too, including any hidden or beyond the cap."
  [world reach gone]
  (let [usable (filter #(and (:consumable? %) (not (:fixed? %))) reach)
        bad (remove (set (map :id usable)) gone)
        gone? (set gone)
        shown? (set (map :id reach))
        [box left] (first (for [it reach
                                :when (gone? (:id it))
                                :let [left (remove #(gone? (:id %)) (items/within world (:id it)))]
                                :when (seq left)]
                            [it left]))]
    (cond
      (seq bad) (str "you may use up only consumable items among these: " (ids-or-none usable)
                     "; not " (str/join ", " bad))
      (some #(not (shown? (:id %))) left)
      (str "the " (:name box) " (" (:id box) ") still holds things not shown here; leave it, or name it with `on` next time")
      box (str "the " (:name box) " (" (:id box) ") still holds " (ids-or-none left)
               ": use those up too, or don't use it up"))))

(defn made-problem
  "Why the judge can't make `made`, or nil: optional from names a store,
  an :in must be a container in `reach` that isn't used up, and aspects
  need well-formed keys and values of 1 to `max-aspect` characters."
  [reach stores gone made]
  (let [boxes (filter #(and (:container %) (not ((set gone) (:id %)))) reach)
        bad-in (remove (set (map :id boxes)) (keep :in made))
        bad-value (first (for [{item-name :name :keys [aspects]} made
                               [k v] aspects
                               :when (not (<= 1 (count v) items/max-aspect))]
                           (str "made " (pr-str item-name) ": its " (name k) " is " (count v) " characters; give it 1 to " items/max-aspect)))]
    (cond
      (some #(and (:from %) (not ((set (map :id stores)) (:from %)))) made)
      (str "from must name a store in scope: " (ids-or-none stores))
      (seq bad-in) (str "in must be the id of a container among these: " (ids-or-none boxes)
                        "; not " (str/join ", " (distinct bad-in)))
      :else (or (key-problem (mapcat (comp keys :aspects) made)) bad-value))))

(defn made-entries
  "The judge's `made` entries with their aspect keys normalised, and no
  :aspects when there are none."
  [made]
  (mapv #(let [aspects (update-keys (:aspects %) a/kebab-key)]
           (cond-> (dissoc % :aspects) (seq aspects) (assoc :aspects aspects)))
        made))

(defn- absorption-problem
  "Reject invalid removals without revealing unseen container contents."
  [world reach stores gone absorbed]
  (let [by-id (into {} (map (juxt :id identity)) reach)
        store-ids (set (map :id stores))
        ids (map :thing absorbed)
        removing (set ids)]
    (cond
      (not= (count ids) (count removing)) "absorb each thing only once"
      (some (set gone) ids) "a thing cannot be both used up and absorbed"
      (some #(not (items/absorbable? (by-id %))) ids) "absorb only unfixed consumable or drawn-from things in focus or also_here"
      (some #(not (store-ids (:into %))) absorbed) (str "into must name a store in scope: " (ids-or-none stores))
      (some (fn [id] (some #(not (removing (:id %))) (items/within world id))) ids)
      "a container still holds things; absorb all its contents too, or leave it")))

(defn checked-verdict
  "Check decoded effects against this narrow scene. The steward may use up
  any absorbable candidate, but may change only candidates, never the room,
  and may only remove sleepers' aspects."
  [world args things stores steward?]
  (let [changes (decoded-changes (:changes args))
        by-id (into {} (map (juxt :id identity)) things)
        gone (vec (distinct (:used-up args)))
        absorbed (:absorbed args)
        removed (into (set gone) (map :thing absorbed))
        made (made-entries (:made args))
        fates (update-keys (:fates args) name)
        usable (if steward? (map #(assoc % :consumable? (items/absorbable? %)) things) things)
        set-on-sleeper (when steward? (first (filter #(and (:to %) (:person? (by-id (:thing %)))) changes)))]
    (if-let [problem (or (change-problem things changes)
                         (when set-on-sleeper
                           (str "a sleeper's aspects can only be removed, with the value \"\"; not "
                                (:thing set-on-sleeper) " " (name (:aspect set-on-sleeper)) " " (pr-str (:to set-on-sleeper))))
                         (use-up-problem world usable gone)
                         (absorption-problem world things stores gone absorbed)
                         (made-problem (remove #(some #{(:id %)} (map :thing absorbed)) things) stores gone made)
                         (when (some #(not ((set gone) %)) (keys fates)) "fates may name only used-up things")
                         (when (some str/blank? (vals fates)) "a fate must not be blank"))]
      {:error problem}
      (cond-> {:summary (:summary args)
               :changes (vec (for [{:keys [thing aspect to]} changes
                                   :let [from (get-in by-id [thing :aspects aspect])
                                         kind (cond (:room? (by-id thing)) :place
                                                    (:person? (by-id thing)) :person
                                                    :else :item)]
                                   :when (and (not= from to) (not (removed thing)))]
                               {kind thing :aspect aspect :from from :to to}))}
        (seq gone) (assoc :used-up gone)
        (seq fates) (assoc :fates fates)
        (seq absorbed) (assoc :absorbed (vec absorbed))
        (seq made) (assoc :made made)))))

(defn with-inner
  "`v` (a checked verdict) with the checked changes to the inner state of
  the people among `things` (marked :person?) under :inner, or the reason
  they won't do."
  [v inner-config world things answers]
  (let [{:keys [changes error]} (inner/checked-changes inner-config world (map :id (filter :person? things)) answers)]
    (cond error {:error error}
          (seq changes) (assoc v :inner changes)
          :else v)))

(defn physical-changes
  "The answers about bodies in `args` (`physical`, `impediments`, `died`)
  as [[id change] ...] for manor.mechanics.physical.changes/judged-events,
  in the order the people first come."
  [{:keys [physical impediments died]}]
  (let [entries (concat (for [p physical] [(:person p) :physical (dissoc p :person)])
                        (for [i impediments] [(:person i) :impediments (dissoc i :person)])
                        (for [d died] [(:person d) :died (or (:cause d) true)]))]
    (vec (for [id (distinct (map first entries))]
           [id (reduce (fn [change [_ k v]] (if (= :died k) (assoc change k v) (update change k (fnil conj []) v)))
                       {} (filter #(= id (first %)) entries))]))))

(defn- impediments-after
  "`id`'s impediments once `changes` ([{:key :value}]) are made."
  [world id changes]
  (reduce (fn [m {:keys [key value]}]
            (if (str/blank? value) (dissoc m (a/kebab-key key)) (assoc m (a/kebab-key key) value)))
          (or (:impediments (w/agent world id)) {}) changes))

(defn- person-problem
  "Why `change` can't be made to the judged person `id`, or nil."
  [world id {:keys [physical impediments] death :died :as change}]
  (let [after (impediments-after world id impediments)]
    (cond
      (and (w/dead? world id) (or (seq physical) death))
      "is dead: only their aspects and impediments can change, and what they carry be moved"
      (key-problem (map (comp a/kebab-key :key) impediments)) (key-problem (map (comp a/kebab-key :key) impediments))
      (> (count after) items/max-impediments)
      (str "would have " (count after) " impediments (" (str/join ", " (map name (keys after))) "); a person has at most "
           items/max-impediments ": change one they have instead, or remove one with the value \"\"")
      :else (physical-changes/problem world id change))))

(defn physical-problem
  "Why the judge can't make the physical `changes` ([[id change] ...], see
  `physical-changes`), or nil: they may name only the judged people among
  `things`, each change must be one manor.mechanics.physical.changes/problem
  allows, the dead may only have impediments, and nobody may end up with
  more than `items/max-impediments`."
  [world things changes]
  (let [judged (set (map :id (filter :person? things)))
        strays (distinct (remove judged (map first changes)))]
    (if (seq strays)
      (str "you may change the physical state only of the judged people: " (str/join ", " (sort judged))
           "; not " (str/join ", " strays))
      (first (for [[id change] changes
                   :let [p (person-problem world id change)]
                   :when p]
               (str (:name (w/agent world id)) " (" id ") " p))))))

(defn- moved-problem
  "Why an act can't move things as `moved` says, or nil: each thing once,
  held by a judged person, to a judged person or \"place\"."
  [world things moved]
  (let [judged (set (map :id (filter :person? things)))
        carried (filter #(judged (:holder %)) (items/all world))
        bad-item (first (remove #(judged (:holder (items/item world %))) (map :item moved)))
        bad-to (first (remove #(or (= "place" %) (judged %)) (map :to moved)))]
    (cond
      (not= (count moved) (count (distinct (map :item moved)))) "move each thing only once"
      bad-item (str "you may move only what the judged people carry: " (ids-or-none carried) "; not " bad-item)
      bad-to (str "to must be a judged person's id (" (str/join ", " (sort judged)) ") or \"place\"; not " bad-to))))

(defn- with-physical
  "`v` (a checked verdict) with the checked changes to bodies under
  :physical (see `physical-changes`) and, for an act, the things it moves
  under :moved (leaving out moves to whoever holds them already), or the
  reason they won't do."
  [v world things args act?]
  (let [changes (physical-changes args)
        moved (vec (remove #(= (:to %) (:holder (items/item world (:item %)))) (:moved args)))]
    (if-let [p (or (physical-problem world things changes) (when act? (moved-problem world things (:moved args))))]
      {:error p}
      (cond-> v (seq changes) (assoc :physical changes) (seq moved) (assoc :moved moved)))))

(defn- verdict [world arguments things inner-config act?]
  (let [{:keys [args error]} (a/decode-args {:args (answer-schema inner-config act?)} arguments)
        place (:id (first (filter :room? things)))
        v (when-not error (checked-verdict world args things (items/stores-for world place) false))
        v (if (or error (:error v)) v (with-physical v world things args act?))]
    (cond error {:error error}
          (or (:error v) (nil? inner-config)) v
          :else (with-inner v inner-config world things (:inner args)))))

(defn judgement
  "Read a judge response: {:verdict v :replies [...]} or {:error reason
  :replies [...]}, where :replies are the messages to send back (a reply to
  each tool call, or a reminder if it made none). Only the first `judge`
  call counts. `things` are the judged things (see `judged-things`). With
  the scenario's :inner as `inner-config`, the verdict's :inner holds the
  checked changes to the judged people's inner state. The verdict's
  :physical holds the changes to bodies (see `physical-changes`), and for
  an immediate act (a `job` with :act?) :moved the things it moved."
  ([response world things] (judgement response world things nil nil))
  ([response world things inner-config] (judgement response world things inner-config nil))
  ([response world things inner-config job]
   (adjudication/answer "judge" response #(verdict world % things inner-config (:act? job)))))

(defn- mock-put-away
  "Absorb deposited consumables whose contents can go too; clear matching
  semicolon-separated shortages, using whole words (tea isn't in meat)."
  [world job]
  (let [store (items/item world (:store job))
        deposited (filter #(= (:id store) (:in %)) (keep #(items/item world %) (:items job)))
        candidates (filter #(and (:consumable? %) (items/absorbable? %))
                           (distinct (mapcat #(cons % (items/within world (:id %))) deposited)))
        ids (set (map :id candidates))
        absorbed (filter #(every? (comp ids :id) (items/within world (:id %))) candidates)
        lacking (get-in store [:aspects :lacking])
        matches? (fn [phrase]
                   (let [pattern (re-pattern (str "(?i)\\b" (java.util.regex.Pattern/quote phrase) "\\b"))]
                     (some #(re-find pattern (:name %)) absorbed)))
        left (str/join "; " (remove matches? (str/split (or lacking "") #"; ")))]
    {:absorbed (mapv #(hash-map :thing (:id %) :into (:id store)) absorbed)
     :changes (if (and lacking (not= lacking left))
                [{:thing (:id store) :aspect "lacking" :value left}] [])}))

(defn mock-judge
  "Focused things get cleanliness just seen to, and the people named who are
  still here a state just seen to; the worker is left alone unless named,
  and unfocused pastimes are quiet. Quick put-away jobs absorb consumables
  and clear matching shortages. An immediate act changes nothing."
  [{:keys [world job stopped-early?]}]
  {:tool :judge
   :args (assoc (cond
                  (:act? job) {}
                  (:quick? job) (mock-put-away world job)
                  :else
                  {:changes (into (mapv (fn [t] {:thing (:id t) :aspect "cleanliness" :value "just seen to"})
                                        (item-work/focus-things world job))
                                  (for [id (item-work/judged-people world job) :when (some #{id} (:people job))]
                                    {:thing id :aspect "state" :value "just seen to"}))})
                :summary (if (:act? job)
                           (str (:name (w/agent world (:actor job))) " acts: " (:doing job) ".")
                           (item-work/neutral-summary world job stopped-early?)))})
