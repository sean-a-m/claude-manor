(ns manor.engine.models
  "Making a model call and recording it: usage events for the run's cost,
  and the call itself in llm.edn."
  (:require [clojure.string :as str]
            [manor.engine.run :as run]
            [manor.io.llm :as llm]
            [manor.io.store :as store])
  (:import (java.time Instant)))

(defn record-usage! [run agent-id request response]
  (run/emit! run [{:type :llm/usage :actor agent-id :visible-to :system
                   :data (assoc (:usage response) :agent agent-id :model (:model request))}]))

(defn complete!
  "Make a model call. The response gets :at, the instant (ISO-8601, UTC) the
  call was sent."
  [run request]
  (let [at (str (Instant/now))]
    (assoc (llm/complete (:llm run) request) :at at)))

(defn record-call!
  "Log a model call to llm.edn. `replies` are the messages the engine added
  after the response (tool replies and any reminder), so a turn's last call
  has its replies too. The character's private :thoughts, if the model
  returned any, are kept here only; they're never sent back to the model.
  :at is when the call was sent (see `complete!`), :tool-digests fingerprint
  the tools sent (`llm/tool-digests`), and :provider and :generation-id are
  there when the provider gives them. `extra` is merged into the entry,
  e.g. a speaking turn's :conversation."
  ([run agent-id request response replies] (record-call! run agent-id request response replies nil))
  ([run agent-id request response replies extra]
   (let [entry (merge (cond-> {:agent agent-id
                               :round (:round @(:world run))
                               :at (:at response)
                               :model (:model request)
                               :messages (:messages request)
                               :tools (mapv #(get-in % [:function :name]) (:tools request))
                               :tool-digests (llm/tool-digests (:tools request))
                               :response (:message response)
                               :thoughts (:thoughts response)
                               :finish-reason (:finish-reason response)
                               :usage (:usage response)
                               :replies replies}
                        (:provider response) (assoc :provider (:provider response))
                        (:generation-id response) (assoc :generation-id (:generation-id response)))
                      extra)]
     ;; Seats and judges can finish together. A single append can span several
     ;; writer buffers, so serialize the log write and publication per run.
     (locking (:last-calls run)
       (store/append-llm! (:dir run) entry)
       (swap! (:last-calls run) assoc agent-id entry)))))

(defn conj-reply
  "Add the model's reply to the conversation, unless it has neither content
  nor tool calls (e.g. all reasoning, cut off by `:max-tokens`): providers
  reject such an assistant message (DeepSeek: HTTP 400)."
  [messages {:keys [content tool_calls] :as message}]
  (if (or (not (str/blank? content)) (seq tool_calls))
    (conj messages message)
    messages))
