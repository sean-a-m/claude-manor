(ns manor.person-aspects-test
  "Person aspects (manor.mechanics.items) in the dressing fixture world: checks, views,
  judged work on oneself and on others, job scope, the nightly steward and
  replay. Nothing here depends on Claudeworth's content."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [manor.actions]
            [manor.engine :as e]
            [manor.test-support :as support]
            [manor.io.llm :as llm]
            [manor.io.store :as store]
            [manor.io.worldfile :as wf]
            [manor.mechanics.items :as items]
            [manor.mechanics.items.judge :as item-judge]
            [manor.mechanics.items.steward :as item-steward]
            [manor.mechanics.items.views :as item-views]
            [manor.mechanics.items.work :as item-work]
            [manor.narrate :as n]
            [manor.prompt :as prompt]
            [manor.scenarios.dressing :as dressing]
            [manor.world :as w]))

(use-fixtures :once support/with-runs)

(defn- scripted
  "A mock: `script` maps agent id -> round -> the calls of that turn, in
  order; other turns carry on if interrupted, else pass (the GM is done).
  Judge calls go to the fixture's mock judge, and each judge ctx is added
  to the atom `judged`."
  [script & {:keys [judged]}]
  (llm/mock (fn [{:keys [world agent-id role step] :as ctx}]
              (case role
                :judge (do (some-> judged (swap! conj ctx)) (dressing/mock-judge ctx))
                :gm {:tool :done}
                (or (get-in script [agent-id (:round world) step])
                    (when (:interrupted-by (w/busy world agent-id)) {:tool :carry-on})
                    {:tool :pass})))))

(defn- start [& [opts]]
  (e/start-run :dressing (merge {:llm :mock :test? true :run-id (str (gensym "aspects-"))} opts)))

(defn- rounds! [run n]
  (e/auto! run n)
  (is (= :idle (e/wait-idle run 60000)))
  run)

(defn- spend [on doing hours]
  {:tool :spend-time :args (cond-> {:doing doing :hours hours} (seq on) (assoc :on on))})

(defn- aspects-of [run id] (:aspects (w/agent (e/world run) id)))

(defn- of-type [run t] (filter #(= t (:type %)) (e/events run)))

(defn- view [run id] (prompt/agent-view (:scenario run) (e/world run) id))

(defn- person-here [view id] (first (filter #(= id (:id %)) (get-in view [:location :people-here]))))

;; ---------------------------------------------------------------------------
;; Data, checks and views

(deftest agent-aspects-are-checked
  (let [good (wf/read-file dressing/world-file)
        problems #(wf/problems (assoc-in good [:agents 0 :aspects] %))]
    (is (empty? (wf/problems good)))
    (is (= {:apron "spattered with grease"} (:aspects (nth (:agents (wf/load-world dressing/world-file)) 2))))
    (is (empty? (problems {:hair "dressed high"})))
    (is (= ["agent \"Lady Ashby\": {:aspects {:hair [\"should be at most 200 characters\"]}}"]
           (problems {:hair (apply str (repeat 201 "x"))})))
    (is (str/includes? (first (problems {:Hair "up"})) "should be a key of lower-case letters"))
    (is (str/includes? (first (problems {:a "1" :b "2" :c "3" :d "4" :e "5"})) "agent \"Lady Ashby\""))
    (testing ":init agents are checked too"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"bad :agents from :init: agent \"X\""
                            (#'e/check-agents! dressing/scenario [{:name "X" :aspects {:hair ""}}])))
      (is (nil? (#'e/check-agents! dressing/scenario [{:name "X" :aspects {:hair "up"}} {:name "Y"}]))))))

(deftest everyone-present-sees-aspects
  (let [run (start)]
    (try
      (let [world (e/world run)]
        (is (= {:apron "spattered with grease"} (aspects-of run "mrs-pell")) "the world file's aspects are kept at spawn")
        (testing "your own, under you"
          (is (= {:apron "spattered with grease"} (get-in (view run "mrs-pell") [:you :aspects])))
          (is (not (contains? (:you (view run "lady-ashby")) :aspects)) "none when you have none"))
        (testing "nobody elsewhere sees them"
          (is (nil? (person-here (view run "lady-ashby") "mrs-pell")))
          (is (not (str/includes? (prompt/->json (view run "lady-ashby")) "grease"))))
        (testing "the people here see them beside the appearance, in people-here and look"
          (e/emit! run [{:type :agent/placed :data {:id "dawson" :to "kitchen"}}])
          (is (= {:id "mrs-pell" :name "Mrs Pell" :role "cook"
                  :appearance "A stout woman, usually in a print frock and a white apron."
                  :aspects {:apron "spattered with grease"}}
                 (update (person-here (view run "dawson") "mrs-pell") :role name)))
          (is (= {:apron "spattered with grease"}
                 (:aspects (first (:people (item-views/look (e/world run) "dawson")))))))
        (testing "the GM sees them"
          (is (= {:apron "spattered with grease"}
                 (:aspects (first (filter #(= "mrs-pell" (:id %)) (:agents (prompt/default-gm-view world)))))))))
      (finally (e/stop-run run)))))

;; ---------------------------------------------------------------------------
;; Work on oneself

(deftest work-on-oneself-changes-the-worker
  (let [judged (atom [])
        run (rounds! (start {:llm-impl (scripted {"lady-ashby" {1 [(spend [] "changing for dinner" 0.5)]}} :judged judged)}) 2)
        world (e/world run)
        [done] (of-type run :item/work-done)]
    (try
      (testing "the worker is judged without being named, as a person in the scene"
        (let [{:keys [things]} (first @judged)
              lady (first (filter :person? things))]
          (is (= 1 (count @judged)))
          (is (= {:id "lady-ashby" :name "Lady Ashby" :person? true
                  :appearance "A tall woman of fifty, usually in a grey day dress, her hair in a plain knot."}
                 lady))))
      (testing "the change is recorded, narrated and kept on the worker"
        (is (= [{:person "lady-ashby" :aspect :dress :from nil :to "changed: changing for dinner"}]
               (get-in done [:data :changes])))
        (is (= {:dress "changed: changing for dinner"} (aspects-of run "lady-ashby")))
        (is (= "Lady Ashby finishes changing for dinner. (Lady Ashby: changed: changing for dinner)."
               (n/describe done (n/names world))))
        (is (= #{"lady-ashby" "dawson"} (:visible-to done))))
      (testing "Dawson, in the room, sees it; the transcript shows it"
        (is (= {:dress "changed: changing for dinner"} (:aspects (person-here (view run "dawson") "lady-ashby"))))
        (is (str/includes? (manor.tools.transcript/render (:dir run)) "(Lady Ashby: changed: changing for dinner)")))
      (testing "the exported map keeps the live aspects, and loads again"
        (let [map-file (io/file (:dir run) "map.edn")
              agents (:agents (wf/load-world (str map-file)))]
          (is (= {:dress "changed: changing for dinner"} (:aspects (first agents))))
          (is (= {:apron "spattered with grease"} (:aspects (nth agents 2))))
          (is (not (contains? (second agents) :aspects)))))
      (is (= world (w/replay (store/read-events (:dir run)))))
      (finally (e/stop-run run)))))

(deftest a-removed-aspect-leaves-the-person
  (let [run (start)]
    (try
      (e/emit! run [{:type :item/work-begun :visible-to :gm
                     :data {:id "job-x" :actor "mrs-pell" :items [] :doing "changing her apron" :place "kitchen" :started 0 :due 1}}])
      (let [world (e/world run)
            things (item-work/judged-things world (items/job world "job-x"))
            verdict (:verdict (item-judge/judgement {:tool-calls [{:id "j" :name "judge"
                                                                   :arguments "{\"changes\":[{\"thing\":\"mrs-pell\",\"aspect\":\"apron\",\"value\":\"\"}],\"summary\":\"x\"}"}]}
                                                    world things))]
        (is (= [{:person "mrs-pell" :aspect :apron :from "spattered with grease" :to nil}] (:changes verdict)))
        (e/emit! run [(item-work/work-done world (items/job world "job-x") {:minutes 30} verdict)])
        (is (not (contains? (w/agent (e/world run) "mrs-pell") :aspects)) "no aspects left, no :aspects")
        (is (= "Mrs Pell finishes changing her apron. x (Mrs Pell: no longer spattered with grease)."
               (n/describe (last (of-type run :item/work-done)) (n/names (e/world run)))))
        (is (= (e/world run) (w/replay (e/events run)))))
      (finally (e/stop-run run)))))

;; ---------------------------------------------------------------------------
;; Work on others

(defn- action [run n] (first (filter #(= n (:name %)) (:actions run))))

(deftest on-names-people-here
  (let [run (start)
        world (assoc (e/world run) :round 1)
        spend-time (action run :spend-time)
        check #(manor.actions/check spend-time world "dawson" {:on % :doing "doing her hair"})]
    (try
      (testing "someone here, or yourself"
        (is (nil? (check ["lady-ashby"])))
        (is (nil? (check ["dawson"])))
        (is (nil? (check ["lady-ashby" "dressing-table" "bedroom"]))))
      (testing "someone elsewhere is refused, with who is here"
        (is (= "Mrs Pell is not here; people here: you (dawson), Lady Ashby (lady-ashby)" (check ["mrs-pell"]))))
      (testing "the GM is no person to name"
        (is (str/starts-with? (check ["gm"]) "gm is neither here nor carried by you")))
      (testing "the job keeps people apart from things"
        (let [[begun] (manor.actions/effect-events spend-time world "dawson"
                                                   {:on ["lady-ashby" "dressing-table" "lady-ashby"] :doing "doing her hair"})]
          (is (= ["dressing-table"] (get-in begun [:data :items])))
          (is (= ["lady-ashby"] (get-in begun [:data :people])))
          (is (= "Dawson starts doing her hair (job-x, on dressing-table, lady-ashby, until 18:30)."
                 (n/describe (assoc-in begun [:data :id] "job-x") (n/names world))))
          (is (contains? (item-work/job-scope world (:data begun)) [:person "lady-ashby"]))
          (is (contains? (item-work/job-scope world (:data begun)) [:person "dawson"]))))
      (finally (e/stop-run run)))))

(def ^:private hair [(spend ["lady-ashby"] "dressing her ladyship's hair" 1.0)])

(deftest a-named-person-who-stays-is-changed
  (let [judged (atom [])
        run (rounds! (start {:llm-impl (scripted {"dawson" {1 hair}} :judged judged)}) 3)
        world (e/world run)
        [done] (of-type run :item/work-done)]
    (try
      (is (= [{:person "lady-ashby" :aspect :hair :from nil :to "dressed by Dawson"}] (get-in done [:data :changes])))
      (is (= {:hair "dressed by Dawson"} (aspects-of run "lady-ashby")))
      (is (nil? (aspects-of run "dawson")))
      (is (= ["dawson" "lady-ashby"] (map :id (filter :person? (:things (first @judged))))))
      (is (= "Dawson finishes dressing her ladyship's hair. (Lady Ashby: dressed by Dawson)."
             (n/describe done (n/names world))))
      (is (= {:hair "dressed by Dawson"} (get-in (view run "lady-ashby") [:you :aspects])))
      (is (= world (w/replay (store/read-events (:dir run)))))
      (finally (e/stop-run run)))))

(deftest a-named-person-who-left-is-not-changed
  (let [judged (atom [])
        run (rounds! (start {:llm-impl (scripted {"dawson" {1 hair}
                                                  "lady-ashby" {2 [{:tool :move :args {:to "landing"}}]}}
                                                 :judged judged)}) 3)
        [done] (of-type run :item/work-done)]
    (try
      (is (= 3 (:round done)))
      (is (empty? (get-in done [:data :changes])))
      (is (nil? (aspects-of run "lady-ashby")))
      (is (= ["dawson"] (map :id (filter :person? (:things (first @judged))))) "only the worker is judged")
      (is (= (e/world run) (w/replay (store/read-events (:dir run)))))
      (finally (e/stop-run run)))))

(deftest a-named-person-may-rouse-the-worker
  (let [run (rounds! (start {:llm-impl (scripted {"dawson" {1 hair 2 [{:tool :pass}]}
                                                  "lady-ashby" {2 [{:tool :rouse :args {:who "dawson"}} {:tool :pass}]}})}) 3)
        [done] (of-type run :item/work-done)]
    (try
      (is (= {:minutes 30 :stopped-early? true} (select-keys (:data done) [:minutes :stopped-early?])))
      (is (= {:hair "dressed by Dawson"} (aspects-of run "lady-ashby")) "judged on the time worked")
      (is (str/starts-with? (n/describe done (n/names (e/world run))) "Dawson leaves off dressing her ladyship's hair."))
      (finally (e/stop-run run)))))

(deftest people-are-not-things
  (let [run (start)]
    (try
      (e/emit! run [{:type :item/work-begun :visible-to :gm
                     :data {:id "job-x" :actor "dawson" :items ["dressing-table"] :people ["lady-ashby"]
                            :doing "doing her hair" :place "bedroom" :started 0 :due 1}}])
      (let [world (e/world run)
            things (item-work/judged-things world (items/job world "job-x"))
            judge #(item-judge/judgement {:tool-calls [{:id "j" :name "judge" :arguments (json/write-value-as-string (assoc % :summary "x"))}]}
                                         world things)]
        (is (= ["bedroom" "dressing-table" "dawson" "lady-ashby"] (map :id things)) "people last, by id")
        (is (str/ends-with? (:error (judge {:gone [{:thing "lady-ashby" :fate "eaten"}]})) "; not lady-ashby"))
        (is (str/starts-with? (:error (judge {:absorbed [{:thing "dawson" :into "dressing-table"}]})) "absorb only unfixed"))
        (is (str/starts-with? (:error (judge {:made [{:name "Curl" :desc "A curl." :consumable false :in "lady-ashby"}]}))
                              "in must be the id of a container among these: none"))
        (is (= [{:person "lady-ashby" :aspect :hair :from nil :to "up"}]
               (:changes (:verdict (judge {:changes [{:thing "lady-ashby" :aspect "hair" :value "up"}]}))))))
      (finally (e/stop-run run)))))

(deftest two-jobs-on-one-person-run-in-order
  (let [judged (atom [])
        run (rounds! (start {:llm-impl (scripted {"lady-ashby" {1 [(spend [] "dressing for dinner" 0.5)]}
                                                  "dawson" {1 [(spend ["lady-ashby"] "doing her ladyship's hair" 0.5)]}}
                                                 :judged judged)}) 2)
        lady (fn [ctx] (:aspects (first (filter #(= "lady-ashby" (:id %)) (:things ctx)))))
        [first-ctx second-ctx] (sort-by (comp :id :job) @judged)]
    (try
      (is (= 2 (count @judged)))
      (is (nil? (lady first-ctx)))
      (is (= (into {} (for [done (of-type run :item/work-done)
                            :when (= (:id (:job first-ctx)) (get-in done [:data :job]))
                            {:keys [person aspect to]} (get-in done [:data :changes])
                            :when (= "lady-ashby" person)]
                        [aspect to]))
             (lady second-ctx))
          "the second judge sees the first's result")
      (is (seq (lady second-ctx)))
      (is (= {:dress "changed: dressing for dinner" :hair "dressed by Dawson"} (aspects-of run "lady-ashby")))
      (is (= (e/world run) (w/replay (store/read-events (:dir run)))))
      (finally (e/stop-run run)))))

;; ---------------------------------------------------------------------------
;; Overnight

(defn- dressed-file
  "The fixture with Lady Ashby dressed for dinner, written under test-runs/."
  []
  (let [file (str (gensym "dressed-") ".edn")]
    (store/write-text! store/test-runs-root file
                       (wf/->edn-string (assoc-in (wf/read-file dressing/world-file) [:agents 0 :aspects]
                                                  {:hair "dressed high, with combs" :dress "in her evening gown"})))
    (str (io/file store/test-runs-root file))))

(defn- say [args]
  {:tool-calls [{:id "s1" :name "steward" :arguments (json/write-value-as-string (assoc args :summary "Night."))}]})

(deftest the-steward-undresses-sleepers
  (let [run (start {:world-file (dressed-file) :start "1904-10-16T01:30"
                    :llm-impl (llm/mock (fn [{:keys [world agent-id role] :as ctx}]
                                          (case role
                                            :steward (dressing/mock-steward ctx)
                                            :judge (dressing/mock-judge ctx)
                                            :gm {:tool :done}
                                            (if (and (= "lady-ashby" agent-id) (= 1 (:round world)))
                                              {:tool :sleep :args {:until "07:00"}}
                                              {:tool :pass}))))})]
    (try
      (rounds! run 1)
      (let [world (e/world run)
            sleepers (item-steward/steward-sleepers world)]
        (testing "only people asleep with aspects; Mrs Pell is awake"
          (is (= {"bedroom" ["lady-ashby"]} (update-vals sleepers #(mapv :id %)))))
        (testing "the request shows the sleepers, as they look"
          (let [request (item-steward/steward-request world "bedroom" [] (get sleepers "bedroom"))
                content (get-in request [:messages 1 :content])]
            (is (str/includes? content "\"sleepers\""))
            (is (str/includes? content "dressed high, with combs"))
            (is (str/includes? content "usually in a grey day dress"))
            (is (not (str/includes? content "grease")))
            (is (str/includes? (item-steward/steward-instructions {}) "Never set or change a sleeper's aspect"))))
        (testing "a sleeper's aspects may only be removed"
          (let [check #(item-steward/stewardship (say %) world "bedroom" [] (get sleepers "bedroom"))]
            (is (= [{:person "lady-ashby" :aspect :hair :from "dressed high, with combs" :to nil}]
                   (:changes (:verdict (check {:changes [{:thing "lady-ashby" :aspect "hair" :value ""}]})))))
            (is (= "a sleeper's aspects can only be removed, with the value \"\"; not lady-ashby hair \"let down\""
                   (:error (check {:changes [{:thing "lady-ashby" :aspect "hair" :value "let down"}]}))))
            (is (str/starts-with? (:error (check {:changes [{:thing "mrs-pell" :aspect "apron" :value ""}]}))
                                  "you may change only these things: lady-ashby; not mrs-pell"))
            (is (str/ends-with? (:error (check {:gone [{:thing "lady-ashby" :fate "eaten"}]})) "; not lady-ashby")))))
      (rounds! run 1)
      (let [world (e/world run)
            [stewarded] (of-type run :items/stewarded)]
        (testing "at 02:00 the steward lets her hair down and leaves the gown; nobody awake changes"
          (is (= "1904-10-16" (:items-steward-day world)))
          (is (= {:dress "in her evening gown"} (aspects-of run "lady-ashby")))
          (is (= {:apron "spattered with grease"} (aspects-of run "mrs-pell")))
          (is (= "bedroom" (get-in stewarded [:data :place])))
          (is (= 1 (count (of-type run :items/stewarded))) "no call for the kitchen: no candidates, nobody asleep")
          (is (= :gm (:visible-to stewarded)))
          (is (= "Steward at Lady's bedroom: Everything was left as it was. (Lady Ashby: no longer dressed high, with combs)."
                 (n/describe stewarded (n/names world)))))
        (testing "she finds it gone when she wakes; she's told nothing"
          (is (= {:dress "in her evening gown"} (get-in (view run "lady-ashby") [:you :aspects])))
          (is (not-any? #(= :items/stewarded (:type %)) (prompt/visible-events (e/events run) "lady-ashby" 1000))))
        (is (= world (w/replay (store/read-events (:dir run))))))
      (finally (e/stop-run run)))))
