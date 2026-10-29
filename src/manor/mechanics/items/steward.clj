(ns manor.mechanics.items.steward
  "The nightly steward sees only old, loose, absorbable things at one place,
  and the people asleep or hibernating there with aspects, which it may
  only remove. With inner state (the scenario's :inner, see
  manor.mechanics.inner) it sees everyone asleep or hibernating there, with
  their inner state, and may set, change or clear it. It also sees everyone
  living there with physical statuses or impediments (manor.mechanics.physical),
  asleep or not, and may clear or ease them, never set or worsen them. Its
  events are reduced and narrated in manor.mechanics.items,
  manor.mechanics.inner and manor.mechanics.physical."
  (:require [jsonista.core :as json]
            [malli.json-schema :as mjs]
            [manor.actions :as a]
            [manor.clock :as clock]
            [manor.mechanics.adjudication :as adjudication]
            [manor.mechanics.inner :as inner]
            [manor.mechanics.items :as items]
            [manor.mechanics.items.judge :as item-judge]
            [manor.mechanics.items.work :as item-work]
            [manor.mechanics.physical :as physical]
            [manor.mechanics.physical.changes :as physical-changes]
            [manor.mechanics.space :as space]
            [manor.prompts :as prompts]
            [manor.world :as w]))

(defn steward-day
  "The scheduled day due now, if its :at has been reached since the run's
  clock start and hasn't run yet. An already-past time on a partial first
  day is skipped; starting exactly at :at runs immediately."
  [world {:keys [at]}]
  (when (and at (:clock world))
    (let [now (clock/time-of (:clock world) (:round world))
          time (java.time.LocalTime/parse at)
          today (.atTime (.toLocalDate now) time)
          target (if (.isAfter today now) (.minusDays today 1) today)
          day (str (.toLocalDate target))]
      (when (and (not (.isBefore target (clock/time-of (:clock world) 1)))
                 (not= day (:items-steward-day world)))
        day))))

(defn steward-candidates
  "Old, visible, uncarried absorbable things, grouped by place in place-id
  order, oldest first (id breaks ties), capped per place per night."
  [world {:keys [idle-hours max-per-call]}]
  (into (sorted-map)
        (keep (fn [place]
                (let [candidates (->> (items/visible-at world place)
                                      ;; delivered goods are left alone for now (deliveries design)
                                      (filter #(and (items/absorbable? %) (not (:order-id %))
                                                    (>= (item-work/minutes-of world (- (:round world) (max 1 (or (:touched %) 0))))
                                                        (* 60 idle-hours))))
                                      (sort-by (juxt #(or (:touched %) 0) :id))
                                      (take max-per-call)
                                      vec)]
                  (when (seq candidates) [place candidates]))))
        (sort (keys (get-in world [:space :locations])))))

(defn occupied-since
  "The round `id` began what occupies them now, from `events` (their last
  :agent/occupied: falling asleep, or a routine's activity), or nil."
  [events id]
  (:round (last (filter #(and (= :agent/occupied (:type %)) (= id (:actor %))) events))))

(defn- overnight?
  "Is agent `a` out of play for the night: asleep, or hibernating (kept to
  their routine with no model calls), and not roused?"
  [{:keys [busy]}]
  (and (or (:asleep? busy) (:hibernating? busy)) (not (:interrupted-by busy))))

(defn- hindered?
  "Has agent `a` physical statuses or impediments?"
  [a]
  (boolean (or (seq (:physical a)) (seq (:impediments a)))))

(defn steward-sleepers
  "The people asleep or hibernating at each place, as person things,
  grouped by place in place-id order, by id within a place. Nobody in play
  is among them. Without inner state (the scenario's :inner as
  `inner-config`) only those with aspects, statuses or impediments are; with
  it everyone is, each with :since (see `occupied-since`) when `events` have
  it."
  ([world] (steward-sleepers world nil nil))
  ([world inner-config events]
   (into (sorted-map)
         (update-vals (group-by :location (filter #(and (overnight? %) (:location %)
                                                        (or inner-config (seq (:aspects %)) (hindered? %)))
                                                  (sort-by :id (w/active-agents world))))
                      #(mapv (fn [{:keys [id]}]
                               (let [since (when inner-config (occupied-since events id))]
                                 (cond-> (item-work/person-thing world id) since (assoc :since since))))
                             %)))))

(defn steward-hindered
  "The people with physical statuses or impediments at each place who aren't
  asleep or hibernating (awake, roused or unconscious), as person things,
  grouped by place in place-id order, by id within a place. The dead are
  never among them."
  [world]
  (into (sorted-map)
        (update-vals (group-by :location (filter #(and (not (overnight? %)) (:location %) (hindered? %))
                                                 (sort-by :id (w/active-agents world))))
                     #(mapv (fn [{:keys [id]}] (item-work/person-thing world id)) %))))

(def eased-entry
  "The steward's changes to faculties: each cleared (normal) or eased from
  disabled to impeded, with an optional new cause."
  [:physical {:optional true}
   [:vector {:max item-judge/max-physical}
    [:map
     [:person :string]
     [:faculty (into [:enum] (keys physical/faculties))]
     [:level [:enum :normal :impeded]]
     [:cause {:optional true} [:string {:min 1 :max physical/max-cause :json-schema/maxLength items/advised-aspect}]]]]])

(defn- answer-schema
  "The steward's answer: the judge's (see item-judge/answer-schema, with
  `inner` when the scenario has inner state), without `made` or deaths,
  and with faculties only cleared or eased (`eased-entry`)."
  [inner-config]
  (let [entries (remove #(#{:made :died :physical} (first %)) (rest (item-judge/answer-schema inner-config)))]
    (into [:map] (concat (butlast entries) [eased-entry (last entries)]))))

(defn steward-instructions
  "The steward's instructions, without inner state."
  [world]
  (prompts/render world "judges/steward.md" {:advised-aspect items/advised-aspect}))

(defn- inner-instructions
  "The steward's lines about sleepers' inner state, for a scenario's `config`."
  [world config]
  (prompts/render world "judges/steward-inner.md"
                  {:advised-value inner/advised-value :keys (inner/key-instructions world config)}))

(defn instructions
  "The steward's system message: `steward-instructions`, and with inner state
  (the scenario's :inner as `inner-config`) its lines about sleepers'."
  [world inner-config]
  (cond-> (steward-instructions world)
    inner-config (str "\n\n" (inner-instructions world inner-config))))

(defn- steward-tool [world inner-config]
  {:type "function"
   :function {:name "steward"
              :description (prompts/render world "judges/tools/steward.md"
                                           {:inner (if inner-config (prompts/render world "judges/tools/steward-inner.md" {}) "")})
              :parameters (mjs/transform (answer-schema inner-config))}})

(defn- at-label
  "When `round` is, as the steward reads it: \"21:00\", \"Sun 07:00\", or
  without a clock \"round 6\"."
  [world round]
  (if-let [c (:clock world)] (clock/short-label c round (:round world)) (str "round " round)))

(defn- candidates-json
  "The candidates as the judge sees them (item-judge/judged-json), each
  shown as waiting (see items/waiting?) with whom for and since when:
  \"for Mrs Thorne since Mon 11:00\"."
  [world candidates]
  (mapv (fn [json it]
          (let [{who :for :keys [since]} (:waiting it)]
            (cond-> json
              (items/waiting? world it)
              (assoc :waiting (str "for " (or (:name (w/agent world who)) who) " since " (at-label world since))))))
        (item-judge/judged-json candidates)
        candidates))

(defn- night-json
  "How a sleeper spends the night, for the steward: when they went to sleep
  and will wake, or for someone hibernating, what their routine has them
  doing and since when."
  [world {:keys [id since]}]
  (let [{:keys [activity until hibernating?]} (w/busy world id)]
    (if hibernating?
      (cond-> {:doing activity} since (assoc :since (at-label world since)))
      (cond-> {} since (assoc :went_to_sleep (at-label world since)) until (assoc :wakes (at-label world until))))))

(defn- sleeper-json
  "A sleeper as the steward sees them: how they look, and with inner state
  (`inner-config`) their temperament, their inner state, and how they spend
  the night (see `night-json`)."
  [world inner-config {:keys [id] :as sleeper}]
  (cond-> (select-keys sleeper [:id :name :appearance :aspects :physical :impediments])
    (:physical sleeper) (assoc :physical (physical/judge-view world id))
    inner-config (merge (select-keys (w/agent world id) [:temperament])
                        {:inner (inner/judge-view inner-config world id)}
                        (night-json world sleeper))))

(defn steward-request
  "One place's narrow nightly request: its candidates, the people asleep
  or hibernating there (see `steward-sleepers`), with their inner state when
  the scenario has it (`inner-config`), and the others there with statuses
  or impediments (`hindered`, see `steward-hindered`); no private lines or
  other rooms."
  [world place candidates & [sleepers inner-config hindered]]
  {:tools [(steward-tool world inner-config)]
   :messages [{:role "system" :content (instructions world inner-config)}
              {:role "user" :content (str "## The idle scene\n```json\n"
                                          (json/write-value-as-string
                                           {:place (select-keys (space/location world place) [:id :name :desc :aspects])
                                            :stores (mapv #(assoc (items/store-view place %) :keeps (get-in % [:store :keeps]))
                                                          (items/stores-for world place))
                                            :candidates (candidates-json world candidates)
                                            :sleepers (mapv #(sleeper-json world inner-config %) sleepers)
                                            :hindered (mapv #(cond-> (select-keys % [:id :name :appearance :physical :impediments])
                                                               (:physical %) (assoc :physical (physical/judge-view world (:id %))))
                                                            hindered)}
                                           item-judge/pretty)
                                          "\n```\n\nCall `steward` now.")}]})

(defn- easing-problem
  "Why `id`'s `change` (see manor.mechanics.physical.changes) isn't only
  clearing or easing, or nil: each faculty set is one they have, made
  normal or eased from disabled to impeded, and each impediment one they
  have."
  [world id {:keys [physical impediments]}]
  (or (first (for [{:keys [faculty level]} physical
                   :let [now (:level (physical/entry world id faculty))]
                   :when (not (or (and now (= :normal level)) (and (= :disabled now) (= :impeded level))))]
               (str "you may only clear a faculty someone has hindered (level normal) or ease a disabled one to impeded; "
                    (:name (w/agent world id)) "'s " (name faculty) " is " (if now (name now) "normal"))))
      (first (for [{:keys [key]} impediments
                   :let [k (a/kebab-key key)]
                   :when (not (contains? (:impediments (w/agent world id)) k))]
               (str "you may only clear or ease impediments someone has; " (:name (w/agent world id)) " has no "
                    (name k) " impediment")))))

(defn- with-eased
  "`v` (a checked verdict) with the steward's checked changes to bodies
  under :physical, or the reason they won't do: only the `people`'s, and
  each clears or eases."
  [v world people args]
  (let [changes (item-judge/physical-changes args)]
    (if-let [p (or (item-judge/physical-problem world people changes)
                   (first (keep (fn [[id change]] (easing-problem world id change)) changes)))]
      {:error p}
      (cond-> v (seq changes) (assoc :physical changes)))))

(defn stewardship
  "Check the steward against only its candidates, the sleepers (whose
  aspects it may only remove), the hindered (whose aspects it leaves
  alone) and this place's stores. Statuses and impediments of sleepers and
  the hindered may only be cleared or eased: the verdict's :physical holds
  them (see item-judge/physical-changes). With inner state
  (`inner-config`), the verdict's :inner holds the checked changes to the
  sleepers' inner state, which only they may have."
  [response world place candidates & [sleepers inner-config hindered]]
  (adjudication/answer "steward" response
                       (fn [arguments]
                         (let [{:keys [args error]} (a/decode-args {:args (answer-schema inner-config)} arguments)
                               things (into (vec candidates) sleepers)
                               v (when-not error (item-judge/checked-verdict world args things (items/stores-for world place) true))
                               v (if (or error (:error v)) v (with-eased v world (into (vec sleepers) hindered) args))]
                           (cond error {:error error}
                                 (or (:error v) (nil? inner-config)) v
                                 :else (item-judge/with-inner v inner-config world things (:inner args)))))))

(defn eased-events
  "The steward's checked changes to bodies ([[id change] ...]) as their
  events, GM-only as the rest of its night is, and interrupting nobody: the
  night passing doesn't wake anyone."
  [world changes]
  (second (reduce (fn [[world evs] [id {:keys [physical impediments]}]]
                    (let [more (cond-> []
                                 (seq physical) (conj (physical-changes/physical-changed world id physical))
                                 (seq impediments) (conj (physical-changes/impediments-changed world id impediments)))
                          more (mapv #(assoc % :visible-to :gm) more)]
                      [(reduce w/apply-event world more) (into evs more)]))
                  [world []] changes)))

(defn stewarded
  "A checked nightly result, GM-only; no character sees it happen. It holds
  only item and aspect changes: inner and physical ones are their own events."
  [world place verdict]
  {:type :items/stewarded :visible-to :gm
   :data (merge {:place place :changes (vec (:changes verdict))
                 :summary (or (:summary verdict) "Everything was left as it was.")}
                (item-work/removal-data world "the nightly tidying" verdict))})

(defn mock-steward
  "Put candidates into the first store in scope, except containers whose
  descendants aren't all candidates; with no store leave everything alone."
  [{:keys [world place candidates]}]
  (let [store (first (items/stores-for world place))
        ids (set (map :id candidates))
        can-absorb? (fn [it] (and (items/absorbable? it)
                                  (every? #(and (ids (:id %)) (items/absorbable? %)) (items/within world (:id it)))))
        absorbed (when store (for [it candidates :when (can-absorb? it)] {:thing (:id it) :into (:id store)}))]
    {:tool :steward :args {:absorbed (vec absorbed) :summary (if (seq absorbed) "Idle things were put away." "Everything was left as it was.")}}))
