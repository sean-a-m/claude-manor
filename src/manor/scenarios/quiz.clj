(ns manor.scenarios.quiz
  "Example scenario for MCP bridges: contestants answer one quiz question per
  round, and may first message their own assistant, a persistent pi session
  reached through the pi MCP server (mcp-servers/pi/server.mjs).

  Each contestant's assistant is private: the engine picks the session (the
  contestant's id), so only they talk to it and only they see its replies.
  Sharing what it said takes an in-game action, e.g. `say`."
  (:require [clojure.string :as str]
            [manor.narrate :as narrate]
            [manor.world :as w]))

(def questions
  [{:text "What is the capital city of Mongolia?" :accept ["ulaanbaatar" "ulanbator"]}
   {:text "Which chemical element has atomic number 74?" :accept ["tungsten" "wolfram"]}
   {:text "Who wrote the novel 'The Left Hand of Darkness'?" :accept ["leguin"]}])

(defn- normalize [s] (str/replace (str/lower-case (str s)) #"[^a-z0-9]" ""))

(defn- correct? [question answer]
  (let [a (normalize answer)]
    (boolean (some #(str/includes? a %) (:accept question)))))

(defn- current [world] (get-in world [:state :current]))

(defn- answered? [world actor]
  (contains? (get-in world [:state :answers (current world)]) actor))

;; --- Reducers ------------------------------------------------------------------

(defmethod w/apply-event :quiz/question-posed [world {:keys [data]}]
  (assoc-in world [:state :current] (:index data)))

(defmethod w/apply-event :quiz/answered [world {:keys [actor data]}]
  (cond-> (assoc-in world [:state :answers (:index data) actor] (:answer data))
    (:correct? data) (update-in [:state :scores actor] (fnil inc 0))))

(defmethod w/apply-event :quiz/assistant-replied [world _] world)

(defmethod w/apply-event :quiz/finished [world _]
  (assoc-in world [:state :finished?] true))

;; --- Narration -------------------------------------------------------------------

(defn- clip [s n] (if (> (count s) n) (str (subs s 0 n) "...") s))

(defmethod narrate/describe :quiz/question-posed [{:keys [data]} _]
  (str "Question " (inc (:index data)) ": " (:text data)))

(defmethod narrate/describe :quiz/answered [{:keys [actor data]} names]
  (str (narrate/who names actor) " answered question " (inc (:index data)) ": \"" (:answer data) "\" - "
       (if (:correct? data) "correct!" "wrong.")))

(defmethod narrate/describe :quiz/assistant-replied [{:keys [actor data]} names]
  (str (narrate/who names actor) " asked their assistant: \"" (clip (:message data) 300)
       "\" It replied: \"" (clip (str (:reply data)) 800) "\""))

(defmethod narrate/describe :quiz/finished [{:keys [data]} names]
  (str "The quiz is over. Scores: "
       (str/join ", " (for [[id n] (sort-by key (:scores data))] (str (narrate/who names id) " " n)))))

;; --- Actions -------------------------------------------------------------------

(def actions
  [{:name :ask-assistant
    :doc "Send a message to your personal assistant, an AI that only you can talk to and that remembers your earlier conversation. Its reply comes back to you immediately."
    :kind :query
    :args [:map [:message [:string {:min 1 :max 2000}]]]
    :bridge :pi/send-message
    ;; The engine, not the model, chooses the session: each contestant has their own.
    :bridge-args (fn [_ actor {:keys [message]}] {:session actor :message message})
    ;; Keep the exchange in the contestant's private history for later rounds.
    :on-result (fn [_ actor {:keys [message]} reply]
                 [{:type :quiz/assistant-replied :visible-to #{actor}
                   :data {:message message :reply reply}}])}

   {:name :answer
    :doc "Submit your answer to the current question. You can answer each question only once."
    :args [:map [:answer [:string {:min 1 :max 200}]]]
    :valid? (fn [world actor _]
              (cond (nil? (current world)) "no question has been asked yet"
                    (answered? world actor) "you already answered this question; call pass"))
    :effect (fn [world actor {:keys [answer]}]
              (let [i (current world)]
                [{:type :quiz/answered :visible-to #{actor}
                  :data {:index i :answer answer :correct? (correct? (get questions i) answer)}}]))}])

;; --- Systems -------------------------------------------------------------------

(def quiz-master
  {:name :quiz-master
   :tick (fn [world _]
           (let [next-i (if-let [i (current world)] (inc i) 0)]
             (cond
               (get-in world [:state :finished?]) []
               (< next-i (count questions))
               [{:type :quiz/question-posed :visible-to :all
                 :data {:index next-i :text (:text (get questions next-i))}}]
               :else
               [{:type :quiz/finished :visible-to :all
                 :data {:scores (merge (zipmap (map :id (w/active-agents world)) (repeat 0))
                                       (get-in world [:state :scores]))}}])))})

;; --- Views ---------------------------------------------------------------------

(defn observe [world id]
  (let [i (current world)]
    {:question (when i {:number (inc i) :of (count questions) :text (:text (get questions i))})
     :you {:answered-this-question? (answered? world id)
           :score (get-in world [:state :scores id] 0)}}))

;; --- Mock policy (free offline runs) -------------------------------------------

(defn mock-policy [{:keys [world agent-id role used]}]
  (cond
    (= role :gm) {:tool :done}
    (or (nil? (current world)) (answered? world agent-id)) {:tool :pass}
    (not (some #{"ask-assistant"} used))
    {:tool :ask-assistant :args {:message (:text (get questions (current world)))}}
    :else {:tool :answer :args {:answer "I don't know"}}))

;; --- The scenario ----------------------------------------------------------------

(def scenario
  {:name :quiz
   :init (fn [_opts]
           {:state {:current nil :answers {} :scores {} :finished? false}
            :agents [{:name "Ana" :role :contestant :persona "Careful; checks facts before answering."}
                     {:name "Bo" :role :contestant :persona "Quick and confident."}]})
   :roles {:contestant {:brief "You are a contestant in a quiz. Each round one question is asked. You have a private personal assistant you can message with ask-assistant before answering; then submit your answer with the answer tool. You score a point for each correct answer. Your assistant's replies are private to you."
                        :spawnable? true}
           :gm {:brief "A quiz. Intervene only if asked."}}
   :actions actions
   :systems [quiz-master]
   :observe observe
   ;; No turns once the quiz is over.
   :schedule (fn [world]
               (if (get-in world [:state :finished?])
                 []
                 (mapv (comp vector :id) (w/active-agents world))))
   :pause-when (fn [world] (when (get-in world [:state :finished?]) "the quiz is over"))
   :bridges {:pi/send-message {:doc "Message the caller's own persistent pi session"
                               :mcp {:server :pi :tool "send_message"}
                               :timeout-ms 300000
                               :max-chars 3000}}
   ;; How to start the server. Machine-specific settings (e.g. a sandboxed pi
   ;; command) go in config/mcp.edn; see config/mcp.example.edn.
   :mcp-servers {:pi {:command ["node" "mcp-servers/pi/server.mjs"]
                      :env {"PI_MCP_SYSTEM_PROMPT"
                            "You are the personal assistant of a quiz contestant. Answer briefly: at most three sentences."}}}
   :offline-stubs {:pi/send-message (fn [{:keys [message]} _]
                                      (str "(offline assistant) You asked: " message))}
   :limits {:max-agents 4}
   :mock-policy mock-policy})
