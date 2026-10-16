(ns architect.references
  "Read-only architectural extracts, never the roles or social rules."
  (:require [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def max-text 2500)

(defn library [dir]
  (let [world (yaml/parse-string (slurp (io/file dir "ashcombe_world.yaml")))
        spec (slurp (io/file dir "ashcombe_spec.md"))]
    (concat
     (for [loc (:locations world)]
       {:source "ashcombe_world.yaml" :title (:name loc)
        :text (pr-str (-> (select-keys loc [:name :floor :zone :description])
                         (assoc :connects-to (:connects_to loc))))})
     (for [section (str/split spec #"(?m)(?=^#{1,4} )")
           :let [[heading & lines] (str/split-lines section)]
           :when (re-find #"^#{2,3} (6\.|10\.2 |10\.4 )" (or heading ""))]
       {:source "ashcombe_spec.md" :title (str/replace heading #"^#+ " "")
        :text (str/join "\n" lines)}))))

(defn search [entries query]
  (let [words (set (re-seq #"[a-z0-9]+" (str/lower-case (str query))))
        score (fn [entry]
                (count (filter #(str/includes? (str/lower-case (str (:title entry) " " (:text entry))) %) words)))
        matches (->> entries (filter #(pos? (score %))) (sort-by (comp - score)) (take 3))]
    {:matches (loop [entries matches remaining max-text out []]
                (if (or (empty? entries) (<= remaining 0)) out
                    (let [entry (first entries)
                          text (:text entry)
                          text (subs text 0 (min remaining (count text)))]
                      (recur (next entries) (- remaining (count text))
                             (conj out (assoc entry :text text))))))}))
