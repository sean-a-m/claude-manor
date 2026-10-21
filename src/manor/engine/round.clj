(ns manor.engine.round
  "One round, in order: arrivals, releases and hibernation; work that ends;
  the steward; approved act-bridges; world systems; each schedule group's
  turns; the GM if anything triggers it. See manor.engine for the outline."
  (:require [manor.actions :as a]
            [manor.engine.resolve :as resolve]
            [manor.engine.run :as run]
            [manor.engine.seat :as seat]
            [manor.engine.work :as work]
            [manor.io.bridge :as bridge]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.items :as items]
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

(defn- run-group!
  "Agents in a group act on the same snapshot. A group of one commits its
  action immediately; a larger group's actions are checked during the turn
  and resolved afterwards in schedule order, so conflicts are rejected."
  [run ids]
  (let [ids (filter #(and (w/active? @(:world run) %)
                          (not (space/in-transit? @(:world run) %))
                          (let [busy (w/busy @(:world run) %)]
                            (or (nil? busy) (:interrupted-by busy))))
                    ids)
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
        (when-let [reason (:rejected (resolve/resolve! run agent-id action args))]
          (run/emit! run [{:type :action/rejected :actor agent-id :visible-to #{agent-id}
                           :data {:action (name (:name action)) :reason reason}}]))))
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

(defn- maybe-run-gm! [run]
  (when-let [reasons (gm-reasons run @(:world run))]
    (let [gm-id (:id (w/gm @(:world run)))]
      (run/emit! run [{:type :gm/called :actor gm-id :visible-to :gm :data {:reasons reasons}}])
      (seat/activate run @(:world run) gm-id
                     {:role :gm :reasons reasons :max-finals (:gm-max-actions (:opts run))
                      :commit (fn [action args] (resolve/resolve! run gm-id action args))})
      (run/emit! run [{:type :gm/intervened :actor gm-id :visible-to :gm :data {:reasons reasons}}]))))

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
                             (let [ending (items/ending-jobs @(:world run))]
                               (run/emit! run (space/arrivals @(:world run)))
                               (let [released (b/releases @(:world run))]
                                 (run/emit! run released)
                                 (when-let [hibernate (:hibernate (:scenario run))]
                                   (run/emit! run (b/hibernations hibernate @(:world run) (set (map :actor released))))))
                               ending)))
    (work/run-steward! run)
    (run-approved-bridges! run)
    (run-systems! run)
    (let [acted (reduce (fn [acted group]
                          (into acted (map :agent-id (run-group! run group))))
                        #{} (schedule run @(:world run)))]
      ;; One fixed trailing pass, only for default schedules. A later waking
      ;; during this pass waits until next round.
      (when-not (fn? (:schedule (:scenario run)))
        (let [late (for [{:keys [id travel busy]} (w/active-agents @(:world run))
                         :when (and (not (contains? acted id)) (not travel)
                                    (or (nil? busy) (:interrupted-by busy)))]
                     id)]
          (doseq [id (vec late)]
            (run-group! run [id])))))
    (maybe-run-gm! run)
    (run/emit! run [{:type :round/ended :data {:round round} :visible-to :all}])
    (pause-reasons run)))
