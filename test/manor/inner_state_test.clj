(ns manor.inner-state-test
  "Inner state (manor.mechanics.inner) in the dressing fixture world: checks,
  starting values, privacy of the views, the work judge's changes and their
  checks, the export and replay. Nothing here depends on Claudeworth's
  content."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [manor.engine :as e]
            [manor.io.store :as store]
            [manor.io.worldfile :as wf]
            [manor.mechanics.items :as items]
            [manor.mechanics.items.judge :as item-judge]
            [manor.mechanics.items.steward :as item-steward]
            [manor.mechanics.items.views :as item-views]
            [manor.mechanics.items.work :as item-work]
            [manor.mechanics.inner :as inner]
            [manor.narrate :as n]
            [manor.prompt :as prompt]
            [manor.scenarios.dressing :as dressing]
            [manor.world :as w]))

(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

(defn- start [& [opts]]
  (e/start-run :dressing (merge {:llm :mock :test? true :run-id (str (gensym "inner-"))} opts)))

(defn- rounds! [run k]
  (e/auto! run k)
  (is (= :idle (e/wait-idle run 60000)))
  run)

(defn- inner-of [run id] (:inner (w/agent (e/world run) id)))

(defn- of-type [run t] (filter #(= t (:type %)) (e/events run)))

(defn- view [run id] (prompt/agent-view (:scenario run) (e/world run) id))

(def ^:private config (:inner dressing/scenario))

;; ---------------------------------------------------------------------------
;; Data and checks

(deftest inner-state-is-checked
  (let [good (wf/read-file dressing/world-file)
        problems #(wf/problems (assoc-in good [:agents 0 :inner] %))]
    (is (empty? (wf/problems good)))
    (is (= {:body "aching feet after a long day" :worry "whether the fish will keep"}
           (:inner (nth (:agents (wf/load-world dressing/world-file)) 2))))
    (testing "world files: keys and values, without the scenario's keys"
      (is (empty? (problems {:mood "low"})))
      (is (= ["agent \"Lady Ashby\": {:inner {:mood [\"should be at most 200 characters\"]}}"]
             (problems {:mood (apply str (repeat 201 "x"))})))
      (is (= ["agent \"Lady Ashby\": {:inner {:mood [\"should be at least 1 character\"]}}"] (problems {:mood ""})))
      (is (str/includes? (first (problems {:Mood "low"})) "should be a key of lower-case letters")))
    (testing ":init agents are checked against the scenario's keys"
      (is (nil? (#'e/check-agents! dressing/scenario [{:name "X" :inner {:mood "low" :a "1" :b "2"}} {:name "Y"}])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"agent \"X\": its inner state has 3 keys besides mood, body \(a, b, c\); at most 2"
                            (#'e/check-agents! dressing/scenario [{:name "X" :inner {:mood "low" :a "1" :b "2" :c "3"}}])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"agent \"X\": \{:inner"
                            (#'e/check-agents! dressing/scenario [{:name "X" :inner {:mood ""}}])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"agent \"X\": this scenario has no inner state"
                            (#'e/check-agents! (dissoc dressing/scenario :inner) [{:name "X" :inner {:mood "low"}}]))))))

(deftest people-start-empty-unless-given
  (let [run (start)]
    (try
      (let [world (e/world run)]
        (is (= {:mood "impatient for dinner"} (inner-of run "lady-ashby")))
        (is (= {:mood 0} (:inner-at (w/agent world "lady-ashby"))) "set as the run starts")
        (is (= {:body "aching feet after a long day" :worry "whether the fish will keep"} (inner-of run "mrs-pell")))
        (is (not-any? #(contains? (w/agent world "dawson") %) [:inner :inner-at]) "nothing filled in"))
      (finally (e/stop-run run)))))

;; ---------------------------------------------------------------------------
;; Views

(deftest only-the-person-sees-it
  (let [run (start)]
    (try
      (let [world (e/world run)
            lady (view run "lady-ashby")
            dawson (view run "dawson")]
        (testing "your own, under you"
          (is (= {:mood "impatient for dinner"} (get-in lady [:you :inner])))
          (is (not (contains? (:you dawson) :inner)) "none when you have none"))
        (testing "not for someone in the same room, in people-here or look"
          (is (some #(= "lady-ashby" (:id %)) (get-in dawson [:location :people-here])))
          (is (not (str/includes? (prompt/->json dawson) "impatient")))
          (is (not (str/includes? (pr-str (item-views/look world "dawson")) "impatient"))))
        (testing "the GM sees everyone's"
          (is (= {:mood "impatient for dinner"}
                 (:inner (first (filter #(= "lady-ashby" (:id %)) (:agents (prompt/default-gm-view world)))))))))
      (finally (e/stop-run run)))))

(defn- values-held
  "Each agent's inner values over the run: at the start and every one set since."
  [run]
  (merge-with into
              (into {} (for [a (w/active-agents (w/replay (take-while #(not= :round/started (:type %)) (e/events run))))]
                         [(:id a) (set (vals (:inner a)))]))
              (apply merge-with into (for [ev (of-type run :person/inner-changed)]
                                       {(get-in ev [:data :person]) (set (keep :to (get-in ev [:data :changes])))}))))

(deftest no-other-agent-request-holds-it
  (let [run (rounds! (start) 8)
        held (values-held run)
        calls (remove #(#{"gm" "judge" "steward"} (:agent %)) (store/read-llm (:dir run)))
        text (fn [call] (str/join "\n" (map :content (:messages call))))]
    (try
      (is (= #{"lady-ashby" "dawson" "mrs-pell"} (set (map :agent calls))))
      (is (some #(str/includes? % "pleased with Dawson's work") (get held "lady-ashby")) "changed during the day")
      (testing "a person's own requests hold their inner state"
        (is (some #(str/includes? (text %) "pleased with Dawson's work") (filter #(= "lady-ashby" (:agent %)) calls))))
      (testing "nobody else's requests hold any value they ever had"
        (doseq [call calls
                [id values] held
                :when (not= id (:agent call))
                v values]
          (is (not (str/includes? (text call) v)) (str (:agent call) " saw " id "'s " (pr-str v)))))
      (testing "the changes are the GM's only"
        (is (seq (of-type run :person/inner-changed)))
        (is (every? #(= :gm (:visible-to %)) (of-type run :person/inner-changed))))
      (is (= (e/world run) (w/replay (store/read-events (:dir run)))))
      (finally (e/stop-run run)))))

;; ---------------------------------------------------------------------------
;; The work judge

(defn- scene-json [request]
  (let [content (get-in request [:messages 1 :content])
        from (+ (str/index-of content "```json\n") 8)]
    (json/read-value (subs content from (str/index-of content "\n```" from)) json/keyword-keys-object-mapper)))

(defn- begin!
  "Start job-x by `actor` at `place` with the people `on`, due at once."
  [run actor place on]
  (e/emit! run [{:type :item/work-begun :visible-to :gm
                 :data {:id "job-x" :actor actor :items [] :people on :doing "seeing to things" :place place :started 0 :due 1}}]))

(deftest the-judge-sees-everyone-present
  (let [run (start)]
    (try
      (e/emit! run [{:type :agent/placed :data {:id "mrs-pell" :to "bedroom"}}])
      (begin! run "dawson" "bedroom" ["lady-ashby"])
      (let [world (e/world run)
            job (items/job world "job-x")
            request (item-judge/judge-request (:scenario run) world job {:minutes 30 :luck 50})
            people (into {} (map (juxt :name identity)) (:people (scene-json request)))
            system (get-in request [:messages 0 :content])]
        (testing "every fixed key, empty or not, with when it was set"
          (is (= {:mood {:value "impatient for dinner" :set_at "18:00"} :body {:value ""}}
                 (:inner (people "Lady Ashby")))))
        (testing "a bystander's inner state, but nothing more"
          (is (= {:name "Mrs Pell"
                  :inner {:mood {:value ""} :body {:value "aching feet after a long day" :set_at "18:00"}
                          :worry {:value "whether the fish will keep" :set_at "18:00"}}}
                 (people "Mrs Pell"))))
        (testing "the judged people's persona"
          (is (= "Lady Ashby's maid, quick with a comb." (:persona (people "Dawson"))))
          (is (true? (:person (people "Dawson")))))
        (testing "the instructions and the tool"
          (is (str/includes? system "## Inner state"))
          (is (str/includes? system "- body: how their body is, as only they know it"))
          (is (contains? (get-in request [:tools 0 :function :parameters :properties]) :inner))
          (is (not (contains? (get-in (item-steward/steward-request world "bedroom" []) [:tools 0 :function :parameters :properties]) :inner))
              "the steward has no inner field"))
        (testing "a scenario without inner state is asked as before"
          (let [plain (item-judge/judge-request (dissoc (:scenario run) :inner) world job {:minutes 30 :luck 50})]
            (is (not (str/includes? (get-in plain [:messages 0 :content]) "Inner state")))
            (is (not (contains? (get-in plain [:tools 0 :function :parameters :properties]) :inner)))
            (is (not-any? :inner (:people (scene-json plain)))))))
      (finally (e/stop-run run)))))

(deftest the-worker-and-a-named-person-change
  (let [run (rounds! (start) 3)
        world (e/world run)
        [done] (filter #(= "dawson" (:actor %)) (of-type run :item/work-done))
        changed (filter #(= (get-in done [:data :job]) (get-in % [:data :source :job])) (of-type run :person/inner-changed))]
    (try
      (is (= 3 (:round done)))
      (testing "one GM-only event a person, after the public result"
        (is (= ["dawson" "lady-ashby"] (map #(get-in % [:data :person]) changed)))
        (is (every? #(< (:id done) (:id %)) changed))
        (is (= {:person "lady-ashby" :source {:job (get-in done [:data :job])}
                :changes [{:key :mood :from "impatient for dinner" :to "pleased with Dawson's work"}]}
               (:data (second changed)))))
      (testing "the public result holds no inner state"
        (is (not (contains? (:data done) :inner)))
        (is (not (str/includes? (pr-str done) "pleased with")))
        (is (not (str/includes? (pr-str done) "tired from"))))
      (is (= {:mood "pleased with Dawson's work"} (inner-of run "lady-ashby")))
      (is (= {:mood 3} (:inner-at (w/agent world "lady-ashby"))))
      (is (= {:body "tired from dressing her ladyship's hair"} (inner-of run "dawson")))
      (is (= (str "Lady Ashby, inside (job " (get-in done [:data :job]) "): mood \"pleased with Dawson's work\".")
             (n/describe (second changed) (n/names world))))
      (testing "the transcript shows the changes"
        (is (str/includes? (manor.tools.transcript/render (:dir run)) "mood \"pleased with Dawson's work\".  _[gm]_")))
      (testing "the exported map keeps the live inner state, and loads again"
        (let [agents (:agents (wf/load-world (str (io/file (:dir run) "map.edn"))))]
          (is (= {:mood "pleased with Dawson's work"} (:inner (first agents))))
          (is (= {:body "tired from dressing her ladyship's hair"} (:inner (second agents))))))
      (is (= world (w/replay (store/read-events (:dir run)))))
      (finally (e/stop-run run)))))

(defn- judge [world things args]
  (item-judge/judgement {:tool-calls [{:id "j" :name "judge" :arguments (json/write-value-as-string (assoc args :summary "x"))}]}
                        world things config))

(deftest the-judges-answer-is-checked
  (let [run (start)]
    (try
      (e/emit! run [{:type :agent/placed :data {:id "mrs-pell" :to "bedroom"}}])
      (begin! run "dawson" "bedroom" ["lady-ashby"])
      (let [world (e/world run)
            things (item-work/judged-things world (items/job world "job-x"))
            answer #(judge world things {:inner %})]
        (testing "only the judged people"
          (is (= "you may change the inner state only of these people: dawson, lady-ashby; not mrs-pell"
                 (:error (answer [{:person "mrs-pell" :key "mood" :value "cheered"}])))))
        (testing "keys are well formed and normalised"
          (is (str/starts-with? (:error (answer [{:person "dawson" :key "Mood!" :value "x"}])) "an inner key is lower-case"))
          (is (= [{:person "dawson" :key :mind-set :from nil :to "calm"}]
                 (:inner (:verdict (answer [{:person "dawson" :key "mind_set" :value "calm"}]))))))
        (testing "values fit"
          (is (str/includes? (:error (answer [{:person "dawson" :key "mood" :value (apply str (repeat 201 "x"))}]))
                             "is 201 characters")))
        (testing "a fixed key can be emptied; unchanged and absent ones are left out"
          (let [v (:verdict (answer [{:person "lady-ashby" :key "mood" :value ""}
                                     {:person "lady-ashby" :key "body" :value ""}
                                     {:person "dawson" :key "mood" :value "content"}
                                     {:person "dawson" :key "mood" :value "calm"}]))]
            (is (= [{:person "lady-ashby" :key :mood :from "impatient for dinner" :to nil}
                    {:person "dawson" :key :mood :from nil :to "calm"}]
                   (:inner v)) "the last of a key counts")
            (e/emit! run (inner/changed-events {:job "job-x"} (:inner v)))
            (is (not-any? #(contains? (w/agent (e/world run) "lady-ashby") %) [:inner :inner-at]))
            (is (= "Lady Ashby, inside (job job-x): mood cleared."
                   (n/describe (last (filter #(= "lady-ashby" (get-in % [:data :person])) (of-type run :person/inner-changed)))
                               (n/names (e/world run)))))))
        (testing "without inner state the answer's inner list is dropped"
          (is (nil? (:inner (:verdict (item-judge/judgement {:tool-calls [{:id "j" :name "judge"
                                                                            :arguments "{\"inner\":[{\"person\":\"dawson\",\"key\":\"mood\",\"value\":\"x\"}],\"summary\":\"x\"}"}]}
                                                            world things)))))))
      (is (= (e/world run) (w/replay (e/events run))))
      (finally (e/stop-run run)))))

(deftest open-keys-are-capped
  (let [run (start)]
    (try
      (begin! run "mrs-pell" "kitchen" [])
      (let [world (e/world run)
            things (item-work/judged-things world (items/job world "job-x"))
            answer #(judge world things {:inner %})]
        (testing "too many open keys are refused"
          (is (= (str "Mrs Pell (mrs-pell) would have 3 keys besides mood, body (worry, dread, hope); at most 2: "
                      "change one they have instead, or clear one with the value \"\"")
                 (:error (answer [{:person "mrs-pell" :key "dread" :value "the soufflé"}
                                  {:person "mrs-pell" :key "hope" :value "an early night"}])))))
        (testing "fixed keys don't count, and clearing one makes room"
          (let [v (:verdict (answer [{:person "mrs-pell" :key "worry" :value ""}
                                     {:person "mrs-pell" :key "dread" :value "the soufflé"}
                                     {:person "mrs-pell" :key "hope" :value "an early night"}
                                     {:person "mrs-pell" :key "mood" :value "harried"}]))]
            (is (= 4 (count (:inner v))))
            (e/emit! run (inner/changed-events {:job "job-x"} (:inner v)))
            (testing "an emptied open key is removed"
              (is (= {:body "aching feet after a long day" :dread "the soufflé" :hope "an early night" :mood "harried"}
                     (inner-of run "mrs-pell")))
              (is (= #{:body :dread :hope :mood} (set (keys (:inner-at (w/agent (e/world run) "mrs-pell"))))))))))
      (is (= (e/world run) (w/replay (e/events run))))
      (finally (e/stop-run run)))))
