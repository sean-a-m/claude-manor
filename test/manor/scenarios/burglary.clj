(ns manor.scenarios.burglary
  "Test scenario for physical status (manor.mechanics.physical) on
  Claudeworth's mechanics, loaded from test/fixtures/burglary.edn: a house
  at one in the morning after a burglary has gone wrong. The butler is bound
  and gagged in his pantry; in the dining room the housemaid is blindfolded,
  the first footman lies dead with the pantry keys, and the burglar holds the
  jewels; the cook is knocked out and comes to within the hour; the
  under-gardener is tied up in the potting shed, away from his routine.
  With the mock policy the burglar takes the keys from the dead footman and
  the salver from the plate chest, and the second footman goes to bed;
  everyone else passes.

    clojure -M:dev:run burglary --mock --test --port 8091"
  (:require [manor.scenarios.claudeworth :as cw]))

(def world-file "test/fixtures/burglary.edn")

(def mock-day
  "Agent id -> time of day -> the tool calls of that turn, in order."
  {"slade" {"01:00" [[:take {:item "pantry-keys"}] [:walk {:to "butlers-pantry"}]
                     [:open {:container "plate-chest"}] [:take {:item "silver-salver"}]
                     [:put-in {:item "silver-salver" :container "sack"}]]}
   "will" {"01:00" [[:sleep {:until "06:00"}]]}})

(defn mock-policy [ctx]
  (cw/scripted-policy mock-day ctx))

(def scenario
  (assoc (dissoc cw/scenario :calendar :mock-conversation)
         :init (comp #(dissoc % :calendar) (:init cw/scenario))
         :name :burglary
         :doc "Hollin Grange, 1904: a burglary at night that has gone wrong."
         :defaults {:world-file world-file}
         :mock-policy mock-policy))
