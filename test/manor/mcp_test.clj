(ns manor.mcp-test
  "The MCP client and the pi MCP server (mcp-servers/pi/server.mjs), driven
  end to end with a fake pi (test/fixtures/fake-pi.mjs): free and offline.
  Needs node on the PATH."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [manor.engine :as e]
            [manor.mcp :as mcp]
            [manor.store :as store]
            [manor.world :as w]))

(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

(def fake-pi (.getAbsolutePath (io/file "test/fixtures/fake-pi.mjs")))

(def pi-server {:command ["node" "mcp-servers/pi/server.mjs"]
                :env {"PI_MCP_COMMAND" fake-pi}})

(defn- test-pool [dir]
  (mcp/pool {:pi pi-server} {:run-id "mcp-test" :run-dir dir}))

(deftest pi-server-sessions
  (let [dir (str (io/file store/test-runs-root (str (gensym "mcp-"))))
        pool (test-pool dir)
        send (fn [session message] (mcp/call pool :pi "send_message" {:session session :message message} 10000))]
    (try
      (testing "the first call starts the server"
        (is (= "reply 1 to: hi [session ana]" (:value (send "ana" "hi"))))
        (is (= ["send_message"] (map :name (mcp/list-tools (:pi @(:conns pool)))))))
      (testing "each session keeps its own conversation"
        (is (= {:value "reply 2 to: again [session ana]" :cost 0.001} (send "ana" "again")))
        (is (= "reply 1 to: hey [session bo]" (:value (send "bo" "hey")))))
      (testing "errors come back as exceptions with pi's message"
        (is (thrown-with-msg? Exception #"boom" (send "ana" "!error")))
        (is (thrown-with-msg? Exception #"invalid session" (send "../etc" "x"))))
      (testing "a timed-out call is cancelled and the session keeps working"
        (is (thrown-with-msg? Exception #"timed out"
                              (mcp/call pool :pi "send_message" {:session "bo" :message "!slow"} 500)))
        (is (= "reply 2 to: after [session bo]" (:value (send "bo" "after")))))
      (testing "unknown servers are an error"
        (is (thrown-with-msg? Exception #"no MCP server" (mcp/call pool :nope "x" {} 1000))))
      (finally (mcp/close-all! pool)))
    (testing "closing stops the server"
      (is (empty? @(:conns pool))))
    (testing "conversations persist across server restarts"
      (let [pool2 (test-pool dir)]
        (try
          (is (= "reply 3 to: back [session ana]"
                 (:value (mcp/call pool2 :pi "send_message" {:session "ana" :message "back"} 10000))))
          (finally (mcp/close-all! pool2)))))))

(deftest server-configs-merge
  (is (= {:pi {:command ["node" "s.mjs"] :env {"A" "1" "B" "3" "C" "4"}}
          :other {:command ["x"]}}
         (mcp/server-configs {:pi {:command ["node" "s.mjs"] :env {"A" "1" "B" "2"}}}
                             nil
                             {:pi {:env {"B" "3" "C" "4"}} :other {:command ["x"]}}))))

(deftest quiz-run-with-pi-assistants
  (let [run (e/start-run :quiz {:llm :mock :test? true
                                :run-id (str (gensym "quiz-mcp-"))
                                :mcp-servers {:pi {:env {"PI_MCP_COMMAND" fake-pi}}}})]
    (e/auto! run 10)
    (is (= :idle (e/wait-idle run 60000)))
    (let [evs (e/events run)
          world (e/world run)
          replies (filter #(= :quiz/assistant-replied (:type %)) evs)]
      (is (true? (get-in world [:state :finished?])))
      (is (empty? (filter #(= :action/forfeited (:type %)) evs)))
      (testing "every contestant asked their own assistant each round"
        (is (= 6 (count replies)))
        (doseq [[id rs] (group-by :actor replies)]
          (is (= (map #(str "reply " % " to: ") [1 2 3])
                 (map #(re-find #"reply \d+ to: " (get-in % [:data :reply])) rs))
              "the session remembers earlier messages")
          (is (every? #(str/includes? (get-in % [:data :reply]) (str "[session " id "]")) rs))))
      (testing "replies are private to the caller"
        (doseq [ev (filter #(#{:quiz/assistant-replied :bridge/called} (:type %)) evs)]
          (is (= #{(:actor ev)} (:visible-to ev))))
        (is (not-any? #(w/visible-to? % "bo") (filter #(= "ana" (:actor %)) replies))))
      (testing "the server's reported cost counts towards the run"
        (is (< 0.0029 (get-in world [:usage "ana" :cost]) 0.0031)))
      (is (= world (w/replay (store/read-events (:dir run))))))
    (let [conns (vals @(:conns (:mcp run)))]
      (is (= 1 (count conns)))
      (e/stop-run run)
      (is (.awaitTermination ^java.util.concurrent.ExecutorService (:exec run) 10 java.util.concurrent.TimeUnit/SECONDS))
      (is (not-any? mcp/alive? conns) "stopping the run closes its MCP servers"))))
