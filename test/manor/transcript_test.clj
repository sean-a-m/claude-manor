(ns manor.transcript-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [manor.engine :as e]
            [manor.store :as store]
            [manor.transcript :as transcript])
  (:import (java.util.concurrent TimeUnit)))

;; All runs here use {:test? true}, so they live under test-runs/.
(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

(deftest transcript-of-a-mock-run
  (let [run (e/start-run :household {:llm :mock :offline? true :test? true :run-id (str (gensym "transcript-"))})]
    (e/auto! run 2)
    (is (= :idle (e/wait-idle run 60000)))
    (let [path (transcript/write! (:dir run))
          md (slurp path)]
      (is (= (str (io/file (:dir run) "transcript.md")) path))
      (is (str/starts-with? md (str "# Transcript of " (:dir run))))
      (is (str/includes? md "2 rounds, "))
      (is (str/includes? md "| lord-claudeworth | "))
      (is (str/includes? md "## 08:00 (round 1)"))
      (is (str/includes? md "- Lord Claudeworth goes from Earl's bedroom to Dining room.  _[lord-claudeworth]_"))
      (is (str/includes? md "- **lord-claudeworth** walk {\"to\":\"dining-room\"} _($0.0000)_\n  - engine: ok\n- **lord-claudeworth** say"))
      (is (str/includes? md "## 08:30 (round 2)"))
      (testing "a turn's last call shows its replies"
        (is (re-find #"- \*\*lord-claudeworth\*\* say .*\n  - engine: done\n" md))
        (is (not (str/includes? md "didn't record replies")))))
    (testing "runs logged before replies were recorded read them from the next call"
      (let [f (io/file (:dir run) "llm.edn")
            old-style (map #(dissoc % :replies) (store/read-llm (:dir run)))]
        (spit f (apply str (map #(str (pr-str %) "\n") old-style)))
        (let [md (transcript/render (:dir run))]
          (is (str/includes? md "_($0.0000)_\n  - engine: ok\n- **lord-claudeworth** say"))
          (is (not (re-find #"- \*\*lord-claudeworth\*\* say .*\n  - engine: done\n" md)))
          (is (str/includes? md "(this run didn't record replies to a turn's last call)")))))
    (e/stop-run run)))

(defn- written-rounds
  "The round count in <run-dir>/transcript.md's heading, or nil if there is none."
  [run]
  (let [f (io/file (:dir run) "transcript.md")]
    (when (.exists f) (some-> (re-find #"\n(\d+) rounds, " (slurp f)) second parse-long))))

(deftest transcripts-are-written-when-a-run-stops
  (testing "a pause on :max-rounds"
    (let [run (e/start-run :household {:llm :mock :offline? true :test? true :max-rounds 3
                                         :run-id (str (gensym "transcript-"))})]
      (is (nil? (written-rounds run)))
      (e/auto! run)
      (is (= :idle (e/wait-idle run 60000)))
      (is (= ["max rounds reached"] (:pause-reasons (e/status run))))
      (is (= 3 (written-rounds run)))
      (is (str/includes? (slurp (io/file (:dir run) "transcript.md")) "## 09:00 (round 3)"))
      (e/stop-run run)))
  (testing "stop-run, while auto mode waits between rounds"
    (let [run (e/start-run :household {:llm :mock :offline? true :test? true :auto-delay-ms 200
                                         :run-id (str (gensym "transcript-"))})]
      (e/auto! run)
      (Thread/sleep 300)
      (e/stop-run run)
      (is (.awaitTermination ^java.util.concurrent.ExecutorService (:exec run) 10 TimeUnit/SECONDS))
      (is (pos? (:round (e/world run))))
      (is (= (:round (e/world run)) (written-rounds run))))))

(deftest thoughts-are-shown-when-recorded
  (let [run (e/start-run :household {:llm :mock :offline? true :test? true
                                       :run-id (str (gensym "transcript-"))})]
    (e/auto! run 1)
    (is (= :idle (e/wait-idle run 60000)))
    (testing "a mock run records no thoughts, so none are shown"
      (is (every? nil? (map :thoughts (store/read-llm (:dir run)))))
      (is (not (str/includes? (transcript/render (:dir run)) "- thoughts: "))))
    (testing "thoughts and their token count show under the call"
      (let [f (io/file (:dir run) "llm.edn")
            calls (map #(assoc % :thoughts "Where is everyone?"
                               :usage (assoc (:usage %) :thought-tokens 300))
                       (store/read-llm (:dir run)))]
        (spit f (apply str (map #(str (pr-str %) "\n") calls)))
        (let [md (transcript/render (:dir run))]
          (is (str/includes? md "  - thoughts: Where is everyone?"))
          (is (str/includes? md ", 300 in thought)")))))
    (e/stop-run run))) 
