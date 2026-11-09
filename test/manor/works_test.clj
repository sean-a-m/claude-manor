(ns manor.works-test
  "Works, steps 1a to 2c (docs/fixtures-design.md 7): `plan-work`, the works
  judge and the private plan; offering it with order-work, accept-work,
  decline-work and cancel-work; carrying it out with carry-out; the
  outcome from the scene judge's `work`; staying on task (the early
  reminder, the calendar entry, hibernation, a tradesperson staying) and
  the rules; a scripted day moving the china cabinet; on the test household (test/fixtures/household.edn:
  start 1904-10-15 08:00, half-hour rounds; the piano and the china cabinet
  in the drawing room, Hale's notebook in his pocket)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hiccup2.core :as hiccup]
            [jsonista.core :as json]
            [manor.actions :as a]
            [manor.engine :as e]
            [manor.engine.turn :as turn]
            [manor.io.store :as store]
            [manor.mechanics.calendar :as calendar]
            [manor.mechanics.items :as items]
            [manor.mechanics.items.judge :as item-judge]
            [manor.mechanics.money :as money]
            [manor.mechanics.purchases :as purchases]
            [manor.mechanics.space :as space]
            [manor.mechanics.contacts :as contacts]
            [manor.mechanics.visits :as visits]
            [manor.mechanics.works :as works]
            [manor.mechanics.works.judge :as judge]
            [manor.narrate :as narrate]
            [manor.prompt :as prompt]
            [manor.ruleset :as ruleset]
            [manor.ruleset.hibernation :as hibernation]
            [manor.ruleset.mock :as mock]
            [manor.ruleset.rules :as rules]
            [manor.scenarios.claudeworth :as cw]
            [manor.scenarios.household :as household]
            [manor.test-support :as support]
            [manor.world :as w]))

(use-fixtures :once support/with-runs)
(use-fixtures :each (partial support/with-structured-attempts 4))

(defn- start-with [scenario day]
  (with-redefs [e/load-scenario (constantly (assoc scenario :mock-policy #(mock/scripted-policy day %)))]
    (e/start-run :household {:test? true :llm :mock :offline? true :gm :off :max-cost 0.20
                             :run-id (str (gensym "works-"))})))

(defn- until! [run r]
  (let [n (- r (:round (e/world run)))]
    (when (pos? n)
      (e/auto! run n)
      (is (= :idle (e/wait-idle run 60000))))
    (is (= r (:round (e/world run))))))

(defn- with-judge [scenario mock] (assoc-in scenario [:adjudicators :works/plan :mock] mock))
(defn- of-type [run type] (filter #(= type (:type %)) (e/events run)))
(defn- replay! [run] (is (= (e/world run) (w/replay (store/read-events (:dir run))))))
(defn- action [run tool] (first (filter #(= tool (:name %)) (:actions run))))
(defn- judge-calls [run] (filter #(= "adjudicator/works/plan" (:agent %)) (store/read-llm (:dir run))))
(defn- judge-input [call] (json/read-value (:content (second (:messages call))) json/keyword-keys-object-mapper))
(defn- replies-to [run agent tool]
  (for [call (store/read-llm (:dir run)) :when (= agent (:agent call))
        tc (get-in call [:response :tool_calls]) :when (= tool (get-in tc [:function :name]))
        r (:replies call) :when (= (:id tc) (:tool_call_id r))]
    (:content r)))

(defn- check [run world actor tool args]
  (let [spec (action run tool)
        decoded (a/decode-args spec args)]
    (or (:error decoded) (a/check spec world actor (:args decoded)))))

(def piano-day
  "Hale goes to the drawing room and plans the piano moved to the dining room."
  {"hale" {"08:00" [[:walk {:to "drawing-room"}]
                    [:plan-work {:kind "move" :target "piano" :to "dining-room"
                                 :request "the piano moved to the dining room"}]
                    [:pass {}]]}})

(deftest a-plan-is-made-and-seen-only-by-the-planner
  (let [run (start-with household/scenario piano-day)]
    (try
      (until! run 1)
      (let [world (e/world run)
            plan (works/work-order world "work-1")
            [ev] (of-type run :works/planned)
            input (judge-input (first (judge-calls run)))]
        (testing "the judge saw the request, the place, the target and the destination"
          (is (= {:kind "move" :request "the piano moved to the dining room" :to "Dining room"}
                 (select-keys input [:kind :request :to])))
          (is (= "Piano" (get-in input [:target :name])))
          (is (= "Drawing room" (get-in input [:place :name])))
          (is (= "Hale" (get-in input [:planner :name]))))
        (testing "the record holds the plan, one phase of two hours"
          (is (= {:id "work-1" :planner "hale" :kind :move :place "drawing-room" :target "piano" :to "dining-room"
                  :status :planned :known-to #{"hale"} :estimate 0 :materials 0}
                 (select-keys plan [:id :planner :kind :place :target :to :status :known-to :estimate :materials])))
          (is (= [{:minutes 120 :done 0}] (map #(select-keys % [:minutes :done]) (:phases plan)))))
        (testing "only the planner sees it, and the reply says to use order-work"
          (is (= #{"hale"} (:visible-to ev)))
          (is (str/includes? (first (replies-to run "hale" "plan-work")) "order-work with id \"work-1\"")))
        (testing "it's under works in his view, not in the earl's"
          (is (= [{:id "work-1" :kind "move" :summary (:summary plan) :who (:who plan) :status "planned"
                   :where "From the Drawing room (drawing-room) to the Dining room (dining-room)."}]
                 (:works (prompt/agent-view (:scenario run) world "hale"))))
          (is (nil? (:works (prompt/agent-view (:scenario run) world "lord-claudeworth"))))))
      (replay! run)
      (finally (e/stop-run run)))))

(deftest a-refusal-makes-no-plan
  (let [run (start-with (with-judge household/scenario (fn [_] {:tool :work_plan :args {:refused "Tuning is upkeep: use spend-time."}}))
                        {"hale" {"08:00" [[:walk {:to "drawing-room"}]
                                          [:plan-work {:kind "install" :request "the piano tuned"}]
                                          [:pass {}]]}})]
    (try
      (until! run 1)
      (is (empty? (works/work-orders (e/world run))))
      (is (= ["No plan was made: Tuning is upkeep: use spend-time."] (replies-to run "hale" "plan-work")))
      (finally (e/stop-run run)))))

(deftest a-malformed-plan-gets-a-retry-with-the-reason
  (let [calls (atom 0)
        good (judge/mock-judge {:input {:kind "remove" :request "the cabinet taken away" :target {:name "China cabinet"}}})
        mock (fn [_] (if (= 1 (swap! calls inc))
                       {:tool :work_plan :args (assoc-in (:args good) [:phases 0 :minutes] 480)}
                       good))
        run (start-with (with-judge household/scenario mock)
                        {"hale" {"08:00" [[:walk {:to "drawing-room"}]
                                          [:plan-work {:kind "remove" :target "china-cabinet" :request "the cabinet taken away"}]
                                          [:pass {}]]}})]
    (try
      (until! run 1)
      (is (= 2 @calls))
      (is (= {:kind :remove :fate "carted away"} (select-keys (works/work-order (e/world run) "work-1") [:kind :fate])))
      (finally (e/stop-run run)))))

(deftest checks-on-plan-work
  (let [run (start-with household/scenario {})
        world (-> (e/world run) (assoc-in [:agents "hale" :location] "drawing-room"))
        c #(check run world "hale" :plan-work %)]
    (try
      (is (nil? (c {:kind "move" :target "piano" :to "dining-room" :request "x"})))
      (is (nil? (c {:kind "install" :request "a range"})))
      (is (some? (c {:kind "alter" :target "piano" :request "x"})))
      (is (str/starts-with? (c {:kind "remove" :request "x"}) "a remove needs target"))
      (is (str/starts-with? (c {:kind "move" :target "piano" :request "x"}) "a move needs to"))
      (is (str/includes? (c {:kind "move" :target "piano" :to "general-store" :request "x"}) "not a place in the home"))
      (is (str/starts-with? (c {:kind "move" :target "piano" :to "drawing-room" :request "x"}) "it is here already"))
      (is (str/includes? (c {:kind "remove" :target "kitchen-range" :request "x"}) "isn't here"))
      (is (str/includes? (c {:kind "remove" :target "pocket-notebook" :request "x"}) "being carried"))
      (is (str/starts-with? (c {:kind "remove" :target "nothing" :request "x"}) "there is no thing"))
      (is (str/includes? (check run (assoc-in world [:items "piano" :order-id] "order-1") "hale" :plan-work
                                {:kind "remove" :target "piano" :request "x"})
                         "can't be worked on"))
      (is (str/includes? (check run (assoc-in world [:items "piano" :store] {:keeps "music"}) "hale" :plan-work
                                {:kind "remove" :target "piano" :request "x"})
                         "stands for supplies"))
      (testing "the cap on open plans"
        (let [full (reduce (fn [wd i] (w/apply-event wd (works/planned wd "hale" {:kind "install" :request (str "thing " i)}
                                                                         (:args (judge/mock-judge {:input {:kind "install" :request "x"}})))))
                           world (range works/max-open-plans))]
          (is (= ["work-1" "work-2" "work-3"] (map :id (works/open-plans full "hale"))))
          (is (str/includes? (check run full "hale" :plan-work {:kind "install" :request "a range"}) "plans not yet given"))
          (is (nil? (check run full "lord-claudeworth" :plan-work {:kind "install" :request "a range"})))))
      (finally (e/stop-run run)))))

(deftest the-judge-checks-structure
  (let [run (start-with household/scenario {})
        world (e/world run)
        ok (:args (judge/mock-judge {:input {:kind "install" :request "a range"}}))
        verdict #(judge/check-answer world %1 %2)]
    (try
      (is (= {:refused "No."} (verdict "install" {:refused " No. "})))
      (is (= "Install a range." (get-in (verdict "install" ok) [:plan :summary])) (pr-str (verdict "install" ok)))
      (is (some? (:error (verdict "install" (dissoc ok :item)))))
      (is (some? (:error (verdict "remove" (dissoc ok :item)))))
      (is (some? (:error (verdict "install" (assoc ok :materials 999)))))
      (is (some? (:error (verdict "install" (assoc ok :estimate 99999999 :materials 0)))))
      (is (some? (:error (verdict "install" (update ok :phases conj (first (:phases ok)))))))
      (is (some? (:error (verdict "install" (assoc-in ok [:item :aspects] {:state (apply str (repeat 300 "x"))})))))
      (finally (e/stop-run run)))))

;; ---------------------------------------------------------------------------
;; Step 1b: offering

(def offer-day
  "Hale plans the piano moved and offers it to Lady Claudeworth, who has come to the
  drawing room before him; she accepts on her next turn."
  {"hale" {"08:00" [[:walk {:to "drawing-room"}]
                    [:plan-work {:kind "move" :target "piano" :to "dining-room"
                                 :request "the piano moved to the dining room"}]
                    [:order-work {:id "work-1" :worker "lady-claudeworth"}]
                    [:pass {}]]}
   "lady-claudeworth" {"08:00" [[:walk {:to "drawing-room"}] [:pass {}]]
             "08:30" [[:accept-work {:id "work-1"}] [:pass {}]]}})

(deftest a-work-is-offered-and-accepted
  (let [run (start-with household/scenario offer-day)]
    (try
      (until! run 2)
      (let [world (e/world run)
            wo (works/work-order world "work-1")
            [offer] (of-type run :works/offered)
            [acc] (of-type run :works/accepted)
            names (narrate/names world)]
        (is (= {:status :accepted :orderer "hale" :worker "lady-claudeworth" :price 0 :priced-by "hale" :known-to #{"hale" "lady-claudeworth"}}
               (select-keys wo [:status :orderer :worker :price :priced-by :known-to])))
        (testing "the offer and the answer are seen by the two of them only"
          (is (= #{"hale" "lady-claudeworth"} (:visible-to offer) (:visible-to acc))))
        (testing "the offer shows Lady Claudeworth the plan, its recommendation, the price, and how to answer"
          (let [text (narrate/describe offer names)]
            (is (str/includes? text (:summary wo)))
            (is (str/includes? text (:who wo)))
            (is (str/includes? text "0d"))
            (is (str/includes? text "accept-work"))))
        (testing "both see it in their views, the earl doesn't"
          (is (= {:id "work-1" :status "accepted" :ordered-by "Hale" :worker "you" :price "0d"}
                 (select-keys (first (:works (prompt/agent-view (:scenario run) world "lady-claudeworth")))
                              [:id :status :ordered-by :worker :price])))
          (is (= {:status "accepted" :worker "Lady Claudeworth"}
                 (select-keys (first (:works (prompt/agent-view (:scenario run) world "hale"))) [:status :worker :ordered-by])))
          (is (nil? (:works (prompt/agent-view (:scenario run) world "lord-claudeworth")))))
        (is (= ["You have accepted work work-1 at 0d: it is yours to carry out."] (replies-to run "lady-claudeworth" "accept-work"))))
      (replay! run)
      (finally (e/stop-run run)))))

(defn- act
  "`world` after `actor` does `tool` with `args`, checked as the engine
  checks it; throws with the reason if refused."
  [run world actor tool args]
  (let [spec (action run tool)]
    (when-let [r (check run world actor tool args)] (throw (ex-info r {:tool tool})))
    (reduce w/apply-event world (a/effect-events spec world actor (:args (a/decode-args spec args))))))

(defn- planned-world
  "The household with Hale, Lady Claudeworth and the earl in the drawing room, and
  Hale's plan work-1 to install something (materials 480)."
  [run]
  (let [world (reduce #(assoc-in %1 [:agents %2 :location] "drawing-room") (e/world run)
                      ["hale" "lady-claudeworth" "lord-claudeworth"])]
    (w/apply-event world (works/planned world "hale" {:kind "install" :request "a range"}
                                        (:args (judge/mock-judge {:input {:kind "install" :request "a range"}}))))))

(deftest decline-then-offer-to-someone-else
  (let [run (start-with household/scenario {})]
    (try
      (let [world (planned-world run)
            offered (act run world "hale" :order-work {:id "work-1" :worker "lady-claudeworth" :price "£2 10s"})
            declined (act run offered "lady-claudeworth" :decline-work {:id "work-1"})
            again (act run declined "hale" :order-work {:id "work-1" :worker "lord-claudeworth"})]
        (is (= {:status :offered :worker "lady-claudeworth" :price 600} (select-keys (works/work-order offered "work-1") [:status :worker :price])))
        (is (= :planned (:status (works/work-order declined "work-1"))))
        (is (nil? (:worker (works/work-order declined "work-1"))))
        (testing "the orderer is told of the decline"
          (let [ev (first (a/effect-events (action run :decline-work) offered "lady-claudeworth" {:id "work-1"}))]
            (is (= #{"hale" "lady-claudeworth"} (:visible-to ev)))
            (is (str/includes? (narrate/describe ev (narrate/names offered)) "declines"))))
        (testing "Lady Claudeworth still knows the plan, but it isn't hers to answer any more"
          (is (contains? (:known-to (works/work-order again "work-1")) "lady-claudeworth"))
          (is (str/includes? (check run again "lady-claudeworth" :accept-work {:id "work-1"}) "only they can accept it")))
        (is (= {:status :offered :worker "lord-claudeworth" :price 480}
               (select-keys (works/work-order again "work-1") [:status :worker :price])))
        (is (= :accepted (:status (works/work-order (act run again "lord-claudeworth" :accept-work {:id "work-1"}) "work-1")))))
      (finally (e/stop-run run)))))

(deftest cancel-and-self-order
  (let [run (start-with household/scenario {})]
    (try
      (let [world (planned-world run)
            offered (act run world "hale" :order-work {:id "work-1" :worker "lady-claudeworth"})]
        (testing "the planner cancels an offered work; the worker can't"
          (is (str/includes? (check run offered "lady-claudeworth" :cancel-work {:id "work-1"}) "only the person who planned"))
          (let [ev (first (a/effect-events (action run :cancel-work) offered "hale" {:id "work-1"}))
                done (w/apply-event offered ev)]
            (is (= #{"hale" "lady-claudeworth"} (:visible-to ev)))
            (is (= :cancelled (:status (works/work-order done "work-1"))))
            (is (nil? (:works (prompt/agent-view (:scenario run) done "hale"))))
            (is (str/includes? (check run done "hale" :order-work {:id "work-1" :worker "lady-claudeworth"}) "is cancelled"))))
        (testing "ordering to yourself takes it on at once"
          (let [ev (first (a/effect-events (action run :order-work) world "hale" {:id "work-1" :worker "hale"}))
                mine (w/apply-event world ev)]
            (is (= :works/accepted (:type ev)))
            (is (= #{"hale"} (:visible-to ev)))
            (is (= {:status :accepted :orderer "hale" :worker "hale" :price 480}
                   (select-keys (works/work-order mine "work-1") [:status :orderer :worker :price])))
            (is (str/includes? (check run mine "hale" :cancel-work {:id "work-1"}) "not yet accepted")))))
      (finally (e/stop-run run)))))

(deftest checks-on-offering
  (let [run (start-with household/scenario {})
        world (planned-world run)
        c #(check run %1 %2 %3 %4)]
    (try
      (is (nil? (c world "hale" :order-work {:id "work-1" :worker "lady-claudeworth"})))
      (testing "an unknown plan, or one the actor doesn't know"
        (is (str/includes? (c world "hale" :order-work {:id "work-9" :worker "lady-claudeworth"}) "you know of no work work-9"))
        (is (str/includes? (c world "lady-claudeworth" :order-work {:id "work-1" :worker "hale"}) "you know of no work")))
      (testing "the worker must be here, or the actor"
        (is (str/includes? (c (assoc-in world [:agents "lady-claudeworth" :location] "kitchen") "hale" :order-work {:id "work-1" :worker "lady-claudeworth"})
                           "is here with you"))
        (is (str/includes? (c world "hale" :order-work {:id "work-1" :worker "nobody"}) "is here with you")))
      (testing "the price covers the materials"
        (is (str/includes? (c world "hale" :order-work {:id "work-1" :worker "lady-claudeworth" :price "8s 4d"}) "must cover the materials"))
        (is (nil? (c world "hale" :order-work {:id "work-1" :worker "lady-claudeworth" :price "£2"}))))
      (testing "only the worker accepts an order, with the orderer present"
        (let [offered (act run world "hale" :order-work {:id "work-1" :worker "lady-claudeworth"})]
          (is (str/includes? (c offered "hale" :accept-work {:id "work-1"}) "you set the price"))
          (is (str/includes? (c offered "lord-claudeworth" :accept-work {:id "work-1"}) "you know of no work"))
          (is (str/includes? (c (assoc-in offered [:agents "hale" :location] "kitchen") "lady-claudeworth" :accept-work {:id "work-1"})
                             "must be here with you to settle"))
          (is (nil? (c (assoc-in offered [:agents "hale" :location] "kitchen") "lady-claudeworth" :decline-work {:id "work-1"})))
          (is (str/includes? (c offered "hale" :order-work {:id "work-1" :worker "lord-claudeworth"}) "is offered to Lady Claudeworth"))))
      (finally (e/stop-run run)))))

(deftest offering-on-a-speaking-turn
  (let [run (start-with household/scenario {})
        world (planned-world run)
        tools (ruleset/speaking-actions world "hale" {:members ["hale" "lady-claudeworth"]})
        order (first (filter #(= :order-work (:name %)) tools))]
    (try
      (is (every? (set (map :name tools)) [:order-work :revise-work :accept-work :decline-work]))
      (is (nil? (a/check order world "hale" {:id "work-1" :worker "lady-claudeworth"})))
      (is (nil? (a/check order world "hale" {:id "work-1" :worker "hale"})))
      (is (str/includes? (a/check order world "hale" {:id "work-1" :worker "lord-claudeworth"}) "someone in this conversation"))
      (finally (e/stop-run run)))))

;; ---------------------------------------------------------------------------
;; Step 2a: prices and payment

(defn- funds [world account] (get-in world [:state :accounts account]))
(defn- view-work [run world id] (first (:works (prompt/agent-view (:scenario run) world id))))

(deftest a-price-is-haggled-and-paid
  (let [run (start-with household/scenario {})]
    (try
      (let [world (planned-world run)
            before (funds world "household")
            ordered (act run world "hale" :order-work {:id "work-1" :worker "lady-claudeworth"})
            up (act run ordered "lady-claudeworth" :revise-work {:id "work-1" :price "£3 15s"})
            down (act run up "hale" :revise-work {:id "work-1" :price "£3"})
            rev-ev (first (a/effect-events (action run :revise-work) ordered "lady-claudeworth" {:id "work-1" :price "£3 15s"}))
            acc-evs (a/effect-events (action run :accept-work) down "lady-claudeworth" {:id "work-1"})
            done (reduce w/apply-event down acc-evs)]
        (testing "the order is at the estimate, paid from the household, and the worker is to answer"
          (is (= {:price 480 :priced-by "hale" :account "household"}
                 (select-keys (works/work-order ordered "work-1") [:price :priced-by :account])))
          (is (= {:price "£2" :priced-by "Hale" :paid-from "household"}
                 (select-keys (view-work run ordered "lady-claudeworth") [:price :priced-by :paid-from])))
          (is (str/starts-with? (:next (view-work run ordered "lady-claudeworth")) "answer with accept-work"))
          (is (str/starts-with? (:next (view-work run ordered "hale")) "waiting for Lady Claudeworth")))
        (testing "the worker asks more: seen by both, and the orderer is to answer"
          (is (= #{"hale" "lady-claudeworth"} (:visible-to rev-ev)))
          (let [text (narrate/describe rev-ev (narrate/names ordered))]
            (is (str/includes? text "£3 15s"))
            (is (str/includes? text "Hale is to answer")))
          (is (= {:price 900 :priced-by "lady-claudeworth"} (select-keys (works/work-order up "work-1") [:price :priced-by])))
          (is (= "you" (:priced-by (view-work run up "lady-claudeworth"))))
          (is (str/starts-with? (:next (view-work run up "hale")) "answer with accept-work"))
          (is (str/includes? (check run up "lady-claudeworth" :accept-work {:id "work-1"}) "you set the price"))
          (is (nil? (check run up "hale" :accept-work {:id "work-1"}))))
        (testing "the orderer comes down; now the worker answers"
          (is (str/includes? (check run down "hale" :accept-work {:id "work-1"}) "you set the price"))
          (is (nil? (check run down "lady-claudeworth" :accept-work {:id "work-1"}))))
        (testing "accepted: the household pays the price, the worker's purse gets the labour"
          (is (= :accepted (:status (works/work-order done "work-1"))))
          (is (= (- before 720) (funds done "household")))
          (is (= 240 (funds done "lady-claudeworth")))
          (is (str/includes? (narrate/describe (first acc-evs) (narrate/names down))
                             "Paid: £3 from the household account, for work work-1, £1 of it to Lady Claudeworth for the labour; the materials are there. Nothing more is owed for this work."))
          (is (= "in full when accepted; nothing more is owed for it" (:paid (view-work run done "hale")))))
        (testing "an offer isn't paid yet"
          (is (nil? (:paid (view-work run ordered "hale"))))))
      (finally (e/stop-run run)))))

(deftest checks-on-prices
  (let [run (start-with household/scenario {})
        world (planned-world run)
        ordered (act run world "hale" :order-work {:id "work-1" :worker "lady-claudeworth"})
        c #(check run %1 %2 %3 %4)]
    (try
      (testing "a revised price covers the materials and stays under the bound"
        (is (str/includes? (c ordered "lady-claudeworth" :revise-work {:id "work-1" :price "8s 4d"}) "must cover the materials"))
        (is (str/includes? (c ordered "hale" :revise-work {:id "work-1" :price (money/display (money/spec world) (inc (judge/max-estimate world)))}) "at most")))
      (testing "only the two sides, together, while it's offered"
        (is (str/includes? (c ordered "lord-claudeworth" :revise-work {:id "work-1" :price "£2 10s"}) "you know of no work"))
        (is (str/includes? (c (assoc-in ordered [:agents "hale" :location] "kitchen") "lady-claudeworth" :revise-work {:id "work-1" :price "£2 10s"})
                           "must be here with you"))
        (is (str/includes? (c world "hale" :revise-work {:id "work-1" :price "£2 10s"}) "isn't on offer")))
      (testing "an account that can't cover the price: the orderer hears the balance, the worker doesn't"
        (let [poor (assoc-in ordered [:state :accounts "household"] 300)
              up (act run poor "lady-claudeworth" :revise-work {:id "work-1" :price "£2 10s"})]
          (is (= "the household account can't cover £2" (c poor "lady-claudeworth" :accept-work {:id "work-1"})))
          (is (= "it costs £2 10s; the household has £1 5s" (c up "hale" :accept-work {:id "work-1"})))))
      (testing "an own purse: the orderer must have one"
        (is (str/includes? (c world "hale" :order-work {:id "work-1" :worker "lady-claudeworth" :from "own"}) "no personal account")))
      (testing "the worker never hears the orderer's balance"
        (let [own (act run (assoc-in world [:state :accounts "hale"] 1000) "hale" :order-work
                       {:id "work-1" :worker "lady-claudeworth" :from "own"})
              broke (assoc-in own [:state :accounts "hale"] 100)
              reason (c broke "lady-claudeworth" :accept-work {:id "work-1"})]
          (is (= "Hale's purse can't cover £2" reason))
          (is (not (str/includes? reason "8s 4d")))))
      (finally (e/stop-run run)))))

(deftest paid-from-an-own-purse
  (let [run (start-with household/scenario {})]
    (try
      (let [world (assoc-in (planned-world run) [:state :accounts "hale"] 1000)
            hh (funds world "household")
            done (-> (act run world "hale" :order-work {:id "work-1" :worker "lady-claudeworth" :price "£2 10s" :from "own"})
                     (as-> w (act run w "lady-claudeworth" :accept-work {:id "work-1"})))]
        (is (= "hale" (:account (works/work-order done "work-1"))) "recorded as the account's owner")
        (is (= 400 (funds done "hale")))
        (is (= hh (funds done "household")))
        (is (= 120 (funds done "lady-claudeworth")))
        (is (= "Hale's purse" (:paid-from (view-work run done "lady-claudeworth")))))
      (finally (e/stop-run run)))))

(deftest a-self-order-pays-the-materials-at-once
  (let [run (start-with household/scenario {})]
    (try
      (let [world (assoc-in (planned-world run) [:state :accounts "hale"] 1000)
            hh (funds world "household")
            own (act run world "hale" :order-work {:id "work-1" :worker "hale" :from "own"})
            household (act run world "hale" :order-work {:id "work-1" :worker "hale" :price "£3 15s"})]
        (is (= {:status :accepted :price 480 :account "hale"} (select-keys (works/work-order own "work-1") [:status :price :account])))
        (is (= 520 (funds own "hale")))
        (is (= hh (funds own "household")))
        (testing "from the household: the materials only, and no labour to himself"
          (is (= (- hh 480) (funds household "household")))
          (is (= 1000 (funds household "hale"))))
        (testing "an account that can't cover the materials"
          (is (str/includes? (check run (assoc-in world [:state :accounts "hale"] 100) "hale" :order-work {:id "work-1" :worker "hale" :from "own"})
                             "your purse has"))))
      (finally (e/stop-run run)))))

(def haggle-day
  "Hale plans a range and orders it to Lady Claudeworth at the estimate; she asks more,
  he comes down, she accepts. Replayed."
  {"hale" {"08:00" [[:walk {:to "drawing-room"}]
                    [:plan-work {:kind "install" :request "a new range"}]
                    [:order-work {:id "work-1" :worker "lady-claudeworth"}]
                    [:pass {}]]
           "08:30" [[:revise-work {:id "work-1" :price "£3"}] [:pass {}]]}
   "lady-claudeworth" {"08:00" [[:walk {:to "drawing-room"}] [:pass {}]]
                       "08:30" [[:revise-work {:id "work-1" :price "£3 15s"}] [:pass {}]]
                       "09:00" [[:accept-work {:id "work-1"}] [:pass {}]]}})

(deftest a-haggled-day-replays
  (let [run (start-with household/scenario haggle-day)]
    (try
      (until! run 3)
      (let [world (e/world run)]
        (is (= {:status :accepted :price 720 :priced-by "hale"}
               (select-keys (works/work-order world "work-1") [:status :price :priced-by])))
        (is (= 240 (funds world "lady-claudeworth")))
        (is (= 2 (count (of-type run :works/revised)))))
      (replay! run)
      (finally (e/stop-run run)))))

;; ---------------------------------------------------------------------------
;; Step 2b: the order book

(defn- work-entries [world] (filter :work (purchases/household-book world)))

(deftest prices-are-money-text
  (let [run (start-with household/scenario {})
        world (planned-world run)
        c #(check run world "hale" :order-work (assoc {:id "work-1" :worker "lady-claudeworth"} :price %))]
    (try
      (is (nil? (c "£2 10s")))
      (is (= 600 (:price (works/work-order (act run world "hale" :order-work {:id "work-1" :worker "lady-claudeworth" :price "£2 10s"}) "work-1"))))
      (is (str/includes? (c "600") "a bare number"))
      (is (str/includes? (c "600") "\"£3 10s\""))
      (is (str/includes? (c "two pounds") "isn't an amount of money"))
      (is (str/includes? (:error (a/decode-args (action run :order-work) {:id "work-1" :worker "x" :price 600})) "price"))
      (is (str/includes? ((:doc (action run :order-work)) world) "\"£3 10s\""))
      (finally (e/stop-run run)))))

(deftest household-works-are-in-its-book
  (let [run (start-with household/scenario {})]
    (try
      (let [world (planned-world run)
            ordered (act run world "hale" :order-work {:id "work-1" :worker "lady-claudeworth" :price "£3"})
            accepted (act run ordered "lady-claudeworth" :accept-work {:id "work-1"})
            failed (w/apply-event accepted {:type :works/failed :data {:id "work-1" :kind :install :outcome :failed :reason :judged}})]
        (is (empty? (work-entries ordered)) "an offer isn't in the book")
        (testing "accepted: what, who, the price, agreed and paid"
          (let [[e] (work-entries accepted)]
            (is (str/starts-with? (:work e) "work-1: "))
            (is (= {:done-by "Lady Claudeworth" :ordered-by "Hale" :price "£3" :status "agreed and paid, to be carried out"}
                   (select-keys e [:done-by :ordered-by :price :status])))))
        (testing "the household's members see it in their view, and the judge's books"
          (is (= 1 (count (filter :work (get-in (prompt/agent-view (:scenario run) accepted "hale") [:household :orders])))))
          (is (= 1 (count (filter :work (:household_orders (purchases/judge-books accepted ["hale"])))))))
        (is (str/starts-with? (:status (first (work-entries failed))) "came to nothing")))
      (finally (e/stop-run run)))))

(deftest own-purse-works-are-not-in-the-household-book
  (let [run (start-with household/scenario {})]
    (try
      (let [world (assoc-in (planned-world run) [:state :accounts "hale"] 1000)
            done (-> (act run world "hale" :order-work {:id "work-1" :worker "lady-claudeworth" :price "£2 10s" :from "own"})
                     (as-> w (act run w "lady-claudeworth" :accept-work {:id "work-1"})))]
        (is (= :accepted (:status (works/work-order done "work-1"))))
        (is (empty? (work-entries done))))
      (finally (e/stop-run run)))))

;; ---------------------------------------------------------------------------
;; Step 1c: carry-out

(def carry-day
  "Hale plans the piano moved, takes it on himself and carries it out: two
  hours, rounds 1 to 5."
  {"hale" {"08:00" [[:walk {:to "drawing-room"}]
                    [:plan-work {:kind "move" :target "piano" :to "dining-room"
                                 :request "the piano moved to the dining room"}]
                    [:order-work {:id "work-1" :worker "hale"}]
                    [:carry-out {:id "work-1"}]]}})

(deftest a-phase-is-carried-out-in-full
  (let [run (start-with household/scenario carry-day)]
    (try
      (until! run 1)
      (let [world (e/world run)
            [begun] (of-type run :item/work-begun)]
        (testing "an ordinary item job with the work-order reference and the phase's text"
          (is (= {:actor "hale" :items ["piano"] :place "drawing-room" :started 1 :due 5
                  :work-order {:id "work-1" :phase 0}
                  :doing (get-in (works/work-order world "work-1") [:phases 0 :doing])}
                 (select-keys (:data begun) [:actor :items :place :started :due :work-order :doing])))
          (is (= (get-in begun [:data :id]) (:job (w/busy world "hale")))))
        (testing "the view shows the phase and its progress"
          (is (= {:phase (str "1 of 1: " (get-in (works/work-order world "work-1") [:phases 0 :doing]))
                  :progress "0 of 120 minutes done"}
                 (select-keys (first (:works (prompt/agent-view (:scenario run) world "hale"))) [:phase :progress]))))
        (testing "nobody else can start it while it's going on"
          (is (str/includes? (check run world "hale" :carry-out {:id "work-1"}) "already being carried out"))))
      (until! run 5)
      (let [world (e/world run)
            wo (works/work-order world "work-1")]
        (is (= 1 (count (of-type run :item/work-done))))
        (is (= [{:id "work-1" :phase 0}] (map :data (of-type run :works/phase-done))))
        (is (= #{"hale"} (:visible-to (first (of-type run :works/phase-done)))))
        (is (= :done (:status wo)) "the default mock scene judge says as planned")
        (is (= 120 (get-in wo [:phases 0 :done]))))
      (replay! run)
      (finally (e/stop-run run)))))

(defn- accepted-world
  "planned-world's work-1, a move of the piano to the dining room, taken on
  by Hale himself."
  [run]
  (let [world (reduce #(assoc-in %1 [:agents %2 :location] "drawing-room") (e/world run)
                      ["hale" "lady-claudeworth"])
        world (w/apply-event world (works/planned world "hale" {:kind "move" :target "piano" :to "dining-room" :request "the piano moved"}
                                                  (:args (judge/mock-judge {:input {:kind "move" :request "the piano moved"}}))))]
    (act run world "hale" :order-work {:id "work-1" :worker "hale"})))

(deftest checks-on-carry-out
  (let [run (start-with household/scenario {})
        world (accepted-world run)
        c #(check run %1 %2 :carry-out {:id %3})]
    (try
      (is (nil? (c world "hale" "work-1")))
      (testing "the view tells the worker to carry it out"
        (is (str/includes? (:next (first (:works (prompt/agent-view (:scenario run) world "hale")))) "carry-out with id \"work-1\"")))
      (is (str/includes? (c world "hale" "work-9") "you know of no work"))
      (testing "not the worker"
        (let [offered (act run (planned-world run) "hale" :order-work {:id "work-1" :worker "lady-claudeworth"})
              accepted (act run offered "lady-claudeworth" :accept-work {:id "work-1"})]
          (is (str/includes? (c accepted "hale" "work-1") "isn't yours"))
          (testing "not accepted"
            (is (str/includes? (c offered "lady-claudeworth" "work-1") "only an accepted work")))))
      (testing "the wrong place: where the target is now"
        (is (str/includes? (c (assoc-in world [:agents "hale" :location] "dining-room") "hale" "work-1") "go there first"))
        (let [moved (-> world (assoc-in [:items "piano" :place] "dining-room") (assoc-in [:agents "hale" :location] "dining-room"))]
          (is (nil? (c moved "hale" "work-1")))))
      (testing "the target gone"
        (is (str/includes? (c (update world :items dissoc "piano") "hale" "work-1") "no longer there")))
      (finally (e/stop-run run)))))

(deftest stopped-early-then-resumed
  (let [run (start-with household/scenario {})
        world (accepted-world run)]
    (try
      (let [begun (act run world "hale" :carry-out {:id "work-1"})
            job (works/job-for begun "work-1")
            stop (works/after-job begun job {:outcome :stopped :minutes 60 :stopped-early? true} {})
            ;; the job's ending removes it, as :item/work-done does
            stopped (reduce w/apply-event (update begun :item-jobs dissoc (:id job)) stop)]
        (is (= 4 (- (:due job) (:started job))))
        (is (= [{:id "work-1" :phase 0 :minutes 120 :done 60}] (map :data stop)))
        (is (= 60 (get-in (works/work-order stopped "work-1") [:phases 0 :done])))
        (is (= :accepted (:status (works/work-order stopped "work-1"))))
        (is (str/includes? (narrate/describe (first stop) (narrate/names stopped)) "60 of 120"))
        (testing "carry-out again resumes the remaining hour"
          (let [again (works/job-for (act run stopped "hale" :carry-out {:id "work-1"}) "work-1")]
            (is (= 2 (- (:due again) (:started again))))
            (is (= {:id "work-1" :phase 0} (:work-order again)))))
        (testing "no time worked, a dropped job or another job records nothing"
          (is (nil? (works/after-job begun job {:outcome :stopped :minutes 0} {})))
          (is (nil? (works/after-job begun job {:outcome :dropped :minutes 0} {})))
          (is (nil? (works/after-job begun (dissoc job :work-order) {:outcome :finished :minutes 120} {})))))
      (finally (e/stop-run run)))))

;; ---------------------------------------------------------------------------
;; Step 1d: the outcome

(defn- with-scene-judge
  "The scenario with a mock scene judge: the default verdict, with `work`
  from (f ctx) for a work's job (nil leaves it out)."
  [scenario f]
  (assoc scenario :mock-judge
         (fn [ctx]
           (let [{:keys [args] :as answer} (item-judge/mock-judge ctx)
                 work (when (get-in ctx [:job :work-order]) (f ctx))]
             (assoc answer :args (cond-> (dissoc args :work) work (assoc :work work)))))))

(defn- scene-judge-calls [run] (filter #(= "judge" (:agent %)) (store/read-llm (:dir run))))

(deftest the-piano-is-moved
  (let [run (start-with (with-scene-judge household/scenario (constantly {:outcome "as-planned"})) carry-day)]
    (try
      (until! run 5)
      (let [world (e/world run)
            [done] (of-type run :works/done)
            call (first (scene-judge-calls run))]
        (testing "the scene judge saw the work and was asked for its outcome"
          (is (str/includes? (get-in call [:messages 1 :content]) "## The work"))
          (is (str/includes? (get-in call [:messages 1 :content]) "phase 1 of 1"))
          (is (str/includes? (get-in call [:messages 1 :content]) "To: Dining room"))
          (is (str/includes? (get-in call [:messages 0 :content]) "only through `work`")))
        (is (= "dining-room" (:place (items/item world "piano"))))
        (is (:fixed? (items/item world "piano")))
        (is (= {:status :done :outcome :as-planned} (select-keys (works/work-order world "work-1") [:status :outcome])))
        (is (contains? (:visible-to done) "hale"))
        (is (= "Hale finishes work work-1: the Piano is moved to the Dining room."
               (narrate/describe done (narrate/names world))))
        (is (= "done as planned: now in the Dining room"
               (:result (first (:works (prompt/agent-view (:scenario run) world "hale")))))))
      (replay! run)
      (finally (e/stop-run run)))))

(def install-day
  "Hale plans a range for the kitchen, takes it on himself and carries it out."
  {"hale" {"08:00" [[:walk {:to "kitchen"}]
                    [:plan-work {:kind "install" :request "a new range"}]
                    [:order-work {:id "work-1" :worker "hale"}]
                    [:carry-out {:id "work-1"}]]}})

(deftest a-range-is-installed
  (let [run (start-with (with-scene-judge household/scenario (constantly {:outcome "as-planned"})) install-day)]
    (try
      (until! run 5)
      (let [world (e/world run)
            wo (works/work-order world "work-1")
            it (items/item world (:made wo))]
        (is (= {:name "New fitting" :place "kitchen" :fixed? true :owner "household"}
               (select-keys it [:name :place :fixed? :owner])))
        (is (= :done (:status wo)))
        (is (= 1 (count (filter #(= "New fitting" (:name %)) (items/all world))))))
      (replay! run)
      (finally (e/stop-run run)))))

(deftest a-flawed-install-takes-the-judges-thing
  (let [run (start-with (with-scene-judge household/scenario
                          (constantly {:outcome "flawed"
                                       :item {:name "Crooked range" :desc "A range set in askew."
                                              :aspects {"repair" "one oven door sticks"}}}))
                        install-day)]
    (try
      (until! run 5)
      (let [world (e/world run)
            wo (works/work-order world "work-1")
            it (items/item world (:made wo))]
        (is (= {:name "Crooked range" :place "kitchen" :fixed? true :aspects {:repair "one oven door sticks"}}
               (select-keys it [:name :place :fixed? :aspects])))
        (is (= {:status :done :outcome :flawed} (select-keys wo [:status :outcome])))
        (is (str/includes? (narrate/describe (first (of-type run :works/done)) (narrate/names world)) "not as well")))
      (replay! run)
      (finally (e/stop-run run)))))

(deftest a-failed-install-makes-nothing
  (let [run (start-with (with-scene-judge household/scenario (constantly {:outcome "failed"})) install-day)]
    (try
      (until! run 5)
      (let [world (e/world run)
            [failed] (of-type run :works/failed)]
        (is (= :failed (:status (works/work-order world "work-1"))))
        (is (empty? (filter #(= "New fitting" (:name %)) (items/all world))))
        (is (empty? (of-type run :works/done)))
        (is (= "Work work-1 by Hale comes to nothing: no New fitting is put in, in the Kitchen."
               (narrate/describe failed (narrate/names world))))
        (is (= "failed: nothing came of it" (:result (first (:works (prompt/agent-view (:scenario run) world "hale")))))))
      (replay! run)
      (finally (e/stop-run run)))))

(deftest a-missing-work-entry-gets-a-retry
  (let [calls (atom 0)
        run (start-with (with-scene-judge household/scenario
                          (fn [_] (when (< 1 (swap! calls inc)) {:outcome "as-planned"})))
                        carry-day)]
    (try
      (until! run 5)
      (is (= 2 @calls))
      (is (str/includes? (str (:replies (first (scene-judge-calls run)))) "this job carries out a work"))
      (is (= "dining-room" (:place (items/item (e/world run) "piano"))))
      (finally (e/stop-run run)))))

(deftest the-work-entry-is-only-for-works
  (let [run (start-with household/scenario {})
        world (e/world run)
        job {:id "job-9" :actor "hale" :items ["piano"] :place "drawing-room" :doing "dusting" :started 0 :due 2}
        things [(assoc (select-keys (items/item world "piano") [:id :name :desc :aspects]) :fixed? true)]
        answer (fn [args] {:tool-calls [{:id "c1" :name "judge" :arguments (assoc args :summary "Done.")}]})]
    (try
      (testing "an ordinary job's tool has no work, and a work given is dropped"
        (is (not (contains? (get-in (item-judge/judge-request (:scenario run) world job {:minutes 60 :luck 50})
                                    [:tools 0 :function :parameters :properties])
                            :work)))
        (let [accepted (act run (accepted-world run) "hale" :carry-out {:id "work-1"})
              request (item-judge/judge-request (:scenario run) accepted (works/job-for accepted "work-1") {:minutes 120 :luck 50})]
          (is (contains? (get-in request [:tools 0 :function :parameters :properties]) :work))
          (is (str/includes? (get-in request [:tools 0 :function :description]) "how the work turned out"))
          (is (str/includes? (get-in request [:messages 1 :content]) "The worker is: Private secretary")))
        (is (nil? (:work (:verdict (item-judge/judgement (answer {:work {:outcome "failed"}}) world things nil job))))))
      (testing "a work's job needs it, well formed"
        (let [wjob (assoc job :work-order {:id "work-1" :phase 0})]
          (is (str/includes? (:error (item-judge/judgement (answer {}) world things nil wjob)) "give work"))
          (is (some? (:error (item-judge/judgement (answer {:work {:outcome "superb"}}) world things nil wjob))))
          (is (= {:outcome :flawed :item {:name "X" :desc "Y."}}
                 (:work (:verdict (item-judge/judgement (answer {:work {:outcome "flawed" :item {:name " X " :desc "Y."}}})
                                                        world things nil wjob)))))))
      (finally (e/stop-run run)))))

(defn- finished
  "`world` with work `id`'s current phase carried out by Hale and judged
  with `work` (nil: the judge failed): the job's ending applied, then the
  after-job events."
  [run world id work]
  (let [begun (act run world "hale" :carry-out {:id id})
        job (works/job-for begun id)
        ended (update begun :item-jobs dissoc (:id job))
        evs (works/after-job ended job {:outcome :finished :minutes 120} (when work {:verdict {:work work}}))]
    [(reduce w/apply-event ended evs) evs]))

(defn- with-dresser
  "The drawing room with a dresser holding a jug."
  [world]
  (-> world
      (assoc-in [:items "dresser"] {:id "dresser" :name "Dresser" :desc "An oak dresser." :place "drawing-room"
                                    :fixed? true :container :open})
      (assoc-in [:items "jug"] {:id "jug" :name "Jug" :desc "A blue jug." :in "dresser"})))

(deftest a-dresser-is-removed-its-contents-left
  (let [run (start-with household/scenario {})]
    (try
      (let [world (-> (with-dresser (e/world run))
                      (assoc-in [:agents "hale" :location] "drawing-room")
                      (assoc-in [:agents "lady-claudeworth" :location] "drawing-room"))
            world (w/apply-event world (works/planned world "hale" {:kind "remove" :target "dresser" :request "the dresser gone"}
                                                      (:args (judge/mock-judge {:input {:kind "remove" :request "the dresser gone"}}))))
            world (act run world "hale" :order-work {:id "work-1" :worker "hale"})
            [after [_ done]] (finished run world "work-1" {:outcome :as-planned})]
        (is (nil? (items/item after "dresser")))
        (is (= "carted away" (get-in after [:items-gone "dresser" :fate])))
        (is (= {:place "drawing-room"} (select-keys (items/item after "jug") [:place :in])))
        (is (contains? (:visible-to done) "lady-claudeworth"))
        (is (= "Hale finishes work work-1: the Dresser is gone (carted away); what was in it is left in the Drawing room."
               (narrate/describe done (narrate/names after)))))
      (finally (e/stop-run run)))))

(deftest a-target-gone-fails-the-work
  (let [run (start-with household/scenario {})]
    (try
      (let [world (accepted-world run)
            begun (act run world "hale" :carry-out {:id "work-1"})
            job (works/job-for begun "work-1")
            gone (-> begun (update :item-jobs dissoc (:id job)) (items/remove-items ["piano"] "burnt"))
            [_ failed] (works/after-job gone job {:outcome :finished :minutes 120} {:verdict {:work {:outcome :as-planned}}})
            after (w/apply-event gone failed)]
        (is (= :works/failed (:type failed)))
        (is (= {:status :failed :failure :gone} (select-keys (works/work-order after "work-1") [:status :failure])))
        (is (str/includes? (narrate/describe failed (narrate/names after)) "Piano it was for is no longer there")))
      (testing "a failed judge counts as done as planned"
        (let [[after _] (finished run (accepted-world run) "work-1" nil)]
          (is (= "dining-room" (:place (items/item after "piano"))))))
      (testing "a failed move leaves it where it was"
        (let [[after _] (finished run (accepted-world run) "work-1" {:outcome :failed})]
          (is (= "drawing-room" (:place (items/item after "piano"))))
          (is (= :failed (:status (works/work-order after "work-1"))))))
      (finally (e/stop-run run)))))

;; ---------------------------------------------------------------------------
;; Step 1e: staying on task and the rules

(deftest the-early-reminder-mentions-accepted-work
  (let [run (start-with household/scenario {})
        world (accepted-world run)
        remind (:early-reminder (:scenario run))]
    (try
      (testing "nothing before the work is accepted, or for someone else"
        (is (nil? (remind (planned-world run) "hale")))
        (is (nil? (remind world "lady-claudeworth"))))
      (testing "accepted and not yet finished: one sentence, with the id, the place and carry-out"
        (is (= "You have accepted work work-1, not yet finished: use `carry-out` with id \"work-1\" in the Drawing room (drawing-room)."
               (remind world "hale"))))
      (testing "added to the early reminder only"
        (let [early (turn/turn-reminder world 2 8 ["pass"] (remind world "hale"))]
          (is (str/includes? early "turn-ending action"))
          (is (str/ends-with? early "in the Drawing room (drawing-room).")))
        (is (not (str/includes? (turn/turn-reminder world 7 8 ["pass"] (remind world "hale")) "work-1"))))
      (testing "not while it's being carried out, nor once done"
        (is (nil? (remind (act run world "hale" :carry-out {:id "work-1"}) "hale")))
        (is (nil? (remind (first (finished run world "work-1" {:outcome :as-planned})) "hale"))))
      (finally (e/stop-run run)))))

(def calendar-household
  "The test household with a calendar."
  (ruleset/scenario {:name :household :defaults {:world-file household/world-file :leave-out ["nellie"]}}))

(deftest the-worker-gets-a-calendar-entry-until-the-work-is-done
  (let [run (start-with calendar-household {})
        world (accepted-world run)
        entry #(get-in % [:calendar :entries "works-work-1"])]
    (try
      (is (nil? (entry (planned-world run))))
      (is (= {:date "1904-10-15" :visible-to #{"hale"} :managed-by :works}
             (select-keys (entry world) [:date :visible-to :managed-by])))
      (is (str/includes? (calendar/character-section world "hale") "Carry out work work-1: "))
      (is (str/includes? (calendar/character-section world "hale") "(in the Drawing room)"))
      (is (not (str/includes? (calendar/character-section world "lady-claudeworth") "work-1")))
      (testing "offered and accepted, it's the worker's"
        (let [offered (act run (planned-world run) "hale" :order-work {:id "work-1" :worker "lady-claudeworth"})
              accepted (act run offered "lady-claudeworth" :accept-work {:id "work-1"})]
          (is (nil? (entry offered)))
          (is (= #{"lady-claudeworth"} (:visible-to (entry accepted))))))
      (testing "nobody else may edit it"
        (is (thrown-with-msg? Exception #"managed by works"
                              (calendar/command-events world :cancel {:id "works-work-1"}))))
      (testing "removed when the work is done or fails"
        (doseq [work [{:outcome :as-planned} {:outcome :failed}]]
          (let [[after evs] (finished run world "work-1" work)]
            (is (some #(= :calendar/cancelled (:type %)) evs))
            (is (= :cancelled (get-in (entry after) [:lifecycle :status])))
            (is (not (str/includes? (calendar/character-section after "hale") "work-1"))))))
      (finally (e/stop-run run)))))

(deftest open-work-keeps-pugh-in-play
  (let [run (start-with household/scenario {})
        world (assoc (e/world run) :round 5)
        given #(assoc-in world [:state :work-orders "work-1"] {:id "work-1" :n 1 :worker "pugh" :status %})]
    (try
      (is (some? (hibernation/hibernate world "pugh")))
      (is (nil? (hibernation/hibernate (given :accepted) "pugh")))
      (is (nil? (hibernation/hibernate (given :finishing) "pugh")))
      (is (some? (hibernation/hibernate (given :done) "pugh")))
      (is (some? (hibernation/hibernate (given :offered) "pugh")))
      (finally (e/stop-run run)))))

(deftest fixed-things-point-to-plan-work
  (let [run (start-with household/scenario {})
        world (reduce #(assoc-in %1 [:agents %2 :location] "drawing-room") (e/world run) ["hale"])]
    (try
      (is (= "the Piano is fixed in place; to move it, plan a work with plan-work"
             (check run world "hale" :take {:item "piano"})))
      (testing "the rules say so"
        (let [text (rules/rules world)]
          (is (str/includes? text "### Works"))
          (is (str/includes? text "`plan-work` first, then `order-work`"))))
      (finally (e/stop-run run)))))

(def tuner-day
  "Hale recommends a piano tuner from elsewhere to the earl, who sends for him."
  {"hale" {"20:00" [[:travel {:to "post-office"}]]
           "20:30" [[:recommend {:wanted "a piano tuner" :for "lord-claudeworth"}] [:pass {}]]}
   "lord-claudeworth" {"20:00" [[:pass {}]]
                       "20:30" [[:pass {}]]
                       "21:00" [[:send-for {:id "contact-1" :about "tune the drawing-room piano"}]]}})

(defn- start-trades
  "The trades test world (test/fixtures/visits.edn), Claudeworth's scenario, `day` scripted."
  [day]
  (with-redefs [e/load-scenario (constantly (assoc cw/scenario :mock-policy #(mock/scripted-policy day %)))]
    (e/start-run :claudeworth {:test? true :llm :mock :offline? true :gm :off :max-cost 0.20
                               :world-file "test/fixtures/visits.edn" :run-id (str (gensym "works-trades-"))})))

(deftest a-tradesperson-with-open-work-may-still-take-leave
  (let [run (start-trades tuner-day)]
    (try
      (until! run 9)
      (let [world (e/world run)
            tuner (:agent-id (contacts/contact world "contact-1"))
            given (assoc-in world [:state :work-orders "work-1"] {:id "work-1" :n 1 :worker tuner :status :accepted})]
        (is (= "carriage-sweep" (space/location-of world tuner)) "he has arrived")
        (is (works/open-work? given tuner))
        (is (nil? (check run given tuner :take-leave {})) "take-leave stays allowed: he decides when to go"))
      (finally (e/stop-run run)))))

;; ---------------------------------------------------------------------------
;; Step 1f: a scripted day, end to end

(def move-day
  "Lady Claudeworth plans the china cabinet moved to the dining room and
  orders it to Nellie, who accepts, looks about (so the early reminder comes),
  and carries it out: two hours from 09:30."
  {"lady-claudeworth" {"08:00" [[:walk {:to "drawing-room"}] [:pass {}]]
                       "08:30" [[:plan-work {:kind "move" :target "china-cabinet" :to "dining-room"
                                             :request "the china cabinet moved to the dining room"}]
                                [:order-work {:id "work-1" :worker "nellie"}]
                                [:pass {}]]}
   "nellie" {"08:00" [[:walk {:to "drawing-room"}] [:pass {}]]
             "09:00" [[:accept-work {:id "work-1"}] [:look {}] [:pass {}]]
             "09:30" [[:carry-out {:id "work-1"}]]}})

(def move-household
  "The test household with Nellie and a calendar."
  (ruleset/scenario {:name :household :defaults {:world-file household/world-file :leave-out []}}))

(defn- html [x] (str (hiccup/html x)))

(deftest a-scripted-day-moves-the-cabinet
  (let [run (start-with move-household move-day)]
    (try
      (until! run 5)
      (testing "the scene view shows the work under way: listed, a badge on its room, in the inspectors"
        (let [world (e/world run)
              hooks (:scene (:scenario run))
              toolbar (html ((:toolbar hooks) world))]
          (is (str/includes? toolbar "Works: "))
          (is (str/includes? toolbar "1 open"))
          (is (str/includes? toolbar "work-1"))
          (is (str/includes? toolbar "Nellie"))
          (is (some #(str/starts-with? % "1 work") ((:badges hooks) world "drawing-room")))
          (is (empty? ((:badges hooks) world "dining-room")) "the badge is on the room the work is done in")
          (is (str/includes? (html ((:inspect hooks) world "place" "drawing-room")) "Works here"))
          (is (str/includes? (html ((:inspect hooks) world "agent" "nellie")) "work-1"))
          (is (str/includes? (html ((:inspect hooks) world "agent" "lady-claudeworth")) "work-1"))
          (is (nil? ((:inspect hooks) world "agent" "hale")) "nobody else's works")))
      (until! run 9)
      (testing "once it's done, the scene view folds it away with its result"
        (let [world (e/world run)
              hooks (:scene (:scenario run))
              toolbar (html ((:toolbar hooks) world))]
          (is (str/includes? toolbar "0 open, 1 finished or cancelled"))
          (is (str/includes? toolbar "done as planned"))
          (is (empty? ((:badges hooks) world "drawing-room")))))
      (let [world (e/world run)
            calls (store/read-llm (:dir run))
            reminders (for [c calls :when (= "nellie" (:agent c)) r (:replies c)
                            :when (str/includes? (:content r "") "You have accepted work work-1, not yet finished")]
                        (:content r))
            entry (get-in world [:calendar :entries "works-work-1"])]
        (testing "no forfeits or refused calls"
          (is (empty? (of-type run :action/forfeited)))
          (is (not-any? #(re-find #"^(error:|rejected:)" (:content % "")) (mapcat :replies calls))))
        (testing "planned, offered, accepted, carried out and done"
          (is (= [:works/planned :works/offered :works/accepted :works/phase-done :works/done]
                 (map :type (filter #(= "works" (namespace (:type %))) (e/events run)))))
          (is (= {:status :done :outcome :as-planned :orderer "lady-claudeworth" :worker "nellie"}
                 (select-keys (works/work-order world "work-1") [:status :outcome :orderer :worker]))))
        (testing "the cabinet is in the dining room, still fixed"
          (is (= "dining-room" (:place (items/item world "china-cabinet"))))
          (is (:fixed? (items/item world "china-cabinet"))))
        (testing "the early reminder came once, while the work was accepted and not begun"
          (is (= 1 (count reminders)))
          (is (str/includes? (first reminders) "in the Drawing room (drawing-room)")))
        (testing "the household's book has the work, done"
          (let [[e] (work-entries world)]
            (is (= {:done-by "Nellie" :ordered-by "Lady Claudeworth" :price "0d"} (select-keys e [:done-by :ordered-by :price])))
            (is (str/starts-with? (:status e) "done at "))))
        (testing "Nellie's calendar entry was made and is cancelled now"
          (is (= #{"nellie"} (:visible-to entry)))
          (is (= :cancelled (get-in entry [:lifecycle :status])))))
      (replay! run)
      (finally (e/stop-run run)))))

;; ---------------------------------------------------------------------------
;; Step 2c: tradespeople

(deftest a-plan-names-the-trade-it-wants
  (let [run (start-with household/scenario {})
        world (planned-world run)
        wo (works/work-order world "work-1")
        c #(judge/check-answer world "install" %)
        ok (:args (judge/mock-judge {:input {:kind "install" :request "a range"}}))]
    (try
      (testing "the mock wants a gas fitter for an install, the household for a move"
        (is (= {:household false :trade "gas fitter"} (select-keys wo [:household :trade])))
        (is (true? (get-in (judge/mock-judge {:input {:kind "move" :request "x"}}) [:args :household]))))
      (testing "the plan says how to find one, and the view names the trade"
        (let [text (works/describe-plan wo (narrate/names world))]
          (is (str/includes? text "recommend with wanted \"gas fitter\""))
          (is (str/includes? text "send-for them")))
        (is (= "gas fitter" (:trade (view-work run world "hale")))))
      (testing "household work has no hint"
        (is (nil? (works/trade-hint {:id "work-1"}))))
      (testing "the plan says where it goes, that a price agreed in talk must be given, and how to re-plan"
        (let [text (works/describe-plan wo (narrate/names world))
              place (:place wo)]
          (is (str/includes? text (str "(" place "), where it was planned")))
          (is (str/includes? text "give that"))
          (is (str/includes? text "cancel-work it and plan it again"))
          (is (= (works/where-text wo (narrate/names world)) (:where (view-work run world "hale"))))))
      (testing "only structure is checked"
        (is (str/includes? (:error (c (dissoc ok :household))) "household"))
        (is (str/includes? (:error (c (dissoc ok :trade))) "trade"))
        (is (nil? (:trade (:plan (c (assoc ok :household true))))) "household work keeps no trade")
        (is (some? (:error (c (assoc ok :trade (apply str (repeat 81 "x"))))))))
      (finally (e/stop-run run)))))

(def gas-fitter-day
  "On the trades test world from 08:00: Hale plans a gasolier for the
  drawing room (the mock judge wants a gas fitter); the earl comes home and
  recommends one, from Ely; Hale sends for him at 10:00 with the work's id
  in about. He arrives at the carriage sweep at noon and walks in; Hale orders
  him the work at the estimate (£2, all materials), he asks £3 10s, Hale
  accepts, and he carries it out: two hours from 14:30. \"@tradesman\" in a
  script is the gas fitter's id, and \"tradesman\" his script."
  {"hale" {"08:00" [[:plan-work {:kind "install" :request "a three-light gas chandelier over the drawing-room fireplace"}]
                    [:pass {}]]
           "10:00" [[:send-for {:id "contact-1" :about "work-1: fit the gasolier in the drawing room"}]]
           "13:00" [[:order-work {:id "work-1" :worker "@tradesman"}] [:pass {}]]
           "14:00" [[:accept-work {:id "work-1"}] [:pass {}]]}
   "lord-claudeworth" {"08:00" [[:travel {:to "drawing-room"}]]
                       "08:30" [[:recommend {:wanted "a gas fitter" :for "hale"}] [:pass {}]]}
   "tradesman" {"12:30" [[:walk {:to "drawing-room"}] [:pass {}]]
                "13:30" [[:revise-work {:id "work-1" :price "£3 10s"}] [:pass {}]]
                "14:30" [[:carry-out {:id "work-1"}]]}})

(defn- tradesman-policy
  "The scripted policy for `day`, with the gas fitter (contact-1's person)
  following the \"tradesman\" script and \"@tradesman\" in any args his id."
  [day]
  (fn [{:keys [world agent-id] :as ctx}]
    (let [fitter (:agent-id (contacts/contact world "contact-1"))
          day (cond-> day (= fitter agent-id) (assoc agent-id (get day "tradesman")))
          answer (mock/scripted-policy day ctx)]
      (update answer :args #(update-vals (or % {}) (fn [v] (if (= "@tradesman" v) fitter v)))))))

(deftest a-gas-fitter-is-sent-for-and-fits-the-gasolier
  (let [scenario (with-scene-judge cw/scenario (constantly {:outcome "as-planned"}))
        run (with-redefs [e/load-scenario (constantly (assoc scenario :mock-policy (tradesman-policy gas-fitter-day)))]
              (e/start-run :claudeworth {:test? true :llm :mock :offline? true :gm :off :max-cost 0.20 :start "1904-10-15T08:00"
                                         :world-file "test/fixtures/visits.edn" :run-id (str (gensym "works-gas-"))}))]
    (try
      (until! run 5)
      (let [world (e/world run)
            fitter (:agent-id (contacts/contact world "contact-1"))]
        (testing "the plan named the trade and how to find one"
          (is (= "gas fitter" (:trade (works/work-order world "work-1"))))
          (is (str/includes? (first (replies-to run "hale" "plan-work")) "recommend with wanted \"gas fitter\"")))
        (testing "the gas fitter is recommended and sent for with the work's id"
          (is (= {:line "gas fitter" :known-to #{"hale" "lord-claudeworth"}}
                 (select-keys (contacts/contact world "contact-1") [:line :known-to])))
          (is (= "work-1: fit the gasolier in the drawing room" (:stay (visits/visit world "visit-1"))))
          (is (= :away (:status (w/agent world fitter))))))
      (until! run 18)
      (let [world (e/world run)
            fitter (:agent-id (contacts/contact world "contact-1"))
            wo (works/work-order world "work-1")
            it (items/item world (:made wo))
            start (* 100 240)]
        (testing "ordered at the estimate, haggled once, accepted and paid"
          (is (= [:works/planned :works/offered :works/revised :works/accepted :works/phase-done :works/done]
                 (map :type (filter #(= "works" (namespace (:type %))) (e/events run)))))
          (is (= {:status :done :outcome :as-planned :orderer "hale" :worker fitter :price 840 :priced-by fitter :account "household"}
                 (select-keys wo [:status :outcome :orderer :worker :price :priced-by :account]))))
        (testing "the household (starting with £100) paid £3 10s, and £1 10s of it is his labour"
          (is (= (- start 840) (funds world "household")))
          (is (= 360 (funds world fitter))))
        (testing "the gasolier is fixed in the drawing room"
          (is (= {:place "drawing-room" :fixed? true :owner "household"} (select-keys it [:place :fixed? :owner]))))
        (testing "the scene judge was told he's a gas fitter by trade"
          (is (some #(str/includes? (get-in % [:messages 1 :content]) "a gas fitter (by trade)") (scene-judge-calls run))))
        (testing "the household's book shows it done"
          (let [[e] (work-entries world)]
            (is (= {:done-by (:name (w/agent world fitter)) :ordered-by "Hale" :price "£3 10s"} (select-keys e [:done-by :ordered-by :price])))
            (is (str/starts-with? (:status e) "done at "))))
        (testing "no forfeits or refused calls"
          (is (empty? (of-type run :action/forfeited)))
          (is (not-any? #(re-find #"^(error:|rejected:)" (:content % "")) (mapcat :replies (store/read-llm (:dir run)))))))
      (replay! run)
      (finally (e/stop-run run)))))

(def maid-day
  "Lady Claudeworth plans a gasolier for the dining room and orders it to
  Nellie, the housemaid, who accepts and carries it out from 09:30."
  {"lady-claudeworth" {"08:00" [[:walk {:to "dining-room"}] [:pass {}]]
                       "08:30" [[:plan-work {:kind "install" :request "a three-light gas chandelier over the dining table"}]
                                [:order-work {:id "work-1" :worker "nellie"}]
                                [:pass {}]]}
   "nellie" {"08:00" [[:walk {:to "dining-room"}] [:pass {}]]
             "09:00" [[:accept-work {:id "work-1"}] [:pass {}]]
             "09:30" [[:carry-out {:id "work-1"}]]}})

(defn- with-gas-in-the-air
  "`scenario`'s scene judge, as with-scene-judge with a flawed work, also
  saying the place's air smells of gas."
  [scenario]
  (let [s (with-scene-judge scenario (constantly {:outcome "flawed"
                                                   :item {:name "Brass gasolier" :desc "A three-light brass gasolier."
                                                          :aspects {"repair" "hanging crooked, one tap stiff"}}}))
        judge (:mock-judge s)]
    (assoc s :mock-judge
           (fn [ctx]
             (cond-> (judge ctx)
               (get-in ctx [:job :work-order])
               (update-in [:args :changes] (fnil conj [])
                          {:thing (get-in ctx [:job :place]) :aspect "air" :value "smelling faintly of gas"}))))))

(deftest a-maid-botches-the-gasolier
  (let [run (start-with (with-gas-in-the-air move-household) maid-day)]
    (try
      (until! run 9)
      (let [world (e/world run)
            wo (works/work-order world "work-1")
            it (items/item world (:made wo))]
        (testing "anyone may be given it: the plan wanted a gas fitter, Nellie took it"
          (is (= {:trade "gas fitter" :worker "nellie" :status :done :outcome :flawed}
                 (select-keys wo [:trade :worker :status :outcome]))))
        (testing "the flawed gasolier is fixed in the dining room, with its fault"
          (is (= {:name "Brass gasolier" :place "dining-room" :fixed? true :aspects {:repair "hanging crooked, one tap stiff"}}
                 (select-keys it [:name :place :fixed? :aspects]))))
        (testing "and the dining room smells of gas"
          (is (= "smelling faintly of gas" (get-in (space/location world "dining-room") [:aspects :air]))))
        (testing "the scene judge was told she's a housemaid"
          (is (some #(str/includes? (get-in % [:messages 1 :content]) "The worker is: Housemaid") (scene-judge-calls run))))
        (is (empty? (of-type run :action/forfeited))))
      (replay! run)
      (finally (e/stop-run run)))))
