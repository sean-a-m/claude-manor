(ns manor.physical-test
  "Physical status (manor.mechanics.physical) in the burglary fixture world:
  starting statuses and their shape, views, each faculty's code effects,
  taking from the helpless, coming to, death, interrupts, the perception
  filter, hibernation and replay. Speech, hearing and sight effects on
  actions and views are tested with them, not here."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [manor.actions :as a]
            [manor.engine :as e]
            [manor.engine.turn :as turn]
            [manor.io.llm :as llm]
            [manor.io.store :as store]
            [manor.io.worldfile :as wf]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.conversation :as conversation]
            [manor.mechanics.items :as items]
            [manor.mechanics.items.judge :as item-judge]
            [manor.mechanics.items.steward :as item-steward]
            [manor.mechanics.items.views :as item-views]
            [manor.mechanics.physical :as physical]
            [manor.mechanics.physical.changes :as changes]
            [manor.mechanics.space :as space]
            [manor.prompt :as prompt]
            [manor.scenarios.burglary :as burglary]
            [manor.scenarios.claudeworth :as cw]
            [manor.world :as w]))

(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

(defn- scripted
  "A mock: `script` maps agent id -> round -> the calls of that turn, in
  order; other turns carry on if interrupted, else pass (the GM is done).
  The judge and steward are the default mocks."
  [script]
  (llm/mock (fn [{:keys [world agent-id role step] :as ctx}]
              (case role
                :judge (item-judge/mock-judge ctx)
                :steward (item-steward/mock-steward ctx)
                :gm {:tool :done}
                (or (get-in script [agent-id (:round world) step])
                    (when (:interrupted-by (w/busy world agent-id)) {:tool :carry-on})
                    {:tool :pass})))))

(defn- start [& [opts]]
  (e/start-run :burglary (merge {:llm :mock :offline? true :test? true :run-id (str (gensym "physical-"))} opts)))

(defn- rounds! [run n]
  (e/auto! run n)
  (is (= :idle (e/wait-idle run 60000)))
  run)

(defn- action [run n] (get (a/by-name (:actions run)) (name n)))

(defn- check [run world id n args] (a/check (action run n) world id args))

(defn- of-type [run t] (filter #(= t (:type %)) (e/events run)))

(defn- view [run world id] (prompt/agent-view (:scenario run) world id))

(defn- person-here [view id] (first (filter #(= id (:id %)) (get-in view [:location :people-here]))))

(defn- changed
  "`world` after `change` (see changes/change-events) to `id`."
  [world id change]
  (reduce w/apply-event world (changes/change-events world id change id)))

(defn- set-faculty [world id faculty level & [cause]]
  (changed world id {:physical [(cond-> {:faculty faculty :level level} cause (assoc :cause cause))]}))

(defn- replays? [run]
  (= (e/world run) (w/replay (store/read-events (:dir run)))))

;; ---------------------------------------------------------------------------
;; Data, world files and views

(deftest starting-statuses-are-checked
  (let [good (wf/read-file burglary/world-file)
        crane (fn [k v] (wf/problems (assoc-in good [:agents 0 k] v)))]
    (is (empty? (wf/problems good)))
    (is (empty? (cw/problems good)))
    (is (str/includes? (first (crane :physical {:sight {:level :impeded}})) "agent \"Crane\""))
    (is (seq (crane :physical {:smell {:level :disabled}})))
    (is (seq (crane :physical {:movement {:level :disabled :minutes 30}})) "only consciousness takes minutes")
    (is (seq (crane :impediments {:a "1" :b "2" :c "3" :d "4"})) "at most three impediments")
    (is (seq (crane :died {:cause "a fall"})) ":died needs :status :dead")
    (is (seq (wf/problems (assoc-in good [:agents 0 :status] :retired))))
    (testing ":init agents are checked too"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"bad :agents from :init: agent \"X\""
                            (#'e/check-agents! burglary/scenario [{:name "X" :physical {:grasp {:level :numb}}}]))))))

(deftest the-fixture-starts-mid-crime
  (let [run (start)]
    (try
      (let [world (e/world run)]
        (testing "statuses are set in the starting round, with coming to as a round"
          (is (= {:level :disabled :cause "bound hand and foot to a chair" :since 0}
                 (physical/entry world "crane" :movement)))
          (is (= 2 (:until (physical/entry world "mrs-bell" :consciousness))) "an hour is two rounds")
          (is (physical/unconscious? world "mrs-bell"))
          (is (= {:hand "a cut palm, from the pantry window"} (:impediments (w/agent world "slade")))))
        (testing "the dead footman is dead where he fell, still holding the keys"
          (is (w/dead? world "albert"))
          (is (= {:round 0 :cause "struck on the head with a candlestick"} (:died (w/agent world "albert"))))
          (is (not-any? #(= "albert" (:id %)) (w/active-agents world)))
          (is (= #{"albert"} (physical/dead-at world "dining-room")))
          (is (= "albert" (:holder (items/item world "pantry-keys")))))
        (is (replays? run)))
      (finally (e/stop-run run)))))

(deftest everyone-present-sees-statuses
  (let [run (start)]
    (try
      (let [world (e/world run)
            slade (view run world "slade")]
        (testing "the dead are among the people here, marked dead, with what they carry"
          (is (= {:id "albert" :name "Albert" :dead true :carrying ["Pantry keys"]}
                 (select-keys (person-here slade "albert") [:id :name :dead :carrying :doing]))))
        (is (= {:sight "disabled: blindfolded with a pillowcase"} (:physical (person-here slade "ivy"))))
        (testing "their own, under you"
          (is (= {:movement "disabled: bound hand and foot to a chair"
                  :grasp "disabled: wrists tied behind the chair"
                  :speech "impeded: gagged with a table napkin"}
                 (get-in (view run world "crane") [:you :physical])))
          (is (= [:movement :grasp :speech] (keys (get-in (view run world "crane") [:you :physical]))) "in faculty order")
          (is (= {:hand "a cut palm, from the pantry window"} (get-in slade [:you :impediments]))))
        (is (= "unconscious: struck on the head"
               (get-in (person-here (view run (w/apply-event world {:type :agent/placed :data {:id "will" :to "kitchen"}}) "will")
                                    "mrs-bell")
                       [:physical :consciousness])))
        (testing "look shows the same"
          (let [people (:people (item-views/look world "slade"))]
            (is (= #{"Albert" "Ivy"} (set (map :name people))))
            (is (:dead (first (filter #(= "Albert" (:name %)) people))))
            (is (= [{:id "pantry-keys" :name "Pantry keys" :desc "A ring of keys to the pantry and the plate chest."}]
                   (:carrying (first (filter :dead people)))))))
        (testing "the walk reply shows them too"
          (is (:dead (first (filter #(= "albert" (:id %)) (:people-here (cw/arrival world "slade"))))))))
      (finally (e/stop-run run)))))

;; ---------------------------------------------------------------------------
;; Movement

(deftest disabled-movement-keeps-them-where-they-are
  (let [run (start)
        world (e/world run)]
    (try
      (is (str/starts-with? (check run world "crane" :walk {:to "entrance-hall"})
                            "your movement is disabled (bound hand and foot to a chair), so you can't move"))
      (is (str/includes? (check run world "crane" :travel {:to "village"}) "your movement is disabled"))
      (is (str/includes? (check run world "mrs-bell" :walk {:to "entrance-hall"}) "you are unconscious (struck on the head)"))
      (is (nil? (check run world "will" :walk {:to "kitchen"})))
      (finally (e/stop-run run)))))

(deftest impeded-movement-allows-three-walks-and-no-travel
  (let [run (start)]
    (try
      @(e/afflict! run "will" {:faculty :movement :level :impeded :cause "a twisted ankle"})
      (let [by-name (a/by-name (:actions run))
            walk (fn [st to] (turn/handle-call run "will" by-name {:max-queries 2 :max-finals 1}
                                               (assoc st :replies []) {:id "w" :name "walk" :arguments {:to to}}))
            sts (reductions walk {:used [] :finals [] :queries 0} ["kitchen" "entrance-hall" "dining-room" "entrance-hall"])
            replies (map #(:content (first (:replies %))) (rest sts))]
        (is (every? #(not (str/starts-with? % "rejected")) (take 3 replies)))
        (is (= "rejected: you can use walk only 3 times per turn; take your turn-ending action now, and use it again next turn"
               (last replies)))
        (is (= "dining-room" (space/location-of (e/world run) "will")))
        (is (str/includes? (check run (e/world run) "will" :travel {:to "village"})
                           "your movement is impeded (a twisted ankle), so you can only go a few steps nearby, not travel")))
      (testing "the limit isn't in the tool's description, which doesn't depend on who reads it"
        (is (not (str/includes? (get-in (a/->tool (action run :walk)) [:function :description]) "per turn"))))
      (is (replays? run))
      (finally (e/stop-run run)))))

(deftest move-along-routes
  (let [move (first (filter #(= :move (:name %)) (space/actions)))
        world (e/world (doto (start) e/stop-run))
        impeded (set-faculty world "tom" :movement :impeded)
        at-sweep (w/apply-event impeded {:type :agent/placed :data {:id "tom" :to "carriage-sweep"}})]
    (is (str/includes? (a/check move world "tom" {:to "carriage-sweep"}) "your movement is disabled"))
    (is (nil? (a/check move impeded "tom" {:to "carriage-sweep"})) "next door is fine")
    (is (str/includes? (a/check move at-sweep "tom" {:to "village-green"}) "not travel"))
    (is (nil? (a/check move (set-faculty at-sweep "tom" :movement :normal) "tom" {:to "village-green"})))))

(deftest a-routine-leaves-a-bound-villager-where-he-is
  (let [run (start)]
    (try
      (is (not-any? #(= "tom" (:actor %)) (b/hibernations cw/hibernate (e/world run))))
      (is (some? (cw/hibernate (e/world run) "tom")) "his routine would otherwise take him home")
      (rounds! run 3)
      (is (= "potting-shed" (space/location-of (e/world run) "tom")))
      (is (nil? (w/busy (e/world run) "tom")))
      (is (= 3 (count (filter #(= "tom" (:actor %)) (of-type run :agent/passed)))) "he gets his turns")
      (testing "untied, his routine applies again"
        (let [untied (set-faculty (e/world run) "tom" :movement :normal)]
          (is (= ["tom" "tom"] (map :actor (b/hibernations cw/hibernate untied))))))
      (finally (e/stop-run run)))))

;; ---------------------------------------------------------------------------
;; Grasp, and taking from the helpless

(deftest disabled-grasp
  (let [run (start)
        world (set-faculty (e/world run) "slade" :grasp :disabled "hands tied")]
    (try
      (is (str/includes? (check run (e/world run) "crane" :open {:container "plate-chest"})
                         "your grasp is disabled (wrists tied behind the chair), so you can't open things"))
      (is (str/includes? (check run world "slade" :take {:item "brass-candlestick"}) "so you can't pick things up"))
      (is (str/includes? (check run world "slade" :give {:item "jewel-case" :to "ivy"}) "so you can't hand things over"))
      (is (str/includes? (check run world "slade" :put-in {:item "jewel-case" :container "sideboard"}) "so you can't put things in"))
      (is (nil? (check run world "slade" :put-down {:item "jewel-case"})) "letting something fall is allowed")
      (is (= "slade" (:holder (items/item world "jewel-case"))) "nothing is dropped")
      (finally (e/stop-run run)))))

(deftest taking-from-the-helpless
  (let [run (start)]
    (try
      (let [world (e/world run)]
        (testing "from the dead"
          (is (nil? (check run world "slade" :take {:item "pantry-keys"})))
          (e/emit! run (a/effect-events (action run :take) world "slade" {:item "pantry-keys"}))
          (let [taken (last (of-type run :item/taken))]
            (is (= {:item "pantry-keys" :from-place nil :from-person "albert"} (:data taken)))
            (is (= #{"slade"} (:visible-to taken)) "the blindfolded maid doesn't see it")
            (is (= "slade" (:holder (items/item (e/world run) "pantry-keys"))))))
        (testing "from someone who can't keep hold, even out of what they carry"
          (let [will-has (w/apply-event world {:type :item/given :actor "slade" :data {:item "sack" :to "ivy"}})
                bound (set-faculty will-has "ivy" :grasp :disabled)
                out (set-faculty will-has "ivy" :consciousness :disabled)]
            (is (str/includes? (check run will-has "slade" :take {:item "sack"}) "Ivy is holding the Sack"))
            (is (nil? (check run bound "slade" :take {:item "sack"})))
            (is (nil? (check run out "slade" :take {:item "sack"})))
            (is (= "ivy" (get-in (first (a/effect-events (action run :take) bound "slade" {:item "sack"})) [:data :from-person])))))
        (testing "not from someone elsewhere"
          (is (str/includes? (check run world "will" :take {:item "pantry-keys"}) "not here"))))
      (is (replays? run))
      (finally (e/stop-run run)))))

;; ---------------------------------------------------------------------------
;; Consciousness and death

(deftest the-unconscious-get-no-turns-and-come-to-on-time
  (let [run (start)]
    (try
      (let [world (e/world run)]
        (is (= "Mrs Bell is unconscious: rousing won't bring them round"
               (check run (w/apply-event world {:type :agent/placed :data {:id "will" :to "kitchen"}}) "will" :rouse {:who "mrs-bell"})))
        (is (= "Mrs Bell is unconscious"
               (conversation/reason-not-drawn-in (w/apply-event world {:type :agent/placed :data {:id "will" :to "kitchen"}}) "will" "mrs-bell")))
        (is (= :disabled (physical/level world "mrs-bell" :grasp)) "unconsciousness implies the rest")
        (is (nil? (physical/entry world "mrs-bell" :grasp)) "without changing their entries"))
      (rounds! run 2)
      (let [came-to (of-type run :person/came-to)
            calls (store/read-llm (:dir run))]
        (is (= [[2 "mrs-bell"]] (map (juxt :round :actor) came-to)))
        (is (= #{"mrs-bell"} (:visible-to (first came-to))) "she hears herself come to though she was out")
        (is (not (physical/unconscious? (e/world run) "mrs-bell")))
        (is (nil? (:physical (w/agent (e/world run) "mrs-bell"))))
        (is (not-any? #(and (= "mrs-bell" (:agent %)) (= 1 (:round %))) calls))
        (is (some #(and (= "mrs-bell" (:agent %)) (= 2 (:round %))) calls)))
      (is (replays? run))
      (finally (e/stop-run run)))))

(deftest knocked-out-mid-job
  (let [run (start {:llm-impl (scripted {"slade" {1 {0 {:tool :spend-time :args {:on ["sideboard"] :doing "forcing the sideboard" :hours 2.0}}}}})})]
    (try
      (rounds! run 2)
      (is (w/busy (e/world run) "slade"))
      @(e/afflict! run "slade" {:faculty :consciousness :level :disabled :cause "felled with a poker" :minutes 30})
      (testing "no interrupt: the occupation just ends"
        (is (nil? (w/busy (e/world run) "slade")))
        (is (empty? (of-type run :agent/interrupted))))
      (rounds! run 1)
      (let [done (first (of-type run :item/work-done))]
        (is (= {:stopped-early? true :minutes 30} (select-keys (:data done) [:stopped-early? :minutes]))))
      (is (= [3] (keep #(when (= "slade" (:actor %)) (:round %)) (of-type run :person/came-to))) "half an hour later")
      (is (replays? run))
      (finally (e/stop-run run)))))

(deftest death
  (let [run (start {:llm-impl (scripted {"slade" {1 {0 {:tool :spend-time :args {:on ["sideboard"] :doing "forcing the sideboard" :hours 2.0}}}}})})]
    (try
      (rounds! run 2)
      @(e/afflict! run "slade" {:died "shot by the second footman"})
      (let [world (e/world run)
            died (last (of-type run :person/died))]
        (is (= #{"slade"} (:visible-to died)) "Ivy can't see it, and the others are elsewhere")
        (is (= {:round 2 :cause "shot by the second footman"} (:died (w/agent world "slade"))))
        (is (nil? (w/busy world "slade")))
        (is (= "slade" (:holder (items/item world "jewel-case"))) "what they held stays held")
        (is (= #{"albert" "slade"} (physical/dead-at world "dining-room")))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no living participant"
                              (e/afflict! run "slade" {:died true}))))
      (rounds! run 1)
      (testing "their job is judged on the time worked"
        (is (= {:stopped-early? true :minutes 30}
               (select-keys (:data (first (of-type run :item/work-done))) [:stopped-early? :minutes]))))
      (is (not-any? #(and (= "slade" (:actor %)) (= 3 (:round %))) (of-type run :agent/passed)) "no more turns")
      (is (replays? run))
      (finally (e/stop-run run)))))

;; ---------------------------------------------------------------------------
;; Interrupts and impediments

(deftest status-changes-interrupt-the-busy
  (let [run (start)]
    (try
      (rounds! run 1)
      (is (:asleep? (w/busy (e/world run) "will")))
      (let [world (e/world run)]
        (testing "a change wakes a sleeper to decide"
          (is (= [:person/impediments-changed :agent/interrupted]
                 (map :type (changes/change-events world "will" {:impediments [{:key "head" :value "a headache"}]} "slade"))))
          (is (= [:person/physical-changed :agent/interrupted]
                 (map :type (changes/change-events world "will" {:physical [{:faculty :hearing :level :disabled}]} "slade")))))
        (testing "but not one that knocks them out or kills them"
          (is (= [:person/physical-changed]
                 (map :type (changes/change-events world "will" {:physical [{:faculty :consciousness :level :disabled}]} "slade"))))
          (is (= [:person/died] (map :type (changes/change-events world "will" {:died true} "slade"))))))
      @(e/afflict! run "will" {:impediment :head :value "a split lip"})
      (is (= "will" (:interrupted-by (w/busy (e/world run) "will"))))
      (is (= {:head "a split lip"} (get-in (view run (e/world run) "will") [:you :impediments])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"impediments"
                            (doseq [k [:a :b :c]] @(e/afflict! run "will" {:impediment k :value "a bruise"}))))
      @(e/afflict! run "will" {:impediment :head})
      (is (nil? (get-in (e/world run) [:agents "will" :impediments :head])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"speech can be impeded, disabled, normal"
                            (e/afflict! run "will" {:faculty :speech :level :numb})))
      (is (replays? run))
      (finally (e/stop-run run)))))

;; ---------------------------------------------------------------------------
;; Perception

(deftest the-audience-is-trimmed-by-what-they-can-perceive
  (let [world (e/world (doto (start) e/stop-run))
        moved {:type :agent/moved :actor "slade" :visible-to #{"slade" "ivy" "will"}}
        said {:type :agent/said :actor "slade" :visible-to #{"slade" "ivy" "mrs-bell"}}
        trimmed #(:visible-to (physical/perceivable world %))]
    (is (= #{"slade" "will"} (trimmed moved)) "the blindfolded don't see")
    (is (= #{"slade" "ivy"} (trimmed said)) "but hear, and the unconscious get nothing")
    (is (= #{"ivy"} (trimmed (assoc moved :actor "ivy" :visible-to #{"ivy"}))) "the actor is always kept")
    (is (= #{"mrs-bell"} (trimmed (assoc said :actor "mrs-bell" :visible-to #{"mrs-bell"}))))
    (is (= #{"slade" "ivy"} (trimmed {:type :item/given :actor "slade" :visible-to #{"slade" "ivy"}
                                      :data {:item "sack" :to "ivy"}}))
        "someone handed something knows it")
    (is (= #{"slade" "ivy"} (trimmed {:type :agent/passed :actor "slade" :visible-to #{"slade" "ivy" "mrs-bell"}}))
        "untagged events still miss the unconscious")
    (doseq [v [:all :gm :system]]
      (is (= v (trimmed (assoc moved :visible-to v)))))))

(deftest a-mock-night-replays
  (let [run (start)]
    (try
      (rounds! run 4)
      @(e/afflict! run "ivy" {:faculty :sight :level :normal})
      @(e/afflict! run "crane" {:faculty :speech :level :disabled :cause "the gag pulled tight"})
      (rounds! run 2)
      (let [world (e/world run)]
        (is (nil? (:physical (w/agent world "ivy"))))
        (is (= :disabled (physical/level world "crane" :speech)))
        (is (= "sack" (:in (items/item world "silver-salver"))))
        (is (empty? (of-type run :action/forfeited)))
        (testing "the live map keeps statuses as a world file would"
          (let [exported (wf/export world (wf/read-file burglary/world-file))]
            (is (empty? (wf/problems exported)))
            (is (= {:level :disabled :cause "the gag pulled tight"}
                   (get-in (first (:agents exported)) [:physical :speech])))
            (is (= {:status :dead :died {:cause "struck on the head with a candlestick"}}
                   (select-keys (nth (:agents exported) 2) [:status :died]))))))
      (is (replays? run))
      (finally (e/stop-run run)))))
