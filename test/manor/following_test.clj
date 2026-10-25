(ns manor.following-test
  "Following (manor.mechanics.following): who can follow whom, going along
  on walks and journeys, busy followers stopped, those left behind, chains,
  following from a conversation, the view and replay."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [manor.actions :as a]
            [manor.engine :as e]
            [manor.engine.resolve :as resolve]
            [manor.io.llm :as llm]
            [manor.io.store :as store]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.following :as following]
            [manor.narrate :as n]
            [manor.prompt :as prompt]
            [manor.world :as w]))

(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

(defn- start
  "A household run with the earl, the countess and Hale in the dining room."
  [& [opts]]
  (let [run (e/start-run :household (merge {:llm :mock :offline? true :test? true
                                            :run-id (str (gensym "following-"))}
                                           opts))]
    (doseq [id ["lord-claudeworth" "lady-claudeworth" "hale"]]
      @(e/place! run id "dining-room"))
    run))

(defn- act! [run actor tool args]
  (resolve/resolve! run actor (get (a/by-name (:actions run)) tool) args))

(defn- where [run id] (get-in (e/world run) [:agents id :location]))

(defn- of-type [run t] (filter #(= t (:type %)) (e/events run)))

(defn- narrated [run t]
  (let [names (n/names (e/world run))]
    (map #(n/describe % names) (of-type run t))))

(defn- replays? [run]
  (= (e/world run) (w/replay (store/read-events (:dir run)))))

(deftest who-can-follow-whom
  (let [run (start)
        world (e/world run)
        reason #(following/follow-reason %1 %2 {:who %3})]
    (try
      (is (nil? (reason world "hale" "lord-claudeworth")))
      (is (= "you can't follow yourself" (reason world "hale" "hale")))
      (is (= "no one has the id nobody" (reason world "hale" "nobody")))
      (is (= "Briggs is not here" (reason world "hale" "briggs")))
      (let [world (w/apply-event world (following/started world "hale" "lord-claudeworth"))]
        (is (= "you are already following Lord Claudeworth" (reason world "hale" "lord-claudeworth")))
        (is (= "Hale is following you, so you can't follow them" (reason world "lord-claudeworth" "hale")))
        (let [world (w/apply-event world (following/started world "lady-claudeworth" "hale"))]
          (is (= "Lady Claudeworth is following you, so you can't follow them"
                 (reason world "lord-claudeworth" "lady-claudeworth"))
              "nor through someone else")))
      (is (some? (reason (assoc-in world [:agents "hale" :physical :movement] {:level :disabled :cause "bound"})
                         "hale" "lord-claudeworth"))
          "someone who can't move can't follow")
      (is (= "you aren't following anyone"
             (:rejected (act! run "hale" "follow-stop" {}))))
      (finally (e/stop-run run)))))

(deftest followers-go-along
  (let [run (start)]
    (try
      (is (:ok (act! run "hale" "follow" {:who "lord-claudeworth"})))
      (is (:ok (act! run "lady-claudeworth" "follow" {:who "hale"})))
      (testing "following is seen, and shown in the view of both"
        (is (= ["Hale follows Lord Claudeworth, to go where they go."
                "Lady Claudeworth follows Hale, to go where they go."]
               (narrated run :agent/following)))
        (is (= #{"lord-claudeworth" "lady-claudeworth" "hale"}
               (:visible-to (first (of-type run :agent/following)))))
        (let [view #(prompt/agent-view (:scenario run) (e/world run) %)]
          (is (= {:id "lord-claudeworth" :name "Lord Claudeworth"} (get-in (view "hale") [:you :following])))
          (is (= [{:id "hale" :name "Hale"}] (get-in (view "lord-claudeworth") [:you :followed-by])))))
      (testing "a walk takes the follower, and their follower, along at once"
        (is (:ok (act! run "lord-claudeworth" "walk" {:to "study"})))
        (is (= ["study" "study" "study"] (map #(where run %) ["lord-claudeworth" "hale" "lady-claudeworth"])))
        (is (= ["Lord Claudeworth goes from Dining room to Study."
                "Hale goes with Lord Claudeworth from Dining room to Study."
                "Lady Claudeworth goes with Hale from Dining room to Study."]
               (narrated run :agent/moved))))
      (testing "a busy follower stops what they're doing; their work is judged on the time done"
        (e/emit! run [(b/occupy (e/world run) "hale" "copying letters" 10)])
        (is (:ok (act! run "lord-claudeworth" "walk" {:to "dining-room"})))
        (is (nil? (w/busy (e/world run) "hale")))
        (is (= "Hale is no longer copying letters (interrupted by Lord Claudeworth)."
               (last (narrated run :agent/freed))))
        (is (= "dining-room" (where run "hale") (where run "lady-claudeworth"))))
      (testing "someone asleep is left behind and stops following, and so their follower stays too"
        (e/emit! run [(b/occupy (e/world run) "hale" "asleep" 10 {:asleep? true})])
        (is (:ok (act! run "lord-claudeworth" "walk" {:to "study"})))
        (is (= ["study" "dining-room" "dining-room"] (map #(where run %) ["lord-claudeworth" "hale" "lady-claudeworth"])))
        (is (= "Hale can't go with Lord Claudeworth, and stops following them."
               (last (narrated run :agent/stopped-following))))
        (is (nil? (following/leader (e/world run) "hale")))
        (is (= "hale" (following/leader (e/world run) "lady-claudeworth")) "she still follows Hale"))
      (is (replays? run))
      (finally (e/stop-run run)))))

(deftest following-ends
  (let [run (start)]
    (try
      (testing "with follow-stop"
        (act! run "hale" "follow" {:who "lord-claudeworth"})
        (is (:ok (act! run "hale" "follow-stop" {})))
        (act! run "lord-claudeworth" "walk" {:to "study"})
        (is (= "dining-room" (where run "hale")))
        (is (= "Hale stops following Lord Claudeworth." (last (narrated run :agent/stopped-following)))))
      (testing "when the follower goes somewhere themselves"
        (act! run "lady-claudeworth" "follow" {:who "hale"})
        (act! run "lady-claudeworth" "walk" {:to "kitchen"})
        (is (nil? (following/leader (e/world run) "lady-claudeworth")))
        (act! run "hale" "walk" {:to "study"})
        (is (= "kitchen" (where run "lady-claudeworth"))))
      (testing "when the follower is no longer with them, which only they are told"
        (act! run "hale" "follow" {:who "lord-claudeworth"})
        @(e/place! run "hale" "kitchen")
        (act! run "lord-claudeworth" "walk" {:to "dining-room"})
        (is (= "kitchen" (where run "hale")))
        (let [ev (last (of-type run :agent/stopped-following))]
          (is (= :apart (get-in ev [:data :reason])))
          (is (= #{"hale"} (:visible-to ev)))))
      (testing "following someone else instead stops following the first"
        @(e/place! run "hale" "dining-room")
        @(e/place! run "lady-claudeworth" "dining-room")
        (act! run "hale" "follow" {:who "lord-claudeworth"})
        (is (:ok (act! run "hale" "follow" {:who "lady-claudeworth"})))
        (is (= "lady-claudeworth" (following/leader (e/world run) "hale"))))
      (is (replays? run))
      (finally (e/stop-run run)))))

(deftest followers-travel-too
  (let [run (start)]
    (try
      @(e/place! run "lord-claudeworth" "village-green")
      @(e/place! run "hale" "village-green")
      (act! run "hale" "follow" {:who "lord-claudeworth"})
      (testing "a journey of a round arrives within the turn, together"
        (is (:ok (act! run "lord-claudeworth" "travel" {:to "carriage-sweep"})))
        (is (= "carriage-sweep" (where run "lord-claudeworth") (where run "hale"))))
      (testing "a longer journey leaves them both travelling, to arrive together"
        (e/emit! run [{:type :space/route-closed :data {:from "carriage-sweep" :to "abbey-gatehouse"} :visible-to :gm}])
        (is (:ok (act! run "lord-claudeworth" "travel" {:to "abbey-gatehouse"})))
        (let [world (e/world run)]
          (is (= (get-in world [:agents "lord-claudeworth" :travel])
                 (get-in world [:agents "hale" :travel])))
          (is (= "abbey-gatehouse" (get-in world [:agents "hale" :travel :to]))))
        (is (str/starts-with? (last (narrated run :agent/departed))
                              "Hale leaves Carriage sweep with Lord Claudeworth for Abbey gatehouse")))
      (is (replays? run))
      (finally (e/stop-run run)))))

(defn- come-along
  "A policy: the earl asks Hale to come to the study, then walks there; in
  the conversation Hale follows him and answers; other turns pass."
  [{:keys [agent-id role step conversation used]}]
  (cond (= role :gm) {:tool :done}
        conversation (cond (= agent-id "lord-claudeworth") {:tool :end-conversation}
                           (not (some #{"follow"} used)) {:tool :follow :args {:who "lord-claudeworth"}}
                           :else {:tool :reply :args {:text "At once, my lord."}})
        (and (= agent-id "lord-claudeworth") (= 0 step))
        {:tool :converse :args {:with ["hale"] :text "Come with me to the study, Hale."}}
        (and (= agent-id "lord-claudeworth") (= 1 step)) {:tool :walk :args {:to "study"}}
        :else {:tool :pass}))

(deftest following-from-a-conversation
  (let [run (start {:llm :mock :llm-impl (llm/mock come-along)})]
    (try
      (e/step! run)
      (is (= :idle (e/wait-idle run 60000)))
      (testing "follow is offered in a speaking turn, and Hale goes along when the earl walks after it"
        (is (some #(= :follow (:name %)) ((:speaking-actions (:scenario run)) (e/world run) "hale" nil)))
        (is (= "study" (where run "hale")))
        (is (< (:id (first (of-type run :agent/following)))
               (:id (first (of-type run :conversation/ended)))
               (:id (first (filter #(= "hale" (:actor %)) (of-type run :agent/moved)))))))
      (is (empty? (of-type run :action/forfeited)))
      (is (replays? run))
      (finally (e/stop-run run)))))
