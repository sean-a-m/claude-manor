(ns manor.scenarios.calendar-fixture
  "Small, test-owned schedule world. No real model or bridge needed."
  (:require [manor.io.worldfile :as worldfile]))

(def scenario
  {:name :calendar-fixture
   :calendar {}
   :roles {:resident {:brief "Live here." :spawnable? true} :gm {:brief "Review lightly."}}
   :init (fn [opts]
           (if-let [file (:world-file opts)]
             (assoc (worldfile/load-world file) :state {:money 100})
             {:state {:money 100}
              :clock {:start "1904-10-15T04:30" :minutes-per-round (:minutes-per-round opts 30)}
              :agents [{:name "Ada" :role :resident} {:name "Ben" :role :resident}]
              :calendar (:entries opts [])}))
   :mock-policy (fn [{:keys [role world agent-id used]}]
                  (if (= role :gm) {:tool :done}
                      (if (and (= "ada" agent-id) (= 1 (:round world)) (not (some #{"pray"} used)))
                        {:tool :pray :args {:text "A request after the calendar review."}}
                        {:tool :pass})))})
