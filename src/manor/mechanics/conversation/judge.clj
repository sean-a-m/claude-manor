(ns manor.mechanics.conversation.judge
  "The conversation judge: after a conversation ends, one narrow call
  judges how it left the inner state (manor.mechanics.inner) of the people
  in it. Only in scenarios with :inner. It sees the place and time, each
  member (those who left part-way too) with their persona, situation,
  temperament, appearance, aspects and inner state, and the conversation's
  lines in order, each marked with the members who heard it when not all
  of them did. It changes only the members' inner state; its summary is
  for the GM and the transcript. The engine makes the call
  (manor.engine.talk); this namespace has the prompt, the tool, the checks
  and the events."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [malli.json-schema :as mjs]
            [manor.actions :as a]
            [manor.clock :as clock]
            [manor.mechanics.adjudication :as adjudication]
            [manor.mechanics.conversation :as conversation]
            [manor.mechanics.inner :as inner]
            [manor.mechanics.space :as space]
            [manor.narrate :as n]
            [manor.prompts :as prompts]
            [manor.world :as w]))

(def max-summary 300)

(def answer-schema
  [:map
   inner/answer-entry
   [:summary [:string {:min 1 :max max-summary}]]])

(defn- react-tool [world]
  {:type "function"
   :function {:name "react"
              :description (prompts/render world "judges/tools/react.md"
                                           {:advised-value inner/advised-value :max-summary max-summary})
              :parameters (mjs/transform answer-schema)}})

(defn instructions
  "The conversation judge's system message, for the scenario's inner state
  `config`."
  [world config]
  (prompts/render world "judges/conversation.md"
                  {:advised-value inner/advised-value :max-summary max-summary
                   :keys (inner/key-instructions world config)}))

;; ---------------------------------------------------------------------------
;; The request

(def line-types
  "The events that are a conversation's lines."
  #{:agent/said :person/muffled :conversation/silent :conversation/left})

(defn lines
  "The lines of conversation `id` among `events`, in order. A gagged
  speaker's words (:muffled, see manor.mechanics.physical/spoken) aren't
  among them: their muffled noise is."
  [events id]
  (filter #(and (line-types (:type %)) (not (get-in % [:data :muffled]))) (conversation/of events id)))

(defn heard-by
  "The `members` (ids, in order) who heard `event`, from its :visible-to."
  [members event]
  (filterv #(w/visible-to? event %) members))

(defn- line-json
  "A line as the judge sees it, with :heard_by when not every member heard it.
  A muffled noise counts as heard by its speaker too."
  [members {:keys [type actor data] :as event}]
  (let [heard (heard-by members (cond-> event
                                  (and (= :person/muffled type) (set? (:visible-to event))) (update :visible-to conj actor)))]
    (cond-> (case type
              :agent/said {:person actor :says (:text data)}
              :person/muffled {:person actor :does "tries to speak, but only muffled noises come out"}
              :conversation/silent {:person actor :does "says nothing"}
              :conversation/left {:person actor :does "leaves the conversation, staying within hearing"})
      (not= heard members) (assoc :heard_by heard))))

(defn- member-json
  "A member as the judge sees them: everything about them, inner state too."
  [config world id]
  (assoc (select-keys (w/agent world id) [:id :name :persona :situation :temperament :appearance :aspects])
         :inner (inner/judge-view config world id)))

(def ^:private pretty (json/object-mapper {:pretty true}))

(defn judge-request
  "The conversation judge's request about `conv` ({:initiator :members}),
  whose `lines` (see `lines`) are given in order."
  [config world {:keys [initiator members]} lines]
  (let [place (space/location-of world initiator)
        scene {:members (mapv #(member-json config world %) members)
               :lines (mapv #(line-json members %) lines)}]
    {:tools [(react-tool world)]
     :messages
     [{:role "system" :content (instructions world config)}
      {:role "user"
       :content (str "# The conversation\n\n"
                     "- Time: " (if-let [c (:clock world)] (clock/world-label world (:round world)) (str "round " (:round world))) "\n"
                     (when place (str "- Place: " (:name (space/location world place) place) "\n"))
                     "\n```json\n"
                     (json/write-value-as-string scene pretty)
                     "\n```\n\nCall `react` now.")}]}))

;; ---------------------------------------------------------------------------
;; The answer

(defn- verdict [config world members arguments]
  (let [{:keys [args error]} (a/decode-args {:args answer-schema} arguments)
        checked (when-not error (inner/checked-changes config world members (:inner args)))]
    (cond error {:error error}
          (:error checked) checked
          :else {:summary (:summary args) :inner (:changes checked)})))

(defn judgement
  "Read a conversation judge response: {:verdict {:summary :inner} :replies
  [...]} or {:error reason :replies [...]} (see adjudication/answer). Only
  the `members` (ids) can be changed; :inner holds the checked changes
  ({:person :key :from :to})."
  [response world config members]
  (adjudication/answer "react" response #(verdict config world members %)))

(defn mock-judge
  "The conversation judge in mock runs, unless a scenario has
  :mock-conversation-judge: it changes nothing."
  [_ctx]
  {:tool :react :args {:summary "The conversation left everyone as they were."}})

;; ---------------------------------------------------------------------------
;; Events. They hold the conversation's id as :about, not :conversation: they
;; aren't among the conversation's own events (`conversation/of`), which
;; everyone present saw.

(defn judged-events
  "The events of the judge's `verdict` on conversation `id`: a GM-only
  :conversation/judged with its summary, then one GM-only
  :person/inner-changed per changed member. No member is told."
  [id {:keys [summary inner]}]
  (into [{:type :conversation/judged :visible-to :gm :data {:about id :summary summary}}]
        (inner/changed-events {:conversation id} inner)))

(defn judge-failed
  "Event, for the operator only: why the judge's answer about conversation
  `id` wasn't used."
  [id reason]
  {:type :conversation/judge-failed :visible-to :system :data {:about id :reason reason}})

(defmethod n/describe :conversation/judged [{:keys [data]} _]
  (str "The conversation judge on " (:about data) ": " (str/trim (:summary data))))

(defmethod n/describe :conversation/judge-failed [{:keys [data]} _]
  (str "The conversation judge's answer for " (:about data) " was not used: " (:reason data)))
