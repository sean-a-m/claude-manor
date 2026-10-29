(ns manor.settings-test
  (:require [clojure.test :refer [deftest is testing]]
            [manor.mechanics.positions :as positions]
            [manor.ruleset.init :as ruleset-init]
            [manor.settings :as settings]))

(deftest england-is-the-default-for-worlds-and-old-states
  (let [pack (settings/load-pack settings/default-path)]
    (is (= "england-1904" (:id pack)))
    (is (= pack (settings/pack {})))
    (is (= pack (settings/for-worldfile {:meta {}})))
    (is (string? (get-in pack [:prompts "money.md"])))
    (is (string? (get-in pack [:prompts "money-pricing.md"])))
    (is (string? (get-in pack [:prompts "invitation-examples.md"])))
    (is (= (:positions pack) (positions/table {} {})))
    (is (= (:calendar pack) (:calendar (ruleset-init/add-holidays {}))))))

(deftest init-stores-the-pack-and-catalogue-overrides-still-work
  (let [init (ruleset-init/init {:world-file "worlds/claudeworth.edn"})]
    (is (= (settings/load-pack settings/default-path)
           (get-in init [:state :setting-pack])))
    (is (= (positions/read-catalogue "settings/england-1904/positions.edn")
           (:positions (settings/pack init)))))
  (testing "an explicit catalogue wins, then the world's roles"
    (with-redefs [positions/read-catalogue (fn [source]
                                           (is (= "custom.edn" source))
                                           {:worker {:title "Worker" :about "Work."}})]
      (is (= {:worker {:title "Worker" :about "Local work."}}
             (positions/table {:meta {:positions "custom.edn"}
                               :roles {:worker {:about "Local work."}}} {}))))))

(deftest holidays-read-the-snapshot-not-a-fixed-file
  (let [entry {:id "test-day" :date "2000-01-01" :description "A day." :visible-to :all}
        init {:state {:setting-pack {:id "test" :calendar [entry]}}}]
    (is (= [entry] (:calendar (ruleset-init/add-holidays init))))))
