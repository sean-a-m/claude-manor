(ns manor.io.bridge
  "Bridges are the only way the simulation touches the real world. Agents
  never get raw tools or credentials: an action names a bridge, the engine
  validates the call, runs it here, and records the result as an event so
  replays never call out again.

  Bridge spec (in a scenario's :bridges map), either a Clojure function:
    {:doc       \"Daily weather for a location and date range\"
     :call      (fn [args ctx]) => result (plain data; may throw)
     :timeout-ms 15000
     :max-chars 4000}       ; results are truncated to this when printed

  or a tool on an MCP server (see manor.io.mcp); the bridge args are the tool's
  arguments and the result is the tool's text (or structured) content:
    {:doc \"Message your pi assistant\"
     :mcp {:server :pi :tool \"send_message\"}
     :timeout-ms 300000}

  ctx is {:run-id ... :run-dir ... :opts ... :mcp <the run's server pool>}.
  Run options can replace any bridge through :bridge-stubs
  {bridge-name (fn [args ctx])}, for offline runs and tests."
  (:require [manor.io.http :as http]
            [manor.io.mcp :as mcp]))

(defn- truncate-result
  "Keep results small and inert. Large results are replaced by a truncated
  string so they cannot flood an agent's context."
  [result max-chars]
  (let [s (if (string? result) result (pr-str result))]
    (if (> (count s) max-chars)
      (str (subs s 0 max-chars) "...[truncated]")
      result)))

(defn- mcp-call
  "A bridge function for an MCP tool. Returns {::value v ::cost c}."
  [{:keys [server tool]} timeout-ms]
  (fn [args ctx]
    (let [{:keys [value cost]} (mcp/call (:mcp ctx) server tool args timeout-ms)]
      {::value value ::cost cost})))

(defn- call*
  [scenario opts ctx bridge-name args]
  (let [spec (get-in scenario [:bridges bridge-name])
        timeout-ms (or (:timeout-ms spec) 15000)
        f (or (get-in opts [:bridge-stubs bridge-name])
              (:call spec)
              (when (:mcp spec) (mcp-call (:mcp spec) timeout-ms)))]
    (if-not f
      {:error (str "unknown bridge " bridge-name)}
      (let [fut (future (f args ctx))
            ;; MCP calls time out themselves (and cancel on the server); allow them a little longer
            res (deref fut (cond-> timeout-ms (:mcp spec) (+ 5000)) ::timeout)
            [res cost] (if (and (map? res) (contains? res ::value)) [(::value res) (::cost res)] [res nil])]
        (cond
          (= res ::timeout) (do (future-cancel fut) {:error "timed out"})
          :else (cond-> {:result (truncate-result res (or (:max-chars spec) 4000))}
                  cost (assoc :cost cost)))))))

(defn call
  "Run bridge `bridge-name`. Returns {:result r} (plus :cost if the bridge
  reported one) or {:error msg}; never throws."
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
