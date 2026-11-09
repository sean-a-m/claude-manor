(ns manor.mechanics.contacts.judge
  "The recommendation judge (docs/sending-for-design.md 4.2, from the trade
  judge of docs/trades-design.md): whom a recommender means by `recommend`'s
  `wanted`, in their own words: someone for a place in service or for a job.
  One checked narrow call, built like the invitation judge, before anyone is
  made. It answers someone listed (`existing`: someone on the map, or a
  contact made before), someone new from elsewhere (`new`), or, only when no
  such person could exist in the setting, `impossible`. It reads which kind
  `wanted` is: for a place it never picks someone on the map. It sees the
  recommender's own context and the people they could mean: those on the map
  by position and workplace, contacts who hold no post by name, line and
  home only, never who knows them. Code checks only the answer's shape."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [malli.json-schema :as mjs]
            [manor.actions :as a]
            [manor.mechanics.adjudication :as adjudication]
            [manor.mechanics.positions :as positions]
            [manor.mechanics.space :as space]
            [manor.mechanics.visits.judge :as visits-judge]
            [manor.prompts :as prompts]
            [manor.world :as w]))

(def max-wanted 120)
(def max-line 80)

(def new-fields
  "Someone new from elsewhere, as the judge writes them. The people server
  rolls their name."
  [[:line [:string {:min 1 :max max-line}]]
   [:sex [:enum "male" "female"]]
   [:age [:int {:min 14 :max 90}]]
   [:based [:string {:min 1 :max 80}]]
   [:known-as [:string {:min 1 :max 120}]]
   [:situation [:string {:min 1 :max 600}]]])

(def answer-schema
  [:map
   [:existing {:optional true} :string]
   [:new {:optional true} (into [:map] new-fields)]
   [:impossible {:optional true} [:string {:min 1 :max 300}]]])

(defn instructions [world]
  (prompts/render world "judges/recommendation.md" {}))

;; ---------------------------------------------------------------------------
;; Whom the recommender could mean

(def ^:private not-work
  "Roles that are no work on the map: no post, the GM, guests, and callers
  sent for (who are listed as contacts)."
  #{:none :gm :guest :caller})

(defn- title [world role]
  (get-in world [:state :positions role :title] (name role)))

(defn workplaces
  "The names of the places `role`'s position works at, or nil."
  [world role]
  (not-empty (mapv #(:name (space/location world %) %) (get-in world [:state :positions role :workplace]))))

(defn local-people
  "The people on the map who work at something, apart from the household
  and `actor`: id, name, position and where they work."
  [world actor]
  (vec (for [a (w/active-agents world)
             :when (and (not= actor (:id a)) (not (not-work (:role a)))
                        (not (positions/household-member? world (:id a))))]
         (cond-> {:id (:id a) :name (:name a) :position (title world (:role a))}
           (workplaces world (:role a)) (assoc :works-at (workplaces world (:role a)))))))

(defn- placed?
  "Does `id` hold a post now, other than :none or :caller?"
  [world id]
  (not (contains? #{nil :none :caller} (:position (positions/post world id)))))

(defn contacts-listed
  "The contacts made so far from elsewhere whose person can still come and
  holds no post, by name, line, home and public description only."
  [world]
  (vec (for [c (sort-by :created (vals (get-in world [:state :contacts])))
             :when (and (not (:local? c)) (not (#{:retired :dead} (:status (w/agent world (:agent-id c)))))
                        (not (placed? world (:agent-id c))))]
         {:id (:id c) :name (:name c) :line (:line c) :based (:based c) :known-as (:known-as c)})))

;; ---------------------------------------------------------------------------
;; The checked answer

(defn- parsed [raw]
  (let [m (cond (map? raw) raw
                (str/blank? raw) {}
                :else (json/read-value raw json/keyword-keys-object-mapper))]
    (cond-> m (map? (:new m)) (update :new update-keys a/kebab-key))))

(defn- check-answer
  "The judge's answer, checked against the `listed` ids: {:existing id},
  {:new fields} or {:impossible reason}, or {:error why}."
  [listed raw]
  (let [{:keys [args error]} (try (a/decode-args {:args answer-schema} (parsed raw))
                                  (catch Exception e {:error (str "could not parse arguments: " (ex-message e))}))
        given (filter #(contains? args %) [:existing :new :impossible])]
    (cond error {:error error}
          (not= 1 (count given)) {:error "give exactly one of existing, new or impossible"}
          (:existing args) (if (contains? listed (:existing args))
                             {:existing (:existing args)}
                             {:error "existing must be the id of someone listed in local or contacts"})
          (:new args) (if (some #(str/blank? (get-in args [:new %])) [:line :based :known-as :situation])
                        {:error "new needs a line, based, known-as and situation that aren't blank"}
                        {:new (update-vals (:new args) #(if (string? %) (str/trim %) %))})
          (str/blank? (:impossible args)) {:error "impossible needs a reason"}
          :else {:impossible (str/trim (:impossible args))})))

(defn request
  "The judge's request for `actor`'s `wanted`, with their `recent` lines:
  their own context, the people on the map and the contacts it may match."
  [world actor {:keys [wanted recent]}]
  (let [who (visits-judge/inviter world actor recent)
        local (local-people world actor)
        listed (contacts-listed world)]
    {:messages [{:role "system" :content (instructions world)}
                {:role "user" :content (json/write-value-as-string
                                        {:recommender who :wanted wanted :local local :contacts listed})}]
     :tools [{:type "function"
              :function {:name "recommendation"
                         :description (prompts/render world "judges/tools/recommendation.md" {})
                         :parameters (mjs/transform answer-schema)}}]
     :ctx {:world world :recommender who :wanted wanted :local local :contacts listed}
     :check #(adjudication/answer "recommendation" %
                                  (partial check-answer (set (map :id (concat local listed)))))}))

;; ---------------------------------------------------------------------------
;; The mock, for --mock runs and tests

(defn- mock-position
  "The catalogue position with the longest title `text` names, as [id
  entry], or nil: a mock's rule, not a reading of prose."
  [world text]
  (last (sort-by (fn [[id p]] [(count (:title p)) (name id)])
                 (for [[id p] (get-in world [:state :positions])
                       :when (and (not (not-work id)) (:title p)
                                  (str/includes? text (str/lower-case (:title p))))]
                   [id p]))))

(defn mock-judge
  "A stand-in judge, not a reader of prose: a listed contact whose line, or a
  local person whose position, `wanted` names is them. Otherwise, for a
  catalogue position `wanted` names, a listed contact seeking a place as it,
  or someone new from Ely of its sex and age; for anything else, someone new
  from Ely whose line is `wanted` itself."
  [{:keys [world wanted local contacts]}]
  (let [text (str/lower-case wanted)
        names? #(and (not (str/blank? %)) (str/includes? text (str/lower-case %)))
        [_ position] (mock-position world text)
        line (if position
               (str (str/lower-case (:title position)) " seeking a place")
               (str/replace (str/trim wanted) #"^(?i)(a|an|the)\s+" ""))
        line (subs line 0 (min max-line (count line)))
        match (or (first (filter #(names? (:line %)) contacts))
                  (first (filter #(names? (:position %)) local))
                  (first (filter #(= line (:line %)) contacts)))]
    {:tool :recommendation
     :args (cond
             match {:existing (:id match)}
             position {:new {:line line :sex (name (:sex position :female)) :age (first (:ages position [25]))
                             :based "Ely" :known-as (str "a " line ", of Ely")
                             :situation (str "You live in Ely and are " (str/lower-case (:title position))
                                             " by trade, seeking a new place in service.")}}
             :else {:new {:line line :sex "male" :age 45 :based "Ely" :known-as (str line " from Ely")
                          :situation (str "You work from Ely, and go out to houses in the country round about when sent for: " line ".")}})}))

(def adjudicators
  {:contacts/recommendation {:request (fn [world actor args _] (request world actor args)) :mock mock-judge}})
