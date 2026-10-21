(ns manor.space-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [manor.actions :as a]
            [manor.engine :as e]
            [manor.io.store :as store]
            [manor.mechanics.space :as space]
            [manor.prompt :as prompt]
            [manor.world :as w]))

;; All runs here use {:test? true}, so they live under test-runs/.
(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

(defn- village []
  (e/start-run :village {:llm :mock :offline? true :test? true :run-id (str (gensym "space-village-"))}))

(defn- action [run n] (first (filter #(= n (:name %)) (:actions run))))

(defn- perform
  "Check and apply an action purely. Returns [world events] or a rejection reason."
  [run world actor n args]
  (let [act (action run n)]
    (or (a/check act world actor args)
        (let [evs (a/effect-events act world actor args)]
          [(reduce w/apply-event world evs) evs]))))

(deftest graph-and-views
  (let [run (village)
        world (e/world run)]
    (testing "the scenario's graph is built from events"
      (is (= #{"square" "market" "fields" "lisbon"} (set (keys (get-in world [:space :locations])))))
      (is (= 2 (:travel (space/route world "lisbon" "square")))))
    (testing "agents start where their role says"
      (is (= "fields" (space/location-of world "ana")))
      (is (= "market" (space/location-of world "clara"))))
    (testing "an agent's view includes where they are, exits and who is there"
      (let [loc (:location (prompt/agent-view (:scenario run) world "ana"))]
        (is (= "fields" (get-in loc [:here :id])))
        (is (= #{"market" "square"} (set (map :to (:exits loc)))))
        (is (= ["bruno"] (map :id (:people-here loc))))))
    (testing "quickest path"
      (is (= ["square" "lisbon"] (space/path world "fields" "lisbon")))
      (is (= "square" (space/next-step world "fields" "lisbon"))))
    (e/stop-run run)))

(deftest moving-and-hearing
  (let [run (village)
        world (e/world run)
        [world [moved]] (perform run world "ana" :move {:to "market"})]
    (testing "a one-round move is immediate and seen at both ends"
      (is (= "market" (space/location-of world "ana")))
      (is (= #{"ana" "bruno" "clara"} (:visible-to moved))))
    (testing "speech is heard only where it is said"
      (let [[_ [said]] (perform run world "ana" :say {:text "hello"})]
        (is (= #{"ana" "clara"} (:visible-to said)))))
    (testing "whispering and trading need both people in the same place"
      (is (= "bruno is not here" (a/check (action run :whisper) world "ana" {:to "bruno" :text "psst"})))
      (is (= "duarte is not here" (a/check (action run :offer) world "ana" {:to "duarte" :give-coins 1})))
      (is (nil? (a/check (action run :offer) world "ana" {:to "clara" :give-coins 5 :want-grain 1}))))
    (testing "location-gated actions explain where to go"
      (is (re-find #"must be at Fields" (a/check (action run :plant) world "ana" {:grain 2}))))
    (testing "unknown or unconnected destinations are rejected"
      (is (= "there is no location moon" (a/check (action run :move) world "ana" {:to "moon"})))
      (is (= "there is no route from here to lisbon" (a/check (action run :move) world "ana" {:to "lisbon"}))))
    (e/stop-run run)))

(deftest travel-takes-time
  (let [run (village)
        world (assoc (e/world run) :round 3)
        [world [departed]] (perform run world "duarte" :move {:to "lisbon"})]
    (testing "a long trip puts the agent in transit"
      (is (= :agent/departed (:type departed)))
      (is (= 5 (get-in departed [:data :arrives])))
      (is (space/in-transit? world "duarte"))
      (is (nil? (space/location-of world "duarte"))))
    (testing "travellers can't take the move action"
      (is (not ((:available? (action run :move)) world "duarte"))))
    (testing "arrival happens at the start of the due round"
      (is (empty? (space/arrivals (assoc world :round 4))))
      (let [[arrived] (space/arrivals (assoc world :round 5))]
        (is (= "lisbon" (get-in arrived [:data :to])))
        (is (= "lisbon" (space/location-of (w/apply-event world arrived) "duarte")))))
    (e/stop-run run)))

(deftest travellers-skip-turns
  (let [run (village)]
    (e/auto! run 5)
    (is (= :idle (e/wait-idle run 60000)))
    (let [evs (e/events run)
          departed (first (filter #(and (= :agent/departed (:type %)) (= "clara" (:actor %))) evs))
          {:keys [arrives]} (:data departed)
          acted-in-transit (filter #(and (= "clara" (:actor %))
                                         (< (:round departed) (:round %) arrives)
                                         (not (#{:village/ate :village/hungry :llm/usage} (:type %))))
                                   evs)]
      (is (some? departed))
      (is (empty? acted-in-transit))
      (is (some #(and (= :agent/arrived (:type %)) (= "clara" (:actor %)) (= arrives (:round %))) evs))
      (testing "replay rebuilds positions too"
        (is (= (e/world run) (w/replay (store/read-events (:dir run)))))))
    (e/stop-run run)))

(deftest the-graph-can-change
  (let [run (village)
        world (e/world run)
        [world _] (perform run world "gm" :close-route {:from "square" :to "lisbon"})]
    (is (= "the route to lisbon is closed" (a/check (action run :move) world "duarte" {:to "lisbon"})))
    (is (nil? (space/path world "square" "lisbon")))
    (let [[world _] (perform run world "gm" :add-location {:id "porto" :name "Porto"})
          [world _] (perform run world "gm" :open-route {:from "square" :to "porto" :travel 3})]
      (is (= ["porto"] (space/path world "square" "porto")))
      (is (= 3 (:travel (space/route world "porto" "square")))))
    (testing "operator can place an agent directly"
      @(e/place! run "ana" "square")
      (is (= "square" (space/location-of (e/world run) "ana"))))
    (e/stop-run run)))

(deftest next-door-routes
  (let [run (e/start-run :hamlet {:llm :mock :test? true :run-id (str (gensym "space-hamlet-"))})
        world (e/world run)]
    (testing "travel 0 routes join an area; journeys don't"
      (is (= #{"hall" "bedroom" "porch"} (space/area world "bedroom")))
      (is (= #{"green" "shop"} (space/area world "shop")))
      (is (= #{"farm"} (space/area world "farm"))))
    (testing "paths and distances count only journeys"
      (is (= ["porch" "green" "farm"] (space/path world "hall" "farm")))
      (is (= 4 (space/distance world "hall" "farm")))
      (is (= 0 (space/distance world "hall" "bedroom")))
      (is (nil? (space/distance world "hall" "nowhere"))))
    (testing "moving next door is immediate"
      (let [[world [moved]] (perform run world "ben" :move {:to "porch"})]
        (is (= :agent/moved (:type moved)))
        (is (= "porch" (space/location-of world "ben")))))
    (testing "the GM can open next-door routes"
      (is (= {:from "shop" :to "farm" :travel 0}
             (:args (a/decode-args (action run :open-route) {:from "shop" :to "farm" :travel 0})))))
    (e/stop-run run)))

;; ---------------------------------------------------------------------------
;; Last seen

(defn- seen [world id] (get-in (prompt/agent-view (e/load-scenario :hamlet) world id) [:location :last-seen]))

(deftest last-seen
  (let [run (e/start-run :hamlet {:llm :mock :test? true :run-id (str (gensym "space-seen-"))})
        world (assoc (e/world run) :round 1)          ; Ada and Ben in the hall, 21:00
        [parted] (perform run world "ada" :move {:to "bedroom"})]
    (testing "nothing while they're together"
      (is (nil? (seen world "ada")))
      (is (nil? (seen world "ben"))))
    (testing "when Ada goes upstairs, Ben saw her go, and she remembers where she left him"
      (is (= [{:id "ada" :name "Ada" :place "Hall" :at "21:00" :went "Bedroom"}] (seen parted "ben")))
      (is (= [{:id "ben" :name "Ben" :place "Hall" :at "21:00"}] (seen parted "ada"))))
    (testing "together again, neither is listed; the record stays for next time"
      (let [[met] (perform run (assoc parted :round 2) "ben" :move {:to "bedroom"})]
        (is (nil? (seen met "ada")))
        (is (= "hall" (get-in met [:seen "ada" "ben" :place])))))
    (testing "a journey: the people left behind see where they set off for"
      (let [at-green (-> world
                         (w/apply-event {:type :agent/placed :data {:id "ada" :to "green"}})
                         (w/apply-event {:type :agent/placed :data {:id "ben" :to "green"}})
                         (assoc :round 3))
            [gone] (perform run at-green "ben" :move {:to "farm"})]
        (is (space/in-transit? gone "ben"))
        (is (= [{:id "ben" :name "Ben" :place "Green" :at "22:00" :went "Far farm"}] (seen gone "ada")))
        (is (= ["Green"] (map :place (seen gone "ben"))) "shown while travelling too")))
    (testing "the operator placing someone elsewhere: seen, but not where they went"
      (let [placed (w/apply-event world {:type :agent/placed :data {:id "ben" :to "shop"}})]
        (is (= [{:id "ben" :name "Ben" :place "Hall" :at "21:00"}] (seen placed "ada")))))
    (testing "most recent first, and the retired are left out"
      (let [[w1] (perform run parted "ben" :move {:to "porch"})
            w2 (w/apply-event w1 {:type :agent/retired :data {:id "ada" :reason "moved away"}})]
        (is (= ["ada"] (map :id (seen w1 "ben"))))
        (is (nil? (seen w2 "ben")))))
    (e/stop-run run))
  (testing "without a clock, the round"
    (let [run (village)
          world (e/world run)
          [a b] (take 2 (map :id (w/active-agents world)))
          here (space/location-of world a)
          other (some #(when (and (not= here %) (<= (:travel (space/route world here %)) 1)) %)
                      (keys (get-in world [:space :routes here])))
          world (w/apply-event world {:type :agent/placed :data {:id b :to here}})
          [moved] (perform run world a :move {:to other})]
      (is (= (:round world) (:round (first (get-in (prompt/agent-view (:scenario run) moved b) [:location :last-seen])))))
      (e/stop-run run))))
