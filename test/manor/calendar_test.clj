(ns manor.calendar-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [manor.actions :as a]
            [manor.engine :as e]
            [manor.test-support :as support]
            [manor.io.llm :as llm]
            [manor.io.store :as store]
            [manor.io.worldfile :as worldfile]
            [manor.mechanics.calendar :as c]
            [manor.prompt :as p]
            [manor.world :as w]))

(use-fixtures :once support/with-runs)

(def day {:id "holiday" :date "1904-10-15" :description "A holiday." :visible-to :all})
(defn- start [& [opts]]
  (e/start-run :calendar-fixture (merge {:llm :mock :offline? true :test? true
                                        :run-id (str "calendar-" (random-uuid))} opts)))
(defmacro with-run [[sym opts] & body]
  `(let [~sym (start ~opts)] (try ~@body (finally (e/stop-run ~sym)))))
(defn- step [run] @(e/step! run))
(defn- status [world id] (get-in world [:calendar :entries id :lifecycle :status]))
(defn- of-type [run type] (filter #(= type (:type %)) (e/events run)))
(defn- apply-events [world events] (reduce w/apply-event world events))
(defn- command [world op args] (apply-events world (c/command-events world op args)))
(defn- user [messages] (:content (last messages)))
(defn- prefix [text] (first (str/split text #"## Recent events")))

(defn- review-llm
  "A scripted GM; nil really means no tool calls, unlike Mock's default pass."
  [policy]
  (reify llm/LLM
    (complete [_ {:keys [ctx] :as request}]
      (if-let [decision (if (= :gm (:role ctx)) (policy ctx) {:tool :pass})]
        (llm/complete (llm/mock (constantly decision)) request)
        {:message {:role "assistant" :content ""} :content "" :tool-calls []
         :usage {:prompt-tokens 0 :completion-tokens 0 :cached-tokens 0 :cost 0}}))))

(deftest projections-and-prefix
  (with-run [run {:start "1904-10-15T04:00"
                 :entries [day
                           {:id "visit" :at "1904-10-15T14:00" :description "Expected visitor." :visible-to #{"ada"} :gm-notes "SECRET-NOTE"}
                           {:id "private" :date "1904-10-15" :description "GM-SECRET"}
                           {:id "edge" :date "1904-11-14" :description "Horizon edge." :visible-to :all}
                           {:id "outside" :date "1904-11-15" :description "Outside horizon." :visible-to :all}]}]
    (let [world (e/world run) scenario (:scenario run)
          text (fn [world id] (user (p/agent-messages scenario world (e/events run) id {})))
          ada (text world "ada") ben (text world "ben")
          conv (user (p/conversation-messages scenario world (e/events run) "ada" {}
                                              {:initiator "ada" :members ["ada" "ben"]}
                                              {:pass 1 :in ["ada" "ben"]}))]
      (is (str/starts-with? ada "## Your calendar"))
      (is (str/includes? ada "15 October 1904 — A holiday."))
      (is (str/includes? ada "15 October 1904, 14:00 — Expected visitor."))
      (is (str/includes? ada "Horizon edge."))
      (doseq [hidden ["SECRET-NOTE" "GM-SECRET" "Outside horizon." "05:00" "visible-to" "lifecycle"]]
        (is (not (str/includes? ada hidden)))
        (is (not (str/includes? conv hidden))))
      (is (not (str/includes? ben "Expected visitor.")))
      (is (= (prefix ada) (prefix conv)))
      (let [later (-> world (assoc :round 40) (assoc-in [:agents "ada" :notes] [{:round 2 :text "Changing notes"}]))
            changed (command later :update {:id "visit" :description "Corrected arrangement."})]
        (is (= (prefix ada) (prefix (text later "ada"))))
        (is (not= (prefix ada) (prefix (text changed "ada")))))
      (is (not (str/includes? (text (assoc world :round 41) "ada") "A holiday.")))
      (is (not (str/includes? (c/character-section (assoc-in world [:calendar :lookahead-days] 0) "ada") "Horizon edge.")))
      (is (= (first (p/agent-messages scenario world [] "ada" {}))
             (first (p/agent-messages scenario (assoc world :round 2) [] "ada" {}))))
      (doseq [event (filter #(= "calendar" (namespace (:type %))) (e/events run))]
        (is (= :gm (:visible-to event)))
        (is (not (w/visible-to? event "ada"))))
      (let [gm-text (user (p/gm-messages scenario world [] ["calendar"] {:event-window 0 :calendar-due (c/export-entries world)}))]
        (is (str/includes? gm-text "SECRET-NOTE"))
        (is (str/includes? gm-text "GM-SECRET"))))))

(deftest validation-and-mutation
  (with-run [run {:entries [day]}]
    (let [world (e/world run)]
      (doseq [entry [(assoc day :id "bad" :date "1904-02-30")
                     (assoc day :id "bad" :at "1904-10-15T15:00")
                     (assoc day :id "bad" :visible-to #{"missing"})
                     (assoc day :id "bad" :visible-to #{"gm"})
                     (assoc day :id "bad" :description (apply str (repeat 601 "x")))
                     (assoc day :id "bad" :date "1904-10-14")
                     {:id "bad" :at "1904-10-15T04:00" :description "Past"}]]
        (is (thrown? Exception (c/command-events world :add entry))))
      (is (thrown? Exception (c/command-events world :add day)))
      (is (thrown? Exception (c/command-events world :add (assoc day :id "bad" :lifecycle {:status :reviewed :revision 1})))))
    @(e/update-calendar-entry! run "holiday" {:visible-to #{"ben"} :gm-notes "private"})
    (is (not (str/includes? (c/character-section (e/world run) "ada") "A holiday.")))
    (is (str/includes? (c/character-section (e/world run) "ben") "A holiday."))
    @(e/cancel-calendar-entry! run "holiday")
    (is (= :cancelled (status (e/world run) "holiday")))
    (is (thrown? Exception @(e/add-calendar-entry! run day)))
    (is (empty? (c/due-records (e/world run))))
    (doseq [action (c/actions)] (is (= #{:gm} (:roles action))))
    (let [add (first (c/actions))
          decoded (a/decode-args add {:id "snake" :date "1904-10-15" :description "Snake fields" :visible_to ["ada"] :gm_notes "secret" :junk true})]
      (is (= #{"ada"} (get-in decoded [:args :visible-to])))
      (is (= "secret" (get-in decoded [:args :gm-notes])))
      (is (nil? (a/check add (e/world run) "gm" (:args decoded)))))))

(deftest startup-clock-override-and-history
  (doseq [[time expected] [["04:59" :pending] ["05:00" :due] ["08:00" :due]]]
    (with-run [run {:start (str "1904-10-15T" time)
                   :entries [day (assoc day :id "old" :date "1904-10-14")
                             {:id "past" :at "1904-10-15T04:00" :description "Past appointment." :visible-to :all}]}]
      (is (= expected (status (e/world run) "holiday")))
      (is (= :historical (status (e/world run) "old")))
      (is (= :historical (status (e/world run) "past")))
      (is (str/includes? (c/character-section (e/world run) "ada") "15 October 1904, 04:00 — Past appointment."))
      (is (empty? (store/read-llm (:dir run))))
      (step run)
      (is (= (if (= expected :pending) :pending :reviewed) (status (e/world run) "holiday")))
      (is (= :historical (status (e/world run) "past")))
      (when (= expected :pending)
        (step run)
        (is (= :reviewed (status (e/world run) "holiday")))))))

(deftest crossed-times-batching-and-after-turn-requests
  (with-run [run {:minutes-per-round 60 :event-window 0
                 :entries [day {:id "visit" :at "1904-10-15T05:20" :description "A visit." :visible-to :all}]}]
    (step run) ; first 04:30 round: Ada prays, ordinary GM after turns
    (is (= :pending (status (e/world run) "holiday")))
    (step run) ; crosses both due times at 05:30
    (is (= :reviewed (status (e/world run) "holiday")))
    (is (= :reviewed (status (e/world run) "visit")))
    (let [calls (store/read-llm (:dir run))
          second-round (filter #(= 2 (:round %)) calls)]
      (is (= ["gm" "ada" "ben"] (mapv :agent second-round)))
      (is (= 1 (count (of-type run :calendar/reviewed))))
      (is (str/includes? (user (:messages (first second-round))) "A visit.")))
    (step run)
    (is (= 1 (count (of-type run :calendar/reviewed))))
    (is (= 100 (get-in (e/world run) [:state :money])))
    (is (= #{"gm" "ada" "ben"} (set (keys (:agents (e/world run)))))))
  (with-run [run {:start "1904-10-15T05:00" :entries [day]}]
    ;; A request already in the inbox is not consumed by the calendar intervention.
    (e/emit! run [{:type :gm/requested :actor "ben" :data {:text "Earlier prayer"} :visible-to :gm}])
    (step run)
    (is (= ["gm" "ada" "ben" "gm"]
           (mapv first (partition-by identity (map :agent (store/read-llm (:dir run)))))))
    (is (= 1 (count (of-type run :calendar/reviewed))))))

(deftest disabled-failed-and-cost-guarded-reviews
  (with-run [run {:start "1904-10-15T05:00" :entries [day] :gm :off}]
    (step run)
    (is (= :due (status (e/world run) "holiday")))
    @(e/update-agent! run "gm" {:controller :llm})
    (step run)
    (is (= :reviewed (status (e/world run) "holiday"))))
  (with-run [run {:start "1904-10-15T05:00" :entries [day] :max-cost 0
                 :llm-impl (llm/mock (fn [{:keys [role]}] (if (= role :gm) {:tool :done} {:tool :pass})))}]
    (step run)
    (is (= :due (status (e/world run) "holiday")))
    (is (empty? (filter #(= "gm" (:agent %)) (store/read-llm (:dir run))))))
  (doseq [policy [(fn [_] (throw (ex-info "failed review" {})))
                  (fn [{:keys [step]}]
                    (if (zero? step) {:tool :announce :args {:text "Review started"}}
                        (throw (ex-info "failed after an action" {}))))
                  (fn [{:keys [step]}]
                    (when (zero? step) {:tool :announce :args {:text "Review started"}}))
                  (constantly nil)]]
    (with-run [run {:start "1904-10-15T05:00" :entries [day]
                   :llm-impl (review-llm policy)}]
      (step run)
      (is (= :due (status (e/world run) "holiday")))
      (is (empty? (of-type run :calendar/reviewed)))
      (is (seq (of-type run :run/pause-requested))))))

(deftest successful-review-after-nudge-or-action-limit
  (doseq [policy [(fn [{:keys [step]}]
                   (case step
                     0 nil
                     1 {:tool :announce :args {:text "Review resumed"}}
                     {:tool :done}))
                 (constantly {:tool :announce :args {:text "Review action"}})]]
    (with-run [run {:start "1904-10-15T05:00" :entries [day]
                   :llm-impl (review-llm policy)}]
      (step run)
      (is (= :reviewed (status (e/world run) "holiday")))
      (is (= 1 (count (of-type run :calendar/reviewed))))
      (is (empty? (of-type run :run/pause-requested))))))

(deftest revision-safe-batch-acknowledgement
  (with-run [run {:start "1904-10-15T05:00" :entries [day]}]
    (let [world (e/world run) batch (c/due-records world) ack (c/reviewed-event batch)]
      (doseq [[edit expected] [[:cancel :cancelled] [:reschedule :pending] [:describe :due]]]
        (let [changed (case edit
                        :cancel (command world :cancel {:id "holiday"})
                        :reschedule (command world :update {:id "holiday" :at "1904-10-16T10:00"})
                        :describe (command world :update {:id "holiday" :description "Corrected"}))
              acknowledged (w/apply-event changed ack)]
          (is (= expected (status acknowledged "holiday")))
          (is (= (:calendar changed) (:calendar acknowledged)))))
      (let [reviewed (w/apply-event world ack)
            edited (command reviewed :update {:id "holiday" :gm-notes "Not another review"})
            moved (command edited :update {:id "holiday" :date "1904-10-16"})]
        (is (= :reviewed (status edited "holiday")))
        (is (= :pending (status moved "holiday")))))))

(deftest gm-tools-mutate-during-review-without-recursion
  (doseq [[tool args expected] [[:cancel-calendar-entry {:id "holiday"} :cancelled]
                               [:update-calendar-entry {:id "holiday" :date "1904-10-16"} :pending]
                               [:update-calendar-entry {:id "holiday" :description "New wording"} :due]
                               [:add-calendar-entry {:id "new" :date "1904-10-15" :description "New due work"} :reviewed]]]
    (with-run [run {:start "1904-10-15T05:00" :entries [day] :gm-max-actions 1
                   :llm-impl (llm/mock (fn [{:keys [role world]}]
                                        (if (= role :gm)
                                          (if (zero? (:round world)) {:tool :done} {:tool tool :args args})
                                          {:tool :pass})))}]
      (step run)
      (is (= expected (status (e/world run) "holiday")))
      (is (= 1 (count (filter #(= "gm" (:agent %)) (store/read-llm (:dir run))))))
      (when (= tool :add-calendar-entry)
        (is (= :pending (status (e/world run) "new")))))))

(deftest replay-load-branch-and-export
  (with-run [run {:start "1904-10-15T05:00"
                 :entries [day (assoc day :id "historical" :date "1904-10-14")
                           (assoc day :id "pending" :date "1904-10-20")]}]
    (step run)
    @(e/add-calendar-entry! run {:id "future" :date "1904-10-17" :description "Future" :visible-to #{"ada"} :gm-notes "Private"})
    @(e/cancel-calendar-entry! run "future")
    (step run)
    (let [world (e/world run)
          loaded (e/load-run (:id run) {:test? true})
          branch (e/branch-run run 2)]
      (try
        (is (= world (w/replay (store/read-events (:dir run)))))
        (is (= world (e/world loaded) (e/world branch)))
        (step loaded)
        (is (= 1 (count (of-type loaded :calendar/reviewed))))
        (let [start {:locations [{:id "hall" :name "Hall"}] :agents [{:name "Ada" :role :resident} {:name "Ben" :role :resident}]
                     :meta {:start "1904-10-15T05:00" :minutes-per-round 30}}
              ;; Add space for a valid world-file export; production map export uses live space.
              world (assoc-in world [:space :locations "hall"] {:id "hall" :name "Hall"})
              exported (worldfile/export world start)
              path (str (:dir run) "/calendar-export.edn")]
          (spit path (worldfile/->edn-string exported))
          (with-run [imported {:world-file path}]
            (is (= (:calendar world) (:calendar (e/world imported))))
            (step imported)
            (is (empty? (of-type imported :calendar/reviewed)))))
        (finally (e/stop-run loaded) (e/stop-run branch))))))

(deftest persisted-due-and-new-audience-export-limitation
  (with-run [run {:start "1904-10-15T05:00" :entries [day] :gm :off}]
    (step run)
    (let [loaded (e/load-run (:id run) {:test? true}) branch (e/branch-run run 1)]
      (try
        (is (= :due (status (e/world loaded) "holiday")))
        (is (= :due (status (e/world branch) "holiday")))
        ;; Do not advance a second writer on the original run directory.
        @(e/update-agent! branch "gm" {:controller :llm})
        (step branch)
        (is (= :reviewed (status (e/world branch) "holiday")))
        (finally (e/stop-run loaded) (e/stop-run branch))))
    @(e/spawn! run {:name "Newcomer" :role :resident})
    @(e/add-calendar-entry! run {:id "new-person" :date "1904-10-16" :description "Private arrangement" :visible-to #{"newcomer"}})
    (let [world (e/world run)
          exported (worldfile/export world {:agents [{:name "Ada" :role :resident}]})
          recipient (first (filter #(= "new-person" (:id %)) (:calendar exported)))
          loaded (e/load-run (:id run) {:test? true})]
      (try
        (is (= #{"newcomer"} (:visible-to recipient)))
        (is (= world (e/world loaded)))
        (is (str/includes? (c/character-section (e/world loaded) "newcomer") "Private arrangement"))
        (is (= (:calendar world)
               (:calendar (apply-events (dissoc world :calendar) (c/init-events world {} (:calendar exported))))))
        (is (thrown-with-msg? Exception #"existing character IDs"
                              (c/init-events (update world :agents dissoc "newcomer") {} (:calendar exported))))
        (finally (e/stop-run loaded))))))

(deftest opt-out-is-unchanged
  (let [run (e/start-run :treasure {:llm :mock :offline? true :test? true :run-id (str "calendar-optout-" (random-uuid))})]
    (try
      (is (nil? (:calendar (e/world run))))
      (is (not-any? #(#{:add-calendar-entry :update-calendar-entry :cancel-calendar-entry} (:name %)) (:actions run)))
      (is (nil? (c/character-section (e/world run) "anyone")))
      (finally (e/stop-run run)))))

(deftest exact-timed-boundary-and-live-today-addition
  (with-run [run {:start "1904-10-15T05:00"
                 :entries [{:id "exact" :at "1904-10-15T05:30" :description "Exact appointment" :visible-to :all}]}]
    (step run)
    (is (= :pending (status (e/world run) "exact")))
    @(e/add-calendar-entry! run day)
    (is (= :pending (status (e/world run) "holiday")))
    (step run)
    (is (= :reviewed (status (e/world run) "exact")))
    (is (= :reviewed (status (e/world run) "holiday")))
    (let [calls (filter #(= 2 (:round %)) (store/read-llm (:dir run)))]
      (is (= "gm" (:agent (first calls)))))
    (is (str/includes? (c/character-section (e/world run) "ada") "Exact appointment"))))

(deftest calendar-live-map-refresh-and-audience-does-not-follow-post
  (let [path (str "/tmp/manor-calendar-fixture-" (random-uuid) ".edn")]
    (try
      (spit path (worldfile/->edn-string
                  {:meta {:start "1904-10-15T05:00" :minutes-per-round 30}
                   :locations [{:id "hall" :name "Hall"}]
                   :agents [{:name "Ada" :role :resident} {:name "Ben" :role :resident}]
                   :calendar [(assoc day :visible-to #{"ada"} :gm-notes "Operator-only notes")]}))
      (with-run [run {:world-file path}]
        @(e/update-calendar-entry! run "holiday" {:description "Changed arrangement"})
        (let [live (worldfile/read-file (str (:dir run) "/map.edn"))]
          (is (= "Changed arrangement" (:description (first (:calendar live)))))
          (is (= "Operator-only notes" (:gm-notes (first (:calendar live))))))
        @(e/spawn! run {:name "Replacement" :role :resident})
        (is (not (str/includes? (c/character-section (e/world run) "replacement") "Changed arrangement")))
        (let [messages (p/agent-messages (:scenario run) (e/world run) (e/events run) "ben" {})]
          (doseq [secret ["Changed arrangement" "Operator-only notes" "calendar/updated" "visible-to"]]
            (is (not (str/includes? (pr-str messages) secret))))))
      (finally (.delete (java.io.File. path))))))

(deftest shared-initial-validation-and-deterministic-order
  (with-run [run {}]
    (let [world (e/world run)]
      (is (thrown-with-msg? Exception #"requires a simulation clock" (c/init-events (dissoc world :clock) {} [])))
      (is (thrown-with-msg? Exception #"non-negative integer" (c/init-events world {:lookahead-days -1} [])))
      (is (thrown-with-msg? Exception #"already reserved" (c/init-events world {} [day day])))
      (is (thrown-with-msg? Exception #"persisted calendar lifecycle"
                            (c/init-events world {} [(assoc day :lifecycle {:status :unknown :revision 1})]))))
    @(e/add-calendar-entry! run (assoc day :id "b"))
    @(e/add-calendar-entry! run (assoc day :id "a"))
    @(e/add-calendar-entry! run {:id "a-time" :at "1904-10-15T05:00:00" :description "Timed A" :visible-to :all})
    @(e/add-calendar-entry! run {:id "b-time" :at "1904-10-15T05:00" :description "Timed B" :visible-to :all})
    (let [world (apply-events (assoc (e/world run) :round 2) (c/due-events (assoc (e/world run) :round 2)))
          batch (c/due-records world)
          section (c/character-section world "ada")]
      (is (= ["a" "b" "a-time" "b-time"] (mapv :id batch)))
      (is (< (.indexOf section "Timed A") (.indexOf section "Timed B")))
      (is (= section (c/character-section (w/apply-event world (c/reviewed-event batch)) "ada"))))))
