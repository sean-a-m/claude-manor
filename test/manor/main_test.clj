(ns manor.main-test
  (:require [clojure.test :refer [deftest is]]
            [manor.engine :as e]
            [manor.io.store :as store]
            [manor.main :as main]))

(deftest max-rounds-cli-option
  (let [{:keys [scenario opts port]} (#'main/parse-args ["lodge" "--max-rounds" "6" "--mock" "--offline" "--test" "--port" "8081"])
        run (e/start-run scenario (assoc opts :run-id (str (gensym "main-max-rounds-"))))]
    (try
      (is (= :lodge scenario))
      (is (= 8081 port))
      (is (= {:max-rounds 6 :llm :mock :offline? true :test? true} opts))
      (e/auto! run 10)
      (is (= :idle (e/wait-idle run 60000)))
      (is (= 6 (:round (e/world run))))
      (is (= ["max rounds reached"] (:pause-reasons @(:status run))))
      (finally
        (e/stop-run run)
        (store/delete-test-runs!)))))
