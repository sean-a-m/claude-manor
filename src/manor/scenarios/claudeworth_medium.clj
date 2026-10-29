(ns manor.scenarios.claudeworth-medium
  "Claudeworth Manor, the medium household: a peer's family newly come into
  ironstone royalties, their nine servants, and the village and abbey beside
  them, on the shared ruleset (manor.ruleset). The world is in
  resources/worlds/claudeworth-medium.edn (run option :world-file overrides
  it), and the design is in docs/household-1904-proposal.md.

  Same rules, actions, judges and bridges as :claudeworth, on a different
  world file with its own cast; its mock day is empty (everyone passes).
  The baron's son is away at school (:away? true in the world file): in the
  run, but elsewhere until he
  comes home on 20 December (the file's authored :visits) or someone invites
  him home sooner (manor.mechanics.visits). Starting after that homecoming
  skips it, with no reconstructed stay; he remains away. :leave-out can
  remove him and his authored visit altogether.

    clojure -M:run claudeworth-medium --mock --test
    clojure -M:run claudeworth-medium --test --max-cost 0.25"
  (:require [manor.ruleset :as ruleset]))

(def scenario
  (ruleset/scenario
   {:name :claudeworth-medium
    :doc "Claudeworth Manor, 1904: a baron, his family and nine servants, the village tradespeople and the abbey, with new ironstone money and a house to enlarge."
    :defaults {:world-file "worlds/claudeworth-medium.edn"
               :max-cost 10.00}}))
