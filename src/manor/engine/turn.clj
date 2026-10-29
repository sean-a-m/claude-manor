(ns manor.engine.turn
  "The model calls of one turn, or one speaking turn in a conversation:
  each response's tool calls are handled in order, with replies, nudges
  and reminders sent back, until a turn-ending action or the step limit."
  (:require [clojure.string :as str]
            [manor.actions :as a]
            [manor.engine.models :as models]
            [manor.engine.resolve :as resolve]
            [manor.engine.run :as run]
            [manor.prompt :as prompt]))

(defn- limit-reached
  "Why `actor` can't use `action` again this turn, or nil: its :per-turn
  uses (counted under its limit key, in the turn state's :uses) are spent."
  [by-name st action world actor]
  (let [k (a/limit-key action)
        n (a/per-turn action world actor)]
    (when (and n (>= (get-in st [:uses k] 0) n))
      (let [sharing (sort (keep #(when (= k (a/limit-key %)) (name (:name %))) (vals by-name)))]
        (str "you can use " (str/join " or " sharing) " only "
             (if (= 1 n) "once" (str n " times")) " per turn; take your turn-ending action now, and use "
             (if (next sharing) "them" "it") " again next turn")))))

(defn- use-up
  "The turn state after an accepted use of an action with a :per-turn limit."
  [st action]
  (cond-> st (:per-turn action) (update-in [:uses (a/limit-key action)] (fnil inc 0))))

(defn handle-call
  "Process one tool call within a turn. `commit` either applies a turn-ending
  action now (sequential turns, GM) or only checks it and defers it
  (simultaneous groups). `converse` (fn [action args]) runs a conversation
  (manor.engine.talk/run-conversation!); it's needed only where a
  conversation is offered. Returns the updated turn state."
  [run agent-id by-name {:keys [commit converse max-finals max-queries keep-occupation?]} st {:keys [id name arguments]}]
  (let [reply (fn [st text] (update st :replies conj {:role "tool" :tool_call_id id :content text}))
        action (get by-name name)]
    (cond
      (:done? st) (reply st "ignored: your turn is already over")
      (nil? action) (reply st (str "error: there is no tool called " name))
      :else
      (let [{:keys [args error]} (a/decode-args action arguments)
            spent (when-not error (limit-reached by-name st action @(:world run) agent-id))]
        (cond
          error (reply st (str "error: " error))
          spent (reply st (str "rejected: " spent))
          :else
          (case (a/kind action args)
            :free
            (let [{:keys [rejected world]} (resolve/resolve! run agent-id action args keep-occupation?)]
              (-> st
                  (update :used conj name)
                  (cond-> (not rejected) (use-up action))
                  (reply (if rejected
                           (str "rejected: " rejected)
                           (resolve/free-reply action world agent-id args)))))

            :converse
            (let [{:keys [rejected summary]} (converse action args)]
              (-> st
                  (update :used conj name)
                  (cond-> (not rejected) (use-up action))
                  (reply (if rejected (str "rejected: " rejected) summary))))

            :query
            (if (>= (:queries st) max-queries)
              (reply st (str "error: query limit reached for this turn (" max-queries "); take your action now, and query again next turn"))
              (let [{:keys [result error] :as response} (resolve/run-query! run agent-id action args keep-occupation?)]
                (-> st
                    (update :queries inc)
                    (update :used conj name)
                    (cond-> (or (not error) (:reply response)) (use-up action))
                    (reply (or (:reply response) (if error (str "error: " error) (prompt/->json result)))))))

            (:final :act)
            (let [{:keys [rejected] judged :reply} (commit action args)]
              (if rejected
                (reply st (str "rejected: " rejected))
                (let [st (-> st
                             (use-up action)
                             (update :finals conj {:action action :args args})
                             (update :used conj name)
                             (reply (cond (= :act (a/kind action)) "requested; it will happen if the operator approves"
                                          judged judged
                                          :else "done")))]
                  (cond-> st
                    (or (= "done" name) (>= (count (:finals st)) max-finals))
                    (assoc :done? true)))))))))))

(defn human-calls
  "A human seat's turn or speaking turn: wait for `act!` input and handle
  each as a tool call, its reply shown as feedback, until a turn-ending
  action finishes it. `actions-fn` (fn []) gives the tools for each wait;
  `context` (text, or nil) heads the panel's form. Returns the turn state."
  [run agent-id actions-fn call-opts context]
  (loop [st {:finals [] :queries 0 :used []} feedback nil]
    (let [actions (actions-fn)
          by-name (a/by-name actions)
          p (promise)]
      (reset! (:input run) {:agent agent-id :promise p :request-id (str (random-uuid))
                           :tools (sort (keys by-name)) :tool-specs (mapv #(a/->tool % @(:world run)) actions)
                           :feedback feedback :context context})
      (run/set-status! run assoc :state :awaiting-human :awaiting agent-id)
      (let [{:keys [action args]} @p
            st' (handle-call run agent-id by-name call-opts (assoc st :replies [])
                             {:id "human" :name action :arguments args})
            fb (:content (last (:replies st')))
            st' (dissoc st' :replies)]
        (if (:done? st')
          (do (reset! (:input run) nil)
              (run/set-status! run assoc :state :running :awaiting nil :last-human-feedback fb)
              st')
          (recur st' fb))))))

(def ^:private early-reminder-at
  "Calls into a turn, with no turn-ending action yet, after which an agent is
  first reminded to take one. It is reminded again before its last call."
  2)

(defn or-list [names]
  (let [names (map #(str "`" % "`") names)]
    (if (next names)
      (str (str/join ", " (butlast names)) " or " (last names))
      (first names))))

(defn turn-reminder
  "A reminder for an agent that has made `calls-made` calls this turn without
  a turn-ending action, or nil: once `early-reminder-at` calls have passed,
  and again before the last call. `final-names` are the turn-ending actions
  it has."
  [calls-made max-steps final-names]
  (let [wait (when (some #{"pass"} final-names)
               " If you're unsure what to do, `pass` is a fine way to wait and see.")]
    (cond
      (>= calls-made max-steps) nil
      (= calls-made (dec max-steps))
      (str "This is your last call this turn. Finish now with one turn-ending action ("
           (or-list final-names) "), or you lose your turn." wait)
      (= calls-made early-reminder-at)
      (str "Your turn isn't over: free tools, queries and rejected calls don't end it. "
           "When you're ready, finish with one turn-ending action: " (or-list final-names) "." wait))))

(defn- nudge
  "The reply to a response without tool calls. One cut off by `:max-tokens`
  (usually all reasoning) is told to think briefly."
  [response]
  (if (= "length" (:finish-reason response))
    "You ran out of room while thinking, before calling a tool. Think briefly, then call one of the tools."
    "You must respond by calling one of the tools."))

(defn- handle-text-as
  "A response with text and no tool call, taken as a call of `tool` with
  that text (a speaking turn's line). The engine's reply goes back as a
  user message, since the model made no tool call to answer."
  [run agent-id by-name call-opts st tool text]
  (let [st (handle-call run agent-id by-name call-opts (assoc st :replies [])
                        {:id "text" :name tool :arguments {:text text}})]
    (update st :replies (partial mapv (fn [{:keys [content] :as m}]
                                        (if (= "text" (:tool_call_id m))
                                          {:role "user" :content (str "(Your text was taken as `" tool "`.) " content)}
                                          m))))))

(defn- handle-response
  "The turn state after one model response, with :replies (the messages to
  send back). A response without tool calls is told to call one; a second
  ends the turn. With call-opts :text-as (a tool name), its text, if any,
  is taken as a call of that tool instead."
  [run agent-id by-name call-opts st response]
  (let [calls (:tool-calls response)
        text (some-> (:content response) str/trim not-empty)]
    (cond
      (seq calls) (reduce (partial handle-call run agent-id by-name call-opts) (assoc st :replies []) calls)
      (and text (:text-as call-opts)) (handle-text-as run agent-id by-name call-opts st (:text-as call-opts) text)
      (:nudged st) (assoc st :replies [] :done? true)
      :else (assoc st :nudged true
                   :replies [{:role "user" :content (nudge response)}]))))

(defn- add-reminder
  "Append `text` to the replies as a user message, merged into one already
  there."
  [replies text]
  (let [lst (peek replies)]
    (cond (nil? text) replies
          (= "user" (:role lst)) (conj (pop replies) (update lst :content str " " text))
          :else (conj replies {:role "user" :content text}))))

(defn run-calls
  "The model calls of one turn (or speaking turn), until it's done or
  `max-steps` calls are made. `messages` start the conversation with the
  model, `actions` are its initial tools. Optional `refresh-actions` (fn [])
  supplies the next step's actions from the live world. `remind` (fn
  [turn-state calls-made next-actions]) may add a reminder after a call.
  `ctx` goes to the LLM with each call and `log` into each llm.edn entry.
  Returns the turn state."
  [run agent-id {:keys [model messages actions refresh-actions max-steps call-opts remind ctx log]}]
  (let [{:keys [opts]} run]
    (loop [messages messages, actions actions, step 0, st {:finals [] :queries 0 :used [] :nudged false}]
      (if (or (:done? st) (>= step max-steps))
        st
        (let [by-name (a/by-name actions)
              tools (mapv #(a/->tool % @(:world run)) actions)
              request {:model model :messages messages :tools tools
                       :max-tokens (:max-tokens opts) :reasoning (:reasoning opts)
                       :tool-choice (:tool-choice opts)}
              response (models/complete! run
                                         (assoc request :ctx (merge {:world @(:world run) :agent-id agent-id
                                                                     :step step :used (:used st)}
                                                                    ctx)))
              _ (models/record-usage! run agent-id request response)
              st' (try (handle-response run agent-id by-name call-opts st response)
                       (catch Throwable t
                         (models/record-call! run agent-id request response [] log)
                         (throw t)))
              next-actions (if (and refresh-actions (not (:done? st')))
                             (refresh-actions)
                             actions)
              replies (cond-> (:replies st')
                        (not (:done? st')) (add-reminder (remind st' (inc step) next-actions)))]
          (models/record-call! run agent-id request response replies log)
          (recur (into (models/conj-reply messages (:message response)) replies)
                 next-actions (inc step) (dissoc st' :replies)))))))
