(ns manor.scenarios.hamlet
  "Test scenario for world files, the clock, occupation and items: a house
  and a village loaded from test/fixtures/hamlet.edn, starting at 21:00,
  with a few things about the house and the shop, and a free action with a
  :reply (check-time). With the mock policy Ada goes to bed at once and Ben
  stays up; with real models it's a cheap check of times, sleep and rousing.

    clojure -M:dev:run hamlet --mock --test
    clojure -M:dev:run hamlet --test --max-cost 0.05"
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
    :doc "Interrupt someone here who is asleep or busy; they decide whether to stop."
    :args [:map [:who :string]]
    :valid? (fn [world actor {:keys [who]}]
              (cond (not (space/together? world actor who)) (str who " is not here")
                    (not (w/busy world who)) (str who " is not busy")
                    (:interrupted-by (w/busy world who)) (str (:name (w/agent world who)) " has already been interrupted")))
    :effect (fn [world actor {:keys [who]}] [(b/interrupt world who actor)])}

   {:name :check-time
    :kind :free
    :doc "Look at the clock: the time now."
    :reply (fn [world _ _] (str "It is " (clock/label (:clock world) (:round world)) "."))}])

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
   :roles {:resident {:brief (str "You live in a small house: a hall, a bedroom upstairs and a porch. The village green "
                                  "and its shop are half an hour from the porch; a farm is further off. Each round is half "
                                  "an hour, and it is evening. Talk with your housemate, and when you are tired, go to the "
                                  "bedroom and sleep until morning (sleep with a time like \"07:00\"). Someone asleep can "
                                  "be woken with rouse.")
                      :spawnable? true}
           :gm {:brief "Test GM."}}
   :actions actions
   :items {}
   :mock-policy mock-policy})
