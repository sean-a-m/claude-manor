(ns manor.llm
  "Model access. The engine builds every request itself (messages and tools)
  and keeps no conversation state here, so each call is self-contained.

  Request:  {:model \"deepseek/deepseek-v4.1-flash\" :messages [...] :tools [...]
             :max-tokens 2000 :reasoning {:effort \"low\"} :ctx {...}}
            :ctx is engine context for mock policies; it is never sent anywhere.
  Response: {:message {...assistant message, OpenAI wire format...}
             :content \"...\" :tool-calls [{:id :name :arguments}]
             :reasoning \"...\"                  ; the model's thinking, if it exposes any
             :usage {:prompt-tokens :completion-tokens :reasoning-tokens :cost}}

  `:reasoning` is recorded (llm.edn, the transcript) but never sent back to
  the model: `:message` is what the engine sends."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jsonista.core :as json]
            [manor.http :as http]))

(defprotocol LLM
  (complete [this request]))

;; ---------------------------------------------------------------------------
;; OpenRouter (OpenAI-compatible chat completions)

(defn api-key
  "OPENROUTER_API_KEY, falling back to the key stored by pi in
  ~/.pi/agent/auth.json so an existing setup works without extra config."
  []
  (or (not-empty (System/getenv "OPENROUTER_API_KEY"))
      (let [f (io/file (System/getProperty "user.home") ".pi" "agent" "auth.json")]
        (when (.exists f)
          (get-in (json/read-value f) ["openrouter" "key"])))))

(defn- parse-response
  "Read one chat-completions response. The reasoning text some models
  expose is kept separately from `:message`, so it is never sent back."
  [body]
  (let [resp (json/read-value body json/keyword-keys-object-mapper)
        msg (get-in resp [:choices 0 :message])
        usage (:usage resp)]
    (when-let [err (:error resp)]
      (throw (ex-info (str "OpenRouter error: " (or (:message err) (pr-str err))) {:error err})))
    {:message (select-keys msg [:role :content :tool_calls])   ; what goes back to the model
     :content (:content msg)
     :reasoning (not-empty (:reasoning msg))                  ; recorded, never sent back
     :finish-reason (get-in resp [:choices 0 :finish_reason])
     :tool-calls (mapv (fn [tc] {:id (:id tc)
                                 :name (get-in tc [:function :name])
                                 :arguments (get-in tc [:function :arguments])})
                       (:tool_calls msg))
     :usage {:prompt-tokens (:prompt_tokens usage)
             :completion-tokens (:completion_tokens usage)
             :reasoning-tokens (get-in usage [:completion_tokens_details :reasoning_tokens])
             :cost (:cost usage)}}))

(defn- retryable? [{:keys [status]}]
  (or (nil? status) (= status 429) (>= status 500)))

(defrecord OpenRouter [key base-url timeout-ms]
  LLM
  (complete [_ {:keys [model messages tools max-tokens reasoning]}]
    (let [body (json/write-value-as-string
                (cond-> {:model model
                         :messages messages
                         :max_tokens (or max-tokens 2000)
                         :usage {:include true}}
                  (seq tools) (assoc :tools tools :tool_choice "required")
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
      (throw (ex-info "No OpenRouter key: set OPENROUTER_API_KEY" {})))
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
       :usage {:prompt-tokens 0 :completion-tokens 0 :cost 0}})))

(defn mock [policy] (->Mock (or policy (constantly nil))))
