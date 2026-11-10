(ns activity-check-eval
  "An evaluation of the activity check's prompt (judges/activity-check.md)
  against labelled cases, with the real judge model. Costs money: each call
  is about $0.0001, and `evaluate` stops at `:max-cost`.

    clojure -M:dev -e \"(require 'activity-check-eval) (activity-check-eval/evaluate {:repeats 2})\"
    ;; another prompt text: {:prompt \"path/to/prompt.md\"}; some cases: {:ids #{\"s1\" \"a2\"}}

  The cases, dev/activity_check_cases.edn, are one map a line: :doing,
  :place {:id :name} and :stores [{:id :name}], exactly what
  `manor.mechanics.items.activity-check/request` sends, with the outcome
  wanted (:expect :accept or :refuse) and :ambiguous? where the rule could be
  read either way. Ids \"r<n>\" come from two real runs of
  claudeworth-small (runs/claudeworth-small-20261109-064910 and -061056,
  :src gives the run and round, :was the verdict then); \"s<n>\" and
  \"a<n>\" were written by hand. The request is built like the engine's: the
  prompt as the system message, the case as compact JSON, the
  check-activity tool from the same schema and tool prompt, and the engine's
  default call settings for a judge. One call a case and repeat, without the
  engine's corrective retries: a malformed answer counts as :invalid.
  Nothing is written to a run; results are returned and printed."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.json-schema :as mjs]
            [manor.io.llm :as llm]
            [manor.mechanics.adjudication :as adjudication]
            [manor.mechanics.items.activity-check :as activity-check]
            [manor.prompt :as prompt]
            [manor.prompts :as prompts]))

(def cases-file "dev/activity_check_cases.edn")

(def call-settings
  "The engine's defaults for a judge call (manor.engine/default-opts)."
  {:model "deepseek/deepseek-v4.1-flash" :reasoning {:effort "low"}
   :max-tokens 32000 :tool-choice "auto"})

(defn cases []
  (with-open [r (io/reader cases-file)]
    (mapv edn/read-string (remove str/blank? (line-seq r)))))

(defn request
  "The activity check's request for `case`, with `system` as its prompt."
  [system {:keys [doing place stores]}]
  (merge call-settings
         {:messages [{:role "system" :content system}
                     {:role "user" :content (prompt/->json {:doing doing :place place :stores stores})}]
          :tools [{:type "function"
                   :function {:name "check-activity"
                              :description (prompts/render "judges/tools/check-activity.md" {})
                              :parameters (mjs/transform activity-check/answer-schema)}}]}))

(defn- verdict [response]
  (let [{:keys [error] v :verdict} (adjudication/answer "check-activity" response activity-check/check-answer)]
    (if error {:outcome :invalid :reason error}
        {:outcome (if (:allowed v) :accept :refuse) :reason (:reason v)})))

(defn evaluate
  "Run every case (or those in `ids`) `repeats` times against `prompt` (a
  file, default the shared template), at most `parallel` calls at once,
  stopping new calls once `max-cost` dollars are spent. Prints each case's
  outcomes and the agreement with the labels; returns the results."
  [{:keys [prompt repeats ids parallel max-cost]
    :or {repeats 2 parallel 8 max-cost 0.25}}]
  (let [system (if prompt (str/trim-newline (slurp prompt)) (prompts/render "judges/activity-check.md" {}))
        client (llm/openrouter)
        spent (atom 0.0)
        jobs (for [c (cases) :when (or (nil? ids) (ids (:id c))) i (range repeats)] [c i])
        pool (java.util.concurrent.Executors/newFixedThreadPool parallel)
        call (fn [[c i]]
               (if (>= @spent max-cost)
                 {:id (:id c) :outcome :skipped}
                 (try (let [resp (llm/complete client (request system c))]
                        (swap! spent + (or (get-in resp [:usage :cost]) 0))
                        (merge {:id (:id c) :run i} (verdict resp)))
                      (catch Exception e {:id (:id c) :outcome :error :reason (ex-message e)}))))
        results (try (mapv deref (mapv #(.submit pool ^Callable (fn [] (call %))) jobs))
                     (finally (.shutdown pool)))
        by-id (group-by :id results)
        rows (for [c (cases) :let [rs (by-id (:id c))] :when rs]
               (let [agree (count (filter #(= (:expect c) (:outcome %)) rs))]
                 {:id (:id c) :expect (:expect c) :ambiguous? (boolean (:ambiguous? c))
                  :outcomes (mapv :outcome rs) :agree agree :of (count rs)
                  :reasons (vec (distinct (keep :reason rs))) :doing (:doing c)}))
        score (fn [rows] (str (reduce + (map :agree rows)) "/" (reduce + (map :of rows))))]
    (doseq [{:keys [id expect ambiguous? outcomes agree of reasons doing]} rows]
      (println (format "%-5s %-6s%s %d/%d %s | %s" id (name expect) (if ambiguous? "?" " ") agree of
                       (pr-str (mapv name outcomes)) (subs doing 0 (min 90 (count doing)))))
      (when (< agree of) (doseq [r reasons] (println "        " r))))
    (let [clear (remove :ambiguous? rows)]
      (println "agreement: all" (score rows)
               "| clear" (score clear)
               "| clear accept" (score (filter #(= :accept (:expect %)) clear))
               "| clear refuse" (score (filter #(= :refuse (:expect %)) clear))
               "| ambiguous" (score (filter :ambiguous? rows)))
      (println "cost:" (format "$%.4f" @spent)))
    {:rows rows :cost @spent}))
