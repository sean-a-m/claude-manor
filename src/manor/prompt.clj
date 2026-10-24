(ns manor.prompt
  "Builds every model request from scratch each turn. An agent's prompt is
  made only from: its role brief, its persona and its own traits, how it is
  addressed, its own situation, its own notes, the scenario's `reference` and
  `observe` projections for that agent, and the events visible to it. The
  full world never goes into an agent prompt.

  Requests are laid out for the provider's prompt cache, which reuses the
  longest start a request shares with an earlier one: what rarely changes
  comes first (the system message: one preamble for every kind of call, the
  role, who they are, their situation and the reference), then the tools,
  then the optional daily calendar and recent events (a window that only
  grows between jumps), and last what changes every call: the time, the
  view, the notes and what is asked
  of them now."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [manor.clock :as clock]
            [manor.mechanics.calendar :as calendar]
            [manor.mechanics.conversation :as conversation]
            [manor.mechanics.items :as items]
            [manor.mechanics.items.views :as item-views]
            [manor.mechanics.people :as people]
            [manor.mechanics.physical :as physical]
            [manor.mechanics.positions :as positions]
            [manor.mechanics.space :as space]
            [manor.narrate :as narrate]
            [manor.world :as w]))

(def ^:private pretty-mapper (json/object-mapper {:pretty true}))

(defn ->json
  "Compact JSON, for what models read: indentation costs tokens."
  [x]
  (json/write-value-as-string x))

(defn ->pretty-json
  "Indented JSON, for people (the control panel)."
  [x]
  (json/write-value-as-string x pretty-mapper))

(def ^:private round-markers #{:round/started :round/ended})

(defn- window
  "The latest of `xs` to show: at least `n` (all of them if there are fewer)
  and fewer than 2n. The first one shown moves on only every `n` new ones, so
  between those jumps the window just grows at its end, and a prompt that
  holds it starts the same way as the last one (the provider's prompt cache
  can reuse it)."
  [n xs]
  (let [extra (- (count xs) n)]
    (cond (not (pos? n)) ()
          (pos? extra) (drop (* n (quot extra n)) xs)
          :else xs)))

(defn visible-events
  "The latest events `viewer` can see, oldest first: at least the last `n`
  and fewer than 2n (see `window`). Usage bookkeeping is never shown to
  agents or the GM. With `hide-rounds?`, round start and end markers are left
  out too (with a clock, every line carries its time)."
  ([events viewer n] (visible-events events viewer n false))
  ([events viewer n hide-rounds?]
   (->> events
        (filter #(and (w/visible-to? % viewer)
                      (not= :llm/usage (:type %))
                      (not (and hide-rounds? (round-markers (:type %))))))
        (window n))))

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

(defn shared-recent-lines
  "At most ten narrated lines `actor` saw in `events`, excluding events only
  they saw. Use the same visibility and narration as their prompt."
  [world events actor]
  (let [shared (->> (visible-events events actor (count events) true)
                    (remove (fn [{audience :visible-to}]
                              (and (set? audience) (empty? (disj audience actor :gm)))))
                    (take-last 10))]
    (if (seq shared)
      (vec (take-last 10 (str/split-lines (event-lines world shared (narrate/names world)))))
      [])))

(defn scene-recent
  "At most ten narrated lines the worker saw since this job began, excluding
  worker-only events (see `shared-recent-lines`)."
  [world events {:keys [id actor]}]
  (let [since (rest (drop-while #(not (and (= :item/work-begun (:type %))
                                        (= id (get-in % [:data :id])))) events))]
    (shared-recent-lines world (vec since) actor)))

(defn recent-lines
  "At most ten narrated lines `actor` saw last, as their prompt shows them:
  their own view, for a judge reading what they mean (the invitation judge)."
  [world events actor]
  (let [shown (take-last 10 (visible-events events actor 10 true))]
    (if (seq shown)
      (vec (take-last 10 (str/split-lines (event-lines world shown (narrate/names world)))))
      [])))

(defn- heading
  "\"Round 3\", or with a clock \"Saturday 15 October 1904, 09:30\"."
  [world]
  (if-let [c (:clock world)]
    (clock/label c (:round world))
    (str "Round " (:round world))))

(def agent-preamble
  "The system message of every agent request, for turns and conversations
  alike, so the two share it in the provider's cache. What differs between
  them goes at the end of the request (`turn-closing`, `conversation-closing`)."
  "You are a an agent taking part in a the simulation of an Edwardian manor house with other agent. You act only by calling special tools that interface with this world.

You will be informed of your situation, including:
- Recent events you witnessed, oldest first: the newest are last.
- Now: the time, your current private view of the world, and your notes.
- What is asked of you now (your turn, or your line in a conversation), and how to answer.

Your perspective is private to you. Other AI agents playing other characters only learn what is public or what someone tells them. You are here to embody your character according to their own characteristics and motives.")

(def turn-closing
  "## Your turn
How to act:
- Each round you take one turn-ending action (a tool marked \"Ends your turn\").
- Free tools and query tools do not end your turn; use them first if they help, then take your action.
- If an action is rejected you will be told why and may try again once.
- If there is nothing useful to do, call `pass`.

Remember: There are no goals besides what you create for yourself. You are an agent embodying your given character, and you should act as they do. Don't worry about saving turns, or being efficient, or even being correct. The most important thing is to THINK and ACT and COMMUNICATE IN CHARACTER.

Take your turn now.")

(defn agent-view
  "Everything an agent is shown about the world: the scenario's `observe`
  projection, plus identity, their own aspects, inner state (see
  manor.mechanics.inner), physical statuses and impediments (see
  manor.mechanics.physical), (when the world has locations) where they are,
  and (when it has items) what they carry and the things around them.
  Someone who can't see doesn't see the people and things around them
  (physical/view-by-sight)."
  [scenario world agent-id]
  (let [a (w/agent world agent-id)
        observe (:observe scenario (fn [_ _] {}))]
    (physical/view-by-sight
     world agent-id
     (cond-> (-> (merge (if-let [c (:clock world)]
                          {:time (clock/label c (:round world))}
                          {:round (:round world)})
                        (observe world agent-id))
                 (update :you #(merge {:id agent-id :name (:name a) :role (name (:role a))} %)))
       (seq (:aspects a)) (assoc-in [:you :aspects] (:aspects a))
       (seq (:inner a)) (assoc-in [:you :inner] (:inner a))
       (seq (:physical a)) (assoc-in [:you :physical] (physical/shown a))
       (seq (:impediments a)) (assoc-in [:you :impediments] (:impediments a))
       (get-in a [:busy :interrupted-by])
       (assoc-in [:you :busy] (cond-> {:activity (get-in a [:busy :activity])
                                       :interrupted-by (:name (w/agent world (get-in a [:busy :interrupted-by])))}
                                (get-in a [:busy :until])
                                (assoc :until (narrate/at (narrate/names world) (get-in a [:busy :until])))))
       (space/enabled? world) (assoc :location (space/view world agent-id))
       (items/enabled? world) (item-views/add-to-view world agent-id)))))

(defn- brief-text
  "A role's :brief: a string, or a function of the world (and, for agents,
  their id) that builds one, so it can fill in facts from the world."
  [brief & args]
  (if (fn? brief) (apply brief args) brief))

(defn- who-you-are
  "Who `agent-id` is, for their own prompt: their name and position (and
  how they're addressed, when the run shows it), then their persona, how
  strangers see them and their nature, each a paragraph of its own."
  [world agent-id]
  (let [a (w/agent world agent-id)
        title (:title (positions/position world agent-id))
        address (positions/addressed-as world agent-id)]
    (str/join "\n\n" (remove str/blank?
                             [(str "You are " (:name a) " (id: " agent-id ")"
                                   (cond (= :none (:role a)) ", with no post at present"
                                         title (str ", the " (str/lower-case title)))
                                   "."
                                   (when address (str " People address you as " address ".")))
                              (:persona a)
                              (when-not (str/blank? (:manner a)) (str "How strangers see you: " (:manner a)))
                              (when-not (str/blank? (:appearance a)) (str "What people see: " (:appearance a)))
                              (people/nature a)]))))

(defn- reference-text
  "The scenario's :reference for `agent-id`, facts that rarely change (a
  map, the places, a catalogue), as a system-prompt section, or nil."
  [scenario world agent-id]
  (when-let [reference (some-> (:reference scenario) (apply [world agent-id]) not-empty)]
    (str "\n\n## Reference\nFacts that rarely change:\n```json\n" (->json reference) "\n```")))

(defn- agent-request
  "An agent's request. The system message: the preamble, their role, who
  they are, their situation and the reference. The user message: the recent
  events, then the time, their view and notes, and `closing`. Their role's
  :brief comes from the scenario's :roles, or else its :agent-brief."
  [scenario world events agent-id {:keys [event-window]} closing]
  (let [a (w/agent world agent-id)
        brief (or (get-in scenario [:roles (:role a) :brief]) (:agent-brief scenario))
        view (agent-view scenario world agent-id)
        notes (:notes a)]
    [{:role "system"
      :content (str agent-preamble
                    "\n\n## Your role\n" (brief-text brief world agent-id)
                    "\n\n## Who you are\n" (who-you-are world agent-id)
                    (when-not (str/blank? (:situation a)) (str "\n\n## Your situation\n" (:situation a)))
                    (reference-text scenario world agent-id))}
     {:role "user"
      :content (str (calendar/character-section world agent-id) "## Recent events you witnessed\n"
                    (event-lines world
                                 (visible-events events agent-id (or event-window 30) (some? (:clock world)))
                                 (narrate/names world))
                    "\n\n# Now: " (heading world)
                    "\n\n## Your view\n```json\n" (->json view) "\n```"
                    "\n\n## Your notes\n"
                    (if (seq notes)
                      (str/join "\n" (map #(str "- " (stamp world (:round %)) " " (:text %)) (take-last 15 notes)))
                      "(none)")
                    "\n\n" closing)}]))

(defn agent-messages
  [scenario world events agent-id opts]
  (agent-request scenario world events agent-id opts turn-closing))

(defn- how-to-answer-speechless
  "The rules of a speaking turn for someone who can't speak
  (manor.mechanics.physical), for the conversation's closing."
  [initiator?]
  (str "How to answer:\n- You can't speak, so you have no line to say. Call exactly ONE of "
       (if initiator?
         "`end-conversation` (end it now)."
         "`stay-silent` (stay in the conversation) or `leave` (drop out of the conversation; you stay where you are and still hear it).")
       "\n- `note` is free, if you want to remember something."))

(defn- how-to-answer
  "The rules of a speaking turn, for the conversation's closing."
  [initiator? extra-actions items?]
  (str "How to answer:\n"
       (when (seq extra-actions)
         (str "- You may first use " (str/join ", " (map #(str "`" (name (:name %)) "`") extra-actions))
              " before your line. Use their results in your reply. "
              "Then finish this speaking turn with exactly ONE of the speaking tools below.\n"))
       "- Call exactly ONE of "
       (if initiator?
         "`reply` (say your line) or `end-conversation` (end it now)."
         "`reply` (say your line), `stay-silent` (say nothing this time) or `leave` (drop out of the conversation; you stay where you are and still hear it).")
       "
- Your line goes in `reply`'s text. A line written as plain text, outside a tool call, isn't heard.
- `note` is free, if you want to remember something.
- A line can agree to something but doesn't do it: nothing is handed over, made, mended or moved by talking. What was agreed happens only when someone acts on their turn"
       (if items?
         (str "; what you hold is under `carrying` in your view, and being told you've been given or lent something "
              "doesn't put it there. A thing changes hands only when someone `give`s it on their own turn, "
              "finding it with `find` first if they don't carry it.")
         ".")
       "
- Keep each line to a few sentences, at most " conversation/line-limit " characters, spoken as your character would to the people in the conversation.
- Say something new, or don't speak: when the matter is settled, "
       (if initiator? "`end-conversation`" "`stay-silent` or `leave`")
       " rather than repeat it or say goodbye again."))

(defn- and-list [xs]
  (if (next xs) (str (str/join ", " (butlast xs)) " and " (last xs)) (first xs)))

(def conversation-lines
  "The fixed sentences of a speaking turn that differ by who is speaking:
  how the one who started it (:initiator) and everyone else (:member) are
  told about the conversation, and the initiator's closing line at their
  last word. Tests check which is used, not their wording."
  {:initiator "You are in a conversation. You started it during this time period: when it's over, you carry on."
   :member "You are in a conversation. This conversation was started by someone else: you are free to talk, and talking doesn't stop anything you are busy with."
   :last-word "This is your last word: call `reply` with a closing line, or `end-conversation` to end it without one."})

(defn- conversation-closing
  "Who is in the conversation, where it has got to, and whose line it is."
  [world agent-id {:keys [initiator members]} {:keys [pass final? in extra-actions]}]
  (let [names (narrate/names world)
        nm #(narrate/who names %)
        you? (= agent-id initiator)
        others (remove #{agent-id} in)
        gone (remove (set in) members)
        busy (w/busy world agent-id)
        speechless? (not (physical/can? world agent-id :speech))]
    (str "## The conversation\n"
         (conversation-lines (if you? :initiator :member))
         "\n\n"
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
         "\n\n" (if speechless? (how-to-answer-speechless you?) (how-to-answer you? extra-actions (items/enabled? world)))
         "\n\n"
         (cond speechless? (str "Pass " pass " of " conversation/passes ". Your turn: you can't speak; call "
                                (if you? "`end-conversation`" "`stay-silent` or `leave`") ".")
               final? (:last-word conversation-lines)
               you? (str "Pass " pass " of " conversation/passes ". Your turn: call `reply` with your line, or `end-conversation` to end it now.")
               :else (str "Pass " pass " of " conversation/passes ". Your turn: call `reply` with your line, or `stay-silent` or `leave`.")))))

(defn conversation-messages
  "The request for `agent-id`'s speaking turn in conversation `conv`
  ({:id :initiator :members}). `turn` is {:pass :final? :in :extra-actions},
  `:in` the members still in it, `:extra-actions` the scenario tools offered."
  [scenario world events agent-id opts conv turn]
  (agent-request scenario world events agent-id opts
                 (conversation-closing world agent-id conv turn)))

(def gm-preamble
  "You are the game master of a turn-based simulation. The engine enforces the rules and runs the world; you step in only when called, and you should intervene lightly. You see everything, including private information. Never reveal one participant's private information to another.

You act only by calling tools. You may take a few actions; call `done` when finished. If nothing is needed, call `done` immediately.

Each message to you gives the recent events, oldest first, then the time now and why you were called, the requests addressed to you, and the world state.")

(defn- gm-state
  "The world's :state for the default GM view, without what the GM brief
  already carries: a :setting, and the GM's own entry in :roles (and
  :roles, if nothing else is left in it). Of :positions, only those someone
  holds are shown."
  [world]
  (let [state (:state world)
        roles (dissoc (:roles state) :gm)
        held (set (map :role (w/active-agents world)))
        positions (select-keys (:positions state) held)]
    (cond-> (dissoc state :setting :roles :positions)
      (seq roles) (assoc :roles roles)
      (seq positions) (assoc :positions positions))))

(defn default-gm-view
  "What the GM is shown of the world unless a scenario sets :gm-view."
  [world]
  (cond-> {:state (gm-state world)
           :agents (mapv #(select-keys % [:id :name :role :status :died :persona :appearance :aspects :impediments :physical :inner :situation :location :travel :busy])
                         (w/agents-in-order world))}
    (space/enabled? world) (assoc :space (:space world))
    (items/enabled? world) (assoc :items (item-views/gm-view world))
    (seq (items/jobs world)) (assoc :item-jobs (vec (items/jobs world)))))

(defn gm-messages
  [scenario world events reasons {:keys [event-window calendar-due]}]
  (let [gm-view (or (:gm-view scenario) default-gm-view)]
    [{:role "system"
      :content (str gm-preamble
                    (when-let [b (get-in scenario [:roles :gm :brief])] (str "\n\n## This world\n" (brief-text b world))))}
     {:role "user"
      :content (str "## Recent events\n"
                    (event-lines world (visible-events events :gm (or event-window 40)) (narrate/names world))
                    "\n\n# Now: " (heading world)
                    (when (:clock world) (str " (round " (:round world) ")"))
                    " - you were called because:\n"
                    (str/join "\n" (map #(str "- " %) reasons))
                    (when (seq calendar-due)
                      (str "\n\n## Due calendar entries\nReview these due calendar entries and decide what, if anything, to do. Calling done is fine. Review does not mean an occasion occurred.\n```json\n"
                           (->json calendar-due) "\n```"))
                    "\n\n## Requests addressed to you\n"
                    (if-let [inbox (seq (get-in world [:gm :inbox]))]
                      (str/join "\n" (map #(str "- " (stamp world (:round %)) " " (:from %) ": " (:text %)) inbox))
                      "(none)")
                    "\n\n## World state\n```json\n" (->json (cond-> (gm-view world) (:calendar world) (assoc :calendar (calendar/export-entries world)))) "\n```"
                    "\n\nDecide what, if anything, to do.")}]))
