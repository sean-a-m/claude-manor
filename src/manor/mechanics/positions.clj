(ns manor.mechanics.positions
  "Positions and posts: what a job is, and who holds which.

  A position is an entry of a shared catalogue (resources/settings/), which
  a world file names in :meta :positions, and adds to or overrides entry by
  entry in its own :roles:

    :housemaid {:title \"Housemaid\"
                :about \"Your day is housework: ...\"   ; duties only: no names,
                                                       ; personality or situation
                :standing :servant                     ; one of the pack's `standings`
                :sleeps :in-house                      ; or :out
                :workplace [\"kitchen\"]                 ; optional location ids
                :capabilities #{:shop-clerk}           ; optional: what code treats
                                                       ; the holder as (actions' :needs)
                :addressed-as \"{first-name}\"          ; optional; or {:male .. :female ..}
                :ages [15 30] :sex :female}            ; for the people server

  A run keeps its positions in [:state :positions]. An agent's :role is the
  id of the position they hold, so their brief and tools follow it; their
  post in [:state :posts] says more ({:position :household :since :terms}).
  The events here keep posts, and [:state :bedrooms] and [:state :routines],
  so that someone who arrives later can have them too. Nothing checks who
  holds what: two cooks, or none, are allowed, and so is sharing a bed."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [manor.narrate :as n]
            [manor.settings :as settings]
            [manor.world :as w]))

(defn standings
  "The standings a setting pack allows: the keys of its people.edn's
  :standings."
  [pack]
  (set (keys (get-in pack [:people :standings]))))

(defn household-standing?
  "Does `standing` belong to the household's own people, by the setting
  pack (:household? true in its people.edn)?"
  [pack standing]
  (boolean (get-in pack [:people :standings standing :household?])))

(def household
  "The household a post belongs to when it's in the house (the owner of the
  household's things and its account)."
  "household")

;; ---------------------------------------------------------------------------
;; The catalogue

(defn read-catalogue
  "The positions of a catalogue: a file path, or a classpath resource such as
  \"settings/england-1904/positions.edn\"."
  [source]
  (let [f (io/file (str source))
        r (when-not (.exists f) (io/resource (str source)))]
    (when-not (or (.exists f) r)
      (throw (ex-info (str "no positions catalogue " source " (looked for a file and a classpath resource)")
                      {:source source})))
    (edn/read-string (slurp (or r f)))))

(defn table
  "A world file's positions: its catalogue (:meta :positions), if it names
  one, with its own :roles merged over it entry by entry. With
  :addressed-as? false in `opts`, no position has a form of address."
  [wf opts]
  (let [catalogue (if-let [source (get-in wf [:meta :positions])]
                    (read-catalogue source)
                    (:positions (settings/for-worldfile wf)))
        merged (merge-with merge catalogue (:roles wf))]
    (cond-> merged
      (false? (:addressed-as? opts)) (update-vals #(dissoc % :addressed-as)))))

(defn- address-problem [a]
  (when-not (or (and (string? a) (not (str/blank? a)))
                (and (map? a) (seq a) (every? #{:male :female} (keys a)) (every? string? (vals a))))
    "a string, or a map of :male and :female to strings"))

(defn entry-problems
  "What's wrong with one position entry, as readable strings. `standings`
  are the setting pack's (`standings`)."
  [standings id {:keys [about addressed-as] :as entry}]
  (let [where (str "position " id ": ")]
    (concat
     (when-not (and (string? about) (not (str/blank? about)))
       [(str where "needs an :about paragraph (in the world file's :roles or the catalogue)")])
     (for [[k ok? what] [[:title #(and (string? %) (not (str/blank? %))) "a name such as \"Housemaid\""]
                         [:standing standings (str "one of " (str/join " " (sort standings)))]
                         [:sleeps #{:in-house :out} ":in-house or :out"]
                         [:workplace #(and (vector? %) (every? string? %)) "a vector of location ids"]
                         [:capabilities #(and (set? %) (every? keyword? %)) "a set of keywords"]
                         [:ages #(and (vector? %) (= 2 (count %)) (every? nat-int? %) (apply <= %)) "[youngest oldest]"]
                         [:sex #{:male :female} ":male or :female"]]
           :when (and (contains? entry k) (not (ok? (get entry k))))]
       (str where k " must be " what))
     (when-let [p (and (contains? entry :addressed-as) (address-problem addressed-as))]
       [(str where ":addressed-as must be " p)]))))

;; ---------------------------------------------------------------------------
;; Queries

(defn position
  "The position `id` holds: their role's entry in [:state :positions]."
  [world id]
  (get-in world [:state :positions (:role (w/agent world id))]))

(defn capabilities
  "What code treats `id` as, from their position: a set, perhaps empty."
  [world id]
  (set (:capabilities (position world id))))

(defn capable? [world id capability]
  (contains? (capabilities world id) capability))

(defn post [world id] (get-in world [:state :posts id]))

(defn household-member?
  "Does `id` hold a post in `household`?"
  ([world id] (household-member? world id household))
  ([world id h] (= h (:household (post world id)))))

(defn fill
  "An address `template` filled from `person-name`, by `people` (a setting
  pack's people.edn), with its :honorifics left out of the name first. By
  :name-order :given-first, {first-name} is the name's first word and
  {surname} its last; by :family-first, {surname} is its first word and
  {first-name} the rest (or that one word, if it's all there is)."
  [{:keys [honorifics name-order]} template person-name]
  (let [words (remove (set honorifics) (str/split (str/trim (str person-name)) #"\s+"))
        [given family] (if (= :family-first name-order)
                         [(if (next words) (str/join " " (rest words)) (first words)) (first words)]
                         [(first words) (last words)])]
    (-> template
        (str/replace "{first-name}" (or given ""))
        (str/replace "{surname}" (or family "")))))

(defn addressed-as
  "How `id` is addressed, from their position's :addressed-as and their name
  and sex, or nil if their position gives no form of address (or the run
  shows none)."
  [world id]
  (let [a (w/agent world id)
        template (:addressed-as (position world id))
        template (if (map? template) (get template (:sex a) (first (vals template))) template)]
    (when template (fill (:people (settings/pack world)) template (:name a)))))

;; ---------------------------------------------------------------------------
;; Events

(defn post-changed
  "Event: `id` now holds `position`, in `household` (nil for none), on
  `terms` (free text, or nil). It changes their :role to match."
  [world id position {:keys [household terms conditions employer visible-to]}]
  {:type :people/post-changed :visible-to (or visible-to :gm)
   :data (cond-> {:id id :position position :title (get-in world [:state :positions position :title])
                  :household household :terms terms}
           conditions (assoc :conditions conditions)
           employer (assoc :employer employer))})

(defn bedroom-assigned
  "Event: `id` sleeps in `bedroom` (a location id), or in none if nil. With
  `by`, someone gave it to them (manor.mechanics.appointments)."
  ([id bedroom] (bedroom-assigned id bedroom nil))
  ([id bedroom by]
   {:type :people/bedroom-assigned :data (cond-> {:id id :bedroom bedroom} by (assoc :by by))}))

(defn routine-set
  "Event: `id`'s routine is `routine` ([{:from \"09:00\" :at loc :doing ..}],
  kept in time order), or none if empty."
  [id routine]
  {:type :people/routine-set :data {:id id :routine (vec (sort-by :from routine))}})

(defmethod w/apply-event :people/post-changed [world {:keys [round data]}]
  (let [{:keys [id position household terms]} data]
    (-> world
        (assoc-in [:state :posts id] (merge {:position position :household household :since round :terms terms}
                                                 (select-keys data [:conditions :employer])))
        (assoc-in [:agents id :role] position))))

(defmethod w/apply-event :people/bedroom-assigned [world {:keys [data]}]
  (let [{:keys [id bedroom]} data]
    (if bedroom
      (assoc-in world [:state :bedrooms id] bedroom)
      (update-in world [:state :bedrooms] dissoc id))))

(defmethod w/apply-event :people/routine-set [world {:keys [data]}]
  (let [{:keys [id routine]} data]
    (if (seq routine)
      (assoc-in world [:state :routines id] routine)
      (update-in world [:state :routines] dissoc id))))

(defn starting-events
  "The post, bedroom and routine of someone just arrived (`spec` is what
  they were spawned with: a world file's agent, or an operator's spec). The
  post is their role's position, in the household if `spec` says
  :household, or else if the position's standing is one of the household's
  (`household-standing?`). The GM holds no post."
  [world id {:keys [bedroom routine] :as spec}]
  (let [role (:role (w/agent world id))
        h (if (contains? spec :household)
            (:household spec)
            (when (household-standing? (settings/pack world) (get-in world [:state :positions role :standing]))
              household))]
    (if (= :gm role)
      []
      (cond-> [(post-changed world id role {:household h})]
        bedroom (conj (bedroom-assigned id bedroom))
        (seq routine) (conj (routine-set id routine))))))

(defmethod n/describe :people/post-changed [{:keys [data]} names]
  (let [{:keys [id position title terms employer]} data]
    (if (and (= :none position) employer)
      (str (n/who names id) " no longer holds a post: dismissed by " (n/who names employer) ".")
      (str (n/who names id) " now holds the post of " (or title (name position))
           (when terms (str ", on these terms: " terms))
           (when employer (str "; appointed by " (n/who names employer))) "."))))

(defmethod n/describe :people/bedroom-assigned [{:keys [data]} names]
  (let [{:keys [id bedroom by]} data]
    (cond (and bedroom by (= by id)) (str (n/who names id) " has taken the " (n/place names bedroom) " to sleep in.")
          (and bedroom by) (str (n/who names by) " has given " (n/who names id) " the " (n/place names bedroom) " to sleep in.")
          bedroom (str (n/who names id) " sleeps in the " (n/place names bedroom) ".")
          :else (str (n/who names id) " has no bedroom."))))

(defmethod n/describe :people/routine-set [{:keys [data]} names]
  (str (n/who names (:id data)) "'s routine: "
       (if (seq (:routine data))
         (str/join "; " (for [{:keys [from at doing]} (:routine data)] (str "from " from ", " doing " (" at ")")))
         "none")
       "."))
