(ns manor.engine.round
  "One round, in order: arrivals, releases, coming to and hibernation; work that ends;
  the steward; approved act-bridges; world systems; each schedule group's
  turns; the GM if anything triggers it. Calendar review runs before turns.
  See manor.engine for the outline."
  (:require [manor.actions :as a]
            [manor.engine.resolve :as resolve]
            [manor.engine.run :as run]
            [manor.engine.seat :as seat]
            [manor.engine.work :as work]
            [manor.io.bridge :as bridge]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.calendar :as calendar]
            [manor.mechanics.items.work :as item-work]
            [manor.mechanics.physical :as physical]
            [manor.mechanics.physical.changes :as physical-changes]
            [manor.mechanics.space :as space]
            [manor.world :as w]))

(defn- schedule
  "Groups of active agents in spawn order, or the scenario's custom groups.
  Eligibility is checked when each group runs, allowing mid-round wakings."
  [run world]
  (let [ids (mapv :id (w/active-agents world))
        s (or (:schedule (:scenario run)) :round-robin)]
    (cond
      (fn? s) (s world)
      (= s :simultaneous) [ids]
      :else (mapv vector ids))))

(defn- turn-due?
  "Does `id` take a turn now: active, not travelling or unconscious, and not
  busy unless interrupted?"
  [world id]
  (and (w/active? world id)
       (not (space/in-transit? world id))
       (not (physical/unconscious? world id))
       (let [busy (w/busy world id)]
         (or (nil? busy) (:interrupted-by busy)))))

(defn- run-group!
  "Agents in a group act on the same snapshot. A group of one commits its
  action immediately; a larger group's actions are checked during the turn
  and resolved afterwards in schedule order, so conflicts are rejected.
  Each queued final rechecks that its actor is still active and conscious."
  [run ids]
  (let [ids (filter #(turn-due? @(:world run) %) ids)
        snapshot @(:world run)
        simultaneous? (> (count ids) 1)
        turn (fn [id]
               (let [commit (if simultaneous?
                              (fn [action args]
                                (if-let [r (a/check action @(:world run) id args)]
                                  {:rejected r}
                                  (resolve/stop-interrupted! run id action)))
                              (fn [action args] (resolve/resolve! run id action args)))]
                 {:role (:role (w/agent snapshot id)) :max-finals 1 :commit commit
                  :simultaneous? simultaneous?}))
        results (if simultaneous?
                  (mapv deref (mapv #(future (seat/activate run snapshot % (turn %))) ids))
                  (mapv #(seat/activate run snapshot % (turn %)) ids))]
    (when simultaneous?
      (doseq [{:keys [agent-id finals]} results
              {:keys [action args]} finals]
        (let [world @(:world run)
              reason (cond
                       (not (w/active? world agent-id)) "you are no longer active"
                       (physical/unconscious? world agent-id) "you are unconscious"
                       :else (:rejected (resolve/resolve! run agent-id action args)))]
          (when reason
            (run/emit! run [{:type :action/rejected :actor agent-id :visible-to #{agent-id}
                             :data {:action (name (:name action)) :reason reason}}])))))
    results))

(defn- run-approved-bridges! [run]
  (doseq [[req-id {:keys [bridge bridge-args action args requester status]}] (:pending-bridges @(:world run))
          :when (= status :approved)]
    (let [{:keys [result error cost]} (bridge/call (:scenario run) (:opts run) (run/bridge-ctx run) bridge bridge-args)
          on-result (:on-result (run/action-named run action))]
      (locking (:world run)
        (run/emit! run [{:type :bridge/completed :actor requester :visible-to #{requester}
                         :data {:req-id req-id :bridge bridge :result result :error error}}])
        (run/record-bridge-cost! run requester bridge cost)
        (when (and result on-result)
          (run/emit! run (on-result @(:world run) requester args result)))))))

(defn- composed!
  "A system's :compose, given the composed-query services: {:result r}, or
  {:error msg} if it threw (recorded for the operator)."
  [run system-name compose]
  (try {:result (compose @(:world run) (resolve/query-services run "system"))}
       (catch Exception e
         (run/emit! run [{:type :adjudication/failed :visible-to :system
                          :data {:task system-name :caller "system" :reason (ex-message e)}}])
         {:error (ex-message e)})))

(defn- run-systems! [run]
  (doseq [{:keys [name bridge bridge-args compose tick]} (:systems (:scenario run))]
    (let [res (cond
                compose (composed! run name compose)
                bridge
                (let [bargs (bridge-args @(:world run))
                      r (bridge/call (:scenario run) (:opts run) (run/bridge-ctx run) bridge bargs)]
                  (run/emit! run [{:type :bridge/called :visible-to :gm
                                   :data {:system name :bridge bridge :args bargs
                                          :result (:result r) :error (:error r)}}])
                  (run/record-bridge-cost! run "system" bridge (:cost r))
                  r))]
      (locking (:world run)
        (run/emit! run (tick @(:world run) res))))))

(defn- gm-reasons [run world]
  (let [gm (w/gm world)
        every (:gm-check-in-every (:opts run))]
    (when (and gm (= :active (:status gm)) (not= :off (:controller gm)))
      (seq (concat
            (when (seq (get-in world [:gm :inbox])) ["participants sent you requests"])
            (when (and (pos? every) (>= (- (:round world) (get-in world [:gm :last-intervention])) every))
              ["scheduled check-in"])
            (keep #(% world) (:gm-triggers (:scenario run))))))))

(defn- gm-enabled? [world]
  (let [gm (w/gm world)]
    (and gm (= :active (:status gm)) (not= :off (:controller gm)))))

(defn- run-gm! [run reasons batch]
  (when (and (gm-enabled? @(:world run)) (or (empty? batch) (not (run/cost-reached? run))))
    (let [gm-id (:id (w/gm @(:world run)))]
      (run/emit! run [{:type :gm/called :actor gm-id :visible-to :gm :data {:reasons reasons}}])
      (let [result (seat/activate run @(:world run) gm-id
                                  {:role :gm :reasons reasons :calendar-due batch
                                   :max-finals (:gm-max-actions (:opts run))
                                   :commit (fn [action args] (resolve/resolve! run gm-id action args))})
            ;; :done? also ends an exhausted, twice-nudged response. Only
            ;; an accepted done action or the action limit completes review.
            completed? (and (:done? result) (seq (:finals result))
                            (or (some #(= :done (get-in % [:action :name])) (:finals result))
                                (>= (count (:finals result)) (:gm-max-actions (:opts run)))))]
        (when (or (empty? batch) completed?)
          (run/emit! run (cond-> [{:type :gm/intervened :actor gm-id :visible-to :gm
                                  :data (cond-> {:reasons reasons} (seq batch) (assoc :calendar? true))}]
                          (seq batch) (conj (calendar/reviewed-event batch)))))
        (when (and (seq batch) (not completed?))
          (run/emit! run [{:type :run/pause-requested :visible-to :system
                           :data {:reason "calendar GM review did not complete"}}]))))))

(defn- maybe-run-gm! [run]
  (when-let [reasons (gm-reasons run @(:world run))]
    (run-gm! run reasons nil)))

(defn- review-calendar! [run]
  (run/emit! run (calendar/due-events @(:world run)))
  (when-let [batch (seq (calendar/due-records @(:world run)))]
    (run-gm! run ["calendar entries are due for review"] (vec batch))))

(defn- pause-reasons [run]
  (let [world @(:world run)
        {:keys [max-cost max-rounds]} (:opts run)
        pause-when (:pause-when (:scenario run))]
    (seq (concat (:pause-requests world)
                 (when (and max-cost (>= (w/total-cost world) max-cost))
                   [(format "cost limit $%.2f reached" (double max-cost))])
                 (when (and max-rounds (>= (:round world) max-rounds)) ["max rounds reached"])
                 (when-let [r (and pause-when (pause-when world))] [r])
                 (when (empty? (w/active-agents world)) ["no active participants"])))))

(defn run-round!
  "Run one full round on the calling thread. Returns pause reasons, if any."
  [run]
  (let [round (inc (:round @(:world run)))]
    (run/emit! run [{:type :round/started :data {:round round} :visible-to :all}])
    (work/finish-jobs! run (locking (:world run)
                   ;; decided before releases, so a worker whose job is due still holds it
                             (let [ending (item-work/ending-jobs @(:world run))]
                               (run/emit! run (space/arrivals @(:world run)))
                               (let [released (b/releases @(:world run))
                                     came-to (physical-changes/comings-to @(:world run))]
                                 (run/emit! run released)
                                 (run/emit! run came-to)
                                 (when-let [hibernate (:hibernate (:scenario run))]
                                   (run/emit! run (b/hibernations hibernate @(:world run)
                                                                  (set (map :actor (concat released came-to)))))))
                               ending)))
    (work/run-steward! run)
    (run-approved-bridges! run)
    (run-systems! run)
    (review-calendar! run)
    (let [acted (reduce (fn [acted group]
                          (into acted (map :agent-id (run-group! run group))))
                        #{} (schedule run @(:world run)))]
      ;; One fixed trailing pass, only for default schedules. A later waking
      ;; during this pass waits until next round.
      (when-not (fn? (:schedule (:scenario run)))
        (let [late (for [{:keys [id]} (w/active-agents @(:world run))
                         :when (and (not (contains? acted id)) (turn-due? @(:world run) id))]
                     id)]
          (doseq [id (vec late)]
            (run-group! run [id])))))
    (maybe-run-gm! run)
    (run/emit! run [{:type :round/ended :data {:round round} :visible-to :all}])
    (pause-reasons run)))
