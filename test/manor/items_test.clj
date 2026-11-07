(ns manor.items-test
  "Items (manor.mechanics.items) in the hamlet test world: world files, take, put-down
  and give, look and free actions' replies, views, privacy, replay and the
  exported map."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [manor.actions :as a]
            [manor.engine :as e]
            [manor.test-support :as support]
            [manor.engine.work :as work]
            [manor.io.llm :as llm]
            [manor.io.store :as store]
            [manor.io.worldfile :as wf]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.items :as items]
            [manor.mechanics.items.judge :as item-judge]
            [manor.mechanics.items.steward :as item-steward]
            [manor.mechanics.items.views :as item-views]
            [manor.mechanics.items.work :as item-work]
            [manor.mechanics.space :as space]
            [manor.narrate :as n]
            [manor.prompt :as prompt]
            [manor.scenarios.hamlet :as hamlet]
            [manor.world :as w]))

;; All runs here use {:test? true}, so they live under test-runs/.
(use-fixtures :once support/with-runs)
;; Retry assertions use a fixed budget, not the production default.
(use-fixtures :each (partial support/with-structured-attempts 4))

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
           [#(assoc-in % [:items 0 :aspects :state] (apply str (repeat 201 "x"))) "item \"clock\": {:aspects {:state [\"should be at most 200 characters\"]}}"]
           [#(assoc-in % [:items 0 :desc] (apply str (repeat 401 "x"))) "item \"clock\": {:desc [\"should be at most 400 characters\"]}"]
           [#(assoc-in % [:items 0 :aspects :state] "") "item \"clock\": {:aspects {:state [\"should be at least 1 character\"]}}"]
           [#(assoc-in % [:items 0 :aspects] {:a "x" :b "x" :c "x" :d "x" :e "x"}) "item \"clock\": {:aspects [\"should have at most 4 elements\"]}"]
           [#(assoc-in % [:items 0 :aspects] {:Glass_Eye "loose"}) "item \"clock\": {:aspects {:Glass_Eye [\"should be a key of lower-case letters and hyphens, at most 24 characters\"]}}"]
           [#(assoc-in % [:items 0 :condition] "dusty") "item \"clock\": {:condition [\"disallowed key\"]}"]
           [#(assoc-in % [:locations 0 :aspects :hearth] "") "location \"hall\": {:aspects {:hearth [\"should be at least 1 character\"]}}"]
           [#(assoc-in % [:locations 0 :aspects :glass-eye-of-the-stag-head] "x") "location \"hall\": {:aspects {:glass-eye-of-the-stag-head [\"should be a key"]
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
        evs (items/added-events world (repeat 2 {:name "Umbrella" :desc "Another umbrella." :aspects {:state "new"}
                                                 :holder "ada" :owner "household"})
                                #{"ada"})]
    (is (= ["umbrella-2" "umbrella-3"] (map #(get-in % [:data :item :id]) evs)))
    (is (= "Ada now has an umbrella." (n/describe (first evs) (n/names world))))
    (testing "invalid fields are refused"
      (is (thrown? clojure.lang.ExceptionInfo
                   (items/added world {:name "Umbrella" :desc "x" :aspects {:state "new"} :place "attic"} :gm))))
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
        (is (= 1 (:touched (items/item taken "umbrella"))) "the round it was last handled")
        (is (= {:item "umbrella" :from-place "hall"} (:data e)))
        (is (= #{"ada" "ben"} (:visible-to e)))
        (is (= "Ben picks up the umbrella." (n/describe e (n/names taken))))))
    (testing "put-down: only what you carry, where you are"
      (is (= "you are not carrying umbrella; you carry: nothing" (check world "ada" :put-down {:item "umbrella"})))
      (is (= "you are not carrying basket; you carry: pipe (Pipe)" (check world "ben" :put-down {:item "basket"})))
      (let [[down [e]] (perform run (at world "ben" "porch") "ben" :put-down {:item "pipe"})]
        (is (= "porch" (:place (items/item down "pipe"))))
        (is (= 1 (:touched (items/item down "pipe"))))
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
        (is (= 1 (:touched (items/item given "pipe"))))
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
    (testing "items here by name only; what you carry with its aspects; what people here carry"
      (let [ada (view world "ada")
            ben (view world "ben")]
        (is (= [{:id "clock" :name "Grandfather clock"} {:id "umbrella" :name "Umbrella"}]
               (get-in ada [:location :here :items])))
        (is (= [{:id "pipe" :name "Pipe" :aspects {:state "still warm"}}] (get-in ben [:you :carrying])))
        (is (= [] (get-in ada [:you :carrying])) "always shown, empty when holding nothing")
        (is (= [{:id "ben" :name "Ben" :role :resident :carrying ["Pipe"]}] (get-in ada [:location :people-here])))
        (is (= [{:id "ada" :name "Ada" :role :resident}] (get-in ben [:location :people-here])))))
    (testing "the place's aspects are in the view, without looking"
      (is (= {:cleanliness "muddy boot prints by the door" :hearth "cold ash in the grate"}
             (get-in (view world "ada") [:location :here :aspects]))))
    (testing "nothing from other rooms, and no :items where there are none"
      (let [porch (view (at world "ada" "porch") "ada")
            shop (view (at world "ada" "shop") "ada")]
        (is (not (contains? (get-in porch [:location :here]) :items)))
        (is (not (contains? (get-in porch [:location :here]) :aspects)) "a place with none shows none")
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
;; Goods waiting for someone: Ada keeps a goose in the hall for Ben

(def ^:private for-ben {:for "ben" :keeper "ada" :at "hall" :since 1})

(def ^:private waiting-goods
  [{:id "goose" :name "Goose" :desc "A plucked goose." :place "hall" :consumable? true :waiting for-ben}
   {:id "hamper" :name "Hamper" :desc "A wicker hamper." :place "hall" :container :open}
   {:id "crate" :name "Crate" :desc "An open crate." :place "porch" :container :open}])

(defn- marked? [world id] (contains? (items/item world id) :waiting))

(deftest waiting-goods-marker
  (let [run (start)
        _ (e/emit! run (items/init-events waiting-goods))
        world (assoc (e/world run) :round 1)
        ev (fn [world t actor data] (w/apply-event world {:type t :actor actor :data data}))
        here-items #(get-in (prompt/agent-view (:scenario run) %1 %2) [:location :here :items])]
    (testing "shown as waiting at the shop, in the view's items and the buyer's list"
      (is (items/waiting? world (items/item world "goose")))
      (is (not (items/waiting? world (items/item world "hamper"))))
      (is (= ["goose"] (map :id (items/waiting-for world "ben"))))
      (is (empty? (items/waiting-for world "ada")))
      (is (= {:id "goose" :name "Goose" :waiting-for "Ben"}
             (first (filter #(= "goose" (:id %)) (here-items world "ada"))))))
    (testing "the keeper may pick it up, pack it or put it down at the shop: it stays waiting"
      (let [held (ev world :item/taken "ada" {:item "goose"})
            packed (ev held :item/put-in "ada" {:item "goose" :into "hamper"})
            down (ev held :item/put-down "ada" {:item "goose" :place "hall"})]
        (is (every? #(marked? % "goose") [held packed down]))
        (is (items/waiting? held (items/item held "goose")) "held by the keeper")
        (is (= ["goose"] (map :id (items/waiting-for packed "ben"))))
        (is (= [{:id "goose" :name "Goose" :waiting-for "Ben"}]
               (:contents (first (filter #(= "hamper" (:id %)) (here-items packed "ben"))))))
        (testing "the keeper carrying it elsewhere hides it but keeps the marker; putting it down or in there clears it"
          (let [away (at held "ada" "porch")]
            (is (not (items/waiting? away (items/item away "goose"))))
            (is (empty? (items/waiting-for away "ben")))
            (is (marked? away "goose"))
            (is (not (marked? (ev away :item/put-down "ada" {:item "goose" :place "porch"}) "goose")))
            (is (not (marked? (ev away :item/put-in "ada" {:item "goose" :into "crate"}) "goose")))))
        (testing "given to someone other than the keeper clears it; to the keeper keeps it"
          (is (not (marked? (ev held :item/given "ada" {:item "goose" :to "ben"}) "goose")))
          (let [ben-has (assoc-in world [:items "goose"] (-> (items/item world "goose") (dissoc :place) (assoc :holder "ben")))]
            (is (not (items/waiting? ben-has (items/item ben-has "goose"))) "the marker alone doesn't show it")
            (is (marked? (ev ben-has :item/given "ben" {:item "goose" :to "ada"}) "goose"))))
        (testing "taken by someone other than the keeper clears it, and from what's inside what they take"
          (is (not (marked? (ev world :item/taken "ben" {:item "goose"}) "goose")))
          (is (not (marked? (ev packed :item/taken "ben" {:item "hamper"}) "goose"))))))
    (testing "a marker away from its shop isn't shown"
      (let [moved (assoc-in world [:items "goose" :waiting :at] "porch")]
        (is (not (items/waiting? moved (items/item moved "goose"))))
        (is (empty? (items/waiting-for moved "ben")))
        (is (= {:id "goose" :name "Goose"} (first (filter #(= "goose" (:id %)) (here-items moved "ada")))))))
    (testing "new items check the marker's people and place"
      (is (some? (items/new-item world {:name "Fowl" :desc "A fowl." :place "hall" :waiting for-ben})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"waiting for unknown person"
                            (items/new-item world {:name "Fowl" :desc "A fowl." :place "hall" :waiting (assoc for-ben :for "bob")})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"waiting at unknown location"
                            (items/new-item world {:name "Fowl" :desc "A fowl." :place "hall" :waiting (assoc for-ben :at "attic")}))))
    (testing "in a run: the keeper packs it, Ben takes the hamper away; replay agrees, and the map has no marker"
      (e/emit! run [{:type :item/taken :actor "ada" :data {:item "goose"}}
                    {:type :item/put-in :actor "ada" :data {:item "goose" :into "hamper"}}])
      (is (marked? (e/world run) "goose"))
      (is (not-any? :waiting (:items (wf/read-file (str (io/file (:dir run) "map.edn"))))))
      (e/emit! run [{:type :item/taken :actor "ben" :data {:item "hamper"}}])
      (is (not (marked? (e/world run) "goose")))
      (is (= (e/world run) (w/replay (store/read-events (:dir run))))))
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
    (testing "the place with its aspects and detail, the items here in detail, owners by name, and what people carry"
      (is (= {:place {:name "Hall" :aspects {:cleanliness "muddy boot prints by the door" :hearth "cold ash in the grate"}
                      :detail "Coats on pegs, a worn rug, and a smell of pipe smoke."}
              :items [{:id "clock" :name "Grandfather clock" :desc "A tall oak clock that strikes the hours."
                       :aspects {:state "ticking loudly"} :owner "Ada" :fixed? true}
                      {:id "umbrella" :name "Umbrella" :desc "A black umbrella with a horn handle."
                       :aspects {:state "dry"} :owner "Ada"}]
              :people [{:name "Ben" :carrying [{:id "pipe" :name "Pipe" :desc "A briar pipe, much chewed."
                                                :aspects {:state "still warm"}}]}]}
             (item-views/look world "ada"))))
    (testing "people who carry nothing, and what busy people are doing"
      (let [busy (w/apply-event world (b/occupy world "ada" "reading" 4))]
        (is (= [{:name "Ada" :doing "reading"}] (:people (item-views/look busy "ben"))))))
    (testing "empty parts are left out; other owners are shown as they are"
      (is (= {:place {:name "Porch"}} (item-views/look (at world "ada" "porch") "ada")))
      (is (= ["the shop"] (map :owner (:items (item-views/look (at world "ada" "shop") "ada"))))))
    (testing "what someone carries isn't among the items here"
      (let [[taken] (perform run world "ben" :take {:item "umbrella"})]
        (is (= ["clock"] (map :id (:items (item-views/look taken "ada")))))
        (is (= ["pipe" "umbrella"] (map :id (:carrying (first (:people (item-views/look taken "ada")))))))))
    (testing "not while travelling"
      (let [[travelling] (perform run (at world "ada" "green") "ada" :move {:to "farm"})]
        (is (space/in-transit? travelling "ada"))
        (is (= "you can't look around while travelling" (a/check look travelling "ada" {})))))
    (testing "the detail is only for look, never in the view"
      (is (= {:id "hall" :name "Hall" :desc "A small hall."
              :aspects {:cleanliness "muddy boot prints by the door" :hearth "cold ash in the grate"}}
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
        (is (= {:name "Hall" :aspects {:cleanliness "muddy boot prints by the door" :hearth "cold ash in the grate"}
                :detail "Coats on pegs, a worn rug, and a smell of pipe smoke."}
               (:place seen)))
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
      (is (= 2 (:touched (items/item world "umbrella"))) "put down in round 2")
      (is (= [:item/taken :item/put-down] (map :type (filter #(and (item-event? %) (= "ben" (:actor %))) evs))))
      (is (empty? (filter #(= :action/forfeited (:type %)) evs))))
    (testing "replay gives the same world"
      (is (= world (w/replay (store/read-events (:dir run))))))
    (testing "privacy: Ada, upstairs, sees no item events; the starting items reach no agent"
      (is (empty? (filter item-event? (prompt/visible-events evs "ada" 1000))))
      (is (= [:item/taken :item/put-down] (map :type (filter item-event? (prompt/visible-events evs "ben" 1000)))))
      (is (not (str/includes? (pr-str (prompt/agent-messages (:scenario run) world evs "ada" {:event-window 1000}))
                              "umbrella"))))
    (testing "the exported map has the live items, holders as names, and starts a run with the same items (untouched)"
      (let [m (wf/read-file (str (io/file (:dir run) "map.edn")))
            by-id (into {} (map (juxt :id identity)) (:items m))]
        (is (= "porch" (:place (by-id "umbrella"))))
        (is (= "Ben" (:holder (by-id "pipe"))))
        (is (= "Ada" (:owner (by-id "clock"))))
        (is (= (map :id (:items (wf/read-file hamlet-file))) (map :id (:items m))))
        (let [r2 (start {:world-file (str (io/file (:dir run) "map.edn"))})]
          (is (not (contains? (by-id "umbrella") :touched)))
          (is (= (map #(dissoc % :touched) (items/all world)) (map #(dissoc % :touched) (items/all (e/world r2)))))
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
    (cond
      (= :items/activity-check (:task ctx)) {:tool :check-activity :args {:allowed true}}
      (= :judge role)
      (do (some-> judged (swap! conj ctx))
          ((or judge item-judge/mock-judge) ctx))
      :else (or (get-in script [agent-id (:round world) step])
          (when (:interrupted-by (w/busy world agent-id)) {:tool :carry-on})
          (hamlet/mock-policy ctx)))))

(defn- work [items doing hours]
  {:tool :work-on :args (cond-> {:items items :doing doing} hours (assoc :hours hours))})

(defn- of-type [run t] (filter #(= t (:type %)) (e/events run)))

(defn- judge-calls [run] (filter #(= "judge" (:agent %)) (store/read-llm (:dir run))))

(defn- aspects [run id] (:aspects (items/item (e/world run) id)))

(def ^:private clock-as-found {:state "ticking loudly"})
(def ^:private clock-seen-to {:state "ticking loudly" :cleanliness "just seen to"})

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
    (testing "the arguments: 1-6 items, doing 3-400 characters, hours 0.5-4"
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
      (is (= "basket is neither here nor carried by you; this place: hall (Hall); items here: clock (Grandfather clock), umbrella (Umbrella); you carry: nothing; people here: you (ada), Ben (ben)"
             (check world "ada" {:items ["basket"] :doing "mending"})))
      (testing "the place you're in is fine too, alone or with items; another place isn't"
        (is (nil? (check world "ada" {:items ["hall"] :doing "sweeping the hall"})))
        (is (nil? (check world "ada" {:items ["hall" "clock"] :doing "dusting"})))
        (is (str/starts-with? (check world "ada" {:items ["porch"] :doing "sweeping"}) "porch is neither here nor carried by you")))
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
    (testing "the aspects are unchanged at round due - 1"
      (is (= 3 (:round (e/world run))))
      (is (= clock-as-found (aspects run "clock")))
      (is (empty? @judged)))
    (rounds! run 1)
    (let [world (e/world run)
          [done] (of-type run :item/work-done)]
      (testing "and changed at due, when Ben is freed and acts again; the other aspects are left alone"
        (is (= clock-seen-to (aspects run "clock")))
        (is (= {:state "still warm" :cleanliness "just seen to"} (aspects run "pipe")))
        (is (= 4 (:touched (items/item world "clock"))) "changed items are touched")
        (is (= 4 (:round done)))
        (is (= 4 (:round (last (filter #(= "ben" (:actor %)) (of-type run :agent/freed))))))
        (is (contains? (set (map :round (filter #(= "ben" (:actor %)) (of-type run :llm/usage)))) 4))
        (is (empty? (items/jobs world))))
      (testing "the judge was told the whole time, the luck, and the items in reach"
        (let [[ctx] @judged]
          (is (= 1 (count @judged)))
          (is (= {:minutes 90 :stopped-early? false} (select-keys ctx [:minutes :stopped-early?])))
          (is (<= 1 (:luck ctx) 100))
          (is (= ["clock" "pipe"] (map :id (:focus ctx))))))
      (testing "the result: data, narration and who sees it (Ada is upstairs)"
        (is (= {:actor "ben" :place "hall" :doing "polishing the clock" :minutes 90 :stopped-early? false
                :summary "Ben finishes polishing the clock."
                :changes [{:item "clock" :aspect :cleanliness :from nil :to "just seen to"}
                          {:item "pipe" :aspect :cleanliness :from nil :to "just seen to"}]}
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
        (let [text (manor.tools.transcript/render (:dir run))]
          (is (str/includes? text "| judge | 1 | $0.0100 |"))
          (is (str/includes? text "- **judge** judge {"))
          (is (str/includes? text "Ben finishes polishing the clock. (Grandfather clock: just seen to; Pipe: just seen to)."))))
      (testing "replay gives the same world"
        (is (= world (w/replay (store/read-events (:dir run)))))))
    (e/stop-run run)))

(defn- request-scene [request]
  (-> (get-in request [:messages 1 :content])
      (->> (re-find #"(?s)```json\n(.*)\n```")) second
      (json/read-value json/keyword-keys-object-mapper)))

(def pantry
  {:id "pantry" :name "Pantry shelves" :desc "Stout shelves."
   :place "shop" :fixed? true :container :open
   :store {:keeps "dry goods and preserves" :serves ["hall"]}
   :aspects {:stock "well stocked"}})

(deftest the-judge-request
  (let [run (start)
        world (w/apply-event (assoc (e/world run) :round 4) {:type :item/added :data {:item pantry}})
        job {:id "job-9" :actor "ben" :items ["clock" "pipe"] :doing "polishing the clock" :place "hall" :started 1 :due 4}
        request (item-judge/judge-request (assoc (:scenario run) :items {:judge-brief (constantly "A small house.")
                                                                         :aspect-keys {:cleanliness "how clean"}})
                                          world job {:minutes 60 :stopped-early? true :luck 7 :recent ["Ada speaks."]})
        [system user] (:messages request)
        scene (request-scene request)]
    (is (= ["judge"] (map #(get-in % [:function :name]) (:tools request))))
    (is (str/starts-with? (:content system) (item-judge/judge-instructions world))
        "the request includes the rendered scene instructions without pinning their prose")
    (is (str/includes? (:content system) "- cleanliness: how clean"))
    (is (str/ends-with? (:content system) "## The setting\nA small house."))
    (testing "aspect values: the judge and steward are told 80 characters; 200 are accepted"
      (is (str/includes? (:content system) "a short phrase of at most 80 characters"))
      (is (= {:type "string" :maxLength 80}
             (get-in (first (:tools request)) [:function :parameters :properties :changes :items :properties :value])))
      (is (str/includes? (item-steward/steward-instructions {}) "values are 1–80 characters")))
    (is (str/includes? (:content user) (str "- Time: Saturday 15 October 1904, 22:30\n"
                                            "- Worker: Ben. A night owl who hates going to bed.\n"
                                            "- Place: Hall\n"
                                            "- Doing: polishing the clock\n"
                                            "- Carrying: Pipe (pipe)\n"
                                            "- Time worked: 60 minutes; they stopped early\n"
                                            "- Luck: 7\n")))
    (is (= #{:place :focus :also_here :stores :people :recent} (set (keys scene))))
    (is (= {:id "hall" :name "Hall" :desc "A small hall."
            :aspects {:cleanliness "muddy boot prints by the door" :hearth "cold ash in the grate"}}
           (:place scene)))
    (is (= ["clock" "pipe"] (map :id (:focus scene))))
    (is (= {:state "still warm"} (:aspects (second (:focus scene)))))
    (is (= ["umbrella"] (map :id (:also_here scene))))
    (is (= [{:name "Ada"} {:id "ben" :name "Ben" :person true}] (:people scene))
        "the worker is judged, as a person; others are context")
    (is (= [{:id "pantry" :name "Pantry shelves" :aspects {:stock "well stocked"}
             :at "shop" :keeps "dry goods and preserves"}] (:stores scene)))
    (is (= ["Ada speaks."] (:recent scene)))
    (is (not (str/includes? (:content user) "Coats on pegs")) "no room detail")
    (is (not (str/includes? (:content user) "basket")) "nothing from elsewhere")
    (testing "a place named as focus is marked as a room too"
      (let [scene (request-scene (item-judge/judge-request (:scenario run) world (assoc job :items ["hall"]) {:minutes 60}))]
        (is (= true (:room (first (:focus scene)))))
        (is (= "hall" (:id (first (:focus scene)))))))
    (testing "without a :judge-brief, no setting"
      (is (not (str/includes? (get-in (item-judge/judge-request (:scenario run) world job {:minutes 30}) [:messages 0 :content])
                              "## The setting"))))
    (e/stop-run run)))

(defn- judge-says [args]
  {:tool-calls [{:id "j1" :name "judge" :arguments (json/write-value-as-string args)}]})

(deftest checking-the-judge
  (let [run (start)
        world (e/world run)
        things (item-work/judged-things world {:items ["hall" "clock" "pipe"] :place "hall" :actor "ben"})
        judge #(item-judge/judgement (judge-says %) world things)
        change (fn [thing aspect value] {:thing thing :aspect aspect :value value})]
    (testing "a good answer: each change with the aspect's old value; unchanged ones left out"
      (is (= {:verdict {:summary "It shines."
                        :changes [{:item "clock" :aspect :cleanliness :from nil :to "polished"}]}
              :replies [{:role "tool" :tool_call_id "j1" :content "ok"}]}
             (judge {:changes [(change "clock" "cleanliness" "polished") (change "pipe" "state" "still warm")]
                     :summary "It shines."}))))
    (testing "the room changes as a place; its other aspects are left alone"
      (is (= [{:place "hall" :aspect :cleanliness :from "muddy boot prints by the door" :to "swept"}]
             (:changes (:verdict (judge {:changes [(change "hall" "cleanliness" "swept")] :summary "Swept."}))))))
    (testing "an empty value removes the aspect; removing one it doesn't have is no change"
      (is (= [{:item "clock" :aspect :state :from "ticking loudly" :to nil}]
             (:changes (:verdict (judge {:changes [(change "clock" "state" "") (change "pipe" "repair" "")] :summary "Stopped."}))))))
    (testing "aspect names are normalised like argument names, and the last change to an aspect counts"
      (is (= [{:item "clock" :aspect :glass-front :from nil :to "clean"}
              {:item "pipe" :aspect :state :from "still warm" :to "cold"}]
             (:changes (:verdict (judge {:changes [(change "clock" "glass_front" "smeared") (change "pipe" "state" "cold")
                                                   (change "clock" "glassFront" "clean")]
                                         :summary "x"}))))))
    (testing "descriptions, names and other keys are ignored: only aspects change"
      (is (= {:summary "Gilded." :changes [{:item "clock" :aspect :state :from "ticking loudly" :to "gilded"}]}
             (:verdict (judge {:changes [(assoc (change "clock" "state" "gilded") :desc "A gold clock." :name "Gold clock")]
                               :summary "Gilded." :desc "x"})))))
    (testing "no changes is a fine answer"
      (is (= {:summary "Nothing came of it." :changes []} (:verdict (judge {:summary "Nothing came of it."})))))
    (testing "at most four aspects a thing, counting those it has less those removed"
      (let [four-more (for [k ["a" "b" "c" "d"]] (change "clock" k "x"))]
        (is (= (str "the Grandfather clock (clock) would have 5 aspects (state, a, b, c, d); a thing has at most 4: "
                    "change one it has instead, or remove one with the value \"\"")
               (:error (judge {:changes four-more :summary "x"}))))
        (is (= 5 (count (:changes (:verdict (judge {:changes (cons (change "clock" "state" "") four-more) :summary "x"}))))))))
    (testing "refused: things not judged, bad keys, bad lengths, no summary, the old shape, no call"
      (is (= "you may change only these things: hall, clock, pipe, umbrella, ben; not basket"
             (:error (judge {:changes [(change "basket" "state" "dry")] :summary "x"}))))
      (is (= "an aspect's key is lower-case letters and hyphens, at most 24 characters, starting with a letter (cleanliness, glass-eye); not \"glass eye\", \"1st\""
             (:error (judge {:changes [(change "clock" "Glass Eye" "loose") (change "clock" "1st" "x")] :summary "x"}))))
      (is (str/includes? (:error (judge {:changes [(change "clock" "state" (apply str (repeat 201 "x")))] :summary "x"}))
                         "changes.0.value is 201 characters; the limit is 200"))
      (is (nil? (:error (judge {:changes [(change "clock" "state" (apply str (repeat 200 "x")))] :summary "x"})))
          "over the advised 80 characters is still accepted up to 200")
      (is (str/includes? (:error (judge {:changes [] :summary (apply str (repeat 301 "x"))})) "the limit is 300"))
      (is (:error (judge {:changes []})))
      (is (:error (judge {:changes [{:item "clock" :condition "polished"}] :summary "x"})))
      (is (= "error: you may change only these things: hall, clock, pipe, umbrella, ben; not basket. Call judge again with a corrected answer."
             (:content (first (:replies (judge {:changes [(change "basket" "state" "dry")] :summary "x"}))))))
      (is (= {:error "no judge call" :replies [{:role "user" :content "You must answer by calling the judge tool."}]}
             (item-judge/judgement {:tool-calls []} world things))))
    (e/stop-run run)))

(deftest bad-judge-answers
  (let [run-with (fn [judge]
                   (let [judged (atom [])
                         run (rounds! (start {:llm-impl (llm/mock (scripted {"ben" {1 [(work ["clock"] "winding the clock" nil)]}}
                                                                            :judge judge :judged judged))})
                                      2)]
                     [run judged]))]
    (testing "an invalid answer (a bad key) is sent back with the reason; a good second answer is used"
      (let [[run judged] (run-with (fn [{:keys [step]}]
                                     {:tool :judge :args (if (zero? step)
                                                           {:changes [{:thing "clock" :aspect "wound up" :value "yes"}] :summary "x"}
                                                           {:changes [{:thing "clock" :aspect "state" :value "wound"}] :summary "It ticks."})}))
            [first-call second-call] (judge-calls run)]
        (is (= 2 (count @judged)))
        (is (= {:state "wound"} (aspects run "clock")))
        (is (str/starts-with? (:content (last (:messages second-call))) "error: an aspect's key is lower-case letters and hyphens"))
        (is (= (:replies first-call) [(last (:messages second-call))]))
        (is (empty? (of-type run :item/judge-failed)))
        (e/stop-run run)))
    (testing "four bad answers: no changes, a neutral summary, and a :system event with the reason"
      (let [[run judged] (run-with (constantly {:tool :judge :args {:changes [{:thing "clock" :aspect "state"}] :summary "x"}}))
            [done] (of-type run :item/work-done)
            [failed] (of-type run :item/judge-failed)]
        (is (= 4 (count @judged) (count (judge-calls run))))
        (is (= clock-as-found (aspects run "clock")))
        (is (= {:summary "Ben finishes winding the clock." :changes []} (select-keys (:data done) [:summary :changes])))
        (is (= :system (:visible-to failed)))
        (is (str/starts-with? (get-in failed [:data :reason]) "no valid answer after 4 attempts: invalid arguments"))
        (is (= "Ben finishes winding the clock." (n/describe done (n/names (e/world run)))))
        (is (empty? (filter #(= :run/pause-requested (:type %)) (e/events run))) "the run isn't paused")
        (e/stop-run run)))
    (testing "an exception: the same, with no retry"
      (let [[run] (run-with (fn [_] (throw (ex-info "provider down" {}))))
            [failed] (of-type run :item/judge-failed)]
        (is (= "the judge call failed: provider down" (get-in failed [:data :reason])))
        (is (= 1 (count (of-type run :item/work-done))))
        (is (= clock-as-found (aspects run "clock")))
        (is (= (e/world run) (w/replay (store/read-events (:dir run)))))
        (e/stop-run run)))))

(deftest stopping-early-or-carrying-on
  (let [run-with (fn [ben-round-2]
                   (let [judged (atom [])
                         ;; Ada rouses Ben in round 2; Ben decides in his slot that round
                         policy (scripted {"ada" {1 [{:tool :pass}]
                                                  2 [{:tool :rouse :args {:who "ben"}} {:tool :pass}]}
                                           "ben" {1 [(work ["clock"] "winding the clock" 2)]
                                                  2 ben-round-2}}
                                          :judged judged)]
                     [(start {:llm-impl (llm/mock policy)}) judged]))]
    (testing "stopping after the interruption: judged on the time really worked"
      (let [[run judged] (run-with [{:tool :say :args {:text "Coming!"}}])]
        (rounds! run 2)
        (is (= 1 (count (of-type run :item/work-begun))))
        (is (empty? @judged) "the job is judged as the next round starts")
        (rounds! run 1)
        (let [[done] (of-type run :item/work-done)]
          (is (= 3 (:round done)))
          (is (= {:minutes 30 :stopped-early? true} (select-keys (:data done) [:minutes :stopped-early?])))
          (is (= {:minutes 30 :stopped-early? true} (select-keys (first @judged) [:minutes :stopped-early?])))
          (is (= "Ben leaves off winding the clock. (Grandfather clock: just seen to)."
                 (n/describe done (n/names (e/world run))))))
        (is (= clock-seen-to (aspects run "clock")))
        (is (empty? (items/jobs (e/world run))))
        (e/stop-run run)))
    (testing "carrying on: the job runs to its end"
      (let [[run judged] (run-with [{:tool :carry-on}])]
        (rounds! run 4)
        (is (empty? @judged))
        (is (= clock-as-found (aspects run "clock")))
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
      (is (= clock-as-found (aspects run "clock"))))
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
      (is (= ["clock" "pipe"] (map :id (:focus (first @judged)))))
      (is (= clock-seen-to (aspects run "clock")))
      (is (= "just seen to" (:cleanliness (aspects run "pipe"))))
      (is (= {:state "dry"} (aspects run "umbrella")))
      (e/stop-run run)))
  (testing "nothing left in focus: the scene is still judged"
    (let [judged (atom [])
          run (rounds! (start {:llm-impl (llm/mock (scripted {"ada" {1 [{:tool :pass}]
                                                                     2 [{:tool :take :args {:item "umbrella"}}
                                                                        {:tool :move :args {:to "porch"}}]}
                                                              "ben" {1 [(work ["umbrella"] "drying the umbrella" 1)]}}
                                                             :judged judged))})
                       3)
          [done] (of-type run :item/work-done)]
      (is (= 1 (count @judged) (count (judge-calls run))))
      (is (empty? (:focus (first @judged))))
      (is (= ["hall" "pipe" "clock" "ben"] (map :id (:things (first @judged)))) "with the pipe Ben carries")
      (is (empty? (:changes (:data done))))
      (is (= #{"ben"} (:visible-to done)) "Ada took it to the porch, so only Ben is in the hall")
      (is (= "Ben finishes drying the umbrella." (n/describe done (n/names (e/world run)))))
      (is (= {:state "dry"} (aspects run "umbrella")))
      (e/stop-run run))))

(def ^:private hall-as-found {:cleanliness "muddy boot prints by the door" :hearth "cold ash in the grate"})

(deftest work-on-the-room
  (testing "the room with items: the room goes to the judge first, then the items"
    (let [judged (atom [])
          run (rounds! (start {:llm-impl (llm/mock (scripted {"ben" {1 [(work ["hall" "clock"] "sweeping the hall and dusting the clock" 1)]}}
                                                             :judged judged))})
                       3)
          [done] (of-type run :item/work-done)]
      (is (= ["hall" "clock"] (map :id (:focus (first @judged)))))
      (is (= [{:place "hall" :aspect :cleanliness :from "muddy boot prints by the door" :to "just seen to"}
              {:item "clock" :aspect :cleanliness :from nil :to "just seen to"}]
             (get-in done [:data :changes])))
      (is (= "Ben finishes sweeping the hall and dusting the clock. (Hall: just seen to; Grandfather clock: just seen to)."
             (n/describe done (n/names (e/world run)))))
      (e/stop-run run)))
  (testing "the room alone is judged too; its other aspects are left alone, and replay agrees"
    (let [judged (atom [])
          run (rounds! (start {:llm-impl (llm/mock (scripted {"ben" {1 [(work ["hall"] "sweeping the hall" 1)]}}
                                                             :judged judged))})
                       3)
          [done] (of-type run :item/work-done)
          world (e/world run)]
      (is (= 1 (count @judged) (count (judge-calls run))))
      (is (= [{:id "hall" :name "Hall" :desc "A small hall." :aspects hall-as-found :room? true}] (:focus (first @judged))))
      (is (empty? (of-type run :item/work-ended)))
      (is (= {:minutes 60 :stopped-early? false :summary "Ben finishes sweeping the hall."
              :changes [{:place "hall" :aspect :cleanliness :from "muddy boot prints by the door" :to "just seen to"}]}
             (select-keys (:data done) [:minutes :stopped-early? :summary :changes])))
      (is (= "Ben finishes sweeping the hall. (Hall: just seen to)." (n/describe done (n/names world))))
      (is (= {:cleanliness "just seen to" :hearth "cold ash in the grate"} (:aspects (space/location world "hall"))))
      (is (= {:cleanliness "just seen to" :hearth "cold ash in the grate"}
             (get-in (prompt/agent-view (:scenario run) world "ben") [:location :here :aspects]))
          "the view shows the room as it is now")
      (is (empty? (items/jobs world)))
      (is (= world (w/replay (store/read-events (:dir run)))))
      (e/stop-run run)))
  (testing "a room aspect removed: gone from the place, and read as \"no longer\""
    (let [judge (fn [{:keys [world job stopped-early?]}]
                  {:tool :judge :args {:changes [{:thing "hall" :aspect "hearth" :value ""}
                                                 {:thing "hall" :aspect "air" :value "fresh"}]
                                       :summary (item-work/neutral-summary world job stopped-early?)}})
          run (rounds! (start {:llm-impl (llm/mock (scripted {"ben" {1 [(work ["hall"] "clearing the grate" 1)]}} :judge judge))})
                       3)
          [done] (of-type run :item/work-done)
          world (e/world run)]
      (is (= {:cleanliness "muddy boot prints by the door" :air "fresh"} (:aspects (space/location world "hall"))))
      (is (= "Ben finishes clearing the grate. (Hall: no longer hearth, fresh)." (n/describe done (n/names world))))
      (is (= world (w/replay (store/read-events (:dir run)))))
      (e/stop-run run)))
  (testing "the place and visible scene are judged even when no focus remains"
    (let [run (start)
          world (e/world run)]
      (is (= ["porch" "pipe" "ben"] (map :id (item-work/judged-things world {:items ["umbrella"] :place "porch" :actor "ben"})))
          "the worker, and what they carry, are judged wherever they are")
      (is (= ["hall" "clock" "umbrella" "ada"] (map :id (item-work/judged-things world {:items ["hall" "basket"] :place "hall" :actor "ada"})))
          "the room is judged even when its items have gone")
      (is (= ["hall" "umbrella" "clock" "ada"] (map :id (item-work/judged-things world {:items ["umbrella"] :place "hall" :actor "ada"}))))
      (e/stop-run run))))

(deftest overlapping-jobs
  (let [judged (atom [])
        judge (fn [{:keys [job]}]
                {:tool :judge :args {:changes [{:thing "clock" :aspect "state" :value (str "after " (:doing job))}]
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
      (is (= "ticking loudly" (get-in (first (:focus (first @judged))) [:aspects :state])))
      (is (= (str "after " (:doing first-job)) (get-in (first (:focus (second @judged))) [:aspects :state])))
      (is (= {:state (str "after " (:doing second-job))} (aspects run "clock"))))
    (testing "replay gives the same world"
      (is (= (e/world run) (w/replay (store/read-events (:dir run))))))
    (e/stop-run run)))

(deftest stores-in-scope
  (let [run (start)
        world (w/apply-event (e/world run) {:type :item/added :data {:item pantry}})
        view #(get-in (prompt/agent-view (:scenario run) % "ben") [:location :here])]
    (is (items/store? pantry))
    (is (not (items/store? (items/item world "clock"))))
    (is (= [(assoc pantry :touched 0)] (items/stores-for world "hall") (items/stores-for world "shop")))
    (is (empty? (items/stores-for world "porch")))
    (is (= [{:id "pantry" :name "Pantry shelves" :aspects {:stock "well stocked"}
             :keeps "dry goods and preserves" :at "shop"}]
           (:stores (view world))))
    (is (= [{:id "pantry" :name "Pantry shelves" :aspects {:stock "well stocked"} :keeps "dry goods and preserves"}]
           (:stores (view (at world "ben" "shop")))))
    (is (= ["basket"] (map :id (:items (view (at world "ben" "shop"))))))
    (is (= "dry goods and preserves" (:keeps (first (:stores (item-views/look world "ben"))))))
    (is (= "dry goods and preserves"
           (:keeps (first (filter #(= "pantry" (:id %)) (:items (item-views/look (at world "ben" "shop") "ben")))))))
    (is (nil? (a/check (action run :work-on) world "ben" {:items ["pantry"] :doing "taking stock"})))
    (is (= [(assoc pantry :touched 0)] (items/in-reach world {:items ["pantry"] :place "hall" :actor "ben"})))
    (is (= {:name "Pantry shelves" :aspects {:stock "well stocked"} :keeps "dry goods and preserves"
            :contents "Nothing else of note"}
           (item-views/opened world pantry))
        "an opened store shows what it keeps, and no empty list that reads as an empty store")
    (e/stop-run run)))

(deftest store-checks-and-export
  (let [good (update (wf/read-file hamlet-file) :items conj pantry)
        problems #(str/join "\n" (wf/problems (update good :items (fn [its] (conj (pop its) %)))))]
    (is (empty? (wf/problems good)))
    (is (str/includes? (problems (dissoc pantry :fixed?)) "a store must be :fixed?"))
    (is (str/includes? (problems (assoc pantry :consumable? true)) "a store cannot be :consumable?"))
    (is (str/includes? (problems (assoc-in pantry [:store :serves] ["attic"])) "store serves unknown location \"attic\""))
    (doseq [keeps ["" (apply str (repeat 201 "x"))]]
      (is (str/includes? (problems (assoc-in pantry [:store :keeps] keeps)) ":keeps")))
    (let [file (str (gensym "stores-") ".edn")
          path (str (io/file store/test-runs-root file))]
      (store/write-text! store/test-runs-root file (wf/->edn-string good))
      (is (= good (wf/read-file path))))))

(deftest spend-time-and-work-on-alias
  (let [run (start)
        world (w/apply-event (assoc (e/world run) :round 1) {:type :item/added :data {:item pantry}})
        spend (action run :spend-time)
        alias (action run :work-on)
        args {:doing "taking stock" :hours 1 :on ["pantry" "pantry"]}]
    (is (= :final (a/kind spend)))
    (is (not (a/allowed? spend :gm)))
    (is (empty? (a/available [spend] (dissoc world :clock) "ben")))
    (is (nil? (a/check spend world "ben" args)))
    (is (= (a/effect-events spend world "ben" args)
           (a/effect-events alias world "ben" {:doing "taking stock" :hours 1 :items ["pantry" "pantry"]})))
    (is (= ["pantry"] (get-in (first (a/effect-events spend world "ben" args)) [:data :items])))
    (is (= {:doing "resting"} (:args (a/decode-args spend {:doing "resting"}))))
    (doseq [args [{:doing "do"} {:doing (apply str (repeat 401 "x"))}
                  {:doing "resting" :hours 0.4} {:doing "resting" :hours 4.1}
                  {:doing "resting" :on (vec (repeat 7 "clock"))}]]
      (is (:error (a/decode-args spend args))))
    (doseq [act [spend alias]
            :let [doing (str "writing " (apply str (repeat 392 "x")))
                  args (cond-> {:doing doing} (= act alias) (assoc :items ["clock"]))
                  {:keys [args error]} (a/decode-args act args)]]
      (is (= 400 (count doing)))
      (is (= {:type "string" :minLength 3 :maxLength 200}
             (get-in (a/->tool act) [:function :parameters :properties :doing])))
      (is (str/includes? (get-in (a/->tool act) [:function :description]) "at most 200 characters"))
      (is (:args (a/decode-args act (assoc args :doing (subs doing 0 200)))))
      (is (nil? error))
      (is (str/includes? (:error (a/decode-args act (assoc args :doing (str doing "x"))))
                         "doing is 401 characters; the limit is 400 - shorten it by at least 1"))
      (let [[begun occupied] (a/effect-events act world "ben" args)]
        (is (= doing (get-in occupied [:data :activity])))
        (is (str/includes? (n/describe occupied (n/names world)) (str "Ben is " doing)))
        (is (str/starts-with? (n/describe (item-work/work-done world (:data begun) {:minutes 30} {}) (n/names world))
                              (str "Ben finishes " doing ".")))))
    (is (nil? (a/check spend world "ben" {:doing "reading" :on []})))
    (is (str/includes? (a/check spend world "ben" {:doing "taking stock" :on ["basket"]}) "basket is neither here"))
    (e/stop-run run)))

(deftest scene-scope-and-cap
  (let [run (start)
        things (concat [pantry
                        {:id "chest" :name "Chest" :desc "A closed chest." :place "hall" :container :closed}
                        {:id "secret" :name "Secret" :desc "Hidden." :in "chest"}]
                       (for [i (range 16)] {:id (str "token-" i) :name (str "Token " i) :desc "A token." :place "hall"}))
        world (reduce w/apply-event (e/world run) (items/init-events things))
        job {:actor "ben" :place "hall" :items ["clock" "pantry"]}
        request (item-judge/judge-request (:scenario run) world job {:minutes 30})
        scene (request-scene request)
        judged (item-work/judged-things world job)
        verdict (:verdict (item-judge/judgement (judge-says {:summary "Done."
                                                             :changes [{:thing "hall" :aspect "air" :value "fresh"}
                                                                       {:thing "pantry" :aspect "stock" :value "running low"}
                                                                       {:thing "chest" :aspect "state" :value "dusty"}]}) world judged))]
    (is (= ["clock" "pantry"] (map :id (:focus scene))))
    (is (= 12 (count (:also_here scene))))
    (is (= "chest" (:id (first (:also_here scene)))))
    (is (not-any? #(#{"secret" "clock" "pantry" "basket" "pipe"} (:id %)) (:also_here scene)))
    (is (= [{:id "pantry" :name "Pantry shelves" :aspects {:stock "well stocked"}
             :at "shop" :keeps "dry goods and preserves"}] (:stores scene)))
    (is (= 1 (count (filter #(= "pantry" (:id %)) judged))))
    (is (= ["hall" "pantry" "chest"] (map #(or (:place %) (:item %)) (:changes verdict))))
    (is (:error (item-judge/judgement (judge-says {:summary "No." :changes [{:thing "secret" :aspect "state" :value "found"}]})
                                      world judged)))
    (e/stop-run run)))

(deftest scene-recent-uses-the-workers-shared-events
  (let [run (start)
        world (assoc (e/world run) :round 3)
        job {:id "job-9" :actor "ben"}
        announcement (fn [text audience] {:type :gm/announced :round 2 :visible-to audience :data {:text text}})
        begun {:type :item/work-begun :round 1 :visible-to :gm :data {:id "job-9"}}
        events [(announcement "before the job" :all) begun
                (announcement "the range ran too hot" #{"ben" "ada"})
                (announcement "for Ada only" #{"ada"})
                (announcement "for Ben only" #{"ben"})
                {:type :agent/noted :actor "ben" :round 2 :visible-to #{"ben"} :data {:text "private note"}}
                {:type :llm/usage :round 2 :visible-to :all}
                {:type :round/ended :round 2 :visible-to :all}
                (announcement "operator only" :system)
                (announcement "shared whisper" #{"ben" "ada"})]]
    (is (= ["[21:30] Announcement: the range ran too hot" "[21:30] Announcement: shared whisper"]
           (prompt/scene-recent world events job)))
    (let [events (into events (map #(announcement (str "line " %) :all) (range 14)))
          recent (prompt/scene-recent world events job)]
      (is (= 10 (count recent))))
    (is (empty? (prompt/scene-recent world events (assoc job :id "missing"))))
    (e/stop-run run)))

(deftest judged-pastime-and-empty-place
  (testing "a pastime may change the room even with no on; replay agrees"
    (let [judge (constantly {:tool :judge :args {:changes [{:thing "hall" :aspect "air" :value "smoky"}]
                                                 :summary "Ben smoked quietly."}})
          run (rounds! (start {:llm-impl (llm/mock (scripted {"ben" {1 [{:tool :spend-time :args {:doing "smoking a pipe"}}]}}
                                                             :judge judge))}) 2)]
      (is (= "smoky" (get-in (space/location (e/world run) "hall") [:aspects :air])))
      (is (= 1 (count (judge-calls run))))
      (is (= (e/world run) (w/replay (store/read-events (:dir run)))))
      (e/stop-run run)))
  (testing "a rest at an empty place is still judged, since the worker may change; the mock leaves them alone"
    (let [run (rounds! (start {:llm-impl (llm/mock (scripted {"ben" {1 [{:tool :move :args {:to "porch"}}]
                                                                     2 [{:tool :spend-time :args {:doing "resting quietly"}}]}}))}) 3)
          [done] (of-type run :item/work-done)]
      (is (= 1 (count (judge-calls run))))
      (is (= "Ben finishes resting quietly." (get-in done [:data :summary])))
      (is (empty? (get-in done [:data :changes])))
      (is (empty? (items/jobs (e/world run))))
      (is (empty? (of-type run :item/work-ended)))
      (e/stop-run run))))

(deftest spend-time-on-a-serving-store
  (let [file (str (gensym "serving-store-") ".edn")
        _ (store/write-text! store/test-runs-root file
                             (wf/->edn-string (update (wf/read-file hamlet-file) :items conj pantry)))
        run (rounds! (start {:world-file (str (io/file store/test-runs-root file))
                             :llm-impl (llm/mock (scripted {"ben" {1 [{:tool :spend-time
                                                                       :args {:on ["pantry"] :doing "taking stock"}}]}}))}) 2)
        [done] (of-type run :item/work-done)]
    (is (= [{:item "pantry" :aspect :cleanliness :from nil :to "just seen to"}] (get-in done [:data :changes])))
    (is (= {:stock "well stocked" :cleanliness "just seen to"} (:aspects (items/item (e/world run) "pantry"))))
    (is (= "shop" (:place (items/item (e/world run) "pantry"))))
    (is (= (e/world run) (w/replay (store/read-events (:dir run)))))
    (e/stop-run run)))

(deftest parallel-ending-jobs
  (let [works (mapv (fn [id actor place]
                      {:job {:id id :actor actor :place place :items [] :doing "tidying"}
                       :outcome :finished :minutes 30 :luck 50})
                    ["job-a" "job-b"] ["ada" "ben"] ["hall" "bedroom"])
        go (fn [parallel? works policy]
             (let [run (start {:llm-impl (llm/mock policy)})
                   before (count (e/events run))]
               (try
                 (if parallel?
                   (work/finish-jobs! run works)
                   (doseq [work works]
                     (#'work/finish-job! run work (#'work/job-result run (e/world run) work))))
                 (is (= (e/world run) (w/replay (store/read-events (:dir run)))))
                 (vec (drop before (e/events run)))
                 (finally (e/stop-run run)))))
        policy (fn [{:keys [job world]}]
                 {:tool :judge :args {:summary "Tidied."
                                      :changes [{:thing (:place job) :aspect "state"
                                                 :value (str (get-in world [:space :locations (:place job) :aspects :state]) "x")}]}})]
    (testing "independent jobs emit exactly the sequential events, including usage"
      ;; Give both rooms something to judge, through a named place focus.
      (let [works (mapv #(assoc-in % [:job :items] [(get-in % [:job :place])]) works)]
        (is (= (go false works policy) (go true works policy)))))
    (testing "overlapping jobs see their predecessor's changes"
      (let [works (mapv #(assoc (:job %) :place "hall" :items ["hall"]) works)
            works (mapv #(hash-map :job % :outcome :finished :minutes 30 :luck 50) works)
            events (go true works policy)]
        (is (= ["x" "xx"] (map #(get-in % [:data :changes 0 :to]) (filter #(= :item/work-done (:type %)) events))))))
    (testing "independent model calls actually overlap, without holding the world lock"
      (let [arrived (java.util.concurrent.CountDownLatch. 2)
            overlapped (atom [])
            works (mapv #(assoc-in % [:job :items] [(get-in % [:job :place])]) works)]
        (go true works (fn [ctx]
                         (.countDown arrived)
                         (swap! overlapped conj (.await arrived 2 java.util.concurrent.TimeUnit/SECONDS))
                         (policy ctx)))
        (is (= [true true] @overlapped))))
    (testing "shared serving stores and focus places conflict"
      (let [run (start)
            world (w/apply-event (e/world run)
                                 {:type :item/added :data {:item {:id "store" :place "hall" :fixed? true
                                                                  :store {:keeps "supplies" :serves ["bedroom"]}}}})
            a (item-work/job-scope world {:place "hall"})
            b (item-work/job-scope world {:place "bedroom" :items ["store"]})]
        (is (contains? a [:store "store"]))
        (is (contains? b [:store "store"]))
        (is (contains? b [:place "hall"]))
        (e/stop-run run)))))
