(ns manor.mechanics.items.work
  "Finishing work. The engine takes the jobs that end as each round starts,
  in independent batches: for each it asks the judge (manor.mechanics.items.judge
  builds the request) about the items still in reach, checks the answer, and
  emits the result."
  (:require [manor.mechanics.items :as items]
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
      (not (w/active? world actor)) {:outcome :dropped :minutes 0}
      quick? (when (<= due round) {:outcome :finished :minutes 0 :stopped-early? false})
      (not= id (:job (w/busy world actor)))
      {:outcome :stopped :minutes (minutes-of world (- round 1 started)) :stopped-early? true}
      (<= due round) {:outcome :finished :minutes (minutes-of world (- due started)) :stopped-early? false})))

(defn ending-jobs
  "The jobs that end as this round starts, in order of :due then id, each
  {:job job :outcome o :minutes m :stopped-early? bool :luck 1-100}:
    :dropped   the worker has been retired
    :stopped   the worker's occupation is no longer this job (they stopped
               after being interrupted, or something replaced it): judged on
               the rounds before this one
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
  the people named with `on` who are still at the job's place."
  [world {:keys [actor place people]}]
  (vec (distinct (cons actor (filter #(and (w/active? world %) (= place (space/location-of world %))) people)))))

(defn person-thing
  "A person as a judged thing: their :appearance is how they usually look,
  their :aspects how they differ from it now."
  [world id]
  (assoc (select-keys (w/agent world id) [:id :name :appearance :aspects]) :person? true))

(defn scene
  "The activity's local scene: place, named focus (including container contents),
  at most twelve other visible items, stores in scope, and people: those
  present, with the judged people (see `judged-people`) as person things."
  [world {:keys [place] :as job}]
  (let [focus (focus-things world job)
        named (set (map :id focus))
        judged (set (judged-people world job))]
    {:place (assoc (select-keys (space/location world place) [:id :name :desc :aspects]) :room? true)
     :focus focus
     :also-here (vec (take 12 (remove #(or (named (:id %)) (items/store? %)) (items/visible-at world place))))
     :stores (vec (items/stores-for world place))
     :people (mapv (fn [id]
                     (cond-> (if (judged id) (person-thing world id) {:name (:name (w/agent world id))})
                       (w/busy world id) (assoc :doing (:activity (w/busy world id)))))
                   (sort (into (space/present world place) judged)))}))

(defn judged-things
  "Everything the scene judge may change, once each: place, focus, other
  visible items (at most twelve), stores in scope and the judged people. A
  person whose id is also a thing's isn't judged."
  [world job]
  (let [{:keys [place focus also-here stores people]} (scene world job)]
    (->> (concat [place] focus also-here stores (filter :person? people))
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
  "Checked ids and fates as named event data; default fates describe the activity."
  [world doing {:keys [used-up absorbed fates]}]
  (cond-> {}
    (seq used-up) (assoc :used-up (mapv #(hash-map :item % :name (:name (items/item world %))
                                                   :fate (or (get fates %) (str "used up while " doing))) used-up))
    (seq absorbed) (assoc :absorbed (mapv (fn [{:keys [thing into]}]
                                            {:item thing :name (:name (items/item world thing)) :into into}) absorbed))))

(defn work-done
  "A checked activity result. All removals retain names and fates; new items
  are allocated on this world so ids remain deterministic across batches."
  [world job {:keys [minutes stopped-early? luck]} verdict]
  (let [gone (keep #(items/item world %) (:used-up verdict))
        made (made-items world job gone (:made verdict))
        fetched (mapv :id (keep (fn [[entry it]] (when (:from entry) it)) (map vector (:made verdict) made)))]
    {:type :item/work-done :actor (:actor job) :visible-to (audience world job)
     :data (cond-> (merge {:job (:id job) :actor (:actor job) :place (:place job) :doing (:doing job)
                           :minutes minutes :stopped-early? stopped-early? :luck luck
                           :summary (or (:summary verdict) (neutral-summary world job stopped-early?))
                           :changes (vec (:changes verdict))}
                          (removal-data world (:doing job) verdict))
             (seq made) (assoc :made made)
             (seq fetched) (assoc :fetched fetched))}))

(defn judge-failed
  "Event, for the operator only: why the judge's answer about `job` wasn't used."
  [job reason]
  {:type :item/judge-failed :visible-to :system :data {:job (:id job) :reason reason}})
