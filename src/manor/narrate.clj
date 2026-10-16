(ns manor.narrate
  "Narrative is a view of state: each event type can describe itself as a
  line of text. Agents see these lines for the events visible to them.
  Scenarios add `(defmethod manor.narrate/describe :my/event ...)`."
  (:require [clojure.string :as str]
            [manor.clock :as clock]))

(defmulti describe
  "One line of text for `event`. `names` maps agent id -> display name."
  (fn [event _names] (:type event)))

(defn who
  "Display name of an agent id."
  [names id]
  (get names id id))

(defn kw-str [k] (if (keyword? k) (subs (str k) 1) (str k)))

(defn place
  "Display name of a location id (names maps [:loc id] -> name)."
  [names loc-id]
  (get names [:loc loc-id] loc-id))

(defn at
  "When `round` starts: a time with a clock (\"Sun 07:00\"), else \"round N\"."
  [names round]
  (if-let [c (get names [:clock])]
    (clock/short-label c round (get names [:today]))
    (str "round " round)))

(defmethod describe :default [{:keys [type actor data]} names]
  (str (when actor (str (who names actor) ": ")) (name type)
       (when (seq data) (str " " (pr-str data)))))

(defmethod describe :round/started [{:keys [data]} _] (str "--- Round " (:round data) " begins ---"))
(defmethod describe :round/ended [{:keys [data]} _] (str "--- Round " (:round data) " ends ---"))

(defmethod describe :world/initialized [{:keys [data]} _]
  (str "The world is created (scenario " (name (:scenario data)) ", seed " (:seed data) ")."))

(defmethod describe :agent/spawned [{:keys [data]} _]
  (let [a (:agent data)]
    (str (:name a) " (" (name (:role a)) ") joins.")))

(defmethod describe :agent/retired [{:keys [data]} names]
  (str (who names (:id data)) " leaves"
       (when-let [r (:reason data)] (str ": " r)) "."))

(defmethod describe :agent/updated [{:keys [data]} names]
  (str (who names (:id data)) " updated: " (pr-str (:changes data))))

(defmethod describe :agent/said [{:keys [actor data]} names]
  (str (who names actor) (when-let [at (:at data)] (str " (" (place names at) ")"))
       " says: \"" (:text data) "\""))

(defmethod describe :agent/moved [{:keys [actor data]} names]
  (str (who names actor) " goes from " (place names (:from data)) " to " (place names (:to data)) "."))

(defmethod describe :agent/departed [{:keys [actor data]} names]
  (str (who names actor) " leaves " (place names (:from data)) " for " (place names (:to data))
       " (arriving " (at names (:arrives data)) ")."))

(defmethod describe :agent/occupied [{:keys [actor data]} names]
  (str (who names actor) " is " (:activity data)
       (when (:until data) (str " until " (at names (:until data)))) "."))

(defmethod describe :agent/interrupted [{:keys [actor data]} names]
  (str (who names (:by data)) " interrupts " (who names actor) " (" (:activity data) ")."))

(defmethod describe :agent/resumed [{:keys [actor data]} names]
  (str (who names actor) " carries on (" (:activity data)
       (when (:until data) (str ", until " (at names (:until data)))) ")."))

(defmethod describe :agent/freed [{:keys [actor data]} names]
  (str (who names actor) " is no longer " (:activity data)
       (when-let [by (:by data)] (str " (interrupted by " (who names by) ")")) "."))

(defmethod describe :agent/arrived [{:keys [actor data]} names]
  (str (who names actor) " arrives at " (place names (:to data)) " from " (place names (:from data)) "."))

(defmethod describe :agent/placed [{:keys [data]} names]
  (str (who names (:id data)) " is placed at " (place names (:to data)) "."))

(defmethod describe :space/location-added [{:keys [data]} _]
  (str "New location: " (get-in data [:location :name]) " (" (get-in data [:location :id]) ")."))

(defmethod describe :space/route-opened [{:keys [data]} names]
  (str "Route open: " (place names (:from data)) (if (:one-way? data) " -> " " <-> ") (place names (:to data))
       (if (zero? (:travel data))
         " (next door)."
         (str " (" (:travel data) " round" (when (not= 1 (:travel data)) "s") ")."))))

(defmethod describe :space/route-closed [{:keys [data]} names]
  (str "Route closed: " (place names (:from data)) (if (:one-way? data) " -> " " <-> ") (place names (:to data)) "."))

(defmethod describe :agent/whispered [{:keys [actor data]} names]
  (str (who names actor) " whispers to " (who names (:to data)) ": \"" (:text data) "\""))

(defmethod describe :agent/noted [{:keys [actor data]} names]
  (str (who names actor) " notes privately: " (:text data)))

(defmethod describe :agent/passed [{:keys [actor]} names]
  (str (who names actor) " waits."))

(defmethod describe :gm/requested [{:keys [actor data]} names]
  (str (who names actor) " prays: \"" (:text data) "\""))

(defmethod describe :gm/announced [{:keys [data]} _]
  (str "Announcement: " (:text data)))

(defmethod describe :gm/called [{:keys [data]} _]
  (str "The game master is called (" (str/join "; " (:reasons data)) ")."))

(defmethod describe :gm/intervened [{:keys [data]} _]
  (str "The game master finishes intervening."))

(defmethod describe :action/rejected [{:keys [actor data]} names]
  (str (who names actor) "'s action " (:action data) " was rejected: " (:reason data)))

(defmethod describe :action/forfeited [{:keys [actor data]} names]
  (str (who names actor) " forfeits the turn: " (:reason data)))

(defmethod describe :bridge/called [{:keys [actor data]} names]
  (str (if actor (who names actor) "The world") " consulted " (kw-str (:bridge data))
       (if (:error data) (str " (error: " (:error data) ")") ".")))

(defmethod describe :bridge/requested [{:keys [actor data]} names]
  (str (who names actor) " requested " (kw-str (:bridge data)) " (awaiting operator approval, " (:req-id data) ")."))

(defmethod describe :bridge/approved [{:keys [data]} _] (str "Operator approved " (:req-id data) "."))
(defmethod describe :bridge/denied [{:keys [data]} _] (str "Operator denied " (:req-id data) "."))

(defmethod describe :bridge/completed [{:keys [data]} _]
  (str (kw-str (:bridge data)) " " (:req-id data)
       (if (:error data) (str " failed: " (:error data)) " completed.")))

(defmethod describe :run/pause-requested [{:keys [data]} _] (str "Pause requested: " (:reason data)))
(defmethod describe :run/paused [{:keys [data]} _] (str "Run paused: " (str/join "; " (:reasons data))))

(defmethod describe :llm/usage [{:keys [data]} _]
  (format "LLM call for %s: %d+%d tokens, $%.5f" (:agent data)
          (or (:prompt-tokens data) 0) (or (:completion-tokens data) 0) (double (or (:cost data) 0))))

(defn names
  "Display names: agent id -> name, [:loc id] -> location name, and
  [:item id] -> item name. With a clock, also [:clock] and [:today] (the
  current round), for `at`."
  [world]
  (cond-> (merge (into {} (map (juxt key (comp :name val))) (:agents world))
                 (into {} (map (fn [[id l]] [[:loc id] (:name l)])) (get-in world [:space :locations]))
                 ;; resolved at call time: manor.items requires this namespace for its describe methods
                 ((requiring-resolve 'manor.items/names) world))
    (:clock world) (assoc [:clock] (:clock world) [:today] (:round world))))
