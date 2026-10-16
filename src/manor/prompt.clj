(ns manor.prompt
  "Builds every model request from scratch each turn. An agent's prompt is
  made only from: its role brief and persona, its own notes, the scenario's
  `observe` projection for that agent, and the events visible to it. The
  full world never goes into an agent prompt."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [manor.clock :as clock]
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
  projection, plus identity and (when the world has locations) where they are."
  [scenario world agent-id]
  (let [a (w/agent world agent-id)
        observe (:observe scenario (fn [_ _] {}))]
    (cond-> (-> (merge (if-let [c (:clock world)]
                         {:time (clock/label c (:round world))}
                         {:round (:round world)})
                       (observe world agent-id))
                (update :you #(merge {:id agent-id :name (:name a) :role (name (:role a))} %)))
      (space/enabled? world) (assoc :location (space/view world agent-id)))))

(defn- brief-text
  "A role's :brief: a string, or a function of the world (and, for agents,
  their id) that builds one, so it can fill in facts from the world."
  [brief & args]
  (if (fn? brief) (apply brief args) brief))

(defn agent-messages
  [scenario world events agent-id {:keys [event-window]}]
  (let [a (w/agent world agent-id)
        role (get-in scenario [:roles (:role a)])
        view (agent-view scenario world agent-id)
        notes (:notes a)]
    [{:role "system"
      :content (str agent-preamble
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
                    "\n\nTake your turn now.")}]))

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
    (space/enabled? world) (assoc :space (:space world))))

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
