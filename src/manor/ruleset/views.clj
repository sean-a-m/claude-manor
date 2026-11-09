(ns manor.ruleset.views
  "What agents see: in `reference` (the system prompt) every place by area
  and hiring's meeting place and positions; in `observe` their bedroom, the
  household's bedrooms, opening hours, where they can walk and the journeys
  to other areas, with the views of building, the shop, hiring, visits and
  following."
  (:require [clojure.string :as str]
            [manor.clock :as clock]
            [manor.mechanics.building :as building]
            [manor.mechanics.contacts :as contacts]
            [manor.mechanics.following :as following]
            [manor.mechanics.hiring :as hiring]
            [manor.mechanics.positions :as positions]
            [manor.mechanics.shop :as shop]
            [manor.mechanics.space :as space]
            [manor.mechanics.works :as works]
            [manor.mechanics.visits :as visits]
            [manor.ruleset.places :as places]
            [manor.world :as w]))

(defn- places-by-area
  "Every place by area, largest area first: {:house [{:id :name} ...] ...},
  with opening hours where a place has them."
  [world]
  (->> (vals (get-in world [:space :locations]))
       (sort-by :id)
       (group-by #(:domain % :elsewhere))
       (sort-by (comp - count val))
       (reduce (fn [m [domain locs]]
                 (assoc m domain (mapv #(cond-> (select-keys % [:id :name])
                                          (:hours %) (assoc :open (str/join "-" (:hours %))))
                                       locs)))
               (array-map))))

(defn- journeys
  "For each other area: its nearest place, how long the journey takes, and
  when you would arrive."
  [world here]
  (let [c (:clock world)
        round (:round world)]
    (vec (for [area (places/area-names world)
               :when (not= area (places/area-of world here))
               :let [[place d] (places/way-in world here area)]
               :when place]
           {:to area :place place :minutes (* d (:minutes-per-round c))
            :arrive (clock/short-label c (+ (max 1 round) d) round)}))))

(defn- home-bedrooms
  "The home area's bedrooms and other rooms assigned for sleeping, with all
  active assigned sleepers, guests included. [{:id :name :sleeps [names]}]."
  [world]
  (let [sleepers (group-by #(places/bedroom-of world (:id %)) (w/active-agents world))
        home (space/home-domain world)]
    (vec (for [l (sort-by :id (vals (get-in world [:space :locations])))
               :when (and (= home (:domain l))
                          (or (= :bedroom (:kind l)) (seq (get sleepers (:id l)))))]
           {:id (:id l) :name (:name l) :sleeps (mapv :name (get sleepers (:id l)))}))))

(defn reference
  "What rarely changes, for the system prompt: every place by area, and
  hiring's meeting place and positions."
  [world _]
  {:places (places-by-area world)
   :hiring (hiring/reference world)})

(defn observe
  "Your bedroom, the home's bedrooms if you're of the household or a guest
  from elsewhere, your visit if you're a guest and the people you've met who
  are away (manor.mechanics.visits), opening hours where you are, where you
  can walk from here, and the journeys to other areas. Every place by area
  is in `reference`."
  [world id]
  (let [here (space/location-of world id)
        hours (:hours (space/location world here))]
    (cond-> (-> (merge-with merge (building/observe world id) (shop/observe world id))
                (update :you assoc :bedroom (places/bedroom-of world id))
                (assoc :hiring (hiring/observe world id))
                (visits/add-to-view world id)
                (contacts/add-to-view world id)
                (works/add-to-view world id)
                (following/add-to-view world id))
      (or (positions/household-member? world id) (visits/from-elsewhere? world id))
      (assoc-in [:household :bedrooms] (home-bedrooms world))
      hours (update :here merge {:open (str/join "-" hours) :open-now? (building/open-now? world hours)})
      here (assoc :walkable (vec (sort (places/walkable world here)))
                  :journeys (journeys world here)))))

