(ns manor.main
  "Command line entry point: start or load a run and serve the control panel.

    clojure -M:run                         new village run, real models
    clojure -M:run village --mock --offline
    clojure -M:run --load village-20261014-201500 --port 8081
    clojure -M:run village --model openai/gpt-5-mini --max-cost 0.5
    clojure -M:run claudeworth --tool-choice required  force a tool call on every model reply
    clojure -M:run village --mock --offline --test     store under test-runs/ (disposable)
    clojure -M:run claudeworth --mock --test --start 1904-10-15T21:00
    clojure -M:run claudeworth --no-address        no forms of address in prompts"
  (:require [manor.engine :as e]
            [manor.web :as web])
  (:gen-class))

(defn- parse-args [args]
  (loop [[a & more :as args] args, acc {:scenario :village :opts {} :port 8080}]
    (cond
      (empty? args) acc
      (= a "--mock") (recur more (assoc-in acc [:opts :llm] :mock))
      (= a "--offline") (recur more (assoc-in acc [:opts :offline?] true))
      (= a "--test") (recur more (assoc-in acc [:opts :test?] true))
      (= a "--no-address") (recur more (assoc-in acc [:opts :addressed-as?] false))
      (= a "--load") (recur (rest more) (assoc acc :load (first more)))
      (= a "--port") (recur (rest more) (assoc acc :port (parse-long (first more))))
      (= a "--model") (recur (rest more) (assoc-in acc [:opts :model] (first more)))
      (= a "--max-cost") (recur (rest more) (assoc-in acc [:opts :max-cost] (parse-double (first more))))
      (= a "--tool-choice") (recur (rest more) (assoc-in acc [:opts :tool-choice] (first more)))
      (= a "--start") (recur (rest more) (assoc-in acc [:opts :start] (first more)))
      :else (recur more (assoc acc :scenario (keyword a))))))

(defn -main [& args]
  (let [{:keys [scenario opts port load]} (parse-args args)
        run (if load (e/load-run load opts) (e/start-run scenario opts))]
    (println "Run" (:id run) "in" (str (:dir run)))
    (web/start! run port)
    @(promise)))
