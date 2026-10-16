(ns manor.clock-test
  (:require [clojure.test :refer [deftest is testing]]
            [manor.clock :as clock]))

(def c {:start "1904-10-15T21:00" :minutes-per-round 30})

(deftest rounds-and-times
  (testing "round 1 starts at :start, each round is one step later"
    (is (= "Saturday 15 October 1904, 21:00" (clock/label c 1)))
    (is (= "Saturday 15 October 1904, 21:30" (clock/label c 2)))
    (is (= "Sunday 16 October 1904, 00:00" (clock/label c 7))))
  (testing "round 0 (setup) counts as round 1"
    (is (= (clock/label c 1) (clock/label c 0))))
  (testing "short labels add the day only when it differs from today"
    (is (= "21:30" (clock/short-label c 2 3)))
    (is (= "Sat 23:30" (clock/short-label c 6 7))))
  (is (= 48 (clock/rounds-per-day c)))
  (is (= 1 (clock/rounds-for c 10)))
  (is (= 4 (clock/rounds-for c 120))))

(deftest waiting-until-a-time
  (testing "the next 07:00 from 21:30 is round 21"
    (is (= "Sunday 16 October 1904, 07:00" (clock/label c (clock/round-at c 2 "07:00"))))
    (is (= 21 (clock/round-at c 2 "07:00"))))
  (testing "a time between rounds rounds up"
    (is (= "Sunday 16 October 1904, 07:30" (clock/label c (clock/round-at c 2 "07:10")))))
  (testing "the current time means tomorrow"
    (is (= (+ 2 48) (clock/round-at c 2 "21:30")))))

(deftest malformed-clocks
  (is (nil? (clock/problems c)))
  (is (= 1 (count (clock/problems (assoc c :start "15/10/1904")))))
  (is (= 1 (count (clock/problems (assoc c :minutes-per-round 0)))))
  (is (seq (clock/problems "noon"))))
