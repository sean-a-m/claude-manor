(ns people.server
  "Newline-delimited MCP JSON-RPC over stdio. No model calls, no key and no
  mutable world: the same request always gives the same answer."
  (:require [cheshire.core :as json]
            [people.generate :as generate]
            [people.reputation :as reputation]))

(def tools
  [{:name "generate_people"
    :description "Roll basic identity, temperament and faculties for a position. No behavioural prose or circumstances; the engine writes those."
    :inputSchema {:type "object"
                  :properties {:position {:type "string"}
                               :count {:type "integer" :minimum 1 :maximum generate/max-count}
                               :area {:type "string"}
                               :setting {:type "string" :enum (vec (sort generate/settings))}
                               :seed {:type ["string" "integer"]}
                               :taken-names {:type "array" :items {:type "string"}}
                               :sex {:type "string" :enum ["male" "female"]}
                               :ages {:type "array" :items {:type "integer"} :minItems 2 :maxItems 2}}
                  :required ["position" "seed"]}}
   {:name "reputation"
    :description "What a knower knows of a person, from how they know them."
    :inputSchema {:type "object"
                  :properties {:person {:type "object"}
                               :knower {:type "string"}
                               :relation {:type "string" :enum (vec (sort (keys reputation/relations)))}
                               :seed {:type ["string" "integer"]}}
                  :required ["person" "knower" "relation" "seed"]}}])

(defn tool-result [{:keys [name arguments]}]
  (try
    (let [result (case name
                   "generate_people" (generate/generate arguments)
                   "reputation" (reputation/reputation arguments)
                   (throw (ex-info "unknown tool; use tools/list" {})))]
      {:structuredContent result :content [{:type "text" :text (json/generate-string result)}]})
    (catch Exception e
      {:isError true :content [{:type "text" :text (ex-message e)}]})))

(defn dispatch [{:keys [id method params]}]
  (when (some? id)
    (merge {:jsonrpc "2.0" :id id}
           (case method
             "initialize" {:result {:protocolVersion "2024-11-05" :capabilities {:tools {}}
                                     :serverInfo {:name "people" :version "1.0"}}}
             "ping" {:result {}}
             "tools/list" {:result {:tools tools}}
             "tools/call" {:result (tool-result params)}
             {:error {:code -32601 :message "method not found"}}))))

(defn -main [& _]
  (doseq [line (line-seq (java.io.BufferedReader. *in*))]
    (let [response (try (dispatch (json/parse-string line true))
                        (catch Exception _ {:jsonrpc "2.0" :id nil
                                            :error {:code -32700 :message "invalid JSON-RPC request"}}))]
      (when response (println (json/generate-string response)) (flush)))))
