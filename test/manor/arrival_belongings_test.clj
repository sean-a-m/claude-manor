(ns manor.arrival-belongings-test
  "First-arrival belongings (docs/arrival-belongings-design.md), for someone
  sent for from elsewhere (docs/sending-for-design.md 4.6): written with
  the contact, brought the first time they come, a written character marked
  with the contact's id."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.walk :as walk]
            [jsonista.core :as json]
            [manor.engine :as e]
            [manor.hiring-test :as hiring-test]
            [manor.io.store :as store]
            [manor.mechanics.hiring :as hiring]
            [manor.mechanics.items :as items]
            [manor.mechanics.items.belongings :as belongings]
            [manor.mechanics.items.views :as item-views]
            [manor.mechanics.people :as people]
            [manor.mechanics.visits :as visits]
            [manor.prompt :as prompt]
            [manor.ruleset.mock :as mock]
            [manor.scenarios.claudeworth :as cw]
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
   :arrival-context {:kind :sent-for :line "housemaid seeking a place" :wanted "a housemaid wanting a place"}})

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
  (testing "reference markers require someone sent for, including false flags"
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

(defn- with-polish [mock]
  {:adjudicators (assoc-in (:adjudicators cw/scenario) [:people/polish :mock] mock)})

(defn- start-kit
  ([] (start-kit hiring-test/housemaid-day (constantly {:tool :polish :args (assoc writer-args :belongings kit)})))
  ([day polish] (hiring-test/start day (with-polish polish) {})))

(defn item-by-name [world name]
  (first (filter #(= name (:name %)) (items/all world))))

(def earl hiring-test/earl)

(deftest first-arrival-private-once-and-return-persistence
  (let [day (-> hiring-test/housemaid-day
                (assoc "@maid" {"23:30" [[:open {:container "@box"}] [:take {:item "@letter"}]
                                         [:give {:item "@letter" :to earl}] [:put-down {:item "@box"}] [:pass {}]]
                                "00:00" [[:take-leave {:return-date "1904-10-16" :return-time "01:00"}]]}))
        policy (fn [{:keys [world] :as ctx}]
                 (let [id (hiring-test/maid world)
                       ids {"@box" (:id (item-by-name world "Servant's box")) "@letter" (:id (item-by-name world "Written character"))}
                       day (-> day (assoc id (get day "@maid")) (dissoc "@maid"))]
                   (mock/scripted-policy (walk/postwalk #(get ids % %) day) ctx)))
        run (hiring-test/start day (merge (with-polish (constantly {:tool :polish :args (assoc writer-args :belongings kit)}))
                                          {:mock-policy policy}) {})]
    (try
      (hiring-test/until! run 2)
      (let [world (e/world run) c (hiring-test/the-contact world)
            calls (store/read-llm (:dir run))]
        (testing "written with the contact, kept privately until she comes"
          (is (= kit (:pending-belongings c)))
          (is (not (contains? (:record c) :belongings)))
          (is (empty? (items/all world)))
          (is (= 2 (count (filter #(str/starts-with? (:agent %) "adjudicator/people") calls))))
          (let [polish (first (filter #(= "adjudicator/people/polish" (:agent %)) calls))]
            (is (= {:kind "sent-for" :line "housemaid seeking a place" :wanted "a housemaid wanting a place"}
                   (:arrival-context (json/read-value (get-in polish [:messages 1 :content]) json/keyword-keys-object-mapper))))))
        (doseq [id [earl "hale" "carter"]]
          (is (not (str/includes? (pr-str (prompt/agent-view (:scenario run) world id)) letter-text)))
          (is (not (str/includes? (pr-str (filter #(w/visible-to? % id) (e/events run))) letter-text)))))
      (testing "while there's no room, she waits, and nothing is made"
        (let [world (assoc (e/world run) :round 7)
              capped (assoc-in world [:state :hiring-max-agents] (count (w/active-agents world)))]
          (is (empty? (visits/tick (:scenario run) capped)))
          (is (empty? (items/all capped)))))
      (hiring-test/until! run 7)
      (let [world (e/world run) c (hiring-test/the-contact world) id (:agent-id c)
            box (item-by-name world "Servant's box")
            letter (item-by-name world "Written character")
            additions (filter #(= :item/added (:type %)) (e/events run))]
        (testing "she arrives carrying them, seen only by her"
          (is (= 3 (count additions)))
          (is (every? #(= #{id} (:visible-to %)) additions))
          (is (not (contains? c :pending-belongings)))
          (is (= #{id} (set (map :owner (items/all world)))))
          (is (= id (:holder box)))
          (is (= (:id box) (:in letter)))
          (is (= {:contents "Spare linen and mending things."} (:aspects box)))
          (is (some #(= (:id box) (:id %)) (get-in (prompt/agent-view (:scenario run) world id) [:you :carrying]))))
        (testing "the written character carries the contact's id"
          (is (= "contact-1" (:candidate-id letter)))
          (is (items/identity-bound? letter))
          (is (not (items/identity-bound? box))))
        (doseq [viewer [earl "hale"]]
          (is (not (str/includes? (pr-str (prompt/agent-view (:scenario run) world viewer)) letter-text)))
          (is (not (str/includes? (pr-str (item-views/look world viewer)) letter-text)))
          (is (not (str/includes? (pr-str (filter #(w/visible-to? % viewer) (e/events run))) letter-text)))))
      (hiring-test/until! run 8)
      (let [world (e/world run)
            id (hiring-test/maid world)
            calls (filter #(and (= id (:agent %)) (= 8 (:round %))) (store/read-llm (:dir run)))]
        (testing "she shows and gives her character with ordinary item actions"
          (is (= earl (:holder (item-by-name world "Written character"))))
          (is (= "carriage-sweep" (:place (item-by-name world "Servant's box"))))
          (is (some #(str/includes? (:content %) letter-text) (mapcat :replies calls)))
          (is (not-any? #(str/starts-with? (:content %) "error:") (mapcat :replies calls)))))
      (hiring-test/until! run 11)
      (let [world (e/world run) id (hiring-test/maid world)]
        (testing "coming back, she brings nothing more: what she left stays where it is"
          (is (= :active (:status (w/agent world id))))
          (is (= 3 (count (items/all world))))
          (is (= 3 (count (filter #(= :item/added (:type %)) (e/events run)))))
          (is (= earl (:holder (item-by-name world "Written character"))))
          (is (= "carriage-sweep" (:place (item-by-name world "Servant's box"))))
          (is (= id (:holder (item-by-name world "Keepsake")))))
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
                (is (= 3 (count (items/all (e/world branch)))))
                (finally (e/stop-run loaded) (e/stop-run branch)))))))
      (finally (e/stop-run run)))))

(deftest empty-and-reference-free-kits-are-consumed
  (doseq [objects [[] [{:name "Bundle" :desc "A plain bundle." :aspects {:contents "Ordinary personal things."}}]]]
    (let [run (start-kit hiring-test/housemaid-day (constantly {:tool :polish :args (assoc writer-args :belongings objects)}))]
      (try
        (hiring-test/until! run 7)
        (is (not (contains? (hiring-test/the-contact (e/world run)) :pending-belongings)))
        (is (= (count objects) (count (items/all (e/world run)))))
        (is (not-any? :candidate-id (items/all (e/world run))))
        (hiring-test/replay! run)
        (finally (e/stop-run run))))))

(deftest enabled-mock-writer-and-checked-retry
  (doseq [[mock expected-polish-calls]
          [[people/mock-polish 1]
           [(fn [{:keys [step]}]
              {:tool :polish :args (cond-> writer-args (pos? step) (assoc :belongings kit))}) 2]]]
    (let [run (start-kit hiring-test/housemaid-day mock)]
      (try
        (hiring-test/until! run 2)
        (let [calls (filter #(= "adjudicator/people/polish" (:agent %)) (store/read-llm (:dir run)))]
          (is (seq (:pending-belongings (hiring-test/the-contact (e/world run)))))
          (is (= expected-polish-calls (count calls))))
        (hiring-test/until! run 7)
        (let [world (e/world run)
              id (hiring-test/maid world)]
          (is (seq (items/held-by world id)))
          (is (= ["contact-1"] (mapv :candidate-id (filter :candidate-id (items/all world)))))
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
        ;; Guests pass notes, couriers use the two-argument arity.
        p {:record {:position "visitor"}}]
    (doseq [notes [nil "An authored guest."]]
      (let [written (hiring/write-person services p notes)]
        (is (not (contains? written :pending-belongings)))
        (is (not (contains? written :belongings)))
        (is (not (contains? (hiring/spawn-spec written) :belongings)))))
    (is (every? #(not (contains? (second %) :belongings-mode)) @calls))))

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
