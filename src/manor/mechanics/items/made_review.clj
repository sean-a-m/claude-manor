(ns manor.mechanics.items.made-review
  "A second look at new things a work judge made: when its checked verdict
  makes something from nothing tracked (no `from`, no `taken`), the judge
  sees the finder's candidates, may `inspect` some once, and says of each
  new thing whether it's new or the same object as a listed candidate. A
  candidate within reach of the job is used instead of being copied; one
  out of reach isn't made, and the summary is rewritten to say so. Aspects
  of things out of reach are never changed."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [malli.json-schema :as mjs]
            [manor.actions :as a]
            [manor.mechanics.adjudication :as adjudication]
            [manor.mechanics.items :as items]
            [manor.mechanics.items.find :as item-find]
            [manor.mechanics.items.judge :as item-judge]
            [manor.prompts :as prompts]))

(defn fresh
  "The verdict's made entries that come from nothing tracked, with their
  indexes: [[i entry] ...]."
  [verdict]
  (keep-indexed (fn [i m] (when-not (or (:from m) (:taken m)) [i m])) (:made verdict)))

(defn needed?
  "Does this work verdict make anything that might already exist? Not for
  acts, which make nothing, nor for a hiring reference, which is new by
  definition."
  [job verdict]
  (and (not (:act? job)) (not (:reference job)) (seq (fresh verdict))))

(def review-schema
  [:map
   [:made [:vector {:min 1 :max items/max-made}
           [:map [:index :int] [:same {:optional true} [:maybe :string]]]]]
   [:summary {:optional true} [:string {:min 1 :max items/max-summary}]]])

(defn review-request
  "The follow-up to a work judge's checked `verdict`: the conversation so
  far is `messages` (ending with the judge's answer and its replies); the
  new user message lists the fresh made things and the candidates, and
  offers `inspect` once and `review`."
  [world job verdict messages events]
  (let [candidates (item-find/finder-candidates world (:actor job))
        made (vec (for [[i m] (fresh verdict)] {:index i :name (:name m) :desc (:desc m)}))]
    {:messages (conj (vec messages)
                     {:role "user"
                      :content (str (prompts/render world "judges/made-review.md" {})
                                    "\n\n```json\n"
                                    (json/write-value-as-string {:made made :candidates candidates} item-judge/pretty)
                                    "\n```")})
     :tools [{:type "function" :function {:name "review"
                                          :description (prompts/render world "judges/tools/review.md" {})
                                          :parameters (mjs/transform review-schema)}}
             (item-find/inspect-tool world "review")]
     :lookups {"inspect" #(item-find/inspect-lookup world events (:actor job) candidates %)}
     :max-lookups 1
     :candidates candidates
     :made made}))

(defn- to-hand?
  "Is `it` at the job's place (in a container or not) or carried by the worker?"
  [world {:keys [place actor]} it]
  (let [o (items/outermost world it)]
    (or (= actor (:holder o)) (and place (= place (:place o))))))

(defn- review-problem
  "Why the review won't do, or nil."
  [{:keys [made summary]} fresh-indexes candidate-ids dropped?]
  (let [indexes (map :index made)]
    (cond
      (not= (set indexes) (set fresh-indexes))
      (str "give one entry for each new thing, by index: " (str/join ", " (sort fresh-indexes)))
      (not= (count indexes) (count (distinct indexes))) "give each index once"
      (some #(and (:same %) (not (candidate-ids (:same %)))) made)
      (str "same must be a listed candidate's id, not " (str/join ", " (keep #(when-not (candidate-ids (:same %)) (:same %)) (filter :same made))))
      (and dropped? (str/blank? summary))
      "a thing you named is not to hand, so it can't be made: give a new summary, without it")))

(defn apply-review
  "The verdict once reviewed: each fresh made thing kept, or dropped as the
  same object as a candidate. A candidate in reach of the job is used, not
  copied; dropping one out of reach needs a new summary."
  [world job verdict {:keys [made summary]}]
  (let [same (into {} (keep #(when (:same %) [(:index %) (:same %)])) made)
        kept (vec (keep-indexed (fn [i m] (when-not (contains? same i) m)) (:made verdict)))]
    (cond-> (assoc verdict :made kept)
      (empty? kept) (dissoc :made)
      (and (seq same) summary) (assoc :summary summary))))

(defn review-check
  "Check a `review` answer against the fresh things and the candidates."
  [world job verdict candidates response]
  (let [fresh-indexes (map first (fresh verdict))
        candidate-ids (set (map :id candidates))]
    (adjudication/answer
     "review" response
     (fn [raw]
       (let [{:keys [args error]} (a/decode-args {:args review-schema} raw)
             dropped? (some #(when-let [id (:same %)] (not (to-hand? world job (items/item world id)))) (:made args))]
         (if-let [problem (or error (review-problem args fresh-indexes candidate-ids dropped?))]
           {:error problem}
           (apply-review world job verdict args)))))))

(defn mock-review
  "For mock runs: every new thing is new."
  [{:keys [made]}]
  {:tool :review :args {:made (mapv #(select-keys % [:index]) made)}})
