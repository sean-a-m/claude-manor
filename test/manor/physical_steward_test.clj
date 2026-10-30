(ns manor.physical-steward-test
  "The nightly steward's physical recovery: candidates, checks, emission and replay."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [manor.engine :as e]
            [manor.test-support :as support]
            [manor.engine.work :as work]
            [manor.io.llm :as llm]
            [manor.io.store :as store]
            [manor.mechanics.items.steward :as steward]
            [manor.mechanics.physical :as physical]
            [manor.mechanics.physical.changes :as changes]
            [manor.world :as w]))

(use-fixtures :once support/with-runs)
;; Retry assertions use a fixed budget, not the production default.
(use-fixtures :each (partial support/with-structured-attempts 4))

(defn- start [& [opts]]
  (e/start-run :burglary (merge {:llm :mock :offline? true :test? true
                               :run-id (str (gensym "physical-steward-"))} opts)))

(defn- stop! [run]
  (e/stop-run run)
  (is (.awaitTermination ^java.util.concurrent.ExecutorService (:exec run)
                         10 java.util.concurrent.TimeUnit/SECONDS)))

(defn- answer [args]
  {:tool-calls [{:id "s" :name "steward" :arguments (assoc args :summary "The night passed.")}]})

(defn- scene-json [request]
  (let [content (get-in request [:messages 1 :content])
        from (+ (str/index-of content "```json\n") 8)]
    (json/read-value (subs content from (str/index-of content "\n```" from)) json/keyword-keys-object-mapper)))

(defn- overnight! [run]
  (e/emit! run [(changes/physical-changed (e/world run) "will" [{:faculty :movement :level :disabled :cause "a twisted ankle"}])
                {:type :agent/occupied :actor "will" :visible-to :gm
                 :data {:activity "asleep" :asleep? true :until 20}}
                {:type :agent/occupied :actor "slade" :visible-to :gm
                 :data {:activity "resting for the night" :hibernating? true}}]))

(defn- at-steward-time! [run]
  (e/emit! run [{:type :round/started :visible-to :all :data {:round 3}}]))

(deftest steward-sees-living-hindered-people-whether-asleep-or-not
  (let [run (start)]
    (try
      (overnight! run)
      (let [world (e/world run)
            sleepers (steward/steward-sleepers world)
            hindered (steward/steward-hindered world)
            ids #(update-vals % (fn [people] (mapv :id people)))]
        (is (= {"dining-room" ["slade"] "footmens-room" ["will"]} (ids sleepers)))
        (is (= {"butlers-pantry" ["crane"] "dining-room" ["ivy"]
                "kitchen" ["mrs-bell"] "potting-shed" ["tom"]} (ids hindered)))
        (testing "awake and unconscious people are shown locally, without their private context"
          (doseq [[place id faculty] [["butlers-pantry" "crane" :movement]
                                      ["kitchen" "mrs-bell" :consciousness]]]
            (let [request (steward/steward-request world place [] [] (:inner (:scenario run)) (get hindered place))
                  scene (scene-json request)
                  [person] (:hindered scene)
                  tool (get-in request [:tools 0 :function :parameters :properties])]
              (is (= id (:id person)))
              (is (= "disabled" (get-in person [:physical faculty :level])))
              (is (= "01:00" (get-in person [:physical faculty :set_at])))
              (is (= (:cause (physical/entry world id faculty)) (get-in person [:physical faculty :cause])))
              (is (not-any? #(contains? person %) [:inner :persona :temperament :situation]))
              (is (empty? (:sleepers scene)))
              (is (not (contains? tool :died)))
              (is (not (contains? tool :made)))
              (is (not (str/includes? (get-in request [:messages 1 :content]) "albert"))))))
        (testing "sleepers and hibernators show their physical problems even without inner state"
          (let [scene (scene-json (steward/steward-request world "footmens-room" [] (get sleepers "footmens-room")))]
            (is (= "will" (get-in scene [:sleepers 0 :id])))
            (is (= {:level "disabled" :cause "a twisted ankle" :set_at "01:00"}
                   (get-in scene [:sleepers 0 :physical :movement]))))))
      (finally (stop! run)))))

(deftest stewardship-checks-only-structural-easing
  (let [run (start)]
    (try
      (overnight! run)
      (let [world (e/world run)
            sleepers (steward/steward-sleepers world)
            hindered (steward/steward-hindered world)
            check (fn [place args]
                    (steward/stewardship (answer args) world place [] (get sleepers place)
                                        (:inner (:scenario run)) (get hindered place)))]
        (testing "existing faculties may clear or ease, and existing impediments may change or clear"
          (doseq [[place args] [["butlers-pantry" {:physical [{:person "crane" :faculty :movement :level :impeded :cause "looser bonds"}]}]
                                ["butlers-pantry" {:physical [{:person "crane" :faculty :speech :level :normal}]}]
                                ["kitchen" {:physical [{:person "mrs-bell" :faculty :consciousness :level :normal}]}]
                                ["dining-room" {:impediments [{:person "slade" :key "hand" :value "a tender healing palm"}]}]
                                ["dining-room" {:impediments [{:person "slade" :key "hand" :value ""}]}]]]
            (is (seq (get-in (check place args) [:verdict :physical])))))
        (testing "new problems, worsening, unsupported levels and out-of-scope changes are rejected"
          (doseq [[place args] [["butlers-pantry" {:physical [{:person "crane" :faculty :sight :level :normal}]}]
                                ["butlers-pantry" {:physical [{:person "crane" :faculty :sight :level :impeded}]}]
                                ["butlers-pantry" {:physical [{:person "crane" :faculty :movement :level :disabled}]}]
                                ["butlers-pantry" {:physical [{:person "crane" :faculty :speech :level :impeded}]}]
                                ["butlers-pantry" {:impediments [{:person "crane" :key "ankle" :value "a twinge"}]}]
                                ["butlers-pantry" {:changes [{:thing "crane" :aspect "dress" :value ""}]}]
                                ["butlers-pantry" {:inner [{:person "crane" :key "body" :value "rested"}]}]
                                ["butlers-pantry" {:physical [{:person "mrs-bell" :faculty :consciousness :level :normal}]}]
                                ["dining-room" {:physical [{:person "albert" :faculty :consciousness :level :normal}]}]
                                ["dining-room" {:impediments [{:person "albert" :key "hand" :value ""}]}]
                                ["dining-room" {:physical [{:person "ivy" :faculty :sight :level :impeded}]}]
                                ["dining-room" {:impediments [{:person "slade" :key "bad!" :value ""}]}]
                                ["dining-room" {:impediments [{:person "slade" :key "hand" :value (apply str (repeat 201 "x"))}]}]
                                ["kitchen" {:physical [{:person "mrs-bell" :faculty :consciousness :level :impeded}]}]]]
            (is (:error (check place args)) (pr-str [place args]))))
        (testing "code does not try to rank open impediment prose"
          (is (:verdict (check "dining-room" {:impediments [{:person "slade" :key "hand" :value "a different phrase"}]}))))
        (testing "unknown death/making fields never enter the checked result"
          (let [verdict (:verdict (check "dining-room" {:died [{:person "ivy"}] :made [{:name "a thing"}]}))]
            (is verdict)
            (is (not (contains? verdict :physical)))
            (is (not (contains? verdict :made))))))
      (finally (stop! run)))))

(deftest steward-recovery-is-applied-privately-without-waking-sleepers-and-replays
  (let [contexts (atom [])
        run (start {:llm-impl (llm/mock
                              (fn [{:keys [place] :as ctx}]
                                (swap! contexts conj ctx)
                                {:tool :steward
                                 :args (merge {:summary "The night passed."}
                                              (case place
                                                "kitchen" {:physical [{:person "mrs-bell" :faculty :consciousness :level :normal}]}
                                                "footmens-room" {:physical [{:person "will" :faculty :movement :level :impeded :cause "a tender ankle"}]
                                                                 :inner [{:person "will" :key "body" :value "rested"}]}
                                                "dining-room" {:impediments [{:person "slade" :key "hand" :value "a healing cut"}]}
                                                {}))}))})]
    (try
      (overnight! run)
      (at-steward-time! run)
      (let [before (e/world run)
            occupations (into {} (map (fn [id] [id (w/busy before id)]) ["will" "slade"]))]
        (work/run-steward! run)
        (work/run-steward! run)
        (let [world (e/world run)
              events (e/events run)
              nightly (drop (inc (last (keep-indexed #(when (= :items/steward-ran (:type %2)) %1) events))) events)
              recovered (filter #(#{:person/physical-changed :person/impediments-changed} (:type %)) nightly)]
          (is (= ["butlers-pantry" "dining-room" "footmens-room" "kitchen" "potting-shed"] (map :place @contexts)))
          (is (= ["crane"] (map :id (:hindered (first @contexts)))))
          (is (not (physical/unconscious? world "mrs-bell")))
          (is (= :impeded (physical/level world "will" :movement)))
          (is (= "a tender ankle" (:cause (physical/entry world "will" :movement))))
          (is (= "a healing cut" (get-in world [:agents "slade" :impediments :hand])))
          (is (= "rested" (get-in world [:agents "will" :inner :body])))
          (is (= (:physical (w/agent before "crane")) (:physical (w/agent world "crane"))) "ropes and gag stay")
          (is (= :dead (:status (w/agent world "albert"))))
          (doseq [[id occupation] occupations] (is (= occupation (w/busy world id))))
          (is (not-any? #(= :agent/interrupted (:type %)) nightly))
          (is (= 3 (count recovered)))
          (is (every? #(= :gm (:visible-to %)) recovered))
          (is (not-any? #(w/visible-to? % "will") recovered))
          (is (= world (w/replay (store/read-events (:dir run)))))))
      (finally (stop! run)))))

(deftest invalid-physical-stewardship-retries-and-applies-nothing-on-failure
  (let [attempts (atom 0)
        run (start {:llm-impl (llm/mock
                              (fn [_]
                                (swap! attempts inc)
                                {:tool :steward :args {:summary "Invalid recovery."
                                                      :physical [{:person "crane" :faculty :movement :level :normal}
                                                                 {:person "crane" :faculty :sight :level :impeded}]}}))})]
    (try
      (at-steward-time! run)
      ;; Only Crane is eligible, so one call and its three correction attempts.
      (let [world (e/world run)
            hindered (get (steward/steward-hindered world) "butlers-pantry")]
        (with-redefs [steward/steward-hindered (constantly {"butlers-pantry" hindered})]
          (work/run-steward! run))
        (is (= 4 @attempts))
        (is (= (:physical (w/agent world "crane")) (:physical (w/agent (e/world run) "crane"))))
        (is (= 1 (count (filter #(= :items/steward-failed (:type %)) (e/events run)))))
        (is (= (e/world run) (w/replay (store/read-events (:dir run))))))
      (finally (stop! run)))))
