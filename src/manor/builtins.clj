(ns manor.builtins
  "Actions every scenario gets. Scenarios can override any of them by name."
  (:require [clojure.string :as str]
            [manor.space :as space]
            [manor.world :as w]))

(defn slug [s]
  (let [s (-> s str/lower-case (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-|-$" ""))]
    (if (str/blank? s) "agent" s)))

(defn unique-id [world base]
  (let [taken (set (keys (:agents world)))]
    (first (remove taken (cons base (map #(str base "-" %) (iterate inc 2)))))))

(defn spawn-events
  "Events that add an agent of `role` to the world, followed by the role's
  own :on-spawn events (starting holdings and so on)."
  [scenario world {:keys [role name persona model controller location]}]
  (let [id (unique-id world (slug name))
        location (or location (get-in scenario [:roles role :start-location]))
        agent (cond-> {:id id :name name :role role :persona (or persona "")}
                model (assoc :model model)
                controller (assoc :controller controller)
                location (assoc :location location))
        spawned {:type :agent/spawned :data {:agent agent} :visible-to :all}
        world' (w/apply-event world spawned)
        on-spawn (get-in scenario [:roles role :on-spawn])]
    (into [spawned] (when on-spawn (on-spawn world' id)))))

(defn- active-ids [world] (set (map :id (w/active-agents world))))

(defn agent-actions []
  [{:name :pass
    :doc "Do nothing this round."
    :effect (fn [_ _ _] [{:type :agent/passed :visible-to :all}])}

   {:name :note
    :kind :free
    :doc "Write a private note to your future self. Notes are shown to you every round."
    :args [:map [:text [:string {:min 1 :max 500}]]]
    :effect (fn [_ actor {:keys [text]}]
              [{:type :agent/noted :data {:text text} :visible-to #{actor}}])}

   {:name :say
    :doc "Say something out loud. Everyone in the same place hears it."
    :args [:map [:text [:string {:min 1 :max 800}]]]
    :effect (fn [world actor {:keys [text]}]
              [{:type :agent/said :data {:text text :at (space/location-of world actor)}
                :visible-to (space/here world actor)}])}

   {:name :whisper
    :doc "Say something privately to one other participant in the same place (use their id)."
    :args [:map [:to :string] [:text [:string {:min 1 :max 800}]]]
    :valid? (fn [world actor {:keys [to]}]
              (cond (= to actor) "you cannot whisper to yourself"
                    (not (contains? (active-ids world) to)) (str "no active participant with id " to)
                    (and (space/location-of world actor) (not (space/together? world actor to)))
                    (str to " is not here")))
    :effect (fn [_ actor {:keys [to text]}]
              [{:type :agent/whispered :data {:to to :text text} :visible-to #{actor to}}])}

   {:name :request-gm
    :kind :free
    :doc "Send a private request to the game master, who runs the world (for example, asking for a newcomer to be invited). The game master may or may not act on it."
    :args [:map [:text [:string {:min 1 :max 500}]]]
    :effect (fn [_ actor {:keys [text]}]
              [{:type :gm/requested :data {:text text} :visible-to #{actor}}])}])

(defn gm-actions [scenario]
  (let [spawnable (->> (:roles scenario)
                       (filter (comp :spawnable? val))
                       (map (comp name key)))
        max-agents (get-in scenario [:limits :max-agents] 10)]
    [{:name :spawn-agent
      :roles #{:gm}
      :doc "Bring a new participant into the world."
      :args [:map
             [:role (into [:enum] spawnable)]
             [:name [:string {:min 1 :max 40}]]
             [:persona {:optional true} [:string {:max 600}]]
             [:location {:optional true} :string]]
      :valid? (fn [world _ {:keys [location]}]
                (cond (>= (count (w/active-agents world)) max-agents)
                      (str "the world is at its limit of " max-agents " participants")
                      (and location (nil? (space/location world location)))
                      (str "there is no location " location)))
      :effect (fn [world _ {:keys [role name persona location]}]
                (spawn-events scenario world {:role (keyword role) :name name :persona persona
                                              :location location}))}

     {:name :retire-agent
      :roles #{:gm}
      :doc "Remove a participant from the world."
      :args [:map [:id :string] [:reason [:string {:max 300}]]]
      :valid? (fn [world _ {:keys [id]}]
                (when-not (contains? (active-ids world) id) (str "no active participant with id " id)))
      :effect (fn [_ _ {:keys [id reason]}]
                [{:type :agent/retired :data {:id id :reason reason} :visible-to :all}])}

     {:name :announce
      :roles #{:gm}
      :doc "Make an announcement. Leave `to` empty for everyone, or give a list of participant ids."
      :args [:map [:text [:string {:min 1 :max 800}]] [:to {:optional true} [:vector :string]]]
      :effect (fn [_ _ {:keys [text to]}]
                [{:type :gm/announced :data {:text text}
                  :visible-to (if (seq to) (set to) :all)}])}

     {:name :ask-human
      :roles #{:gm}
      :doc "Pause the simulation after this round and ask the human operator a question."
      :args [:map [:question [:string {:min 1 :max 800}]]]
      :effect (fn [_ _ {:keys [question]}]
                [{:type :run/pause-requested :data {:reason (str "GM asks: " question)} :visible-to :gm}])}

     {:name :done
      :roles #{:gm}
      :doc "Finish this intervention."
      :effect (fn [_ _ _] [])}]))
