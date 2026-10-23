(ns manor.claudeworth-medium-test
  "The medium household: a baron's family and nine servants in a house that
  means to grow (see docs/household-1904-proposal.md). Checks the world file
  as the design describes it, and a whole mock day, all offline."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [manor.engine :as e]
            [manor.io.store :as store]
            [manor.io.worldfile :as worldfile]
            [manor.mechanics.items :as items]
            [manor.mechanics.positions :as positions]
            [manor.scenarios.claudeworth :as cw]
            [manor.scenarios.claudeworth.building :as building]
            [manor.world :as w]))

(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

(def world-file "worlds/claudeworth-medium.edn")

(defn- world [] (:world-file (worldfile/load-world world-file)))

(defn- start
  ([] (start {}))
  ([opts] (e/start-run :claudeworth-medium
                       (merge {:llm :mock :offline? true :test? true
                               :run-id (str (gensym "medium-"))} opts))))

(defn- entry [wf name] (first (filter #(= name (:name %)) (:agents wf))))
(defn- room-kind [wf id] (:kind (first (filter #(= id (:id %)) (:locations wf)))))

(def family
  "The design's family, by agent name and position."
  {"Lord Claudeworth" :baron "Lady Claudeworth" :baroness
   "Miss Claudeworth" :daughter-of-the-house "Master Claudeworth" :son-of-the-house})

(def staff
  "The design's nine servants: one man indoors where a bigger house keeps three."
  {"Mr Merrick" :butler-valet "Mrs Thorne" :cook "Miss Nash" :ladys-maid
   "Emily" :head-housemaid "Rose" :housemaid "Bessie" :kitchen-maid
   "Mr Ridley" :coachman "Mr Durrant" :head-gardener "Alf" :garden-lad})

(deftest the-medium-household-is-the-designed-one
  (let [wf (world)]
    (is (empty? (cw/problems wf)))
    (testing "every family member and servant holds the designed position and has a bedroom"
      (doseq [[name role] (merge family staff)]
        (is (= role (:role (entry wf name))) (str name " holds " role))
        (is (= :bedroom (room-kind wf (:bedroom (entry wf name)))) (str name "'s bedroom")))
      (let [table (positions/table wf {})]
        (doseq [role (vals (merge family staff))]
          (is (seq (:about (table role))) (str role " has an :about")))))
    (testing "a bathroom is still to be built, and the grown-up comforts are not there yet"
      (is (nil? (room-kind wf "bathroom")))
      (is (= :room (room-kind wf "box-room")))              ; the attic room to convert into beds
      (is (empty? (filter #(= :housekeeper (:role %)) (:agents wf)))))
    (testing "the son is in the world file but out of the run until he comes home"
      (let [run (start)]
        (try
          (is (nil? (w/agent (e/world run) "master-claudeworth")))
          (is (some? (w/agent (e/world run) "miss-claudeworth")))
          (is (= 12 (count (filter #(positions/household-member? (e/world run) (:id %))
                                   (w/active-agents (e/world run))))))
          (finally (e/stop-run run)))))
    (testing "the quarried money is under the grand price, so one commission cannot take the account"
      (let [prices (:sizes (building/read-price-list (get-in wf [:meta :architect-prices])))
            funds (get-in wf [:meta :starting-funds])]
        (is (= 2900 funds))
        (is (< funds (apply max (map :price prices))))
        (is (apply < (map :price prices)))))
    (testing "the staff eat in the kitchen, and the plate is locked in the butler's pantry"
      (let [run (start) stores (into #{} (map :id (items/items-where (e/world run) items/store?)))]
        (try
          (is (contains? stores "pantry-shelves"))
          (is (contains? stores "plate-safe"))
          (is (contains? stores "housemaids-cupboard"))
          (is (contains? stores "linen-press"))
          (finally (e/stop-run run)))))))

(deftest a-whole-mock-day-of-the-medium-household
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
        (testing "the day is everybody's own: no plan drawn, no money spent"
          (is (empty? (get-in w [:state :plans])))
          (is (= 2900 (quot (get-in w [:state :household :funds]) 240)))))
      (finally (e/stop-run run)))))

(deftest the-village-and-the-abbey-are-the-claudeworth-ones
  (let [wf (world)
        claudeworth (:world-file (worldfile/load-world "worlds/claudeworth.edn"))
        names (fn [w domain] (into {} (comp (filter #(= domain (:domain %))) (map (juxt :id :name)))
                                  (:locations w)))]
    (doseq [domain [:village :abbey]]
      (let [theirs (names claudeworth domain) here (names wf domain)]
        (is (= theirs (select-keys here (keys theirs)))
            (str "the " (name domain) " keeps the :claudeworth world's places"))
        (is (= (case domain
                 :village {"labourers-cottage" "Labourer's cottage"}   ; where the garden lad lives
                 :abbey {})
               (apply dissoc here (keys theirs)))
            (str "nothing else is added to the " (name domain)))))
    (is (every? #(entry wf %) ["Hobbs" "Briggs" "Miss Ellis" "Turner" "Polly" "Mrs Bell"
                               "Carter" "Rudge" "Webb" "Mrs Dobbins" "Father Anselm" "Pugh"])
        "the village's residents are in the world file")))
