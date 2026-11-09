(ns manor.mechanics.hiring
  "What finding and placing people share: making a new person (the people
  server's record, then the writers), reputations, arrival at the meeting
  place, place offers and the post they lead to, and leaving with a return
  time. `recommend`, `send-for`, `engage` and the answers are contacts'
  (manor.mechanics.contacts), on these parts. I/O runs through the engine's
  composed-query services; reducers never call out."
  (:require [clojure.string :as str]
            [manor.actions :as a]
            [manor.clock :as clock]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.items.actions :as item-actions]
            [manor.mechanics.items.belongings :as belongings]
            [manor.mechanics.people :as people]
            [manor.mechanics.physical :as physical]
            [manor.mechanics.positions :as positions]
            [manor.mechanics.space :as space]
            [manor.narrate :as n]
            [manor.prompts :as prompts]
            [manor.settings :as settings]
            [manor.world :as w])
  (:import (java.time LocalDate LocalDateTime LocalTime)))

(def bridges
  {:people/generate {:mcp {:server :people :tool "generate_people"} :max-chars 20000}
   :people/reputation {:mcp {:server :people :tool "reputation"} :max-chars 20000}})

(def offline-stubs
  "Free fixtures, not a second production generator. Never start a process offline."
  {:people/generate
   (fn [{:keys [position sex ages taken-names]} _]
     {:people [{:name (first (remove (set taken-names) (map #(str "Pat Ellis " %) (range))))
                :age (first (or ages [22 40])) :sex (or sex "female") :position position
                :origin "a place not far off"
                :lives "lodgings nearby"
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
                   :request (fn [world _ {:keys [record appearance notes belongings-mode arrival-context]} _]
                              (people/polish-request world {:setting (get-in world [:state :setting])
                                                      :position (:position record) :record record
                                                      :belongings-mode belongings-mode :arrival-context arrival-context
                                                      :notes (str (when-not (str/blank? notes) (str notes "\n\n"))
                                                                  "Keep these visible physical facts consistent: " appearance)}))
                   :mock people/mock-polish}})

(defn place-area
  "The people server's area for `place`: a walking area, not a domain, so
  Lodge's shop and house share one."
  [world place]
  (first (sort (space/area world place))))
(defn taken-names
  "Names already in use: everyone spawned (contacts among them), and the
  shops' couriers, who are named before they first appear
  (manor.mechanics.deliveries)."
  [world]
  (concat (map :name (vals (:agents world)))
          (keep (comp :name :person) (vals (get-in world [:state :couriers])))))
(defn contact-of
  "The contact (manor.mechanics.contacts) whose person is `id`, or nil.
  Here so visits can read contacts without requiring them."
  [world id]
  (first (filter #(= id (:agent-id %)) (sort-by :created (vals (get-in world [:state :contacts]))))))

(defmethod w/apply-event :people/reputation-known [world {:keys [actor data]}]
  (assoc-in world [:state :reputations actor (:id data)] (:reputation data)))
(defmethod w/apply-event :people/offered [world {:keys [data]}]
  (assoc-in world [:state :place-offers (:who data)] data))
(defmethod w/apply-event :people/offer-closed [world {:keys [data]}]
  (update-in world [:state :place-offers] dissoc (:who data)))

(def ^:private not-posts
  "Catalogue positions nobody is hired for: no post at all, the GM, a
  visitor (manor.mechanics.visits) and a caller sent for
  (manor.mechanics.contacts). Recommend and engage refuse them, and
  `reference` doesn't list them."
  #{:none :gm :guest :caller})

(defn not-a-post-reason
  "Why `position` can't be filled by hiring, or nil."
  [world position]
  (let [position (keyword position)]
    (cond (not (get-in world [:state :positions position])) "use a position id from the catalogue"
          (= :guest position) "guest is not a post; use the id of a position to fill"
          (not-posts position) "name a character's position to fill")))

(defn recipient-reason
  "Why `actor` can't recommend anyone to `for`, or nil: they must be someone
  else, here. Shared with contacts (manor.mechanics.contacts)."
  [world actor for]
  (cond
    (= actor for) "ask someone else here to recommend to you, not yourself"
    (not (and (w/active? world for) (not= :gm (:role (w/agent world for)))
              (space/together? world actor for))) "the person asking must be here"))

(defn- check-record [record]
  (when-not (and (map? record) (string? (:name record)) (not (str/blank? (:name record)))
                 (integer? (:age record)) (#{"male" "female" :male :female} (:sex record))
                 (string? (:position record))
                 (every? sequential? (map record [:temperament :faculties])))
    (throw (ex-info "The people server returned an invalid person." {})))
  (people/base-record record))

(defn person-record
  "The first, free half of a new person for `position`: the people server's
  record, {:name :record :lives}. `sex` and `ages` ([youngest oldest]), if
  given, replace the position's. No model call."
  [world {:keys [bridge]} {:keys [position area seed taken-names sex ages]}]
  (let [record (check-record
                (first (:people (bridge :people/generate
                                        (assoc (people/generate-args position (get-in world [:state :positions position])
                                                                     {:dataset (people/dataset (settings/pack world))
                                                                      :seed seed :taken-names taken-names :sex sex :ages ages})
                                               :area area)))))]
    (merge {:name (:name record) :record record} (select-keys record [:lives]))))

(defn written? [p] (some? (:persona p)))

(defn write-person
  "The second half: a public appearance and a private polish for person `p`
  from `person-record`, adding :persona :manner :appearance and the record's
  circumstances, and the polish's :goals and :aspects if it gave any. `notes`,
  if given, go to the polish (who they are, for a guest written to order).
  Explicit first-arrival options preserve checked descriptions privately as
  :pending-belongings, never as traits or public appearance input."
  ([services p] (write-person services p nil))
  ([services p notes] (write-person services p notes {}))
  ([{:keys [adjudicate]} {:keys [record] :as p} notes {:keys [belongings-mode arrival-context]}]
   (let [public (adjudicate :people/appearance {:record record})
         enabled? (= :first-arrival belongings-mode)
         polish (adjudicate :people/polish (cond-> {:record record :appearance (:appearance public)}
                                             notes (assoc :notes notes)
                                             enabled? (assoc :belongings-mode belongings-mode :arrival-context arrival-context)))]
     (cond-> (merge (assoc-in p [:record :circumstances] (:circumstances polish))
                    (select-keys polish [:persona :manner :goals :aspects]) public)
       enabled? (assoc :pending-belongings (:belongings polish))))))

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

(defmethod n/describe :people/departed [{:keys [data]} names]
  (str (n/who names (:id data)) " leaves towards " (:towards data)
       (when (:off-map? data) " (outside the mapped area)") ": " (:reason data)
       (when-let [back (:returns data)] (str ", meaning to be back at " (n/place names (:at back)) " on " (:when back)))
       "."))
(defmethod n/describe :people/returned [{:keys [data]} names]
  (str (n/who names (:id data)) " returns to " (n/place names (:at data)) "."))

(doseq [t [:people/departed :people/returned]]
  (defmethod w/perceived-by t [_] :sight)
  (defmethod w/done-to t [{:keys [data]}] [(:id data)]))

(defn arrival
  "Someone arriving at `at` (the meeting place) from elsewhere, shared by
  deliveries and visits: {:id :events}. An away agent `id` returns
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

(defmethod w/apply-event :people/belongings-brought [world {:keys [data]}]
  (update-in world [:state :contacts (:contact data)] dissoc :pending-belongings))

(defn first-arrival-events
  "The things contact `c`'s person `id` brings the first time they come, from
  the descriptions written with them (:pending-belongings), carried and
  seen only by them; a paper marked as their reference carries the
  contact's id as its :contact-id. Then the descriptions are gone, so
  coming again brings nothing more. `world` has them arrived. Nil if there
  are none waiting."
  [world id c]
  (when (contains? c :pending-belongings)
    (conj (belongings/arrival-events world id (:pending-belongings c) {:contact-id (:id c)})
          {:type :people/belongings-brought :visible-to :system :data {:contact (:id c)}})))

(defn held-reason
  "Why `who` can't be sent off now (unconscious, or their movement
  disabled), or nil."
  [world who]
  (when (physical/held-in-place? world who)
    (str (:name (w/agent world who)) " cannot leave: "
         (if (physical/unconscious? world who)
           "they are unconscious; revive them first"
           "their movement is disabled; restore their movement first"))))

(defn offer-terms-reason
  "Why `actor` can't offer `who` this place, whoever `who` is, or nil:
  not oneself, a catalogue position, a bedroom in the home area. Shared
  with contacts."
  [world actor {:keys [who position bedroom]}]
  (or (when (= actor who) "you cannot offer yourself a place")
      (when-let [r (not-a-post-reason world position)]
        (if (= :guest (keyword position)) r "use a position id from the catalogue"))
      (when (and bedroom (not (space/home-location? world bedroom)))
        "bedroom must name an existing location in the household's home area; any room there may be assigned")))

(defn- offerer-reason
  "Why `actor` can't answer their offer now: its offerer isn't here."
  [world actor]
  (let [offer (get-in world [:state :place-offers actor])]
    (when-not (and (w/active? world (:employer offer)) (space/together? world actor (:employer offer)))
      (str (:name (w/agent world (:employer offer))) " (" (:employer offer) ") made the offer and must be present to hear your answer. "
           "Use find-person and walk to them, or ask them to come to you; once together, accept-place to take the place or decline-place to refuse it."))))

(defn answer-reason
  "Why `actor` can't answer their offer, given why they aren't someone who
  may be offered one (`unplaced-reason`, nil if they are), or nil. Shared
  with contacts."
  [world actor unplaced-reason]
  (or (when-not (get-in world [:state :place-offers actor]) "nobody has offered you a place")
      unplaced-reason
      (offerer-reason world actor)))

(defn place-taken
  "`actor` takes the place they were offered: the post, the offer closed,
  their situation and any bedroom. Contacts adds the end of their visit."
  [world actor {:keys [conditions]}]
  (let [{:keys [position household terms bedroom employer]} (get-in world [:state :place-offers actor])
        audience #{actor employer}]
    (cond-> [(positions/post-changed world actor position {:household household :terms terms :conditions conditions
                                                           :employer employer :visible-to audience})
             {:type :people/offer-closed :visible-to :system :data {:who actor}}
             {:type :agent/updated :visible-to :system
              :data {:id actor :changes {:situation (str "You have taken the " (name position) " place with "
                                                         (:name (w/agent world employer)) ". Your post records the agreed terms.")}}}]
      bedroom (conj (assoc (positions/bedroom-assigned actor bedroom) :visible-to audience)))))

(defmethod n/describe :people/offered [{:keys [data]} names]
  (str (n/who names (:employer data)) " offers " (n/who names (:who data)) " the " (name (:position data))
       " place" (when (:terms data) (str ": " (:terms data)))
       ". Answer with accept-place (optional conditions) or decline-place while together; you may discuss a different offer. "
       "The offerer must stay with them until they answer; making the offer in a conversation with them lets them accept there."))
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


(defn reference
  "What everyone may need and that rarely changes: the meeting place and the
  positions (anyone may be asked to recommend someone for one). For a
  scenario's :reference, where the prompt cache can reuse it."
  [world]
  {:meeting-place (get-in world [:state :meeting-place])
   :positions (mapv (fn [[id p]] {:id id :title (:title p) :sleeps (:sleeps p)})
                    (sort-by key (apply dissoc (get-in world [:state :positions]) not-posts)))})

(defn observe
  "The viewer's reputations and agreements, never raw records. The meeting
  place and positions are in `reference`; the people they've been told of
  are contacts' (manor.mechanics.contacts/add-to-view)."
  [world id]
  {:reputations (get-in world [:state :reputations id] {})
   :place-offers (filterv (fn [offer] (or (= id (:who offer)) (= id (:employer offer))))
                          (vals (get-in world [:state :place-offers])))
   :posts (into {} (filter (fn [[who post]] (or (= who id) (= id (:employer post))))
                           (get-in world [:state :posts])))})

(defn reference-activity
  "An ordinary writing job may name a contact the writer knows of (a
  character for someone sent for a place). Snapshot only the writer's
  knowledge, never the contact's record, for the scene judge; what's made
  carries the contact's id as its :contact-id. Contacts live in
  [:state :contacts] (manor.mechanics.contacts, which requires this)."
  [spec]
  (let [contact (fn [world args] (when-let [id (not-empty (:contact args))] (get-in world [:state :contacts id])))]
    (-> spec
        (update :doc (fn [doc]
                       (fn [world]
                         (str (a/description {:doc doc} world) " "
                              (prompts/render world "docs/hiring/spend-time-contact.md" {})))))
        (update :args conj [:contact {:optional true} :string])
        (assoc :valid? (fn [world actor args]
                         (or ((:valid? spec) world actor args)
                             (when (not-empty (:contact args))
                               (when-not (contains? (:known-to (contact world args)) actor)
                                 "you must know of that contact through a recommendation before writing their character"))))
               :effect (fn [world actor args]
                         (let [c (contact world args)
                               reference (when c {:contact-id (:id c) :name (:name c) :line (:line c)
                                                  :reputation (get-in world [:state :reputations actor (:id c)])})]
                           (mapv #(if (and reference (= :item/work-begun (:type %)))
                                    (-> % (assoc :visible-to :system) (assoc-in [:data :reference] reference)) %)
                                 ((:effect spec) world actor args))))))))

(defn install
  "Add hiring's shared state to a scenario: the cap on active people for
  arrivals, whether people new from elsewhere bring first-arrival belongings
  (an items-enabled scenario), and `spend-time` able to write a contact's
  character. People arrive at the meeting place, which the scenario's :init
  puts at [:state :meeting-place] (manor.ruleset reads it from the world
  file's :meta :meeting-place)."
  [scenario]
  (let [init (:init scenario)]
    (-> scenario
        (assoc :init (fn [opts]
                       (update (init opts) :state merge
                               {:hiring-max-agents (get-in scenario [:limits :max-agents] b/default-max-agents)
                                ;; An items-enabled scenario may have no actual items yet.
                                :hiring-belongings? (boolean (:items scenario))})))
        (cond-> (:items scenario)
          (update :actions (fn [actions]
                             (conj (vec (remove #(= :spend-time (:name %)) actions))
                                   (reference-activity (first (filter #(= :spend-time (:name %)) (item-actions/actions)))))))))))
