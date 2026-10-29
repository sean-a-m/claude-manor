(ns manor.prompt-test
  "How requests are laid out for the provider's prompt cache: a stable start
  (system message, tools in a fixed order, a window of events that only grows
  between jumps), and what changes every call at the end."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [manor.actions :as a]
            [manor.engine :as e]
            [manor.io.store :as store]
            [manor.prompt :as prompt]))

(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

(defn- said [n] {:type :say :visible-to :all :round 1 :data {:n n}})

(defn- shown [total window]
  (map (comp :n :data) (prompt/visible-events (map said (range total)) "ada" window)))

(deftest the-event-window-jumps
  (testing "fewer than the window: all of them"
    (is (= (range 5) (shown 5 30))))
  (testing "the window grows at its end, up to 2n-1 events"
    (is (= (range 59) (shown 59 30))))
  (testing "then its start jumps by n"
    (is (= (range 30 60) (shown 60 30)))
    (is (= (range 30 89) (shown 89 30)))
    (is (= (range 60 90) (shown 90 30))))
  (testing "always at least n and fewer than 2n once there are n"
    (doseq [total (range 30 200)
            :let [c (count (shown total 30))]]
      (is (<= 30 c 59) total)))
  (testing "a zero window shows nothing"
    (is (empty? (shown 10 0)))))

(deftest tools-keep-a-fixed-order
  (let [act (fn [n & [avail?]] (cond-> {:name n} avail? (assoc :available? (constantly true))))]
    (testing "a later list overrides an action but keeps its first place"
      (is (= [:a :b :c] (map :name (a/merge-actions [(act :a) (act :b)] [(act :c) (assoc (act :a) :doc "new")]))))
      (is (= "new" (:doc (first (a/merge-actions [(act :a)] [(assoc (act :a) :doc "new")]))))))
    (testing "more than eight actions stay in declaration order"
      (let [names (map #(keyword (str "t" %)) (range 20))]
        (is (= names (map :name (apply a/merge-actions (map (comp vector act) names)))))))
    (testing "actions that come and go are listed after the rest"
      (is (= [:a :c :b :d] (map :name (a/available [(act :a) (act :b true) (act :c) (act :d true)] {} "ada")))))))

(deftest a-request-is-laid-out-for-the-cache
  (let [run (e/start-run :household {:llm :mock :offline? true :test? true :run-id (str (gensym "prompt-"))})
        world (e/world run)
        [system user] (map :content (prompt/agent-messages (:scenario run) world (e/events run) "hale" {}))]
    (testing "the system message ends with the reference"
      (is (str/includes? system "\n\n## Reference\n"))
      (is (str/includes? system "\"places\""))
      (is (str/includes? system "\"positions\"")))
    (testing "the user message has the events first, then now, the view, the notes and the turn"
      (is (str/starts-with? user "## Recent events you witnessed\n"))
      (is (apply < (map #(str/index-of user %) ["## Recent events" "\n# Now: " "\n## Your view\n" "\n## Your notes\n" "\n## Your turn\n"])))
      (is (not (str/includes? user "\"places\"")) "the reference isn't repeated in the view")
      (is (not (str/includes? user "\n  \"")) "the view is compact JSON"))
    (e/stop-run run)))

(deftest the-gm-view-leaves-out-the-setting-pack
  (let [world {:state {:setting "A house." :setting-pack {:id "test" :positions {:cook {}}}
                       :quotes {}}
               :agents {}}]
    (is (= {:quotes {}} (:state (prompt/default-gm-view world))))))
