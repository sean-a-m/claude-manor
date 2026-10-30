(ns manor.burglary-test
  "The burglary fixture's complete mock night: immediate acts, theft,
  recovery, perception privacy and stored-event replay."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [manor.engine :as e]
            [manor.test-support :as support]
            [manor.io.store :as store]
            [manor.mechanics.items :as items]
            [manor.mechanics.physical :as physical]
            [manor.prompt :as prompt]
            [manor.scenarios.burglary :as burglary]
            [manor.world :as w]))

(use-fixtures :once support/with-runs)

(defn- rounds! [run n]
  (e/auto! run n)
  (is (= :idle (e/wait-idle run 60000))))

(defn- events-of [run t] (filter #(= t (:type %)) (e/events run)))

(deftest immediate-acts-theft-and-nightly-recovery-in-one-replayable-night
  (let [run (e/start-run :burglary {:llm :mock :offline? true :test? true
                                  :run-id (str (gensym "burglary-night-"))})]
    (try
      ;; Keep the phase-1 mid-crime fixture; rewind the butler's bonds by
      ;; operator events so this check proves the act, not initial data, binds him.
      (doseq [faculty [:movement :grasp :speech]]
        @(e/afflict! run "crane" {:faculty faculty :level :normal}))
      @(e/afflict! run "will" {:faculty :movement :level :disabled :cause "a twisted ankle"})
      ;; A blind witness in the pantry hears the gagged butler, but sees
      ;; neither act nor the silver being taken, even in the stored log.
      (e/emit! run [{:type :agent/placed :visible-to :gm :data {:id "ivy" :to "butlers-pantry"}}])
      (is (nil? (:physical (w/agent (e/world run) "crane"))))
      (rounds! run 1)
      (let [world (e/world run)
            [bound] (events-of run :person/acted-on)]
        (testing "the burglar's final action binds and gags immediately, without an occupation"
          (is (= [1 "slade" ["crane"]] [(:round bound) (:actor bound) (get-in bound [:data :on])]))
          (is (= #{"slade" "crane"} (:visible-to bound)))
          (is (= :disabled (physical/level world "crane" :movement)))
          (is (= :disabled (physical/level world "crane" :grasp)))
          (is (= :impeded (physical/level world "crane" :speech)))
          (is (empty? (items/jobs world)))
          (is (nil? (w/busy world "slade")))))
      (rounds! run 1)
      (let [world (e/world run)
            stolen (first (filter #(= "silver-salver" (get-in % [:data :item])) (events-of run :item/taken)))]
        (testing "silver can be taken after the immediate act, and the knocked-out cook comes to"
          (is (= "sack" (:in (items/item world "silver-salver"))))
          (is (= "slade" (:holder (items/item world "sack"))))
          (is (= 2 (:round stolen)))
          (is (not (contains? (:visible-to stolen) "ivy")))
          (is (= [[2 "mrs-bell"]] (map (juxt :round :actor) (events-of run :person/came-to))))
          (is (not (physical/unconscious? world "mrs-bell")))))
      (let [ropes (:physical (w/agent (e/world run) "crane"))]
        (rounds! run 2)
        (let [world (e/world run)
              events (e/events run)
              calls (store/read-llm (:dir run))
              seized (second (events-of run :person/acted-on))
              ;; The setup's affliction is first; recovery is the GM-only one.
              eased (first (filter #(and (= "will" (get-in % [:data :person])) (= :gm (:visible-to %)))
                                   (events-of run :person/physical-changed)))]
          (testing "the steward eases the ankle, not the ropes, before the footman's turn"
            (is (= :impeded (physical/level world "will" :movement)))
            (is (= "a tender ankle" (:cause (physical/entry world "will" :movement))))
            (is (= ropes (:physical (w/agent world "crane"))))
            (is (= 3 (:round eased)))
            (is (= 1 (count (events-of run :items/steward-ran))))
            (is (< (:id eased) (:id seized))))
          (testing "the footman seizes the burglar and takes the jewels in the same immediate act"
            (is (= [3 "will" ["slade"]] [(:round seized) (:actor seized) (get-in seized [:data :on])]))
            (is (= "will" (:holder (items/item world "jewel-case"))))
            (is (= [{:item "jewel-case" :from "slade" :holder "will"}] (get-in seized [:data :moved])))
            (is (= :disabled (physical/level world "slade" :movement)))
            (is (= :disabled (physical/level world "slade" :grasp)))
            (is (not (contains? (:visible-to seized) "ivy"))))
          (testing "muffled words stay private in stored audiences and every other prompt"
            (let [words (filter #(and (= "crane" (:actor %)) (get-in % [:data :muffled])) events)
                  noises (events-of run :person/muffled)
                  secrets (map #(get-in % [1 :text]) (mapcat val (get burglary/mock-day "crane")))]
              (is (= 2 (count words)))
              (is (= 2 (count noises)))
              (is (every? #(= #{"crane"} (:visible-to %)) words))
              (is (every? #(contains? (:visible-to %) "ivy") noises))
              (is (every? #(not (contains? (:data %) :text)) noises))
              (is (some #(= "crane" (:agent %)) calls))
              (doseq [call calls :when (not= "crane" (:agent call))
                      secret secrets]
                (is (not (str/includes? (pr-str (:messages call)) secret))
                    (str "muffled words leaked to " (:agent call))))))
          (testing "blind views and prompts contain no visual scene or unseen act summaries"
            (let [view (prompt/agent-view (:scenario run) world "ivy")
                  messages (pr-str (prompt/agent-messages (:scenario run) world events "ivy" {}))]
              (is (= {:id "butlers-pantry" :name "Butler's pantry"} (get-in view [:location :here])))
              (is (= physical/cant-see (get-in view [:location :cant-see])))
              (is (not-any? #(contains? (:location view) %) [:people-here :items]))
              (doseq [acted (events-of run :person/acted-on)]
                (is (not (str/includes? messages (get-in acted [:data :summary])))))))
          (is (empty? (filter #(#{:action/forfeited :action/rejected :item/judge-failed :items/steward-failed :adjudication/failed} (:type %)) events)))
          (doseq [reply (mapcat :replies calls)]
            (is (not-any? #(str/starts-with? (:content reply "") %) ["error:" "rejected:"])
                (:content reply)))
          (is (= 0.0 (double (w/total-cost world))))
          (is (= events (store/read-events (:dir run))))
          (is (= world (w/replay (store/read-events (:dir run)))))))
      (finally
        (e/stop-run run)
        (is (.awaitTermination ^java.util.concurrent.ExecutorService (:exec run)
                               10 java.util.concurrent.TimeUnit/SECONDS))))))
