(ns manor.conversation-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [manor.actions :as a]
            [manor.engine :as e]
            [manor.io.llm :as llm]
            [manor.io.store :as store]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.conversation :as conversation]
            [manor.narrate :as n]
            [manor.prompt :as prompt]
            [manor.tools.transcript :as transcript]
            [manor.world :as w]))

;; All runs here use {:test? true}, so they live under test-runs/.
(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

(defn- test-id [base] (str (gensym (str base "-"))))

(defn- costly
  "The mock LLM with `policy`, each call costing `cost`."
  [policy cost]
  (let [m (llm/mock policy)]
    (reify llm/LLM
      (complete [_ request]
        (assoc-in (llm/complete m request) [:usage :cost] cost)))))

(defn- household
  "A household run with the earl, the countess and Hale in the dining room
  (and `others` placed there too), its model following `policy`."
  [policy & [{:keys [others opts cost]}]]
  (let [run (e/start-run :household (merge {:llm-impl (costly policy (or cost 0)) :offline? true :test? true
                                            :run-id (test-id "conversation")}
                                           opts))]
    (doseq [id (concat ["lord-claudeworth" "lady-claudeworth" "hale"] others)]
      @(e/place! run id "dining-room"))
    run))

(defn- round! [run]
  (e/step! run)
  (is (= :idle (e/wait-idle run 60000)))
  run)

(defn- of-type [run t] (filter #(= t (:type %)) (e/events run)))

(defn- conversation-events [run]
  (filter #(get-in % [:data :conversation]) (e/events run)))

(defn- lines
  "[speaker text] of each line spoken in a conversation."
  [run]
  (for [ev (conversation-events run) :when (= :agent/said (:type ev))]
    [(:actor ev) (get-in ev [:data :text])]))

(defn- ending [run] (get-in (first (of-type run :conversation/ended)) [:data :reason]))

(defn- earl-opens
  "A policy: the earl starts a conversation with `args` and then passes;
  speaking turns follow `speak` (fn [ctx] => tool call); other turns pass."
  [args speak]
  (fn [{:keys [agent-id role step conversation] :as ctx}]
    (cond (= role :gm) {:tool :done}
          conversation (speak ctx)
          (and (= agent-id "lord-claudeworth") (zero? step))
          {:tool :converse :args (merge {:text "Shall we build?"} args)}
          (:interrupted-by (w/busy (:world ctx) agent-id)) {:tool :carry-on}
          :else {:tool :pass})))

(defn- chatty [{:keys [agent-id conversation]}]
  {:tool :reply :args {:text (str agent-id (if (:final? conversation) " has the last word" (str " " (:pass conversation))))}})

(deftest a-full-conversation
  (let [run (round! (household (earl-opens {} chatty)))
        world (e/world run)]
    (testing "the initiator opens, everyone speaks in turn for two passes, and the initiator has the last word"
      (is (= [["lord-claudeworth" "Shall we build?"]
              ["lady-claudeworth" "lady-claudeworth 1"] ["hale" "hale 1"]
              ["lord-claudeworth" "lord-claudeworth 2"] ["lady-claudeworth" "lady-claudeworth 2"] ["hale" "hale 2"]
              ["lord-claudeworth" "lord-claudeworth has the last word"]]
             (lines run)))
      (is (= :finished (ending run))))
    (testing "the conversation is narrated"
      (let [names (n/names world)]
        (is (= "Lord Claudeworth draws Lady Claudeworth and Hale into conversation."
               (n/describe (first (of-type run :conversation/started)) names)))
        (is (= (str "The conversation Lord Claudeworth started is over. " conversation/nothing-handed)
               (n/describe (first (of-type run :conversation/ended)) names)))
        (testing "unless something changed hands, or the world has no items"
          (let [ended (first (of-type run :conversation/ended))]
            (is (= 0 (get-in ended [:data :handed])))
            (is (= "The conversation Lord Claudeworth started is over."
                   (n/describe (assoc-in ended [:data :handed] 1) names)
                   (n/describe (update ended :data dissoc :handed) names)))
            (is (nil? (conversation/handed (dissoc world :items) [])))
            (is (= 1 (conversation/handed world [{:type :item/given} {:type :agent/said}])))))))
    (testing "the initiator gets the conversation back and carries on with the turn; nobody loses a turn"
      (let [calls (store/read-llm (:dir run))
            converse-call (first (filter #(and (= "lord-claudeworth" (:agent %)) (nil? (:conversation %))) calls))
            reply (:content (first (:replies converse-call)))]
        (is (str/starts-with? reply "The conversation is over: everyone had their say.\nLord Claudeworth (Dining room) says: \"Shall we build?\""))
        (is (str/ends-with? reply (str "\n" conversation/nothing-handed " It changes hands only when someone `give`s it, "
                                      "finding it with `find` first if they don't carry it.\nCarry on with your turn.")))
        (is (= ["lord-claudeworth" "lady-claudeworth" "hale"]
               (filter #{"lord-claudeworth" "lady-claudeworth" "hale"} (map :actor (of-type run :agent/passed)))))
        (is (< (:id (first (of-type run :conversation/ended)))
               (:id (first (filter #(= "lord-claudeworth" (:actor %)) (of-type run :agent/passed))))))
        (is (empty? (of-type run :action/forfeited)))))
    (testing "speaking turns are labelled in llm.edn and the transcript"
      (let [calls (filter :conversation (store/read-llm (:dir run)))]
        (is (= 6 (count calls)))
        (is (= {:pass :last-word} (select-keys (:conversation (last calls)) [:pass])))
        (is (= #{"reply" "end-conversation" "note"} (set (:tools (last calls)))))
        (is (= #{"reply" "stay-silent" "leave" "note"} (set (:tools (first calls))))))
      (let [text (transcript/render (:dir run))]
        (is (re-find #"\*\*hale\*\* _\(conv-\d+, pass 2\)_ reply" text))
        (is (re-find #"\*\*lord-claudeworth\*\* _\(conv-\d+, last word\)_ reply" text))))
    (testing "the started round is recorded, and replay reproduces the world"
      (is (= 1 (:conversed-round (w/agent world "lord-claudeworth"))))
      (is (= world (w/replay (store/read-events (:dir run))))))
    (e/stop-run run)))

(deftest the-prompt-of-a-speaking-turn
  (let [run (round! (household (earl-opens {} chatty)))
        [hale-first] (filter #(and (= "hale" (:agent %)) (:conversation %)) (store/read-llm (:dir run)))
        [earl-last] (filter #(= :last-word (get-in % [:conversation :pass])) (store/read-llm (:dir run)))
        system (comp :content first :messages)
        user (comp :content second :messages)]
    ;; These check what varies (who is told what, the tools named, the names, lines and numbers
    ;; filled in), not the wording of fixed instructions, so rewording a prompt doesn't fail them.
    (testing "a member is told someone else started it, and is offered the member's tools"
      (is (str/includes? (user hale-first) (:member prompt/conversation-lines)))
      (is (not (str/includes? (user hale-first) (:initiator prompt/conversation-lines))))
      (is (not (str/includes? (user hale-first) (:last-word prompt/conversation-lines))))
      (doseq [tool ["`reply`" "`stay-silent`" "`leave`" "`note`"]]
        (is (str/includes? (user hale-first) tool) tool))
      (is (not (str/includes? (user hale-first) "`end-conversation`")))
      (is (str/includes? (user hale-first) "Lady Claudeworth and you"))
      (is (str/includes? (user hale-first) "Lady Claudeworth (Dining room) says: \"lady-claudeworth 1\""))
      (is (str/includes? (user hale-first) (str conversation/line-limit)))
      (is (str/includes? (user hale-first) "`carrying`") "with items on, it says where to see what you hold")
      (is (str/includes? (user hale-first) (str "Pass 1 of " conversation/passes))))
    (testing "turns and speaking turns share the system message, for the prompt cache"
      (is (= (system hale-first)
             (system (first (filter #(and (= "hale" (:agent %)) (not (:conversation %))) (store/read-llm (:dir run))))))))
    (testing "the initiator is told they started it, is offered the initiator's tools, and is told it's their last word"
      (is (str/includes? (user earl-last) (:initiator prompt/conversation-lines)))
      (is (not (str/includes? (user earl-last) (:member prompt/conversation-lines))))
      (is (str/ends-with? (user earl-last) (:last-word prompt/conversation-lines)))
      (is (not (str/includes? (user earl-last) "Pass ")) "the last word has no pass count")
      (is (str/includes? (user earl-last) "`end-conversation`"))
      (doseq [tool ["`stay-silent`" "`leave`"]]
        (is (not (str/includes? (user earl-last) tool)) tool)))
    (e/stop-run run)))

(deftest ways-a-conversation-ends
  (testing "the initiator ends it at their turn"
    (let [run (round! (household (earl-opens {} (fn [{:keys [agent-id] :as ctx}]
                                                   (if (= "lord-claudeworth" agent-id) {:tool :end-conversation} (chatty ctx))))))]
      (is (= :ended (ending run)))
      (is (= 3 (count (lines run))))
      (e/stop-run run)))
  (testing "everyone else leaves"
    (let [run (round! (household (earl-opens {} (fn [{:keys [agent-id conversation] :as ctx}]
                                                   (if (= 1 (:pass conversation)) {:tool :leave} (chatty ctx))))))]
      (is (= :everyone-left (ending run)))
      (is (= ["lady-claudeworth" "hale"] (map :actor (of-type run :conversation/left))))
      (is (= 1 (count (lines run))))
      (e/stop-run run)))
  (testing "nobody else speaks in a pass"
    (let [run (round! (household (earl-opens {} (fn [{:keys [agent-id conversation] :as ctx}]
                                                   (if (and (= 2 (:pass conversation)) (not= "lord-claudeworth" agent-id))
                                                     {:tool :stay-silent}
                                                     (chatty ctx))))))]
      (is (= :silence (ending run)))
      (is (= 4 (count (lines run))))
      (is (= "Hale says nothing." (n/describe (last (of-type run :conversation/silent)) (n/names (e/world run)))))
      (e/stop-run run)))
  (testing "a member who answers with no valid action is silent"
    (let [run (round! (household (earl-opens {} (fn [{:keys [agent-id] :as ctx}]
                                                   (if (= "hale" agent-id) {:tool :walk :args {:to "study"}} (chatty ctx))))))]
      (is (= ["hale" "hale"] (map :actor (of-type run :conversation/silent))))
      (is (= "dining-room" (:location (w/agent (e/world run) "hale"))))
      (is (= :finished (ending run)))
      (e/stop-run run)))
  (testing "the cost limit cuts it short"
    (let [run (round! (household (earl-opens {} chatty) {:cost 0.01 :opts {:max-cost 0.025}}))]
      (is (= :cost-limit (ending run)))
      (is (= 3 (count (lines run))) "the opening and two lines, at 0.03")
      (e/stop-run run)))
  (testing "a failed call ends it and asks the run to pause"
    (let [run (round! (household (earl-opens {} (fn [{:keys [agent-id] :as ctx}]
                                                   (if (= "hale" agent-id) (throw (ex-info "boom" {})) (chatty ctx))))))]
      (is (= :error (ending run)))
      (is (some #(str/includes? (get-in % [:data :reason]) "error during hale's line") (of-type run :run/pause-requested)))
      (is (= :manual (:mode (e/status run))))
      (e/stop-run run))))

(defn- plain-text
  "The mock LLM with `policy`, except that Hale's speaking turns answer
  with `text` as plain text, with no tool call."
  [policy text]
  (let [m (llm/mock policy)]
    (reify llm/LLM
      (complete [_ {:keys [ctx] :as request}]
        (if (and (:conversation ctx) (= "hale" (:agent-id ctx)))
          {:message {:role "assistant" :content text} :content text :tool-calls []
           :finish-reason "stop" :usage {:prompt-tokens 0 :completion-tokens 0 :cost 0}}
          (llm/complete m request))))))

(deftest plain-text-is-the-line
  (testing "a speaking turn's text with no tool call is taken as its reply, without quotation marks"
    (let [run (e/start-run :household {:llm-impl (plain-text (earl-opens {} chatty) "\"Very good, my lord.\"")
                                       :offline? true :test? true :run-id (test-id "plain")})]
      (doseq [id ["lord-claudeworth" "lady-claudeworth" "hale"]] @(e/place! run id "dining-room"))
      (round! run)
      (is (= [["hale" "Very good, my lord."] ["hale" "Very good, my lord."]] (filter #(= "hale" (first %)) (lines run))))
      (is (= :finished (ending run)))
      (let [hale (filter #(and (= "hale" (:agent %)) (:conversation %)) (store/read-llm (:dir run)))]
        (is (= 2 (count hale)) "one call per line")
        (is (= [{:role "user" :content "(Your text was taken as `reply`.) done"}] (:replies (first hale)))))
      (e/stop-run run)))
  (testing "text too long for a line is rejected, and the model may try again"
    (let [run (e/start-run :household {:llm-impl (plain-text (earl-opens {} chatty) (apply str (repeat 1600 "x")))
                                       :offline? true :test? true :run-id (test-id "plain")})]
      (doseq [id ["lord-claudeworth" "lady-claudeworth" "hale"]] @(e/place! run id "dining-room"))
      (round! run)
      (let [[first-call] (filter #(and (= "hale" (:agent %)) (:conversation %)) (store/read-llm (:dir run)))]
        (is (str/starts-with? (:content (first (:replies first-call)))
                              "(Your text was taken as `reply`.) error: invalid arguments: text is 1600 characters; the limit is 400")))
      (is (not-any? #(= "hale" (first %)) (lines run)))
      (is (= ["hale" "hale"] (map :actor (of-type run :conversation/silent))))
      (e/stop-run run)))
  (testing "a normal turn's plain text still gets the nudge"
    (let [m (llm/mock (constantly {:tool :pass}))
          talker (reify llm/LLM
                   (complete [_ {:keys [ctx] :as request}]
                     (if (and (= "lord-claudeworth" (:agent-id ctx)) (zero? (:step ctx)))
                       {:message {:role "assistant" :content "Good morning."} :content "Good morning." :tool-calls []
                        :finish-reason "stop" :usage {:prompt-tokens 0 :completion-tokens 0 :cost 0}}
                       (llm/complete m request))))
          run (round! (household (constantly nil) {:opts {:llm-impl talker}}))
          [earl] (filter #(= "lord-claudeworth" (:agent %)) (store/read-llm (:dir run)))]
      (is (= [{:role "user" :content "You must respond by calling one of the tools."}] (:replies earl)))
      (is (empty? (of-type run :agent/said)))
      (e/stop-run run))))

(deftest who-can-be-drawn-in
  (let [run (household (constantly {:tool :pass}) {:opts {:leave-out []}})
        _ @(e/spawn! run {:role :secretary :name "Clerk" :location "dining-room"})
        world (e/world run)
        check #(conversation/check %1 "lord-claudeworth" {:with %2})]
    (testing "named people must be here, awake, in play and not a human seat"
      (is (nil? (check world ["lady-claudeworth" "hale"])))
      (is (= "you can't converse with yourself" (check world ["lord-claudeworth"])))
      (is (= "no one has the id nobody" (check world ["nobody"])))
      (is (= "no one has the id gm" (check world ["gm"])))
      (is (= "Nellie is not here" (check world ["nellie"])))
      (is (= "name each person in with only once" (check world ["hale" "hale"])))
      (is (= "at most 3 people can join you; name up to 3 in with"
             (check world ["lady-claudeworth" "hale" "clerk" "nellie"])))
      (let [asleep (w/apply-event world (b/occupy world "hale" "asleep" 5 {:asleep? true}))]
        (is (= "Hale is asleep" (check asleep ["hale"])))
        (is (nil? (check (w/apply-event asleep (b/interrupt asleep "hale" "lady-claudeworth")) ["hale"]))
            "a roused sleeper can talk"))
      (is (nil? (check (w/apply-event world (b/occupy world "hale" "writing letters" 5)) ["hale"]))
          "someone busy but awake can talk")
      (let [hib (w/apply-event world (b/hibernate world "hale" "dozing in a chair"))]
        (is (= "Hale is dozing in a chair and not free to talk" (check hib ["hale"]))))
      (let [human (w/apply-event world {:type :agent/updated :data {:id "hale" :changes {:controller :human}}})]
        (is (= "Hale can't be drawn into a conversation" (check human ["hale"])))))
    (testing "leaving out with means everyone here who can talk, if there are no more than 3"
      (is (= ["lord-claudeworth" "lady-claudeworth" "hale" "clerk"] (conversation/members world "lord-claudeworth" nil)))
      (is (nil? (check world nil)))
      (let [nellie (w/apply-event world {:type :agent/placed :data {:id "nellie" :to "dining-room"}})]
        (is (= "4 people here could join you, but at most 3 can; name up to 3 in with" (check nellie nil)))
        (is (nil? (check (w/apply-event nellie (b/occupy nellie "nellie" "asleep" 5 {:asleep? true})) nil)))))
    (testing "with nobody else here"
      (let [alone (reduce #(w/apply-event %1 {:type :agent/placed :data {:id %2 :to "study"}})
                          world ["lady-claudeworth" "hale" "clerk"])]
        (is (= "there is no one here to talk to" (check alone nil)))
        (let [sleeper (w/apply-event alone {:type :agent/placed :data {:id "hale" :to "dining-room"}})
              sleeper (w/apply-event sleeper (b/occupy sleeper "hale" "asleep" 5 {:asleep? true}))]
          (is (= "no one here can talk: Hale is asleep" (check sleeper nil))))))
    (testing "one conversation started per round"
      (is (= "you have already started a conversation this round"
             (check (assoc-in world [:agents "lord-claudeworth" :conversed-round] (:round world)) ["hale"]))))
    (e/stop-run run)))

(deftest busy-members-stay-busy
  (let [run (household (earl-opens {} chatty))
        world (e/world run)]
    (e/emit! run [(b/occupy world "hale" "writing letters" 5)
                  (b/occupy world "lady-claudeworth" "reading" 6)])
    (e/emit! run [(b/interrupt (e/world run) "lady-claudeworth" "lord-claudeworth")])
    (round! run)
    (let [evs (e/events run)
          [start end] (map :id [(first (of-type run :conversation/started)) (first (of-type run :conversation/ended))])
          during (filter #(< start (:id %) end) evs)]
      (testing "both spoke in it"
        (is (= 2 (count (filter #(= "hale" (first %)) (lines run)))))
        (is (= 2 (count (filter #(= "lady-claudeworth" (first %)) (lines run))))))
      (testing "Hale is still writing letters until round 5"
        (is (= {:activity "writing letters" :until 5} (w/busy (e/world run) "hale"))))
      (testing "the countess's interruption wasn't ended by talking; she decided on her own turn"
        (is (empty? (filter #(#{:agent/freed :agent/resumed} (:type %)) during)))
        (is (= ["lady-claudeworth"] (map :actor (of-type run :agent/resumed))))
        (is (= {:activity "reading" :until 6} (w/busy (e/world run) "lady-claudeworth")))))
    (testing "the busy member is told so"
      (let [hale (first (filter #(and (= "hale" (:agent %)) (:conversation %)) (store/read-llm (:dir run))))]
        (is (str/includes? (:content (second (:messages hale))) "You are busy writing letters until 10:00; talking doesn't stop it."))))
    (e/stop-run run)))

(deftest who-hears-it
  (let [run (round! (household (earl-opens {:with ["lady-claudeworth"]} chatty)))]
    (testing "a bystander hears every line and isn't drawn in"
      (is (every? #(contains? (:visible-to %) "hale") (conversation-events run)))
      (is (not-any? #(= "hale" (first %)) (lines run)))
      (is (= ["lord-claudeworth" "lady-claudeworth"] (get-in (first (of-type run :conversation/started)) [:data :members]))))
    (testing "someone elsewhere hears none of it"
      (is (not-any? #(w/visible-to? % "pugh") (conversation-events run))))
    (e/stop-run run)))

(defn- prompt-json [tool] (pr-str (get-in tool [:function :parameters])))

(deftest the-converse-tool
  (let [converse (first (filter #(= :converse (:name %)) (e/all-actions (e/load-scenario :household))))
        tool (a/->tool converse)]
    (testing "it's free, once per turn, and tells models 400 characters"
      (is (str/ends-with? (get-in tool [:function :description])
                          "Free: does not end your turn; the others answer before it returns. Once per turn."))
      (is (str/includes? (get-in tool [:function :description]) "at most 400 characters"))
      (is (not (str/includes? (prompt-json tool) "1500")))))
  (testing "speaking-turn tools say they end the line, not the turn"
    (doseq [tool (map a/->tool (conversation/speaking-actions {:id "c" :initiator "a"} "b"))]
      (is (str/ends-with? (get-in tool [:function :description])
                          "Ends your part in this round of the conversation, not your own turn."))))
  (testing "quotation marks around a whole line are dropped"
    (let [reply (first (conversation/speaking-actions {:id "c" :initiator "a"} "b"))
          text #(get-in (first (a/effect-events reply {:agents {}} "b" {:text %})) [:data :text])]
      (is (= "My lady, it shall be so." (text "\"\"My lady, it shall be so.\"\"")))
      (is (= "My lady." (text "“My lady.”")))
      (is (= "He said \"no\" twice." (text "He said \"no\" twice.")))
      (is (= "\"Yes,\" I said, \"now.\"" (text "\"Yes,\" I said, \"now.\"")))))
  (testing "with may be a single id"
    (is (= {:args {:with ["hale"] :text "Hello."}}
           (a/decode-args conversation/converse "{\"with\": \"hale\", \"text\": \"Hello.\"}"))))
  (testing "lines up to 1500 characters are accepted; longer ones are advised down to 400"
    (let [reply (first (conversation/speaking-actions {:id "c" :initiator "a"} "b"))]
      (is (:args (a/decode-args reply {:text (apply str (repeat 1500 "x"))})))
      (is (str/starts-with? (:error (a/decode-args reply {:text (apply str (repeat 1501 "x"))}))
                            "invalid arguments: text is 1501 characters; the limit is 400 - shorten it by at least 1101"))))
  (testing "a second converse in the same turn is refused"
    (let [run (household (fn [{:keys [agent-id role step conversation]}]
                           (cond (= role :gm) {:tool :done}
                                 conversation {:tool :end-conversation}
                                 (and (= agent-id "lord-claudeworth") (< step 2)) {:tool :converse :args {:text "Well?"}}
                                 :else {:tool :pass})))
          run (round! run)
          earl (filter #(and (= "lord-claudeworth" (:agent %)) (nil? (:conversation %))) (store/read-llm (:dir run)))]
      (is (= 1 (count (of-type run :conversation/started))))
      (is (str/starts-with? (:content (first (:replies (second earl)))) "rejected: you can use converse only once per turn"))
      (e/stop-run run))))

(deftest not-offered-in-a-simultaneous-group-or-to-a-human
  (let [run (e/start-run :prize {:llm :mock :test? true :run-id (test-id "prize")})]
    (round! run)
    (let [calls (remove #(= "gm" (:agent %)) (store/read-llm (:dir run)))]
      (is (seq calls))
      (is (not-any? #(some #{"converse"} (:tools %)) calls)))
    (e/stop-run run))
  (let [run (household (constantly {:tool :pass}))]
    @(e/update-agent! run "hale" {:controller :human})
    (e/step! run)
    (is (= :awaiting-human (e/wait-idle run 60000)))
    (is (not (some #{"converse"} (:tools (deref (:input run))))))
    (e/act! run "hale" :pass {})
    (is (= :idle (e/wait-idle run 60000)))
    (e/stop-run run)))
