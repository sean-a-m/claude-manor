(ns manor.claudeworth-holidays-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [manor.engine :as e]
            [manor.io.store :as store]
            [manor.mechanics.calendar :as calendar]
            [manor.prompt :as prompt]
            [manor.scenarios.claudeworth.holidays :as holidays]
            [manor.world :as w]))

(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

(defn- with-run [scenario opts f]
  (let [run (e/start-run scenario (merge {:llm :mock :offline? true :test? true
                                         :run-id (str "holidays-" (random-uuid))} opts))]
    (try (f run) (finally (e/stop-run run)))))

(defn- entries [world] (get-in world [:calendar :entries]))
(defn- status [world id] (get-in (entries world) [id :lifecycle :status]))

(deftest catalogue-is-explicit-public-and-valid
  (let [records holidays/entries
        by-id (into {} (map (juxt :id identity)) records)]
    (is (vector? records))
    (is (= 58 (count records) (count by-id)))
    (doseq [entry records]
      (is (nil? (calendar/entry-problem {} entry)) (:id entry))
      (is (= :all (:visible-to entry)))
      (is (= #{:id :date :description :visible-to} (set (keys entry)))))
    (testing "fixed dates and period bank-holiday dates"
      (doseq [[id date] [["lady-day-1904" "1904-03-25"]
                         ["midsummer-1904" "1904-06-24"]
                         ["michaelmas-1904" "1904-09-29"]
                         ["christmas-1904" "1904-12-25"]
                         ["easter-1904" "1904-04-03"]
                         ["easter-1905" "1905-04-23"]
                         ["whit-monday-1904" "1904-05-23"]
                         ["whit-monday-1905" "1905-06-12"]
                         ["august-bank-holiday-1904" "1904-08-01"]
                         ["august-bank-holiday-1905" "1905-08-07"]]]
        (is (= date (:date (by-id id))) id)))))

(deftest authored-entries-replace-defaults-without-losing-other-entries
  (let [replacement {:id "christmas-1904" :date "1904-12-25"
                     :description "A private arrangement." :visible-to #{"hale"}
                     :lifecycle {:status :cancelled :revision 3}}
        appointment {:id "appointment" :at "1904-11-03T14:00"
                     :description "An appointment." :visible-to :gm}
        init {:state {:unchanged true} :calendar [replacement appointment]}
        result (holidays/add-to init)
        records (:calendar result)]
    (is (= (:state init) (:state result)))
    (is (= 59 (count records) (count (set (map :id records)))))
    (is (= replacement (first (filter #(= "christmas-1904" (:id %)) records))))
    (is (= appointment (last records)))
    ;; Duplicate authored IDs must still reach the normal validator, not be hidden.
    (is (= 2 (count (filter #(= "appointment" (:id %))
                           (:calendar (holidays/add-to {:calendar [appointment appointment]}))))))))

(deftest both-scenarios-start-with-the-shared-calendar
  (doseq [scenario [:claudeworth :claudeworth-medium]]
    (testing (name scenario)
      ;; The expected entries are those in view from 15 October, whatever the world file says.
      (with-run scenario {:start "1904-10-15T08:00"}
        (fn [run]
          (let [world (e/world run)
                records (entries world)
                expected-visible #{"all-hallows-eve-1904" "all-saints-1904" "all-souls-1904"
                                   "guy-fawkes-1904" "martinmas-1904"}]
            (is (= 30 (get-in world [:calendar :lookahead-days])))
            ;; Besides the holidays, only entries visits manage (the medium son's homecoming).
            (is (= (set (map :id holidays/entries)) (set (keys (remove (comp :managed-by val) records)))))
            (is (= :historical (status world "lady-day-1904")))
            (is (= :pending (status world "lady-day-1905")))
            (is (empty? (calendar/due-records world)))
            (doseq [agent (w/active-agents world) :when (not= :gm (:role agent))]
              (let [section (calendar/character-section world (:id agent))
                    messages (prompt/agent-messages (:scenario run) world (e/events run) (:id agent) {})]
                (is (str/starts-with? (:content (last messages)) section))
                (is (= section (calendar/character-section
                                (assoc-in world [:calendar :entries] (select-keys records expected-visible))
                                (:id agent))))
                ;; Match exact authored descriptions to test projection, not prose semantics.
                (doseq [id expected-visible]
                  (is (str/includes? section (:description (records id))) id))))
            (is (= world (w/replay (store/read-events (:dir run)))))))))))

(deftest stored-holidays-roll-into-view-across-the-year-boundary
  (with-run :claudeworth-medium {:start "1904-12-15T08:00"}
    (fn [run]
      (let [world (e/world run)
            section (calendar/character-section world "lord-claudeworth")]
        (doseq [id ["christmas-1904" "new-year-1905" "epiphany-1905"]]
          (is (str/includes? section (:description (get (entries world) id)))))
        (is (not (str/includes? section (:description (get (entries world) "candlemas-1905")))))
        (is (= :pending (status world "candlemas-1905")))
        (let [later (assoc-in world [:clock :start] "1905-01-07T08:00")
              section (calendar/character-section later "lord-claudeworth")]
          (is (str/includes? section (:description (get (entries world) "candlemas-1905"))))
          (is (not (str/includes? section (:description (get (entries world) "christmas-1904"))))))))))

(deftest a-holiday-gets-one-review-without-extra-world-effects
  (doseq [scenario [:claudeworth :claudeworth-medium]]
    (with-run scenario {:start "1904-11-05T05:00"}
      (fn [run]
        (let [before (e/world run)]
          (is (= :due (status before "guy-fawkes-1904")))
          (is (= ["guy-fawkes-1904"] (map :id (calendar/due-records before))))
          @(e/step! run)
          @(e/step! run)
          (let [world (e/world run)
                reviewed (filter #(= :calendar/reviewed (:type %)) (e/events run))]
            (is (= :reviewed (status world "guy-fawkes-1904")))
            (is (= 1 (count reviewed)))
            (is (= (get-in before [:state :household :funds])
                   (get-in world [:state :household :funds])))
            (is (= (set (keys (:agents before))) (set (keys (:agents world)))))
            (is (not-any? #(= :action/forfeited (:type %)) (e/events run)))
            (is (= world (w/replay (store/read-events (:dir run)))))))))))

(deftest other-worlds-do-not-inherit-the-catalogue
  (doseq [[scenario meeting-place] [[:lodge "front-gate"] [:household "carriage-sweep"]]]
    (with-run scenario {}
      (fn [run]
        (is (nil? (:calendar (e/world run))))
        (is (= meeting-place (get-in (e/world run) [:state :meeting-place])))))))
