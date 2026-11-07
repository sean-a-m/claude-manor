(ns manor.appointments-test
  "Appointments on the bundled Claudeworth world (mock and offline):
  `appoint`, `dismiss`, `assign-bedroom`, the GM's `change-post` and the post
  check that admits the first two."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [manor.actions :as a]
            [manor.engine :as e]
            [manor.engine.resolve :as resolve]
            [manor.io.store :as store]
            [manor.mechanics.appointments :as appointments]
            [manor.mechanics.appointments.post-check :as post-check]
            [manor.mechanics.positions :as positions]
            [manor.narrate :as n]
            [manor.prompt :as prompt]
            [manor.scenarios.claudeworth :as cw]
            [manor.test-support :as support]
            [manor.world :as w]))

(use-fixtures :once support/with-runs)

(def refusal "A housemaid cannot dismiss the lady of the house.")

(defn- start
  "A Claudeworth run whose post check answers with `checker` (default: allowed),
  counting its calls in `calls`."
  ([] (start (constantly {:allowed true}) (atom 0)))
  ([checker calls]
   (let [scenario (assoc-in cw/scenario [:adjudicators :people/post-check :mock]
                            (fn [ctx] (swap! calls inc) {:tool :check-post :args (checker ctx)}))]
     (with-redefs [e/load-scenario (constantly scenario)]
       (e/start-run :claudeworth {:llm :mock :offline? true :test? true :gm :off :start "1904-10-15T08:00"
                                  :run-id (str (gensym "appointments-"))})))))

(defn- action [run name] (first (filter #(= name (:name %)) (:actions run))))
(defn- act! [run actor name args] (resolve/resolve! run actor (action run name) args))
(defn- replayed? [run] (= (e/world run) (w/replay (store/read-events (:dir run)))))
(defn- tools [run world id]
  (set (map :name (a/for-role (:actions run) (:role (w/agent world id)) (positions/capabilities world id)))))
(defn- new-events [run before] (drop before (e/events run)))
(defn- recent [run world id]
  (str/join "\n" (map :content (prompt/agent-messages (:scenario run) world (e/events run) id
                                                      (assoc (:opts run) :event-window 1000)))))

(deftest tools-only-for-appointers
  (let [run (start)
        world (e/world run)]
    (try
      (doseq [id ["lord-claudeworth" "lady-claudeworth" "mrs-finch"]]
        (is (every? (tools run world id) [:appoint :dismiss :assign-bedroom]) id)
        (is (not (contains? (tools run world id) :change-post)) id))
      (doseq [id ["hale" "nellie" "bert" "daisy" "briggs"]]
        (is (not-any? (tools run world id) [:appoint :dismiss :assign-bedroom]) id))
      (is (contains? (set (map :name (a/for-role (:actions run) :gm #{}))) :change-post))
      (finally (e/stop-run run)))))

(deftest appointing-changes-the-post-for-the-two-only
  (let [calls (atom 0)
        run (start (constantly {:allowed true}) calls)
        before (count (e/events run))]
    (try
      (is (:ok (act! run "lady-claudeworth" :appoint {:who "nellie" :position "cook" :terms "£28 a year"})))
      (let [world (e/world run)
            events (new-events run before)
            change (first (filter #(= :people/post-changed (:type %)) events))]
        (is (= 1 @calls))
        (testing "role, post and situation change together"
          (is (= :cook (:role (w/agent world "nellie"))))
          (is (= {:position :cook :household "household" :terms "£28 a year" :employer "lady-claudeworth"}
                 (select-keys (positions/post world "nellie") [:position :household :terms :employer])))
          (is (str/includes? (:situation (w/agent world "nellie")) "Lady Claudeworth has made you cook")))
        (testing "only the two see it, wherever they are"
          (is (= #{"lady-claudeworth" "nellie"} (:visible-to change)))
          (is (not (w/visible-to? change "hale")))
          (is (str/includes? (recent run world "nellie") "appointed by Lady Claudeworth"))
          (is (not (str/includes? (recent run world "hale") "now holds the post of Cook"))))
        (is (= "Nellie now holds the post of Cook, on these terms: £28 a year; appointed by Lady Claudeworth."
               (n/describe change (n/names world)))))
      (testing "the bedroom is unchanged"
        (is (= "maids-room" (get-in (e/world run) [:state :bedrooms "nellie"]))))
      (testing "appointing someone to the post they hold, or yourself, is refused before the check"
        (is (str/includes? (:rejected (act! run "lady-claudeworth" :appoint {:who "nellie" :position "cook"})) "already holds"))
        (is (= "you cannot appoint or dismiss yourself"
               (:rejected (act! run "lady-claudeworth" :appoint {:who "lady-claudeworth" :position "housekeeper"}))))
        (is (:rejected (act! run "lady-claudeworth" :appoint {:who "nellie" :position "guest"})))
        (is (:rejected (act! run "lady-claudeworth" :appoint {:who "nellie" :position "duke"})))
        (is (= 1 @calls)))
      (is (replayed? run))
      (finally (e/stop-run run)))))

(deftest a-refusal-changes-nothing
  (let [run (start (constantly {:allowed false :reason refusal}) (atom 0))
        world (e/world run)]
    (try
      (is (= {:rejected refusal} (act! run "mrs-finch" :dismiss {:who "lady-claudeworth"})))
      (is (= {:rejected refusal} (act! run "mrs-finch" :appoint {:who "lord-claudeworth" :position "scullery-maid"})))
      (is (= (get-in world [:state :posts]) (get-in (e/world run) [:state :posts])))
      (is (= :countess (:role (w/agent (e/world run) "lady-claudeworth"))))
      (is (replayed? run))
      (finally (e/stop-run run))))
  (testing "a failed check rejects too"
    (let [run (start (fn [_] (throw (ex-info "provider failed" {}))) (atom 0))]
      (try
        (is (:rejected (act! run "mrs-finch" :dismiss {:who "daisy"})))
        (is (= :scullery-maid (:role (w/agent (e/world run) "daisy"))))
        (finally (e/stop-run run))))))

(deftest dismissal
  (let [run (start)]
    (try
      (is (:ok (act! run "mrs-finch" :dismiss {:who "daisy" :reason "idle"})))
      (let [world (e/world run)
            events (e/events run)]
        (is (= :none (:role (w/agent world "daisy"))))
        (is (= {:position :none :household nil :employer "mrs-finch"}
               (select-keys (positions/post world "daisy") [:position :household :employer])))
        (is (not (positions/household-member? world "daisy")))
        (is (nil? (get-in world [:state :bedrooms "daisy"])))
        (is (str/includes? (:situation (w/agent world "daisy")) "take-leave"))
        (is (every? #(= #{"mrs-finch" "daisy"} (:visible-to %))
                    (filter #(and (#{:people/post-changed :people/bedroom-assigned} (:type %)) (= "daisy" (get-in % [:data :id])) (:actor %)) events)))
        (is (str/includes? (recent run world "daisy") "Daisy no longer holds a post: dismissed by Mrs Finch."))
        (testing "someone with no post can't be dismissed again"
          (is (str/includes? (:rejected (act! run "mrs-finch" :dismiss {:who "daisy"})) "no post"))))
      (is (replayed? run))
      (finally (e/stop-run run)))))

(deftest assigning-bedrooms
  (let [calls (atom 0)
        run (start (constantly {:allowed true}) calls)]
    (try
      (is (:ok (act! run "lady-claudeworth" :assign-bedroom {:who "nellie" :bedroom "kitchen-box-room"})))
      (is (= "kitchen-box-room" (get-in (e/world run) [:state :bedrooms "nellie"])))
      (let [given (last (filter #(= :people/bedroom-assigned (:type %)) (e/events run)))]
        (is (= #{"lady-claudeworth" "nellie"} (:visible-to given)))
        (is (= "Lady Claudeworth has given Nellie the Box-room off the kitchen to sleep in."
               (n/describe given (n/names (e/world run))))))
      (testing "self is allowed, with no judge"
        (is (:ok (act! run "lady-claudeworth" :assign-bedroom {:who "lady-claudeworth" :bedroom "drawing-room"})))
        (is (= "drawing-room" (get-in (e/world run) [:state :bedrooms "lady-claudeworth"])))
        (is (zero? @calls)))
      (testing "structure: a home room, someone of the household, not where they already sleep"
        (is (:rejected (act! run "lady-claudeworth" :assign-bedroom {:who "nellie" :bedroom "general-store"})))
        (is (:rejected (act! run "lady-claudeworth" :assign-bedroom {:who "briggs" :bedroom "maids-room"})))
        (is (:rejected (act! run "lady-claudeworth" :assign-bedroom {:who "nellie" :bedroom "kitchen-box-room"}))))
      (is (replayed? run))
      (finally (e/stop-run run)))))

(deftest the-gms-change-post
  (let [run (start)]
    (try
      (is (:ok (act! run "gm" :change-post {:who "hale" :position "butler" :terms "for the season"})))
      (let [world (e/world run)
            change (last (filter #(= :people/post-changed (:type %)) (e/events run)))]
        (is (= :butler (:role (w/agent world "hale"))))
        (is (= "household" (:household (positions/post world "hale"))))
        (is (= #{"hale"} (:visible-to change))))
      (is (:ok (act! run "gm" :change-post {:who "hale" :position "none" :household ""})))
      (is (nil? (:household (positions/post (e/world run) "hale"))))
      (is (:rejected (act! run "gm" :change-post {:who "hale" :position "duke"})))
      (is (replayed? run))
      (finally (e/stop-run run)))))

(deftest the-check-sees-only-the-proposal
  (let [run (start)
        world (e/world run)]
    (try
      (let [request (post-check/request world "lady-claudeworth" {:who "nellie" :position "cook" :reason "she cooks well"} [])
            data (json/read-value (get-in request [:messages 1 :content]) json/keyword-keys-object-mapper)]
        (is (= #{:action :by :who :to :reason :setting} (set (keys data))))
        (is (= "appoint" (:action data)))
        (is (= #{:name :post :about :household} (set (keys (:by data))) (set (keys (:who data)))))
        (is (= "Cook" (get-in data [:to :post])))
        (is (= (get-in world [:state :setting]) (:setting data))))
      (let [data (json/read-value (get-in (post-check/request world "mrs-finch" {:who "daisy"} []) [:messages 1 :content])
                                  json/keyword-keys-object-mapper)]
        (is (= "dismiss" (:action data)))
        (is (not (contains? data :to))))
      (testing "a refusal needs a reason"
        (is (= {:allowed true} (post-check/check-answer {:allowed true})))
        (doseq [bad [{} {:allowed false} {:allowed false :reason " "} {:allowed "maybe"}]]
          (is (:error (post-check/check-answer bad)))))
      (is (= appointments/adjudicators (select-keys (get-in cw/scenario [:adjudicators]) [:people/post-check])))
      (finally (e/stop-run run)))))
