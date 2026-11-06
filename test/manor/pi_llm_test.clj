(ns manor.pi-llm-test
  "Model calls through pi (manor.io.pi-llm and mcp-servers/pi-llm/server.mjs),
  driven end to end with a fake pi runtime (test/fixtures/fake-pi-runtime.mjs):
  free and offline. Needs node on the PATH."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [manor.io.llm :as llm]
            [manor.io.pi-llm :as pi-llm]
            [manor.io.store :as store]
            [manor.test-support :as support]))

(use-fixtures :once support/with-runs)

(def ^:private fake-server
  (assoc pi-llm/server :env {"PI_LLM_MODULE" (.getAbsolutePath (io/file "test/fixtures/fake-pi-runtime.mjs"))}))

(def ^:private walk-tool
  {:type "function" :function {:name "walk" :description "Walk somewhere."
                               :parameters {:type "object" :properties {:to {:type "string"}}}}})

(defn- seen
  "What the fake runtime was sent, from the reply's text."
  [response]
  (json/read-value (:content response) json/keyword-keys-object-mapper))

(deftest calls-through-pi
  (let [dir (str (io/file store/test-runs-root (str (gensym "pi-llm-"))))
        client (pi-llm/pi-llm {:run-id "pi-llm-test" :run-dir dir} fake-server)
        first-messages [{:role "system" :content "You are Ann."}
                        {:role "user" :content "It is morning."}]
        request {:model "fake/m1" :messages first-messages :tools [walk-tool]
                 :max-tokens 4000 :reasoning {:effort "none"} :tool-choice "auto"
                 :ctx {:world :not-sent}}]
    (try
      (let [r1 (llm/complete client request)]
        (testing "the request reaches pi-ai converted"
          (is (= {:model "fake/m1" :systemPrompt "You are Ann." :roles ["user"] :tools ["walk"]
                  :toolResultNames [] :thinkingSentBack [] :reasoning nil :maxTokens 4000 :toolChoice "auto"}
                 (seen r1))))
        (testing "the reply comes back in manor's shape, thoughts apart from the message"
          (is (= [{:id "call-1" :name "walk" :arguments "{\"to\":\"study\"}"}] (:tool-calls r1)))
          (is (= [{:id "call-1" :type "function" :function {:name "walk" :arguments "{\"to\":\"study\"}"}}]
                 (get-in r1 [:message :tool_calls])))
          (is (= "thinking 1" (:thoughts r1)))
          (is (not (contains? (:message r1) :thoughts)))
          (is (= "tool_calls" (:finish-reason r1)))
          (is (= {:prompt-tokens 1100 :completion-tokens 20 :thought-tokens 5 :cost 0.0021
                  :cached-tokens 100 :cache-write-tokens 0}
                 (:usage r1)))
          (is (= "pi:fake" (:provider r1))))
        (testing "within a turn, the reply goes back with its thinking"
          (let [r2 (llm/complete client (assoc request
                                               :reasoning {:effort "low"}
                                               :messages (conj first-messages (:message r1)
                                                               {:role "tool" :tool_call_id "call-1" :content "You walk."})))]
            (is (= {:roles ["user" "assistant" "toolResult"] :toolResultNames ["walk"]
                    :thinkingSentBack ["thinking 1"] :reasoning "low"}
                   (select-keys (seen r2) [:roles :toolResultNames :thinkingSentBack :reasoning]))))))
      (testing "an assistant message pi didn't write goes as it is"
        (let [r (llm/complete client (assoc request :messages
                                            (conj first-messages
                                                  {:role "assistant" :content nil
                                                   :tool_calls [{:id "old" :type "function"
                                                                 :function {:name "walk" :arguments "{}"}}]}
                                                  {:role "tool" :tool_call_id "old" :content "ok"})))]
          (is (= [] (:thinkingSentBack (seen r))))))
      (testing "other names are OpenRouter ids"
        (is (= "openrouter/deepseek/deepseek-v4.1-flash"
               (:model (seen (llm/complete client (assoc request :model "deepseek/deepseek-v4.1-flash"))))))
        (is (= "openrouter/x/y" (:model (seen (llm/complete client (assoc request :model "openrouter/x/y")))))))
      (testing "provider errors throw with their message"
        (is (thrown-with-msg? Exception #"boom" (llm/complete client (assoc request :model "fake/error")))))
      (finally (.close ^java.io.Closeable client)))))
