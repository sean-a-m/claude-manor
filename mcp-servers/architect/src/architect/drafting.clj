(ns architect.drafting
  "Stateless drafting: tidy a request, choose a fresh id, quote the price from the
  price list the caller sends."
  (:require [clojure.string :as str]))

(defn tidy [s] (str/trim (str/replace (str s) #"\s+" " ")))

(defn fresh-id [base taken]
  (first (remove (set taken) (cons base (map #(str base "-" %) (iterate inc 2))))))

(defn draft [{:keys [house taken request prices]}]
  (let [{:keys [name floor desc connects-to size]} request
        name (tidy name)
        price (first (filter #(= size (:size %)) prices))
        rooms (set (map :id house))
        base (-> name str/lower-case (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-|-$" ""))]
    (when (str/blank? name) (throw (ex-info "give the room a non-empty name" {})))
    (when-not price (throw (ex-info (str "size must be one of: " (str/join ", " (map :size prices))) {})))
    (when-let [bad (first (remove rooms connects-to))]
      (throw (ex-info (str bad " is not in the house; use: " (str/join ", " (sort rooms))) {})))
    {:plan {:id (fresh-id (if (str/blank? base) "room" base) taken)
            :name name :floor (tidy (if (keyword? floor) (clojure.core/name floor) floor))
            :desc (tidy desc) :connects-to (vec connects-to)}
     :price (:price price) :build-days (:build-days price)}))
