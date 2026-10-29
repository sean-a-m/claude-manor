(ns manor.scenarios.claudeworth
  "Claudeworth Manor: an earl, a countess, a private secretary and the
  village architect, living at and enlarging a manor beside a village and an
  abbey, on the shared ruleset (manor.ruleset). The world is in
  resources/worlds/claudeworth.edn (run option :world-file overrides it),
  and the demonstration day of the mock in resources/worlds/claudeworth-day.edn.

    clojure -M:dev:run claudeworth --mock --test
    clojure -M:run claudeworth --test --max-cost 0.25"
  (:require [manor.ruleset :as ruleset]))

(defn mock-conversation
  "Speaking turns of the demonstration day: at breakfast the earl answers the
  countess once, and she ends it. Other turns take the default (the
  initiator ends it, the others stay silent)."
  [{:keys [agent-id conversation]}]
  (when (and (= "lord-claudeworth" agent-id) (= 1 (:pass conversation)))
    {:tool :reply :args {:text "Every room, my dear. I would not dream of choosing a wallpaper without you."}}))

(def scenario
  (ruleset/scenario
   {:name :claudeworth
    :doc "Claudeworth Manor, 1904: a family and their servants, a village architect, shops, a public house and cottage trades, living from day to day."
    :defaults {:world-file "worlds/claudeworth.edn"}
    :mock-day "worlds/claudeworth-day.edn"
    :mock-conversation mock-conversation}))
