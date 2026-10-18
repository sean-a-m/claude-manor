(ns people.generate
  "Base records for new people, rolled from the data files: name, sex, age,
  origin, where they live, five temperament levels, one faculty from each of
  the seven groups and one or two circumstances, each with its descriptor
  text. Deterministic: the same request always gives the same people."
  (:require [clojure.edn :as edn]
            [clojure.string :as str])
  (:import (java.util Random)))

(defn- data [file] (edn/read-string (slurp file)))

(def temperament (data "temperament.edn"))
(def faculties (data "faculties.edn"))
(def circumstances (data "circumstances.edn"))
(def names (data "names.edn"))

(def default-ages
  "The ages rolled when a request gives none. The engine passes a position's
  own :ages and :sex from its catalogue (resources/positions/)."
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

(defn pick-weighted
  "One of `xs`, each as likely as its `weight`; nil if every weight is 0."
  [^Random r weight xs]
  (let [total (reduce + 0 (map weight xs))]
    (when (pos? total)
      (loop [n (.nextInt r (int total)), [x & more] xs]
        (if (< n (weight x)) x (recur (- n (weight x)) more))))))

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
  (vec (for [{:keys [axis] :as a} (:axes temperament)
             :let [level (pick r (:levels temperament))]]
         {:axis axis :level level :text (pick r (get a level))})))

(defn- roll-faculties [r]
  (vec (for [{:keys [group] :as g} (:groups faculties)
             :let [f (pick r (:faculties g))
                   size (pick r (:sizes faculties))]]
         {:group group :faculty (:faculty f) :means (:means f) :size size :text (get f size)})))

(defn excluded?
  "Whether circumstance `id` may not be rolled beside any of `picked` (ids)."
  [picked id]
  (boolean (some (fn [[a b]] (or (and (= a id) (picked b)) (and (= b id) (picked a))))
                 (:exclusions circumstances))))

(defn- roll-circumstances
  "One or two circumstances from different groups, weighted for `position`,
  never an excluded pair."
  [r position]
  (let [all (for [{:keys [group] :as g} (:groups circumstances)
                  c (:circumstances g)]
              (assoc c :group group))
        weight #(get (:weights %) position 1)]
    (loop [picked [], n (inc (.nextInt r 2))]
      (let [ids (set (map :id picked))
            groups (set (map :group picked))
            c (when (pos? n)
                (pick-weighted r weight (remove #(or (groups (:group %)) (excluded? ids (:id %))) all)))]
        (if c
          (recur (conj picked c) (dec n))
          (mapv (fn [c] {:id (:id c) :group (:group c) :label (:label c) :text (pick r (:texts c))})
                picked))))))

(defn position-key
  "\"Scullery maid\" or \"scullery-maid\" -> :scullery-maid"
  [position]
  (keyword (str/replace (str/lower-case (str/trim (str position))) #"[^a-z0-9]+" "-")))

(defn- person [r {:keys [position area sex ages]} taken]
  (let [sex (or sex (pick r [:male :female]))
        [lo hi] (or ages default-ages)]
    {:name (roll-name r sex taken)
     :sex sex
     :age (+ lo (.nextInt r (inc (- hi lo))))
     :position (name position)
     :origin (pick r (:origins names))
     :lives (cond-> (pick r (:dwellings names)) (not (str/blank? area)) (str ", " area))
     :temperament (roll-temperament r)
     :faculties (roll-faculties r)
     :circumstances (roll-circumstances r position)}))

;; ---------------------------------------------------------------------------
;; The tool

(defn- checked
  "The request with defaults, or an ex-info saying what is wrong."
  [{:keys [position count setting seed sex ages] :as req}]
  (let [n (or count 1)]
    (cond
      (str/blank? (str position)) (throw (ex-info "position is required, such as \"housemaid\"" {}))
      (not (and (integer? n) (<= 1 n max-count))) (throw (ex-info (str "count must be 1 to " max-count) {}))
      (and setting (not (settings setting))) (throw (ex-info (str "unknown setting; known: " (str/join ", " (sort settings))) {}))
      (nil? seed) (throw (ex-info "seed is required" {}))
      (and sex (not (#{"male" "female"} sex))) (throw (ex-info "sex must be \"male\" or \"female\"" {}))
      (and ages (not (and (sequential? ages) (= 2 (clojure.core/count ages)) (every? nat-int? ages) (apply <= ages))))
      (throw (ex-info "ages must be [youngest oldest], whole numbers" {}))
      :else (assoc req :count n :position (position-key position) :sex (some-> sex keyword)))))

(defn generate
  "`count` new people for `position`: base records with their descriptor
  texts, named so as not to clash with `taken-names` or with each other."
  [req]
  (let [{:keys [count position seed taken-names] :as req} (checked req)]
    (loop [i 0, taken (vec taken-names), people []]
      (if (= i count)
        {:people people}
        (let [p (person (rng seed (name position) i) req taken)]
          (recur (inc i) (conj taken (:name p)) (conj people p)))))))
