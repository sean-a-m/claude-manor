(ns manor.money-test
  (:require [clojure.test :refer [deftest is]]
            [manor.money :as money]))

(deftest old-sterling
  (is (= 720000 (money/pounds->pence 3000)))
  (doseq [[pence label] [[0 "0d"] [1 "1d"] [11 "11d"] [12 "1s"] [18 "1s 6d"]
                        [239 "19s 11d"] [240 "£1"] [241 "£1 1d"] [258 "£1 1s 6d"]
                        [720000 "£3000"] [-18 "-1s 6d"]]]
    (is (= label (money/display pence)))))
