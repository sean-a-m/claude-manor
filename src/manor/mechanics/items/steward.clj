(ns manor.mechanics.items.steward
  "The nightly steward sees only old, loose, absorbable things at one place,
  and the people asleep there with aspects, which it may only remove. With
  inner state (the scenario's :inner, see manor.mechanics.inner) it sees
  everyone asleep there, with their inner state, and may set, change or
  clear it. Its events are reduced and narrated in manor.mechanics.items
  and manor.mechanics.inner."
  (:require [jsonista.core :as json]
            [malli.json-schema :as mjs]
            [manor.actions :as a]
            [manor.clock :as clock]
            [manor.mechanics.adjudication :as adjudication]
            [manor.mechanics.inner :as inner]
            [manor.mechanics.items :as items]
            [manor.mechanics.items.judge :as item-judge]
            [manor.mechanics.items.work :as item-work]
            [manor.mechanics.space :as space]
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

(defn asleep-since
  "The round `id` last fell asleep in, from `events` (their last
  :agent/occupied asleep), or nil."
  [events id]
  (:round (last (filter #(and (= :agent/occupied (:type %)) (= id (:actor %)) (get-in % [:data :asleep?])) events))))

(defn steward-sleepers
  "The people asleep at each place, as person things, grouped by place in
  place-id order, by id within a place. Nobody awake is among them. Without
  inner state (the scenario's :inner as `inner-config`) only those with
  aspects are; with it everyone asleep is, each with :asleep-since (see
  `asleep-since`) when `events` have it."
  ([world] (steward-sleepers world nil nil))
  ([world inner-config events]
   (into (sorted-map)
         (update-vals (group-by :location (filter #(and (:asleep? (:busy %)) (:location %) (or inner-config (seq (:aspects %))))
                                                  (sort-by :id (w/active-agents world))))
                      #(mapv (fn [{:keys [id]}]
                               (let [since (when inner-config (asleep-since events id))]
                                 (cond-> (item-work/person-thing world id) since (assoc :asleep-since since))))
                             %)))))

(defn- answer-schema
  "The steward's answer: the judge's (see item-judge/answer-schema, with
  `inner` when the scenario has inner state), without `made`."
  [inner-config]
  (into [:map] (remove #(= :made (first %)) (rest (item-judge/answer-schema inner-config)))))

(def steward-instructions
  (str "You are the simulation's nightly steward. Decide the fates of only the listed idle candidates, calling `steward`.
- Empty answers are normal. Leave anything still individually important.
- For each candidate, you may absorb it into a listed store in scope (`absorbed: [{thing, into}]`), use it up (`used-up: [id]`, with a short fate in `fates: {id: phrase}`), change its aspects (`changes: [{thing, aspect, value}]`), or leave it.
- Absorb a container only with all its contents absorbed too; use one up only with all its contents used up too. Otherwise leave it.
- Change only candidates, never the place or stores. Aspect keys are lower-case letters and hyphens, at most 24 characters; values are 1–" items/advised-aspect " characters, or empty to remove. Keep at most four aspects per thing.
- `sleepers` are people asleep here. Their `appearance` is how they usually look, and their `aspects` how they differ from it now. Remove (value \"\") each aspect that wouldn't last the night, such as hair let down or evening dress taken off, and leave the ones that would, such as a grease-stained apron. Never set or change a sleeper's aspect: only remove.
- Never make anything. A store holds everything it keeps unless its aspects say otherwise. Do not invent events. Give a short third-person summary, at most 300 characters, for the GM only."))

(defn- inner-instructions
  "The steward's lines about sleepers' inner state, for a scenario's `config`."
  [config]
  (str "## Inner state\n"
       "- `sleepers` also have an `inner` state: how they are inside, which only they know: their spirits, how their body is, what's on their mind, and anything else about them that doesn't show. Each entry says when it was set (`set_at`); `went_to_sleep` and `wakes` say when they went to sleep and when they will wake.\n"
       "- Judge how each sleeper will wake, and set, change or clear their inner state only where the night would; leave the rest. Their temperament says how they're inclined to take things; it informs your judgement but doesn't decide it.\n"
       "- Change only sleepers' inner state, in `inner: [{person, key, value}]`, apart from `changes`: the person's id, the key, and a phrase of at most " inner/advised-value " characters saying how they will wake, or \"\" to clear it. "
       (inner/key-instructions config)))

(defn instructions
  "The steward's system message: `steward-instructions`, and with inner state
  (the scenario's :inner as `inner-config`) its lines about sleepers'."
  [inner-config]
  (cond-> steward-instructions
    inner-config (str "\n\n" (inner-instructions inner-config))))

(defn- steward-tool [inner-config]
  {:type "function"
   :function {:name "steward"
              :description (str "Resolve only the listed idle candidates"
                                (when inner-config ", and judge how the sleepers will wake (`inner`)")
                                ". Never make anything.")
              :parameters (mjs/transform (answer-schema inner-config))}})

(defn- at-label
  "When `round` is, as the steward reads it: \"21:00\", \"Sun 07:00\", or
  without a clock \"round 6\"."
  [world round]
  (if-let [c (:clock world)] (clock/short-label c round (:round world)) (str "round " round)))

(defn- sleeper-json
  "A sleeper as the steward sees them: how they look, and with inner state
  (`inner-config`) their temperament, their inner state, and when they went
  to sleep and will wake."
  [world inner-config {:keys [id asleep-since] :as sleeper}]
  (let [until (:until (w/busy world id))]
    (cond-> (select-keys sleeper [:id :name :appearance :aspects])
      inner-config (merge (select-keys (w/agent world id) [:temperament])
                          {:inner (inner/judge-view inner-config world id)})
      (and inner-config asleep-since) (assoc :went_to_sleep (at-label world asleep-since))
      (and inner-config until) (assoc :wakes (at-label world until)))))

(defn steward-request
  "One place's narrow nightly request: its candidates, and the people asleep
  there (see `steward-sleepers`), with their inner state when the scenario
  has it (`inner-config`); no one awake, no private lines or other rooms."
  [world place candidates & [sleepers inner-config]]
  {:tools [(steward-tool inner-config)]
   :messages [{:role "system" :content (instructions inner-config)}
              {:role "user" :content (str "## The idle scene\n```json\n"
                                          (json/write-value-as-string
                                           {:place (select-keys (space/location world place) [:id :name :desc :aspects])
                                            :stores (mapv #(assoc (items/store-view place %) :keeps (get-in % [:store :keeps]))
                                                          (items/stores-for world place))
                                            :candidates (item-judge/judged-json candidates)
                                            :sleepers (mapv #(sleeper-json world inner-config %) sleepers)} item-judge/pretty)
                                          "\n```\n\nCall `steward` now.")}]})

(defn stewardship
  "Check the steward against only its candidates, the sleepers (whose
  aspects it may only remove) and this place's stores. With inner state
  (`inner-config`), the verdict's :inner holds the checked changes to the
  sleepers' inner state, which only they may have."
  [response world place candidates & [sleepers inner-config]]
  (adjudication/answer "steward" response
                       (fn [arguments]
                         (let [{:keys [args error]} (a/decode-args {:args (answer-schema inner-config)} arguments)
                               things (into (vec candidates) sleepers)
                               v (when-not error (item-judge/checked-verdict world args things (items/stores-for world place) true))]
                           (cond error {:error error}
                                 (or (:error v) (nil? inner-config)) v
                                 :else (item-judge/with-inner v inner-config world things (:inner args)))))))

(defn stewarded
  "A checked nightly result, GM-only; no character sees it happen. It holds
  only the outward changes: inner ones are their own events."
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
