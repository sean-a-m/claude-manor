(ns manor.mcp
  "A minimal MCP client: the engine calls tools on MCP servers so that
  scenario bridges can reach them. Only the stdio transport is supported
  (newline-delimited JSON-RPC 2.0 on the server's stdin/stdout).

  Server config (a scenario's :mcp-servers, config/mcp.edn, or the run
  option :mcp-servers, merged in that order):
    {:pi {:command [\"node\" \"mcp-servers/pi/server.mjs\"]  ; program and args
          :env {\"PI_MCP_MODEL\" \"...\"}                  ; added to the inherited environment
          :cwd \".\"}}                                    ; default: the JVM's working directory

  Every server also gets MANOR_RUN_ID and MANOR_RUN_DIR in its environment.
  Servers start lazily on first use, one process per run, and are closed
  with the run. Their stderr goes to <run-dir>/mcp-<name>.log."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jsonista.core :as json])
  (:import (java.io BufferedReader Writer)
           (java.lang ProcessBuilder$Redirect)
           (java.util.concurrent TimeUnit)))

(def protocol-version "2025-06-18")

(def ^:private mapper (json/object-mapper {:decode-key-fn true}))

;; ---------------------------------------------------------------------------
;; One connection

(defn- send! [{:keys [^Writer in]} msg]
  (locking in
    (.write in (str (json/write-value-as-string (assoc msg :jsonrpc "2.0")) "\n"))
    (.flush in)))

(defn- fail-pending! [{:keys [pending]} reason]
  (doseq [[_ p] @pending] (deliver p {:error {:message reason}}))
  (reset! pending {}))

(defn- dispatch!
  "Handle one message from the server: a response to one of our requests,
  a request from the server (answer ping; refuse the rest), or a
  notification (ignored)."
  [{:keys [pending] :as conn} {:keys [id method] :as msg}]
  (cond
    (and id (not method)) (when-let [p (get @pending id)]
                            (swap! pending dissoc id)
                            (deliver p msg))
    (and id (= method "ping")) (send! conn {:id id :result {}})
    id (send! conn {:id id :error {:code -32601 :message (str "method not supported: " method)}})))

(defn- read-loop [{:keys [^BufferedReader out name] :as conn}]
  (try
    (loop []
      (when-let [line (.readLine out)]
        (when-not (str/blank? line)
          (try (dispatch! conn (json/read-value line mapper))
               (catch Exception _)))         ; ignore lines that aren't JSON-RPC
        (recur)))
    (catch java.io.IOException _))
  (fail-pending! conn (str "MCP server " name " exited")))

(defn- request
  "Send a JSON-RPC request and wait for its result. Throws on error or
  timeout (and tells the server to cancel)."
  [{:keys [pending next-id name] :as conn} method params timeout-ms]
  (let [id (swap! next-id inc)
        p (promise)]
    (swap! pending assoc id p)
    (send! conn {:id id :method method :params params})
    (let [res (deref p timeout-ms ::timeout)]
      (cond
        (= res ::timeout)
        (do (swap! pending dissoc id)
            (try (send! conn {:method "notifications/cancelled"
                              :params {:requestId id :reason "timed out"}})
                 (catch Exception _))
            (throw (ex-info (str "MCP " name " " method " timed out after " timeout-ms " ms") {})))

        (:error res) (throw (ex-info (str "MCP " name ": " (get-in res [:error :message])) {:error (:error res)}))
        :else (:result res)))))

(defn connect
  "Start an MCP server process and complete the initialize handshake."
  [server-name {:keys [command env cwd]} {:keys [run-id run-dir]}]
  (let [pb (doto (ProcessBuilder. ^java.util.List (mapv str command))
             (.redirectError (ProcessBuilder$Redirect/appendTo
                              (doto (io/file run-dir (str "mcp-" (name server-name) ".log")) io/make-parents))))
        _ (when cwd (.directory pb (io/file cwd)))
        _ (doto (.environment pb)
            (.putAll (update-vals (update-keys env str) str))
            (.put "MANOR_RUN_ID" (str run-id))
            (.put "MANOR_RUN_DIR" (.getAbsolutePath (io/file run-dir))))
        proc (.start pb)
        conn {:name (name server-name)
              :process proc
              :in (io/writer (.getOutputStream proc) :encoding "UTF-8")
              :out (io/reader (.getInputStream proc) :encoding "UTF-8")
              :pending (atom {})
              :next-id (atom 0)}]
    (doto (Thread. ^Runnable #(read-loop conn) (str "mcp-" (name server-name)))
      (.setDaemon true)
      (.start))
    (try
      (request conn "initialize" {:protocolVersion protocol-version
                                  :capabilities {}
                                  :clientInfo {:name "manor" :version "0.1.0"}}
               30000)
      (send! conn {:method "notifications/initialized"})
      conn
      (catch Exception e
        (.destroyForcibly proc)
        (throw e)))))

(defn alive? [conn] (.isAlive ^Process (:process conn)))

(defn close!
  "Ask the server to exit by closing its stdin; kill it if it doesn't."
  [{:keys [^Process process ^Writer in]}]
  (try (.close in) (catch Exception _))
  (when-not (.waitFor process 5 TimeUnit/SECONDS)
    (.destroyForcibly process)))

(defn list-tools [conn]
  (:tools (request conn "tools/list" {} 30000)))

(defn- content-text [content]
  (str/join "\n" (keep #(when (= "text" (:type %)) (:text %)) content)))

(defn call-tool
  "Call `tool` with `args`. Returns {:value v :cost c}: v is the tool's
  structured content if it has any, otherwise its text content; c is the
  cost the server reported in _meta \"manor/cost\" (or nil). A tool error
  throws with the tool's message."
  [conn tool args timeout-ms]
  (let [{:keys [content structuredContent isError _meta]}
        (request conn "tools/call" {:name tool :arguments args} timeout-ms)]
    (when isError
      (throw (ex-info (let [t (content-text content)] (if (str/blank? t) "tool error" t)) {})))
    {:value (or structuredContent (content-text content))
     :cost (get _meta (keyword "manor/cost"))}))

;; ---------------------------------------------------------------------------
;; A run's servers

(defn- merge-server [a b]
  (merge-with (fn [x y] (if (and (map? x) (map? y)) (merge x y) y)) a b))

(defn server-configs
  "Merge server configs; later maps override earlier ones per server, and
  :env maps are merged."
  [& config-maps]
  (apply merge-with merge-server (remove nil? config-maps)))

(defn local-config
  "Machine-specific server settings from config/mcp.edn, if present."
  []
  (let [f (io/file "config" "mcp.edn")]
    (when (.exists f) (edn/read-string (slurp f)))))

(defn pool
  "The MCP servers a run may use. Nothing starts until the first call."
  [configs ctx]
  {:configs configs :ctx ctx :conns (atom {})})

(defn- conn-for [{:keys [configs ctx conns] :as pool} server]
  (locking pool
    (let [c (get @conns server)]
      (if (and c (alive? c))
        c
        (let [config (or (get configs server)
                         (throw (ex-info (str "no MCP server configured named " (name server)) {})))
              c (connect server config ctx)]
          (swap! conns assoc server c)
          c)))))

(defn call
  "Call `tool` on the run's `server`, starting the server if needed."
  [pool server tool args timeout-ms]
  (call-tool (conn-for pool server) tool args timeout-ms))

(defn close-all! [{:keys [conns] :as pool}]
  (locking pool
    (run! close! (vals @conns))
    (reset! conns {})))
