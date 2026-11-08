(ns manor.ruleset.hibernation
  "Hibernation (Claudeworth design decision 11): someone nobody needs, like
  the village architect on a day without clients, is out of play and their
  day is summarised by their :routine instead of played."
  (:require [manor.mechanics.builtins :as b]
            [manor.mechanics.space :as space]
            [manor.mechanics.visits :as visits]
            [manor.mechanics.works :as works]
            [manor.ruleset.places :as places]
            [manor.world :as w])
  (:import (java.time LocalTime)))

(defn- routine-now
  "The entry of `id`'s routine in force now, {:from :at :doing}, or nil if
  they have none. Each entry holds from its time until the next one; before
  the first, the last (from the night before) holds."
  [world id]
  (when-let [entries (seq (get-in world [:state :routines id]))]
    (let [t (places/now world)]
      (or (last (remove #(.isAfter (LocalTime/parse (:from %)) t) entries))
          (last entries)))))

(defn- where-routine-has-them?
  "Is `id` in the place their routine puts them now? Never for someone without a routine."
  [world id]
  (= (:at (routine-now world id)) (space/location-of world id)))

(defn- company?
  "Is anyone else in play (not hibernating) at one of `places`, or on their
  way there? Someone just where their routine has them isn't company, so two
  people who share a workplace (the publican and his barmaid) don't keep each
  other in play once their callers have gone."
  [world id places]
  (some #(and (not= id (:id %))
              (not (b/hibernating? world (:id %)))
              (not (where-routine-has-them? world (:id %)))
              (contains? places (or (get-in % [:travel :to]) (:location %))))
        (w/active-agents world)))

(defn- watched
  "The places where company keeps `id` in play, with their routine putting
  them `at`: that place and the one they're in; or, when they're away from
  the routine's area (visiting the house, say), that place and the whole area
  they're in, so they don't vanish when their host steps into the next room."
  [world id at]
  (let [here (space/location-of world id)]
    (if (= (places/area-of world here) (places/area-of world at))
      (hash-set here at)
      (into (hash-set here at) (places/in-area world (places/area-of world here))))))

(defn hibernate
  "Where `id`'s routine puts them now, {:activity :location}, as long as
  nobody else is in the place they're in or the place it puts them in
  (when visiting another area: anywhere in that area), and no visit keeps
  them in play (an invited guest, on the day) and they have no accepted work
  unfinished (manor.mechanics.works); else nil. So people
  elsewhere in the village don't wake them, nobody sees them taken off, and
  anyone arriving finds them where their routine has them."
  [world id]
  (when-let [{:keys [at doing]} (routine-now world id)]
    (when-not (or (visits/keeps-awake? world id) (works/open-work? world id) (company? world id (watched world id at)))
      {:activity doing :location at})))

