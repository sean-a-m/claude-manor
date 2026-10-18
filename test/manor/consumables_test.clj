(ns manor.consumables-test
  "Consumables (manor.items): items marked :consumable? that the judge may
  use up when work ends, and the things it makes from them, in a variant of
  the hamlet world with a kitchen's worth of things in the hall. Checks on
  the judge's answer, the :item/work-done event and its reducer, narration,
  a mock run, replay and the exported map."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [manor.engine :as e]
            [manor.items :as items]
            [manor.llm :as llm]
            [manor.narrate :as n]
            [manor.prompt :as prompt]
            [manor.scenarios.hamlet :as hamlet]
            [manor.store :as store]
            [manor.transcript :as transcript]
            [manor.world :as w]
            [manor.worldfile :as wf]))

;; All runs here use {:test? true}, so they live under test-runs/.
(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

(def consumables
  "In the hall: a roasted rib of beef (Ada's), an open saucepan, a paper bag
  holding a bun, and a fixed woodpile; Ben carries an apple."
  [{:id "beef" :name "Rib of beef" :place "hall" :owner "Ada" :consumable? true :aspects {:state "roasted, on the board"} :desc "A roasted rib of beef."}
   {:id "saucepan" :name "Saucepan" :place "hall" :container :open :aspects {:cleanliness "clean"} :desc "A copper saucepan."}
   {:id "bag" :name "Paper bag" :place "hall" :container :open :consumable? true :aspects {:state "crumpled"} :desc "A baker's paper bag."}
   {:id "bun" :name "Bun" :in "bag" :consumable? true :aspects {:state "fresh"} :desc "A currant bun."}
   {:id "woodpile" :name "Woodpile" :place "hall" :fixed? true :consumable? true :aspects {:state "stacked"} :desc "Split logs by the hearth."}
   {:id "apple" :name "Apple" :holder "Ben" :consumable? true :aspects {:state "ripe"} :desc "A red apple."}])

(def world-file
  (update (wf/read-file "test/fixtures/hamlet.edn") :items into consumables))

(defn- file-of
  "`wf` written under test-runs/, as a path."
  [wf]
  (let [file-name (str (gensym "consumables-") ".edn")]
    (store/write-text! store/test-runs-root file-name (wf/->edn-string wf))
    (str (io/file store/test-runs-root file-name))))

(defn- start [& [opts]]
  (e/start-run :hamlet (merge {:llm :mock :test? true :run-id (str (gensym "consumables-"))
                               :world-file (file-of world-file)}
                              opts)))

(defn- rounds! [run n]
  (e/auto! run n)
  (is (= :idle (e/wait-idle run 60000)))
  run)

(defn- of-type [run t] (filter #(= t (:type %)) (e/events run)))

(def job {:id "job-9" :actor "ben" :items ["beef" "saucepan" "bag" "woodpile" "apple" "clock"]
          :doing "making stock" :place "hall" :started 1 :due 3})

(defn- judge-says [args]
  {:tool-calls [{:id "j1" :name "judge" :arguments (json/write-value-as-string args)}]})

(def stock {:name "Beef stock" :desc "A rich brown stock." :aspects {:state "simmering"} :in "saucepan"})
(def bones {:name "Beef bones" :desc "The rib bones, picked clean." :aspects {:state "picked clean"}})

;; ---------------------------------------------------------------------------
;; The judge's answer

(deftest the-judge-sees-what-is-consumable
  (let [run (start)
        world (e/world run)
        user (:content (second (:messages (items/judge-request (:scenario run) world job {:minutes 60 :luck 50}))))
        judged (:focus (json/read-value (second (re-find #"(?s)```json\n(.*)\n```" user)) json/keyword-keys-object-mapper))
        system (:content (first (:messages (items/judge-request (:scenario run) world job {:minutes 60}))))]
    (is (= {"beef" [true nil] "saucepan" [nil true] "bag" [true true] "bun" [true nil] "clock" [nil nil]}
           (select-keys (into {} (map (juxt :id (juxt :consumable :container))) judged)
                        ["beef" "saucepan" "bag" "bun" "clock"])))
    (is (str/includes? system "Only things marked `consumable` can be used up"))
    (is (str/includes? system "Something partly used is not used up"))
    (is (str/includes? system "An item's nature can't change"))
    (e/stop-run run)))

(deftest checking-used-up-and-made
  (let [run (start)
        world (e/world run)
        reach (items/judged-things world job)
        judge #(items/judgement (judge-says %) world reach)
        error #(:error (judge (assoc % :summary "Done.")))]
    (testing "a good answer: what's used up, and what's made from it; a change to a used-up item is dropped"
      (is (= {:summary "The beef went into stock." :changes [{:item "saucepan" :aspect :cleanliness :from "clean" :to "greasy"}]
              :used-up ["beef"] :made [stock bones]}
             (:verdict (judge {:changes [{:thing "saucepan" :aspect "cleanliness" :value "greasy"} {:thing "beef" :aspect "state" :value "carved"}]
                               :used_up ["beef" "beef"] :made [stock bones] :summary "The beef went into stock."})))))
    (testing "using up without making anything is fine"
      (is (= {:summary "Eaten." :changes [] :used-up ["apple"]} (:verdict (judge {:used-up ["apple"] :summary "Eaten."})))))
    (testing "only consumable, unfixed items in reach may be used up"
      (is (= "you may use up only consumable items among these: beef, bag, bun, apple; not saucepan"
             (error {:used-up ["beef" "saucepan"]})))
      (is (= "you may use up only consumable items among these: beef, bag, bun, apple; not umbrella"
             (error {:used-up ["umbrella"]})))
      (is (str/ends-with? (error {:used-up ["woodpile"]}) "; not woodpile")))
    (testing "a container is used up only with everything in it"
      (is (= "the Paper bag (bag) still holds bun: use those up too, or don't use it up" (error {:used-up ["bag"]})))
      (is (= ["bag" "bun"] (get-in (judge {:used-up ["bag" "bun"] :summary "Done."}) [:verdict :used-up]))))
    (testing "nothing is made without something used up"
      (is (str/starts-with? (error {:made [bones]}) "things are made only from consumable items the work used up")))
    (testing "at most two things made"
      (is (str/includes? (error {:used-up ["beef"] :made [stock bones bones]}) "made has 3 items")))
    (testing "in names a container in reach that isn't used up"
      (is (= "in must be the id of a container among these: saucepan, bag; not clock"
             (error {:used-up ["beef"] :made [(assoc stock :in "clock")]})))
      (is (= "in must be the id of a container among these: saucepan; not bag"
             (error {:used-up ["beef" "bag" "bun"] :made [(assoc stock :in "bag")]})))
      (is (str/ends-with? (error {:used-up ["beef"] :made [(assoc stock :in "cellar")]}) "; not cellar")))
    (testing "made things need a name and a description within the limits; aspects are optional, and checked"
      (is (error {:used-up ["beef"] :made [(dissoc bones :desc)]}))
      (is (error {:used-up ["beef"] :made [(assoc bones :name (apply str (repeat 61 "x")))]}))
      (is (= [{:name "Beef bones" :desc "The rib bones, picked clean."}]
             (:made (:verdict (judge {:used-up ["beef"] :made [(dissoc bones :aspects)] :summary "x"})))))
      (is (= {:picked-state "clean"}
             (:aspects (first (:made (:verdict (judge {:used-up ["beef"] :made [(assoc bones :aspects {"picked_state" "clean"})] :summary "x"})))))))
      (is (= "an aspect's key is lower-case letters and hyphens, at most 24 characters, starting with a letter (cleanliness, glass-eye); not \"bone count\""
             (error {:used-up ["beef"] :made [(assoc bones :aspects {"Bone Count" "three"})]})))
      (is (str/includes? (error {:used-up ["beef"] :made [(assoc bones :aspects (zipmap ["a" "b" "c" "d" "e"] (repeat "x")))]})
                         "should have at most 4 elements"))
      (is (= "made \"Beef bones\": its state is 81 characters; give it 1 to 80"
             (error {:used-up ["beef"] :made [(assoc bones :aspects {"state" (apply str (repeat 81 "x"))})]})))
      (is (= "made \"Beef bones\": its state is 0 characters; give it 1 to 80"
             (error {:used-up ["beef"] :made [(assoc bones :aspects {"state" ""})]}))))
    (testing "the reason goes back to the judge"
      (is (= "error: the Paper bag (bag) still holds bun: use those up too, or don't use it up. Call judge again with a corrected answer."
             (:content (first (:replies (judge {:used-up ["bag"] :summary "x"})))))))
    (e/stop-run run)))

;; ---------------------------------------------------------------------------
;; The event, its reducer and its narration

(deftest work-done-uses-up-and-makes
  (let [run (start)
        world (e/world run)
        reach (items/judged-things world job)
        verdict-of #(:verdict (items/judgement (judge-says %) world reach))
        done (items/work-done world job {:minutes 60 :stopped-early? false :luck 50}
                              (verdict-of {:used-up ["beef"] :made [stock bones] :summary "Ben made stock of the beef."}))
        after (w/apply-event world done)]
    (testing "the data names what was used up and holds the new items"
      (is (= [{:item "beef" :name "Rib of beef"}] (get-in done [:data :used-up])))
      (is (= [{:id "beef-stock" :name "Beef stock" :desc "A rich brown stock." :aspects {:state "simmering"}
               :consumable? true :owner "ada" :in "saucepan"}
              {:id "beef-bones" :name "Beef bones" :desc "The rib bones, picked clean." :aspects {:state "picked clean"}
               :consumable? true :owner "ada" :place "hall"}]
             (get-in done [:data :made]))
          "owned like the beef; the stock in the saucepan, the bones where the work was"))
    (testing "the reducer removes the used-up item and adds the made ones"
      (is (nil? (items/item after "beef")))
      (is (= ["beef-stock"] (map :id (items/contents after "saucepan"))))
      (is (= "hall" (:place (items/item after "beef-bones"))))
      (is (= (:round world) (:touched (items/item after "beef-bones"))) "made things are touched"))
    (testing "a used-up item keeps its name for older lines, and its id isn't given out again"
      (is (= "Ada picks up the rib of beef."
             (n/describe {:type :item/taken :actor "ada" :data {:item "beef" :from-place "hall"}} (n/names after))))
      (is (not= "beef" (items/fresh-id after "Beef"))))
    (testing "narration lists what was used up and made"
      (is (= "Ben finishes making stock. Ben made stock of the beef. Used up: Rib of beef. Made: Beef stock in the saucepan; Beef bones."
             (n/describe done (n/names after)))))
    (testing "the used-up item leaves the views; the made ones appear"
      (let [ben (prompt/agent-view (:scenario run) after "ben")]
        (is (not-any? #(= "beef" (:id %)) (get-in ben [:location :here :items])))
        (is (some #(= [{:id "beef-stock" :name "Beef stock"}] (:contents %)) (get-in ben [:location :here :items])))
        (is (not-any? #(= "beef" (:id %)) (:items (prompt/default-gm-view after))))))
    (testing "made from something the worker carried: the worker holds it"
      (let [d (items/work-done world job {:minutes 30 :luck 50}
                               (verdict-of {:used-up ["apple"] :made [{:name "Apple core" :desc "A core." :aspects {:state "brown"}}]
                                            :summary "Ben ate the apple."}))]
        (is (= {:holder "ben" :consumable? true} (select-keys (first (get-in d [:data :made])) [:holder :consumable? :place])))
        (is (= ["apple-core" "pipe"] (map :id (items/held-by (w/apply-event world d) "ben"))))))
    (testing "two made with one name get their own ids"
      (let [d (items/work-done world job {:minutes 30 :luck 50}
                               (verdict-of {:used-up ["beef"] :made [bones bones] :summary "x"}))]
        (is (= ["beef-bones" "beef-bones-2"] (map :id (get-in d [:data :made]))))))
    (testing "an old-shaped event, without used-up or made, still applies"
      (is (= (items/all (w/apply-event world (update done :data dissoc :used-up :made)))
             (items/all world))))
    (e/stop-run run)))

;; ---------------------------------------------------------------------------
;; A short mock run: Ben makes stock of the beef in the saucepan; Ada goes to bed.

(defn- judge [{:keys [things]}]
  {:tool :judge
   :args (if (some #(= "beef" (:id %)) things)
           {:used-up ["beef"] :made [stock] :changes [{:thing "saucepan" :aspect "state" :value "full of stock"}]
            :summary "Ben boiled the beef down to stock."}
           {:summary "Nothing to do."})})

(defn- policy [{:keys [world agent-id role step] :as ctx}]
  (cond
    (= :judge role) (judge ctx)
    (not= agent-id "ben") (hamlet/mock-policy ctx)
    :else (get-in {1 [{:tool :work-on :args {:items ["beef" "saucepan"] :doing "making stock" :hours 1}}]}
                  [(:round world) step]
                  {:tool :pass})))

(deftest a-run-that-uses-things-up
  (let [run (rounds! (start {:llm-impl (llm/mock policy)}) 4)
        world (e/world run)
        [done] (of-type run :item/work-done)]
    (testing "the beef became stock in the saucepan"
      (is (nil? (items/item world "beef")))
      (is (= [{:id "beef-stock" :in "saucepan" :owner "ada" :consumable? true}]
             (map #(select-keys % [:id :in :owner :consumable?]) (items/contents world "saucepan"))))
      (is (= {:cleanliness "clean" :state "full of stock"} (:aspects (items/item world "saucepan")))))
    (testing "Ben saw it; Ada, upstairs, didn't"
      (is (= #{"ben"} (:visible-to done))))
    (testing "replay gives the same world"
      (is (= world (w/replay (store/read-events (:dir run))))))
    (testing "the transcript has the line"
      (is (str/includes? (transcript/render (:dir run)) "Used up: Rib of beef. Made: Beef stock in the saucepan.")))
    (testing "the exported map has the stock and not the beef, and starts a run with the same items"
      (let [path (str (io/file (:dir run) "map.edn"))
            by-id (into {} (map (juxt :id identity)) (:items (wf/read-file path)))]
        (is (not (contains? by-id "beef")))
        (is (= {:in "saucepan" :consumable? true} (select-keys (by-id "beef-stock") [:in :consumable?])))
        (is (str/includes? (slurp path) "{:id \"beef-stock\" :name \"Beef stock\" :in \"saucepan\" :owner \"Ada\" :consumable? true"))
        (let [r2 (start {:world-file path})]
          (is (= (map #(dissoc % :touched) (items/all world)) (items/all (e/world r2))))
          (e/stop-run r2))))
    (e/stop-run run)))

(deftest dining-uses-up-an-unfocused-joint
  (let [judge (fn [_] {:tool :judge :args {:used-up ["beef"] :summary "Ben ate the joint."}})
        policy (fn [{:keys [role agent-id world] :as ctx}]
                 (cond (= :judge role) (judge ctx)
                       (and (= "ben" agent-id) (= 1 (:round world)))
                       {:tool :spend-time :args {:doing "dining"}}
                       :else (hamlet/mock-policy ctx)))
        run (rounds! (start {:llm-impl (llm/mock policy)}) 2)
        [done] (of-type run :item/work-done)
        call (first (filter #(= "judge" (:agent %)) (store/read-llm (:dir run))))
        scene (json/read-value (second (re-find #"(?s)```json\n(.*)\n```" (get-in call [:messages 1 :content])))
                               json/keyword-keys-object-mapper)]
    (is (empty? (:focus scene)))
    (is (some #(and (= "beef" (:id %)) (:consumable %)) (:also_here scene)))
    (is (= [{:item "beef" :name "Rib of beef"}] (get-in done [:data :used-up])))
    (is (nil? (items/item (e/world run) "beef")))
    (is (= (e/world run) (w/replay (store/read-events (:dir run)))))
    (e/stop-run run)))

(deftest using-up-a-container-never-orphans-unseen-contents
  (let [run (start)
        world (e/world run)
        hidden (w/apply-event world {:type :item/added :data {:item {:id "secret" :name "Secret" :desc "Hidden."
                                                                    :in "bag" :consumable? true}}})
        ;; Use a closed consumable bag, then an open one whose contents exceed the scene cap.
        hidden (assoc-in hidden [:items "bag" :container] :closed)
        activity (assoc job :items [] :doing "dining")
        attempt (fn [world] (items/judgement (judge-says {:used-up ["bag"] :summary "Eaten."}) world
                                              (items/judged-things world activity)))
        generic "the Paper bag (bag) still holds things not shown here; leave it, or name it with `on` next time"]
    (is (= generic (:error (attempt hidden))))
    (is (not (str/includes? (:error (attempt hidden)) "secret")))
    (let [extra (for [i (range 15)] {:id (str "crumb-" i) :name (str "Crumb " i) :desc "A crumb."
                                     :in "bag" :consumable? true})
          crowded (reduce w/apply-event world (items/init-events extra))]
      (is (= generic (:error (attempt crowded)))))
    (let [things (items/judged-things hidden (assoc activity :items ["bag"]))
          result (items/judgement (judge-says {:used-up ["bag" "bun" "secret"] :summary "Used it all."}) hidden things)
          done (items/work-done hidden activity {:minutes 30} (:verdict result))
          after (w/apply-event hidden done)]
      (is (nil? (:error result)))
      (is (every? #(nil? (items/item after %)) ["bag" "bun" "secret"])))
    (e/stop-run run)))
