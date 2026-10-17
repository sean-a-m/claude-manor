(ns manor.items-test
  "Items (manor.items) in the hamlet test world: world files, take, put-down
  and give, look and free actions' replies, views, privacy, replay and the
  exported map."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [manor.actions :as a]
            [manor.builtins :as b]
            [manor.engine :as e]
            [manor.items :as items]
            [manor.llm :as llm]
            [manor.narrate :as n]
            [manor.prompt :as prompt]
            [manor.scenarios.hamlet :as hamlet]
            [manor.space :as space]
            [manor.store :as store]
            [manor.world :as w]
            [manor.worldfile :as wf]))

;; All runs here use {:test? true}, so they live under test-runs/.
(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

(def hamlet-file "test/fixtures/hamlet.edn")

(defn- start [& [opts]]
  (e/start-run :hamlet (merge {:llm :mock :test? true :run-id (str (gensym "items-"))} opts)))

(defn- rounds! [run n]
  (e/auto! run n)
  (is (= :idle (e/wait-idle run 60000)))
  run)

(defn- action [run n] (first (filter #(= n (:name %)) (:actions run))))

(defn- perform
  "Check and apply an action purely. Returns [world events] or a rejection reason."
  [run world actor n args]
  (let [act (action run n)]
    (or (a/check act world actor args)
        (let [evs (a/effect-events act world actor args)]
          [(reduce w/apply-event world evs) evs]))))

(defn- at [world actor location]
  (w/apply-event world {:type :agent/placed :data {:id actor :to location}}))

(defn- item-event? [e] (= "item" (namespace (:type e))))

;; ---------------------------------------------------------------------------
;; World files

(defn- are-problems [problems-of good]
  (doseq [[change expected]
          [[#(update % :items conj (assoc (first (:items %)) :place "porch")) "item \"clock\" is defined 2 times"]
           [#(assoc-in % [:items 0 :holder] "Ada") "item \"clock\": give exactly one of :place, :holder and :in"]
           [#(update-in % [:items 0] dissoc :place) "item \"clock\": give exactly one of :place, :holder and :in"]
           [#(assoc-in % [:items 0 :place] "attic") "item \"clock\": unknown location \"attic\""]
           [#(assoc-in % [:items 2 :holder] "Bob") "item \"pipe\": unknown holder \"Bob\""]
           [#(assoc-in % [:items 0 :condition] (apply str (repeat 81 "x"))) "item \"clock\": {:condition [\"should be at most 80 characters\"]}"]
           [#(assoc-in % [:items 0 :desc] (apply str (repeat 401 "x"))) "item \"clock\": {:desc [\"should be at most 400 characters\"]}"]
           [#(assoc-in % [:items 0 :condition] "") "item \"clock\": {:condition [\"should be at least 1 character\"]}"]
           [#(assoc-in % [:items 0 :id] "Grand Clock") "item \"Grand Clock\": {:id"]
           [#(assoc-in % [:items 0 :colour] "brown") "item \"clock\": {:colour [\"disallowed key\"]}"]
           [#(assoc % :items [1]) "malformed"]]]
    (is (str/includes? (problems-of (change good)) expected) expected)))

(deftest world-file-items
  (let [good (wf/read-file hamlet-file)
        problems-of #(str/join "\n" (wf/problems %))]
    (testing "holders and agent owners resolve to ids; other owners are kept"
      (let [by-id (into {} (map (juxt :id identity)) (:items (wf/load-world hamlet-file)))]
        (is (= "ben" (:holder (by-id "pipe"))))
        (is (= "ben" (:owner (by-id "pipe"))))
        (is (= "ada" (:owner (by-id "clock"))))
        (is (= "the shop" (:owner (by-id "basket"))))))
    (testing "each check gives a readable error naming the item"
      (is (empty? (wf/problems good)))
      (are-problems problems-of good))
    (testing "load-world throws with the list"
      (let [f (io/file store/test-runs-root (str (gensym "bad-items-") ".edn"))]
        (store/write-text! store/test-runs-root (.getName f)
                           (wf/->edn-string (assoc-in good [:items 0 :place] "attic")))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"item \"clock\": unknown location \"attic\""
                              (wf/load-world (str f))))))))

;; ---------------------------------------------------------------------------
;; The engine

(deftest items-in-the-world
  (let [run (start)
        world (e/world run)
        added (filter #(= :item/added (:type %)) (e/events run))]
    (testing "the starting items are added after the agents, visible to the GM only"
      (is (= 4 (count added)))
      (is (every? #(= :gm (:visible-to %)) added))
      (is (< (apply max (map :id (filter #(= :agent/spawned (:type %)) (e/events run))))
             (apply min (map :id added)))))
    (testing "accessors"
      (is (= "Grandfather clock" (:name (items/item world "clock"))))
      (is (= ["clock" "umbrella"] (map :id (items/items-at world "hall"))))
      (is (= ["pipe"] (map :id (items/held-by world "ben"))))
      (is (= ["clock"] (map :id (items/items-where world :fixed?)))))
    (testing "the GM sees every item"
      (is (= (items/all world) (:items (prompt/default-gm-view world)))))
    (testing "the item actions are offered, and only with :items"
      (is (every? #(action run %) [:take :put-down :give]))
      (is (not-any? #(#{:take :put-down :give} (:name %)) (e/all-actions (dissoc hamlet/scenario :items))))
      (is (not-any? #(#{:take :put-down :give} (:name %)) (e/all-actions (e/load-scenario :village)))))
    (e/stop-run run)))

(deftest new-items-get-fresh-ids
  (let [run (start)
        world (e/world run)
        evs (items/added-events world (repeat 2 {:name "Umbrella" :desc "Another umbrella." :condition "new"
                                                 :holder "ada" :owner "household"})
                                #{"ada"})]
    (is (= ["umbrella-2" "umbrella-3"] (map #(get-in % [:data :item :id]) evs)))
    (is (= "Ada now has an umbrella." (n/describe (first evs) (n/names world))))
    (testing "invalid fields are refused"
      (is (thrown? clojure.lang.ExceptionInfo
                   (items/added world {:name "Umbrella" :desc "x" :condition "new" :place "attic"} :gm))))
    (e/stop-run run)))

(deftest take-put-down-and-give
  (let [run (start)
        world (assoc (e/world run) :round 1)          ; Ada and Ben in the hall
        check (fn [world actor n args] (a/check (action run n) world actor args))]
    (testing "all three are free"
      (is (every? #(= :free (a/kind (action run %))) [:take :put-down :give])))
    (testing "take: reasons say what's wrong and what would work"
      (is (= "the Grandfather clock is fixed in place" (check world "ada" :take {:item "clock"})))
      (is (= "basket is not here; items here: clock (Grandfather clock), umbrella (Umbrella)"
             (check world "ada" :take {:item "basket"})))
      (is (= "teapot is not here; items here: clock (Grandfather clock), umbrella (Umbrella)"
             (check world "ada" :take {:item "teapot"})))
      (is (= "Ben is holding the Pipe" (check world "ada" :take {:item "pipe"})))
      (is (= "you are already carrying the Pipe" (check world "ben" :take {:item "pipe"})))
      (is (= "pipe is not here; items here: none" (check (at world "ada" "porch") "ada" :take {:item "pipe"}))))
    (testing "taking something you don't own is allowed, and everyone here sees it"
      (let [[taken [e]] (perform run world "ben" :take {:item "umbrella"})]
        (is (= "ben" (:holder (items/item taken "umbrella"))))
        (is (nil? (:place (items/item taken "umbrella"))))
        (is (= {:item "umbrella" :from-place "hall"} (:data e)))
        (is (= #{"ada" "ben"} (:visible-to e)))
        (is (= "Ben picks up the umbrella." (n/describe e (n/names taken))))))
    (testing "put-down: only what you carry, where you are"
      (is (= "you are not carrying umbrella; you carry: nothing" (check world "ada" :put-down {:item "umbrella"})))
      (is (= "you are not carrying basket; you carry: pipe (Pipe)" (check world "ben" :put-down {:item "basket"})))
      (let [[down [e]] (perform run (at world "ben" "porch") "ben" :put-down {:item "pipe"})]
        (is (= "porch" (:place (items/item down "pipe"))))
        (is (nil? (:holder (items/item down "pipe"))))
        (is (= #{"ben"} (:visible-to e)))
        (is (= "Ben puts down the pipe." (n/describe e (n/names down))))))
    (testing "give: you carry it, and they're someone else here"
      (is (= "ada is not here" (check (at world "ada" "porch") "ben" :give {:item "pipe" :to "ada"})))
      (is (= "no one has the id bob" (check world "ben" :give {:item "pipe" :to "bob"})))
      (is (= "you already have it; give it to someone else here" (check world "ben" :give {:item "pipe" :to "ben"})))
      (is (str/starts-with? (check world "ada" :give {:item "pipe" :to "ben"}) "you are not carrying pipe"))
      (let [[given [e]] (perform run world "ben" :give {:item "pipe" :to "ada"})]
        (is (= "ada" (:holder (items/item given "pipe"))))
        (is (= "ben" (:owner (items/item given "pipe"))) "ownership is recorded, not changed")
        (is (= #{"ada" "ben"} (:visible-to e)))
        (is (= "Ben gives the pipe to Ada." (n/describe e (n/names given))))))
    (testing "a carried item goes with its holder"
      (let [[moved] (perform run world "ben" :move {:to "porch"})]
        (is (= ["pipe"] (map :id (items/held-by moved "ben"))))
        (is (= "Ben is holding the Pipe" (check (at moved "ada" "porch") "ada" :take {:item "pipe"})))
        (is (str/starts-with? (check moved "ada" :take {:item "pipe"}) "pipe is not here"))))
    (e/stop-run run)))

(deftest item-views
  (let [run (start)
        world (assoc (e/world run) :round 1)
        view #(prompt/agent-view (:scenario run) %1 %2)]
    (testing "items here by name only; what you carry with its condition; what people here carry"
      (let [ada (view world "ada")
            ben (view world "ben")]
        (is (= [{:id "clock" :name "Grandfather clock"} {:id "umbrella" :name "Umbrella"}]
               (get-in ada [:location :here :items])))
        (is (= [{:id "pipe" :name "Pipe" :condition "still warm"}] (get-in ben [:you :carrying])))
        (is (not (contains? (:you ada) :carrying)))
        (is (= [{:id "ben" :name "Ben" :role :resident :carrying ["Pipe"]}] (get-in ada [:location :people-here])))
        (is (= [{:id "ada" :name "Ada" :role :resident}] (get-in ben [:location :people-here])))))
    (testing "nothing from other rooms, and no :items where there are none"
      (let [porch (view (at world "ada" "porch") "ada")
            shop (view (at world "ada" "shop") "ada")]
        (is (not (contains? (get-in porch [:location :here]) :items)))
        (is (empty? (get-in porch [:location :people-here])))
        (is (= [{:id "basket" :name "Basket"}] (get-in shop [:location :here :items])))))
    (testing "worlds without items have no item parts"
      (let [village (e/start-run :village {:llm :mock :offline? true :test? true :run-id (str (gensym "items-village-"))})
            v (prompt/agent-view (:scenario village) (e/world village) "ana")]
        (is (not (items/enabled? (e/world village))))
        (is (not (str/includes? (pr-str v) "carrying")))
        (is (not (contains? (prompt/default-gm-view (e/world village)) :items)))
        (e/stop-run village)))
    (e/stop-run run)))

;; ---------------------------------------------------------------------------
;; look

(deftest look-around
  (let [run (start)
        world (assoc (e/world run) :round 1)          ; Ada and Ben in the hall
        look (action run :look)]
    (testing "free, for everyone but the GM, with no arguments and no effect"
      (is (= :free (a/kind look)))
      (is (not (a/allowed? look :gm)))
      (is (= {} (:args (a/decode-args look "{\"at\": \"clock\"}"))))
      (is (empty? (a/effect-events look world "ada" {}))))
    (testing "the place with its detail, the items here in detail, owners by name, and what people carry"
      (is (= {:place {:name "Hall" :detail "Coats on pegs, a worn rug, and a smell of pipe smoke."}
              :items [{:id "clock" :name "Grandfather clock" :desc "A tall oak clock that strikes the hours."
                       :condition "ticking loudly" :owner "Ada" :fixed? true}
                      {:id "umbrella" :name "Umbrella" :desc "A black umbrella with a horn handle."
                       :condition "dry" :owner "Ada"}]
              :people [{:name "Ben" :carrying [{:id "pipe" :name "Pipe" :desc "A briar pipe, much chewed."
                                                :condition "still warm"}]}]}
             (items/look world "ada"))))
    (testing "people who carry nothing, and what busy people are doing"
      (let [busy (w/apply-event world (b/occupy world "ada" "reading" 4))]
        (is (= [{:name "Ada" :doing "reading"}] (:people (items/look busy "ben"))))))
    (testing "empty parts are left out; other owners are shown as they are"
      (is (= {:place {:name "Porch"}} (items/look (at world "ada" "porch") "ada")))
      (is (= ["the shop"] (map :owner (:items (items/look (at world "ada" "shop") "ada"))))))
    (testing "what someone carries isn't among the items here"
      (let [[taken] (perform run world "ben" :take {:item "umbrella"})]
        (is (= ["clock"] (map :id (:items (items/look taken "ada")))))
        (is (= ["pipe" "umbrella"] (map :id (:carrying (first (:people (items/look taken "ada")))))))))
    (testing "not while travelling"
      (let [[travelling] (perform run (at world "ada" "green") "ada" :move {:to "farm"})]
        (is (space/in-transit? travelling "ada"))
        (is (= "you can't look around while travelling" (a/check look travelling "ada" {})))))
    (testing "the detail is only for look, never in the view"
      (is (= {:id "hall" :name "Hall" :desc "A small hall."}
             (dissoc (get-in (prompt/agent-view (:scenario run) world "ada") [:location :here]) :items))))
    (e/stop-run run)))

(defn- looker-policy
  "Ben looks around, checks the time and takes the umbrella, then passes;
  Ada goes to bed (the hamlet policy)."
  [{:keys [world agent-id step] :as ctx}]
  (if (not= agent-id "ben")
    (hamlet/mock-policy ctx)
    (get-in {1 [{:tool :look} {:tool :check-time} {:tool :take :args {:item "umbrella"}} {:tool :pass}]}
            [(:round world) step]
            {:tool :pass})))

(deftest free-action-replies
  (let [run (rounds! (start {:llm-impl (llm/mock looker-policy)}) 1)
        replies (into {} (for [entry (store/read-llm (:dir run))
                               :when (= "ben" (:agent entry))
                               r (:replies entry)
                               :when (= "tool" (:role r))]
                           [(:tool_call_id r) (:content r)]))]
    (testing "look's reply is the tool result, as JSON"
      (let [seen (json/read-value (replies "mock-0-look") json/keyword-keys-object-mapper)]
        (is (= {:name "Hall" :detail "Coats on pegs, a worn rug, and a smell of pipe smoke."} (:place seen)))
        (is (= ["clock" "umbrella"] (map :id (:items seen))))))
    (testing "a string :reply is sent as it is"
      (is (= "It is Saturday 15 October 1904, 21:00." (replies "mock-1-check-time"))))
    (testing "free actions without :reply still say ok"
      (is (= "ok" (replies "mock-2-take"))))
    (testing "looking records no event"
      (is (= [:item/taken :agent/passed]
             (map :type (filter #(and (= "ben" (:actor %)) (not= :llm/usage (:type %))) (e/events run)))))
      (is (empty? (filter #(= :action/forfeited (:type %)) (e/events run)))))
    (e/stop-run run)))

;; ---------------------------------------------------------------------------
;; A short run: Ben takes the umbrella out to the porch while Ada goes to bed

(defn- ben-policy
  "Ada goes to bed (the hamlet policy); Ben takes the umbrella to the porch,
  puts it down, and passes."
  [{:keys [world agent-id step] :as ctx}]
  (if (not= agent-id "ben")
    (hamlet/mock-policy ctx)
    (get-in {1 [{:tool :take :args {:item "umbrella"}} {:tool :move :args {:to "porch"}}]
             2 [{:tool :put-down :args {:item "umbrella"}} {:tool :pass}]}
            [(:round world) step]
            {:tool :pass})))

(deftest a-run-with-items
  (let [run (rounds! (start {:llm-impl (llm/mock ben-policy)}) 3)
        world (e/world run)
        evs (e/events run)]
    (testing "Ben carried the umbrella to the porch"
      (is (= "porch" (:place (items/item world "umbrella"))))
      (is (= [:item/taken :item/put-down] (map :type (filter #(and (item-event? %) (= "ben" (:actor %))) evs))))
      (is (empty? (filter #(= :action/forfeited (:type %)) evs))))
    (testing "replay gives the same world"
      (is (= world (w/replay (store/read-events (:dir run))))))
    (testing "privacy: Ada, upstairs, sees no item events; the starting items reach no agent"
      (is (empty? (filter item-event? (prompt/visible-events evs "ada" 1000))))
      (is (= [:item/taken :item/put-down] (map :type (filter item-event? (prompt/visible-events evs "ben" 1000)))))
      (is (not (str/includes? (pr-str (prompt/agent-messages (:scenario run) world evs "ada" {:event-window 1000}))
                              "umbrella"))))
    (testing "the exported map has the live items, holders as names, and starts a run with the same items"
      (let [m (wf/read-file (str (io/file (:dir run) "map.edn")))
            by-id (into {} (map (juxt :id identity)) (:items m))]
        (is (= "porch" (:place (by-id "umbrella"))))
        (is (= "Ben" (:holder (by-id "pipe"))))
        (is (= "Ada" (:owner (by-id "clock"))))
        (is (= (map :id (:items (wf/read-file hamlet-file))) (map :id (:items m))))
        (let [r2 (start {:world-file (str (io/file (:dir run) "map.edn"))})]
          (is (= (items/all world) (items/all (e/world r2))))
          (e/stop-run r2))))
    (e/stop-run run)))

;; ---------------------------------------------------------------------------
;; Work on items and the judge

(defn- scripted
  "A mock policy: `script` maps agent id -> round -> the calls of that turn,
  in order. Other turns carry on if interrupted, else follow the hamlet
  policy (Ada goes to bed, Ben passes). Judge calls go to `judge` (default
  items/mock-judge) and each judge ctx is added to the atom `judged`."
  [script & {:keys [judge judged]}]
  (fn [{:keys [world agent-id role step] :as ctx}]
    (if (= :judge role)
      (do (some-> judged (swap! conj ctx))
          ((or judge items/mock-judge) ctx))
      (or (get-in script [agent-id (:round world) step])
          (when (:interrupted-by (w/busy world agent-id)) {:tool :carry-on})
          (hamlet/mock-policy ctx)))))

(defn- work [items doing hours]
  {:tool :work-on :args (cond-> {:items items :doing doing} hours (assoc :hours hours))})

(defn- of-type [run t] (filter #(= t (:type %)) (e/events run)))

(defn- judge-calls [run] (filter #(= "judge" (:agent %)) (store/read-llm (:dir run))))

(defn- condition [run id] (:condition (items/item (e/world run) id)))

(deftest work-on-checks
  (let [run (start)
        world (assoc (e/world run) :round 1)          ; Ada and Ben in the hall, 21:00
        work-on (action run :work-on)
        check (fn [world actor args] (a/check work-on world actor args))]
    (testing "turn-ending, for everyone but the GM, and offered only in worlds with a clock"
      (is (= :final (a/kind work-on)))
      (is (not (a/allowed? work-on :gm)))
      (is (= [work-on] (a/available [work-on] world "ada")))
      (is (empty? (a/available [work-on] (dissoc world :clock) "ada")))
      (is (not-any? #(= :work-on (:name %)) (e/all-actions (e/load-scenario :village)))))
    (testing "the arguments: 1-6 items, doing 3-120 characters, hours 0.5-4"
      (is (str/includes? (:error (a/decode-args work-on {:items [] :doing "dusting"})) "items has 0 items"))
      (is (str/includes? (:error (a/decode-args work-on {:items (repeat 7 "clock") :doing "dusting"})) "items has 7 items"))
      (is (:error (a/decode-args work-on {:items ["clock"] :doing "do"})))
      (is (:error (a/decode-args work-on {:items ["clock"] :doing "dusting" :hours 5})))
      (is (= {:items ["clock"] :doing "dusting" :hours 1.0}
             (:args (a/decode-args work-on "{\"items\": [\"clock\"], \"doing\": \"dusting\", \"hours\": 1}")))))
    (testing "items lying here (fixed ones too) or carried by you; reasons name what would work"
      (is (nil? (check world "ada" {:items ["clock" "umbrella"] :doing "dusting"})))
      (is (nil? (check world "ben" {:items ["pipe" "clock"] :doing "cleaning"})))
      (is (= "Ben is holding the Pipe" (check world "ada" {:items ["clock" "pipe"] :doing "cleaning"})))
      (is (= "basket is neither here nor carried by you; items here: clock (Grandfather clock), umbrella (Umbrella); you carry: nothing"
             (check world "ada" {:items ["basket"] :doing "mending"})))
      (is (str/starts-with? (check world "ada" {:items ["teapot"] :doing "mending"}) "teapot is neither here nor carried by you"))
      (is (= "there is no game time to work in" (check (dissoc world :clock) "ada" {:items ["clock"] :doing "dusting"})))
      (let [[travelling] (perform run (at world "ada" "green") "ada" :move {:to "farm"})]
        (is (= "you can't work on things while travelling" (check travelling "ada" {:items ["clock"] :doing "dusting"})))))
    (testing "the job (seen by the GM) and the occupation that carries its id (seen by the room)"
      (let [[after [begun occupied]] (perform run world "ben" :work-on {:items ["clock" "pipe" "clock"] :doing "polishing the clock" :hours 1.5})
            id (str "job-" (inc (:seq world)))]
        (is (= {:id id :actor "ben" :items ["clock" "pipe"] :doing "polishing the clock" :place "hall" :started 1 :due 4}
               (:data begun) (items/job after id)))
        (is (= :gm (:visible-to begun)))
        (is (= #{"ada" "ben"} (:visible-to occupied)))
        (is (= {:activity "polishing the clock" :until 4 :job id} (w/busy after "ben")))
        (is (= "Ben is polishing the clock until 22:30." (n/describe occupied (n/names after))))
        (is (= [(items/job after id)] (:item-jobs (prompt/default-gm-view after))))
        (is (not (contains? (prompt/default-gm-view world) :item-jobs)))
        (testing "nothing changes while the work goes on"
          (is (= (items/all world) (items/all after))))))
    (testing "half an hour by default"
      (let [[_ [begun]] (perform run world "ada" :work-on {:items ["umbrella"] :doing "drying the umbrella"})]
        (is (= 2 (get-in begun [:data :due])))))
    (e/stop-run run)))

(deftest work-runs-to-its-end
  (let [judged (atom [])
        policy (scripted {"ben" {1 [(work ["clock" "pipe"] "polishing the clock" 1.5)]}} :judged judged)
        ;; the mock costs nothing; give judge calls a price to see them counted
        priced (let [inner (llm/mock policy)]
                 (reify llm/LLM
                   (complete [_ req]
                     (cond-> (llm/complete inner req)
                       (= :judge (get-in req [:ctx :role])) (assoc-in [:usage :cost] 0.01)))))
        run (rounds! (start {:llm-impl priced}) 3)]
    (testing "the condition is unchanged at round due - 1"
      (is (= 3 (:round (e/world run))))
      (is (= "ticking loudly" (condition run "clock")))
      (is (empty? @judged)))
    (rounds! run 1)
    (let [world (e/world run)
          [done] (of-type run :item/work-done)]
      (testing "and changed at due, when Ben is freed and acts again"
        (is (= "just seen to" (condition run "clock") (condition run "pipe")))
        (is (= 4 (:round done)))
        (is (= 4 (:round (last (filter #(= "ben" (:actor %)) (of-type run :agent/freed))))))
        (is (contains? (set (map :round (filter #(= "ben" (:actor %)) (of-type run :llm/usage)))) 4))
        (is (empty? (items/jobs world))))
      (testing "the judge was told the whole time, the luck, and the items in reach"
        (let [[ctx] @judged]
          (is (= 1 (count @judged)))
          (is (= {:minutes 90 :stopped-early? false} (select-keys ctx [:minutes :stopped-early?])))
          (is (<= 1 (:luck ctx) 100))
          (is (= ["clock" "pipe"] (map :id (:items ctx))))))
      (testing "the result: data, narration and who sees it (Ada is upstairs)"
        (is (= {:actor "ben" :place "hall" :doing "polishing the clock" :minutes 90 :stopped-early? false
                :summary "Ben finishes polishing the clock."
                :changes [{:item "clock" :from "ticking loudly" :to "just seen to"}
                          {:item "pipe" :from "still warm" :to "just seen to"}]}
               (dissoc (:data done) :job :luck)))
        (is (= #{"ben"} (:visible-to done)))
        (is (= "Ben finishes polishing the clock. (Grandfather clock: just seen to; Pipe: just seen to)."
               (n/describe done (n/names world))))
        (is (empty? (filter item-event? (prompt/visible-events (e/events run) "ada" 1000)))))
      (testing "the judge's usage counts towards the cost, and its call is in llm.edn"
        (is (= {:calls 1 :cost 0.01} (select-keys (get-in world [:usage "judge"]) [:calls :cost])))
        (is (= 0.01 (w/total-cost world)))
        (let [[call] (judge-calls run)]
          (is (= 4 (:round call)))
          (is (= ["judge"] (:tools call)))
          (is (= [{:role "tool" :tool_call_id "mock-0-judge" :content "ok"}] (:replies call)))))
      (testing "the transcript lists the judge like any other agent"
        (let [text (manor.transcript/render (:dir run))]
          (is (str/includes? text "| judge | 1 | $0.0100 |"))
          (is (str/includes? text "- **judge** judge {"))
          (is (str/includes? text "Ben finishes polishing the clock. (Grandfather clock: just seen to; Pipe: just seen to)."))))
      (testing "replay gives the same world"
        (is (= world (w/replay (store/read-events (:dir run)))))))
    (e/stop-run run)))

(deftest the-judge-request
  (let [run (start)
        world (assoc (e/world run) :round 4)
        job {:id "job-9" :actor "ben" :items ["clock" "pipe"] :doing "polishing the clock" :place "hall" :started 1 :due 4}
        {:keys [messages tools]} (items/judge-request (assoc (:scenario run) :items {:judge-brief (constantly "A small house.")})
                                                      world job {:minutes 60 :stopped-early? true :luck 7}
                                                      (items/in-reach world job))
        [system user] messages]
    (is (= ["judge"] (map #(get-in % [:function :name]) tools)))
    (is (str/includes? (:content system) "work can't turn a vase into gold"))
    (is (str/ends-with? (:content system) "## The setting\nA small house."))
    (is (str/includes? (:content user) (str "- Time: Saturday 15 October 1904, 22:30\n"
                                            "- Worker: Ben. A night owl who hates going to bed.\n"
                                            "- Place: Hall\n"
                                            "- Doing: polishing the clock\n"
                                            "- Time worked: 60 minutes; they stopped early\n"
                                            "- Luck: 7\n")))
    (is (str/includes? (:content user) "\"desc\" : \"A briar pipe, much chewed.\""))
    (testing "only the job and its items: nothing about other items or people"
      (is (not (str/includes? (pr-str messages) "Umbrella")))
      (is (not (str/includes? (pr-str messages) "Ada"))))
    (testing "without a :judge-brief, no setting"
      (is (not (str/includes? (:content (first (:messages (items/judge-request (:scenario run) world job {:minutes 30} []))))
                              "## The setting"))))
    (e/stop-run run)))

(defn- judge-says [args]
  {:tool-calls [{:id "j1" :name "judge" :arguments (json/write-value-as-string args)}]})

(deftest checking-the-judge
  (let [run (start)
        world (e/world run)
        reach (map #(items/item world %) ["clock" "pipe"])
        judge #(items/judgement (judge-says %) reach)]
    (testing "a good answer: changes with their old condition; unchanged ones left out"
      (is (= {:verdict {:summary "It shines."
                        :changes [{:item "clock" :from "ticking loudly" :to "polished"}]}
              :replies [{:role "tool" :tool_call_id "j1" :content "ok"}]}
             (judge {:changes [{:item "clock" :condition "polished"} {:item "pipe" :condition "still warm"}]
                     :summary "It shines."}))))
    (testing "descriptions, names and other keys are ignored: only conditions change"
      (is (= {:summary "Gilded." :changes [{:item "clock" :from "ticking loudly" :to "gilded"}]}
             (:verdict (judge {:changes [{:item "clock" :condition "gilded" :desc "A gold clock." :name "Gold clock"}]
                               :summary "Gilded." :desc "x"})))))
    (testing "no changes is a fine answer"
      (is (= {:summary "Nothing came of it." :changes []} (:verdict (judge {:summary "Nothing came of it."})))))
    (testing "refused: items out of reach, bad lengths, no summary, no call"
      (is (= "you may change only these items: clock, pipe; not umbrella"
             (:error (judge {:changes [{:item "umbrella" :condition "dry"}] :summary "x"}))))
      (is (str/includes? (:error (judge {:changes [{:item "clock" :condition (apply str (repeat 81 "x"))}] :summary "x"}))
                         "is 81 characters; the limit is 80"))
      (is (str/includes? (:error (judge {:changes [] :summary (apply str (repeat 301 "x"))})) "the limit is 300"))
      (is (:error (judge {:changes [{:item "clock" :condition ""}] :summary "x"})))
      (is (:error (judge {:changes []})))
      (is (= "error: you may change only these items: clock, pipe; not umbrella. Call judge again with a corrected answer."
             (:content (first (:replies (judge {:changes [{:item "umbrella" :condition "dry"}] :summary "x"}))))))
      (is (= {:error "no judge call" :replies [{:role "user" :content "You must answer by calling the judge tool."}]}
             (items/judgement {:tool-calls []} reach))))
    (e/stop-run run)))

(deftest bad-judge-answers
  (let [run-with (fn [judge]
                   (let [judged (atom [])
                         run (rounds! (start {:llm-impl (llm/mock (scripted {"ben" {1 [(work ["clock"] "winding the clock" nil)]}}
                                                                            :judge judge :judged judged))})
                                      2)]
                     [run judged]))]
    (testing "an invalid answer is sent back with the reason; a good second answer is used"
      (let [[run judged] (run-with (fn [{:keys [step]}]
                                     {:tool :judge :args (if (zero? step)
                                                           {:changes [{:item "umbrella" :condition "dry"}] :summary "x"}
                                                           {:changes [{:item "clock" :condition "wound"}] :summary "It ticks."})}))
            [first-call second-call] (judge-calls run)]
        (is (= 2 (count @judged)))
        (is (= "wound" (condition run "clock")))
        (is (str/starts-with? (:content (last (:messages second-call))) "error: you may change only these items: clock"))
        (is (= (:replies first-call) [(last (:messages second-call))]))
        (is (empty? (of-type run :item/judge-failed)))
        (e/stop-run run)))
    (testing "a second bad answer: no changes, a neutral summary, and a :system event with the reason"
      (let [[run judged] (run-with (constantly {:tool :judge :args {:changes [{:item "clock" :condition ""}] :summary "x"}}))
            [done] (of-type run :item/work-done)
            [failed] (of-type run :item/judge-failed)]
        (is (= 2 (count @judged) (count (judge-calls run))))
        (is (= "ticking loudly" (condition run "clock")))
        (is (= {:summary "Ben finishes winding the clock." :changes []} (select-keys (:data done) [:summary :changes])))
        (is (= :system (:visible-to failed)))
        (is (str/starts-with? (get-in failed [:data :reason]) "a second bad answer: invalid arguments"))
        (is (= "Ben finishes winding the clock." (n/describe done (n/names (e/world run)))))
        (is (empty? (filter #(= :run/pause-requested (:type %)) (e/events run))) "the run isn't paused")
        (e/stop-run run)))
    (testing "an exception: the same, with no retry"
      (let [[run] (run-with (fn [_] (throw (ex-info "provider down" {}))))
            [failed] (of-type run :item/judge-failed)]
        (is (= "the judge call failed: provider down" (get-in failed [:data :reason])))
        (is (= 1 (count (of-type run :item/work-done))))
        (is (= "ticking loudly" (condition run "clock")))
        (is (= (e/world run) (w/replay (store/read-events (:dir run)))))
        (e/stop-run run)))))

(deftest stopping-early-or-carrying-on
  (let [run-with (fn [ben-round-3]
                   (let [judged (atom [])
                         ;; Ada rouses Ben in round 2; in round 3 Ben decides
                         policy (scripted {"ada" {1 [{:tool :pass}]
                                                  2 [{:tool :rouse :args {:who "ben"}} {:tool :pass}]}
                                           "ben" {1 [(work ["clock"] "winding the clock" 2)]
                                                  3 ben-round-3}}
                                          :judged judged)]
                     [(start {:llm-impl (llm/mock policy)}) judged]))]
    (testing "stopping after the interruption: judged on the time really worked"
      (let [[run judged] (run-with [{:tool :say :args {:text "Coming!"}}])]
        (rounds! run 3)
        (is (= 1 (count (of-type run :item/work-begun))))
        (is (empty? @judged) "the job is judged as the next round starts")
        (rounds! run 1)
        (let [[done] (of-type run :item/work-done)]
          (is (= 4 (:round done)))
          (is (= {:minutes 60 :stopped-early? true} (select-keys (:data done) [:minutes :stopped-early?])))
          (is (= {:minutes 60 :stopped-early? true} (select-keys (first @judged) [:minutes :stopped-early?])))
          (is (= "Ben leaves off winding the clock. (Grandfather clock: just seen to)."
                 (n/describe done (n/names (e/world run))))))
        (is (= "just seen to" (condition run "clock")))
        (is (empty? (items/jobs (e/world run))))
        (e/stop-run run)))
    (testing "carrying on: the job runs to its end"
      (let [[run judged] (run-with [{:tool :carry-on}])]
        (rounds! run 4)
        (is (empty? @judged))
        (is (= "ticking loudly" (condition run "clock")))
        (rounds! run 1)
        (let [[done] (of-type run :item/work-done)]
          (is (= 5 (:round done)))
          (is (= {:minutes 120 :stopped-early? false} (select-keys (:data done) [:minutes :stopped-early?]))))
        (is (= 1 (count @judged)))
        (e/stop-run run)))))

(deftest a-retired-worker
  (let [judged (atom [])
        run (rounds! (start {:llm-impl (llm/mock (scripted {"ben" {1 [(work ["clock"] "winding the clock" 1)]}}
                                                           :judged judged))})
                     1)]
    (e/retire! run "ben" "called away")
    (rounds! run 3)
    (let [[ended] (of-type run :item/work-ended)]
      (is (= {:reason :retired :job (get-in (first (of-type run :item/work-begun)) [:data :id])}
             (select-keys (:data ended) [:reason :job])))
      (is (= :gm (:visible-to ended)))
      (is (= 2 (:round ended)))
      (is (empty? @judged))
      (is (empty? (judge-calls run)))
      (is (empty? (items/jobs (e/world run))))
      (is (= "ticking loudly" (condition run "clock"))))
    (e/stop-run run)))

(deftest things-taken-away-mid-job
  (testing "only the items still in reach are judged"
    (let [judged (atom [])
          run (rounds! (start {:llm-impl (llm/mock (scripted {"ada" {1 [{:tool :pass}]
                                                                     2 [{:tool :take :args {:item "umbrella"}}
                                                                        {:tool :move :args {:to "porch"}}]}
                                                              "ben" {1 [(work ["umbrella" "clock" "pipe"] "tidying up" 1.5)]}}
                                                             :judged judged))})
                       4)]
      (is (= ["clock" "pipe"] (map :id (:items (first @judged)))))
      (is (= "just seen to" (condition run "clock") (condition run "pipe")))
      (is (= "dry" (condition run "umbrella")))
      (e/stop-run run)))
  (testing "nothing left in reach: the work ends with no call"
    (let [judged (atom [])
          run (rounds! (start {:llm-impl (llm/mock (scripted {"ada" {1 [{:tool :pass}]
                                                                     2 [{:tool :take :args {:item "umbrella"}}
                                                                        {:tool :move :args {:to "porch"}}]}
                                                              "ben" {1 [(work ["umbrella"] "drying the umbrella" 1)]}}
                                                             :judged judged))})
                       3)
          [ended] (of-type run :item/work-ended)]
      (is (empty? @judged))
      (is (empty? (judge-calls run)))
      (is (= :nothing-in-reach (get-in ended [:data :reason])))
      (is (= #{"ben"} (:visible-to ended)) "Ada took it to the porch, so only Ben is in the hall")
      (is (= "Ben leaves off drying the umbrella before getting anywhere." (n/describe ended (n/names (e/world run)))))
      (is (= "dry" (condition run "umbrella")))
      (e/stop-run run))))

(deftest overlapping-jobs
  (let [judged (atom [])
        judge (fn [{:keys [job]}]
                {:tool :judge :args {:changes [{:item "clock" :condition (str "after " (:doing job))}]
                                     :summary "Done."}})
        run (rounds! (start {:llm-impl (llm/mock (scripted {"ada" {1 [(work ["clock"] "dusting the clock" 1)]}
                                                            "ben" {1 [(work ["clock"] "winding the clock" 1)]}}
                                                           :judge judge :judged judged))})
                     3)
        [first-job second-job] (map :job @judged)
        dones (of-type run :item/work-done)]
    (testing "both end in the same round, applied in order of id"
      (is (= 2 (count dones)))
      (is (= [3 3] (map :round dones)))
      (is (= (sort (map :id [first-job second-job])) [(:id first-job) (:id second-job)])))
    (testing "the second judge saw the first one's result, and the last applied wins"
      (is (= "ticking loudly" (:condition (first (:items (first @judged))))))
      (is (= (str "after " (:doing first-job)) (:condition (first (:items (second @judged))))))
      (is (= (str "after " (:doing second-job)) (condition run "clock"))))
    (testing "replay gives the same world"
      (is (= (e/world run) (w/replay (store/read-events (:dir run))))))
    (e/stop-run run)))
