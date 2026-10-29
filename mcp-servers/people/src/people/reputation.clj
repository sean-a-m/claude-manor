(ns people.reputation
  "What one person knows of another: how well they know them, and which
  basic readings and model-written circumstances they know. No stock behaviours."
  (:require [clojure.string :as str]
            [people.generate :as g]))

;; How well each relation knows someone, and how many of each kind of trait
;; it may know. :years is the range the acquaintance has lasted.
(def relations
  {"by-sight" {:text "%k knows %p by sight." :temperament 1 :faculties 1 :circumstances 0}
   "neighbour" {:text "%k has been a neighbour of %p these %y years." :years [1 15]
                :temperament 2 :faculties 1 :circumstances 1}
   "acquaintance" {:text "%k has known %p these %y years." :years [2 20]
                   :temperament 3 :faculties 2 :circumstances 1}
   "worked-alongside" {:text "%k worked alongside %p for %y years." :years [1 6]
                       :temperament 3 :faculties 2 :circumstances 2}
   "employer" {:text "%k employed %p for %y years." :years [1 8]
               :temperament 4 :faculties 3 :circumstances 1}
   "family" {:text "%k is family to %p." :temperament 5 :faculties 7 :circumstances 2}})

(def ^:private number-words
  ["no" "one" "two" "three" "four" "five" "six" "seven" "eight" "nine" "ten" "eleven" "twelve"
   "thirteen" "fourteen" "fifteen" "sixteen" "seventeen" "eighteen" "nineteen" "twenty"])

(defn reputation
  "What `knower` knows of `person` (basic traits plus model-written circumstances),
  given their `relation`. Deterministic from `seed`."
  [{:keys [person knower relation seed]}]
  (let [rel (get relations relation)]
    (cond
      (not (and (map? person) (string? (:name person)))) (throw (ex-info "person must be a base record with a name" {}))
      (str/blank? (str knower)) (throw (ex-info "knower is required" {}))
      (nil? rel) (throw (ex-info (str "relation must be one of: " (str/join ", " (sort (keys relations)))) {}))
      (nil? seed) (throw (ex-info "seed is required" {})))
    (let [r (g/rng seed (:name person) knower relation)
          years (when-let [[lo hi] (:years rel)] (+ lo (.nextInt r (inc (- hi lo)))))
          text (-> (:text rel)
                   (str/replace "%k" knower)
                   (str/replace "%p" (:name person))
                   (str/replace "%y" (str (get number-words years years))))]
      {:person (:name person)
       :knower knower
       :relation relation
       :acquaintance text
       :knows (into {} (for [k [:temperament :faculties :circumstances]]
                         [k (g/some-of r (get rel k) (vec (get person k)))]))})))
