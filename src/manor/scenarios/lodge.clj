(ns manor.scenarios.lodge
  "Ashby Lodge: three neighbours on Claudeworth's production mechanics,
  with a scripted day for free checks and no building business."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.walk :as walk]
            [manor.hiring :as hiring]
            [manor.scenarios.claudeworth :as cw]))

(def mock-day (edn/read-string (slurp (io/resource "worlds/lodge-day.edn"))))

(defn mock-policy [{:keys [world] :as ctx}]
  (let [p (first (sort-by :created (vals (hiring/pool world))))
        day (walk/postwalk #(case % "@candidate" (:agent-id p) "@record" (:id p) %) mock-day)
        day (if-let [id (:agent-id p)]
              (assoc day id {"09:30" [[:accept-place {:conditions "Sunday afternoons free."}]]
                             "10:00" [[:walk {:to "maids-room"}] [:spend-time {:doing "unpacking" :hours 0.5}]]
                             "22:00" [[:sleep {:until "07:00"}]]}) day)]
    (cw/scripted-policy day ctx)))

(defn mock-conversation
  "The cook answers the mistress's morning enquiry once."
  [{:keys [agent-id conversation]}]
  (when (and (= agent-id "mrs-bell") (= 1 (:pass conversation)))
    {:tool :reply :args {:text "We can manage breakfast, but we shall want another pair of hands. Perhaps the shop knows of a girl."}}))

(def scenario
  (hiring/install (assoc cw/scenario
         :name :lodge
         :doc "Ashby Lodge, 1904: a widow, her cook-general and the shopkeeper across the street. A maid is wanted."
         :defaults {:world-file "worlds/lodge.edn" :max-cost 0.10}
         :exclude-actions (into (:exclude-actions cw/scenario)
                                #{:consult-references :price-list :draw-plans :show-plans :commission})
         :mock-policy mock-policy
         :mock-conversation mock-conversation) "front-gate"))
