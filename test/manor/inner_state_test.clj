(ns manor.inner-state-test
  "Inner state (manor.mechanics.inner) in the dressing fixture world: checks,
  starting values, privacy of the views, the work judge's, the conversation
  judge's and the nightly steward's changes and their checks, the export
  and replay.
  Nothing here depends on Claudeworth's content."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [manor.engine :as e]
            [manor.io.llm :as llm]
            [manor.io.store :as store]
            [manor.io.worldfile :as wf]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.conversation :as conversation]
            [manor.mechanics.conversation.judge :as conversation-judge]
            [manor.mechanics.items :as items]
            [manor.mechanics.items.judge :as item-judge]
            [manor.mechanics.items.steward :as item-steward]
            [manor.mechanics.items.views :as item-views]
            [manor.mechanics.items.work :as item-work]
            [manor.mechanics.inner :as inner]
            [manor.mechanics.space :as space]
            [manor.narrate :as n]
            [manor.prompt :as prompt]
            [manor.scenarios.dressing :as dressing]
            [manor.tools.cache-report :as cache-report]
            [manor.tools.transcript :as transcript]
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
        all-calls (store/read-llm (:dir run))
        calls (remove #(#{"gm" "judge" "steward" "conversation-judge"} (:agent %)) all-calls)
        text (fn [call] (str/join "\n" (map :content (:messages call))))]
    (try
      (is (= #{"lady-ashby" "dawson" "mrs-pell"} (set (map :agent calls))))
      (is (some #(str/includes? % "pleased with Dawson's work") (get held "lady-ashby")) "changed during the day")
      (is (some #(str/includes? % "turning over what Dawson said") (get held "lady-ashby")) "changed by a conversation")
      (testing "a person's own requests hold their inner state"
        (is (some #(str/includes? (text %) "pleased with Dawson's work") (filter #(= "lady-ashby" (:agent %)) calls))))
      (testing "nobody else's requests hold any value they ever had"
        (doseq [call calls
                [id values] held
                :when (not= id (:agent call))
                v values]
          (is (not (str/includes? (text call) v)) (str (:agent call) " saw " id "'s " (pr-str v)))))
      (testing "the conversation judge sees no non-member's"
        (let [judged (filter #(= "conversation-judge" (:agent %)) all-calls)]
          (is (= 1 (count judged)))
          (doseq [call judged, v (get held "mrs-pell")]
            (is (not (str/includes? (text call) v)) (str "the conversation judge saw Mrs Pell's " (pr-str v))))))
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
              "nor the steward, asked without the scenario's inner state"))
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

;; ---------------------------------------------------------------------------
;; The conversation judge

(defn- scripted
  "A mock: `script` maps agent id -> round -> the calls of that turn, in
  order; other turns pass (the GM is done). Speaking turns go to `speak`,
  or conversation/mock-default when it returns nil; the conversation judge
  to `react`, or the fixture's mock. Work goes to the fixture's mock judge,
  the night to its mock steward."
  [script & {:keys [speak react]}]
  (llm/mock (fn [{:keys [world agent-id role step] :as ctx}]
              (case role
                :judge (dressing/mock-judge ctx)
                :steward (dressing/mock-steward ctx)
                :conversation-judge ((or react dressing/mock-conversation-judge) ctx)
                :gm {:tool :done}
                (if (:conversation ctx)
                  (or (when speak (speak ctx)) (conversation/mock-default ctx))
                  (get-in script [agent-id (:round world) step] {:tool :pass}))))))

(defn- talks
  "Lady Ashby's first turn: a word with `with`, then she passes."
  [with]
  {"lady-ashby" {1 [{:tool :converse :args {:with with :text "Is my blue silk pressed?"}} {:tool :pass}]}})

(defn- answers [text] (fn [{:keys [agent-id conversation]}]
                        (when (and (not= agent-id (:initiator conversation)) (= 1 (:pass conversation)))
                          {:tool :reply :args {:text text}})))

(defn- judge-calls [run] (filter #(= "conversation-judge" (:agent %)) (store/read-llm (:dir run))))

(defn- ending [run] (get-in (first (of-type run :conversation/ended)) [:data :reason]))

(deftest members-change-after-a-conversation
  (let [run (rounds! (start) 5)
        world (e/world run)
        [ended] (of-type run :conversation/ended)
        id (get-in ended [:data :conversation])
        [judged] (of-type run :conversation/judged)
        changed (filter #(= {:conversation id} (get-in % [:data :source])) (of-type run :person/inner-changed))]
    (try
      (is (= :ended (ending run)))
      (testing "after the end, before anything else: the summary, then one change a member"
        (is (= [:llm/usage :conversation/judged :person/inner-changed :person/inner-changed]
               (map :type (take 4 (drop-while #(<= (:id %) (:id ended)) (e/events run)))))
            "only the judge's usage comes between")
        (is (= {:about id :summary "Each was left turning over what was said."} (:data judged)))
        (is (= ["dawson" "lady-ashby"] (map #(get-in % [:data :person]) changed)))
        (is (= [{:key :mind :from nil :to "turning over what Dawson said"}] (get-in (second changed) [:data :changes]))))
      (testing "GM-only, and no member is told"
        (is (every? #(= :gm (:visible-to %)) (cons judged changed)))
        (is (not-any? #(w/visible-to? % "lady-ashby") (cons judged changed))))
      (is (= "turning over what Dawson said" (:mind (inner-of run "lady-ashby"))))
      (is (= "turning over what Lady Ashby said" (:mind (inner-of run "dawson"))))
      (is (= 5 (get-in (w/agent world "dawson") [:inner-at :mind])))
      (testing "the initiator's summary doesn't hold it"
        (let [summary (some #(when (str/starts-with? (:content %) "The conversation is over") (:content %))
                            (mapcat :replies (filter #(= "lady-ashby" (:agent %)) (store/read-llm (:dir run)))))]
          (is (some? summary))
          (is (not (str/includes? summary "turning over")))))
      (testing "the call: one, recorded as the conversation judge, counted with the narrow calls"
        (let [[call] (judge-calls run)]
          (is (= 1 (count (judge-calls run))))
          (is (= ["react"] (:tools call)))
          (is (= :adjudicator (cache-report/kind call)))))
      (testing "the transcript shows the call and the verdict"
        (let [md (transcript/render (:dir run))]
          (is (str/includes? md "**conversation-judge** react"))
          (is (str/includes? md (str "The conversation judge on " id ": Each was left turning over what was said.  _[gm]_")))
          (is (str/includes? md (str "Dawson, inside (conversation " id "): mind \"turning over what Lady Ashby said\".")))))
      (is (= world (w/replay (store/read-events (:dir run)))))
      (finally (e/stop-run run)))))

(deftest the-conversation-judge-sees-the-members-and-the-lines
  (let [run (start)]
    (try
      (e/emit! run [{:type :agent/placed :data {:id "mrs-pell" :to "bedroom"}}])
      (let [world (e/world run)
            all #{"lady-ashby" "dawson" "mrs-pell"}
            said (fn [actor text heard] {:type :agent/said :actor actor :visible-to heard :data {:text text :conversation "conv-1"}})
            events [{:type :conversation/started :actor "lady-ashby" :visible-to all :data {:conversation "conv-1"}}
                    (said "lady-ashby" "Is my blue silk pressed?" all)
                    {:type :agent/said :actor "mrs-pell" :visible-to all :data {:text "Elsewhere." :conversation "conv-2"}}
                    (said "dawson" "Not yet, my lady." #{"dawson" "lady-ashby"})
                    {:type :conversation/silent :actor "mrs-pell" :visible-to all :data {:conversation "conv-1"}}
                    {:type :conversation/left :actor "dawson" :visible-to all :data {:conversation "conv-1"}}]
            conv {:id "conv-1" :initiator "lady-ashby" :members ["lady-ashby" "dawson" "mrs-pell"]}
            request (conversation-judge/judge-request config world conv (conversation-judge/lines events "conv-1"))
            {:keys [members lines]} (scene-json request)]
        (testing "the lines in order, and who heard one when not every member did"
          (is (= [{:person "lady-ashby" :says "Is my blue silk pressed?"}
                  {:person "dawson" :says "Not yet, my lady." :heard_by ["lady-ashby" "dawson"]}
                  {:person "mrs-pell" :does "says nothing"}
                  {:person "dawson" :does "leaves the conversation, staying within hearing"}]
                 lines)))
        (testing "each member, with every fixed key and when it was set"
          (is (= ["lady-ashby" "dawson" "mrs-pell"] (map :id members)))
          (is (= {:id "lady-ashby" :name "Lady Ashby"
                  :persona "A particular woman who likes to look well at dinner."
                  :appearance "A tall woman of fifty, usually in a grey day dress, her hair in a plain knot."
                  :inner {:mood {:value "impatient for dinner" :set_at "18:00"} :body {:value ""}}}
                 (first members))))
        (testing "the place, the time, the instructions and the tool"
          (is (str/includes? (get-in request [:messages 1 :content]) "- Time: Saturday 15 October 1904, 18:00\n- Place: Lady's bedroom\n"))
          (is (str/includes? (get-in request [:messages 0 :content]) "\n- body: how their body is, as only they know it"))
          (is (= #{:inner :summary} (set (keys (get-in request [:tools 0 :function :parameters :properties]))))))
        (testing "nothing about anyone else"
          (let [only-two (conversation-judge/judge-request config world (assoc conv :members ["lady-ashby" "dawson"])
                                                           (take 2 (conversation-judge/lines events "conv-1")))]
            (is (not (str/includes? (pr-str (:messages only-two)) "aching feet")))
            (is (not (str/includes? (pr-str (:messages only-two)) "Mrs Pell"))))))
      (finally (e/stop-run run)))))

(deftest a-non-member-present-cant-be-changed
  (let [react (fn [{:keys [step]}]
                {:tool :react :args {:summary "Mrs Pell overheard."
                                     :inner (if (zero? step)
                                              [{:person "mrs-pell" :key "mood" :value "put out at being ignored"}]
                                              [{:person "dawson" :key "mood" :value "anxious about the silk"}])}})
        run (start {:llm-impl (scripted (talks ["dawson"]) :speak (answers "Not yet, my lady.") :react react)})]
    (try
      (e/emit! run [{:type :agent/placed :data {:id "mrs-pell" :to "bedroom"}}])
      (rounds! run 1)
      (let [[first-call second-call] (judge-calls run)]
        (is (some #(contains? (:visible-to %) "mrs-pell") (of-type run :agent/said)) "she heard it")
        (is (= [{:role "tool" :tool_call_id "mock-0-react"
                 :content (str "error: you may change the inner state only of these people: lady-ashby, dawson; not mrs-pell. "
                               "Call react again with a corrected answer.")}]
               (:replies first-call)))
        (is (= "ok" (:content (first (:replies second-call))))))
      (is (= {:body "aching feet after a long day" :worry "whether the fish will keep"} (inner-of run "mrs-pell")))
      (is (= {:mood "anxious about the silk"} (inner-of run "dawson")))
      (is (= (e/world run) (w/replay (store/read-events (:dir run)))))
      (finally (e/stop-run run)))))

(deftest a-failed-judgement-changes-nothing
  (let [react (constantly {:tool :react :args {:summary "x" :inner [{:person "mrs-pell" :key "mood" :value "cross"}]}})
        run (rounds! (start {:llm-impl (scripted (talks ["dawson"]) :speak (answers "Yes, my lady.") :react react)}) 1)
        [failed] (of-type run :conversation/judge-failed)]
    (try
      (is (= 2 (count (judge-calls run))))
      (is (= :system (:visible-to failed)))
      (is (str/starts-with? (get-in failed [:data :reason]) "a second bad answer: you may change the inner state only of"))
      (is (empty? (of-type run :conversation/judged)))
      (is (empty? (of-type run :person/inner-changed)))
      (is (= {:mood "impatient for dinner"} (inner-of run "lady-ashby")))
      (finally (e/stop-run run)))))

(deftest judged-on-silence
  (let [run (rounds! (start {:llm-impl (scripted (talks ["dawson"]))}) 1)]
    (try
      (is (= :silence (ending run)))
      (is (= 1 (count (judge-calls run))))
      (is (= 1 (count (of-type run :conversation/judged))))
      (testing "only the initiator's line was said, so only Dawson heard someone else"
        (is (= "turning over what Lady Ashby said" (:mind (inner-of run "dawson"))))
        (is (nil? (:mind (inner-of run "lady-ashby")))))
      (finally (e/stop-run run)))))

(deftest skipped-when-cut-short
  (testing "on an error"
    (let [run (start {:llm-impl (scripted (talks ["dawson"]) :speak (fn [_] (throw (ex-info "model down" {}))))})]
      (try
        (e/auto! run 1)
        (e/wait-idle run 60000)
        (is (= :error (ending run)))
        (is (empty? (judge-calls run)))
        (is (empty? (of-type run :conversation/judged)))
        (finally (e/stop-run run)))))
  (testing "at the cost limit"
    (let [mock (scripted (talks ["dawson"]) :speak (answers "Yes, my lady."))
          costly (reify llm/LLM
                   (complete [_ {:keys [ctx] :as request}]
                     (cond-> (llm/complete mock request)
                       (and (= "lady-ashby" (:agent-id ctx)) (not (:conversation ctx))) (assoc-in [:usage :cost] 1.0))))
          run (start {:llm-impl costly :max-cost 0.5})]
      (try
        (e/auto! run 1)
        (e/wait-idle run 60000)
        (is (= :cost-limit (ending run)))
        (is (empty? (judge-calls run)))
        (is (empty? (of-type run :conversation/judged)))
        (finally (e/stop-run run))))))

(deftest no-call-without-inner-state
  (let [plain (-> dressing/scenario
                  (dissoc :inner)
                  (assoc :init (fn [opts] (update ((:init dressing/scenario) opts) :agents #(mapv (fn [a] (dissoc a :inner)) %)))))
        run (with-redefs [e/load-scenario (constantly plain)]
              (start {:llm-impl (scripted (talks ["dawson"]) :speak (answers "Yes, my lady."))}))]
    (try
      (rounds! run 1)
      (is (= :ended (ending run)))
      (is (empty? (judge-calls run)))
      (is (empty? (of-type run :conversation/judged)))
      (finally (e/stop-run run)))))

;; ---------------------------------------------------------------------------
;; The nightly steward

(def ^:private bedtime
  "Lady Ashby sleeps in her bedroom and Mrs Pell in the maid's room in the
  first round; Dawson stays up."
  {"lady-ashby" {1 [{:tool :sleep :args {:until "07:00"}}]}
   "mrs-pell" {1 [{:tool :sleep :args {:until "07:00"}}]}})

(defn- night-run
  "A run starting at 01:30 with Mrs Pell in the maid's room, and the
  `bedtime` script; the steward runs at 02:00, in round 2."
  [& [opts]]
  (let [run (start (merge {:start "1904-10-16T01:30" :llm-impl (scripted bedtime)} opts))]
    (e/emit! run [{:type :agent/placed :data {:id "mrs-pell" :to "maids-room"}}])
    run))

(defn- steward-says [args]
  {:tool-calls [{:id "s1" :name "steward" :arguments (json/write-value-as-string (assoc args :summary "Night."))}]})

(deftest the-steward-sees-every-sleeper
  (let [run (rounds! (night-run) 1)]
    (try
      (let [world (e/world run)
            sleepers (item-steward/steward-sleepers world config (e/events run))
            ids #(update-vals % (fn [ss] (mapv :id ss)))]
        (testing "with inner state, everyone asleep, with or without aspects; Dawson is awake"
          (is (= {"bedroom" ["lady-ashby"] "maids-room" ["mrs-pell"]} (ids sleepers)))
          (is (nil? (:aspects (w/agent world "lady-ashby"))) "Lady Ashby has no aspects"))
        (testing "without it, only sleepers with aspects, as before"
          (is (= {"maids-room" ["mrs-pell"]} (ids (item-steward/steward-sleepers world)))))
        (testing "each sleeper shows their inner state, temperament and when they went to sleep and will wake"
          (let [tempered (assoc-in world [:agents "lady-ashby" :temperament] {:neuroticism :high})
                request (item-steward/steward-request tempered "bedroom" [] (get sleepers "bedroom") config)
                [lady] (:sleepers (scene-json request))
                system (get-in request [:messages 0 :content])]
            (is (= {:mood {:value "impatient for dinner" :set_at "01:30"} :body {:value ""}} (:inner lady)))
            (is (= "01:30" (:went_to_sleep lady)))
            (is (= "07:00" (:wakes lady)))
            (is (= {:neuroticism "high"} (:temperament lady)))
            (is (str/includes? system "## Inner state"))
            (is (str/includes? system "- body: how their body is, as only they know it"))
            (is (str/includes? system "Never set or change a sleeper's aspect: only remove."))
            (is (contains? (get-in request [:tools 0 :function :parameters :properties]) :inner))))
        (testing "a scenario without inner state asks as before"
          (let [request (item-steward/steward-request world "maids-room" [] (get (item-steward/steward-sleepers world) "maids-room"))]
            (is (= [{:id "mrs-pell" :name "Mrs Pell" :appearance "A stout woman, usually in a print frock and a white apron."
                     :aspects {:apron "spattered with grease"}}]
                   (:sleepers (scene-json request))))
            (is (= (item-steward/steward-instructions {}) (get-in request [:messages 0 :content])))
            (is (not (contains? (get-in request [:tools 0 :function :parameters :properties]) :inner))))))
      (rounds! run 1)
      (let [world (e/world run)
            stewarded (of-type run :items/stewarded)
            changed (filter #(= {:steward "1904-10-16"} (get-in % [:data :source])) (of-type run :person/inner-changed))]
        (testing "a call for each place with sleepers, none for the kitchen"
          (is (= ["bedroom" "maids-room"] (map #(get-in % [:data :place]) stewarded))))
        (testing "inner state is set, changed and cleared, by GM-only events after each place's result"
          (is (= [{:person "lady-ashby" :source {:steward "1904-10-16"}
                   :changes [{:key :body :from nil :to "stiff from the night"} {:key :mood :from "impatient for dinner" :to nil}]}
                  {:person "mrs-pell" :source {:steward "1904-10-16"}
                   :changes [{:key :body :from "aching feet after a long day" :to "stiff from the night"}]}]
                 (map :data changed)))
          (is (every? #(= :gm (:visible-to %)) changed))
          (is (= (map :id stewarded) (map #(dec (:id %)) changed)) "each follows its place's :items/stewarded")
          (is (= {:body "stiff from the night"} (inner-of run "lady-ashby")))
          (is (= {:body "stiff from the night" :worry "whether the fish will keep"} (inner-of run "mrs-pell")))
          (is (= "Lady Ashby, inside (steward 1904-10-16): body \"stiff from the night\"; mood cleared."
                 (n/describe (first changed) (n/names world)))))
        (testing "Dawson, awake, is untouched"
          (is (nil? (inner-of run "dawson"))))
        (testing ":items/stewarded holds no inner state"
          (is (not-any? #(contains? (:data %) :inner) stewarded))
          (is (not-any? #(str/includes? (pr-str %) "stiff") stewarded)))
        (testing "the sleepers are told nothing"
          (is (not-any? #(= :person/inner-changed (:type %)) (prompt/visible-events (e/events run) "lady-ashby" 1000))))
        (is (= world (w/replay (store/read-events (:dir run))))))
      (finally (e/stop-run run)))))

(deftest the-steward-sees-people-hibernating
  (let [run (rounds! (night-run) 1)]
    (try
      (let [world (e/world run)
            _ (e/emit! run [(b/hibernate world "dawson" "asleep over her mending")])
            world (e/world run)
            sleepers (item-steward/steward-sleepers world config (e/events run))
            here (space/location-of world "dawson")]
        (testing "someone hibernating is seen with the sleepers where they are"
          (is (some #{"dawson"} (map :id (get sleepers here)))))
        (testing "they show what their routine has them doing, and since when"
          (let [request (item-steward/steward-request world here [] (get sleepers here) config)
                dawson (first (filter #(= "dawson" (:id %)) (:sleepers (scene-json request))))]
            (is (= "asleep over her mending" (:doing dawson)))
            (is (= "01:30" (:since dawson)))
            (is (not (contains? dawson :went_to_sleep)))
            (is (contains? dawson :inner))))
        (testing "without inner state, only if they have aspects"
          (is (not-any? #(= "dawson" (:id %)) (mapcat val (item-steward/steward-sleepers world))))))
      (finally (e/stop-run run)))))

(deftest the-stewards-answer-is-checked
  (let [run (rounds! (night-run) 1)]
    (try
      (let [world (e/world run)
            sleepers (get (item-steward/steward-sleepers world config (e/events run)) "maids-room")
            check #(item-steward/stewardship (steward-says %) world "maids-room" [] sleepers config)]
        (testing "a sleeper's inner state may be set or cleared"
          (is (= [{:person "mrs-pell" :key :mood :from nil :to "anxious about the fish"}
                  {:person "mrs-pell" :key :worry :from "whether the fish will keep" :to nil}]
                 (:inner (:verdict (check {:inner [{:person "mrs-pell" :key "mood" :value "anxious about the fish"}
                                                   {:person "mrs-pell" :key "worry" :value ""}]}))))))
        (testing "nobody awake, nor a sleeper elsewhere, can be changed"
          (is (= "you may change the inner state only of these people: mrs-pell; not dawson"
                 (:error (check {:inner [{:person "dawson" :key "mood" :value "sleepy"}]}))))
          (is (= "you may change the inner state only of these people: mrs-pell; not lady-ashby"
                 (:error (check {:inner [{:person "lady-ashby" :key "mood" :value "calm"}]})))))
        (testing "too many open keys are refused"
          (is (str/starts-with? (:error (check {:inner [{:person "mrs-pell" :key "dream" :value "of the sea"}
                                                        {:person "mrs-pell" :key "dread" :value "of the morning"}]}))
                                "Mrs Pell (mrs-pell) would have 3 keys besides mood, body")))
        (testing "outward aspects stay remove-only"
          (is (= "a sleeper's aspects can only be removed, with the value \"\"; not mrs-pell apron \"clean\""
                 (:error (check {:changes [{:thing "mrs-pell" :aspect "apron" :value "clean"}]}))))
          (is (= [{:person "mrs-pell" :aspect :apron :from "spattered with grease" :to nil}]
                 (:changes (:verdict (check {:changes [{:thing "mrs-pell" :aspect "apron" :value ""}]})))))))
      (finally (e/stop-run run)))))

(deftest the-steward-without-inner-state
  (let [plain (-> dressing/scenario
                  (dissoc :inner)
                  (assoc :init (fn [opts] (update ((:init dressing/scenario) opts) :agents #(mapv (fn [a] (dissoc a :inner)) %)))))
        run (with-redefs [e/load-scenario (constantly plain)] (night-run))]
    (try
      (rounds! run 2)
      (testing "only the sleeper with aspects is seen, and no inner state changes"
        (is (= ["maids-room"] (map #(get-in % [:data :place]) (of-type run :items/stewarded))))
        (is (empty? (of-type run :person/inner-changed)))
        (is (not (str/includes? (str/join (map pr-str (filter #(= "steward" (:agent %)) (store/read-llm (:dir run))))) "\"inner\""))))
      (finally (e/stop-run run)))))
