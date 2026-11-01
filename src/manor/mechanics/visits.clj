(ns manor.mechanics.visits
  "Visits: a guest expected at the house at a date and time (see
  docs/visits-design.md). A visit is one engine-owned record in
  [:state :visits]. Every source (the `invite` action, the GM's
  `schedule-visit`, the operator's `engine/schedule-visit!`, a world file's
  authored :visits at the start) makes the same record, and a system tick
  moves it on before turns. Whether the guest is in the world or away is
  decided when it falls due. A guest in the world
  is told, makes their own way (:here), goes home (:ended), or doesn't come
  (:lapsed); nobody moves them. An away guest (someone from elsewhere)
  arrives at the meeting place at the time, and goes with `take-leave`.
  Someone described in `invite`'s `person` is settled by the invitation
  judge (manor.mechanics.visits.judge), and someone new is made an away
  agent in the same query.
  Their calendar entries, the host's and the guest's, are written from the
  record and owned by it (:managed-by :visits), so they're never GM-reviewed
  and only visits change them. A scenario with :hibernate keeps an expected
  guest in play by asking `keeps-awake?`."
  (:require [clojure.string :as str]
            [manor.clock :as clock]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.calendar :as calendar]
            [manor.mechanics.hiring :as hiring]
            [manor.mechanics.physical :as physical]
            [manor.mechanics.positions :as positions]
            [manor.mechanics.space :as space]
            [manor.mechanics.visits.judge :as judge]
            [manor.narrate :as n]
            [manor.prompts :as prompts]
            [manor.world :as w])
  (:import (java.time LocalDate LocalDateTime LocalTime)
           (java.time.format DateTimeFormatter)
           (java.util Locale)))

(def default-config
  "Lead times are the shortest gap between sending an invitation and the
  guest being expected. A guest is kept in play from :wake-from on the
  visit's day, and no longer expected :lapse-hours after its time.
  :from-away-hours is added to an invitation's lead time when the guest is
  away (the letter and their journey). :house names the house in
  invitations; `install` takes it from the world file's :meta :name."
  {:lead-hours {:in-person 0 :messenger 1 :post 3}
   :from-away-hours 24
   :wake-from "06:00"
   :lapse-hours 3})

(def max-stay 200)
(def max-reason 300)

;; ---------------------------------------------------------------------------
;; Queries

(defn config [world] (get-in world [:state :visit-config]))
(defn visits [world] (get-in world [:state :visits] {}))
(defn visit [world id] (get (visits world) id))

(defn- ordered-ids [world] (sort-by judge/visit-number (keys (visits world))))

(defn- meeting-place [world] (get-in world [:state :meeting-place]))
(defn- house [world] (:house (config world) "the house"))

(defn house-area
  "The meeting place's walking area: the carriage sweep and every room."
  [world]
  (if-let [place (meeting-place world)] (space/area world place) #{}))

(defn- now ^LocalDateTime [world] (clock/time-of (:clock world) (:round world)))

(defn- newest-here
  "`guest`'s newest visit that is :here, or nil."
  [world guest]
  (last (filter #(and (= guest (:guest %)) (= :here (:status %))) (map #(visit world %) (ordered-ids world)))))

(defn visiting?
  "Is `id` a guest here now: one of their visits is :here?"
  [world id]
  (some? (newest-here world id)))

(defn from-elsewhere?
  "Is `id` a guest from elsewhere here now: one of their :here visits began
  with their arrival at the meeting place?"
  [world id]
  (boolean (some #(and (= id (:guest %)) (= :here (:status %)) (:from-away? %)) (vals (visits world)))))

(def round-of
  "The first round starting at or after a time (manor.clock/round-of)."
  clock/round-of)

(defn- rounds-for-hours [world hours] (clock/rounds-for (:clock world) (* 60 hours)))

(defn- parse-time [date time]
  (try (LocalDateTime/of (LocalDate/parse (str date)) (LocalTime/parse (str time)))
       (catch Exception _ nil)))

(defn- fmt [pattern] (DateTimeFormatter/ofPattern pattern Locale/ENGLISH))
(defn- when-label
  "\"Friday 21 October at 19:00\" for a visit's :at, in the world's setting."
  [world v]
  (clock/when-label world (LocalDateTime/parse (:at v))))

(defn- agent-name [world id] (:name (w/agent world id) id))

(defn- awake-for?
  "Is visit `v` keeping its guest in play now: told, and from its :wake round
  until it arrives, then all the while it's :here?"
  [world v]
  (or (= :here (:status v))
      (and (= :scheduled (:status v)) (:told? v) (>= (:round world) (:wake v)))))

(defn keeps-awake?
  "Is a visit keeping `id` in play now? For a scenario's :hibernate: an
  invited guest in the world is kept out of hibernation all day from
  :wake-from (or the day before, for a visit too early to reach from
  then), once told, through the visit, until it ends or lapses."
  [world id]
  (boolean (some #(and (= id (:guest %)) (awake-for? world %)) (vals (visits world)))))

;; ---------------------------------------------------------------------------
;; Times

(defn- lead-minutes [world by]
  (long (Math/ceil (* 60 (get-in (config world) [:lead-hours by] 0)))))

(defn- away? [world id] (= :away (:status (w/agent world id))))

(defn- earliest
  "The earliest time a guest invited now `by` a channel can be expected, with
  :from-away-hours more for a guest who is away (`away?`)."
  ^LocalDateTime [world by away?]
  (.plusMinutes (now world) (+ (lead-minutes world by)
                               (if away? (long (* 60 (:from-away-hours (config world)))) 0))))

(defn- next-round-time
  "The time of the next round: the GM's and operator's only lead time."
  ^LocalDateTime [world]
  (clock/time-of (:clock world) (inc (:round world))))

(defn- journey-minutes
  "How long `guest` takes from where they are (or are going) to the house."
  [world guest]
  (let [a (w/agent world guest)
        from (or (:location a) (get-in a [:travel :to]))
        rounds (when (and from (meeting-place world)) (space/distance world from (meeting-place world)))]
    (* (or rounds 0) (:minutes-per-round (:clock world)))))

(defn- wake-round
  "From when a visit at `at` keeps `guest` in play: :wake-from on its day, or
  the day before if that leaves too little time to get there (their journey
  and an hour)."
  [world guest ^LocalDateTime at]
  (let [day (.atTime (.toLocalDate at) (LocalTime/parse (:wake-from (config world))))
        late? (.isBefore at (.plusMinutes day (+ (journey-minutes world guest) 60)))]
    (round-of (:clock world) (if late? (.minusDays day 1) day))))

;; ---------------------------------------------------------------------------
;; Calendar entries, owned by the visit

(defn- entry-id [v side] (str (:id v) "-" (name side)))

(defn- clip
  "`s` cut to the calendar's 600 characters."
  [s]
  (if (<= (count s) 600) s (str (subs s 0 599) "…")))

(defn- with-reason [s v] (str s (when (:reason v) (str " " (:reason v)))))

(defn- host-audience
  "Who the host side's entry is for: the inviter, or a GM visit's known-to."
  [v]
  (if (:host v) #{(:host v)} (set (:known-to v))))

(defn- entry-events [world v side audience description]
  (when (and (:calendar world) (seq audience)
             (not (.isBefore (LocalDateTime/parse (:at v)) (now world))))
    (calendar/managed-command-events world :add {:id (entry-id v side) :at (:at v) :visible-to audience
                                                  :description (clip description)}
                                     :visits)))

(defn- sent-for? [v] (= :sent-for (:by v)))

(defn- host-entry [world v]
  (entry-events world v :host (host-audience v)
                (with-reason (str (agent-name world (:guest v)) " (" (:guest v) ") "
                                  (if (:returning? v) "is coming back to " "is expected at ") (house world)
                                  (cond (sent-for? v) ", sent for by you: "
                                        (and (:host v) (not (:returning? v))) ", invited by you: "
                                        :else ": ")
                                  (:stay v) ".")
                  v)))

(defn- guest-entry [world v]
  (let [place (meeting-place world)
        where (str (house world) ", at the " (:name (space/location world place) place) " (" place ")")]
    (entry-events world v :guest #{(:guest v)}
                  (with-reason (cond (:returning? v) (str "You mean to come back to " where ": " (:stay v) ".")
                                     (sent-for? v) (str "You are wanted at " where ", sent for by " (agent-name world (:host v)) ": " (:stay v) ".")
                                     (:host v) (str "You are expected at " where ", invited by " (agent-name world (:host v)) ": " (:stay v) ".")
                                     :else (str "You mean to call at " where ": " (:stay v) "."))
                    v))))

(defn- cancelled-entries [world v]
  (for [side [:host :guest]
        :let [id (entry-id v side)
              entry (get-in world [:calendar :entries id])]
        :when (and entry (not= :cancelled (get-in entry [:lifecycle :status])))
        e (calendar/managed-command-events world :cancel {:id id} :visits)]
    e))

;; ---------------------------------------------------------------------------
;; Events

(defn- apply-all [world events] (reduce w/apply-event world events))

(defn- scheduled-events
  "The new visit's record, as a GM-only event, and the calendar entries due
  now: the host's at once, and the guest's if they're told now (`:deliver`
  is this round)."
  [world {:keys [guest at deliver known-to by via] :as spec}]
  (let [v (cond-> (merge (select-keys spec [:guest :host :by :via :stay :reason :bedroom :returning?])
                         {:id (str "visit-" (inc (count (visits world))))
                          :at (str at) :arrive (round-of (:clock world) at) :deliver deliver
                          :wake (wake-round world guest at) :status :scheduled
                          ;; Someone away hears of it only if they come back before it's due.
                          :told? (and (<= deliver (:round world)) (w/active? world guest)
                                       (or (not (from-elsewhere? world guest))
                                           (= :in-person by) (= guest via) (= :returning by)))})
            (seq known-to) (assoc :known-to (set known-to)))
        scheduled {:type :visits/scheduled :visible-to :gm :data v}
        world' (w/apply-event world scheduled)]
    (vec (concat [scheduled] (host-entry world' v) (when (:told? v) (guest-entry world' v))))))

(defn- status-event [type v] {:type type :visible-to :gm :data {:visit (:id v)}})

(defmethod w/apply-event :visits/scheduled [world {:keys [data]}]
  (assoc-in world [:state :visits (:id data)] data))
(defmethod w/apply-event :visits/delivered [world {:keys [data]}]
  (assoc-in world [:state :visits (:visit data) :told?] true))
(defmethod w/apply-event :visits/reminded [world {:keys [data]}]
  (assoc-in world [:state :visits (:visit data) :reminded?] true))
(defmethod w/apply-event :visits/arrived [world {:keys [round data]}]
  (update-in world [:state :visits (:visit data)]
             #(cond-> (assoc % :status :here :arrived round) (or (:at data) (:from-away? data)) (assoc :from-away? true))))
(doseq [[type status] {:visits/ended :ended :visits/lapsed :lapsed :visits/cancelled :cancelled}]
  (defmethod w/apply-event type [world {:keys [data]}]
    (assoc-in world [:state :visits (:visit data) :status] status)))

(defn- invitation-data [world v]
  (merge (select-keys v [:guest :host :via :stay :reason])
         {:visit (:id v) :house (house world) :expected (when-label world v)}))

(defn- invitation-text [{:keys [house expected stay reason]}]
  (str house " on " expected ": " stay "." (when reason (str " " reason))))

(defmethod n/describe :visits/invited [{:keys [data]} names]
  (str (n/who names (:host data)) " invites " (n/who names (:guest data)) " to " (invitation-text data)))
(defmethod n/describe :visits/posted [{:keys [data]} names]
  (str (n/who names (:host data)) " posts a letter."))
(defmethod n/describe :visits/entrusted [{:keys [data]} names]
  (str (n/who names (:host data)) " gives " (n/who names (:via data)) " a message for "
       (n/who names (:guest data)) ": an invitation to " (invitation-text data)))
(defmethod n/describe :visits/delivered [{:keys [data]} names]
  (cond (nil? (:host data)) (str "You mean to call at " (invitation-text data))
        (:via data) (str "A message comes from " (n/who names (:host data)) " (sent with " (n/who names (:via data))
                         "): you are invited to " (invitation-text data))
        :else (str "A letter comes from " (n/who names (:host data)) ": you are invited to " (invitation-text data))))
(defmethod n/describe :visits/reminded [{:keys [data]} names]
  (str "You have an engagement: " (:house data) " at " (n/at names (:arrive data))
       (when (:host data) (str " (" (n/who names (:host data)) "'s invitation)")) "."))
(defmethod n/describe :visits/scheduled [{:keys [data]} names]
  (str "Visit " (:id data) ": " (n/who names (:guest data)) " is expected at " (:at data)
       (cond
         (:returning? data) ", coming back as they said when they left"
         (= :sent-for (:by data)) (str ", sent for by " (n/who names (:host data)))
         (:host data)
         (str ", invited by " (n/who names (:host data)) " (" (name (:by data)) ")")
         :else (if (= :authored (:by data)) ", from the world file" (str ", arranged by the " (name (:by data)))))
       ": " (:stay data) "."))
(defmethod n/describe :visits/arrived [{:keys [data]} names]
  (let [{:keys [guest at known-as visitor?]} data]
    (cond (nil? at) (str "The guest of " (:visit data) " has come.")
          visitor? (str "A visitor arrives at the " (n/place names at) ": " (n/who names guest) " (" guest ")"
                        (when known-as (str ", " known-as)) ".")
          :else (str (n/who names guest) " (" guest ") arrives at the " (n/place names at) "."))))
(defmethod n/describe :visits/ended [{:keys [data]} _] (str (:visit data) " has ended: the guest has gone."))
(defmethod n/describe :visits/lapsed [{:keys [data]} _] (str (:visit data) " has lapsed: the guest didn't come."))
(defmethod n/describe :visits/cancelled [{:keys [data]} _] (str (:visit data) " is cancelled."))

(doseq [t [:visits/posted :visits/entrusted :visits/arrived]]
  (defmethod w/perceived-by t [_] :sight))
(defmethod w/perceived-by :visits/invited [_] :hearing)
(defmethod w/done-to :visits/arrived [{:keys [data]}] [(:guest data)])

(defn- channel-event
  "What people see of the invitation being sent: the invitation itself (in
  person), a letter posted (no contents), or the message given to the messenger."
  [world actor v]
  (case (:by v)
    :in-person {:type :visits/invited :visible-to #{actor (:guest v)} :data (invitation-data world v)}
    :post {:type :visits/posted :visible-to (space/here world actor) :data {:visit (:id v) :host actor}}
    :messenger {:type :visits/entrusted :visible-to #{actor (:via v)} :data (invitation-data world v)}))

;; ---------------------------------------------------------------------------
;; The tick: before turns, a visit at a time in id order, each step on the
;; world the previous one left.

(defn- staying
  "A visit falling due while its guest is here from elsewhere already (come
  for another visit, this round or before) is :here at once, with no arrival
  and no letter, and its named bedroom is theirs."
  [world v]
  (when (and (= :scheduled (:status v)) (>= (:round world) (:arrive v))
             (w/active? world (:guest v)) (from-elsewhere? world (:guest v)))
    (cond-> [{:type :visits/arrived :visible-to :gm :data {:visit (:id v) :from-away? true}}]
      (:bedroom v) (conj (positions/bedroom-assigned (:guest v) (:bedroom v))))))

(defn- telling
  "A guest invited by post or messenger learns of it at :deliver, if they're
  about. One here from elsewhere isn't: the word went to where they live, and
  the visit is theirs when it falls due (`staying`)."
  [world v]
  (when (and (= :scheduled (:status v)) (not (:told? v)) (>= (:round world) (:deliver v))
             (w/active? world (:guest v)) (not (from-elsewhere? world (:guest v))))
    (into [{:type :visits/delivered :visible-to #{(:guest v)} :data (invitation-data world v)}]
          (guest-entry world v))))

(defn- reminding
  "When a visit first keeps a guest who has a routine in play, wake them if
  they're hibernating and remind them privately of the engagement."
  [world v]
  (let [guest (:guest v)
        busy (w/busy world guest)]
    (when (and (= :scheduled (:status v)) (not (:reminded? v)) (w/active? world guest)
               (seq (get-in world [:state :routines guest])) (awake-for? world v))
      (conj (if (and (:hibernating? busy) (not (:interrupted-by busy))) [(b/free world guest)] [])
            {:type :visits/reminded :visible-to #{guest}
             :data {:visit (:id v) :house (house world) :arrive (:arrive v) :host (:host v)}}))))

(defn- arriving
  "A told guest in the house's area, from an hour before the time, has come."
  [world v]
  (when (and (= :scheduled (:status v)) (:told? v) (w/active? world (:guest v))
             (contains? (house-area world) (space/location-of world (:guest v)))
             (>= (:round world) (- (:arrive v) (rounds-for-hours world 1))))
    [(status-event :visits/arrived v)]))

(defn- position-title [world position]
  (str/lower-case (get-in world [:state :positions position :title] (name position))))

(defn- returning-situation
  "A short situation, from their record, for a former candidate or courier
  arriving as a guest, in place of the one hiring or deliveries gave them
  (\"You have come about the cook place…\"). A candidate whose pool record
  was evicted (role :none, no record left) gets a plain one. Nil for anyone
  else, who keeps their own."
  [world guest]
  (let [p (hiring/for-agent world guest)
        [shop c] (first (filter #(= guest (:agent-id (val %))) (sort-by key (get-in world [:state :couriers]))))
        lives (or (:candidate-home p) (:lives p) (get-in c [:person :lives]) (:lives (w/agent world guest)))
        you (str "You are " (agent-name world guest) (when lives (str ", of " lives)))]
    (cond p (str you ". You came to " (house world) " once before, about the "
                 (str/join ", " (map #(position-title world %) (:seeks p))) " place.")
          c (str you "; you carry for the " (:name (space/location world shop) shop) ".")
          (= :none (:role (w/agent world guest))) (str you ". You have been to " (house world) " before."))))

(defn- coming
  "An away guest, once the visit is due, arrives at the meeting place, if
  there's room for another active person (else they wait): seen by those
  there, the host wherever they are, and themselves. The named bedroom is theirs."
  [scenario world v]
  (let [guest (:guest v)
        at (meeting-place world)]
    (when (and (= :scheduled (:status v)) (>= (:round world) (:arrive v)) (hiring/can-arrive? world guest)
               (space/location world at) (hiring/room-for-arrival? world))
      (let [{:keys [events]} (hiring/arrival scenario world at {:id guest :visible-to :system
                                                               :situation (returning-situation world guest)})]
        (cond-> (conj events {:type :visits/arrived
                              :visible-to (into (conj (space/present world at) guest) (remove nil?) [(:host v)])
                              :data (cond-> {:visit (:id v) :guest guest :at at
                                             :visitor? (not (positions/household-member? world guest))}
                                      (:known-as (w/agent world guest)) (assoc :known-as (:known-as (w/agent world guest))))})
          (:bedroom v) (conj (positions/bedroom-assigned guest (:bedroom v))))))))

(defn- ending
  "A guest from the village no longer in, or heading to, the house's area
  has gone home. A guest from elsewhere goes with `take-leave`, which ends
  the visit itself; here, only if they've gone some other way."
  [world v]
  (let [a (w/agent world (:guest v))
        area (house-area world)]
    (when (and (= :here (:status v))
               (not (and (= :active (:status a))
                         (or (:from-away? v)
                             (contains? area (:location a)) (contains? area (get-in a [:travel :to]))))))
      [(status-event :visits/ended v)])))

(defn- lapsing
  "A guest who is retired, or is about and hasn't come :lapse-hours after
  the time, isn't expected. An away guest waits to arrive instead."
  [world v]
  (let [status (:status (w/agent world (:guest v)))]
    (when (and (= :scheduled (:status v))
               (or (#{:retired :dead} status)
                   (and (= :active status)
                        (>= (:round world) (+ (:arrive v) (rounds-for-hours world (:lapse-hours (config world))))))))
      [(status-event :visits/lapsed v)])))

(defn- visit-steps [scenario world id]
  (reduce (fn [[world events] step]
            (let [evs (step world (visit world id))]
              [(apply-all world evs) (into events evs)]))
          [world []] [staying telling reminding arriving (partial coming scenario) ending lapsing]))

(defn tick
  "Every visit's events this round. `scenario` is for arrivals at the meeting place."
  [scenario world]
  (second (reduce (fn [[world events] id]
                    (let [[world evs] (visit-steps scenario world id)] [world (into events evs)]))
                  [world []] (ordered-ids world))))

;; ---------------------------------------------------------------------------
;; Checks

(defn guest-reason
  "Why `guest` can't be invited, or nil. Someone away can be: they come from
  elsewhere. Someone of the household can't while they're at home."
  [world guest]
  (let [a (w/agent world guest)]
    (cond (or (nil? a) (= :gm (:role a))) (str "no one has the id " guest)
          (#{:retired :dead} (:status a)) (str (:name a) " cannot come")
          (and (= :active (:status a)) (positions/household-member? world guest)) (str (:name a) " lives here"))))

(defn- time-reason [world ^LocalDateTime earliest channel {:keys [date time]}]
  (let [at (parse-time date time)]
    (cond (nil? at) (prompts/render world "docs/visits/time-format.md"
                                         {:example-date (prompts/example-date world)})
          (.isBefore at earliest) (str channel "the earliest they can be here is " (.format earliest (fmt (str (clock/date-pattern world) ", HH:mm")))))))

(defn- text-reason
  "The action schema's limits on stay and reason, for the operator, whose
  spec isn't decoded by a schema."
  [{:keys [stay reason]}]
  (cond (not (and (string? stay) (<= 1 (count stay) max-stay)))
        (str "stay must be 1 to " max-stay " characters: how long, in your words (\"to dine\", \"a week\")")
        (and (some? reason) (not (and (string? reason) (<= 1 (count reason) max-reason))))
        (str "reason must be 1 to " max-reason " characters")))

(defn- bedroom-reason [world bedroom]
  (when (and bedroom (not (space/home-location? world bedroom)))
    "bedroom must be the id of an existing location in the household's home area; any room there may be assigned"))

(defn- post-offices [world]
  (sort-by :id (filter #(contains? (:services %) :post-office) (vals (get-in world [:space :locations])))))

(defn- channel-reason
  "Why the invitation can't go `by` this channel. In person needs the guest
  here, which waits for the judge when they're described (`person`)."
  [world actor {:keys [by who via]}]
  (case by
    "in-person" (or (physical/speech-reason world actor)
                     (when who
                       (cond (not (space/together? world actor who))
                             (str (agent-name world who) " must be here with you to be invited in person; otherwise send it by post or messenger")
                             (physical/unconscious? world who) (str (agent-name world who) " is unconscious; revive them first")
                             :else (physical/hearing-reason world who))))
    "post" (when-not (contains? (:services (space/location world (space/location-of world actor))) :post-office)
             (str "go to a post office to send it by post"
                  (when-let [p (first (post-offices world))] (str ": " (:name p) " (" (:id p) ")"))))
    "messenger" (cond (str/blank? via) "name who carries the message with via: the id of someone here"
                      (= via actor) "you can't be your own messenger; invite them in person, by post, or give the message to someone here"
                      (not (and (w/active? world via) (not= :gm (:role (w/agent world via)))
                                (space/together? world actor via)))
                      (str via " is not here to carry it"))))

(def ^:private channel-names {"in-person" "in person, " "post" "by post, " "messenger" "by messenger, "})

(defn invite-reason
  "Why `actor` can't send this invitation, or nil: structural checks only.
  For someone described (`person`), what can be checked before the judge
  says who they are; `resolved-reason` checks the rest."
  [world actor {:keys [by who person bedroom] :as args}]
  (or (when-not (config world) "visits are not enabled here")
      (cond (and who person) "give who (their id) or person (who they are, in your words), not both"
            (not (or who person)) "say whom you're inviting: who (their id) or person (who they are, in your words)")
      (when who
        (or (when (= who actor) "you can't invite yourself")
            (guest-reason world who)))
      (channel-reason world actor args)
      (time-reason world (earliest world (keyword by) (and who (away? world who))) (channel-names by) args)
      (bedroom-reason world bedroom)))

(defn- new-guest-reason
  "Why someone new from elsewhere, `fields` from the judge, can't be invited
  so, or nil: they can't be here to be asked in person, and they come from
  away. Checked before they're written."
  [world {:keys [by] :as args} fields]
  (or (when-not (get-in world [:state :positions :guest]) "this world has no :guest position for someone new")
      (when (= "in-person" by)
        (str (:name fields) " is not here to be invited in person; send it by post or messenger"))
      (time-reason world (earliest world (keyword by) true) (channel-names by) args)))

(defn- name-taken?
  "Is `person-name` someone's in the world already, other than the retired?"
  [world person-name]
  (some #(and (= person-name (:name %)) (not= :retired (:status %))) (w/agents-in-order world)))

(defn resolved-reason
  "Why `actor`'s invitation of someone described can't go, now the judge's
  `verdict` says who they are, or nil."
  [world actor args verdict]
  (cond (:impossible verdict) (:impossible verdict)
        (:existing verdict) (invite-reason world actor (-> args (dissoc :person) (assoc :who (:existing verdict))))
        (:new verdict) (or (new-guest-reason world args (:new verdict))
                           (when (name-taken? world (get-in verdict [:new :name]))
                             (str "someone called " (get-in verdict [:new :name]) " is in the world already; say more exactly whom you mean")))))

(def new-person-keys
  "The fields of someone new from elsewhere: name, sex (\"male\" or
  \"female\"), age, from (where they live), known-as (a public description)
  and situation (who they are, in the second person)."
  (mapv first judge/new-person-fields))

(defn- new-person? [spec] (some #(contains? spec %) new-person-keys))

(defn- new-person-reason
  "Why someone new can't be made from `spec`'s fields, or nil."
  [world spec]
  (let [missing (remove #(contains? spec %) new-person-keys)
        same (first (filter #(= (:name spec) (:name %)) (w/agents-in-order world)))]
    (cond (seq missing) (str "for someone new, give " (str/join ", " (map name new-person-keys))
                             "; missing: " (str/join ", " (map name missing)))
          (not (get-in world [:state :positions :guest])) "this world has no :guest position for someone new"
          same (str "someone called " (:name same) " is in the world already (" (:id same) "): use who"))))

(defn- arranged-reason-from
  "Shared checks with a chosen lower time bound; authored visits allow past times."
  [world {:keys [who bedroom known-to] :as spec} earliest]
  (or (when-not (config world) "visits are not enabled in this run")
      (cond (and who (new-person? spec)) "give who for someone in the world, or a new person's fields, not both"
            who (guest-reason world who)
            (new-person? spec) (new-person-reason world spec)
            :else "give who (their id), or a new person's name, sex, age, from, known-as and situation")
      (text-reason spec)
      (time-reason world earliest "" spec)
      (bedroom-reason world bedroom)
      (when-let [bad (seq (remove #(let [a (w/agent world %)] (and a (not= :gm (:role a)))) known-to))]
        (str "known-to must be character ids; unknown: " (str/join ", " bad)))))

(defn arranged-reason
  "Why the GM or operator can't arrange this visit, or nil: for `who`, an
  existing person, or for someone new (the GM only), `new-person-keys`. Its
  only lead time is the next round."
  [world spec]
  (arranged-reason-from world spec (next-round-time world)))

(defn cancel-reason [world id]
  (let [v (visit world id)]
    (cond (nil? v) (str "there is no visit " id)
          (not= :scheduled (:status v)) (str id " is " (name (:status v)) "; only a visit not yet begun can be cancelled"))))

;; ---------------------------------------------------------------------------
;; Making and cancelling visits

(defn- muffled-invitation? [world actor args]
  (and (= "in-person" (:by args)) (physical/impeded? world actor :speech)))

(defn- muffled-invitation [world actor guest]
  [{:type :person/muffled :actor actor :visible-to (if guest #{guest} (disj (space/here world actor) actor))
    :data {:sound :speech :at (space/location-of world actor)}}])

(defn invite-events
  "An invitation `actor` sends: the record, their calendar entry, and what's
  seen of it being sent. In person, or carried by the invitee themselves,
  the guest knows at once; otherwise at the end of the channel's lead time."
  [world actor {:keys [by who via date time stay reason bedroom] :as args}]
  (if (muffled-invitation? world actor args)
    (muffled-invitation world actor who)
    (let [by (keyword by)
          via (when (= :messenger by) via)
          deliver (if (or (= :in-person by) (= via who))
                    (:round world)
                    (round-of (:clock world) (earliest world by false)))
          scheduled (scheduled-events world {:guest who :host actor :by by :via via :at (parse-time date time)
                                             :deliver deliver :stay stay :reason reason :bedroom bedroom})]
      (into [(channel-event world actor (:data (first scheduled)))] scheduled))))

(defn sent-for-events
  "`actor` sends for `guest` (manor.mechanics.trades) to do `job`, expected
  at the meeting place `at`: a visit by :sent-for with the job as its stay.
  Someone about is told now and makes their own way, as an invited guest
  does; someone away arrives at the meeting place then."
  [world actor {:keys [guest job at]}]
  (scheduled-events world {:guest guest :host actor :by :sent-for :at at :deliver (:round world) :stay job}))

(defn arranged-events
  "A visit the GM or operator (`source`) arranges for an existing person: if
  they're about, told at once with their calendar entry, so they come;
  guests already here from elsewhere and away guests are told nothing.
  The host's entry goes to `known-to` only (default nobody)."
  [world source {:keys [who date time stay reason bedroom known-to]}]
  (scheduled-events world {:guest who :host nil :by source :via nil :at (parse-time date time)
                           :deliver (:round world) :stay stay :reason reason :bedroom bedroom
                           :known-to known-to}))

(defn cancel-events [world id]
  (into [(status-event :visits/cancelled (visit world id))] (cancelled-entries world (visit world id))))

(defn operator-events
  "The operator's `engine/schedule-visit!`, for an existing person (`who`).
  Throws with the reason if it can't be arranged."
  [world spec]
  (when-let [r (or (when (and (nil? (:who spec)) (new-person? spec))
                     "the operator names an existing person with :who; the GM's schedule-visit can write someone new")
                   (arranged-reason world spec))]
    (throw (ex-info r {:spec spec})))
  (arranged-events world :operator spec))

(defn operator-cancel-events
  "The operator's `engine/cancel-visit!`. Throws with the reason if it can't be cancelled."
  [world id]
  (when-let [r (cancel-reason world id)] (throw (ex-info r {:id id})))
  (cancel-events world id))

(def ^:private authored-keys #{:who :date :time :stay :reason :bedroom :known-to})

(defn- authored-reason
  "Why the world file's visit `spec` can't be made at the start, or nil: an
  existing character's id in :who, no other keys than an arranged visit's,
  known-to a vector of ids; then the GM's and operator's checks, without
  their lead time (valid past visits are skipped after validation)."
  [world {:keys [who date time known-to] :as spec}]
  (let [at (parse-time date time)]
    (or (when-let [extra (seq (remove authored-keys (keys spec)))]
          (str "unknown keys " (str/join ", " (map pr-str extra)) "; a visit has " (str/join ", " (map pr-str (sort authored-keys)))))
        (when-not (string? who) ":who must be the id of a character in the world")
        (when-not (or (nil? known-to) (and (vector? known-to) (every? string? known-to)))
          ":known-to must be a vector of character ids")
        (arranged-reason-from world spec (or at (now world))))))

(defn authored-events
  "The world file's :visits, made at a fresh run's start (after the cast,
  away characters included, and the calendar): each the same record and
  events as the GM's and operator's (`arranged-events`), with no host and
  :by :authored, on the world the one before left. Throws with every
  problem, each naming its visit, if any is invalid. Valid visits before
  the final start time are skipped, with no catch-up. Load and branch
  replay these events instead."
  [world specs]
  (when-not (vector? specs) (throw (ex-info "the world file's :visits must be a vector" {:visits specs})))
  (let [[_ events problems]
        (reduce (fn [[world events problems] [i spec]]
                  (if-let [r (if (map? spec) (authored-reason world spec) "a visit must be a map")]
                    [world events (conj problems (str "visit " (inc i) (when (and (map? spec) (:who spec)) (str " (" (:who spec) ")")) ": " r))]
                    (if (.isBefore (parse-time (:date spec) (:time spec)) (now world))
                      [world events problems]
                      (let [evs (arranged-events world :authored spec)]
                        [(apply-all world evs) (into events evs) problems]))))
                [world [] []] (map-indexed vector specs))]
    (when (seq problems)
      (throw (ex-info (str "bad :visits in the world file:\n- " (str/join "\n- " problems)) {:problems problems})))
    events))

;; ---------------------------------------------------------------------------
;; Someone new from elsewhere, made in advance as an away agent

(defn write-new-person
  "Someone new from elsewhere, written to order from `fields` (see
  `new-person-keys`) by the people writer: the people server's record for
  their sex and exact age, with their name kept and :lives set to `from`,
  then the appearance and polish, with `situation` as the polish's notes.
  I/O, through the composed-query `services`: call it from a :query-fn or a
  system's :compose, and make the agent from its result with `away-guest-events`."
  [world services {:keys [name sex age from situation]}]
  (let [p (hiring/person-record world services {:position :guest :area from :sex sex :ages [age age]
                                                :seed (str (:seed world) "/guest/" (inc (:seq world)))
                                                :taken-names (hiring/taken-names world)})
        p (-> p (assoc :name name :lives from) (update :record assoc :name name :age age :sex sex :lives from))]
    (hiring/write-person services p situation)))

(defn away-guest-events
  "Events making person `p` (from `write-new-person`) an away agent, from
  their `fields`: spawned as a :guest with their situation and no household,
  their :lives, public :known-as and :age set, then away towards :lives. All
  :system: a spawn holds a private persona and traits. {:id :events}."
  [scenario world p {:keys [situation known-as age]}]
  (let [spawned (b/spawn-events scenario world (merge (hiring/spawn-spec p)
                                                      {:role :guest :situation situation :household nil}))
        id (get-in (first spawned) [:data :agent :id])]
    {:id id
     :events (into (mapv #(assoc % :visible-to :system) spawned)
                   [{:type :agent/updated :visible-to :system :data {:id id :changes {:lives (:lives p) :known-as known-as :age age}}}
                    {:type :people/departed :visible-to :system
                     :data {:id id :reason "lives elsewhere" :towards (:lives p)}}])}))

;; ---------------------------------------------------------------------------
;; Leaving

(defn- leave-reason [world actor]
  (or (physical/disabled-reason world actor :movement "leave")
      (cond (not (from-elsewhere? world actor)) "you are not a guest from elsewhere here"
            (not (contains? (house-area world) (space/location-of world actor)))
            (str "take your leave from " (house world) ": go back there first"))))

(defn- return-visit-events
  "A guest from elsewhere leaving with a return time (hiring/return-at):
  a new visit, from their newest one here, for when they come back. They
  know of it, so their calendar has it, and so does the host's."
  [world actor args]
  (when-let [at (hiring/return-at args)]
    (let [v (newest-here world actor)]
      (scheduled-events world (cond-> {:guest actor :host (:host v) :at at :deliver (:round world)
                                       :by :returning :stay (:stay v) :returning? true
                                       :reason "Coming back, as they said when they left."}
                                (:known-to v) (assoc :known-to (:known-to v)))))))

(defn leave-events
  "A guest from elsewhere goes home, towards where they live, and their
  visits here end; with a return time, a visit for their return begins."
  [world actor args]
  (let [at (hiring/return-at args)]
    (into (vec (return-visit-events world actor args))
          (cons {:type :people/departed :visible-to (space/here world actor)
                 :data (cond-> {:id actor :reason "takes leave"
                                :towards (or (:lives (w/agent world actor))
                                             (:candidate-home (hiring/for-agent world actor))
                                             (:lives (hiring/for-agent world actor)) "elsewhere")}
                         at (assoc :returns {:at (meeting-place world) :when (clock/when-label world at)}))}
                (for [v (map #(visit world %) (ordered-ids world))
                      :when (and (= actor (:guest v)) (= :here (:status v)))]
                  (status-event :visits/ended v))))))

(defn take-leave
  "One `take-leave` for whatever brought someone: hiring's own for a
  candidate here (`candidate-leave`, unchanged), and for a guest from
  elsewhere anywhere in the house's area, going home."
  [candidate-leave]
  (let [candidate? (fn [world actor] (boolean (and candidate-leave ((:available? candidate-leave) world actor))))]
    {:name :take-leave :kind :final :roles :all
     :available? (fn [world actor] (or (candidate? world actor) (from-elsewhere? world actor)))
     :doc hiring/take-leave-doc
     :args hiring/return-args
     :valid? (fn [world actor args]
               (if (candidate? world actor)
                 ((:valid? candidate-leave) world actor args)
                 (or (leave-reason world actor) (hiring/return-reason world args))))
     :effect (fn [world actor args]
               (if (candidate? world actor) ((:effect candidate-leave) world actor args) (leave-events world actor args)))}))

;; ---------------------------------------------------------------------------
;; Views

(defn add-to-view
  "`view` with `you.visit` while `id` is a guest here (their newest visit
  :here: who asked them, for how long and why, when they came, their
  bedroom), and `away-acquaintances`: the people they've met who are away
  now, whom they can invite by id."
  [view world id]
  (let [v (newest-here world id)
        acquaintances (space/away-acquaintances world id)]
    (cond-> view
      v (assoc-in [:you :visit]
                  (cond-> {(if (sent-for? v) :job :invited-for) (:stay v)
                           :arrived (clock/short-label (:clock world) (:arrived v) (:round world))}
                    (:host v) (assoc (if (sent-for? v) :sent-for-by :host) (agent-name world (:host v)))
                    (:reason v) (assoc :reason (:reason v))
                    (:bedroom v) (assoc :bedroom (:bedroom v))))
      (seq acquaintances) (assoc :away-acquaintances acquaintances))))

;; ---------------------------------------------------------------------------
;; Actions

(def visit-args
  [[:date :string] [:time :string]
   [:stay [:string {:min 1 :max max-stay}]]
   [:reason {:optional true} [:string {:min 1 :max max-reason}]]
   [:bedroom {:optional true} :string]])

(def max-person 300)

(defn- invitee!
  "`invite` with `person`, a query: the invitation judge says who is meant
  from the inviter's own context, and someone new is written now (I/O,
  through `services`) unless they couldn't be invited anyway."
  [world actor {:keys [person reason] :as args} {:keys [adjudicate recent] :as services}]
  (if (muffled-invitation? world actor args)
    {:muffled? true}
    (let [verdict (adjudicate :visits/invitee (cond-> {:person person :recent (recent)} reason (assoc :reason reason)))]
      (cond-> {:verdict verdict}
        (and (:new verdict) (not (resolved-reason world actor args verdict)))
        (assoc :person (write-new-person world services (:new verdict)))))))

(defn- described-invite-events
  "The invitation of someone described, on the world as it is when the
  query's done: of the person the judge matched, or of someone new, made an
  away agent first from the query's written person. Nothing if it can't go
  (impossible, or a check the judge's answer fails)."
  [scenario world actor args {:keys [verdict person muffled?]}]
  (if muffled?
    (muffled-invitation world actor nil)
    (when-not (resolved-reason world actor args verdict)
      (cond (:existing verdict) (invite-events world actor (assoc args :who (:existing verdict)))
            person (let [{:keys [id events]} (away-guest-events scenario world person (:new verdict))]
                     (into events (invite-events (apply-all world events) actor (assoc args :who id))))))))

(defn- expected [world guest at]
  (str (agent-name world guest) " (" guest ") is expected at " (house world) " on " (when-label world {:at at}) "."))

(defn- invite-reply
  "Who is expected when; for someone described, why nothing is arranged if it isn't."
  ([world actor {:keys [who date time] :as args}]
   (if (muffled-invitation? world actor args)
     "Only muffled noises come out. No invitation was arranged."
     (expected world who (str (parse-time date time)))))
  ([world actor args {:keys [verdict events muffled?]}]
   (if muffled?
     "Only muffled noises come out. No invitation was arranged."
     (if-let [v (:data (first (filter #(= :visits/scheduled (:type %)) events)))]
       (expected world (:guest v) (:at v))
       (if-let [impossible (:impossible verdict)]
         (str impossible " Nothing is arranged.")
         (str "Nothing is arranged: " (or (resolved-reason world actor args verdict) "try again") "."))))))

(defn- invite-action
  "`invite`: free with `who`; with `person`, a query (the judge, and perhaps
  the people writer)."
  [scenario]
  {:name :invite :kind :free :roles :all :per-turn 1
   :kind-for (fn [args] (if (:person args) :query :free))
   :hint (prompts/doc "docs/hints/invite.md")
   :doc (fn [world]
           (prompts/render world "docs/visits/invite.md"
                           {:max-person max-person :example-date (prompts/example-date world)
                            :post (if (seq (post-offices world))
                                    (prompts/render world "docs/visits/post.md" {}) "")}))
   :args (into [:map [:by [:enum "in-person" "post" "messenger"]]
                [:who {:optional true} :string]
                [:person {:optional true} [:string {:min 1 :max max-person}]]
                [:via {:optional true} :string]]
               visit-args)
   :valid? invite-reason
   :effect invite-events
   :query-fn invitee!
   :on-result (partial described-invite-events scenario)
   :reply invite-reply})

(defn- gm-arranged-events
  "The GM's visit, on the world as it is when the query's done: for someone
  new, their away agent first (from the query's written person)."
  [scenario world args {:keys [person]}]
  (when-not (arranged-reason world args)
    (if person
      (let [{:keys [id events]} (away-guest-events scenario world person (select-keys args new-person-keys))]
        (into events (arranged-events (apply-all world events) :gm (-> (apply dissoc args new-person-keys) (assoc :who id)))))
      (arranged-events world :gm args))))

(defn- gm-actions
  "The GM's `schedule-visit` (a query: someone new is written first) and `cancel-visit`."
  [scenario]
  [{:name :schedule-visit :roles #{:gm} :kind :query
    :doc (prompts/doc "docs/visits/schedule-visit.md")
    :args (-> [:map [:who {:optional true} :string]]
              (into (map (fn [[k schema]] [k {:optional true} schema])) judge/new-person-fields)
              (conj [:known-to {:optional true} [:vector :string]])
              (into visit-args))
    :valid? (fn [world _ args] (arranged-reason world args))
    :query-fn (fn [world _ args services]
                (if (:who args) {} {:person (write-new-person world services (select-keys args new-person-keys))}))
    :on-result (fn [world _ args result] (gm-arranged-events scenario world args result))
    :reply (fn [world _ args {:keys [events]}]
             (if-let [v (:data (first (filter #(= :visits/scheduled (:type %)) events)))]
               (str (:id v) ": " (agent-name world (:guest v)) " (" (:guest v) ") is expected at " (house world)
                    " on " (when-label world v) ".")
               (str "Nothing was arranged: " (or (arranged-reason world args) "try again"))))}
   {:name :cancel-visit :roles #{:gm} :kind :final
    :doc (prompts/doc "docs/visits/cancel-visit.md")
    :args [:map [:id :string]]
    :valid? (fn [world _ {:keys [id]}] (cancel-reason world id))
    :effect (fn [world _ {:keys [id]}] (cancel-events world id))}])

(defn actions
  "`invite`, the shared `take-leave` and the GM's tools, for `scenario`."
  [scenario]
  (into [(invite-action scenario) (take-leave (first (filter #(= :take-leave (:name %)) hiring/actions)))] (gm-actions scenario)))

(defn rules
  "The rules paragraph, from the design's section 9: with the post only
  where there's a post office, and the calendar only where there is one."
  [world]
  (prompts/render world "rules/visits.md"
                  {:post (if (seq (post-offices world))
                           (prompts/render world "rules/visits-post.md" {}) "")
                   :calendar (if (:calendar world)
                               (prompts/render world "rules/visits-calendar.md" {}) "")}))

(defn- merged
  "Configs merged in order, the :lead-hours map key by key."
  [& configs]
  (apply merge-with (fn [a b] (if (map? a) (merge a b) b)) configs))

(def calendar-owner
  "How visits own their calendar entries (see manor.mechanics.calendar): ids
  starting visit- are theirs, and an edit is refused with a pointer to cancel-visit."
  {:id-prefix "visit-" :hint "use cancel-visit"})

(defn install
  "Add visits to a scenario: `invite`, `take-leave` for candidates and guests
  from elsewhere alike (in place of hiring's), the GM's `schedule-visit` and
  `cancel-visit`, the invitation judge (`:visits/invitee` in :adjudicators;
  it needs hiring's people writer), the tick, and the config in [:state :visit-config]: the
  defaults, the house's name from the world file's :meta :name, the
  scenario's :visits, then the run option :visits. Idempotent. Install it
  after hiring (it needs the meeting place); the scenario needs a clock, a
  :guest position for someone new, and :calendar for the entries (visits
  names itself there as their owner). A scenario with :hibernate should
  leave out anyone `keeps-awake?`, and its :observe should call `add-to-view`."
  [scenario]
  (let [init (:init scenario)
        actions (actions scenario)
        names (set (map :name actions))]
    (-> scenario
        (cond-> (:calendar scenario) (assoc-in [:calendar :managed :visits] calendar-owner))
        (assoc :init (fn [opts]
                       (let [result (init opts)]
                         (assoc-in result [:state :visit-config]
                                   (merged default-config
                                           (when-let [h (get-in result [:world-file :meta :name])] {:house h})
                                           (:visits scenario) (:visits opts))))))
        (update :actions #(into (vec (remove (comp names :name) %)) actions))
        (update :adjudicators merge judge/adjudicators)
        (update :systems #(conj (vec (remove (fn [s] (= :visits/tick (:name s))) %))
                                {:name :visits/tick :tick (fn [world _] (tick scenario world))})))))
