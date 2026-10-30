(ns manor.web-test
  "The control panel renders for worlds with and without areas and a clock."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [manor.engine :as e]
            [manor.test-support :as support]
            [manor.tools.web :as web]))

(use-fixtures :once support/with-runs)

(def ^:private status-html #'web/status-fragment)

(defn- run-of [k n]
  (let [run (e/start-run k {:llm :mock :offline? true :test? true :run-id (str (gensym (str "web-" (name k) "-")))})]
    (e/auto! run n)
    (e/wait-idle run 60000)
    run))

(deftest panel-renders
  (testing "a world with areas and a clock: one grid per area, times, busy people"
    (let [run (run-of :hamlet 3)
          html (status-html run)]
      (is (str/includes? html "HOUSE"))
      (is (str/includes? html "VILLAGE"))
      (is (str/includes? html "Saturday 15 October 1904, 22:00"))
      (is (str/includes? html "Ada (asleep)"))
      (e/stop-run run)))
  (testing "a world without areas still uses the circle"
    (let [run (run-of :village 1)
          html (status-html run)]
      (is (str/includes? html "Village square"))
      (is (not (str/includes? html "HOUSE")))
      (e/stop-run run))))
