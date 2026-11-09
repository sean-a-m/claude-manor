(ns manor.hiring-test
  "Hiring's shared parts on the contact flow (docs/sending-for-design.md):
  someone new written privately when recommended for a place, reputations,
  offers, terms, bedrooms and answers, leaving with a return time, and
  writing a contact's character. Runs use the production Claudeworth
  mechanics on test/fixtures/visits.edn (start 1904-10-15 20:00, half-hour
  rounds; the earl at the post office, Hale in the drawing room)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [manor.actions :as a]
            [manor.engine :as e]
            [manor.io.llm :as llm]
            [manor.io.store :as store]
            [manor.mechanics.calendar :as calendar]
            [manor.mechanics.contacts :as contacts]
            [manor.mechanics.contacts.judge :as contacts-judge]
            [manor.mechanics.hiring :as h]
            [manor.mechanics.items.judge :as item-judge]
            [manor.mechanics.items.work :as item-work]
            [manor.mechanics.people :as people]
            [manor.mechanics.space :as space]
            [manor.mechanics.visits :as visits]
            [manor.prompt :as prompt]
            [manor.ruleset.mock :as mock]
            [manor.ruleset.views :as ruleset-views]
            [manor.scenarios.claudeworth :as cw]
            [manor.test-support :as support]
            [manor.world :as w]))

(use-fixtures :once support/with-runs)
(use-fixtures :each (partial support/with-structured-attempts 4))

(def earl "lord-claudeworth")

(def housemaid-day
  "Hale goes down to the post office and recommends a housemaid to the earl,
  who sends for her and goes home to meet her at the carriage sweep, where
  she arrives at 23:00 (round 7)."
  {"hale" {"20:00" [[:travel {:to "post-office"}]]
           "20:30" [[:recommend {:wanted "a housemaid wanting a place" :for earl}] [:pass {}]]}
   earl {"20:00" [[:pass {}]]
         "20:30" [[:pass {}]]
         "21:00" [[:send-for {:id "contact-1"}]]
         "21:30" [[:travel {:to "carriage-sweep"}]]}})

(defn start
  "A run on `day`, with `changes` merged into the scenario and `opts` into the run's."
  ([] (start housemaid-day {} {}))
  ([day changes opts]
   (with-redefs [e/load-scenario (constantly (merge (assoc cw/scenario :mock-policy #(mock/scripted-policy day %)) changes))]
     (e/start-run :claudeworth (merge {:test? true :llm :mock :offline? true :gm :off :max-cost 0.20
                                       :world-file "test/fixtures/visits.edn" :run-id (str (gensym "hiring-"))}
                                      opts)))))

(defn until! [run r]
  (let [n (- r (:round (e/world run)))]
    (when (pos? n)
      (e/auto! run n)
      (is (= :idle (e/wait-idle run 60000))))))

(defn replay! [run] (is (= (e/world run) (w/replay (store/read-events (:dir run))))))
(defn action [run tool] (first (filter #(= tool (:name %)) (:actions run))))
(defn of-type [run type] (filter #(= type (:type %)) (e/events run)))
(defn the-contact [world] (contacts/contact world "contact-1"))
(defn maid [world] (:agent-id (the-contact world)))

(defn check [run world actor tool args]
  (let [spec (action run tool)
        decoded (a/decode-args spec args)]
    (or (:error decoded) (a/check spec world actor (:args decoded)))))

(defn act
  "`world` after `actor` does `tool` with `args`, checked as the engine
  checks it; throws with the reason if refused."
  [run world actor tool args]
  (let [spec (action run tool)]
    (when-let [r (check run world actor tool args)] (throw (ex-info r {:tool tool})))
    (reduce #(w/apply-stamped %1 (w/stamp %1 (assoc %2 :actor actor)))
            world (a/effect-events spec world actor (:args (a/decode-args spec args))))))

(defn act! [run actor tool args]
  (let [spec (action run tool)
        world (e/world run)]
    (when-let [r (check run world actor tool args)] (throw (ex-info r {:tool tool})))
    (e/emit! run (a/effect-events spec world actor (:args (a/decode-args spec args))))))

(defn- quiet? [run]
  (is (not-any? #(#{:action/forfeited :run/pause-requested :adjudication/failed} (:type %)) (e/events run))))

(deftest appearance-is-structurally-private
  (let [record {:name "Secret Name" :age 21 :sex "female" :position "housemaid"
                :persona "private-persona" :manner "private-manner" :origin "private-origin"
                :temperament [{:axis "openness" :level "high" :text "private-temperament"}]
                :faculties [{:faculty "Sight" :size "small" :text "private-description"}
                            {:faculty "Acquisitiveness" :size "large" :text "private-faculty"}]
                :circumstances [{:label "private-circumstance"}]}
        req (people/appearance-request record)
        data (json/read-value (get-in req [:messages 1 :content]) json/keyword-keys-object-mapper)]
    (is (= {:age 21 :sex "female" :position "housemaid" :small-senses ["Sight"]} data))
    (is (= data (get-in req [:ctx :record])))
    (is (not (str/includes? (pr-str (dissoc req :check)) "private-")))
    (doseq [bad ["" "  " (apply str (repeat 601 "x"))]]
      (is (:error ((:check req) {:tool-calls [{:id "a" :name "appearance" :arguments {:appearance bad}}]}))))
    (is (= {:appearance "Plain clothes."}
           (:verdict ((:check req) {:tool-calls [{:id "a" :name "appearance" :arguments {:appearance "Plain clothes."}}]}))))))

(deftest a-new-person-is-written-privately-with-the-default-model
  (let [secret "private-contact-sentinel"
        polish (fn [ctx] (update (people/mock-polish ctx) :args assoc
                                 :persona (str "You keep " secret " to yourself.") :circumstances [secret] :appearance secret))
        run (start housemaid-day {:adjudicators (assoc-in (:adjudicators cw/scenario) [:people/polish :mock] polish)}
                   {:model "default-test-model" :judge-model "other-judge-model"})]
    (try
      (until! run 2)
      (let [world (e/world run) c (the-contact world) id (:agent-id c)]
        (testing "the judge's new person seeking a place, written now and away"
          (is (= {:line "housemaid seeking a place" :based "Ely" :known-to #{"hale" earl}}
                 (select-keys c [:line :based :known-to])))
          (is (= {:status :away :role :caller :lives "Ely" :sex :female} (select-keys (w/agent world id) [:status :role :lives :sex])))
          (is (= [secret] (get-in c [:record :circumstances])))
          (is (= [secret] (:circumstances (w/agent world id)))))
        (testing "only the recommender learns what they know of her"
          (is (seq (get-in world [:state :reputations "hale" "contact-1"])))
          (is (empty? (:reputations (h/observe world earl)))))
        (testing "nobody else sees the private parts"
          (doseq [viewer [earl "hale" "carter"]]
            (is (not (str/includes? (pr-str (prompt/agent-view (:scenario run) world viewer)) secret)))
            (is (not (str/includes? (pr-str (filter #(w/visible-to? % viewer) (e/events run))) secret)))))
        (testing "the writers run on the run's model"
          (let [calls (filter #(str/starts-with? (:agent %) "adjudicator/people") (store/read-llm (:dir run)))]
            (is (= 2 (count calls)))
            (is (every? #(= "default-test-model" (:model %)) calls))))
        (is (str/starts-with? (check run world "carter" :send-for {:id "contact-1"}) "you have no recommendation")))
      (until! run 7)
      (let [world (e/world run) id (maid world)]
        (testing "she arrives at the meeting place as a caller, holding no post"
          (is (= "carriage-sweep" (space/location-of world id)))
          (is (= :caller (:role (w/agent world id))))
          (is (nil? (:model (w/agent world id))))
          (is (contacts/sent-for-here? world id))))
      (quiet? run)
      (replay! run)
      (finally (e/stop-run run)))))

(deftest self-recommendation-is-rejected-without-pausing
  (let [run (start {"hale" {"20:00" [[:recommend {:wanted "a housemaid" :for "hale"}] [:pass {}]]}} {} {})]
    (try
      (until! run 1)
      (let [calls (filter #(= "hale" (:agent %)) (store/read-llm (:dir run)))]
        (is (= "error: ask someone else here to recommend to you, not yourself"
               (get-in (first calls) [:replies 0 :content])))
        (is (empty? (of-type run :bridge/called)))
        (is (empty? (contacts/contacts (e/world run))))
        (is (not-any? #(#{:action/forfeited :run/pause-requested} (:type %)) (e/events run))))
      (replay! run)
      (finally (e/stop-run run)))))

(deftest reputation-selects-written-circumstances-and-basic-readings
  (let [stubs (assoc h/offline-stubs :people/reputation
                     (fn [{:keys [person knower]} _]
                       {:person (:name person) :knower knower :relation "neighbour"
                        :acquaintance "They know one another."
                        :knows {:temperament [(first (:temperament person))]
                                :faculties [(first (:faculties person))]
                                :circumstances [(first (:circumstances person))]}}))
        run (start housemaid-day {} {:bridge-stubs stubs})]
    (try
      (until! run 2)
      (let [world (e/world run) c (the-contact world)
            r (get-in world [:state :reputations "hale" "contact-1"])
            reply (some #(when (= "recommend" (get-in % [:response :tool_calls 0 :function :name]))
                           (get-in % [:replies 0 :content]))
                        (filter #(= "hale" (:agent %)) (store/read-llm (:dir run))))]
        (is (= [(first (get-in c [:record :circumstances]))] (get-in r [:knows :circumstances])))
        (is (= #{:axis :level :measures} (set (keys (first (get-in r [:knows :temperament]))))))
        (is (= #{:faculty :size :means} (set (keys (first (get-in r [:knows :faculties]))))))
        (is (str/includes? reply (first (get-in r [:knows :circumstances]))))
        (is (str/includes? reply (get-in r [:knows :temperament 0 :level])))
        (is (empty? (:reputations (h/observe world earl)))))
      (replay! run)
      (finally (e/stop-run run)))))

(deftest offers-haggling-bedrooms-and-conditions
  (let [run (start)]
    (try
      (until! run 7)
      (let [world (e/world run) id (maid world)
            offered (act run world earl :engage {:who id :position "housemaid" :terms "Ten pounds."})
            declined (act run offered id :decline-place {:reason "Twelve, please."})
            replaced (as-> declined w
                       (act run w earl :engage {:who id :position "footman" :terms "Twelve pounds." :bedroom "blue-room"})
                       (act run w earl :engage {:who id :position "housemaid" :terms "Fourteen pounds." :bedroom "blue-room"}))
            accepted (act run replaced id :accept-place {:conditions "Sunday afternoons free."})
            post (get-in accepted [:state :posts id])]
        (is (= :caller (:role (w/agent offered id))) "an offer is not a post")
        (is (nil? (get-in declined [:state :place-offers id])))
        (testing "declining closes the offer and its answers"
          (is (not ((:available? (action run :accept-place)) declined id)))
          (is (not ((:available? (action run :decline-place)) declined id))))
        (is (= {:employer earl :terms "Fourteen pounds." :conditions "Sunday afternoons free." :household "household"}
               (select-keys post [:employer :terms :conditions :household])))
        (is (= "blue-room" (get-in accepted [:state :bedrooms id])))
        (is (= :housemaid (:role (w/agent accepted id))))
        (testing "the post is seen by the two of them only"
          (is (= post (get-in (h/observe accepted earl) [:posts id])))
          (is (= post (get-in (h/observe accepted id) [:posts id])))
          (is (nil? (get-in (h/observe accepted "hale") [:posts id]))))
        (is (some? (check run accepted id :accept-place {})))
        (is (some? (check run accepted earl :send-away {:who id})))
        (is (= "use a position id from the catalogue" (check run world earl :engage {:who id :position "nonexistent"})))
        (is (= "use a position id from the catalogue" (check run world earl :engage {:who id :position "gm"})))
        (is (str/starts-with? (check run world earl :engage {:who id :position "housemaid" :bedroom "post-office"}) "bedroom must")))
      (finally (e/stop-run run)))))

(deftest answer-the-offer-together-and-sleep-in-an-assigned-room
  (let [run (start)]
    (try
      (until! run 7)
      (let [id (maid (e/world run))
            sleep (action run :sleep)]
        (act! run earl :engage {:who id :position "housemaid" :bedroom "drawing-room"})
        @(e/place! run earl "entrance-hall")
        (testing "both answers explain how to reach the named offerer"
          (doseq [tool [:accept-place :decline-place]]
            (let [reason (check run (e/world run) id tool {})]
              (is (str/includes? reason "Lord Claudeworth (lord-claudeworth)"))
              (is (str/includes? reason "find-person and walk")))))
        @(e/place! run earl "carriage-sweep")
        (act! run id :accept-place {})
        (is (= "drawing-room" (get-in (e/world run) [:state :bedrooms id])))
        @(e/place! run id "drawing-room")
        (let [world (e/world run)]
          (is (= {:id "drawing-room" :name "Drawing room" :sleeps [(:name (w/agent world id))]}
                 (first (filter #(= "drawing-room" (:id %)) (get-in (ruleset-views/observe world id) [:household :bedrooms])))))
          (is (some? (a/check sleep (assoc-in world [:agents "hale" :location] "drawing-room") "hale" {:until "07:00"}))
              "an unassigned non-bedroom isn't a place to sleep")
          (is (nil? (a/check sleep world id {:until "07:00"})))))
      (replay! run)
      (finally (e/stop-run run)))))

(deftest held-people-cannot-be-sent-away-or-leave
  (doseq [faculty [:movement :consciousness]]
    (testing (str faculty " disabled")
      (let [run (start)]
        (try
          (until! run 7)
          (let [id (maid (e/world run))]
            @(e/afflict! run id {:faculty faculty :level :disabled})
            (let [world (e/world run)]
              (is (some? (check run world id :take-leave {})))
              (is (str/includes? (check run world earl :send-away {:who id}) "cannot leave")))
            @(e/afflict! run id {:faculty faculty :level :normal})
            (is (nil? (check run (e/world run) earl :send-away {:who id}))))
          (replay! run)
          (finally (e/stop-run run)))))))

(deftest failed-generation-or-polish-leaves-no-partial-person
  (doseq [[changes opts] [[{} {:bridge-stubs (assoc h/offline-stubs :people/generate (fn [& _] (throw (ex-info "offline failure" {}))))}]
                          [{:adjudicators (assoc-in (:adjudicators cw/scenario) [:people/appearance :mock]
                                                    (constantly {:tool :appearance :args {:appearance ""}}))} {}]
                          [{:adjudicators (assoc-in (:adjudicators cw/scenario) [:people/polish :mock]
                                                    (constantly {:tool :polish :args {:persona ""}}))} {}]]]
    (let [run (start housemaid-day changes opts)
          agents (count (:agents (e/world run)))]
      (try
        (until! run 2)
        (is (empty? (contacts/contacts (e/world run))))
        (is (some #(= :adjudication/failed (:type %)) (e/events run)))
        (is (= agents (count (:agents (e/world run)))))
        (replay! run)
        (finally (e/stop-run run))))))

(deftest composed-query-stops-at-the-run-budget
  (let [calls (atom [])
        policy #(mock/scripted-policy housemaid-day %)
        paid-mock (reify llm/LLM
                    (complete [_ request]
                      (let [ctx (:ctx request)]
                        (swap! calls conj (:task ctx))
                        (llm/complete (llm/mock (fn [ctx]
                                                  (case (:task ctx)
                                                    :contacts/recommendation (contacts-judge/mock-judge ctx)
                                                    :people/appearance {:tool :appearance :args {:appearance "Plain working clothes."}}
                                                    (policy ctx))))
                                      request))))
        priced (reify llm/LLM
                 (complete [_ request]
                   (cond-> (llm/complete paid-mock request)
                     (= :people/appearance (get-in request [:ctx :task])) (assoc-in [:usage :cost] 0.10))))
        run (start housemaid-day {} {:llm-impl priced :max-cost 0.05})]
    (try
      (until! run 2)
      (is (= 0.10 (w/total-cost (e/world run))))
      (is (some #{:people/appearance} @calls))
      (is (not-any? #{:people/polish} @calls))
      (is (empty? (contacts/contacts (e/world run))))
      (is (= :manual (:mode (e/status run))))
      (replay! run)
      (finally (e/stop-run run)))))

(deftest references-use-only-the-writers-knowledge
  (let [run (start)]
    (try
      (until! run 2)
      (let [world (e/world run)
            c (the-contact world)
            spec (action run :spend-time)
            args {:doing "writing a character" :contact "contact-1" :hours 0.5}
            events ((:effect spec) world "hale" args)
            job (get-in (first events) [:data])
            request (item-judge/judge-request {} world job {:minutes 30 :luck 50})
            done (item-work/work-done world job {:minutes 30 :luck 50}
                                      {:made [{:name "Written character" :desc "A letter of reference." :aspects {} :held true :consumable false}]})]
        (is (nil? (a/check spec world "hale" args)))
        (is (= "you must know of that contact through a recommendation before writing their character"
               (a/check spec (assoc-in world [:agents "carter" :location] "post-office") "carter" args)))
        (testing "ordinary activities accept an omitted or empty contact"
          (let [ordinary {:doing "checking the estate ledgers" :hours 0.5}
                expected ((:effect spec) world "hale" ordinary)]
            (is (not-any? #(contains? (:data %) :reference) expected))
            (doseq [raw [ordinary (assoc ordinary :contact "")]]
              (let [{:keys [args error]} (a/decode-args spec raw)]
                (is (nil? error))
                (is (nil? (a/check spec world "hale" args)))
                (is (= expected ((:effect spec) world "hale" args)))))
            (is (a/check spec world "hale" (assoc ordinary :contact "none")))))
        (is (= {:contact-id "contact-1" :name (:name c) :line "housemaid seeking a place"
                :reputation (get-in world [:state :reputations "hale" "contact-1"])}
               (:reference job)))
        (is (not (str/includes? (pr-str (:messages request)) (first (get-in c [:record :circumstances])))))
        (is (= "contact-1" (get-in done [:data :made 0 :contact-id])))
        (is (= "hale" (get-in done [:data :made 0 :holder]))))
      (finally (e/stop-run run)))))

(deftest leaving-with-a-return-time-and-coming-back-for-the-place
  (let [run (start)
        back {:return-date "1904-10-16" :return-time "09:00"}]
    (try
      (until! run 7)
      (let [world (e/world run) id (maid world)]
        (testing "a return time is checked"
          (doseq [[args reason] [[{:return-date "1904-10-16"} "give both return-date and return-time, or neither"]
                                 [{:return-date "1904-10-15" :return-time "22:00"} "the return time must be later than now"]
                                 [{:return-date "1904-10-30" :return-time "08:00"} "say you'll be back within 7 days"]]]
            (is (= reason (check run world id :take-leave args))))
          (is (str/includes? (check run world id :take-leave {:return-date "16 October" :return-time "09:00"}) "\"1904-10-15\"")))
        (let [left (act run world id :take-leave back)
              v (last (sort-by :id (vals (visits/visits left))))]
          (testing "she goes home, her visit ends, and a return is expected"
            (is (= :away (:status (w/agent left id))))
            (is (= {:guest id :host earl :by :returning :status :scheduled :at "1904-10-16T09:00"}
                   (select-keys v [:guest :host :by :status :at])))
            (is (str/includes? (calendar/character-section left id) "You mean to come back to")))
          (testing "coming back then, she is still someone sent for, who may be offered the place"
            (let [due (assoc left :round (:arrive v))
                  after (reduce w/apply-event due (visits/tick (:scenario run) due))
                  after (assoc-in after [:agents earl :location] "carriage-sweep")]
              (is (= "carriage-sweep" (space/location-of after id)))
              (is (= (:situation (w/agent world id)) (:situation (w/agent after id))) "she keeps her situation")
              (is (contacts/sent-for-here? after id))
              (is (nil? (check run after earl :engage {:who id :position "housemaid"})))))))
      (finally (e/stop-run run)))))

(deftest striking-the-bargain-in-a-conversation
  (let [day (assoc-in housemaid-day [earl "23:00"] [[:converse {:with ["@maid"] :text "Will you take the place?"}] [:pass {}]])
        policy (fn [{:keys [world] :as ctx}]
                 (mock/scripted-policy (assoc-in day [earl "23:00" 0 1 :with] [(maid world)]) ctx))
        conversation (fn [{:keys [world agent-id used]}]
                       (let [id (maid world)]
                         (cond
                           (and (= earl agent-id) (not (some #{"engage"} used)) (contacts/sent-for-here? world id))
                           {:tool :engage :args {:who id :position "housemaid" :terms "Twelve pounds."}}
                           (and (= id agent-id) (get-in world [:state :place-offers id]))
                           {:tool :accept-place :args {:conditions "Sundays free."}}
                           (and (= earl agent-id) (some #{"engage"} used)) {:tool :reply :args {:text "Twelve pounds, then."}}
                           (and (= earl agent-id) (get-in world [:state :posts id])) {:tool :end-conversation}
                           :else {:tool :reply :args {:text "Very good, my lord."}})))
        run (start day {:mock-policy policy :mock-conversation conversation} {})]
    (try
      (until! run 7)
      (let [world (e/world run)
            id (maid world)
            evs (e/events run)
            started (:id (first (of-type run :conversation/started)))
            ended (:id (first (of-type run :conversation/ended)))
            within? #(< started (:id %) ended)]
        (testing "the offer is made and accepted on lines of the conversation"
          (is (within? (first (of-type run :people/offered))))
          (is (within? (first (filter #(and (= :people/post-changed (:type %)) (= :housemaid (get-in % [:data :position]))) evs)))))
        (is (= :housemaid (:role (w/agent world id))))
        (is (= "Sundays free." (get-in world [:state :posts id :conditions])))
        (is (not (visits/from-elsewhere? world id)) "taking the place ended her visit")
        (is (not-any? #(#{:action/forfeited :run/pause-requested} (:type %)) evs)))
      (replay! run)
      (finally (e/stop-run run)))))
