(ns manor.tools.scene
  "Read-only operator scene: coherent snapshots, generic history and SVG rendering.
  Scenario-specific reservations and size evidence belong to the temporal adapter."
  (:require [clojure.string :as str]
            [manor.clock :as clock]
            [manor.mechanics.items :as items]
            [manor.mechanics.positions :as positions]
            [manor.mechanics.space :as space]
            [manor.scene-layout :as layout]
            [manor.tools.scene-history :as history]
            [manor.tools.scene-time :as time]
            [manor.world :as w]))

(defn snapshot
  "Capture matching world/events under the engine's existing emit lock. Status and
  input are advisory UI values captured at that instant; never enqueue a command."
  [run]
  (locking (:world run)
    {:id (:id run) :scenario (:scenario run) :world @(:world run)
     :events @(:events run) :status @(:status run) :input @(:input run)}))

(defn topology
  "Ordered generic display topology from an existing world."
  [world]
  (history/topology world))

(defn placement-history
  "Historical placement with optional event-indexed presentation annotations.
  Routes known later cannot reinterpret earlier local geometry."
  ([events] (placement-history events nil))
  ([events annotations] (:history (history/project events annotations))))

(defn presentation
  "Project current connections separately from immutable historical placement.
  Optional single-entry cache is UI-owned, bounded, and safe to discard."
  ([snap] (presentation snap nil))
  ([{:keys [world events scenario]} cache]
   (let [annotations (when-let [f (get-in scenario [:scene :history])] (f events))
         {:keys [history reservations overlays]} (history/project events annotations)
         cached (when cache @cache)
         placement (if (= history (:history cached)) (:placement cached)
                       (layout/place-history history))
         current (-> (topology world)
                     (update :locations #(mapv (fn [loc] (merge loc (get overlays (:id loc)))) %))
                     (assoc :reservations reservations))]
     (when (and cache (not= history (:history cached)))
       (reset! cache {:history history :placement placement}))
     (layout/present placement current))))

(defn label [x] (if (keyword? x) (name x) (str x)))
(defn- region-label [id region]
  (label (if (= :domain (first id)) (second id) (:label region))))
(defn- rect [{:keys [x y w h]}] {:x x :y y :width w :height h})
(defn- clip [s n] (let [s (str s)] (if (> (count s) n) (str (subs s 0 (dec n)) "…") s)))
(defn- selection [kind id] {:data-kind kind :data-id (str id) :tabindex 0 :role "button"})
(defn- initials [s]
  (->> (str/split (str s) #"\s+") (remove str/blank?) (take 2) (map first) (apply str) str/upper-case))

(defn- travel-label [world travel]
  (if-let [c (:clock world)] (str (* travel (:minutes-per-round c)) " min")
          (str travel " rounds")))

(defn- agent-tooltip [world ag]
  (str (:name ag) " · " (label (:role ag)) " · " (label (:status ag))
       (when-let [{:keys [to arrives]} (:travel ag)]
         (str " · travelling to " (or (:name (space/location world to)) to)
              (when (number? arrives)
                (str " · arrives " (if-let [c (:clock world)] (clock/label c arrives) (str "r" arrives))))))
       (when-let [busy (:busy ag)]
         (str " · " (:activity busy)
              (when-let [until (:until busy)]
                (str " until " (if-let [c (:clock world)] (clock/label c until) (str "r" until))))))))

(defn- token [snap ag x y]
  (let [world (:world snap)
        standing (:standing (positions/position world (:id ag)))
        color (nth ["#96c9ee" "#e6b6e8" "#9bd5b2" "#f4cf86" "#c5bcf6"]
                   (mod (hash (or standing (:role ag))) 5))
        asleep? (get-in ag [:busy :asleep?])
        acting? (contains? (set (get-in snap [:status :acting])) (:id ag))
        progress (time/busy-progress snap ag)]
    [:g (merge (selection "agent" (:id ag))
               {:class (str "person" (when asleep? " asleep") (when acting? " acting"))
                :transform (str "translate(" x " " y ")")})
     [:title (str (agent-tooltip world ag)
                  (when (some? progress) (format " · scheduled time %.0f%% elapsed" (* 100.0 progress))))]
     (if (= :human (:controller ag))
       [:rect {:x -0.13 :y -0.13 :width 0.26 :height 0.26 :rx 0.025 :fill color}]
       [:circle {:r 0.14 :fill color}])
     (when (:busy ag)
       [:circle (cond-> {:class (if (some? progress) "busy-progress" "busy-mark")
                        :r 0.17 :fill "none" :stroke "#eebc69" :stroke-width 0.035}
                  (some? progress) (assoc :pathLength 1 :stroke-dasharray (str (double progress) " 1")
                                          :transform "rotate(-90)" :data-progress (double progress)))])
     [:text {:text-anchor "middle" :y 0.045 :font-size 0.12 :fill "#18202a"} (initials (:name ag))]
     (when asleep? [:text {:x 0.12 :y -0.1 :font-size 0.12} "z"])]))

(defn- connection [world rooms {:keys [id path route kind]}]
  (when (seq path)
    (let [[x y] (first path) [ex ey] (last path)
          center (fn [id] (let [{:keys [x y w h]} (get-in rooms [id :global])]
                            [(+ x (/ w 2)) (+ y (/ h 2))]))
          [ax ay] (center (:from route)) [bx by] (center (:to route))
          door? (= :door kind)
          a (get-in rooms [(:from route) :global])
          b (get-in rooms [(:to route) :global])
          vertical-wall? (and door? (or (= (+ (:x a) (:w a)) (:x b))
                                           (= (+ (:x b) (:w b)) (:x a))))
          dx (if door? (if vertical-wall? (compare bx ax) 0) (- bx ax))
          dy (if door? (if vertical-wall? 0 (compare by ay)) (- by ay))
          length (Math/hypot dx dy)
          ux (if (zero? length) 1 (/ dx length)) uy (if (zero? length) 0 (/ dy length))
          ;; Opposite directions use opposite lanes. Shared-wall doors need an
          ;; extended arrow rather than a zero-length line.
          points (if (and (= :door kind) (= [x y] [ex ey]))
                   [[(- x (* ux 0.16)) (- y (* uy 0.16))]
                    [(+ x (* ux 0.16)) (+ y (* uy 0.16))]] path)
          points (map (fn [[px py]] [(- px (* uy 0.045)) (+ py (* ux 0.045))]) points)
          mx (/ (+ x ex) 2) my (/ (+ y ey) 2)]
      [:g {:class (str "connection " (name kind) (when-not (:open? route) " closed")
                      (when (:reservation? route) " planned"))
           :data-from (str (:from route)) :data-to (str (:to route))}
       [:title (str (label (or (get-in rooms [(:from route) :name]) (:from route))) " → "
                    (label (or (get-in rooms [(:to route) :name]) (:to route))) " · " (name kind)
                    " · " (travel-label world (:travel route)) (when-not (:open? route) " · closed")
                    (when (:reservation? route) " · planned connection"))]
       [:polyline {:data-route (str id) :points (str/join " " (map #(str/join "," %) points))
                   :fill "none" :marker-end "url(#scene-route-arrow)"}]
       (when (= :door kind)
         [:line {:x1 (- mx 0.08) :x2 (+ mx 0.08) :y1 (- my 0.08) :y2 (+ my 0.08)
                 :stroke-width 0.06}])
       (when (= :stair kind)
         [:text {:x x :y y :font-size 0.22} "↕"])
       (when (= :road kind)
         [:text {:x (- mx (* uy 0.18)) :y (- (+ my (* ux 0.18)) 0.06)
                 :font-size 0.16 :text-anchor "middle"}
          (travel-label world (:travel route))])])))

(defn- room [snap id r]
  (let [world (:world snap) g (:global r)
        count-items (count (items/items-at world id))
        jobs (seq (filter #(= id (:place %)) (items/jobs world)))
        closed? (time/closed? world r)
        badges (when-let [f (get-in snap [:scenario :scene :badges])] (f world id))
        badge-text (str (when (pos? count-items) (str count-items " items "))
                        (when (seq (:aspects r)) "◆ ") (when jobs "⚒ ")
                        (when closed? "closed ")
                        (when (:reservation? r)
                          (str "⚒ done " (if-let [due (:due r)]
                                           (if-let [c (:clock world)] (clock/short-label c due (:round world))
                                                   (str "r" due)) "time unknown") " "))
                        (str/join " · " badges))]
    [:g
     [:g (merge (selection "place" id) {:class (str "room" (when (:outside? r) " outside")
                                               (when (:reservation? r) " ghost") (when closed? " closed-shop"))})
      [:title (str (or (:name r) id) "\n" (:desc r)
                   (when (seq (:aspects r)) (str "\n" (pr-str (:aspects r))))
                   (when closed? "\nClosed")
                   (when (seq badges) (str "\n" (str/join " · " badges)))
                   (when (:reservation? r)
                     (str "\nUnder construction" (when-let [due (:due r)]
                                                  (str " · done " (if-let [c (:clock world)]
                                                                   (clock/label c due) (str "r" due)))))))]
      [:rect (rect g)]
      [:text {:x (+ (:x g) 0.06) :y (+ (:y g) 0.26) :font-size 0.16
              :textLength (min (- (:w g) 0.12) (* 0.088 (count (str (or (:name r) id)))))
              :lengthAdjust "spacingAndGlyphs"}
       (clip (or (:name r) id) 42)]
      [:text {:x (+ (:x g) 0.06) :y (+ (:y g) 0.44) :font-size 0.12 :class "badges"
              :textLength (min (- (:w g) 0.12) (* 0.066 (count badge-text)))
              :lengthAdjust "spacingAndGlyphs"}
       badge-text]]]))

(defn- room-people [snap id r]
  (let [g (:global r)
        people (filter #(and (= :active (:status %)) (not (:travel %)) (= id (:location %)))
                       (w/agents-in-order (:world snap)))
        visible-count (if (> (count people) 6) 5 6)]
    [:g.room-people
     (map-indexed (fn [i ag]
                    (token snap ag (+ (:x g) 0.21 (* 0.3 (mod i 3)))
                           (+ (:y g) 0.6 (* 0.24 (quot i 3))))) (take visible-count people))
     (when (> (count people) visible-count)
       [:text (merge (selection "place" id)
                     {:x (+ (:x g) 0.81) :y (+ (:y g) 0.885) :font-size 0.13
                      :text-anchor "middle" :class "overflow"})
        [:title (str/join ", " (map :name (drop visible-count people)))]
        (str "+" (- (count people) visible-count))])]))

(defn- point-on-path [path fraction]
  (let [segments (map (fn [[a b]] [a b (Math/hypot (- (first b) (first a)) (- (second b) (second a)))])
                      (partition 2 1 path))
        total (reduce + 0 (map #(nth % 2) segments))]
    (loop [[[a b length] & more] segments distance (* fraction total)]
      (cond (nil? a) (first path)
            (and (seq more) (> distance length)) (recur more (- distance length))
            :else (let [f (if (zero? length) 0 (/ distance length))]
                    (mapv #(+ %1 (* f (- %2 %1))) a b))))))

(defn- travellers [snap result]
  (for [ag (w/active-agents (:world snap)) :when (:travel ag)
        :let [{:keys [from to arrives]} (:travel ag)
              departure (last (filter #(and (= :agent/departed (:type %)) (= (:id ag) (:actor %))
                                           (= (:travel ag) (select-keys (:data %) [:from :to :arrives])))
                                      (:events snap)))
              start (:round departure)
              fraction (if (and (number? start) (number? arrives) (> arrives start))
                         (max 0 (min 1 (/ (- (get-in snap [:world :round]) start) (- arrives start)))) 0)
              route (first (filter #(and (= from (:from %)) (= to (:to %)) (seq (:path %))) (:connections result)))
              path (or (:path route)
                       (when-let [r (get-in result [:rooms from :global])]
                         [[(+ (:x r) 0.5) (+ (:y r) 0.5)]]))]
        :when (seq path) :let [[x y] (point-on-path path fraction)]]
    (token snap ag x y)))

(defn svg
  "Hiccup SVG. All ids/labels are escaped data, never selectors or inline code."
  [snap {:keys [bounds regions panels rooms connections] :as result}]
  (let [{:keys [x y w h]} bounds
        box (str/join " " [(- x 0.5) (- y 0.5) (max 4 (+ w 1)) (max 3 (+ h 1))])]
    [:svg#scene-map {:xmlns "http://www.w3.org/2000/svg" :viewBox box :data-fit box
                     :aria-label "Operator scene" :role "img"}
     [:defs [:marker {:id "scene-route-arrow" :viewBox "0 0 10 10" :refX 9 :refY 5
                      :markerWidth 5 :markerHeight 5 :orient "auto-start-reverse"}
             [:path {:d "M 0 0 L 10 5 L 0 10 z" :fill "#9cacbf"}]]]
     (when (empty? rooms) [:text {:x 0 :y 1 :font-size 0.22 :fill "#ddd"} "This scenario has no locations."])
     (for [[id r] (sort-by (comp pr-str key) regions)]
       [:g.region {:data-region (pr-str id) :data-box (str/join " " [(:x r) (:y r) (:w r) (:h r)])}
        [:rect (rect r)]
        [:text {:x (+ (:x r) 0.1) :y (+ (:y r) 0.3) :font-size 0.22} (region-label id r)]])
     (for [[[_ floor] p] (sort-by (comp pr-str key) panels)]
       [:g.floor [:rect (merge (rect p) {:fill "none" :stroke "#536071" :stroke-width 0.015})]
        [:text {:x (:x p) :y (- (:y p) 0.1) :font-size 0.17} (label (or floor :places))]])
     (for [[id r] (sort-by (comp pr-str key) rooms)] (room snap id r))
     ;; Even the straight fallback and self-loops must remain visible. Lines
     ;; follow room edges where possible and are painted over fills, under people.
     (for [c connections] (connection (:world snap) rooms c))
     (for [[id r] (sort-by (comp pr-str key) rooms)] (room-people snap id r))
     (travellers snap result)]))

(defn away-strip [snap result]
  [:div.away-strip
   (for [ag (w/agents-in-order (:world snap))
         :when (and (not= :gm (:role ag))
                    (or (not= :active (:status ag))
                        (not (contains? (:rooms result) (or (:location ag) (get-in ag [:travel :from]))))))]
     [:button.alt (merge (selection "agent" (:id ag)) {:title (agent-tooltip (:world snap) ag)})
      (:name ag) " · " (if (= :active (:status ag))
                         (if (:travel ag) "travelling · location unavailable" "unplaced")
                         (label (:status ag)))])])

(defn navigation [result]
  [:nav.scene-navigation {:aria-label "Scene navigation"}
   [:button {:type "button" :data-view "fit"} "Fit"]
   [:button {:type "button" :data-view "in" :aria-label "Zoom in"} "+"]
   [:button {:type "button" :data-view "out" :aria-label "Zoom out"} "−"]
   (for [[id r] (sort-by (comp pr-str key) (:regions result))]
     [:button.alt {:type "button" :data-region-target (pr-str id)
                   :data-box (str/join " " [(:x r) (:y r) (:w r) (:h r)])}
      (region-label id r)])])

(defn toolbar [{:keys [id world status input scenario]}]
  (let [{:keys [state mode remaining acting pause-reasons error last-human-feedback]} status]
    [:section.scene-toolbar
     [:div [:b id] " · round " [:b (:round world)]
      (when-let [c (:clock world)] (str " · " (clock/label c (:round world))))
      " · " (label state) " / " (label mode) (when remaining (str " (" remaining " left)"))
      (format " · cost $%.4f" (double (w/total-cost world)))]
     (when-let [f (get-in scenario [:scene :toolbar])] (f world))
     [:div.controls
      [:form.inline {:method "post" :action "/cmd/step" :data-command true} [:button "Step"]] " "
      [:form.inline {:method "post" :action "/cmd/auto" :data-command true}
       [:input {:name "n" :size 3 :placeholder "∞" :aria-label "Rounds"}] " " [:button "Auto"]] " "
      [:form.inline {:method "post" :action "/cmd/pause" :data-command true} [:button.alt "Pause"]]
      " " [:a {:href "/panel"} "Full controls"]]
     (when (seq acting) [:div "acting: " (str/join ", " acting)])
     (when (seq pause-reasons) [:div.warning "paused: " (str/join "; " pause-reasons)])
     (when error [:div.error "error: " error])
     (when input [:div.warning "Waiting for " (:agent input) " · " [:a {:href "/panel#status"} "Act on the panel"]
                  (when-let [feedback (:feedback input)] (str " · " feedback))])
     (when last-human-feedback [:div "last human action: " last-human-feedback])]))
