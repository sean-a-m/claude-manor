(ns manor.mechanics.items.activity-check
  "Admission to a timed activity, before any job or occupation starts.
  A small semantic check, not a prediction of the work's outcome."
  (:require [clojure.string :as str]
            [malli.json-schema :as mjs]
            [manor.actions :as actions]
            [manor.mechanics.adjudication :as adjudication]
            [manor.mechanics.items :as items]
            [manor.mechanics.space :as space]
            [manor.prompt :as prompt]
            [manor.prompts :as prompts]))

(def answer-schema
  [:map
   [:allowed :boolean]
   [:reason {:optional true} [:string {:min 1 :max 300}]]])

(defn check-answer
  "Require a corrective reason for rejection; accepted activities need none."
  [raw]
  (let [{:keys [args error]} (actions/decode-args {:args answer-schema} raw)]
    (cond
      error {:error error}
      (and (false? (:allowed args)) (str/blank? (:reason args)))
      {:error "A rejected activity needs a short reason explaining what to do instead."}
      :else args)))

(defn request
  "Only the proposal, current place and in-scope store names. No history,
  inventory, other rooms, private state or prediction of possible results."
  [world actor {:keys [doing]} _events]
  (let [place (space/location-of world actor)]
    {:messages [{:role "system" :content (prompts/render world "judges/activity-check.md" {})}
                {:role "user" :content (prompt/->json
                                        {:doing doing
                                         :place (select-keys (space/location world place) [:id :name])
                                         :stores (mapv #(select-keys % [:id :name]) (items/stores-for world place))})}]
     :tools [{:type "function"
              :function {:name "check-activity"
                         :description (prompts/render world "judges/tools/check-activity.md" {})
                         :parameters (mjs/transform answer-schema)}}]
     :ctx {:doing doing}
     :check #(adjudication/answer "check-activity" % check-answer)}))

(def adjudicators
  {:items/activity-check
   {:request request
    ;; Scripted mock activities are accepted. Tests supply explicit verdicts;
    ;; prose is never classified by keyword or regex heuristics.
    :mock (constantly {:tool :check-activity :args {:allowed true}})
    :failure-reply "The activity check failed; no work has started. Choose another action or try again later."}})
