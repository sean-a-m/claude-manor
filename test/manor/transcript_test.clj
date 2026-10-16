(ns manor.transcript-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [manor.engine :as e]
            [manor.store :as store]
            [manor.transcript :as transcript]))

;; All runs here use {:test? true}, so they live under test-runs/.
(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

(deftest transcript-of-a-mock-run
  (let [run (e/start-run :claudeworth {:llm :mock :offline? true :test? true :run-id (str (gensym "transcript-"))})]
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
