(ns manor.lodge-test
  "The small bundled world exercises production household mechanics for a day."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [manor.engine :as e]
            [manor.items :as items]
            [manor.hiring :as hiring]
            [manor.prompt :as prompt]
            [manor.scenarios.building :as building]
            [manor.scenarios.claudeworth :as cw]
            [manor.space :as space]
            [manor.store :as store]
            [manor.world :as w]
            [manor.worldfile :as worldfile]))

(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

(deftest worlds-with-and-without-an-office
  (let [wf (:world-file (worldfile/load-world "worlds/lodge.edn"))
        office {:id "office" :services #{:architect}}]
    (is (empty? (cw/problems wf)))
    (is (= ["at least one architect service needs :hours"]
           (building/problems (update wf :locations conj office))))
    (is (empty? (building/problems (update wf :locations conj (assoc office :hours ["09:00" "18:00"]))))))
  (let [run (e/start-run :claudeworth {:llm :mock :offline? true :test? true
                                      :run-id (str (gensym "lodge-office-regression-"))})]
    (try
      (let [rules (cw/rules (e/world run))]
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
          (is (= "1904-10-15T08:00" (get-in world [:clock :start])))
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
            (is (not (.contains (cw/rules world) (str "`" (name tool) "`")))))
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
                      :conversation/started :claudeworth/goods-quoted :claudeworth/bought
                      :item/put-in :item/work-done :items/steward-ran]]
          (is (some #(= type (:type %)) events) (name type)))
        (is (some #(= "judge" (:agent %)) calls))
        (is (= "just seen to" (get-in (items/item world "candlesticks") [:aspects :cleanliness])))
        (is (not-any? #(#{:action/forfeited :item/judge-failed :items/steward-failed :adjudication/failed} (:type %)) events))
        (is (not-any? #(re-find #"^(error:|rejected:)" (:content % "")) (mapcat :replies calls)))
        (is (= world (w/replay (store/read-events (:dir run))))))
      (finally (e/stop-run run)))))
