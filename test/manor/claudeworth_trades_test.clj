(ns manor.claudeworth-trades-test
  "Bundled village content: residents follow their routines quietly and are
  reachable for work or counter purchases. No fixed cast or map snapshot."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [manor.actions :as a]
            [manor.builtins :as b]
            [manor.engine :as e]
            [manor.positions :as positions]
            [manor.scenarios.claudeworth :as cw]
            [manor.scenarios.shop :as shop]
            [manor.space :as space]
            [manor.store :as store]
            [manor.world :as w]))

(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

(deftest claudeworth-village-residents-and-counters
  (let [run (e/start-run :claudeworth {:llm :mock :offline? true :test? true
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
              (is (= :village (:domain (space/location noon place))))
              (is (contains? (space/area noon "village-green") place))
              (is (= :bedroom (:kind (space/location noon (get-in noon [:state :bedrooms id])))))
              (is (some #{place} (:workplace position)))
              (is (not (positions/household-member? noon id)))
              (is (nil? (cw/hibernate visiting id)) "a caller wakes the intended resident")
              (is (not (b/hibernating? awake id)))
              (is (every? #(b/hibernating? awake %) (remove #{id} residents))
                  "a visitor does not wake the rest of the village")
              (is (nil? (a/check work awake id {:doing "attending to work here" :on [place] :hours 0.5})))
              (is (seq (cw/agent-brief awake id)))
              (if (positions/capable? awake id :shop-clerk)
                (let [args {:for (:id customer) :goods [{:item "a small counter purchase" :qty 1}]}
                      request (shop/request awake id args)
                      data (json/read-value (get-in request [:messages 1 :content]) json/keyword-keys-object-mapper)]
                  (is (nil? (shop/quote-reason awake id args)))
                  (is (= [:quote-goods] (map :name (cw/speaking-actions awake id {}))))
                  (is (= (:store-profile (space/location awake place)) (get-in data [:store :store-profile])))
                  (is (nil? (:household data)))
                  (is (nil? (:agents data))))
                (do
                  (is (empty? (cw/speaking-actions awake id {})))
                  (is (seq (:services (space/location awake place)))
                      "service workrooms cannot be searched for free supplies")
                  (is (string? ((get-in run [:scenario :items :find-allowed?]) awake (:id customer) place)))))))))
      (is (= (e/world run) (w/replay (store/read-events (:dir run)))))
      (finally (e/stop-run run)))))
