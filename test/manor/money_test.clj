(ns manor.money-test
  (:require [clojure.test :refer [deftest is]]
            [manor.scenarios.claudeworth.money :as money]))

(def england (money/spec {}))
(def tael {:units [{:value 1000 :singular "tael" :plural "taels"}
                   {:value 1 :singular "cash" :plural "cash"}]
           :zero "0 cash" :base-name "cash" :authored 1000 :authored-name "whole taels"})

(deftest old-sterling
  (is (= 720000 (money/authored->units england 3000)))
  (is (= 0 (money/authored->units england 0)))
  (is (= -480 (money/authored->units england -2)))
  (doseq [[n label] [[0 "0d"] [1 "1d"] [11 "11d"] [12 "1s"] [18 "1s 6d"]
                    [239 "19s 11d"] [240 "£1"] [241 "£1 1d"] [258 "£1 1s 6d"]
                    [720000 "£3000"] [-18 "-1s 6d"] [-258 "-£1 1s 6d"]]]
    (is (= label (money/display england n)))))

(deftest another-currency
  (is (= tael (money/spec {:state {:setting-pack {:money tael}}})))
  (doseq [[n units] [[0 0] [1 1000] [-2 -2000]]]
    (is (= units (money/authored->units tael n))))
  (doseq [[n label] [[0 "0 cash"] [1 "1 cash"] [250 "250 cash"] [1000 "1 tael"]
                    [3250 "3 taels 250 cash"] [-1001 "-1 tael 1 cash"]]]
    (is (= label (money/display tael n)))))
