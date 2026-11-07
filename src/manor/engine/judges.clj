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
            [manor.mechanics.items.made-review :as made-review]
            [manor.mechanics.items.steward :as item-steward]
            [manor.mechanics.items.work :as item-work]
            [manor.prompt :as prompt]))

(def max-attempts
  "Total attempts for a checked model answer: the initial call plus corrections."
  4)

(defn- lookup-step
  "The replies to a response whose tool calls are all `lookups` (tool name ->
  (fn [raw-args] {:reply text} or {:error reason})), with :error when one
  failed and :used? when one answered."
  [lookups calls]
  (let [results (mapv (fn [{:keys [name arguments]}] ((get lookups name) arguments)) calls)
        error (some :error results)]
    {:replies (mapv (fn [{:keys [id name]} {:keys [reply error]}]
                      {:role "tool" :tool_call_id id
                       :content (if error (str "error: " error ". Call " name " again, or answer.") reply)})
                    calls results)
     :error error
     :used? (boolean (some :reply results))}))

(defn- lookups-spent
  "Replies to lookup calls once none are left, or alongside an answer."
  [calls why]
  (mapv (fn [{:keys [id name]}] {:role "tool" :tool_call_id id :content (str "ignored: " name " " why)}) calls))

(defn ask-structured!
  "Shared narrow model-call lifecycle: checked answer, up to three corrections,
  usage/transcript logging and safe failure. The domain owns prompts/checks.
  A request may offer `:lookups`, tools the model may call before answering
  (name -> (fn [raw-args] {:reply text} or {:error reason})), at most
  `:max-lookups` (default 1) answered calls in all. A response that only
  calls lookups gets their replies and doesn't spend an attempt, unless one
  of them fails; lookup calls beside an answer, or beyond the limit, are
  ignored. With `:keep-messages?`, a verdict comes with the conversation's
  :messages, for a follow-up in the same conversation."
  [run identity {:keys [messages tools ctx label defer-records? model before-call lookups max-lookups keep-messages?]
                 :or {label "adjudicator" max-lookups 1}} check]
  (let [records (atom [])
        record! (fn [f & args]
                  (if defer-records?
                    (swap! records conj #(apply f args))
                    (apply f args)))
        opts (:opts run)
        model (or model (:judge-model opts) (:model opts))]
    (let [result
          (try
            (loop [messages messages, attempt 0, step 0, looked 0]
              (when before-call (before-call))
              (let [request {:model model :messages messages :tools tools
                             :max-tokens (:max-tokens opts) :reasoning (:reasoning opts)
                             :tool-choice (:tool-choice opts)}
                    response (models/complete! run identity (assoc request :ctx (assoc ctx :step step :looked looked)))
                    _ (record! models/record-usage! run identity request response)
                    calls (:tool-calls response)
                    lookup-calls (filter #(contains? lookups (:name %)) calls)
                    only-lookups? (and (seq calls) (= (count lookup-calls) (count calls)))
                    {:keys [verdict error replies used?]}
                    (try (cond
                           (and only-lookups? (< looked max-lookups)) (lookup-step lookups lookup-calls)
                           only-lookups? {:error "no lookups left"
                                          :replies (lookups-spent lookup-calls "(none left): answer now")}
                           :else (let [answer (check (update response :tool-calls #(vec (remove (set lookup-calls) %))))]
                                   (update answer :replies #(into (lookups-spent lookup-calls "(only an answer counts here)") %))))
                         (catch Throwable t
                           (record! models/record-call! run identity request response [])
                           (throw t)))
                    next-messages (into (models/conj-reply messages (:message response)) replies)]
                (record! models/record-call! run identity request response replies)
                (cond verdict {:verdict verdict :messages next-messages}
                      used? (recur next-messages attempt (inc step) (inc looked))
                      (< (inc attempt) max-attempts) (recur next-messages (inc attempt) (inc step) looked)
                      :else {:failure (str "no valid answer after " max-attempts " attempts: " error)})))
            (catch Throwable t
              {:failure (str "the " label " call failed: " (ex-message t))}))]
      (cond-> result
        (not keep-messages?) (dissoc :messages)
        defer-records? (assoc :records @records)))))

(defn- review-made
  "The judge's `result` after its second look at the new things it made
  (manor.mechanics.items.made-review), in the same conversation. If the
  review fails, the verdict stands as it was."
  [run world job {:keys [verdict messages records] :as result} request]
  (let [{:keys [candidates made] :as review} (made-review/review-request world job verdict messages (run/events run))
        reviewed (ask-structured! run "judge"
                                  (assoc (select-keys review [:messages :tools :lookups :max-lookups])
                                         :label "judge" :defer-records? true
                                         :ctx (assoc (:ctx request) :role :made-review :made made))
                                  #(made-review/review-check world job verdict candidates %))]
    (-> result
        (dissoc :messages)
        (assoc :records (into (vec records) (:records reviewed)))
        (cond-> (:verdict reviewed) (assoc :verdict (:verdict reviewed))))))

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
                       :label "judge" :defer-records? true :keep-messages? true
                       :ctx (assoc (select-keys work [:minutes :stopped-early? :luck])
                                   :role :judge :world world :job job :things things
                                   :focus (item-work/focus-things world job)))
        {:keys [verdict] :as result} (ask-structured! run "judge" request #(item-judge/judgement % world things (get-in run [:scenario :inner]) job
                                                                                   (get-in run [:scenario :relations])))]
    (if (made-review/needed? job verdict)
      (review-made run world job result request)
      (dissoc result :messages))))

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
                              (select-keys request [:lookups :max-lookups])
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
