(ns manor.io.llm
  "Model access. The engine builds every request itself (messages and tools)
  and keeps no conversation state here, so each call is self-contained.

  Request:  {:model \"deepseek/deepseek-v4.1-flash\" :messages [...] :tools [...]
             :max-tokens 2000 :reasoning {:effort \"low\"} :tool-choice \"auto\"
             :ctx {...}}
            :ctx is engine context for mock policies; it is never sent anywhere.
  Response: {:message {...assistant message, OpenAI wire format...}
             :content \"...\" :tool-calls [{:id :name :arguments}]
             :thoughts \"...\"                 ; the character's private thoughts, if it returns any
             :usage {:prompt-tokens :completion-tokens :thought-tokens :cost
                     :cached-tokens :cache-write-tokens}   ; the cache counts if the provider gives them
             :provider \"...\" :generation-id \"...\"}     ; OpenRouter's provider and id, if given

  A model may return the character's private thoughts beside its reply. They
  are recorded (llm.edn, the transcript) and never sent back: `:message` is
  what the engine sends. The run option `:reasoning` is the provider's own
  deliberation setting.

  `tool-digests` fingerprints the tools of a request, so a log can show
  which tool definitions stayed the same from one call to the next (what a
  provider's prompt cache depends on)."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [manor.io.http :as http])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)))

(defprotocol LLM
  (complete [this request]))

;; ---------------------------------------------------------------------------
;; OpenRouter (OpenAI-compatible chat completions)

(defn api-key
  "MANOR_OPENROUTER_API_KEY: manor's own OpenRouter key, kept apart from pi's
  so it can carry its own guardrails and budget. There is deliberately no
  fallback to another key."
  []
  (not-empty (System/getenv "MANOR_OPENROUTER_API_KEY")))

(defn- parse-response
  "Read one chat-completions response. Any private thoughts the model returns
  are kept apart from `:message`, so they are never sent back to the model."
  [body]
  (let [resp (json/read-value body json/keyword-keys-object-mapper)
        msg (get-in resp [:choices 0 :message])
        usage (:usage resp)]
    (when-let [err (:error resp)]
      (throw (ex-info (str "OpenRouter error: " (or (:message err) (pr-str err))) {:error err})))
    (cond->
     {:message (select-keys msg [:role :content :tool_calls])   ; what goes back to the model
      :content (:content msg)
      :thoughts (not-empty (:reasoning msg))                   ; the wire field; recorded, never sent back
      :finish-reason (get-in resp [:choices 0 :finish_reason])
      :tool-calls (mapv (fn [tc] {:id (:id tc)
                                  :name (get-in tc [:function :name])
                                  :arguments (get-in tc [:function :arguments])})
                        (:tool_calls msg))
      :usage (let [{:keys [cached_tokens cache_write_tokens]} (:prompt_tokens_details usage)]
               (cond-> {:prompt-tokens (:prompt_tokens usage)
                        :completion-tokens (:completion_tokens usage)
                        :thought-tokens (get-in usage [:completion_tokens_details :reasoning_tokens])
                        :cost (:cost usage)}
                 cached_tokens (assoc :cached-tokens cached_tokens)
                 cache_write_tokens (assoc :cache-write-tokens cache_write_tokens)))}
      (:provider resp) (assoc :provider (:provider resp))
      (:id resp) (assoc :generation-id (:id resp)))))

(defn tool-digests
  "A fingerprint of each tool in `tools` (tool definitions as sent, e.g.
  {:type \"function\" :function {...}}), in order: {:name :hash :chars}, where
  :hash is the first 16 hex characters of the SHA-256 of the tool's compact
  JSON (as the request body writes it) and :chars is that JSON's length."
  [tools]
  (let [sha (MessageDigest/getInstance "SHA-256")]
    (mapv (fn [tool]
            (let [s (json/write-value-as-string tool)
                  digest (.digest sha (.getBytes s StandardCharsets/UTF_8))]
              {:name (get-in tool [:function :name])
               :hash (subs (apply str (map #(format "%02x" %) digest)) 0 16)
               :chars (count s)}))
          tools)))

(defn- retryable? [{:keys [status]}]
  (or (nil? status) (= status 429) (>= status 500)))

(defrecord OpenRouter [key base-url timeout-ms]
  LLM
  (complete [_ {:keys [model messages tools max-tokens reasoning tool-choice]}]
    (let [body (json/write-value-as-string
                (cond-> {:model model
                         :messages messages
                         :max_tokens (or max-tokens 2000)
                         :usage {:include true}}
                  (seq tools) (assoc :tools tools :tool_choice (or tool-choice "auto"))
                  reasoning (assoc :reasoning reasoning)))
          send #(try
                  (http/request {:method :post
                                 :url (str base-url "/chat/completions")
                                 :headers {"Authorization" (str "Bearer " key)
                                           "Content-Type" "application/json"
                                           "X-Title" "claude-manor"}
                                 :body body
                                 :timeout-ms timeout-ms})
                  (catch Exception e {:error e}))]
      (loop [attempt 1]
        (let [{:keys [status body error] :as resp} (send)]
          (cond
            (and (= status 200) body) (parse-response body)
            (and (retryable? resp) (< attempt 3))
            (do (Thread/sleep (* 2000 attempt)) (recur (inc attempt)))
            :else (throw (ex-info (str "OpenRouter request failed: "
                                       (or (some-> error ex-message)
                                           (str "HTTP " status " " (some-> body (subs 0 (min 300 (count body)))))))
                                  {:status status}))))))))

(defn openrouter []
  (let [key (api-key)]
    (when (str/blank? key)
      (throw (ex-info "No OpenRouter key: set MANOR_OPENROUTER_API_KEY" {})))
    (->OpenRouter key "https://openrouter.ai/api/v1" 180000)))

;; ---------------------------------------------------------------------------
;; Mock: a scenario policy picks the tool call. Free to run, deterministic.

(defrecord Mock [policy]
  LLM
  (complete [_ {:keys [ctx]}]
    (let [{:keys [tool args say]} (or (policy ctx) {:tool "pass"})
          call {:id (str "mock-" (:step ctx) "-" (name tool))
                :name (name tool)
                :arguments (json/write-value-as-string (or args {}))}]
      {:message {:role "assistant"
                 :content say
                 :tool_calls [{:id (:id call) :type "function"
                               :function {:name (:name call) :arguments (:arguments call)}}]}
       :content say
       :tool-calls [call]
       :usage {:prompt-tokens 0 :completion-tokens 0 :cached-tokens 0 :cost 0}})))

(defn mock [policy] (->Mock (or policy (constantly nil))))
