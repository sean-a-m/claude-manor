(ns manor.mechanics.hiring
  "Candidate records, private recommendations and event-owned hiring.
  I/O runs through the engine's composed-query services; reducers never call out."
  (:require [clojure.string :as str]
            [manor.actions :as a]
            [manor.clock :as clock]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.calendar :as calendar]
            [manor.mechanics.items.actions :as item-actions]
            [manor.mechanics.people :as people]
            [manor.mechanics.physical :as physical]
            [manor.mechanics.positions :as positions]
            [manor.mechanics.space :as space]
            [manor.narrate :as n]
            [manor.prompts :as prompts]
            [manor.settings :as settings]
            [manor.world :as w])
  (:import (java.time LocalDate LocalDateTime LocalTime)))

(def pool-limit 40)
(def candidate-wait-hours 24)

(defn candidate-wait-rounds
  "A full day's grace, rounded up to whole rounds. Without a game clock,
  use 24 rounds; no real-time duration can be inferred in those worlds."
  [world]
  (if-let [c (:clock world)]
    (clock/rounds-for c (* 60 candidate-wait-hours))
    candidate-wait-hours))

(def bridges
  {:people/generate {:mcp {:server :people :tool "generate_people"} :max-chars 20000}
   :people/reputation {:mcp {:server :people :tool "reputation"} :max-chars 20000}})

(def offline-stubs
  "Free fixtures, not a second production generator. Never start a process offline."
  {:people/generate
   (fn [{:keys [position sex ages taken-names candidate]} _]
     {:people [{:name (first (remove (set taken-names) (map #(str "Pat Ellis " %) (range))))
                :age (first (or ages [22 40])) :sex (or sex "female") :position position
                :origin "a place not far off"
                (if candidate :candidate-home :lives) (if candidate "lodgings outside the mapped area" "lodgings nearby")
                :temperament (mapv #(hash-map :axis (name %) :level "average" :measures (people/axis-measures %)) people/axes)
                :faculties [{:faculty "Sight" :size "small" :means "eyesight"}]}]})
   :people/reputation
   (fn [{:keys [person knower]} _]
     {:person (:name person) :knower knower :relation "neighbour"
      :acquaintance (str knower " knows " (:name person) " as a neighbour.")
      :knows {:temperament (vec (take 1 (:temperament person))) :faculties [] :circumstances []}})})

(def adjudicators
  "Hiring's two character writers, on the run's :model (judges/adjudicate)."
  {:people/appearance {:writer? true
                       :request (fn [world _ {:keys [record]} _] (people/appearance-request world record))
                       :mock (fn [_] {:tool :appearance :args {:appearance "A young figure in plain working clothes and spectacles."}})}
   :people/polish {:writer? true
                   :request (fn [world _ {:keys [record appearance notes]} _]
                              (people/polish-request world {:setting (get-in world [:state :setting])
                                                      :position (:position record) :record record
                                                      :notes (str (when-not (str/blank? notes) (str notes "\n\n"))
                                                                  "Keep these visible physical facts consistent: " appearance)}))
                   :mock people/mock-polish}})

(defn pool [world] (get-in world [:state :pool] {}))
(defn candidate [world id] (get (pool world) id))
(defn for-agent [world id] (first (filter #(= id (:agent-id %)) (vals (pool world)))))
(defn- ordered [world] (sort-by (juxt :created :id) (vals (pool world))))
(defn- evictable? [world p]
  (and (#{:pooled :away} (:status p))
       (not (w/active? world (:agent-id p)))))
(defn eviction [world] (first (filter #(evictable? world %) (ordered world))))
(defn- room? [world] (or (< (count (pool world)) pool-limit) (eviction world)))
(defn place-area
  "The people server's area for `place`: a walking area, not a domain, so
  Lodge's shop and house share one."
  [world place]
  (first (sort (space/area world place))))
(defn- area [world actor] (place-area world (space/location-of world actor)))
(defn taken-names
  "Names already in use: everyone spawned, every candidate, and the shops'
  couriers, who are named before they first appear (manor.mechanics.deliveries)."
  [world]
  (concat (map :name (vals (:agents world))) (map :name (vals (pool world)))
          (keep (comp :name :person) (vals (get-in world [:state :couriers])))))
(defn- reusable [world actor position]
  (first (filter #(and (#{:pooled :away} (:status %))
                       (= (area world actor) (:area %))
                       (some #{position} (:seeks %))
                       (not (#{:retired :dead} (:status (w/agent world (:agent-id %))))))
                 (ordered world))))

(defmethod w/apply-event :people/pooled [world {:keys [data]}]
  (assoc-in world [:state :pool (:id data)] data))
(defmethod w/apply-event :people/evicted [world {:keys [data]}]
  (update-in world [:state :pool] dissoc (:id data)))
(defmethod w/apply-event :people/candidate-updated [world {:keys [data]}]
  (update-in world [:state :pool (:id data)] merge (:changes data)))
(defmethod w/apply-event :people/recommended [world {:keys [data]}]
  (update-in world [:state :pool (:id data) :known-to] (fnil into #{}) (:knowers data)))
(defmethod w/apply-event :people/reputation-known [world {:keys [actor data]}]
  (assoc-in world [:state :reputations actor (:id data)] (:reputation data)))
(defmethod w/apply-event :people/offered [world {:keys [data]}]
  (assoc-in world [:state :place-offers (:who data)] data))
(defmethod w/apply-event :people/offer-closed [world {:keys [data]}]
  (update-in world [:state :place-offers] dissoc (:who data)))

(defn- changed [id changes]
  {:type :people/candidate-updated :visible-to :system :data {:id id :changes changes}})

(def ^:private not-posts
  "Catalogue positions nobody is hired for: no post at all, the GM, a
  visitor (manor.mechanics.visits) and a tradesman sent for
  (manor.mechanics.trades). Recommend and engage refuse them, and
  `reference` doesn't list them."
  #{:none :gm :guest :tradesman})

(defn not-a-post-reason
  "Why `position` can't be filled by hiring, or nil."
  [world position]
  (let [position (keyword position)]
    (cond (not (get-in world [:state :positions position])) "use a position id from the catalogue"
          (= :guest position) "guest is not a post; use the id of a position to fill"
          (not-posts position) "name a character's position to fill")))

(defn recipient-reason
  "Why `actor` can't recommend anyone to `for`, or nil: they must be someone
  else, here. Shared with trades (manor.mechanics.trades)."
  [world actor for]
  (cond
    (= actor for) "ask someone else here to recommend to you, not yourself"
    (not (and (w/active? world for) (not= :gm (:role (w/agent world for)))
              (space/together? world actor for))) "the person asking must be here"))

(defn recommend-reason [world actor {:keys [position for]}]
  (or (not-a-post-reason world position)
      (recipient-reason world actor for)
      (when-not (or (reusable world actor (keyword position)) (room? world)) "the candidate pool is full of people still in play")))

(defn- check-record [record]
  (when-not (and (map? record) (string? (:name record)) (not (str/blank? (:name record)))
                 (integer? (:age record)) (#{"male" "female" :male :female} (:sex record))
                 (string? (:position record))
                 (every? sequential? (map record [:temperament :faculties])))
    (throw (ex-info "The people server returned an invalid person." {})))
  (people/base-record record))

(defn person-record
  "The first, free half of a new person for `position`: the people server's
  record, {:name :record :lives | :candidate-home}. `candidate?` requests a
  hiring-only, off-map home instead of :lives. `sex` and `ages`
  ([youngest oldest]), if given, replace the position's. No model call."
  [world {:keys [bridge]} {:keys [position area seed taken-names sex ages candidate?]}]
  (let [record (check-record
                (first (:people (bridge :people/generate
                                        (cond-> (assoc (people/generate-args position (get-in world [:state :positions position])
                                                                             {:dataset (people/dataset (settings/pack world))
                                                                              :seed seed :taken-names taken-names :sex sex :ages ages})
                                                       :area area)
                                          candidate? (assoc :candidate true))))))]
    (when (and candidate? (or (not (string? (:candidate-home record))) (str/blank? (:candidate-home record))))
      (throw (ex-info "The people server returned no candidate home." {})))
    (merge {:name (:name record) :record record} (select-keys record [:lives :candidate-home]))))

(defn written? [p] (some? (:persona p)))

(defn write-person
  "The second half: a public appearance and a private polish for person `p`
  from `person-record`, adding :persona :manner :appearance and the record's
  circumstances, and the polish's :goals and :aspects if it gave any. `notes`, if given, go to the polish (who they are, for a
  guest written to order)."
  ([services p] (write-person services p nil))
  ([{:keys [adjudicate]} {:keys [record] :as p} notes]
   (let [public (adjudicate :people/appearance {:record record})
         polish (adjudicate :people/polish (cond-> {:record record :appearance (:appearance public)}
                                             notes (assoc :notes notes)))]
     (merge (assoc-in p [:record :circumstances] (:circumstances polish))
            (select-keys polish [:persona :manner :goals :aspects]) public))))

(defn new-person
  "A fresh person for `position`, made through the composed-query services:
  {:name :record :lives | :candidate-home :persona :manner :appearance}, with
  any :goals and :aspects. Candidates and couriers
  are both made here, so a change to how people are written reaches both."
  [world services opts]
  (write-person services (person-record world services opts)))

(defn spawn-spec
  "What an agent spawned from person `p` is made of: their hidden traits, the
  prose written for them, and any goals and aspects. Merge in :role,
  :location and :situation."
  [p]
  (merge (people/traits (:record p)) (select-keys p [:name :persona :manner :appearance :goals :aspects])))

(defn idle-rounds
  "Rounds since `id` arrived (in round `arrived`) or last conversed, whichever is later."
  [world id arrived]
  (- (:round world) (max arrived (:last-conversation-round (w/agent world id) 0))))

(defn recommend!
  "One query, composed of engine-recorded bridge calls and checked model calls.
  No intermediate full record is returned to a character."
  [world actor {:keys [position]} {:keys [bridge] :as services}]
  (let [position (keyword position)
        old (reusable world actor position)
        p (or old (merge {:id (str "candidate-" (inc (:seq world))) :created (inc (:seq world))
                          :area (area world actor) :seeks [position] :known-to #{} :status :pooled}
                         (new-person world services {:position position :area (area world actor) :candidate? true
                                                     :seed (str (:seed world) "/candidate/" (inc (:seq world)))
                                                     :taken-names (taken-names world)})))
        record (:record p)
        reputation (or (get-in world [:state :reputations actor (:id p)])
                       (bridge :people/reputation {:person record :knower (:name (w/agent world actor))
                                                   :relation "neighbour" :seed (str (:seed world) "/" actor "/" (:id p))}))]
    (when-not (and (map? reputation) (string? (:acquaintance reputation)) (map? (:knows reputation)))
      (throw (ex-info "The people server returned an invalid reputation." {})))
    {:candidate p :new? (nil? old) :reputation reputation}))

(defn recommendation-events [world actor {:keys [for] :as args} {:keys [candidate new? reputation]}]
  ;; Another actor's query may have committed during I/O. Allocate on the
  ;; current world, recheck capacity/presence, and reuse an identical record.
  (when-not (recommend-reason world actor args)
    (let [existing (if new?
                     (first (filter #(= (:record candidate) (:record %)) (ordered world)))
                     (get (pool world) (:id candidate)))
          new? (and new? (nil? existing))
          candidate (if new?
                      (assoc candidate :id (str "candidate-" (inc (:seq world))) :created (inc (:seq world)))
                      existing)
          id (:id candidate)]
      (when candidate
        (vec (concat
              (when (and new? (>= (count (pool world)) pool-limit))
                [{:type :people/evicted :visible-to :system :data {:id (:id (eviction world))}}])
              (when new? [{:type :people/pooled :visible-to :system :data candidate}])
              [{:type :people/reputation-known :visible-to #{actor} :data {:id id :reputation reputation}}
               {:type :people/recommended :visible-to (conj #{} actor for)
                :data (assoc (select-keys candidate [:id :name :seeks]) :knowers (conj #{} actor for))}]))))))

(defmethod n/describe :people/recommended [{:keys [data]} _]
  (str (:name data) " (" (:id data) ") seeks " (str/join ", " (map name (:seeks data)))
       ". To meet them, use send-for with id \"" (:id data) "\"; they arrive next round."))
(defn- known-reading
  "A known basic reading or written circumstance, not a stock behaviour.
  Old event records may still carry descriptor maps; preserve their narration."
  [t]
  (cond
    (string? t) t
    (:text t) (:text t)
    (:axis t) (str (str/capitalize (name (:axis t))) ": " (name (:level t))
                   " (" (people/axis-measures (keyword (:axis t))) ").")
    (:faculty t) (str (:faculty t) ": " (name (:size t))
                      " (" (people/faculty-means (:faculty t)) ").")))

(defmethod n/describe :people/reputation-known [{:keys [data]} _]
  (let [r (:reputation data)]
    (str (:acquaintance r) " " (str/join " " (map known-reading (mapcat val (:knows r)))))))

(defn room-for-arrival?
  "Is the run under its cap of active people, so someone more can arrive?"
  [world]
  (< (count (w/active-agents world)) (get-in world [:state :hiring-max-agents] b/default-max-agents)))

(defn can-arrive?
  "May an existing away person return by code? Disabled movement or
  unconsciousness keeps them away; impeded movement does not."
  [world id]
  (and (= :away (:status (w/agent world id)))
       (not (physical/held-in-place? world id))))

(defn send-for-reason
  "Why `actor` can't send for the candidate whose id is `id`, or nil."
  [world actor {:keys [id]}]
  (let [p (get (pool world) id)]
    (cond
      (nil? p) "nobody of that id has been recommended to you; ask someone who might know to `recommend` a candidate to you, then use the id it gives"
      (not (contains? (:known-to p) actor)) "you have no current recommendation for that candidate"
      (not (#{:pooled :away :returning} (:status p))) "that candidate has already been summoned or engaged"
      (#{:retired :dead} (:status (w/agent world (:agent-id p)))) "that person cannot return"
      (w/active? world (:agent-id p)) "that person is about already (a guest, perhaps); speak to them"
      (not (room-for-arrival? world)) "there is no room for another active person"
      (nil? (space/location world (get-in world [:state :meeting-place]))) "this world has no meeting place")))

(declare return-entries-cancelled)

(defn sent-for
  "`actor` sends for the candidate whose id is `id`, who comes next round,
  sooner than any return they planned (whose calendar entries are cancelled)."
  [world actor {:keys [id]}]
  (into [(changed id {:status :sent-for :arrival (inc (:round world)) :summoner actor
                             :return-at nil :return-entries nil})
         {:type :people/sent-for :visible-to #{actor} :data {:name (:name (get (pool world) id))
                                                             :at (get-in world [:state :meeting-place])}}]
        (return-entries-cancelled world (get (pool world) id))))
(defmethod n/describe :people/sent-for [{:keys [data]} names]
  (str "Word is sent to " (:name data) "; they are expected next round at " (n/place names (:at data)) "."))

(defn departure [world id reason]
  (let [p (for-agent world id)]
    (cond-> [{:type :people/departed :visible-to (space/here world id)
              :data {:id id :reason reason :towards (or (:candidate-home p) "home") :off-map? true}}
             {:type :people/offer-closed :visible-to :system :data {:who id}}]
      p (conj (changed (:id p) {:status :away :seen? true})))))
(defmethod n/describe :people/departed [{:keys [data]} names]
  (str (n/who names (:id data)) " leaves towards " (:towards data)
       (when (:off-map? data) " (outside the mapped area)") ": " (:reason data)
       (when-let [back (:returns data)] (str ", meaning to be back at " (n/place names (:at back)) " on " (:when back)))
       "."))
(defmethod n/describe :people/returned [{:keys [data]} names]
  (str (n/who names (:id data)) " returns to " (n/place names (:at data)) "."))

(doseq [t [:people/departed :people/returned :people/arrived]]
  (defmethod w/perceived-by t [_] :sight)
  (defmethod w/done-to t [{:keys [data]}] [(:id data)]))

(defn arrival
  "Someone arriving at `at` (the meeting place) from elsewhere, shared by
  hiring, deliveries and visits: {:id :events}. An away agent `id` returns
  there, seen by everyone there, themselves and the ids in `also` (or by
  `visible-to`, when given), with their :situation replaced if `situation`
  is given. If they cannot return (`can-arrive?`), no events are made.
  Without an `id`, a new agent is spawned there from `spawn` (a
  spec for b/spawn-events, without :location); the spawn holds a private
  persona and traits, so its events are never public."
  [scenario world at {:keys [id spawn situation also visible-to]}]
  (if id
    {:id id
     :events (if-not (can-arrive? world id)
               []
               (cond-> [{:type :people/returned :data {:id id :at at}
                         :visible-to (or visible-to (into (conj (space/present world at) id) (remove nil?) also))}]
                 situation (conj {:type :agent/updated :visible-to :system
                                  :data {:id id :changes {:situation situation}}})))}
    (let [spawned (b/spawn-events scenario world (assoc spawn :location at))]
      {:id (get-in (first spawned) [:data :agent :id])
       :events (mapv #(assoc % :visible-to :system) spawned)})))

(defn- arrival-events [scenario world p]
  (let [at (get-in world [:state :meeting-place])
        situation (str (if (= :returning (:status p))
                         (str "You have come back about the " (str/join ", " (map name (:seeks p)))
                              " place, as you said you would when you left (" (:name (w/agent world (:summoner p))) " first sent for you)")
                         (str "You have come about the " (str/join ", " (map name (:seeks p)))
                              " place, sent for by " (:name (w/agent world (:summoner p)))))
                       ". Talk over the terms; you may refuse, haggle, accept-place, or take-leave. "
                       "Remain locally available while a decision is pending: wait anywhere in the mapped area, "
                       "and tell the interviewer where you will be. Walking there does not end your application. "
                       "Use take-leave only when you mean to leave the area completely, not to wait nearby: you are gone until someone sends for you, "
                       "unless you give take-leave a return-date and return-time for when to come back again."
                       (if (:clock world)
                         "You leave automatically only after a full 24 hours without a conversation involving you. "
                         "You leave automatically only after 24 rounds without a conversation involving you. ")
                       (when-let [home (:candidate-home p)]
                         (str "Your home is " home ". It is outside the mapped area, not a walking destination. ")))
        returning? (= :away (:status (w/agent world (:agent-id p))))
        {:keys [id events]} (arrival scenario world at
                                     (if returning?
                                       {:id (:agent-id p) :situation situation :also [(:summoner p)]}
                                       {:spawn (merge (spawn-spec p) {:role :none :situation situation :household nil})}))
        audience (conj (space/present world at) id (:summoner p))]
    (vec (concat
          events
          [(changed (:id p) {:status :here :agent-id id :arrived (:round world) :return-at nil :return-entries nil})
           {:type :people/arrived :visible-to audience :data {:id id :at at :name (:name p)}}]))))
(defmethod n/describe :people/arrived [{:keys [data]} names]
  (str (:name data) " (" (:id data) ") arrives at " (n/place names (:at data)) " to talk about a place."))

(defn tick
  "Arrivals and idle departures before turns; each event batch sees the prior batch."
  [scenario world]
  (second
   (reduce
    (fn [[world events] p]
      (let [evs (cond
                  (and (#{:sent-for :returning} (:status p)) (<= (:arrival p) (:round world)) (room-for-arrival? world)
                       (or (nil? (:agent-id p)) (can-arrive? world (:agent-id p))))
                  (arrival-events scenario world p)
                  (and (= :here (:status p)) (w/active? world (:agent-id p))
                       (not (physical/held-in-place? world (:agent-id p)))
                       (>= (idle-rounds world (:agent-id p) (:arrived p)) (candidate-wait-rounds world)))
                  (departure world (:agent-id p) (if (:clock world)
                                                       "a day has passed without another conversation"
                                                       "24 rounds have passed without another conversation"))
                  :else [])]
        [(reduce w/apply-event world evs) (into events evs)]))
    [world []] (ordered world))))

(defn- present-candidate-reason [world actor who]
  (cond
    (not (and (w/active? world who) (= :here (:status (for-agent world who))))) "that person is not an unengaged candidate here"
    (not (space/together? world actor who)) "the candidate must be present"))

(defn- departure-reason [world actor who]
  (or (present-candidate-reason world actor who)
      (when (physical/held-in-place? world who)
        (str (:name (w/agent world who)) " cannot leave: "
             (if (physical/unconscious? world who)
               "they are unconscious; revive them first"
               "their movement is disabled; restore their movement first")))))

(defn engage-reason [world actor {:keys [who position bedroom]}]
  (or (present-candidate-reason world actor who)
      (when (= actor who) "you cannot offer yourself a place")
      (when-let [r (not-a-post-reason world position)]
        (if (= :guest (keyword position)) r "use a position id from the catalogue"))
      (when (and bedroom (not (space/home-location? world bedroom)))
        "bedroom must name an existing location in the household's home area; any room there may be assigned")))
(defn- offer-reason [world actor _]
  (let [offer (get-in world [:state :place-offers actor])]
    (or (when-not offer "nobody has offered you a place")
        (present-candidate-reason world actor actor)
        (when-not (and (w/active? world (:employer offer)) (space/together? world actor (:employer offer)))
          (str (:name (w/agent world (:employer offer))) " (" (:employer offer) ") made the offer and must be present to hear your answer. "
               "Use find-person and walk to them, or ask them to come to you; once together, accept-place to take the place or decline-place to refuse it.")))))

(defn accept [world actor {:keys [conditions]}]
  (let [{:keys [position household terms bedroom employer]} (get-in world [:state :place-offers actor])
        p (for-agent world actor)
        audience #{actor employer}]
    (cond-> [(positions/post-changed world actor position {:household household :terms terms :conditions conditions
                                                           :employer employer :visible-to audience})
             (changed (:id p) {:status :engaged})
             {:type :people/offer-closed :visible-to :system :data {:who actor}}
             {:type :agent/updated :visible-to :system
              :data {:id actor :changes {:situation (str "You have taken the " (name position) " place with "
                                                         (:name (w/agent world employer)) ". Your post records the agreed terms.")}}}]
      bedroom (conj (assoc (positions/bedroom-assigned actor bedroom) :visible-to audience)))))

(defmethod n/describe :people/offered [{:keys [data]} names]
  (str (n/who names (:employer data)) " offers " (n/who names (:who data)) " the " (name (:position data))
       " place" (when (:terms data) (str ": " (:terms data)))
       ". Answer with accept-place (optional conditions) or decline-place while together; you may discuss a different offer. "
       "The offerer must stay with the candidate until they answer; making the offer in a conversation with them lets them accept there."))
(defmethod n/describe :people/declined [{:keys [actor data]} names]
  (str (n/who names actor) " declines the place" (when (:reason data) (str ": " (:reason data))) "."))

(def text-arg [:string {:min 1 :max 1000}])

;; ---------------------------------------------------------------------------
;; Leaving with a return time

(def max-return-days
  "How far ahead someone taking leave may say they'll be back."
  7)

(def return-args
  "`take-leave`'s optional return time, both or neither."
  [:map [:return-date {:optional true} :string] [:return-time {:optional true} :string]])

(defn take-leave-doc [world]
  (prompts/render world "docs/hiring/take-leave.md"
                  {:example-date (prompts/example-date world) :max-return-days max-return-days}))

(defn return-at
  "The time someone taking leave gave for coming back, or nil."
  ^LocalDateTime [{:keys [return-date return-time]}]
  (try (LocalDateTime/of (LocalDate/parse (str return-date)) (LocalTime/parse (str return-time)))
       (catch Exception _ nil)))

(defn return-reason
  "Why `take-leave` can't have this return time, or nil when it has none or
  a good one. Shared with visits' `take-leave`."
  [world {:keys [return-date return-time] :as args}]
  (when (or return-date return-time)
    (let [at (return-at args)]
      (cond (not (:clock world)) "there is no clock here to come back by; leave without a return time"
            (not (and return-date return-time)) "give both return-date and return-time, or neither"
            (nil? at) (prompts/render world "docs/hiring/return-time-format.md"
                                      {:example-date (prompts/example-date world)})
            (not (.isAfter at (clock/time-of (:clock world) (:round world)))) "the return time must be later than now"
            (.isAfter at (.plusDays (clock/time-of (:clock world) (:round world)) max-return-days))
            (str "say you'll be back within " max-return-days " days")))))


(def calendar-owner
  "How hiring owns the calendar entries of a candidate's return (see
  manor.mechanics.calendar)."
  {:id-prefix "hiring-" :hint "it is kept by the candidate's return"})

(defn- return-entries
  "Calendar entries for candidate `p` coming back to the meeting place at
  `at`: their own, and one for everyone who knows them. [ids events]."
  [world p ^LocalDateTime at]
  (if-not (:calendar world)
    [[] []]
    (let [place (get-in world [:state :meeting-place])
          where (str (:name (space/location world place) place) " (" place ")")
          about (str "about the " (str/join ", " (map name (:seeks p))) " place")
          id (:agent-id p)
          stem (str "hiring-" (:id p) "-return-" (:round world))
          household (->> (conj (:known-to p) (:summoner p))
                         (remove #(or (nil? %) (= id %) (not (w/active? world %)) (= :gm (:role (w/agent world %)))))
                         set)
          specs (cond-> [[(str stem "-own") #{id} (str "You mean to come back to the " where " " about ".")]]
                  (seq household)
                  (conj [(str stem "-known") household (str (:name p) " (" id ") means to come back to the " where " " about ".")]))]
      [(mapv first specs)
       (vec (mapcat (fn [[entry-id audience text]]
                      (calendar/managed-command-events world :add {:id entry-id :at (str at) :visible-to audience
                                                                   :description text}
                                                       :hiring))
                    specs))])))

(defn- return-entries-cancelled [world p]
  (for [id (:return-entries p)
        :let [entry (get-in world [:calendar :entries id])]
        :when (and entry (not= :cancelled (get-in entry [:lifecycle :status])))
        e (calendar/managed-command-events world :cancel {:id id} :hiring)]
    e))

(defn leave-with-return
  "A candidate takes leave: gone, as `departure`, or with a return time,
  coming back to the meeting place then (status :returning), with calendar
  entries for them and those who know them."
  [world actor args]
  (let [p (for-agent world actor)
        at (return-at args)]
    (if-not at
      (departure world actor "takes leave")
      (let [[ids entries] (return-entries world p at)
            place (get-in world [:state :meeting-place])]
        (vec (concat
              (map #(cond-> % (= :people/departed (:type %))
                      (assoc-in [:data :returns] {:at place :when (clock/when-label world at)}))
                   (departure world actor "takes leave"))
              [(changed (:id p) {:status :returning :arrival (clock/round-of (:clock world) at)
                                 :return-at (str at) :return-entries ids})]
              entries))))))
(def actions
  [{:name :recommend :kind :query :roles :all
    :doc (prompts/doc "docs/hiring/recommend.md")
    :args [:map [:position :string] [:for :string]] :valid? recommend-reason
    :query-fn recommend! :on-result recommendation-events
    :reply (fn [_ _ _ {:keys [events]}]
             (if (seq events)
               (str/join "\n" (map #(n/describe % {}) (filter #(#{:people/recommended :people/reputation-known} (:type %)) events)))
               "No recommendation was made: the people or candidate pool are no longer ready."))}
   {:name :send-for :kind :final :roles :all
    :doc (prompts/doc "docs/hiring/send-for.md")
    :args [:map [:id :string]] :valid? send-for-reason :effect sent-for}
   {:name :engage :kind :free :roles :all
    :available? (fn [world actor] (boolean (some #(and (not= actor (:agent-id %)) (nil? (present-candidate-reason world actor (:agent-id %)))) (vals (pool world)))))
    :doc (prompts/doc "docs/hiring/engage.md")
    :args [:map [:who :string] [:position :string] [:terms {:optional true} text-arg] [:bedroom {:optional true} :string]]
    :valid? engage-reason
    :effect (fn [world actor args]
              [{:type :people/offered :visible-to #{actor (:who args)}
                :data (assoc args :position (keyword (:position args)) :employer actor
                             :household (or (get-in world [:state :posts actor :household]) positions/household))}])
    :reply (fn [world _ {:keys [who position]}]
             (str "You have offered " (:name (w/agent world who)) " the " position " place. Stay with them until they answer with accept-place or decline-place: you must be present to hear it. "
                  "Making the offer in a conversation with them lets them accept there."))}
   {:name :accept-place :kind :free :roles :all
    :available? (fn [world actor] (some? (get-in world [:state :place-offers actor])))
    :doc (prompts/doc "docs/hiring/accept-place.md")
    :args [:map [:conditions {:optional true} text-arg]] :valid? offer-reason :effect accept
    :reply (fn [world actor _]
             (str "You have taken the place: you are now the " (str/lower-case (:title (positions/position world actor) "")) "."))}
   {:name :decline-place :kind :free :roles :all
    :available? (fn [world actor] (some? (get-in world [:state :place-offers actor])))
    :doc (prompts/doc "docs/hiring/decline-place.md")
    :args [:map [:reason {:optional true} text-arg]] :valid? offer-reason
    :effect (fn [world actor args]
              [{:type :people/declined :visible-to #{actor (get-in world [:state :place-offers actor :employer])} :data args}
               {:type :people/offer-closed :visible-to :system :data {:who actor}}])}
   {:name :send-away :kind :final :roles :all
    :available? (fn [world actor] (boolean (some #(nil? (present-candidate-reason world actor (:agent-id %))) (vals (pool world)))))
    :doc (prompts/doc "docs/hiring/send-away.md")
    :args [:map [:who :string]]
    :valid? (fn [world actor {:keys [who]}] (departure-reason world actor who))
    :effect (fn [world _ {:keys [who]}] (departure world who "sent away"))}
   {:name :take-leave :kind :final :roles :all
    :available? (fn [world actor] (= :here (:status (for-agent world actor))))
    :doc take-leave-doc
    :args return-args
    :valid? (fn [world actor args] (or (departure-reason world actor actor) (return-reason world args)))
    :effect leave-with-return}])

(defn reference
  "What everyone may need and that rarely changes: the meeting place and the
  positions (anyone may be asked to recommend someone for one). For a
  scenario's :reference, where the prompt cache can reuse it."
  [world]
  {:meeting-place (get-in world [:state :meeting-place])
   :positions (mapv (fn [[id p]] {:id id :title (:title p) :sleeps (:sleeps p)})
                    (sort-by key (apply dissoc (get-in world [:state :positions]) not-posts)))})

(defn observe
  "Only known candidates and the viewer's reputations/agreements, never raw
  records. The meeting place and positions are in `reference`."
  [world id]
  {:candidates (mapv #(select-keys % [:id :name :seeks :status :agent-id :candidate-home :return-at])
                     (filter #(contains? (:known-to %) id) (ordered world)))
   :reputations (get-in world [:state :reputations id] {})
   :place-offers (filterv (fn [offer] (or (= id (:who offer)) (= id (:employer offer))))
                          (vals (get-in world [:state :place-offers])))
   :posts (into {} (filter (fn [[who post]] (or (= who id) (= id (:employer post))))
                           (get-in world [:state :posts])))})

(defn reference-activity
  "An ordinary writing job may name a known candidate. Snapshot only the
  writer's knowledge, never the candidate's record, for the scene judge."
  [spec]
  (-> spec
      (update :doc (fn [doc]
                     (fn [world]
                       (str (a/description {:doc doc} world) " "
                            (prompts/render world "docs/hiring/spend-time-candidate.md" {})))))
      (update :args conj [:candidate {:optional true} :string])
      (assoc :valid? (fn [world actor args]
                       (or ((:valid? spec) world actor args)
                           (when-let [id (not-empty (:candidate args))]
                             (when-not (contains? (:known-to (candidate world id)) actor)
                               "you must know of that candidate before writing their character"))))
             :effect (fn [world actor args]
                       (let [p (when-let [id (not-empty (:candidate args))] (candidate world id))
                             reference (when p {:candidate-id (:id p) :name (:name p) :seeks (:seeks p)
                                                :reputation (get-in world [:state :reputations actor (:id p)])})]
                         (mapv #(if (and reference (= :item/work-begun (:type %)))
                                  (-> % (assoc :visible-to :system) (assoc-in [:data :reference] reference)) %)
                               ((:effect spec) world actor args)))))))

(defn install
  "Add hiring to a scenario, with its spawn hook. Candidates arrive at the
  meeting place, which the scenario's :init puts at [:state :meeting-place]
  (manor.ruleset reads it from the world file's :meta :meeting-place)."
  [scenario]
  (let [init (:init scenario)]
    (-> scenario
        (cond-> (:calendar scenario) (assoc-in [:calendar :managed :hiring] calendar-owner))
        (assoc :init (fn [opts]
                       (update (init opts) :state merge
                               {:hiring-max-agents (get-in scenario [:limits :max-agents] b/default-max-agents)})))
        (cond-> (:items scenario)
          (update :actions (fn [actions]
                             (conj (vec (remove #(= :spend-time (:name %)) actions))
                                   (reference-activity (first (filter #(= :spend-time (:name %)) (item-actions/actions))))))))
        (update :systems #(conj (vec (remove (fn [s] (= :people/visits (:name s))) %))
                                {:name :people/visits :tick (fn [world _] (tick scenario world))})))))

(defn- rules-text
  "Hiring's rules, step by step for each side, with the template `arrivals`
  saying who may arrive."
  [world arrivals]
  (prompts/render world "rules/hiring.md" {:arrivals (prompts/render world arrivals {})}))

(defn rules [world] (rules-text world "rules/hiring-arrivals.md"))

(defn rules-beside-visits
  "Hiring's rules for a world with visits (manor.mechanics.visits), where
  guests arrive by invitation too."
  [world]
  (rules-text world "rules/hiring-arrivals-beside-visits.md"))
