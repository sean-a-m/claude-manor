(ns manor.viewer.render
  "HTML for the transcript viewer: a small markdown-ish renderer, a layout, and
  one function per page. Pages take plain data (already read by
  `manor.viewer.data`) and return hiccup; nothing here reads files."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [manor.viewer.data :as data]
            [manor.viewer.html :refer [html raw]]))

;;; ---------------------------------------------------------------------------
;;; helpers

(defn url-encode [s]
  (java.net.URLEncoder/encode (str s) "UTF-8"))

(defn url
  "Build \"path?a=1&b=2\", dropping nil and blank values."
  [path params]
  (let [qs (->> params
                (remove (fn [[_ v]] (or (nil? v) (and (string? v) (str/blank? v)))))
                (map (fn [[k v]] (str (name k) "=" (url-encode (if (true? v) "1" (str v))))))
                (str/join "&"))]
    (if (str/blank? qs) path (str path "?" qs))))

(defn money [x] (format "$%.4f" (double (or x 0))))
(defn n-int [x] (str (long (or x 0))))

(defn short-bytes [n]
  (let [n (long (or n 0))]
    (cond (< n 1024) (str n " B")
          (< n (* 1024 1024)) (format "%.1f kB" (/ n 1024.0))
          :else (format "%.2f MB" (/ n (* 1024.0 1024.0))))))

(defn- trunc [s n]
  (let [s (str s)]
    (if (> (count s) n) (str (subs s 0 n) "…") s)))

(defn chip
  ([text] (chip nil text))
  ([class text]
   (when (and text (not (str/blank? (str text))))
     [:span {:class (str "chip " (or class ""))} (str text)])))

(defn reply-class
  "CSS class describing the engine's reply to a tool call."
  [text]
  (let [t (str/trim (str text))]
    (cond (str/starts-with? t "error:") "err"
          (str/starts-with? t "rejected:") "rej"
          (= t "ok") "ok"
          (str/blank? t) "muted"
          :else "note")))

;;; ---------------------------------------------------------------------------
;;; markdown-ish rendering

(defn- inline
  "A line's text as hiccup: `code` and **bold** become elements, everything
  else stays text (hiccup escapes it)."
  [s]
  (let [s (str s)
        m (re-matcher #"`([^`]*)`|\*\*([^*]+)\*\*" s)]
    (loop [pos 0 out []]
      (if (.find m)
        (let [out (-> (conj out (subs s pos (.start m)))
                      (cond-> (.group m 1) (conj [:code (.group m 1)]))
                      (cond-> (.group m 2) (conj [:strong (.group m 2)])))]
          (recur (.end m) out))
        (seq (conj out (subs s pos)))))))

(defn- json-pre [body]
  (let [parsed (try (json/parse-string body) (catch Throwable _ nil))
        text (if parsed (json/generate-string parsed {:pretty true}) (str body))]
    [:details {:class "blob"}
     [:summary (str "json · " (short-bytes (count (str body))) " · " (count (str/split-lines text)) " lines")]
     [:pre.code text]]))

(defn- plain-line
  "One line inside a paragraph; a leading [08:00] becomes a time chip."
  [line]
  (if-let [[_ t rest] (re-matches #"\[(\d{1,2}:\d{2})\]\s*(.*)" (str/trim line))]
    [:div.line (chip "time" t) " " (inline rest)]
    [:div.line (inline line)]))

(defn- table-rows
  "Header row, separator row, then body rows — each starting with |."
  [lines]
  (let [cells (fn [l] (map str/trim (str/split (str/replace (str/trim l) #"^\||\|$" "") #"\|")))
        [head _ & body] lines]
    [:table.md
     [:thead [:tr (for [c (cells head)] [:th (inline c)])]]
     [:tbody (for [r body] [:tr (for [c (cells r)] [:td (inline c)])])]]))

(defn md->hiccup
  "Render the markdown-ish text manor writes (prompts, transcript.md).
  Everything is escaped; code fences, headings, bullets, tables and inline
  code/bold are interpreted, nothing else. Returns a seq of hiccup forms, so
  hiccup splices them into whichever element they are placed in."
  [text]
  (let [lines (str/split-lines (str text))]
    (seq
     (loop [lines lines out []]
      (if-not (seq lines)
        out
        (let [line (first lines)
              trimmed (str/trim line)]
          (cond
            (str/starts-with? trimmed "```")
            (let [[body rest-lines] (split-with #(not (str/starts-with? (str/trim %) "```")) (rest lines))
                  lang (str/trim (subs trimmed 3))
                  body (str/join "\n" body)]
              (recur (rest rest-lines)
                     (conj out (if (= lang "json") (json-pre body) [:pre.code body]))))

            (re-matches #"#{1,6} .*" trimmed)
            (let [level (count (re-find #"^#+" trimmed))
                  txt (str/trim (subs trimmed level))]
              (recur (rest lines) (conj out [(keyword (str "h" (min 6 (+ level 1)))) (inline txt)])))

            (and (str/starts-with? trimmed "|") (second lines)
                 (re-matches #"\|[\s:|-]+\|" (str/trim (second lines))))
            (let [[tbl rest-lines] (split-with #(str/starts-with? (str/trim %) "|") lines)]
              (recur rest-lines (conj out (table-rows tbl))))

            (re-matches #"[-*] +.*" trimmed)
            (let [[items rest-lines] (split-with #(re-matches #"[-*] +.*" (str/trim %)) lines)]
              (recur rest-lines
                     (conj out [:ul (for [i items]
                                      [:li (inline (str/replace (str/trim i) #"^[-*] +" ""))])])))

            (str/blank? trimmed) (recur (rest lines) out)

            :else
            (let [para? (fn [l] (let [t (str/trim l)]
                                  (and (not (str/blank? t))
                                       (not (str/starts-with? t "```"))
                                       (not (re-matches #"#{1,6} .*" t))
                                       (not (re-matches #"[-*] +.*" t))
                                       (not (str/starts-with? t "|")))))
                  [para rest-lines] (split-with para? lines)]
              (recur rest-lines (conj out [:div.para (map plain-line para)]))))))))))

;;; ---------------------------------------------------------------------------
;;; layout

(def css
  "body{margin:0;font:14px/1.5 ui-sans-serif,system-ui,sans-serif;background:#12141a;color:#dfe3ec}
   a{color:#8ab4f8;text-decoration:none} a:hover{text-decoration:underline}
   header.top{position:sticky;top:0;background:#181b23;border-bottom:1px solid #2b3040;padding:8px 16px;display:flex;gap:16px;align-items:center;flex-wrap:wrap;z-index:5}
   header.top .brand{font-weight:700;color:#f0f3f8}
   nav.crumbs{color:#7c8496;font-size:13px}
   main{max-width:1200px;margin:0 auto;padding:16px 16px 64px}
   h1{font-size:20px;margin:8px 0 4px} h2{font-size:17px;margin:24px 0 8px;border-bottom:1px solid #2b3040;padding-bottom:4px}
   h3{font-size:15px;margin:16px 0 6px;color:#cbd3e3} h4{font-size:14px;margin:12px 0 4px;color:#b9c2d6}
   .muted{color:#8b93a7} .small{font-size:12px}
   table{border-collapse:collapse;width:100%;margin:8px 0} th,td{border:1px solid #2b3040;padding:4px 8px;text-align:left;vertical-align:top}
   th{background:#1c202a;color:#c8d0e0;font-weight:600} tr:nth-child(even) td{background:#171a22}
   code,pre,.mono{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12.5px}
   pre.code,pre.blob{background:#0d0f14;border:1px solid #2b3040;border-radius:6px;padding:8px;overflow:auto;white-space:pre-wrap;word-break:break-word;max-height:60vh}
   pre.code{max-height:none}
   .chip{display:inline-block;padding:1px 6px;border-radius:10px;background:#262c3a;color:#cdd5e6;font-size:12px;margin-right:4px;white-space:nowrap}
   .chip.agent{background:#1f3a2c;color:#a7e3bd} .chip.round{background:#22304a;color:#a9c6f5}
   .chip.err{background:#4a1f24;color:#ffb4b4} .chip.rej{background:#4a3a1f;color:#ffdfa8}
   .chip.ok{background:#1f3a2c;color:#a7e3bd} .chip.note{background:#2b3448;color:#c2cde6}
   .chip.time{background:#20242e;color:#96a0b6} .chip.gm{background:#3a2140;color:#e3b6f5}
   .chip.private{background:#3a2f21;color:#f0d3a5} .chip.public{background:#1f3340;color:#a9dff5}
   .chip.muted{background:#20242e;color:#7c8496}
   details{margin:6px 0;border-left:2px solid #2b3040;padding-left:8px}
   details>summary{cursor:pointer;color:#9fb0cd} details[open]>summary{color:#dfe3ec}
   .tool{border:1px solid #2b3040;border-radius:6px;padding:8px;margin:8px 0;background:#171a22}
   .tool .name{font-weight:700;color:#ffe1a8}
   .reply{border-left:3px solid #3a4152;padding-left:8px;margin-top:6px;white-space:pre-wrap;word-break:break-word;max-height:50vh;overflow:auto}
   .reply.err{border-color:#a33a3a} .reply.rej{border-color:#a3813a}
   .line{margin:1px 0}
   ul,ol{padding-left:20px} div.para{margin:6px 0}
   .filters{display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin:10px 0;padding:8px;background:#181b23;border:1px solid #2b3040;border-radius:6px}
   input,select,button{background:#0f1218;color:#dfe3ec;border:1px solid #333a4b;border-radius:4px;padding:4px 6px;font:inherit}
   button{cursor:pointer} button:hover{border-color:#556}
   .msg{border:1px solid #262b38;border-radius:6px;padding:4px 8px;margin:6px 0}
   .msg>summary{color:#8b93a7;font-size:12px}
   mark{background:#5a4a12;color:#ffe9a8;padding:0 1px}
   .grid{display:flex;gap:24px;flex-wrap:wrap} .grid>div{flex:1 1 320px;min-width:280px}
   .totals{font-size:15px} .totals b{color:#f0f3f8}
   footer{color:#5c6478;font-size:12px;padding:16px;text-align:center}")

(defn- search-form [action params placeholder]
  [:form.filters {:method "get" :action action}
   (for [[k v] params :when v] [:input {:type "hidden" :name (name k) :value (str v)}])
   [:input {:type "search" :name "q" :placeholder placeholder :value (str (or (:q params) ""))}]
   [:button {:type "submit"} "Search"]])

(defn layout
  "Wrap `body` in the page shell. `crumbs` are [text href] pairs."
  [{:keys [title crumbs home]} body]
  (html
   [:html
    [:head
     [:meta {:charset "utf-8"}]
     [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
     [:title (str title " · manor viewer")]
     [:style (raw css)]]
    [:body
     [:header.top
      [:a.brand {:href home} "manor viewer"]
      [:nav.crumbs (interpose " › " (for [[text href] crumbs]
                                      (if href [:a {:href href} text] [:span text])))]]
     (into [:main] body)
     [:footer "read-only: runs are read from disk and never changed"]]]))

;;; ---------------------------------------------------------------------------
;;; run index

(defn- run-index-row [{:keys [rel name rounds calls-count stats replies bytes config errors]}]
  [:tr
   [:td [:a {:href (url "/run" {:dir rel})} name]]
   [:td (str (:scenario config))]
   [:td (trunc (str (get-in config [:opts :model])) 32)]
   [:td (n-int rounds)]
   [:td (n-int calls-count)]
   [:td (money (:cost (:total stats)))]
   [:td (str (:errors replies) " errors / " (:rejections replies) " rejected")]
   [:td (chip "private" (when (get-in config [:opts :test?]) "test"))
    (chip "err" (when (seq errors) (str (count errors) " unreadable lines")))
    (chip nil (short-bytes bytes))]])

(defn runs-page
  "The index: every run under the root, plus global search results."
  [{:keys [root runs search]}]
  (let [search-table
        [:table
         [:thead [:tr [:th "run"] [:th "round"] [:th "agent"] [:th "where"] [:th "match"]]]
         [:tbody
          (for [h search]
            [:tr
             [:td [:a {:href (url "/call" {:dir (:dir h) :n (:n h)})} (:run h)]]
             [:td (:round h)]
             [:td (chip "agent" (:agent h))]
             [:td (chip nil (:field h))]
             [:td [:div.reply (str (:snippet h))]]])]]
        runs-table
        [:table
         [:thead [:tr [:th "run"] [:th "scenario"] [:th "model"] [:th "rounds"] [:th "calls"]
                  [:th "cost"] [:th "engine replies"] [:th "notes"]]]
         [:tbody (for [r runs] (run-index-row r))]]]
    (layout
     {:title "runs" :home (url "/" {}) :crumbs [["runs" nil]]}
     (concat
      [[:h1 "runs under " [:code (str root)]]
       (search-form "/" {} "search every run: thoughts, tool args, replies, text")]
      (if (seq search)
        [[:h2 (str (count search) " matches")] search-table]
        [[:h2 (str (count runs) " runs")] runs-table])))))

;;; ---------------------------------------------------------------------------
;;; run overview

(defn run-page
  "One run: configuration, per-agent totals, rounds and files."
  [{:keys [rel run name stats replies round-rollup round-times file-sizes rounds]}]
  (let [base {:dir rel}
        l (fn [p extra] (url p (merge base extra)))]
    (layout
     {:title name :home (url "/" {}) :crumbs [["runs" (url "/" {})] [name nil]]}
     [(let [cfg (:config run)]
        [:div
         [:h1 name]
         [:div.muted.small (str (:dir run) " · " (get-in cfg [:opts :model]) " · scenario "
                                (:scenario cfg) " · seed " (get-in cfg [:opts :seed])
                                " · max " (get-in cfg [:opts :max-rounds]) " rounds / "
                                (get-in cfg [:opts :max-steps]) " calls a turn"
                                (when (get-in cfg [:opts :test?]) " · test run"))]
         [:div.filters
          [:a {:href (l "/calls" {})} "all calls"]
          [:a {:href (l "/events" {})} "events"]
          [:a {:href (l "/file" {:name "transcript.md"})} "transcript.md"]
          [:a {:href (l "/search" {})} "search this run"]]
         [:div.totals
          [:p (str (n-int (get-in stats [:total :calls])) " model calls · " (n-int rounds) " rounds · " (money (:cost (:total stats)))
                   " · " (n-int (:prompt-tokens (:total stats))) " prompt + "
                   (n-int (:completion-tokens (:total stats))) " completion tokens ("
                   (n-int (:thought-tokens (:total stats))) " in reasoning)")]
          [:p (str (:errors replies) " error replies · " (:rejections replies) " rejections · "
                   (:forfeits replies) " forfeited turns")]
          (when (seq (:errors run))
            [:p (chip "err" (str (count (:errors run)) " lines that would not read"))
             [:span.small (str "  " (str/join "; " (for [e (take 4 (:errors run))]
                                                     (str (:file e) " line " (:line e)))))]])]])

      [:div.grid
       [:div
        [:h2 "calls by agent"]
        [:table
         [:thead [:tr [:th "agent"] [:th "calls"] [:th "cost"] [:th "prompt"] [:th "completion"] [:th "thought"]]]
         [:tbody (for [[agent s] (:by-agent stats)]
                   [:tr [:td [:a {:href (l "/calls" {:agent agent})} (chip "agent" agent)]]
                    [:td (n-int (:calls s))] [:td (money (:cost s))]
                    [:td (n-int (:prompt-tokens s))] [:td (n-int (:completion-tokens s))]
                    [:td (n-int (:thought-tokens s))]])]]]
       [:div
        [:h2 "tools called"]
        [:table
         [:thead [:tr [:th "tool"] [:th "calls"]]]
         [:tbody (for [[tool n] (sort-by (comp - val) (:tools stats))]
                   [:tr [:td [:a {:href (l "/calls" {:q tool :in :tools})} tool]] [:td (n-int n)]])]]
        [:h2 "finish reasons"]
        [:table [:tbody (for [[k n] (sort-by key (:finish stats))]
                          [:tr [:td k] [:td (n-int n)]])]]]]

      [:h2 "rounds"]
      [:table
       [:thead [:tr [:th "round"] [:th "when"] [:th "calls"] [:th "cost"] [:th "agents"] [:th "forfeits"]]]
       [:tbody (for [r round-rollup]
                 (let [fs (filterv (fn [e] (and (= :action/forfeited (:type e)) (= (:round r) (:round e))))
                                   (:events run))]
                   [:tr [:td [:a {:href (l "/calls" {:round (:round r)})} (n-int (:round r))]]
                    [:td (or (get round-times (:round r)) "")]
                    [:td (n-int (:calls r))] [:td (money (:cost r))]
                    [:td (for [a (:agents r)] (chip "agent" a))]
                    [:td (when (seq fs) (chip "rej" (str (count fs) " forfeited")))]]))]]

      [:h2 "files"]
      [:table
       [:tbody (for [f (:files run)]
                 [:tr [:td [:code f]]
                  [:td (short-bytes (get file-sizes f 0))]
                  [:td (case f
                         "transcript.md" [:a {:href (l "/file" {:name f})} "view / raw"]
                         "llm.edn" [:a {:href (l "/calls" {})} "browse calls"] "events.edn"
                         [:a {:href (l "/events" {})} "browse events"]
                         [:a {:href (l "/raw-file" {:name f})} "raw"])]])]]])))

;;; ---------------------------------------------------------------------------
;;; calls list

(defn tool-summary [call]
  (let [tcs (data/tool-calls call)]
    (str (if (seq tcs)
           (str/join "; " (for [{:keys [name raw]} tcs] (str name " " (trunc (str raw) 90))))
           (str "(no tool call)"))
         (when (and (:finish-reason call) (not= "tool_calls" (:finish-reason call)))
           (str "  [" (:finish-reason call) "]")))))

(defn- reply-status
  "The worst engine reply among this call's replies."
  [call]
  (let [texts (map (comp str :content) (:replies call))]
    (cond (some #(str/starts-with? % "error:") texts) "err"
          (some #(str/starts-with? % "rejected:") texts) "rej"
          (empty? texts) "muted"
          (every? #(= % "ok") texts) "ok"
          :else "note")))

(defn call-row
  [{:keys [base]} n call]
  [:tr
   [:td [:a {:href (url "/call" (merge base {:n n}))} (n-int (inc n))]]
   [:td (n-int (:round call))]
   [:td (or (data/call-time call) "")]
   [:td (chip "agent" (:agent call))]
   [:td (trunc (tool-summary call) 130)]
   [:td (chip (reply-status call) (reply-status call))]
   [:td (money (get-in call [:usage :cost]))]
   [:td (n-int (get-in call [:usage :prompt-tokens]))]
   [:td (n-int (get-in call [:usage :thought-tokens]))]
   [:td (or (:finish-reason call) "")]])

(defn calls-page
  "The call log, with filters and paging."
  [{:keys [rel run name query calls total page pages prev-page next-page]}]
  (layout
   {:title (str name " · calls") :home (url "/" {})
    :crumbs [["runs" (url "/" {})] [name (url "/run" {:dir rel})] ["calls" nil]]}
   [[:h1 "model calls"]
    [:p.muted.small (str (count calls) " of " (n-int total) " calls shown"
                         (when (seq (:errors run))
                           (str " · " (count (:errors run)) " unreadable lines")))]
    [:form.filters {:method "get" :action "/calls"}
     [:input {:type "hidden" :name "dir" :value (str rel)}]
     [:label "agent " [:input {:name "agent" :value (str (:agent query)) :size 12}]]
     [:label "round " [:input {:name "round" :value (str (:round query)) :size 3}]]
     [:label "flag " [:select {:name "flag"}
                      (for [[v label] [[nil "any"] ["errors" "error replies"] ["rejections" "rejections"]
                                       ["notool" "no tool call"] ["length" "truncated (length)"]
                                       ["forfeit" "forfeited turn"] ["thoughts" "has reasoning"]]]
                        [:option {:value (str (or v "")) :selected (when (= v (:flag query)) "selected")} label])]]
     [:label "text " [:input {:name "q" :value (str (:q query)) :size 22}]]
     [:button {:type "submit"} "Filter"]]
    [:table
     [:thead [:tr [:th "#"] [:th "round"] [:th "time"] [:th "agent"] [:th "tool call"] [:th "reply"]
              [:th "cost"] [:th "prompt tok"] [:th "thought tok"] [:th "finish"]]]
     [:tbody (for [[n call] calls] (call-row {:base {:dir rel}} n call))]]
    (when (> pages 1)
      [:p (when prev-page [:a {:href prev-page} "← newer"])
       " " [:span.muted (str "page " (inc page) " / " pages)] " "
       (when next-page [:a {:href next-page} "older →"])])]))

;;; ---------------------------------------------------------------------------
;;; one call

(defn- msg-block [i {:keys [role content]} open? reminder?]
  [:details.msg (if open? {:open true} {})
   [:summary (str "[" (inc i) "] " role (when reminder? " · reminder") " · "
                  (short-bytes (count (str content))) " · "
                  (count (str/split-lines (str content))) " lines")]
   (if (= role "tool")
     [:div.reply (str content)]
     (md->hiccup (str content)))])

(defn call-page
  "Everything the engine recorded about one model call."
  [{:keys [rel run name n call prev next prev-same next-same turn-n turn-size
           call-time]}]
  (let [base {:dir rel}
        l (fn [p extra] (url p (merge base extra)))
        u (:usage call)
        tool-calls (data/tool-calls call)
        msgs (:messages call)]
    (layout
     {:title (str name " · call " (inc n)) :home (url "/" {})
      :crumbs [["runs" (url "/" {})] [name (url "/run" base)]
               ["calls" (url "/calls" base)] [(str "call " (inc n)) nil]]}
     [[:h1 (str "call " (inc n) " of " (n-int (:calls-count run)))]
      [:div
       (chip "round" (str "round " (:round call)))
       (when call-time (chip "time" call-time))
       (chip "agent" (:agent call))
       (when (and turn-size (> turn-size 1)) (chip nil (str "call " turn-n " of " turn-size " this turn")))
       (when (:finish-reason call)
         (chip (if (= "tool_calls" (:finish-reason call)) "ok" "rej") (str "finish: " (:finish-reason call))))
       (chip nil (money (:cost u)))
       (chip nil (str (n-int (:prompt-tokens u)) " prompt tok"))
       (chip nil (str (n-int (:completion-tokens u)) " completion tok"))
       (when (:thought-tokens u) (chip nil (str (n-int (:thought-tokens u)) " thought tok")))
       (chip nil (:model call))]
      (let [view (data/view-json call)
            here (get-in view ["location" "here" "name"])
            people (get-in view ["location" "people-here"])]
        (when (or here (seq people))
          [:div (when here (chip nil (str "at " here)))
                (when (seq people) (chip nil (str "with " (count people) " here")))]))
      [:div.filters
       (when prev [:a {:href (l "/call" {:n prev})} "← previous call"])
       (when next [:a {:href (l "/call" {:n next})} "next call →"])
       (when prev-same [:a {:href (l "/call" {:n prev-same})} (str "← previous " (:agent call))])
       (when next-same [:a {:href (l "/call" {:n next-same})} (str "next " (:agent call) " →")])
       [:a {:href (l "/raw" {:n n})} "raw EDN"]
       [:a {:href (l "/calls" {:round (:round call) :agent (:agent call)})} "this turn in the log"]]

      [:h2 "tool calls and replies"]
      (if (seq tool-calls)
        (for [tc tool-calls
              :let [reply (data/reply-for call (:id tc))]]
          [:div.tool
           [:div [:span.name (:name tc)]
            (when-let [args (not-empty (:args tc))]
              [:span.mono (str " " (json/generate-string args))])]
           [:div.reply {:class (reply-class reply)} (str (or reply "(no reply recorded)"))]
           [:details [:summary "arguments as sent"] [:pre.code (str (:raw tc))]]])
        [:p (chip "rej" (str "no tool call (finish: " (or (:finish-reason call) "?") ")"))])
      (let [attached (set (keep :id tool-calls))
            orphan (remove #(contains? attached (:tool_call_id %)) (:replies call))]
        (when (seq orphan)
          [:div
           [:h3 "engine replies not tied to a tool call"]
           (for [m orphan]
             [:div.tool [:div.reply {:class (reply-class (:content m))} (str (:content m))]])]))

      (when (seq (:thoughts call))
        [:h2 "reasoning the model returned"]
        [:details (if (< (count (str (:thoughts call))) 1800) {:open true} {})
         [:summary (str (short-bytes (count (str (:thoughts call)))) " · captured out of band")]
         [:pre.blob (str (:thoughts call))]])

      (when-let [text (not-empty (str/trim (str (:content (:response call)))))]
        [:h2 "text returned"] [:div.reply (str text)])

      [:h2 "prompt sent to the model"]
      [:p.muted.small (str (count msgs) " messages · the last one is what this call answered")]
      [:div (for [[i m] (map-indexed vector msgs)]
              (msg-block i m (= i (dec (count msgs))) (data/reminder? m)))]])))

;;; ---------------------------------------------------------------------------
;;; events

(defn- visibility-chips [v]
  (if (set? v)
    (for [x (sort-by name v)] (chip (if (= :gm x) "gm" "private") (name x)))
    (chip (if (= :gm v) "gm" "private") (if (keyword? v) (name v) (str v)))))

(defn events-page
  "The event log, grouped by round."
  [{:keys [rel run query rounds total] :as ctx}]
  (layout
   {:title (str (:name ctx) " · events") :home (url "/" {})
    :crumbs [["runs" (url "/" {})] [(:name ctx) (url "/run" {:dir rel})] ["events" nil]]}
   [[:h1 "events"]
    [:p.muted.small (str (count (mapcat second rounds)) " of " (n-int total) " events shown")]
    [:form.filters {:method "get" :action "/events"}
     [:input {:type "hidden" :name "dir" :value (str rel)}]
     [:label "round " [:input {:name "round" :value (str (:round query)) :size 3}]]
     [:label "type " [:input {:name "type" :value (str (:type query)) :size 16}]]
     [:label "text " [:input {:name "q" :value (str (:q query)) :size 18}]]
     [:button {:type "submit"} "Filter"]]
    (for [[label evs] rounds]
      [:div
       [:h2 label]
       [:table
        [:thead [:tr [:th "id"] [:th "type"] [:th "actor"] [:th "visible to"] [:th "what happened"] [:th "data"]]]
        [:tbody (for [e evs]
                  [:tr [:td (n-int (:id e))]
                   [:td [:code (str (:type e))]]
                   [:td (or (some-> (:actor e) name) "")]
                   [:td (visibility-chips (:visible-to e))]
                   [:td (md->hiccup (data/describe-event run e))]
                   [:td [:details [:summary "data"] [:pre.code (pr-str (:data e))]]]])]]])]))

;;; ---------------------------------------------------------------------------
;;; search, transcript, raw

(defn search-page
  [{:keys [rel run name query hits]}]
  (layout
   {:title (str name " · search") :home (url "/" {})
    :crumbs [["runs" (url "/" {})] [name (url "/run" {:dir rel})] ["search" nil]]}
   [[:h1 "search this run"]
    [:form.filters {:method "get" :action "/search"}
     [:input {:type "hidden" :name "dir" :value (str rel)}]
     [:input {:type "search" :name "q" :value (str (:q query)) :size 32
              :placeholder "thoughts, tool arguments, engine replies, returned text"}]
     [:label "in " [:select {:name "in"}
                    (for [[v label] [[nil "thoughts, tools, replies, text"] ["all" "everything (incl. prompts)"]
                                     ["thoughts" "reasoning only"] ["replies" "engine replies only"]
                                     ["tools" "tool calls only"]]]
                      [:option {:value (str (or v "")) :selected (when (= v (:in query)) "selected")} label])]]
     [:button {:type "submit"} "Search"]]
    (when (:q query)
      [:p.muted.small (str (count hits) " calls match “" (:q query) "”")])
    (for [h hits]
      [:div.tool
       [:div [:a {:href (url "/call" {:dir rel :n (:n h)})} (str "call " (inc (:n h)))]
        " " (chip "round" (str "round " (:round h))) " " (chip "agent" (:agent h))
        " " (chip nil (:field h))]
       [:div.reply (str (:snippet h))]])]))

(defn transcript-page
  [{:keys [rel run name text]}]
  (layout
   {:title (str name " · transcript.md") :home (url "/" {})
    :crumbs [["runs" (url "/" {})] [name (url "/run" {:dir rel})] ["transcript.md" nil]]}
   [[:h1 "transcript.md"]
    [:div.filters [:a {:href (url "/raw-file" {:dir rel :name "transcript.md"})} "raw text"]
     [:a {:href (url "/run" {:dir rel})} "run overview"]]
    [:div (md->hiccup text)]]))

(defn raw-page [{:keys [title text]}]
  (html [:html
         [:head [:meta {:charset "utf-8"}] [:title (str title)] [:style "body{background:#0d0f14;color:#dfe3ec}pre{white-space:pre-wrap;word-break:break-word;font-family:ui-monospace,Menlo,monospace;font-size:12.5px}"]]
         [:body [:pre (str text)]]]))

(defn error-page
  [{:keys [status message home]}]
  (layout {:title (str status) :home home :crumbs [[(str status) nil]]}
          [[:h1 (str status " · " message)]
           [:p [:a {:href home} "back to the runs"]]]))
