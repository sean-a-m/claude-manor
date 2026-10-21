(ns manor.tools.transcript
  "A readable record of a run, for reading after the fact:

    (write! \"runs/claudeworth-20261015-101500\")  ; => \".../transcript.md\"

  For each round: what happened (narrated, with who could see it), then
  every model call: the tools called with their arguments, any text, and
  the engine's reply to each tool call. Read from events.edn and llm.edn
  only; nothing is called or changed.

  llm.edn records each call's request, response and the engine's :replies.
  Runs recorded before :replies existed have them read from the next call
  of the same turn instead, so there a turn's last call shows none. The
  transcript says whether a turn was forfeited.

  The engine writes it whenever a run pauses or stops; call `write!` for
  older runs."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [manor.clock :as clock]
            [manor.io.store :as store]
            [manor.narrate :as narrate]
            [manor.world :as w]))

(def ^:private hidden #{:llm/usage :round/started :round/ended})

(defn- round-label [world round]
  (if-let [c (:clock world)]
    (str (clock/short-label c round 1) " (round " round ")")
    (str "Round " round)))

(defn- visibility [{v :visible-to}]
  (if (set? v) (str/join "," (sort v)) (name v)))

(defn- recorded? [call] (contains? call :replies))

(defn- replies
  "What the engine sent back after `call`: its recorded :replies, or for
  older runs the messages the next call of the same turn adds after the
  call's own response (nil for a turn's last call)."
  [call next-call]
  (cond (recorded? call) (:replies call)
        next-call (drop (inc (count (:messages call))) (:messages next-call))))

(defn- one-line [s] (str/replace (str s) #"\s*\n\s*" " "))

(defn- call-lines
  "Markdown lines for one model call."
  [{:keys [agent thoughts response finish-reason usage conversation] :as call} next-call forfeited?]
  (let [tcs (:tool_calls response)
        text (not-empty (str/trim (str (:content response))))]
    (concat
     [(str "- **" agent "** "
           (when-let [{:keys [id pass]} conversation]
             (str "_(" id ", " (if (= :last-word pass) "last word" (str "pass " pass)) ")_ "))
           (if (seq tcs)
             (str/join "; " (for [{{n :name args :arguments} :function} tcs] (str n " " args)))
             "_(no tool call)_")
           (when (and finish-reason (not= "tool_calls" finish-reason)) (str " _finish: " finish-reason "_"))
           (format " _($%.4f%s)_" (double (or (:cost usage) 0))
                   (if-let [rt (:thought-tokens usage)] (str ", " rt " in thought") "")))]
     (when thoughts [(str "  - thoughts: " (one-line thoughts))])
     (when text [(str "  - text: " (one-line text))])
     (for [m (replies call next-call)]
       (str "  - engine: " (one-line (:content m))))
     (when (and (nil? next-call) forfeited?) ["  - _turn forfeited: no valid action taken_"]))))

(defn- cached-share
  "The percentage of prompt tokens served from the provider's cache, over
  the `calls` that report :cached-tokens, or \"–\" when none do (older runs)
  or they have no prompt tokens (mock runs)."
  [calls]
  (let [reported (filter (comp :cached-tokens :usage) calls)
        prompt (reduce + 0 (keep (comp :prompt-tokens :usage) reported))]
    (if (pos? prompt)
      (format "%.0f%%" (* 100.0 (/ (reduce + 0 (keep (comp :cached-tokens :usage) reported)) prompt)))
      "–")))

(defn- summary-lines
  "Calls, cost and cached share of the prompt per agent, error and rejection
  replies, and forfeits. `pairs` are [call next-call-of-its-turn]."
  [pairs events]
  (let [reply-texts (for [[call next-call] pairs
                          m (replies call next-call)
                          :when (= "tool" (:role m))]
                      (str (:content m)))
        count-of (fn [prefix] (count (filter #(str/starts-with? % prefix) reply-texts)))]
    (concat
     ["| agent | calls | cost | cached |" "|---|---|---|---|"]
     (for [[agent cs] (sort-by key (group-by :agent (map first pairs)))]
       (format "| %s | %d | $%.4f | %s |" agent (count cs) (double (reduce + 0 (keep (comp :cost :usage) cs)))
               (cached-share cs)))
     [""
      (str "Engine replies: " (count-of "error:") " errors, " (count-of "rejected:") " rejections"
           (when-not (every? (comp recorded? first) pairs)
             " (this run didn't record replies to a turn's last call)")
           ". Forfeits: "
           (count (filter #(= :action/forfeited (:type %)) events)) ".")])))

(defn render
  "The transcript of a run directory, as a markdown string."
  [run-dir]
  (let [events (store/read-events run-dir)
        world (w/replay events)
        names (narrate/names world)
        calls (store/read-llm run-dir)
        ;; each call with the next call of its turn (same agent, same round), or nil
        pairs (map-indexed (fn [i {:keys [agent round] :as call}]
                             [call (first (filter #(and (= agent (:agent %)) (= round (:round %)))
                                                  (drop (inc i) calls)))])
                           calls)
        forfeits (set (for [e events :when (= :action/forfeited (:type e))] [(:actor e) (:round e)]))
        round-lines (fn [round]
                      (let [in-round (filter #(= round (:round (first %))) pairs)]
                        (concat
                         ["" (str "## " (round-label world round)) "" "### What happened" ""]
                         (for [e events :when (and (= round (:round e)) (not (hidden (:type e))))]
                           (str "- " (narrate/describe e names) "  _[" (visibility e) "]_"))
                         (when (seq in-round) ["" "### Model calls" ""])
                         (mapcat (fn [[call next-call]] (call-lines call next-call (forfeits [(:agent call) round])))
                                 in-round))))]
    (str (str/join "\n" (concat
                         [(str "# Transcript of " run-dir) ""
                          (format "%d rounds, %d model calls, $%.4f" (:round world) (count calls)
                                  (double (w/total-cost world)))
                          ""]
                         (summary-lines pairs events)
                         (mapcat round-lines (range 1 (inc (:round world))))))
         "\n")))

(defn write!
  "Write <run-dir>/transcript.md and return its path."
  [run-dir]
  (let [f (io/file run-dir "transcript.md")]
    (spit f (render run-dir))
    (str f)))
