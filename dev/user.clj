(ns user
  "REPL controls. Start with `clj -M:dev` (or jack in with the :dev alias).

    (start!)                         ; new village run, real models
    (start! :village {:llm :mock :offline? true})   ; free, no network
    (serve!)                         ; control panel on http://localhost:8080
    (step!)  (step! 5)  (auto!)  (pause!)
    (feed)  (feed 40)  (feed 40 \"ana\")   ; recent events, optionally as one agent sees them
    (view \"ana\")  (last-prompt \"ana\")  (world)
    (spawn! {:role :farmer :name \"Eva\" :location \"market\"})  (retire! \"eva\" \"moved away\")
    (place! \"eva\" \"square\")             ; move someone directly
    (approve! \"req-63\")  (deny! \"req-63\")
    (act! \"eva\" :plant {:grain 3})       ; for a seat with :controller :human
    (load! \"village-20261014-...\")  (branch! 3)
    (export-map! \"resources/worlds/grown.edn\")  ; the live map as a world file
    (transcript!)  (transcript! \"claudeworth-...\")  (transcript! id {:test? true})  ; <run-dir>/transcript.md
    (start! :village {:llm :mock :offline? true :test? true})  ; under test-runs/
    (clean-test-runs!)                ; delete everything under test-runs/"
  (:require [clojure.pprint :refer [pprint]]
            [manor.engine :as e]
            [manor.narrate :as n]
            [manor.prompt :as prompt]
            [manor.store :as store]
            [manor.transcript :as transcript]
            [manor.web :as web]
            [manor.world :as w]
            [manor.worldfile :as worldfile]))

(defonce ^:private current (atom nil))

(defn run [] (or @current (throw (ex-info "no run: call (start!) or (load! id)" {}))))

(defn- use-run! [r]
  (when-let [old @current] (when-not (identical? old r) (e/stop-run old)))
  (reset! current r)
  (web/show! r)
  (:id r))

(defn start!
  ([] (start! :village {}))
  ([scenario] (start! scenario {}))
  ([scenario opts] (use-run! (e/start-run scenario opts))))

(defn load!
  ([id] (load! id {}))
  ([id opts] (use-run! (e/load-run id opts))))

(defn branch!
  "Continue from the end of `round` in a new run (the original is untouched)."
  [round]
  (use-run! (e/branch-run (run) round)))

(defn runs
  ([] (store/list-runs))
  ([test?] (store/list-runs test?)))

(defn clean-test-runs!
  "Delete everything under test-runs/ (stopping the current run if it lives there)."
  []
  (when-let [r @current]
    (when (:test? (:opts r)) (e/stop-run r) (reset! current nil)))
  (store/delete-test-runs!))

(defn serve!
  ([] (serve! 8080))
  ([port] (web/start! (run) port)))

(defn feed
  "Print recent events. `as` restricts to what one agent can see."
  ([] (feed 40))
  ([k] (feed k :operator))
  ([k as]
   (let [world (e/world (run))
         names (n/names world)]
     (doseq [ev (->> (e/events (run))
                     (filter #(and (w/visible-to? % as) (not= :llm/usage (:type %))))
                     (take-last k))]
       (println (format "#%-4d r%-3d %-14s %s" (:id ev) (:round ev)
                        (let [v (:visible-to ev)] (if (set? v) (pr-str v) (name v)))
                        (n/describe ev names)))))))

(defn- wait-and-report []
  (let [r (e/wait-idle (run))]
    (when (= r :awaiting-human)
      (println "Waiting for human input from" (:agent @(:input (run)))
               "- use (act! id action args)"))
    (when-let [p (:pause-reasons (e/status (run)))] (println "Paused:" p))
    r))

(defn step!
  "Run one round (or `n` rounds) and wait for it to finish."
  ([] (e/step! (run)) (wait-and-report))
  ([n] (e/auto! (run) n) (wait-and-report)))

(defn auto!
  "Run continuously in the background until paused or a pause condition fires."
  ([] (e/auto! (run)))
  ([n] (e/auto! (run) n)))

(defn pause! [] (e/pause! (run)))

(defn world [] (e/world (run)))

(defn view
  "Everything an agent is shown about the world."
  [id]
  (pprint (prompt/agent-view (:scenario (run)) (world) id)))

(defn last-prompt [id]
  (let [c (get @(:last-calls (run)) id)]
    (doseq [m (:messages c)]
      (println (str "--- " (:role m)))
      (println (or (:content m) (pr-str (:tool_calls m)))))
    (println "--- response" (:finish-reason c))
    (pprint (:response c))))

(defn spawn! [spec] @(e/spawn! (run) spec))
(defn retire! [id reason] @(e/retire! (run) id reason))
(defn update-agent! [id changes] @(e/update-agent! (run) id changes))
(defn announce! [text] @(e/announce! (run) text))
(defn place! [id loc-id] @(e/place! (run) id loc-id))
(defn approve! [req-id] @(e/approve! (run) req-id))
(defn deny! [req-id] @(e/deny! (run) req-id))
(defn act! [id action args] (e/act! (run) id action args))

(defn export-map!
  "Write the current run's live map as a world file to `path`, e.g. to keep
  a grown house as the starting point for new runs. Only for runs started
  from a world file (their map is also kept in <run-dir>/map.edn)."
  [path]
  (if-let [start (store/read-world-start (:dir (run)))]
    (do (spit path (worldfile/->edn-string (worldfile/export (world) start)))
        path)
    (println "This run did not start from a world file.")))

(defn transcript!
  "Write <run-dir>/transcript.md for the current run, or for the saved run
  `id` (pass {:test? true} for one under test-runs/), and return its path."
  ([] (transcript/write! (:dir (run))))
  ([id] (transcript! id {}))
  ([id {:keys [test?]}] (transcript/write! (store/run-dir id test?))))
