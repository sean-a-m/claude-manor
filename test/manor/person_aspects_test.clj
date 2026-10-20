(ns manor.person-aspects-test
  "Person aspects (manor.items) in the dressing fixture world: checks, views,
  judged work on oneself and on others, job scope, the nightly steward and
  replay. Nothing here depends on Claudeworth's content."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [manor.engine :as e]
            [manor.items :as items]
            [manor.llm :as llm]
            [manor.narrate :as n]
            [manor.prompt :as prompt]
            [manor.scenarios.dressing :as dressing]
            [manor.store :as store]
            [manor.world :as w]
            [manor.worldfile :as wf]))

(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

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
                            (#'e/check-agents! [{:name "X" :aspects {:hair ""}}])))
      (is (nil? (#'e/check-agents! [{:name "X" :aspects {:hair "up"}} {:name "Y"}]))))))

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
                 (:aspects (first (:people (items/look (e/world run) "dawson")))))))
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
        (is (str/includes? (manor.transcript/render (:dir run)) "(Lady Ashby: changed: changing for dinner)")))
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
            things (items/judged-things world (items/job world "job-x"))
            verdict (:verdict (items/judgement {:tool-calls [{:id "j" :name "judge"
                                                              :arguments "{\"changes\":[{\"thing\":\"mrs-pell\",\"aspect\":\"apron\",\"value\":\"\"}],\"summary\":\"x\"}"}]}
                                               world things))]
        (is (= [{:person "mrs-pell" :aspect :apron :from "spattered with grease" :to nil}] (:changes verdict)))
        (e/emit! run [(items/work-done world (items/job world "job-x") {:minutes 30} verdict)])
        (is (not (contains? (w/agent (e/world run) "mrs-pell") :aspects)) "no aspects left, no :aspects")
        (is (= (e/world run) (w/replay (e/events run)))))
      (finally (e/stop-run run)))))
