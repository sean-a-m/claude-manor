(ns manor.made-review-test
  "The work judge's second look at new things it made (manor.mechanics.items.made-review)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [manor.engine :as e]
            [manor.engine.judges :as judges]
            [manor.io.llm :as llm]
            [manor.io.store :as store]
            [manor.mechanics.items :as items]
            [manor.mechanics.items.made-review :as review]
            [manor.mechanics.items.work :as item-work]
            [manor.test-support :as support]))

(use-fixtures :once support/with-runs)
(use-fixtures :each (partial support/with-structured-attempts 4))

(def things
  [{:id "remote" :name "Account book" :desc "The household's account book." :place "bedroom"}])

(def job {:id "job-1" :actor "ada" :place "hall" :items ["hall"] :doing "writing up the accounts" :started 0 :due 1})

(def verdict {:summary "Ada wrote up the accounts in the book." :changes []
              :made [{:name "Account book" :desc "A book of accounts." :consumable false}
                     {:name "Loaf" :desc "A loaf." :consumable true :from "shelves"}
                     {:name "Note" :desc "A note of the totals." :consumable false}]})

(defn- start [& [opts]]
  (let [run (e/start-run :hamlet (merge {:llm :mock :test? true :gm :off :run-id (str (gensym "review-"))} opts))]
    (e/emit! run (items/init-events things))
    run))

(defn- response [tool args] {:tool-calls [{:id "r1" :name tool :arguments (json/write-value-as-string args)}]})

(deftest which-made-things-are-reviewed
  (is (= [0 2] (map first (review/fresh verdict))) "drawn from a store or taken from an aspect: not reviewed")
  (is (review/needed? job verdict))
  (is (not (review/needed? (assoc job :act? true) verdict)))
  (is (not (review/needed? (assoc job :reference {:candidate-id "p"}) verdict)) "a hiring reference is new by definition")
  (is (not (review/needed? job (update verdict :made #(filterv :from %))))))

(deftest review-checks-and-outcomes
  (let [run (start)]
    (try
      (let [world (e/world run)
            {:keys [candidates messages tools]} (review/review-request world job verdict [{:role "system" :content "s"}] (e/events run))
            check #(review/review-check world job verdict candidates (response "review" %))]
        (is (= ["review" "inspect"] (map #(get-in % [:function :name]) tools)))
        (is (str/includes? (:content (last messages)) "\"index\" : 2"))
        (testing "everything new: the verdict stands"
          (is (= verdict (:verdict (check {:made [{:index 0} {:index 2}]})))))
        (testing "the same as something out of reach: not made, and a new summary is needed"
          (is (str/includes? (:error (check {:made [{:index 0 :same "remote"} {:index 2}]})) "new summary"))
          (let [v (:verdict (check {:made [{:index 0 :same "remote"} {:index 2}] :summary "The book wasn't to hand; Ada noted the totals."}))]
            (is (= ["Loaf" "Note"] (map :name (:made v))))
            (is (= "The book wasn't to hand; Ada noted the totals." (:summary v)))))
        (testing "the same as something here: used, not copied, and the summary may stand"
          (let [v (:verdict (check {:made [{:index 0} {:index 2 :same "umbrella"}]}))]
            (is (= ["Account book" "Loaf"] (map :name (:made v))))
            (is (= (:summary verdict) (:summary v)))))
        (testing "structure: every fresh index once, and only listed candidates"
          (is (:error (check {:made [{:index 0}]})))
          (is (:error (check {:made [{:index 0} {:index 2} {:index 2}]})))
          (is (:error (check {:made [{:index 0} {:index 1}]})))
          (is (str/includes? (:error (check {:made [{:index 0 :same "cupboard"} {:index 2}]})) "not cupboard"))))
      (finally (e/stop-run run)))))

(deftest the-work-judge-reviews-in-the-same-conversation
  (let [roles (atom [])
        run (start {:llm-impl (llm/mock (fn [{:keys [role looked]}]
                                          (swap! roles conj role)
                                          (case role
                                            :judge {:tool :judge :args (-> verdict (dissoc :changes) (update :made #(mapv (fn [m] (dissoc m :from)) (take 1 %))))}
                                            :made-review (if (zero? looked)
                                                           {:tool :inspect :args {:ids ["remote"]}}
                                                           {:tool :review :args {:made [{:index 0 :same "remote"}]
                                                                                 :summary "The account book wasn't to hand."}}))))})]
    (try
      (let [world (e/world run)
            {:keys [verdict records failure]} (judges/ask-judge run world job {:minutes 60 :luck 50 :stopped-early? false}
                                                                (item-work/judged-things world job))]
        (is (nil? failure))
        (is (= [:judge :made-review :made-review] @roles))
        (is (nil? (:made verdict)))
        (is (= "The account book wasn't to hand." (:summary verdict)))
        (is (not (contains? verdict :messages)))
        (doseq [r records] (r))
        (let [llm (store/read-llm (:dir run))]
          (is (= 3 (count llm)))
          (is (every? #(= "judge" (:agent %)) llm))
          (is (some #(str/includes? (str (:content %)) "The household's account book.") (mapcat :replies llm))
              "inspect answered in the review")
          (is (str/includes? (pr-str (:messages (last llm))) "Ada wrote up the accounts") "the review continues the judge's conversation")))
      (finally (e/stop-run run)))))
