(ns manor.scene-web-test
  "Presentation-only pages, coherent projections and history seams."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hiccup2.core :as h]
            [jsonista.core :as json]
            [manor.engine :as e]
            [manor.test-support :as support]
            [manor.prompt :as prompt]
            [manor.scenarios-test :refer [bundled]]
            [manor.tools.scene :as scene]
            [manor.tools.web :as web]
            [manor.world :as w]))

(use-fixtures :once support/with-runs)
(defn- request [uri & [query]] (#'web/handler {:uri uri :query-string query :request-method :get}))
(defn- html [x] (str (h/html x)))
(defn- run-of [scenario]
  (e/start-run scenario {:llm :mock :offline? true :test? true
                         :run-id (str (gensym "scene-web-"))}))

(deftest bundled-scene-and-panel-routes
  (doseq [scenario bundled]
    (testing (name scenario)
      (let [run (run-of scenario)]
        (try
          (web/show! run)
          (let [before (scene/snapshot run)
                views (into {} (for [ag (w/active-agents (:world before))]
                                 [(:id ag) (prompt/agent-view (:scenario run) (:world before) (:id ag))]))]
            (doseq [uri ["/" "/panel" "/partial/scene" "/partial/scene-state" "/static/scene.js" "/static/scene.css" "/static/panel.js" "/world"]]
              (let [response (request uri)]
                (is (= 200 (:status response)) uri)
                (is (seq (:body response)) uri)))
            (is (str/includes? (:body (request "/")) "href=\"/panel\""))
            (is (str/includes? (:body (request "/panel")) "href=\"/\""))
            (is (str/includes? (:body (request "/world")) "href=\"/panel\""))
            (let [agent (first (w/active-agents (:world before)))]
              (when agent (is (str/includes? (:body (request (str "/agent/" (:id agent)))) "href=\"/panel\""))))
            (is (str/includes? (:body (request "/panel" "as=gm")) "Events visible to gm"))
            (let [state (json/read-value (:body (request "/partial/scene-state")) json/keyword-keys-object-mapper)]
              (is (= #{:scene :toolbar :navigation :away :feed} (set (keys state))))
              (is (str/includes? (:toolbar state) "Step"))
              (is (str/includes? (:scene state) "data-fit=")))
            (is (= before (scene/snapshot run)) "rendering has no effects")
            (is (= views (into {} (for [ag (w/active-agents (e/world run))]
                                    [(:id ag) (prompt/agent-view (:scenario run) (e/world run) (:id ag))])))))
          (finally (e/stop-run run)))))))

(defn- events-of [specs]
  (:events (reduce (fn [{:keys [world events]} event]
                     (let [event (w/stamp world event)]
                       {:world (w/apply-stamped world event) :events (conj events event)}))
                   {:world (w/new-world) :events []} specs)))
(defn- location [id & [extra]] {:type :space/location-added :data {:location (merge {:id id :name id} extra)}})
(defn- route [a b travel one-way?] {:type :space/route-opened :data {:from a :to b :travel travel :one-way? one-way?}})
(defn- snapshot-of [events]
  {:id "fixture" :events events :world (w/replay events)
   :status {:state :idle :mode :manual :acting #{}}})
(defn- local-rooms [result]
  (update-vals (:rooms result) #(select-keys % [:x :y :w :h :panel :region :floor])))

(deftest route-updates-never-reinterpret-history
  (let [initial [(location "hall" {:domain :house :floor :ground})
                 (location "study" {:domain :house :floor :ground})
                 (route "hall" "study" 0 false)
                 {:type :round/started :data {:round 1}}]
        later (conj initial (location "new"))
        events (events-of later)
        connected (events-of (conj later (route "new" "hall" 0 true)))
        closed (events-of (conj later (route "new" "hall" 3 true)
                               {:type :space/route-closed :data {:from "hall" :to "study" :one-way? true}}))
        cache (atom nil)
        first-result (scene/presentation (snapshot-of events) cache)
        before @cache]
    (doseq [prefix [connected closed]]
      (let [cold (scene/presentation (snapshot-of prefix))
            warm (scene/presentation (snapshot-of prefix) cache)]
        (is (= cold warm))
        (is (= (local-rooms first-result) (local-rooms warm)))
        (is (identical? (:placement before) (:placement @cache)))
        (is (not= (:connections first-result) (:connections warm)))))
    (is (= 1 (count (:steps (scene/placement-history connected)))))
    (is (not-any? #(= "new" (:from %)) (:routes (last (:steps (scene/placement-history connected)))))
        "only then-known routes belong in the room's boundary")))

(deftest escaped-entities-crowding-travel-and-status
  (let [id "room /\"<>&?[]"
        person "person /\"<>&?[]"
        events (events-of
                (concat [(location id {:name "<script>not code</script>" :domain :house :floor :ground
                                        :desc "A \"room\" & its description" :aspects {:state "<clean>"}})
                         (location "up" {:domain :house :floor :first})
                         (location "away" {:domain :village :floor :outside})
                         (route id "up" 0 true) (route id "away" 4 true)
                         {:type :round/started :data {:round 1}}]
                        (for [i (range 8)]
                          {:type :agent/spawned :data {:agent {:id (if (zero? i) person (str "p" i))
                                                              :name (str "Name " i " <&>") :role :guest
                                                              :location id :controller (if (zero? i) :human :llm)}}})
                        [{:type :agent/spawned :data {:agent {:id "traveller" :name "Traveller" :role :guest :location id}}}
                         {:type :agent/departed :actor "traveller" :data {:from id :to "away" :arrives 5}}
                         {:type :agent/occupied :actor person :data {:activity "resting" :asleep? true}}
                         {:type :agent/retired :data {:id "p7"}}
                         {:type :people/departed :data {:id "p6"}}
                         {:type :round/started :data {:round 3}}]))
        snap (assoc (snapshot-of events) :input {:agent person}
                    :status {:state :paused :mode :manual :acting #{person} :error "<error>"})
        result (scene/presentation snap)
        rendered (html (scene/svg snap result))]
    (is (str/includes? rendered "&lt;script&gt;not code&lt;/script&gt;"))
    (is (not (str/includes? rendered "<script>")))
    (is (str/includes? rendered "data-id=\"person /&quot;&lt;&gt;&amp;?[]\""))
    (is (str/includes? rendered "person asleep acting"))
    (is (str/includes? rendered "stair"))
    (is (str/includes? rendered "4 rounds"))
    (is (str/includes? rendered "data-id=\"traveller\""))
    (is (str/includes? (html (scene/away-strip snap result)) "retired"))
    (is (str/includes? (html (scene/away-strip snap result)) "away"))
    (is (str/includes? (html (scene/toolbar snap)) "&lt;error&gt;"))
    (is (str/includes? (html (scene/toolbar snap)) "Act on the panel"))
    (let [crowded (update-in snap [:world :agents] #(into {} (map (fn [[k v]] [k (assoc v :status :active :location id)]) %)))]
      (is (str/includes? (html (scene/svg crowded result)) "+3")))
    (is (= (:world snap) (w/replay events)))))

(deftest empty-space-and-bounded-feed
  (let [snap (snapshot-of [])]
    (is (str/includes? (html (scene/svg snap (scene/presentation snap))) "no locations")))
  (let [run (run-of :quiz)]
    (try
      (web/show! run)
      (is (str/includes? (:body (request "/")) "no locations"))
      ;; Synthetic read-only run: avoid hundreds of engine commands just to
      ;; exercise the presentation bound, and leave the real run untouched.
      (let [events (events-of (repeat 500 {:type :agent/noted :actor "a" :data {:text "note"}}))
            fixture (assoc run :world (atom (w/replay events)) :events (atom events))]
        (is (= 70 (count (re-seq #"<li" (:feed (#'web/scene-fragments fixture)))))))
      (finally (e/stop-run run)))))

(deftest initial-room-and-unknown-floor-order-follows-history
  (let [events (events-of [(location "z" {:domain :house :floor :tower})
                           (location "a" {:domain :house :floor :mezzanine})])
        history (scene/placement-history events)
        result (scene/presentation (snapshot-of events))
        floors (get-in result [:placement :floor-orders [:domain :house]])]
    (is (= ["z" "a"] (mapv :id (get-in history [:initial :locations]))))
    (is (< (.indexOf floors :tower) (.indexOf floors :mezzanine)))))

(deftest directed-rendering-and-incomplete-records
  (let [events (events-of [(location "a" {:domain :house :floor :ground})
                           (location "b" {:domain :house :floor :ground})
                           (route "a" "b" 0 false)
                           {:type :space/route-closed :data {:from "b" :to "a" :one-way? true}}])
        snap (snapshot-of events)
        result (scene/presentation snap)
        rendered (html (scene/svg snap result))
        paths (re-seq #"data-route=\"[^\"]+\"[^>]+points=\"([^\"]+)\"" rendered)]
    (is (= 2 (count (:connections result))))
    (is (= 2 (count paths)))
    (is (not= (second (first paths)) (second (second paths))) "each direction has a distinct visible lane")
    (is (str/includes? rendered "connection door closed"))
    (let [roads (snapshot-of (events-of [(location "a") (location "b")
                                          (route "a" "b" 2 true) (route "b" "a" 5 true)]))
          rendered (html (scene/svg roads (scene/presentation roads)))]
      (is (str/includes? rendered "2 rounds"))
      (is (str/includes? rendered "5 rounds")))
    (let [incomplete (assoc-in snap [:world :agents "traveller"]
                                 {:id "traveller" :name "Traveller" :status :active :role :guest
                                  :travel {:from "a" :to "b"}})]
      (is (str/includes? (html (scene/svg incomplete result)) "data-id=\"traveller\""))
      (is (str/includes? (html (scene/away-strip (assoc-in incomplete [:world :agents "traveller" :travel :from] "missing") result))
                         "travelling · location unavailable")))))

(deftest panel-feed-filtering-remains-agent-specific
  (let [run (run-of :village)]
    (try
      (let [events (events-of [{:type :agent/spawned :data {:agent {:id "ana" :name "Ana" :role :farmer}}}
                               {:type :agent/spawned :data {:agent {:id "bruno" :name "Bruno" :role :farmer}}}
                               {:type :agent/noted :actor "ana" :visible-to #{"ana"} :data {:text "private-for-ana"}}
                               {:type :agent/noted :actor "bruno" :visible-to #{"bruno"} :data {:text "private-for-bruno"}}
                               {:type :system :visible-to :system :data {}}])
            fixture (assoc run :world (atom (w/replay events)) :events (atom events))]
        (web/show! fixture)
        (let [ana (:body (request "/panel" "as=ana"))
              operator (:body (request "/panel"))]
          (is (str/includes? ana "private-for-ana"))
          (is (not (str/includes? ana "private-for-bruno")))
          (is (str/includes? operator "private-for-ana"))
          (is (str/includes? operator "private-for-bruno"))))
      (finally (e/stop-run run)))))

(deftest loaded-scene-and-live-snapshot
  (let [run (run-of :village)]
    (try
      (e/auto! run 3)
      (dotimes [_ 10]
        (let [snap (scene/snapshot run)]
          (is (= (:world snap) (w/replay (:events snap))))))
      (e/wait-idle run 60000)
      (let [id (:id run) live (scene/presentation (scene/snapshot run))]
        (let [loaded (e/load-run id {:llm :mock :offline? true :test? true})]
          (try
            (is (= live (scene/presentation (scene/snapshot loaded))))
            (finally (e/stop-run loaded)))))
      (finally (e/stop-run run)))))
