(ns manor.mechanics.find-person
  "Finding someone within walking distance: `find-person {who}`, a query.
  The seeker describes who they want in their own words; a narrow judge,
  built like the find judge, picks who is meant from the people about (everyone
  in the seeker's walking area) and rates the odds of finding them, and the
  engine rolls. Found, the reply says where they are, so the seeker can walk
  there; it moves no one. The judge sees only what anyone could see of the
  people about (name, position, appearance, where they are and what they're
  doing), and the seeker's own context. Not found and no one meant get the
  same reply, so a failed roll gives nothing away."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [malli.json-schema :as mjs]
            [manor.actions :as a]
            [manor.mechanics.adjudication :as adjudication]
            [manor.mechanics.physical :as physical]
            [manor.mechanics.positions :as positions]
            [manor.mechanics.space :as space]
            [manor.narrate :as narrate]
            [manor.prompts :as prompts]
            [manor.rng :as rng]
            [manor.world :as w]))

(def max-who 80)

(def answer-schema
  [:map
   [:person {:optional true} :string]
   [:likelihood {:optional true} [:enum "certain" "likely" "even" "unlikely" "impossible"]]
   [:nobody {:optional true} :boolean]])

(def odds {"certain" 100 "likely" 75 "even" 50 "unlikely" 25 "impossible" 0})

(def instructions (prompts/render "judges/find-person.md"))

;; ---------------------------------------------------------------------------
;; Who is about

(defn- title [world id] (:title (positions/position world id)))

(defn about
  "Everyone in `actor`'s walking area but them, the dead included, in id order."
  [world actor]
  (let [area (space/area world (space/location-of world actor))]
    (sort (disj (into #{} (mapcat #(concat (space/present world %) (physical/dead-at world %))) area) actor))))

(defn- entry
  "What the judge is shown of `id`: only what anyone about could see or be told."
  [world id]
  (let [a (w/agent world id)
        place (:location a)
        busy (:busy a)]
    (into {} (remove (comp nil? val))
          {:id id :name (:name a) :position (title world id) :address-as (positions/addressed-as world id)
           :appearance (:appearance a) :place (:name (space/location world place) place)
           :doing (when-not (w/dead? world id) (:activity busy))
           :dead (when (w/dead? world id) true)})))

(defn- seeker
  "What the judge knows of the seeker: their own context, as their prompt has it."
  [world actor recent]
  (let [a (w/agent world actor)]
    (into {} (remove (comp nil? val))
          {:name (:name a) :position (title world actor) :nature (:persona a) :situation (:situation a)
           :place (:name (space/location world (:location a)))
           :sight (when-not (physical/can? world actor :sight) "can't see: searching by voice and touch")
           :notes (mapv #(select-keys % [:round :text]) (take-last 15 (:notes a)))
           :recent (vec recent)})))

;; ---------------------------------------------------------------------------
;; The judge

(defn- check-answer
  "The judge's answer: {:person id :likelihood l}, {:nobody true} or {:error why}."
  [listed raw]
  (let [{:keys [args error]} (a/decode-args {:args answer-schema} raw)]
    (cond error {:error error}
          (and (:person args) (:nobody args)) {:error "give either person with likelihood, or nobody"}
          (:nobody args) {:nobody true}
          (not (:person args)) {:error "give either person with likelihood, or nobody"}
          (not (some #{(:person args)} listed)) {:error "person must be the id of someone listed in people"}
          (not (:likelihood args)) {:error "person needs a likelihood"}
          :else (select-keys args [:person :likelihood]))))

(defn seek-request
  "The judge's request for `actor`'s description `who`, with their `recent` lines."
  [world actor {:keys [who recent]}]
  (let [listed (about world actor)
        people (mapv #(entry world %) listed)
        me (seeker world actor recent)]
    {:messages [{:role "system" :content instructions}
                {:role "user" :content (json/write-value-as-string {:seeker me :who who :people people})}]
     :tools [{:type "function"
              :function {:name "seek"
                         :description "Say who is meant (a listed id) and how likely they are to be found, or that no one listed is meant."
                         :parameters (mjs/transform answer-schema)}}]
     :ctx {:world world :who who :people people}
     :check #(adjudication/answer "seek" % (partial check-answer (vec listed)))}))

(defn- bare [s] (str/replace (str/trim (str/lower-case (str s))) #"^(a|an|the)\s+" ""))

(defn mock-judge
  "A stand-in judge: someone listed whose id, name or surname `who` contains is
  certain to be found; otherwise nobody."
  [{:keys [who people]}]
  (let [text (bare who)
        mentions? #(and (not (str/blank? %)) (str/includes? text (str/lower-case %)))
        surname #(last (str/split (str/trim (str %)) #"\s+"))
        match (first (filter #(or (mentions? (:id %)) (mentions? (:name %)) (mentions? (surname (:name %)))) people))]
    {:tool :seek :args (if match {:person (:id match) :likelihood "certain"} {:nobody true})}))

(def adjudicators {:people/find-person {:request seek-request :mock mock-judge}})

;; ---------------------------------------------------------------------------
;; The action

(defn- seek!
  [_world _actor {:keys [who]} {:keys [adjudicate recent]}]
  (adjudicate :people/find-person {:who who :recent (recent)}))

(defn- sought-events
  "The search, decided: the judge's odds, the seeded roll and the outcome.
  Someone no longer about when it's decided isn't found."
  [world actor {:keys [who]} {:keys [person likelihood]}]
  (let [seq (inc (:seq world))
        roll (rng/roll world [:find-person seq] 100)
        found? (and person (some #{person} (about world actor)) (< roll (odds likelihood)))]
    [{:type :people/sought :actor actor :visible-to #{actor}
      :data (cond-> {:who who :outcome (if found? :found :not-found)}
              person (assoc :person person :likelihood likelihood :roll roll :roll-key [:find-person seq])
              found? (assoc :at (space/location-of world person)))}]))

(defmethod narrate/describe :people/sought [{:keys [actor data]} names]
  (if (= :found (:outcome data))
    (str (narrate/who names actor) " looks for " (narrate/who names (:person data))
         " and finds them in the " (str/lower-case (narrate/place names (:at data))) ".")
    (str (narrate/who names actor) " looks about for " (:who data) " but doesn't find them.")))

(defn- sought-reply
  [world actor {:keys [who]} {:keys [events]}]
  (let [{:keys [outcome person at]} (:data (last events))
        here (space/location-of world actor)]
    (cond
      (not= :found outcome) (str "You look about for " who " but don't find them.")
      (= at here) (str (:name (w/agent world person)) " (" person ") is here with you.")
      :else (let [doing (when-not (w/dead? world person) (:activity (w/busy world person)))]
              (str (:name (w/agent world person)) " (" person ") is in the "
                   (:name (space/location world at)) " (" at ")"
                   (cond (w/dead? world person) ", dead"
                         doing (str ", " doing))
                   ". To go there, walk to " at ".")))))

(def action
  {:name :find-person
   :kind :query :per-turn 1
   :doc (prompts/render "docs/find-person.md" {:max-who max-who})
   :args [:map [:who [:string {:min 1 :max max-who}]]]
   :available? (fn [world actor] (some? (space/location-of world actor)))
   :valid? (fn [world actor _]
             (when-not (space/location-of world actor) "you can't look for anyone while travelling"))
   :query-fn seek!
   :on-result sought-events
   :reply sought-reply})
