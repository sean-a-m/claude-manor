(ns manor.llm-test
  "The OpenRouter response parser. A model's reasoning is kept for the
  record (llm.edn, the transcript) and never put in the message the engine
  sends back."
  (:require [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [manor.llm :as llm]))

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

(deftest parse-response-keeps-reasoning-apart
  (let [parsed ((var-get #'llm/parse-response) body)
        {:keys [message reasoning content tool-calls usage]} parsed]
    (testing "reasoning is read"
      (is (= "Let me think about where everyone is." reasoning))
      (is (= 300 (:reasoning-tokens usage)))
      (is (= 365 (:completion-tokens usage))))
    (testing "the message sent back to the model has no reasoning in it"
      (is (not (contains? message :reasoning)))
      (is (= "assistant" (:role message)))
      (is (= "Very well." content))
      (is (= "walk" (get-in tool-calls [0 :name]))))))

(deftest no-reasoning-is-nil
  (let [parsed ((var-get #'llm/parse-response)
                (json/write-value-as-string
                 {:choices [{:message {:role "assistant" :content ""} :finish_reason "stop"}]
                  :usage {:prompt_tokens 1 :completion_tokens 1 :cost 0}}))]
    (is (nil? (:reasoning parsed)))
    (is (nil? (:reasoning-tokens (:usage parsed))))))
