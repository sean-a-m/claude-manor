(ns manor.items-test
  "Items (manor.items) in the hamlet test world: world files, take, put-down
  and give, views, privacy, replay and the exported map."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [manor.actions :as a]
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
           [#(assoc-in % [:items 0 :holder] "Ada") "item \"clock\": give exactly one of :place and :holder"]
           [#(update-in % [:items 0] dissoc :place) "item \"clock\": give exactly one of :place and :holder"]
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
