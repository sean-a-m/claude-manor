(ns people.generate
  "Base records for new people, rolled from the data files: name, sex, age,
  origin, where they live, five temperament levels and one faculty from each
  of the seven groups. Only basic readings and definitions: the engine's
  character writer supplies circumstances and prose. Deterministic from seed."
  (:require [clojure.edn :as edn]
            [clojure.string :as str])
  (:import (java.util Random)))

(defn- data [file] (edn/read-string (slurp file)))

(def temperament (data "temperament.edn"))
(def faculties (data "faculties.edn"))
(def names (data "names.edn"))

(def default-ages
  "The ages rolled when a request gives none. The engine passes a position's
  own :ages and :sex from its catalogue (resources/settings/<setting>/positions.edn)."
  [18 55])

(def settings
  "The settings this server has data for. Another setting would add its own files."
  #{"england-1900"})

(def max-count 10)

;; ---------------------------------------------------------------------------
;; Dice

(defn rng
  "A random source seeded from `parts` (FNV-1a over their joined text), so the
  same parts always give the same rolls."
  ^Random [& parts]
  (Random. (reduce (fn [h c] (unchecked-multiply (bit-xor h (long c)) 1099511628211))
                   -3750763034362895579
                   (str/join "/" parts))))

(defn pick [^Random r xs] (nth xs (.nextInt r (count xs))))

(defn some-of
  "`n` of `xs` chosen at random, in their original order."
  [^Random r n xs]
  (let [order (java.util.ArrayList. ^java.util.Collection (range (count xs)))]
    (java.util.Collections/shuffle order r)
    (mapv #(nth xs %) (sort (take n order)))))

;; ---------------------------------------------------------------------------
;; Rolling one person

(def ^:private honorifics #{"Mr" "Mrs" "Miss" "Master" "Lord" "Lady" "Sir" "Dr"})

(defn- name-words [s] (remove honorifics (str/split (str/trim (str s)) #"\s+")))

(defn- roll-name
  "A first name and surname, neither of which is a word of a taken name, so
  nobody new shares a name with anyone already about."
  [r sex taken]
  (let [used (into #{} (mapcat name-words) taken)]
    (loop [tries 0]
      (let [first-name (pick r (get names sex))
            surname (pick r (:surnames names))]
        (cond
          (not (or (used first-name) (used surname))) (str first-name " " surname)
          (< tries 500) (recur (inc tries))
          :else (throw (ex-info "no untaken name left" {})))))))

(defn- roll-temperament [r]
  (vec (for [{:keys [axis measures]} (:axes temperament)]
         {:axis axis :level (pick r (:levels temperament)) :measures measures})))

(defn- roll-faculties [r]
  (vec (for [{:keys [group] :as g} (:groups faculties)
             :let [f (pick r (:faculties g))
                   size (pick r (:sizes faculties))]]
         {:group group :faculty (:faculty f) :means (:means f) :size size})))

(defn position-key
  "\"Scullery maid\" or \"scullery-maid\" -> :scullery-maid"
  [position]
  (keyword (str/replace (str/lower-case (str/trim (str position))) #"[^a-z0-9]+" "-")))

(defn- person [r {:keys [position area sex ages candidate]} taken]
  (let [sex (or sex (pick r [:male :female]))
        [lo hi] (or ages default-ages)]
    {:name (roll-name r sex taken)
     :sex sex
     :age (+ lo (.nextInt r (inc (- hi lo))))
     :position (name position)
     :origin (pick r (:origins names))
     ;; A hiring home is off-map background, never an internal walking-area id.
     (if candidate :candidate-home :lives)
     (let [dwelling (pick r (:dwellings names))]
       (if candidate
         (str dwelling ", " (pick r (:candidate-home-areas names)))
         (cond-> dwelling (not (str/blank? area)) (str ", " area))))
     :temperament (roll-temperament r)
     :faculties (roll-faculties r)}))

;; ---------------------------------------------------------------------------
;; The tool

(defn- checked
  "The request with defaults, or an ex-info saying what is wrong."
  [{:keys [position count setting seed sex ages candidate] :as req}]
  (let [n (or count 1)]
    (cond
      (str/blank? (str position)) (throw (ex-info "position is required, such as \"housemaid\"" {}))
      (not (and (integer? n) (<= 1 n max-count))) (throw (ex-info (str "count must be 1 to " max-count) {}))
      (and setting (not (settings setting))) (throw (ex-info (str "unknown setting; known: " (str/join ", " (sort settings))) {}))
      (nil? seed) (throw (ex-info "seed is required" {}))
      (and (contains? req :candidate) (not (boolean? candidate))) (throw (ex-info "candidate must be a boolean" {}))
      (and sex (not (#{"male" "female"} sex))) (throw (ex-info "sex must be \"male\" or \"female\"" {}))
      (and ages (not (and (sequential? ages) (= 2 (clojure.core/count ages)) (every? nat-int? ages) (apply <= ages))))
      (throw (ex-info "ages must be [youngest oldest], whole numbers" {}))
      :else (assoc req :count n :position (position-key position) :sex (some-> sex keyword)))))

(defn generate
  "`count` new people for `position`: basic identity/readings and definitions,
  named so as not to clash with `taken-names` or with each other."
  [req]
  (let [{:keys [count position seed taken-names] :as req} (checked req)]
    (loop [i 0, taken (vec taken-names), people []]
      (if (= i count)
        {:people people}
        (let [p (person (rng seed (name position) i) req taken)]
          (recur (inc i) (conj taken (:name p)) (conj people p)))))))
