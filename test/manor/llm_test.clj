(ns manor.llm-test
  "The OpenRouter response parser. A character's private thoughts are kept
  for the record (llm.edn, the transcript) and never put in the message the
  engine sends back."
  (:require [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [manor.io.llm :as llm]))

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
