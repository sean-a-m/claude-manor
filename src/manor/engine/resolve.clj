(ns manor.engine.resolve
  "Applying what an agent calls: `resolve!` checks an action against the
  current world and applies its effect (judging it first, for one with
  :judge-job), and `run-query!` runs a query (a bridge, an adjudication or a
  composed query) and its :on-result."
  (:require [manor.actions :as a]
            [manor.engine.judges :as judges]
            [manor.engine.run :as run]
            [manor.io.bridge :as bridge]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.items.work :as item-work]
            [manor.prompt :as prompt]
            [manor.world :as w]))

(defn- action-events
  "Events for a valid action. An :act action becomes a pending bridge request."
  [action world actor args]
  (if (= :act (a/kind action))
    (let [bargs ((or (:bridge-args action) (fn [_ _ args] args)) world actor args)]
      [{:type :bridge/requested :actor actor :visible-to #{actor}
        :data {:req-id (str "req-" (inc (:seq world)))
               :bridge (:bridge action) :action (:name action)
               :args args :bridge-args bargs}}])
    (a/effect-events action world actor args)))

(defn stop-interrupted!
  "An accepted action other than carry-on ends an interrupted occupation first."
  [run actor action]
  (when (not= :carry-on (:name action))
    (when-let [by (:interrupted-by (w/busy @(:world run) actor))]
      (run/emit! run [(b/free @(:world run) actor by)]))))

(defn- mark-started-alone
  "Mark occupations agents start where the scenario's :hibernate would
  already put them out of play (nobody near) with :started-alone?, so they
  return to their routine when it ends instead of taking another turn."
  [run world events]
  (if-let [hibernate (:hibernate (:scenario run))]
    (mapv #(cond-> %
             (and (= :agent/occupied (:type %)) (get-in % [:data :until])
                  (not (get-in % [:data :hibernating?])) (hibernate world (:actor %)))
             (assoc-in [:data :started-alone?] true))
          events)
    events))

(defn- reply-text [data]
  (if (string? data) data (prompt/->json data)))

(defn- resolve-judged!
  "Check a turn-ending action with :judge-job, have the work judge decide
  it at once, outside the lock, and apply its :on-result. Its :reply is
  the tool result. A failed judgement still applies, with no verdict."
  [run actor action args keep-occupation?]
  (if-let [reason (a/check action @(:world run) actor args)]
    {:rejected reason}
    (let [_ (when-not keep-occupation? (stop-interrupted! run actor action))
          world @(:world run)
          {:keys [job work]} ((:judge-job action) world actor args)
          result (select-keys (judges/ask-judge run world job work (item-work/judged-things world job))
                              [:verdict :failure :records])]
      (locking (:world run)
        (doseq [record! (:records result)] (record!))
        (let [result (assoc (dissoc result :records) :job job :work work)
              before (count @(:events run))]
          (run/emit! run (mark-started-alone run @(:world run)
                                             (mapv #(cond-> % (not (:actor %)) (assoc :actor actor))
                                                   ((:on-result action) @(:world run) actor args result))))
          {:ok true :world @(:world run)
           :reply (reply-text ((:reply action) @(:world run) actor args
                               (assoc result :events (subvec @(:events run) before))))})))))

(defn prepare-action
  "Check structure and optional semantic admission before any effect or
  interruption. Return a prepared action without :admission, so queued
  simultaneous finals do not repeat the model call when applied. Ordinary
  validation still runs on the live world at application."
  [run actor action args]
  (if-let [reason (a/check action @(:world run) actor args)]
    {:rejected reason}
    (if-let [task (:admission action)]
      (let [{:keys [verdict failure]}
            (judges/adjudicate run task @(:world run) actor args
                               {:before-call #(when (run/cost-reached? run)
                                                (throw (ex-info "The run's cost limit has been reached." {})))})]
        (cond
          failure
          (do (run/emit! run [{:type :adjudication/failed :visible-to :system
                              :data {:task task :caller actor :reason failure}}])
              {:rejected (get-in run [:scenario :adjudicators task :failure-reply]
                                 "The activity check failed; nothing has started.")})
          (true? (:allowed verdict)) {:action (dissoc action :admission)}
          :else {:rejected (or (:reason verdict) "The activity was not approved; nothing has started.")}))
      {:action action})))

(defn- apply-action!
  "Check and apply an action against the current world, atomically.
  Returns {:ok true :world world-after} or {:rejected reason}. With
  `keep-occupation?` (a speaking turn in a conversation) an interrupted
  occupation goes on: the agent still decides on their own turn. An action
  with :judge-job is judged first (see `resolve-judged!`), and its result
  has the :reply."
  ([run actor action args] (apply-action! run actor action args false))
  ([run actor action args keep-occupation?]
   (if (:judge-job action)
     (resolve-judged! run actor action args keep-occupation?)
     (locking (:world run)
       (let [world @(:world run)]
         (if-let [reason (a/check action world actor args)]
           {:rejected reason}
           (do (when-not keep-occupation? (stop-interrupted! run actor action))
               (let [events (mark-started-alone run @(:world run) (action-events action @(:world run) actor args))]
                 (run/emit! run events)
                 (when (some #(= :agent/moved (:type %)) events)
                   (when-let [hibernate (:hibernate (:scenario run))]
                     (run/emit! run (b/wakings hibernate @(:world run))))))
               {:ok true :world @(:world run)})))))))

(defn resolve!
  "Admit an action before starting it. Rejections leave any existing
  occupation intact; accepted actions are revalidated and applied."
  ([run actor action args] (resolve! run actor action args false))
  ([run actor action args keep-occupation?]
   (let [{:keys [action rejected]} (prepare-action run actor action args)]
     (if rejected
       {:rejected rejected}
       (apply-action! run actor action args keep-occupation?)))))

(defn free-reply
  "The tool result for an accepted free action: its :reply (a string as is,
  anything else as JSON), computed from the world after its effect, or \"ok\"."
  [action world actor args]
  (if-let [f (:reply action)]
    (reply-text (f world actor args))
    "ok"))

(defn query-services
  "Engine-owned I/O for a composed query. Raw intermediate records are operator-only;
  only the query's checked projection is returned to its caller. `(recent)`
  gives the caller's own last few narrated lines, for a judge's context."
  [run actor]
  (let [budget! #(when-let [cap (get-in run [:opts :max-cost])]
                   (when (>= (w/total-cost @(:world run)) cap)
                     (throw (ex-info "The run's cost limit has been reached." {}))))]
    {:bridge (fn [bridge-name args]
               (budget!)
               (let [{:keys [result error cost]} (bridge/call (:scenario run) (:opts run) (run/bridge-ctx run) bridge-name args)]
                 (run/emit! run [{:type :bridge/called :actor actor :visible-to :system
                                  :data {:bridge bridge-name :args args :result result :error error}}])
                 (run/record-bridge-cost! run actor bridge-name cost)
                 (when error (throw (ex-info error {})))
                 result))
     :adjudicate (fn [task args]
                   (budget!)
                   (let [{:keys [verdict failure]}
                         (judges/adjudicate run task @(:world run) actor args
                                            {:before-call budget!})]
                     (when failure (throw (ex-info failure {})))
                     verdict))
     :recent (fn [] (prompt/recent-lines @(:world run) (run/events run) actor))}))

(defn run-query!
  "Run a bridge or internal adjudication query during a turn. Only checked
  adjudication results reach :on-result; technical failures have no effects.
  Speaking-turn queries keep the caller's occupation, even if interrupted."
  ([run actor action args] (run-query! run actor action args false))
  ([run actor action args keep-occupation?]
   (let [world @(:world run)]
     (if-let [reason (a/check action world actor args)]
       {:error reason}
       (let [_ (when-not keep-occupation? (stop-interrupted! run actor action))
             world @(:world run)
             task (:adjudicate action)
             spec (get-in run [:scenario :adjudicators task])
             composed (:query-fn action)
             bargs (when-not (or task composed) ((or (:bridge-args action) (fn [_ _ args] args)) world actor args))
             {:keys [result error cost] :as res}
             (cond
               composed (try {:result (composed world actor args (query-services run actor))}
                             (catch Exception e {:error "The enquiry failed; try again later." :failure (ex-message e)}))
               task
               (let [{:keys [verdict failure]} (judges/adjudicate run task world actor args nil)]
                 (if failure
                   {:error (or (:failure-reply spec) "The enquiry failed; try again later.") :failure failure}
                   {:result verdict}))
               :else (bridge/call (:scenario run) (:opts run) (run/bridge-ctx run) (:bridge action) bargs))]
         (locking (:world run)
           (if (or task composed)
             (when error
               (run/emit! run [{:type :adjudication/failed :visible-to :system
                                :data {:task (or task (:name action)) :caller actor :reason (:failure res)}}]))
             (do (run/emit! run [{:type :bridge/called :actor actor :visible-to #{actor}
                                  :data {:bridge (:bridge action) :args bargs :result result :error error}}])
                 (run/record-bridge-cost! run actor (:bridge action) cost)))
           (let [before (count @(:events run))]
             (when (and result (:on-result action))
               (run/emit! run (mapv #(cond-> % (not (:actor %)) (assoc :actor actor))
                                    ((:on-result action) @(:world run) actor args result))))
             (cond
               (and error (:failure-reply spec)) (assoc res :reply error)
               (and result (:reply action))
               (assoc res :reply (reply-text ((:reply action) @(:world run) actor args
                                                              (assoc result :events (subvec @(:events run) before)))))
               :else res))))))))
