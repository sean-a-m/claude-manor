(ns manor.viewer.main
  "Command line entry point for the transcript viewer.

    manor-viewer                         ; serve ./old-test-runs (or ./runs)
    manor-viewer --root runs             ; serve another run root
    manor-viewer old-test-runs/phase2-real-1
    manor-viewer --port 9000 --no-narrate

  A run root may be a run directory itself or a directory of run directories;
  `old-test-runs/` is both. Nothing is ever written."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [manor.viewer.data :as data]
            [manor.viewer.web :as web]))

(def usage
  (str/join
   "\n"
   ["manor-viewer — read manor runs (transcript.md, llm.edn, events.edn) in a browser"
    ""
    "usage: manor-viewer [options] [run-dir]"
    ""
    "options:"
    "  --root DIR     directory of runs (or one run dir). default: ./old-test-runs, else ./runs"
    "  --port N       port to serve on (default 8787; 0 picks a free port)"
    "  --host HOST    address to bind (default localhost)"
    "  --no-narrate   do not use the repo's event narration (plain event types)"
    "  -h, --help     this text"
    ""
    "Pages: runs index, run overview, calls (filter by agent/round/flag/text),"
    "one call with its full prompt, events, transcript.md, search, raw EDN."]))

(defn- default-root []
  (some #(when (fs/exists? %) (str %)) ["old-test-runs" "runs" "test-runs"]))

(defn parse-args [args]
  (loop [args args opts {}]
    (let [[a & more] args]
      (cond
        (nil? a) opts
        (contains? #{"-h" "--help"} a) (assoc opts :help true)
        (= a "--root") (recur (rest more) (assoc opts :root (first more)))
        (= a "--port") (recur (rest more) (assoc opts :port (parse-long (str (first more)))))
        (= a "--host") (recur (rest more) (assoc opts :host (first more)))
        (= a "--no-narrate") (recur more (assoc opts :narrate false))
        (str/starts-with? a "--") (recur more (assoc opts :unknown a))
        :else (recur more (assoc opts :root a))))))

(defn- narration-available?
  "Whether the repo's narration namespaces are on the classpath at all."
  []
  (try (some? (requiring-resolve 'manor.world/replay)) (catch Throwable _ false)))

(defn -main [& args]
  (let [{:keys [help unknown] :as opts} (parse-args args)
        root (or (:root opts) (default-root))]
    (cond
      help (println usage)
      unknown (do (println (str "unknown option: " unknown)) (println usage) (System/exit 2))
      (nil? root) (do (println "no run directory found; pass one with --root") (System/exit 2))
      (not (fs/directory? root)) (do (println (str "not a directory: " root)) (System/exit 2))
      :else
      (let [narrate? (not (false? (:narrate opts)))]
        (data/set-narrate! narrate?)
        (let [{:keys [url]} (web/start! {:root root
                                         :port (or (:port opts) 8787)
                                         :host (:host opts)})]
          (println (str "manor viewer: " url))
          (println (str "serving run root: " (fs/canonicalize (fs/file root))))
          (when (and narrate? (not (narration-available?)))
            (println "(repo narration unavailable: event pages show event types only)"))
          (println "Ctrl-C to stop.")
          @(promise))))))
