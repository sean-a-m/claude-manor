(ns manor.scenarios.prize
  "Minimal test scenario: one prize, everyone acts simultaneously, and only
  one claim can succeed."
  (:require [manor.world :as w]))

(defmethod w/apply-event :prize/claimed [world {:keys [actor]}]
  (assoc-in world [:state :winner] actor))

(def scenario
  {:name :prize
   :init (fn [_] {:state {:winner nil}
                  :agents [{:name "P1" :role :player} {:name "P2" :role :player}]})
   :roles {:player {:brief "Claim the prize." :spawnable? true}
           :gm {:brief "Test GM."}}
   :actions [{:name :claim
              :roles #{:player}
              :doc "Claim the prize."
              :valid? (fn [world _ _] (when (get-in world [:state :winner]) "already claimed"))
              :effect (fn [_ actor _] [{:type :prize/claimed :visible-to :all}])}]
   :observe (fn [world _] {:winner (get-in world [:state :winner])})
   :schedule :simultaneous
   :mock-policy (fn [{:keys [role]}] (if (= role :gm) {:tool :done} {:tool :claim}))})
