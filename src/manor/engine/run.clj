(ns manor.engine.run
  "The run's plumbing: reading its world, events and status, listeners,
  and `emit!`, the only way the world changes. A run is the map built by
  manor.engine/start-run.

  The manor.engine.* namespaces are the engine's parts; code outside the
  engine uses manor.engine."
  (:require [manor.actions :as a]
            [manor.store :as store]
            [manor.transcript :as transcript]
            [manor.world :as w]
            [manor.worldfile :as worldfile]))

(defn world [run] @(:world run))
(defn events [run] @(:events run))
(defn status [run] @(:status run))

(defn- notify! [run msg]
  (doseq [f (vals @(:listeners run))]
    (try (f msg) (catch Throwable _))))

(defn listen! [run k f] (swap! (:listeners run) assoc k f))
(defn unlisten! [run k] (swap! (:listeners run) dissoc k))

(defn set-status! [run f & args]
  (apply swap! (:status run) f args)
  (notify! run {:kind :status :status @(:status run)}))

(defn- export-map!
  "Runs started from a world file keep their live map (and items) in
  <run-dir>/map.edn, in the same format. Output only: nothing reads it
  during the run."
  [run]
  (when-let [start (store/read-world-start (:dir run))]
    (store/write-text! (:dir run) "map.edn" (worldfile/->edn-string (worldfile/export @(:world run) start)))))

(defn emit!
  "Stamp, apply, persist and broadcast events. The only way the world changes."
  [run events]
  (locking (:world run)
    (doseq [e events]
      (let [e (w/stamp @(:world run) e)]
        (swap! (:world run) w/apply-stamped e)
        (swap! (:events run) conj e)
        (store/append-event! (:dir run) e)
        (notify! run {:kind :event :event e})))
    (when (some #(#{"space" "item" "items"} (namespace (:type %))) events)
      (export-map! run))))

(defn write-transcript!
  "Write <run-dir>/transcript.md if events were added since it was last
  written, or since the run was started or loaded. Call it on the executor,
  between rounds. Never throws: a failure is printed and the run goes on."
  [run]
  (let [n (count @(:events run))]
    (when-not (= n @(:transcript-at run))
      (try (transcript/write! (:dir run))
           (reset! (:transcript-at run) n)
           (catch Throwable t
             (binding [*out* *err*]
               (println (str "transcript of " (:id run) " not written: " (ex-message t)))))))))

(defn bridge-ctx [run] {:run-id (:id run) :run-dir (str (:dir run)) :opts (:opts run) :mcp (:mcp run)})

(defn record-bridge-cost!
  "Bridges that spend money (e.g. an MCP server running its own model)
  report a cost; count it towards the run's usage, under the caller."
  [run who bridge cost]
  (when (and cost (pos? cost))
    (emit! run [{:type :llm/usage :actor who :visible-to :system
                 :data {:agent who :model (str "bridge " (subs (str bridge) 1)) :cost cost}}])))

(defn action-named [run n] (get (a/by-name (:actions run)) (name n)))

(defn cost-reached? [run]
  (let [max-cost (:max-cost (:opts run))]
    (boolean (and max-cost (>= (w/total-cost @(:world run)) max-cost)))))
