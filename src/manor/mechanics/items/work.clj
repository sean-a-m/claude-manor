(ns manor.mechanics.items.work
  "Finishing work. The engine takes the jobs that end as each round starts,
  in independent batches: for each it asks the judge (manor.mechanics.items.judge
  builds the request) about the items still in reach, checks the answer, and
  emits the result."
  (:require [manor.mechanics.inner :as inner]
            [manor.mechanics.items :as items]
            [manor.mechanics.physical :as physical]
            [manor.mechanics.physical.changes :as physical-changes]
            [manor.mechanics.relations :as relations]
            [manor.mechanics.space :as space]
            [manor.rng :as rng]
            [manor.world :as w]))

(defn place-worked?
  "Did `job` name its place among its items: work on the room itself?"
  [{:keys [items place]}]
  (boolean (some #{place} items)))

(defn minutes-of [world rounds]
  (* (max 0 rounds) (get-in world [:clock :minutes-per-round])))

(defn- job-outcome
  "How `job` stands as the round starts, or nil while it's still going on."
  [world {:keys [id actor started due quick?]}]
  (let [round (:round world)]
    (cond
      (and (not (w/active? world actor)) (not (w/dead? world actor))) {:outcome :dropped :minutes 0}
      quick? (when (<= due round) {:outcome :finished :minutes 0 :stopped-early? false})
      (not= id (:job (w/busy world actor)))
      {:outcome :stopped :minutes (minutes-of world (- round 1 started)) :stopped-early? true}
      (<= due round) {:outcome :finished :minutes (minutes-of world (- due started)) :stopped-early? false})))

(defn ending-jobs
  "The jobs that end as this round starts, in order of :due then id, each
  {:job job :outcome o :minutes m :stopped-early? bool :luck 1-100}:
    :dropped   the worker has been retired
    :stopped   the worker's occupation is no longer this job (they stopped
               after being interrupted, something replaced it, or they were
               knocked out or died): judged on the rounds before this one
    :finished  :due has come: judged on the whole time
  Call it on the world before occupations are released, so the worker of a
  job that is due still holds it."
  [world]
  (vec (for [job (items/jobs world)
             :let [o (job-outcome world job)]
             :when o]
         (assoc o :job job :luck (inc (rng/roll world [:judge (:id job)] 100))))))

(defn focus-things
  "Named things still in reach, including the place when named."
  [world {:keys [place] :as job}]
  (let [loc (when (place-worked? job) (space/location world place))]
    (into (if loc [(assoc (select-keys loc [:id :name :desc :aspects]) :room? true)] [])
          (items/in-reach world job))))

(defn judged-people
  "Ids of the people a job may change: the worker, wherever they are, then
  the people named with `on` who are still at the job's place, alive or
  dead."
  [world {:keys [actor place people]}]
  (vec (distinct (cons actor (filter #(and (or (w/active? world %) (w/dead? world %))
                                           (= place (space/location-of world %)))
                                     people)))))

(defn person-thing
  "A person as a judged thing: their :appearance is how they usually look,
  their :aspects how they differ from it now, with their physical statuses
  and impediments, and dead: true if they are (manor.mechanics.physical)."
  [world id]
  (let [a (w/agent world id)]
    (physical/with-status (assoc (select-keys a [:id :name :appearance :aspects]) :person? true) a)))

(defn- carrying
  "What `id` holds, by id and name: for an act, whose `moved` may take it."
  [world id]
  (mapv #(select-keys % [:id :name]) (items/by-name (items/held-by world id))))

(defn carried-things
  "What the worker holds that the job didn't name, with what's inside it:
  work may still use it or leave it somewhere."
  [world {:keys [actor] :as job}]
  (let [named (set (map :id (focus-things world job)))]
    (->> (items/by-name (items/held-by world actor))
         (mapcat #(cons % (items/within world (:id %))))
         (remove #(named (:id %)))
         distinct vec)))

(defn scene
  "The activity's local scene: place, named focus (including container contents),
  for work the worker's other carried things (see `carried-things`), at most
  twelve other visible items, stores in scope, and people: those present and
  the dead lying here, with the judged people (see `judged-people`) as
  person things, and the others by id and name; all with their physical
  statuses. For an act (:act?), the judged people's :carrying instead."
  [world {:keys [place act?] :as job}]
  (let [focus (focus-things world job)
        named (set (map :id focus))
        judged (set (judged-people world job))]
    {:place (assoc (select-keys (space/location world place) [:id :name :desc :aspects]) :room? true)
     :focus focus
     :carried (if act? [] (carried-things world job))
     :also-here (vec (take 12 (remove #(or (named (:id %)) (items/store? %)) (items/visible-at world place))))
     :stores (vec (items/stores-for world place))
     :people (mapv (fn [id]
                     (cond-> (if (judged id)
                               (person-thing world id)
                               (physical/with-status {:id id :name (:name (w/agent world id))} (w/agent world id)))
                       (w/busy world id) (assoc :doing (:activity (w/busy world id)))
                       (and act? (judged id)) (assoc :carrying (carrying world id))))
                   (sort (-> (space/present world place) (into (physical/dead-at world place)) (into judged))))}))

(defn judged-things
  "Everything the scene judge may change, once each: place, focus, the
  worker's other carried things, other visible items (at most twelve),
  stores in scope and the judged people. A person whose id is also a
  thing's isn't judged."
  [world job]
  (let [{:keys [place focus carried also-here stores people]} (scene world job)]
    (->> (concat [place] focus carried also-here stores (filter :person? people))
         (reduce (fn [its it] (if (some #(= (:id it) (:id %)) its) its (conj its it))) [])
         vec)))

(defn job-scope
  "Places, stores and people a job can affect, including the places of
  named focus items (a store may serve several places), the worker and the
  people named. Tagged keys avoid id collisions."
  [world {:keys [place actor people] :as job}]
  (into #{[:place place]}
        (concat (map #(vector :store (:id %)) (items/stores-for world place))
                (map #(vector :person %) (cons actor people))
                (for [it (items/in-reach world job)]
                  [:place (or (items/place-of world it)
                              (space/location-of world (:holder (items/outermost world it)))
                              (space/location-of world actor))]))))

(defn- audience
  "The people at the job's place, and the worker wherever they are."
  [world {:keys [actor place]}]
  (conj (space/present world place) actor))

(defn work-ended
  "Event: `job` ends with nothing done. `reason` is :retired (the GM sees
  it), or :no-time (the worker and people at the place see it)."
  [world job reason]
  {:type :item/work-ended :actor (:actor job)
   :visible-to (if (= :retired reason) :gm (audience world job))
   :data {:job (:id job) :place (:place job) :doing (:doing job) :reason reason}})

(defn neutral-summary
  "\"Hale finishes dusting the china cabinet.\": the summary when the judge
  gives none."
  [world {:keys [actor doing]} stopped-early?]
  (items/work-opener (:name (w/agent world actor)) doing stopped-early?))

(defn- made-items
  "New things with fresh ids. `in` wins, then explicit `held`; otherwise a
  place-bound focus leaves them here, and no place-bound focus gives them to the worker."
  [world {:keys [actor place] :as job} [first-used] made]
  (let [place-bound? (or (place-worked? job) (some #(items/place-of world %) (items/in-reach world job)))]
    (second
     (reduce (fn [[world its] {:keys [in from consumable held] :as m}]
               (let [held? (if (contains? m :held) held (not place-bound?))
                     it (items/new-item world
                                        (cond-> (assoc (select-keys m [:name :desc :aspects]) :consumable? consumable)
                                          (:owner first-used) (assoc :owner (:owner first-used))
                                          (:reference job) (assoc :candidate-id (get-in job [:reference :candidate-id]))
                                          (and from (not consumable)) (assoc :drawn-from from)
                                          in (assoc :in in)
                                          (and (not in) held?) (assoc :holder actor)
                                          (and (not in) (not held?)) (assoc :place place)))]
                 [(assoc-in world [:items (:id it)] it) (conj its it)]))
             [world []] made))))

(defn removal-data
  "Checked removals as named event data: what's gone with its fate, and
  what's absorbed with its store."
  [world {:keys [gone absorbed]}]
  (cond-> {}
    (seq gone) (assoc :gone (mapv (fn [{:keys [thing fate]}] {:item thing :name (:name (items/item world thing)) :fate fate}) gone))
    (seq absorbed) (assoc :absorbed (mapv (fn [{:keys [thing into]}]
                                            {:item thing :name (:name (items/item world thing)) :into into}) absorbed))))

(defn work-done
  "A checked activity result. All removals retain names and fates; new items
  are allocated on this world so ids remain deterministic across batches.
  :on names only people with public changes, who perceive the result even
  without sight; named context and private inner changes grant no audience."
  [world job {:keys [minutes stopped-early? luck]} verdict]
  (let [gone (keep #(items/item world (:thing %)) (:gone verdict))
        made (made-items world job gone (:made verdict))
        fetched (mapv :id (keep (fn [[entry it]] (when (:from entry) it)) (map vector (:made verdict) made)))
        on (vec (distinct (concat (keep :person (:changes verdict)) (map first (:physical verdict)))))]
    {:type :item/work-done :actor (:actor job) :visible-to (audience world job)
     :data (cond-> (merge {:job (:id job) :actor (:actor job) :place (:place job) :doing (:doing job)
                           :minutes minutes :stopped-early? stopped-early? :luck luck
                           :summary (or (:summary verdict) (neutral-summary world job stopped-early?))
                           :changes (vec (:changes verdict))}
                          (removal-data world verdict))
             (seq made) (assoc :made made)
             (seq fetched) (assoc :fetched fetched)
             (seq on) (assoc :on on))}))

(defn act-job
  "An immediate act (manor.mechanics.items.actions `act-on`) as the judge
  takes a job: `actor` acts on the people `on` with the items `with`, at
  their place, now. Its id comes from the world's event count, so it is
  deterministic."
  [world actor {:keys [on with doing]}]
  {:id (str "act-" (inc (:seq world))) :act? true :actor actor :place (space/location-of world actor)
   :items (vec (distinct with)) :people (vec (distinct on)) :doing doing :started (:round world)})

(defn act-work
  "How an act is judged: no time worked, and a luck roll as a job's."
  [world job]
  {:minutes 0 :stopped-early? false :luck (inc (rng/roll world [:judge (:id job)] 100))})

(defn acted-on
  "Event: the checked result of the act `job`, seen by everyone at the place
  (and always by the people acted on, see manor.mechanics.items): its
  summary (none if the judge failed), the aspects it changed, and the
  things it moved, each to a judged person's hands (:holder) or put down
  here (:place)."
  [world {:keys [id actor place items people doing]} {:keys [luck]} verdict]
  {:type :person/acted-on :actor actor :visible-to (into (space/here world actor) people)
   :data (cond-> {:act id :place place :on people :doing doing :luck luck
                  :changes (vec (:changes verdict))}
           (:summary verdict) (assoc :summary (:summary verdict))
           (seq items) (assoc :with items)
           (seq (:moved verdict))
           (assoc :moved (mapv (fn [{:keys [item to]}]
                                 (cond-> {:item item :from (:holder (items/item world item))}
                                   (= "place" to) (assoc :place place)
                                   (not= "place" to) (assoc :holder to)))
                               (:moved verdict))))})

(defn judge-failed
  "Event, for the operator only: why the judge's answer about `job` wasn't used."
  [job reason]
  {:type :item/judge-failed :visible-to :system :data {:job (:id job) :reason reason}})

(defn result-events
  "The events of a judged job or act: `event` (its :item/work-done or
  :person/acted-on), then the GM-only changes to inner state and relations, then the
  changes to bodies (manor.mechanics.physical.changes), with the
  interruptions they cause by the worker, and if the judgement failed, why."
  [world {:keys [id actor act?] :as job} event {:keys [verdict failure]}]
  (cond-> [event]
    (seq (:inner verdict)) (into (inner/changed-events {(if act? :act :job) id} (:inner verdict)))
    (:relations verdict) (into (relations/changed-events {(if act? :act :job) id} (:relations verdict)))
    (seq (:physical verdict)) (into (physical-changes/judged-events world (:physical verdict) actor))
    failure (conj (judge-failed job failure))))
