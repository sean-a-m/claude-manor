(ns manor.engine.judges
  "The narrow model calls that answer one checked question: the work judge,
  the conversation judge, the nightly steward and scenario adjudicators.
  Each gets a checked answer, up to three correction attempts and a safe failure;
  the domain code (manor.mechanics.items, manor.mechanics.conversation.judge,
  the scenario) owns the prompts and the checks."
  (:require [manor.engine.models :as models]
            [manor.engine.run :as run]
            [manor.mechanics.conversation.judge :as conversation-judge]
            [manor.mechanics.items.judge :as item-judge]
            [manor.mechanics.items.steward :as item-steward]
            [manor.mechanics.items.work :as item-work]
            [manor.prompt :as prompt]))

(def max-attempts
  "Total attempts for a checked model answer: the initial call plus corrections."
  4)

(defn ask-structured!
  "Shared narrow model-call lifecycle: checked answer, up to three corrections,
  usage/transcript logging and safe failure. The domain owns prompts/checks."
  [run identity {:keys [messages tools ctx label defer-records? model before-call] :or {label "adjudicator"}} check]
  (let [records (atom [])
        record! (fn [f & args]
                  (if defer-records?
                    (swap! records conj #(apply f args))
                    (apply f args)))
        opts (:opts run)
        model (or model (:judge-model opts) (:model opts))]
    (let [result
          (try
            (loop [messages messages, attempt 0]
              (when before-call (before-call))
              (let [request {:model model :messages messages :tools tools
                             :max-tokens (:max-tokens opts) :reasoning (:reasoning opts)
                             :tool-choice (:tool-choice opts)}
                    response (models/complete! run identity (assoc request :ctx (assoc ctx :step attempt)))
                    _ (record! models/record-usage! run identity request response)
                    {:keys [verdict error replies]}
                    (try (check response)
                         (catch Throwable t
                           (record! models/record-call! run identity request response [])
                           (throw t)))]
                (record! models/record-call! run identity request response replies)
                (cond verdict {:verdict verdict}
                      (< (inc attempt) max-attempts)
                      (recur (into (models/conj-reply messages (:message response)) replies) (inc attempt))
                      :else {:failure (str "no valid answer after " max-attempts " attempts: " error)})))
            (catch Throwable t
              {:failure (str "the " label " call failed: " (ex-message t))}))]
      (cond-> result defer-records? (assoc :records @records)))))

(defn ask-judge
  "Ask the judge what `job` did to the judged `things` (see
  items/judged-things): {:summary :changes}, with :inner when the scenario
  has inner state and it changed, :relations when it has relations and the
  judge added any, :physical for changes to bodies and, for
  an immediate act (a job with :act?), :moved, or {:failure reason}. An
  act's recent lines are the actor's last, not those since a job began. A bad
  answer is sent back with the reason, up to four total attempts. Each call is
  recorded in llm.edn as agent \"judge\", and its usage counts towards the
  run's cost."
  [run world job work things]
  (let [work (assoc work :recent (if (:act? job)
                                   (prompt/shared-recent-lines world (run/events run) (:actor job))
                                   (prompt/scene-recent world (run/events run) job)))
        request (assoc (item-judge/judge-request (:scenario run) world job work)
                       :label "judge" :defer-records? true
                       :ctx (assoc (select-keys work [:minutes :stopped-early? :luck])
                                   :role :judge :world world :job job :things things
                                   :focus (item-work/focus-things world job)))
        {:keys [verdict] :as result} (ask-structured! run "judge" request #(item-judge/judgement % world things (get-in run [:scenario :inner]) job
                                                                                   (get-in run [:scenario :relations])))]
    (assoc result :verdict verdict)))

(defn ask-conversation-judge
  "Ask the conversation judge how conversation `conv` ({:id :initiator
  :members}) left its members, from `events` (the conversation's, at least):
  {:verdict {:summary :inner}}, with :relations when the scenario has them
  and the judge added any, or {:failure reason}. A bad answer is sent
  back with its reason, up to four total attempts. Each call is recorded in llm.edn as agent
  \"conversation-judge\", and its usage counts towards the run's cost."
  [run world conv events]
  (let [config (get-in run [:scenario :inner])
        relations-config (get-in run [:scenario :relations])
        lines (conversation-judge/lines events (:id conv))
        request (assoc (conversation-judge/judge-request config world conv lines relations-config
                                                         (get-in run [:scenario :order-books]))
                       :label "conversation judge"
                       :ctx {:role :conversation-judge :world world :conversation conv :lines lines})]
    (ask-structured! run "conversation-judge" request
                     #(conversation-judge/judgement % world config (:members conv) relations-config))))

(defn adjudicate
  "Ask the scenario's adjudicator for `task` about `args`, on `world`:
  {:verdict v}, or {:failure reason}. The request also gets the run's
  events, for adjudicators that read history. A request that already holds a
  :result needs no call. It's on :judge-model (or :model), unless its
  adjudicator is a character writer (:writer? true, e.g. hiring's appearance
  and polish), which is on the run's :model so that writing people doesn't
  inherit a judge's model. `call-opts` add to the call, e.g. :before-call
  (see `ask-structured!`)."
  [run task world actor args call-opts]
  (let [spec (get-in run [:scenario :adjudicators task])
        {:keys [messages tools ctx result check] :as request} ((:request spec) world actor args (run/events run))]
    (if (contains? request :result)
      {:verdict result}
      (ask-structured! run (str "adjudicator/" (subs (str task) 1))
                       (merge {:messages messages :tools tools
                               :ctx (assoc ctx :role :adjudicator :task task)}
                              (when (:writer? spec) {:model (get-in run [:opts :model])})
                              call-opts)
                       (or check (:check spec))))))

(defn ask-steward
  "Ask the nightly steward what to do with the `candidates` (idle
  leftovers), `sleepers` and other `hindered` people at `place`:
  {:verdict v}, with checked recovery under :physical and the sleepers'
  inner-state changes under :inner when the scenario has inner state, or
  {:failure reason}."
  [run world place candidates sleepers hindered]
  (let [config (get-in run [:scenario :inner])
        request (assoc (item-steward/steward-request world place candidates sleepers config hindered)
                       :label "steward" :ctx {:role :steward :world world :place place
                                              :candidates candidates :sleepers sleepers :hindered hindered})]
    (ask-structured! run "steward" request #(item-steward/stewardship % world place candidates sleepers config hindered))))
