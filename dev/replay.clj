(ns replay
  "Prompt replay: re-send model calls recorded in a run's llm.edn, as they
  were or with edited text, to see how a prompt change alters what the model
  does at a moment that went wrong. Calls the real model, so it costs money:
  a few tenths of a cent a call with the default model.

  llm.edn keeps each call's messages but only the names and digests of its
  tools, so `tools-for` rebuilds them from the current code; `check` says
  whether they still match what was sent.

    (require 'replay)
    (def calls (replay/calls \"runs/claudeworth-20261019-233942\"))
    (replay/find-calls calls #(and (= \"nellie\" (:agent %)) (= 14 (:round %))))
    (replay/check (calls 518))
    (replay/resend (calls 518) 5)                            ; as it was
    (replay/resend (calls 518) 5 #(replay/insert-after % \"user\" \"- `note` is free\" \" New text.\"))

  Read the results' :thoughts as well as their tool calls: they show whether
  the model noticed the new text."
  (:require [clojure.string :as str]
            [manor.actions :as a]
            [manor.engine :as e]
            [manor.io.llm :as llm]
            [manor.io.store :as store]
            [manor.mechanics.conversation :as conv]
            [manor.mechanics.items :as items]))

(defn calls
  "The recorded model calls of the run in `run-dir`, as a vector."
  [run-dir]
  (vec (store/read-llm run-dir)))

(defn find-calls
  "Indexes of the calls matching `pred`, with agent, round and tool names."
  [calls pred]
  (for [[i c] (map-indexed vector calls) :when (pred c)]
    [i (:agent c) (:round c) (:tools c)]))

(defn- tool-pool [scenario-key]
  (let [scenario (e/load-scenario scenario-key)]
    (merge (a/by-name (e/all-actions scenario))
           (a/by-name (conv/speaking-actions {:initiator "x"} "x"))
           (a/by-name (conv/speaking-actions {:initiator "x"} "y")))))

(defn tools-for
  "The call's tools rebuilt from the current code: the scenario's actions,
  the speaking-turn tools, or the scene judge's tool."
  ([call] (tools-for call :claudeworth))
  ([call scenario-key]
   (let [pool (tool-pool scenario-key)]
     (mapv #(if (= "judge" %) @#'items/judge-tool (a/->tool (get pool %))) (:tools call)))))

(defn check
  "The names of tools whose rebuilt definition differs from what was sent;
  empty when the replay sends exactly the recorded tools."
  [call]
  (vec (keep (fn [[rebuilt sent]] (when (not= (:hash rebuilt) (:hash sent)) (:name sent)))
             (map vector (llm/tool-digests (tools-for call)) (:tool-digests call)))))

(defn insert-after
  "`request` with `text` inserted after `anchor` in its first message with
  `role` that contains the anchor. Throws if no message has it."
  [request role anchor text]
  (let [msgs (:messages request)
        i (first (keep-indexed (fn [i m] (when (and (= role (:role m)) (str/includes? (str (:content m)) anchor)) i)) msgs))]
    (when-not i (throw (ex-info (str "anchor not found: " anchor) {:role role})))
    (assoc-in request [:messages i :content] (str/replace-first (get-in msgs [i :content]) anchor (str anchor text)))))

(defn edit-tool-doc
  "`request` with `f` applied to the description of tool `tool-name`."
  [request tool-name f]
  (update request :tools (fn [tools] (mapv #(if (= tool-name (get-in % [:function :name]))
                                              (update-in % [:function :description] f)
                                              %)
                                           tools))))

(defn resend
  "Send `call` `n` times, after `edit` (fn [request] => request) if given, in
  parallel. Returns [{:text :tools :thoughts :cost}], tools as [name args]."
  ([call n] (resend call n identity))
  ([call n edit]
   (let [client (llm/openrouter)
         request (edit {:model (:model call) :messages (:messages call) :tools (tools-for call)
                        :max-tokens 4000 :reasoning {:effort "low"} :tool-choice "auto"})]
     (vec (pmap (fn [_]
                  (let [r (llm/complete client request)]
                    {:text (:content r) :tools (mapv (juxt :name :arguments) (:tool-calls r))
                     :thoughts (:thoughts r) :cost (get-in r [:usage :cost])}))
                (range n))))))
