(ns manor.mechanics.inner
  "Inner state: how a person is inside, which only they know (their
  spirits, how their body is, what's on their mind). Short keyed phrases,
  like aspects, changed only by judges, never by the person. Opt-in with a
  scenario :inner entry:

    :inner {:keys {:mood \"their spirits and emotions now\"   ; fixed keys, each with a gloss
                   :body \"how their body is, as only they know it\"}
            :open 2}                                         ; open keys besides them (default 2)

  World shape, on the agent:
    :inner {:mood \"low, stung by her ladyship's rebuke\"}   ; only entries with a value
    :inner-at {:mood 14}                                    ; the round each entry was set

  Fixed keys only mean that judges are always shown every one (empty ones as
  \"\") and that they don't count against the open keys. Nothing is filled
  in at the start and nothing fades: an entry stays until a judge changes it.

  Only the person (`prompt/agent-view`'s :you :inner), the GM, the operator
  and the judges see it. Its event, :person/inner-changed, is GM-only."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [manor.actions :as a]
            [manor.clock :as clock]
            [manor.mechanics.items :as items]
            [manor.narrate :as n]
            [manor.prompts :as prompts]
            [manor.world :as w]))

(def max-value items/max-aspect)

(def advised-value items/advised-aspect)

(def default-open 2)

(def Inner
  "A person's inner state: keyed phrases, keys as an aspect's."
  [:map-of
   [:fn {:error/message "should be a key of lower-case letters and hyphens, at most 24 characters"} items/aspect-key?]
   [:string {:min 1 :max max-value}]])

(defn fixed-keys
  "The scenario's fixed keys, in the order it gives them."
  [config]
  (vec (keys (:keys config))))

(defn open-cap [config] (:open config default-open))

(defn- open-keys
  "The keys of `inner` that aren't fixed."
  [config inner]
  (remove (set (fixed-keys config)) (keys inner)))

(defn- crowded
  "Why `inner` has too many open keys, or nil."
  [config inner]
  (let [open (open-keys config inner)]
    (when (> (count open) (open-cap config))
      (str (count open) " keys besides " (str/join ", " (map name (fixed-keys config)))
           " (" (str/join ", " (map name open)) "); at most " (open-cap config)))))

(defn shape-problem
  "Why `inner` isn't well formed (see `Inner`), readably, or nil. World
  files are checked this way, without a scenario."
  [inner]
  (when-let [e (m/explain Inner inner)]
    (pr-str {:inner (me/humanize e)})))

(defn problem
  "Why a starting `inner` won't do for a scenario's `config`, or nil: it's
  well formed, and has no more open keys than the scenario allows."
  [config inner]
  (if (nil? config)
    "this scenario has no inner state"
    (or (shape-problem inner)
        (some->> (crowded config inner) (str "its inner state has ")))))

;; ---------------------------------------------------------------------------
;; What judges see and answer

(defn judge-view
  "A person's inner state as a judge sees it: every fixed key, empty ones
  as \"\", then their open keys, each {:value v :set_at time}."
  [config world id]
  (let [{:keys [inner inner-at]} (w/agent world id)
        when-set (fn [round] (if-let [c (:clock world)]
                               (clock/short-label c round (:round world))
                               (str "round " round)))]
    (into (array-map)
          (for [k (distinct (concat (fixed-keys config) (sort (keys inner))))]
            [k (cond-> {:value (get inner k "")}
                 (get inner k) (assoc :set_at (when-set (get inner-at k))))]))))

(def answer-entry
  "The judge tool's `inner` field."
  [:inner {:optional true}
   [:vector [:map [:person :string] [:key :string]
             [:value [:string {:max max-value :json-schema/maxLength advised-value}]]]]])

(defn key-instructions
  "The lines naming the keys every person has, with their glosses, and how
  many others a judge may add, for a scenario's `config`."
  [world config]
  (prompts/render world "judges/inner-keys.md"
                  {:open (open-cap config)
                   :keys (str/join "\n" (for [[k gloss] (:keys config)] (str "- " (name k) ": " gloss)))}))

(defn instructions
  "The judge's lines about inner state, for a scenario's `config`."
  [world config]
  (prompts/render world "judges/inner.md"
                  {:advised-value advised-value :keys (key-instructions world config)}))

(defn- decoded
  "The judge's inner changes as [{:person :key :to}]: keys normalised like
  argument names, a blank value as a nil :to (clearing it). When the same
  key of a person comes more than once, the last counts."
  [answers]
  (let [cs (for [{:keys [person key value]} answers]
             {:person person :key (a/kebab-key key) :to (when-not (str/blank? value) value)})
        last-of (into {} (map (juxt (juxt :person :key) identity)) cs)]
    (map last-of (distinct (map (juxt :person :key) cs)))))

(defn- after
  "`inner` once `changes` for that person are made."
  [inner changes]
  (reduce (fn [m {:keys [key to]}] (if to (assoc m key to) (dissoc m key))) (or inner {}) changes))

(defn checked-changes
  "Check a judge's `inner` answers: each names one of the `judged` people
  (ids), with a well-formed key, and nobody ends up with more open keys than
  the scenario allows. {:changes [{:person :key :from :to}]}, leaving out
  those that change nothing, or {:error reason}."
  [config world judged answers]
  (let [changes (decoded answers)
        strays (distinct (remove (set judged) (map :person changes)))
        bad-keys (distinct (remove items/aspect-key? (map :key changes)))
        by-person (group-by :person changes)
        too-many (first (for [[id cs] by-person
                              :let [why (crowded config (after (:inner (w/agent world id)) cs))]
                              :when why]
                          [id why]))]
    (cond
      (seq strays) {:error (str "you may change the inner state only of these people: " (str/join ", " judged)
                                "; not " (str/join ", " strays))}
      (seq bad-keys) {:error (str "an inner key is lower-case letters and hyphens, at most 24 characters, starting with a letter; not "
                                  (str/join ", " (map #(pr-str (name %)) bad-keys)))}
      too-many (let [[id why] too-many]
                 {:error (str (:name (w/agent world id)) " (" id ") would have " why
                              ": change one they have instead, or clear one with the value \"\"")})
      :else {:changes (vec (for [{:keys [person key to]} changes
                                 :let [from (get-in (w/agent world person) [:inner key])]
                                 :when (not= from to)]
                             {:person person :key key :from from :to to}))})))

;; ---------------------------------------------------------------------------
;; The event

(defn changed-events
  "One GM-only :person/inner-changed per person, in the order they first
  come in `changes` ({:person :key :from :to}). `source` says what changed
  it: {:job id}, {:conversation id} or {:steward day}."
  [source changes]
  (for [id (distinct (map :person changes))]
    {:type :person/inner-changed :visible-to :gm
     :data {:person id :source source
            :changes (mapv #(dissoc % :person) (filter #(= id (:person %)) changes))}}))

(defn- set-entry
  "The agent with inner key `k` set to `to` in `round`, or cleared when `to`
  is nil; an agent with no entries left has no :inner or :inner-at."
  [a k to round]
  (let [inner (if to (assoc (:inner a) k to) (dissoc (:inner a) k))
        at (if to (assoc (:inner-at a) k round) (dissoc (:inner-at a) k))]
    (cond-> (dissoc a :inner :inner-at)
      (seq inner) (assoc :inner inner :inner-at at))))

(defmethod w/apply-event :person/inner-changed [world {:keys [round data]}]
  (reduce (fn [world {:keys [key to]}] (update-in world [:agents (:person data)] set-entry key to round))
          world (:changes data)))

(defn- source-text [source]
  (str/join ", " (for [[k v] source] (str (name k) " " v))))

(defmethod n/describe :person/inner-changed [{:keys [data]} names]
  (str (n/who names (:person data)) ", inside (" (source-text (:source data)) "): "
       (str/join "; " (for [{:keys [key to]} (:changes data)]
                        (str (name key) " " (if to (pr-str to) "cleared"))))
       "."))
