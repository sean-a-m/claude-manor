(ns manor.world
  "The world value and the event reducer.

  The world is changed only by applying events. Engine event types are
  reduced here; scenarios add reducers for their own event types with
  `(defmethod manor.world/apply-event :my/event ...)`.

  Event shape:
    {:id 12 :round 3 :type :village/planted :actor \"ana\"
     :data {...} :visible-to :all | #{\"ana\" \"bruno\"} | :gm | :system}

  Visibility:
    :all      everyone
    #{ids}    only those agents (plus the GM and the operator)
    :gm       the GM and the operator
    :system   the operator only; never put into any prompt

  Engine-owned keys: :round :seq :agents :space :gm :pending-bridges
  :pause-requests :usage, and :clock when the scenario has one (manor.clock)."
  (:refer-clojure :exclude [agent]))

(defn new-world []
  {:round 0
   :seq 0
   :agents {}
   :space {:locations {} :routes {}}
   :state {}
   :gm {:inbox [] :last-intervention 0}
   :pending-bridges {}
   :pause-requests []
   :usage {}})

;; ---------------------------------------------------------------------------
;; Reducer

(defmulti apply-event
  "Return the world with `event` applied. Dispatches on :type.
  Event types without a reducer are informational and leave the world as is."
  (fn [_world event] (:type event)))

(defmethod apply-event :default [world _event] world)

(defn stamp
  "Give an event its sequence id, round, and a safe default visibility."
  [world event]
  (cond-> (assoc event :id (inc (:seq world))
                 :round (if (= :round/started (:type event)) (get-in event [:data :round]) (:round world)))
    (not (contains? event :visible-to)) (assoc :visible-to :gm)))

(defn apply-stamped [world event]
  (-> world (assoc :seq (:id event)) (apply-event event)))

(defn replay
  "Rebuild a world from a sequence of stamped events."
  [events]
  (reduce apply-stamped (new-world) events))

;; ---------------------------------------------------------------------------
;; Queries

(defn agent [world id] (get-in world [:agents id]))

(defn active? [world id] (= :active (:status (agent world id))))

(defn agents-in-order
  "All agents in spawn order."
  [world]
  (sort-by :spawned-at (vals (:agents world))))

(defn active-agents
  "Active non-GM agents in spawn order."
  [world]
  (filter #(and (= :active (:status %)) (not= :gm (:role %))) (agents-in-order world)))

(defn gm [world]
  (first (filter #(= :gm (:role %)) (vals (:agents world)))))

(defn busy
  "What `id` is occupied with, {:activity \"asleep\" :until 47}, or nil.
  Occupied agents skip their turns until round :until. A hibernating agent
  ({:activity .. :hibernating? true}, see manor.builtins) has no :until: the
  scenario wakes them. Work on items (manor.items) adds the :job it belongs to."
  [world id]
  (:busy (agent world id)))

(defn visible-to?
  "Can `viewer` (an agent id, :gm, or :operator) see `event`?"
  [event viewer]
  (let [v (:visible-to event)]
    (cond
      (= viewer :operator) true
      (= v :system)        false
      (= viewer :gm)       true
      (= v :all)           true
      (set? v)             (contains? v viewer)
      :else                false)))

(defn total-cost [world]
  (reduce + 0 (map :cost (vals (:usage world)))))

;; ---------------------------------------------------------------------------
;; Engine event reducers

(defmethod apply-event :world/initialized [world {:keys [data]}]
  (cond-> (assoc world :state (:state data) :scenario (:scenario data) :seed (:seed data))
    (:clock data) (assoc :clock (:clock data))))

(defmethod apply-event :round/started [world {:keys [data]}]
  (assoc world :round (:round data)))

(defmethod apply-event :agent/spawned [world {:keys [id data]}]
  (let [a (:agent data)]
    (assoc-in world [:agents (:id a)]
              (merge {:status :active :notes [] :controller :llm}
                     a
                     {:spawned-at id}))))

(defmethod apply-event :agent/retired [world {:keys [data]}]
  (assoc-in world [:agents (:id data) :status] :retired))

(defmethod apply-event :agent/updated [world {:keys [data]}]
  (update-in world [:agents (:id data)] merge (:changes data)))

(defmethod apply-event :agent/occupied [world {:keys [actor data]}]
  (assoc-in world [:agents actor :busy] (select-keys data [:activity :until :hibernating? :job])))

(defmethod apply-event :agent/interrupted [world {:keys [actor data]}]
  (assoc-in world [:agents actor :busy :interrupted-by] (:by data)))

(defmethod apply-event :agent/resumed [world {:keys [actor]}]
  (update-in world [:agents actor :busy] dissoc :interrupted-by))

(defmethod apply-event :agent/freed [world {:keys [actor]}]
  (update-in world [:agents actor] dissoc :busy))

(defmethod apply-event :agent/noted [world {:keys [actor round data]}]
  (update-in world [:agents actor :notes] conj {:round round :text (:text data)}))

(defmethod apply-event :gm/requested [world {:keys [id actor round data]}]
  (update-in world [:gm :inbox] conj {:event id :from actor :round round :text (:text data)}))

(defmethod apply-event :gm/intervened [world {:keys [round]}]
  (-> world
      (assoc-in [:gm :inbox] [])
      (assoc-in [:gm :last-intervention] round)))

(defmethod apply-event :bridge/requested [world {:keys [data actor]}]
  (assoc-in world [:pending-bridges (:req-id data)]
            (assoc data :requester actor :status :pending)))

(defmethod apply-event :bridge/approved [world {:keys [data]}]
  (assoc-in world [:pending-bridges (:req-id data) :status] :approved))

(defmethod apply-event :bridge/denied [world {:keys [data]}]
  (update world :pending-bridges dissoc (:req-id data)))

(defmethod apply-event :bridge/completed [world {:keys [data]}]
  (update world :pending-bridges dissoc (:req-id data)))

(defmethod apply-event :run/pause-requested [world {:keys [data]}]
  (update world :pause-requests conj (:reason data)))

(defmethod apply-event :run/paused [world _]
  (assoc world :pause-requests []))

(defmethod apply-event :llm/usage [world {:keys [data]}]
  (update-in world [:usage (:agent data)]
             (fn [u]
               (merge-with + (or u {:calls 0 :prompt-tokens 0 :completion-tokens 0 :cost 0})
                           {:calls 1
                            :prompt-tokens (or (:prompt-tokens data) 0)
                            :completion-tokens (or (:completion-tokens data) 0)
                            :cost (or (:cost data) 0)}))))
