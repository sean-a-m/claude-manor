(ns manor.mechanics.builtins
  "Actions every scenario gets. Scenarios can override any of them by name."
  (:require [clojure.string :as str]
            [manor.mechanics.space :as space]
            [manor.world :as w]))

(defn slug [s]
  (let [s (-> s str/lower-case (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-|-$" ""))]
    (if (str/blank? s) "agent" s)))

(defn unique-id [world base]
  (let [taken (set (keys (:agents world)))]
    (first (remove taken (cons base (map #(str base "-" %) (iterate inc 2)))))))

(def trait-keys
  "A person's hidden traits (see manor.mechanics.people): only their own prompt shows them."
  [:sex :manner :temperament :faculties :circumstances])

(defn spawn-events
  "Events that add an agent of `role` to the world, followed by the role's
  own :on-spawn events (starting holdings and so on), then the scenario's
  :on-spawn events, `(fn [world id spec])`, which see everything the agent
  was spawned with (a post, a bedroom; see manor.mechanics.positions). An agent's
  :situation is kept: only their own prompt shows it. The public
  :appearance is kept too, and :aspects if there are any (manor.mechanics.items)."
  [scenario world {:keys [role name persona model controller location situation appearance aspects] :as spec}]
  (let [id (unique-id world (slug name))
        location (or location (get-in scenario [:roles role :start-location]))
        agent (cond-> (merge {:id id :name name :role role :persona (or persona "")}
                             (select-keys spec trait-keys))
                model (assoc :model model)
                controller (assoc :controller controller)
                location (assoc :location location)
                situation (assoc :situation situation)
                appearance (assoc :appearance appearance)
                (seq aspects) (assoc :aspects aspects))
        spawned {:type :agent/spawned :data {:agent agent} :visible-to :all}
        world' (w/apply-event world spawned)
        on-spawn (get-in scenario [:roles role :on-spawn])
        on-spawn-all (:on-spawn scenario)]
    (-> [spawned]
        (into (when on-spawn (on-spawn world' id)))
        (into (when on-spawn-all (on-spawn-all world' id spec))))))

(defn- active-ids [world] (set (map :id (w/active-agents world))))

;; ---------------------------------------------------------------------------
;; Occupation: an agent who is asleep or busy skips turns (no model calls)
;; until a round, like a traveller. Scenarios occupy agents from action
;; effects; the engine frees them at the start of that round.

(defn occupy
  "Event: `id` is busy with `activity` and next acts in round `until`.
  `activity` reads after \"is\": \"asleep\", \"reading in the library\".
  With `{:asleep? true}` they are asleep, which matters to anyone who would
  talk to them (manor.mechanics.conversation). Everyone present sees it."
  ([world id activity until] (occupy world id activity until nil))
  ([world id activity until {:keys [asleep?]}]
   {:type :agent/occupied :actor id :visible-to (space/here world id)
    :data (cond-> {:activity activity :until until} asleep? (assoc :asleep? true))}))

(defn asleep?
  "Is `id` asleep (occupied with :asleep?) and not roused?"
  [world id]
  (let [busy (w/busy world id)]
    (boolean (and (:asleep? busy) (not (:interrupted-by busy))))))

(defn free
  "Event: `id` stops being busy, optionally because `by` interrupted them."
  ([world id] (free world id nil))
  ([world id by]
   (let [v (space/here world id)]
     {:type :agent/freed :actor id :visible-to (if (and by (set? v)) (conj v by) v)
      :data (cond-> {:activity (:activity (w/busy world id))} by (assoc :by by))})))

(defn interrupt
  "Event: ask a busy agent to decide whether to stop on their next turn."
  [world id by]
  {:type :agent/interrupted :actor id :visible-to (conj (space/here world id) by)
   :data {:activity (:activity (w/busy world id)) :by by}})

(defn resume
  "Event: keep the occupation and its original deadline, ignoring an interruption."
  [world id]
  {:type :agent/resumed :actor id :visible-to (space/here world id)
   :data (select-keys (w/busy world id) [:activity :until :hibernating?])})

(defn releases
  "Events freeing everyone whose occupation ends by the current round."
  [world]
  (for [a (w/active-agents world)
        :let [{:keys [until]} (:busy a)]
        :when (and until (<= until (:round world)))]
    (free world (:id a))))

;; ---------------------------------------------------------------------------
;; Hibernation: an agent nobody needs is left out of play, at no model cost,
;; until someone comes near. Each round the scenario's :hibernate function
;; says where and how such an agent spends their time, or nil to wake them;
;; the engine moves them there and keeps them occupied with no end of their own.

(defn hibernating? [world id] (boolean (:hibernating? (w/busy world id))))

(defn hibernate
  "Event: `id` is out of play, `activity`, until the scenario wakes them.
  Everyone present sees it, like any occupation."
  [world id activity]
  {:type :agent/occupied :actor id :visible-to (space/here world id)
   :data {:activity activity :hibernating? true}})

(defn- hibernation-events
  "Events taking `id` to `location` (if they're elsewhere) and occupying them
  with `activity` there."
  [world id {:keys [activity location]}]
  (let [here (space/location-of world id)
        moved (when (and location (not= location here))
                {:type :agent/moved :actor id :data {:from here :to location}
                 :visible-to (into (space/here world id) (space/present world location))})]
    (conj (if moved [moved] [])
          (hibernate (cond-> world moved (w/apply-event moved)) id activity))))

(defn- hibernation-change
  "Events for one agent: hibernate, wake, or change place or activity."
  [hibernate-fn world {:keys [id busy]}]
  (let [wanted (hibernate-fn world id)]
    (cond
      (nil? busy) (when wanted (hibernation-events world id wanted))
      (nil? wanted) [(free world id)]
      (or (not= (:activity wanted) (:activity busy))
          (and (:location wanted) (not= (:location wanted) (space/location-of world id))))
      (hibernation-events world id wanted))))

(defn hibernations
  "Events putting agents out of play or back in, as `hibernate-fn` (the
  scenario's :hibernate) says. Each agent is decided on the world as the
  previous one left it. Travellers, human seats, and agents interrupted or
  busy with an occupation of their own are left alone. `skip` gives agents
  released this round a turn before returning to their routine."
  ([hibernate-fn world] (hibernations hibernate-fn world #{}))
  ([hibernate-fn world skip]
   (second
    (reduce (fn [[world events] a]
              (let [evs (hibernation-change hibernate-fn world a)]
                [(reduce w/apply-event world evs) (into events evs)]))
            [world []]
            (filter #(and (not (contains? skip (:id %)))
                          (not (:travel %))
                          (not= :human (:controller %))
                          (or (nil? (:busy %))
                              (and (get-in % [:busy :hibernating?]) (not (get-in % [:busy :interrupted-by])))))
                    (w/active-agents world))))))

(defn wakings
  "Events waking uninterrupted hibernators whose routine no longer applies.
  Unlike `hibernations`, this never puts anyone out of play mid-round."
  [hibernate-fn world]
  (for [{:keys [id busy]} (w/active-agents world)
        :when (and (:hibernating? busy) (not (:interrupted-by busy))
                   (nil? (hibernate-fn world id)))]
    (free world id)))

(def speech-limit
  "The length `say` and `whisper` tell models to keep to. Their schemas allow
  up to 1500 characters, as slack, and advise longer texts down to this."
  1000)

(def ^:private speech [:string {:min 1 :max 1500 :advised-max speech-limit}])

(def note-limit
  "The length `note` tells models to keep to. Its schema allows up to 500
  characters, as slack, and advises longer notes down to this."
  400)

(defn agent-actions []
  [{:name :carry-on
    :kind :final :roles :all
    :doc "Ignore the interruption and carry on with what you were doing until it is over."
    :available? (fn [world actor] (some? (:interrupted-by (w/busy world actor))))
    :valid? (fn [world actor _]
              (when-not (:interrupted-by (w/busy world actor)) "you are not interrupted"))
    :effect (fn [world actor _] [(resume world actor)])}

   {:name :pass
    :doc "Wait here and do nothing (more) this round: a fine choice when you want to see what others do, or there is nothing to do yet. It ends your turn, but you take your turn again next round."
    :effect (fn [_ _ _] [{:type :agent/passed :visible-to :all}])}

   {:name :note
    :kind :free
    :doc (str "Write a private note to your future self, at most " note-limit
              " characters. Notes are shown to you every round.")
    :args [:map [:text [:string {:min 1 :max 500 :advised-max note-limit}]]]
    :effect (fn [_ actor {:keys [text]}]
              [{:type :agent/noted :data {:text text} :visible-to #{actor}}])}

   {:name :say
    :kind :free :per-turn 1 :limit-key :speech
    :doc (str "Say something out loud to everyone in the same place: a few sentences, at most "
              speech-limit " characters. Shares its limit with whisper.")
    :args [:map [:text speech]]
    :effect (fn [world actor {:keys [text]}]
              [{:type :agent/said :data {:text text :at (space/location-of world actor)}
                :visible-to (space/here world actor)}])}

   {:name :whisper
    :kind :free :per-turn 1 :limit-key :speech
    :doc (str "Say something privately to one other participant in the same place (use their id): "
              "a few sentences, at most " speech-limit " characters. Shares its limit with say.")
    :args [:map [:to :string] [:text speech]]
    :valid? (fn [world actor {:keys [to]}]
              (cond (= to actor) "you cannot whisper to yourself"
                    (not (contains? (active-ids world) to)) (str "no active participant with id " to)
                    (and (space/location-of world actor) (not (space/together? world actor to)))
                    (str to " is not here")))
    :effect (fn [_ actor {:keys [to text]}]
              [{:type :agent/whispered :data {:to to :text text} :visible-to #{actor to}}])}

   {:name :pray
    :kind :free
    :doc "Pray quietly for something beyond your own reach (help, company, a turn of fortune). A prayer is private, and whether it is answered is not yours to decide."
    :args [:map [:text [:string {:min 1 :max 500}]]]
    ;; The diegetic stand-in for reaching the GM; the event still fills the GM's inbox.
    :effect (fn [_ actor {:keys [text]}]
              [{:type :gm/requested :data {:text text} :visible-to #{actor}}])}])

(def default-max-agents
  "How many active participants the GM's `spawn-agent` may bring the world
  to, unless a scenario sets :limits :max-agents. Retired agents don't
  count. It only stops a GM spawning without bound: :max-cost is the real
  guard."
  50)

(defn gm-actions
  "The GM's tools. `spawn-agent` is left out when no role is :spawnable?
  (its role enum would be empty, which isn't a valid schema)."
  [scenario]
  (let [spawnable (->> (:roles scenario)
                       (filter (comp :spawnable? val))
                       (map (comp name key)))
        max-agents (get-in scenario [:limits :max-agents] default-max-agents)]
    (cond->> [{:name :spawn-agent
      :roles #{:gm}
      :doc "Bring a new participant into the world."
      :args [:map
             [:role (into [:enum] spawnable)]
             [:name [:string {:min 1 :max 40}]]
             [:persona {:optional true} [:string {:max 1200}]]
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
      :effect (fn [_ _ _] [])}]
      (empty? spawnable) (filterv #(not= :spawn-agent (:name %))))))
