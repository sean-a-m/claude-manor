(ns manor.mechanics.trades
  "Tradespeople and merchants (docs/trades-design.md): anyone may
  `recommend` a `trade` to someone here, in their own words, and both learn
  a contact, an engine-owned record in [:state :contacts] with an id to
  `send-for`. The trade judge (manor.mechanics.trades.judge) says whom it
  means: someone on the map (a local contact), a contact made before, or
  someone new from elsewhere, written now and made an away agent of the
  generic :tradesman position. `send-for` with a contact and a job is a
  visit (manor.mechanics.visits, by :sent-for) expected `lead-hours` later:
  someone on the map is told at once and makes their own way, someone away
  arrives at the meeting place. A tradesperson from elsewhere goes home with
  `take-leave`, or by themselves after `idle-hours` without a conversation
  or work. Install after hiring and visits; it replaces their `recommend`
  and `send-for` with ones that take a trade and a contact too."
  (:require [clojure.string :as str]
            [manor.clock :as clock]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.hiring :as hiring]
            [manor.mechanics.physical :as physical]
            [manor.mechanics.space :as space]
            [manor.mechanics.trades.judge :as judge]
            [manor.mechanics.visits :as visits]
            [manor.narrate :as n]
            [manor.prompts :as prompts]
            [manor.world :as w])
  (:import (java.time LocalDateTime)))

(def contacts-limit 40)
;; A tradesperson sent for is expected this many hours later.
(def lead-hours 3)
;; A tradesperson from elsewhere goes home after this long without a
;; conversation or work.
(def idle-hours 2)
(def max-job 300)

(defn contacts [world] (get-in world [:state :contacts] {}))
(defn contact [world id] (get (contacts world) id))
(defn- ordered [world] (sort-by :created (vals (contacts world))))

(defn for-agent
  "The contact whose person is `id`, or nil."
  [world id]
  (first (filter #(= id (:agent-id %)) (ordered world))))

(defn- meeting-place [world] (get-in world [:state :meeting-place]))
(defn- place-name [world id] (:name (space/location world id) id))
(defn- agent-name [world id] (:name (w/agent world id) id))

(defn- sent-for-visit
  "`id`'s visit by :sent-for that is expected or under way, or nil."
  [world id]
  (first (filter #(and (= id (:guest %)) (= :sent-for (:by %)) (#{:scheduled :here} (:status %)))
                 (vals (visits/visits world)))))

(defn- evictable?
  "Can contact `c`'s record go to make room: not someone on the map, nor
  about, nor expected?"
  [world c]
  (and (not (:local? c)) (not (w/active? world (:agent-id c))) (nil? (sent-for-visit world (:agent-id c)))))

(defn- eviction [world] (first (filter #(evictable? world %) (ordered world))))
(defn- room? [world] (or (< (count (contacts world)) contacts-limit) (eviction world)))

(defmethod w/apply-event :contacts/made [world {:keys [data]}]
  (-> world
      (assoc-in [:state :contacts (:id data)] data)
      (update-in [:state :contact-count] (fnil inc 0))))
(defmethod n/describe :contacts/made [{:keys [data]} names]
  (str "Contact " (:id data) ": " (n/who names (:agent-id data)) ", " (:trade data)
       (if (:local? data) ", on the map" (str ", of " (:based data))) "."))
(defmethod n/describe :contacts/evicted [{:keys [data]} _] (str "Contact " (:id data) " is forgotten."))
(defmethod w/apply-event :contacts/evicted [world {:keys [data]}]
  (update-in world [:state :contacts] dissoc (:id data)))
(defmethod w/apply-event :contacts/recommended [world {:keys [data]}]
  (update-in world [:state :contacts (:id data) :known-to] (fnil into #{}) (:knowers data)))

;; ---------------------------------------------------------------------------
;; Recommending a trade

(defn trade-reason
  "Why `actor` can't recommend a trade to `for`, or nil."
  [world actor {:keys [for]}]
  (or (hiring/recipient-reason world actor for)
      (when-not (room? world) "too many tradespeople are about or expected; try again later")))

(defn- reputation
  "What `actor` knows of the person of `record`, from the people server."
  [world actor record {:keys [bridge]}]
  (let [r (bridge :people/reputation {:person record :knower (:name (w/agent world actor))
                                      :relation "acquaintance" :seed (str (:seed world) "/" actor "/" (:name record))})]
    (when-not (and (map? r) (string? (:acquaintance r)) (map? (:knows r)))
      (throw (ex-info "The people server returned an invalid reputation." {})))
    r))

(defn- write-tradesperson
  "Someone new from elsewhere, from the judge's `fields`: the people server's
  record for their trade, sex and age, living where they're based, then the
  appearance and polish, with their situation as the polish's notes."
  [world services {:keys [trade sex age based situation]}]
  (let [p (hiring/person-record world services {:position trade :area based :sex sex :ages [age age]
                                                :seed (str (:seed world) "/contact/" (inc (:seq world)))
                                                :taken-names (hiring/taken-names world)})
        p (-> p (assoc :lives based) (update :record assoc :lives based :position trade))]
    (hiring/write-person services p situation)))

(defn recommend-trade!
  "`recommend` with `trade`, a query: the trade judge says whom it means, and
  someone new is written now (I/O, through `services`), with what the
  recommender knows of them."
  [world actor {:keys [trade]} {:keys [adjudicate recent] :as services}]
  (let [verdict (adjudicate :trades/tradesperson {:trade trade :recent (recent)})
        known (some->> (:existing verdict) (contact world))]
    (cond
      (:new verdict)
      (let [p (write-tradesperson world services (:new verdict))]
        {:verdict verdict :person p :reputation (reputation world actor (:record p) services)})
      (and known (:record known))
      {:verdict verdict :reputation (or (get-in world [:state :reputations actor (:id known)])
                                        (reputation world actor (:record known) services))}
      :else {:verdict verdict})))

(defn- next-id [world] (str "contact-" (inc (get-in world [:state :contact-count] 0))))

(defn- made [world c]
  {:type :contacts/made :visible-to :system
   :data (assoc c :id (next-id world) :created (inc (get-in world [:state :contact-count] 0)) :known-to #{})})

(defn- new-contact-events
  "Someone new from elsewhere: an away agent of the :tradesman position,
  spawned from person `p` with their situation, living where they're based,
  and their contact. All :system: a spawn holds a private persona and traits."
  [scenario world p {:keys [trade age based known-as situation]}]
  (let [spawned (b/spawn-events scenario world (merge (hiring/spawn-spec p)
                                                      {:role :tradesman :situation situation :household nil}))
        id (get-in (first spawned) [:data :agent :id])]
    (conj (mapv #(assoc % :visible-to :system) spawned)
          {:type :agent/updated :visible-to :system :data {:id id :changes {:lives based :known-as known-as :age age}}}
          {:type :people/departed :visible-to :system :data {:id id :reason "lives elsewhere" :towards based}}
          (made world {:agent-id id :name (:name p) :trade trade :based based :known-as known-as :record (:record p)}))))

(defn- local-contact-events
  "Someone on the map, `id`: their contact."
  [world id]
  (let [a (w/agent world id)
        works-at (first (judge/workplaces world (:role a)))]
    [(made world (cond-> {:agent-id id :name (:name a) :local? true
                          :trade (str/lower-case (get-in world [:state :positions (:role a) :title] (name (:role a))))}
                   works-at (assoc :works-at works-at)))]))

(defn- apply-all [world events] (reduce w/apply-event world events))

(defn- contact-events
  "[events contact]: the contact the judge's `verdict` means, made now if
  it's new, on the current world. Nil if they can't be had (a contact
  evicted, or someone on the map gone)."
  [scenario world verdict person]
  (let [id (:existing verdict)
        making (fn [evs] [evs (contact (apply-all world evs) (:id (:data (last evs))))])]
    (cond (:new verdict) (making (new-contact-events scenario world person (:new verdict)))
          (contact world id) [[] (contact world id)]
          (for-agent world id) [[] (for-agent world id)]
          (and id (w/active? world id)) (making (local-contact-events world id)))))

(defn trade-recommendation-events
  "The recommendation, on the world as it is when the query's done: the
  contact made if it's new (making room first), what the recommender knows
  of them, and the contact known to both."
  [scenario world actor {:keys [for] :as args} {:keys [verdict person reputation]}]
  (when-not (or (:impossible verdict) (trade-reason world actor args))
    (let [evict (when (>= (count (contacts world)) contacts-limit)
                  [{:type :contacts/evicted :visible-to :system :data {:id (:id (eviction world))}}])
          [evs c] (contact-events scenario (apply-all world evict) verdict person)]
      (when c
        (vec (concat evict evs
                     (when reputation
                       [{:type :people/reputation-known :visible-to #{actor} :data {:id (:id c) :reputation reputation}}])
                     [{:type :contacts/recommended :visible-to (conj #{} actor for)
                       :data (merge (select-keys c [:id :name :trade :based :works-at :local?])
                                    {:knowers (conj #{} actor for) :lead-hours lead-hours
                                     :at (place-name world (meeting-place world))})}]))))))

(defmethod n/describe :contacts/recommended [{:keys [data]} _]
  (str (:name data) " (" (:id data) "), " (:trade data)
       (if (:local? data)
         (when (:works-at data) (str ", who works at the " (:works-at data)))
         (str ", of " (:based data)))
       ". To have them come, use send-for with contact \"" (:id data) "\" and the job; "
       (if (:local? data)
         (str "word reaches them at once and they are expected at the " (:at data) " " (:lead-hours data)
              " hours later, if they're willing.")
         (str "they arrive at the " (:at data) " " (:lead-hours data) " hours later."))))

(defn- trade-reply [_ _ _ {:keys [verdict events]}]
  (cond (seq events)
        (str/join "\n" (map #(n/describe % {}) (filter #(#{:contacts/recommended :people/reputation-known} (:type %)) events)))
        (:impossible verdict) (str "No recommendation was made: " (:impossible verdict))
        :else "No recommendation was made: the person meant can't be had now."))

;; ---------------------------------------------------------------------------
;; Sending for a contact

(defn expected-at
  "When someone sent for now is expected: `lead-hours` on."
  ^LocalDateTime [world]
  (.plusMinutes (clock/time-of (:clock world) (:round world)) (* 60 lead-hours)))

(defn contact-reason
  "Why `actor` can't send for contact `contact` to do `job`, or nil."
  [world actor {:keys [contact job]}]
  (let [c (get (contacts world) contact)
        id (:agent-id c)
        a (w/agent world id)]
    (cond (not (contains? (:known-to c) actor)) "you have no recommendation of that contact; use a contact id from your contacts"
          (str/blank? job) "say in job what they're wanted for"
          (= id actor) "you can't send for yourself"
          (nil? (meeting-place world)) "this world has no meeting place"
          (#{:retired :dead} (:status a)) (str (:name a) " cannot come")
          (sent-for-visit world id) (str (:name a) " has been sent for already")
          (and (not (:local? c)) (w/active? world id)) (str (:name a) " is about already; speak to them")
          (:local? c) (visits/guest-reason world id))))

(defn send-for-reason [world actor {:keys [candidate contact] :as args}]
  (cond (and candidate contact) "give candidate or contact, not both"
        candidate (hiring/send-for-reason world actor args)
        contact (contact-reason world actor args)
        :else "give candidate (a candidate id) or contact (a contact id) to send for"))

(defn sent-for
  "`actor` sends for a candidate (as hiring does) or a contact: a visit by
  :sent-for, expected `lead-hours` on, and word of it to `actor` and to the
  contact if they're about."
  [world actor {:keys [candidate contact job] :as args}]
  (if candidate
    (hiring/sent-for world actor args)
    (let [id (:agent-id (get (contacts world) contact))
          at (expected-at world)]
      (into [{:type :trades/sent-for :visible-to (cond-> #{actor} (w/active? world id) (conj id))
              :data {:id id :by actor :job job :at (meeting-place world)
                     :when (clock/short-label (:clock world) (clock/round-of (:clock world) at) (:round world))}}]
            (visits/sent-for-events world actor {:guest id :job job :at at})))))

(defmethod n/describe :trades/sent-for [{:keys [data]} names]
  (str (n/who names (:by data)) " sends for " (n/who names (:id data)) " (" (:id data) ") to come to the "
       (n/place names (:at data)) " at " (:when data) ": " (:job data) "."))

;; ---------------------------------------------------------------------------
;; Going home

(defn- idle-departure
  "A tradesperson from elsewhere, here on a visit by :sent-for, goes home
  after `idle-hours` without a conversation or work."
  [world c]
  (let [id (:agent-id c)
        v (sent-for-visit world id)]
    (when (and (not (:local? c)) v (= :here (:status v)) (:from-away? v) (w/active? world id)
               (nil? (w/busy world id)) (not (physical/held-in-place? world id))
               (>= (hiring/idle-rounds world id (:arrived v)) (clock/rounds-for (:clock world) (* 60 idle-hours))))
      (visits/leave-events world id {}))))

(defn tick
  "Idle departures before turns; each sees the one before."
  [world]
  (second (reduce (fn [[world events] c]
                    (let [evs (idle-departure world c)]
                      [(apply-all world evs) (into events evs)]))
                  [world []] (ordered world))))

;; ---------------------------------------------------------------------------
;; Views and rules

(defn add-to-view
  "`view` with `contacts`: the tradespeople `id` has been told of."
  [view world id]
  (let [known (filter #(contains? (:known-to %) id) (ordered world))]
    (cond-> view
      (seq known) (assoc :contacts
                         (mapv (fn [c]
                                 (cond-> (select-keys c [:id :name :trade])
                                   (:local? c) (assoc :works-at (:works-at c) :on-the-map true)
                                   (not (:local? c)) (assoc :based (:based c))
                                   (sent-for-visit world (:agent-id c)) (assoc :sent-for true)))
                               known)))))

(defn rules [world]
  (prompts/render world "rules/sections/trades.md" {:lead-hours lead-hours :idle-hours idle-hours}))

;; ---------------------------------------------------------------------------
;; Actions

(defn- recommend-action
  "Hiring's `recommend`, with `trade` as an alternative to `position`."
  [scenario recommend]
  (let [trade? (fn [args] (some? (:trade args)))]
    (assoc recommend
           :doc (fn [world]
                  (str ((:doc recommend) world) "\n\n"
                       (prompts/render world "docs/trades/recommend-trade.md" {:max-trade judge/max-trade})))
           :args [:map [:position {:optional true} :string]
                  [:trade {:optional true} [:string {:min 1 :max judge/max-trade}]]
                  [:for :string]]
           :valid? (fn [world actor {:keys [position trade] :as args}]
                     (cond (and position trade) "give position or trade, not both"
                           trade (trade-reason world actor args)
                           position ((:valid? recommend) world actor args)
                           :else "give position (a position id, for staff) or trade (a trade or service, in your words)"))
           :query-fn (fn [world actor args services]
                       (if (trade? args) (recommend-trade! world actor args services) ((:query-fn recommend) world actor args services)))
           :on-result (fn [world actor args result]
                        (if (trade? args)
                          (trade-recommendation-events scenario world actor args result)
                          ((:on-result recommend) world actor args result)))
           :reply (fn [world actor args result]
                    (if (trade? args) (trade-reply world actor args result) ((:reply recommend) world actor args result))))))

(defn- send-for-action
  "Hiring's `send-for`, for a contact with a job too."
  [send-for]
  (assoc send-for
         :available? (fn [world actor]
                       (or ((:available? send-for) world actor)
                           (boolean (some #(contains? (:known-to %) actor) (vals (contacts world))))))
         :doc (prompts/doc "docs/trades/send-for.md" {:lead-hours lead-hours})
         :args [:map [:candidate {:optional true} :string] [:contact {:optional true} :string]
                [:job {:optional true} [:string {:min 1 :max max-job}]]]
         :valid? send-for-reason
         :effect sent-for))

(defn install
  "Add trades to a scenario on hiring and visits: `recommend` and `send-for`
  in place of theirs, the trade judge, and the tick. Idempotent. The world's
  positions need a :tradesman for people from elsewhere."
  [scenario]
  (-> scenario
      (update :actions (fn [actions]
                         (mapv #(case (:name %)
                                  :recommend (if (::trades %) % (assoc (recommend-action scenario %) ::trades true))
                                  :send-for (if (::trades %) % (assoc (send-for-action %) ::trades true))
                                  %)
                               actions)))
      (update :adjudicators merge judge/adjudicators)
      (update :systems #(conj (vec (remove (fn [s] (= :trades/tick (:name s))) %))
                              {:name :trades/tick :tick (fn [world _] (tick world))}))))
