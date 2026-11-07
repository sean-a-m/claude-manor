(ns manor.mechanics.appointments.post-check
  "The post check: a light judge of `appoint` and `dismiss`, before any
  effect (the actions' :admission). It refuses only the absurd, such as a
  servant dismissing their master, and allows the improper, which is the
  characters' business. It sees the two people's posts and the proposal,
  nothing private."
  (:require [clojure.string :as str]
            [malli.json-schema :as mjs]
            [manor.actions :as actions]
            [manor.mechanics.adjudication :as adjudication]
            [manor.mechanics.positions :as positions]
            [manor.prompt :as prompt]
            [manor.prompts :as prompts]
            [manor.world :as w]))

(def answer-schema
  [:map
   [:allowed :boolean]
   [:reason {:optional true} [:string {:min 1 :max 300}]]])

(defn check-answer
  "Require a reason for a refusal; an allowed change needs none."
  [raw]
  (let [{:keys [args error]} (actions/decode-args {:args answer-schema} raw)]
    (cond
      error {:error error}
      (and (false? (:allowed args)) (str/blank? (:reason args)))
      {:error "A refused appointment or dismissal needs a short reason, addressed to the person making it."}
      :else args)))

(defn- position-summary [world position]
  (let [p (get-in world [:state :positions position])]
    {:post (:title p (name position)) :about (:about p)}))

(defn- person-summary
  "Someone's name, post, its duties and household: nothing private."
  [world id]
  (merge {:name (:name (w/agent world id))}
         (position-summary world (:role (w/agent world id)))
         {:household (:household (positions/post world id))}))

(defn proposal
  "What the judge sees of `actor`'s `appoint` (whose args have a `position`)
  or `dismiss` (whose args have none)."
  [world actor {:keys [who position reason]}]
  (-> {:action (if position "appoint" "dismiss")
       :by (person-summary world actor)
       :who (person-summary world who)}
      (cond-> position (assoc :to (position-summary world (keyword position)))
              (not (str/blank? reason)) (assoc :reason reason))
      (assoc :setting (get-in world [:state :setting]))))

(defn request
  "The judge's request about `actor`'s appoint or dismiss with `args`."
  [world actor args _events]
  {:messages [{:role "system" :content (prompts/render world "judges/post-check.md" {})}
              {:role "user" :content (prompt/->json (proposal world actor args))}]
   :tools [{:type "function"
            :function {:name "check-post"
                       :description (prompts/render world "judges/tools/check-post.md" {})
                       :parameters (mjs/transform answer-schema)}}]
   :ctx (select-keys args [:who :position])
   :check #(adjudication/answer "check-post" % check-answer)})

(def adjudicators
  {:people/post-check
   {:request request
    ;; Mock changes are allowed. Tests supply refusals as explicit verdicts;
    ;; prose is never classified by keyword or regex heuristics.
    :mock (constantly {:tool :check-post :args {:allowed true}})
    :failure-reply "The post check failed; nothing has changed. Try again later."}})
