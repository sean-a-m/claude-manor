(ns manor.steward-test
  "The nightly steward: selection, narrow requests, checked effects and replay."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [manor.engine :as e]
            [manor.engine.work :as work]
            [manor.io.llm :as llm]
            [manor.io.store :as store]
            [manor.io.worldfile :as wf]
            [manor.mechanics.items :as items]
            [manor.narrate :as n]
            [manor.world :as w]))

(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

(def config {:at "02:00" :idle-hours 6 :max-per-call 8})

(def supplies
  [{:id "pantry" :name "Pantry shelves" :desc "Stout shelves." :place "hall" :fixed? true :store {:keeps "food and utensils"}}
   {:id "loaf-sugar" :name "Loaf sugar" :desc "A loaf of sugar." :place "hall" :consumable? true}
   {:id "tin" :name "Dripping tin" :desc "A tin." :place "hall" :drawn-from "pantry"}
   {:id "carried" :name "Carried bun" :desc "A bun." :holder "ben" :consumable? true}
   {:id "closed" :name "Closed cupboard" :desc "A shut cupboard." :place "hall" :container :closed :fixed? true}
   {:id "hidden" :name "Hidden bun" :desc "A bun." :in "closed" :consumable? true}
   {:id "fixed" :name "Fixed food" :desc "Fixed." :place "hall" :fixed? true :consumable? true}
   {:id "open" :name "Open shelf" :desc "A shelf." :place "hall" :fixed? true :container :open}
   {:id "visible" :name "Visible bun" :desc "A bun." :in "open" :consumable? true}
   {:id "bedroom-food" :name "Bedroom food" :desc "A bun." :place "bedroom" :consumable? true}])

(defn- start [& [opts]]
  (e/start-run :hamlet (merge {:llm :mock :test? true :start "1904-10-15T20:00"
                                :run-id (str (gensym "steward-"))} opts)))

(defn- at-round! [run round]
  (e/emit! run [{:type :round/started :data {:round round} :visible-to :all}]))

(defn- say [args]
  {:tool-calls [{:id "s1" :name "steward" :arguments (json/write-value-as-string args)}]})

(deftest steward-selection-and-schedule
  (let [run (start)]
    (try
      (e/emit! run (items/init-events supplies))
      (at-round! run 13) ; exactly six hours later, 02:00
      (e/emit! run (items/init-events [{:id "fresh" :name "Fresh bun" :desc "A bun." :place "hall" :consumable? true}]))
      (let [world (e/world run)
            selected (items/steward-candidates world config)]
        (is (= {"bedroom" ["bedroom-food"] "hall" ["loaf-sugar" "tin" "visible"]}
               (update-vals selected #(mapv :id %))))
        (is (= ["loaf-sugar"] (map :id (get (items/steward-candidates world (assoc config :max-per-call 1)) "hall"))))
        (is (= "1904-10-16" (items/steward-day world config)))
        (is (nil? (items/steward-day (assoc world :round 1) config)) "skip the first day's already-past 02:00")
        (is (nil? (items/steward-day world nil)))
        (is (= "1904-10-16" (items/steward-day (assoc world :round 14) config)) "first round after at is also due")
        (is (= "1904-10-16" (items/steward-day (assoc world :round 1 :clock {:start "1904-10-16T02:00" :minutes-per-round 30}) config)))
        (is (nil? (items/steward-day (w/apply-event world {:type :items/steward-ran :data {:day "1904-10-16"}}) config))))
      (testing "taking, putting down, or finishing work protects handled things"
        (e/emit! run [{:type :item/taken :actor "ben" :data {:item "tin"}}])
        (e/emit! run [{:type :item/put-down :actor "ben" :data {:item "tin" :place "hall"}}])
        (e/emit! run [{:type :item/work-begun :data {:id "job-test" :actor "ben" :place "hall" :items ["loaf-sugar"]}}])
        (e/emit! run [(items/work-done (e/world run) (items/job (e/world run) "job-test") {:minutes 30} nil)])
        (is (= ["visible"] (map :id (get (items/steward-candidates (e/world run) config) "hall"))))
        (is (= 13 (:touched (items/item (e/world run) "loaf-sugar")))))
      (is (= (e/world run) (w/replay (e/events run))))
      (finally (e/stop-run run)))))

(deftest steward-checks-and-mock
  (let [run (start)]
    (try
      (e/emit! run (items/init-events supplies))
      (at-round! run 13)
      (let [world (e/world run)
            candidates (get (items/steward-candidates world config) "hall")
            check #(items/stewardship (say (assoc % :summary "Tidied.")) world "hall" candidates)]
        (testing "each candidate may be absorbed, used up, changed, or left; no other things may change"
          (doseq [args [{:absorbed [{:thing "clock" :into "pantry"}]}
                       {:absorbed [{:thing "tin" :into "closed"}]}
                       {:used-up ["hidden"]}
                       {:changes [{:thing "pantry" :aspect "stock" :value "full"}]}
                       {:changes [{:thing "hall" :aspect "cleanliness" :value "clean"}]}]]
            (is (:error (check args))))
          (let [verdict (:verdict (check {:used-up ["tin"] :fates {"tin" "worn out and discarded"}
                                        :absorbed [{:thing "loaf-sugar" :into "pantry"}]
                                        :changes [{:thing "visible" :aspect "state" :value "stale"}]
                                        :made [{:name "Impossible invention"}]}))
                event (items/stewarded world "hall" verdict)
                after (w/apply-event world event)]
            (is (= :gm (:visible-to event)))
            (is (not (contains? (:data event) :made)))
            (is (= "worn out and discarded" (get-in after [:items-gone "tin" :fate])))
            (is (= "put away in the Pantry shelves" (get-in after [:items-gone "loaf-sugar" :fate])))
            (is (= "stale" (get-in (items/item after "visible") [:aspects :state])))
            (is (some? (items/item after "hidden")))))
        (testing "mock absorbs into the first store, otherwise leaves candidates alone"
          (let [mock (items/mock-steward {:world world :place "hall" :candidates candidates})]
            (is (= (map :id candidates) (map :thing (get-in mock [:args :absorbed]))))
            (is (nil? (:error (check (:args mock))))))
          (is (empty? (get-in (items/mock-steward {:world world :place "bedroom" :candidates [(items/item world "bedroom-food")]}) [:args :absorbed]))))
        (testing "request contains only the candidates and local stores; no hidden ids or private context"
          (let [request (items/steward-request world "hall" candidates)
                user (get-in request [:messages 1 :content])
                tool (get-in request [:tools 0 :function :parameters :properties])]
            (is (not (contains? tool :made)))
            (is (str/includes? user "loaf-sugar"))
            (is (not (str/includes? user "hidden")))
            (is (not (str/includes? user "carried"))))))
      (finally (e/stop-run run)))))

(deftest steward-runs-once-per-night-and-replays
  (let [run (start)]
    (try
      (e/emit! run (items/init-events supplies))
      (let [run (assoc-in run [:scenario :items :steward] config)]
        (at-round! run 1)
        (work/run-steward! run)
        (is (not-any? #(= :items/steward-ran (:type %)) (e/events run)))
        (at-round! run 13)
        (work/run-steward! run)
        (work/run-steward! run)
        (at-round! run 14)
        (work/run-steward! run)
        (let [world (e/world run)
              markers (filter #(= :items/steward-ran (:type %)) (e/events run))
              events (filter #(= :items/stewarded (:type %)) (e/events run))
              calls (filter #(= "steward" (:agent %)) (store/read-llm (:dir run)))]
          (is (= ["1904-10-16"] (map #(get-in % [:data :day]) markers)))
          (is (= ["bedroom" "hall"] (map #(get-in % [:data :place]) events)))
          (is (= 2 (count calls)))
          (is (= 2 (count (filter #(and (= :llm/usage (:type %)) (= "steward" (:actor %))) (e/events run)))))
          (is (nil? (items/item world "loaf-sugar")))
          (is (some? (items/item world "bedroom-food")))
          (is (= world (w/replay (store/read-events (:dir run))))))
        (at-round! run 61) ; next 02:00
        (work/run-steward! run)
        (is (= 2 (count (filter #(= :items/steward-ran (:type %)) (e/events run))))))
      (finally (e/stop-run run)))))


(deftest steward-container-safety
  (let [run (start)]
    (try
      (e/emit! run (items/init-events (into supplies
                                          [{:id "bag" :name "Bag" :desc "A bag." :place "hall" :consumable? true :container :closed}
                                           {:id "bag-bun" :name "Secret bun" :desc "A bun." :in "bag" :consumable? true}])))
      (at-round! run 13)
      (let [world (e/world run)
            candidates (get (items/steward-candidates world config) "hall")
            mock (items/mock-steward {:world world :place "hall" :candidates candidates})
            check #(items/stewardship (say (assoc % :summary "Tidied.")) world "hall" candidates)]
        (is (some #(= "bag" (:id %)) candidates))
        (is (not-any? #(= "bag-bun" (:id %)) candidates))
        (is (not-any? #(= "bag" (:thing %)) (get-in mock [:args :absorbed])))
        (is (nil? (:error (check (:args mock)))))
        (is (:error (check {:absorbed [{:thing "bag" :into "pantry"}]})))
        (is (:error (check {:used-up ["bag"]}))))
      (finally (e/stop-run run)))))

(deftest steward-retries-and-counts-cost
  (let [attempts (atom 0)
        mock (llm/mock (fn [_]
                         (if (= 1 (swap! attempts inc))
                           {:tool :steward :args {:used-up ["hidden"] :summary "Invalid."}}
                           {:tool :steward :args {:used-up ["loaf-sugar"] :fates {"loaf-sugar" "eaten by the staff"}
                                                 :summary "The sugar was eaten."}})))
        run (start {:llm-impl (reify llm/LLM
                                (complete [_ request]
                                  (assoc-in (llm/complete mock request) [:usage :cost] 0.01)))})]
    (try
      (e/emit! run (items/init-events (remove #(= "bedroom-food" (:id %)) supplies)))
      (is (= "pantry" (:drawn-from (first (filter #(= "tin" (:id %))
                                                               (:items (wf/read-file (str (io/file (:dir run) "map.edn")))))))))
      (at-round! run 13)
      (work/run-steward! (assoc-in run [:scenario :items :steward] config))
      (is (= 2 @attempts))
      (is (= 0.02 (w/total-cost (e/world run))))
      (is (= "eaten by the staff" (get-in (e/world run) [:items-gone "loaf-sugar" :fate])))
      (is (= 2 (count (filter #(= "steward" (:agent %)) (store/read-llm (:dir run))))))
      (is (not-any? #(= :items/steward-failed (:type %)) (e/events run)))
      (is (not-any? #(= "loaf-sugar" (:id %)) (:items (wf/read-file (str (io/file (:dir run) "map.edn"))))))
      (is (= (e/world run) (w/replay (store/read-events (:dir run)))))
      (finally (e/stop-run run)))))
