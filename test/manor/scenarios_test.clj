(ns manor.scenarios-test
  "Every bundled scenario should run offline with its mock policy and replay
  to the same world. Add new scenarios to `bundled`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [manor.actions :as a]
            [manor.engine :as e]
            [manor.items :as items]
            [manor.scenarios.claudeworth :as cw]
            [manor.space :as space]
            [manor.store :as store]
            [manor.world :as w]))

(def bundled [:village :treasure :quiz :claudeworth])

(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

(deftest bundled-scenarios-run-offline
  (doseq [k bundled]
    (testing (name k)
      (let [run (e/start-run k {:llm :mock :offline? true :test? true
                                :run-id (str (gensym (str "smoke-" (name k) "-")))})]
        (e/auto! run 8)
        (is (= :idle (e/wait-idle run 60000)))
        (is (pos? (:round (e/world run))))
        (is (empty? (filter #(= :action/forfeited (:type %)) (e/events run)))
            "the mock policy should only choose valid actions")
        (is (= (e/world run) (w/replay (store/read-events (:dir run)))))
        (e/stop-run run)))))

(deftest claudeworth-default-cast
  ;; This is intentionally a bundled-content test, unlike the fixed household
  ;; mechanics tests. Adding residents must not require editing a cast snapshot.
  (doseq [opts [{} {:housemaid? false} {:housemaid? true}]]
    (let [run (e/start-run :claudeworth
                           (merge {:llm :mock :offline? true :test? true
                                   :run-id (str (gensym "claudeworth-defaults-"))} opts))]
      (try
        (let [world (e/world run)]
          (is (= (not (false? (:housemaid? opts))) (some? (w/agent world "nellie"))))
          (doseq [agent (w/active-agents world) :when (not= :gm (:role agent))]
            (is (= :bedroom (:kind (space/location world (get-in world [:state :bedrooms (:id agent)]))))
                (:id agent)))
          (is (= world (w/replay (store/read-events (:dir run))))))
        (finally (e/stop-run run)))))
  (is (empty? (cw/problems (:world-file (cw/init {}))))))

(deftest claudeworth-containers
  ;; Bundled content: the house's containers, and the mock morning that uses them.
  (let [run (e/start-run :claudeworth {:llm :mock :offline? true :test? true
                                       :run-id (str (gensym "claudeworth-containers-"))})
        in (fn [world id] (:in (items/item world id)))]
    (try
      (let [world (e/world run)]
        (testing "the cabinet, the sideboard and the dresser, and what's in them"
          (is (= {"china-cabinet" :open "sideboard" :closed "kitchen-dresser" :open}
                 (into {} (map (juxt :id :container)) (items/items-where world :container))))
          (is (= ["meissen-harlequin" "meissen-pug" "meissen-shepherdess"] (map :id (items/contents world "china-cabinet"))))
          (is (= ["canteen-of-cutlery" "silver-candlesticks" "table-linen"] (map :id (items/contents world "sideboard"))))
          (is (= ["blacking-brushes" "kettle" "saucepans"] (map :id (items/contents world "kitchen-dresser"))))))
      (e/auto! run 12)
      (is (= :idle (e/wait-idle run 120000)))
      (let [world (e/world run)
            replies (mapcat :replies (store/read-llm (:dir run)))]
        (testing "the morning has no forfeits or bad replies"
          (is (empty? (filter #(= :action/forfeited (:type %)) (e/events run))))
          (is (not-any? #(re-find #"^(error:|rejected:)" (:content % "")) replies)))
        (testing "the countess opened the sideboard and found the candlesticks"
          (is (some #(re-find #"\"id\" : \"silver-candlesticks\"" (:content % "")) replies)))
        (testing "Nellie put the candlesticks back and the brushes away"
          (is (= "sideboard" (in world "silver-candlesticks")))
          (is (= "kitchen-dresser" (in world "blacking-brushes")))
          (is (= [:item/taken :item/put-in :item/taken :item/put-in]
                 (map :type (filter #(and (= "nellie" (:actor %)) (#{:item/taken :item/put-in} (:type %)))
                                    (e/events run))))))
        (is (= world (w/replay (store/read-events (:dir run))))))
      (finally (e/stop-run run)))))

(deftest every-action-becomes-a-tool
  ;; The mock GM never builds its tools, so a bad schema (e.g. an empty
  ;; :enum) would otherwise only show up in a real run.
  (doseq [k bundled]
    (testing (name k)
      (let [run (e/start-run k {:llm :mock :offline? true :test? true
                                :run-id (str (gensym (str "tools-" (name k) "-")))})]
        (doseq [action (:actions run)]
          (is (map? (try (a/->tool action) (catch Exception ex (ex-message ex))))
              (str (name (:name action)) " should become a tool")))
        (e/stop-run run)))))
