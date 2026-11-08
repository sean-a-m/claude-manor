(ns manor.scenarios.claudeworth-small
  "Claudeworth Manor, the small household: a test bed for building a house
  out. A baron and baroness, poor all their lives, have come into a legacy
  of £30,000 and moved into the old manor farmhouse, the only house the
  title still owns: six rooms, the tenants' leavings for furniture, a
  smoking range, a near-empty larder and one overworked butler. Everything
  else (new rooms through the architect, furniture and fixtures through
  shops and works, a cook and other servants through recommend, send-for
  and engage) is theirs to get. The village and the abbey are the
  :claudeworth-medium ones, people and all, on the shared ruleset
  (manor.ruleset). The world is in resources/worlds/claudeworth-small.edn
  (run option :world-file overrides it); its mock day is empty (everyone
  passes).

    clojure -M:run claudeworth-small --mock --test
    clojure -M:run claudeworth-small --test --max-cost 0.25"
  (:require [manor.ruleset :as ruleset]))

(def scenario
  (ruleset/scenario
   {:name :claudeworth-small
    :doc "Claudeworth Manor, 1904: a baron and baroness with a new £30,000 legacy, one butler and a shabby farmhouse to build out into a manor, with the village tradespeople, the architect and the abbey."
    :defaults {:world-file "worlds/claudeworth-small.edn"
               :max-cost 10.00}}))
