(ns manor.viewer-test
  "Tests for the transcript viewer.

  A small fixture run is written to a temp directory, so the tests never touch
  `old-test-runs/`, `runs/` or the network. Run with `bb test` in viewer/, or
  `bb --config viewer/bb.edn test` from the repo root."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [manor.viewer.html :as h]
            [manor.viewer.data :as data]
            [manor.viewer.render :as render]
            [manor.viewer.web :as web]))

(def ^:private view-text
  (str "# Monday 1 January 1900, 09:00\n\n"
       "## Your view\n```json\n{\"time\":\"09:00\",\"you\":{\"id\":\"ann\"},\"flags\":[]}\n```\n\n"
       "## Your notes\n(none)\n\n"
       "## Recent events you witnessed\n[09:00] Bob joins.\n\n"
       "Take your turn now."))

(def ^:private calls
  [{:agent "ann" :round 1 :model "test/model" :finish-reason "tool_calls"
    :thoughts "I should say hello to bob."
    :tools ["say"]
    :response {:role "assistant" :content nil
               :tool_calls [{:type "function" :id "c1"
                             :function {:name "say" :arguments "{\"text\":\"hello bob\"}"}}]}
    :replies [{:role "tool" :tool_call_id "c1" :content "ok"}]
    :usage {:prompt-tokens 100 :completion-tokens 10 :thought-tokens 5 :cost 0.001}
    :messages [{:role "system" :content "You are Ann."}
               {:role "user" :content view-text}]}
   {:agent "bob" :round 1 :model "test/model" :finish-reason "length"
    :tools ["say"]
    :response {:role "assistant" :content "I hesitate." :tool_calls []}
    :replies [{:role "tool" :tool_call_id "c2" :content "rejected: you are not at home"}]
    :usage {:prompt-tokens 50 :completion-tokens 80 :thought-tokens 12 :cost 0.002}
    :messages [{:role "system" :content "You are Bob."}
               {:role "user" :content (str "# Monday 1 January 1900, 09:00\n\n## Your view\n```json\n{\"you\":{\"id\":\"bob\"}}\n```")}
               {:role "user" :content "Your turn isn't over."}]}])

(defn- write-fixture!
  "Write a tiny run into `root/run`. Returns [root run-dir]."
  []
  (let [root (fs/create-temp-dir {:prefix "manor-viewer-test-"})
        dir (fs/file root "demo-run")]
    (fs/create-dirs dir)
    (spit (fs/file dir "config.edn")
          (pr-str {:scenario :demo :opts {:model "test/model" :max-rounds 2 :seed 1}}))
    (spit (fs/file dir "events.edn")
          (str (pr-str {:type :demo/started :visible-to :gm :id 1 :round 0 :data {:a 1}}) "\n"
               (pr-str {:type :action/say :actor "ann" :visible-to #{"ann" "bob"} :id 2 :round 1
                        :data {:text "hello there"}}) "\n"
               "{:broken\n"))
    (spit (fs/file dir "llm.edn")
          (str (str/join "\n" (map pr-str calls)) "\n"))
    (spit (fs/file dir "transcript.md")
          "# Transcript of demo-run\n\n| agent | calls |\n|---|---|\n| ann | 1 |\n\n## Round 1\n\n- Ann says hello.\n")
    [root dir]))

(deftest reads-an-edn-file-line-by-line
  (let [[root dir] (write-fixture!)
        run (data/read-run dir)]
    (is (= 2 (count (:calls run))))
    (is (= 2 (count (:events run))))
    (testing "a line that will not read is reported, not thrown"
      (is (= 1 (count (:errors run))))
      (is (= 3 (:line (first (:errors run)))))
      (is (= "events.edn" (:file (first (:errors run))))))
    (is (= "demo-run" (:name (data/summarise root "demo-run" run))))))

(deftest call-helpers
  (let [run (data/read-run (second (write-fixture!)))
        [c1 c2] (:calls run)]
    (is (= [{:id "c1" :name "say" :raw "{\"text\":\"hello bob\"}" :args {"text" "hello bob"}}]
           (vec (data/tool-calls c1))))
    (is (= "ok" (data/reply-for c1 "c1")))
    (is (= false (boolean (seq (data/tool-calls c2)))))
    (testing "the world view in a prompt is parsed"
      (is (= {"time" "09:00" "you" {"id" "ann"} "flags" []} (data/view-json c1))))
    (testing "the prompt's title is the game time"
      (is (= "Monday 1 January 1900, 09:00" (data/call-time c1))))
    (testing "turn reminders are recognised as user messages without a view"
      (is (data/reminder? (last (:messages c2))))
      (is (not (data/reminder? (second (:messages c2))))))
    (testing "a call knows its place in its turn"
      (is (= 1 (data/turn-number (:calls run) 0)))
      (is (= 1 (data/turn-number (:calls run) 1))))))

(deftest stats-and-search
  (let [run (data/read-run (second (write-fixture!)))
        stats (data/call-stats (:calls run))
        replies (data/engine-reply-counts (:calls run) (:events run))]
    (is (= 2 (get-in stats [:total :calls])))
    (is (< 0.0029 (get-in stats [:total :cost]) 0.0031))
    (is (= {"length" 1 "tool_calls" 1} (:finish stats)))
    (is (= {"say" 1} (:tools stats)))
    (is (= {:errors 0 :rejections 1 :forfeits 0} replies))
    (testing "reasoning recorded (or billed without text) is summarised"
      (let [r (data/reasoning-stats (:calls run))]
        (is (= 2 (:calls r)))
        (is (= 1 (:with-text r)))
        (is (= 2 (:with-tokens r)))
        (is (= 17 (:tokens r)))
        (is (= 1 (:tokens-without-text r)))
        (is (= 26 (:bytes r))))
      (is (= [0] (map :n (data/reasoning-hits (:calls run) {}))))
      (is (= [0] (map :n (data/reasoning-hits (:calls run) {:agent "ann"}))))
      (is (empty? (data/reasoning-hits (:calls run) {:agent "bob"})))
      (is (empty? (data/reasoning-hits (:calls run) {:query "saloon"}))))

    (testing "search finds text in calls and can be narrowed"
      (is (= [0] (distinct (map :n (data/search-calls (:calls run) {:query "hello"})))))
      (is (= [0] (distinct (map :n (data/search-calls (:calls run) {:query "hello" :in :tools})))))
      (is (empty? (data/search-calls (:calls run) {:query "hello" :in :replies})))
      (is (= [1] (distinct (map :n (data/search-calls (:calls run) {:query "rejected" :in :replies}))))))))

(deftest traversal-is-refused
  (let [[root _] (write-fixture!)]
    (is (nil? (data/resolve-run-dir root "../etc")))
    (is (nil? (data/read-file-in root "../config.edn")))
    (is (str/includes? (data/read-file-in root "demo-run/config.edn") ":demo"))))

(deftest markdown-rendering
  (is (= "<div><div class=\"para\"><div class=\"line\">hello <strong>world</strong> and <code>code</code></div></div></div>"
         (h/html (into [:div] (render/md->hiccup "hello **world** and `code`")))))
  (is (str/includes? (h/html (into [:div] (render/md->hiccup "| a | b |\n|---|---|\n| 1 | 2 |")))
                     "<th>a</th><th>b</th>"))
  (is (str/includes? (h/html (into [:div] (render/md->hiccup "[09:00] Bob joins.")))
                     "chip time"))
  (testing "html in the source is escaped, never interpreted"
    (is (not (str/includes? (h/html (into [:div] (render/md->hiccup "<script>x</script>"))) "<script>")))))

(defn- http-get [url]
  (let [^java.net.HttpURLConnection conn (.openConnection (java.net.URL. url))
        status (.getResponseCode conn)
        stream (if (>= status 400) (.getErrorStream conn) (.getInputStream conn))]
    {:status status :body (if stream (slurp stream) "")}))

(deftest serves-the-pages
  (let [[root _] (write-fixture!)
        {:keys [server url]} (web/start! {:root root :port 0})]
    (try
      ;; the fixture's event types are invented, so replay would fail anyway
      (do
        (data/set-narrate! false)
        (let [get! (fn [path] (http-get (str url path)))]
          (doseq [[path expect] [["/" "demo-run"]
                                 ["/run?dir=demo-run" "model calls"]
                                 ["/calls?dir=demo-run" "hello bob"]
                                 ["/call?dir=demo-run&n=0" "tool calls and replies"]
                                 ["/call?dir=demo-run&n=1" "rejected: you are not at home"]
                                 ["/call?dir=demo-run&n=0" "I should say hello to bob."]
                                 ["/call?dir=demo-run&n=1" "12 reasoning tokens"]
                                 ["/events?dir=demo-run" "action/say"]
                                 ["/search?dir=demo-run&q=hello" "call 1"]
                                 ["/reasoning?dir=demo-run" "I should say hello to bob."]
                                 ["/reasoning?dir=demo-run&agent=bob" "0 shown"]
                                 ["/file?dir=demo-run&name=transcript.md" "Ann says hello"]
                                 ["/raw?dir=demo-run&n=0" ":thoughts"]
                                 ["/raw-file?dir=demo-run&name=config.edn" ":demo"]]]
            (let [{:keys [status body]} (get! path)]
              (is (= 200 status) (str path " should be 200"))
              (is (str/includes? body expect) (str path " should contain " expect)))))
        (testing "unknown runs, calls, pages and traversal are not found"
          (doseq [path ["/run?dir=nope" "/call?dir=demo-run&n=99" "/nope"
                        "/file?dir=demo-run&name=../../../etc/passwd"]]
            (is (= 404 (:status (http-get (str url path)))) (str path " should be 404")))))
      (finally
        (data/set-narrate! true)
        (web/stop! {:server server})))))
