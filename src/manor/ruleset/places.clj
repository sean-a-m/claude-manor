(ns manor.ruleset.places
  "Places, areas, bedrooms and the time of day, shared by the ruleset's
  actions, views, hibernation and mock. Areas are the locations' :domains
  (in Claudeworth: the house, the village, the abbey)."
  (:require [clojure.string :as str]
            [manor.clock :as clock]
            [manor.mechanics.space :as space])
  (:import (java.time LocalTime)))

(defn loc-name [world loc] (:name (space/location world loc) loc))

(defn now
  "The time of day of the current round, as a LocalTime."
  ^LocalTime [world]
  (.toLocalTime (clock/time-of (:clock world) (:round world))))

(defn bedroom-of [world id] (get-in world [:state :bedrooms id]))

(defn lodgings
  "The ids of the world's lodging, sorted: locations marked `:lodging true`,
  where anyone may sleep, with or without a bedroom of their own."
  [world]
  (sort (for [[id l] (get-in world [:space :locations]) :when (true? (:lodging l))] id)))

(defn named
  "\"Crown's back chamber (crown-chamber)\" for each id, joined with \" or \"."
  [world ids]
  (str/join " or " (for [id ids] (str (loc-name world id) " (" id ")"))))

(defn examples
  "Up to six ids, comma-separated."
  [ids]
  (str/join ", " (take 6 (sort ids))))

(defn area-of [world loc] (some-> (:domain (space/location world loc)) name))

(defn area-names [world]
  (into (sorted-set) (keep (comp #(some-> % name) :domain)) (vals (get-in world [:space :locations]))))

(defn in-area [world area]
  (for [[id l] (get-in world [:space :locations]) :when (= area (some-> (:domain l) name))] id))

(defn way-in
  "The nearest place of `area` from `from`: the first place of that area on
  the quickest path to it, and the journey's length in rounds."
  [world from area]
  (when-let [[d target] (first (sort (for [id (in-area world area)
                                           :let [d (space/distance world from id)]
                                           :when d]
                                       [d id])))]
    [(first (filter #(= area (area-of world %)) (space/path world from target))) d]))

(defn walkable [world loc] (disj (space/area world loc) loc))
