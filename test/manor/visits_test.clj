(ns manor.visits-test
  "Visits, phase 1 (docs/visits-design.md): guests in the world, invited by
  post, messenger or in person, or arranged by the GM or operator. Runs use
  the production Claudeworth mechanics on the test-owned world
  test/fixtures/visits.edn (start 1904-10-15 20:00, half-hour rounds)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [manor.actions :as a]
            [manor.engine :as e]
            [manor.io.store :as store]
            [manor.io.worldfile :as worldfile]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.calendar :as calendar]
            [manor.mechanics.space :as space]
            [manor.mechanics.visits :as visits]
            [manor.narrate :as n]
            [manor.prompt :as prompt]
            [manor.scenarios.claudeworth :as cw]
            [manor.scenarios.claudeworth-medium :as medium]
            [manor.world :as w])
  (:import (java.time LocalDateTime)))

(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

(def world-file "test/fixtures/visits.edn")

(defn- start-with [scenario day opts]
  (with-redefs [e/load-scenario (constantly (assoc scenario :mock-policy #(cw/scripted-policy day %)))]
    (e/start-run :claudeworth (merge {:test? true :llm :mock :offline? true :gm :off :max-cost 0.20
                                      :world-file world-file :run-id (str (gensym "visits-"))}
                                     opts))))

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
        (let [evs (effect run (e/world run) "gm" :schedule-visit
                          {:who "carter" :date "1904-10-16" :time "15:00" :stay "to see the earl"})
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
