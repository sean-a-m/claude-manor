(ns manor.mechanics.money
  "Setting-owned currency. Amounts are integers in the smallest unit.
  This is unrelated to model usage costs (USD)."
  (:require [clojure.string :as str]
            [manor.settings :as settings]))

(defn spec
  "The run's snapshotted money specification."
  [world]
  (:money (settings/pack world)))

(defn authored->units
  "Convert authored amounts at the world-file or architect boundary."
  [spec n]
  (* n (:authored spec)))

(defn- component [{:keys [prefix suffix singular plural]} n]
  (if singular
    (str n " " (if (= 1 n) singular plural))
    (str prefix n suffix)))

(defn display
  "Format an amount using descending units, omitting zero components."
  [spec n]
  (str (when (neg? n) "-")
       (if (zero? n)
         (:zero spec)
         (str/join " "
                   (second
                    (reduce (fn [[remaining parts] {:keys [value] :as unit}]
                              (let [q (quot remaining value)]
                                [(rem remaining value) (cond-> parts (pos? q) (conj (component unit q)))]))
                            [(abs n) []] (:units spec)))))))

(defn account
  "The paying owner: household by default; own can only mean the actor."
  [actor from]
  (if (= "own" from) actor "household"))

(defn spend-reason
  "Why the selected account cannot cover n, or nil. Never exposes another
  character's balance. Callers prefix this with the cost of their purchase."
  [world actor from n]
  (let [owner (account actor from)
        balance (get-in world [:state :accounts owner])]
    (cond
      (nil? balance) "you have no personal account; use from \"household\" instead"
      (> n balance) (str (if (= owner "household") (str (settings/parameter world :household-name) " has ") "your purse has ")
                         (display (spec world) balance)
                         (when (= "own" from) "; spend less or use from \"household\" instead")))))
