(ns manor.mechanics.contacts
  "Contacts (docs/sending-for-design.md, from docs/trades-design.md): anyone
  may `recommend` someone to someone here for what's `wanted`, in their own
  words, and both learn a contact, an engine-owned record in
  [:state :contacts] with an id to `send-for`. The recommendation judge
  (manor.mechanics.contacts.judge) says whom it means: someone on the map (a
  local contact), a contact made before, or someone new from elsewhere,
  written now and made an away agent of the generic :caller position. Each
  contact keeps, per knower who asked, the latest `wanted`. `send-for` with
  a contact is a visit (manor.mechanics.visits, by :sent-for) expected
  `lead-hours` later, about what was wanted unless `about` says otherwise:
  someone on the map is told at once and makes their own way, someone away
  arrives at the meeting place, told by their situation
  (situations/sent-for.md) who sent for them and what about. They decide
  when to go home, with visits' `take-leave`. Install after hiring and visits; it replaces their `recommend`
  and `send-for` with ones that take `wanted` and a contact too (hiring's
  candidates, by `position`, stay beside it for now)."
  (:require [clojure.string :as str]
            [manor.clock :as clock]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.hiring :as hiring]
            [manor.mechanics.positions :as positions]
            [manor.mechanics.space :as space]
            [manor.mechanics.contacts.judge :as judge]
            [manor.mechanics.visits :as visits]
            [manor.narrate :as n]
            [manor.prompts :as prompts]
            [manor.world :as w])
  (:import (java.time LocalDateTime)))

(def contacts-limit 40)
;; Someone sent for is expected this many hours later.
(def lead-hours 2)
(def max-about 300)

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
  (str "Contact " (:id data) ": " (n/who names (:agent-id data)) ", " (:line data)
       (if (:local? data) ", on the map" (str ", of " (:based data))) "."))
(defmethod n/describe :contacts/evicted [{:keys [data]} _] (str "Contact " (:id data) " is forgotten."))
(defmethod w/apply-event :contacts/evicted [world {:keys [data]}]
  (update-in world [:state :contacts] dissoc (:id data)))
(defmethod w/apply-event :contacts/recommended [world {:keys [data]}]
  (cond-> (update-in world [:state :contacts (:id data) :known-to] (fnil into #{}) (:knowers data))
    ;; the latest recommendation to the asker wins (decision 11)
    (:asker data) (assoc-in [:state :contacts (:id data) :wanted (:asker data)] (:wanted data))))

;; ---------------------------------------------------------------------------
;; Recommending someone for what's wanted

(defn wanted-reason
  "Why `actor` can't recommend anyone for `wanted` to `for`, or nil."
  [world actor {:keys [for]}]
  (or (hiring/recipient-reason world actor for)
      (when-not (room? world) "too many people are about or expected; try again later")))

(defn- reputation
  "What `actor` knows of the person of `record`, from the people server."
  [world actor record {:keys [bridge]}]
  (let [r (bridge :people/reputation {:person record :knower (:name (w/agent world actor))
                                      :relation "acquaintance" :seed (str (:seed world) "/" actor "/" (:name record))})]
    (when-not (and (map? r) (string? (:acquaintance r)) (map? (:knows r)))
      (throw (ex-info "The people server returned an invalid reputation." {})))
    r))

(defn- write-contact
  "Someone new from elsewhere, from the judge's `fields`: the people server's
  record for their line, sex and age, living where they're based, then the
  appearance and polish, with their situation as the polish's notes."
  [world services {:keys [line sex age based situation]}]
  (let [p (hiring/person-record world services {:position line :area based :sex sex :ages [age age]
                                                :seed (str (:seed world) "/contact/" (inc (:seq world)))
                                                :taken-names (hiring/taken-names world)})
        p (-> p (assoc :lives based) (update :record assoc :lives based :position line))]
    (hiring/write-person services p situation)))

(defn recommend-wanted!
  "`recommend` with `wanted`, a query: the recommendation judge says whom it
  means, and someone new is written now (I/O, through `services`), with
  what the recommender knows of them."
  [world actor {:keys [wanted]} {:keys [adjudicate recent] :as services}]
  (let [verdict (adjudicate :contacts/recommendation {:wanted wanted :recent (recent)})
        known (some->> (:existing verdict) (contact world))]
    (cond
      (:new verdict)
      (let [p (write-contact world services (:new verdict))]
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
  "Someone new from elsewhere: an away agent of the :caller position,
  spawned from person `p` with their situation, living where they're based,
  and their contact. All :system: a spawn holds a private persona and traits."
  [scenario world p {:keys [line age based known-as situation]}]
  (let [spawned (b/spawn-events scenario world (merge (hiring/spawn-spec p)
                                                      {:role :caller :situation situation :household nil}))
        id (get-in (first spawned) [:data :agent :id])]
    (conj (mapv #(assoc % :visible-to :system) spawned)
          {:type :agent/updated :visible-to :system :data {:id id :changes {:lives based :known-as known-as :age age}}}
          {:type :people/departed :visible-to :system :data {:id id :reason "lives elsewhere" :towards based}}
          (made world {:agent-id id :name (:name p) :line line :based based :known-as known-as :record (:record p)}))))

(defn- local-contact-events
  "Someone on the map, `id`: their contact, their line their position's title."
  [world id]
  (let [a (w/agent world id)
        works-at (first (judge/workplaces world (:role a)))]
    [(made world (cond-> {:agent-id id :name (:name a) :local? true
                          :line (str/lower-case (get-in world [:state :positions (:role a) :title] (name (:role a))))}
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

(defn wanted-recommendation-events
  "The recommendation, on the world as it is when the query's done: the
  contact made if it's new (making room first), what the recommender knows
  of them, and the contact known to both, with `wanted` kept for the asker."
  [scenario world actor {:keys [for wanted] :as args} {:keys [verdict person reputation]}]
  (when-not (or (:impossible verdict) (wanted-reason world actor args))
    (let [evict (when (>= (count (contacts world)) contacts-limit)
                  [{:type :contacts/evicted :visible-to :system :data {:id (:id (eviction world))}}])
          [evs c] (contact-events scenario (apply-all world evict) verdict person)]
      (when c
        (vec (concat evict evs
                     (when reputation
                       [{:type :people/reputation-known :visible-to #{actor} :data {:id (:id c) :reputation reputation}}])
                     [{:type :contacts/recommended :visible-to (conj #{} actor for)
                       :data (merge (select-keys c [:id :name :line :based :works-at :local?])
                                    {:knowers (conj #{} actor for) :asker for :wanted wanted :lead-hours lead-hours
                                     :at (place-name world (meeting-place world))})}]))))))

(defmethod n/describe :contacts/recommended [{:keys [data]} _]
  (str (:name data) " (" (:id data) "), " (:line data)
       (if (:local? data)
         (when (:works-at data) (str ", who works at the " (:works-at data)))
         (str ", of " (:based data)))
       ". To have them come, use send-for with id \"" (:id data) "\" and about, what they're wanted for"
       (when (:asker data) (str " (whoever asked may leave about out, to send for them about \"" (:wanted data) "\")"))
       "; "
       (if (:local? data)
         (str "word reaches them at once and they are expected at the " (:at data) " " (:lead-hours data)
              " hours later, if they're willing.")
         (str "they arrive at the " (:at data) " " (:lead-hours data) " hours later."))))

(defn- wanted-reply [_ _ _ {:keys [verdict events]}]
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

(defn about-for
  "What `actor` sends for contact `c` about: `about` if given, else what
  they last asked for when `c` was recommended to them, or nil."
  [c actor about]
  (if (str/blank? about) (get-in c [:wanted actor]) about))

(defn contact-reason
  "Why `actor` can't send for the contact whose id is `id`, about `about`
  or what they wanted them for, or nil."
  [world actor {:keys [id] :as args}]
  (let [c (get (contacts world) id)
        agent-id (:agent-id c)
        a (w/agent world agent-id)]
    (cond (not (contains? (:known-to c) actor)) "you have no recommendation of that contact; use a contact id from your contacts"
          (str/blank? (about-for c actor (:about args))) "say in about what they're wanted for"
          (= agent-id actor) "you can't send for yourself"
          (nil? (meeting-place world)) "this world has no meeting place"
          (#{:retired :dead} (:status a)) (str (:name a) " cannot come")
          (sent-for-visit world agent-id) (str (:name a) " has been sent for already")
          (and (not (:local? c)) (w/active? world agent-id)) (str (:name a) " is about already; speak to them")
          (:local? c) (visits/guest-reason world agent-id))))

(defn send-for-reason
  "Why `actor` can't send for `id`: a candidate's (as hiring) or a contact's."
  [world actor {:keys [id] :as args}]
  (cond (hiring/candidate world id) (hiring/send-for-reason world actor args)
        (contact world id) (contact-reason world actor args)
        :else "nobody of that id has been recommended to you; ask someone who might know to `recommend` someone to you, then use the id it gives"))

(defn arrival-situation
  "The situation of contact `c`, from elsewhere, on arriving: who sent for
  them, what about, their home off the map, and what may come of it."
  [world actor c about]
  (prompts/render world "situations/sent-for.md"
                  {:name (:name c) :home (or (:lives (w/agent world (:agent-id c))) (:based c))
                   :by (agent-name world actor) :house (visits/house world) :about about}))

(defn sent-for
  "`actor` sends for `id`: a candidate (as hiring does) or a contact, by a
  visit by :sent-for expected `lead-hours` on, about `about` or what was
  wanted, with word of it to `actor` and to the contact if they're about.
  Someone from elsewhere gets their arrival situation with the visit;
  someone on the map keeps their own."
  [world actor {:keys [id] :as args}]
  (if (hiring/candidate world id)
    (hiring/sent-for world actor args)
    (let [c (contact world id)
          id (:agent-id c)
          about (about-for c actor (:about args))
          at (expected-at world)]
      (into [{:type :contacts/sent-for :visible-to (cond-> #{actor} (w/active? world id) (conj id))
              :data {:id id :by actor :about about :at (meeting-place world)
                     :when (clock/short-label (:clock world) (clock/round-of (:clock world) at) (:round world))}}]
            (visits/sent-for-events world actor (cond-> {:guest id :about about :at at}
                                                  (not (:local? c)) (assoc :situation (arrival-situation world actor c about))))))))

(defmethod n/describe :contacts/sent-for [{:keys [data]} names]
  (str (n/who names (:by data)) " sends for " (n/who names (:id data)) " (" (:id data) ") to come to the "
       (n/place names (:at data)) " at " (:when data) ": " (:about data) "."))

;; ---------------------------------------------------------------------------
;; Engaging someone sent for

(defn unplaced?
  "Does `id` hold no post: none, `:none`, or the :caller they came as?"
  [world id]
  (contains? #{nil :none :caller} (:position (positions/post world id))))

(defn sent-for-here?
  "Is `id` a contact here on a visit by :sent-for who holds no post, and so
  someone who may be offered a place (decision 6)?"
  [world id]
  (boolean (and (for-agent world id) (w/active? world id) (unplaced? world id)
                (= :here (:status (sent-for-visit world id))))))

(defn- offerable-reason
  "Why `actor` can't offer `who` a place, or send them away, as someone
  sent for, or nil."
  [world actor who]
  (cond (not (sent-for-here? world who)) "only someone sent for, who holds no post, can be offered a place or sent away"
        (not (space/together? world actor who)) (str (agent-name world who) " must be present")))

(defn- engage-reason
  "Why `actor` can't offer `who` this place: a candidate's (as hiring), or
  a contact's."
  [hiring-reason world actor {:keys [who] :as args}]
  (if (hiring/candidate-here? world who)
    (hiring-reason world actor args)
    (or (offerable-reason world actor who) (hiring/offer-terms-reason world actor args))))

(defn- answer-reason
  "Why `actor` can't answer their offer: a candidate's (as hiring), or a
  contact's."
  [hiring-reason world actor args]
  (if (hiring/candidate-here? world actor)
    (hiring-reason world actor args)
    (hiring/answer-reason world actor (when-not (sent-for-here? world actor)
                                        "only someone sent for, who holds no post, can take a place offered"))))

(defn place-accepted
  "Contact `actor` takes the place offered: the post (as hiring), and their
  visit by :sent-for ends, since they're one of the household now."
  [world actor args]
  (into (vec (hiring/place-taken world actor args)) (visits/ended-events world actor)))

(defn- send-away-reason [world actor who]
  (or (when (= actor who) "you can't send yourself away; use take-leave")
      (offerable-reason world actor who)
      (hiring/held-reason world who)))

(defn sent-away
  "Contact `who` is sent away: they go home as `take-leave` would, with no
  return, their visit ended and any offer to them closed."
  [world who]
  (conj (mapv #(cond-> % (= :people/departed (:type %)) (assoc-in [:data :reason] "sent away"))
              (visits/leave-events world who {}))
        {:type :people/offer-closed :visible-to :system :data {:who who}}))

(defn- someone-here?
  "Is a contact sent for here with `actor`, not `actor`, who holds no post?"
  [world actor]
  (boolean (some #(and (not= actor (:agent-id %)) (nil? (offerable-reason world actor (:agent-id %))))
                 (vals (contacts world)))))

;; ---------------------------------------------------------------------------
;; Views and rules

(defn add-to-view
  "`view` with `contacts`: the people `id` has been told of, with what `id`
  last wanted them for."
  [view world id]
  (let [known (filter #(contains? (:known-to %) id) (ordered world))]
    (cond-> view
      (seq known) (assoc :contacts
                         (mapv (fn [c]
                                 (cond-> (select-keys c [:id :name :line])
                                   (:local? c) (assoc :works-at (:works-at c) :on-the-map true)
                                   (not (:local? c)) (assoc :based (:based c))
                                   (get-in c [:wanted id]) (assoc :wanted (get-in c [:wanted id]))
                                   (sent-for-visit world (:agent-id c)) (assoc :sent-for true)))
                               known)))))

(defn rules [world]
  (prompts/render world "rules/sections/contacts.md" {:lead-hours lead-hours}))

;; ---------------------------------------------------------------------------
;; Actions

(defn- recommend-action
  "Hiring's `recommend`, with `wanted` as an alternative to `position`
  (until the candidates' pool goes)."
  [scenario recommend]
  (let [wanted? (fn [args] (some? (:wanted args)))]
    (assoc recommend
           :doc (fn [world]
                  (str ((:doc recommend) world) "\n\n"
                       (prompts/render world "docs/contacts/recommend.md" {:max-wanted judge/max-wanted})))
           :args [:map [:position {:optional true} :string]
                  [:wanted {:optional true} [:string {:min 1 :max judge/max-wanted}]]
                  [:for :string]]
           :valid? (fn [world actor {:keys [position wanted] :as args}]
                     (cond (and position wanted) "give position or wanted, not both"
                           wanted (wanted-reason world actor args)
                           position ((:valid? recommend) world actor args)
                           :else "give wanted (who or what is wanted, in your words) or position (a position id, for a candidate)"))
           :query-fn (fn [world actor args services]
                       (if (wanted? args) (recommend-wanted! world actor args services) ((:query-fn recommend) world actor args services)))
           :on-result (fn [world actor args result]
                        (if (wanted? args)
                          (wanted-recommendation-events scenario world actor args result)
                          ((:on-result recommend) world actor args result)))
           :reply (fn [world actor args result]
                    (if (wanted? args) (wanted-reply world actor args result) ((:reply recommend) world actor args result))))))

(defn- send-for-action
  "Hiring's `send-for`, for a contact too, perhaps with what about."
  [send-for]
  (assoc send-for
         :doc (prompts/doc "docs/contacts/send-for.md" {:lead-hours lead-hours})
         :args [:map [:id :string] [:about {:optional true} [:string {:min 1 :max max-about}]]]
         :valid? send-for-reason
         :effect sent-for))

(defn- either
  "Hiring's `f` for a candidate (`candidate` is who the args name), else
  `g` for a contact."
  [candidate f g]
  (fn [world actor args]
    (if (hiring/candidate-here? world (candidate actor args)) (f world actor args) (g world actor args))))

(defn- engage-action
  "Hiring's `engage`, for someone sent for too."
  [engage]
  (assoc engage
         :available? (fn [world actor] (or ((:available? engage) world actor) (someone-here? world actor)))
         :valid? (partial engage-reason (:valid? engage))))

(defn- answer-action
  "Hiring's `accept-place` or `decline-place`, for someone sent for too,
  with `effect` for them if given."
  [answer effect]
  (cond-> (assoc answer :valid? (partial answer-reason (:valid? answer)))
    effect (assoc :effect (either (fn [actor _] actor) (:effect answer) effect))))

(defn- send-away-action
  "Hiring's `send-away`, for someone sent for too."
  [send-away]
  (let [who (fn [_ args] (:who args))]
    (assoc send-away
           :available? (fn [world actor] (or ((:available? send-away) world actor) (someone-here? world actor)))
           :valid? (either who (:valid? send-away) (fn [world actor {:keys [who]}] (send-away-reason world actor who)))
           :effect (either who (:effect send-away) (fn [world _ {:keys [who]}] (sent-away world who))))))

(defn- wrapped
  "Action `spec` wrapped for contacts, once."
  [scenario spec]
  (if (::contacts spec)
    spec
    (-> (case (:name spec)
          :recommend (recommend-action scenario spec)
          :send-for (send-for-action spec)
          :engage (engage-action spec)
          :accept-place (answer-action spec place-accepted)
          :decline-place (answer-action spec nil)
          :send-away (send-away-action spec))
        (assoc ::contacts true))))

(def ^:private wrapped-names #{:recommend :send-for :engage :accept-place :decline-place :send-away})

(defn install
  "Add contacts to a scenario on hiring and visits: `recommend`, `send-for`,
  `engage`, `accept-place`, `decline-place` and `send-away` in place of
  theirs (taking contacts too, and passing candidates to hiring's), and the
  recommendation judge. Idempotent. The world's positions need a :caller for
  people from elsewhere."
  [scenario]
  (-> scenario
      (update :actions (fn [actions] (mapv #(if (wrapped-names (:name %)) (wrapped scenario %) %) actions)))
      (update :adjudicators merge judge/adjudicators)))
