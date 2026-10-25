(ns manor.llm-test
  "The OpenRouter response parser and bounded HTTP retries. Private thoughts are kept
  for the record (llm.edn, the transcript) and never put in the message the
  engine sends back."
  (:require [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [manor.io.llm :as llm]
            [manor.io.http :as http]))

(def ^:private body
  (json/write-value-as-string
   {:choices [{:message {:role "assistant"
                         :content "Very well."
                         :reasoning "Let me think about where everyone is."
                         :tool_calls [{:id "c1" :type "function"
                                       :function {:name "walk" :arguments "{\"to\":\"study\"}"}}]}
               :finish_reason "tool_calls"}]
    :usage {:prompt_tokens 120 :completion_tokens 365 :cost 0.0011
            :completion_tokens_details {:reasoning_tokens 300}}}))

(deftest parse-response-keeps-thoughts-apart
  (let [parsed ((var-get #'llm/parse-response) body)
        {:keys [message thoughts content tool-calls usage]} parsed]
    (testing "the character's thoughts are read"
      (is (= "Let me think about where everyone is." thoughts))
      (is (= 300 (:thought-tokens usage)))
      (is (= 365 (:completion-tokens usage))))
    (testing "the message sent back to the model has no thoughts in it"
      (is (not (contains? message :thoughts)))
      (is (= "assistant" (:role message)))
      (is (= "Very well." content))
      (is (= "walk" (get-in tool-calls [0 :name]))))))

(deftest no-thoughts-is-nil
  (let [parsed ((var-get #'llm/parse-response)
                (json/write-value-as-string
                 {:choices [{:message {:role "assistant" :content ""} :finish_reason "stop"}]
                  :usage {:prompt_tokens 1 :completion_tokens 1 :cost 0}}))]
    (is (nil? (:thoughts parsed)))
    (is (nil? (:thought-tokens (:usage parsed))))))

(deftest parse-response-reads-cache-provider-and-id
  (testing "OpenRouter's cache counts, provider and generation id are read"
    (let [parsed ((var-get #'llm/parse-response)
                  (json/write-value-as-string
                   {:id "gen-123" :provider "DeepSeek"
                    :choices [{:message {:role "assistant" :content "Yes."} :finish_reason "stop"}]
                    :usage {:prompt_tokens 1000 :completion_tokens 5 :cost 0.0001
                            :prompt_tokens_details {:cached_tokens 896 :cache_write_tokens 64}}}))]
      (is (= 896 (get-in parsed [:usage :cached-tokens])))
      (is (= 64 (get-in parsed [:usage :cache-write-tokens])))
      (is (= "DeepSeek" (:provider parsed)))
      (is (= "gen-123" (:generation-id parsed)))))
  (testing "without them the keys are left out"
    (let [parsed ((var-get #'llm/parse-response) body)]
      (is (not (contains? (:usage parsed) :cached-tokens)))
      (is (not (contains? (:usage parsed) :cache-write-tokens)))
      (is (not (contains? parsed :provider)))
      (is (not (contains? parsed :generation-id)))
      (is (= 120 (get-in parsed [:usage :prompt-tokens]))))))

(deftest tool-digests-fingerprint-each-tool
  (let [walk {:type "function" :function {:name "walk" :description "Walk." :parameters {:type "object"}}}
        say {:type "function" :function {:name "say" :description "Say." :parameters {:type "object"}}}
        [w s w2] (llm/tool-digests [walk say walk])]
    (is (= ["walk" "say" "walk"] (map :name [w s w2])))
    (is (re-matches #"[0-9a-f]{16}" (:hash w)))
    (is (= (count (json/write-value-as-string walk)) (:chars w)))
    (is (= w w2) "identical definitions give identical digests")
    (is (not= (:hash w) (:hash s)))
    (is (not= (:hash w) (:hash (first (llm/tool-digests [(assoc-in walk [:function :description] "Stroll.")])))))))

(def ^:private provider-400
  ;; The Baidu error reported from a conversation: generic upstream invalid_argument.
  {:status 400
   :body (json/write-value-as-string
          {:error {:message "Provider returned error" :code 400
                   :metadata {:raw "{\"error\":{\"code\":\"invalid_argument\",\"message\":\"Invalid Argument\",\"type\":\"invalid_request_error\"},\"id\":\"as-f32b757vqi\"}"
                              :provider_name "Baidu" :is_byok false
                              :provider_error_code "invalid_argument"}}})})

(defn- complete-sequence
  "Exercise the real client with scripted HTTP responses and no network or sleeps.
  Reuse the last response if retries outnumber the script."
  [responses]
  (let [requests (atom [])
        waits (atom [])
        outcome (with-redefs [http/request (fn [request]
                                            (let [i (count @requests)
                                                  response (nth responses (min i (dec (count responses))))]
                                              (swap! requests conj request)
                                              (if (instance? Exception response)
                                                (throw response)
                                                response)))
                             llm/sleep! #(swap! waits conj %)]
                  (try
                    {:response (llm/complete (llm/->OpenRouter "test-key" "https://unused.invalid" 1000)
                                             {:model "test-model"
                                              :messages [{:role "user" :content "Speak."}]
                                              :tools [{:type "function"
                                                       :function {:name "reply" :parameters {:type "object"}}}]})}
                    (catch clojure.lang.ExceptionInfo e {:error e})))]
    (assoc outcome :requests @requests :waits @waits)))

(deftest provider-400-retries-the-same-request-with-backoff
  (let [{:keys [response error requests waits]}
        (complete-sequence [provider-400 provider-400 {:status 200 :body body}])]
    (is (nil? error))
    (is (= "Very well." (:content response)))
    (is (= 3 (count requests)))
    (is (apply = requests) "resend the same prompt and tools, without applying any actions")
    (is (= [2000 4000] waits))))

(deftest persistent-provider-400-stops-after-three-attempts
  (let [{:keys [error requests waits]} (complete-sequence [provider-400])]
    (is (= 3 (count requests)))
    (is (= [2000 4000] waits))
    (is (= {:status 400 :attempts 3} (ex-data error)))
    (is (.contains (ex-message error) "failed after 3 attempts: HTTP 400"))
    (is (.contains (ex-message error) "Baidu"))))

(deftest request-errors-still-fail-immediately
  (doseq [response [{:status 400 :body (json/write-value-as-string
                                      {:error {:code 400 :message "Invalid request"}})}
                    {:status 400 :body "not JSON"}
                    {:status 400 :body nil}
                    {:status 401 :body "Unauthorized"}
                    {:status 402 :body "Insufficient credits"}
                    {:status 403 :body (:body provider-400)}]]
    (testing (pr-str response)
      (let [{:keys [error requests waits]} (complete-sequence [response])]
        (is (= {:status (:status response) :attempts 1} (ex-data error)))
        (is (= 1 (count requests)))
        (is (empty? waits))))))

(deftest existing-transient-errors-still-retry
  (doseq [failure [{:status 429 :body "Rate limited"}
                   {:status 500 :body "Internal error"}
                   {:status 503 :body "Unavailable"}
                   (java.io.IOException. "Connection reset")]]
    (testing (str failure)
      (let [{:keys [response error requests waits]}
            (complete-sequence [failure {:status 200 :body body}])]
        (is (nil? error))
        (is (= "Very well." (:content response)))
        (is (= 2 (count requests)))
        (is (= [2000] waits)))
      (let [{:keys [error requests waits]} (complete-sequence [failure])]
        (is (= 3 (count requests)))
        (is (= [2000 4000] waits))
        (is (= 3 (:attempts (ex-data error))))))))

(deftest successful-request-needs-no-retry
  (let [{:keys [response error requests waits]} (complete-sequence [{:status 200 :body body}])]
    (is (nil? error))
    (is (= "Very well." (:content response)))
    (is (= 1 (count requests)))
    (is (empty? waits))))
