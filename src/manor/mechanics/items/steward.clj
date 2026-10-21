(ns manor.mechanics.items.steward
  "The nightly steward sees only old, loose, absorbable things at one place,
  and the people asleep there with aspects, which it may only remove. Its
  events are reduced and narrated in manor.mechanics.items."
  (:require [jsonista.core :as json]
            [malli.json-schema :as mjs]
            [manor.actions :as a]
            [manor.clock :as clock]
            [manor.mechanics.adjudication :as adjudication]
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

(defn steward-sleepers
  "The people asleep at each place who have aspects, as person things,
  grouped by place in place-id order, by id within a place. Nobody awake is
  among them."
  [world]
  (into (sorted-map)
        (update-vals (group-by :location (filter #(and (:asleep? (:busy %)) (seq (:aspects %)) (:location %))
                                                 (sort-by :id (w/active-agents world))))
                     #(mapv (fn [a] (item-work/person-thing world (:id a))) %))))

(def ^:private steward-schema
  (into [:map] (remove #(= :made (first %)) (rest item-judge/judge-schema))))

(def steward-instructions
  (str "You are the simulation's nightly steward. Decide the fates of only the listed idle candidates, calling `steward`.
- Empty answers are normal. Leave anything still individually important.
- For each candidate, you may absorb it into a listed store in scope (`absorbed: [{thing, into}]`), use it up (`used-up: [id]`, with a short fate in `fates: {id: phrase}`), change its aspects (`changes: [{thing, aspect, value}]`), or leave it.
- Absorb a container only with all its contents absorbed too; use one up only with all its contents used up too. Otherwise leave it.
- Change only candidates, never the place or stores. Aspect keys are lower-case letters and hyphens, at most 24 characters; values are 1–" items/advised-aspect " characters, or empty to remove. Keep at most four aspects per thing.
- `sleepers` are people asleep here. Their `appearance` is how they usually look, and their `aspects` how they differ from it now. Remove (value \"\") each aspect that wouldn't last the night, such as hair let down or evening dress taken off, and leave the ones that would, such as a grease-stained apron. Never set or change a sleeper's aspect: only remove.
- Never make anything. A store holds everything it keeps unless its aspects say otherwise. Do not invent events. Give a short third-person summary, at most 300 characters, for the GM only."))

(defn steward-request
  "One place's narrow nightly request: its candidates, and the people asleep
  there with aspects (see `steward-sleepers`); no one awake, no private lines
  or other rooms."
  [world place candidates & [sleepers]]
  {:tools [{:type "function" :function {:name "steward" :description "Resolve only the listed idle candidates. Never make anything."
                                        :parameters (mjs/transform steward-schema)}}]
   :messages [{:role "system" :content steward-instructions}
              {:role "user" :content (str "## The idle scene\n```json\n"
                                          (json/write-value-as-string
                                           {:place (select-keys (space/location world place) [:id :name :desc :aspects])
                                            :stores (mapv #(assoc (items/store-view place %) :keeps (get-in % [:store :keeps]))
                                                          (items/stores-for world place))
                                            :candidates (item-judge/judged-json candidates)
                                            :sleepers (mapv #(select-keys % [:id :name :appearance :aspects]) sleepers)} item-judge/pretty)
                                          "\n```\n\nCall `steward` now.")}]})

(defn stewardship
  "Check the steward against only its candidates, the sleepers (whose
  aspects it may only remove) and this place's stores."
  [response world place candidates & [sleepers]]
  (adjudication/answer "steward" response
                       (fn [arguments]
                         (let [{:keys [args error]} (a/decode-args {:args steward-schema} arguments)]
                           (if error {:error error}
                               (item-judge/checked-verdict world args (into (vec candidates) sleepers) (items/stores-for world place) true))))))

(defn stewarded
  "A checked nightly result, GM-only; no character sees it happen."
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
