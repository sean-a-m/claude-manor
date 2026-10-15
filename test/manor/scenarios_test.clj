(ns manor.scenarios-test
  "Every bundled scenario should run offline with its mock policy and replay
  to the same world. Add new scenarios to `bundled`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [manor.engine :as e]
            [manor.store :as store]
            [manor.world :as w]))

(def bundled [:village :treasure])

(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

(deftest bundled-scenarios-run-offline
  (doseq [k bundled]
    (testing (name k)
      (let [run (e/start-run k {:llm :mock :offline? true :test? true
                                :run-id (str (gensym (str "smoke-" (name k) "-")))})]
        (e/auto! run 8)
        (is (= :idle (e/wait-idle run 60000)))
        (is (pos? (:round (e/world run))))
        (is (empty? (filter #(= :action/forfeited (:type %)) (e/events run)))
            "the mock policy should only choose valid actions")
        (is (= (e/world run) (w/replay (store/read-events (:dir run)))))
        (e/stop-run run)))))
