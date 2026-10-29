(ns manor.palace-test
  "The palace skeleton: a second setting pack on the shared ruleset, run for
  a mock day."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [manor.actions :as a]
            [manor.clock :as clock]
            [manor.engine :as e]
            [manor.io.store :as store]
            [manor.io.worldfile :as worldfile]
            [manor.mechanics.items :as items]
            [manor.mechanics.people :as people]
            [manor.mechanics.positions :as positions]
            [manor.mechanics.visits :as visits]
            [manor.prompt :as prompt]
            [manor.ruleset.init :as ruleset-init]
            [manor.ruleset.rules :as rules]
            [manor.settings :as settings]
            [manor.world :as w]))

(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

(def architects-tools #{:consult-references :price-list :draw-plans :show-plans :commission})

(deftest the-palace-world-and-its-pack
  (let [wf (:world-file (worldfile/load-world "worlds/palace.edn"))
        pack (settings/for-worldfile wf)]
    (is (empty? (ruleset-init/problems wf)))
    (is (= "qing-palace" (:id pack)))
    (is (= "qing-1780" (people/dataset pack)))
    (is (= #{:imperial :consort :eunuch :palace-maid :official :merchant :visitor} (positions/standings pack)))
    (is (= #{:imperial :consort :eunuch :palace-maid}
           (set (filter #(positions/household-standing? pack %) (positions/standings pack)))))
    (testing "every position the ruleset relies on is in the catalogue"
      (is (every? (:positions pack) [:guest :none (:courier-position pack)])))))

(deftest palace-mock-day-and-replay
  (let [run (e/start-run :palace {:llm :mock :offline? true :test? true
                                 :run-id (str (gensym "palace-day-"))})]
    (try
      (let [world (e/world run)
            agents (w/active-agents world)
            view #(prompt/agent-view (:scenario run) world %)]
        (testing "five people, a cost guard, and the household of the inner court"
          (is (= 5 (count (remove #(= :gm (:role %)) agents))))
          (is (= 0.20 (get-in run [:opts :max-cost])))
          (is (= #{"sumuru-wanxia" "tan-qiaoyun" "zhao-deshou" "liu-cuiping"}
                 (set (filter #(positions/household-member? world %) (map :id agents))))))
        (testing "names are family name first, and so are forms of address"
          (is (= "Noble Lady Tan" (positions/addressed-as world "tan-qiaoyun")))
          (is (= "Chief Eunuch Zhao" (positions/addressed-as world "zhao-deshou")))
          (is (= "Cuiping" (positions/addressed-as world "liu-cuiping"))))
        (testing "money in taels and cash: the household's funds and private purses"
          (is (= "200 taels" (get-in (view "zhao-deshou") [:household :funds])))
          (is (= "30 taels" (get-in (view "sumuru-wanxia") [:you :purse])))
          (is (= "5 taels" (get-in (view "zhao-deshou") [:you :purse])))
          (is (nil? (get-in (view "liu-cuiping") [:you :purse]))))
        (testing "the clock shows no weekday"
          (is (= "20 October 1784, 08:30" (clock/world-label world 2))))
        (testing "no architect's office: the architect's tools are never offered"
          (doseq [agent agents]
            (is (not-any? #(architects-tools (:name %)) (a/available (:actions run) world (:id agent)))))
          (doseq [tool architects-tools]
            (is (not (.contains (rules/rules world) (str "`" (name tool) "`")))))))
      (e/auto! run 48)
      (is (= :idle (e/wait-idle run 120000)))
      (let [world (e/world run)
            events (e/events run)
            calls (store/read-llm (:dir run))
            of-type (fn [t] (filter #(= t (:type %)) events))]
        (is (= 48 (:round world)))
        (doseq [type [:agent/called :conversation/started :shop/goods-quoted :shop/bought
                      :deliveries/arrived :item/taken :item/given :item/put-in :item/work-done
                      :visits/entrusted :visits/invited :visits/arrived :items/steward-ran]]
          (is (seq (of-type type)) (name type)))
        (testing "one purchase from the household's account and one from Zhao's own purse"
          (is (= #{"household" "zhao-deshou"} (set (map (comp :account :data) (of-type :shop/bought)))))
          (is (= (- 200000 36) (get-in world [:state :accounts "household"])))
          (is (= (- 5000 18) (get-in world [:state :accounts "zhao-deshou"])))
          (is (= "4 taels 982 cash" (get-in (prompt/agent-view (:scenario run) world "zhao-deshou") [:you :purse]))))
        (testing "the hairpin was given, and the stores put away"
          (is (= "tan-qiaoyun" (:holder (items/item world "jade-hairpin"))))
          (is (= "supply-shelves" (:in (items/item world "bundle-of-candles"))))
          (is (= "supply-shelves" (:in (items/item world "basket-of-charcoal")))))
        (testing "the stall keeper came at the consort's message, and was asked again in person"
          (is (= [:messenger :in-person] (map :by (sort-by :id (vals (visits/visits world))))))
          (is (:arrived (visits/visit world "visit-1"))))
        (is (some #(= "judge" (:agent %)) calls))
        (is (not-any? #(#{:action/forfeited :item/judge-failed :items/steward-failed :adjudication/failed :run/paused} (:type %)) events))
        (is (not-any? #(re-find #"^(error:|rejected:)" (:content % "")) (mapcat :replies calls)))
        (is (= world (w/replay (store/read-events (:dir run))))))
      (finally (e/stop-run run)))))
