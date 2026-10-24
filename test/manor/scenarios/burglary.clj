(ns manor.scenarios.burglary
  "Test scenario for physical status (manor.mechanics.physical) on
  Claudeworth's mechanics, loaded from test/fixtures/burglary.edn: a house
  at one in the morning after a burglary has gone wrong. The butler is bound
  and gagged in his pantry; in the dining room the housemaid is blindfolded,
  the first footman lies dead with the pantry keys, and the burglar holds the
  jewels; the cook is knocked out and comes to within the hour; the
  under-gardener is tied up in the potting shed, away from his routine.
  The mock night demonstrates an immediate binding and gagging, theft of the
  silver, muffled speech, and the second footman's seizure of the burglar and
  recovery of the jewels. The steward eases the footman's ankle if hindered,
  but leaves the ropes alone. Tests can clear the butler's starting bonds and
  hinder the footman through operator events before running this script.

    clojure -M:dev:run burglary --mock --test --port 8091"
  (:require [manor.mechanics.items.judge :as judge]
            [manor.mechanics.items.steward :as steward]
            [manor.mechanics.physical :as physical]
            [manor.scenarios.claudeworth :as cw]))

(def world-file "test/fixtures/burglary.edn")

(def mock-day
  "Agent id -> time of day -> the tool calls of that turn, in order."
  {"slade" {"01:00" [[:take {:item "pantry-keys"}] [:walk {:to "butlers-pantry"}]
                     [:act-on {:on ["crane"] :doing "binding Crane to his chair and gagging him with a napkin"}]]
             "01:30" [[:open {:container "plate-chest"}] [:take {:item "silver-salver"}]
                      [:put-in {:item "silver-salver" :container "sack"}]]}
   "crane" {"01:30" [[:say {:text "The jewels are hidden in Slade's coat!"}]]
             "02:30" [[:call {:text "Will, stop the burglar!"}]]}
   "will" {"01:00" [[:sleep {:until "02:00"}]]
           "02:00" [[:walk {:to "butlers-pantry"}]
                     [:act-on {:on ["slade"] :doing "pinning Slade's arms and recovering the jewel case"}]]}})

(defn mock-policy [ctx]
  (cw/scripted-policy mock-day ctx))

(defn mock-judge
  "Fixed outcomes for the two scripted acts; other work uses the default mock.
  Match structured actor/target ids, never the activity's prose."
  [{:keys [job] :as ctx}]
  (case [(:act? job) (:actor job) (:people job)]
    [true "slade" ["crane"]]
    {:tool :judge
     :args {:summary "Slade bound Crane to the chair and gagged him."
            :physical [{:person "crane" :faculty :movement :level :disabled :cause "bound hand and foot to a chair"}
                       {:person "crane" :faculty :grasp :level :disabled :cause "wrists tied behind the chair"}
                       {:person "crane" :faculty :speech :level :impeded :cause "gagged with a table napkin"}]}}
    [true "will" ["slade"]]
    {:tool :judge
     :args {:summary "Will pinned Slade's arms and recovered the jewel case."
            :physical [{:person "slade" :faculty :movement :level :disabled :cause "held fast by Will"}
                       {:person "slade" :faculty :grasp :level :disabled :cause "arms pinned by Will"}]
            :moved [{:item "jewel-case" :to "will"}]}}
    (judge/mock-judge ctx)))

(defn mock-steward
  "The night eases Will's disabled movement; ropes and gags stay as they are."
  [{:keys [world sleepers hindered] :as ctx}]
  (let [will? (some #(= "will" (:id %)) (concat sleepers hindered))]
    (cond-> (steward/mock-steward ctx)
      (and will? (= :disabled (physical/level world "will" :movement)))
      (assoc-in [:args :physical] [{:person "will" :faculty :movement :level :impeded :cause "a tender ankle"}]))))

(def scenario
  (assoc (dissoc cw/scenario :calendar :mock-conversation)
         :init (comp #(dissoc % :calendar) (:init cw/scenario))
         :name :burglary
         :doc "Hollin Grange, 1904: a burglary at night that has gone wrong."
         :defaults {:world-file world-file}
         :mock-policy mock-policy
         :mock-judge mock-judge
         :mock-steward mock-steward))
