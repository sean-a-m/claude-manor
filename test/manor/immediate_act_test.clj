(ns manor.immediate-act-test
  "The work judge's physical answers and the immediate act-on final action."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [manor.actions :as a]
            [manor.engine :as e]
            [manor.test-support :as support]
            [manor.engine.resolve :as resolve]
            [manor.engine.turn :as turn]
            [manor.io.llm :as llm]
            [manor.io.store :as store]
            [manor.mechanics.items :as items]
            [manor.mechanics.items.actions :as item-actions]
            [manor.mechanics.items.judge :as judge]
            [manor.mechanics.items.steward :as steward]
            [manor.mechanics.items.work :as work]
            [manor.mechanics.physical :as physical]
            [manor.prompt :as prompt]
            [manor.world :as w]))

(use-fixtures :once support/with-runs)

(defn- start [& [opts]]
  (e/start-run :burglary (merge {:llm :mock :offline? true :test? true
                               :run-id (str (gensym "immediate-act-"))} opts)))

(defn- stop! [run]
  (e/stop-run run)
  (is (.awaitTermination ^java.util.concurrent.ExecutorService (:exec run)
                         10 java.util.concurrent.TimeUnit/SECONDS)))

(defn- rounds! [run n]
  (e/auto! run n)
  (is (= :idle (e/wait-idle run 60000)))
  run)

(defn- events-of [run t] (filter #(= t (:type %)) (e/events run)))
(defn- replays? [run] (= (e/world run) (w/replay (store/read-events (:dir run)))))
(defn- place! [run id to]
  (e/emit! run [{:type :agent/placed :visible-to :gm :data {:id id :to to}}]))

(defn- scene-json [request]
  (let [content (get-in request [:messages 1 :content])
        from (+ (str/index-of content "```json\n") 8)]
    (json/read-value (subs content from (str/index-of content "\n```" from)) json/keyword-keys-object-mapper)))

(defn- policy [script judge-fn]
  (llm/mock (fn [{:keys [world agent-id role step] :as ctx}]
              (case role
                :judge (judge-fn ctx)
                :steward (steward/mock-steward ctx)
                :gm {:tool :done}
                (or (get-in script [agent-id (:round world) step]) {:tool :pass})))))

(defn- answer [world job args]
  (judge/judgement {:tool-calls [{:id "j" :name "judge" :arguments (merge {:summary "The activity ended."} args)}]}
                   world (work/judged-things world job) nil job))

(deftest act-on-is-opt-in-and-checks-explicit-ids
  (let [run (start)
        world (e/world run)
        act item-actions/act-on
        args {:on ["ivy"] :doing "helping her up"}]
    (try
      (is (= :final (a/kind act)))
      (is (not (a/allowed? act :gm)))
      (is (not-any? #(= :act-on (:name %)) (item-actions/actions)))
      (is (some #(= :act-on (:name %)) (item-actions/actions {:act-on? true})))
      (doseq [bad [{:doing "helping Ivy up"} (assoc args :on []) (assoc args :on (repeat 4 "ivy"))
                   (assoc args :with (repeat 3 "sack")) (assoc args :doing "up")]]
        (is (:error (a/decode-args act bad))))
      (is (nil? (a/check act world "slade" args)))
      (is (nil? (a/check act world "ivy" {:on ["slade"] :doing "taking hold of him"})) "sight is for the judge")
      (is (nil? (a/check act world "slade" (assoc args :on ["albert"]))) "the dead may be acted on")
      (is (a/check act world "albert" (assoc args :on ["slade"])))
      (is (a/check act world "mrs-bell" args) "an unconscious actor is refused")
      (is (a/check act world "slade" (assoc args :on ["slade"])))
      (is (a/check act world "slade" (assoc args :on ["crane"])) "not here")
      (is (a/check act world "slade" (assoc args :on ["nobody"])))
      (is (nil? (a/check act world "slade" (assoc args :with ["sack" "brass-candlestick"]))))
      (is (a/check act world "slade" (assoc args :with ["pantry-keys"])) "held by someone else")
      (place! run "crane" "dining-room")
      (is (nil? (a/check act (e/world run) "crane" args)) "bound hands and a gag are for the judge")
      (finally (stop! run)))))

(deftest work-and-acts-check-physical-answer-lists
  (let [run (start)
        world (e/world run)
        act (work/act-job world "slade" {:on ["ivy" "albert"] :doing "helping them"})]
    (try
      (doseq [job [act (dissoc act :act?)]]
        (testing (if (:act? job) "immediate act" "timed work")
          (let [good (answer world job {:physical [{:person "ivy" :faculty "consciousness" :level "disabled" :minutes 30}
                                                  {:person "slade" :faculty "grasp" :level "impeded"}]
                                       :impediments [{:person "slade" :key "hand" :value ""}
                                                     {:person "albert" :key "face" :value "a bruise"}]
                                       :died [{:person "ivy" :cause "a fall"}]})]
            (is (:verdict good))
            (is (= ["ivy" "slade" "albert"] (map first (get-in good [:verdict :physical]))))
            (is (= "a fall" (get-in good [:verdict :physical 0 1 :died]))))
          (doseq [bad [{:physical [{:person "will" :faculty :movement :level :disabled}]}
                       {:physical [{:person "sideboard" :faculty :movement :level :disabled}]}
                       {:physical [{:person "ivy" :faculty :sight :level :impeded}]}
                       {:physical [{:person "ivy" :faculty :movement :level :disabled :minutes 30}]}
                       {:physical [{:person "ivy" :faculty :consciousness :level :disabled :minutes 0}]}
                       {:physical [{:person "ivy" :faculty :smell :level :disabled}]}
                       {:physical [{:person "ivy" :faculty :grasp :level :disabled :cause (apply str (repeat 201 "x"))}]}
                       {:physical [{:person "albert" :faculty :consciousness :level :normal}]}
                       {:died [{:person "albert"}]} {:died [{:person "will"}]}
                       {:impediments [{:person "will" :key "hand" :value "a cut"}]}
                       {:impediments [{:person "ivy" :key "bad-key!" :value "a cut"}]}
                       {:impediments (mapv #(hash-map :person "ivy" :key % :value "a cut") ["a" "b" "c" "d"])}]]
            (let [checked (answer world job (assoc bad :changes [{:thing "sideboard" :aspect "state" :value "changed"}]))]
              (is (:error checked) (pr-str bad))
              (is (nil? (:verdict checked)) "invalid answers have no partial verdict")))))
      (testing "impediments can be cleared before adding replacements at the cap"
        (let [full (assoc-in world [:agents "slade" :impediments] {:hand "cut" :ankle "sore" :head "bruised"})]
          (is (:verdict (answer full act {:impediments [{:person "slade" :key "hand" :value ""}
                                                       {:person "slade" :key "arm" :value "a scratch"}]})))))
      (finally (stop! run)))))

(deftest only-an-act-can-transfer-judged-peoples-held-items
  (let [run (start)
        world (e/world run)
        job (work/act-job world "slade" {:on ["ivy" "albert"] :doing "handing things over"})]
    (try
      (doseq [moved [[{:item "jewel-case" :to "ivy"}]
                     [{:item "pantry-keys" :to "slade"}]
                     [{:item "sack" :to "place"}]]]
        (is (= moved (get-in (answer world job {:moved moved}) [:verdict :moved]))))
      (doseq [moved [[{:item "brass-candlestick" :to "slade"}]
                     [{:item "missing" :to "slade"}]
                     [{:item "jewel-case" :to "will"}]
                     [{:item "sack" :to "ivy"} {:item "sack" :to "place"}]
                     [{:item "sack" :to "slade"} {:item "sack" :to "place"}]]]
        (is (:error (answer world job {:moved moved})) (pr-str moved)))
      (is (nil? (get-in (answer world job {:moved [{:item "sack" :to "slade"}]}) [:verdict :moved])) "no-op omitted")
      (is (nil? (get-in (answer world (assoc job :people ["ivy"]) {:moved [{:item "pantry-keys" :to "slade"}]}) [:verdict])))
      (is (nil? (get-in (answer world (dissoc job :act?) {:moved [{:item "sack" :to "ivy"}]}) [:verdict :moved]))
          "unknown answer fields are dropped, as in all tool decoders")
      (let [v (:verdict (answer world job {:used-up ["sack"] :made [{:name "X" :desc "X" :consumable false}]}))]
        (is (nil? (:used-up v)))
        (is (nil? (:made v))))
      (finally (stop! run)))))

(deftest immediate-act-applies-before-the-next-actor-and-ends-the-turn
  (let [seen (atom [])
        impl (policy {"slade" {1 {0 {:tool :act-on :args {:on ["ivy"] :with ["brass-candlestick"] :doing "helping Ivy up"}}}}}
                     (fn [ctx]
                       (swap! seen conj ctx)
                       {:tool :judge :args {:summary "Ivy falls unconscious; Slade drops the case."
                                           :physical [{:person "ivy" :faculty :consciousness :level :disabled :minutes 30}]
                                           :inner [{:person "ivy" :key "mind" :value "secret private judgement"}]
                                           :moved [{:item "jewel-case" :to "place"}]}}))
        priced (reify llm/LLM
                 (complete [_ req]
                   (cond-> (llm/complete impl req)
                     (= :judge (get-in req [:ctx :role])) (assoc-in [:usage :cost] 0.01))))
        run (start {:llm-impl priced})
        run (assoc run :scenario (assoc (:scenario run) :schedule (constantly [["slade"] ["ivy"]])))]
    (try
      (place! run "will" "dining-room")
      (place! run "crane" "dining-room")
      (place! run "mrs-bell" "dining-room")
      @(e/afflict! run "crane" {:faculty :sight :level :disabled})
      (rounds! run 1)
      (let [world (e/world run)
            [acted] (events-of run :person/acted-on)
            [ctx] @seen
            request (judge/judge-request (:scenario run) (:world ctx) (:job ctx) ctx)
            user (:content (last (:messages request)))
            calls (store/read-llm (:dir run))
            actor-call (first (filter #(= "slade" (:agent %)) calls))]
        (is (= 1 (count @seen)))
        (is (:act? (:job ctx)))
        (is (= ["ivy"] (get-in ctx [:job :people])))
        (is (= 0 (:minutes ctx)))
        (is (<= 1 (:luck ctx) 100))
        (is (str/includes? user "An immediate act, a few minutes"))
        (is (not (str/includes? user "Time worked:")))
        (is (str/includes? user "blindfolded with a pillowcase"))
        (is (str/includes? user "a cut palm, from the pantry window"))
        (let [ivy (first (filter #(= "ivy" (:id %)) (:people (scene-json request))))]
          (is (= {:level "disabled" :cause "blindfolded with a pillowcase" :set_at "01:00"}
                 (get-in ivy [:physical :sight]))))
        (is (= #{{:id "jewel-case" :name "Jewel case"} {:id "sack" :name "Sack"}}
               (set (:carrying (first (filter #(= "slade" (:id %)) (:people (work/scene (:world ctx) (:job ctx)))))))))
        (is (= #{"slade" "ivy" "will"} (:visible-to acted)))
        (is (= "dining-room" (:place (items/item world "jewel-case"))))
        (is (nil? (:holder (items/item world "jewel-case"))))
        (is (= 1 (:touched (items/item world "jewel-case"))))
        (is (physical/unconscious? world "ivy"))
        (is (not-any? #(= "ivy" (:agent %)) calls) "the next actor is unconscious before their turn")
        (is (= "Ivy falls unconscious; Slade drops the case." (get-in actor-call [:replies 0 :content])))
        (is (= {:calls 1 :cost 0.01} (select-keys (get-in world [:usage "judge"]) [:calls :cost])))
        (is (empty? (items/jobs world)))
        (is (nil? (w/busy world "slade")))
        (is (= [:gm] (map :visible-to (events-of run :person/inner-changed))))
        (is (not (str/includes? (pr-str (prompt/agent-messages (:scenario run) world (e/events run) "slade" {}))
                                "secret private judgement")))
        (is (not-any? #(= (:id acted) (:id %)) (prompt/visible-events (e/events run) "crane" 1000))))
      (is (replays? run))
      (finally (stop! run)))))

(deftest an-act-transfers-held-items-and-keeps-container-contents-with-them
  (let [run (start {:llm-impl (policy {} (constantly {:tool :judge
                                                    :args {:summary "Ivy takes the case and Slade the keys."
                                                           :moved [{:item "jewel-case" :to "ivy"}
                                                                   {:item "pantry-keys" :to "slade"}
                                                                   {:item "sack" :to "place"}]}}))})]
    (try
      (e/emit! run [{:type :item/put-in :actor "slade" :visible-to #{"slade"}
                    :data {:item "silver-salver" :into "sack"}}])
      (is (:ok (resolve/resolve! run "ivy" item-actions/act-on
                                {:on ["slade" "albert"] :doing "taking the case and handing over the keys"})))
      (let [world (e/world run)
            [acted] (events-of run :person/acted-on)]
        (is (= #{"ivy" "slade" "albert"} (:visible-to acted)))
        (is (= "ivy" (:holder (items/item world "jewel-case"))))
        (is (= "slade" (:holder (items/item world "pantry-keys"))))
        (is (= "dining-room" (:place (items/item world "sack"))))
        (is (= "sack" (:in (items/item world "silver-salver"))))
        (is (= [{:item "jewel-case" :from "slade" :holder "ivy"}
                {:item "pantry-keys" :from "albert" :holder "slade"}
                {:item "sack" :from "slade" :place "dining-room"}]
               (get-in acted [:data :moved]))))
      (is (replays? run))
      (finally (stop! run)))))

(deftest an-act-interrupts-a-sleeper-even-with-no-status-change
  (let [run (start {:llm-impl (policy {} judge/mock-judge)})]
    (try
      (place! run "will" "dining-room")
      (place! run "mrs-bell" "dining-room")
      (e/emit! run [{:type :agent/occupied :actor "will" :visible-to #{"will"}
                    :data {:activity "sleeping" :until 8 :asleep? true}}])
      (is (:ok (resolve/resolve! run "slade" item-actions/act-on {:on ["will" "mrs-bell"] :doing "shaking them awake"})))
      (is (= #{"slade" "will"} (:visible-to (first (events-of run :person/acted-on))))
          "an unconscious target still perceives nothing")
      (is (= "slade" (:interrupted-by (w/busy (e/world run) "will"))))
      (is (= 1 (count (events-of run :agent/interrupted))))
      (is (replays? run))
      (finally (stop! run)))))

(deftest failure-still-spends-the-act-and-checks-happen-before-judging
  (let [calls (atom 0)
        run (start {:llm-impl (policy {} (fn [_] (swap! calls inc) {:tool :judge :args {:summary "No." :died [{:person "will"}]}}))})
        act item-actions/act-on
        opts {:max-finals 1 :max-queries 0 :commit #(resolve/resolve! run "slade" %1 %2)}
        call {:id "act" :name "act-on" :arguments {:on ["ivy"] :doing "helping her up"}}]
    (try
      (let [st (turn/handle-call run "slade" {"act-on" act} opts {:finals [] :queries 0 :used [] :replies []} call)
            after (turn/handle-call run "slade" {"act-on" act} opts st call)]
        (is (:done? st))
        (is (= 0 (:queries st)))
        (is (= 2 @calls) "one correction attempt, no repeated acts")
        (is (= "ignored: your turn is already over" (:content (last (:replies after)))))
        (is (= 1 (count (events-of run :person/acted-on))))
        (is (= 1 (count (events-of run :item/judge-failed))))
        (is (w/active? (e/world run) "will")))
      (doseq [id ["albert" "mrs-bell"]]
        (is (:rejected (resolve/resolve! run id act {:on ["ivy"] :doing "helping her up"}))))
      (is (= 2 @calls) "dead and unconscious actors cause no judge calls or effects")
      (is (replays? run))
      (finally (stop! run)))))

(deftest simultaneous-acts-keep-schedule-order-and-recheck-the-actor
  (let [seen (atom [])
        act (fn [id] {:tool :act-on :args {:on [id] :doing "taking hold of them"}})
        run (start {:llm-impl (policy {"slade" {1 {0 (act "ivy")}} "ivy" {1 {0 (act "slade")}}}
                                     (fn [ctx]
                                       (swap! seen conj (:job ctx))
                                       {:tool :judge :args {:summary "Ivy loses consciousness."
                                                           :physical [{:person "ivy" :faculty :consciousness :level :disabled}]}}))})
        run (assoc run :scenario (assoc (:scenario run) :schedule (constantly [["slade" "ivy"]])))]
    (try
      (rounds! run 1)
      (is (= ["slade"] (map :actor @seen)) "no judging while concurrent turns propose finals")
      (is (= ["slade"] (map :actor (events-of run :person/acted-on))))
      (is (= ["ivy"] (map :actor (events-of run :action/rejected))))
      (is (physical/unconscious? (e/world run) "ivy"))
      (is (replays? run))
      (finally (stop! run)))))

(deftest simultaneous-knockout-prevents-a-queued-spend-time
  (let [seen (atom [])
        activity {:tool :spend-time :args {:doing "resting quietly" :hours 2}}
        run (start {:llm-impl
                    (policy {"slade" {1 {0 {:tool :act-on :args {:on ["ivy"] :doing "striking her"}}}}
                             "ivy" {1 {0 activity}}
                             "will" {1 {0 activity}}}
                            (fn [ctx]
                              (swap! seen conj (:job ctx))
                              {:tool :judge :args {:summary "Ivy loses consciousness."
                                                  :physical [{:person "ivy" :faculty :consciousness :level :disabled}]}}))})
        run (assoc run :scenario (assoc (:scenario run) :schedule (constantly [["slade" "ivy" "will"]])))]
    (try
      (place! run "will" "dining-room")
      (rounds! run 1)
      (let [world (e/world run)
            [rejected] (events-of run :action/rejected)]
        (is (physical/unconscious? world "ivy"))
        (is (nil? (w/busy world "ivy")) "the queued activity cannot occupy an unconscious actor")
        (is (= ["will"] (map :actor (items/jobs world))) "the unaffected actor still begins work")
        (is (= ["will"] (map :actor (events-of run :agent/occupied))))
        (is (= ["slade"] (map :actor @seen)) "rejected finals cause no judge calls")
        (is (= 1 (count (events-of run :action/rejected))))
        (is (= "ivy" (:actor rejected)))
        (is (= #{"ivy"} (:visible-to rejected)))
        (is (= "spend-time" (get-in rejected [:data :action]))))
      (is (replays? run))
      (finally (stop! run)))))

(deftest simultaneous-death-prevents-queued-travel
  (doseq [travel [1 2]]
    (testing (if (= travel 1) "immediate movement" "multi-round journey")
      (let [seen (atom [])
            journey {:tool :travel :args {:to "village-green"}}
            run (start {:llm-impl
                        (policy {"slade" {1 {0 {:tool :act-on :args {:on ["ivy"] :doing "striking her"}}}}
                                 "ivy" {1 {0 journey}}
                                 "will" {1 {0 journey}}}
                                (fn [ctx]
                                  (swap! seen conj (:job ctx))
                                  {:tool :judge :args {:summary "Ivy dies."
                                                      :died [{:person "ivy" :cause "a blow"}]}}))})
            run (assoc run :scenario (assoc (:scenario run) :schedule (constantly [["slade" "ivy" "will"]])))]
        (try
          (place! run "will" "dining-room")
          (e/emit! run [{:type :space/route-opened :visible-to :gm
                        :data {:from "carriage-sweep" :to "village-green" :travel travel}}])
          (rounds! run 1)
          (let [world (e/world run)
                [rejected] (events-of run :action/rejected)]
            (is (w/dead? world "ivy"))
            (is (= "dining-room" (get-in world [:agents "ivy" :location])) "the corpse stays where she died")
            (is (nil? (get-in world [:agents "ivy" :travel])))
            (is (= ["will"] (map :actor (concat (events-of run :agent/moved)
                                                (events-of run :agent/departed))))
                "only the unaffected actor moves or departs")
            (if (= travel 1)
              (is (= "village-green" (get-in world [:agents "will" :location])))
              (is (= "village-green" (get-in world [:agents "will" :travel :to]))))
            (is (= ["slade"] (map :actor @seen)) "rejected finals cause no judge calls")
            (is (= 1 (count (events-of run :action/rejected))))
            (is (= "ivy" (:actor rejected)))
            (is (= #{"ivy"} (:visible-to rejected)))
            (is (= "travel" (get-in rejected [:data :action]))))
          (is (replays? run))
          (finally (stop! run)))))))

(deftest an-act-knocking-out-or-killing-a-worker-ends-the-job-early
  (doseq [death? [false true]]
    (let [seen (atom [])
          run (start {:llm-impl
                      (policy {"slade" {1 {0 {:tool :spend-time :args {:on ["sideboard"] :doing "polishing the sideboard" :hours 2}}}}
                               "ivy" {2 {0 {:tool :act-on :args {:on ["slade"] :doing "striking him"}}}}}
                              (fn [{:keys [job] :as ctx}]
                                (swap! seen conj ctx)
                                (if (:act? job)
                                  {:tool :judge :args (merge {:summary "Slade falls."}
                                                            (if death? {:died [{:person "slade" :cause "a blow"}]}
                                                                {:physical [{:person "slade" :faculty :consciousness :level :disabled}]}))}
                                  (judge/mock-judge ctx))))})]
      (try
        (rounds! run 3)
        (let [[done] (events-of run :item/work-done)
              finished (first (remove #(get-in % [:job :act?]) @seen))]
          (is (= {:minutes 30 :stopped-early? true} (select-keys (:data done) [:minutes :stopped-early?])))
          (is (nil? (w/busy (e/world run) "slade")))
          (is (empty? (items/jobs (e/world run))))
          (is (if death? (w/dead? (:world finished) "slade") (physical/unconscious? (:world finished) "slade"))))
        (is (empty? (filter #(= "slade" (:actor %)) (events-of run :agent/interrupted))))
        (is (replays? run))
        (finally (stop! run))))))
