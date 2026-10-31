(ns manor.mechanics.conversation.judge
  "The conversation judge: after a conversation ends, one narrow call
  judges how it left the inner state (manor.mechanics.inner) of the people
  in it. Only in scenarios with :inner. It sees the place and time, each
  member (those who left part-way too) with their persona, situation,
  temperament, appearance, aspects and inner state, and the conversation's
  lines in order, each marked with the members who heard it when not all
  of them did. It changes only the members' inner state and, with the
  scenario's :relations (manor.mechanics.relations), adds to how they feel
  toward each other; its summary is
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
            [manor.mechanics.relations :as relations]
            [manor.mechanics.space :as space]
            [manor.narrate :as n]
            [manor.prompts :as prompts]
            [manor.world :as w]))

(def max-summary 300)

(defn answer-schema
  "The judge's answer: inner state, with relations (`relations-config`)
  how members feel toward each other, and the summary."
  [relations-config]
  (cond-> [:map inner/answer-entry]
    relations-config (conj relations/answer-entry)
    true (conj [:summary [:string {:min 1 :max max-summary}]])))

(defn- react-tool [world relations-config]
  {:type "function"
   :function {:name "react"
              :description (prompts/render world "judges/tools/react.md"
                                           {:advised-value inner/advised-value :max-summary max-summary
                                            :relations (relations/tool-text world relations-config)})
              :parameters (mjs/transform (answer-schema relations-config))}})

(defn instructions
  "The conversation judge's system message, for the scenario's inner state
  `config`, its relations (`relations-config`) if it has them, and what
  order books are (`books?`) if it's shown them."
  ([world config] (instructions world config nil))
  ([world config relations-config] (instructions world config relations-config false))
  ([world config relations-config books?]
   (str (prompts/render world "judges/conversation.md"
                        {:advised-value inner/advised-value :max-summary max-summary
                         :keys (inner/key-instructions world config)})
        (when relations-config (str "\n\n" (relations/instructions world)))
        (when books? (str "\n\n" (prompts/render world "judges/conversation-orders.md" {}))))))

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
  "A member as the judge sees them: everything about them, inner state too,
  and with relations (`relations-config`) how they feel toward the other
  members."
  [config relations-config world members id]
  (cond-> (assoc (select-keys (w/agent world id) [:id :name :persona :situation :temperament :appearance :aspects])
                 :inner (inner/judge-view config world id))
    relations-config (assoc :feelings_toward (relations/judge-view world id members))))

(def ^:private pretty (json/object-mapper {:pretty true}))

(defn judge-request
  "The conversation judge's request about `conv` ({:initiator :members}),
  whose `lines` (see `lines`) are given in order. With the scenario's
  :relations (`relations-config`), it may add to how members feel toward
  each other. With its :order-books (`books`, `(fn [world member-ids] {...})`,
  see purchases/judge-books), the scene shows the members' order books, so
  talk of an order can be told from one placed."
  ([config world conv lines] (judge-request config world conv lines nil))
  ([config world conv lines relations-config] (judge-request config world conv lines relations-config nil))
  ([config world {:keys [initiator members]} lines relations-config books]
   (let [place (space/location-of world initiator)
         scene (merge {:members (mapv #(member-json config relations-config world members %) members)
                       :lines (mapv #(line-json members %) lines)}
                      (when books (books world members)))]
     {:tools [(react-tool world relations-config)]
      :messages
      [{:role "system" :content (instructions world config relations-config (some? books))}
       {:role "user"
        :content (str "# The conversation\n\n"
                      "- Time: " (if-let [c (:clock world)] (clock/world-label world (:round world)) (str "round " (:round world))) "\n"
                      (when place (str "- Place: " (:name (space/location world place) place) "\n"))
                      "\n```json\n"
                      (json/write-value-as-string scene pretty)
                      "\n```\n\nCall `react` now.")}]})))

;; ---------------------------------------------------------------------------
;; The answer

(defn- verdict [config relations-config world members arguments]
  (let [{:keys [args error]} (a/decode-args {:args (answer-schema relations-config)} arguments)
        checked (when-not error (inner/checked-changes config world members (:inner args)))
        related (when (and relations-config (not error))
                  (relations/checked-entries relations-config world members (:relations args)))]
    (cond error {:error error}
          (:error checked) checked
          (:error related) related
          :else (merge {:summary (:summary args) :inner (:changes checked)} related))))

(defn judgement
  "Read a conversation judge response: {:verdict {:summary :inner} :replies
  [...]} or {:error reason :replies [...]} (see adjudication/answer). Only
  the `members` (ids) can be changed; :inner holds the checked changes
  ({:person :key :from :to}), and with relations (`relations-config`)
  :relations the entries added ({:keep :entries}, see
  relations/checked-entries), when there are any."
  ([response world config members] (judgement response world config members nil))
  ([response world config members relations-config]
   (adjudication/answer "react" response #(verdict config relations-config world members %))))

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
  :person/inner-changed per changed member, then one GM-only
  :person/relations-changed per member who feels something new. No member
  is told."
  [id {:keys [summary inner] :as verdict}]
  (-> [{:type :conversation/judged :visible-to :gm :data {:about id :summary summary}}]
      (into (inner/changed-events {:conversation id} inner))
      (into (when (:relations verdict) (relations/changed-events {:conversation id} (:relations verdict))))))

(defn judge-failed
  "Event, for the operator only: why the judge's answer about conversation
  `id` wasn't used."
  [id reason]
  {:type :conversation/judge-failed :visible-to :system :data {:about id :reason reason}})

(defmethod n/describe :conversation/judged [{:keys [data]} _]
  (str "The conversation judge on " (:about data) ": " (str/trim (:summary data))))

(defmethod n/describe :conversation/judge-failed [{:keys [data]} _]
  (str "The conversation judge's answer for " (:about data) " was not used: " (:reason data)))
