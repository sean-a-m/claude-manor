(ns manor.tools.scene-time
  "Display timing from existing clocks, jobs and occupation events."
  (:require [manor.clock :as clock]
            [manor.mechanics.items :as items]))

(defn closed?
  "Unknown clock/hours means no closure claim. Hours use the existing same-day,
  inclusive-open/exclusive-close convention; this does not gate any action."
  [world location]
  (let [[open close :as hours] (:hours location)]
    (when (and (:clock world) (= 2 (count hours)) (every? clock/hh-mm? hours))
      (let [now (.toString (.toLocalTime (clock/time-of (:clock world) (:round world))))]
        (not (and (<= (compare open now) 0) (neg? (compare now close))))))))

(defn busy-progress
  "Fraction only with recorded start/end evidence for the current occupation.
  Missing evidence and indefinite hibernation remain status marks, not estimates."
  [{:keys [world events]} {:keys [id busy]}]
  (when (and busy (not (:hibernating? busy)))
    (let [job (when (:job busy) (items/job world (:job busy)))
          occupation (last (filter #(and (= id (:actor %))
                                         (#{:agent/occupied :agent/freed} (:type %))) events))
          matching? (and (= :agent/occupied (:type occupation))
                         (= (select-keys busy [:activity :until :asleep? :hibernating? :job])
                            (select-keys (:data occupation) [:activity :until :asleep? :hibernating? :job])))
          start (if job (:started job) (when matching? (:round occupation)))
          end (if job (:due job) (:until busy))]
      (when (and (number? start) (number? end) (> end start))
        (max 0 (min 1 (/ (- (:round world) start) (- end start))))))))
