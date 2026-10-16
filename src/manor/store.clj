(ns manor.store
  "Run persistence. A run is a directory (runs/ or test-runs/, see below):

    runs/<id>/config.edn   scenario, seed, options
    runs/<id>/events.edn   one stamped event per line (append-only; the truth)
    runs/<id>/llm.edn      one model call per line (prompt, response, usage)
    runs/<id>/outbox/      files written by act bridges, if any
    runs/<id>/mcp-<name>.log  stderr of the run's MCP servers, if any
    runs/<id>/pi/          pi MCP server sessions and workspaces, if any

  The world is rebuilt by replaying events.edn, so the model is never called
  during a replay.

  Real runs live under runs/. Runs started with the option :test? true
  (tests, ad-hoc checks, experiments) live under test-runs/, which is always
  safe to delete wholesale: `rm -rf test-runs/` or (delete-test-runs!)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.time LocalDateTime)
           (java.time.format DateTimeFormatter)))

(def runs-root "runs")

(def test-runs-root
  "Where :test? runs go. Everything in here is disposable."
  "test-runs")

(defn root [test?] (if test? test-runs-root runs-root))

(defn new-run-id [scenario-name]
  (str (name scenario-name) "-"
       (.format (LocalDateTime/now) (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss"))))

(defn run-dir
  ([id] (run-dir id false))
  ([id test?] (io/file (root test?) id)))

(defn- write-line! [file x]
  (io/make-parents file)
  (binding [*print-length* nil *print-level* nil *print-namespace-maps* false]
    (spit file (str (pr-str x) "\n") :append true)))

(defn- read-lines [file]
  (if (.exists (io/file file))
    (with-open [r (io/reader file)]
      (into [] (comp (remove str/blank?) (map edn/read-string)) (line-seq r)))
    []))

(defn write-config! [dir config]
  (io/make-parents (io/file dir "config.edn"))
  (binding [*print-namespace-maps* false]
    (spit (io/file dir "config.edn") (pr-str config))))

(defn read-config [dir] (edn/read-string (slurp (io/file dir "config.edn"))))

(defn append-event! [dir event] (write-line! (io/file dir "events.edn") event))

(defn read-events [dir] (read-lines (io/file dir "events.edn")))

(defn append-llm! [dir entry] (write-line! (io/file dir "llm.edn") entry))

(defn read-llm [dir] (read-lines (io/file dir "llm.edn")))

(defn delete-test-runs!
  "Delete everything under test-runs/."
  []
  (letfn [(delete [f]
            (when (.isDirectory f) (run! delete (.listFiles f)))
            (.delete f))]
    (delete (io/file test-runs-root))))

(defn list-runs
  ([] (list-runs false))
  ([test?]
   (->> (.listFiles (io/file (root test?)))
        (filter #(.exists (io/file % "config.edn")))
        (map #(.getName %))
        sort)))
