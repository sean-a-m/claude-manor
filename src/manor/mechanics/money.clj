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

(defn example
  "An amount in `spec`'s money as people write it, for reasons and docs:
  three of the largest unit and ten of the next, \"£3 10s\" or \"3 taels 10 cash\"."
  [spec]
  (let [[big small] (:units spec)]
    (display spec (+ (* 3 (:value big)) (if small (* 10 (:value small)) 0)))))

(defn- unit-pattern
  "A regex matching one component of `unit` at the start of a text, its
  number in group 1: \"£15\", \"10s\", \"3 taels\". Case-insensitive."
  [{:keys [prefix suffix singular plural]}]
  (let [q #(java.util.regex.Pattern/quote %)
        end "(?![\\p{L}\\d])"]
    (re-pattern
     (str "(?iu)^"
          (if singular
            (str "(\\d+)\\s+(?:" (q plural) "|" (q singular) ")" end)
            (str (when prefix (str (q prefix) "\\s*")) "(\\d+)" (when suffix (str "\\s*" (q suffix) end))))))))

(defn- components
  "The amount `text` comes to, read as components of `spec`'s units in
  descending order, each at most once, separated by spaces or commas; nil
  if it isn't wholly that."
  [spec text]
  (loop [s (str/trim text) units (:units spec) total 0 any? false]
    (if (str/blank? s)
      (when any? total)
      (when-let [[unit & more] (seq units)]
        (if-let [[whole n] (re-find (unit-pattern unit) s)]
          (recur (str/replace-first (subs s (count whole)) #"^[\s,]+" "") more
                 (+ total (* (parse-long n) (:value unit))) true)
          (recur s more total any?))))))

(defn parse
  "The amount, in the smallest unit, of `text` written in `spec`'s money as
  people write it (\"£15 10s\", \"15s 6d\", \"3 taels 250 cash\"), or nil.
  A bare number is nil: it doesn't say which unit it counts."
  [spec text]
  (when (string? text) (components spec text)))

(defn parse-reason
  "Why `text` isn't an amount in `spec`'s money, or nil; the reason shows
  how to write one."
  [spec text]
  (when-not (parse spec text)
    (str (if (and (string? text) (re-matches #"\s*\d+\s*" text))
           (str "a bare number doesn't say which coin it counts")
           (str "\"" text "\" isn't an amount of money"))
         "; write it in money with its units, e.g. \"" (example spec) "\"")))

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
