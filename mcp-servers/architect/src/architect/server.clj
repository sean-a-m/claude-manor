(ns architect.server
  "Newline-delimited MCP JSON-RPC over stdio. No model calls and no mutable world."
  (:require [architect.drafting :as drafting]
            [architect.references :as references]
            [cheshire.core :as json]))

(def tools
  [{:name "consult_references" :description "Search architectural reference books."
    :inputSchema {:type "object" :properties {:query {:type "string"}} :required ["query"]}}
   {:name "price_list" :description "Quote room sizes, prices and build days."
    :inputSchema {:type "object" :properties {}}}
   {:name "draft_plans" :description "Draft a room, avoiding taken ids, and quote its price."
    :inputSchema {:type "object" :properties {:house {:type "array" :items {:type "object"}}
                                             :taken {:type "array" :items {:type "string"}}
                                             :request {:type "object"}}
                  :required ["house" "taken" "request"]}}])

(defn tool-result [library {:keys [name arguments]}]
  (try
    (let [result (case name
                   "consult_references" (references/search library (:query arguments))
                   "price_list" drafting/prices
                   "draft_plans" (drafting/draft arguments)
                   (throw (ex-info "unknown tool; use tools/list" {})))]
      {:structuredContent result :content [{:type "text" :text (json/generate-string result)}]})
    (catch Exception e
      {:isError true :content [{:type "text" :text (ex-message e)}]})))

(defn dispatch [library {:keys [id method params]}]
  (when (some? id)
    (merge {:jsonrpc "2.0" :id id}
           (case method
             "initialize" {:result {:protocolVersion "2024-11-05" :capabilities {:tools {}}
                                     :serverInfo {:name "architect" :version "1.0"}}}
             "ping" {:result {}}
             "tools/list" {:result {:tools tools}}
             "tools/call" {:result (tool-result library params)}
             {:error {:code -32601 :message "method not found"}}))))

(defn -main [& _]
  (let [library (vec (references/library (or (System/getenv "ARCHITECT_REFERENCE_DIR") "../../external-resources/")))]
    (doseq [line (line-seq (java.io.BufferedReader. *in*))]
      (let [response (try (dispatch library (json/parse-string line true))
                          (catch Exception _ {:jsonrpc "2.0" :id nil
                                              :error {:code -32700 :message "invalid JSON-RPC request"}}))]
        (when response (println (json/generate-string response)) (flush))))))
