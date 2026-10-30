(ns manor.scene-boundary-probe
  "Base-compatible, no-port mock evidence. Loaded unchanged into both revisions."
  (:require [clojure.java.io :as io]
            [clojure.walk :as walk]
            [manor.engine :as e]
            [manor.engine.models :as models]
            [manor.io.store :as store]
            [manor.prompt :as prompt]
            [manor.world :as w]))

(def cases
  [[:village 4] [:treasure 4] [:quiz 4] [:claudeworth 4]
   [:claudeworth-medium 4] [:lodge 4] [:dressing 4] [:household 13]])

(defn evidence [run]
  (let [world (e/world run) events (e/events run)]
    {:world world :events events
     :agents (into {} (for [agent (w/active-agents world) :let [id (:id agent)]]
                        [id {:view (prompt/agent-view (:scenario run) world id)
                             :events (filterv #(w/visible-to? % id) events)}]))
     ;; Only this log envelope timestamp is wall-clock data. All usage, replies,
     ;; arguments, ids, model settings and event payloads remain compared.
     :calls (mapv #(dissoc % :at) (store/read-llm (:dir run)))}))

(defn- request-data [request]
  ;; Tool schemas contain java.util.regex.Pattern values, not EDN literals.
  ;; Preserve their complete source and flags rather than dropping schema fields.
  (walk/postwalk (fn [value]
                   (if (instance? java.util.regex.Pattern value)
                     {:scene-boundary/type :regex :source (.pattern value) :flags (.flags value)}
                     value)) request))

(defn comparable
  "Keep raw evidence intact; normalize only dispatch order of contiguous work
  judges on the exact same pre-batch world. finish-jobs! runs these in futures
  but records their results in job order. Preserve each job's retry ordering."
  [result]
  (update result :requests
          (fn [requests]
            (->> requests
                 (map-indexed vector)
                 (partition-by (fn [[index request]]
                                 (if (= :judge (get-in request [:ctx :role]))
                                   [:parallel-judges (get-in request [:ctx :world])]
                                   [:ordered index])))
                 (mapcat (fn [batch]
                           (if (= :judge (get-in (second (first batch)) [:ctx :role]))
                             (mapcat val (sort-by key (group-by #(get-in % [:ctx :job :id]) (map second batch))))
                             (map second batch))))
                 vec))))

(defn- recorded-files [run]
  (let [dir (io/file (:dir run))]
    (into {} (for [file (file-seq dir) :when (.isFile file)]
               [(str (.relativize (.toPath dir) (.toPath file))) (slurp file)]))))

(defn capture
  "Capture initial + each completed round, with optional read-only rendering.
  hook-mode is :keep or :remove; removing happens before run initialization.
  Keeps each capture in its own directory, including repeated comparisons."
  [scenario rounds hook-mode render!]
  (let [requests (atom [])
        complete! models/complete!
        load-scenario e/load-scenario]
    (with-redefs [store/test-runs-root (str (io/file store/test-runs-root "scene-boundary" (str (random-uuid))))
                  e/load-scenario (fn [key] (cond-> (load-scenario key)
                                            (= :remove hook-mode) (dissoc :scene)))
                  models/complete! (fn [run request]
                                     (swap! requests conj (request-data request))
                                     (complete! run request))]
      (let [run (e/start-run scenario {:llm :mock :offline? true :test? true :seed 1
                                        :build-rounds 6 :max-cost 0.20
                                        :run-id (str "scene-boundary-" (name scenario))})]
        (try
          (let [prefixes
                (mapv (fn [round]
                        (when (pos? round)
                          (e/auto! run 1)
                          (when-not (and (= :idle (e/wait-idle run 60000))
                                         (nil? (:error (e/status run)))
                                         (= round (:round (e/world run))))
                            (throw (ex-info "Mock round did not complete" (e/status run)))))
                        (let [before (evidence run) calls @requests files (recorded-files run)]
                          (when render! (render! run))
                          (when-not (= [before calls files] [(evidence run) @requests (recorded-files run)])
                            (throw (ex-info "Rendering changed simulation or called a model"
                                            {:scenario scenario :round round})))
                          before))
                      (range (inc rounds)))]
            {:prefixes prefixes :requests @requests})
          (finally
            (e/stop-run run)
            (when-not (.awaitTermination ^java.util.concurrent.ExecutorService (:exec run)
                                        10 java.util.concurrent.TimeUnit/SECONDS)
              (throw (ex-info "Scene probe run did not stop" {:run-id (:id run)})))))))))

(defn -main [output mode]
  (try
    (let [render! (when (= mode "render")
                    (requiring-resolve 'manor.scene-integration-test/render-run!))
          result (into {} (for [[scenario rounds] cases]
                            (do (println "Capturing" scenario rounds mode)
                                [scenario (capture scenario rounds
                                                   (if (= mode "no-hooks") :remove :keep) render!)])))]
      (spit output (pr-str result)))
    (finally (shutdown-agents))))
