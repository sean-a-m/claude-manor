(ns manor.mechanics.visits
  "Visits: a guest expected at the house at a date and time (phase 1 of
  docs/visits-design.md: guests who are in the world). A visit is one
  engine-owned record in [:state :visits]. Every source (the `invite` action,
  the GM's `schedule-visit`, the operator's `engine/schedule-visit!`) makes
  the same record, and a system tick moves it on before turns: the guest is
  told, comes (:here), goes home (:ended), or doesn't come (:lapsed). The
  guest makes their own way; nobody moves them. Their calendar entries, the
  host's and the guest's, are written from the record and owned by it
  (:managed-by :visits), so they're never GM-reviewed and only visits change
  them. A scenario with :hibernate keeps an expected guest in play by
  asking `keeps-awake?`."
  (:require [clojure.string :as str]
            [manor.clock :as clock]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.calendar :as calendar]
            [manor.mechanics.positions :as positions]
            [manor.mechanics.space :as space]
            [manor.narrate :as n]
            [manor.world :as w])
  (:import (java.time Duration LocalDate LocalDateTime LocalTime)
           (java.time.format DateTimeFormatter)
           (java.util Locale)))

(def default-config
  "Lead times are the shortest gap between sending an invitation and the
  guest being expected. A guest is kept in play from :wake-from on the
  visit's day, and no longer expected :lapse-hours after its time.
  :from-away-hours is for guests from elsewhere (phase 2). :house names the
  house in invitations; `install` takes it from the world file's :meta :name."
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

(defn- visit-number [id] (parse-long (subs id (count "visit-"))))
(defn- ordered-ids [world] (sort-by visit-number (keys (visits world))))

(defn- meeting-place [world] (get-in world [:state :meeting-place]))
(defn- house [world] (:house (config world) "the house"))

(defn house-area
  "The meeting place's walking area: the carriage sweep and every room."
  [world]
  (if-let [place (meeting-place world)] (space/area world place) #{}))

(defn- now ^LocalDateTime [world] (clock/time-of (:clock world) (:round world)))

(defn round-of
  "The first round starting at or after `t`."
  [clock ^LocalDateTime t]
  (let [minutes (.toMinutes (Duration/between (LocalDateTime/parse (:start clock)) t))]
    (max 1 (inc (long (Math/ceil (/ minutes (double (:minutes-per-round clock)))))))))

(defn- rounds-for-hours [world hours] (clock/rounds-for (:clock world) (* 60 hours)))

(defn- parse-time [date time]
  (try (LocalDateTime/of (LocalDate/parse (str date)) (LocalTime/parse (str time)))
       (catch Exception _ nil)))

(defn- fmt [pattern] (DateTimeFormatter/ofPattern pattern Locale/ENGLISH))
(def ^:private when-format (fmt "EEEE d MMMM 'at' HH:mm"))
(def ^:private earliest-format (fmt "EEEE d MMMM, HH:mm"))

(defn- when-label
  "\"Friday 21 October at 19:00\" for a visit's :at."
  [v]
  (.format (LocalDateTime/parse (:at v)) when-format))

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

(defn- earliest
  "The earliest time a guest invited now `by` a channel can be expected."
  ^LocalDateTime [world by]
  (.plusMinutes (now world) (lead-minutes world by)))

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

(defn- host-entry [world v]
  (entry-events world v :host (host-audience v)
                (with-reason (str (agent-name world (:guest v)) " (" (:guest v) ") is expected at " (house world)
                                  (if (:host v) ", invited by you: " ": ") (:stay v) ".")
                  v)))

(defn- guest-entry [world v]
  (let [place (meeting-place world)
        where (str (house world) ", at the " (:name (space/location world place) place) " (" place ")")]
    (entry-events world v :guest #{(:guest v)}
                  (with-reason (if (:host v)
                                 (str "You are expected at " where ", invited by " (agent-name world (:host v)) ": " (:stay v) ".")
                                 (str "You mean to call at " where ": " (:stay v) "."))
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
  [world {:keys [guest at deliver known-to] :as spec}]
  (let [v (cond-> (merge (select-keys spec [:guest :host :by :via :stay :reason :bedroom])
                         {:id (str "visit-" (inc (count (visits world))))
                          :at (str at) :arrive (round-of (:clock world) at) :deliver deliver
                          :wake (wake-round world guest at) :status :scheduled
                          :told? (<= deliver (:round world))})
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
(doseq [[type status] {:visits/arrived :here :visits/ended :ended :visits/lapsed :lapsed :visits/cancelled :cancelled}]
  (defmethod w/apply-event type [world {:keys [data]}]
    (assoc-in world [:state :visits (:visit data) :status] status)))

(defn- invitation-data [world v]
  (merge (select-keys v [:guest :host :via :stay :reason])
         {:visit (:id v) :house (house world) :expected (when-label v)}))

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
  (str (if (:via data)
         (str (n/who names (:via data)) " brings a message from " (n/who names (:host data)))
         (str "A letter comes from " (n/who names (:host data))))
       ": you are invited to " (invitation-text data)))
(defmethod n/describe :visits/reminded [{:keys [data]} names]
  (str "You have an engagement: " (:house data) " at " (n/at names (:arrive data))
       (when (:host data) (str " (" (n/who names (:host data)) "'s invitation)")) "."))
(defmethod n/describe :visits/scheduled [{:keys [data]} names]
  (str "Visit " (:id data) ": " (n/who names (:guest data)) " is expected at " (:at data)
       (if (:host data)
         (str ", invited by " (n/who names (:host data)) " (" (name (:by data)) ")")
         (str ", arranged by the " (name (:by data))))
       ": " (:stay data) "."))
(defmethod n/describe :visits/arrived [{:keys [data]} _] (str "The guest of " (:visit data) " has come."))
(defmethod n/describe :visits/ended [{:keys [data]} _] (str (:visit data) " has ended: the guest has gone."))
(defmethod n/describe :visits/lapsed [{:keys [data]} _] (str (:visit data) " has lapsed: the guest didn't come."))
(defmethod n/describe :visits/cancelled [{:keys [data]} _] (str (:visit data) " is cancelled."))

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

(defn- telling
  "A guest invited by post or messenger learns of it at :deliver."
  [world v]
  (when (and (= :scheduled (:status v)) (not (:told? v)) (>= (:round world) (:deliver v))
             (w/active? world (:guest v)))
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

(defn- ending
  "A guest no longer in, or heading to, the house's area has gone home."
  [world v]
  (let [a (w/agent world (:guest v))
        area (house-area world)]
    (when (and (= :here (:status v))
               (not (and (= :active (:status a))
                         (or (contains? area (:location a)) (contains? area (get-in a [:travel :to]))))))
      [(status-event :visits/ended v)])))

(defn- lapsing
  "A guest who is retired, or hasn't come :lapse-hours after the time, isn't expected."
  [world v]
  (when (and (= :scheduled (:status v))
             (or (= :retired (:status (w/agent world (:guest v))))
                 (>= (:round world) (+ (:arrive v) (rounds-for-hours world (:lapse-hours (config world)))))))
    [(status-event :visits/lapsed v)]))

(defn- visit-steps [world id]
  (reduce (fn [[world events] step]
            (let [evs (step world (visit world id))]
              [(apply-all world evs) (into events evs)]))
          [world []] [telling reminding arriving ending lapsing]))

(defn tick
  "Every visit's events this round."
  [world]
  (second (reduce (fn [[world events] id]
                    (let [[world evs] (visit-steps world id)] [world (into events evs)]))
                  [world []] (ordered-ids world))))

;; ---------------------------------------------------------------------------
;; Checks

(defn- guest-reason
  "Why `guest` can't be invited, or nil. Phase 1 invites only people in the world."
  [world guest]
  (let [a (w/agent world guest)]
    (cond (or (nil? a) (= :gm (:role a))) (str "no one has the id " guest)
          (= :retired (:status a)) (str (:name a) " cannot come")
          (not= :active (:status a)) (str (:name a) " is not in the neighbourhood now")
          (positions/household-member? world guest) (str (:name a) " lives here"))))

(defn- time-reason [world ^LocalDateTime earliest channel {:keys [date time]}]
  (let [at (parse-time date time)]
    (cond (nil? at) "give the date like \"1904-10-21\" and the time like \"19:00\""
          (.isBefore at earliest) (str channel "the earliest they can be here is " (.format earliest earliest-format)))))

(defn- bedroom-reason [world bedroom]
  (when (and bedroom (not= :bedroom (:kind (space/location world bedroom))))
    "bedroom must be the id of a bedroom"))

(defn- post-offices [world]
  (sort-by :id (filter #(contains? (:services %) :post-office) (vals (get-in world [:space :locations])))))

(defn- channel-reason [world actor {:keys [by who via]}]
  (case by
    "in-person" (when-not (space/together? world actor who)
                  (str (agent-name world who) " must be here with you to be invited in person; otherwise send it by post or messenger"))
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
  "Why `actor` can't send this invitation, or nil: structural checks only."
  [world actor {:keys [by who bedroom] :as args}]
  (or (when-not (config world) "visits are not enabled here")
      (when (= who actor) "you can't invite yourself")
      (guest-reason world who)
      (channel-reason world actor args)
      (time-reason world (earliest world (keyword by)) (channel-names by) args)
      (bedroom-reason world bedroom)))

(defn arranged-reason
  "Why the GM or operator can't arrange this visit, or nil. Its only lead time is the next round."
  [world {:keys [who bedroom known-to] :as spec}]
  (or (when-not (config world) "visits are not enabled in this run")
      (guest-reason world who)
      (time-reason world (next-round-time world) "" spec)
      (bedroom-reason world bedroom)
      (when-let [bad (seq (remove #(let [a (w/agent world %)] (and a (not= :gm (:role a)))) known-to))]
        (str "known-to must be character ids; unknown: " (str/join ", " bad)))))

(defn cancel-reason [world id]
  (let [v (visit world id)]
    (cond (nil? v) (str "there is no visit " id)
          (not= :scheduled (:status v)) (str id " is " (name (:status v)) "; only a visit not yet begun can be cancelled"))))

;; ---------------------------------------------------------------------------
;; Making and cancelling visits

(defn invite-events
  "An invitation `actor` sends: the record, their calendar entry, and what's
  seen of it being sent. In person, or carried by the invitee themselves,
  the guest knows at once; otherwise at the end of the channel's lead time."
  [world actor {:keys [by who via date time stay reason bedroom]}]
  (let [by (keyword by)
        via (when (= :messenger by) via)
        deliver (if (or (= :in-person by) (= via who))
                  (:round world)
                  (round-of (:clock world) (earliest world by)))
        scheduled (scheduled-events world {:guest who :host actor :by by :via via :at (parse-time date time)
                                           :deliver deliver :stay stay :reason reason :bedroom bedroom})]
    (into [(channel-event world actor (:data (first scheduled)))] scheduled)))

(defn arranged-events
  "A visit the GM or operator (`source`) arranges for an existing person:
  told at once with their calendar entry, so they come; the host's entry
  goes to `known-to` only (default nobody)."
  [world source {:keys [who date time stay reason bedroom known-to]}]
  (scheduled-events world {:guest who :host nil :by source :via nil :at (parse-time date time)
                           :deliver (:round world) :stay stay :reason reason :bedroom bedroom
                           :known-to known-to}))

(defn cancel-events [world id]
  (into [(status-event :visits/cancelled (visit world id))] (cancelled-entries world (visit world id))))

(defn operator-events
  "The operator's `engine/schedule-visit!`. Throws with the reason if it can't be arranged."
  [world spec]
  (when-let [r (arranged-reason world spec)] (throw (ex-info r {:spec spec})))
  (arranged-events world :operator spec))

(defn operator-cancel-events
  "The operator's `engine/cancel-visit!`. Throws with the reason if it can't be cancelled."
  [world id]
  (when-let [r (cancel-reason world id)] (throw (ex-info r {:id id})))
  (cancel-events world id))

;; ---------------------------------------------------------------------------
;; Actions

(def visit-args
  [[:date :string] [:time :string]
   [:stay [:string {:min 1 :max max-stay}]]
   [:reason {:optional true} [:string {:min 1 :max max-reason}]]
   [:bedroom {:optional true} :string]])

(def invite
  {:name :invite :kind :free :roles :all :per-turn 1
   :doc (str "Invite someone to the house for a date and time. by: \"in-person\" (they're here with you), "
             "\"post\" (you're at a post office) or \"messenger\" (via: the id of anyone here who will carry it, "
             "the person invited included). who: their id. date (\"1904-10-21\") and time (\"19:00\"): when "
             "they're expected at the house. stay: how long, in your words (\"to dine\", \"a week\"). reason: "
             "optional, why. bedroom: optional, a bedroom id for a guest who stays the night.")
   :args (into [:map [:by [:enum "in-person" "post" "messenger"]] [:who :string] [:via {:optional true} :string]]
               visit-args)
   :valid? invite-reason
   :effect invite-events
   :reply (fn [world _ {:keys [who date time]}]
            (str (agent-name world who) " (" who ") is expected at " (house world) " on "
                 (when-label {:at (str (parse-time date time))}) "."))})

(def gm-actions
  [{:name :schedule-visit :roles #{:gm} :kind :final
    :doc "Arrange for someone in the world (who: their id) to call at the house at a date and time. They're told at once, in their calendar, and make their own way. known-to: ids of anyone else who should have it in their calendar (default nobody)."
    :args (into [:map [:who :string] [:known-to {:optional true} [:vector :string]]] visit-args)
    :valid? (fn [world _ args] (arranged-reason world args))
    :effect (fn [world _ args] (arranged-events world :gm args))}
   {:name :cancel-visit :roles #{:gm} :kind :final
    :doc "Cancel a visit not yet begun, by its id in state.visits, with its calendar entries."
    :args [:map [:id :string]]
    :valid? (fn [world _ {:keys [id]}] (cancel-reason world id))
    :effect (fn [world _ {:keys [id]}] (cancel-events world id))}])

(def actions (into [invite] gm-actions))

(def rules
  "The rules paragraph (phase 1: only `who`)."
  "Anyone may `invite` someone to the house for a date and time: in person (they're here with you), by post (at a post office) or by messenger (anyone here who will carry it, even the person invited). Name them with `who`, their id. They make their own way to the house at that time. If you're invited, your calendar says when and where to be expected.")

(defn- merged
  "Configs merged in order, the :lead-hours map key by key."
  [& configs]
  (apply merge-with (fn [a b] (if (map? a) (merge a b) b)) configs))

(defn install
  "Add visits to a scenario: `invite`, the GM's `schedule-visit` and
  `cancel-visit`, the tick, and the config in [:state :visit-config]: the
  defaults, the house's name from the world file's :meta :name, the
  scenario's :visits, then the run option :visits. Idempotent. Install it
  after hiring (it needs the meeting place); the scenario needs a clock, and
  :calendar for the entries. A scenario with :hibernate should leave out
  anyone `keeps-awake?`."
  [scenario]
  (let [init (:init scenario)
        names (set (map :name actions))]
    (-> scenario
        (assoc :init (fn [opts]
                       (let [result (init opts)]
                         (assoc-in result [:state :visit-config]
                                   (merged default-config
                                           (when-let [h (get-in result [:world-file :meta :name])] {:house h})
                                           (:visits scenario) (:visits opts))))))
        (update :actions #(into (vec (remove (comp names :name) %)) actions))
        (update :systems #(conj (vec (remove (fn [s] (= :visits/tick (:name s))) %))
                                {:name :visits/tick :tick (fn [world _] (tick world))})))))
