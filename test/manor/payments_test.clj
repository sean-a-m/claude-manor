(ns manor.payments-test
  "Paying people (docs/payments-design.md): `pay`, its checks, the purse it
  opens, the household book, a line in a conversation, replay, and the
  `Paid:` line the payments that pay by themselves end with. On the test
  household (test/fixtures/household.edn: £3,000 in old pence, nobody with
  a purse but the architect)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [manor.actions :as a]
            [manor.engine :as e]
            [manor.io.store :as store]
            [manor.mechanics.payments :as payments]
            [manor.mechanics.purchases :as purchases]
            [manor.narrate :as n]
            [manor.prompt :as prompt]
            [manor.ruleset :as ruleset]
            [manor.ruleset.mock :as mock]
            [manor.ruleset.rules :as rules]
            [manor.scenarios.household :as household]
            [manor.test-support :as support]
            [manor.world :as w]))

(use-fixtures :once support/with-runs)

(defn- start-with
  ([day] (start-with household/scenario day))
  ([scenario day]
   (with-redefs [e/load-scenario (constantly (assoc scenario :mock-policy #(mock/scripted-policy day %)))]
     (e/start-run :household {:test? true :llm :mock :offline? true :gm :off :max-cost 0.20
                              :run-id (str (gensym "payments-"))}))))

(defn- until! [run r]
  (e/auto! run (- r (:round (e/world run))))
  (is (= :idle (e/wait-idle run 60000))))

(defn- action [run tool] (first (filter #(= tool (:name %)) (:actions run))))
(defn- funds [world id] (get-in world [:state :accounts id]))
(defn- of-type [run type] (filter #(= type (:type %)) (e/events run)))
(defn- replay! [run] (is (= (e/world run) (w/replay (store/read-events (:dir run))))))

(defn- check [run world actor args]
  (let [spec (action run :pay)
        decoded (a/decode-args spec args)]
    (or (:error decoded) (a/check spec world actor (:args decoded)))))

(defn- pay [run world actor args]
  (let [spec (action run :pay)]
    (when-let [r (check run world actor args)] (throw (ex-info r {})))
    (let [evs (a/effect-events spec world actor (:args (a/decode-args spec args)))]
      [(reduce w/apply-stamped world (map-indexed #(assoc %2 :id (+ 1000 %1)) evs)) evs])))

(defn- together [world ids place]
  (reduce #(assoc-in %1 [:agents %2 :location] place) world ids))

(deftest pay-hands-money-over
  (let [run (start-with {})]
    (try
      (let [world (together (e/world run) ["lady-claudeworth" "hale" "lord-claudeworth"] "study")
            before (funds world "household")
            [after [ev]] (pay run world "lady-claudeworth" {:to "hale" :amount "£8 2s" :for "the carpet and chairs"})
            names (n/names after)]
        (testing "from the household to a purse opened for the payee"
          (is (nil? (funds world "hale")))
          (is (= (- before 1944) (funds after "household")))
          (is (= 1944 (funds after "hale"))))
        (testing "seen by everyone there, and in the one Paid: form"
          (is (= #{"lady-claudeworth" "hale" "lord-claudeworth"} (:visible-to ev)))
          (is (= "Paid: £8 2s from the household account, by Lady Claudeworth to Hale, for the carpet and chairs."
                 (n/describe ev names))))
        (testing "the payee sees their purse; the payer is told what's left"
          (is (= "£8 2s" (get-in (prompt/agent-view (:scenario run) after "hale") [:you :purse])))
          (is (str/starts-with? ((:reply (action run :pay)) after "lady-claudeworth" {:to "hale" :amount "£8 2s" :for "the carpet and chairs"})
                                "Paid: £8 2s from the household account to Hale, for the carpet and chairs. The household account has £")))
        (testing "it goes in the household book"
          (is (= [{:payment "to Hale, for the carpet and chairs" :paid-by "Lady Claudeworth" :amount "£8 2s"}]
                 (map #(select-keys % [:payment :paid-by :amount]) (filter :payment (purchases/household-book after))))))
        (testing "from an own purse: out of it, and not in the household book"
          (let [[again] (pay run after "hale" {:to "lord-claudeworth" :amount "2s" :for "a tip" :from "own"})]
            (is (= (- 1944 24) (funds again "hale")))
            (is (= 24 (funds again "lord-claudeworth")))
            (is (= (funds after "household") (funds again "household")))
            (is (= 1 (count (filter :payment (purchases/household-book again))))))))
      (finally (e/stop-run run)))))

(deftest pay-reaches-someone-elsewhere
  (let [run (start-with {})]
    (try
      (let [world (-> (e/world run) (together ["lady-claudeworth"] "study") (together ["hale"] "kitchen"))
            [after [ev]] (pay run world "lady-claudeworth" {:to "hale" :amount "1s" :for "errands"})]
        (is (= 12 (funds after "hale")))
        (is (= #{"lady-claudeworth" "hale"} (:visible-to ev)))
        (is (str/includes? (n/describe ev (n/names after)) "Hale isn't here; it reaches them where they are.")))
      (finally (e/stop-run run)))))

(deftest checks-on-pay
  (let [run (start-with {})
        world (together (e/world run) ["lady-claudeworth" "hale"] "study")
        c #(check run %1 %2 %3)]
    (try
      (is (str/includes? (c world "hale" {:to "hale" :amount "1s" :for "x"}) "can't pay yourself"))
      (is (str/includes? (c world "hale" {:to "nobody" :amount "1s" :for "x"}) "no one with id nobody"))
      (is (str/includes? (c (assoc-in world [:agents "lady-claudeworth" :status] :dead) "hale" {:to "lady-claudeworth" :amount "1s" :for "x"})
                         "no one with id"))
      (is (str/includes? (c world "hale" {:to "lady-claudeworth" :amount "8" :for "x"}) "bare number"))
      (is (str/includes? (c world "hale" {:to "lady-claudeworth" :amount "0d" :for "x"}) "more than nothing"))
      (is (str/includes? (c world "hale" {:to "lady-claudeworth" :amount "1s" :for "x" :from "own"}) "no personal account"))
      (is (= "you'd pay £5; the household has £1 5s"
             (c (assoc-in world [:state :accounts "household"] 300) "hale" {:to "lady-claudeworth" :amount "£5" :for "x"})))
      (is (some? (c world "hale" {:to "lady-claudeworth" :amount "1s" :for ""})) "for is required")
      (is (nil? (c world "hale" {:to "lady-claudeworth" :amount "1s" :for "a tip"})))
      (finally (e/stop-run run)))))

(deftest pay-is-a-tool-on-turns-and-lines-and-in-the-rules
  (let [run (start-with {})
        world (e/world run)]
    (try
      (is (= :free (a/kind (action run :pay))))
      (is (some #(= :pay (:name %)) ((:speaking-actions (:scenario run)) world "hale" {:id "c" :initiator "hale" :members ["hale" "lady-claudeworth"]})))
      (let [text (rules/rules world)]
        (is (str/includes? text "### Paying people"))
        (is (not (str/includes? text "{{"))))
      (let [doc (a/description (action run :pay) world)]
        (is (str/includes? doc "\"£3 10s\""))
        (is (not (str/includes? doc "{{"))))
      (finally (e/stop-run run)))))

(def tip-day
  "Lady Claudeworth tips Hale on a line of a conversation in the study."
  {"hale" {"08:00" [[:walk {:to "study"}] [:pass {}]]}
   "lady-claudeworth" {"08:00" [[:walk {:to "study"}] [:pass {}]]
                       "08:30" [[:converse {:with ["hale"] :text "A word, Hale."}] [:pass {}]]}})

(defn- tip-talk [{:keys [agent-id used conversation]}]
  (if (and (= "lady-claudeworth" agent-id) (:final? conversation))
    (if (some #{"pay"} used)
      {:tool :end-conversation}
      {:tool :pay :args {:to "hale" :amount "5s" :for "his trouble with the letters"}})
    {:tool :reply :args {:text "My lady?"}}))

(deftest paid-on-a-line-and-replayed
  (let [run (start-with (assoc household/scenario :mock-conversation tip-talk) tip-day)]
    (try
      (until! run 2)
      (let [world (e/world run)
            [ev] (of-type run :money/paid)]
        (is (= {:payer "lady-claudeworth" :payee "hale" :account "household" :amount 60 :for "his trouble with the letters"}
               (:data ev)))
        (is (= 60 (funds world "hale")))
        (is (empty? (of-type run :action/forfeited))))
      (replay! run)
      (finally (e/stop-run run)))))

(deftest the-paid-line
  (let [names (n/names (e/world (let [run (start-with {})] (e/stop-run run) run)))]
    (is (= "Paid: 6d from the household account to the Counter. Nothing more is owed."
           (payments/paid-line names {:amount 6 :account "household" :to "the Counter" :owed ""})))
    (is (= "Paid: 1s from Hale's own purse, for the materials."
           (payments/paid-line names {:amount 12 :account "hale" :for "for the materials"})))))
