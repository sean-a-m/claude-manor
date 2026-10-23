(ns manor.scene-layout
  "Pure operator-only geometry. No world, engine, I/O or cache dependencies.

  A topology is {:locations [location ...] :reservations [location ...]
                :routes [{:from id :to id :travel n :open? boolean ...} ...]}.
  Locations have a unique :id, optional :name/:domain/:floor/:kind/:size.
  :size is :small/:medium/:large/:grand or [positive-width positive-height].
  Vector order is historical order (id breaks remaining ties). Routes are directed;
  every supplied route, including duplicates, closed routes and self-loops, survives.
  Reservations use room ids, the same sizing fields, and optional :connects-to ids.

  `place-history` takes {:initial topology :steps [topology ...]}. Steps are ordered
  placement boundaries: :locations/:reservations introduce records, :routes is the
  complete topology KNOWN AT THAT BOUNDARY. Never supply future routes to old steps.
  Initial placement may use the complete initial graph. Later records never alter
  an existing id's panel-local rectangle, floor or region (including completion of
  a reservation). Missing generic connection metadata is fine: later routes are
  connectors, not a reason to place an old room again.

  `layout` accepts that history plus optional :current topology, used ONLY for
  display metadata and current routes. Its :placement can also be passed directly
  to `present` without repeating search. Caches are optional, never authoritative.
  Output: :rooms keyed by id (local :x/:y/:w/:h plus :global rectangle), :panels and
  :regions with global bounds, :connections (one per directed input route), :quality
  and :bounds, all in grid cells. Panel/region outer packing may shift as they grow;
  only panel-local room geometry is frozen. See docs/scene-layout-api.md."
  (:require [clojure.set :as set]))

(def known-floors [:basement :ground :first :second :third :attic])
(def ^:private sizes {:small [1 1] :medium [2 1] :large [2 2] :grand [3 2]})
(def ^:private large-kinds #{:hall :green :gallery})

(defn- ensure! [ok message data]
  (when-not ok (throw (ex-info message data))))

(defn normalize-topology
  "Validate/normalize display data, not simulation data. Unknown route keys and
  asymmetric travel/open values are retained. :connects-to on reservations adds
  display-only zero-travel links unless that direction is already present."
  [topology]
  (let [locations (vec (:locations topology))
        reservations (mapv #(assoc % :reservation? true) (:reservations topology))
        records (concat locations reservations)
        routes (mapv #(merge {:travel 0 :open? true} %) (:routes topology))]
    (doseq [record records]
      (ensure! (some? (:id record)) "scene location needs an id" {:record record})
      (when-let [size (:size record)]
        (ensure! (or (contains? sizes size)
                     (and (vector? size) (= 2 (count size))
                          (every? pos-int? size)))
                 "invalid scene size" {:id (:id record) :size size})))
    (ensure! (= (count records) (count (set (map :id records))))
             "duplicate scene ids within a boundary" {})
    (doseq [route routes]
      (ensure! (and (some? (:from route)) (some? (:to route))
                    (number? (:travel route)) (<= 0 (:travel route))
                    (boolean? (:open? route)))
               "invalid directed scene route" {:route route}))
    (let [pairs (set (map (juxt :from :to) routes))
          extra (for [r reservations, to (:connects-to r)
                      [a b] [[(:id r) to] [to (:id r)]]
                      :when (not (contains? pairs [a b]))]
                  {:from a :to b :travel 0 :open? true :reservation? true})]
      {:locations locations :reservations reservations
       :routes (vec (concat routes (distinct extra)))})))

(defn- adjacency [routes ids]
  (reduce (fn [adj {:keys [from to travel]}]
            (if (and (zero? travel) (not= from to)
                     (contains? ids from) (contains? ids to))
              (-> adj (update from (fnil conj #{}) to)
                  (update to (fnil conj #{}) from)) adj))
          {} routes))

(defn- components [ids adj order]
  (loop [left (set ids), result []]
    (if (empty? left) result
        (let [root (first (sort-by order left))
              component (loop [todo [root], seen #{}]
                          (if-let [id (peek todo)]
                            (if (contains? seen id)
                              (recur (pop todo) seen)
                              (recur (into (pop todo) (filter left (get adj id)))
                                     (conj seen id))) seen))]
          (recur (set/difference left component) (conj result [root component]))))))

(defn- floor-value [floor] (if (= floor :outside) :ground floor))

(defn- assign-groups [old records routes]
  (let [all (merge (:records old) (into {} (map (juxt :id identity) records)))
        ids (set (keys all))
        order (merge (:order old)
                     (zipmap (map :id records) (range (count (:order old)) (+ (count (:order old)) (count records)))))
        adj (adjacency routes ids)
        existing (or (:rooms old) {})
        explicit (into {} (for [[id r] all :when (some? (:domain r))]
                            [id [:domain (:domain r)]]))
        domainless (remove #(contains? explicit %) ids)
        regions (reduce
                 (fn [m [root component]]
                   ;; A new domainless component may meet several frozen areas: the
                   ;; earliest one wins, but those areas themselves never merge.
                   (let [frozen (->> component (keep existing) (sort-by #(order (:id %))))
                         region (or (:region (first frozen)) [:area root])]
                     (reduce #(assoc %1 %2 region) m component)))
                 explicit (components domainless adj order))
        regions (merge regions (into {} (map (fn [[id r]] [id (:region r)]) existing)))
        floors (into {} (map (fn [[id r]] [id (floor-value (:floor r))]) all))
        floors (merge floors (into {} (map (fn [[id r]] [id (:floor r)]) existing)))
        floor-orders (into {}
                           (for [region (distinct (map regions (sort-by order ids)))]
                             [region (vec (concat known-floors
                                                  (distinct (remove #(or (nil? %) (some #{%} known-floors))
                                                                    (map floors (filter #(= region (regions %)) (sort-by order ids)))))))]))
        groups (into {}
                     (for [r records :let [id (:id r), region (regions id)
                                           floor-order (floor-orders region)
                                           neighbour-floors (keep (fn [n] (when (= region (regions n)) (floors n))) (get adj id))
                                           counts (frequencies neighbour-floors)
                                           region-floors (keep floors (filter #(= region (regions %)) ids))
                                           floor (or (floors id)
                                                     (first (sort-by (fn [f] [(- (get counts f)) (.indexOf floor-order f)]) (keys counts)))
                                                     (when (seq region-floors) :ground))]]
                       [id {:region region :floor floor :panel [region floor]}]))]
    {:groups groups :order order :adj adj :records all :floor-orders floor-orders}))

(defn room-size
  "Size a room from presentation evidence and same-panel undirected neighbours.
  Leaf-heavy hubs grow as corridors; interconnected hubs stay within 3:1."
  [record neighbours adj]
  (let [[w h] (or (get sizes (:size record))
                  (when (vector? (:size record)) (:size record))
                  (when (large-kinds (:kind record)) [2 1]) [1 1])
        degree (count neighbours)
        leaves (count (filter #(<= (count (get adj %)) 1) neighbours))
        corridor? (and (> degree 4) (>= leaves (* 0.7 degree)))]
    (loop [w w, h h]
      (if (>= (* 2 (+ w h)) degree) [w h]
          (if corridor? (recur (inc w) h)
              (if (<= w h) (recur (inc w) h) (recur w (inc h))))))))

(defn overlaps?
  "True only for positive-area intersection; shared walls are allowed."
  [a b]
  (and (< (:x a) (+ (:x b) (:w b))) (< (:x b) (+ (:x a) (:w a)))
       (< (:y a) (+ (:y b) (:h b))) (< (:y b) (+ (:y a) (:h a)))))

(defn touching? [a b]
  (or (and (or (= (+ (:x a) (:w a)) (:x b)) (= (+ (:x b) (:w b)) (:x a)))
           (< (max (:y a) (:y b)) (min (+ (:y a) (:h a)) (+ (:y b) (:h b)))))
      (and (or (= (+ (:y a) (:h a)) (:y b)) (= (+ (:y b) (:h b)) (:y a)))
           (< (max (:x a) (:x b)) (min (+ (:x a) (:w a)) (+ (:x b) (:w b)))))))

(defn- bounds [rects]
  (if (seq rects)
    (let [x (apply min (map :x rects)), y (apply min (map :y rects))]
      {:x x :y y :w (- (apply max (map #(+ (:x %) (:w %)) rects)) x)
       :h (- (apply max (map #(+ (:y %) (:h %)) rects)) y)})
    {:x 0 :y 0 :w 0 :h 0}))

(defn- area [r] (* (:w r) (:h r)))
(defn- gap [a b]
  (+ (max 0 (- (:x a) (+ (:x b) (:w b))) (- (:x b) (+ (:x a) (:w a))))
     (max 0 (- (:y a) (+ (:y b) (:h b))) (- (:y b) (+ (:y a) (:h a))))))

(defn- wall-positions [r w h]
  (concat (for [y (range (- (:y r) h -1) (+ (:y r) (:h r)))]
            [[(- (:x r) w) y] [(+ (:x r) (:w r)) y]])
          (for [x (range (- (:x r) w -1) (+ (:x r) (:w r)))]
            [[x (- (:y r) h)] [x (+ (:y r) (:h r))]])))

(defn- candidates [placed neighbours w h limit]
  (let [rects (vals placed), b (bounds rects)
        ;; This fallback is separated by a full cell from EVERY existing box.
        fallback [(if (seq rects) (+ (:x b) (:w b) 1) 0) (:y b)]
        near (mapcat #(mapcat identity (wall-positions (placed %) w h)) neighbours)
        edge [[(:x b) (+ (:y b) (:h b) 1)]
              [(- (:x b) w 1) (:y b)] fallback]
        positions (if (empty? rects) [[0 0]]
                      (concat (take (dec limit) (distinct (concat near edge))) [fallback]))]
    (->> positions distinct
         (map (fn [[x y]] {:x x :y y :w w :h h}))
         (remove #(some (partial overlaps? %) rects)) vec)))

(defn- placement-score [placed candidate neighbours stairs]
  (let [rects (vals placed), b (bounds rects)
        next-b (bounds (conj (vec rects) candidate))
        neighbour-set (set neighbours)]
    (+ (reduce + 0 (for [id neighbours :let [r (placed id)]]
                     (if (touching? candidate r) 10 (* -2 (gap candidate r)))))
       (- (area b) (area next-b))
       (* 6 (count (filter #(= (select-keys % [:x :y]) (select-keys candidate [:x :y])) stairs)))
       (- (count (for [[id r] placed :when (and (not (neighbour-set id)) (touching? candidate r))] id))))))

(defn- place-panel [frozen records adj order groups other-rooms]
  (let [n (+ (count frozen) (count records))
        ;; Large graphs trade search quality for a strict, reproducible work cap.
        [beam-width limit] (cond (> n 200) [1 16] (> n 80) [2 24] :else [8 100])
        panel-ids (set (concat (keys frozen) (map :id records)))
        neighbours (into {} (for [id panel-ids] [id (set/intersection panel-ids (get adj id #{}))]))
        cross (fn [id] (count (set/difference (get adj id #{}) panel-ids)))
        records-by-id (into {} (map (juxt :id identity) records))]
    (loop [remaining (set (keys records-by-id)), beam [{:placed frozen :score 0}], evaluations 0]
      (if (empty? remaining)
        {:rooms (:placed (first beam)) :evaluations evaluations}
        (let [placed-ids (set (keys (:placed (first beam))))
              id (first (sort-by (fn [id] [(- (count (set/intersection placed-ids (neighbours id))))
                                          (- (count (neighbours id))) (- (cross id)) (order id) (pr-str id)]) remaining))
              [w h] (room-size (records-by-id id) (neighbours id) neighbours)
              stairs (keep other-rooms (get adj id))
              choices (vec (for [{:keys [placed score]} beam
                                 :let [ns (sort-by order (filter #(contains? placed %) (neighbours id)))]
                                 candidate (candidates placed ns w h limit)]
                             {:placed (assoc placed id (merge candidate (groups id) {:id id}))
                              :score (+ score (placement-score placed candidate ns stairs))
                              :tie [(:y candidate) (:x candidate)]}))
              best (->> choices (sort-by (juxt (comp - :score) :tie)) (take beam-width) vec)]
          (recur (disj remaining id) best (+ evaluations (count choices))))))))

(defn extend-placement
  "Add one historical placement boundary to an immutable placement. Existing ids
  retain geometry even when reservation metadata, size or routes later change."
  [placement topology]
  (let [{:keys [locations reservations routes]} (normalize-topology topology)
        updates (into {} (map (juxt :id identity) (concat locations reservations)))
        records (vec (remove #(contains? (:rooms placement) (:id %)) (concat locations reservations)))
        {:keys [groups order adj floor-orders] :as assigned} (assign-groups placement records routes)
        panels (distinct (map #(get-in groups [(:id %) :panel]) records))
        ;; Solve ground/up/down to make same-grid stair bonuses useful. Display
        ;; panels still use basement/ground/first/... order.
        solve-rank (fn [[region floor]] [(apply min (map order (for [[id g] groups :when (= region (:region g))] id)))
                                        (if (= floor :ground) -1 (.indexOf (get floor-orders region) floor))])
        result (reduce
                (fn [state panel]
                  (let [frozen (into {} (filter #(= panel (:panel (val %))) (:rooms state)))
                        additions (filterv #(= panel (get-in groups [(:id %) :panel])) records)
                        solved (place-panel frozen additions adj order groups (:rooms state))]
                    (-> state (update :rooms merge (:rooms solved))
                        (update :evaluations + (:evaluations solved)))))
                (merge {:rooms {} :evaluations 0} placement) (sort-by solve-rank panels))]
    (if (empty? records)
      (update (merge {:rooms {} :evaluations 0 :records {} :order {} :floor-orders {}} placement)
              :records merge updates)
      (assoc result :records (merge (:records assigned) updates) :order order :floor-orders floor-orders))))

(defn place-history
  "Recompute geometry from a normalized historical prefix, with no cache required.
  Later route-only steps are deliberately placement no-ops."
  [{:keys [initial steps]}]
  (reduce extend-placement (extend-placement {} initial) steps))

(defn- pack-panels [placement]
  (let [rooms (:rooms placement), order (:order placement)
        region-order (->> (vals rooms) (group-by :region)
                          (sort-by (fn [[_ rs]] (apply min (map #(order (:id %)) rs)))))
        locals (into {} (for [[panel rs] (group-by :panel (vals rooms))] [panel (bounds rs)]))]
    (mapv (fn [[region rs]]
            (let [floors (get-in placement [:floor-orders region])
                  panels (sort-by #(vector (.indexOf floors (second %)) (pr-str %)) (distinct (map :panel rs)))
                  packed (reduce (fn [{:keys [x panels]} panel]
                                   (let [b (locals panel)]
                                     {:x (+ x (:w b) 2)
                                      :panels (conj panels (merge b {:id panel :x x :y 1
                                                                     :local-bounds b}))}))
                                 {:x 1 :panels []} panels)]
              {:id region :label (if (= :domain (first region)) (str (second region))
                                     (or (get-in placement [:records (second region) :name]) (str (second region))))
               :x 0 :y 0 :w (max 2 (:x packed))
               :h (+ 2 (apply max 0 (map :h (:panels packed)))) :panels (:panels packed)}))
          region-order)))

(defn- pack-regions [regions routes rooms]
  (let [region-map (into {} (map (juxt :id identity) regions))
        links (reduce (fn [m {:keys [from to travel]}]
                        (let [a (:region (rooms from)), b (:region (rooms to))]
                          (if (and a b (not= a b))
                            (-> m (update-in [a b] (fnil min travel) travel)
                                (update-in [b a] (fnil min travel) travel)) m))) {} routes)
        order (zipmap (map :id regions) (range))]
    (loop [remaining (set (keys region-map)), placed {}]
      (if (empty? remaining) placed
          (let [id (first (sort-by (fn [id] [(- (count (get links id))) (order id)]) remaining))
                partner (first (sort-by (fn [p] [(get-in links [id p] Long/MAX_VALUE) (order p)]) (keys placed)))
                r (region-map id)
                b (bounds (vals placed))
                p (placed partner)
                positions (if p [[(+ (:x p) (:w p) 2) (:y p)]
                                 [(:x p) (+ (:y p) (:h p) 2)]
                                 [(- (:x p) (:w r) 2) (:y p)]
                                 [(:x p) (- (:y p) (:h r) 2)]] [[0 0]])
                fallback [(+ (:x b) (:w b) 2) (:y b)]
                candidate (first (remove #(some (partial overlaps? %) (vals placed))
                                         (map (fn [[x y]] (assoc r :x x :y y)) (concat positions [fallback]))))]
            (recur (disj remaining id) (assoc placed id candidate)))))))

(defn- center [r] [(+ (:x r) (/ (:w r) 2.0)) (+ (:y r) (/ (:h r) 2.0))])

(defn- wall-point [r [tx ty]]
  (let [[cx cy] (center r), dx (- tx cx), dy (- ty cy)
        t (min (if (zero? dx) Double/POSITIVE_INFINITY (/ (/ (:w r) 2.0) (abs dx)))
               (if (zero? dy) Double/POSITIVE_INFINITY (/ (/ (:h r) 2.0) (abs dy))))]
    (if (Double/isInfinite t) [cx cy] [(+ cx (* t dx)) (+ cy (* t dy))])))

(defn- clear-segment? [[ax ay] [bx by] rooms]
  (not-any? (fn [r]
              (if (= ax bx)
                (and (< (:x r) ax (+ (:x r) (:w r)))
                     (< (max (min ay by) (:y r)) (min (max ay by) (+ (:y r) (:h r)))))
                (and (< (:y r) ay (+ (:y r) (:h r)))
                     (< (max (min ax bx) (:x r)) (min (max ax bx) (+ (:x r) (:w r))))))) rooms))

(defn- connector-path [a b rooms]
  (let [start (wall-point a (center b)), end (wall-point b (center a))
        [sx sy] start, [ex ey] end
        bends [[ex sy] [sx ey]]]
    (or (some (fn [bend] (when (and (clear-segment? start bend rooms)
                                   (clear-segment? bend end rooms)) [start bend end])) bends)
        [start end])))

(defn connections
  "Render CURRENT directed routes against frozen geometry. No placement search.
  :kind is :door/:connector/:stair/:road/:loop/:unresolved. Each entry retains the
  complete :route verbatim; absence of reverse travel is never fabricated."
  [rooms routes]
  (mapv (fn [index route]
          (let [a (rooms (:from route)), b (rooms (:to route))
                kind (cond (not (and a b)) :unresolved
                           (= (:from route) (:to route)) :loop
                           (or (pos? (:travel route)) (not= (:region a) (:region b))) :road
                           (not= (:panel a) (:panel b)) :stair
                           (touching? a b) :door
                           :else :connector)
                path (when (and a b)
                       (if (= kind :loop)
                         (let [[x y] (center (:global a))]
                           [[x y] [(+ x 0.4) (- y 0.4)] [(+ x 0.4) (+ y 0.4)] [x y]])
                         (connector-path (:global a) (:global b) (map :global (vals rooms)))))
                length (reduce + 0 (map (fn [[[x y] [xx yy]]]
                                         (Math/sqrt (+ (* (- xx x) (- xx x)) (* (- yy y) (- yy y)))))
                                       (partition 2 1 path)))]
            {:id index :from (:from route) :to (:to route) :route route :kind kind
             :path path :length length})) (range) routes))

(defn present
  "Pack frozen local geometry and render current topology. Omitting :current in
  `layout` uses the final boundary's topology. Metadata may change, geometry not."
  [placement topology]
  (let [{:keys [locations reservations routes]} (normalize-topology topology)
        metadata (merge (:records placement) (into {} (map (juxt :id identity) (concat reservations locations))))
        region-boxes (pack-regions (pack-panels placement) routes (:rooms placement))
        panels (into {} (for [[region r] region-boxes, p (:panels r)]
                          [(:id p) (-> p (update :x + (:x r)) (update :y + (:y r)) (assoc :region region))]))
        rooms (into {} (for [[id r] (:rooms placement)
                              :let [panel (panels (:panel r)), b (:local-bounds panel)
                                    global (-> (select-keys r [:x :y :w :h])
                                               (update :x + (- (:x panel) (:x b)))
                                               (update :y + (- (:y panel) (:y b))))]]
                          [id (merge (metadata id) r {:global global
                                                     :outside? (= :outside (:floor (metadata id)))})]))
        cs (connections rooms routes)
        eligible (filter #(#{:door :connector} (:kind %)) cs)
        doors (count (filter #(= :door (:kind %)) eligible))
        b (bounds (vals region-boxes))]
    {:placement placement :rooms rooms :panels panels :regions region-boxes
     :connections cs :bounds b
     :quality {:directed-connections (count cs) :doors doors
               :door-share (if (seq eligible) (/ (double doors) (count eligible)) 0.0)
               :connector-length (reduce + 0 (map :length (filter #(not= :door (:kind %)) cs)))
               :unresolved (count (filter #(= :unresolved (:kind %)) cs))
               :bounding-area (area b) :candidate-evaluations (:evaluations placement)}}))

(defn layout
  "Pure end-to-end entry point. See namespace contract and scene-layout-api.md."
  [{:keys [initial steps current] :as history}]
  (present (place-history history) (or current (last steps) initial)))
