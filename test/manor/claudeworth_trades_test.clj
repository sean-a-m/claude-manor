(ns manor.claudeworth-trades-test
  "Bundled village content: residents follow their routines quietly and are
  reachable for work or counter purchases. No fixed cast or map snapshot."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [manor.actions :as a]
            [manor.engine :as e]
            [manor.io.store :as store]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.hiring :as hiring]
            [manor.mechanics.items :as items]
            [manor.mechanics.positions :as positions]
            [manor.mechanics.space :as space]
            [manor.scenarios.claudeworth :as cw]
            [manor.mechanics.deliveries :as deliveries]
            [manor.mechanics.shop :as shop]
            [manor.world :as w]))

(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

(deftest claudeworth-village-residents-and-counters
  (let [run (e/start-run :claudeworth {:llm :mock :offline? true :test? true :start "1904-10-15T08:00"
                                      :run-id (str (gensym "claudeworth-trades-"))})]
    (try
      (e/auto! run 1)
      (is (= :idle (e/wait-idle run 60000)))
      (let [world (e/world run)
            residents (keys (get-in world [:state :routines]))
            ;; Advance the routine without playing the household's day; all
            ;; counters are open at noon in the bundled world.
            noon (assoc world :round 9)
            noon (reduce w/apply-event noon (b/hibernations cw/hibernate noon))
            customer (first (filter #(positions/household-member? world (:id %))
                                    (w/active-agents world)))
            work (first (filter #(= :spend-time (:name %)) (:actions run)))]
        (is (seq residents))
        (doseq [id residents]
          (testing id
            (let [place (space/location-of noon id)
                  position (positions/position noon id)
                  visiting (w/apply-event noon {:type :agent/placed
                                                :data {:id (:id customer) :to place}})
                  awake (reduce w/apply-event visiting (b/wakings cw/hibernate visiting))]
              (is (b/hibernating? world id) "idle village residents make no character calls")
              (is (not-any? #(and (= id (:actor %)) (= :llm/usage (:type %))) (e/events run)))
              (is (not= :house (:domain (space/location noon place))))
              (is (= :bedroom (:kind (space/location noon (get-in noon [:state :bedrooms id])))))
              (is (some #{place} (:workplace position)))
              (is (not (positions/household-member? noon id)))
              (is (nil? (cw/hibernate visiting id)) "a caller wakes the intended resident")
              (is (not (b/hibernating? awake id)))
              (is (every? #(b/hibernating? awake %)
                          (remove #(= place (space/location-of noon %)) residents))
                  "a visitor wakes only the people where they call")
              (is (nil? (a/check work awake id {:doing "attending to work here" :on [place] :hours 0.5})))
              (is (seq (cw/agent-brief awake id)))
              (if (positions/capable? awake id :shop-clerk)
                (let [args {:for (:id customer) :goods [{:item "a small counter purchase" :amount "1"}]}
                      request (shop/request awake id args)
                      data (json/read-value (get-in request [:messages 1 :content]) json/keyword-keys-object-mapper)]
                  (is (nil? (shop/quote-reason awake id args)))
                  (is (some #{:quote-goods} (map :name (cw/speaking-actions awake id {}))))
                  (is (= (:store-profile (space/location awake place)) (get-in data [:store :store-profile])))
                  (is (nil? (:household data)))
                  (is (nil? (:agents data))))
                (do
                  (is (not-any? #{:quote-goods} (map :name (cw/speaking-actions awake id {}))))
                  (is (seq (:services (space/location awake place)))
                      "service workrooms are places of trade")
                  (is (nil? (cw/find-allowed? awake (:id customer) place)) "find works everywhere by default")
                  (is (string? (cw/find-allowed? (assoc-in awake [:state :find-everywhere?] false) (:id customer) place)))))))))
      (is (= (e/world run) (w/replay (store/read-events (:dir run)))))
      (finally (e/stop-run run)))))

(deftest claudeworth-draper-delivers-with-an-errand-boy
  (let [buyer (atom nil)
        policy (fn [{:keys [world agent-id used]}]
                 (cond
                   (and (= :draper (:role (w/agent world agent-id)))
                        (= 1 (:round world)) (not (some #{"quote-goods"} used)))
                   {:tool :quote-goods :args {:for @buyer :goods [{:item "a large parcel of household linen" :amount "1" :deliver true}]}}
                   (and (= @buyer agent-id) (= 2 (:round world)) (seq (get-in world [:state :quotes])))
                   {:tool :buy :args {:quote-ids (vec (keys (get-in world [:state :quotes]))) :hand-over "take"}}
                   :else {:tool :pass}))
        run (with-redefs [e/load-scenario (constantly (assoc cw/scenario :mock-policy policy))]
              (e/start-run :claudeworth {:llm :mock :offline? true :test? true :gm :off
                                         :start "1904-10-15T12:00"
                                         :run-id (str (gensym "draper-delivery-"))}))]
    (try
      (reset! buyer (:id (first (filter #(positions/household-member? (e/world run) (:id %))
                                       (w/active-agents (e/world run))))))
      @(e/place! run @buyer "drapers-shop")
      (e/auto! run 1)
      (is (= :idle (e/wait-idle run 60000)))
      (let [world (e/world run)
            loc (space/location world "drapers-shop")
            couriers (deliveries/couriers world)
            courier (get couriers "drapers-shop")
            clerk (:id (first (filter #(= :draper (:role %)) (w/active-agents world))))
            args {:for @buyer :goods [{:item "a large parcel of household linen" :amount "1" :deliver true}]}
            data (json/read-value (get-in (shop/request world clerk args) [:messages 1 :content])
                                  json/keyword-keys-object-mapper)]
        (testing "the draper opts into existing deliveries, like the butcher"
          (is (deliveries/delivers? loc))
          (is (< 0 (count (:delivery-profile loc)) deliveries/max-profile))
          (is (nil? (:courier loc)) "use the default, as the butcher does")
          (is (= :errand-boy (:position courier) (deliveries/courier-position world "drapers-shop")))
          (is (= "Errand boy" (get-in world [:state :positions :errand-boy :title])))
          (is (nil? (shop/quote-reason world clerk args)))
          (is (= (:delivery-profile loc) (get-in data [:store :delivery-profile])))
          (is (= (:delivery-profile loc) (get-in (shop/observe world clerk) [:here :delivery-profile]))))
        (testing "every delivering shop gets a distinct courier named before any delivery"
          (let [delivering (into #{} (comp (filter deliveries/delivers?) (map :id))
                                 (vals (get-in world [:space :locations])))]
            (is (contains? delivering "drapers-shop"))
            (is (= delivering (set (keys couriers))))
            (is (= (count delivering) (count (distinct (map #(get-in % [:person :name]) (vals couriers)))))))
          (is (not-any? #(hiring/written? (:person %)) (vals couriers)))
          (is (nil? (:agent-id courier)))
          (is (some? (deliveries/courier-line world "drapers-shop")))
          (is (not (contains? (:here (shop/observe world clerk)) :orders-sent-up-by)) "the courier is named only on orders"))
        (e/auto! run 1)
        (is (= :idle (e/wait-idle run 60000)))
        (let [world (e/world run)
              [order] (vals (deliveries/orders world))]
          (is (= {:location "drapers-shop" :buyer @buyer :clerk clerk :due 6 :to "carriage-sweep"}
                 (select-keys order [:location :buyer :clerk :due :to])))
          (is (= (deliveries/courier-line world "drapers-shop")
                 (:sent-up-by (first (deliveries/observe world @buyer))))))
        (e/auto! run 4)
        (is (= :idle (e/wait-idle run 60000)))
        (let [world (e/world run)
              {:keys [person agent-id position]} (get (deliveries/couriers world) "drapers-shop")
              arrived (first (filter #(= :deliveries/arrived (:type %)) (e/events run)))
              parcel (first (items/items-where world :order-id))]
          (is (empty? (deliveries/orders world)))
          (is (= :errand-boy position (:role (w/agent world agent-id))))
          (is (= (get-in courier [:person :name]) (:name person)))
          (is (= {:position :errand-boy :employer clerk}
                 (select-keys (get-in world [:state :posts agent-id]) [:position :employer])))
          (is (= {:shop "drapers-shop" :at "carriage-sweep" :courier agent-id}
                 (select-keys (:data arrived) [:shop :at :courier])))
          (is (= {:name "a large parcel of household linen" :place "carriage-sweep"}
                 (select-keys parcel [:name :place])))
          (is (= world (w/replay (store/read-events (:dir run)))))))
      (finally (e/stop-run run)))))
