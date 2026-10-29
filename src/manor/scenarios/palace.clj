(ns manor.scenarios.palace
  "The Palace of Gathered Fragrance: a skeleton world in the Qing palace in
  the 1780s, on the shared ruleset (manor.ruleset) with its own setting pack
  (resources/settings/qing-palace): a consort, a noble lady, the consort's
  chief eunuch, the noble lady's maid and a stall keeper beyond the gate.
  Small enough to touch every mechanic once; no architect and no post office.
  The world is in resources/worlds/palace.edn, its mock day in
  resources/worlds/palace-day.edn.

    clojure -M:dev:run palace --mock --test"
  (:require [manor.ruleset :as ruleset]))

(defn mock-conversation
  "Speaking turns of the mock day: at the morning call the consort answers
  the noble lady once. Other turns take the default (the initiator ends it,
  the others stay silent)."
  [{:keys [agent-id conversation]}]
  (when (and (= "sumuru-wanxia" agent-id) (= 1 (:pass conversation)))
    {:tool :reply :args {:text "Well enough. You will take tea with me this afternoon."}}))

(def scenario
  (ruleset/scenario
   {:name :palace
    :doc "The Palace of Gathered Fragrance, 1780s: a consort, a noble lady, a chief eunuch, a maid, and a stall beyond the palace gate."
    :defaults {:world-file "worlds/palace.edn" :max-cost 0.20}
    :mock-day "worlds/palace-day.edn"
    :mock-conversation mock-conversation}))
