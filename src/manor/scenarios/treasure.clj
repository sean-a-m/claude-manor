(ns manor.scenarios.treasure
  "Example scenario: explorers search a small map for one buried treasure."
  (:require [manor.narrate :as narrate]
            [manor.rng :as rng]
            [manor.space :as space]
            [manor.world :as w]))

;; --- Reducers: the only way this scenario's state changes -----------------

(defmethod w/apply-event :treasure/dug [world {:keys [actor data]}]
  (cond-> (update-in world [:state :dug-by actor] (fnil conj #{}) (:at data))
    (:found? data) (assoc-in [:state :found-by] actor)))

(defmethod w/apply-event :treasure/nightfall [world _]
  (update-in world [:state :nights] inc))

;; --- Narration: how events read to agents and in the feed -----------------

(defmethod narrate/describe :treasure/dug [{:keys [actor data]} names]
  (str (narrate/who names actor) " digs at " (narrate/place names (:at data))
       (if (:found? data) " and FINDS THE TREASURE!" " and finds nothing.")))

(defmethod narrate/describe :treasure/nightfall [{:keys [data]} _]
  (str "Night " (:night data) " falls."))

;; --- Actions -----------------------------------------------------------------

(def actions
  [{:name :dig
    :doc "Dig for the treasure where you are."
    :valid? (fn [world actor _]
              (let [here (space/location-of world actor)]
                (cond (get-in world [:state :found-by]) "the treasure has already been found"
                      (contains? (get-in world [:state :dug-by actor]) here) "you already dug here")))
    :effect (fn [world actor _]
              (let [here (space/location-of world actor)
                    found? (and (= here (get-in world [:state :treasure-at]))
                                (zero? (rng/roll world [(:round world) actor :dig] 2)))]
                ;; Everyone present sees the digging; the outcome is recorded in the event.
                [{:type :treasure/dug :data {:at here :found? found?}
                  :visible-to (space/here world actor)}]))}])

;; --- Systems -------------------------------------------------------------------

(def night-system
  {:name :night
   :tick (fn [world _]
           [{:type :treasure/nightfall :data {:night (inc (get-in world [:state :nights]))}
             :visible-to :all}])})

;; --- Views ---------------------------------------------------------------------

(defn observe [world id]
  {:nights (get-in world [:state :nights])
   :treasure-found? (boolean (get-in world [:state :found-by]))
   :you {:dug-at (sort (get-in world [:state :dug-by id]))}})

;; --- Mock policy (free offline runs) ----------------------------------------------

(defn mock-policy [{:keys [world agent-id role]}]
  (if (= role :gm)
    {:tool :done}
    (let [here (space/location-of world agent-id)
          dug (get-in world [:state :dug-by agent-id] #{})]
      (if (contains? dug here)
        (if-let [{:keys [to]} (first (remove #(dug (:to %)) (space/exits world here)))]
          {:tool :move :args {:to to}}
          {:tool :pass})
        {:tool :dig}))))

;; --- The scenario ------------------------------------------------------------------

(def scenario
  {:name :treasure
   :init (fn [_opts]
           {:state {:treasure-at "cave" :found-by nil :dug-by {} :nights 0}
            :space {:locations [{:id "camp" :name "Camp" :desc "Tents and a fire."}
                                {:id "forest" :name "Forest" :desc "Dense pines."}
                                {:id "cave" :name "Cave" :desc "Dark and damp."}]
                    :routes [["camp" "forest" 1] ["forest" "cave" 1] ["camp" "cave" 2]]}
            :agents [{:name "Iris" :role :explorer :persona "Methodical."}
                     {:name "Omar" :role :explorer :persona "Impatient."}]})
   :roles {:explorer {:brief "You are an explorer. One treasure is buried somewhere nearby. Search for it by moving between places and digging. Digging in the right place may still take more than one attempt... but you can only dig once per place."
                      :spawnable? true
                      :start-location "camp"}
           :gm {:brief "A small treasure hunt. Intervene only if asked."}}
   :actions actions
   :systems [night-system]
   :observe observe
   :pause-when (fn [world] (when-let [who (get-in world [:state :found-by])]
                             (str who " found the treasure")))
   :limits {:max-agents 4}
   :mock-policy mock-policy})
