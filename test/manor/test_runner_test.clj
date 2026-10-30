(ns manor.test-runner-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [clojure.tools.namespace.find :as find]
            [manor.test-runner :as runner]))

(deftest discovery-and-filters-do-not-load-test-code
  (with-redefs [find/find-namespaces-in-dir (constantly '[alpha-test beta-test helpers])]
    (is (= '[alpha-test beta-test] (#'runner/selected-namespaces {})))
    (is (= '[alpha-test helpers]
           (#'runner/selected-namespaces {:namespace #{'helpers} :namespace-regex [#"alpha-.*"]})))
    (is (= '[beta-test]
           (#'runner/selected-namespaces {:var #{'beta-test/check}})))
    (is (empty? (#'runner/selected-namespaces {:namespace #{'missing-test}})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"var namespaces"
                         (#'runner/selected-namespaces {:var #{'alpha-test/check 'missing-test/check}})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fully qualified"
                         (#'runner/selected-namespaces {:var #{'unqualified}})))))

(deftest sharding-assigns-every-namespace-once
  (let [nses (mapv #(symbol (str "example-" % "-test")) (range 11))
        groups (#'runner/shards nses 4)]
    (is (= [3 3 3 2] (mapv count groups)))
    (is (= (set nses) (set (mapcat identity groups))))
    (is (= (count nses) (count (mapcat identity groups))))))

(defn- completed-worker [exit summary]
  (let [dir (io/file (System/getProperty "manor.test-runs-root" "test-runs")
                     (str "runner-unit-" (random-uuid)))
        log (io/file dir "output.log")
        result (io/file dir "summary.edn")]
    (#'runner/write-edn! log "worker output")
    (when summary (#'runner/write-edn! result (assoc summary :seconds 0.01)))
    {:process (proxy [Process] []
                (waitFor [] exit)
                (exitValue [] exit)
                (isAlive [] false))
     :result result :log log :index 1}))

(deftest worker-failure-cannot-be-reported-as-success
  (doseq [[exit counts expected-error]
          [[0 {:test 2 :pass 3 :fail 0 :error 0} 0]
           [1 {:test 2 :pass 2 :fail 1 :error 0} 0]
           [7 nil 1]
           [7 {:test 2 :pass 3 :fail 0 :error 0} 1]]]
    (let [worker (completed-worker exit counts)
          result (atom nil)]
      (with-out-str (reset! result (#'runner/collect! worker)))
      (is (= expected-error (:error @result)))
      (when (= exit 1) (is (= 1 (:fail @result)))))))

(deftest suite-invocations-and-workers-have-distinct-roots
  (let [launched (atom [])
        counts {:test 1 :pass 1 :fail 0 :error 0}]
    (with-redefs-fn {#'runner/selected-namespaces (constantly '[a-test b-test])
                    #'runner/launch! (fn [root options i nses]
                                       (swap! launched conj {:root (str root) :index i :nses nses
                                                            :options options})
                                       {})
                    #'runner/collect! (constantly counts)
                    #'runner/stop-workers! (fn [_])}
      #(with-out-str
         (runner/test {:workers 4})
         (runner/test {:workers 4})))
    (is (= [0 1 0 1] (mapv :index @launched)))
    (is (= 2 (count (set (map :root @launched)))))
    (is (every? #(= 1 (count (:nses %))) @launched))))

(deftest no-matches-and-invalid-worker-counts-fail
  (with-redefs-fn {#'runner/selected-namespaces (constantly [])}
    #(do
       (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No test namespaces matched"
                            (runner/test {})))
       (is (thrown-with-msg? clojure.lang.ExceptionInfo #"positive integer"
                            (runner/test {:workers 0}))))))

(deftest empty-test-selection-fails-even-when-workers-exit-successfully
  (with-redefs-fn {#'runner/selected-namespaces (constantly '[a-test])
                  #'runner/launch! (fn [& _] {})
                  #'runner/collect! (constantly {:test 0 :pass 0 :fail 0 :error 0})
                  #'runner/stop-workers! (fn [_])}
    #(with-out-str
       (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Test failures"
                            (runner/test {:includes [:does-not-exist]}))))))

(deftest exec-api-retains-cognitect-selection-options
  (let [seen (atom nil)]
    (with-redefs-fn {#'runner/run-suite! (fn [options workers]
                                         (reset! seen [options workers])
                                         {:test 1 :pass 1 :fail 0 :error 0})}
      #(runner/test {:dirs ["test"] :nses '[a-test] :patterns ["b-.*"] :vars '[a-test/check]
                     :includes [:fast] :excludes [:slow] :workers 2}))
    (let [[options workers] @seen]
      (is (= 2 workers))
      (is (= #{"test"} (:dir options)))
      (is (= #{'a-test} (:namespace options)))
      (is (= #{'a-test/check} (:var options)))
      (is (= ["b-.*"] (mapv str (:namespace-regex options))))
      (is (= #{:fast} (:include options)))
      (is (= #{:slow} (:exclude options))))))
