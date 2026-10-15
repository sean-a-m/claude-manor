(ns manor.bridge
  "Bridges are the only way the simulation touches the real world. Agents
  never get raw tools or credentials: an action names a bridge, the engine
  validates the call, runs it here, and records the result as an event so
  replays never call out again.

  Bridge spec (in a scenario's :bridges map):
    {:doc       \"Daily weather for a location and date range\"
     :call      (fn [args ctx]) => result (plain data; may throw)
     :timeout-ms 15000
     :max-chars 4000}       ; results are truncated to this when printed

  ctx is {:run-dir ... :opts ...}. Run options can replace a bridge's :call
  through :bridge-stubs {bridge-name (fn [args ctx])}, for offline runs and
  tests."
  (:require [manor.http :as http]))

(defn- truncate-result
  "Keep results small and inert. Large results are replaced by a truncated
  string so they cannot flood an agent's context."
  [result max-chars]
  (let [s (pr-str result)]
    (if (> (count s) max-chars)
      (str (subs s 0 max-chars) "...[truncated]")
      result)))

(defn- call*
  [scenario opts ctx bridge-name args]
  (let [spec (get-in scenario [:bridges bridge-name])
        f (or (get-in opts [:bridge-stubs bridge-name]) (:call spec))]
    (if-not f
      {:error (str "unknown bridge " bridge-name)}
      (let [fut (future (f args ctx))
            res (deref fut (or (:timeout-ms spec) 15000) ::timeout)]
        (cond
          (= res ::timeout) (do (future-cancel fut) {:error "timed out"})
          :else {:result (truncate-result res (or (:max-chars spec) 4000))})))))

(defn call
  "Run bridge `bridge-name`. Returns {:result r} or {:error msg}; never throws."
  [scenario opts ctx bridge-name args]
  (try
    (call* scenario opts ctx bridge-name args)
    (catch java.util.concurrent.ExecutionException e
      {:error (ex-message (or (ex-cause e) e))})
    (catch Exception e
      {:error (ex-message e)})))

;; ---------------------------------------------------------------------------
;; Helpers for bridge implementations

(def get-json
  "GET a URL with query params and parse a JSON body with keyword keys."
  http/get-json)
