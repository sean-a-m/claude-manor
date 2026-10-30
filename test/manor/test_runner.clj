(ns manor.test-runner
  "Serial tests inside isolated JVM workers. Keep artifacts and logs under a
  fresh test-runs/tests/<invocation>/ directory; never clean shared run data."
  (:refer-clojure :exclude [test])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.cli :as cli]
            [clojure.tools.namespace.find :as find]
            [cognitect.test-runner :as runner])
  (:import (java.io File)
           (java.lang ProcessBuilder ProcessHandle)))

(def default-workers (min 4 (.availableProcessors (Runtime/getRuntime))))

(defn- selected-namespaces
  "Discover without loading test code in the supervisor JVM."
  [{:keys [dir namespace namespace-regex var]}]
  (let [patterns (if (or (seq namespace) (seq namespace-regex))
                   namespace-regex [#".*-test$"])
        var-nses (when (seq var)
                   (set (map #(if-let [n (clojure.core/namespace %)]
                                (symbol n)
                                (throw (ex-info "Test vars must be fully qualified" {:var %}))) var)))
        nses (->> (or dir #{"test"})
                  (map io/file)
                  (mapcat find/find-namespaces-in-dir)
                  distinct
                  (filter #(or (contains? namespace %)
                               (some (fn [p] (re-matches p (str %))) patterns)))
                  sort vec)]
    ;; Each child receives only its own vars. Refuse missing/excluded namespaces
    ;; here so sharding cannot silently discard a mistyped -v alongside valid ones.
    (when-let [missing (seq (remove (set nses) var-nses))]
      (throw (ex-info "Test var namespaces did not match the selection" {:namespaces missing})))
    (filterv #(or (nil? var-nses) (contains? var-nses %)) nses)))

(defn- shards [nses workers]
  (reduce-kv (fn [groups i n] (update groups (mod i workers) conj n))
             (vec (repeat workers [])) nses))

(defn- write-edn! [file data]
  (io/make-parents file)
  (spit file (binding [*print-length* nil *print-level* nil] (pr-str data))))

(defn- worker!
  "The property must be set before any test loads manor.io.store."
  [manifest]
  (let [{:keys [root options result]} (edn/read-string (slurp manifest))
        started (System/nanoTime)]
    (System/setProperty "manor.test-runs-root" root)
    (println "Test artifacts:" root)
    (let [summary (runner/test options)]
      (write-edn! result (assoc summary :seconds (/ (- (System/nanoTime) started) 1e9)))
      (if (zero? (+ (:fail summary) (:error summary))) 0 1))))

(defn- worker-command [manifest]
  [(str (io/file (System/getProperty "java.home") "bin" "java"))
   ;; Respect the test alias's heap limit (or the operator's -J-Xmx override).
   (str "-Xmx" (.maxMemory (Runtime/getRuntime)))
   "-cp" (System/getProperty "java.class.path")
   "clojure.main" "-m" "manor.test-runner" "--worker" (str manifest)])

(defn- launch! [root options i nses]
  (let [dir (io/file root (str "worker-" (inc i)))
        manifest (io/file dir "worker.edn")
        result (io/file dir "summary.edn")
        log (io/file dir "output.log")
        vars (set (filter #(contains? (set nses) (symbol (namespace %))) (:var options)))
        options (cond-> (assoc (dissoc options :namespace-regex) :namespace (set nses))
                  (seq (:var options)) (assoc :var vars))]
    (write-edn! manifest {:root (str (io/file dir "runs")) :options options :result (str result)})
    (println (str "Worker " (inc i) ": " (count nses) " namespaces; " log))
    {:process (.start (doto (ProcessBuilder. ^java.util.List (worker-command manifest))
                       (.redirectErrorStream true)
                       (.redirectOutput ^File log)))
     :result result :log log :index (inc i)}))

(defn- stop-workers! [workers]
  (doseq [{:keys [^Process process]} workers]
    (when (.isAlive process)
      ;; Include MCP grandchildren when a test run is interrupted.
      (with-open [descendants (.descendants (.toHandle process))]
        (doseq [^ProcessHandle child (iterator-seq (.iterator descendants))]
          (.destroyForcibly child)))
      (.destroyForcibly process))))

(defn- collect! [{:keys [^Process process result log index]}]
  (let [exit (.waitFor process)
        summary (when (.exists ^File result) (edn/read-string (slurp result)))]
    (println (str "\n--- Worker " index " (exit " exit ") ---"))
    (print (slurp log))
    (when summary (printf "Worker %d: %.2f seconds\n" index (double (:seconds summary))))
    (flush)
    (if (and summary (or (zero? exit) (pos? (+ (:fail summary) (:error summary)))))
      summary
      (do (println "Worker did not complete normally; see" (str log))
          {:test 0 :pass 0 :fail 0 :error 1}))))

(defn- run-suite! [options workers]
  (when-not (and (integer? workers) (pos? workers))
    (throw (ex-info "Workers must be a positive integer" {:workers workers})))
  (let [nses (selected-namespaces options)
        workers (min workers (count nses))
        root (io/file (System/getProperty "manor.test-runs-root" "test-runs")
                      "tests" (str (random-uuid)))
        active (atom [])
        hook (Thread. ^Runnable #(stop-workers! @active))
        runtime (Runtime/getRuntime)
        started (System/nanoTime)]
    (when (empty? nses)
      (throw (ex-info "No test namespaces matched" {:options options})))
    (println "Running tests in" workers "isolated JVM(s). Artifacts retained at" (str root))
    (.addShutdownHook runtime hook)
    (try
      (doseq [[i nses] (map-indexed vector (shards nses workers))]
        (swap! active conj (launch! root options i nses)))
      (let [results (mapv collect! @active)
            total (reduce #(merge-with + %1 (select-keys %2 [:test :pass :fail :error]))
                          {:test 0 :pass 0 :fail 0 :error 0} results)
            total (if (and (zero? (:test total)) (zero? (+ (:fail total) (:error total))))
                    (do (println "No tests matched the filters.") (update total :error inc)) total)]
        (printf "\nTotal: %d tests, %d assertions; %d failures, %d errors. %.2f seconds.\n"
                (:test total) (+ (:pass total) (:fail total) (:error total))
                (:fail total) (:error total) (/ (- (System/nanoTime) started) 1e9))
        (flush)
        total)
      (finally
        (stop-workers! @active)
        (.removeShutdownHook runtime hook)))))

(defn test
  "clojure -X:test entry point. Cognitect's :dirs/:nses/:patterns/:vars/
  :includes/:excludes options, plus :workers (default up to four)."
  [{:keys [dirs nses patterns vars includes excludes workers]
    :or {workers default-workers}}]
  (let [options {:dir (when (seq dirs) (set dirs))
                 :namespace (when (seq nses) (set nses))
                 :namespace-regex (mapv re-pattern patterns)
                 :var (when (seq vars) (set vars))
                 :include (when (seq includes) (set includes))
                 :exclude (when (seq excludes) (set excludes))}
        result (run-suite! options workers)]
    (when (pos? (+ (:fail result) (:error result)))
      (throw (ex-info "Test failures or errors occurred" result)))
    result))

(defn -main [& args]
  (if (= "--worker" (first args))
    (try
      (System/exit (worker! (second args)))
      (catch Throwable t
        (.printStackTrace t)
        (System/exit 1)))
    (let [specs (conj runner/cli-options
                      ["-w" "--workers N" "Isolated JVM workers (default up to four)"
                       :default default-workers :parse-fn parse-long
                       :validate [#(and (integer? %) (pos? %)) "must be a positive integer"]])
          {:keys [options errors arguments summary]} (cli/parse-opts args specs)]
      (cond
        (:test-help options)
        (println "clojure -M:test [options]\n" summary)

        (or (seq errors) (seq arguments))
        (do (doseq [error errors] (println error))
            (when (seq arguments) (println "Unexpected arguments:" arguments))
            (System/exit 1))

        :else
        (try
          (let [result (run-suite! (dissoc options :workers :test-help) (:workers options))]
            (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1)))
          (catch Throwable t
            (.printStackTrace t)
            (System/exit 1)))))))
