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
            [manor.mechanics.space :as space]
            [manor.mechanics.visits :as visits]
            [manor.ruleset.init :as ruleset-init]
            [manor.mechanics.building :as building]
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
    (is (empty? (ruleset-init/problems wf)))
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
    (testing "the son is in the run but away at school until he comes home"
      (let [run (start)]
        (try
          (is (= :away (:status (w/agent (e/world run) "master-claudeworth"))))
          (is (nil? (:location (w/agent (e/world run) "master-claudeworth"))))
          (is (= "sons-room" (get-in (e/world run) [:state :bedrooms "master-claudeworth"])))
          (is (some? (w/agent (e/world run) "miss-claudeworth")))
          (is (= (dec (count (merge family staff)))                     ; all but the son, who is away
                 (count (filter #(positions/household-member? (e/world run) (:id %))
                                (w/active-agents (e/world run))))))
          (testing "he comes home from Eton on 20 December, an authored visit his parents know of"
            (is (= {:guest "master-claudeworth" :host nil :by :authored :at "1904-12-20T14:00" :bedroom "sons-room"
                    :known-to #{"lord-claudeworth" "lady-claudeworth"} :status :scheduled}
                   (select-keys (get-in (e/world run) [:state :visits "visit-1"])
                                [:guest :host :by :at :bedroom :known-to :status]))))
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
          (is (= 2900 (quot (get-in w [:state :accounts "household"]) 240)))))
      (finally (e/stop-run run)))))

(deftest the-village-and-the-abbey-keep-the-claudeworth-ones
  ;; Read from both world files rather than listed here, so either village can
  ;; gain or lose places and people without this test changing; the medium
  ;; village may have more than the small one.
  (let [wf (world)
        claudeworth (:world-file (worldfile/load-world "worlds/claudeworth.edn"))
        domains #{:village :abbey}
        domain-of (fn [w id] (:domain (first (filter #(= id (:id %)) (:locations w)))))
        names (fn [w domain] (into {} (comp (filter #(= domain (:domain %))) (map (juxt :id :name)))
                                  (:locations w)))]
    (doseq [domain domains]
      (let [theirs (names claudeworth domain) here (names wf domain)]
        (is (= theirs (select-keys here (keys theirs)))
            (str "the " (name domain) " keeps the :claudeworth world's places"))))
    (testing "the :claudeworth world's villagers and monks are here too, in the same posts"
      (let [residents (filter #(domains (domain-of claudeworth (:bedroom %))) (:agents claudeworth))]
        (is (seq residents))
        (doseq [{:keys [name role]} residents]
          (is (= role (:role (entry wf name))) name))))))

(deftest authored-homecoming-respects-leave-out
  (testing "leaving out the guest drops his authored visit"
    (let [run (start {:leave-out ["master-claudeworth"]})]
      (try
        (is (nil? (w/agent (e/world run) "master-claudeworth")))
        (is (empty? (visits/visits (e/world run))))
        (is (= (e/world run) (w/replay (store/read-events (:dir run)))))
        (finally (e/stop-run run)))))
  (testing "leaving out either or both parents removes only their audience ids"
    (doseq [left-out [["lord-claudeworth"] ["lady-claudeworth"] ["lord-claudeworth" "lady-claudeworth"]]]
      (let [run (start {:leave-out left-out})]
        (try
          (is (= (apply disj #{"lord-claudeworth" "lady-claudeworth"} left-out)
                 (set (:known-to (visits/visit (e/world run) "visit-1")))))
          (is (= :scheduled (:status (visits/visit (e/world run) "visit-1"))))
          (is (= (e/world run) (w/replay (store/read-events (:dir run)))))
          (finally (e/stop-run run)))))))

(deftest a-short-mock-homecoming
  (let [run (start {:start "1904-12-20T13:00"})]
    (try
      (e/auto! run 3)
      (is (= :idle (e/wait-idle run 60000)))
      (let [world (e/world run)]
        (is (= 3 (:round world)))
        (is (= "carriage-sweep" (space/location-of world "master-claudeworth")))
        (is (= :here (:status (visits/visit world "visit-1"))))
        (is (= world (w/replay (store/read-events (:dir run))))))
      (finally (e/stop-run run)))))

(deftest starting-after-homecoming-skips-it-without-reconstructing-a-stay
  (let [run (start {:start "1904-12-25T08:00"})]
    (try
      (is (empty? (visits/visits (e/world run))))
      (is (= :away (:status (w/agent (e/world run) "master-claudeworth"))))
      (is (nil? (space/location-of (e/world run) "master-claudeworth")))
      (is (= (e/world run) (w/replay (store/read-events (:dir run)))))
      (finally (e/stop-run run)))))
