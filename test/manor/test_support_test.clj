(ns manor.test-support-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is use-fixtures]]
            [manor.engine :as e]
            [manor.io.store :as store]
            [manor.test-support :as support])
  (:import (java.util.concurrent Callable CountDownLatch ExecutorService TimeUnit)))

(use-fixtures :once support/with-runs)

(defn- start []
  (e/start-run :treasure {:llm :mock :offline? true :test? true
                          :run-id (str "lifecycle-" (random-uuid))}))

(deftest stopping-waits-for-queued-writes
  (let [run (start)
        entered (CountDownLatch. 1)
        release (CountDownLatch. 1)
        file (io/file (:dir run) "final-write.txt")]
    (.submit ^ExecutorService (:exec run)
             ^Callable (fn []
                         (.countDown entered)
                         (.await release)
                         (spit file "finished")))
    (try
      (is (.await entered 10 TimeUnit/SECONDS))
      (let [closing (future (e/stop-run run))]
        (is (= ::waiting (deref closing 50 ::waiting)))
        (.countDown release)
        @closing
        (is (.isTerminated ^ExecutorService (:exec run)))
        (is (= "finished" (slurp file))))
      (finally
        (.countDown release)
        (e/stop-run run)))))

(deftest fixture-stops-forgotten-runs-and-keeps-their-files-even-on-error
  (let [run (atom nil)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"deliberate test exception"
                         (support/with-runs
                           #(do (reset! run (start))
                                (throw (ex-info "deliberate test exception" {}))))))
    (is (.isTerminated ^ExecutorService (:exec @run)))
    (is (.exists (io/file (:dir @run) "config.edn")))
    (is (.exists (io/file (:dir @run) "events.edn")))))

(deftest tests-leave-unrelated-artifacts-alone
  (let [name (str "keep-" (random-uuid) ".txt")
        file (io/file store/test-runs-root name)]
    (store/write-text! store/test-runs-root name "keep this")
    (support/with-runs #(e/stop-run (start)))
    (is (= "keep this" (slurp file)))))
