(ns manor.mechanics.trades.judge
  "The trade judge (docs/trades-design.md 4.2): whom a recommender means by
  `recommend`'s `trade`, in their own words. One checked narrow call, built
  like the invitation judge, before anyone is made. It answers someone
  listed (`existing`: a tradesperson on the map, or a contact made before),
  someone new from elsewhere (`new`), or, only when no such trade could
  exist in the setting, `impossible`. It sees the recommender's own context
  and the people they could mean: those on the map by position and
  workplace, contacts by name, trade and home only, never who knows them.
  Code checks only the answer's shape."
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

(def max-trade 80)

(def new-fields
  "Someone new from elsewhere, as the judge writes them. The people server
  rolls their name."
  [[:trade [:string {:min 1 :max max-trade}]]
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
  (prompts/render world "judges/trade.md" {}))

;; ---------------------------------------------------------------------------
;; Whom the recommender could mean

(def ^:private not-trades
  "Roles that are no trade on the map: no post, the GM, guests, and
  tradesmen sent for (who are listed as contacts)."
  #{:none :gm :guest :tradesman})

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
             :when (and (not= actor (:id a)) (not (not-trades (:role a)))
                        (not (positions/household-member? world (:id a))))]
         (cond-> {:id (:id a) :name (:name a) :position (title world (:role a))}
           (workplaces world (:role a)) (assoc :works-at (workplaces world (:role a)))))))

(defn contacts-listed
  "The contacts made so far whose person can still come, by name, trade,
  home and public description only."
  [world]
  (vec (for [c (sort-by :created (vals (get-in world [:state :contacts])))
             :when (and (not (:local? c)) (not (#{:retired :dead} (:status (w/agent world (:agent-id c))))))]
         {:id (:id c) :name (:name c) :trade (:trade c) :based (:based c) :known-as (:known-as c)})))

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
          (:new args) (if (some #(str/blank? (get-in args [:new %])) [:trade :based :known-as :situation])
                        {:error "new needs a trade, based, known-as and situation that aren't blank"}
                        {:new (update-vals (:new args) #(if (string? %) (str/trim %) %))})
          (str/blank? (:impossible args)) {:error "impossible needs a reason"}
          :else {:impossible (str/trim (:impossible args))})))

(defn request
  "The judge's request for `actor`'s `trade`, with their `recent` lines: their
  own context, the people on the map and the contacts it may match."
  [world actor {:keys [trade recent]}]
  (let [who (visits-judge/inviter world actor recent)
        local (local-people world actor)
        listed (contacts-listed world)]
    {:messages [{:role "system" :content (instructions world)}
                {:role "user" :content (json/write-value-as-string
                                        {:recommender who :trade trade :local local :contacts listed})}]
     :tools [{:type "function"
              :function {:name "tradesperson"
                         :description (prompts/render world "judges/tools/tradesperson.md" {})
                         :parameters (mjs/transform answer-schema)}}]
     :ctx {:world world :recommender who :trade trade :local local :contacts listed}
     :check #(adjudication/answer "tradesperson" %
                                  (partial check-answer (set (map :id (concat local listed)))))}))

;; ---------------------------------------------------------------------------
;; The mock, for --mock runs and tests

(defn mock-judge
  "A stand-in judge: a listed contact whose trade, or a local person whose
  position, the trade names is them; anyone else is someone new from Ely."
  [{:keys [trade local contacts]}]
  (let [text (str/lower-case trade)
        names? #(and (not (str/blank? %)) (str/includes? text (str/lower-case %)))
        match (or (first (filter #(names? (:trade %)) contacts))
                  (first (filter #(names? (:position %)) local)))
        trade (str/replace (str/trim trade) #"^(?i)(a|an|the)\s+" "")
        trade (subs trade 0 (min max-trade (count trade)))]
    {:tool :tradesperson
     :args (if match
             {:existing (:id match)}
             {:new {:trade trade :sex "male" :age 45 :based "Ely" :known-as (str trade " from Ely")
                    :situation (str "You work from Ely, and go out to houses in the country round about when sent for: " trade ".")}})}))

(def adjudicators
  {:trades/tradesperson {:request (fn [world actor args _] (request world actor args)) :mock mock-judge}})
