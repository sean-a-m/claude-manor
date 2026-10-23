(ns manor.tools.scene-preview
  "Presentation-only SVG preview. Never starts a run or opens a server."
  (:require [clojure.string :as str]
            [hiccup2.core :as h]
            [manor.io.worldfile :as worldfile]
            [manor.scene-layout :as layout]))

(defn world-file-topology
  "Convert a world-file value to directed display input using its existing space
  conversion. This is for previews, not a simulation initializer/history adapter."
  [world-file]
  (let [{:keys [locations routes]} (worldfile/->space world-file)]
    {:locations locations
     :routes (vec (mapcat (fn [[from to travel one-way]]
                           (cond-> [{:from from :to to :travel travel :open? true}]
                             (not= one-way :one-way)
                             (conj {:from to :to from :travel travel :open? true}))) routes))}))

(defn- title [x] (if (keyword? x) (name x) (str x)))
(defn- rect [{:keys [x y w h]}] {:x x :y y :width w :height h})

(defn svg
  "An escaped, inspectable basic SVG string from a scene-layout/layout result.
  Grid coordinates remain in viewBox units. It deliberately contains no people,
  web controls, inspector links or scenario-specific rendering."
  [{:keys [bounds regions panels rooms connections quality]}]
  (let [{:keys [x y w h]} bounds]
    (str (h/html
          [:svg {:xmlns "http://www.w3.org/2000/svg"
                 :viewBox (str/join " " [(- x 1) (- y 1) (max 4 (+ w 2)) (max 3 (+ h 2))])
                 :width (* 90 (max 4 (+ w 2))) :height (* 60 (max 3 (+ h 2)))}
           [:title (str "Scene layout " (pr-str quality))]
           [:defs [:marker {:id "route-arrow" :viewBox "0 0 10 10" :refX 9 :refY 5
                            :markerWidth 5 :markerHeight 5 :orient "auto-start-reverse"}
                   [:path {:d "M 0 0 L 10 5 L 0 10 z" :fill "#586878"}]]]
           (for [[id r] (sort-by (comp pr-str key) regions)]
             [:g [:rect (merge (rect r) {:fill "#f3f4f5" :stroke "#b4bbc4" :stroke-width 0.03})]
              [:text {:x (+ (:x r) 0.1) :y (+ (:y r) 0.3) :font-size 0.22}
               (or (:label r) (title (second id)))]])
           (for [[[_ floor] p] (sort-by (comp pr-str key) panels)]
             [:text {:x (:x p) :y (- (:y p) 0.15) :font-size 0.18} (title (or floor :places))])
           ;; Lines are behind rooms. Doors/stairs are marked again above them.
           (for [{:keys [id path route kind]} connections :when (seq path)]
             [:polyline {:data-route id :points (str/join " " (map #(str/join "," %) path))
                         :fill "none" :stroke (if (:open? route) "#586878" "#b22")
                         :stroke-width 0.045 :stroke-dasharray (when-not (:open? route) "0.12 0.08")
                         :marker-end "url(#route-arrow)"}
              [:title (str (name kind) " " (pr-str route))]])
           (for [[id r] (sort-by (comp pr-str key) rooms) :let [g (:global r)]]
             [:g {:data-kind "place" :data-id (str id)}
              [:rect (merge (rect g) {:fill (if (:outside? r) "#e4eedb" "#fff")
                                     :stroke "#38424c" :stroke-width 0.025
                                     :stroke-dasharray (when (:reservation? r) "0.1 0.08")})]
              [:title (str (or (:name r) id) " " (pr-str (select-keys r [:floor :domain :size])))]
              [:text {:x (+ (:x g) 0.07) :y (+ (:y g) 0.3) :font-size 0.16}
               (subs (str (or (:name r) id)) 0 (min 22 (count (str (or (:name r) id)))))]])
           (for [{:keys [path kind route]} connections :when (and (seq path) (#{:door :stair :road} kind))
                 :let [[a b] [(first path) (last path)]
                       mx (/ (+ (first a) (first b)) 2) my (/ (+ (second a) (second b)) 2)]]
             [:g
              (when (= :door kind)
                [:circle {:cx mx :cy my :r 0.075 :fill (if (:open? route) "#198754" "#b22")}])
              (when (= :stair kind)
                [:text {:x (first a) :y (second a) :font-size 0.2} "↕"])
              (when (and (= :road kind) (pos? (:travel route)))
                [:text {:x mx :y my :font-size 0.16} (str (:travel route) " rounds")])])]))))

(defn scene-preview!
  "Write a world file's layout to SVG without a server. Returns path and quality.
  `(scene-preview! \"resources/worlds/claudeworth.edn\" \"/tmp/manor.svg\")`."
  ([source] (scene-preview! source (str source ".svg")))
  ([source output]
   (let [result (layout/layout {:initial (world-file-topology (worldfile/read-file source))})]
     (spit output (svg result))
     {:path (str output) :quality (:quality result)})))
