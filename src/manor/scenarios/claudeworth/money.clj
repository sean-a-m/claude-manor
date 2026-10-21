(ns manor.scenarios.claudeworth.money
  "Claudeworth's pre-decimal sterling. State uses integer old pence, never floats.
  This is unrelated to model usage costs (USD)."
  (:require [clojure.string :as str]))

(def pence-per-pound 240)

(defn pounds->pence
  "Convert authored whole-pound amounts at the world-file or architect boundary."
  [pounds]
  (* pounds pence-per-pound))

(defn display
  "Human-readable sterling, omitting zero components: 258 -> £1 1s 6d."
  [pence]
  (let [n (abs pence)
        pounds (quot n 240)
        shillings (quot (rem n 240) 12)
        pennies (rem n 12)]
    (str (when (neg? pence) "-")
         (if (zero? n) "0d"
             (str/join " " (cond-> []
                             (pos? pounds) (conj (str "£" pounds))
                             (pos? shillings) (conj (str shillings "s"))
                             (pos? pennies) (conj (str pennies "d"))))))))
