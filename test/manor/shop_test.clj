(ns manor.shop-test
  "Store mechanics use a tiny, test-owned world, never the bundled cast."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [manor.actions :as a]
            [manor.engine :as e]
            [manor.io.llm :as llm]
            [manor.io.store :as store]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.items :as items]
            [manor.mechanics.space :as space]
            [manor.narrate :as n]
            [manor.prompt :as prompt]
            [manor.ruleset :as ruleset]
            [manor.ruleset.actions :as ruleset-actions]
            [manor.ruleset.hibernation :as hibernation]
            [manor.mechanics.shop :as shop]
            [manor.scenarios.household :as household]
            [manor.world :as w]))

(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

(def profile "A tiny rural English general store, 1904. Household necessities and simple tools; not a specialist dealer.")
(def agents [{:name "Customer" :role :secretary :location "counter"}
             {:name "Clerk" :role :shopkeeper :location "counter"}])
(def purchase {:for "customer" :goods [{:item "packet of needles" :amount "2"}]})
(def available {:available true :item "packet of needles" :qty 2 :unit "a paper packet of ten needles"
                :desc "Ten steel sewing needles in a folded paper packet." :condition "new"
                :consumable false :unit-price 3})

(defn policy [{:keys [world agent-id used]}]
  (cond
    (and (= "clerk" agent-id) (= 1 (:round world)) (not (some #{"quote-goods"} used)))
    {:tool :quote-goods :args purchase}
    (and (= "customer" agent-id) (= 2 (:round world)) (seq (get-in world [:state :quotes])))
    {:tool :buy :args {:quote-ids [(:id (first (vals (get-in world [:state :quotes]))))] :hand-over "take"}}
    :else {:tool :pass}))

(defn scenario [cast]
  {:name :store-fixture
   :roles (into {} (for [role [:earl :countess :secretary :housemaid :architect :shopkeeper :bystander :gm]]
                     [role {:brief (fn [_ _] "Test role.")}]))
   :init (fn [_]
           {:agents cast
            :clock {:start "1904-10-15T10:00" :minutes-per-round 30}
            :space {:locations [{:id "counter" :name "Counter" :desc "Tins, thread and soap behind a wooden counter."
                                 :detail "A bell above the door." :services #{:general-store}
                                 :store-profile profile :hours ["08:00" "18:00"]}
                                {:id "back-room" :name "Back room" :desc "A quiet room."}]
                    :routes [["counter" "back-room" 0]]}
            :state {:setting "Rural England, 1904." :accounts {"household" 600} :unrelated {:untouched 7}
                    :positions {:shopkeeper {:capabilities #{:shop-clerk}}}
                    :quotes {}}
            :items []})
   :actions shop/actions :adjudicators shop/adjudicators :items {}
   :observe shop/observe :mock-policy policy})

(defn start
  ([] (start {}))
  ([{:keys [cast appraisal opts policy overrides] :or {cast agents appraisal (constantly {:tool :appraise :args {:lines [available]}})}}]
   (let [sc (cond-> (assoc-in (merge (scenario cast) overrides) [:adjudicators :shop/appraise :mock] appraisal)
              policy (assoc :mock-policy policy))]
     (with-redefs [e/load-scenario (constantly sc)]
       (e/start-run :store-fixture
                    (merge {:test? true :llm :mock :offline? true :gm :off
                            :run-id (str (gensym "store-mechanics-"))} opts))))))

(defn rounds! [run n]
  (let [before (:round (e/world run))]
    (e/auto! run n)
    (is (= :idle (e/wait-idle run 60000)))
    (is (= (+ before n) (:round (e/world run)))))
  run)

(defn action [name] (first (filter #(= name (:name %)) shop/actions)))
(defn response [args] {:tool-calls [{:id "answer" :name "appraise" :arguments args}]})
(defn appraised
  "The checked appraisal of a one-line request answered with `line`."
  [line]
  (shop/check 1 (response {:lines [line]})))

(defn quoted [world]
  (reduce w/apply-event world
          (map #(assoc % :actor "clerk") (shop/offer-events world "clerk" purchase {:lines [available]}))))

(deftest quote-and-purchase-are-separate
  (let [run (start)]
    (try
      (rounds! run 1)
      (let [world (e/world run)
            [offer] (vals (get-in world [:state :quotes]))
            events (e/events run)]
        (is (= 600 (get-in world [:state :accounts "household"])))
        (is (empty? (items/items-where world :holder)))
        (is (= 6 (:price offer)))
        (is (= "customer" (:buyer offer)))
        (is (= "clerk" (:clerk offer)))
        (is (= "6d" (get-in (shop/observe world "customer") [:offers 0 :price])))
        (is (some #(and (= :shop/goods-quoted (:type %))
                        (w/visible-to? % "customer") (w/visible-to? % "clerk")) events))
        (is (str/includes? (n/describe (first (filter #(= :shop/goods-quoted (:type %)) events))
                                     (n/names world)) (:id offer)))
        (rounds! run 2)
        (let [after (e/world run)]
          (is (= 594 (get-in after [:state :accounts "household"])))
          (is (= 7 (get-in after [:state :unrelated :untouched])))
          (is (empty? (get-in after [:state :quotes])))
          (testing "one parcel for the whole quantity, its amount and state as aspects"
            (is (= [{:id "packet-of-needles" :name "packet of needles" :owner "household" :holder "customer"
                     :aspects {:amount "2 × a paper packet of ten needles" :state "new"}
                     :desc "Ten steel sewing needles in a folded paper packet."}]
                   (map #(dissoc % :touched) (items/items-where after #(= "customer" (:holder %)))))))
          (is (str/includes? (shop/buy-reason after "customer" {:quote-ids [(:id offer)]}) "unspent quote"))
          (is (= after (w/replay (store/read-events (:dir run)))))
          (is (not-any? #(= :action/forfeited (:type %)) (e/events run)))
          (is (not-any? #(re-find #"^(error:|rejected:)" (:content % ""))
                        (mapcat :replies (store/read-llm (:dir run)))))))
      (finally (e/stop-run run)))))

(deftest buying-with-a-private-purse
  (let [base (:init (scenario agents))
        policy (fn [ctx]
                 (let [reply (policy ctx)]
                   (if (= :buy (:tool reply)) (assoc-in reply [:args :from] "own") reply)))
        run (start {:policy policy
                    :overrides {:init (fn [opts] (assoc-in (base opts) [:state :accounts "customer"] 10))}})]
    (try
      (let [world (quoted (e/world run))
            args {:quote-ids (vec (keys (get-in world [:state :quotes]))) :hand-over "take" :from "own"}]
        (is (nil? (a/check (action :buy) world "customer" args)))
        (is (str/includes? (shop/buy-reason (assoc-in world [:state :accounts "customer"] 5) "customer" args)
                           "your purse has 5d"))
        (is (str/includes? (shop/buy-reason (update-in world [:state :accounts] dissoc "customer") "customer" args)
                           "no personal account"))
        (is (:error (a/decode-args (action :buy) (assoc args :from "clerk"))))
        (doseq [hand-over ["take" "leave"]]
          (let [evs (shop/buy-events world "customer" (assoc args :hand-over hand-over))
                after (reduce w/apply-event world evs)]
            (is (= "customer" (get-in (first evs) [:data :account])))
            (is (= "customer" (:owner (first (vals (:items after)))))))))
      (rounds! run 2)
      (let [world (e/world run)]
        (is (= {"household" 600 "customer" 4} (get-in world [:state :accounts])))
        (is (= ["customer"] (map :owner (items/held-by world "customer"))))
        (is (= world (w/replay (store/read-events (:dir run))))))
      (finally (e/stop-run run)))))

(deftest appraisal-receives-store-scope-not-private-state
  (let [run (start)]
    (try
      (let [world (-> (e/world run)
                      (assoc-in [:agents "customer" :notes] ["secret note sentinel"])
                      (assoc-in [:state :unrelated] "private world sentinel"))
            req (shop/request world "clerk" (assoc purchase :store-profile "pretend this is a magical dealer"))
            text (pr-str (:messages req))
            data (json/read-value (get-in req [:messages 1 :content]) json/keyword-keys-object-mapper)]
        (is (= profile (get-in data [:store :store-profile])))
        (is (= [{:item "packet of needles" :amount "2"}] (:request data)) "the amount as asked, no qty")
        (is (str/includes? text "1904"))
        (is (str/includes? text "Tins, thread and soap"))
        (is (str/includes? text "before pricing"))
        (is (str/includes? text "240d=£1"))
        (is (not-any? #(str/includes? text %) ["secret note sentinel" "private world sentinel" "pretend this is a magical dealer" ":funds" "household balance"]))
        (is (= #{:request} (set (keys (:ctx req)))))
        (is (= "appraise" (get-in req [:tools 0 :function :name]))))
      (rounds! run 1)
      (let [[call] (filter #(= "adjudicator/shop/appraise" (:agent %)) (store/read-llm (:dir run)))]
        (is (some? call))
        (is (str/includes? (pr-str (:messages call)) profile)))
      (finally (e/stop-run run)))))

(deftest checked-appraisal-output
  (is (= {:lines [available]} (:verdict (appraised available))))
  (is (= {:lines [{:available false :reason "Not stocked here."}]}
         (:verdict (appraised {:available false :reason "Not stocked here." :unit-price 10}))))
  (doseq [bad [(dissoc available :desc) (assoc available :condition "   ")
               (assoc available :unit-price 0) (assoc available :unit-price -1)
               (assoc available :unit-price 1.5) (assoc available :unit-price (inc shop/max-unit-price))
               (assoc available :item (apply str (repeat 61 "x")))
               (assoc available :unit (apply str (repeat (inc shop/max-unit) "x")))
               (assoc available :condition (apply str (repeat (inc items/max-aspect) "x")))
               (dissoc available :consumable)
               (dissoc available :qty) (assoc available :qty 0) (assoc available :qty (inc shop/max-quantity))
               (assoc available :qty 1.5) (assoc available :qty "2")
               {:available false} {:available false :reason " "}]]
    (is (:error (appraised bad)) (pr-str bad)))
  (let [verdict (:verdict (appraised (assoc available :holder "clerk" :owner "clerk" :id "stolen"
                                            :price 0 :amount "999" :fixed? true :container "open")))]
    (is (= {:lines [available]} verdict)))
  (let [snake (-> available (dissoc :unit-price) (assoc :unit_price 5))]
    (is (= 5 (get-in (appraised snake) [:verdict :lines 0 :unit-price]))))
  (is (:error (shop/check 1 {:tool-calls []})))
  (testing "exactly one checked line per requested line, in order"
    (let [refused {:available false :reason "Not stocked here."}]
      (is (= {:lines [available refused]} (:verdict (shop/check 2 (response {:lines [available refused]}))))))
    (is (str/includes? (:error (shop/check 2 (response {:lines [available]}))) "exactly 2"))
    (is (:error (shop/check 1 (response {:lines [available available]}))))
    (is (:error (shop/check 1 (response available))))
    (is (str/includes? (:error (shop/check 2 (response {:lines [available (dissoc available :desc)]}))) "line 2")))
  (let [answer (shop/check 1 {:tool-calls [{:id "wrong" :name "buy" :arguments {}}
                                          {:id "first" :name "appraise" :arguments {:lines [available]}}
                                          {:id "second" :name "appraise" :arguments {:lines [(assoc available :unit-price 999)]}}]})]
    (is (= {:lines [available]} (:verdict answer)))
    (is (= 3 (count (:replies answer))))
    (is (str/starts-with? (get-in answer [:replies 2 :content]) "ignored:")))
  (doseq [args [(assoc-in purchase [:goods 0 :amount] "") (assoc-in purchase [:goods 0 :amount] 2)
                (assoc-in purchase [:goods 0 :amount] (apply str (repeat (inc shop/max-amount) "x")))
                (update-in purchase [:goods 0] dissoc :amount) (assoc purchase :goods [{:item "packet of needles" :qty 2}])
                (assoc-in purchase [:goods 0 :item] "") (assoc purchase :goods [])
                (assoc purchase :goods (vec (repeat (inc shop/max-lines) {:item "tea" :amount "1"})))
                {:for "customer" :item "packet of needles" :amount "2"}]]
    (is (:error (a/decode-args (action :quote-goods) args)) (pr-str args)))
  (testing "the clerk's line is the item and the amount as the customer put it"
    (is (= {:for "customer" :goods [{:item "lump sugar" :amount "a stone" :deliver true}]}
           (:args (a/decode-args (action :quote-goods) {:for "customer" :goods [{:item "lump sugar" :amount "a stone" :deliver true}]})))))
  (doseq [args [{:quote-ids []} {:quote-id "quote-1"} {:quote-ids (vec (repeat (inc shop/max-lines) "quote-1"))}]]
    (is (:error (a/decode-args (action :buy) args)) (pr-str args))))

(def shopping-list
  {:for "customer" :goods [{:item "tea" :amount "1"} {:item "telephone" :amount "1"} {:item "tea" :amount "4"}]})

(defn list-appraisal
  "Ordinary goods at 5d a packet, as many packets as the amount says;
  telephones refused."
  [{:keys [request]}]
  {:tool :appraise
   :args {:lines (for [{:keys [item amount]} request]
                   (if (= "telephone" item)
                     {:available false :reason "No telephones at a village shop."}
                     (assoc available :item item :qty (parse-long amount) :unit "1 packet" :consumable true :unit-price 5)))}})

(defn list-policy [{:keys [world agent-id used]}]
  (cond
    (and (= "clerk" agent-id) (= 1 (:round world)) (not (some #{"quote-goods"} used)))
    {:tool :quote-goods :args shopping-list}
    (and (= "customer" agent-id) (= 2 (:round world)) (seq (get-in world [:state :quotes])))
    {:tool :buy :args {:quote-ids (vec (sort (keys (get-in world [:state :quotes])))) :hand-over "take"}}
    :else {:tool :pass}))

(deftest a-whole-list-is-quoted-and-bought-at-once
  (let [run (start {:appraisal list-appraisal :policy list-policy})]
    (try
      (rounds! run 1)
      (let [world (e/world run)
            events (e/events run)
            quotes (filter #(= :shop/goods-quoted (:type %)) events)
            calls (store/read-llm (:dir run))]
        (testing "one appraisal and one query for three lines"
          (is (= 1 (count (filter #(= "adjudicator/shop/appraise" (:agent %)) calls))))
          (is (= [:shop/goods-quoted :shop/goods-refused :shop/goods-quoted]
                 (map :type (filter #(#{:shop/goods-quoted :shop/goods-refused} (:type %)) events))))
          (is (= [1 4] (map (comp :qty :data) quotes)))
          (is (= [5 20] (map (comp :price :data) quotes))))
        (testing "a quote's id is its event's id"
          (is (every? #(= (str "quote-" (:id %)) (get-in % [:data :id])) quotes)))
        (testing "the clerk is told every offer and refusal"
          (let [reply (->> calls (filter #(= "clerk" (:agent %))) (mapcat :replies) (map :content)
                           (filter #(str/includes? (str %) "quote-")) first)]
            (is (every? #(str/includes? reply (get-in % [:data :id])) quotes))
            (is (str/includes? reply "No telephones"))))
        (rounds! run 1)
        (let [after (e/world run)
              parcels (items/held-by after "customer")]
          (testing "one buy pays for both and hands over a parcel for each"
            (is (= (- 600 25) (get-in after [:state :accounts "household"])))
            (is (empty? (get-in after [:state :quotes])))
            (is (= 2 (count (filter #(= :shop/bought (:type %)) (e/events run)))))
            (is (= #{"1 packet" "4 × 1 packet"} (set (map #(get-in % [:aspects :amount]) parcels))))
            (is (= 2 (count (set (map :id parcels))))))
          (is (= after (w/replay (store/read-events (:dir run)))))
          (is (not-any? #(re-find #"^(error:|rejected:)" (:content % ""))
                        (mapcat :replies (store/read-llm (:dir run)))))))
      (finally (e/stop-run run)))))

(deftest buying-several-quotes-is-checked-as-a-whole
  (let [run (start)]
    (try
      (let [world (reduce w/apply-event (e/world run)
                          (map #(assoc % :actor "clerk")
                               (shop/offer-events (e/world run) "clerk" shopping-list
                                                  (:args (list-appraisal {:request (:goods shopping-list)})))))
            ids (vec (sort (keys (get-in world [:state :quotes]))))]
        (is (= 2 (count ids)))
        (is (nil? (shop/buy-reason world "customer" {:quote-ids ids})))
        (is (str/includes? (shop/buy-reason world "customer" {:quote-ids [(first ids) (first ids)]}) "once"))
        (is (str/includes? (shop/buy-reason world "customer" {:quote-ids [(first ids) "quote-999"]}) "quote-999: no such unspent quote"))
        (is (str/includes? (shop/buy-reason (assoc-in world [:state :accounts "household"] 24) "customer" {:quote-ids ids})
                           "these cost 2s 1d"))
        (is (nil? (shop/buy-reason (assoc-in world [:state :accounts "household"] 24) "customer" {:quote-ids [(first ids)]}))))
      (finally (e/stop-run run)))))

(defn- bought
  "The world after the customer accepts `result` for `qty` of it, and the purchase events."
  [world result qty]
  (let [offered (reduce w/apply-event world
                        (map #(assoc % :actor "clerk")
                             (shop/offer-events world "clerk" {:for "customer" :goods [{:item (:item result) :amount (str qty)}]}
                                                {:lines [(assoc result :qty qty)]})))
        evs (map #(assoc % :actor "customer")
                 (shop/buy-events offered "customer" {:quote-ids [(:id (first (vals (get-in offered [:state :quotes]))))] :hand-over "take"}))]
    [(reduce w/apply-event offered evs) evs]))

(deftest a-purchase-is-one-parcel
  (let [run (start)
        flour {:available true :item "Plain household flour" :unit "1 lb"
               :desc "Fine white flour in a blue paper bag." :condition "fresh, dry, unbleached"
               :consumable true :unit-price 2}]
    (try
      (let [[after evs] (bought (e/world run) flour 20)
            names (n/names after)]
        (testing "one item for twenty pounds, the amount and the condition as aspects, consumable"
          (is (= [:shop/bought :item/added] (map :type evs)))
          (is (= [{:id "plain-household-flour" :name "Plain household flour" :holder "customer" :owner "household"
                   :desc "Fine white flour in a blue paper bag." :aspects {:amount "20 × 1 lb" :state "fresh, dry, unbleached"}
                   :consumable? true}]
                 (map #(dissoc % :touched) (items/held-by after "customer")))))
        (testing "both lines give the amount"
          (is (= "Customer buys Plain household flour (20 × 1 lb) for 3s 4d at the Counter, and takes the goods away." (n/describe (first evs) names)))
          (is (= "Customer now has plain household flour (20 × 1 lb)." (n/describe (second evs) names))))
        (testing "a single unit is just the unit; lasting goods aren't consumable"
          (let [[after] (bought (e/world run) available 1)]
            (is (= [[{:amount "a paper packet of ten needles" :state "new"} nil]]
                   (map (juxt :aspects :consumable?) (map #(dissoc % :touched) (items/held-by after "customer"))))))))
      (testing "the longest unit and condition the appraiser may give still fit an item's aspects"
        (let [longest (assoc flour :unit (apply str (repeat shop/max-unit "u"))
                             :condition (apply str (repeat items/max-aspect "c")) :unit-price 1)
              [after] (bought (e/world run) longest shop/max-quantity)
              [it] (map #(dissoc % :touched) (items/held-by after "customer"))]
          (is (= {:amount (str "20 × " (apply str (repeat shop/max-unit "u"))) :state (apply str (repeat items/max-aspect "c"))}
                 (:aspects it)))))
      (finally (e/stop-run run)))))

(deftest the-appraiser-turns-the-amount-into-units
  (let [run (start)
        sugar {:available true :item "Lump sugar" :qty 14 :unit "1 lb" :desc "White lump sugar in a blue paper bag."
               :condition "dry" :consumable true :unit-price 3}]
    (try
      (let [world (e/world run)
            [quote] (shop/offer-events world "clerk" {:for "customer" :goods [{:item "lump sugar" :amount "a stone"}]} {:lines [sugar]})]
        (testing "the quote takes the appraiser's qty and unit, and prices them"
          (is (= {:item "Lump sugar" :qty 14 :unit "1 lb" :price 42}
                 (select-keys (:data quote) [:item :qty :unit :price])))
          (is (str/includes? (n/describe (assoc quote :actor "clerk") (n/names world)) "14 Lump sugar (1 lb each) for 3s 6d")))
        (testing "an available line without a qty, or past the cap, is corrected"
          (is (str/includes? (:error (appraised (dissoc sugar :qty))) "qty"))
          (is (:error (appraised (assoc sugar :qty (inc shop/max-quantity))))))
        (let [[after] (bought world sugar 14)]
          (is (= "14 × 1 lb" (get-in (first (items/held-by after "customer")) [:aspects :amount])))))
      (finally (e/stop-run run)))))

(defn- buy-policy
  "The clerk quotes in round 1; the customer buys in round 2 with `hand-over`."
  [hand-over]
  (fn [{:keys [world agent-id used]}]
    (cond
      (and (= "clerk" agent-id) (= 1 (:round world)) (not (some #{"quote-goods"} used)))
      {:tool :quote-goods :args purchase}
      (and (= "customer" agent-id) (= 2 (:round world)) (seq (get-in world [:state :quotes])))
      {:tool :buy :args {:quote-ids (vec (keys (get-in world [:state :quotes]))) :hand-over hand-over}}
      :else {:tool :pass})))

(deftest goods-left-at-the-shop-wait-for-the-buyer
  (let [run (start {:policy (buy-policy "leave")})]
    (try
      (rounds! run 2)
      (let [world (e/world run)
            [it] (items/items-where world :waiting)
            [bought] (filter #(= :shop/bought (:type %)) (e/events run))
            replies (map :content (mapcat :replies (filter #(= "customer" (:agent %)) (store/read-llm (:dir run)))))]
        (testing "paid for, the quote spent, and the goods left at the counter for the customer"
          (is (= 594 (get-in world [:state :accounts "household"])))
          (is (empty? (get-in world [:state :quotes])))
          (is (empty? (items/held-by world "customer")))
          (is (= {:name "packet of needles" :place "counter" :owner "household"
                  :aspects {:amount "2 × a paper packet of ten needles" :state "new"}
                  :waiting {:for "customer" :keeper "clerk" :at "counter" :since 2}}
                 (select-keys it [:name :place :owner :aspects :waiting :holder])))
          (is (= "leave" (get-in bought [:data :hand-over]))))
        (testing "the purchase says the goods are left, and the buyer is told where they are"
          (is (= "Customer buys packet of needles (2 × a paper packet of ten needles) for 6d at the Counter, and leaves the goods there to be collected or sent up."
                 (n/describe bought (n/names world))))
          (is (some #(str/includes? (str %) "waiting-at-shops") replies)))
        (testing "only the buyer sees them under waiting-at-shops, as orders give places"
          (is (= [{:item "packet of needles" :amount "2 × a paper packet of ten needles" :at "counter"}]
                 (:waiting-at-shops (shop/observe world "customer"))))
          (is (not (contains? (shop/observe world "clerk") :waiting-at-shops))))
        (testing "collected, they wait no longer"
          (let [taken (w/apply-event world {:type :item/taken :actor "customer" :data {:item (:id it) :from-place "counter"}})]
            (is (= "customer" (:holder (items/item taken (:id it)))))
            (is (not (contains? (shop/observe taken "customer") :waiting-at-shops)))))
        (is (= world (w/replay (store/read-events (:dir run)))))
        (is (not-any? #(= :action/forfeited (:type %)) (e/events run)))
        (is (not-any? #(re-find #"^(error:|rejected:)" (str %)) replies)))
      (finally (e/stop-run run)))))

(deftest buy-needs-a-hand-over-and-does-not-end-the-turn
  (is (= :free (a/kind (action :buy))))
  (is (:error (a/decode-args (action :buy) {:quote-ids ["quote-1"]})))
  (is (:error (a/decode-args (action :buy) {:quote-ids ["quote-1"] :hand-over "send"})))
  (is (= {:quote-ids ["quote-1"] :hand-over "leave"}
         (:args (a/decode-args (action :buy) {"quote_ids" ["quote-1"] "hand_over" "leave"})))))

(deftest refusals-and-failures-never-charge-or-create-goods
  ;; These test the contract, not the model's historical knowledge.
  (doseq [reason ["A telephone exchange is not stock for this shop."
                  "No smartphones existed in 1904."
                  "We do not deliver tons of coal."]]
    (let [run (start {:appraisal (constantly {:tool :appraise :args {:lines [{:available false :reason reason}]}})})]
      (try
        (rounds! run 2)
        (let [world (e/world run)
              [event] (filter #(= :shop/goods-refused (:type %)) (e/events run))]
          (is (= reason (get-in event [:data :reason])))
          (is (= #{"customer" "clerk"} (:visible-to event)))
          (is (= 600 (get-in world [:state :accounts "household"])))
          (is (empty? (get-in world [:state :quotes])))
          (is (empty? (items/items-where world :holder)))
          (is (= world (w/replay (e/events run)))))
        (finally (e/stop-run run)))))
  (doseq [[responder success calls] [[(fn [{:keys [step]}] {:tool :appraise :args {:lines [(if (zero? step) {:available true} available)]}}) true 2]
                                    [(constantly {:tool :appraise :args {:lines [{:available true}]}}) false 2]
                                    [(constantly {:tool :appraise :args {:lines [(dissoc available :qty)]}}) false 2]
                                    [(fn [_] (throw (ex-info "provider down" {}))) false 0]]]
    (let [run (start {:appraisal responder})]
      (try
        (rounds! run 1)
        (let [world (e/world run)
              logged (filter #(= "adjudicator/shop/appraise" (:agent %)) (store/read-llm (:dir run)))
              failed (filter #(= :adjudication/failed (:type %)) (e/events run))]
          (is (= calls (count logged)))
          (is (= success (boolean (seq (get-in world [:state :quotes])))))
          (is (= 600 (get-in world [:state :accounts "household"])))
          (is (empty? (items/items-where world :holder)))
          (is (= (not success) (boolean (seq failed))))
          (is (every? #(= :system (:visible-to %)) failed)))
        (finally (e/stop-run run))))))

(deftest purchase-preconditions-and-cast-independence
  (let [run (start)]
    (try
      (let [world (quoted (e/world run))
            id (:id (first (vals (get-in world [:state :quotes]))))
            args {:quote-ids [id]}
            placed (fn [world actor place] (w/apply-event world {:type :agent/placed :data {:id actor :to place}}))]
        (is (nil? (shop/buy-reason world "customer" args)))
        (is (shop/buy-reason (assoc-in world [:state :accounts "household"] 5) "customer" args))
        (is (shop/buy-reason world "clerk" args))
        (is (shop/buy-reason (assoc-in world [:agents "other"] (assoc (w/agent world "customer") :id "other")) "other" args))
        (is (shop/buy-reason (placed world "customer" "back-room") "customer" args))
        (is (shop/buy-reason (placed world "clerk" "back-room") "customer" args))
        (is (shop/buy-reason (update world :agents dissoc "clerk") "customer" args))
        (is (shop/buy-reason (assoc-in world [:agents "clerk" :status] :retired) "customer" args))
        (is (str/includes? (shop/buy-reason (assoc world :round 17) "customer" args) "closed"))
        (is (shop/quote-reason world "customer" purchase))
        (is (shop/quote-reason world "clerk" (assoc purchase :for "missing")))
        (is (nil? (shop/quote-reason (assoc-in world [:agents "customer" :role] :bystander) "clerk" purchase)))
        (is (shop/quote-reason (assoc-in world [:agents "customer" :role] :gm) "clerk" purchase))
        (is (shop/buy-reason (assoc-in world [:agents "customer" :role] :gm) "customer" args))
        (is (shop/buy-reason (assoc-in world [:agents "customer" :status] :retired) "customer" args))
        (is (shop/service-reason (update-in world [:space :locations "counter"] dissoc :services) "clerk"))
        (is (shop/service-reason (update-in world [:space :locations "counter"] dissoc :store-profile) "clerk")))
      (finally (e/stop-run run))))
  (doseq [cast [agents (vec (reverse agents))
                (into [{:name "Spectator" :role :bystander :location "back-room"}] agents)
                (into agents (for [i (range 12)] {:name (str "Extra " i) :role :bystander :location "back-room"}))]]
    (let [run (start {:cast cast})]
      (try
        (rounds! run 2)
        (is (= 594 (get-in (e/world run) [:state :accounts "household"])))
        (is (= 1 (count (items/items-where (e/world run) #(= "customer" (:holder %))))))
        (is (not-any? #(= :action/forfeited (:type %)) (e/events run)))
        (finally (e/stop-run run))))))

(deftest spending-is-not-restricted-by-character-role
  (doseq [role [:earl :countess :secretary :housemaid :architect :shopkeeper :bystander]]
    (let [run (start {:cast (assoc-in agents [0 :role] role)})]
      (try
        (is (some #(= :buy (:name %)) (a/for-role (:actions run) role)))
        (rounds! run 2)
        (let [world (e/world run)]
          (is (= 594 (get-in world [:state :accounts "household"])) (name role))
          (is (= 1 (count (items/items-where world #(= "customer" (:holder %))))))
          (is (= world (w/replay (e/events run)))))
        (finally (e/stop-run run)))))
  (is (empty? (a/for-role shop/actions :gm))))

(deftest clerk-routine-does-not-depend-on-the-village-cast
  (let [run (start)]
    (try
      (let [world (-> (e/world run)
                      (assoc-in [:state :routines "clerk"]
                                [{:from "08:00" :at "counter" :doing "tending the shop"}])
                      (assoc-in [:agents "customer" :location] "back-room"))
            other (assoc (w/agent world "clerk") :id "other-clerk" :location "back-room")
            with-other (-> world (assoc-in [:agents "other-clerk"] other)
                           (assoc-in [:state :routines "other-clerk"]
                                     [{:from "08:00" :at "back-room" :doing "drawing plans"}]))]
        (doseq [world [world with-other (update with-other :agents dissoc "other-clerk")]]
          (is (= {:activity "tending the shop" :location "counter"} (hibernation/hibernate world "clerk")))
          (is (nil? (hibernation/hibernate (assoc-in world [:agents "customer" :location] "counter") "clerk")))
          (is (nil? (hibernation/hibernate (assoc-in world [:agents "customer" :travel]
                                                 {:from "back-room" :to "counter" :arrives 3}) "clerk"))))
        (let [asleep (reduce w/apply-event world (b/hibernations hibernation/hibernate world))
              visited (assoc-in asleep [:agents "customer" :location] "counter")
              changes (filter #(= "clerk" (:actor %)) (b/hibernations hibernation/hibernate visited))]
          (is (b/hibernating? asleep "clerk"))
          (is (= [:agent/freed] (map :type changes)))))
      (finally (e/stop-run run)))))

(deftest store-profile-validation
  (doseq [profile [nil "" "   " 123 (apply str (repeat 2001 "x"))]]
    (is (seq (shop/problems {:locations [{:id "test-store" :services #{:general-store}
                                        :hours ["08:00" "18:00"] :store-profile profile}]}))))
  (is (empty? (shop/problems {:locations [{:id "test-store" :services #{:general-store}
                                         :hours ["08:00" "18:00"] :store-profile profile}]}))))

(deftest adjudication-model-cost-and-query-limit
  (let [seen (atom [])
        mock (llm/mock (fn [ctx]
                         (if (= :adjudicator (:role ctx)) {:tool :appraise :args {:lines [available]}} (policy ctx))))
        impl (reify llm/LLM
               (complete [_ req]
                 (swap! seen conj req)
                 (update (llm/complete mock req) :usage assoc :cost (if (= :adjudicator (get-in req [:ctx :role])) 0.012 0))))
        run (start {:opts {:llm-impl impl :judge-model "test-appraiser"}})]
    (try
      (rounds! run 2)
      (is (= ["test-appraiser"] (map :model (filter #(= :adjudicator (get-in % [:ctx :role])) @seen))))
      (is (= 0.012 (w/total-cost (e/world run))))
      (is (= 0.012 (get-in (e/world run) [:usage "adjudicator/shop/appraise" :cost])))
      (finally (e/stop-run run))))
  (let [run (start {:opts {:max-queries 0}})]
    (try
      (rounds! run 1)
      (is (empty? (get-in (e/world run) [:state :quotes])))
      (is (not-any? #(= "adjudicator/shop/appraise" (:agent %)) (store/read-llm (:dir run))))
      (finally (e/stop-run run)))))

(defn- routine-overrides
  "The fixture clerk tends the counter when nobody is there."
  [cast]
  {:hibernate hibernation/hibernate
   :actions (vec (concat ruleset-actions/actions shop/actions))
   :init (fn [opts]
           (assoc-in ((:init (scenario cast)) opts) [:state :routines "clerk"]
                     [{:from "00:00" :at "counter" :doing "tending the shop"}]))})

(deftest arrivals-wake-the-clerk-in-time-to-act
  (doseq [clerk-first? [false true]]
    (let [cast (cond-> [{:name "Customer" :role :secretary :location "back-room"}
                       {:name "Clerk" :role :shopkeeper :location "counter"}]
                 clerk-first? reverse)
          run (start {:cast cast :overrides (routine-overrides cast)
                      :policy (fn [{:keys [agent-id used]}]
                                (if (and (= "customer" agent-id) (empty? used))
                                  {:tool :walk :args {:to "counter"}}
                                  {:tool :pass}))})]
      (try
        (rounds! run 1)
        (let [evs (e/events run)
              moved (first (filter #(= :agent/moved (:type %)) evs))
              freed (first (filter #(= :agent/freed (:type %)) evs))
              passed (filter #(= :agent/passed (:type %)) evs)]
          (is (= "clerk" (:actor freed)))
          (is (< (:id moved) (:id freed)))
          (is (= ["customer" "clerk"] (mapv :actor passed)))
          (is (= [1 1] (mapv :round passed)))
          (is (= (e/world run) (w/replay (store/read-events (:dir run))))))
        (finally (e/stop-run run))))))

(deftest wakings-only-wake-nearby-hibernators
  (let [run (start {:cast [{:name "Customer" :role :secretary :location "back-room"}
                           {:name "Clerk" :role :shopkeeper :location "counter"}]})]
    (try
      (let [world (assoc-in (e/world run) [:state :routines "clerk"]
                            [{:from "00:00" :at "counter" :doing "tending the shop"}])
            hib (w/apply-event world (b/hibernate world "clerk" "tending the shop"))]
        (doseq [place ["village-green" "other-shop"]]
          (is (empty? (b/wakings hibernation/hibernate (assoc-in hib [:agents "customer" :location] place)))))
        (is (empty? (b/wakings hibernation/hibernate
                              (assoc-in world [:agents "customer" :location] "counter")))
            "a waking pass never hibernates someone who is awake"))
      (finally (e/stop-run run)))))

(deftest departing-customers-dont-hibernate-the-clerk-mid-round
  (let [run (start {:overrides (routine-overrides agents)
                    :policy (fn [{:keys [agent-id used]}]
                              (if (and (= "customer" agent-id) (empty? used))
                                {:tool :walk :args {:to "back-room"}}
                                {:tool :pass}))})]
    (try
      (rounds! run 1)
      (is (nil? (w/busy (e/world run) "clerk")))
      (is (= ["customer" "clerk"] (mapv :actor (filter #(= :agent/passed (:type %)) (e/events run)))))
      (finally (e/stop-run run)))))

(deftest released-workers-get-one-turn-before-hibernation
  (let [cast [{:name "Customer" :role :secretary :location "back-room"}
              {:name "Clerk" :role :shopkeeper :location "counter"}]
        run (start {:cast cast :overrides (routine-overrides cast)
                    :policy (constantly {:tool :pass})})]
    (try
      (e/emit! run [(b/occupy (e/world run) "clerk" "writing the account" 1)])
      (rounds! run 1)
      (is (nil? (w/busy (e/world run) "clerk")))
      (rounds! run 1)
      (is (b/hibernating? (e/world run) "clerk"))
      (is (= [1] (mapv :round (filter #(and (= :agent/passed (:type %)) (= "clerk" (:actor %))) (e/events run)))))
      (finally (e/stop-run run)))))

(defn- counter-conversation
  "Quote once, then speak; the initiator ends the exchange."
  [{:keys [agent-id used]}]
  (if (= "clerk" agent-id)
    (if (some #{"quote-goods"} used)
      {:tool :reply :args {:text "The two packets cost sixpence."}}
      {:tool :quote-goods :args purchase})
    {:tool :end-conversation}))

(defn- counter-customer
  "Ask at the counter, then buy any offers in this same turn."
  [{:keys [world agent-id used]}]
  (cond
    (not= "customer" agent-id) {:tool :carry-on}
    (not (some #{"converse"} used)) {:tool :converse :args {:with ["clerk"] :text "Two packets of needles, please."}}
    (seq (get-in world [:state :quotes])) {:tool :buy :args {:quote-ids (vec (keys (get-in world [:state :quotes]))) :hand-over "take"}}
    :else {:tool :pass}))

(defn- converse-reply [run]
  (->> (store/read-llm (:dir run))
       (filter #(and (= "customer" (:agent %)) (not (:conversation %))))
       first :replies first :content))

(defn one-turn-shop-policy
  "A test errand, also used by the offline Claudeworth transcript check."
  [{:keys [world agent-id used]}]
  (cond
    (not= "lord-claudeworth" agent-id) {:tool :pass}
    (empty? used) {:tool :walk :args {:to "general-store"}}
    (not (some #{"converse"} used))
    {:tool :converse :args {:with ["briggs"] :text "Two packets of needles, please."}}
    (seq (get-in world [:state :quotes])) {:tool :buy :args {:quote-ids (vec (keys (get-in world [:state :quotes]))) :hand-over "take"}}
    :else {:tool :pass}))

(deftest one-customer-turn-at-the-counter
  (let [sc (assoc household/scenario
                  :mock-policy one-turn-shop-policy
                  :init (fn [opts]
                          (assoc-in ((:init household/scenario) opts) [:state :routines "briggs"]
                                    [{:from "00:00" :at "general-store" :doing "tending the shop"}])))
        run (with-redefs [e/load-scenario (constantly sc)]
              (e/start-run :household {:test? true :offline? true :llm :mock :gm :off
                                       :start "1904-10-15T10:00" :run-id (str (gensym "counter-visit-"))}))]
    (try
      @(e/place! run "lord-claudeworth" "village-green")
      (rounds! run 1)
      (let [evs (e/events run)
            types [:agent/moved :agent/freed :conversation/started :shop/goods-quoted
                   :conversation/ended :shop/bought]
            events (map #(first (filter (fn [ev] (= % (:type ev))) evs)) types)
            quote (get-in (nth events 3) [:data :id])
            calls (store/read-llm (:dir run))
            summary (->> calls (filter #(and (= "lord-claudeworth" (:agent %))
                                            (some #{"converse"} (map (comp :name :function) (get-in % [:response :tool_calls])))))
                         first :replies first :content)]
        (is (every? some? events))
        (is (apply < (map :id events)))
        (is (= #{1} (set (map :round events))))
        (is (= "briggs" (:actor (second events))))
        (is (str/includes? summary (str "quote " quote)))
        (is (empty? (get-in (e/world run) [:state :quotes])))
        (is (some #(= "lord-claudeworth" (:holder %)) (items/items-where (e/world run) :holder)))
        (is (not-any? #(= :action/forfeited (:type %)) evs))
        (is (= (e/world run) (w/replay (store/read-events (:dir run))))))
      (finally (e/stop-run run)))))

(deftest speaking-tools-and-query-budget
  (let [unavailable {:name :unavailable :kind :free :available? (constantly false)}
        clash {:name :reply :kind :free}
        extra {:name :check-counter :kind :free}
        turn-ending {:name :close-shop :kind :final}
        run (start {:policy counter-customer
                    :overrides {:speaking-actions (fn [world speaker conv]
                                                    (into [unavailable clash extra turn-ending]
                                                          (ruleset/speaking-actions world speaker conv)))
                                :mock-conversation
                                (fn [{:keys [agent-id step] :as ctx}]
                                  (if (= "clerk" agent-id)
                                    (case step
                                      0 {:tool :close-shop}
                                      2 {:tool :quote-goods :args purchase}
                                      (counter-conversation ctx))
                                    {:tool :end-conversation}))}})]
    (try
      (rounds! run 1)
      (let [calls (filter #(and (= "clerk" (:agent %)) (:conversation %)) (store/read-llm (:dir run)))
            prompt (:content (second (:messages (first calls))))]
        (is (= 4 (count calls)) "an invalid action, query, rejected second query, then reply")
        (is (= ["reply" "stay-silent" "leave" "note" "check-counter" "recommend" "quote-goods"] (:tools (first calls))))
        (is (= "error: there is no tool called close-shop" (get-in (first calls) [:replies 0 :content])))
        (is (str/starts-with? (get-in (nth calls 2) [:replies 0 :content]) "error: query limit reached"))
        (is (str/includes? (get-in (nth calls 2) [:replies 1 :content]) "This is your last call"))
        (is (str/includes? prompt "You may first use `check-counter`, `recommend`, `quote-goods` before your line."))
        (is (str/includes? prompt "Then finish this speaking turn with exactly ONE"))
        (is (= 1 (count (filter #(= "adjudicator/shop/appraise" (:agent %)) (store/read-llm (:dir run))))))
        (is (= 1 (count (filter #(= :shop/bought (:type %)) (e/events run))))))
      (finally (e/stop-run run)))))

(deftest buying-on-a-line-at-the-counter
  (let [buy? (fn [world speaker] (some #(= :buy (:name %)) (ruleset/speaking-actions world speaker {})))
        customer-line (fn [{:keys [agent-id world used] :as ctx}]
                        (cond
                          (= "clerk" agent-id) (counter-conversation ctx)
                          (and (seq (shop/quotes-here world "customer")) (not (some #{"buy"} used)))
                          {:tool :buy :args {:quote-ids (mapv :id (shop/quotes-here world "customer")) :hand-over "leave"}}
                          :else {:tool :end-conversation}))
        run (start {:policy #(if (= "clerk" (:agent-id %)) {:tool :pass} (counter-customer %))
                    :overrides {:speaking-actions ruleset/speaking-actions :mock-conversation customer-line}})]
    (try
      (testing "buy is a speaking-turn tool only for a customer with quotes at this shop"
        (let [world (quoted (e/world run))]
          (is (buy? world "customer"))
          (is (not (buy? world "clerk")))
          (is (not (buy? (e/world run) "customer")))
          (is (not (buy? (assoc-in world [:agents "customer" :location] "back-room") "customer")))))
      (rounds! run 1)
      (let [evs (e/events run)
            id-of (fn [t] (:id (first (filter #(= t (:type %)) evs))))
            calls (filter #(and (= "customer" (:agent %)) (:conversation %)) (store/read-llm (:dir run)))
            world (e/world run)]
        (is (< (id-of :shop/goods-quoted) (id-of :shop/bought) (id-of :conversation/ended))
            "bought on the customer's line, after the quote")
        (is (some #(some #{"buy"} (:tools %)) calls))
        (is (= 594 (get-in world [:state :accounts "household"])))
        (is (= ["counter"] (map :at (:waiting-at-shops (shop/observe world "customer")))))
        (is (not-any? #(= :action/forfeited (:type %)) evs))
        (is (= world (w/replay (store/read-events (:dir run))))))
      (finally (e/stop-run run)))))

(deftest only-serving-clerks-get-quote-goods
  (let [run (start)
        quote-goods? (fn [world speaker] (some #(= :quote-goods (:name %)) (ruleset/speaking-actions world speaker {})))]
    (try
      (let [world (e/world run)]
        (is (quote-goods? world "clerk"))
        (is (not (quote-goods? world "customer")))
        (is (not (quote-goods? (assoc-in world [:agents "clerk" :location] "back-room") "clerk")))
        (is (not (quote-goods? (assoc-in world [:clock :start] "1904-10-15T20:00") "clerk"))))
      (finally (e/stop-run run)))))

(deftest speaking-queries-preserve-occupations
  (doseq [interrupted? [false true]]
    (let [run (start {:policy counter-customer
                      :overrides {:speaking-actions ruleset/speaking-actions :mock-conversation counter-conversation}})]
      (try
        (e/emit! run [(b/occupy (e/world run) "clerk" "writing accounts" 5)])
        (when interrupted? (e/emit! run [(b/interrupt (e/world run) "clerk" "customer")]))
        (rounds! run 1)
        (let [evs (e/events run)
              start (:id (first (filter #(= :conversation/started (:type %)) evs)))
              end (:id (first (filter #(= :conversation/ended (:type %)) evs)))
              during (filter #(< start (:id %) end) evs)]
          (is (some #(= :shop/goods-quoted (:type %)) during))
          (is (not-any? #(#{:agent/freed :agent/resumed} (:type %)) during))
          (is (= {:activity "writing accounts" :until 5} (w/busy (e/world run) "clerk")))
          (when interrupted?
            (is (= ["clerk"] (mapv :actor (filter #(= :agent/resumed (:type %)) evs))))))
        (finally (e/stop-run run))))))

(deftest conversation-summary-includes-visible-query-effects-only
  (let [query (assoc (action :quote-goods)
                    :on-result (fn [world actor args result]
                                 (into (shop/offer-events world actor args result)
                                       (for [[audience text] [[#{"clerk"} "private line"] [:gm "GM line"]
                                                              [:system "system line"] [#{"customer"} "visible line"]]]
                                         {:type :agent/said :visible-to audience :data {:text text}}))))
        run (start {:policy counter-customer
                    :appraisal (constantly {:tool :appraise :args {:lines [{:available false :reason "Not stocked."}]}})
                    :overrides {:speaking-actions (fn [_ speaker _] (when (= "clerk" speaker) [query]))
                                :mock-conversation counter-conversation}})]
    (try
      (e/emit! run [{:type :agent/said :actor "clerk" :visible-to #{"customer"} :data {:text "earlier line"}}])
      (rounds! run 1)
      (let [summary (converse-reply run)]
        (is (str/includes? summary "cannot offer packet of needles to Customer: Not stocked."))
        (is (str/includes? summary "visible line"))
        (doseq [hidden ["private line" "GM line" "system line" "earlier line"]]
          (is (not (str/includes? summary hidden)))))
      (finally (e/stop-run run)))))

(deftest conversation-appraisal-counts-toward-cost-limit
  (let [policy (fn [{:keys [role conversation] :as ctx}]
                 (cond (= role :adjudicator) {:tool :appraise :args {:lines [available]}}
                       conversation (counter-conversation ctx)
                       :else (counter-customer ctx)))
        mock (llm/mock policy)
        priced (reify llm/LLM
                 (complete [_ request]
                   (cond-> (llm/complete mock request)
                     (= :adjudicator (get-in request [:ctx :role])) (assoc-in [:usage :cost] 0.02))))
        run (start {:opts {:llm-impl priced :max-cost 0.01}
                    :overrides {:speaking-actions ruleset/speaking-actions}})]
    (try
      (rounds! run 1)
      (is (= 0.02 (w/total-cost (e/world run))))
      (is (= :cost-limit (get-in (first (filter #(= :conversation/ended (:type %)) (e/events run))) [:data :reason])))
      (is (= 1 (count (filter #(= "adjudicator/shop/appraise" (:agent %)) (store/read-llm (:dir run))))))
      (is (not-any? #(and (= "customer" (:agent %)) (:conversation %)) (store/read-llm (:dir run))))
      (finally (e/stop-run run)))))
