(ns manor.engine.resolve
  "Applying what an agent calls: `resolve!` checks an action against the
  current world and applies its effect, and `run-query!` runs a query
  (a bridge, an adjudication or a composed query) and its :on-result."
  (:require [manor.actions :as a]
            [manor.engine.judges :as judges]
            [manor.engine.run :as run]
            [manor.io.bridge :as bridge]
            [manor.mechanics.builtins :as b]
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

(defn resolve!
  "Check and apply an action against the current world, atomically.
  Returns {:ok true :world world-after} or {:rejected reason}. With
  `keep-occupation?` (a speaking turn in a conversation) an interrupted
  occupation goes on: the agent still decides on their own turn."
  ([run actor action args] (resolve! run actor action args false))
  ([run actor action args keep-occupation?]
   (locking (:world run)
     (let [world @(:world run)]
       (if-let [reason (a/check action world actor args)]
         {:rejected reason}
         (do (when-not keep-occupation? (stop-interrupted! run actor action))
             (let [events (action-events action @(:world run) actor args)]
               (run/emit! run events)
               (when (some #(= :agent/moved (:type %)) events)
                 (when-let [hibernate (:hibernate (:scenario run))]
                   (run/emit! run (b/wakings hibernate @(:world run))))))
             {:ok true :world @(:world run)}))))))

(defn- reply-text [data]
  (if (string? data) data (prompt/->json data)))

(defn free-reply
  "The tool result for an accepted free action: its :reply (a string as is,
  anything else as JSON), computed from the world after its effect, or \"ok\"."
  [action world actor args]
  (if-let [f (:reply action)]
    (reply-text (f world actor args))
    "ok"))

(defn query-services
  "Engine-owned I/O for a composed query. Raw intermediate records are operator-only;
  only the query's checked projection is returned to its caller."
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
                                            {:model (get-in run [:opts :model]) :before-call budget!})]
                     (when failure (throw (ex-info failure {})))
                     verdict))}))

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
