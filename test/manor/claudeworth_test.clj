(ns manor.claudeworth-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [manor.actions :as a]
            [manor.builtins :as b]
            [manor.clock :as clock]
            [manor.engine :as e]
            [manor.prompt :as prompt]
            [manor.scenarios.claudeworth :as cw]
            [manor.space :as space]
            [manor.store :as store]
            [manor.world :as w]
            [manor.worldfile :as wf]))

;; All runs here use {:test? true}, so they live under test-runs/.
(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

(defn- start [& [opts]]
  (e/start-run :claudeworth (merge {:llm :mock :offline? true :test? true :run-id (str (gensym "claudeworth-"))}
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

(def house #{"carriage-sweep" "entrance-hall" "drawing-room" "study" "dining-room" "kitchen" "staircase"
             "gallery" "earls-bedroom" "countess-bedroom" "secretarys-room"})

(deftest the-world
  (let [{:keys [world-file]} (wf/load-world "worlds/claudeworth.edn")
        run (start)
        world (e/world run)]
    (testing "the world file loads with no problems, the scenario's own included"
      (is (empty? (wf/problems world-file)))
      (is (empty? (cw/problems world-file))))
    (testing "everyone's bedroom is a bedroom"
      (is (= {"lord-claudeworth" "earls-bedroom" "lady-claudeworth" "countess-bedroom"
              "hale" "secretarys-room" "pugh" "architects-lodgings"}
             (get-in world [:state :bedrooms])))
      (is (every? #(= :bedroom (:kind (space/location world %))) (vals (get-in world [:state :bedrooms])))))
    (testing "the house and the village are areas; journeys join them"
      (is (= house (space/area world "study")))
      (is (= #{"village-green" "general-store" "architects-office" "architects-lodgings"}
             (space/area world "architects-office")))
      (is (= 1 (space/distance world "study" "architects-office"))))
    (testing "everyone but Mr Pugh starts in their bedroom at 08:00"
      (is (= "earls-bedroom" (space/location-of world "lord-claudeworth")))
      (is (= "architects-office" (space/location-of world "pugh")))
      (is (= "Saturday 15 October 1904, 08:00" (:time (prompt/agent-view (:scenario run) world "hale")))))
    (testing "bad bedrooms and opening hours are named"
      (let [ps (str/join "\n" (cw/problems (-> world-file
                                                (assoc-in [:agents 0 :bedroom] "study")
                                                (assoc-in [:locations 12 :hours] ["8am" "18:00"]))))]
        (is (str/includes? ps "agent \"Lord Claudeworth\": :bedroom \"study\" is not a bedroom"))
        (is (str/includes? ps "location \"general-store\": :hours must be two times")))
      (let [ps (str/join "\n" (cw/problems (-> world-file (update :meta dissoc :setting) (update :roles dissoc :earl))))]
        (is (str/includes? ps ":meta :setting must be a paragraph"))
        (is (str/includes? ps ":roles :earl needs an :about paragraph"))))
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
        (is (= {:activity "asleep" :until 9} (:data slept)))
        (is (= {:activity "asleep" :until 9} (w/busy world' "hale")))))
    (testing "spend-time occupies the right number of rounds"
      (let [{:keys [args]} (a/decode-args (action run :spend-time) "{\"activity\": \"writing letters\", \"hours\": 1.5}")
            [world' _] (perform run world "hale" :spend-time args)]
        (is (= {:activity "writing letters" :until 4} (w/busy world' "hale")))))
    (testing "rouse frees someone busy in the same room, and nobody else"
      (let [asleep (w/apply-event world (b/occupy world "lord-claudeworth" "asleep" 20))
            [together] (perform run asleep "hale" :walk {:to "earls-bedroom"})
            [woken [freed]] (perform run together "hale" :rouse {:who "lord-claudeworth"})]
        (is (= :free (a/kind (action run :rouse))))
        (is (= "hale" (get-in freed [:data :by])))
        (is (nil? (w/busy woken "lord-claudeworth")))
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
        evs (e/events run)]
    (testing "a whole day to 07:00 Sunday with no forfeits, and replay gives the same world"
      (is (= 47 (:round world)))
      (is (empty? (of-type run :action/forfeited)))
      (is (= world (w/replay (store/read-events (:dir run))))))
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
                    woke (if freed (:round freed) until)]]
        (is (<= slept 31) id)
        (is (= #{} (set (filter #(< slept % woke) (rounds-called run id)))) id)))
    (testing "Hale wakes at 06:30 (round 46), rouses the earl, and the earl acts at 07:00"
      (is (= 46 (:until (:data (first (sleeps run "hale"))))))
      (is (some #(and (= 46 (:round %)) (= "hale" (get-in % [:data :by]))) (by "lord-claudeworth" (of-type run :agent/freed))))
      (is (contains? (rounds-called run "lord-claudeworth") 47)))
    (testing "the run keeps its starting world and its live map"
      (is (.exists (io/file (:dir run) "world-start.edn")))
      (is (.exists (io/file (:dir run) "map.edn"))))
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
    (testing "the scripted evening follows the clock: Hale sleeps at 22:00 and rouses the earl at 06:30"
      (rounds! run 20)
      (is (= 3 (:round (first (sleeps run "hale")))))
      (is (= 4 (:round (first (sleeps run "lord-claudeworth")))))
      (is (some #(and (= 20 (:round %)) (= "hale" (get-in % [:data :by]))) (by "lord-claudeworth" (of-type run :agent/freed))))
      (is (= (e/world run) (w/replay (store/read-events (:dir run))))))
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
        (is (str/includes? system (get-in world [:state :roles :architect :about])))
        (is (str/includes? system "Each turn is half an hour of game time"))
        (is (str/includes? system "at most 1000 characters"))))
    (testing "opening hours appear only in the view, from :hours"
      (doseq [id ["lord-claudeworth" "lady-claudeworth" "hale" "pugh"]
              :let [system (:content (first (prompt/agent-messages (:scenario run) world (e/events run) id {})))]]
        (is (not (re-find #"\d\d:\d\d-\d\d:\d\d|09:00|18:00" system)) id)))
    (testing "the GM brief has the setting too"
      (is (str/includes? (:content (first (prompt/gm-messages (:scenario run) world (e/events run) ["test"] {})))
                         (get-in world [:state :setting]))))
    (e/stop-run run)))

(deftest move-the-abbey
  (let [farther (-> (wf/read-file "worlds/claudeworth.edn")
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
