(ns manor.prompt
  "Builds every model request from scratch each turn. An agent's prompt is
  made only from: its role brief and persona, its own notes, the scenario's
  `observe` projection for that agent, and the events visible to it. The
  full world never goes into an agent prompt."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [manor.clock :as clock]
            [manor.conversation :as conversation]
            [manor.items :as items]
            [manor.narrate :as narrate]
            [manor.space :as space]
            [manor.world :as w]))

(def ^:private mapper (json/object-mapper {:pretty true}))

(defn ->json [x] (json/write-value-as-string x mapper))

(def ^:private round-markers #{:round/started :round/ended})

(defn visible-events
  "The last `n` events `viewer` can see, oldest first. Usage bookkeeping is
  never shown to agents or the GM. With `hide-rounds?`, round start and end
  markers are left out too (with a clock, every line carries its time)."
  ([events viewer n] (visible-events events viewer n false))
  ([events viewer n hide-rounds?]
   (->> events
        (filter #(and (w/visible-to? % viewer)
                      (not= :llm/usage (:type %))
                      (not (and hide-rounds? (round-markers (:type %))))))
        (take-last n))))

(defn- stamp
  "Line prefix for something that happened in `round`: [09:30] with a clock, else [r3]."
  [world round]
  (if-let [c (:clock world)]
    (str "[" (clock/short-label c round (:round world)) "]")
    (str "[r" round "]")))

(defn- event-lines [world events names]
  (if (seq events)
    (str/join "\n" (map #(str (stamp world (:round %)) " " (narrate/describe % names)) events))
    "(nothing yet)"))

(defn- heading
  "\"Round 3\", or with a clock \"Saturday 15 October 1904, 09:30\"."
  [world]
  (if-let [c (:clock world)]
    (clock/label c (:round world))
    (str "Round " (:round world))))

(def agent-preamble
  "You are a participant in a turn-based simulation. Each round you get your current private view of the world and the recent events you witnessed.

How to act:
- You act only by calling tools. Each round you take exactly ONE turn-ending action (a tool marked \"Ends your turn\").
- Free tools and query tools do not end your turn; use them first if they help, then take your action.
- If an action is rejected you will be told why and may try again once.
- If there is nothing useful to do, call `pass`.

Your view is private. Others only learn what is public or what someone tells them. Stay in character and pursue your goals.")

(defn agent-view
  "Everything an agent is shown about the world: the scenario's `observe`
  projection, plus identity, (when the world has locations) where they are,
  and (when it has items) what they carry and the things around them."
  [scenario world agent-id]
  (let [a (w/agent world agent-id)
        observe (:observe scenario (fn [_ _] {}))]
    (cond-> (-> (merge (if-let [c (:clock world)]
                         {:time (clock/label c (:round world))}
                         {:round (:round world)})
                       (observe world agent-id))
                (update :you #(merge {:id agent-id :name (:name a) :role (name (:role a))} %)))
      (get-in a [:busy :interrupted-by])
      (assoc-in [:you :busy] (cond-> {:activity (get-in a [:busy :activity])
                                      :interrupted-by (:name (w/agent world (get-in a [:busy :interrupted-by])))}
                               (get-in a [:busy :until])
                               (assoc :until (narrate/at (narrate/names world) (get-in a [:busy :until])))))
      (space/enabled? world) (assoc :location (space/view world agent-id))
      (items/enabled? world) (items/add-to-view world agent-id))))

(defn- brief-text
  "A role's :brief: a string, or a function of the world (and, for agents,
  their id) that builds one, so it can fill in facts from the world."
  [brief & args]
  (if (fn? brief) (apply brief args) brief))

(defn- agent-request
  "An agent's request: `preamble`, their role and who they are, then their
  view, notes and recent events, and `closing`."
  [scenario world events agent-id {:keys [event-window]} preamble closing]
  (let [a (w/agent world agent-id)
        role (get-in scenario [:roles (:role a)])
        view (agent-view scenario world agent-id)
        notes (:notes a)]
    [{:role "system"
      :content (str preamble
                    "\n\n## Your role\n" (brief-text (:brief role) world agent-id)
                    "\n\n## Who you are\nYou are " (:name a) " (id: " agent-id ")."
                    (when-not (str/blank? (:persona a)) (str " " (:persona a))))}
     {:role "user"
      :content (str "# " (heading world)
                    "\n\n## Your view\n```json\n" (->json view) "\n```"
                    "\n\n## Your notes\n"
                    (if (seq notes)
                      (str/join "\n" (map #(str "- " (stamp world (:round %)) " " (:text %)) (take-last 15 notes)))
                      "(none)")
                    "\n\n## Recent events you witnessed\n"
                    (event-lines world
                                 (visible-events events agent-id (or event-window 30) (some? (:clock world)))
                                 (narrate/names world))
                    "\n\n" closing)}]))

(defn agent-messages
  [scenario world events agent-id opts]
  (agent-request scenario world events agent-id opts agent-preamble "Take your turn now."))

(defn- conversation-preamble [initiator?]
  (str "You are a participant in a turn-based simulation, and you are in a conversation. "
       (if initiator?
         "You started it during your turn: when it's over, you carry on with your turn."
         "This is not your own turn: talking here doesn't use it up, and doesn't stop anything you are busy with.")
       "

How to answer:
- Call exactly ONE of "
       (if initiator?
         "`reply` (say your line) or `end-conversation` (end it now)."
         "`reply` (say your line), `stay-silent` (say nothing this time) or `leave` (drop out of the conversation; you stay where you are and still hear it).")
       "
- `note` is free, if you want to remember something.
- Keep each line to a few sentences, at most " conversation/line-limit " characters, spoken as your character to the people in the conversation.

Your view is private. Others only learn what is public or what someone tells them. Stay in character and pursue your goals."))

(defn- and-list [xs]
  (if (next xs) (str (str/join ", " (butlast xs)) " and " (last xs)) (first xs)))

(defn- conversation-closing
  "Who is in the conversation, where it has got to, and whose line it is."
  [world agent-id {:keys [initiator members]} {:keys [pass final? in]}]
  (let [names (narrate/names world)
        nm #(narrate/who names %)
        you? (= agent-id initiator)
        others (remove #{agent-id} in)
        gone (remove (set in) members)
        busy (w/busy world agent-id)]
    (str "## The conversation\n"
         (if you? "You started a conversation here with " (str (nm initiator) " started a conversation here with "))
         (and-list (map #(if (= % agent-id) "you" (nm %)) (rest members))) ". "
         "Everyone speaks in turn, " (if you? "you" (nm initiator)) " first, up to " conversation/passes
         " times, and " (if you? "you have" (str (nm initiator) " has")) " the last word. "
         (when (seq gone) (str (and-list (map nm gone)) (if (next gone) " have" " has") " left it. "))
         (when (seq others) (str "Still in it with you: " (and-list (map nm others)) ". "))
         "The lines so far are the latest of the recent events above."
         (when (and busy (not (:interrupted-by busy)))
           (str "\n\nYou are busy " (:activity busy)
                (when-let [u (:until busy)] (str " until " (narrate/at names u)))
                "; talking doesn't stop it."))
         "\n\n"
         (cond final? "This is your last word: say a closing line with `reply`, or `end-conversation` to end it without one."
               you? (str "Pass " pass " of " conversation/passes ". Your turn to speak: `reply`, or `end-conversation` to end it now.")
               :else (str "Pass " pass " of " conversation/passes ". Your turn to speak.")))))

(defn conversation-messages
  "The request for `agent-id`'s speaking turn in conversation `conv`
  ({:id :initiator :members}). `turn` is {:pass :final? :in}, `:in` the
  members still in it."
  [scenario world events agent-id opts conv turn]
  (agent-request scenario world events agent-id opts
                 (conversation-preamble (= agent-id (:initiator conv)))
                 (conversation-closing world agent-id conv turn)))

(def gm-preamble
  "You are the game master of a turn-based simulation. The engine enforces the rules and runs the world; you step in only when called, and you should intervene lightly. You see everything, including private information. Never reveal one participant's private information to another.

You act only by calling tools. You may take a few actions; call `done` when finished. If nothing is needed, call `done` immediately.")

(defn- gm-state
  "The world's :state for the default GM view, without what the GM brief
  already carries: a :setting, and the GM's own entry in :roles (and
  :roles, if nothing else is left in it)."
  [state]
  (let [roles (dissoc (:roles state) :gm)]
    (cond-> (dissoc state :setting :roles)
      (seq roles) (assoc :roles roles))))

(defn default-gm-view
  "What the GM is shown of the world unless a scenario sets :gm-view."
  [world]
  (cond-> {:state (gm-state (:state world))
           :agents (mapv #(select-keys % [:id :name :role :status :persona :location :travel :busy])
                         (w/agents-in-order world))}
    (space/enabled? world) (assoc :space (:space world))
    (items/enabled? world) (assoc :items (items/gm-view world))
    (seq (items/jobs world)) (assoc :item-jobs (vec (items/jobs world)))))

(defn gm-messages
  [scenario world events reasons {:keys [event-window]}]
  (let [gm-view (or (:gm-view scenario) default-gm-view)]
    [{:role "system"
      :content (str gm-preamble
                    (when-let [b (get-in scenario [:roles :gm :brief])] (str "\n\n## This world\n" (brief-text b world))))}
     {:role "user"
      :content (str "# " (heading world)
                    (when (:clock world) (str " (round " (:round world) ")"))
                    " - you were called because:\n"
                    (str/join "\n" (map #(str "- " %) reasons))
                    "\n\n## Requests addressed to you\n"
                    (if-let [inbox (seq (get-in world [:gm :inbox]))]
                      (str/join "\n" (map #(str "- " (stamp world (:round %)) " " (:from %) ": " (:text %)) inbox))
                      "(none)")
                    "\n\n## World state\n```json\n" (->json (gm-view world)) "\n```"
                    "\n\n## Recent events\n"
                    (event-lines world (visible-events events :gm (or event-window 40)) (narrate/names world))
                    "\n\nDecide what, if anything, to do.")}]))
