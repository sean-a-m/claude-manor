(ns manor.mechanics.conversation
  "Conversations within a turn. `converse` draws up to three people in the
  same place into an exchange: the initiator opens, then everyone speaks in
  turn, the initiator first, for up to two passes, and the initiator has
  the last word. The initiator may end it at any of their turns; the others
  may stay silent or leave. It uses nobody's turn, and it doesn't touch
  anyone's occupation: someone busy talks while they work.

  The engine runs the exchange (manor.engine.talk/run-conversation!), one short
  model call per speaking turn. This namespace has the rules: who can be
  drawn in, the tools of a speaking turn, the events and their narration.
  Lines are ordinary `:agent/said` events, heard by everyone in the place.
  The conversation's state (who is still in it, the pass) lives only in the
  engine's loop; the events are the record."
  (:require [clojure.string :as str]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.physical :as physical]
            [manor.mechanics.space :as space]
            [manor.narrate :as narrate]
            [manor.world :as w]))

(def max-others "People the initiator can draw in." 3)

(def passes "Full passes of speaking turns, before the initiator's last word." 2)

(def line-limit
  "The length models are told to keep a line to. The schema allows up to
  1500 characters, as `say` does, and advises longer lines down to this."
  400)

(def ^:private line [:string {:min 1 :max 1500 :advised-max line-limit}])

;; ---------------------------------------------------------------------------
;; Who can be drawn in

(defn- who [world id] (or (:name (w/agent world id)) id))

(defn- here? [world a b]
  (if (space/enabled? world) (space/together? world a b) true))

(defn reason-not-drawn-in
  "Why `id` can't be drawn into a conversation by `actor`, or nil."
  [world actor id]
  (let [a (w/agent world id)
        busy (w/busy world id)]
    (cond (= id actor) "you can't converse with yourself"
          (not (and a (w/active? world id) (not= :gm (:role a)))) (str "no one has the id " id)
          (not (here? world actor id)) (str (who world id) " is not here")
          (b/asleep? world id) (str (who world id) " is asleep")
          (physical/unconscious? world id) (str (who world id) " is unconscious")
          (and (:hibernating? busy) (not (:interrupted-by busy)))
          (str (who world id) " is " (:activity busy) " and not free to talk")
          (= :human (:controller a)) (str (who world id) " can't be drawn into a conversation"))))

(defn- others-here
  "Everyone else here, in spawn order."
  [world actor]
  (keep #(when (and (not= actor (:id %)) (here? world actor (:id %))) (:id %))
        (w/active-agents world)))

(defn- invited
  "The ids `with` names, or everyone else here when it's empty."
  [world actor with]
  (if (seq with) with (others-here world actor)))

(defn check
  "Why `actor` can't start a conversation with `with` (ids, or empty for
  everyone here), or nil."
  [world actor {:keys [with]}]
  (let [ids (invited world actor with)
        problems (keep #(reason-not-drawn-in world actor %) ids)]
    (cond
      (and (space/enabled? world) (nil? (space/location-of world actor))) "you can't converse while travelling"
      (= (:round world) (:conversed-round (w/agent world actor))) "you have already started a conversation this round"
      (not= (count ids) (count (distinct ids))) "name each person in with only once"
      (seq with) (if (> (count ids) max-others)
                   (str "at most " max-others " people can join you; name up to " max-others " in with")
                   (first problems))
      (empty? ids) "there is no one here to talk to"
      (= (count problems) (count ids)) (str "no one here can talk: " (str/join "; " problems))
      (> (- (count ids) (count problems)) max-others)
      (str (- (count ids) (count problems)) " people here could join you, but at most " max-others
           " can; name up to " max-others " in with"))))

(defn members
  "Everyone in the conversation `actor` starts, in speaking order: `actor`
  first, then the people `with` names, in its order, or everyone here who
  can talk, in spawn order."
  [world actor with]
  (into [actor] (remove #(reason-not-drawn-in world actor %)) (invited world actor with)))

;; ---------------------------------------------------------------------------
;; Events. Each carries the conversation's id as :conversation.

(defn new-id [world] (str "conv-" (inc (:seq world))))

(defn started
  "Event: `actor` starts conversation `conv` ({:id :members})."
  [world actor {:keys [id members]}]
  {:type :conversation/started :actor actor :visible-to (space/here world actor)
   :data {:conversation id :members members :at (space/location-of world actor)}})

(defn said
  "Event: `actor` says `text` in the conversation: an ordinary `:agent/said`."
  [world actor {:keys [id]} text]
  {:type :agent/said :actor actor :visible-to (space/here world actor)
   :data {:text text :at (space/location-of world actor) :conversation id}})

(defn silent [world actor {:keys [id]}]
  {:type :conversation/silent :actor actor :visible-to (space/here world actor) :data {:conversation id}})

(defn left [world actor {:keys [id]}]
  {:type :conversation/left :actor actor :visible-to (space/here world actor) :data {:conversation id}})

(def hand-over-types
  "Events that put a thing into someone's hands."
  #{:item/given :item/taken :item/found})

(defn handed
  "How many things came into someone's hands among `events` (a
  conversation's), or nil in a world without items."
  [world events]
  (when (contains? world :items)
    (count (filter #(hand-over-types (:type %)) events))))

(defn ended
  "Event: the conversation is over, for `reason` (a keyword, see `endings`).
  `handed` (see `handed`) is kept when it's a number, so the ending can say
  that nothing changed hands."
  [world {:keys [id initiator]} reason handed]
  {:type :conversation/ended :actor initiator :visible-to (space/here world initiator)
   :data (cond-> {:conversation id :reason reason} handed (assoc :handed handed))})

(def nothing-handed
  "How everyone present is told that talk handed nothing over."
  "Nothing changed hands in it: anything offered, lent or promised is still where it was.")

(def endings
  "How a conversation can end, as the initiator is told."
  {:ended "you ended it"
   :finished "everyone had their say"
   :everyone-left "everyone else left it"
   :silence "nobody else said anything"
   :cost-limit "it was cut short"
   :error "it was cut short"})

(defn of
  "The events of conversation `id` among `events`."
  [events id]
  (filter #(= id (get-in % [:data :conversation])) events))

(defmethod w/apply-event :conversation/started [world {:keys [actor round data]}]
  (reduce #(assoc-in %1 [:agents %2 :last-conversation-round] round)
          (assoc-in world [:agents actor :conversed-round] round)
          (:members data)))

(defn- and-list [xs]
  (if (next xs) (str (str/join ", " (butlast xs)) " and " (last xs)) (first xs)))

(defmethod narrate/describe :conversation/started [{:keys [actor data]} names]
  (str (narrate/who names actor) " draws " (and-list (map #(narrate/who names %) (rest (:members data))))
       " into conversation."))

(defmethod narrate/describe :conversation/silent [{:keys [actor]} names]
  (str (narrate/who names actor) " says nothing."))

(defmethod narrate/describe :conversation/left [{:keys [actor]} names]
  (str (narrate/who names actor) " leaves the conversation."))

(defmethod narrate/describe :conversation/ended [{:keys [actor data]} names]
  (str "The conversation " (narrate/who names actor) " started is over."
       (when (= 0 (:handed data)) (str " " nothing-handed))))

;; ---------------------------------------------------------------------------
;; Actions

(def converse
  {:name :converse
   :kind :converse :per-turn 1
   :doc (str "Start a conversation with up to " max-others " people here: their ids in with, or leave it out "
             "for everyone here (if that's " max-others " or fewer). You open with text; then each of you speaks "
             "in turn, you first, up to " passes " times each, and you have the last word. You can end it at "
             "any of your turns; the others can stay silent or leave. Everyone here hears it. Keep each line "
             "to a few sentences, at most " line-limit " characters. People asleep or away can't join; someone "
             "busy can talk without stopping. When it's over, carry on with your turn.")
   :args [:map
          [:with {:optional true} [:vector {:max 10 :decode/json #(if (string? %) [%] %)} :string]]
          [:text line]]
   :valid? check})

(def ^:private speaking-hint
  "Each speaking-turn tool ends the line, which models otherwise read, from
  the usual hint, as ending their own turn."
  "Ends your part in this round of the conversation, not your own turn.")

(defn- speaking-turn-action [conv spec effect]
  (assoc spec :kind :final :hint speaking-hint
         :effect (fn [world actor args] [(effect world actor conv args)])))

(defn- unquote-line
  "`text` without quotation marks around the whole of it, which models
  sometimes add to a line; left as it is if there are quotes inside too
  (\"Yes,\" she said, \"now.\")."
  [text]
  (let [t (str/trim text)]
    (if-let [[_ inner] (re-matches #"(?s)[\"“]+(.*?)[\"”]+" t)]
      (if (or (str/blank? inner) (re-find #"[\"“”]" inner)) t inner)
      t)))

(defn speaking-actions
  "The tools of `speaker`'s speaking turn in `conv` ({:id :initiator}),
  besides `note`. Each ends the speaking turn. `end-conversation` emits
  nothing: the engine ends the conversation."
  [{:keys [initiator] :as conv} speaker]
  (let [reply (speaking-turn-action conv {:name :reply
                                          :doc (str "Say your line to the conversation: a few sentences, at most "
                                                    line-limit " characters.")
                                          :args [:map [:text line]]}
                                    (fn [world actor conv {:keys [text]}] (said world actor conv (unquote-line text))))]
    (if (= speaker initiator)
      [reply
       {:name :end-conversation :kind :final :hint speaking-hint
        :doc "End the conversation now, without another word."}]
      [reply
       (speaking-turn-action conv {:name :stay-silent :doc "Say nothing this time; you stay in the conversation."}
                             (fn [world actor conv _] (silent world actor conv)))
       (speaking-turn-action conv {:name :leave :doc "Leave the conversation. You stay where you are, and still hear it."}
                             (fn [world actor conv _] (left world actor conv)))])))

(defn outcome
  "How a speaking turn went, from the name of its accepted action (nil if
  it took none): :spoke, :silent, :left or :ended."
  [action-name]
  (case action-name
    "reply" :spoke
    "leave" :left
    "end-conversation" :ended
    :silent))

(defn mock-default
  "Mock answers for a speaking turn, unless a scenario has
  :mock-conversation: the initiator ends it, the others stay silent."
  [{:keys [agent-id conversation]}]
  {:tool (if (= agent-id (:initiator conversation)) :end-conversation :stay-silent)})
