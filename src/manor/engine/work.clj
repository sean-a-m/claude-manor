(ns manor.engine.work
  "The items side of a round: judging the work that ends as it starts, and
  the nightly steward."
  (:require [clojure.set :as set]
            [manor.engine.judges :as judges]
            [manor.engine.run :as run]
            [manor.mechanics.items :as items]))

(defn- job-result
  "Prepare a job's ending on a snapshot, with model calls and their records
  deferred from the world. Applying results stays on the round thread."
  [run world {:keys [job outcome minutes] :as work}]
  (cond
    (= :dropped outcome) {:ended :retired}
    (and (zero? minutes) (not (:quick? job))) {:ended :no-time}
    :else (judges/ask-judge run world job work (items/judged-things world job))))

(defn- finish-job!
  "Record and apply a prepared ending in job order. Build new item ids on
  the live world, so independent jobs making identically named things agree
  with sequential judging too."
  [run {:keys [job] :as work} {:keys [ended verdict failure records]}]
  (doseq [record! records] (record!))
  (let [world @(:world run)]
    (run/emit! run (if ended
                     [(items/work-ended world job ended)]
                     (cond-> [(items/work-done world job work verdict)]
                       failure (conj (items/judge-failed job failure)))))))

(defn- independent-prefix
  "The longest consecutive prefix with disjoint scopes. Never move a job
  past a conflicting predecessor: it must see that predecessor's result."
  [scope xs]
  (loop [batch [] used #{} [x & more] xs]
    (if (and x (empty? (set/intersection used (scope x))))
      (recur (conj batch x) (into used (scope x)) more)
      batch)))

(defn finish-jobs!
  "Judge independent jobs concurrently on the pre-batch world, then emit
  all records and endings in the original due/id order, outside model calls."
  [run works]
  (loop [works (seq works)]
    (when works
      (let [world @(:world run)
            batch (independent-prefix #(items/job-scope world (:job %)) works)
            results (mapv #(future (job-result run world %)) batch)]
        (doseq [[work result] (map vector batch results)]
          (finish-job! run work @result))
        (recur (seq (drop (count batch) works)))))))

(defn run-steward!
  "After jobs finish, tidy only idle leftovers and take off what sleepers
  wouldn't keep on overnight, before bridges/systems or characters can
  create new work: one call per place with candidates or sleepers. The
  marker is emitted even with none."
  [run]
  (let [world @(:world run)
        config (get-in run [:scenario :items :steward])]
    (when-let [day (items/steward-day world config)]
      (run/emit! run [{:type :items/steward-ran :visible-to :gm :data {:day day}}])
      (let [all-candidates (items/steward-candidates world config)
            all-sleepers (items/steward-sleepers world)]
        (doseq [place (sort (distinct (concat (keys all-candidates) (keys all-sleepers))))]
          (let [candidates (get all-candidates place [])
                sleepers (get all-sleepers place [])
                {:keys [verdict failure]} (judges/ask-steward run world place candidates sleepers)]
            (run/emit! run (cond-> [(items/stewarded @(:world run) place verdict)]
                             failure (conj {:type :items/steward-failed :visible-to :system
                                            :data {:place place :reason failure}})))))))))
