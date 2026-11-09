(ns manor.tools.scene-ruleset
  "Pure operator overlays for the ruleset's scenarios (manor.ruleset):
  household funds, plans, the architect's building works and works
  (manor.mechanics.works: things installed, moved or removed). No changes
  to the world or its events."
  (:require [manor.clock :as clock]
            [manor.mechanics.money :as money]
            [manor.mechanics.works :as works]))

(defn- display-size [size]
  ;; Custom architect price lists may use names with no geometric meaning.
  (let [size (if (string? size) (keyword size) size)]
    (when (#{:small :medium :large :grand} size) size)))

(defn- reservation [plan data]
  (cond-> {:id (:room data) :name (:name data) :domain (:domain data) :kind :room
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
        :building/plans-drawn (assoc-in acc [:plans (get-in data [:plan :id])] (:plan data))
        :building/commissioned
        (let [record (reservation (get plans (:plan-id data)) data)]
          (-> acc (assoc-in [:rooms (:room data)] record)
              (assoc-in [:annotations index] {:reservation record})))
        :building/works-completed
        (if (contains? rooms (:room data)) acc
            (assoc-in acc [:rooms (:room data)] (reservation (get plans (:plan-id data)) data)))
        :space/location-added
        (if-let [record (get rooms (get-in data [:location :id]))]
          (assoc-in acc [:annotations index] {:location (select-keys record [:size])}) acc)
        acc))
    {:plans {} :rooms {} :annotations {}} (map-indexed vector events))))

(defn- work-details
  "One work, as a collapsible entry: its summary and status, then the rest."
  [{:keys [id kind status summary] :as e}]
  [:details.scene-work
   [:summary id " · " kind " · " status " · " summary]
   (for [[k label] [[:where "Where"] [:planner "Planned by"] [:who "Who should do it"] [:trade "Trade"]
                    [:ordered-by "Ordered by"] [:worker "Worker"] [:estimate "Estimate"] [:materials "Materials"]
                    [:price "Price"] [:priced-by "Price set by"] [:paid-from "Paid from"] [:phase "Phase"]
                    [:progress "Progress"] [:under-way "Under way"] [:result "Result"]]
         :when (some? (get e k))]
     [:p [:b label ": "] (str (get e k))])])

(defn- works-panel
  "Every work in the run: open ones listed, finished and cancelled ones folded
  away. Nil when there are none."
  [world]
  (let [entries (works/operator-entries world)
        open? #(contains? works/open-statuses (keyword (:status %)))
        [open closed] [(filter open? entries) (remove open? entries)]]
    (when (seq entries)
      [:div.scene-works
       [:b "Works: "] (count open) " open, " (count closed) " finished or cancelled"
       (map work-details open)
       (when (seq closed)
         [:details [:summary "Finished and cancelled"] (map work-details closed)])])))

(defn toolbar [world]
  (let [funds (get-in world [:state :accounts "household"])
        panel (works-panel world)]
    (when (or funds panel)
      [:div
       (when funds [:div.scene-household [:b "Household funds: "] (money/display (money/spec world) funds)])
       panel])))

(defn- works-at
  "The open works whose place is `loc-id`."
  [world loc-id]
  (filter #(and (= loc-id (:place %)) (contains? works/open-statuses (keyword (:status %))))
          (works/operator-entries world)))

(defn badges [world loc-id]
  (let [building? (some #(some #{loc-id} (:connects-to %)) (get-in world [:state :works]))
        open (works-at world loc-id)]
    (cond-> []
      building? (conj "adjoining works")
      (seq open) (conj (str (count open) (if (= 1 (count open)) " work" " works")
                            (when (some :under-way open) ", under way"))))))

(defn- due [world round]
  (when (number? round)
    (if-let [c (:clock world)] (clock/world-label world round) (str "r" round))))

(defn inspect [world kind id]
  (case kind
    "agent"
    (let [purse (get-in world [:state :accounts id])
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
    (let [work (first (filter #(= id (:room %)) (get-in world [:state :works])))
          here (filter #(= id (:place %)) (works/operator-entries world))]
      (when (or work (seq here))
        [:section.scene-extra
         (when work [:div [:h3 "Under construction: " (:name work)] [:p "Completion: " (due world (:due work))]])
         (when (seq here) [:div [:h3 "Works here"] (map work-details here)])]))
    nil))

(defn- agent-works
  "The works `id` planned, ordered or works on, as the inspector's section."
  [world id]
  (let [mine (filter #(contains? (:people %) id) (works/operator-entries world))]
    (when (seq mine)
      [:section.scene-extra [:h3 "Works"] (map work-details mine)])))

(defn- inspect-all
  "The place and agent inspectors, with an agent's works after the rest."
  [world kind id]
  (let [base (inspect world kind id)
        extra (when (= "agent" kind) (agent-works world id))]
    (cond (and base extra) [:div base extra] :else (or base extra))))

(def hooks {:history history :toolbar toolbar :badges badges :inspect inspect-all})
