(ns manor.tools.scene-history
  "Event-prefix display projection. Never writes simulation records or uses a UI cache."
  (:require [manor.world :as w]))

(defn topology [world]
  {:locations (vec (sort-by (comp pr-str :id) (vals (get-in world [:space :locations]))))
   :routes (vec (for [[from tos] (sort-by (comp pr-str key) (get-in world [:space :routes]))
                     [to route] (sort-by (comp pr-str key) tos)]
                 (assoc route :from from :to to)))})

(defn project
  "Fold existing events and optional display annotations keyed by zero-based event
  index. An annotation may supply :reservation (a normalized room record) or
  :location (fields enriching that event's location). A location completes the
  reservation with its id. Only then-known routes enter placement boundaries."
  [events annotations]
  (let [[initial later] (split-with #(< (:round % 0) 1) events)
        advance
        (fn [{:keys [world reservations overlays order] :as acc} [index event]]
          (let [{:keys [reservation location]} (get annotations index)
                loc (when (= :space/location-added (:type event))
                      (merge (get-in event [:data :location]) location))
                id (:id loc)
                world (if (= "space" (namespace (:type event)))
                        (w/apply-stamped world event) world)
                reservations (cond-> reservations reservation (conj reservation))
                reservations (if loc (filterv #(not= id (:id %)) reservations) reservations)
                overlays (cond-> overlays loc (assoc id location))
                boundary {:locations (if loc [loc] []) :reservations reservations
                          :routes (:routes (topology world))}]
            (cond-> (assoc acc :world world :reservations reservations :overlays overlays)
              (and loc (not (some #{id} order))) (update :order conj id)
              (or loc reservation) (update :steps conj boundary))))
        start (reduce advance {:world (w/new-world) :reservations [] :overlays {} :order [] :steps []}
                      (filter (fn [[index event]]
                                (or (= "space" (namespace (:type event))) (contains? annotations index)))
                              (map-indexed vector initial)))
        initial-topology (assoc (topology (:world start))
                                :locations (mapv #(merge (get-in start [:world :space :locations %])
                                                        (get-in start [:overlays %])) (:order start))
                                :reservations (:reservations start))
        end (reduce advance (assoc start :steps [])
                    (filter (fn [[index event]]
                              (or (= "space" (namespace (:type event))) (contains? annotations index)))
                            (map vector (range (count initial) (count events)) later)))]
    {:history {:initial initial-topology :steps (:steps end)}
     :reservations (:reservations end) :overlays (:overlays end)}))
