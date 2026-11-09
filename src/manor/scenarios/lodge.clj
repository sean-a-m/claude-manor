(ns manor.scenarios.lodge
  "Ashby Lodge: three neighbours on the shared ruleset (manor.ruleset), with
  a scripted day for free checks (resources/worlds/lodge-day.edn), no
  building business, and no calendar (so none of the setting pack's
  holidays: a content choice; visits work without one)."
  (:require [clojure.walk :as walk]
            [manor.mechanics.contacts :as contacts]
            [manor.ruleset :as ruleset]
            [manor.ruleset.mock :as mock]))

(def mock-day (mock/read-day "worlds/lodge-day.edn"))

(defn mock-policy
  "Lodge's day, with the first contact (\"@caller\" for their person,
  \"@contact\" for the contact's id) taking the place and settling in."
  [{:keys [world] :as ctx}]
  (let [p (first (sort-by :created (vals (contacts/contacts world))))
        day (walk/postwalk #(case % "@caller" (:agent-id p) "@contact" (:id p) %) mock-day)
        day (if-let [id (:agent-id p)]
              (assoc day id {"11:30" [[:accept-place {:conditions "Sunday afternoons free."}]]
                             "12:00" [[:walk {:to "maids-room"}] [:spend-time {:doing "unpacking" :hours 0.5}]]
                             "22:00" [[:sleep {:until "07:00"}]]}) day)]
    (mock/scripted-policy day ctx)))

(defn mock-conversation
  "The cook answers the mistress's morning enquiry once."
  [{:keys [agent-id conversation]}]
  (when (and (= agent-id "mrs-bell") (= 1 (:pass conversation)))
    {:tool :reply :args {:text "We can manage breakfast, but we shall want another pair of hands. Perhaps the shop knows of a girl."}}))

(def scenario
  (ruleset/scenario
   {:name :lodge
    :doc "Ashby Lodge, 1904: a widow, her cook-general and the shopkeeper across the street. A maid is wanted."
    :defaults {:world-file "worlds/lodge.edn" :max-cost 0.10}
    :mock-policy mock-policy
    :mock-conversation mock-conversation
    :calendar? false}))
