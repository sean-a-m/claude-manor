(ns manor.fixture-test
  "Regression coverage for the boundary between test conditions and bundled content."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [manor.engine :as e]
            [manor.io.store :as store]
            [manor.io.worldfile :as wf]
            [manor.mechanics.items :as items]
            [manor.scenarios.claudeworth :as cw]
            [manor.scenarios.claudeworth.money :as money]
            [manor.scenarios.household :as fixture]
            [manor.world :as w]))

(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

(defn- start [scenario]
  (e/start-run scenario {:llm :mock :offline? true :test? true
                         :run-id (str (gensym "fixture-boundary-"))}))

(deftest household-is-independent-of-bundled-content
  (let [read-file wf/read-file
        ;; Build a synthetic production configuration from known-valid data,
        ;; so even this regression has no dependency on the real world's layout.
        bundled (-> (read-file fixture/world-file)
                    (assoc-in [:meta :starting-funds] 12)
                    (update :agents into
                            (for [n (range 24)]
                              {:name (str "Extra resident " n) :role :housemaid
                               :location "maids-room" :bedroom "maids-room"
                               :persona "An additional resident for an isolation test."}))
                    (update :agents #(vec (reverse %))))]
    (with-redefs [wf/read-file (fn [source]
                                (if (= source "worlds/claudeworth.edn") bundled (read-file source)))
                  cw/mock-day {}
                  cw/scenario (assoc cw/scenario
                                     :defaults {:leave-out ["pugh"]}
                                     :mock-policy (fn [_] {:tool :pass}))]
      (let [live (start :claudeworth)
            fixed (start :household)]
        (try
          (testing "production really has the expanded cast and changed defaults"
            (is (= 24 (count (filter #(re-find #"^extra-resident-" (:id %))
                                    (w/active-agents (e/world live))))))
            (is (some? (w/agent (e/world live) "nellie")))
            (is (nil? (w/agent (e/world live) "pugh")))
            (is (= (money/pounds->pence 12) (get-in (e/world live) [:state :household :funds]))))
          (testing "the mechanics fixture keeps its own cast, defaults, map and funds"
            (is (= #{"gm" "lord-claudeworth" "lady-claudeworth" "hale" "pugh" "briggs"}
                   (set (keys (:agents (e/world fixed))))))
            (is (= (money/pounds->pence 3000) (get-in (e/world fixed) [:state :household :funds])))
            (is (= (read-file fixture/world-file) (store/read-world-start (:dir fixed)))))
          (testing "hibernation still tests an explicitly empty house"
            (let [world (reduce (fn [world id]
                                  (w/apply-event world {:type :agent/placed :data {:id id :to "abbey-church"}}))
                                (assoc (e/world fixed) :round 5)
                                ["lord-claudeworth" "lady-claudeworth" "hale"])
                  world (w/apply-event world {:type :agent/placed :data {:id "pugh" :to "dining-room"}})]
              (is (some? (cw/hibernate world "pugh")))))
          (testing "the fixed mock day still runs even when the production script is replaced"
            (e/auto! fixed 47)
            (is (= :idle (e/wait-idle fixed 120000)))
            (is (= 47 (:round (e/world fixed))))
            (is (= "lady-claudeworth" (:holder (items/item (e/world fixed) "feather-duster"))))
            (is (some #(= :claudeworth/commissioned (:type %)) (e/events fixed)))
            (is (not-any? #(#{:action/forfeited :item/given} (:type %)) (e/events fixed)))
            (is (not-any? #(re-find #"^(error:|rejected:)" (:content % ""))
                          (mapcat :replies (store/read-llm (:dir fixed)))))
            (is (= (e/world fixed) (w/replay (store/read-events (:dir fixed))))))
          (finally
            (e/stop-run live)
            (e/stop-run fixed)))))))
