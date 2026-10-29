(ns manor.scenarios.household-containers
  "Frozen container-world and choreography fixtures, independent of bundled content."
  (:require [clojure.edn :as edn]
            [manor.ruleset.mock :as mock]
            [manor.scenarios.household :as household]))

(def mock-day (edn/read-string (slurp "test/fixtures/household-containers-day.edn")))

(def scenario
  (assoc household/scenario
         :name :household-containers
         :defaults {:world-file "test/fixtures/household-containers.edn" :leave-out []}
         :mock-policy (fn [ctx] (mock/scripted-policy mock-day ctx))))
