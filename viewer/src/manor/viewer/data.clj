(ns manor.viewer.data
  "Reading a manor run directory for the transcript viewer.

  Nothing here starts a run, calls a model or changes a file: a run directory
  is read (`config.edn`, `events.edn`, `llm.edn`) and turned into plain maps
  the renderer can walk. Parsing is deliberately tolerant — a line that will
  not read is kept in `:errors` and skipped, so a half-written `llm.edn` from
  a live run can still be reviewed.

  `llm.edn` is one printed map per line (see `manor.io.store`); the viewer keeps
  that shape: `:calls` is a vector of call maps in run order, each with the
  engine's own `:messages`, `:response`, `:replies`, `:thoughts` and `:usage`."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;;; ---------------------------------------------------------------------------
;;; reading files

(defn read-edn-lines
  "Read a file of one printed EDN form per line.

  Returns {:forms [...] :errors [{:line n :error \"...\"}]}. Blank lines and
  comments are skipped; a line that does not read is recorded, not thrown."
  [file]
  (if-not (and file (fs/exists? file))
    {:forms [] :errors []}
    (reduce (fn [acc [n line]]
              (cond
                (str/blank? line) acc
                (str/starts-with? (str/trim line) ";") acc
                :else (try (update acc :forms conj (edn/read-string line))
                           (catch Throwable e
                             (update acc :errors conj {:line n :error (str (.getMessage e))})))))
            {:forms [] :errors []}
            (map-indexed (fn [i l] [(inc i) l]) (str/split-lines (slurp (fs/file file)))))))

(defn read-edn-file
  "Read a file holding exactly one EDN value (or nil if the file is absent)."
  [file]
  (when (fs/exists? file)
    (try (edn/read-string (slurp (fs/file file)))
         (catch Throwable _ nil))))

(defn- file-stamp [dir name]
  (let [f (fs/file dir name)]
    (when (fs/exists? f) [(str (fs/last-modified-time f)) (fs/size f)])))

(defn run-dir?
  "A directory is a run if it holds a run artifact."
  [dir]
  (and (fs/directory? dir)
       (or (fs/exists? (fs/file dir "llm.edn"))
           (fs/exists? (fs/file dir "config.edn")))))

(defn list-run-dirs
  "Run directories under `root`: `root` itself if it is a run, then its
  immediate subdirectories that are runs, newest first."
  [root]
  (let [root (fs/canonicalize (fs/file root))]
    (->> (cons root (filter fs/directory? (fs/list-dir root)))
         (filter run-dir?)
         (sort-by (fn [d] (str (or (file-stamp d "llm.edn") (file-stamp d "config.edn"))))
                  #(compare %2 %1)))))

(defn resolve-inside
  "The path `rel` names inside `root`, if it really is inside `root`.
  nil if it is missing or tries to escape (path traversal)."
  [root rel]
  (try
    (let [root (fs/canonicalize (fs/file root))
          target (fs/canonicalize (fs/file root (str (or rel ""))))
          root-str (str root)]
      (when (and (fs/exists? target)
                 (or (= (str target) root-str)
                     (str/starts-with? (str target) (str root-str fs/file-separator))))
        target))
    (catch Throwable _ nil)))

(defn resolve-run-dir
  "Like `resolve-inside`, but only when it is a directory."
  [root rel]
  (when-let [p (resolve-inside root rel)]
    (when (fs/directory? p) p)))

(defn read-file-in
  "Slurp `name` from a run directory, or nil. `name` must stay inside it."
  [dir name]
  (when-let [f (resolve-inside dir name)]
    (when (fs/regular-file? f) (slurp (fs/file f)))))

(defn dir-name
  "What to call a run directory in the UI."
  [root rel]
  (if (str/blank? (str rel))
    (str (fs/file-name (fs/canonicalize (fs/file root))) "/")
    (str rel)))

;;; ---------------------------------------------------------------------------
;;; runs

(defonce ^:private cache (atom {}))

(defn- cache-key [dir]
  (into [dir] (mapcat #(file-stamp dir %) ["config.edn" "events.edn" "llm.edn" "transcript.md"])))

(defn read-run
  "Read a run directory into {:dir :config :events :calls :errors :files ...}.

  Cached on file mtimes and sizes, so a live run being written is re-read."
  [dir]
  (let [dir (fs/canonicalize (fs/file dir))
        key (cache-key dir)]
    (or (get-in @cache [key :run])
        (let [llm (read-edn-lines (fs/file dir "llm.edn"))
              ev (read-edn-lines (fs/file dir "events.edn"))
              run {:dir (str dir)
                   :config (read-edn-file (fs/file dir "config.edn"))
                   :events (:forms ev)
                   :calls (:forms llm)
                   :errors (into [] (concat (map #(assoc % :file "events.edn") (:errors ev))
                                            (map #(assoc % :file "llm.edn") (:errors llm))))
                   :files (->> (fs/list-dir dir)
                               (filter fs/regular-file?)
                               (map #(str (fs/file-name %)))
                               (sort)
                               (vec))}]
          (swap! cache assoc key {:run run :mtime (file-stamp dir "llm.edn")})
          run))))

;;; ---------------------------------------------------------------------------
;;; named events (best effort, with the repo's own narration)

(defonce ^:private world-cache (atom {}))

(defonce narrate-enabled?
  ;; set once at startup: when false, event pages show plain event types
  ;; (no replay, no narration). An atom, not a dynamic var, because the HTTP
  ;; server answers on its own threads.
  (atom true))

(defn- manor-narrator
  "The repo's own replay/narration functions, or nil (old runs may predate the
  current event schema, and the viewer must work when they do)."
  []
  (when @narrate-enabled?
    (try
      (require 'manor.world 'manor.narrate 'manor.clock)
      {:replay (requiring-resolve 'manor.world/replay)
       :names (requiring-resolve 'manor.narrate/names)
       :describe (requiring-resolve 'manor.narrate/describe)
       :label (requiring-resolve 'manor.clock/short-label)}
      (catch Throwable _ nil))))

(defn set-narrate!
  "Turn the repo's event narration on or off, dropping cached narrators."
  [on?]
  (reset! narrate-enabled? (boolean on?))
  (reset! world-cache {}))

(defn event-names
  "Replay a run's events to get names/labels for narration.

  Returns {:describe (fn [event] text) :label (fn [round] text)}, falling back
  to a plain description when the repo's namespaces cannot be used."
  [run]
  (let [dir (:dir run)
        stamp (file-stamp dir "events.edn")
        cached (get @world-cache [dir stamp])]
    (or cached
        (let [n (manor-narrator)
              ctx (when n
                    (try
                      (let [world ((:replay n) (:events run))]
                        {:world world :names ((:names n) world) :label (:label n)})
                      (catch Throwable _ nil)))
              result {:describe (if-let [d (:describe n)]
                                  (if (:names ctx)
                                    (fn [e] (try (d e (:names ctx)) (catch Throwable _ (str (:type e)))))
                                    (fn [e] (str (:type e))))
                                  (fn [e] (str (:type e))))
                      :label (fn [round]
                               (if-let [l (:label ctx)]
                                 (try (str (l (:clock (:world ctx)) round 1))
                                      (catch Throwable _ (str "Round " round)))
                                 (str "Round " round)))
                      :world (:world ctx)}]
          (when (and dir stamp) (swap! world-cache assoc [dir stamp] result))
          result))))

;;; ---------------------------------------------------------------------------
;;; calls

(defn tool-calls
  "A call's tool calls, with arguments parsed from the JSON string."
  [call]
  (for [tc (:tool_calls (:response call))
        :let [{:keys [name arguments]} (:function tc)]]
    {:id (:id tc)
     :name name
     :raw arguments
     :args (try (json/parse-string arguments) (catch Throwable _ nil))}))

(defn reply-for
  "The engine's reply to one tool call (matched by id), or nil."
  [call tool-call-id]
  (some #(when (= tool-call-id (:tool_call_id %)) (:content %)) (:replies call)))

(defn user-messages [call]
  (filter #(= "user" (:role %)) (:messages call)))

(defn last-user-message [call] (last (user-messages call)))

(defn reminder?
  "A user message that is not a fresh view of the world: a turn reminder, a
  rejection note, and so on."
  [m]
  (and (= "user" (:role m))
       (not (str/includes? (str (:content m)) "## "))))

(defn title-line
  "The `# ...` heading of a user message (the game time, usually)."
  [text]
  (some-> (re-find #"(?m)^# +(.*)$" (str text)) second str/trim not-empty))

(defn call-time
  "The game time shown on a call's prompt, or nil."
  [call]
  (some (comp title-line :content) (reverse (vec (user-messages call)))))

(defn code-block
  "The first fenced code block of `text`, with its language."
  [text]
  (when-let [[_ lang body] (re-find #"(?s)```([a-zA-Z0-9+-]*)\n(.*?)```" (str text))]
    {:lang lang :body body}))

(defn view-json
  "The world view a call's prompt contained, parsed (nil if there is none)."
  [call]
  (when-let [m (last-user-message call)]
    (when-let [{:keys [body]} (code-block (:content m))]
      (try (json/parse-string body) (catch Throwable _ nil)))))

(defn turn-key [call] [(:round call) (:agent call)])

(defn turn-number
  "Index of this call within its turn (1-based) — how many calls the agent has
  made this round so far, including this one."
  [calls n]
  (let [call (nth calls n)
        turn (turn-key call)]
    (inc (count (filter #(= turn (turn-key %)) (take n calls))))))

(defn call-stats
  "Totals and per-agent figures for a run's calls."
  [calls]
  (let [zeros {:calls 0 :cost 0.0 :prompt-tokens 0 :completion-tokens 0 :thought-tokens 0}
        add (fn [{:keys [calls cost prompt-tokens completion-tokens thought-tokens] :as acc} call]
              (let [u (:usage call)]
                (-> acc
                    (update :calls inc)
                    (update :cost + (double (or (:cost u) 0)))
                    (update :prompt-tokens + (long (or (:prompt-tokens u) 0)))
                    (update :completion-tokens + (long (or (:completion-tokens u) 0)))
                    (update :thought-tokens + (long (or (:thought-tokens u) 0))))))]
    {:total (reduce add zeros calls)
     :by-agent (reduce-kv (fn [m agent cs] (assoc m agent (reduce add zeros cs)))
                          (sorted-map) (group-by :agent calls))
     :finish (frequencies (map #(or (:finish-reason %) "?") calls))
     :tools (frequencies (mapcat #(map :name (tool-calls %)) calls))}))

(defn engine-reply-counts
  "Error and rejection replies, and forfeited turns."
  [calls events]
  (let [texts (for [c calls, m (:replies c) :when (= "tool" (:role m))] (str (:content m)))]
    {:errors (count (filter #(str/starts-with? % "error:") texts))
     :rejections (count (filter #(str/starts-with? % "rejected:") texts))
     :forfeits (count (filter #(= :action/forfeited (:type %)) events))}))

(defn reasoning-stats
  "What a run recorded of the models' reasoning (llm.edn's :thoughts).

  A run can have reasoning tokens and no text (the provider billed them but the
  record predates capture), which is worth saying out loud on the pages."
  [calls]
  (let [thoughts (fn [c] (str (:thoughts c)))
        tokens (fn [c] (long (or (get-in c [:usage :thought-tokens]) 0)))]
    {:calls (count calls)
     :with-text (count (filter #(seq (thoughts %)) calls))
     :with-tokens (count (filter #(pos? (tokens %)) calls))
     :tokens (reduce + 0 (map tokens calls))
     :bytes (reduce + 0 (map #(count (thoughts %)) calls))
     :tokens-without-text (count (filter #(and (pos? (tokens %)) (empty? (thoughts %))) calls))}))

(defn reasoning-hits
  "Calls that carry reasoning text, in run order, optionally filtered."
  [calls {:keys [agent round query]}]
  (let [q (str/lower-case (str/trim (str query)))]
    (for [[n call] (map-indexed vector calls)
          :when (and (seq (str (:thoughts call)))
                     (or (str/blank? agent) (= agent (:agent call)))
                     (or (nil? round) (= round (:round call)))
                     (or (str/blank? q) (str/includes? (str/lower-case (str (:thoughts call))) q)))]
      (assoc call :n n))))

(defn round-rollup
  "Per-round: calls, cost and the agents called, for the run overview."
  [calls]
  (for [[round cs] (sort-by key (group-by :round calls))]
    {:round round
     :calls (count cs)
     :cost (reduce + 0.0 (keep (comp :cost :usage) cs))
     :agents (vec (sort (distinct (map :agent cs))))}))

;;; ---------------------------------------------------------------------------
;;; summaries

(defn round-count
  "The highest round number a run reached."
  [run]
  (apply max 0 (concat (map :round (:events run)) (map :round (:calls run)))))

(defn round-times
  "The game time of each round, taken from the first prompt of the round."
  [calls]
  (into (sorted-map)
        (for [[round cs] (sort-by key (group-by :round calls))]
          [round (some call-time cs)])))

(defn describe-event
  "Narrate one event, using the repo's narration when it works."
  [run event]
  ((:describe (event-names run)) event))

(defn round-label
  "A heading for a round (game time when the run has a clock)."
  [run round]
  ((:label (event-names run)) round))

(defn file-sizes [dir]
  (into (sorted-map)
        (for [f (fs/list-dir dir) :when (fs/regular-file? f)]
          [(str (fs/file-name f)) (long (fs/size f))])))

(defn summarise
  "A run plus the figures the pages show (cached with the run)."
  [root rel run]
  (let [stats (call-stats (:calls run))
        sizes (file-sizes (:dir run))]
    (assoc run
           :rel rel
           :name (dir-name root rel)
           :rounds (round-count run)
           :calls-count (count (:calls run))
           :stats stats
           :replies (engine-reply-counts (:calls run) (:events run))
           :reasoning (reasoning-stats (:calls run))
           :round-rollup (round-rollup (:calls run))
           :round-times (round-times (:calls run))
           :file-sizes sizes
           :bytes (long (get sizes "llm.edn" 0)))))

;;; ---------------------------------------------------------------------------
;;; search

(defn- field-texts
  "The searchable pieces of a call: [field-name text]."
  [call]
  (concat
   [["thoughts" (:thoughts call)]
    ["text" (:content (:response call))]]
   (for [{:keys [name raw]} (tool-calls call)] ["tool" (str name " " raw)])
   (for [m (:replies call)] ["reply" (:content m)])
   (for [m (:messages call)]
     [(case (:role m) "user" "prompt" "assistant" "assistant" "system" "system" "tool") (:content m)])))

(defn- snippet
  "The line of `text` around the first match of `query`."
  [text query]
  (let [flat (str/replace (str text) #"\s+" " ")
        i (str/index-of (str/lower-case flat) (str/lower-case query))
        start (max 0 (- i 90))]
    (str (when (pos? start) "…") (subs flat start (min (count flat) (+ i 180))) "…")))

(defn search-calls
  "Calls whose text contains `query` (case-insensitive).

  `in` limits the fields searched: :prompts excludes the world view (the big
  one), :thoughts, :replies and :tools each search only that field."
  [calls {:keys [query in]}]
  (when-not (str/blank? query)
    (let [q (str/lower-case (str/trim query))
          allowed (case in
                    :thoughts #{"thoughts"}
                    :replies #{"reply"}
                    :tools #{"tool"}
                    :prompts #{"prompt" "assistant" "system"}
                    :all nil
                    #{"thoughts" "text" "tool" "reply"})]
      (for [[i call] (map-indexed vector calls)
            [field text] (field-texts call)
            :when (and text
                       (or (nil? allowed) (contains? allowed field))
                       (str/includes? (str/lower-case (str text)) q))]
        {:n i :round (:round call) :agent (:agent call) :field field
         :snippet (snippet text query)}))))

(defn search-runs
  "Search every run in `runs` (summarised run maps) for `query`."
  [runs opts]
  (for [{:keys [rel name calls]} runs
        hit (search-calls calls opts)]
    (assoc hit :dir rel :run name)))
