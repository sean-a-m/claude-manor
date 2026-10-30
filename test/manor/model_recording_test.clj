(ns manor.model-recording-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [manor.engine :as e]
            [manor.engine.models :as models]
            [manor.io.store :as store]
            [manor.test-support :as support])
  (:import (java.util.concurrent CountDownLatch)))

(use-fixtures :once support/with-runs)

(deftest simultaneous-calls-produce-complete-edn-records
  (let [run (e/start-run :treasure {:llm :mock :offline? true :test? true
                                   :run-id (str "recording-" (random-uuid))})
        gate (CountDownLatch. 1)
        active (atom 0)
        maximum (atom 0)
        append store/append-llm!
        ;; Larger than a writer buffer, including quotes, slashes and newlines.
        content (apply str (repeat 2000 "a \"quoted\" value\\\n"))
        request {:model "mock" :messages [{:role "user" :content content}] :tools []}
        response {:message {:role "assistant" :content "done"} :usage {:cost 0}}]
    (with-redefs [store/append-llm!
                  (fn [dir entry]
                    (swap! maximum max (swap! active inc))
                    (try
                      ;; Make overlapping entry to the writer observable, not
                      ;; dependent on the filesystem's own buffer scheduling.
                      (Thread/sleep 2)
                      (append dir entry)
                      (finally (swap! active dec))))]
      (let [writers (mapv (fn [worker]
                            (future
                              (.await gate)
                              (dotimes [i 4]
                                (models/record-call! run (str worker "-" i) request response []))))
                          (range 8))]
        (.countDown gate)
        (run! deref writers)))
    (let [records (store/read-llm (:dir run))]
      (is (= 1 @maximum))
      (is (= 32 (count records)))
      (is (= (set (for [worker (range 8) i (range 4)] (str worker "-" i)))
             (set (map :agent records))))
      (is (every? #(= content (get-in % [:messages 0 :content])) records)))
    (e/stop-run run)))
