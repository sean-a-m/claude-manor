(ns manor.scenarios.claudeworth.money
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
