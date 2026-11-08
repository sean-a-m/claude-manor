(ns manor.claudeworth-small-test
  "The small household: a baron and baroness with a new legacy and one
  butler in a shabby farmhouse they mean to build out. Checks the world file
  as designed and a whole mock day, all offline."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [manor.engine :as e]
            [manor.test-support :as support]
            [manor.io.store :as store]
            [manor.io.worldfile :as worldfile]
            [manor.mechanics.building :as building]
            [manor.mechanics.items :as items]
            [manor.mechanics.positions :as positions]
            [manor.ruleset.init :as ruleset-init]
            [manor.world :as w]))

(use-fixtures :once support/with-runs)

(def world-file "worlds/claudeworth-small.edn")

(defn- world [] (:world-file (worldfile/load-world world-file)))

(defn- start []
  (e/start-run :claudeworth-small {:llm :mock :offline? true :test? true
                                   :run-id (str (gensym "small-"))}))

(def household {"Lord Claudeworth" :baron "Lady Claudeworth" :baroness "Mr Fenwick" :butler})

(deftest the-small-household-is-the-designed-one
  (let [wf (world)
        house (filter #(= :house (:domain %)) (:locations wf))]
    (is (empty? (ruleset-init/problems wf)))
    (testing "three people in the household, each with a bedroom in the house"
      (let [members (filter #(= :house (:domain (first (filter (fn [l] (= (:bedroom %) (:id l))) (:locations wf)))))
                            (:agents wf))]
        (is (= household (into {} (map (juxt :name :role)) members)))))
    (testing "the house is small, and only the basics are there"
      (is (<= (count house) 15))
      (is (some #(= :kitchen (:kind %)) house))
      (is (not-any? #(= :dining-room (:kind %)) house)))
    (testing "the legacy covers the architect's grandest work several times over"
      (let [prices (:sizes (building/read-price-list (get-in wf [:meta :architect-prices])))]
        (is (= 30000 (get-in wf [:meta :starting-funds])))
        (is (< (* 5 (apply max (map :price prices))) 30000))))
    (testing "the household's roles have :abouts, and the village's people are here"
      (let [table (positions/table wf {})]
        (doseq [role (vals household)] (is (seq (:about (table role))))))
      (is (some #(= "Pugh" (:name %)) (:agents wf))))))

(deftest a-whole-mock-day-of-the-small-household
  (let [run (start)]
    (try
      (e/auto! run 48)
      (is (= :idle (e/wait-idle run 300000)))
      (let [w (e/world run)
            replies (mapcat :replies (store/read-llm (:dir run)))]
        (is (= 48 (:round w)))
        (is (empty? (filter #(= :action/forfeited (:type %)) (e/events run))))
        (is (not-any? #(re-find #"^(error:|rejected:)" (str (:content %))) replies))
        (is (= w (w/replay (store/read-events (:dir run)))))
        (is (contains? (into #{} (map :id (items/items-where w items/store?))) "larder-shelves"))
        (is (= 30000 (quot (get-in w [:state :accounts "household"]) 240))))
      (finally (e/stop-run run)))))
