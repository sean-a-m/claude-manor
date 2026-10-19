(ns manor.scenarios.household
  "Stable test household for Claudeworth mechanics. The world, cast defaults
  and scripted day are test-owned, not inherited from the bundled content.
  Reuse the production mechanics so regressions in those still fail tests."
  (:require [clojure.edn :as edn]
            [manor.scenarios.claudeworth :as cw]
            [manor.scenarios.shop :as shop]))

(def world-file "test/fixtures/household.edn")

(def mock-day (edn/read-string (slurp "test/fixtures/household-day.edn")))

(defn mock-policy [ctx]
  (cw/scripted-policy mock-day ctx))

(defn mock-conversation
  "The breakfast exchange, and Briggs quoting needles during a counter visit."
  [{:keys [world agent-id conversation used]}]
  (cond
    (and (= "briggs" agent-id) (nil? (shop/service-reason world agent-id)))
    (if (some #{"quote-goods"} used)
      {:tool :reply :args {:text "The two packets cost three shillings altogether."}}
      {:tool :quote-goods :args {:for (:initiator conversation)
                                 :goods [{:item "packet of needles" :qty 2}]}})
    (and (= "lord-claudeworth" agent-id) (= 1 (:pass conversation)))
    {:tool :reply :args {:text "Every room, my dear. I would not dream of choosing a wallpaper without you."}}))

(def scenario
  (assoc cw/scenario
         :name :household
         :defaults {:world-file world-file :architect? true :housemaid? false}
         :mock-policy mock-policy
         :mock-conversation mock-conversation))
