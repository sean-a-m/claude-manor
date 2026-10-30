(ns manor.ruleset-test
  "The ruleset's world-file data: the meeting place and the home area."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [manor.actions :as a]
            [manor.engine :as e]
            [manor.test-support :as support]
            [manor.io.worldfile :as worldfile]
            [manor.mechanics.space :as space]
            [manor.ruleset.init :as ruleset-init]
            [manor.ruleset.views :as views]))

(use-fixtures :once support/with-runs)

(def architect-actions #{:consult-references :price-list :draw-plans :show-plans :commission})

(deftest meeting-place-and-home-domain-are-checked
  (let [wf (:world-file (worldfile/load-world "worlds/lodge.edn"))]
    (is (empty? (ruleset-init/problems wf)))
    (is (= [":meta :meeting-place must be the id of a location, where people from elsewhere arrive, not nil"]
           (ruleset-init/problems (update wf :meta dissoc :meeting-place))))
    (is (= [":meta :meeting-place must be the id of a location, where people from elsewhere arrive, not \"nowhere\""]
           (ruleset-init/problems (assoc-in wf [:meta :meeting-place] "nowhere"))))
    (is (= [":meta :home-domain :palace is not the :domain of any location"]
           (ruleset-init/problems (assoc-in wf [:meta :home-domain] :palace))))
    (is (empty? (ruleset-init/problems (assoc-in wf [:meta :home-domain] :village))))))

(deftest the-home-area-is-the-meeting-places-domain-unless-the-world-says
  (let [run (e/start-run :lodge {:llm :mock :offline? true :test? true :run-id (str (gensym "ruleset-home-"))})]
    (try
      (let [world (e/world run)
            bedrooms #(set (map :id (get-in (views/observe % "mrs-ashby") [:household :bedrooms])))]
        (testing "from the world file's :meta :meeting-place"
          (is (= "front-gate" (space/meeting-place world)))
          (is (not (contains? (:state world) :home-domain)))
          (is (= :house (space/home-domain world)))
          (is (= #{"mistress-bedroom" "maids-room" "cooks-room"} (bedrooms world))))
        (testing "or :home-domain"
          (let [elsewhere (assoc-in world [:state :home-domain] :village)]
            (is (= :village (space/home-domain elsewhere)))
            (is (= #{"shop-lodgings"} (bedrooms elsewhere)))))
        (testing "the architect's actions are offered only with an office"
          (is (not-any? #(architect-actions (:name %)) (a/available (:actions run) world "mrs-ashby")))
          (let [office (assoc-in world [:space :locations "parlour" :services] #{:architect})]
            (is (= architect-actions
                   (set (keep (comp architect-actions :name) (a/available (:actions run) office "mrs-ashby"))))))))
      (finally (e/stop-run run)))))
