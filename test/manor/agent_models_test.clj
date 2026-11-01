(ns manor.agent-models-test
  "An agent's own :model (manor.io.worldfile/ModelSpec): a model id or a map
  of the run's call settings it replaces, used for their turns and their
  conversation lines and for no one else's calls."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [manor.engine :as e]
            [manor.engine.models :as models]
            [manor.io.http :as http]
            [manor.io.llm :as llm]
            [manor.io.store :as store]
            [manor.io.worldfile :as wf]
            [manor.test-support :as support]
            [manor.world :as w]))

(use-fixtures :once support/with-runs)

(def ^:private opts
  {:model "run/model" :gm-model "run/gm" :reasoning {:effort "none"} :tool-choice "auto" :max-tokens 16000})

(def ^:private run-settings
  {:model "run/model" :reasoning {:effort "none"} :tool-choice "auto" :max-tokens 16000})

(deftest call-settings-put-the-agent's-model-over-the-run's
  (is (= run-settings (models/call-settings opts {:role :maid})))
  (is (= (assoc run-settings :model "run/gm") (models/call-settings opts {:role :gm})))
  (is (= (assoc run-settings :model "other/model") (models/call-settings opts {:role :maid :model "other/model"})))
  (testing "a map replaces only the settings it names, each whole; without :id the model is the run's"
    (is (= (assoc run-settings :reasoning {:effort "high"} :max-tokens 30000)
           (models/call-settings opts {:role :maid :model {:reasoning {:effort "high"} :max-tokens 30000}})))
    (is (= (assoc run-settings :model "other/model" :tool-choice "required" :cache-control {:type "ephemeral"})
           (models/call-settings opts {:role :maid :model {:id "other/model" :tool-choice "required"
                                                           :cache-control {:type "ephemeral"}}})))))

(deftest model-labels-for-the-operator
  (is (= "run default (run/model)" (models/model-label opts {:role :maid})))
  (is (= "other/model" (models/model-label opts {:role :maid :model "other/model"})))
  (is (= "run default (run/model) {:reasoning {:effort \"high\"}}"
         (models/model-label opts {:role :maid :model {:reasoning {:effort "high"}}}))))

(defn- world-file [agents]
  {:locations [{:id "hall" :name "Hall"}] :agents agents})

(deftest world-files-check-the-shape-of-model
  (is (empty? (wf/problems (world-file [{:name "Ada" :role :maid :model "other/model"}
                                        {:name "Ben" :role :maid :model {:reasoning {:effort "low"} :max-tokens 8000}}
                                        {:name "Cal" :role :maid :model {:id "x/y" :tool-choice "required"
                                                                         :cache-control {:type "ephemeral" :ttl "1h"}}}]))))
  (doseq [bad [{:temperature 1} {:id 3} {:max-tokens 0} {:reasoning "high"} 7]]
    (is (seq (wf/problems (world-file [{:name "Ada" :role :maid :model bad}]))) (pr-str bad))))

(deftest openrouter-sends-cache-control-only-when-given
  (let [bodies (atom [])
        reply (json/write-value-as-string {:choices [{:message {:role "assistant" :content "Yes."}}]
                                           :usage {:prompt_tokens 1 :completion_tokens 1 :cost 0}})]
    (with-redefs [http/request (fn [{:keys [body]}]
                                 (swap! bodies conj (json/read-value body json/keyword-keys-object-mapper))
                                 {:status 200 :body reply})]
      (doseq [extra [{} {:cache-control {:type "ephemeral" :ttl "1h"}}]]
        (llm/complete (llm/->OpenRouter "test-key" "https://unused.invalid" 1000)
                      (merge {:model "m" :messages [{:role "user" :content "Speak."}]} extra))))
    (is (not (contains? (first @bodies) :cache_control)))
    (is (= {:type "ephemeral" :ttl "1h"} (:cache_control (second @bodies))))))

(defn- update-agent [agents agent-name f]
  (mapv #(if (= agent-name (:name %)) (f %) %) agents))

(deftest an-agent's-model-reaches-their-turns-and-lines-only
  (let [lady-settings {:reasoning {:effort "high"} :max-tokens 123 :cache-control {:type "ephemeral"}}
        source (-> (wf/read-file "worlds/claudeworth.edn")
                   (update :agents update-agent "Lord Claudeworth" #(assoc % :model "other/lord"))
                   (update :agents update-agent "Lady Claudeworth" #(assoc % :model lady-settings)))
        filename (str (gensym "agent-models-") ".edn")
        _ (store/write-text! store/test-runs-root filename (wf/->edn-string source))
        run (e/start-run :claudeworth {:llm :mock :offline? true :test? true :model "run/model"
                                       :world-file (str (io/file store/test-runs-root filename))})]
    (try
      (e/auto! run 3)
      (e/wait-idle run)
      (let [calls (store/read-llm (:dir run))
            of (fn [id] (filter #(= id (:agent %)) calls))
            ;; whatever the run's defaults are now, not copies of them
            run-defaults (select-keys (:opts run) [:reasoning :tool-choice :max-tokens])]
        (doseq [id ["lord-claudeworth" "lady-claudeworth"]]
          (is (seq (filter :conversation (of id))) (str id " spoke in a conversation, so both paths are checked")))
        (is (every? #(and (= "other/lord" (:model %)) (= run-defaults (:settings %))) (of "lord-claudeworth")))
        (is (every? #(and (= "run/model" (:model %))
                          (= (merge run-defaults lady-settings) (:settings %)))
                    (of "lady-claudeworth")))
        (testing "everyone else, and the judges, keep the run's model and settings"
          (let [others (remove (comp #{"lord-claudeworth" "lady-claudeworth"} :agent) calls)]
            (is (some #(= "conversation-judge" (:agent %)) others))
            (is (every? #(and (= "run/model" (:model %)) (= run-defaults (:settings %))) others))))
        (is (= (e/world run) (w/replay (store/read-events (:dir run))))))
      (finally (e/stop-run run)))))
