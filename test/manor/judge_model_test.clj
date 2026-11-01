(ns manor.judge-model-test
  "Which model an adjudication is on: :judge-model (or :model), unless its
  adjudicator is a character writer (:writer? true), which is on :model."
  (:require [clojure.test :refer [deftest is]]
            [manor.engine.models :as models]
            [manor.engine.resolve :as resolve]
            [manor.mechanics.hiring :as hiring]
            [manor.world :as w]))

(defn- fake-run [opts]
  {:opts opts :world (atom (w/new-world)) :events (atom [])
   :scenario {:adjudicators (assoc hiring/adjudicators
                                   :test/judge {:request (fn [_ _ _ _] {:messages [] :tools []})})}})

(defn- models-asked
  "The model of each task's first call, through a composed query's services.
  The call itself fails (no model is reached), so each task throws."
  [opts tasks]
  (let [seen (atom {})
        run (fake-run opts)]
    (with-redefs [models/complete! (fn [_ request]
                                     (swap! seen assoc (get-in request [:ctx :task]) (:model request))
                                     (throw (ex-info "no model in this test" {})))]
      (let [adjudicate (:adjudicate (resolve/query-services run "ada"))]
        (doseq [t tasks]
          (try (adjudicate t {:record {:position "maid"} :appearance "" :notes ""})
               (catch clojure.lang.ExceptionInfo _)))))
    @seen))

(deftest judges-use-judge-model-and-writers-the-run's-model
  (is (= {:test/judge "run/judge" :people/appearance "run/model" :people/polish "run/model"}
         (models-asked {:model "run/model" :judge-model "run/judge"}
                       [:test/judge :people/appearance :people/polish]))))
