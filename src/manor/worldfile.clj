(ns manor.worldfile
  "World files: a scenario's starting world as EDN data instead of code.

    {:meta {:name \"Claudeworth Manor\"
            :start \"1904-10-15T08:00\"       ; optional clock (manor.clock)
            :minutes-per-round 30}
     :locations [{:id \"entrance-hall\" :name \"Entrance hall\" :desc \"...\"
                  :connects-to [\"study\" \"drawing-room\"]   ; next door: :travel 0
                  :domain :house :floor :ground}            ; any other keys are kept
                 ...]
     :routes [[\"carriage-sweep\" \"village-green\" 1]       ; journeys, as in :space
              [\"cliff\" \"beach\" 1 :one-way]]
     :agents [{:name \"Hale\" :role :secretary :location \"study\"}]
     :items [{:id \"pocket-watch\" :name \"Pocket watch\" :holder \"Hale\"   ; optional, see manor.items
              :owner \"Hale\" :condition \"keeping good time\" :desc \"...\"}]
     ...}                                                   ; anything else is the scenario's

  `:connects-to` is undirected: listing a neighbour on either side is enough.
  Items name their :holder (and an :owner who is an agent) by agent name;
  loading turns those into agent ids, and exporting turns them back.
  `load-world` reads and checks a file and returns the parts a scenario's
  :init needs. The file is a template: nothing writes to it. The engine keeps
  a copy of it in the run directory (world-start.edn) and writes the live map
  back in the same format (map.edn) whenever the map changes, see `export`."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [manor.builtins :as b]
            [manor.clock :as clock]
            [manor.items :as items]
            [manor.world :as w]))

(def Location
  [:map
   [:id [:re #"^[a-z0-9][a-z0-9_-]*$"]]
   [:name [:string {:min 1}]]
   [:desc {:optional true} :string]
   [:connects-to {:optional true} [:vector :string]]])

(def WorldFile
  [:map
   [:meta {:optional true} :map]
   [:locations [:vector {:min 1} Location]]
   [:routes {:optional true} [:vector [:vector :any]]]
   [:agents {:optional true} [:vector [:map [:name :string] [:role :keyword] [:location {:optional true} :string]]]]
   [:items {:optional true} [:vector :map]]])

;; ---------------------------------------------------------------------------
;; Reading and checking

(defn read-file
  "Read EDN from a file path, or else a classpath resource of that name."
  [source]
  (let [f (io/file (str source))
        r (when-not (.exists f) (io/resource (str source)))]
    (when-not (or (.exists f) r)
      (throw (ex-info (str "no world file " source " (looked for a file and a classpath resource)") {:source source})))
    (try (edn/read-string (slurp (or r f)))
         (catch Exception e
           (throw (ex-info (str "world file " source " is not valid EDN: " (ex-message e)) {:source source} e))))))

(defn ->clock
  "The clock from :meta, if the file sets a :start time."
  [{:keys [meta]}]
  (when (:start meta) (select-keys meta [:start :minutes-per-round])))

(defn- route-problems [known [from to travel one-way :as r]]
  (let [where (str "route " (pr-str r) ": ")]
    (cond
      (not (<= 3 (count r) 4)) [(str where "routes look like [from to rounds] or [from to rounds :one-way]")]
      :else (concat
             (for [id [from to] :when (not (known id))] (str where "unknown location " (pr-str id)))
             (when (= from to) [(str where "a route needs two different locations")])
             (when-not (nat-int? travel) [(str where "rounds must be a whole number, 0 or more")])
             (when (and (= 4 (count r)) (not= :one-way one-way)) [(str where "the fourth element can only be :one-way")])))))

(defn problems
  "Everything wrong with a world file, as readable strings (empty if fine)."
  [wf]
  (if-let [e (m/explain WorldFile wf)]
    [(str "malformed: " (pr-str (me/humanize e)))]
    (let [ids (map :id (:locations wf))
          known (set ids)]
      (vec (concat
            (for [[id n] (frequencies ids) :when (> n 1)]
              (str "location " (pr-str id) " is defined " n " times"))
            (for [{:keys [id connects-to]} (:locations wf)
                  to connects-to
                  :when (or (not (known to)) (= to id))]
              (str "location " (pr-str id) ": :connects-to "
                   (if (= to id) "names the location itself" (str "names unknown location " (pr-str to)))))
            (mapcat #(route-problems known %) (:routes wf))
            (for [{:keys [name location]} (:agents wf)
                  :when (and location (not (known location)))]
              (str "agent " (pr-str name) ": unknown location " (pr-str location)))
            (when-let [c (->clock wf)] (map #(str ":meta clock: " %) (clock/problems c)))
            (items/problems (:items wf) {:places known :holders (set (map :name (:agents wf)))}))))))

(defn ->space
  "The :space for a scenario's :init: locations (without :connects-to) and
  routes, with each :connects-to pair as a :travel 0 route."
  [{:keys [locations routes]}]
  {:locations (mapv #(dissoc % :connects-to) locations)
   :routes (concat (distinct (for [{:keys [id connects-to]} locations
                                   to connects-to]
                               (conj (vec (sort [id to])) 0)))
                   routes)})

(defn ->items
  "The file's items for a scenario's :init: a :holder, and an :owner that
  names an agent of the file, become agent ids. Other owners (\"household\")
  are kept as they are."
  [{:keys [agents] :as wf}]
  (let [ids (into {} (map (juxt :name (comp b/slug :name))) agents)]
    (mapv (fn [it]
            (cond-> it
              (:holder it) (update :holder ids)
              (contains? ids (:owner it)) (update :owner ids)))
          (:items wf))))

(defn load-world
  "Read and check a world file (a path, or a classpath resource such as
  \"worlds/claudeworth.edn\"). Throws with a list of problems if it is
  wrong. Returns {:space :clock :agents :items :world-file}; all but the
  last are ready for a scenario's :init (:clock, :agents and :items only if
  the file has them), and :world-file is the data as read (the scenario
  reads its own sections from it; the engine saves a copy)."
  [source]
  (let [wf (read-file source)]
    (when-let [ps (seq (problems wf))]
      (throw (ex-info (str "world file " source " has problems:\n- " (str/join "\n- " ps)) {:problems (vec ps)})))
    (cond-> {:space (->space wf) :world-file wf}
      (->clock wf) (assoc :clock (->clock wf))
      (:agents wf) (assoc :agents (:agents wf))
      (:items wf) (assoc :items (->items wf)))))

;; ---------------------------------------------------------------------------
;; Exporting the live map

(defn- export-routes
  "Journeys (and one-way routes) of the live map, as route vectors. Routes
  that were in `start` keep their place and direction; new ones follow."
  [routes start two-way?]
  (let [route-key (fn [a b two?] (if two? #{a b} [a b]))
        orig (into {} (map-indexed (fn [i [from to _ one-way]]
                                     [(route-key from to (not= :one-way one-way)) [i from to]])
                                   (:routes start)))
        current (for [[a tos] (sort-by key routes)
                      [b r] (sort-by key tos)
                      :when (:open? r)
                      :let [two? (two-way? a b)]
                      :when (not (and two? (or (zero? (:travel r)) (pos? (compare a b)))))]
                  (let [[i from to] (get orig (route-key a b two?) [Long/MAX_VALUE a b])]
                    [i (if two? [from to (:travel r)] [from to (:travel r) :one-way])]))]
    (mapv second (sort-by first current))))

(defn- export-items
  "The live items, in `start`'s order with new ones after, holders and agent
  owners as names again."
  [world start]
  (let [agent-name #(:name (w/agent world %))
        live (into {} (map (juxt :id identity)) (items/all world))
        orig (map :id (:items start))
        order (concat (filter live orig) (sort (remove (set orig) (keys live))))]
    (vec (for [id order
               :let [it (live id)]]
           (cond-> it
             (:holder it) (update :holder #(or (agent-name %) %))
             (agent-name (:owner it)) (update :owner agent-name))))))

(defn export
  "The world file `start` with its :locations and :routes replaced by the
  live world's map, and its :items by the live items if the world has items.
  Locations, routes and items keep the order they had in `start`; new ones
  follow. Closed routes are left out."
  [world start]
  (let [locs (get-in world [:space :locations])
        routes (get-in world [:space :routes])
        orig (map :id (:locations start))
        order (concat (filter locs orig) (sort (remove (set orig) (keys locs))))
        travel (fn [a b] (let [r (get-in routes [a b])] (when (:open? r) (:travel r))))
        two-way? (fn [a b] (and (travel a b) (= (travel a b) (travel b a))))
        connects (fn [id] (vec (sort (for [[to r] (get routes id)
                                           :when (and (= 0 (:travel r)) (two-way? id to))]
                                       to))))]
    (cond-> (assoc start
                   :locations (vec (for [id order
                                         :let [c (connects id)]]
                                     (cond-> (get locs id) (seq c) (assoc :connects-to c))))
                   :routes (export-routes routes start two-way?))
      (items/enabled? world) (assoc :items (export-items world start)))))

(def ^:private key-order [:id :name])

(def ^:private item-key-order [:id :name :place :in :holder :owner :fixed? :consumable? :container :condition :desc])

(defn- location-str
  "One location per line: :id and :name first, :connects-to, :desc and
  :detail last."
  [loc]
  (let [last-keys [:connects-to :desc :detail]
        ks (concat key-order
                   (sort (remove (set (concat key-order last-keys)) (keys loc)))
                   last-keys)]
    (str "{" (str/join " " (for [k ks :when (contains? loc k)] (str (pr-str k) " " (pr-str (get loc k))))) "}")))

(defn- item-str
  "One item per line: :id and :name first, :condition and :desc last."
  [it]
  (str "{" (str/join " " (for [k item-key-order :when (contains? it k)] (str (pr-str k) " " (pr-str (get it k))))) "}"))

(defn ->edn-string
  "A world file as readable EDN: sections in a fixed order, one location and
  one item per line."
  [wf]
  (binding [*print-namespace-maps* false]
    (let [pretty #(str/replace (str/trim (with-out-str (pp/pprint %))) "\n" "\n ")
          section (fn [k]
                    (case k
                      :locations (str ":locations\n [" (str/join "\n  " (map location-str (:locations wf))) "]")
                      :routes (str ":routes\n " (pr-str (vec (:routes wf))))
                      :items (str ":items\n [" (str/join "\n  " (map item-str (:items wf))) "]")
                      (str (pr-str k) "\n " (pretty (get wf k)))))
          fixed [:meta :locations :routes :agents :items]
          ks (concat (filter #(contains? wf %) fixed)
                     (sort (remove (set fixed) (keys wf))))]
      (str "{" (str/join "\n\n " (map section ks)) "}\n"))))
