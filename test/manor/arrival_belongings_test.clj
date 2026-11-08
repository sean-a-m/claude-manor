(ns manor.arrival-belongings-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [manor.engine :as e]
            [manor.hiring-test :as hiring-test]
            [manor.io.store :as store]
            [manor.mechanics.hiring :as hiring]
            [manor.mechanics.items :as items]
            [manor.mechanics.items.belongings :as belongings]
            [manor.mechanics.items.views :as item-views]
            [manor.mechanics.people :as people]
            [manor.prompt :as prompt]
            [manor.settings :as settings]
            [manor.test-support :as support]
            [manor.world :as w]))

(use-fixtures :once support/with-runs)

(def letter-text "private-letter-sentinel")
(def kit
  [{:name "Servant's box" :desc "A worn wooden box." :container :closed
    :aspects {:contents "Spare linen and mending things."}
    :contents [{:name "Written character" :desc letter-text :candidate-reference? true}]}
   {:name "Keepsake" :desc "A small wooden keepsake."}])

(def arrival-options
  {:belongings-mode :first-arrival
   :arrival-context {:kind :candidate :position :housemaid :occasion "coming for interview"}})

(def writer-args
  {:circumstances ["You were in service before the household reduced its staff."]
   :persona "You like orderly work." :manner "You seem civil." :appearance "Plain working clothes."})

(defn check-answer
  ([args] (check-answer arrival-options args))
  ([options args]
   (let [request (people/polish-request (merge {:record {:position "housemaid"}} options))]
     ((:check request) {:tool-calls [{:id "p" :name "polish" :arguments (json/write-value-as-string args)}]}))))

(deftest polish-belongings-structure-and-explicit-mode
  (testing "deliberate empty kits and ordinary kits without a reference are valid"
    (doseq [objects [[] kit [{:name "Bundle" :desc "A linen bundle."}]]]
      (is (= objects (get-in (check-answer (assoc writer-args :belongings objects)) [:verdict :belongings])))))
  (testing "missing answers and malformed structure are corrected, never judged by prose"
    (is (:error (check-answer writer-args)))
    (doseq [objects [nil {}
                    (vec (repeat 5 {:name "Thing" :desc "An object."}))
                    [{:name "Box" :desc "A box." :container :closed
                      :contents (vec (repeat 6 {:name "Thing" :desc "An object."}))}]
                    [{:name "Thing" :desc "An object." :contents []}]
                    [(assoc (first kit) :container :locked)]
                    [(assoc-in (first kit) [:contents 0 :container] :closed)]
                    [(assoc-in (first kit) [:contents 0 :contents] [])]
                    [(assoc-in (first kit) [:contents 0 :candidate-reference?] "not-a-boolean")]
                    [(assoc-in (first kit) [:contents 0 :candidate-reference?] true)
                     {:name "Other paper" :desc "Another document." :candidate-reference? true}]
                    [{:name " " :desc "An object."}]
                    [{:name "Thing" :desc " "}]
                    [{:name (apply str (repeat 61 "x")) :desc "An object."}]
                    [{:name "Thing" :desc (apply str (repeat 401 "x"))}]
                    [{:name "Thing" :desc "An object." :aspects {:Bad "value"}}]
                    [{:name "Thing" :desc "An object." :aspects {:contents 12}}]
                    [{:name "Thing" :desc "An object." :aspects {:contents " "}}]
                    [{:name "Thing" :desc "An object." :aspects {:contents (apply str (repeat 201 "x"))}}]
                    [{:name "Thing" :desc "An object." :aspects {:a "a" :b "b" :c "c" :d "d" :e "e"}}]]]
      (is (:error (check-answer (assoc writer-args :belongings objects))) (pr-str objects))))
  (testing "unknown state keys are dropped and nested argument spellings normalised"
    (let [objects [{:name " Box " :desc " A box. " :container "closed" :owner "employer" :id "stolen-id"
                    :contents [{:name "Paper" :desc " A letter. " :candidate_reference? true :holder "employer"}]}]
          answer (get-in (check-answer (assoc writer-args :belongings objects)) [:verdict :belongings])]
      (is (= [{:name "Box" :desc "A box." :container :closed
               :contents [{:name "Paper" :desc "A letter." :candidate-reference? true}]}] answer))))
  (testing "reference markers require candidate context, including false flags"
    (doseq [flag [true false]]
      (is (:error (check-answer (assoc arrival-options :arrival-context {:kind :guest})
                               (assoc writer-args :belongings [{:name "Paper" :desc "A paper." :candidate-reference? flag}]))))))
  (testing "default callers neither require nor consume unsolicited belongings"
    (let [result (check-answer {} (assoc writer-args :belongings kit))
          request (people/polish-request {:record {}})
          enabled (people/polish-request (assoc arrival-options :record {}))]
      (is (= writer-args (:verdict result)))
      (is (not (contains? (get-in request [:tools 0 :function :parameters :properties]) :belongings)))
      (is (some #{:belongings "belongings"} (get-in enabled [:tools 0 :function :parameters :required])))
      (is (not (contains? (:args (people/mock-polish {:record {}})) :belongings)))
      (is (contains? (:args (people/mock-polish (assoc arrival-options :record {}))) :belongings))))
  (testing "malformed JSON still yields the normal checked-answer correction"
    (let [request (people/polish-request (assoc arrival-options :record {}))]
      (is (:error ((:check request) {:tool-calls [{:id "bad" :name "polish" :arguments "{"}]})))))
  (testing "the public appearance writer never receives pending descriptions"
    (let [request (people/appearance-request (merge {:age 25 :sex "female" :position "housemaid"}
                                                   {:belongings kit :pending-belongings kit :circumstances [letter-text]}))]
      (is (= {:age 25 :sex "female" :position "housemaid" :small-senses []} (get-in request [:ctx :record])))
      (is (not (str/includes? (pr-str (dissoc request :check)) letter-text)))))
  (testing "England guidance is pack-supplied, not imposed on other settings"
    (is (str/includes? (people/instructions {} :first-arrival) "servant's box"))
    (is (not (str/includes? (people/instructions {:state {:setting-pack (settings/load-pack "settings/qing-palace")}} :first-arrival)
                           "servant's box")))))

(defn items-start [changes]
  (let [init (:init (hiring-test/scenario))]
    (hiring-test/start
     (hiring/install (merge (hiring-test/scenario) {:items {}
             :init (fn [opts] (assoc (init opts) :clock {:start "1904-10-15T08:00" :minutes-per-round 30}))
             :adjudicators (assoc-in hiring/adjudicators [:people/polish :mock]
                                     (constantly {:tool :polish :args (assoc writer-args :belongings kit)}))}
                            changes))
     {})))

(defn item-by-name [world name]
  (first (filter #(= name (:name %)) (items/all world))))

(deftest candidate-first-arrival-private-once-and-return-persistence
  (let [policy (fn [{:keys [world agent-id used] :as ctx}]
                 (let [id (:agent-id (hiring-test/first-person world))
                       box (:id (item-by-name world "Servant's box"))
                       letter (:id (item-by-name world "Written character"))]
                   (cond
                     (and (= id agent-id) (= 4 (:round world)))
                     (cond
                       (not (some #{"open"} used)) {:tool :open :args {:container box}}
                       (not (some #{"take"} used)) {:tool :take :args {:item letter}}
                       (not (some #{"give"} used)) {:tool :give :args {:item letter :to "employer"}}
                       (not (some #{"put-down"} used)) {:tool :put-down :args {:item box}}
                       :else {:tool :pass})
                     (and (= id agent-id) (= 5 (:round world)))
                     {:tool :take-leave :args {:return-date "1904-10-15" :return-time "11:00"}}
                     :else (hiring-test/policy ctx))))
        run (items-start {:mock-policy policy})]
    (try
      (hiring-test/rounds! run 1)
      (let [world (e/world run) p (hiring-test/first-person world)
            calls (store/read-llm (:dir run))]
        (is (= kit (:pending-belongings p)))
        (is (not (contains? (:record p) :belongings)))
        (is (empty? (items/all world)))
        (is (= 2 (count (filter #(str/starts-with? (:agent %) "adjudicator/people") calls))))
        (doseq [id ["employer" "recommender" "bystander"]]
          (is (not (str/includes? (pr-str (prompt/agent-view (:scenario run) world id)) letter-text)))
          (is (not (str/includes? (pr-str (filter #(w/visible-to? % id) (e/events run))) letter-text))))
        (let [result (hiring/recommend! world "recommender" {:position "housemaid"}
                                       {:bridge (fn [& _] (throw (ex-info "must reuse" {})))
                                        :adjudicate (fn [& _] (throw (ex-info "must not rewrite" {})))})]
          (is (= kit (get-in result [:candidate :pending-belongings])))))
      (hiring-test/rounds! run 1)
      (let [world (assoc (e/world run) :round 3)
            capped (assoc-in world [:state :hiring-max-agents] 3)]
        (is (empty? (hiring/tick (:scenario run) capped)))
        (is (= kit (:pending-belongings (hiring-test/first-person capped))))
        (is (empty? (items/all capped))))
      (hiring-test/rounds! run 1)
      (let [world (e/world run) p (hiring-test/first-person world) id (:agent-id p)
            box (item-by-name world "Servant's box")
            letter (item-by-name world "Written character")
            additions (filter #(= :item/added (:type %)) (e/events run))]
        (is (= 3 (count additions)))
        (is (every? #(= #{id} (:visible-to %)) additions))
        (is (nil? (:pending-belongings p)))
        (is (= #{id} (set (map :owner (items/all world)))))
        (is (= id (:holder box)))
        (is (= (:id box) (:in letter)))
        (is (= (:id p) (:candidate-id letter)))
        (is (items/identity-bound? letter))
        (is (not (items/identity-bound? box)))
        (is (= {:contents "Spare linen and mending things."} (:aspects box)))
        (is (= 1 (count (items/contents world (:id box)))))
        (is (some #(= (:id box) (:id %)) (get-in (prompt/agent-view (:scenario run) world id) [:you :carrying])))
        (doseq [viewer ["employer" "recommender" "bystander"]]
          (is (str/includes? (pr-str (prompt/agent-view (:scenario run) world viewer)) (:name box)))
          (is (not (str/includes? (pr-str (prompt/agent-view (:scenario run) world viewer)) letter-text)))
          (is (not (str/includes? (pr-str (item-views/look world viewer)) letter-text)))
          (is (not (str/includes? (pr-str (filter #(w/visible-to? % viewer) (e/events run))) letter-text))))
        (is (= kit (get-in (first (filter #(= :people/pooled (:type %)) (e/events run))) [:data :pending-belongings])))
        (is (empty? (hiring/tick (:scenario run) world))))
      (hiring-test/rounds! run 1)
      (let [world (e/world run)
            letter (item-by-name world "Written character")
            box (item-by-name world "Servant's box")
            id (:agent-id (hiring-test/first-person world))
            candidate-calls (filter #(and (= id (:agent %)) (= 4 (:round %))) (store/read-llm (:dir run)))]
        (is (= "employer" (:holder letter)))
        (is (= "gate" (:place box)))
        (is (str/includes? (get-in (first candidate-calls) [:replies 0 :content]) letter-text))
        (is (not-any? #(str/starts-with? (:content %) "error:") (mapcat :replies candidate-calls))))
      (hiring-test/rounds! run 2)
      (let [world (e/world run)
            id (:agent-id (hiring-test/first-person world))]
        (is (= :away (:status (w/agent world id))))
        (is (= id (:holder (item-by-name world "Keepsake"))))
        (doseq [faculty [:movement :consciousness]]
          (let [blocked (-> world (assoc :round 7)
                            (assoc-in [:agents id :physical faculty] {:level :disabled}))]
            (is (empty? (hiring/tick (:scenario run) blocked)))
            (is (= (items/all world) (items/all blocked)))
            (is (nil? (:pending-belongings (hiring-test/first-person blocked)))))))
      (hiring-test/rounds! run 1)
      (let [world (e/world run) p (hiring-test/first-person world) id (:agent-id p)
            spawned (filter #(and (= :agent/spawned (:type %)) (= id (get-in % [:data :agent :id]))) (e/events run))]
        (is (= :active (:status (w/agent world id))))
        (is (= :here (:status p)))
        (is (nil? (:pending-belongings p)))
        (is (= 1 (count spawned)))
        (is (= 3 (count (items/all world))))
        (is (= 3 (count (filter #(= :item/added (:type %)) (e/events run)))))
        (is (= "employer" (:holder (item-by-name world "Written character"))))
        (is (= "gate" (:place (item-by-name world "Servant's box"))))
        (is (= id (:holder (item-by-name world "Keepsake"))))
        (is (= 2 (count (filter #(str/starts-with? (:agent %) "adjudicator/people") (store/read-llm (:dir run))))))
        (is (not-any? #(#{:action/forfeited :adjudication/failed} (:type %)) (e/events run)))
        (hiring-test/replay! run)
        (testing "load and branch consume recorded events with no generation"
          (with-redefs [e/load-scenario (constantly (:scenario run))
                        people/polish-request (fn [& _] (throw (ex-info "no writing during replay" {})))]
            (let [loaded (e/load-run (:id run) {:test? true :llm :mock :offline? true})
                  branch (e/branch-run run 7)]
              (try
                (is (= world (e/world loaded)))
                (is (= world (e/world branch)))
                (hiring-test/rounds! loaded 1)
                (is (= (items/all world) (items/all (e/world loaded))))
                (is (nil? (:pending-belongings (hiring-test/first-person (e/world loaded)))))
                (finally (e/stop-run loaded) (e/stop-run branch)))))))
      (finally (e/stop-run run)))))

(deftest pooled-descriptions-survive-load-and-branch-without-regeneration
  (let [run (items-start {})]
    (try
      (hiring-test/rounds! run 1)
      (with-redefs [e/load-scenario (constantly (:scenario run))
                    people/polish-request (fn [& _] (throw (ex-info "no rewriting pooled candidates" {})))]
        (let [loaded (e/load-run (:id run) {:test? true :llm :mock :offline? true})
              branch (e/branch-run run 1)]
          (try
            (doseq [copy [loaded branch]]
              (is (= (e/world run) (e/world copy)))
              (is (= kit (:pending-belongings (hiring-test/first-person (e/world copy)))))
              (is (empty? (items/all (e/world copy)))))
            (hiring-test/rounds! branch 2)
            (is (= 3 (count (items/all (e/world branch)))))
            (is (nil? (:pending-belongings (hiring-test/first-person (e/world branch)))))
            (hiring-test/replay! branch)
            (finally (e/stop-run loaded) (e/stop-run branch)))))
      (finally (e/stop-run run)))))

(deftest empty-and-reference-free-kits-are-consumed
  (doseq [objects [[] [{:name "Bundle" :desc "A plain bundle." :aspects {:contents "Ordinary personal things."}}]]]
    (let [run (items-start {:adjudicators (assoc-in hiring/adjudicators [:people/polish :mock]
                                                  (constantly {:tool :polish :args (assoc writer-args :belongings objects)}))})]
      (try
        (hiring-test/rounds! run 3)
        (is (nil? (:pending-belongings (hiring-test/first-person (e/world run)))))
        (is (= (count objects) (count (items/all (e/world run)))))
        (is (not-any? :candidate-id (items/all (e/world run))))
        (hiring-test/replay! run)
        (finally (e/stop-run run))))))

(deftest enabled-mock-writer-and-checked-retry
  (doseq [[mock expected-polish-calls]
          [[people/mock-polish 1]
           [(fn [{:keys [step]}]
              {:tool :polish :args (cond-> writer-args (pos? step) (assoc :belongings kit))}) 2]]]
    (let [run (items-start {:adjudicators (assoc-in hiring/adjudicators [:people/polish :mock] mock)})]
      (try
        (hiring-test/rounds! run 1)
        (let [world (e/world run)
              p (hiring-test/first-person world)
              calls (filter #(= "adjudicator/people/polish" (:agent %)) (store/read-llm (:dir run)))]
          (is (seq (:pending-belongings p)))
          (is (= expected-polish-calls (count calls)))
          (is (empty? (items/all world))))
        (hiring-test/rounds! run 2)
        (let [world (e/world run)
              p (hiring-test/first-person world)
              references (filter :candidate-id (items/all world))]
          (is (seq (items/held-by world (:agent-id p))))
          (is (= [(:id p)] (mapv :candidate-id references)))
          (is (nil? (:pending-belongings p)))
          (is (not-any? #(= :adjudication/failed (:type %)) (e/events run))))
        (hiring-test/replay! run)
        (finally (e/stop-run run))))))

(deftest other-writer-callers-do-not-consume-unsolicited-items
  (let [calls (atom [])
        services {:adjudicate (fn [task args]
                               (swap! calls conj [task args])
                               (if (= :people/appearance task)
                                 {:appearance "Public appearance."}
                                 (assoc writer-args :belongings kit)))}
        ;; Guests/tradespeople pass notes, couriers use the two-argument arity.
        p {:record {:position "visitor"}}]
    (doseq [notes [nil "An authored guest or tradesperson."]]
      (let [written (hiring/write-person services p notes)]
        (is (not (contains? written :pending-belongings)))
        (is (not (contains? written :belongings)))
        (is (not (contains? (hiring/spawn-spec written) :belongings)))))
    (is (every? #(not (contains? (second %) :belongings-mode)) @calls)))
  (let [run (hiring-test/start {:adjudicators (assoc-in hiring/adjudicators [:people/polish :mock]
                                                        (constantly {:tool :polish :args (assoc writer-args :belongings kit)}))} {})]
    (try
      (hiring-test/rounds! run 3)
      (is (not (items/enabled? (e/world run))))
      (is (not (contains? (hiring-test/first-person (e/world run)) :pending-belongings)))
      (hiring-test/replay! run)
      (finally (e/stop-run run)))))

(deftest materialisation-allocates-against-prior-and-gone-items
  (let [world {:round 2 :agents {"person" {:id "person"}}
               :items {} :items-gone {"box" {:name "Box"}}}
        events (belongings/arrival-events world "person"
                                          [{:name "Box" :desc "An open box." :container :open
                                            :contents [{:name "Box" :desc "A small object." :consumable true}]}
                                           {:name "Box" :desc "Another object."}]
                                          {})
        after (reduce w/apply-event world events)]
    (is (= ["box-2" "box-3" "box-4"] (mapv #(get-in % [:data :item :id]) events)))
    (is (= "box-2" (:in (items/item after "box-3"))))
    (is (true? (:consumable? (items/item after "box-3"))))
    (is (= #{"person"} (set (map :holder (items/held-by after "person")))))
    (let [visible (w/apply-event after {:type :item/put-down :data {:item "box-2" :place "gate"}})]
      (is (= ["box-2" "box-3"] (mapv :id (items/visible-at visible "gate")))))))
