(ns architect.references
  "Read-only architectural extracts, never the roles or social rules."
  (:require [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def max-text 2500)

;; The Ashcombe documents are the reference library until the curated reference
;; replaces them (design decision 9). They carry social detail the library should
;; not: who may use a stair, when servants work. Drop whole sentences that state
;; such a rule; keep everything else, so the text stays the source's.
(def ^:private social-word
  #"(?i)\b(?:servants?|staff|family|guests?|visitors?|tradesmen|household|housemaids?|butler|footm[ae]n|maids?|valet|housekeeper)\b")
(def ^:private permission-word
  #"(?i)\b(?:do(?:es)? not|must not|may not|are not|is not|not used|reserved|forbidden|only|unless|never)\b")

(defn- social-rule? [sentence]
  (and (re-find social-word sentence) (re-find permission-word sentence)))

(defn- architectural
  "Drop whole sentences that state a social rule, not architecture."
  [text]
  (->> (str/split text #"(?<=[.!?])\s+")
       (remove social-rule?)
       (str/join " ")
       str/trim))

(defn- entry
  "One location as an architectural entry: the allowed fields, minus social rules."
  [loc]
  (-> (select-keys loc [:name :floor :zone :description])
      (cond-> (:description loc) (update :description architectural))
      (assoc :connects-to (:connects_to loc))))

(defn library [dir]
  (let [world (yaml/parse-string (slurp (io/file dir "ashcombe_world.yaml")))
        spec (slurp (io/file dir "ashcombe_spec.md"))]
    (concat
     (for [loc (:locations world)]
       {:source "ashcombe_world.yaml" :title (:name loc) :text (pr-str (entry loc))})
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
