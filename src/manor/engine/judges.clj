(ns manor.engine.judges
  "The narrow model calls that answer one checked question: the work judge,
  the nightly steward and scenario adjudicators. Each gets a checked
  answer, one correction attempt and a safe failure; the domain code
  (manor.mechanics.items, the scenario) owns the prompts and the checks."
  (:require [manor.engine.models :as models]
            [manor.engine.run :as run]
            [manor.mechanics.items.judge :as item-judge]
            [manor.mechanics.items.steward :as item-steward]
            [manor.mechanics.items.work :as item-work]
            [manor.prompt :as prompt]))

(defn ask-structured!
  "Shared narrow model-call lifecycle: checked answer, one correction attempt,
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
                    response (models/complete! run (assoc request :ctx (assoc ctx :step attempt)))
                    _ (record! models/record-usage! run identity request response)
                    {:keys [verdict error replies]}
                    (try (check response)
                         (catch Throwable t
                           (record! models/record-call! run identity request response [])
                           (throw t)))]
                (record! models/record-call! run identity request response replies)
                (cond verdict {:verdict verdict}
                      (zero? attempt) (recur (into (models/conj-reply messages (:message response)) replies) 1)
                      :else {:failure (str "a second bad answer: " error)})))
            (catch Throwable t
              {:failure (str "the " label " call failed: " (ex-message t))}))]
      (cond-> result defer-records? (assoc :records @records)))))

(defn ask-judge
  "Ask the judge what `job` did to the judged `things` (see
  items/judged-things): {:summary :changes}, or {:failure reason}. A bad
  answer is sent back with the reason and asked once more. Each call is
  recorded in llm.edn as agent \"judge\", and its usage counts towards the
  run's cost."
  [run world job work things]
  (let [work (assoc work :recent (prompt/scene-recent world (run/events run) job))
        request (assoc (item-judge/judge-request (:scenario run) world job work)
                       :label "judge" :defer-records? true
                       :ctx (assoc (select-keys work [:minutes :stopped-early? :luck])
                                   :role :judge :world world :job job :things things
                                   :focus (item-work/focus-things world job)))
        {:keys [verdict] :as result} (ask-structured! run "judge" request #(item-judge/judgement % world things))]
    (assoc result :verdict verdict)))

(defn adjudicate
  "Ask the scenario's adjudicator for `task` about `args`, on `world`:
  {:verdict v}, or {:failure reason}. A request that already holds a
  :result needs no call. `call-opts` add to the call, e.g. :model and
  :before-call (see `ask-structured!`)."
  [run task world actor args call-opts]
  (let [spec (get-in run [:scenario :adjudicators task])
        {:keys [messages tools ctx result check] :as request} ((:request spec) world actor args)]
    (if (contains? request :result)
      {:verdict result}
      (ask-structured! run (str "adjudicator/" (subs (str task) 1))
                       (merge {:messages messages :tools tools
                               :ctx (assoc ctx :role :adjudicator :task task)}
                              call-opts)
                       (or check (:check spec))))))

(defn ask-steward
  "Ask the nightly steward what to do with the `candidates` (idle
  leftovers) and `sleepers` at `place`: {:verdict v}, or {:failure reason}."
  [run world place candidates sleepers]
  (let [request (assoc (item-steward/steward-request world place candidates sleepers)
                       :label "steward" :ctx {:role :steward :world world :place place
                                              :candidates candidates :sleepers sleepers})]
    (ask-structured! run "steward" request #(item-steward/stewardship % world place candidates sleepers))))
