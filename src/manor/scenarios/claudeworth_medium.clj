(ns manor.scenarios.claudeworth-medium
  "Claudeworth Manor, the medium household: a peer's family newly come into
  ironstone royalties, their nine servants, and the village and abbey beside
  them, on Claudeworth's mechanics. The world is in
  resources/worlds/claudeworth-medium.edn (run option :world-file overrides
  it), and the design is in docs/household-1904-proposal.md.

  It is a copy of the :claudeworth scenario on a different world file: same
  rules, actions, judges and bridges, and its own cast. Nothing here is
  Claudeworth-specific beyond the meeting place (where candidates and
  deliveries arrive), the default world file, and the fact that the baron's
  son is away at school (:leave-out leaves him in the world file but out of
  the run until there is a way to bring him home).

    clojure -M:run claudeworth-medium --mock --test
    clojure -M:run claudeworth-medium --test --max-cost 0.25"
  (:require [manor.mechanics.hiring :as hiring]
            [manor.scenarios.claudeworth :as cw]
            [manor.scenarios.claudeworth.deliveries :as deliveries]))

(def scenario
  (deliveries/install
   (hiring/install
    (assoc cw/scenario
           :name :claudeworth-medium
           :doc "Claudeworth Manor, 1904: a baron, his family and nine servants, the village tradespeople and the abbey, with new ironstone money and a house to enlarge."
           :defaults {:world-file "worlds/claudeworth-medium.edn"
                      :max-cost 0.20
                      ;; Master Claudeworth is at school: in the world file, out of the run.
                      :leave-out ["master-claudeworth"]}
           :mock-policy (fn [ctx] (cw/scripted-policy {} ctx))
           :mock-conversation (constantly nil))
    "carriage-sweep")))
