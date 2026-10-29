(ns manor.prompts
  "Prompt templates: markdown files under resources/prompts/ on the
  classpath, filled in from parameters.

    (render \"rules/turns.md\" {:turn \"half an hour\" :max-queries 2})

  A template holds text and `{{key}}` placeholders, nothing else: no
  conditionals or loops. Code chooses which templates to render and join,
  and passes the numbers and names they show. A placeholder may be a dotted
  path into nested maps, `{{money.base-name}}`; keys may be keywords or
  strings. A placeholder with no value throws, naming the template and the
  key, so a prompt never shows `{{home}}`.

  A file's one final newline is not part of the template (editors add one),
  so a template renders as the text it holds. Templates are read once and
  kept."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [manor.settings :as settings]
            [manor.clock :as clock]))

(def ^:private placeholder #"\{\{([^{}]+)\}\}")

(defn- without-final-newline [s]
  (if (str/ends-with? s "\n") (subs s 0 (dec (count s))) s))

(def ^:private cache (atom {}))

(defn template
  "The text of the template at `path`, under prompts/ on the classpath,
  without its final newline. Throws if there's none."
  [path]
  (or (get @cache path)
      (let [res (or (io/resource (str "prompts/" path))
                    (throw (ex-info (str "no prompt template " path) {:template path})))
            text (without-final-newline (slurp res))]
        (swap! cache assoc path text)
        text)))

(defn- lookup
  "The value at dotted `key` in `params`, each step a keyword or a string
  key, or nil."
  [params key]
  (reduce (fn [m k]
            (let [v (when (map? m) (get m (keyword k) (get m k)))]
              (if (nil? v) (reduced nil) v)))
          params
          (str/split key #"\.")))

(defn fill
  "`text` with every `{{key}}` replaced by its value in `params`, as `str`
  makes it. `path` names the template in the error for a missing key."
  [path text params]
  (str/replace text placeholder
               (fn [[_ key]]
                 (let [key (str/trim key)
                       v (lookup params key)]
                   (when (nil? v)
                     (throw (ex-info (str "prompt template " path ": no value for {{" key "}}")
                                     {:template path :key key})))
                   (str v)))))

(defn render
  "Render for a world: its snapshotted pack overrides shared templates and
  supplies setting parameters. Explicit params win. The shorter arities
  use England, for callers without a run (including old tooling). Only
  shared templates are cached; pack text is already held in state."
  ([path] (render {} path {}))
  ([path params] (render {} path params))
  ([world path params]
   (let [pack (settings/pack world)
         text (or (get-in pack [:prompts path]) (template path))]
     (fill path text (merge (settings/parameters world) params)))))

(defn doc
  "An action's per-world description, optionally with explicit parameters."
  ([path] (doc path {}))
  ([path params] (fn [world] (render world path params))))

(defn example-date
  "The run's start date, stable across requests; a format example without
  a clock uses the Gregorian epoch, never the machine's current date."
  [world]
  (if-let [c (:clock world)]
    (str (.toLocalDate (clock/time-of c 1)))
    "1970-01-01"))
