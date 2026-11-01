(ns manor.engine.talk
  "Running a conversation (manor.mechanics.conversation has its rules): the speaking
  turns, each a short model call by the speaker, the conversation judge
  (with inner state), and the summary the initiator gets back as the reply
  to `converse`."
  (:require [clojure.string :as str]
            [manor.actions :as a]
            [manor.engine.judges :as judges]
            [manor.engine.models :as models]
            [manor.engine.resolve :as resolve]
            [manor.engine.run :as run]
            [manor.engine.turn :as turn]
            [manor.mechanics.conversation :as conversation]
            [manor.mechanics.conversation.judge :as conversation-judge]
            [manor.mechanics.physical :as physical]
            [manor.narrate :as narrate]
            [manor.prompt :as prompt]
            [manor.prompts :as prompts]
            [manor.world :as w]))

(defn- once-per-line
  "A query tool on a line is used at most once there, unless it has a
  stricter limit of its own."
  [action]
  (cond-> action (and (= :query (a/kind action)) (nil? (:per-turn action))) (assoc :per-turn 1)))

(defn- extra-speaking-actions
  "Scenario tools for this line: only available free/query actions, never
  replacements for the conversation's tools or note; each query at most
  once."
  [scenario world speaker conv]
  (let [extras (->> ((:speaking-actions scenario (constantly [])) world speaker conv)
                    (filter #(#{:free :query} (a/kind %)))
                    (remove #(#{:reply :stay-silent :leave :end-conversation :note} (:name %))))]
    (mapv once-per-line (a/available extras world speaker))))

(defn- line-queries
  "How many queries a line with the scenario tools `extra-actions` may make:
  one for each query tool offered."
  [extra-actions]
  (count (filter #(= :query (a/kind %)) extra-actions)))

(defn- speaking-turn-steps
  "Three calls for a line and retries; one more for each query it may make."
  [queries]
  (+ 3 queries))

(defn- speaking-reminder
  "The reminder before a speaking turn's last call."
  [world max-steps st calls-made actions]
  (when (and (empty? (:finals st)) (= calls-made (dec max-steps)))
    (prompts/render world "agent/reminder-speaking-last.md"
                    {:actions (turn/or-list (map (comp name :name) (filter a/ends-turn? actions)))})))

(defn- during
  "The events of conversation `id` after it started, up to its end."
  [run id]
  (->> @(:events run)
       (drop-while #(not (and (= :conversation/started (:type %))
                              (= id (get-in % [:data :conversation])))))
       rest
       (take-while #(not (and (= :conversation/ended (:type %))
                              (= id (get-in % [:data :conversation])))))))

(defn- heard
  "The events of conversation `id` so far that `viewer` saw."
  [run id viewer]
  (->> (during run id)
       (filter #(w/visible-to? % viewer))
       (remove #(or (= :llm/usage (:type %)) (= :system (:visible-to %))))))

(defn- human-context
  "The heading of a human speaker's form: whose conversation, which line,
  and what they've heard of it so far."
  [run conv speaker {:keys [pass final? in]}]
  (let [world @(:world run)
        names (narrate/names world)
        others (remove #{speaker} in)]
    (str "Your " (if final? "last word" (str "line (pass " pass " of " conversation/passes ")"))
         " in the conversation with " (str/join ", " (map #(narrate/who names %) others))
         ". So far:\n" (str/join "\n" (map #(narrate/describe % names) (heard run (:id conv) speaker))))))

(defn- speaking-turn!
  "One member's turn to speak in conversation `conv`. `turn` is {:pass
  :final? :in} (see manor.prompt/conversation-messages). Returns :spoke,
  :silent, :left or :ended, or :failed if the call failed (the run is then
  asked to pause). A member who takes no action is silent; an initiator who
  takes none ends it. Nobody's occupation is touched."
  [run conv speaker turn]
  (let [{:keys [scenario opts]} run
        world @(:world run)
        extra-actions (extra-speaking-actions scenario world speaker conv)
        queries (line-queries extra-actions)
        max-steps (speaking-turn-steps queries)
        actions (into (cond-> (conversation/speaking-actions world conv speaker)
                        (run/action-named run :note) (conj (run/action-named run :note)))
                      extra-actions)
        initiator? (= speaker (:initiator conv))]
    (run/set-status! run update :acting conj speaker)
    (try
      (let [call-opts {:commit (fn [action args] (resolve/resolve! run speaker action args true))
                       :max-finals 1 :max-queries queries :keep-occupation? true}
            st (if (= :human (:controller (w/agent world speaker)))
                 (turn/human-calls run speaker (constantly actions) call-opts
                                   (human-context run conv speaker turn))
                 (turn/run-calls run speaker
                                 {:settings (models/call-settings opts (w/agent world speaker))
                                  :messages (prompt/conversation-messages scenario world @(:events run) speaker opts conv
                                                                          (assoc turn :extra-actions extra-actions))
                                  :actions actions
                                  :max-steps max-steps
                                  ;; models often write the line as plain text, not as a reply
                                  :call-opts (assoc call-opts :text-as (when (physical/can? world speaker :speech) "reply"))
                                  :remind (partial speaking-reminder world max-steps)
                                  :ctx {:role (:role (w/agent world speaker))
                                        :conversation (assoc (select-keys conv [:id :initiator :members])
                                                             :pass (:pass turn) :final? (boolean (:final? turn)))}
                                  :log {:conversation {:id (:id conv) :pass (if (:final? turn) :last-word (:pass turn))}}}))
            outcome (conversation/outcome (some-> (first (:finals st)) :action :name name))]
        (cond
          (and initiator? (= :silent outcome)) :ended
          (and (= :silent outcome) (empty? (:finals st)))
          (do (run/emit! run [(conversation/silent @(:world run) speaker conv)]) :silent)
          :else outcome))
      (catch Throwable t
        (run/emit! run [{:type :run/pause-requested :visible-to :system
                         :data {:reason (str "error during " speaker "'s line in " (:id conv) ": " (ex-message t))}}])
        :failed)
      (finally
        (run/set-status! run update :acting disj speaker)))))

(defn- converse!
  "Run the speaking turns of `conv` after the initiator's opening line, and
  return how it ended (a key of manor.mechanics.conversation/endings): up to
  `conversation/passes` passes, the initiator first (who opened pass 1
  already), then the initiator's last word. It ends early when the
  initiator ends it, when everyone else has left, when nobody else speaks
  in a pass, at the cost limit, or when a call fails."
  [run {:keys [initiator members] :as conv}]
  (loop [pass 1, queue (vec (rest members)), in members, answered? false]
    (cond
      (run/cost-reached? run) :cost-limit
      (empty? queue)
      (cond (not answered?) :silence
            (< pass conversation/passes) (recur (inc pass) in in false)
            :else (case (speaking-turn! run conv initiator {:pass pass :final? true :in in})
                    :failed :error
                    :ended :ended
                    :finished))
      :else
      (let [speaker (first queue)
            outcome (speaking-turn! run conv speaker {:pass pass :in in})
            in' (if (= :left outcome) (vec (remove #{speaker} in)) in)]
        (case outcome
          :failed :error
          :ended :ended
          (if (= [initiator] in')
            :everyone-left
            (recur pass (subvec queue 1) in' (or answered? (and (not= speaker initiator) (= :spoke outcome))))))))))

(defn- judge!
  "With inner state in the scenario, ask the conversation judge how `conv`
  left its members and record its verdict; a failure changes nothing. Not
  when it was cut short (`ending` :cost-limit or :error) or the run's cost
  limit is reached. It runs before the initiator's summary, so their next
  step and everyone's later turns see the result."
  [run conv ending]
  (when (and (get-in run [:scenario :inner])
             (not (#{:cost-limit :error} ending))
             (not (run/cost-reached? run)))
    (let [{:keys [verdict failure]} (judges/ask-conversation-judge run @(:world run) conv (during run (:id conv)))]
      (run/emit! run (if verdict
                       (conversation-judge/judged-events (:id conv) verdict)
                       [(conversation-judge/judge-failed (:id conv) failure)])))))

(def ^:private how-to-hand-over
  "After `conversation/nothing-handed`, in the initiator's summary."
  "It changes hands only when someone `give`s it, finding it with `find` first if they don't carry it.")

(defn- conversation-summary
  "The initiator's tool reply: visible events during the conversation, its
  ending, and (`handed` 0) that nothing changed hands."
  [run {:keys [id initiator]} ending handed]
  (let [names (narrate/names @(:world run))
        lines (heard run id initiator)]
    (str "The conversation is over: " (conversation/endings ending) ".\n"
         (str/join "\n" (map #(narrate/describe % names) lines))
         (when (= 0 handed) (str "\n" conversation/nothing-handed " " how-to-hand-over))
         "\nCarry on with your turn.")))

(defn run-conversation!
  "Start and run a conversation for `actor`'s `converse` call. Returns
  {:rejected reason} or {:summary text}, the tool reply."
  [run actor action {:keys [with text] :as args}]
  (let [world @(:world run)]
    (if-let [reason (a/check action world actor args)]
      {:rejected reason}
      (let [_ (resolve/stop-interrupted! run actor action)
            world @(:world run)
            conv {:id (conversation/new-id world) :initiator actor
                  :members (conversation/members world actor with)}]
        (locking (:world run)
          (run/emit! run [(conversation/started @(:world run) actor conv)])
          (run/emit! run (conversation/line-events @(:world run) actor conv text)))
        (let [ending (converse! run conv)
              handed (conversation/handed @(:world run) (during run (:id conv)))]
          (run/emit! run [(conversation/ended @(:world run) conv ending handed)])
          (judge! run conv ending)
          {:summary (conversation-summary run conv ending handed)})))))
