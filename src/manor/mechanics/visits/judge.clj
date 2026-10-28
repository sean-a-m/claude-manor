(ns manor.mechanics.visits.judge
  "The invitation judge (docs/visits-design.md 4.2): who an inviter means by
  `invite`'s `person`, a description in their own words. One checked narrow
  call, built like the find judge, before anyone is made or told. It answers
  someone listed (`existing`), someone new from elsewhere (`new`), or, only
  when the person physically can't come, `impossible`. It sees the inviter's
  own context and the people they could mean: private detail only for the
  inviter's own guests, and people elsewhere they don't know only by name,
  home and public description, so a match tells the inviter nothing about
  anyone else's invitations. Code checks only the answer's shape."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [malli.json-schema :as mjs]
            [manor.actions :as a]
            [manor.mechanics.adjudication :as adjudication]
            [manor.mechanics.hiring :as hiring]
            [manor.mechanics.positions :as positions]
            [manor.mechanics.space :as space]
            [manor.prompts :as prompts]
            [manor.world :as w]))

(def new-person-fields
  "The fields of someone new from elsewhere and their limits, for the judge's
  `new` answer and the GM's `schedule-visit`."
  [[:name [:string {:min 1 :max 40}]]
   [:sex [:enum "male" "female"]]
   [:age [:int {:min 1 :max 110}]]
   [:from [:string {:min 1 :max 80}]]
   [:known-as [:string {:min 1 :max 120}]]
   [:situation [:string {:min 1 :max 600}]]])

(def answer-schema
  [:map
   [:existing {:optional true} :string]
   [:new {:optional true} (into [:map] new-person-fields)]
   [:impossible {:optional true} [:string {:min 1 :max 300}]]])

(def instructions (prompts/render "judges/invitation.md"))

;; ---------------------------------------------------------------------------
;; Who the inviter could mean

(defn- title
  "The title of `id`'s position, or nil for none."
  [world id]
  (let [p (:position (positions/post world id))]
    (when-not (#{nil :none :gm} p) (get-in world [:state :positions p :title] (name p)))))

(defn- lives
  "Where `id` lives, as far as anyone can say: their :lives, the house for
  its household, a candidate's home, or their bedroom's name."
  [world id]
  (or (:lives (w/agent world id))
      (when (positions/household-member? world id) (get-in world [:state :visit-config :house]))
      (:candidate-home (hiring/for-agent world id))
      (:lives (hiring/for-agent world id))
      (some->> (get-in world [:state :bedrooms id]) (space/location world) :name)))

(defn- can-come?
  "Could `id` be the person meant: someone in the world, not the inviter, the
  GM or retired?"
  [world actor id]
  (let [a (w/agent world id)]
    (and a (not= actor id) (not= :gm (:role a)) (not= :retired (:status a)))))

(defn- known-ids
  "The people `actor` knows of: everyone active, their household away
  members included, the away people they've met, and the candidates
  recommended to them who have come before."
  [world actor]
  (let [h (:household (positions/post world actor))]
    (concat (map :id (w/active-agents world))
            (when h (keep #(when (positions/household-member? world (:id %) h) (:id %)) (w/agents-in-order world)))
            (map :id (space/away-acquaintances world actor))
            (keep #(when (contains? (:known-to %) actor) (:agent-id %)) (sort-by :id (vals (hiring/pool world)))))))

(defn visit-number
  "A visit id's number, for putting visits in order: \"visit-3\" → 3."
  [id]
  (parse-long (subs id (count "visit-"))))

(defn- own-guest-ids
  "The guests `actor` has invited, in the order of their visits (with repeats)."
  [world actor]
  (->> (get-in world [:state :visits])
       (sort-by (comp visit-number key))
       (keep #(when (= actor (:host (val %))) (:guest (val %))))))

(defn- entry
  "What the judge is shown of `id`: the fields `ks` they have."
  [world id ks]
  (let [a (w/agent world id)
        fields {:id id :name (:name a) :position (title world id) :lives (lives world id) :from (:lives a)
                :known-as (:known-as a) :situation (:situation a) :dead (when (w/dead? world id) true)}]
    (into {} (keep #(when-some [v (get fields %)] [% v])) ks)))

(defn people
  "The people the judge may match `actor`'s description to, each once:
  `:your-guests` (those `actor` has invited, with their situation and public
  description), `:known` (id, name, position and where they live) and
  `:others` (everyone else away: only name, home and public description)."
  [world actor]
  (let [ok? (partial can-come? world actor)
        own (distinct (filter ok? (own-guest-ids world actor)))
        known (remove (set own) (distinct (filter ok? (known-ids world actor))))
        others (remove (set (concat own known)) (filter ok? (map :id (w/agents-in-order world))))]
    (array-map :your-guests (mapv #(entry world % [:id :name :position :lives :known-as :situation :dead]) own)
               :known (mapv #(entry world % [:id :name :position :lives :dead]) known)
               :others (mapv #(entry world % [:id :name :from :known-as :dead]) others))))

(defn- inviter-age
  "The inviter's age: their own, or their position's when it's fixed."
  [world actor]
  (or (:age (w/agent world actor))
      (let [[lo hi] (get-in world [:state :positions (:position (positions/post world actor)) :ages])]
        (when (and lo (= lo hi)) lo))))

(defn inviter
  "What the judge knows of the inviter: their own context, as their prompt has it."
  [world actor recent]
  (let [a (w/agent world actor)]
    (into {} (remove (comp nil? val))
          {:name (:name a) :age (inviter-age world actor) :sex (some-> (:sex a) name)
           :position (title world actor) :nature (:persona a) :situation (:situation a)
           :notes (mapv #(select-keys % [:round :text]) (take-last 15 (:notes a)))
           :recent (vec recent)})))

;; ---------------------------------------------------------------------------
;; The checked answer

(defn- parsed
  "The raw answer as a map, with the keys of `new` normalised as the decoder
  normalises top-level ones (models write known_as)."
  [raw]
  (let [m (cond (map? raw) raw
                (str/blank? raw) {}
                :else (json/read-value raw json/keyword-keys-object-mapper))]
    (cond-> m (map? (:new m)) (update :new update-keys a/kebab-key))))

(defn- new-problem
  "Why the judge's `new` person can't be written, or nil: a blank field, or a
  name already `listed`."
  [listed fields]
  (let [same (first (filter #(= (str/lower-case (str/trim (:name fields))) (str/lower-case (:name %))) listed))]
    (cond (some #(str/blank? (get fields %)) [:name :from :known-as :situation])
          "new needs a name, from, known-as and situation that aren't blank"
          same (str (:name same) " is listed already (" (:id same) "): answer existing with that id if they're meant, or give the new person another name"))))

(defn- check-answer
  "The judge's answer, checked against the listed people: {:existing id},
  {:new fields} or {:impossible reason}, or {:error why}."
  [listed raw]
  (let [{:keys [args error]} (try (a/decode-args {:args answer-schema} (parsed raw))
                                  (catch Exception e {:error (str "could not parse arguments: " (ex-message e))}))
        given (filter #(contains? args %) [:existing :new :impossible])]
    (cond error {:error error}
          (not= 1 (count given)) {:error "give exactly one of existing, new or impossible"}
          (:existing args) (if (some #(= (:existing args) (:id %)) listed)
                             {:existing (:existing args)}
                             {:error "existing must be the id of someone listed in people"})
          (:new args) (if-let [problem (new-problem listed (:new args))]
                        {:error problem}
                        {:new (update (:new args) :name str/trim)})
          (str/blank? (:impossible args)) {:error "impossible needs a reason"}
          :else {:impossible (str/trim (:impossible args))})))

(defn invitation-request
  "The judge's request for `actor`'s description `person` (and the visit's
  `reason`), with their `recent` lines: their own context and `people`."
  [world actor {:keys [person reason recent]}]
  (let [who (inviter world actor recent)
        listed (people world actor)]
    {:messages [{:role "system" :content instructions}
                {:role "user" :content (json/write-value-as-string
                                        (cond-> {:inviter who :description person :people listed}
                                          reason (assoc :reason reason)))}]
     :tools [{:type "function"
              :function {:name "invitee"
                         :description "Say who is meant: existing (a listed id), new (someone not yet in the world), or impossible (they physically cannot come)."
                         :parameters (mjs/transform answer-schema)}}]
     :ctx {:world world :inviter who :description person :people listed}
     :check #(adjudication/answer "invitee" % (partial check-answer (vec (mapcat val listed))))}))

;; ---------------------------------------------------------------------------
;; The mock, for --mock runs and tests

(defn- bare [s] (str/replace (str/trim (str/lower-case (str s))) #"^(a|an|the)\s+" ""))

(defn mock-judge
  "A stand-in judge: the inviter named means impossible; a listed person
  whose surname or public description the description contains is them;
  anyone else is someone new, named by the description's last capitalised
  part."
  [{:keys [inviter description people]}]
  (let [text (str/lower-case description)
        mentions? #(and (not (str/blank? %)) (str/includes? text (str/lower-case %)))
        surname #(last (str/split (str/trim (str %)) #"\s+"))
        match (first (filter #(or (mentions? (surname (:name %))) (mentions? (bare (:known-as %))))
                             (mapcat people [:your-guests :known :others])))
        given (or (last (filter #(re-find #"^\p{Lu}" %) (map str/trim (str/split description #",")))) "Mr Smith")
        given (subs given 0 (min 40 (count given)))]
    {:tool :invitee
     :args (cond (mentions? (:name inviter)) {:impossible "That is you."}
                 (:dead match) {:impossible "That person is dead."}
                 match {:existing (:id match)}
                 :else {:new {:name given :sex "male" :age 50 :from "London" :known-as "a visitor from London"
                              :situation (let [s (str "You are " given ", of London. " (:name inviter)
                                                      " has invited you to the house: " description)]
                                           (subs s 0 (min 600 (count s))))}})}))

(def adjudicators
  {:visits/invitee {:request invitation-request :mock mock-judge}})
