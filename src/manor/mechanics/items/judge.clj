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
            [manor.mechanics.relations :as relations]
            [manor.mechanics.space :as space]
            [manor.prompts :as prompts]
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

(def aspect-value
  "An aspect's new value: a phrase, or \"\" to remove it."
  [:string {:max items/max-aspect :json-schema/maxLength items/advised-aspect}])

(def taken-entry
  "Where a thing came from when it was only an aspect: that aspect of a
  judged thing, and its new value."
  [:taken {:optional true} [:map [:thing :string] [:aspect :string] [:value aspect-value]]])

(defn gone-entry
  "Things that stop being separate things, each with its fate. With
  `hosts?`, one may be left `on` a judged thing, kept as one of its aspects."
  [hosts?]
  [:gone {:optional true}
   [:vector (into [:map [:thing :string] [:fate [:string {:min 1 :max items/max-aspect :json-schema/maxLength items/advised-aspect}]]]
                  (when hosts? [[:on {:optional true} :string] [:aspect {:optional true} :string] [:value {:optional true} aspect-value]]))]])

(def judge-schema
  [:map
   [:changes {:optional true} [:vector [:map [:thing :string] [:aspect :string] [:value aspect-value]]]]
   (gone-entry true)
   [:absorbed {:optional true} [:vector [:map [:thing :string] [:into :string]]]]
   [:made {:optional true} [:vector {:max items/max-made}
                            [:map
                             [:name [:string {:min 1 :max items/max-name}]]
                             [:desc [:string {:min 1 :max items/max-desc}]]
                             ;; value lengths are checked by `made-problem`: decoding drops map-of entries that don't fit
                             [:aspects {:optional true} [:map-of {:max items/max-aspects} :keyword :string]]
                             [:consumable :boolean]
                             [:from {:optional true} :string]
                             [:held {:optional true} :boolean]
                             [:in {:optional true} :string]
                             taken-entry]]]
   [:summary [:string {:min 1 :max items/max-summary}]]])

(def ^:private not-in-acts
  "What an immediate act can't do: make things gone, absorb or make them."
  #{:gone :absorbed :made})

(defn answer-schema
  "The judge's answer: `judge-schema` with `physical-entries` before the
  summary, and with inner state (the scenario's :inner, see
  manor.mechanics.inner) an `inner` list beside `changes`, and with
  relations (the scenario's :relations, see manor.mechanics.relations) a
  `relations` list after it. For an immediate act (`act?`), nothing used
  up, absorbed or made, and `moved` instead. The steward's schema comes
  from it, without relations."
  ([inner-config] (answer-schema inner-config false))
  ([inner-config act?] (answer-schema inner-config act? nil))
  ([inner-config act? relations-config]
   (let [[m changes & more] judge-schema
         summary (last more)
         more (cond->> (butlast more) act? (remove #(not-in-acts (first %))))]
     (into [m changes] (concat (when inner-config [inner/answer-entry])
                               (when relations-config [relations/answer-entry])
                               more physical-entries
                               (when act? [moved-entry]) [summary])))))

(defn- judge-tool [world inner-config act? relations-config]
  {:type "function"
   :function {:name "judge"
              :description (prompts/render world "judges/tools/judge.md"
                                           {:activity (if act? "act" "work")
                                            :advised-aspect items/advised-aspect :max-summary items/max-summary
                                            :inner (if inner-config (str (prompts/render world "judges/tools/judge-inner.md" {}) " ") "")
                                            :relations (relations/tool-text world relations-config)
                                            :effects (if act?
                                                       (prompts/render world "judges/tools/judge-act.md" {})
                                                       (prompts/render world "judges/tools/judge-work.md" {:max-made items/max-made}))})
              :parameters (mjs/transform (answer-schema inner-config act? relations-config))}})

(defn judge-instructions
  "The scene judge's instructions, before the suggested aspect keys, inner
  state and the setting."
  [world]
  (prompts/render world "judges/scene.md"
                  {:advised-aspect items/advised-aspect :max-aspects items/max-aspects
                   :max-made items/max-made :max-impediments items/max-impediments
                   :max-summary items/max-summary}))

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
  shown, and the judged people's temperament, persona and situation too.
  With relations (`relations-config`), each judged person's feelings toward
  the other judged people."
  [world inner-config relations-config people]
  (let [judged (map :id (filter :person? people))]
    (mapv (fn [{:keys [id person?] :as p}]
            (cond-> (select-keys p (if person?
                                     [:id :name :appearance :aspects :physical :impediments :dead :doing :carrying]
                                     [:name :physical :impediments :dead :doing]))
              (:physical p) (assoc :physical (physical/judge-view world id))
              (and inner-config person?) (merge (select-keys (w/agent world id) [:temperament :persona :situation]))
              inner-config (assoc :inner (inner/judge-view inner-config world id))
              (and relations-config person?) (assoc :feelings_toward (relations/judge-view world id judged))
              person? (assoc :person true)))
          people)))

(defn judge-request
  "The scene judge's request, including only the worker's shared recent lines
  supplied by the engine through prompt/scene-recent, and the worker's
  order books and pending orders from the scenario's :items :orders (a map
  of scene keys, see deliveries/judge-orders), if any."
  [scenario world job {:keys [minutes stopped-early? luck recent]}]
  (let [act? (:act? job)
        worker (w/agent world (:actor job))
        brief (when-let [f (get-in scenario [:items :judge-brief])] (f world))
        suggested (get-in scenario [:items :aspect-keys])
        inner-config (:inner scenario)
        relations-config (:relations scenario)
        {:keys [place focus carried also-here stores people]} (item-work/scene world job)
        scene-json (cond-> {:place (dissoc place :room?)
                            :focus (judged-json focus)}
                     (seq carried) (assoc :carried (judged-json carried)))
        scene-json (assoc scene-json
                          :also_here (judged-json also-here)
                          :stores (mapv #(items/store-view (:place job) %) stores)
                          :people (people-json world inner-config relations-config people) :recent (vec (take-last 10 recent)))
        orders (when-let [f (get-in scenario [:items :orders])] (f world (:actor job)))
        scene-json (cond-> scene-json
                     (:reference job) (assoc :reference (:reference job))
                     (seq orders) (merge orders))]
    {:tools [(judge-tool world inner-config act? relations-config)]
     :messages
     [{:role "system"
       :content (str (judge-instructions world)
                     (when (seq suggested)
                       (str "\n\n## Suggested aspect keys\n"
                            (str/join "\n" (for [[k gloss] (sort-by key suggested)] (str "- " (name k) ": " gloss)))))
                     (when inner-config (str "\n\n" (inner/instructions world inner-config)))
                     (when relations-config (str "\n\n" (relations/instructions world)))
                     (when-not (str/blank? brief) (str "\n\n## The setting\n" brief)))}
      {:role "user"
       :content (str "# The activity\n\n"
                     "- Time: " (if-let [c (:clock world)] (clock/world-label world (:round world)) (str "round " (:round world))) "\n"
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

(defn- contents-problem
  "Why the things in `gone` can't go as a batch, or nil: a container goes
  only with everything inside it, including things not shown in `reach`."
  [world reach gone]
  (let [gone? (set gone)
        shown? (set (map :id reach))
        [box left] (first (for [it reach
                                :when (gone? (:id it))
                                :let [left (remove #(gone? (:id %)) (items/within world (:id it)))]
                                :when (seq left)]
                            [it left]))]
    (cond
      (some #(not (shown? (:id %))) left)
      (str "the " (:name box) " (" (:id box) ") still holds things not shown here; leave it, or name it with `on` next time")
      box (str "the " (:name box) " (" (:id box) ") still holds " (ids-or-none left)
               ": say what became of those too, or leave it"))))

(defn- with-job?
  "Is `it` with the job: lying at its place, or carried by its worker while
  they're there?"
  [world {:keys [place actor]} it]
  (let [o (items/outermost world it)]
    (or (= place (:place o))
        (and (= actor (:holder o)) (= place (space/location-of world actor))))))

(defn- left-on-problem
  "Why the thing `it` can't be left on something, kept only as an aspect,
  or nil: it must be movable, not one a mechanic tracks, and with the job."
  [world job it]
  (cond
    (or (:fixed? it) (items/store? it)) (str "the " (:name it) " (" (:id it) ") is fixed in place; leave it as it is and change its aspects instead")
    (items/identity-bound? it) (str "the " (:name it) " (" (:id it) ") must stay a separate thing; leave it as it is")
    (and job (not (with-job? world job it))) (str "the " (:name it) " (" (:id it) ") isn't here, so it can't be left on anything here")))

(defn- gone-problem
  "Why the judge's `gone` entries won't do, or nil. Each names a thing in
  the scene once. One without `on` is used up: only `usable` things (the
  consumables, or for the steward its candidates). One with `on` is left on
  a thing that stays, given with its aspect and value (see
  `left-on-problem`, with the `job` when there is one). A container goes
  only with all it holds."
  [world things usable steward? job entries]
  (let [by-id (into {} (map (juxt :id identity)) things)
        items-here (filter #(and (not (:room? %)) (not (:person? %)) (items/item world (:id %))) things)
        ids (map :thing entries)
        gone (set ids)
        usable? (set (map :id (filter #(and (:consumable? %) (not (:fixed? %))) usable)))
        hosted (filter #(some % [:on :aspect :value]) entries)
        stays (remove (comp gone :id) things)]
    (cond
      (not= (count ids) (count gone)) "say what became of each thing only once"
      (seq (remove (set (map :id items-here)) ids))
      (str "gone may name only these things: " (ids-or-none items-here) "; not " (str/join ", " (remove (set (map :id items-here)) ids)))
      :else
      (or (first (for [{:keys [thing]} (remove (set hosted) entries) :when (not (usable? thing))]
                   (cond
                     steward? (str "you may use up only these: " (ids-or-none (filter (comp usable? :id) things)) "; not " thing)
                     (or (:fixed? (by-id thing)) (items/store? (by-id thing))) (left-on-problem world nil (by-id thing))
                     :else
                     (str "the " (:name (by-id thing)) " (" thing ") isn't consumable, so it can't be used up: if it's now part of "
                          "something here, give `on`, `aspect` and `value`; otherwise leave it as it is"))))
          (first (for [{:keys [on aspect value]} hosted
                       :when (not (and on aspect (not (str/blank? value))))]
                   "to leave a thing on something, give `on` (its id), `aspect` (the key) and `value` (the phrase there that records it)"))
          (first (for [{:keys [thing on]} hosted :when (not (some #(= on (:id %)) stays))]
                   (str "on must name the place, a thing or a judged person that stays: " (ids-or-none stays) "; not " on)))
          (first (keep #(left-on-problem world job (by-id (:thing %))) hosted))
          (contents-problem world things (vec gone))))))

(defn- taken-problem
  "Why the `taken` of things made won't do, or nil: each names a thing that
  stays and an aspect it has."
  [things removed made]
  (let [by-id (into {} (map (juxt :id identity)) things)
        stays (remove (comp removed :id) things)]
    (first (for [{:keys [taken]} made
                 :when taken
                 :let [{:keys [thing aspect]} taken
                       it (by-id thing)]]
             (cond
               (or (nil? it) (removed thing))
               (str "taken must name the place, a thing or a judged person that stays: " (ids-or-none stays) "; not " thing)
               (not (contains? (:aspects it) (a/kebab-key aspect)))
               (str "taken must name an aspect the " (:name it) " (" thing ") has: "
                    (if (seq (:aspects it)) (str/join ", " (map name (keys (:aspects it)))) "it has none")
                    "; not " aspect))))))

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
      (some (set gone) ids) "a thing cannot be both gone and absorbed"
      (some #(not (items/absorbable? (by-id %))) ids) "absorb only unfixed consumable or drawn-from things in focus or also_here"
      (some #(not (store-ids (:into %))) absorbed) (str "into must name a store in scope: " (ids-or-none stores))
      (some (fn [id] (some #(not (removing (:id %))) (items/within world id))) ids)
      "a container still holds things; absorb all its contents too, or leave it")))

(defn checked-verdict
  "Check decoded effects against this narrow scene. Things left on others
  (`gone` with `on`) and things made from an aspect (`taken`) become changes
  to those aspects, checked with the rest. The steward (no `job`) may use up
  any absorbable candidate, but may change only candidates, never the room,
  and may only remove sleepers' aspects."
  ([world args things stores steward?] (checked-verdict world args things stores steward? nil))
  ([world args things stores steward? job]
   (let [entries (vec (:gone args))
         made (made-entries (:made args))
         changes (decoded-changes (concat (:changes args)
                                          (keep :taken made)
                                          (for [{:keys [on aspect value]} entries :when (and on aspect)]
                                            {:thing on :aspect aspect :value value})))
         by-id (into {} (map (juxt :id identity)) things)
         gone (mapv :thing entries)
         absorbed (:absorbed args)
         removed (into (set gone) (map :thing absorbed))
         usable (if steward? (map #(assoc % :consumable? (items/absorbable? %)) things) things)
         set-on-sleeper (when steward? (first (filter #(and (:to %) (:person? (by-id (:thing %)))) changes)))]
     (if-let [problem (or (gone-problem world things usable steward? job entries)
                          (change-problem things changes)
                          (when set-on-sleeper
                            (str "a sleeper's aspects can only be removed, with the value \"\"; not "
                                 (:thing set-on-sleeper) " " (name (:aspect set-on-sleeper)) " " (pr-str (:to set-on-sleeper))))
                          (absorption-problem world things stores gone absorbed)
                          (taken-problem things removed made)
                          (made-problem (remove #(some #{(:id %)} (map :thing absorbed)) things) stores gone made))]
       {:error problem}
       (cond-> {:summary (:summary args)
                :changes (vec (for [{:keys [thing aspect to]} changes
                                    :let [from (get-in by-id [thing :aspects aspect])
                                          kind (cond (:room? (by-id thing)) :place
                                                     (:person? (by-id thing)) :person
                                                     :else :item)]
                                    :when (and (not= from to) (not (removed thing)))]
                                {kind thing :aspect aspect :from from :to to}))}
         (seq entries) (assoc :gone (mapv #(select-keys % [:thing :fate]) entries))
         (seq absorbed) (assoc :absorbed (vec absorbed))
         (seq made) (assoc :made made))))))

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

(defn- with-relations
  "`v` (a checked verdict) with the checked relations entries among the
  people in `things` (marked :person?) under :relations, or the reason they
  won't do."
  [v relations-config world things answers]
  (let [checked (relations/checked-entries relations-config world (map :id (filter :person? things)) answers)]
    (if (:error checked) checked (merge v checked))))

(defn- verdict [world arguments things inner-config job relations-config]
  (let [act? (:act? job)
        {:keys [args error]} (a/decode-args {:args (answer-schema inner-config act? relations-config)} arguments)
        place (:id (first (filter :room? things)))
        v (when-not error (checked-verdict world args things (items/stores-for world place) false job))
        v (if (or error (:error v)) v (with-physical v world things args act?))
        v (if (or error (:error v) (nil? inner-config)) v (with-inner v inner-config world things (:inner args)))]
    (cond error {:error error}
          (or (:error v) (nil? relations-config)) v
          :else (with-relations v relations-config world things (:relations args)))))

(defn judgement
  "Read a judge response: {:verdict v :replies [...]} or {:error reason
  :replies [...]}, where :replies are the messages to send back (a reply to
  each tool call, or a reminder if it made none). Only the first `judge`
  call counts. `things` are the judged things (see `judged-things`). With
  the scenario's :inner as `inner-config`, the verdict's :inner holds the
  checked changes to the judged people's inner state, and with the
  scenario's :relations as `relations-config` its :relations the entries
  added among them ({:keep :entries}, see relations/checked-entries). The
  verdict's :physical holds the changes to bodies (see `physical-changes`),
  and for an immediate act (a `job` with :act?) :moved the things it moved."
  ([response world things] (judgement response world things nil nil))
  ([response world things inner-config] (judgement response world things inner-config nil))
  ([response world things inner-config job] (judgement response world things inner-config job nil))
  ([response world things inner-config job relations-config]
   (adjudication/answer "judge" response #(verdict world % things inner-config job relations-config))))

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
