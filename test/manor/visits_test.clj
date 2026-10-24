(ns manor.visits-test
  "Visits (docs/visits-design.md): guests in the world, invited by post,
  messenger or in person, or arranged by the GM or operator (phase 1); and
  guests from elsewhere, away agents who arrive at the meeting place (phase
  2, part A); and people described in `invite`'s `person`, settled by the
  invitation judge (phase 2, part B). Runs use the production Claudeworth mechanics on the
  test-owned world test/fixtures/visits.edn (start 1904-10-15 20:00,
  half-hour rounds)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [manor.actions :as a]
            [manor.engine :as e]
            [manor.engine.resolve :as resolve]
            [manor.io.store :as store]
            [manor.io.worldfile :as worldfile]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.calendar :as calendar]
            [manor.mechanics.hiring :as hiring]
            [manor.mechanics.positions :as positions]
            [manor.mechanics.space :as space]
            [manor.mechanics.visits :as visits]
            [manor.mechanics.visits.judge :as judge]
            [manor.narrate :as n]
            [manor.prompt :as prompt]
            [manor.scenarios.claudeworth :as cw]
            [manor.scenarios.claudeworth-medium :as medium]
            [manor.world :as w])
  (:import (java.time LocalDateTime)))

(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

(def world-file "test/fixtures/visits.edn")

(defn- start-policy [scenario policy opts]
  (with-redefs [e/load-scenario (constantly (assoc scenario :mock-policy policy))]
    (e/start-run :claudeworth (merge {:test? true :llm :mock :offline? true :gm :off :max-cost 0.20
                                      :world-file world-file :run-id (str (gensym "visits-"))}
                                     opts))))

(defn- start-with [scenario day opts] (start-policy scenario #(cw/scripted-policy day %) opts))

(defn- start [day opts] (start-with cw/scenario day opts))

(defn- until!
  "Run rounds until round `r` has run."
  [run r]
  (let [n (- r (:round (e/world run)))]
    (when (pos? n)
      (e/auto! run n)
      (is (= :idle (e/wait-idle run 60000))))
    (is (= r (:round (e/world run))))))

(defn- round-at [world t] (visits/round-of (:clock world) (LocalDateTime/parse t)))
(defn- status [world id] (:status (visits/visit world id)))
(defn- hibernating? [run id] (b/hibernating? (e/world run) id))
(defn- calendar-of [run id] (calendar/character-section (e/world run) id))
(defn- seen-by [run id] (filter #(w/visible-to? % id) (e/events run)))
(defn- of-type [run type] (filter #(= type (:type %)) (e/events run)))
(defn- replay! [run] (is (= (e/world run) (w/replay (store/read-events (:dir run))))))
(defn- action [run tool] (first (filter #(= tool (:name %)) (:actions run))))

(defn- check
  "The reason `actor`'s `tool` call is refused on `world`, or nil."
  [run world actor tool args]
  (let [spec (action run tool)
        decoded (a/decode-args spec args)]
    (or (:error decoded) (a/check spec world actor (:args decoded)))))

(defn- effect [run world actor tool args]
  (let [spec (action run tool)]
    (is (nil? (check run world actor tool args)))
    (a/effect-events spec world actor (:args (a/decode-args spec args)))))

(def post-day
  "The earl posts an invitation at 20:00 for 10:00 tomorrow; Mrs Bell goes up
  at 09:30 and home at 10:30."
  {"lord-claudeworth" {"20:00" [[:invite {:by "post" :who "mrs-bell" :date "1904-10-16" :time "10:00"
                                          :stay "to tea" :reason "SENTINEL-REASON"}]
                                [:travel {:to "carriage-sweep"}]]}
   "mrs-bell" {"09:30" [[:travel {:to "carriage-sweep"}]]
               "10:30" [[:travel {:to "postmistress-room"}]]}})

(deftest villager-invited-by-post
  (let [run (start post-day {})]
    (try
      (until! run 1)
      (let [world (e/world run) v (visits/visit world "visit-1")]
        (testing "the record, and what's seen of the posting"
          (is (= {:guest "mrs-bell" :host "lord-claudeworth" :by :post :at "1904-10-16T10:00"
                  :deliver (round-at world "1904-10-15T23:00") :arrive (round-at world "1904-10-16T10:00")
                  :wake (round-at world "1904-10-16T06:00") :status :scheduled :told? false}
                 (select-keys v [:guest :host :by :at :deliver :arrive :wake :status :told?])))
          (is (= [#{"lord-claudeworth"}] (map :visible-to (of-type run :visits/posted))))
          (is (= [:gm] (map :visible-to (of-type run :visits/scheduled)))))
        (testing "only the inviter has it in their calendar until the letter comes"
          (is (str/includes? (calendar-of run "lord-claudeworth") "Mrs Bell (mrs-bell) is expected at Claudeworth Manor, invited by you: to tea."))
          (is (not (str/includes? (calendar-of run "mrs-bell") "Claudeworth Manor")))
          (is (not (str/includes? (calendar-of run "hale") "Claudeworth Manor"))))
        (is (hibernating? run "mrs-bell")))
      (until! run 6)
      (is (not (:told? (visits/visit (e/world run) "visit-1"))))
      (until! run 7)
      (testing "the letter comes at 23:00, to her alone, with her calendar entry"
        (is (:told? (visits/visit (e/world run) "visit-1")))
        (is (= [#{"mrs-bell"}] (map :visible-to (of-type run :visits/delivered))))
        (is (str/includes? (calendar-of run "mrs-bell")
                           "16 October 1904, 10:00 — You are expected at Claudeworth Manor, at the Carriage sweep (carriage-sweep), invited by Lord Claudeworth: to tea. SENTINEL-REASON"))
        (is (hibernating? run "mrs-bell")))
      (until! run 20)
      (is (hibernating? run "mrs-bell") "asleep until the visit's day begins")
      (until! run 21)
      (testing "kept in play from 06:00, and reminded privately"
        (is (not (hibernating? run "mrs-bell")))
        (is (visits/keeps-awake? (e/world run) "mrs-bell"))
        (is (= [#{"mrs-bell"}] (map :visible-to (of-type run :visits/reminded)))))
      (until! run 26)
      (is (not (hibernating? run "mrs-bell")) "still in play, nobody near")
      (until! run 29)
      (testing "she travels up and is here"
        (is (= "carriage-sweep" (space/location-of (e/world run) "mrs-bell")))
        (is (= :here (status (e/world run) "visit-1"))))
      (until! run 31)
      (testing "she goes home and the visit ends"
        (is (= "postmistress-room" (space/location-of (e/world run) "mrs-bell")))
        (is (= :ended (status (e/world run) "visit-1"))))
      (until! run 32)
      (testing "and she hibernates again, where her routine has her"
        (is (hibernating? run "mrs-bell"))
        (is (= "post-office" (space/location-of (e/world run) "mrs-bell"))))
      (testing "nobody else learns of it"
        (is (not-any? #(= "visits" (namespace (:type %))) (seen-by run "hale")))
        (is (not (str/includes? (calendar-of run "hale") "Mrs Bell")))
        (doseq [event (filter #(= "calendar" (namespace (:type %))) (e/events run))]
          (is (= :gm (:visible-to event))))
        (let [world (e/world run)]
          (is (not (str/includes? (pr-str (prompt/agent-messages (:scenario run) world (e/events run) "hale" {}))
                                  "SENTINEL")))
          (is (not (str/includes? (pr-str (prompt/agent-view (:scenario run) world "mrs-bell")) "SENTINEL"))
              "the view never carries visits; only her calendar does")))
      (is (empty? (of-type run :action/forfeited)))
      (replay! run)
      (finally (e/stop-run run)))))

(deftest invitation-checks-and-channels
  (let [run (start {} {})]
    (try
      (let [world (e/world run)
            invite (fn [by who time & {:as more}]
                     (merge {:by by :who who :date "1904-10-16" :time time :stay "to dine"} more))
            together (-> world (assoc-in [:agents "mrs-bell" :location] "post-office")
                         (assoc-in [:agents "hale" :location] "post-office"))]
        (testing "free, once a turn, and offered to every character"
          (is (= :free (a/kind (action run :invite))))
          (is (= 1 (:per-turn (action run :invite))))
          (is (= #{:gm} (:roles (action run :schedule-visit))))
          (is (str/includes? (cw/agent-brief world "hale") visits/rules)))
        (testing "structural checks, with reasons that say what to do"
          (is (= "you can't invite yourself" (check run world "lord-claudeworth" :invite (invite "post" "lord-claudeworth" "10:00"))))
          (is (= "no one has the id gm" (check run world "lord-claudeworth" :invite (invite "post" "gm" "10:00"))))
          (is (= "no one has the id nobody" (check run world "lord-claudeworth" :invite (invite "post" "nobody" "10:00"))))
          (is (= "Hale lives here" (check run world "lord-claudeworth" :invite (invite "post" "hale" "10:00"))))
          (is (= "go to a post office to send it by post: Post office (post-office)"
                 (check run world "hale" :invite (invite "post" "mrs-bell" "10:00"))))
          (is (= "by post, the earliest they can be here is Saturday 15 October, 23:00"
                 (check run world "lord-claudeworth" :invite (assoc (invite "post" "mrs-bell" "22:00") :date "1904-10-15"))))
          (is (= "give the date like \"1904-10-21\" and the time like \"19:00\""
                 (check run world "lord-claudeworth" :invite (invite "post" "mrs-bell" "teatime"))))
          (is (= "bedroom must be the id of a bedroom"
                 (check run world "lord-claudeworth" :invite (invite "post" "mrs-bell" "10:00" :bedroom "drawing-room"))))
          (is (str/starts-with? (check run world "lord-claudeworth" :invite (invite "in-person" "mrs-bell" "10:00"))
                                "Mrs Bell must be here with you"))
          (is (= "name who carries the message with via: the id of someone here"
                 (check run world "lord-claudeworth" :invite (invite "messenger" "mrs-bell" "10:00"))))
          (is (= "hale is not here to carry it"
                 (check run world "lord-claudeworth" :invite (invite "messenger" "mrs-bell" "10:00" :via "hale"))))
          (is (str/starts-with? (check run together "lord-claudeworth" :invite (invite "messenger" "mrs-bell" "10:00" :via "lord-claudeworth"))
                                "you can't be your own messenger")))
        (testing "in person: the guest knows at once"
          (let [evs (effect run together "lord-claudeworth" :invite (invite "in-person" "mrs-bell" "10:00"))
                after (reduce w/apply-event together evs)]
            (is (= #{"lord-claudeworth" "mrs-bell"} (:visible-to (first (filter #(= :visits/invited (:type %)) evs)))))
            (is (:told? (visits/visit after "visit-1")))
            (is (str/includes? (calendar/character-section after "mrs-bell") "invited by Lord Claudeworth: to dine."))
            (is (not (str/includes? (calendar/character-section after "hale") "Claudeworth Manor")))))
        (testing "by messenger: the messenger sees the message; the guest is told after the lead time"
          (let [evs (effect run together "lord-claudeworth" :invite (invite "messenger" "mrs-bell" "10:00" :via "hale"))
                after (reduce w/apply-event together evs)
                v (visits/visit after "visit-1")]
            (is (= #{"lord-claudeworth" "hale"} (:visible-to (first (filter #(= :visits/entrusted (:type %)) evs)))))
            (is (= (round-at world "1904-10-15T21:00") (:deliver v)))
            (is (not (:told? v)))
            (is (not (str/includes? (calendar/character-section after "hale") "Claudeworth Manor")))))
        (testing "the invitee may carry their own invitation, and then knows at once"
          (let [evs (effect run together "lord-claudeworth" :invite (invite "messenger" "mrs-bell" "10:00" :via "mrs-bell"))]
            (is (:told? (visits/visit (reduce w/apply-event together evs) "visit-1")))))
        (testing "the run option :visits is merged over the defaults"
          (let [run2 (start {} {:visits {:lead-hours {:post 1}}})]
            (try
              (is (= {:in-person 0 :messenger 1 :post 1} (:lead-hours (visits/config (e/world run2)))))
              (is (nil? (check run2 (e/world run2) "lord-claudeworth" :invite
                               (assoc (invite "post" "mrs-bell" "21:00") :date "1904-10-15"))))
              (finally (e/stop-run run2))))))
      (finally (e/stop-run run)))))

(deftest arranged-visits-wakes-lapses-and-managed-entries
  (let [run (start {} {:gm :llm})]
    (try
      (testing "the operator's time must be at least the next round's"
        (is (thrown-with-msg? Exception #"the earliest they can be here is Saturday 15 October, 20:00"
                              @(e/schedule-visit! run {:who "carter" :date "1904-10-15" :time "19:00" :stay "x"}))))
      @(e/schedule-visit! run {:who "carter" :date "1904-10-15" :time "21:00" :stay "to look at a gate"
                               :known-to ["lord-claudeworth"]})
      @(e/schedule-visit! run {:who "mrs-bell" :date "1904-10-17" :time "01:00" :stay "a word at midnight"})
      @(e/schedule-visit! run {:who "mrs-bell" :date "1904-10-18" :time "15:00" :stay "to tea" :known-to ["hale"]})
      (let [world (e/world run)]
        (testing "told at once; the host side only to known-to"
          (is (every? :told? (vals (visits/visits world))))
          (is (= :operator (:by (visits/visit world "visit-1"))))
          (is (str/includes? (calendar-of run "carter") "You mean to call at Claudeworth Manor"))
          (is (str/includes? (calendar-of run "lord-claudeworth") "Carter (carter) is expected at Claudeworth Manor: to look at a gate."))
          (is (not (str/includes? (calendar-of run "hale") "Carter")))
          (is (not (str/includes? (calendar-of run "lord-claudeworth") "Mrs Bell"))))
        (testing "a 01:00 visit keeps its guest in play from 06:00 the day before"
          (is (= (round-at world "1904-10-16T06:00") (:wake (visits/visit world "visit-2")))))
        (testing "the GM and operator can't edit managed entries"
          (is (= "that entry is managed by visits; use cancel-visit"
                 (check run world "gm" :update-calendar-entry {:id "visit-1-host" :description "Changed."})))
          (is (= "that entry is managed by visits; use cancel-visit"
                 (check run world "gm" :cancel-calendar-entry {:id "visit-1-guest"})))
          (is (thrown-with-msg? Exception #"use cancel-visit"
                                @(e/update-calendar-entry! run "visit-1-host" {:description "Changed."})))
          (is (thrown? Exception (calendar/command-events world :add {:id "x" :date "1904-10-16" :description "x"
                                                                     :managed-by :visits})))))
      (until! run 1)
      (testing "a guest expected today is kept in play and reminded"
        (is (not (hibernating? run "carter")))
        (is (= [#{"carter"}] (map :visible-to (of-type run :visits/reminded))))
        (is (hibernating? run "mrs-bell")))
      (until! run 8)
      (is (= :scheduled (status (e/world run) "visit-1")))
      (is (not (hibernating? run "carter")))
      (until! run 9)
      (testing "a guest who doesn't come lapses, seen by the GM only"
        (is (= :lapsed (status (e/world run) "visit-1")))
        (is (= [:gm] (map :visible-to (of-type run :visits/lapsed)))))
      (until! run 10)
      (is (hibernating? run "carter") "hibernation resumes once the visit lapses")
      (until! run 20)
      (is (hibernating? run "mrs-bell"))
      (until! run 21)
      (testing "the day before a 01:00 visit, she's woken at 06:00"
        (is (not (hibernating? run "mrs-bell")))
        (is (= #{"visit-1" "visit-2"} (set (map #(get-in % [:data :visit]) (of-type run :visits/reminded))))))
      (testing "managed entries are never queued for GM review"
        (let [managed (set (keep #(when (:managed-by %) (:id %)) (calendar/export-entries (e/world run))))
              queued (set (mapcat #(keys (get-in % [:data :revisions]))
                                  (concat (of-type run :calendar/due) (of-type run :calendar/reviewed))))]
          (is (= #{"visit-1-host" "visit-1-guest" "visit-2-guest" "visit-3-host" "visit-3-guest"} managed))
          (is (empty? (filter managed queued)))
          (is (every? #(= :pending (get-in (e/world run) [:calendar :entries % :lifecycle :status])) managed))))
      (testing "cancelling a visit not yet begun cancels its entries"
        (is (= "visit-1 is lapsed; only a visit not yet begun can be cancelled"
               (check run (e/world run) "gm" :cancel-visit {:id "visit-1"})))
        @(e/cancel-visit! run "visit-3")
        (is (= :cancelled (status (e/world run) "visit-3")))
        (is (not (str/includes? (calendar-of run "hale") "Mrs Bell")))
        (is (not (str/includes? (calendar-of run "mrs-bell") "18 October"))))
      (testing "the GM's schedule-visit makes the same record"
        (let [args {:who "carter" :date "1904-10-16" :time "15:00" :stay "to see the earl"}
              _ (is (nil? (check run (e/world run) "gm" :schedule-visit args)))
              evs ((:on-result (action run :schedule-visit)) (e/world run) "gm" args {})
              after (reduce w/apply-event (e/world run) evs)]
          (is (= :gm (:by (visits/visit after "visit-4"))))
          (is (:told? (visits/visit after "visit-4")))))
      (replay! run)
      (finally (e/stop-run run)))))

(deftest install-is-idempotent
  (doseq [scenario [cw/scenario medium/scenario]]
    (is (= 1 (count (filter #(= :invite (:name %)) (:actions scenario)))))
    (is (= 1 (count (filter #(= :visits/tick (:name %)) (:systems scenario)))))
    (is (= :visits/tick (:name (last (:systems scenario))))
        "after hiring's and deliveries' systems")))

(deftest visit-calendar-ids-are-reserved
  (let [run (start {} {:gm :llm})]
    (try
      (let [world (e/world run)]
        (testing "nobody else may add an entry with a visit's id, so a visit never clashes"
          (is (= "calendar IDs starting \"visit-\" are kept for visits; choose another ID"
                 (check run world "gm" :add-calendar-entry {:id "visit-1-host" :date "1904-10-16" :description "Mine."})))
          (is (thrown-with-msg? Exception #"are kept for visits"
                                @(e/add-calendar-entry! run {:id "visit-1-guest" :date "1904-10-16" :description "Mine."})))
          (is (nil? (check run world "gm" :add-calendar-entry {:id "visiting-day" :date "1904-10-16" :description "Fine."})))))
      @(e/schedule-visit! run {:who "carter" :date "1904-10-16" :time "15:00" :stay "to see the earl" :known-to ["hale"]})
      (is (= #{"visit-1-host" "visit-1-guest"}
             (set (keep #(when (:managed-by %) (:id %)) (calendar/export-entries (e/world run))))))
      (testing "world-file export leaves managed entries out"
        @(e/add-calendar-entry! run {:id "harvest-supper" :date "1904-10-20" :description "The harvest supper."})
        (let [start (store/read-world-start (:dir run))
              exported (worldfile/export (e/world run) start)]
          (is (some #{"harvest-supper"} (map :id (:calendar exported))))
          (is (not-any? #(str/starts-with? (:id %) "visit-") (:calendar exported)))
          (is (not-any? :managed-by (:calendar exported)))))
      (finally (e/stop-run run)))))

(deftest operator-visits-check-stay-and-reason
  (let [run (start {} {})]
    (try
      (is (thrown-with-msg? Exception #"stay must be 1 to 200 characters"
                            @(e/schedule-visit! run {:who "carter" :date "1904-10-16" :time "15:00"})))
      (is (thrown-with-msg? Exception #"stay must be 1 to 200 characters"
                            @(e/schedule-visit! run {:who "carter" :date "1904-10-16" :time "15:00" :stay (apply str (repeat 201 "x"))})))
      (is (thrown-with-msg? Exception #"reason must be 1 to 300 characters"
                            @(e/schedule-visit! run {:who "carter" :date "1904-10-16" :time "15:00" :stay "to tea"
                                                     :reason (apply str (repeat 301 "x"))})))
      (is (empty? (visits/visits (e/world run))))
      (finally (e/stop-run run)))))

(deftest scenario-visits-config-between-defaults-and-run-option
  (let [scenario (visits/install (assoc cw/scenario :visits {:lead-hours {:post 2 :messenger 2} :lapse-hours 5}))
        run (start-with scenario {} {:visits {:lead-hours {:messenger 4}}})]
    (try
      (is (= {:lead-hours {:in-person 0 :messenger 4 :post 2} :from-away-hours 24 :wake-from "06:00"
              :lapse-hours 5 :house "Claudeworth Manor"}
             (visits/config (e/world run))))
      (finally (e/stop-run run)))))

(def messenger-day
  "Hale goes down to the post office at 20:00, where the earl gives him a
  message for Mrs Bell at 20:30."
  {"hale" {"20:00" [[:travel {:to "post-office"}]]}
   "lord-claudeworth" {"20:30" [[:invite {:by "messenger" :via "hale" :who "mrs-bell" :date "1904-10-16"
                                          :time "15:00" :stay "to tea"}]]}})

(deftest messenger-invitation-delivered
  (let [run (start messenger-day {})]
    (try
      (until! run 2)
      (let [world (e/world run) v (visits/visit world "visit-1")]
        (is (= [#{"lord-claudeworth" "hale"}] (map :visible-to (of-type run :visits/entrusted))))
        (is (= (round-at world "1904-10-15T21:30") (:deliver v)))
        (is (not (:told? v))))
      (until! run 3)
      (is (empty? (of-type run :visits/delivered)))
      (until! run 4)
      (testing "she's told at the end of the messenger's lead time, without saying he came"
        (let [[delivered] (of-type run :visits/delivered)]
          (is (= #{"mrs-bell"} (:visible-to delivered)))
          (is (= "A message comes from Lord Claudeworth (sent with Hale): you are invited to Claudeworth Manor on Sunday 16 October at 15:00: to tea."
                 (n/describe delivered (n/names (e/world run))))))
        (is (str/includes? (calendar-of run "mrs-bell") "invited by Lord Claudeworth: to tea."))
        (is (not (str/includes? (calendar-of run "hale") "Mrs Bell"))))
      (replay! run)
      (finally (e/stop-run run)))))

;; ---------------------------------------------------------------------------
;; Phase 2, part A: guests from elsewhere

(def arden
  "Someone new, as the GM gives them."
  {:name "Mr Arden" :sex "male" :age 34 :from "Ely" :known-as "a curate from Ely"
   :situation "You are Mr Arden, curate of Ely, and you have never met the earl; SECRET-SITUATION."})

(defn- gm-then
  "A policy: the GM calls `tool` with `args` once, in round `round`;
  everyone else follows `day`."
  [round tool args day]
  (fn [{:keys [role used world] :as ctx}]
    (if (= :gm role)
      (if (and (= round (:round world)) (not (some #{(name tool)} used)))
        {:tool tool :args args}
        {:tool :done})
      (cw/scripted-policy day ctx))))

(def arden-day
  "The earl waits at the carriage sweep. Mr Arden notes something when he
  comes, and takes his leave the next round; the earl then walks in and
  sends him an invitation by Hale, and Mr Arden comes again at 01:00."
  {"lord-claudeworth" {"20:00" [[:travel {:to "carriage-sweep"}]]
                       "23:00" [[:walk {:to "drawing-room"}]
                                [:invite {:by "messenger" :via "hale" :who "mr-arden" :date "1904-10-16"
                                          :time "01:00" :stay "a week"}]
                                [:pass {}]]}
   "mr-arden" {"22:00" [[:note {:text "MEMORY-SENTINEL"}] [:pass {}]]
               "22:30" [[:take-leave {}]]}})

(defn- visible-before [run id round]
  (filter #(and (w/visible-to? % id) (< (:round %) round)) (e/events run)))

(deftest gm-secret-visit-by-someone-new
  (let [run (start-policy cw/scenario
                          (gm-then 1 :schedule-visit (merge arden {:date "1904-10-15" :time "22:00" :stay "to call"}) arden-day)
                          {:gm :llm :gm-check-in-every 1 :visits {:from-away-hours 1}})]
    (try
      (until! run 1)
      (let [world (e/world run) a (w/agent world "mr-arden")]
        (testing "the GM's new person is written in the query, spawned and away at once, all :system"
          (is (= :away (:status a)))
          (is (= {:name "Mr Arden" :role :guest :lives "Ely" :known-as "a curate from Ely" :sex :male}
                 (select-keys a [:name :role :lives :known-as :sex])))
          (is (str/includes? (:situation a) "SECRET-SITUATION"))
          (is (seq (:persona a)))
          (is (= {:position :guest :household nil} (select-keys (positions/post world "mr-arden") [:position :household])))
          (is (= :system (:visible-to (first (filter #(and (= :agent/spawned (:type %)) (= "mr-arden" (get-in % [:data :agent :id])))
                                                     (e/events run)))))))
        (testing "nothing called out: the people server's stub and the mock writers"
          (is (seq (of-type run :bridge/called)))
          (is (every? #(nil? (get-in % [:data :error])) (of-type run :bridge/called)))
          (is (empty? (of-type run :adjudication/failed))))
        (let [v (visits/visit world "visit-1")]
          (is (= {:guest "mr-arden" :by :gm :status :scheduled :told? false}
                 (select-keys v [:guest :by :status :told?])))))
      (until! run 4)
      (testing "nobody is told in advance"
        (doseq [id ["lord-claudeworth" "hale" "mrs-bell" "carter"]]
          (is (not-any? #(str/includes? (pr-str %) "mr-arden") (visible-before run id 5)) id)
          (is (not (str/includes? (calendar-of run id) "Arden")) id)))
      (until! run 5)
      (let [world (e/world run)
            [arrived] (filter #(get-in % [:data :at]) (of-type run :visits/arrived))]
        (testing "he arrives at the meeting place at the time, seen only by those there"
          (is (= "carriage-sweep" (space/location-of world "mr-arden")))
          (is (= :here (status world "visit-1")))
          (is (= #{"lord-claudeworth" "mr-arden"} (:visible-to arrived)))
          (is (= "A visitor arrives at the Carriage sweep: Mr Arden (mr-arden), a curate from Ely."
                 (n/describe arrived (n/names world))))
          (is (= :system (:visible-to (first (of-type run :people/returned))))))
        (testing "his view: his visit, the house's bedrooms, and the guest's brief"
          (let [view (prompt/agent-view (:scenario run) world "mr-arden")]
            (is (= {:invited-for "to call" :arrived "22:00"} (get-in view [:you :visit])))
            (is (some #(= "blue-room" (:id %)) (get-in view [:household :bedrooms]))))
          (is (str/includes? (cw/agent-brief world "mr-arden") "`visit` under `you`"))
          (is (not (str/includes? (pr-str (prompt/agent-messages (:scenario run) world (e/events run) "lord-claudeworth" {}))
                                  "SECRET-SITUATION")))))
      (until! run 6)
      (let [world (e/world run)]
        (testing "he takes his leave: away towards Ely, the visit over, and the earl saw him go"
          (is (= :away (:status (w/agent world "mr-arden"))))
          (is (= :ended (status world "visit-1")))
          (is (= ["Ely"] (map #(get-in % [:data :towards])
                              (filter #(= "takes leave" (get-in % [:data :reason])) (of-type run :people/departed)))))
          (is (= [{:id "mr-arden" :name "Mr Arden" :place "Carriage sweep" :at "22:30"}]
                 (:away-acquaintances (prompt/agent-view (:scenario run) world "lord-claudeworth"))))))
      (until! run 7)
      (testing "the earl invites him again by who: the lead time from away applies"
        (let [v (visits/visit (e/world run) "visit-2")]
          (is (= {:guest "mr-arden" :host "lord-claudeworth" :by :messenger :status :scheduled}
                 (select-keys v [:guest :host :by :status])))))
      (until! run 11)
      (let [world (e/world run) a (w/agent world "mr-arden")]
        (testing "and he comes back as the same agent, with his memories"
          (is (= :here (status world "visit-2")))
          (is (= "carriage-sweep" (:location a)))
          (is (= 1 (count (filter #(= "Mr Arden" (:name %)) (vals (:agents world))))))
          (is (= ["MEMORY-SENTINEL"] (map :text (:notes a))))
          (is (str/includes? (pr-str (prompt/agent-messages (:scenario run) world (e/events run) "mr-arden" {}))
                             "MEMORY-SENTINEL"))
          (is (= {:host "Lord Claudeworth" :invited-for "a week" :arrived "01:00"}
                 (get-in (prompt/agent-view (:scenario run) world "mr-arden") [:you :visit])))))
      (is (empty? (of-type run :action/forfeited)))
      (replay! run)
      (finally (e/stop-run run)))))

(deftest away-guests-wait-for-room-and-never-lapse
  (let [scenario (-> cw/scenario (assoc-in [:limits :max-agents] 4) (hiring/install "carriage-sweep"))
        run (start-with scenario {} {})]
    (try
      (testing "the world file's away friend is in the run, away, from the start"
        (is (= :away (:status (w/agent (e/world run) "captain-hart"))))
        (is (every? #(= :system (:visible-to %))
                    (filter #(= "captain-hart" (or (get-in % [:data :agent :id]) (get-in % [:data :id])))
                            (filter #(#{:agent/spawned :people/departed} (:type %)) (e/events run))))))
      (testing "someone away can be invited; the lead time from away is added"
        (let [world (assoc-in (e/world run) [:agents "hale" :location] "post-office")]
          (is (= "by post, the earliest they can be here is Sunday 16 October, 23:00"
                 (check run world "hale" :invite {:by "post" :who "captain-hart" :date "1904-10-16" :time "10:00" :stay "x"})))))
      @(e/schedule-visit! run {:who "captain-hart" :date "1904-10-15" :time "21:00" :stay "to dine"
                               :bedroom "blue-room" :known-to ["lord-claudeworth"]})
      (until! run 3)
      (testing "with the agent cap full, he waits"
        (is (= :away (:status (w/agent (e/world run) "captain-hart"))))
        (is (= :scheduled (status (e/world run) "visit-1"))))
      (until! run 12)
      (is (= :scheduled (status (e/world run) "visit-1")) "an away guest never lapses")
      @(e/retire! run "carter" "moved away")
      (until! run 13)
      (let [world (e/world run)]
        (testing "once there's room he arrives, with the named bedroom"
          (is (= "carriage-sweep" (space/location-of world "captain-hart")))
          (is (= :here (status world "visit-1")))
          (is (= "blue-room" (get-in world [:state :bedrooms "captain-hart"])))
          (is (= {:invited-for "to dine" :bedroom "blue-room" :arrived "02:00"}
                 (get-in (prompt/agent-view (:scenario run) world "captain-hart") [:you :visit])))))
      (replay! run)
      (finally (e/stop-run run)))))

(deftest returning-candidate-and-courier-get-a-guest-situation
  (let [run (start {} {})
        scenario (:scenario run)
        world (e/world run)
        person (fn [n] {:name n :persona "p" :appearance "a" :lives "a cottage in Ely"
                        :record {:name n :age 30 :sex "female" :temperament [] :faculties [] :circumstances []}})
        spawn (fn [world n situation]
                (let [evs (b/spawn-events scenario world (merge (hiring/spawn-spec (person n))
                                                                {:role :none :location "carriage-sweep" :situation situation}))]
                  [(get-in (first evs) [:data :agent :id]) (reduce w/apply-event world evs)]))
        [cand world] (spawn world "Pat Ellis" "You have come about the cook place, sent for by Lord Claudeworth.")
        [courier world] (spawn world "Stephen Dobbs" "You carry for the Post office. You have brought up a parcel.")
        world (reduce w/apply-event world
                      [{:type :people/pooled :data {:id "candidate-1" :agent-id cand :seeks [:cook] :status :away
                                                    :lives "a cottage in Ely" :known-to #{}}}
                       {:type :deliveries/courier-updated :data {:shop "post-office" :changes {:agent-id courier :person (person "Stephen Dobbs")}}}
                       {:type :people/departed :data {:id cand :reason "takes leave" :towards "Ely"}}
                       {:type :people/departed :data {:id courier :reason "the delivery is made" :towards "the shop"}}])
        world (reduce w/apply-event world (concat (visits/operator-events world {:who cand :date "1904-10-15" :time "21:00" :stay "to tea"})
                                                  ))
        world (reduce w/apply-event world (visits/operator-events world {:who courier :date "1904-10-15" :time "21:00" :stay "to tea"}))
        world (assoc world :round 3)
        events (visits/tick scenario world)
        after (reduce w/apply-event world events)]
    (try
      (is (= :here (:status (visits/visit after "visit-1"))))
      (is (= "You are Pat Ellis, of a cottage in Ely. You came to Claudeworth Manor once before, about the cook place."
             (:situation (w/agent after cand))))
      (is (= "You are Stephen Dobbs, of a cottage in Ely; you carry for the Post office."
             (:situation (w/agent after courier))))
      (finally (e/stop-run run)))))

(deftest pool-eviction-leaves-away-agents-and-visits
  (let [run (start {} {})
        base (-> (e/world run) (assoc-in [:agents "hale" :location] "post-office"))
        pooled (for [i (range hiring/pool-limit)]
                 {:type :people/pooled :data {:id (str "candidate-" i) :created i :area "post-office" :seeks [:cook]
                                              :known-to #{} :status :pooled :name (str "Pooled " i)}})
        world (reduce w/apply-event base pooled)
        world (reduce w/apply-event world (visits/operator-events world {:who "captain-hart" :date "1904-10-16" :time "10:00" :stay "a week"}))
        agents (:agents world)
        fresh {:id "candidate-new" :created 99 :area "post-office" :seeks [:cook] :known-to #{} :status :pooled
               :name "New One" :record {:name "New One"}}
        events (hiring/recommendation-events world "lord-claudeworth" {:position "cook" :for "hale"}
                                             {:candidate fresh :new? true :reputation {:acquaintance "x" :knows {}}})
        after (reduce w/apply-event world events)]
    (try
      (is (= [{:id "candidate-0"}] (map :data (filter #(= :people/evicted (:type %)) events))))
      (is (= hiring/pool-limit (count (hiring/pool after))))
      (is (= agents (:agents after)) "no agent is evicted, away ones included")
      (is (= :away (:status (w/agent after "captain-hart"))))
      (is (= (visits/visits world) (visits/visits after)))
      (finally (e/stop-run run)))))

(deftest the-son-can-be-invited-home
  (let [run (e/start-run :claudeworth-medium {:llm :mock :offline? true :test? true :run-id (str (gensym "medium-visit-"))})]
    (try
      (let [world (e/world run)]
        (is (= :away (:status (w/agent world "master-claudeworth"))))
        (is (not= "Master Claudeworth lives here"
                  (check run world "lord-claudeworth" :invite {:by "post" :who "master-claudeworth" :date "1904-10-20"
                                                               :time "14:00" :stay "the holidays"}))))
      @(e/schedule-visit! run {:who "master-claudeworth" :date "1904-10-15" :time "08:30" :stay "a few days' holiday"
                               :known-to ["lord-claudeworth"]})
      (until! run 2)
      (let [world (e/world run)
            [arrived] (filter #(get-in % [:data :at]) (of-type run :visits/arrived))]
        (is (= "carriage-sweep" (space/location-of world "master-claudeworth")))
        (is (= "sons-room" (get-in world [:state :bedrooms "master-claudeworth"])))
        (is (= "Master Claudeworth (master-claudeworth) arrives at the Carriage sweep."
               (n/describe arrived (n/names world))))
        (is (contains? (:visible-to arrived) "master-claudeworth"))
        (is (str/includes? (:situation (w/agent world "master-claudeworth")) "Eton") "his own situation is kept"))
      (replay! run)
      (finally (e/stop-run run)))))

(deftest a-guest-who-goes-away-first-arrives-from-elsewhere
  (let [run (start {} {})
        world (e/world run)
        world (reduce w/apply-event world (visits/operator-events world {:who "carter" :date "1904-10-15" :time "21:00" :stay "a word"}))
        _ (is (:told? (visits/visit world "visit-1")))
        world (w/apply-event world {:type :people/departed :data {:id "carter" :reason "gone to market" :towards "Ely"}})
        world (assoc world :round (:arrive (visits/visit world "visit-1")))
        after (reduce w/apply-event world (visits/tick (:scenario run) world))]
    (try
      (is (= :here (:status (visits/visit after "visit-1"))))
      (is (:from-away? (visits/visit after "visit-1")))
      (is (= "carriage-sweep" (space/location-of after "carter")))
      (finally (e/stop-run run)))))

;; ---------------------------------------------------------------------------
;; Phase 2, part B: `person` and the invitation judge

(defn- with-judge
  "`scenario` with `mock` standing in for the invitation judge."
  [scenario mock]
  (assoc-in scenario [:adjudicators :visits/invitee :mock] mock))

(defn- judge-calls [run] (filter #(= "adjudicator/visits/invitee" (:agent %)) (store/read-llm (:dir run))))
(defn- judge-input [call] (json/read-value (:content (second (:messages call))) json/keyword-keys-object-mapper))
(defn- replies-to [run agent tool]
  (for [call (store/read-llm (:dir run)) :when (= agent (:agent call))
        tc (get-in call [:response :tool_calls]) :when (= tool (get-in tc [:function :name]))
        r (:replies call) :when (= (:id tc) (:tool_call_id r))]
    (:content r)))

(deftest invite-is-free-with-who-and-a-query-with-person
  (let [run (start {} {})
        invite (action run :invite)]
    (try
      (is (= :free (a/kind invite {:who "mrs-bell"})))
      (is (= :query (a/kind invite {:person "Mrs Bell"})))
      (is (str/includes? (get-in (a/->tool invite) [:function :description]) "my father, Sir Henry Vane"))
      (is (str/includes? visits/rules "my father, Sir Henry Vane"))
      (let [world (e/world run)]
        (is (= "give who (their id) or person (who they are, in your words), not both"
               (check run world "lord-claudeworth" :invite {:by "post" :who "mrs-bell" :person "Mrs Bell"
                                                            :date "1904-10-16" :time "10:00" :stay "x"})))
        (is (= "say whom you're inviting: who (their id) or person (who they are, in your words)"
               (check run world "lord-claudeworth" :invite {:by "post" :date "1904-10-16" :time "10:00" :stay "x"})))
        (is (nil? (check run world "lord-claudeworth" :invite {:by "in-person" :person "Mrs Bell"
                                                               :date "1904-10-16" :time "10:00" :stay "x"}))
            "in person waits for the judge to say who"))
      (finally (e/stop-run run)))))

(defn- father-judge
  "A judge that writes the inviter's father from what it's told of the inviter."
  [{:keys [inviter description] :as ctx}]
  (if (str/includes? description "Visitsworth")
    {:tool :invitee
     :args {:new {:name "Baron von Visitsworth" :sex "male" :age 78 :from "Schloss Visitsworth"
                  :known_as "an elderly baron from abroad"
                  :situation (str "You are Baron von Visitsworth, father of " (:name inviter) "; you last saw your son at Easter.")}}}
    (judge/mock-judge ctx)))

(def father-day
  {"lord-claudeworth" {"20:00" [[:invite {:by "post" :person "my father, Baron von Visitsworth" :date "1904-10-16"
                                          :time "00:00" :stay "a week" :reason "to meet the family"}]
                                [:pass {}]]}})

(deftest a-described-father-is-made-away-and-arrives
  (let [run (start-with (with-judge cw/scenario father-judge) father-day {:visits {:from-away-hours 1}})]
    (try
      (until! run 1)
      (let [world (e/world run)
            barons (filter #(= "Baron von Visitsworth" (:name %)) (vals (:agents world)))
            a (first barons)
            [call] (judge-calls run)
            input (judge-input call)]
        (testing "the judge read the inviter's own context and the description"
          (is (= "Lord Claudeworth" (get-in input [:inviter :name])))
          (is (= "Earl" (get-in input [:inviter :position])))
          (is (= "my father, Baron von Visitsworth" (:description input)))
          (is (= "to meet the family" (:reason input))))
        (testing "one away agent, consistent with the inviter, all :system"
          (is (= 1 (count barons)))
          (is (= {:status :away :role :guest :lives "Schloss Visitsworth" :known-as "an elderly baron from abroad"
                  :age 78 :sex :male}
                 (select-keys a [:status :role :lives :known-as :age :sex])))
          (is (str/includes? (:situation a) "father of Lord Claudeworth"))
          (is (every? #(= :system (:visible-to %))
                      (filter #(= (:id a) (or (get-in % [:data :agent :id]) (get-in % [:data :id])))
                              (filter #(#{:agent/spawned :agent/updated :people/departed} (:type %)) (e/events run))))))
        (testing "the visit is the inviter's, by post, and the reply says who is expected"
          (is (= {:guest (:id a) :host "lord-claudeworth" :by :post :status :scheduled}
                 (select-keys (visits/visit world "visit-1") [:guest :host :by :status])))
          (is (= ["Baron von Visitsworth (baron-von-visitsworth) is expected at Claudeworth Manor on Sunday 16 October at 00:00."]
                 (replies-to run "lord-claudeworth" "invite"))))
        (testing "nothing called out: the people stub and mock writers"
          (is (seq (of-type run :bridge/called)))
          (is (every? #(nil? (get-in % [:data :error])) (of-type run :bridge/called)))
          (is (empty? (of-type run :adjudication/failed)))))
      (until! run 9)
      (let [world (e/world run)
            [arrived] (filter #(get-in % [:data :at]) (of-type run :visits/arrived))]
        (testing "he arrives at the meeting place at the time, seen by the host"
          (is (= "carriage-sweep" (space/location-of world "baron-von-visitsworth")))
          (is (= :here (status world "visit-1")))
          (is (contains? (:visible-to arrived) "lord-claudeworth"))
          (is (= "A visitor arrives at the Carriage sweep: Baron von Visitsworth (baron-von-visitsworth), an elderly baron from abroad."
                 (n/describe arrived (n/names world))))))
      (is (empty? (of-type run :action/forfeited)))
      (replay! run)
      (finally (e/stop-run run)))))

(def arden-judge
  "Writes Mr Arden for the earl's description; otherwise the mock judge."
  (fn [{:keys [description] :as ctx}]
    (if (= "Mr Arden, a curate from Ely" description)
      {:tool :invitee :args {:new arden}}
      (judge/mock-judge ctx))))

(def two-inviters-day
  "The earl, at the post office, invites Mr Arden by post; Hale goes down and
  invites \"the curate from Ely\" there half an hour later."
  {"lord-claudeworth" {"20:00" [[:invite {:by "post" :person "Mr Arden, a curate from Ely" :date "1904-10-16"
                                          :time "10:00" :stay "STAY-SENTINEL" :reason "REASON-SENTINEL"}]
                                [:pass {}]]}
   "hale" {"20:00" [[:travel {:to "post-office"}]]
           "20:30" [[:invite {:by "post" :person "the curate from Ely" :date "1904-10-17" :time "10:00" :stay "to tea"}]
                    [:pass {}]]}})

(deftest a-second-inviter-gets-the-first-inviters-guest
  (let [run (start-with (with-judge cw/scenario arden-judge) two-inviters-day {:visits {:from-away-hours 1}})]
    (try
      (until! run 2)
      (let [world (e/world run)
            [_ hales] (judge-calls run)
            input (judge-input hales)
            listed (first (filter #(= "mr-arden" (:id %)) (get-in input [:people :others])))]
        (testing "both visits are of the same Mr Arden"
          (is (= 1 (count (filter #(= "Mr Arden" (:name %)) (vals (:agents world))))))
          (is (= [["mr-arden" "lord-claudeworth"] ["mr-arden" "hale"]]
                 (map (juxt :guest :host) (map #(visits/visit world %) ["visit-1" "visit-2"]))))
          (is (= ["Mr Arden (mr-arden) is expected at Claudeworth Manor on Monday 17 October at 10:00."]
                 (replies-to run "hale" "invite"))))
        (testing "Hale's judge read his own recent lines"
          (is (some #(str/includes? % "Hale") (get-in input [:inviter :recent])) (pr-str (get-in input [:inviter :recent]))))
        (testing "Hale's judge saw him only by name, home and public description"
          (is (= {:id "mr-arden" :name "Mr Arden" :from "Ely" :known-as "a curate from Ely"} listed))
          (doseq [secret ["SECRET-SITUATION" "STAY-SENTINEL" "REASON-SENTINEL" "1904-10-16" "visit-1"]]
            (is (not (str/includes? (pr-str (:messages hales)) secret)) secret)))
        (testing "while the earl's own guest is shown to him with who they are"
          (let [world' (assoc world :round 3)
                earls (judge/people world' "lord-claudeworth")]
            (is (= ["mr-arden"] (map :id (:your-guests earls))))
            (is (str/includes? (:situation (first (:your-guests earls))) "SECRET-SITUATION")))))
      (replay! run)
      (finally (e/stop-run run)))))

(deftest pugh-the-architect-is-pugh
  (let [run (e/start-run :claudeworth {:llm :mock :offline? true :test? true :gm :off :run-id (str (gensym "visits-pugh-"))})]
    (try
      (let [agents (count (:agents (e/world run)))
            {:keys [reply error]} (resolve/run-query! run "mrs-bell" (action run :invite)
                                                      {:by "post" :person "Pugh the architect" :date "1904-10-16"
                                                       :time "10:00" :stay "to talk about a new porch"})
            world (e/world run)]
        (is (nil? error))
        (is (= "Pugh (pugh) is expected at Claudeworth Manor on Sunday 16 October at 10:00." reply))
        (is (= {:guest "pugh" :host "mrs-bell" :by :post} (select-keys (visits/visit world "visit-1") [:guest :host :by])))
        (is (= agents (count (:agents world))) "no second Pugh")
        (replay! run))
      (finally (e/stop-run run)))))

(deftest impossible-schedules-nothing
  (let [run (start {} {})
        before (count (e/events run))
        {:keys [reply error]} (resolve/run-query! run "lord-claudeworth" (action run :invite)
                                                  {:by "post" :person "Lord Claudeworth himself" :date "1904-10-16"
                                                   :time "10:00" :stay "to dine"})]
    (try
      (is (nil? error))
      (is (= "That is you. Nothing is arranged." reply))
      (is (empty? (visits/visits (e/world run))))
      (is (empty? (filter #(not= :llm/usage (:type %)) (drop before (e/events run)))) "no events but usage")
      (replay! run)
      (finally (e/stop-run run)))))

(deftest a-judge-that-never-answers-well-fails-neutrally
  (let [run (start-with (with-judge cw/scenario (constantly {:tool :invitee :args {:existing "nobody"}})) {} {})
        {:keys [error]} (resolve/run-query! run "lord-claudeworth" (action run :invite)
                                            {:by "post" :person "Mrs Bell" :date "1904-10-16" :time "10:00" :stay "x"})]
    (try
      (is (= "The enquiry failed; try again later." error))
      (is (= 2 (count (judge-calls run))) "one correction")
      (is (empty? (visits/visits (e/world run))))
      (is (= [:system] (map :visible-to (of-type run :adjudication/failed))))
      (finally (e/stop-run run)))))

(deftest the-judges-request-and-checked-answer
  (let [run (start {} {})
        world (-> (e/world run)
                  (assoc-in [:agents "lord-claudeworth" :notes] [{:round 1 :text "NOTE-SENTINEL"}])
                  (assoc-in [:agents "lord-claudeworth" :persona] "NATURE-SENTINEL")
                  (assoc-in [:agents "lord-claudeworth" :situation] "SITUATION-SENTINEL"))
        world (reduce w/apply-event world (visits/invite-events world "lord-claudeworth"
                                                                {:by "post" :who "captain-hart" :date "1904-10-17"
                                                                 :time "10:00" :stay "STAY-SENTINEL"}))
        request (judge/invitation-request world "lord-claudeworth" {:person "DESCRIPTION-SENTINEL" :reason "REASON-SENTINEL"
                                                                    :recent ["[20:00] RECENT-SENTINEL"]})
        input (json/read-value (:content (second (:messages request))) json/keyword-keys-object-mapper)
        answer (fn [args] ((:check request) {:tool-calls [{:id "c1" :name "invitee" :arguments args}]}))]
    (try
      (testing "the inviter's own context"
        (is (= {:name "Lord Claudeworth" :position "Earl" :nature "NATURE-SENTINEL" :situation "SITUATION-SENTINEL"
                :notes [{:round 1 :text "NOTE-SENTINEL"}] :recent ["[20:00] RECENT-SENTINEL"]}
               (select-keys (:inviter input) [:name :position :nature :situation :notes :recent])))
        (is (= ["DESCRIPTION-SENTINEL" "REASON-SENTINEL"] [(:description input) (:reason input)])))
      (testing "the people they could mean, each once"
        (let [{:keys [your-guests known others]} (:people input)]
          (is (= ["captain-hart"] (map :id your-guests)))
          (is (str/includes? (:situation (first your-guests)) "old friend"))
          (is (= ["hale" "mrs-bell" "carter"] (map :id known)))
          (is (= {:id "carter" :name "Carter" :position "Blacksmith" :lives "Blacksmith's room"} (last known)))
          (is (= "Claudeworth Manor" (:lives (first known))))
          (is (empty? others))))
      (testing "someone else's guest is shown to Hale by public fields only"
        (let [hales (judge/people world "hale")]
          (is (= [{:id "captain-hart" :name "Captain Hart"}] (:others hales)))
          (is (not (str/includes? (pr-str (:messages (judge/invitation-request world "hale" {:person "x" :recent []}))) "old friend")))))
      (testing "code checks only the answer's shape"
        (is (= {:existing "carter"} (:verdict (answer {:existing "carter"}))))
        (is (= "existing must be the id of someone listed in people" (:error (answer {:existing "lord-claudeworth"}))))
        (is (= "give exactly one of existing, new or impossible" (:error (answer {:existing "carter" :impossible "x"}))))
        (is (= "a curate from Ely" (get-in (answer (assoc-in {:new (dissoc arden :known-as)} [:new :known_as] "a curate from Ely"))
                                           [:verdict :new :known-as]))
            "known_as is read as known-as")
        (is (str/starts-with? (:error (answer {:new (assoc arden :name "Carter")})) "Carter is listed already (carter)"))
        (is (:error (answer {:new (assoc arden :age 200)})))
        (is (= {:impossible "Your father died in 1898."} (:verdict (answer {:impossible "Your father died in 1898."}))))
        (is (= "ok" (:content (first (:replies (answer {:existing "carter"})))))))
      (finally (e/stop-run run)))))
