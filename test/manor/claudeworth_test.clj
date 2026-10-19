(ns manor.claudeworth-test
  (:require [clojure.edn :as edn]
            [clojure.java.shell :as shell]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [manor.actions :as a]
            [manor.builtins :as b]
            [manor.clock :as clock]
            [manor.engine :as e]
            [manor.items :as items]
            [manor.prompt :as prompt]
            [manor.llm :as llm]
            [manor.mcp :as mcp]
            [manor.money :as money]
            [manor.narrate :as narrate]
            [manor.scenarios.building :as building]
            [manor.scenarios.claudeworth :as cw]
            [manor.scenarios.household :as fixture]
            [manor.space :as space]
            [manor.store :as store]
            [manor.world :as w]
            [manor.worldfile :as wf]))

;; All runs here use {:test? true}, so they live under test-runs/.
(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

;; Mechanics tests never inherit the bundled world's content or cast defaults.
(defn- start [& [opts]]
  (e/start-run :household (merge {:llm :mock :offline? true :test? true :run-id (str (gensym "claudeworth-"))}
                                   opts)))

(defn- rounds! [run n]
  (e/auto! run n)
  (is (= :idle (e/wait-idle run 120000)))
  run)

(defn- of-type [run t] (filter #(= t (:type %)) (e/events run)))

(defn- by [id evs] (filter #(= id (:actor %)) evs))

(defn- rounds-called
  "Rounds in which `id` made a model call."
  [run id]
  (set (map :round (by id (of-type run :llm/usage)))))

(defn- update-entry
  "Change exactly one named fixture entry; a missing target is a setup error."
  [entries k value f]
  (assert (= 1 (count (filter #(= value (get % k)) entries)))
          (str "expected one fixture entry " k " = " value))
  (mapv #(if (= value (get % k)) (f %) %) entries))

(def house #{"carriage-sweep" "entrance-hall" "drawing-room" "study" "dining-room" "kitchen" "maids-room"
             "staircase" "gallery" "earls-bedroom" "countess-bedroom" "secretarys-room"})

(deftest the-world
  (let [{:keys [world-file]} (wf/load-world fixture/world-file)
        run (start)
        world (e/world run)]
    (testing "the world file loads with no problems, the scenario's own included"
      (is (empty? (wf/problems world-file)))
      (is (empty? (cw/problems world-file))))
    (testing "everyone's bedroom is a bedroom"
      (is (= {"lord-claudeworth" "earls-bedroom" "lady-claudeworth" "countess-bedroom"
              "hale" "secretarys-room" "pugh" "architects-lodgings" "briggs" "maids-room"}
             (get-in world [:state :bedrooms])))
      (is (every? #(= :bedroom (:kind (space/location world %))) (vals (get-in world [:state :bedrooms])))))
    (testing "the house and the village are areas; journeys join them"
      (is (= house (space/area world "study")))
      (is (= #{"village-green" "general-store" "architects-office" "architects-lodgings"}
             (space/area world "architects-office")))
      (is (= 1 (space/distance world "study" "architects-office"))))
    (testing "the housemaid is left out, and her room is in the house all the same"
      (is (nil? (w/agent world "nellie")))
      (is (not (contains? (get-in world [:state :bedrooms]) "nellie")))
      (is (contains? (space/area world "kitchen") "maids-room")))
    (testing "everyone but Mr Pugh starts in their bedroom at 08:00"
      (is (= "earls-bedroom" (space/location-of world "lord-claudeworth")))
      (is (= "architects-office" (space/location-of world "pugh")))
      (is (= "Saturday 15 October 1904, 08:00" (:time (prompt/agent-view (:scenario run) world "hale")))))
    (testing "bad bedrooms and opening hours are named"
      (let [closed-store (fn [ls] (mapv #(if (= "general-store" (:id %)) (assoc % :hours ["8am" "18:00"]) %) ls))
            ps (str/join "\n" (cw/problems (-> world-file
                                               (update :agents update-entry :name "Lord Claudeworth" #(assoc % :bedroom "study"))
                                               (update :locations closed-store))))]
        (is (str/includes? ps "agent \"Lord Claudeworth\": :bedroom \"study\" is not a bedroom"))
        (is (str/includes? ps "location \"general-store\": :hours must be two times")))
      (let [ps (str/join "\n" (cw/problems (-> world-file (update :meta dissoc :setting) (update :roles dissoc :earl))))]
        (is (str/includes? ps ":meta :setting must be a paragraph"))
        (is (str/includes? ps "position :earl has no :about paragraph: add it to :roles or the catalogue"))))
    (e/stop-run run)))

;; ---------------------------------------------------------------------------
;; Actions, applied purely

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

(def draft {:name "Library" :floor "ground" :size "large"
            :desc "A quiet library." :connects-to ["study"]})

(defn- drafted [world actor args]
  (reduce w/apply-event world
          (building/store-draft world actor args
                                (building/draft-stub (building/draft-args world actor args) {}))))

(deftest shop-and-building-validation
  (let [run (start) world (assoc (e/world run) :round 3)
        office (-> world (at "lord-claudeworth" "architects-office"))
        clerk (update office :agents dissoc "pugh")
        shop (at world "hale" "general-store")
        check (fn [world actor tool args] (a/check (action run tool) world actor args))]
    (testing "office, hours, architect, client, floor and connections"
      (is (str/includes? (check world "lord-claudeworth" :draw-plans draft) "go to an architect's office"))
      (is (str/includes? (check (assoc office :round 1) "pugh" :draw-plans draft) "is closed"))
      (is (= "Pugh draws the plans here; ask him" (check office "lord-claudeworth" :draw-plans draft)))
      (is (= "Pugh draws the plans here; ask him"
             (check (w/apply-event office (b/occupy office "pugh" "drawing" 10)) "lord-claudeworth" :draw-plans draft)))
      (is (str/includes? (check office "pugh" :draw-plans (assoc draft :for "hale")) "must be present"))
      (is (nil? (check clerk "lord-claudeworth" :draw-plans draft)))
      (is (nil? (check clerk "lord-claudeworth" :draw-plans (assoc draft :floor "Ground floor"))))
      (is (= :first (:floor (get-in (drafted clerk "lord-claudeworth" (assoc draft :floor "First Floor"))
                                   [:state :plans "library"]))))
      (is (str/includes? (check clerk "lord-claudeworth" :draw-plans (assoc draft :for "hale")) "only for you"))
      (is (= "floor must be one of: first, ground" (check clerk "lord-claudeworth" :draw-plans (assoc draft :floor "attic"))))
      (is (str/includes? (check clerk "lord-claudeworth" :draw-plans (assoc draft :connects-to ["abbey-church"])) "not a room of the house"))
      (is (:error (a/decode-args (action run :draw-plans) (assoc draft :size "huge")))))
    (testing "holders, roles, fees and repeat commissions"
      (let [world (drafted office "pugh" (assoc draft :for "lord-claudeworth"))
            [commissioned [event]] (perform run world "lord-claudeworth" :commission {:plan-id "library"})
            due (get-in event [:data :due])
            completed (reduce w/apply-event (assoc commissioned :round due) ((:tick building/construction) (assoc commissioned :round due) {}))
            [second-world [second-event]] (perform run completed "lord-claudeworth" :commission {:plan-id "library"})]
        (is (= 51 due))
        (is (= (money/pounds->pence 120) (get-in commissioned [:state :purses "pugh"])))
        (is (= "library-2" (get-in second-event [:data :room])))
        (is (= (money/pounds->pence 600) (get-in second-world [:state :household :funds])))
        (is (str/includes? (check commissioned "lord-claudeworth" :commission {:plan-id "library"}) "already being built"))
        (is (str/includes? (check (assoc-in world [:state :household :funds] (money/pounds->pence 900)) "lord-claudeworth" :commission {:plan-id "library"}) "would cost £1200; the household has £900"))
        (is (str/includes? (check world "hale" :show-plans {:plan-id "library"}) "not seen those plans"))
        (is (some #(= :commission (:name %)) (a/for-role (:actions run) :architect)))
        (is (contains? (cw/observe world "pugh") :household))
        (is (= "0d" (get-in (cw/observe world "pugh") [:you :purse])))
        (is (empty? (:plans (cw/observe world "hale"))))
        (is (not (str/includes? (narrate/describe event (narrate/names world)) "fee")))
        (let [shared (at world "hale" "architects-office")
              [shown] (perform run shared "pugh" :show-plans {:plan-id "library"})]
          (is (contains? (get-in shown [:state :plans "library" :holders]) "hale")))))
    (testing "only one room is built at a time, whatever the plan"
      (let [world (-> office
                      (drafted "pugh" (assoc draft :for "lord-claudeworth"))
                      (drafted "pugh" (assoc draft :name "Saloon" :size "grand" :desc "A fine saloon."
                                             :connects-to ["entrance-hall"] :for "lord-claudeworth")))
            [commissioned _] (perform run world "lord-claudeworth" :commission {:plan-id "library"})
            refusal (check commissioned "lord-claudeworth" :commission {:plan-id "saloon"})]
        (is (= "library" (get-in commissioned [:state :works 0 :plan-id])))
        (is (str/includes? refusal "already being built"))
        (is (str/includes? refusal "Only one room is built at a time"))
        (is (str/includes? refusal "commission the Saloon then"))))
    (testing "the office marker, not the location id, selects the service"
      (let [moved (-> clerk (update-in [:space :locations "architects-office"] dissoc :services)
                      (assoc-in [:space :locations "study" :services] #{:architect})
                      (assoc-in [:space :locations "study" :hours] ["09:00" "18:00"])
                      (at "lord-claudeworth" "study"))]
        (is (nil? (check moved "lord-claudeworth" :draw-plans draft)))))
    (e/stop-run run)))

(defn bb-available? []
  (try (zero? (:exit (shell/sh "bb" "-e" ""))) (catch Exception _ false)))

(deftest architect-server-contract
  (if-not (bb-available?) (println "Skipping architect MCP contracts: bb unavailable")
    (let [run (start)
          conn (mcp/connect :architect (:architect building/mcp-servers) {:run-id (:id run) :run-dir (str (:dir run))})
          call (fn [tool args] (:value (mcp/call-tool conn tool args 20000)))
          args {:house [{:id "study" :floor "ground"}] :taken ["library"] :request draft}]
      (try
        (is (= #{"consult_references" "price_list" "draft_plans"} (set (map :name (mcp/list-tools conn)))))
        (is (= (edn/read-string (slurp "mcp-servers/architect/prices.edn"))
               (call "price_list" {}) ((:architect/prices building/offline-stubs) {} {})))
        (is (= (building/draft-stub args {}) (call "draft_plans" args)))
        (is (= "library-2" (get-in (call "draft_plans" args) [:plan :id])))
        (doseq [request [(assoc draft :name " ") (assoc draft :size "huge") (assoc draft :connects-to ["nowhere"])]]
          (is (thrown? clojure.lang.ExceptionInfo (call "draft_plans" (assoc args :request request)))))
        (let [result (call "consult_references" {:query "library"}) text (pr-str result)]
          (is (seq (:matches result)))
          (is (<= (reduce + (map (comp count :text) (:matches result))) 2500))
          (is (not (re-find #"precedence|staffing_tiers|reports_to|:access" text))))
        (let [result (call "consult_references" {:query "portraits"}) text (pr-str result)]
          (is (str/includes? text "Main stair hung with family portraits"))
          (is (not (str/includes? text "Servants do not use"))))
        (finally (mcp/close! conn) (e/stop-run run))))))

(declare moves-to)

(deftest full-building-slice
  (doseq [opts (cond-> [{:offline? true} {:offline? true :architect? false}]
                (bb-available?) (conj {:offline? false}))]
    (let [run (start (merge {:build-rounds 6} opts))]
      (rounds! run 7)
      (let [event (first (of-type run :claudeworth/commissioned))
            due (get-in event [:data :due])]
        (is (some? event) (pr-str opts))
        (is (= (money/pounds->pence 1200) (get-in event [:data :price])))
        (is (= 13 due))
        (is (= (money/pounds->pence 1800) (get-in (e/world run) [:state :household :funds])))
        (is (= (if (false? (:architect? opts)) {} {"pugh" (money/pounds->pence 120)}) (get-in (e/world run) [:state :purses])))
        (is (nil? (space/location (e/world run) "library")))
        (rounds! run 5)
        (is (nil? (space/location (e/world run) "library")))
        (rounds! run 1)
        (let [world (e/world run) loc (space/location world "library")]
          (is (= :ground (:floor loc)))
          (is (= {:cleanliness "new, smelling of plaster and paint"} (:aspects loc)))
          (is (= (:aspects loc)
                 (get-in (first (filter #(= "library" (get-in % [:data :location :id]))
                                        (of-type run :space/location-added)))
                         [:data :location :aspects])))
          (is (not (contains? loc :connects-to)))
          (is (contains? (space/area world "study") "library"))
          (is (nil? (a/check (action run :walk) (at world "hale" "study") "hale" {:to "library"})))
          (is (some #(and (= "library" (get-in % [:data :from])) (= "study" (get-in % [:data :to]))
                          (zero? (get-in % [:data :travel])) (= :gm (:visible-to %))) (of-type run :space/route-opened)))
          (let [exported (edn/read-string (slurp (io/file (:dir run) "map.edn")))
                room (first (filter #(= "library" (:id %)) (:locations exported)))]
            (is (= (:aspects loc) (:aspects room)))
            (is (empty? (wf/problems exported))))
          (is (= (- (money/pounds->pence 1800) 18) (get-in world [:state :household :funds]))))
        (rounds! run 34)
        (is (seq (moves-to run "lord-claudeworth" "library")))
        (is (empty? (of-type run :action/forfeited)))
        (is (= (e/world run) (w/replay (e/events run))))
        (if (:offline? opts)
          (is (empty? @(get-in run [:mcp :conns])))
          (is (contains? @(get-in run [:mcp :conns]) :architect))))
      (e/stop-run run))))

(deftest odd-room-and-untrusted-drafts
  (let [run (start {:architect? false :build-rounds 1})
        world (-> (e/world run) (assoc :round 3) (at "lord-claudeworth" "architects-office"))
        args (assoc draft :name "Bathroom" :connects-to ["dining-room" "gallery"])
        result (building/draft-stub (building/draft-args world "lord-claudeworth" args) {})]
    (is (nil? (a/check (action run :draw-plans) world "lord-claudeworth" args)))
    (let [drawn (drafted world "lord-claudeworth" args)
          [commissioned [event]] (perform run drawn "lord-claudeworth" :commission {:plan-id "bathroom"})
          completed (reduce w/apply-event commissioned ((:tick building/construction) (assoc commissioned :round 4) {}))]
      (is (zero? (get-in event [:data :fee])))
      (is (nil? (get-in event [:data :payee])))
      (is (= #{"dining-room" "gallery"} (set (map :to (space/exits completed "bathroom"))))))
    (doseq [bad ["truncated" nil (assoc-in result [:plan :id] "study") (assoc-in result [:plan :id] "Bad ID")
                 (assoc-in result [:plan :floor] "attic") (assoc-in result [:plan :desc] "")
                 (assoc-in result [:plan :connects-to] ["unknown"]) (assoc result :price -100)
                 (assoc result :build-days 0)]]
      (let [events (building/store-draft world "lord-claudeworth" args bad)]
        (is (= [:claudeworth/draft-refused] (mapv :type events)))
        (is (= world (reduce w/apply-event world events)))))
    (e/stop-run run)))

(deftest private-query-memory-and-bad-bridge
  (let [run (rounds! (start {:build-rounds 6}) 5)
        world (e/world run)
        text (fn [id] (pr-str (prompt/agent-messages (:scenario run) world (e/events run) id {})))]
    (is (str/includes? (text "pugh") "quiet reading table"))
    (doseq [id ["lord-claudeworth" "lady-claudeworth" "hale"]]
      (is (not (str/includes? (text id) "quiet reading table"))))
    (rounds! run 1)
    (is (= 5 (:round (first (of-type run :claudeworth/consulted)))) )
    (is (= 6 (:round (first (of-type run :claudeworth/plans-drawn)))))
    (e/stop-run run))
  (let [run (rounds! (start {:architect? false :bridge-stubs {:architect/draft (fn [_ _] "bad draft")}}) 6)]
    (is (empty? (get-in (e/world run) [:state :plans])))
    (is (= #{"lord-claudeworth"} (:visible-to (first (of-type run :claudeworth/draft-refused)))))
    (e/stop-run run)))

(deftest building-world-file-checks
  (let [source (wf/read-file fixture/world-file)]
    ;; Insertion/reordering must not change which actor a validation case exercises.
    (doseq [world-file [source (update source :agents #(vec (reverse %)))]]
      (is (empty? (cw/problems world-file)))
      (doseq [[bad diagnostic]
              [[(update world-file :locations update-entry :id "general-store" #(dissoc % :store-profile)) ":store-profile"]
               [(assoc-in world-file [:meta :starting-funds] -1) "starting-funds"]
               [(update world-file :locations update-entry :id "architects-office" #(dissoc % :hours)) "architect"]
               [(update world-file :locations update-entry :id "architects-office" #(assoc % :services ["architect"])) ":services"]]]
        (is (str/includes? (str/join "\n" (cw/problems bad)) diagnostic)))
      (doseq [[field value] [[:from "9am"] [:at "nowhere"] [:doing " "]]]
        (let [change #(assoc-in % [:routine 0 field] value)
              bad (update world-file :agents update-entry :name "Pugh" change)
              ps (cw/problems bad)]
          (is (= 1 (count ps)))
          (is (str/includes? (first ps) "agent \"Pugh\": :routine entry")))))))

(deftest walking-and-travelling
  (let [run (start)
        world (e/world run)]
    (testing "walk goes anywhere in the house at once and doesn't end the turn"
      (is (= :free (a/kind (action run :walk))))
      (let [[world' [moved]] (perform run world "hale" :walk {:to "study"})]
        (is (= "study" (space/location-of world' "hale")))
        (is (= :agent/moved (:type moved)))))
    (testing "walk won't go to the village"
      (is (= "Architect's office is not within walking distance of here; use travel"
             (a/check (action run :walk) world "hale" {:to "architects-office"})))
      (is (= "village is not a place. Use a place id: carriage-sweep, countess-bedroom, dining-room, drawing-room, earls-bedroom, entrance-hall; to go to the village, use travel"
             (a/check (action run :walk) world "hale" {:to "village"})))
      (is (= "cellar is not a place. Use a place id: carriage-sweep, countess-bedroom, dining-room, drawing-room, earls-bedroom, entrance-hall"
             (a/check (action run :walk) world "hale" {:to "cellar"}))))
    (testing "travel won't go within the house"
      (is (= "Study is close by; use walk, which doesn't use up your turn"
             (a/check (action run :travel) world "hale" {:to "study"})))
      (is (= "you are already in the house; use walk to go to a particular place, e.g. carriage-sweep, countess-bedroom, dining-room, drawing-room, earls-bedroom, entrance-hall"
             (a/check (action run :travel) world "hale" {:to "house"}))))
    (testing "travel to an unknown place lists places beyond walking distance, and the area names"
      (is (= "london is not a place. Use a place id: abbey-church, abbey-gatehouse, architects-lodgings, architects-office, general-store, village-green, or an area name: abbey, house, village"
             (a/check (action run :travel) world "hale" {:to "london"}))))
    (testing "travel reaches the architect's office in one turn, seen at both ends"
      (is (= :final (a/kind (action run :travel))))
      (let [[world' [moved]] (perform run world "hale" :travel {:to "architects-office"})]
        (is (= "architects-office" (space/location-of world' "hale")))
        (is (= #{"hale" "pugh"} (:visible-to moved)))))
    (testing "travel to an area name goes to its nearest place"
      (is (= "village-green" (space/location-of (first (perform run world "hale" :travel {:to "village"})) "hale")))
      (is (= "abbey-gatehouse" (space/location-of (first (perform run world "hale" :travel {:to "abbey"})) "hale")))
      (is (= "carriage-sweep" (space/location-of (first (perform run world "pugh" :travel {:to "house"})) "pugh"))))
    (testing "journeys in the view: the nearest place of each other area, minutes and arrival"
      (is (= [{:to "abbey" :place "abbey-gatehouse" :minutes 30 :arrive "08:30"}
              {:to "village" :place "village-green" :minutes 30 :arrive "08:30"}]
             (:journeys (prompt/agent-view (:scenario run) world "hale"))))
      (is (= ["abbey" "house"] (map :to (:journeys (prompt/agent-view (:scenario run) world "pugh"))))))
    (e/stop-run run)))

(deftest walking-shows-the-new-place
  (let [run (start)
        world (-> (e/world run) (assoc :round 9) (at "lady-claudeworth" "dining-room"))   ; 12:00
        [arrived] (perform run world "hale" :walk {:to "dining-room"})
        reply (cw/arrival arrived "hale")]
    (testing "the place, who is there and what they carry, and the things there"
      (is (= "Dining room" (get-in reply [:here :name])))
      (is (= ["family-portrait" "silver-candlesticks"] (map :id (get-in reply [:here :items]))))
      (is (= [{:id "lady-claudeworth" :name "Lady Claudeworth" :role :countess}] (:people-here reply))))
    (testing "nobody there is said outright"
      (let [[alone] (perform run world "hale" :walk {:to "kitchen"})]
        (is (= [] (:people-here (cw/arrival alone "hale"))))))
    (testing "opening hours where the place has them"
      (let [[shop] (perform run (at world "hale" "village-green") "hale" :walk {:to "general-store"})]
        (is (= {:open "08:00-18:00" :open-now? true} (select-keys (:here (cw/arrival shop "hale")) [:open :open-now?])))))
    (testing "it's walk's reply"
      (is (= reply ((:reply (action run :walk)) arrived "hale" {:to "dining-room"}))))
    (e/stop-run run)))

(deftest calling-out
  (let [run (start)
        world (assoc (e/world run) :round 1)              ; 08:00, everyone in their bedrooms; Pugh at the office
        busy (w/apply-event world (b/occupy world "hale" "copying letters" 5))
        call (action run :call)]
    (testing "free, and it shares the one utterance a turn with say and whisper"
      (is (= :free (a/kind call)))
      (is (= :speech (a/limit-key call))))
    (testing "everyone within walking distance hears it, wherever they are; not the village"
      (let [[_ [e]] (perform run world "lord-claudeworth" :call {:text "Is anyone about?"})]
        (is (= #{"lord-claudeworth" "lady-claudeworth" "hale"} (:visible-to e)))
        (is (= "Lord Claudeworth calls from the earl's bedroom: \"Is anyone about?\""
               (narrate/describe e (narrate/names world))))))
    (testing "naming someone busy but awake interrupts them, as rouse would"
      (let [[called [e interrupted]] (perform run busy "lord-claudeworth" :call {:text "Hale!" :to "hale"})]
        (is (= "Lord Claudeworth calls for Hale from the earl's bedroom: \"Hale!\"" (narrate/describe e (narrate/names world))))
        (is (= :agent/interrupted (:type interrupted)))
        (is (= "lord-claudeworth" (:interrupted-by (w/busy called "hale"))))))
    (testing "but it doesn't wake a sleeper, or reach someone out of earshot"
      (let [asleep (w/apply-event world (b/occupy world "hale" "asleep" 5))
            away (w/apply-event (at world "hale" "village-green") (b/occupy world "hale" "shopping" 5))]
        (doseq [w [asleep away]]
          (is (= 1 (count (second (perform run w "lord-claudeworth" :call {:text "Hale!" :to "hale"}))))))
        (is (not (contains? (:visible-to (first (second (perform run away "lord-claudeworth" :call {:text "Hale!" :to "hale"}))))
                            "hale")))))
    (testing "refusals"
      (is (= "no one has the id nobody" (a/check call world "hale" {:text "Hello?" :to "nobody"})))
      (is (= "you can't call for yourself" (a/check call world "hale" {:text "Hale!" :to "hale"}))))
    (testing "the rules explain it, and last-seen"
      (is (str/includes? (cw/rules world) "`call` is heard everywhere within walking distance"))
      (is (str/includes? (cw/rules world) "`last-seen` in your view"))
      (is (str/includes? (cw/rules world) "with `say`, `whisper` or `call`")))
    (e/stop-run run)))

(deftest sleeping-pastimes-and-rousing
  (let [run (start)
        world (assoc (e/world run) :round 1)]
    (testing "sleep only in a bedroom; the reason names your own"
      (let [[in-study] (perform run world "hale" :walk {:to "study"})]
        (is (= "go to a bedroom first; yours is Secretary's room (secretarys-room)"
               (a/check (action run :sleep) in-study "hale" {:until "22:00"})))))
    (testing "sleep at most 12 hours"
      (is (re-find #"more than 12 hours" (a/check (action run :sleep) world "hale" {:until "07:00"})))
      (is (re-find #"time of day" (a/check (action run :sleep) world "hale" {:until "seven"})))
      (let [[world' [slept]] (perform run world "hale" :sleep {:until "12:00"})]
        (is (= {:activity "asleep" :until 9 :asleep? true} (:data slept)))
        (is (= {:activity "asleep" :until 9 :asleep? true} (w/busy world' "hale")))))
    (testing "spend-time occupies the right number of rounds"
      (let [{:keys [args]} (a/decode-args (action run :spend-time) "{\"doing\": \"writing letters\", \"hours\": 1.5}")
            [world' _] (perform run world "hale" :spend-time args)]
        (is (= {:activity "writing letters" :until 4} (dissoc (w/busy world' "hale") :job)))))
    (testing "rouse interrupts someone busy in the same room, and nobody else"
      (let [asleep (w/apply-event world (b/occupy world "lord-claudeworth" "asleep" 20))
            [together] (perform run asleep "hale" :walk {:to "earls-bedroom"})
            [woken [freed]] (perform run together "hale" :rouse {:who "lord-claudeworth"})]
        (is (= :free (a/kind (action run :rouse))))
        (is (= "hale" (get-in freed [:data :by])))
        (is (= "hale" (:interrupted-by (w/busy woken "lord-claudeworth"))))
        (is (= "lord-claudeworth is not here" (a/check (action run :rouse) asleep "hale" {:who "lord-claudeworth"})))
        (is (= "Lady Claudeworth is not asleep or busy"
               (a/check (action run :rouse) (first (perform run world "hale" :walk {:to "countess-bedroom"}))
                        "hale" {:who "lady-claudeworth"})))))
    (e/stop-run run)))

;; ---------------------------------------------------------------------------
;; The scripted day

(defn- moves-to [run id loc] (filter #(= loc (get-in % [:data :to])) (by id (of-type run :agent/moved))))

(defn- sleeps [run id] (filter #(= "asleep" (get-in % [:data :activity])) (by id (of-type run :agent/occupied))))

(deftest the-mock-day
  (let [run (rounds! (start) 47)
        world (e/world run)
        evs (e/events run)
        calls (store/read-llm (:dir run))]
    (testing "a whole day to 07:00 Sunday with no forfeits or bad replies, and replay gives the same world"
      (is (= 47 (:round world)))
      (is (empty? (of-type run :action/forfeited)))
      (is (not-any? #(re-find #"^(error:|rejected:)" (:content % "")) (mapcat :replies calls)))
      (is (= world (w/replay (store/read-events (:dir run))))))
    (testing "at breakfast the countess draws the earl into a conversation; he answers and she ends it"
      (let [[started] (of-type run :conversation/started)
            id (get-in started [:data :conversation])
            in-it (filter #(= id (get-in % [:data :conversation])) evs)]
        (is (= ["lady-claudeworth" "lord-claudeworth"] (get-in started [:data :members])))
        (is (= 1 (:round started)))
        (is (= [["lady-claudeworth" "Enlarge it? Then I expect to be consulted on every room, Claudeworth."]
                ["lord-claudeworth" "Every room, my dear. I would not dream of choosing a wallpaper without you."]]
               (for [e in-it :when (= :agent/said (:type e))] [(:actor e) (get-in e [:data :text])])))
        (is (= :ended (get-in (last in-it) [:data :reason])))
        (is (= 1 (count (of-type run :conversation/started))))))
    (testing "the countess buys a feather duster on her way home, and keeps it while there is no maid"
      (let [bought (first (by "lady-claudeworth" (of-type run :claudeworth/bought)))]
        (is (= {:item "feather duster" :qty 1 :price 18 :location "general-store"}
               (select-keys (:data bought) [:item :qty :price :location])))
        (is (= "16:30" (clock/short-label (:clock world) (:round bought) (:round bought))))
        (is (= "lady-claudeworth" (:holder (items/item world "feather-duster"))))
        (is (empty? (of-type run :item/given)))
        (is (every? #(empty? (get-in % [:data :items])) (of-type run :item/work-begun)))))
    (testing "the countess's look reply shows the drawing room and the dusty cabinet"
      (let [[call] (filter #(some (fn [tc] (= "look" (get-in tc [:function :name])))
                                  (get-in % [:response :tool_calls])) calls)
            seen (json/read-value (:content (first (:replies call))) json/keyword-keys-object-mapper)]
        (is (= "lady-claudeworth" (:agent call)))
        (is (= "Drawing room" (get-in seen [:place :name])))
        (is (= {:cleanliness "dusty, the glass smeared"} (:aspects (first (filter #(= "china-cabinet" (:id %)) (:items seen))))))))
    (testing "the countess carries the candlesticks from the dining room to the drawing room"
      (is (= {:item "silver-candlesticks" :from-place "dining-room"}
             (:data (first (by "lady-claudeworth" (of-type run :item/taken))))))
      (is (= {:item "silver-candlesticks" :place "drawing-room"}
             (:data (first (by "lady-claudeworth" (of-type run :item/put-down))))))
      (is (= "drawing-room" (:place (items/item world "silver-candlesticks")))))
    (testing "the earl visits Mr Pugh in office hours (rounds 3-20) and speaks to him"
      (is (some #(<= 3 (:round %) 20) (moves-to run "lord-claudeworth" "architects-office")))
      (let [said (filter #(= "architects-office" (get-in % [:data :at])) (by "lord-claudeworth" (of-type run :agent/said)))]
        (is (seq said))
        (is (every? #(w/visible-to? % "pugh") said))
        (testing "where nobody at home hears it"
          (is (not-any? #(or (w/visible-to? % "lady-claudeworth") (w/visible-to? % "hale")) said))
          (let [prompt (str/join "\n" (map :content (prompt/agent-messages (:scenario run) world evs "lady-claudeworth" {:event-window 1000})))]
            (is (str/includes? prompt "I think he is our man"))
            (is (not (str/includes? prompt (get-in (first said) [:data :text]))))))
        (testing "and Mr Pugh replies"
          (is (some #(= "architects-office" (get-in % [:data :at])) (by "pugh" (of-type run :agent/said)))))))
    (testing "the countess visits the abbey church"
      (is (seq (moves-to run "lady-claudeworth" "abbey-church"))))
    (testing "everyone is asleep by 23:00 and makes no model call until they wake"
      (doseq [id ["lord-claudeworth" "lady-claudeworth" "hale" "pugh"]
              :let [{slept :round {:keys [until]} :data} (first (sleeps run id))
                    freed (first (filter #(> (:round %) slept) (by id (of-type run :agent/freed))))
                    woke (or (:round freed) until (inc (:round world)))]]
        (is (<= slept 31) id)
        (is (= #{} (set (filter #(< slept % woke) (rounds-called run id)))) id)))
    (testing "Mr Pugh is out of play until the earl comes to the office, and again once the village is empty"
      (let [arrived (:round (first (moves-to run "lord-claudeworth" "architects-office")))
            called (rounds-called run "pugh")]
        (is (= (inc arrived) (apply min called)))
        (is (< (count called) 6))
        (is (= {:activity "asleep" :hibernating? true} (w/busy world "pugh")))
        (is (= "architects-lodgings" (space/location-of world "pugh")))))
    (testing "Hale wakes at 06:30 (round 46), rouses the earl, and the earl acts at 07:00"
      (is (= 46 (:until (:data (first (sleeps run "hale"))))))
      (is (some #(and (= 46 (:round %)) (= "hale" (get-in % [:data :by]))) (by "lord-claudeworth" (of-type run :agent/interrupted))))
      (is (contains? (rounds-called run "lord-claudeworth") 47)))
    (testing "the run keeps its starting world and its live map"
      (is (.exists (io/file (:dir run) "world-start.edn")))
      (is (.exists (io/file (:dir run) "map.edn"))))
    (e/stop-run run)))

(deftest hibernation
  (let [run (start)
        world (assoc (e/world run) :round 5)              ; 10:00, everyone at home
        hib #(cw/hibernate % "pugh")
        changes #(map :type (b/hibernations cw/hibernate %))]
    (testing "alone in the village, Mr Pugh's routine says where he is: at his drawing board in office hours"
      (is (= {:activity "at his drawing board" :location "architects-office"} (hib world))))
    (testing "in his lodgings in the evening; the night's entry holds until breakfast"
      (is (= "reading by the fire" (:activity (hib (assoc world :round 23)))))
      (is (= "asleep" (:activity (hib (assoc world :round 46))))))
    (testing "someone at his office, or on the way there, keeps him in play"
      (is (nil? (hib (at world "hale" "architects-office"))))
      (is (nil? (hib (assoc-in world [:agents "hale" :travel] {:from "study" :to "architects-office" :arrives 7})))))
    (testing "and so does someone where he is, though his routine has him elsewhere"
      (is (nil? (hib (-> world (at "pugh" "village-green") (at "hale" "village-green"))))))
    (testing "but people elsewhere in the village don't"
      (is (some? (hib (at world "hale" "general-store"))))
      (is (some? (hib (assoc-in world [:agents "hale" :travel] {:from "study" :to "village-green" :arrives 7})))))
    (testing "away from the village, anyone in the area he's visiting keeps him: he isn't spirited away from the house"
      (is (nil? (hib (at world "pugh" "dining-room"))))
      (is (some? (hib (-> world (at "pugh" "dining-room") (at "lord-claudeworth" "abbey-church")
                          (at "lady-claudeworth" "abbey-church") (at "hale" "abbey-church"))))))
    (testing "someone hibernating isn't company"
      (let [hale-there (at world "hale" "architects-office")]
        (is (some? (hib (w/apply-event hale-there (b/hibernate hale-there "hale" "copying plans")))))))
    (let [lodgings (at world "pugh" "architects-lodgings")
          evs (b/hibernations cw/hibernate lodgings)
          out (reduce w/apply-event lodgings evs)]
      (testing "the engine takes him to his routine's place and occupies him there, with no end of its own"
        (is (= [:agent/moved :agent/occupied] (map :type evs)))
        (is (= "architects-office" (space/location-of out "pugh")))
        (is (= {:activity "at his drawing board" :hibernating? true} (w/busy out "pugh")))
        (is (= "Pugh is at his drawing board." (narrate/describe (second evs) (narrate/names out)))))
      (testing "and does nothing more until something changes"
        (is (empty? (changes out))))
      (testing "his routine moves on: home for the evening"
        (is (= [:agent/moved :agent/occupied] (changes (assoc out :round 21)))))
      (testing "company at the office wakes him; a customer at the store doesn't"
        (is (= [:agent/freed] (changes (at out "hale" "architects-office"))))
        (is (empty? (changes (at out "hale" "general-store")))))
      (testing "rouse works on him, and the engine leaves him to his turn; his view has no end time"
        (let [visited (at out "hale" "architects-office")
              roused (w/apply-event visited (b/interrupt visited "pugh" "hale"))]
          (is (empty? (changes roused)))
          (is (= {:activity "at his drawing board" :interrupted-by "Hale"}
                 (get-in (prompt/agent-view (:scenario run) roused "pugh") [:you :busy])))
          (is (= "Pugh carries on (at his drawing board)." (narrate/describe (b/resume roused "pugh") (narrate/names roused)))))))
    (testing "a human seat, a traveller, or someone busy on their own account never hibernates"
      (is (empty? (changes (assoc-in world [:agents "pugh" :controller] :human))))
      (is (empty? (changes (assoc-in world [:agents "pugh" :travel] {:from "study" :to "architects-office" :arrives 7}))))
      (is (empty? (changes (w/apply-event world (b/occupy world "pugh" "lunching" 7))))))
    (e/stop-run run)))

(deftest a-late-start
  (let [run (start {:start "1904-10-15T21:00"})
        c (:clock (e/world run))]
    (testing "the day starts at 21:00 with everyone where the world file puts them"
      (is (= "Saturday 15 October 1904, 21:00" (:time (prompt/agent-view (:scenario run) (e/world run) "hale"))))
      (is (= "secretarys-room" (space/location-of (e/world run) "hale"))))
    (testing "sleeping until 07:00 from 21:00 takes 20 rounds"
      (let [[_ [slept]] (perform run (assoc (e/world run) :round 1) "hale" :sleep {:until "07:00"})]
        (is (= 21 (get-in slept [:data :until])))
        (is (= "Sunday 16 October 1904, 07:00" (clock/label c 21)))))
    (testing "a scripted sleep away from your bedroom goes there first: a walk, or a travel from another area"
      (let [world (assoc (e/world run) :round 3)
            ctx {:world world :agent-id "pugh" :role :architect :used []}]
        (is (= {:tool :walk :args {:to "architects-lodgings"}} (fixture/mock-policy ctx)))
        (is (= {:tool :sleep :args {:until "07:30"}}
               (fixture/mock-policy (assoc ctx :world (assoc-in world [:agents "pugh" :location] "architects-lodgings")
                                      :used ["walk"]))))
        (is (= {:tool :travel :args {:to "architects-lodgings"}}
               (fixture/mock-policy (assoc ctx :world (assoc-in world [:agents "pugh" :location] "study")))))))
    (testing "the scripted evening follows the clock: Hale sleeps at 22:00 and rouses the earl at 06:30"
      (rounds! run 20)
      (is (= 3 (:round (first (sleeps run "hale")))))
      (is (= 4 (:round (first (sleeps run "lord-claudeworth")))))
      (is (some #(and (= 20 (:round %)) (= "hale" (get-in % [:data :by]))) (by "lord-claudeworth" (of-type run :agent/interrupted))))
      (is (= (e/world run) (w/replay (store/read-events (:dir run))))))
    (testing "Mr Pugh, alone in the village, is out of play: his routine takes him home, and to sleep at 22:00"
      (is (empty? (of-type run :action/forfeited)))
      (is (= 3 (:round (first (sleeps run "pugh")))))
      (is (= "architects-lodgings" (space/location-of (e/world run) "pugh"))))
    (e/stop-run run)))

(deftest without-the-architect
  (let [run (rounds! (start {:architect? false}) 47)
        world (e/world run)]
    (is (nil? (w/agent world "pugh")))
    (is (not (contains? (get-in world [:state :bedrooms]) "pugh")))
    (is (empty? (of-type run :action/forfeited)))
    (is (= world (w/replay (store/read-events (:dir run)))))
    (e/stop-run run)))

;; ---------------------------------------------------------------------------
;; Items

(deftest items-in-claudeworth
  (let [run (start)
        world (assoc (e/world run) :round 9)              ; 12:00, the store is open
        shop (at world "hale" "general-store")]
    (testing "the starting items, with holders and agent owners as ids"
      (is (= {:holder "lord-claudeworth" :owner "lord-claudeworth"}
             (select-keys (items/item world "pocket-watch") [:holder :owner])))
      (is (= {:holder "hale" :owner "hale"} (select-keys (items/item world "pocket-notebook") [:holder :owner])))
      (is (= #{"piano" "china-cabinet"} (set (map :id (items/items-at world "drawing-room")))))
      (is (every? #(= "household" (:owner %)) (items/items-where world :place))))
    (testing "one home per fact: what became an item is gone from the rooms' descriptions"
      (doseq [[loc word] [["entrance-hall" "stag"] ["drawing-room" "piano"] ["dining-room" "portrait"]
                          ["carriage-sweep" "motor car"] ["study" "ledgers"]]]
        (is (not (str/includes? (str/lower-case (:desc (space/location world loc))) word)) loc)))
    (testing "Hale's view lists what he carries, and the shop says to ask its clerk"
      (is (= [{:id "pocket-notebook" :name "Pocket notebook" :aspects {:state "half full"}}]
             (get-in (prompt/agent-view (:scenario run) world "hale") [:you :carrying])))
      (is (str/includes? (get-in (cw/observe shop "hale") [:here :service]) "shopkeeper"))
      (is (not (contains? (:here (cw/observe shop "hale")) :sells))))
    (testing "look in the drawing room: its detail, the furniture, and the earl's watch"
      (let [room (-> world (at "hale" "drawing-room") (at "lord-claudeworth" "drawing-room"))
            seen (items/look room "hale")]
        (is (= "Drawing room" (get-in seen [:place :name])))
        (is (str/starts-with? (get-in seen [:place :detail]) "Faded rose wallpaper"))
        (is (= [["china-cabinet" "household" true {:cleanliness "dusty, the glass smeared"}]
                ["piano" "household" true {:repair "a little out of tune"}]]
               (map (juxt :id :owner :fixed? :aspects) (:items seen))))
        (is (= ["pocket-watch"]
               (->> (:people seen) (filter #(= "Lord Claudeworth" (:name %))) first :carrying (map :id))))
        (is (not (contains? (get-in (prompt/agent-view (:scenario run) room "hale") [:location :here]) :detail)))))
    (testing "a few rooms have a :detail"
      (is (every? #(:detail (space/location world %)) ["drawing-room" "study" "general-store"])))
    (testing "the rules say the item actions are free, and what look shows"
      (is (str/includes? (cw/rules world) "`look` shows them in detail, with their descriptions, owners and aspects"))
      (is (str/includes? (cw/rules world) "The room's own aspects (its floor, hearth and air) and the stores in scope are under `here` in your view."))
      (is (str/includes? (cw/rules world) "`look`, `open`, `take`, `put-in`, `put-down` and `give` don't end your turn")))
    (testing "the rules and the judge both take everyday things as to hand"
      (let [judge-brief ((get-in (:scenario run) [:items :judge-brief]) world)]
        (is (str/includes? (cw/rules world) "everyday things (cloths, paper, pans, salt, matches and the like) can be assumed to be to hand"))
        (is (str/starts-with? judge-brief (get-in world [:state :setting])))
        (is (str/includes? judge-brief "are to hand unless what you're shown says otherwise"))))
    (e/stop-run run))
  (testing "leaving Mr Pugh out leaves out what he holds"
    (let [with-pugh (update (wf/read-file fixture/world-file) :items conj
                            {:id "set-square" :name "Set square" :holder "Pugh" :aspects {:state "true"} :desc "A brass set square."})
          file-name (str (gensym "pugh-items-") ".edn")
          _ (store/write-text! store/test-runs-root file-name (wf/->edn-string with-pugh))
          source (str (io/file store/test-runs-root file-name))
          r1 (start {:world-file source})
          r2 (start {:world-file source :architect? false})]
      (is (= "pugh" (:holder (items/item (e/world r1) "set-square"))))
      (is (nil? (items/item (e/world r2) "set-square")))
      (is (some? (items/item (e/world r2) "pocket-watch")))
      (e/stop-run r1)
      (e/stop-run r2))))

(deftest the-housemaid
  (let [run (rounds! (start {:housemaid? true}) 47)
        world (e/world run)
        evs (e/events run)
        calls (store/read-llm (:dir run))]
    (testing "Nellie is in the run, with her own room and access to the household account"
      (is (some? (w/agent world "nellie")))
      (is (= :housemaid (:role (w/agent world "nellie"))))
      (is (= "maids-room" (get-in world [:state :bedrooms "nellie"])))
      (is (= "maids-room" (space/location-of world "nellie")))
      (is (contains? (cw/observe world "nellie") :household)))
    (testing "the day is free of forfeits and bad replies, and replay gives the same world"
      (is (= 47 (:round world)))
      (is (empty? (of-type run :action/forfeited)))
      (is (not-any? #(re-find #"^(error:|rejected:)" (:content % "")) (mapcat :replies calls)))
      (is (= world (w/replay (store/read-events (:dir run))))))
    (testing "she works through the house, judged when each job ends"
      (let [begun (by "nellie" (of-type run :item/work-begun))
            done (by "nellie" (of-type run :item/work-done))]
        (is (= [[3 5 ["family-portrait"]] [5 8 ["silver-candlesticks"]] [8 12 ["kitchen-range"]]
                [12 14 ["stags-head"]] [21 23 ["china-cabinet"]]]
               (mapv (fn [e] [(get-in e [:data :started]) (get-in e [:data :due]) (get-in e [:data :items])]) begun)))
        (is (= (mapv #(get-in % [:data :id]) begun) (mapv #(get-in % [:data :job]) done)))
        (is (every? #(seq (get-in % [:data :changes])) done))
        (is (every? #(= {:aspect :cleanliness :to "just seen to"} (select-keys (get-in % [:data :changes 0]) [:aspect :to])) done))
        (is (= 5 (count (filter #(and (= "judge" (:agent %))
                                              (str/includes? (get-in % [:messages 1 :content]) "- Worker: Nellie.")) calls))))
        (testing "the cabinet is dusty until the job that dusts it finishes"
          (let [due (get-in (first (filter #(= ["china-cabinet"] (get-in % [:data :items])) begun)) [:data :due])
                before (w/replay (take-while #(< (:round %) due) evs))]
            (is (= {:cleanliness "dusty, the glass smeared"} (:aspects (items/item before "china-cabinet"))))
            (is (= {:cleanliness "just seen to"} (:aspects (items/item world "china-cabinet"))))))))
    (testing "the first judge call was given the setting, the worker and the item"
      (let [[call] (filter #(and (= "judge" (:agent %))
                                 (str/includes? (get-in % [:messages 1 :content]) "- Worker: Nellie.")) calls)]
        (is (str/includes? (:content (first (:messages call))) (str "## The setting\n" (get-in world [:state :setting]))))
        (is (str/includes? (:content (second (:messages call))) "- Worker: Nellie. Nellie, the housemaid"))
        (is (str/includes? (:content (second (:messages call))) "powdered wig"))))
    (testing "the countess gives her the duster she bought in the village"
      (is (every? #(empty? (get-in % [:data :items])) (by "hale" (of-type run :item/work-begun))))
      (let [[given] (of-type run :item/given)]
        (is (= "lady-claudeworth" (:actor given)))
        (is (= {:item "feather-duster" :to "nellie"} (:data given)))
        (is (= "nellie" (:holder (items/item world "feather-duster"))))))
    (testing "the rules explain when to look"
      (is (str/includes? (cw/rules world) "`spend-time` spends time on work or pastimes"))
      (is (str/includes? (cw/rules world) "not routinely on arrival")))
    (e/stop-run run)))

;; ---------------------------------------------------------------------------
;; One home per fact: what agents read comes from the world file

(defn- agent-text
  "Everything `id` reads on their turn: system prompt, user message and tool descriptions."
  [run id]
  (let [world (e/world run)
        role (:role (w/agent world id))]
    (str/join "\n" (concat (map :content (prompt/agent-messages (:scenario run) world (e/events run) id (:opts run)))
                           (for [act (a/available (a/for-role (:actions run) role) world id)]
                             (get-in (a/->tool act) [:function :description]))))))

(deftest briefs-come-from-the-world-file
  (let [run (start)
        world (e/world run)]
    (testing "the setting and each role's :about are in the brief, before the rules"
      (let [system (:content (first (prompt/agent-messages (:scenario run) world (e/events run) "pugh" {})))]
        (is (str/includes? system (get-in world [:state :setting])))
        (is (str/includes? system (get-in world [:state :positions :architect :about])))
        (is (str/includes? system "Each turn is half an hour of game time"))
        (is (str/includes? system "at most 1000 characters"))
        (is (str/includes? system "any character can `commission`"))))
    (testing "opening hours appear only in the view and the reference, from :hours, not in the brief"
      (doseq [id ["lord-claudeworth" "lady-claudeworth" "hale" "pugh"]
              :let [system (:content (first (prompt/agent-messages (:scenario run) world (e/events run) id {})))
                    [brief reference] (str/split system #"\n\n## Reference\n")]]
        (is (not (re-find #"\d\d:\d\d-\d\d:\d\d|09:00|18:00" brief)) id)
        (is (str/includes? reference "\"id\":\"architects-office\",\"name\":\"Architect's office\",\"open\":\"09:00-18:00\"") id)))
    (testing "the GM brief has the setting too, and the no-income guard"
      (is (str/includes? (:content (first (prompt/gm-messages (:scenario run) world (e/events run) ["test"] {})))
                         (get-in world [:state :setting])))
      (is (str/includes? (cw/gm-brief world) "no rents")))
    (testing "the rules quote the query limit from the world, not a literal"
      (is (str/includes? (cw/rules world) "towards your 2 queries a turn"))
      (is (str/includes? (cw/rules (assoc-in world [:state :max-queries] 3)) "towards your 3 queries a turn")))
    (e/stop-run run)))

(deftest move-the-abbey
  (let [farther (-> (wf/read-file fixture/world-file)
                    (update :routes (fn [rs] (mapv (fn [[from to _ :as r]]
                                                     (if (some #{"abbey-gatehouse"} [from to]) [from to 3] r))
                                                   rs))))
        file-name (str (gensym "far-abbey-") ".edn")
        _ (store/write-text! store/test-runs-root file-name (wf/->edn-string farther))
        run (start {:world-file (str (io/file store/test-runs-root file-name))})
        world (assoc (e/world run) :round 1)]
    (testing "nothing agents read says the abbey is half an hour away"
      (doseq [id ["lord-claudeworth" "lady-claudeworth" "hale" "pugh"]
              sentence (str/split (agent-text run id) #"[.\n]")
              :when (re-find #"(?i)abbey" sentence)]
        (is (not (re-find #"half an hour|30 min|\"minutes\" : 30" sentence)) sentence)))
    (testing "journeys shows 90 minutes to the abbey"
      (is (= {:to "abbey" :place "abbey-gatehouse" :minutes 90 :arrive "09:30"}
             (first (:journeys (prompt/agent-view (:scenario run) world "hale"))))))
    (testing "travelling there puts the agent in transit until they arrive"
      (let [[world' [departed]] (perform run world "hale" :travel {:to "abbey"})]
        (is (= :agent/departed (:type departed)))
        (is (space/in-transit? world' "hale"))
        (is (= {:from "secretarys-room" :to "abbey-gatehouse" :arrives 4} (:travel (w/agent world' "hale"))))))
    (e/stop-run run)))

(deftest find-is-refused-at-service-places
  (let [run (start)]
    (try
      (doseq [place ["general-store" "architects-office"]]
        (e/emit! run [{:type :agent/placed :data {:id "hale" :to place}}])
        (let [before (e/events run)
              calls (store/read-llm (:dir run))
              result (#'e/run-query! run "hale" (action run :find) {:what "sugar"})]
          (is (= "this isn't your house to search; ask for it here instead" (:error result)))
          (is (= before (e/events run)))
          (is (= calls (store/read-llm (:dir run))))))
      (let [world (-> (e/world run) (at "hale" "study"))]
        (is (nil? (a/check (action run :find) world "hale" {:what "a letter"})))
        (is (str/includes? (cw/rules world) "Carry things such as dishes with `take` and `walk`; they are used up where they are eaten, not while being carried.")))
      (is (= (e/world run) (w/replay (store/read-events (:dir run)))))
      (finally (e/stop-run run)))))
