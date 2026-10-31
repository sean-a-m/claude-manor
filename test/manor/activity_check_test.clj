(ns manor.activity-check-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [manor.engine :as e]
            [manor.engine.resolve :as resolve]
            [manor.io.store :as store]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.items.activity-check :as activity-check]
            [manor.scenarios.hamlet :as hamlet]
            [manor.test-support :as support]
            [manor.world :as w]))

(use-fixtures :once support/with-runs)

(def correction "Use movement actions to reach the shop, collect the goods there, then return.")
(def errand "going to the shop to collect the geese and returning to the hall")

(defn- start [checker overrides]
  (let [scenario (merge hamlet/scenario
                        {:adjudicators (assoc-in activity-check/adjudicators [:items/activity-check :mock] checker)}
                        overrides)]
    (with-redefs [e/load-scenario (constantly scenario)]
      (e/start-run :hamlet {:llm :mock :offline? true :test? true
                           :run-id (str (gensym "activity-check-"))}))))

(defn- action [run name] (first (filter #(= name (:name %)) (:actions run))))
(defn- jobs [run] (filter #(= :item/work-begun (:type %)) (e/events run)))
(defn- replayed? [run] (= (e/world run) (w/replay (store/read-events (:dir run)))))

(deftest answer-contract-and-small-context
  (is (= {:allowed true} (activity-check/check-answer {:allowed true})))
  (is (= {:allowed false :reason correction}
         (activity-check/check-answer {:allowed false :reason correction})))
  (doseq [bad [{} {:allowed false} {:allowed false :reason "  "} {:allowed "maybe"}]]
    (is (:error (activity-check/check-answer bad))))
  (let [run (start (constantly {:tool :check-activity :args {:allowed true}}) {})
        world (e/world run)
        request (activity-check/request world "ada" {:doing errand :on ["clock"]} [])
        data (json/read-value (get-in request [:messages 1 :content]) json/keyword-keys-object-mapper)]
    (is (= #{:doing :place :stores} (set (keys data))))
    (is (= "Hall" (get-in data [:place :name])))
    (is (= errand (:doing data)))
    (e/stop-run run)))

(deftest rejection-preserves-interrupted-work-and-alias
  (let [calls (atom 0)
        run (start (fn [_] (swap! calls inc) {:tool :check-activity :args {:allowed false :reason correction}}) {})]
    (e/emit! run [(b/occupy (e/world run) "ada" "resting" 10)])
    (e/emit! run [(b/interrupt (e/world run) "ada" "ben")])
    (let [busy (w/busy (e/world run) "ada")]
      (doseq [[name args] [[:spend-time {:doing errand}] [:work-on {:doing errand :items ["clock"]}]]]
        (is (= {:rejected correction} (resolve/resolve! run "ada" (action run name) args)))
        (is (= busy (w/busy (e/world run) "ada")))))
    (is (= 2 @calls))
    (is (empty? (jobs run)))
    (is (= "hall" (get-in (e/world run) [:agents "ada" :location])))
    (is (replayed? run))
    (e/stop-run run)))

(deftest invalid-target-does-not-call-model
  (let [calls (atom 0)
        run (start (fn [_] (swap! calls inc) {:tool :check-activity :args {:allowed true}}) {})]
    (is (:rejected (resolve/resolve! run "ada" (action run :spend-time)
                                    {:doing "polishing" :on ["basket"]})))
    (is (zero? @calls))
    (is (empty? (jobs run)))
    (e/stop-run run)))

(deftest failed-check-never-starts
  (doseq [checker [(fn [_] (throw (ex-info "provider failed" {})))
                   (constantly {:tool :check-activity :args {:allowed false}})]]
    (let [run (start checker {})]
      (is (:rejected (resolve/resolve! run "ada" (action run :spend-time) {:doing "resting"})))
      (is (empty? (jobs run)))
      (is (nil? (w/busy (e/world run) "ada")))
      (is (some #(= :adjudication/failed (:type %)) (e/events run)))
      (is (replayed? run))
      (e/stop-run run))))

(deftest rejection-can-be-corrected-in-the-same-turn
  (doseq [schedule [:round-robin :simultaneous]]
    (let [checks (atom [])
          checker (fn [{:keys [doing]}]
                    (swap! checks conj doing)
                    ;; Explicit scripted verdicts, not a prose classifier.
                    {:tool :check-activity :args (if (= doing errand)
                                                  {:allowed false :reason correction}
                                                  {:allowed true})})
          policy (fn [{:keys [agent-id step]}]
                   (if (= agent-id "ada")
                     {:tool :spend-time :args {:doing (if (zero? step) errand "sweeping the hall")}}
                     {:tool :pass}))
          run (start checker {:schedule schedule :mock-policy policy})]
      (e/auto! run 1)
      (is (= :idle (e/wait-idle run 60000)))
      (is (= [errand "sweeping the hall"] @checks) "accepted queued actions are not checked twice")
      (is (= ["sweeping the hall"] (map #(get-in % [:data :doing]) (jobs run))))
      (is (= "hall" (get-in (e/world run) [:agents "ada" :location])))
      (is (some #(= (str "rejected: " correction) (:content %))
                (mapcat :replies (store/read-llm (:dir run)))))
      (is (replayed? run))
      (e/stop-run run))))

(deftest cost-cap-prevents-admission-call
  (let [calls (atom 0)
        run (start (fn [_] (swap! calls inc) {:tool :check-activity :args {:allowed true}}) {})
        capped (assoc-in run [:opts :max-cost] 0)]
    (is (:rejected (resolve/resolve! capped "ada" (action run :spend-time) {:doing "resting"})))
    (is (zero? @calls))
    (is (empty? (jobs run)))
    (e/stop-run run)))
