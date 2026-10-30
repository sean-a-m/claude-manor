(ns manor.mechanics.relations
  "Relations: how a person has felt toward each other person, and why. For
  each other person, a queue of judged entries, oldest first; the last is
  how they feel now. Only judges add entries (the conversation judge and the
  work judge, among the people they judge), never the person, and nobody
  edits or removes one: the oldest goes when the queue is full. Opt-in with
  a scenario :relations entry, beside :inner (it needs it):

    :relations {:keep 20}     ; entries kept toward each other person (default 20)

  World shape, on the holder:
    :relations {\"mrs-thorne\" [{:feeling \"stung\"
                                :because \"Mrs Thorne scolded you for spilling the sugar\"
                                :round 14 :source {:conversation \"conv-7\"}}]}

  `because` is written to the holder: \"you\" is them, and the other person is
  named. Only the holder (`prompt/agent-view`'s :you :feelings-toward, for
  the people they can see here), the GM, the operator and the judges see it.
  Its event, :person/relations-changed, is GM-only and carries :keep, so the
  reducer needs no configuration and replay is unaffected."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [manor.clock :as clock]
            [manor.narrate :as n]
            [manor.prompts :as prompts]
            [manor.world :as w]))

(def default-keep 20)

(def view-entries
  "How many of the latest entries toward each person the holder's view shows."
  3)

(def judge-entries
  "How many of the latest entries toward each other judged person a judge sees."
  5)

(def max-feeling 40)

(def max-because 160)

(defn keep-cap [config] (:keep config default-keep))

(def Entry
  "A starting entry, as world files and :init give them."
  [:map {:closed true}
   [:feeling [:string {:min 1 :max max-feeling}]]
   [:because [:string {:min 1 :max max-because}]]])

(def Starting
  "A starting :relations: the other person's id to their entries, oldest first."
  [:map-of :string [:vector {:min 1} Entry]])

(defn shape-problem
  "Why a starting `relations` isn't well formed (see `Starting`), readably,
  or nil. World files are checked this way, without a scenario or the cast."
  [relations]
  (when-let [e (m/explain Starting relations)]
    (pr-str {:relations (me/humanize e)})))

(defn problem
  "Why `id`'s starting `relations` won't do in `world` (the cast spawned)
  for a scenario's `config`, or nil: it's well formed, names other people
  of the cast, and has no more entries toward anyone than `:keep`."
  [config world id relations]
  (let [other? #(and (not= id %) (w/agent world %) (not= :gm (:role (w/agent world %))))]
    (or (when (nil? config) "this scenario has no relations")
        (shape-problem relations)
        (when-let [strays (seq (remove other? (keys relations)))]
          (str "its relations name " (str/join ", " (map pr-str strays))
               ", who aren't other people of the cast (use their ids)"))
        (when-let [[toward entries] (first (filter #(> (count (val %)) (keep-cap config)) relations))]
          (str "its relations have " (count entries) " entries toward " toward "; at most " (keep-cap config))))))

;; ---------------------------------------------------------------------------
;; What people and judges see

(defn- when-set [world round]
  (if-let [c (:clock world)]
    (clock/short-label c round (:round world))
    (str "round " round)))

(defn- shown
  "The latest `n` of `entries` as a prompt shows them: when, the feeling and why."
  [world n entries]
  (mapv (fn [{:keys [round feeling because]}]
          (array-map :at (when-set world round) :feeling feeling :because because))
        (take-last n entries)))

(defn view
  "`holder`'s feelings toward the people `others` (ids), for their own view:
  {\"Mrs Thorne (mrs-thorne)\" [{:at :feeling :because}]}, the latest
  `view-entries` toward each, only for those they have entries about, or nil."
  [world holder others]
  (let [relations (:relations (w/agent world holder))]
    (not-empty (into (array-map)
                     (for [id others
                           :let [entries (get relations id)]
                           :when (seq entries)]
                       [(str (:name (w/agent world id)) " (" id ")") (shown world view-entries entries)])))))

(defn judge-view
  "`holder`'s feelings toward the people `others` (ids), as a judge sees
  them: {id [{:at :feeling :because}]}, the latest `judge-entries` toward
  each, or {} when they have none."
  [world holder others]
  (let [relations (:relations (w/agent world holder))]
    (into (array-map)
          (for [id others
                :when (and (not= id holder) (seq (get relations id)))]
            [id (shown world judge-entries (get relations id))]))))

(def answer-entry
  "The judges' `relations` field."
  [:relations {:optional true}
   [:vector [:map [:person :string] [:toward :string]
             [:feeling [:string {:min 1 :max max-feeling}]]
             [:because [:string {:min 1 :max max-because}]]]]])

(defn instructions
  "The judges' lines about relations."
  [world]
  (prompts/render world "judges/relations.md" {:max-feeling max-feeling :max-because max-because}))

(defn checked-entries
  "Check a judge's `relations` answers: each names two different `judged`
  people (ids), the first alive, and no pair comes twice.
  {:relations {:keep k :entries [{:person :toward :feeling :because}]}}
  (no :relations when there are none) or {:error reason}."
  [config world judged answers]
  (let [ids (set judged)
        strays (distinct (remove ids (mapcat (juxt :person :toward) answers)))
        self (first (filter #(= (:person %) (:toward %)) answers))
        dead (first (filter #(w/dead? world (:person %)) answers))
        pairs (map (juxt :person :toward) answers)]
    (cond
      (seq strays) {:error (str "relations may name only these people: " (str/join ", " judged)
                                "; not " (str/join ", " strays))}
      self {:error (str "a relations entry is toward someone else; not " (:person self) " toward themselves")}
      dead {:error (str (:person dead) " is dead and feels nothing now")}
      (not= (count pairs) (count (distinct pairs)))
      {:error "give at most one relations entry for each person toward each other person"}
      (empty? answers) {}
      :else {:relations {:keep (keep-cap config)
                         :entries (mapv #(select-keys % [:person :toward :feeling :because]) answers)}})))

;; ---------------------------------------------------------------------------
;; The event

(defn changed-events
  "One GM-only :person/relations-changed per holder, in the order they first
  come in `relations` ({:keep :entries}, see `checked-entries`). `source`
  says what added them: {:job id}, {:act id}, {:conversation id} or
  {:start true}."
  [source {:keys [keep entries]}]
  (for [id (distinct (map :person entries))]
    {:type :person/relations-changed :visible-to :gm
     :data {:person id :source source :keep keep
            :entries (mapv #(dissoc % :person) (filter #(= id (:person %)) entries))}}))

(defn starting-events
  "The events that give the cast their starting relations, once they're all
  spawned: `holders` are [[id relations] ...], each a starting :relations."
  [config holders]
  (changed-events {:start true}
                  {:keep (keep-cap config)
                   :entries (vec (for [[id relations] holders
                                       [toward entries] relations
                                       entry entries]
                                   (assoc entry :person id :toward toward)))}))

(defn- add-entry [keep round source]
  (fn [entries {:keys [feeling because]}]
    (vec (take-last keep (conj (or entries []) {:feeling feeling :because because :round round :source source})))))

(defmethod w/apply-event :person/relations-changed [world {:keys [round data]}]
  (let [{:keys [person source keep entries]} data]
    (reduce (fn [world {:keys [toward] :as entry}]
              (update-in world [:agents person :relations toward] (add-entry keep round source) entry))
            world entries)))

(defn exported
  "`relations` (an agent's) as a world file gives them: without rounds or sources."
  [relations]
  (update-vals relations #(mapv (fn [e] (select-keys e [:feeling :because])) %)))

(defn- source-text [source]
  (str/join ", " (for [[k v] source] (if (true? v) (name k) (str (name k) " " v)))))

(defmethod n/describe :person/relations-changed [{:keys [data]} names]
  (str (n/who names (:person data)) ", toward others (" (source-text (:source data)) "): "
       (str/join "; " (for [{:keys [toward feeling because]} (:entries data)]
                        (str (n/who names toward) ": " feeling ", because " because)))
       "."))

(defn tool-text
  "The judge tools' words on the `relations` field, with a space after, or
  \"\" without relations (`config` nil)."
  [world config]
  (if config (str (prompts/render world "judges/tools/relations.md" {}) " ") ""))

(defn latest
  "`relations` (an agent's) with only the latest entry toward each person,
  as {id {:feeling :because}}: the GM's view."
  [relations]
  (update-vals relations #(select-keys (peek %) [:feeling :because])))
