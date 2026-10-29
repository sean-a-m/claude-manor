(ns manor.engine.seat
  "One seat's turn: the actions it has, then the model's calls, or a human's
  input through manor.engine/act!. A turn without a turn-ending action is
  forfeited."
  (:require [manor.actions :as a]
            [manor.engine.run :as run]
            [manor.engine.talk :as talk]
            [manor.engine.turn :as turn]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.positions :as positions]
            [manor.prompt :as prompt]
            [manor.world :as w]))

(defn- turn-actions
  "The actions `agent-id` has on this turn. A conversation isn't offered
  in a simultaneous group, where the others are in their own turns."
  [run world agent-id {:keys [role simultaneous?]}]
  (cond->> (a/available (a/for-role (:actions run) role (positions/capabilities world agent-id)) world agent-id)
    simultaneous? (remove #(= :converse (a/kind %)))))

(defn- activate-llm [run snapshot agent-id {:keys [role reasons calendar-due max-finals commit] :as turn}]
  (let [{:keys [scenario opts]} run
        gm? (= role :gm)
        actions (turn-actions run snapshot agent-id turn)
        agent (w/agent snapshot agent-id)
        max-steps (if gm? (+ 2 (:gm-max-actions opts)) (:max-steps opts))]
    (turn/run-calls run agent-id
                    {:model (or (:model agent) (when gm? (:gm-model opts)) (:model opts))
                     :messages (if gm?
                                 (prompt/gm-messages scenario snapshot @(:events run) reasons (assoc opts :calendar-due calendar-due))
                                 (prompt/agent-messages scenario snapshot @(:events run) agent-id opts))
                     :actions actions
                     :refresh-actions #(turn-actions run @(:world run) agent-id turn)
                     :max-steps max-steps
                     :call-opts {:commit commit :max-finals max-finals :max-queries (:max-queries opts)
                                 :converse (fn [action args] (talk/run-conversation! run agent-id action args))}
                ;; the GM's turn has no forfeit, so only participants are reminded
                     :remind (fn [st calls-made next-actions]
                               (when (and (not gm?) (empty? (:finals st)))
                                 (turn/turn-reminder @(:world run) calls-made max-steps
                                                     (map (comp name :name) (filter a/ends-turn? next-actions)))))
                     :ctx {:role role :reasons reasons}})))

(defn- activate-human
  "A human holds this seat: wait for `act!` input. Free and query actions
  are applied and the wait continues (a conversation runs its lines, the
  human's included, before it does); a turn-ending action finishes."
  [run agent-id {:keys [max-finals commit] :as turn}]
  (turn/human-calls run agent-id
                    #(turn-actions run @(:world run) agent-id turn)
                    {:commit commit :max-finals max-finals :max-queries (:max-queries (:opts run))
                     :converse (fn [action args] (talk/run-conversation! run agent-id action args))}
                    nil))

(defn activate
  "One agent's turn. Never throws: failures forfeit the turn and ask the
  run to pause so the operator can look."
  [run snapshot agent-id {:keys [role] :as turn}]
  (run/set-status! run update :acting conj agent-id)
  (try
    (let [controller (:controller (w/agent snapshot agent-id))
          st (if (= :human controller)
               (activate-human run agent-id turn)
               (activate-llm run snapshot agent-id turn))]
      (when (and (empty? (:finals st)) (not= role :gm))
        (run/emit! run [{:type :action/forfeited :actor agent-id :visible-to #{agent-id}
                         :data {:reason "no valid action taken"}}])
        (when (:interrupted-by (w/busy @(:world run) agent-id))
          (run/emit! run [(b/resume @(:world run) agent-id)])))
      (assoc st :agent-id agent-id))
    (catch Throwable t
      (run/emit! run [{:type :action/forfeited :actor agent-id :visible-to #{agent-id}
                       :data {:reason "the turn failed"}}
                      {:type :run/pause-requested :visible-to :system
                       :data {:reason (str "error during " agent-id "'s turn: " (ex-message t))}}])
      (when (:interrupted-by (w/busy @(:world run) agent-id))
        (run/emit! run [(b/resume @(:world run) agent-id)]))
      {:agent-id agent-id :finals []})
    (finally
      (run/set-status! run update :acting disj agent-id))))
