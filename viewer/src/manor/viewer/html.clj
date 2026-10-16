(ns manor.viewer.html
  "A very small HTML renderer for hiccup-style data.

  Written because babashka's bundled hiccup does not escape string content
  (`(hiccup.core/html [:div \"<b>\"])` emits a real `<b>`), and this viewer
  shows model-produced text and prompt contents — which must never be
  interpreted as markup. Here every string is escaped unless it is wrapped in
  `raw`, which is only for CSS.

    (html [:div.card {:id \"x\"} \"a < b\" [:p \"ok\"]]) ;=> \"<div class=\\\"card\\\" id=\\\"x\\\">a &lt; b<p>ok</p></div>\"

  Elements: keywords with optional `.class` and `#id` parts (`:div.card.big`),
  an optional attribute map ({:open true} renders a bare attribute), then any
  number of children — strings, numbers, nil, nested vectors and seqs."
  (:require [clojure.string :as str]))

(defrecord Raw [text])

(defn raw
  "Content to emit verbatim: only for the stylesheet."
  [s]
  (->Raw (str s)))

(defn esc [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(def ^:private void-tags #{"area" "base" "br" "col" "embed" "hr" "img" "input"
                           "link" "meta" "param" "source" "track" "wbr"})

(defn- parse-tag
  "Split :div.card#id into {\"div\" [\"card\"] \"id\"}."
  [kw]
  (let [s (name kw)
        tag (or (re-find #"^[^.#]+" s) "div")
        rest (subs s (count tag))]
    {:tag tag
     :classes (mapv second (re-seq #"\.([^.#]+)" rest))
     :id (second (re-find #"#([^.#]+)" rest))}))

(defn- attrs->str [attrs classes id]
  (let [attrs (cond-> (into {} (for [[k v] attrs
                                    :when (and v (not (false? v)))]
                                [(name k) (if (true? v) true (str v))]))
                (seq classes) (update "class" #(str/join " " (remove str/blank? [% (str/join " " classes)])))
                id (assoc "id" id))]
    (str/join
     (for [[k v] (sort-by key attrs)]
       (if (true? v)
         (str " " k)
         (str " " k "=\"" (esc v) "\""))))))

(declare render)

(defn- render-children [children]
  (apply str (map render children)))

(defn render [x]
  (cond
    (nil? x) ""
    (instance? Raw x) (:text x)
    (string? x) (esc x)
    (number? x) (str x)
    (keyword? x) (esc (name x))
    (vector? x)
    (let [[tag & body] x
          [attrs body] (if (and (map? (first body)) (not (instance? Raw (first body))))
                         [(first body) (rest body)]
                         [nil body])
          {:keys [tag] :as parsed} (if (keyword? tag) (parse-tag tag) {:tag (str tag)})
          open (str "<" tag (attrs->str attrs (:classes parsed) (:id parsed))) ]
      (if (void-tags tag)
        (str open " />")
        (str open ">" (render-children body) "</" tag ">")))
    (seq? x) (render-children x)
    (coll? x) (render-children x)
    :else (esc x)))

(defn html
  "Render hiccup-style data to an HTML string."
  [& body]
  (render-children body))
