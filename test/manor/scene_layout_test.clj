(ns manor.scene-layout-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [manor.io.worldfile :as worldfile]
            [manor.scene-layout :as l]
            [manor.tools.scene-preview :as preview]))

(defn- graph [n edges]
  {:locations (mapv #(hash-map :id (str %) :domain :house) (range n))
   :routes (mapv (fn [[a b]] {:from (str a) :to (str b) :travel 0 :open? true}) edges)})

(defn- pairs [xs] (for [i (range (count xs)), j (range (inc i) (count xs))] [(nth xs i) (nth xs j)]))
(defn- local-geometry [result]
  (into {} (map (fn [[id r]] [id (select-keys r [:x :y :w :h :panel :region :floor])]) (:rooms result))))

(defn- check-layout [topology result]
  (is (= (set (map :id (concat (:locations topology) (:reservations topology)))) (set (keys (:rooms result)))))
  (is (= (:routes (l/normalize-topology topology)) (mapv :route (:connections result))))
  (is (every? #(seq (:path %)) (:connections result)))
  (is (zero? (get-in result [:quality :unresolved])))
  (is (not-any? (fn [[a b]] (l/overlaps? (:global a) (:global b))) (pairs (vec (vals (:rooms result))))))
  (is (not-any? (fn [[a b]] (l/overlaps? a b)) (pairs (vec (vals (:regions result))))))
  (is (not-any? (fn [[a b]] (l/overlaps? a b)) (pairs (vec (vals (:panels result)))))))

(deftest empty-and-validation
  (doseq [history [{} {:initial {}} {:initial nil :steps [{}]}]]
    (let [result (l/layout history)]
      (is (empty? (:rooms result)))
      (is (empty? (:regions result)))
      (is (empty? (:connections result)))
      (is (= 0 (get-in result [:quality :bounding-area])))
      (is (str/includes? (preview/svg result) "<svg"))))
  (doseq [topology [{:locations [{:id "a"} {:id "a"}]}
                    {:locations [{}]}
                    {:locations [{:id "a" :size [0 1]}]}
                    {:routes [{:from "a" :to "b" :travel -1}]}]]
    (is (thrown? clojure.lang.ExceptionInfo (l/normalize-topology topology)))))

(deftest awkward-graphs
  (doseq [[label topology]
          [["star-20" (graph 21 (for [n (range 1 21)] [0 n]))]
           ["clique-5" (graph 5 (for [a (range 5), b (range (inc a) 5)] [a b]))]
           ["ring-with-chords" (graph 16 (concat (for [n (range 16)] [n (mod (inc n) 16)])
                                                         (for [n (range 8)] [n (+ 8 n)])))]
           ["chain-30" (graph 30 (for [n (range 29)] [n (inc n)]))]
           ["disconnected" (graph 20 [[0 1] [1 2] [5 6] [10 11]])]
           ["isolated" (graph 20 [])]]]
    (testing label
      (let [result (l/layout {:initial topology})]
        (check-layout topology result)
        (is (= result (l/layout {:initial topology})))
        (is (<= 0.0 (get-in result [:quality :door-share]) 1.0))
        (is (<= (get-in result [:quality :candidate-evaluations]) (* 8 100 (count (:locations topology)))))))))

(deftest sizes-and-quality-not-a-universal-door-promise
  (let [star (l/layout {:initial (graph 21 (for [n (range 1 21)] [0 n]))})
        hub (get-in star [:rooms "0"])]
    (is (>= (* 2 (+ (:w hub) (:h hub))) 20))
    (is (> (:w hub) (* 3 (:h hub))) "leaf-heavy hubs are corridors")
    (is (> (get-in star [:quality :door-share]) 0.75)))
  (doseq [[size expected] [[:small [1 1]] [:medium [2 1]] [:large [2 2]] [:grand [3 2]] [[4 2] [4 2]]]]
    (is (= expected (l/room-size {:size size} #{} {}))))
  (is (= [2 1] (l/room-size {:kind :hall} #{} {})))
  (is (= [1 1] (l/room-size {} #{} {})))
  (let [g (graph 25 (for [a (range 25), b (range (inc a) 25)] [a b]))
        r (l/layout {:initial g})]
    (check-layout g r)
    (is (< (get-in r [:quality :door-share]) 1.0))
    (is (pos? (get-in r [:quality :connector-length])))
    (doseq [room (vals (:rooms r))]
      (is (<= (/ (max (:w room) (:h room)) (min (:w room) (:h room))) 3)))))

(deftest mixed-domains-floors-and-direction
  (let [locations [{:id "outside" :domain :house :floor :outside}
                   {:id "up" :domain :house :floor :first}
                   {:id "missing" :domain :house}
                   {:id "down" :domain :house :floor :basement}
                   {:id "tower" :domain :house :floor :tower-top}
                   {:id "mezz" :domain :house :floor :mezzanine}
                   {:id "ground" :domain :house :floor :ground}
                   {:id "village" :domain :village}
                   {:id "loose-a"} {:id "loose-b"} {:id "island"}]
        routes [{:from "missing" :to "up" :travel 0 :open? false :detail "locked"}
                {:from "down" :to "missing" :travel 0 :open? true}
                {:from "loose-b" :to "loose-a" :travel 0 :open? false}
                {:from "loose-a" :to "ground" :travel 0 :open? true}
                {:from "up" :to "down" :travel 0 :open? true}
                {:from "up" :to "tower" :travel 0 :open? true}
                {:from "ground" :to "village" :travel 2 :open? true}
                {:from "village" :to "ground" :travel 5 :open? false}
                {:from "ground" :to "ground" :travel 0 :open? true}]
        topology {:locations locations :routes routes}
        r (l/layout {:initial topology})
        room #(get-in r [:rooms %])]
    (check-layout topology r)
    (is (= :basement (:floor (room "missing"))) "missing-floor tie uses known floor order")
    (is (= (:panel (room "outside")) (:panel (room "ground"))))
    (is (= (:region (room "loose-a")) (:region (room "loose-b"))))
    (is (not= (:region (room "ground")) (:region (room "loose-a"))))
    (is (not= (:region (room "loose-a")) (:region (room "island"))))
    (is (= [:basement :ground :first :tower-top :mezzanine]
           (->> (:panels r) vals (filter #(= [:domain :house] (:region %))) (sort-by :x) (mapv #(second (:id %))))))
    (is (= routes (mapv :route (:connections r))))
    (is (= [:road :road :loop] (mapv :kind (take-last 3 (:connections r)))))
    (is (= (:placement r)
           (:placement (l/layout {:initial topology :current (assoc topology :routes (mapv #(update % :open? not) routes))}))))))

(deftest historical-boundaries-not-current-topology
  (let [initial {:locations [{:id "a" :domain :house :floor :ground}
                             {:id "up" :domain :house :floor :first}]}
        first-step {:locations [{:id "b" :domain :house}]
                    :routes [{:from "a" :to "b" :travel 0}]}
        later-step {:locations [{:id "c" :domain :house}]
                    :routes [{:from "b" :to "up" :travel 0}
                             {:from "c" :to "up" :travel 0}]}
        h {:initial initial :steps [first-step later-step]}
        p0 (l/place-history {:initial initial})
        p1 (l/extend-placement p0 first-step)
        p2 (l/extend-placement p1 later-step)
        current {:locations (vec (concat (:locations initial) (:locations first-step) (:locations later-step)))
                 :routes [{:from "b" :to "up" :travel 0 :open? false}
                          {:from "a" :to "c" :travel 0 :open? true}]}
        r (l/layout (assoc h :current current))]
    (is (= p2 (l/place-history h)))
    (is (= r (l/present p2 current)) "incremental and cold recomputation agree")
    (doseq [[id geometry] (local-geometry (l/present p0 initial))]
      (is (= geometry (get (local-geometry r) id))))
    (is (= :ground (get-in r [:rooms "b" :floor])) "new future stair link must not infer an earlier floor")
    (is (= :first (get-in r [:rooms "c" :floor])))
    (is (= (:rooms p1) (select-keys (:rooms p2) (keys (:rooms p1)))))
    (is (= p2 (l/extend-placement p2 {:routes (:routes current)})))
    (is (= (mapv :route (:connections r)) (:routes current)))
    (is (= (:placement r) (:placement (l/layout (assoc h :current {:routes []})))))))

(deftest reservations-are-normalized-and-completion-reuses-local-geometry
  (let [initial {:locations [{:id "hall" :domain :house :floor :ground}]}
        reservation {:id "new-room" :name "Future room" :domain :house :floor :ground
                     :size :grand :connects-to ["hall"]}
        step {:reservations [reservation]}
        p (l/place-history {:initial initial :steps [step]})
        completed {:locations [(dissoc reservation :connects-to)]
                   :routes [{:from "hall" :to "new-room" :travel 0 :open? true}]}
        after (l/extend-placement p completed)
        ghost (l/present p step)
        real (l/present after completed)]
    (is (= (:rooms p) (:rooms after)))
    (is (= (local-geometry ghost) (local-geometry real)))
    (is (true? (get-in ghost [:rooms "new-room" :reservation?])))
    (is (not (get-in real [:rooms "new-room" :reservation?])))
    (is (= [3 2] ((juxt :w :h) (get-in real [:rooms "new-room"]))))
    (is (= 2 (count (:connections ghost))))
    (is (= :door (get-in real [:connections 0 :kind])))
    (is (= after (l/place-history {:initial initial :steps [step completed]})))
    (is (not (get-in (l/present (l/extend-placement after {:routes (:routes completed)})
                               {:routes (:routes completed)})
                     [:rooms "new-room" :reservation?])))
    (is (= "Morning room" (get-in (l/layout {:initial {:locations [{:id "loose" :name "Morning room"}]}})
                                   [:regions [:area "loose"] :label])))))

(deftest new-routes-do-not-merge-frozen-regions-or-move-isolated-rooms
  (let [initial {:locations [{:id "a"} {:id "b"}]}
        p (l/place-history {:initial initial})
        step {:locations [{:id "c"}]
              :routes [{:from "a" :to "b" :travel 0} {:from "c" :to "b" :travel 0}]}
        r (l/layout {:initial initial :steps [step]})]
    (is (= (:rooms p) (select-keys (get-in r [:placement :rooms]) ["a" "b"])))
    (is (not= (get-in r [:rooms "a" :region]) (get-in r [:rooms "b" :region])))
    (is (= (get-in r [:rooms "a" :region]) (get-in r [:rooms "c" :region]))
        "a new domainless component uses its earliest frozen region without merging old regions")
    (is (= :road (get-in r [:connections 0 :kind])))))

(deftest generated-graphs-and-incremental-prefixes
  (doseq [seed (range 6)]
    (let [n (+ 7 seed)
          topology (graph n (for [i (range n), j (range (inc i) n)
                                  :when (< (mod (+ (* i 13) (* j 7) seed) 11) 3)] [i j]))
          r (l/layout {:initial topology})
          reordered (-> topology
                        (update :locations #(mapv (fn [r] (into (sorted-map) r)) %))
                        (update :routes #(vec (reverse %))))]
      (check-layout topology r)
      (is (= (local-geometry r) (local-geometry (l/layout {:initial reordered}))))))
  (let [initial {:locations [{:id "root" :domain :house :floor :ground}]}
        steps (mapv (fn [n]
                      {:locations [{:id (str n) :domain :house}]
                       :routes (mapv (fn [j] {:from (if (zero? j) "root" (str (dec j)))
                                              :to (str j) :travel 0}) (range (inc n)))}) (range 18))]
    (loop [prefix [], placement (l/place-history {:initial initial}), remaining steps]
      (when-let [step (first remaining)]
        (let [next-prefix (conj prefix step)
              next-placement (l/extend-placement placement step)
              result (l/present next-placement step)]
          (is (= next-placement (l/place-history {:initial initial :steps next-prefix})))
          (is (= (:rooms placement) (select-keys (:rooms next-placement) (keys (:rooms placement)))))
          (is (not-any? (fn [[a b]] (l/overlaps? (:global a) (:global b))) (pairs (vec (vals (:rooms result))))))
          (recur next-prefix next-placement (rest remaining)))))))

(deftest separated-fallback-and-incomplete-connection-records
  (let [r (l/layout {:initial (graph 4 [])})]
    (is (not-any? (fn [[a b]] (l/touching? a b)) (pairs (vec (vals (:rooms r)))))))
  (let [r (l/layout {:initial {:locations [{:id :only}]
                              :routes [{:from :only :to :missing :travel 0 :open? false}]}})]
    (is (= :unresolved (get-in r [:connections 0 :kind])))
    (is (= 1 (get-in r [:quality :unresolved])))
    (is (nil? (get-in r [:connections 0 :path])))))

(deftest bounded-large-graph
  ;; Deterministic pseudo-random long cross-links, no RNG/dependency required.
  (let [n 320
        topology (graph n (concat (for [i (range (dec n))] [i (inc i)])
                                  (for [i (range n)] [i (mod (+ (* i 37) 19) n)])))
        started (System/nanoTime)
        r (l/layout {:initial topology})
        elapsed (/ (- (System/nanoTime) started) 1e9)]
    (check-layout topology r)
    (is (< elapsed 8.0) (str "320 rooms took " elapsed "s (generous shared-CI ceiling)"))
    (is (< (get-in r [:quality :candidate-evaluations]) (* n 16)))
    (is (= r (l/layout {:initial topology})))
    (println "scene-layout 320 rooms seconds:" elapsed "quality:" (:quality r))))

(deftest bundled-and-world-file-layouts
  (doseq [scenario [:village :treasure :quiz :claudeworth :claudeworth-medium :lodge :dressing]]
    (testing (name scenario)
      (let [definition @(requiring-resolve (symbol (str "manor.scenarios." (name scenario)) "scenario"))
            topology (preview/world-file-topology (:space ((:init definition) (merge (:defaults definition) {:llm :mock :offline? true :test? true}))))
            r (l/layout {:initial topology})]
        (check-layout topology r)
        (is (= r (l/layout {:initial topology}))))))
  (doseq [source ["resources/worlds/claudeworth.edn" "resources/worlds/claudeworth-medium.edn"
                  "test/fixtures/household.edn" "test/fixtures/hamlet.edn"]]
    (let [topology (preview/world-file-topology (worldfile/read-file source))
          r (l/layout {:initial topology})]
      (check-layout topology r)
      (when (str/includes? source "household.edn")
        (is (> (get-in r [:quality :door-share]) 0.75))))))

(deftest svg-preview-escapes-and-writes-without-a-server
  (let [r (l/layout {:initial {:locations [{:id "<id>" :name "<script>& room"}]}})
        svg (preview/svg r)
        file (java.io.File/createTempFile "manor-scene-" ".svg")]
    (is (str/includes? svg "&lt;script&gt;&amp; room"))
    (is (not (str/includes? svg "<script>")))
    (try
      (let [result (preview/scene-preview! "test/fixtures/hamlet.edn" (.getPath file))]
        (is (= (.getPath file) (:path result)))
        (is (pos? (:bounding-area (:quality result))))
        (is (str/starts-with? (slurp file) "<svg"))
        ;; Parse as real XML rather than just checking a string prefix.
        (is (= "svg" (.getTagName (.getDocumentElement
                                   (.parse (.newDocumentBuilder (javax.xml.parsers.DocumentBuilderFactory/newInstance))
                                           (io/file file)))))))
      (finally (.delete file)))))
