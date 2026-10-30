(ns manor.test-support
  "Engine-test lifecycle. Each JVM tests serially; parallelism belongs between
  JVMs because test doubles use with-redefs. Artifacts are kept for inspection."
  (:require [clojure.java.io :as io]
            [manor.engine :as e]
            [manor.engine.judges :as judges]
            [manor.io.store :as store])
  (:import (java.util.concurrent ExecutorService TimeUnit)))

(defn- stop-and-wait! [stop run]
  (let [^ExecutorService exec (:exec run)]
    (when-not (.isShutdown exec)
      (stop run))
    (when-not (.awaitTermination exec 10 TimeUnit/SECONDS)
      (throw (ex-info "Test run did not stop within 10 seconds"
                      {:run-id (:id run) :dir (str (:dir run))})))))

(defn with-structured-attempts
  "A fixture with an explicit checked-answer attempt budget, independent of
  the production default. JVM-local redefinition also reaches engine threads."
  [n f]
  (with-redefs [judges/max-attempts n]
    (f)))

(defn with-runs
  "A :once fixture: isolate directly invoked tests too, wait for stop-run's
  final writes, and close any runs a test forgot to stop. Never delete files."
  [f]
  (let [root (or (System/getProperty "manor.test-runs-root")
                 (str (io/file store/test-runs-root "tests" (str (random-uuid)))))
        runs (atom [])
        track (fn [make-run]
                (fn [& args]
                  (let [run (apply make-run args)]
                    (swap! runs conj run)
                    run)))
        stop e/stop-run]
    (with-redefs [store/test-runs-root root
                  e/start-run (track e/start-run)
                  e/load-run (track e/load-run)
                  e/stop-run (partial stop-and-wait! stop)]
      (try
        (f)
        (finally
          (doseq [run @runs]
            (stop-and-wait! stop run)))))))
