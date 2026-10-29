(ns manor.scenarios-test
  "Every bundled scenario should run offline with its mock policy and replay
  to the same world. Add new scenarios to `bundled`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [manor.actions :as a]
            [manor.engine :as e]
            [manor.io.store :as store]
            [manor.mechanics.items :as items]
            [manor.mechanics.space :as space]
            [manor.scenarios.claudeworth :as cw]
            [manor.world :as w]))

(def bundled [:village :treasure :quiz :claudeworth :claudeworth-medium :lodge :dressing :burglary])

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

(deftest household-cast-options
  ;; Character options and permissions use fixed test content, not the live cast.
  (doseq [opts [{} {:leave-out ["nellie"]}]]
    (let [run (e/start-run :household
                           (merge {:llm :mock :offline? true :test? true :leave-out []
                                   :run-id (str (gensym "household-defaults-"))} opts))]
      (try
        (let [world (e/world run)]
          (is (= (empty? (:leave-out opts)) (some? (w/agent world "nellie"))))
          (doseq [agent (w/active-agents world) :when (not= :gm (:role agent))]
            (is (= :bedroom (:kind (space/location world (get-in world [:state :bedrooms (:id agent)]))))
                (:id agent))
            (is (contains? (cw/observe world (:id agent)) :household) (:id agent))
            (is (= #{:buy :commission}
                   (set (keep #(when (#{:buy :commission} (:name %)) (:name %))
                              (a/for-role (:actions run) (:role agent)))))
                (:id agent)))
          (is (= world (w/replay (store/read-events (:dir run))))))
        (finally (e/stop-run run)))))
  (is (empty? (cw/problems (:world-file (cw/init {:world-file "test/fixtures/household.edn"}))))))

(deftest household-containers
  ;; Exact contents and choreography belong to a frozen test-owned world.
  (let [run (e/start-run :household-containers {:llm :mock :offline? true :test? true
                                       :run-id (str (gensym "household-containers-"))})
        in (fn [world id] (:in (items/item world id)))]
    (try
      (let [world (e/world run)]
        (testing "the household containers and what's in them"
          (is (= {"china-cabinet" :open "sideboard" :closed "kitchen-dresser" :open "sugar-jar" :open}
                 (into {} (map (juxt :id :container)) (items/items-where world :container))))
          (is (= ["meissen-harlequin" "meissen-pug" "meissen-shepherdess"] (map :id (items/contents world "china-cabinet"))))
          (is (= ["canteen-of-cutlery" "silver-candlesticks" "table-linen"] (map :id (items/contents world "sideboard"))))
          (is (= ["blacking-brushes" "kettle" "saucepans"] (map :id (items/contents world "kitchen-dresser"))))
          (is (= "kitchen" (:place (items/item world "sugar-jar"))))
          (is (= {:state "empty"} (:aspects (items/item world "sugar-jar"))))
          (is (empty? (items/contents world "sugar-jar")))))
      (e/auto! run 12)
      (is (= :idle (e/wait-idle run 120000)))
      (let [world (e/world run)
            replies (mapcat :replies (store/read-llm (:dir run)))]
        (testing "the morning has no forfeits or bad replies"
          (is (empty? (filter #(= :action/forfeited (:type %)) (e/events run))))
          (is (not-any? #(re-find #"^(error:|rejected:)" (:content % "")) replies)))
        (testing "the countess opened the sideboard and found the candlesticks"
          (is (some #(re-find #"\"id\":\"silver-candlesticks\"" (:content % "")) replies)))
        (testing "Nellie put the candlesticks back and the brushes away"
          (is (= "sideboard" (in world "silver-candlesticks")))
          (is (= "kitchen-dresser" (in world "blacking-brushes")))
          (is (= [:item/taken :item/put-in :item/taken :item/put-in]
                 (map :type (filter #(and (= "nellie" (:actor %)) (#{:item/taken :item/put-in} (:type %)))
                                    (e/events run))))))
        (is (= world (w/replay (store/read-events (:dir run))))))
      (finally (e/stop-run run)))))

(deftest household-store-demonstration
  ;; Purchase choreography uses the test household; bundled content only gets smoke checks.
  (let [run (e/start-run :household {:llm :mock :offline? true :test? true
                                    :run-id (str (gensym "household-store-day-"))})]
    (try
      (let [world (e/world run)
            clerks (filter #(= :shopkeeper (:role %)) (w/active-agents world))]
        (is (seq clerks))
        (doseq [clerk clerks]
          (is (contains? (:services (space/location world (:location clerk))) :general-store))))
      (e/auto! run 47)
      (is (= :idle (e/wait-idle run 120000)))
      (let [world (e/world run) events (e/events run)
            bought (filter #(= :claudeworth/bought (:type %)) events)
            quoted (into {} (for [e events :when (= :claudeworth/goods-quoted (:type e))]
                              [(get-in e [:data :id]) (:data e)]))]
        (is (= 47 (:round world)))
        (is (seq bought))
        (doseq [purchase bought
                :let [offer (get quoted (get-in purchase [:data :quote-id]))]]
          (is (= (:actor purchase) (:buyer offer)))
          (is (= (get-in purchase [:data :price]) (:price offer))))
        (is (not-any? #(#{:action/forfeited :adjudication/failed} (:type %)) events))
        (is (not-any? #(re-find #"^(error:|rejected:)" (:content % ""))
                      (mapcat :replies (store/read-llm (:dir run)))))
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
          (is (map? (try (a/->tool action (e/world run)) (catch Exception ex (ex-message ex))))
              (str (name (:name action)) " should become a tool")))
        (e/stop-run run)))))

(deftest claudeworth-judged-mock-day-stays-quiet
  (let [run (e/start-run :claudeworth {:llm :mock :offline? true :test? true
                                      :run-id (str (gensym "claudeworth-activities-"))})]
    (try
      (let [world (e/world run)
            stores (items/items-where world items/store?)
            sleep (first (filter #(= :sleep (:name %)) (:actions run)))]
        (testing "every resident has a reachable sleeping place, including makeshift bedrooms"
          (doseq [agent (w/active-agents world) :when (not= :gm (:role agent))
                  :let [id (:id agent)
                        bed (get-in world [:state :bedrooms id])
                        at-bed (w/apply-event world {:type :agent/placed :data {:id id :to bed}})]]
            (is (= :bedroom (:kind (space/location world bed))) id)
            (is (contains? (space/area world (space/location-of world id)) bed) id)
            (is (nil? (a/check sleep at-bed id {:until "09:00"})) id)))
        (is (seq stores))
        (is (= {:at "02:00" :idle-hours 4 :max-per-call 8}
               (get-in run [:scenario :items :steward])))
        (is (every? (fn [store]
                      (some #(= (:id store) (:id %))
                            (items/stores-for world (:place store)))) stores)))
      (e/auto! run 48)
      (is (= :idle (e/wait-idle run 120000)))
      (let [world (e/world run)
            events (e/events run)
            calls (store/read-llm (:dir run))
            begun (into {} (for [event events :when (= :item/work-begun (:type event))]
                             [(get-in event [:data :id]) (:data event)]))
            pastimes (filter #(and (= :item/work-done (:type %))
                                   (empty? (:items (begun (get-in % [:data :job]))))) events)]
        (is (= 48 (:round world)))
        (testing "goods left at the shop are sent up by the shopkeeper and arrive at the carriage sweep"
          (let [sent (filter #(= :deliveries/sent-up (:type %)) events)
                ids (set (map :id (mapcat (comp :orders :data) sent)))]
            (is (seq sent))
            (is (some #(= "leave" (get-in % [:data :hand-over])) (filter #(= :claudeworth/bought (:type %)) events)))
            (is (every? #(contains? (:items-gone world) %) (mapcat #(map :item-id (get-in % [:data :orders])) sent)))
            (is (every? (set (mapcat (comp :orders :data) (filter #(= :deliveries/arrived (:type %)) events))) ids))
            (is (every? (set (keep :order-id (items/items-where world #(= "carriage-sweep" (:place %))))) ids)
                "and lies at the carriage sweep")))
        (is (seq pastimes))
        (is (every? #(every? empty? ((juxt :changes :used-up :made) (:data %))) pastimes))
        (is (not-any? #(#{:action/forfeited :item/judge-failed :items/steward-failed :adjudication/failed} (:type %)) events))
        (is (not-any? #(re-find #"^(error:|rejected:)" (:content % "")) (mapcat :replies calls)))
        (is (= world (w/replay (store/read-events (:dir run))))))
      (finally (e/stop-run run)))))


(deftest claudeworth-evening-mock-day-crosses-steward-time
  (let [run (e/start-run :claudeworth {:llm :mock :offline? true :test? true
                                      :start "1904-10-15T20:00"
                                      :run-id (str (gensym "claudeworth-steward-night-"))})]
    (try
      (e/auto! run 48)
      (is (= :idle (e/wait-idle run 120000)))
      (let [world (e/world run)
            events (e/events run)
            markers (filter #(= :items/steward-ran (:type %)) events)]
        (is (= 48 (:round world)))
        (is (= [[13 "1904-10-16"]] (map (juxt :round #(get-in % [:data :day])) markers)))
        (is (not-any? #(#{:action/forfeited :item/judge-failed :items/steward-failed :adjudication/failed} (:type %)) events))
        (is (not-any? #(re-find #"^(error:|rejected:)" (:content % ""))
                      (mapcat :replies (store/read-llm (:dir run)))))
        (is (= world (w/replay (store/read-events (:dir run))))))
      (finally (e/stop-run run)))))
