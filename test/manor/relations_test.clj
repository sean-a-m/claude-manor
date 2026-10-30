(ns manor.relations-test
  "Relations (manor.mechanics.relations) in the dressing fixture world:
  starting relations and their checks, the queue, the views and their
  privacy, the work judge's and the conversation judge's entries and their
  checks, the steward leaving them alone, the export and replay.
  Nothing here depends on Claudeworth's content."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [manor.engine :as e]
            [manor.io.store :as store]
            [manor.io.worldfile :as wf]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.conversation.judge :as conversation-judge]
            [manor.mechanics.items :as items]
            [manor.mechanics.items.judge :as item-judge]
            [manor.mechanics.items.steward :as item-steward]
            [manor.mechanics.items.work :as item-work]
            [manor.mechanics.relations :as relations]
            [manor.narrate :as n]
            [manor.prompt :as prompt]
            [manor.ruleset.init :as init]
            [manor.scenarios.dressing :as dressing]
            [manor.world :as w]))

(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

(defn- start [& [opts]]
  (e/start-run :dressing (merge {:llm :mock :test? true :run-id (str (gensym "relations-"))} opts)))

(defn- rounds! [run k]
  (e/auto! run k)
  (is (= :idle (e/wait-idle run 60000)))
  run)

(defn- relations-of [run id] (:relations (w/agent (e/world run) id)))

(defn- of-type [run t] (filter #(= t (:type %)) (e/events run)))

(defn- view [world id] (prompt/agent-view dressing/scenario world id))

(def ^:private config (:relations dressing/scenario))

(def ^:private dawsons-start
  {:feeling "anxious to please" :because "Lady Ashby found fault with your work on her hair yesterday"})

(def ^:private pells-start
  {:feeling "put out" :because "Dawson took the last of the hot water for her ladyship's bath"})

;; ---------------------------------------------------------------------------
;; Starting relations and their checks

(deftest starting-relations-are-checked
  (let [good (wf/read-file dressing/world-file)
        problems #(wf/problems (assoc-in good [:agents 0 :relations] %))]
    (is (empty? (wf/problems good)))
    (testing "world files: the shape, without the cast"
      (is (empty? (problems {"dawson" [{:feeling "fond" :because "Dawson is kind"}]})))
      (is (str/includes? (first (problems {"dawson" [{:feeling "" :because "x"}]})) "should be at least 1 character"))
      (is (str/includes? (first (problems {"dawson" [{:feeling "fond"}]})) "missing required key"))
      (is (str/includes? (first (problems {"dawson" []})) "should have at least 1 element"))
      (is (str/includes? (first (problems {"dawson" [{:feeling "fond" :because "x" :round 3}]})) "disallowed key")))
    (testing ":init agents: the shape, and only in a scenario with relations"
      (is (nil? (#'e/check-agents! dressing/scenario [{:name "X" :relations {"y" [{:feeling "fond" :because "x"}]}}])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"agent \"X\": \{:relations"
                            (#'e/check-agents! dressing/scenario [{:name "X" :relations {"y" [{:feeling "" :because "x"}]}}])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"agent \"X\": this scenario has no relations"
                            (#'e/check-agents! (dissoc dressing/scenario :relations) [{:name "X" :relations {"y" []}}])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":relations without :inner"
                            (#'e/check-agents! (dissoc dressing/scenario :inner) []))))
    (testing "whom they name, once the cast is spawned"
      (let [run (start)]
        (try
          (let [world (e/world run)
                check #(#'e/check-relations! dressing/scenario world [["Dawson" "dawson" %]])]
            (is (nil? (check {"lady-ashby" [dawsons-start]})))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"agent \"Dawson\": its relations name \"albert\", \"dawson\", \"gm\""
                                  (check {"albert" [dawsons-start] "dawson" [dawsons-start] "gm" [dawsons-start]})))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"5 entries toward lady-ashby; at most 4"
                                  (check {"lady-ashby" (vec (repeat 5 dawsons-start))}))))
          (finally (e/stop-run run)))))
    (testing "the ruleset drops starting relations toward people left out"
      (is (= {:name "A" :relations {"c" [dawsons-start]}}
             (#'init/without-relations-toward #{"b"} {:name "A" :relations {"b" [pells-start] "c" [dawsons-start]}})))
      (is (= {:name "A"} (#'init/without-relations-toward #{"b"} {:name "A" :relations {"b" [pells-start]}}))))))

(deftest people-start-with-their-given-relations
  (let [run (start)]
    (try
      (let [world (e/world run)
            given (of-type run :person/relations-changed)]
        (is (= {"lady-ashby" [(assoc dawsons-start :round 0 :source {:start true})]} (relations-of run "dawson")))
        (is (= {"dawson" [(assoc pells-start :round 0 :source {:start true})]} (relations-of run "mrs-pell")))
        (is (nil? (relations-of run "lady-ashby")))
        (testing "given after the whole cast, by GM-only events"
          (is (= ["dawson" "mrs-pell"] (map #(get-in % [:data :person]) given)))
          (is (every? #(= :gm (:visible-to %)) given))
          (is (every? (fn [ev] (every? #(< (:id %) (:id ev)) (of-type run :agent/spawned))) given)))
        (is (= "Dawson, toward others (start): Lady Ashby: anxious to please, because Lady Ashby found fault with your work on her hair yesterday."
               (n/describe (first given) (n/names world)))))
      (finally (e/stop-run run)))))

;; ---------------------------------------------------------------------------
;; The queue and the views

(defn- feel! [run person toward feeling because]
  (e/emit! run [{:type :person/relations-changed :visible-to :gm
                 :data {:person person :source {:test true} :keep (:keep config)
                        :entries [{:toward toward :feeling feeling :because because}]}}]))

(deftest the-queue-keeps-the-latest
  (let [run (start)]
    (try
      (testing "an entry toward someone new starts their queue, even someone who came during the run"
        (e/emit! run (b/spawn-events dressing/scenario (e/world run) {:name "Mr Quill" :role :cook :location "kitchen"}))
        (feel! run "mrs-pell" "mr-quill" "curious" "Mr Quill came into your kitchen unannounced")
        (is (= ["curious"] (map :feeling (get (relations-of run "mrs-pell") "mr-quill"))))
        (is (= [pells-start] (map #(select-keys % [:feeling :because]) (get (relations-of run "mrs-pell") "dawson")))
            "the other queues are untouched"))
      (testing "at most :keep entries, oldest dropped"
        (doseq [i (range 1 7)]
          (feel! run "lady-ashby" "mrs-pell" (str "feeling " i) (str "reason " i)))
        (is (= ["feeling 3" "feeling 4" "feeling 5" "feeling 6"]
               (map :feeling (get (relations-of run "lady-ashby") "mrs-pell")))))
      (testing "the view shows the latest three, oldest first, with when"
        (e/emit! run [{:type :agent/placed :data {:id "mrs-pell" :to "bedroom"}}])
        (is (= {"Mrs Pell (mrs-pell)" [{:at "18:00" :feeling "feeling 4" :because "reason 4"}
                                       {:at "18:00" :feeling "feeling 5" :because "reason 5"}
                                       {:at "18:00" :feeling "feeling 6" :because "reason 6"}]}
               (get-in (view (e/world run) "lady-ashby") [:you :feelings-toward]))))
      (is (= (e/world run) (w/replay (e/events run))))
      (finally (e/stop-run run)))))

(deftest only-the-person-sees-theirs-for-the-people-here
  (let [run (start)]
    (try
      (let [world (e/world run)]
        (testing "your own, under you, for the people here"
          (is (= {"Lady Ashby (lady-ashby)" [(assoc dawsons-start :at "18:00")]}
                 (get-in (view world "dawson") [:you :feelings-toward])))
          (is (not (contains? (:you (view world "mrs-pell")) :feelings-toward)) "not for Dawson, who is upstairs")
          (is (not (contains? (:you (view world "lady-ashby")) :feelings-toward)) "none when you have none"))
        (testing "not in anyone else's view, people-here or look"
          (is (not (str/includes? (prompt/->json (view world "lady-ashby")) "anxious to please")))
          (is (not (str/includes? (pr-str (manor.mechanics.items.views/look world "lady-ashby")) "anxious to please"))))
        (testing "someone who can't see doesn't see whom they feel things toward"
          (let [blind (assoc-in world [:agents "dawson" :physical :sight] {:level :disabled})]
            (is (not (contains? (:you (view blind "dawson")) :feelings-toward)))))
        (testing "when they come together, Mrs Pell's toward Dawson shows"
          (e/emit! run [{:type :agent/placed :data {:id "mrs-pell" :to "bedroom"}}])
          (is (= {"Dawson (dawson)" [(assoc pells-start :at "18:00")]}
                 (get-in (view (e/world run) "mrs-pell") [:you :feelings-toward]))))
        (testing "the GM sees the latest toward each"
          (is (= {"lady-ashby" dawsons-start}
                 (:feelings-toward (first (filter #(= "dawson" (:id %)) (:agents (prompt/default-gm-view world)))))))))
      (finally (e/stop-run run)))))

;; ---------------------------------------------------------------------------
;; The judges

(defn- scene-json [request]
  (let [content (get-in request [:messages 1 :content])
        from (+ (str/index-of content "```json\n") 8)]
    (json/read-value (subs content from (str/index-of content "\n```" from)) json/keyword-keys-object-mapper)))

(defn- properties [request] (get-in request [:tools 0 :function :parameters :properties]))

(deftest the-work-judge-sees-and-adds-them
  (let [run (start)]
    (try
      (e/emit! run [{:type :agent/placed :data {:id "mrs-pell" :to "bedroom"}}
                    {:type :item/work-begun :visible-to :gm
                     :data {:id "job-x" :actor "dawson" :items [] :people ["lady-ashby"] :doing "seeing to things"
                            :place "bedroom" :started 0 :due 1}}])
      (let [world (e/world run)
            job (items/job world "job-x")
            request (item-judge/judge-request (:scenario run) world job {:minutes 30 :luck 50})
            people (into {} (map (juxt :name identity)) (:people (scene-json request)))]
        (testing "the judged people's feelings toward each other, not a bystander's"
          (is (= {:lady-ashby [(assoc dawsons-start :at "18:00")]} (:feelings_toward (people "Dawson"))))
          (is (= {} (:feelings_toward (people "Lady Ashby"))))
          (is (not (contains? (people "Mrs Pell") :feelings_toward))))
        (testing "the instructions and the tool"
          (is (str/includes? (get-in request [:messages 0 :content]) "## Relations"))
          (is (str/includes? (get-in request [:tools 0 :function :description]) "`relations`"))
          (is (contains? (properties request) :relations))
          (is (not (contains? (properties (item-steward/steward-request world "bedroom" [] [] (:inner dressing/scenario))) :relations))
              "the steward doesn't add them"))
        (testing "a scenario without relations is asked as before"
          (let [plain (item-judge/judge-request (dissoc (:scenario run) :relations) world job {:minutes 30 :luck 50})]
            (is (not (str/includes? (get-in plain [:messages 0 :content]) "Relations")))
            (is (not (str/includes? (get-in plain [:tools 0 :function :description]) "`relations`")))
            (is (not (contains? (properties plain) :relations)))
            (is (not-any? :feelings_toward (:people (scene-json plain)))))))
      (finally (e/stop-run run))))
  (let [run (rounds! (start) 3)
        [done] (filter #(= "dawson" (:actor %)) (of-type run :item/work-done))
        job (get-in done [:data :job])
        [added] (filter #(= {:job job} (get-in % [:data :source])) (of-type run :person/relations-changed))]
    (try
      (is (= {:person "lady-ashby" :source {:job job} :keep 4
              :entries [{:toward "dawson" :feeling "pleased" :because "Dawson dressed your hair"}]}
             (:data added)))
      (is (= :gm (:visible-to added)))
      (is (< (:id done) (:id added)) "after the public result")
      (is (not (str/includes? (pr-str done) "dressed your hair")) "which holds none")
      (is (= [{:feeling "pleased" :because "Dawson dressed your hair" :round 3 :source {:job job}}]
             (get (relations-of run "lady-ashby") "dawson")))
      (is (= (e/world run) (w/replay (store/read-events (:dir run)))))
      (finally (e/stop-run run)))))

(defn- judge [world things args]
  (item-judge/judgement {:tool-calls [{:id "j" :name "judge" :arguments (json/write-value-as-string (assoc args :summary "x"))}]}
                        world things (:inner dressing/scenario) nil config))

(deftest the-judges-answers-are-checked
  (let [run (start)]
    (try
      (e/emit! run [{:type :agent/placed :data {:id "mrs-pell" :to "bedroom"}}
                    {:type :item/work-begun :visible-to :gm
                     :data {:id "job-x" :actor "dawson" :items [] :people ["lady-ashby"] :doing "seeing to things"
                            :place "bedroom" :started 0 :due 1}}])
      (let [world (e/world run)
            things (item-work/judged-things world (items/job world "job-x"))
            answer #(judge world things {:relations %})
            entry (fn [person toward] {:person person :toward toward :feeling "cross" :because "x"})]
        (is (= {:keep 4 :entries [(entry "lady-ashby" "dawson")]}
               (:relations (:verdict (answer [(entry "lady-ashby" "dawson")])))))
        (is (not (contains? (:verdict (answer [])) :relations)) "none is a normal answer")
        (testing "only the judged people, either way"
          (is (= "relations may name only these people: dawson, lady-ashby; not mrs-pell"
                 (:error (answer [(entry "mrs-pell" "dawson")]))))
          (is (str/ends-with? (:error (answer [(entry "dawson" "mrs-pell")])) "not mrs-pell")))
        (testing "toward someone else, at most once a pair, and not from the dead"
          (is (str/includes? (:error (answer [(entry "dawson" "dawson")])) "toward themselves"))
          (is (str/includes? (:error (answer [(entry "dawson" "lady-ashby") (entry "dawson" "lady-ashby")])) "at most one"))
          (is (= "dawson is dead and feels nothing now"
                 (:error (relations/checked-entries config (assoc-in world [:agents "dawson" :status] :dead)
                                                    ["dawson" "lady-ashby"] [(entry "dawson" "lady-ashby")])))))
        (testing "lengths"
          (is (some? (:error (answer [(assoc (entry "dawson" "lady-ashby") :feeling (apply str (repeat 41 "x")))]))))
          (is (some? (:error (answer [(assoc (entry "dawson" "lady-ashby") :because (apply str (repeat 161 "x")))]))))))
      (finally (e/stop-run run)))))

(deftest the-conversation-judge-sees-and-adds-them
  (let [run (rounds! (start) 5)
        [ended] (of-type run :conversation/ended)
        id (get-in ended [:data :conversation])
        added (filter #(= {:conversation id} (get-in % [:data :source])) (of-type run :person/relations-changed))
        [call] (filter #(= "conversation-judge" (:agent %)) (store/read-llm (:dir run)))
        members (into {} (map (juxt :id identity)) (:members (scene-json call)))]
    (try
      (testing "the request: the members' feelings toward each other"
        (is (= {:lady-ashby [(assoc dawsons-start :at "18:00")]} (:feelings_toward (members "dawson"))))
        (is (= {:dawson [{:at "19:00" :feeling "pleased" :because "Dawson dressed your hair"}]}
               (:feelings_toward (members "lady-ashby"))))
        (is (str/includes? (get-in call [:messages 0 :content]) "## Relations")))
      (testing "one GM-only event a member, after the inner-state changes"
        (is (= ["dawson" "lady-ashby"] (map #(get-in % [:data :person]) added)))
        (is (every? #(= :gm (:visible-to %)) added))
        (is (every? (fn [ev] (every? #(< (:id %) (:id ev)) (of-type run :person/inner-changed))) added)))
      (is (= [{:feeling "attentive" :because "Lady Ashby spoke to you"}]
             (map #(select-keys % [:feeling :because]) (rest (get (relations-of run "dawson") "lady-ashby")))))
      (is (= ["pleased" "attentive"] (map :feeling (get (relations-of run "lady-ashby") "dawson"))))
      (testing "and without relations, the conversation judge is asked as before"
        (let [world (e/world run)
              conv {:id id :initiator "lady-ashby" :members ["lady-ashby" "dawson"]}
              plain (conversation-judge/judge-request (:inner dressing/scenario) world conv [])]
          (is (not (contains? (properties plain) :relations)))
          (is (not-any? :feelings_toward (:members (scene-json plain))))))
      (finally (e/stop-run run)))))

(deftest a-day-keeps-them-private-and-the-steward-leaves-them
  (let [run (rounds! (start) 20)
        world (e/world run)
        held (into {} (for [a (w/agents-in-order world)]
                        [(:id a) (set (mapcat #(map :because (val %)) (:relations a)))]))
        calls (remove #(#{"gm" "judge" "steward" "conversation-judge"} (:agent %)) (store/read-llm (:dir run)))
        text (fn [call] (str/join "\n" (map :content (:messages call))))]
    (try
      (is (seq (filter #(= "steward" (:agent %)) (store/read-llm (:dir run)))) "the steward ran")
      (testing "the steward added none"
        (is (= #{:start :job :conversation}
               (set (mapcat #(keys (get-in % [:data :source])) (of-type run :person/relations-changed))))))
      (testing "a person's own requests hold theirs"
        (is (some #(str/includes? (text %) "Dawson dressed your hair") (filter #(= "lady-ashby" (:agent %)) calls))))
      (testing "nobody else's requests hold any reason anyone was given"
        (doseq [call calls
                [id becauses] held
                :when (not= id (:agent call))
                because becauses]
          (is (not (str/includes? (text call) because)) (str (:agent call) " saw " id "'s " (pr-str because)))))
      (testing "the exported map keeps them, without rounds or sources, and loads again"
        (let [agents (into {} (map (juxt :name identity)) (:agents (wf/load-world (str (io/file (:dir run) "map.edn")))))]
          (is (= {"lady-ashby" [dawsons-start {:feeling "attentive" :because "Lady Ashby spoke to you"}]}
                 (:relations (agents "Dawson"))))
          (is (= {"dawson" [{:feeling "pleased" :because "Dawson dressed your hair"}
                            {:feeling "attentive" :because "Dawson spoke to you"}]}
                 (:relations (agents "Lady Ashby"))))))
      (is (= world (w/replay (store/read-events (:dir run)))))
      (finally (e/stop-run run)))))
