(ns manor.settings
  "Setting packs, loaded once into a run's state, including prompt text.
  Pack-less worlds/fixtures use England. No files are read when
  rendering a world that already has its pack snapshot."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def default-path "settings/england-1904")

(defn- resource [path]
  (or (io/resource path)
      (throw (ex-info (str "no setting resource " path) {:path path}))))

(defn- read-edn [path] (edn/read-string (slurp (resource path))))

(defn- prompt-paths [path]
  (let [url (resource (str path "/prompts"))
        prefix (str path "/prompts/")]
    (if (= "jar" (.getProtocol url))
      (let [conn ^java.net.JarURLConnection (.openConnection url)]
        (.setUseCaches conn false)
        (with-open [jar (.getJarFile conn)]
          (->> (enumeration-seq (.entries jar))
               (map #(.getName ^java.util.jar.JarEntry %))
               (filter #(and (str/starts-with? % prefix) (str/ends-with? % ".md"))) vec)))
      (let [dir (io/file url)]
        (for [f (file-seq dir) :when (and (.isFile f) (str/ends-with? (.getName f) ".md"))]
          (str prefix (.relativize (.toPath dir) (.toPath f))))))))

(defn load-pack
  "A classpath pack directory as immutable data. Prompt keys are relative
  paths under prompts/; fragments and overrides are both stored as text."
  [path]
  (let [path (or path default-path)
        prefix (str path "/prompts/")]
    (assoc (read-edn (str path "/setting.edn"))
           :source path
           :positions (read-edn (str path "/positions.edn"))
           :calendar (read-edn (str path "/calendar.edn"))
           :money (read-edn (str path "/money.edn"))
           :prompts (into {} (for [p (prompt-paths path)]
                               [(subs p (count prefix)) (str/replace (slurp (resource p)) #"\n$" "")])))))

(def ^:private england (delay (load-pack default-path)))

(defn pack
  "The run's snapshot, or England for pack-less worlds and fixtures."
  [world]
  (or (get-in world [:state :setting-pack]) @england))

(defn for-worldfile [wf]
  (load-pack (get-in wf [:meta :setting-pack])))

(defn parameters
  "Setting parameters and the money spec, not catalogues or prompt text."
  [world]
  (dissoc (pack world) :source :positions :calendar :prompts))

(defn parameter [world & path] (get-in (parameters world) path))
