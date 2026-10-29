(ns manor.tools.scene-claudeworth
  "Pure Claudeworth operator overlays. No changes to the world or its events."
  (:require [manor.clock :as clock]
            [manor.scenarios.claudeworth.money :as money]))

(defn- display-size [size]
  ;; Custom architect price lists may use names with no geometric meaning.
  (let [size (if (string? size) (keyword size) size)]
    (when (#{:small :medium :large :grand} size) size)))

(defn- reservation [plan data]
  (cond-> {:id (:room data) :name (:name data) :domain :house :kind :room
           :floor (:floor plan) :desc (:desc plan) :connects-to (:connects-to data)
           :plan-id (:plan-id data) :due (:due data)}
    (display-size (:size plan)) (assoc :size (display-size (:size plan)))))

(defn history
  "Event-indexed display evidence. Capture a plan at commissioning, never look
  up its later/current value to reinterpret an old reservation. Completion-only
  legacy evidence can size a location, but never invent an earlier reservation."
  [events]
  (:annotations
   (reduce
    (fn [{:keys [plans rooms] :as acc} [index {:keys [type data]}]]
      (case type
        :claudeworth/plans-drawn (assoc-in acc [:plans (get-in data [:plan :id])] (:plan data))
        :claudeworth/commissioned
        (let [record (reservation (get plans (:plan-id data)) data)]
          (-> acc (assoc-in [:rooms (:room data)] record)
              (assoc-in [:annotations index] {:reservation record})))
        :claudeworth/works-completed
        (if (contains? rooms (:room data)) acc
            (assoc-in acc [:rooms (:room data)] (reservation (get plans (:plan-id data)) data)))
        :space/location-added
        (if-let [record (get rooms (get-in data [:location :id]))]
          (assoc-in acc [:annotations index] {:location (select-keys record [:size])}) acc)
        acc))
    {:plans {} :rooms {} :annotations {}} (map-indexed vector events))))

(defn toolbar [world]
  (when-let [funds (get-in world [:state :household :funds])]
    [:div.scene-household [:b "Household funds: "] (money/display (money/spec world) funds)]))

(defn badges [world loc-id]
  (when (some #(some #{loc-id} (:connects-to %)) (get-in world [:state :works]))
    ["adjoining works"]))

(defn- due [world round]
  (when (number? round)
    (if-let [c (:clock world)] (clock/world-label world round) (str "r" round))))

(defn inspect [world kind id]
  (case kind
    "agent"
    (let [purse (get-in world [:state :purses id])
          plans (filter #(contains? (:holders %) id) (sort-by :id (vals (get-in world [:state :plans]))))]
      (when (or (some? purse) (seq plans))
        [:section.scene-extra [:h3 "Household · plans and purse"]
         (when (some? purse) [:p "Purse: " (money/display (money/spec world) purse)])
         (for [plan plans]
           [:details [:summary (:name plan) " · " (if (number? (:price plan))
                                                    (money/display (money/spec world) (:price plan)) "price not recorded")]
            [:p (str (:id plan) " · " (:size plan) " · " (some-> (:floor plan) name))]
            [:p (:desc plan)]
            [:p (cond (some #(= (:id plan) (:plan-id %)) (get-in world [:state :works])) "Being built"
                      (:built? plan) "Built" :else "Drawn")]])]))
    "place"
    (when-let [work (first (filter #(= id (:room %)) (get-in world [:state :works])))]
      [:section.scene-extra [:h3 "Under construction: " (:name work)]
       [:p "Completion: " (due world (:due work))]])
    nil))

(def hooks {:history history :toolbar toolbar :badges badges :inspect inspect})
