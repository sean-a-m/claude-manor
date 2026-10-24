(ns manor.mechanics.space
  "Locations as a graph.

  World shape:
    :space {:locations {\"square\" {:id \"square\" :name \"Village square\" :desc \"...\"}}
            :routes    {\"square\" {\"market\" {:travel 1 :open? true}}}}   ; directed edges

  Agents carry :location (nil while travelling) and, while travelling,
  :travel {:from \"square\" :to \"lisbon\" :arrives 7}.

  Moving along a route with :travel 1 is immediate (it is the agent's action
  for the round). A route with :travel n > 1 puts the agent in transit: they
  skip turns and arrive at the start of round (current + n). A route with
  :travel 0 joins places with negligible distance between them (rooms of one
  house); places joined that way form an `area`.

  Location matters for visibility: use `here` to make an event visible to
  everyone where it happens. The GM has no location and sees everything.

  People remember where they last saw each other. When two people part (one
  of them moves, sets off or is placed elsewhere), the movement reducers
  record it:
    :seen {\"ada\" {\"ben\" {:place \"hall\" :round 4 :went \"porch\"}}}
  Ada saw Ben in the hall in round 4, going to the porch (:went only for
  the one who stayed). `last-seen` reads it for views."
  (:require [manor.clock :as clock]
            [manor.mechanics.physical :as physical]
            [manor.mechanics.positions :as positions]
            [manor.world :as w]))

;; ---------------------------------------------------------------------------
;; Queries

(defn enabled? [world] (boolean (seq (get-in world [:space :locations]))))

(defn location [world loc-id] (get-in world [:space :locations loc-id]))

(defn location-of [world agent-id] (:location (w/agent world agent-id)))

(defn in-transit? [world agent-id] (some? (:travel (w/agent world agent-id))))

(defn present
  "Ids of active agents at `loc-id`."
  [world loc-id]
  (if (nil? loc-id)
    #{}
    (set (keep #(when (= loc-id (:location %)) (:id %)) (w/active-agents world)))))

(defn here
  "Visibility for something `agent-id` does where they are: everyone present
  plus the agent. Without locations, everyone."
  [world agent-id]
  (if-let [loc (location-of world agent-id)]
    (conj (present world loc) agent-id)
    :all))

(defn together? [world a b]
  (let [la (location-of world a)]
    (and (some? la) (= la (location-of world b)))))

(defn route [world from to] (get-in world [:space :routes from to]))

(defn exits
  "Open routes out of `loc-id`: [{:to id :travel n} ...]."
  [world loc-id]
  (for [[to r] (sort-by key (get-in world [:space :routes loc-id]))
        :when (:open? r)]
    {:to to :travel (:travel r)}))

(defn- shortest
  "[total-travel locations-after-from] for the quickest route over open
  routes, or nil if unreachable."
  [world from to]
  (loop [frontier {from [0 []]} done #{}]
    (when (seq frontier)
      (let [[loc [cost p]] (apply min-key (comp first val) frontier)]
        (if (= loc to)
          [cost p]
          (recur (reduce (fn [f {nxt :to t :travel}]
                           (let [c (+ cost t)]
                             (if (or (contains? done nxt) (<= (first (get f nxt [Long/MAX_VALUE])) c))
                               f
                               (assoc f nxt [c (conj p nxt)]))))
                         (dissoc frontier loc)
                         (exits world loc))
                 (conj done loc)))))))

(defn path
  "Quickest route (by total travel) from `from` to `to` over open routes, as
  the list of locations after `from`. nil if unreachable."
  [world from to]
  (second (shortest world from to)))

(defn distance
  "Total travel (rounds) of the quickest route from `from` to `to`, or nil."
  [world from to]
  (first (shortest world from to)))

(defn next-step [world from to] (first (path world from to)))

(defn area
  "Every location reachable from `loc-id` over open :travel 0 routes,
  including itself: the rooms of one house, the shops round a green."
  [world loc-id]
  (loop [seen #{loc-id} todo [loc-id]]
    (if-let [[loc & more] (seq todo)]
      (let [nxt (for [{:keys [to travel]} (exits world loc)
                      :when (and (zero? travel) (not (seen to)))]
                  to)]
        (recur (into seen nxt) (into (vec more) nxt)))
      seen)))

(defn- person
  "Someone in a view of the people here: who they are, how to address them
  if their position says (manor.mechanics.positions), their appearance, how they
  differ from it now (:aspects, see manor.mechanics.items), their physical
  statuses and impediments, whether they're dead (manor.mechanics.physical)
  and what they're doing."
  [world id]
  (let [a (w/agent world id)
        address (positions/addressed-as world id)]
    (cond-> (physical/with-status (select-keys a [:id :name :role :appearance :aspects]) a)
      address (assoc :address-as address)
      (:busy a) (assoc :doing (get-in a [:busy :activity])))))

(defn- seen-where
  "Where `agent-id` last saw each person `keep?` accepts (an id), most recent
  first: [{:id :name :place (a place name) :at (a time, or :round) :went (a
  place name, if they saw them go)}]."
  [world agent-id keep?]
  (let [c (:clock world)
        loc-name #(:name (location world %) %)]
    (->> (get-in world [:seen agent-id])
         (filter (fn [[id _]] (keep? id)))
         (sort-by (fn [[id {:keys [round]}]] [(- round) (:name (w/agent world id))]))
         (mapv (fn [[id {:keys [place round went]}]]
                 (cond-> {:id id :name (:name (w/agent world id)) :place (loc-name place)}
                   c (assoc :at (clock/short-label c round (:round world)))
                   (not c) (assoc :round round)
                   went (assoc :went (loc-name went))))))))

(defn last-seen
  "Where `agent-id` last saw each active person who isn't with them now, most
  recent first: [{:id :name :place (a place name) :at (a time, or :round)
  :went (a place name, if they saw them go)}]."
  [world agent-id]
  (let [with-me (present world (location-of world agent-id))]
    (seen-where world agent-id #(and (w/active? world %) (not (with-me %))))))

(defn away-acquaintances
  "Where `agent-id` last saw each person they've met who is away now (gone
  elsewhere, and able to come back), most recent first, as `last-seen` shows them."
  [world agent-id]
  (seen-where world agent-id #(= :away (:status (w/agent world %)))))

(defn view
  "The location section of an agent's observation: the place you're in
  (with its :aspects, if it has any), the exits, the people here (the dead
  among them) and where you last saw others. With a clock, times replace
  round numbers."
  [world agent-id]
  (let [a (w/agent world agent-id)
        c (:clock world)
        loc-name #(:name (location world %))]
    (cond->
      (if-let [{:keys [from to arrives]} (:travel a)]
        {:travelling (cond-> {:from from :to to :to-name (loc-name to)}
                       c (assoc :arrives (clock/label c arrives))
                       (not c) (assoc :arrives-round arrives))}
        (let [loc (:location a)]
          {:here (select-keys (location world loc) [:id :name :desc :aspects])
           :exits (mapv (fn [{:keys [to travel]}]
                          (cond-> {:to to :name (loc-name to)}
                            c (assoc :travel-minutes (* travel (:minutes-per-round c)))
                            (not c) (assoc :travel-rounds travel)))
                        (exits world loc))
           :people-here (mapv #(person world %) (sort (into (disj (present world loc) agent-id)
                                                             (physical/dead-at world loc))))}))
      (seq (last-seen world agent-id)) (assoc :last-seen (last-seen world agent-id)))))

;; ---------------------------------------------------------------------------
;; Events

(defn init-events
  "Events that build the graph from a scenario's
  {:locations [{:id :name :desc}] :routes [[from to travel] or [from to travel :one-way]]}."
  [{:keys [locations routes]}]
  (concat
   (for [l locations]
     {:type :space/location-added :data {:location l} :visible-to :gm})
   (for [[from to travel one-way] routes]
     {:type :space/route-opened :visible-to :gm
      :data {:from from :to to :travel (or travel 1) :one-way? (= one-way :one-way)}})))

(defn arrivals
  "Arrival events for travellers due by the current round."
  [world]
  (for [a (w/active-agents world)
        :let [{:keys [from to arrives]} (:travel a)]
        :when (and arrives (<= arrives (:round world)))]
    {:type :agent/arrived :actor (:id a) :data {:from from :to to}
     :visible-to (conj (present world to) (:id a))}))

(defmethod w/apply-event :space/location-added [world {:keys [data]}]
  (let [l (:location data)]
    (assoc-in world [:space :locations (:id l)] l)))

(defmethod w/apply-event :space/route-opened [world {{:keys [from to travel one-way?]} :data}]
  (cond-> (assoc-in world [:space :routes from to] {:travel travel :open? true})
    (not one-way?) (assoc-in [:space :routes to from] {:travel travel :open? true})))

(defmethod w/apply-event :space/route-closed [world {{:keys [from to one-way?]} :data}]
  (let [close (fn [wd a b] (if (route wd a b) (assoc-in wd [:space :routes a b :open?] false) wd))]
    (cond-> (close world from to)
      (not one-way?) (close to from))))

(defn- parted
  "The world after `mover` leaves `from` for `to` (nil if unseen): each
  person there remembers seeing them go there, and they remember each person
  they left, if they could see them (manor.mechanics.physical/sees?). Call
  it before the move is applied."
  [world mover from to]
  (reduce (fn [world p]
            (cond-> world
              (physical/sees? world p)
              (assoc-in [:seen p mover] (cond-> {:place from :round (:round world)} to (assoc :went to)))
              (physical/sees? world mover)
              (assoc-in [:seen mover p] {:place from :round (:round world)})))
          world
          (disj (present world from) mover)))

(defmethod w/apply-event :agent/moved [world {:keys [actor data]}]
  (-> (parted world actor (:from data) (:to data))
      (assoc-in [:agents actor :location] (:to data))))

(defmethod w/apply-event :agent/placed [world {:keys [data]}]
  (-> (parted world (:id data) (location-of world (:id data)) nil)
      (update-in [:agents (:id data)] #(-> % (assoc :location (:to data)) (dissoc :travel)))))

(defmethod w/apply-event :agent/departed [world {:keys [actor data]}]
  (-> (parted world actor (:from data) (:to data))
      (update-in [:agents actor] assoc :location nil :travel (select-keys data [:from :to :arrives]))))

(defmethod w/apply-event :agent/arrived [world {:keys [actor data]}]
  (update-in world [:agents actor] #(-> % (assoc :location (:to data)) (dissoc :travel))))

(doseq [t [:agent/moved :agent/placed :agent/departed :agent/arrived]]
  (defmethod w/perceived-by t [_] :sight))

;; ---------------------------------------------------------------------------
;; Actions

(defn- endpoints-visibility [world from to]
  (let [v (into (present world from) (present world to))]
    (if (seq v) v :gm)))

(defn actions []
  [{:name :move
    :doc "Go to another location along one of your exits (use its id). A trip longer than 1 round leaves you in transit, unable to act until you arrive."
    :args [:map [:to :string]]
    :available? (fn [world actor] (some? (location-of world actor)))
    :valid? (fn [world actor {:keys [to]}]
              (let [from (location-of world actor)
                    r (route world from to)]
                (cond (= to from) "you are already there"
                      (nil? (location world to)) (str "there is no location " to)
                      (nil? r) (str "there is no route from here to " to)
                      (not (:open? r)) (str "the route to " to " is closed")
                      :else (physical/movement-reason world actor (:travel r)))))
    :effect (fn [world actor {:keys [to]}]
              (let [from (location-of world actor)
                    travel (:travel (route world from to))]
                (if (<= travel 1)
                  [{:type :agent/moved :data {:from from :to to}
                    :visible-to (into (here world actor) (present world to))}]
                  [{:type :agent/departed :data {:from from :to to :arrives (+ (:round world) travel)}
                    :visible-to (here world actor)}])))}

   {:name :add-location
    :roles #{:gm}
    :doc "Create a new location. Connect it with open-route."
    :args [:map [:id [:re #"^[a-z0-9-]+$"]] [:name :string] [:desc {:optional true} :string]]
    :available? (fn [world _] (enabled? world))
    :valid? (fn [world _ {:keys [id]}] (when (location world id) (str id " already exists")))
    :effect (fn [_ _ {:keys [id name desc]}]
              [{:type :space/location-added :data {:location {:id id :name name :desc (or desc "")}}
                :visible-to :gm}])}

   {:name :open-route
    :roles #{:gm}
    :doc "Open (or create) a route between two locations, both ways unless one-way is true. travel is in rounds; 0 means next door (e.g. rooms of one house)."
    :args [:map [:from :string] [:to :string] [:travel {:optional true} [:int {:min 0 :max 20}]]
           [:one-way {:optional true} :boolean]]
    :available? (fn [world _] (enabled? world))
    :valid? (fn [world _ {:keys [from to]}]
              (cond (nil? (location world from)) (str "there is no location " from)
                    (nil? (location world to)) (str "there is no location " to)
                    (= from to) "a route needs two different locations"))
    :effect (fn [world _ {:keys [from to travel one-way]}]
              [{:type :space/route-opened :visible-to (endpoints-visibility world from to)
                :data {:from from :to to :one-way? (boolean one-way)
                       :travel (or travel (:travel (route world from to)) 1)}}])}

   {:name :close-route
    :roles #{:gm}
    :doc "Close a route (e.g. a storm blocks the road), both ways unless one-way is true. Travellers already on it still arrive."
    :args [:map [:from :string] [:to :string] [:one-way {:optional true} :boolean]]
    :available? (fn [world _] (enabled? world))
    :valid? (fn [world _ {:keys [from to]}]
              (when-not (route world from to) (str "there is no route from " from " to " to)))
    :effect (fn [world _ {:keys [from to one-way]}]
              [{:type :space/route-closed :visible-to (endpoints-visibility world from to)
                :data {:from from :to to :one-way? (boolean one-way)}}])}])
