(ns manor.worldfile
  "World files: a scenario's starting world as EDN data instead of code.

    {:meta {:name \"Claudeworth Manor\"
            :start \"1904-10-15T08:00\"       ; optional clock (manor.clock)
            :minutes-per-round 30}
     :locations [{:id \"entrance-hall\" :name \"Entrance hall\" :desc \"...\"
                  :connects-to [\"study\" \"drawing-room\"]   ; next door: :travel 0
                  :aspects {:cleanliness \"dusty\"}          ; optional, see manor.items
                  :domain :house :floor :ground}            ; any other keys are kept
                 ...]
     :routes [[\"carriage-sweep\" \"village-green\" 1]       ; journeys, as in :space
              [\"cliff\" \"beach\" 1 :one-way]]
     :agents [{:name \"Hale\" :role :secretary :location \"study\"}]
     :items [{:id \"pocket-watch\" :name \"Pocket watch\" :holder \"Hale\"   ; optional, see manor.items
              :owner \"Hale\" :aspects {:state \"keeping good time\"} :desc \"...\"}]
     ...}                                                   ; anything else is the scenario's

  `:connects-to` is undirected: listing a neighbour on either side is enough.
  Items name their :holder (and an :owner who is an agent) by agent name;
  loading turns those into agent ids, and exporting turns them back.
  `load-world` reads and checks a file and returns the parts a scenario's
  :init needs. The file is a template: no run writes to it (`set-agent-keys`
  is for the REPL's roll-cast!, see manor.people). The engine keeps
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
   [:aspects {:optional true} :any]          ; checked by `problems`, for a readable message
   [:connects-to {:optional true} [:vector :string]]])

(def Agent
  "An agent of a world file. The trait keys (see manor.people) are optional;
  `roll-cast!` writes them."
  [:map
   [:name :string]
   [:role :keyword]
   [:location {:optional true} :string]
   [:persona {:optional true} :string]
   [:sketch {:optional true} :string]
   [:manner {:optional true} :string]
   [:sex {:optional true} [:enum :male :female]]
   [:temperament {:optional true}
    [:map-of [:enum :openness :conscientiousness :extraversion :agreeableness :neuroticism]
     [:enum :low :average :high]]]
   [:faculties {:optional true} [:map-of :string [:enum :small :moderate :large]]]
   [:circumstances {:optional true} [:vector :string]]])

(def WorldFile
  [:map
   [:meta {:optional true} :map]
   [:locations [:vector {:min 1} Location]]
   [:routes {:optional true} [:vector [:vector :any]]]
   [:agents {:optional true} [:vector Agent]]
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
            (for [{:keys [id] :as loc} (:locations wf)
                  :when (contains? loc :aspects)
                  :let [e (m/explain items/Aspects (:aspects loc))]
                  :when e]
              (str "location " (pr-str id) ": " (pr-str {:aspects (me/humanize e)})))
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
  owners as names again, and without :touched (it's the run's, not the map's)."
  [world start]
  (let [agent-name #(:name (w/agent world %))
        live (into {} (map (juxt :id identity)) (items/all world))
        orig (map :id (:items start))
        order (concat (filter live orig) (sort (remove (set orig) (keys live))))]
    (vec (for [id order
               :let [it (live id)]]
           (cond-> (dissoc it :touched)
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

(def ^:private item-key-order [:id :name :place :in :holder :owner :fixed? :consumable? :drawn-from :container :store :aspects :desc])

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
  "One item per line: :id and :name first, :aspects and :desc last."
  [it]
  (str "{" (str/join " " (for [k item-key-order :when (contains? it k)] (str (pr-str k) " " (pr-str (get it k))))) "}"))

;; ---------------------------------------------------------------------------
;; Editing a world file's text in place, so its comments and layout survive

(defn- skip-blank
  "The index of the next form at or after `i`: past whitespace, commas and comments."
  [^String s i]
  (loop [i i]
    (cond
      (>= i (count s)) i
      (or (Character/isWhitespace (.charAt s i)) (= \, (.charAt s i))) (recur (inc i))
      (= \; (.charAt s i)) (recur (let [j (.indexOf s "\n" (int i))] (if (neg? j) (count s) j)))
      :else i)))

(def ^:private closing #{\} \] \)})

(defn- form-end
  "The index just past the form that starts at `i`: a string, a collection
  (whose strings and comments are skipped) or a token."
  [^String s i]
  (let [c (.charAt s i)]
    (cond
      (= c \") (loop [j (inc i)]
                 (case (.charAt s j) \\ (recur (+ j 2)) \" (inc j) (recur (inc j))))
      (= c \#) (form-end s (inc i))
      (#{\{ \[ \(} c) (loop [j (skip-blank s (inc i))]
                       (if (closing (.charAt s j)) (inc j) (recur (skip-blank s (form-end s j)))))
      :else (loop [j i]
              (if (or (= j (count s))
                      (let [d (.charAt s j)]
                        (or (Character/isWhitespace d) (closing d) (#{\, \; \" \{ \[ \(} d))))
                j
                (recur (inc j)))))))

(defn- children
  "[start end] of each form in the collection whose opening bracket is at `open`."
  [^String s open]
  (loop [i (skip-blank s (inc open)), acc []]
    (if (closing (.charAt s i))
      acc
      (let [j (form-end s i)] (recur (skip-blank s j) (conj acc [i j]))))))

(defn- entries
  "[[key-start key-end] [value-start value-end]] for each entry of the map at `open`."
  [s open]
  (partition 2 (children s open)))

(defn- agent-open
  "Where the map of the agent called `agent-name` starts in world file text `s`."
  [^String s agent-name]
  (let [[_ [agents-open]] (first (filter (fn [[[a b]]] (= ":agents" (subs s a b))) (entries s (skip-blank s 0))))]
    (or (when agents-open
          (ffirst (filter (fn [[a b]] (= agent-name (:name (edn/read-string (subs s a b)))))
                          (children s agents-open))))
        (throw (ex-info (str "no agent called " agent-name " in the world file") {:agent agent-name})))))

(defn- value-str
  "`v` as EDN, with a map's entries on one line and no commas."
  [v]
  (if (map? v)
    (str "{" (str/join " " (for [[k x] v] (str (pr-str k) " " (pr-str x)))) "}")
    (pr-str v)))

(defn- set-agent-key
  "Set one key of an agent: replace its value where it stands, or else add it
  on a line of its own after the key `after` (or the agent's last key)."
  [^String s agent-name after k v]
  (let [open (agent-open s agent-name)
        es (entries s open)
        entry (fn [k] (first (filter (fn [[[a b]]] (= (pr-str k) (subs s a b))) es)))]
    (if-let [[_ [a b]] (entry k)]
      (str (subs s 0 a) (value-str v) (subs s b))
      (let [[[key-start] [_ value-end]] (or (entry after) (last es))
            line-start #(inc (.lastIndexOf s "\n" (int %)))
            indent (if (= (line-start key-start) (line-start open))
                     (inc (- open (line-start open)))
                     (- key-start (line-start key-start)))]
        (str (subs s 0 value-end) "\n" (apply str (repeat indent \space))
             (pr-str k) " " (value-str v) (subs s value-end))))))

(defn set-agent-keys
  "World file text `s` with the agent called `agent-name` given each [key
  value] of `kvs`. A key the agent has keeps its place; new keys go on lines
  of their own after its :persona (or its last key), in the order given.
  Everything else in the text, comments and layout included, is untouched."
  [s agent-name kvs]
  (first (reduce (fn [[s after] [k v]]
                   (let [had? (contains? (edn/read-string (subs s (agent-open s agent-name)
                                                                (form-end s (agent-open s agent-name))))
                                         k)]
                     [(set-agent-key s agent-name after k v) (if had? after k)]))
                 [s :persona]
                 kvs)))

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
