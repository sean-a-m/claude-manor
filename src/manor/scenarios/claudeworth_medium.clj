(ns manor.scenarios.claudeworth-medium
  "Claudeworth Manor, the medium household: a peer's family newly come into
  ironstone royalties, their nine servants, and the village and abbey beside
  them, on Claudeworth's mechanics. The world is in
  resources/worlds/claudeworth-medium.edn (run option :world-file overrides
  it), and the design is in docs/household-1904-proposal.md.

  It is a copy of the :claudeworth scenario on a different world file: same
  rules, actions, judges and bridges, and its own cast. Nothing here is
  Claudeworth-specific beyond the meeting place (where candidates and
  deliveries arrive) and the default world file. The baron's son is away at
  school (:away? true in the world file): in the run, but elsewhere until he
  comes home on 20 December (the file's authored :visits) or someone invites
  him home sooner (manor.mechanics.visits).

    clojure -M:run claudeworth-medium --mock --test
    clojure -M:run claudeworth-medium --test --max-cost 0.25"
  (:require [manor.mechanics.hiring :as hiring]
            [manor.mechanics.visits :as visits]
            [manor.scenarios.claudeworth :as cw]
            [manor.scenarios.claudeworth.deliveries :as deliveries]))

(def scenario
  (visits/install
   (deliveries/install
    (hiring/install
     (assoc cw/scenario
            :name :claudeworth-medium
            :doc "Claudeworth Manor, 1904: a baron, his family and nine servants, the village tradespeople and the abbey, with new ironstone money and a house to enlarge."
            :defaults {:world-file "worlds/claudeworth-medium.edn"
                       :max-cost 10.00}
            :mock-policy (fn [ctx] (cw/scripted-policy {} ctx))
            :mock-conversation (constantly nil))
     "carriage-sweep"))))
