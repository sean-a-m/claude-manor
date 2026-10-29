(ns manor.scenarios.claudeworth.holidays
  "Shared, explicitly dated holidays for the two Claudeworth scenarios.
  These are starting calendar records, not recurring rules or world effects."
  (:require [manor.settings :as settings]))

(def entries
  "Public English holidays, quarter days and observances for 1904–1905."
  (:calendar (settings/pack {})))

(defn add-to
  "Add the shared holidays to an init result. A world-file entry with the same
  ID replaces the default entirely, including its audience and lifecycle. The
  engine still validates and emits every entry after the final clock and cast."
  [init]
  (let [entries (:calendar (settings/pack init))
        authored (:calendar init)
        ids (set (map :id authored))]
    (assoc init :calendar (into (filterv #(not (ids (:id %))) entries) authored))))
