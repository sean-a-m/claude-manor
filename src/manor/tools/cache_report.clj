(ns manor.tools.cache-report
  "How well a run's prompts hit the provider's prompt cache, and what that
  costs. Reads <run-dir>/llm.edn only; never calls a model.

    clojure -M:cache report <run-dir> [--top N]
    clojure -M:cache simulate <run-dir> [--order system-first|tools-first] [--tier off-peak|peak] [--top N]
    clojure -M:cache compare <run-dir-a> <run-dir-b> [--order ...] [--tier ...]

  report: tokens and cost (uncached input, cached input, output), by price
  tier, by kind of call and by agent, and the biggest misses with where each
  prompt first differs from an earlier one. Cached tokens are the log's
  :usage :cached-tokens or, for older runs, inferred from :cost and the
  prices in resources/pricing.edn.

  simulate: an offline model of the cache. Each request is rendered as
  segments (the system message, each tool, each other message) and its
  cached prefix predicted as the longest prefix it shares with any earlier
  request, in whole blocks of tokens. It's a model, not the provider: use it
  to compare prompt layouts on free mock runs, and trust it as far as its
  error against real runs (which it prints) allows.

  compare: two runs simulated side by side, by kind of call and per round."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jsonista.core :as json]
            [manor.io.store :as store])
  (:import (java.time DayOfWeek Instant ZoneOffset)
           (java.util Arrays)))

;; ---------------------------------------------------------------------------
;; Prices

(def pricing
  "Prices and cache facts by model id, from resources/pricing.edn."
  (delay (edn/read-string (slurp (io/resource "pricing.edn")))))

(defn model-pricing [model] (get @pricing model))

(def ^:private no-prices {:input 0 :cache-read 0 :output 0})

(defn peak?
  "Whether `instant` falls in the model's peak hours."
  [{:keys [peak-hours]} ^Instant instant]
  (let [t (.atZone instant ZoneOffset/UTC)
        day (.getValue ^DayOfWeek (.getDayOfWeek t))
        hour (.getHour t)]
    (boolean (and (some #{day} (:weekdays peak-hours))
                  (some (fn [[from to]] (and (<= from hour) (< hour to))) (:utc peak-hours))))))

(defn tier-at
  "The price tier at ISO-8601 instant `at`, or nil without one."
  [mp at]
  (when (and mp at)
    (if (peak? mp (Instant/parse at)) :peak :off-peak)))

(defn costs
  "What a call's tokens cost at its :tier: :uncached-cost and :cached-cost
  for input, :output-cost and the :thinking-cost part of it."
  [{:keys [model tier uncached-tokens cached-tokens completion-tokens thought-tokens]}]
  (let [{:keys [input cache-read output]} (or (get-in (model-pricing model) [:tiers tier]) no-prices)]
    {:uncached-cost (* input uncached-tokens)
     :cached-cost (* cache-read cached-tokens)
     :output-cost (* output completion-tokens)
     :thinking-cost (* output thought-tokens)}))

(defn total-cost [call]
  (let [{:keys [uncached-cost cached-cost output-cost]} (costs call)]
    (+ uncached-cost cached-cost output-cost)))

;; ---------------------------------------------------------------------------
;; Cached tokens and tier from :cost

(def inference-unit
  "The provider counts cached tokens in multiples of this; an inferred count
  that isn't one is the wrong tier."
  64)

(defn- fit
  "The uncached and cached tokens that `cost` implies at `prices`, and how
  far the uncached count was from a whole number (:off-by)."
  [{:keys [input cache-read output]} {:keys [prompt-tokens completion-tokens cost]}]
  (let [u (/ (- cost (* output completion-tokens) (* cache-read prompt-tokens)) (- input cache-read))
        uncached (Math/round (double u))]
    {:uncached-tokens uncached
     :cached-tokens (- prompt-tokens uncached)
     :off-by (Math/abs (- u uncached))}))

(defn- plausible? [{:keys [cached-tokens off-by]} prompt-tokens]
  (and (< off-by 0.1) (<= 0 cached-tokens prompt-tokens) (zero? (mod cached-tokens inference-unit))))

(defn infer-cached
  "The price tier and cached tokens that explain a call's :cost: the tier
  whose fit is a whole, plausible count, preferring whole cache blocks, then
  `prev-tier`, then the closer fit. Nil if no tier explains it."
  [mp prev-tier usage]
  (let [block (:cache-block mp inference-unit)]
    (->> (:tiers mp)
         (keep (fn [[tier prices]]
                 (let [f (fit prices usage)]
                   (when (plausible? f (:prompt-tokens usage)) (assoc f :tier tier)))))
         (sort-by (juxt #(if (zero? (mod (:cached-tokens %) block)) 0 1)
                        #(if (= prev-tier (:tier %)) 0 1)
                        :off-by))
         first)))

(defn- closest-tier
  "The tier whose prices, with the logged cached tokens, come closest to :cost."
  [mp usage]
  (when (pos? (:cost usage))
    (->> (:tiers mp)
         (sort-by (fn [[_ prices]]
                    (let [{:keys [input cache-read output]} prices
                          {:keys [prompt-tokens cached-tokens completion-tokens cost]} usage]
                      (Math/abs (- cost (+ (* input (- prompt-tokens cached-tokens))
                                           (* cache-read cached-tokens)
                                           (* output completion-tokens)))))))
         ffirst)))

;; ---------------------------------------------------------------------------
;; Calls

(defn kind
  "What sort of call an llm.edn entry is: :turn-first (an agent turn's first
  call, just the system and user messages), :turn-follow-up, :conversation (a
  speaking turn), :judge, :adjudicator (scenario adjudication and the
  steward) or :gm."
  [{:keys [agent conversation messages]}]
  (cond (= agent "judge") :judge
        (or (str/starts-with? (str agent) "adjudicator/") (= agent "steward")) :adjudicator
        (= agent "gm") :gm
        conversation :conversation
        (= ["system" "user"] (mapv :role messages)) :turn-first
        :else :turn-follow-up))

(def kinds [:turn-first :turn-follow-up :conversation :judge :adjudicator :gm])

(defn- usage-of [entry]
  (let [u (:usage entry)]
    {:prompt-tokens (or (:prompt-tokens u) 0)
     :completion-tokens (or (:completion-tokens u) 0)
     :thought-tokens (or (:thought-tokens u) 0)
     :cost (or (:cost u) 0)
     :cached-tokens (:cached-tokens u)
     :cache-write-tokens (:cache-write-tokens u)}))

(defn- account-one
  "One entry as a call: its usage, :cached-tokens with their :cached-source
  (:logged, :inferred or :unknown) and its :tier with :tier-source."
  [prev-tier i entry]
  (let [mp (model-pricing (:model entry))
        usage (usage-of entry)
        logged-tier (tier-at mp (:at entry))
        inferred (when (and mp (nil? (:cached-tokens usage)) (pos? (:cost usage)))
                   (infer-cached mp (or logged-tier prev-tier) usage))
        cached (cond (:cached-tokens usage) {:cached-tokens (:cached-tokens usage) :cached-source :logged}
                     inferred {:cached-tokens (:cached-tokens inferred) :cached-source :inferred}
                     :else {:cached-tokens 0 :cached-source :unknown})
        [tier tier-source] (cond logged-tier [logged-tier :logged]
                                 inferred [(:tier inferred) :inferred]
                                 (and mp (:cached-tokens usage)) [(closest-tier mp usage) :inferred]
                                 :else [nil nil])]
    (merge (select-keys entry [:agent :round :model])
           usage
           cached
           {:i i
            :kind (kind entry)
            :uncached-tokens (- (:prompt-tokens usage) (:cached-tokens cached))
            :tier (or tier prev-tier)
            :tier-source (if tier tier-source (when prev-tier :inferred))
            :entry entry})))

(defn account
  "A run's llm.edn entries as calls, in order (see `account-one`). A call
  whose tier can't be told from its time or cost takes the one before."
  [entries]
  (loop [[e & more] entries, i 0, prev nil, acc []]
    (if-not e
      acc
      (let [call (account-one prev i e)]
        (recur more (inc i) (:tier call) (conj acc call))))))

(defn load-calls [run-dir] (account (store/read-llm run-dir)))

;; ---------------------------------------------------------------------------
;; Requests as segments

(defn- message-segment
  "A message as rendered text: role, content and, for an assistant, its
  tool calls as JSON. :content-start and :calls-start are offsets into it."
  [index {:keys [role content tool_calls]}]
  (let [head (str role "\n")
        body (str content)
        calls (when (seq tool_calls) (str "\n" (json/write-value-as-string tool_calls)))]
    (cond-> {:type (if (= "system" role) :system :message)
             :index index :role role
             :text (str head body calls)
             :content-start (count head)}
      calls (assoc :calls-start (+ (count head) (count body))))))

(defn- tool-segments
  "The request's tools in the order sent. A tool is all or nothing: the same
  digest when the log has :tool-digests, else the same name with
  `tool-chars` as its size."
  [entry tool-chars]
  (if-let [digests (seq (:tool-digests entry))]
    (map-indexed (fn [i {:keys [name hash chars]}] {:type :tool :index i :name name :key hash :chars chars}) digests)
    (map-indexed (fn [i n] {:type :tool :index i :name n :key n :chars tool-chars}) (:tools entry))))

(def orders
  "How the provider may lay out a request before caching it."
  #{:system-first :tools-first})

(defn segments
  "The request of `entry` as segments in `order`: :system-first is the
  system message, the tools, then the other messages; :tools-first puts the
  tools before the system message."
  [entry {:keys [order tool-chars]}]
  (let [messages (map-indexed message-segment (:messages entry))
        [system others] (split-with #(= :system (:type %)) messages)
        tools (tool-segments entry tool-chars)]
    (vec (if (= order :tools-first)
           (concat tools system others)
           (concat system tools others)))))

(defn seg-chars [seg] (or (:chars seg) (count (:text seg))))

(defn- same-segment? [x y]
  (and (= (:type x) (:type y))
       (if (= :tool (:type x)) (= (:key x) (:key y)) (= (:text x) (:text y)))))

(defn- text-prefix-length [^String a ^String b]
  (let [n (Arrays/mismatch (.toCharArray a) (.toCharArray b))]
    (if (neg? n) (min (count a) (count b)) n)))

(defn common-prefix
  "How much of request `a` (segments) begins the same as request `b`: whole
  equal segments, then the matching characters of the first text segment
  that differs (a tool that differs counts for nothing). Returns :chars, and
  where they part: :at, the index in `a` of that segment, and :offset into
  it. :at is nil when all of `a` begins `b`."
  [a b]
  (loop [k 0, chars 0]
    (let [x (get a k) y (get b k)]
      (cond (nil? x) {:chars chars :at nil}
            (nil? y) {:chars chars :at k :offset 0}
            (same-segment? x y) (recur (inc k) (+ chars (seg-chars x)))
            :else (let [offset (if (and (= (:type x) (:type y)) (:text x))
                                 (text-prefix-length (:text x) (:text y))
                                 0)]
                    {:chars (+ chars offset) :at k :offset offset})))))

(defn best-prefix
  "The longest `common-prefix` of request `i` in `requests` with any earlier
  one, with its :with (the earlier index), or nil for the first."
  [requests i]
  (let [a (requests i)]
    (reduce (fn [best j]
              (let [p (assoc (common-prefix a (requests j)) :with j)]
                (if (or (nil? best) (> (:chars p) (:chars best))) p best)))
            nil
            (range i))))

;; ---------------------------------------------------------------------------
;; Where a prompt differs

(def ^:private heading-line #"^#{1,6} ")

(defn- clip [s n] (if (> (count s) n) (str (subs s 0 n) "…") s))

(defn heading-at
  "The nearest markdown heading line at or before `offset` in `text`, or nil."
  [text offset]
  (let [line-end (or (str/index-of text "\n" offset) (count text))]
    (some->> (str/split-lines (subs text 0 line-end))
             (filter #(re-find heading-line %))
             last
             (#(clip % 50)))))

(defn json-key-at
  "The top-level key of the JSON object starting at `start` in `text` whose
  entry holds `offset`, or nil. A small scanner, so it works on pretty or
  compact JSON, cut off anywhere; when `offset` falls inside a key, it reads
  that key to its end."
  [text start offset]
  (let [n (count text)]
    (loop [i start, depth 0, in-string? false, escape? false, key-start nil, expect-key? false, key nil]
      (if (or (>= i n) (and (>= i offset) (nil? key-start)))
        key
        (let [c (.charAt ^String text i)
              next-i (inc i)]
          (cond
            escape? (recur next-i depth true false key-start expect-key? key)
            in-string? (cond (= c \\) (recur next-i depth true true key-start expect-key? key)
                             (= c \") (recur next-i depth false false nil false
                                             (if key-start (subs text key-start i) key))
                             :else (recur next-i depth true false key-start expect-key? key))
            (= c \") (recur next-i depth true false (when (and expect-key? (= 1 depth)) next-i) expect-key? key)
            (#{\{ \[} c) (recur next-i (inc depth) false false nil (and (= c \{) (zero? depth)) key)
            (#{\} \]} c) (recur next-i (dec depth) false false nil false key)
            (and (= c \,) (= 1 depth)) (recur next-i depth false false nil true key)
            :else (recur next-i depth false false nil expect-key? key)))))))

(defn- json-start
  "Where the JSON holding `offset` in `seg` begins: just after an open
  ```json fence, or the content if it is JSON; nil if `offset` isn't in JSON."
  [{:keys [text content-start]} offset]
  (let [fence (when (pos? offset) (str/last-index-of text "```json" (dec offset)))
        fence-end (when fence (str/index-of text "```" (+ fence 7)))]
    (cond (and fence (or (nil? fence-end) (>= fence-end offset))) (+ fence 7)
          (str/starts-with? (str/triml (subs text content-start)) "{") content-start)))

(defn- text-label
  "Where in text segment `seg` the characters part at `offset`: the
  message, the nearest heading, and inside JSON the top-level key."
  [{:keys [type index role text calls-start] :as seg} offset]
  (let [where (if (= :system type) "system" (str "messages[" index "] " role))]
    (if (and calls-start (>= offset calls-start))
      (str where " > tool_calls")
      (let [h (heading-at text offset)
            start (json-start seg offset)
            k (when start (json-key-at text start offset))]
        (str where (when h (str " > " h)) (when k (str " > json " k)))))))

(defn- tool-label
  "Which tool request `a` and request `b` part at: one added, removed,
  changed (same name, different digest) or moved."
  [a b x y]
  (let [names (fn [segs] (set (keep #(when (= :tool (:type %)) (:name %)) segs)))
        tx (when (= :tool (:type x)) x)
        ty (when (= :tool (:type y)) y)
        label (fn [{:keys [index name]} what] (str "tools[" index "] " name " (" what ")"))]
    (cond (and tx ty (= (:name tx) (:name ty))) (label tx "changed")
          (and tx (not ((names b) (:name tx)))) (label tx "added")
          (and ty (not ((names a) (:name ty)))) (label ty "removed")
          tx (label tx "moved")
          :else (label ty "moved"))))

(defn divergence
  "Where request `a` first differs from request `b` (both segments)."
  [a b]
  (let [{:keys [at offset]} (common-prefix a b)
        x (when at (get a at))
        y (when at (get b at))]
    (cond (nil? at) "nothing (it begins the earlier call)"
          (or (= :tool (:type x)) (= :tool (:type y))) (tool-label a b x y)
          (nil? y) (str (text-label x 0) " (new)")
          :else (text-label x offset))))

(defn- reference-index
  "The call to compare call `i` with: the same agent's previous call of the
  same kind, else the earlier call it shares the longest prefix with."
  [calls requests i]
  (let [{:keys [agent kind]} (calls i)]
    (or (last (filter #(let [c (calls %)] (and (= agent (:agent c)) (= kind (:kind c)))) (range i)))
        (:with (best-prefix requests i)))))

;; ---------------------------------------------------------------------------
;; Simulation

(defn calibration
  "Characters to tokens for a run: :tool-chars, fitted by least squares of
  prompt tokens on rendered message characters and tool count over the calls
  without :tool-digests (or the model's default when there are none, or
  their tool counts don't differ), and :tokens-per-char, all prompt tokens
  over all rendered characters (or the model's default for a run without
  usage). Only calls with usage count."
  [entries mp]
  (let [used (filter #(pos? (get-in % [:usage :prompt-tokens] 0)) entries)
        rows (for [e used :when (empty? (:tool-digests e))]
               [(reduce + (map seg-chars (segments e {:tool-chars 0}))) (count (:tools e)) (get-in e [:usage :prompt-tokens])])
        sum (fn [f xs] (reduce + (map f xs)))
        sxx (sum (fn [[x]] (* x x)) rows) sxt (sum (fn [[x t]] (* x t)) rows) stt (sum (fn [[_ t]] (* t t)) rows)
        sxy (sum (fn [[x _ y]] (* x y)) rows) sty (sum (fn [[_ t y]] (* t y)) rows)
        det (- (* sxx stt) (* sxt sxt))
        a (when (pos? det) (/ (- (* sxy stt) (* sty sxt)) det))
        b (when (pos? det) (/ (- (* sxx sty) (* sxt sxy)) det))
        fitted? (boolean (and a b (pos? a) (pos? b) (> (count (set (map second rows))) 1)))
        tool-chars (if fitted? (Math/round (double (/ b a))) (:tool-chars mp))
        chars (sum #(reduce + (map seg-chars (segments % {:tool-chars tool-chars}))) used)]
    {:tool-chars tool-chars
     :tool-chars-fitted? fitted?
     :tokens-per-char (if (pos? chars)
                        (double (/ (sum #(get-in % [:usage :prompt-tokens]) used) chars))
                        (:tokens-per-char mp))
     :tokens-per-char-fitted? (pos? chars)}))

(defn render-requests
  "Every call's request as segments in `order`, tools without digests sized
  by `cal` (see `calibration`)."
  [calls order cal]
  (mapv #(segments (:entry %) {:order order :tool-chars (:tool-chars cal)}) calls))

(defn- run-pricing [calls] (model-pricing (:model (first calls))))

(defn- predict
  "Call `call` with its predicted :cached-tokens (the shared prefix in
  tokens, floored to a cache block, at most the prompt) and the real ones as
  :actual-cached-tokens when known. Without usage, :prompt-tokens is
  estimated from the characters (:prompt-estimated? true)."
  [call requests i {:keys [tokens-per-char]} block]
  (let [segs (requests i)
        chars (reduce + (map seg-chars segs))
        real (:prompt-tokens call)
        tpc (if (pos? real) (/ real (double chars)) tokens-per-char)
        prompt (if (pos? real) real (Math/round (* chars tpc)))
        shared (get (best-prefix requests i) :chars 0)
        cached (min prompt (* block (quot (long (* shared tpc)) block)))]
    (assoc call
           :prompt-tokens prompt
           :prompt-estimated? (not (pos? real))
           :cached-tokens cached
           :uncached-tokens (- prompt cached)
           :actual-cached-tokens (when (not= :unknown (:cached-source call)) (:cached-tokens call)))))

(defn simulate
  "Predicted cache hits for `calls` (from `account`), each priced at `tier`
  (or its own when nil). Returns {:calls :calibration :requests}."
  [calls {:keys [order tier] :or {order :system-first}}]
  (let [mp (run-pricing calls)
        cal (calibration (map :entry calls) mp)
        reqs (render-requests calls order cal)
        block (:cache-block mp 128)]
    {:calibration cal
     :requests reqs
     :calls (mapv (fn [i call] (cond-> (predict call reqs i cal block) tier (assoc :tier tier)))
                  (range) calls)}))

(defn errors
  "How far predicted cached tokens are from the real ones, over calls where
  those are known: :n, :mae and :bias in tokens, and :within-block (calls
  off by at most one cache block) and :exact."
  [calls block]
  (let [diffs (keep #(when-let [real (:actual-cached-tokens %)] (- (:cached-tokens %) real)) calls)
        n (count diffs)]
    (when (pos? n)
      {:n n
       :mae (/ (reduce + (map abs diffs)) (double n))
       :bias (/ (reduce + diffs) (double n))
       :within-block (count (filter #(<= (abs %) block) diffs))
       :exact (count (filter zero? diffs))})))

;; ---------------------------------------------------------------------------
;; Tables

(defn- pad [s w left?]
  (let [fill (apply str (repeat (- w (count s)) \space))]
    (if left? (str s fill) (str fill s))))

(defn print-table
  "Print rows of strings under `header`; columns in `left` (indexes) are
  left-aligned, the rest right-aligned."
  ([header rows] (print-table header rows #{0}))
  ([header rows left]
   (let [all (cons header rows)
         widths (apply map (fn [& col] (apply max (map count col))) all)]
     (doseq [row all]
       (println (str/trimr (str/join "  " (map-indexed (fn [i [w cell]] (pad cell w (left i)))
                                                       (map vector widths row)))))))))

(defn- n [x] (format "%,d" (long x)))
(defn- usd [x] (format "$%.4f" (double x)))
(defn- usd-fine [x] (format "$%.6f" (double x)))
(defn- pct [part whole] (if (pos? whole) (format "%.1f%%" (* 100.0 (/ part whole))) "-"))

(defn- sum-of [k calls] (reduce + (map k calls)))

(defn- group-rows
  "Rows of calls, prompt tokens, hit % and uncached cost per group of `calls`
  by `f`, plus `extra` columns (fn [calls] -> strings)."
  [calls f order extra]
  (for [[g cs] (sort-by (comp order key) (group-by f calls))]
    (into [(name g) (n (count cs)) (n (sum-of :prompt-tokens cs))
           (pct (sum-of :cached-tokens cs) (sum-of :prompt-tokens cs))
           (usd (sum-of (comp :uncached-cost costs) cs))]
          (extra cs))))

(defn- kind-order [k] (.indexOf ^java.util.List kinds k))

(defn- print-groups
  "Tables by kind of call and by agent (most uncached cost first)."
  [calls extra-header extra]
  (let [header (into ["calls" "prompt" "hit %" "uncached $"] extra-header)]
    (println "\nBy kind of call")
    (print-table (cons "kind" header) (group-rows calls :kind kind-order extra))
    (println "\nBy agent")
    (print-table (cons "agent" header)
                 (group-rows calls :agent
                             (into {} (map (fn [[a cs]] [a (- (sum-of (comp :uncached-cost costs) cs))])
                                           (group-by :agent calls)))
                             extra))))

(defn- print-totals [calls label]
  (let [cs (map costs calls)
        prompt (sum-of :prompt-tokens calls)
        cached (sum-of :cached-tokens calls)
        writes (keep :cache-write-tokens calls)]
    (println (str "\nTokens" label))
    (print-table ["" "tokens" ""]
                 (cond-> [["prompt" (n prompt) ""]
                          ["  cached" (n cached) (pct cached prompt)]
                          ["  uncached" (n (sum-of :uncached-tokens calls)) ""]
                          ["completion" (n (sum-of :completion-tokens calls)) ""]
                          ["  thinking" (n (sum-of :thought-tokens calls)) ""]]
                   (seq writes) (conj ["cache writes" (n (reduce + writes)) ""])))
    (println (str "\nCost" label))
    (print-table ["" "USD"]
                 [["uncached input" (usd (sum-of :uncached-cost cs))]
                  ["cached input" (usd (sum-of :cached-cost cs))]
                  ["output" (usd (sum-of :output-cost cs))]
                  ["  thinking" (usd (sum-of :thinking-cost cs))]
                  ["total" (usd (reduce + (map total-cost calls)))]])))

(defn- print-tiers [calls]
  (println "\nBy price tier")
  (print-table ["tier" "calls" "cost" "from"]
               (for [[tier cs] (sort-by (comp str key) (group-by :tier calls))]
                 [(if tier (name tier) "?") (n (count cs)) (usd (reduce + (map total-cost cs)))
                  (str/join ", " (map name (distinct (keep :tier-source cs))))]))
  (println "All off-peak it would have cost"
           (usd (reduce + (map #(total-cost (assoc % :tier :off-peak)) calls)))))

(defn- print-misses
  "The `top` calls with the most uncached input, each with where its prompt
  first differs from the call it's compared with (`reference-index`)."
  [calls requests top]
  (println (str "\nTop " top " misses (most uncached input; \"differs at\" is against the call in \"vs\")"))
  (print-table ["#" "round" "agent" "kind" "prompt" "uncached" "hit %" "vs" "differs at"]
               (for [{:keys [i round agent kind prompt-tokens uncached-tokens cached-tokens]}
                     (take top (sort-by (comp - :uncached-tokens) calls))
                     :let [ref (reference-index calls requests i)]]
                 [(str i) (str round) agent (name kind) (n prompt-tokens) (n uncached-tokens)
                  (pct cached-tokens prompt-tokens) (if ref (str "#" ref) "-")
                  (if ref (divergence (requests i) (requests ref)) "(first call)")])
               #{2 3 8}))

(defn- sources-line [calls]
  (str/join ", " (for [[s cs] (sort-by (comp str key) (group-by :cached-source calls))]
                   (str (count cs) " " (name s)))))

;; ---------------------------------------------------------------------------
;; Commands

(defn report
  "Print the cost and cache report of the run in `run-dir`."
  [run-dir {:keys [top order] :or {top 10 order :system-first}}]
  (let [calls (load-calls run-dir)
        requests (render-requests calls order (calibration (map :entry calls) (run-pricing calls)))]
    (println "Cache report:" (str run-dir))
    (println (count calls) "calls; models" (str/join ", " (distinct (map :model calls))))
    (println "Cached tokens:" (sources-line calls)
             "(inferred: from :cost and resources/pricing.edn; unknown: counted as 0)")
    (println "Logged cost:" (usd (sum-of :cost calls)))
    (print-totals calls "")
    (print-tiers calls)
    (print-groups calls [] (constantly []))
    (print-misses calls requests top)))

(defn- print-errors [{:keys [n mae bias within-block exact]} block]
  (println (format "\nAgainst real cached tokens (%d calls): mean absolute error %.0f tokens, bias %+.0f; %d within one block (%d), %d exact"
                   n mae bias within-block block exact)))

(defn simulate-report
  "Print the simulated cache report of the run in `run-dir`."
  [run-dir {:keys [order tier top] :or {order :system-first tier :off-peak top 10}}]
  (let [calls (load-calls run-dir)
        {:keys [calibration requests] :as sim} (simulate calls {:order order :tier tier})
        predicted (:calls sim)
        block (:cache-block (run-pricing calls) 128)
        mae (fn [cs] [(if-let [e (errors cs block)] (format "%.0f" (:mae e)) "-")
                      (let [real (keep :actual-cached-tokens cs)]
                        (if (seq real) (pct (reduce + real) (sum-of :prompt-tokens cs)) "-"))])]
    (println "Simulated cache:" (str run-dir))
    (println (str (count calls) " calls; order " (name order) "; priced " (name tier)))
    (println (format "Chars to tokens: per call from its usage, else %.4f tokens per char (%s); %d chars per tool without digests (%s)"
                     (:tokens-per-char calibration)
                     (if (:tokens-per-char-fitted? calibration) "this run" "default")
                     (:tool-chars calibration)
                     (if (:tool-chars-fitted? calibration) "fitted on this run" "default")))
    (when (some :prompt-estimated? predicted)
      (println "Prompt tokens are estimated from characters for" (count (filter :prompt-estimated? predicted)) "calls"))
    (print-totals predicted " (predicted)")
    (print-groups predicted ["MAE tok" "real hit %"] mae)
    (when-let [e (errors predicted block)] (print-errors e block))
    (print-misses predicted requests top)))

(defn- per-round [calls]
  (let [rounds (count (distinct (map :round calls)))]
    (if (pos? rounds) (/ (reduce + (map total-cost calls)) rounds) 0)))

(defn compare-runs
  "Print two runs' simulated cache hits and costs side by side."
  [dir-a dir-b {:keys [order tier] :or {order :system-first tier :off-peak}}]
  (let [sim #(:calls (simulate (load-calls %) {:order order :tier tier}))
        a (sim dir-a) b (sim dir-b)
        by-kind (fn [cs] (group-by :kind cs))
        ka (by-kind a) kb (by-kind b)
        cost-per-call (fn [cs] (if (seq cs) (usd-fine (/ (reduce + (map total-cost cs)) (count cs))) "-"))
        hit (fn [cs] (pct (sum-of :cached-tokens cs) (sum-of :prompt-tokens cs)))]
    (println "Simulated cache, A:" (str dir-a))
    (println "                  B:" (str dir-b))
    (println (str "Order " (name order) "; priced " (name tier)))
    (println)
    (print-table ["kind" "calls A" "calls B" "hit % A" "hit % B" "$/call A" "$/call B"]
                 (concat
                  (for [k kinds :let [ca (ka k) cb (kb k)] :when (or ca cb)]
                    [(name k) (n (count ca)) (n (count cb)) (hit ca) (hit cb) (cost-per-call ca) (cost-per-call cb)])
                  [["all" (n (count a)) (n (count b)) (hit a) (hit b) (cost-per-call a) (cost-per-call b)]]))
    (println)
    (print-table ["" "A" "B"]
                 [["rounds" (str (count (distinct (map :round a)))) (str (count (distinct (map :round b))))]
                  ["cost" (usd (reduce + (map total-cost a))) (usd (reduce + (map total-cost b)))]
                  ["cost per round" (usd-fine (per-round a)) (usd-fine (per-round b))]])))

(defn- parse-args
  "Positional arguments and --top/--order/--tier options."
  [args]
  (loop [[a & more] args, acc {:dirs [] :opts {}}]
    (case a
      nil acc
      "--top" (recur (rest more) (assoc-in acc [:opts :top] (parse-long (first more))))
      "--order" (recur (rest more) (assoc-in acc [:opts :order] (keyword (first more))))
      "--tier" (recur (rest more) (assoc-in acc [:opts :tier] (keyword (first more))))
      (recur more (update acc :dirs conj a)))))

(def ^:private usage
  "usage: clojure -M:cache report <run-dir> [--top N]
       clojure -M:cache simulate <run-dir> [--order system-first|tools-first] [--tier off-peak|peak] [--top N]
       clojure -M:cache compare <run-dir-a> <run-dir-b> [--order ...] [--tier ...]")

(defn -main [& args]
  (let [[command & more] args
        {:keys [dirs opts]} (parse-args more)
        ok? (and (every? #(.exists (io/file % "llm.edn")) dirs)
                 (or (nil? (:order opts)) (orders (:order opts)))
                 (or (nil? (:tier opts)) (#{:off-peak :peak} (:tier opts))))]
    (cond
      (and ok? (= command "report") (= 1 (count dirs))) (report (first dirs) opts)
      (and ok? (= command "simulate") (= 1 (count dirs))) (simulate-report (first dirs) opts)
      (and ok? (= command "compare") (= 2 (count dirs))) (compare-runs (first dirs) (second dirs) opts)
      :else (do (println usage) (System/exit 1)))))
