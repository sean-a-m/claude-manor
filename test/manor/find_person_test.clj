(ns manor.find-person-test
  "find-person (manor.mechanics.find-person): who the judge is shown, its
  checked answer, the seeded roll, the replies and replay."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [manor.actions :as a]
            [manor.engine :as e]
            [manor.engine.resolve :as resolve]
            [manor.io.llm :as llm]
            [manor.io.store :as store]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.find-person :as fp]
            [manor.narrate :as n]
            [manor.rng :as rng]
            [manor.world :as w]))

(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

(defn- start [& [opts]]
  (e/start-run :household (merge {:llm :mock :offline? true :test? true :run-id (str (gensym "find-person-"))} opts)))

(defn- action [run] (get (a/by-name (:actions run)) "find-person"))
(defn- seek! [run who] (resolve/run-query! run "lord-claudeworth" (action run) {:who who}))
(defn- response [args] {:tool-calls [{:id "s1" :name "seek" :arguments args}]})
(defn- sought [run] (filter #(= :people/sought (:type %)) (e/events run)))

(defn- replays? [run]
  (= (e/world run) (with-redefs [rng/roll (fn [& _] (throw (Exception. "no roll on replay")))
                                 llm/complete (fn [& _] (throw (Exception. "no call on replay")))]
                     (w/replay (store/read-events (:dir run))))))

(deftest the-judge-sees-only-the-people-about
  (let [run (start)
        world (w/apply-event (e/world run) (b/occupy (e/world run) "hale" "copying letters" 5))]
    (try
      (let [{:keys [messages ctx]} (fp/seek-request world "lord-claudeworth" {:who "my secretary" :recent ["a line"]})
            user (json/read-value (:content (second messages)) json/keyword-keys-object-mapper)
            people (:people user)]
        (testing "everyone within walking distance but the seeker; not the village"
          (is (= ["hale" "lady-claudeworth"] (map :id people)))
          (is (= people (:people ctx))))
        (testing "only what anyone could see of them: where they are and what they're doing, no persona or notes"
          (is (= "copying letters" (:doing (first (filter #(= "hale" (:id %)) people)))))
          (is (every? :place people))
          (is (not-any? #(some % [:nature :persona :notes :situation :inner]) people)))
        (testing "the seeker's own context and recent lines"
          (is (= "Lord Claudeworth" (get-in user [:seeker :name])))
          (is (= ["a line"] (get-in user [:seeker :recent])))
          (is (= "my secretary" (:who user)))))
      (finally (e/stop-run run)))))

(deftest the-answer-is-checked
  (let [run (start)
        check (:check (fp/seek-request (e/world run) "lord-claudeworth" {:who "Hale" :recent []}))
        verdict #(let [{:keys [verdict error]} (check (response %))] (or verdict error))]
    (try
      (is (= {:person "hale" :likelihood "certain"} (verdict {:person "hale" :likelihood "certain"})))
      (is (= {:nobody true} (verdict {:nobody true})))
      (is (= "person must be the id of someone listed in people" (verdict {:person "pugh" :likelihood "certain"}))
          "someone in the village isn't about")
      (is (= "person needs a likelihood" (verdict {:person "hale"})))
      (is (= "give either person with likelihood, or nobody" (verdict {:person "hale" :likelihood "certain" :nobody true})))
      (is (= "give either person with likelihood, or nobody" (verdict {})))
      (finally (e/stop-run run)))))

(deftest finding-someone-says-where-they-are
  (let [run (start)]
    (try
      (e/emit! run [(b/occupy (e/world run) "hale" "copying letters" 5)])
      (let [{:keys [reply]} (seek! run "Hale, my secretary")
            [event] (sought run)]
        (is (= "Hale (hale) is in the Secretary's room (secretarys-room), copying letters. To go there, walk to secretarys-room."
               reply))
        (testing "a private record of the odds, the roll and where they were found; no one moves"
          (is (= #{"lord-claudeworth"} (:visible-to event)))
          (is (= {:who "Hale, my secretary" :outcome :found :person "hale" :likelihood "certain" :at "secretarys-room"}
                 (dissoc (:data event) :roll :roll-key)))
          (is (= "earls-bedroom" (get-in (e/world run) [:agents "lord-claudeworth" :location])))
          (is (= "Lord Claudeworth looks for Hale and finds them in the secretary's room."
                 (n/describe event (n/names (e/world run)))))))
      (testing "no one about answers: the same reply as a miss"
        (is (= "You look about for Mr Pugh but don't find them." (:reply (seek! run "Mr Pugh"))))
        (is (= {:who "Mr Pugh" :outcome :not-found} (:data (last (sought run))))))
      (is (replays? run))
      (finally (e/stop-run run)))))

(deftest the-engine-rolls-the-judges-odds
  (let [run (start {:llm-impl (llm/mock (fn [_] {:tool :seek :args {:person "hale" :likelihood "unlikely"}}))})]
    (try
      (with-redefs [rng/roll (fn [_ _ _] 25)]
        (is (= "You look about for my secretary but don't find them." (:reply (seek! run "my secretary")))))
      (with-redefs [rng/roll (fn [_ _ _] 24)]
        (is (str/starts-with? (:reply (seek! run "my secretary")) "Hale (hale) is in the Secretary's room")))
      (is (= [[:not-found 25] [:found 24]] (map (comp (juxt :outcome :roll) :data) (sought run))))
      (is (= "Lord Claudeworth looks about for my secretary but doesn't find them."
             (n/describe (first (sought run)) (n/names (e/world run)))))
      (is (replays? run))
      (finally (e/stop-run run)))))

(deftest someone-in-the-same-room
  (let [run (start)]
    (try
      (e/emit! run [{:type :agent/placed :data {:id "hale" :to "earls-bedroom"}}])
      (is (= "Hale (hale) is here with you." (:reply (seek! run "Hale"))))
      (finally (e/stop-run run)))))

(deftest a-free-query-once-a-turn
  (let [run (start)
        act (action run)]
    (try
      (is (= :query (a/kind act)))
      (is (= 1 (:per-turn act)))
      (doseq [raw [{:who ""} {:who (apply str (repeat 81 "x"))} {}]]
        (is (:error (a/decode-args act raw))))
      (let [world (-> (e/world run)
                      (assoc-in [:agents "lord-claudeworth" :travel] {:to "village-green"})
                      (update-in [:agents "lord-claudeworth"] dissoc :location))]
        (is (empty? (a/available [act] world "lord-claudeworth")))
        (is (= "you can't look for anyone while travelling" (a/check act world "lord-claudeworth" {:who "Hale"}))))
      (finally (e/stop-run run)))))
