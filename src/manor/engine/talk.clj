(ns manor.engine.talk
  "Running a conversation (manor.mechanics.conversation has its rules): the speaking
  turns, each a short model call by the speaker, and the summary the
  initiator gets back as the reply to `converse`."
  (:require [clojure.string :as str]
            [manor.actions :as a]
            [manor.engine.resolve :as resolve]
            [manor.engine.run :as run]
            [manor.engine.turn :as turn]
            [manor.mechanics.conversation :as conversation]
            [manor.narrate :as narrate]
            [manor.prompt :as prompt]
            [manor.world :as w]))

(defn- extra-speaking-actions
  "Scenario tools for this line: only available free/query actions, never
  replacements for the conversation's tools or note."
  [scenario world speaker conv]
  (let [extras (->> ((:speaking-actions scenario (constantly [])) world speaker conv)
                    (filter #(#{:free :query} (a/kind %)))
                    (remove #(#{:reply :stay-silent :leave :end-conversation :note} (:name %))))]
    (vec (a/available extras world speaker))))

(defn- speaking-turn-steps
  "Three calls for a line and retries; one more when a query is offered."
  [query?]
  (if query? 4 3))

(defn- speaking-reminder
  "The reminder before a speaking turn's last call."
  [max-steps st calls-made actions]
  (when (and (empty? (:finals st)) (= calls-made (dec max-steps)))
    (str "This is your last call for this line. Answer now with "
         (turn/or-list (map (comp name :name) (filter a/ends-turn? actions))) ".")))

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
        query? (some #(= :query (a/kind %)) extra-actions)
        max-steps (speaking-turn-steps query?)
        actions (into (cond-> (conversation/speaking-actions conv speaker)
                        (run/action-named run :note) (conj (run/action-named run :note)))
                      extra-actions)
        initiator? (= speaker (:initiator conv))]
    (run/set-status! run update :acting conj speaker)
    (try
      (let [st (turn/run-calls run speaker
                               {:model (or (:model (w/agent world speaker)) (:model opts))
                                :messages (prompt/conversation-messages scenario world @(:events run) speaker opts conv
                                                                        (assoc turn :extra-actions extra-actions))
                                :actions actions
                                :max-steps max-steps
                                :call-opts {:commit (fn [action args] (resolve/resolve! run speaker action args true))
                                            :max-finals 1 :max-queries (if query? 1 0) :keep-occupation? true
                                       ;; models often write the line as plain text, not as a reply
                                            :text-as "reply"}
                                :remind (partial speaking-reminder max-steps)
                                :ctx {:role (:role (w/agent world speaker))
                                      :conversation (assoc (select-keys conv [:id :initiator :members])
                                                           :pass (:pass turn) :final? (boolean (:final? turn)))}
                                :log {:conversation {:id (:id conv) :pass (if (:final? turn) :last-word (:pass turn))}}})
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

(defn- conversation-summary
  "The initiator's tool reply: visible events during the conversation and its ending."
  [run {:keys [id initiator]} ending]
  (let [names (narrate/names @(:world run))
        lines (->> @(:events run)
                   (drop-while #(not (and (= :conversation/started (:type %))
                                          (= id (get-in % [:data :conversation])))))
                   rest
                   (take-while #(not (and (= :conversation/ended (:type %))
                                          (= id (get-in % [:data :conversation])))))
                   (filter #(w/visible-to? % initiator))
                   (remove #(or (= :llm/usage (:type %)) (= :system (:visible-to %)))))]
    (str "The conversation is over: " (conversation/endings ending) ".\n"
         (str/join "\n" (map #(narrate/describe % names) lines))
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
          (run/emit! run [(conversation/said @(:world run) actor conv text)]))
        (let [ending (converse! run conv)]
          (run/emit! run [(conversation/ended @(:world run) conv ending)])
          {:summary (conversation-summary run conv ending)})))))
