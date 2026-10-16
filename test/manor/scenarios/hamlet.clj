(ns manor.scenarios.hamlet
  "Test scenario for world files, the clock and occupation: a house and a
  village loaded from test/fixtures/hamlet.edn, starting at 21:00. Ada goes
  to bed in her first turn; Ben stays up."
  (:require [manor.builtins :as b]
            [manor.clock :as clock]
            [manor.space :as space]
            [manor.world :as w]
            [manor.worldfile :as worldfile]))

(def actions
  [{:name :sleep
    :doc "Sleep until a time of day, e.g. \"07:00\". Only in a bedroom."
    :args [:map [:until :string]]
    :valid? (fn [world actor {:keys [until]}]
              (cond (not= :bedroom (:kind (space/location world (space/location-of world actor))))
                    "go to a bedroom first"
                    (not (clock/hh-mm? until)) "until must be a time like 07:00"))
    :effect (fn [world actor {:keys [until]}]
              [(b/occupy world actor "asleep" (clock/round-at (:clock world) (:round world) until))])}

   {:name :rouse
    :kind :free
    :doc "Wake someone here who is asleep or busy."
    :args [:map [:who :string]]
    :valid? (fn [world actor {:keys [who]}]
              (cond (not (space/together? world actor who)) (str who " is not here")
                    (not (w/busy world who)) (str who " is not busy")))
    :effect (fn [world actor {:keys [who]}] [(b/free world who actor)])}])

(defn mock-policy [{:keys [world agent-id role]}]
  (let [here (space/location-of world agent-id)]
    (cond
      (= role :gm) {:tool :done}
      (and (= agent-id "ada") (not= here "bedroom")) {:tool :move :args {:to "bedroom"}}
      (= agent-id "ada") {:tool :sleep :args {:until "07:00"}}
      :else {:tool :pass})))

(def scenario
  {:name :hamlet
   :init (fn [opts]
           (assoc (worldfile/load-world (or (:world-file opts) "test/fixtures/hamlet.edn"))
                  :state {}))
   :roles {:resident {:brief "You live here." :spawnable? true}
           :gm {:brief "Test GM."}}
   :actions actions
   :mock-policy mock-policy})
