(ns manor.lodge-test
  "The small bundled world exercises production household mechanics for a day."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [manor.engine :as e]
            [manor.io.store :as store]
            [manor.io.worldfile :as worldfile]
            [manor.mechanics.hiring :as hiring]
            [manor.mechanics.items :as items]
            [manor.mechanics.space :as space]
            [manor.mechanics.visits :as visits]
            [manor.prompt :as prompt]
            [manor.ruleset.init :as ruleset-init]
            [manor.ruleset.mock :as mock]
            [manor.ruleset.rules :as rules]
            [manor.mechanics.building :as building]
            [manor.world :as w]))

(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

(deftest worlds-with-and-without-an-office
  (let [wf (:world-file (worldfile/load-world "worlds/lodge.edn"))
        office {:id "office" :services #{:architect}}
        priced (assoc-in wf [:meta :architect-prices] "architect/prices-1904.edn")]
    (is (empty? (ruleset-init/problems wf)))
    (is (= ["at least one architect service needs :hours"]
           (building/problems (update priced :locations conj office))))
    (is (= [":meta :architect-prices must name a price list, such as \"architect/prices-1904.edn\", when there is an architect's office"]
           (building/problems (update wf :locations conj (assoc office :hours ["09:00" "18:00"])))))
    (is (empty? (building/problems (update priced :locations conj (assoc office :hours ["09:00" "18:00"]))))))
  (let [run (e/start-run :claudeworth {:llm :mock :offline? true :test? true
                                      :run-id (str (gensym "lodge-office-regression-"))})]
    (try
      (let [rules (rules/rules (e/world run))]
        (doseq [text ["Purchases and commissions are paid" "Building is arranged at the architect's office"
                      "`consult-references`" "`price-list`" "`draw-plans`" "`show-plans`"
                      "Only one room is built at a time" "10% on commissions for plans he drew."]]
          (is (.contains rules text) text)))
      (finally (e/stop-run run)))))

(deftest lodge-mock-day-and-replay
  (let [run (e/start-run :lodge {:llm :mock :offline? true :test? true
                                :run-id (str (gensym "lodge-day-"))})]
    (try
      (let [world (e/world run)
            agents (w/active-agents world)
            locations (set (keys (get-in world [:space :locations])))
            excluded #{:consult-references :price-list :draw-plans :show-plans :commission}]
        (testing "a small, walkable household with a vacancy and a cost guard"
          (is (= 3 (count agents)))
          (is (= 0.10 (get-in run [:opts :max-cost])))
          (is (= (get-in (worldfile/load-world "worlds/lodge.edn") [:world-file :meta :start])
                 (get-in world [:clock :start])))
          (doseq [agent agents]
            (is (= locations (space/area world (:location agent))))
            (is (seq (:situation agent)))
            (is (= :bedroom (:kind (space/location world (get-in world [:state :bedrooms (:id agent)]))))))
          (is (some #(and (= "maids-room" (:id %)) (empty? (:sleeps %)))
                    (get-in (prompt/agent-view (:scenario run) world "mrs-ashby") [:household :bedrooms])))
          (is (empty? (:notes (w/agent world "mrs-ashby")))))
        (testing "tools and briefs do not advertise building without an office"
          (is (not-any? #(excluded (:name %)) (:actions run)))
          (doseq [tool excluded]
            (is (not (.contains (rules/rules world) (str "`" (name tool) "`")))))
          (is (every? (set (map :name (:actions run))) [:quote-goods :buy :spend-time :find :look]))))
      (e/auto! run 48)
      (is (= :idle (e/wait-idle run 120000)))
      (let [world (e/world run)
            events (e/events run)
            calls (store/read-llm (:dir run))]
        (is (= 48 (:round world)))
        (let [p (first (vals (hiring/pool world))) id (:agent-id p)]
          (is (= :engaged (:status p)))
          (is (= :housemaid (:role (w/agent world id))))
          (is (= "maids-room" (get-in world [:state :bedrooms id])))
          (is (= "Sunday afternoons free." (get-in world [:state :posts id :conditions]))))
        (doseq [type [:people/recommended :people/sent-for :people/arrived :people/offered
                      :conversation/started :shop/goods-quoted :shop/bought
                      :item/put-in :item/work-done :items/steward-ran]]
          (is (some #(= type (:type %)) events) (name type)))
        (is (some #(= "judge" (:agent %)) calls))
        (is (= "just seen to" (get-in (items/item world "candlesticks") [:aspects :cleanliness])))
        (is (not-any? #(#{:action/forfeited :item/judge-failed :items/steward-failed :adjudication/failed} (:type %)) events))
        (is (not-any? #(re-find #"^(error:|rejected:)" (:content % "")) (mapcat :replies calls)))
        (is (= world (w/replay (store/read-events (:dir run))))))
      (finally (e/stop-run run)))))

(deftest invitations-without-a-calendar
  (let [day {"mrs-ashby" {"08:00" [[:walk {:to "general-store"}]
                                   [:invite {:by "in-person" :who "ward" :date "1904-10-15" :time "10:00" :stay "to tea"}]
                                   [:walk {:to "parlour"}]]}
             "ward" {"09:30" [[:walk {:to "front-gate"}]]}}
        run (with-redefs [e/load-scenario (constantly (assoc (e/load-scenario :lodge)
                                                             :mock-policy #(mock/scripted-policy day %)))]
              (e/start-run :lodge {:llm :mock :offline? true :test? true :gm :off
                                   :run-id (str (gensym "lodge-visit-"))}))]
    (try
      (is (nil? (:calendar (e/world run))))
      (is (= "Ashby Lodge" (:house (visits/config (e/world run)))))
      (is (.contains (rules/rules (e/world run)) "`invite`"))
      (e/auto! run 6)
      (is (= :idle (e/wait-idle run 60000)))
      (let [world (e/world run) events (e/events run) v (visits/visit world "visit-1")]
        (is (= {:guest "ward" :host "mrs-ashby" :by :in-person :told? true}
               (select-keys v [:guest :host :by :told?])))
        (is (= :here (:status v)))
        (is (some #(= :visits/invited (:type %)) events))
        (is (not-any? #(#{:action/forfeited :run/paused} (:type %)) events))
        (is (= world (w/replay (store/read-events (:dir run))))))
      (finally (e/stop-run run)))))
