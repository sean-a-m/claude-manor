(ns manor.consumables-test
  "Consumables (manor.mechanics.items): items marked :consumable? that the judge may
  use up when work ends, and the things it makes from them, in a variant of
  the hamlet world with a kitchen's worth of things in the hall. Checks on
  the judge's answer, the :item/work-done event and its reducer, narration,
  a mock run, replay and the exported map."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [manor.actions :as a]
            [manor.engine :as e]
            [manor.test-support :as support]
            [manor.io.llm :as llm]
            [manor.io.store :as store]
            [manor.io.worldfile :as wf]
            [manor.mechanics.items :as items]
            [manor.mechanics.items.judge :as item-judge]
            [manor.mechanics.items.work :as item-work]
            [manor.narrate :as n]
            [manor.prompt :as prompt]
            [manor.scenarios.hamlet :as hamlet]
            [manor.tools.transcript :as transcript]
            [manor.world :as w]))

;; All runs here use {:test? true}, so they live under test-runs/.
(use-fixtures :once support/with-runs)

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

(defn- used
  "`gone` entries using up the things `ids`."
  [& ids]
  (mapv #(hash-map :thing % :fate "used up") ids))

(defn- judge-says [args]
  {:tool-calls [{:id "j1" :name "judge" :arguments (json/write-value-as-string args)}]})

(def stock {:consumable true :name "Beef stock" :desc "A rich brown stock." :aspects {:state "simmering"} :in "saucepan"})
(def bones {:consumable true :name "Beef bones" :desc "The rib bones, picked clean." :aspects {:state "picked clean"}})

;; ---------------------------------------------------------------------------
;; The judge's answer

(deftest the-judge-sees-what-is-consumable
  (let [run (start)
        world (e/world run)
        user (:content (second (:messages (item-judge/judge-request (:scenario run) world job {:minutes 60 :luck 50}))))
        judged (:focus (json/read-value (second (re-find #"(?s)```json\n(.*)\n```" user)) json/keyword-keys-object-mapper))
        system (:content (first (:messages (item-judge/judge-request (:scenario run) world job {:minutes 60}))))]
    (is (= {"beef" [true nil] "saucepan" [nil true] "bag" [true true] "bun" [true nil] "clock" [nil nil]}
           (select-keys (into {} (map (juxt :id (juxt :consumable :container))) judged)
                        ["beef" "saucepan" "bag" "bun" "clock"])))
    (is (str/starts-with? system (item-judge/judge-instructions world)))
    (e/stop-run run)))

(deftest checking-used-up-and-made
  (let [run (start)
        world (e/world run)
        reach (item-work/judged-things world job)
        judge #(item-judge/judgement (judge-says %) world reach)
        error #(:error (judge (assoc % :summary "Done.")))]
    (testing "a good answer: what's used up, and what's made from it; a change to a used-up item is dropped"
      (is (= {:summary "The beef went into stock." :changes [{:item "saucepan" :aspect :cleanliness :from "clean" :to "greasy"}]
              :gone [{:thing "beef" :fate "boiled down"}] :made [stock bones]}
             (:verdict (judge {:changes [{:thing "saucepan" :aspect "cleanliness" :value "greasy"} {:thing "beef" :aspect "state" :value "carved"}]
                               :gone [{:thing "beef" :fate "boiled down"}] :made [stock bones] :summary "The beef went into stock."})))))
    (testing "using up without making anything is fine"
      (is (= {:summary "Eaten." :changes [] :gone (used "apple")} (:verdict (judge {:gone (used "apple") :summary "Eaten."})))))
    (testing "each thing once, with a fate"
      (is (= "say what became of each thing only once" (error {:gone (used "beef" "beef")})))
      (is (error {:gone [{:thing "beef"}]}))
      (is (error {:gone [{:thing "beef" :fate ""}]})))
    (testing "only consumable, unfixed items in reach may be used up"
      (is (= (str "the Saucepan (saucepan) isn't consumable, so it can't be used up: if it's now part of something here, "
                  "give `on`, `aspect` and `value`; otherwise leave it as it is")
             (error {:gone (used "beef" "saucepan")})))
      (is (str/starts-with? (error {:gone (used "umbrella")}) "the Umbrella (umbrella) isn't consumable"))
      (is (= "the Woodpile (woodpile) is fixed in place; leave it as it is and change its aspects instead"
             (error {:gone (used "woodpile")})))
      (is (str/ends-with? (error {:gone (used "ben")}) "; not ben")))
    (testing "a container is used up only with everything in it"
      (is (= "the Paper bag (bag) still holds bun: say what became of those too, or leave it" (error {:gone (used "bag")})))
      (is (= ["bag" "bun"] (map :thing (get-in (judge {:gone (used "bag" "bun") :summary "Done."}) [:verdict :gone])))))
    (testing "work products need no tracked source or use-up"
      (is (nil? (error {:made [{:name "Letter" :desc "A letter in ink." :consumable false :held true}]}))))
    (testing "at most two things made"
      (is (str/includes? (error {:gone (used "beef") :made [stock bones bones]}) "made has 3 items")))
    (testing "in names a container in reach that isn't used up"
      (is (= "in must be the id of a container among these: saucepan, bag; not clock"
             (error {:gone (used "beef") :made [(assoc stock :in "clock")]})))
      (is (= "in must be the id of a container among these: saucepan; not bag"
             (error {:gone (used "beef" "bag" "bun") :made [(assoc stock :in "bag")]})))
      (is (str/ends-with? (error {:gone (used "beef") :made [(assoc stock :in "cellar")]}) "; not cellar")))
    (testing "made things need a name and a description within the limits; aspects are optional, and checked"
      (is (error {:gone (used "beef") :made [(dissoc bones :desc)]}))
      (is (error {:gone (used "beef") :made [(assoc bones :name (apply str (repeat 61 "x")))]}))
      (is (= [{:consumable true :name "Beef bones" :desc "The rib bones, picked clean."}]
             (:made (:verdict (judge {:gone (used "beef") :made [(dissoc bones :aspects)] :summary "x"})))))
      (is (= {:picked-state "clean"}
             (:aspects (first (:made (:verdict (judge {:gone (used "beef") :made [(assoc bones :aspects {"picked_state" "clean"})] :summary "x"})))))))
      (is (= "an aspect's key is lower-case letters and hyphens, at most 24 characters, starting with a letter (cleanliness, glass-eye); not \"bone count\""
             (error {:gone (used "beef") :made [(assoc bones :aspects {"Bone Count" "three"})]})))
      (is (str/includes? (error {:gone (used "beef") :made [(assoc bones :aspects (zipmap ["a" "b" "c" "d" "e"] (repeat "x")))]})
                         "should have at most 4 elements"))
      (is (nil? (error {:gone (used "beef") :made [(assoc bones :aspects {"state" (apply str (repeat 200 "x"))})]}))
          "over the advised 80 characters is still accepted up to 200")
      (is (= "made \"Beef bones\": its state is 201 characters; give it 1 to 200"
             (error {:gone (used "beef") :made [(assoc bones :aspects {"state" (apply str (repeat 201 "x"))})]})))
      (is (= "made \"Beef bones\": its state is 0 characters; give it 1 to 200"
             (error {:gone (used "beef") :made [(assoc bones :aspects {"state" ""})]}))))
    (testing "the reason goes back to the judge"
      (is (= "error: the Paper bag (bag) still holds bun: say what became of those too, or leave it. Call judge again with a corrected answer."
             (:content (first (:replies (judge {:gone (used "bag") :summary "x"})))))))
    (e/stop-run run)))

;; ---------------------------------------------------------------------------
;; The event, its reducer and its narration

(deftest work-done-uses-up-and-makes
  (let [run (start)
        world (e/world run)
        reach (item-work/judged-things world job)
        verdict-of #(:verdict (item-judge/judgement (judge-says %) world reach))
        done (item-work/work-done world job {:minutes 60 :stopped-early? false :luck 50}
                                  (verdict-of {:gone (used "beef") :made [stock bones] :summary "Ben made stock of the beef."}))
        after (w/apply-event world done)]
    (testing "the data names what was used up and holds the new items"
      (is (= [{:item "beef" :name "Rib of beef" :fate "used up"}] (get-in done [:data :gone])))
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
      (is (= "Ben finishes making stock. Ben made stock of the beef. Rib of beef: used up. Made: Beef stock in the saucepan; Beef bones."
             (n/describe done (n/names after)))))
    (testing "the used-up item leaves the views; the made ones appear"
      (let [ben (prompt/agent-view (:scenario run) after "ben")]
        (is (not-any? #(= "beef" (:id %)) (get-in ben [:location :here :items])))
        (is (some #(= [{:id "beef-stock" :name "Beef stock"}] (:contents %)) (get-in ben [:location :here :items])))
        (is (not-any? #(= "beef" (:id %)) (:items (prompt/default-gm-view after))))))
    (testing "made from something the worker carried: the worker holds it"
      (let [d (item-work/work-done world (assoc job :items ["apple"]) {:minutes 30 :luck 50}
                                   (verdict-of {:gone (used "apple") :made [{:consumable true :name "Apple core" :desc "A core." :aspects {:state "brown"}}]
                                                :summary "Ben ate the apple."}))]
        (is (= {:holder "ben" :consumable? true} (select-keys (first (get-in d [:data :made])) [:holder :consumable? :place])))
        (is (= ["apple-core" "pipe"] (map :id (items/held-by (w/apply-event world d) "ben"))))))
    (testing "two made with one name get their own ids"
      (let [d (item-work/work-done world job {:minutes 30 :luck 50}
                                   (verdict-of {:gone (used "beef") :made [bones bones] :summary "x"}))]
        (is (= ["beef-bones" "beef-bones-2"] (map :id (get-in d [:data :made]))))))
    (testing "an event without gone or made still applies"
      (is (= (items/all (w/apply-event world (update done :data dissoc :gone :made)))
             (items/all world))))
    (e/stop-run run)))

;; ---------------------------------------------------------------------------
;; A short mock run: Ben makes stock of the beef in the saucepan; Ada goes to bed.

(defn- judge [{:keys [things]}]
  {:tool :judge
   :args (if (some #(= "beef" (:id %)) things)
           {:gone (used "beef") :made [stock] :changes [{:thing "saucepan" :aspect "state" :value "full of stock"}]
            :summary "Ben boiled the beef down to stock."}
           {:summary "Nothing to do."})})

(defn- policy [{:keys [world agent-id role step] :as ctx}]
  (cond
    (= :items/activity-check (:task ctx)) {:tool :check-activity :args {:allowed true}}
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
      (is (str/includes? (transcript/render (:dir run)) "Rib of beef: used up. Made: Beef stock in the saucepan.")))
    (testing "the exported map has the stock and not the beef, and starts a run with the same items"
      (let [path (str (io/file (:dir run) "map.edn"))
            by-id (into {} (map (juxt :id identity)) (:items (wf/read-file path)))]
        (is (not (contains? by-id "beef")))
        (is (= {:in "saucepan" :consumable? true} (select-keys (by-id "beef-stock") [:in :consumable?])))
        (is (= {:id "beef-stock" :name "Beef stock" :in "saucepan" :owner "Ada" :consumable? true}
               (select-keys (by-id "beef-stock") [:id :name :in :owner :consumable?])))
        (let [r2 (start {:world-file path})]
          (is (= (map #(dissoc % :touched) (items/all world)) (map #(dissoc % :touched) (items/all (e/world r2)))))
          (e/stop-run r2))))
    (e/stop-run run)))

(deftest dining-uses-up-an-unfocused-joint
  (let [judge (fn [_] {:tool :judge :args {:gone [{:thing "beef" :fate "eaten at dinner"}] :summary "Ben ate the joint."}})
        policy (fn [{:keys [role agent-id world] :as ctx}]
                 (cond (= :items/activity-check (:task ctx)) {:tool :check-activity :args {:allowed true}}
                       (= :judge role) (judge ctx)
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
    (is (= [{:item "beef" :name "Rib of beef" :fate "eaten at dinner"}] (get-in done [:data :gone])))
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
        attempt (fn [world] (item-judge/judgement (judge-says {:gone (used "bag") :summary "Eaten."}) world
                                                  (item-work/judged-things world activity)))
        generic "the Paper bag (bag) still holds things not shown here; leave it, or name it with `on` next time"]
    (is (= generic (:error (attempt hidden))))
    (is (not (str/includes? (:error (attempt hidden)) "secret")))
    (let [extra (for [i (range 15)] {:id (str "crumb-" i) :name (str "Crumb " i) :desc "A crumb."
                                     :in "bag" :consumable? true})
          crowded (reduce w/apply-event world (items/init-events extra))]
      (is (= generic (:error (attempt crowded)))))
    (let [things (item-work/judged-things hidden (assoc activity :items ["bag"]))
          result (item-judge/judgement (judge-says {:gone (used "bag" "bun" "secret") :summary "Used it all."}) hidden things)
          done (item-work/work-done hidden activity {:minutes 30} (:verdict result))
          after (w/apply-event hidden done)]
      (is (nil? (:error result)))
      (is (every? #(nil? (items/item after %)) ["bag" "bun" "secret"])))
    (e/stop-run run)))

(deftest drawing-absorbing-and-fates
  (let [run (start)
        world (reduce w/apply-event (e/world run)
                      (items/init-events
                       [{:id "pantry" :name "Pantry shelves" :desc "Shelves of provisions." :place "shop" :fixed? true
                         :store {:keeps "food and utensils" :serves ["hall"]}}
                        {:id "remote" :name "Remote store" :desc "Distant supplies." :place "farm" :fixed? true :store {:keeps "food"}}
                        {:id "tin" :name "Dripping tin" :desc "A tin." :place "hall" :drawn-from "pantry"}]))
        activity (assoc job :items [] :doing "putting things away")
        check (fn [activity args]
                (item-judge/judgement (judge-says (assoc args :summary "Things were put away.")) world (item-work/judged-things world activity)))
        tin {:name "Dripping tin" :desc "A tin of dripping." :consumable false :from "pantry" :aspects {:state "full"}}]
    (try
      (testing "draw from an in-scope serving store without using anything up"
        (let [{:keys [verdict error]} (check activity {:made [tin]})
              event (item-work/work-done world activity {:minutes 30} verdict)
              made (first (get-in event [:data :made]))]
          (is (nil? error))
          (is (= {:holder "ben" :drawn-from "pantry" :consumable? false}
                 (select-keys made [:holder :place :drawn-from :consumable?])))
          (is (= {:state "full"} (:aspects made)))
          (is (= "Dripping tin" (get (items/names (w/apply-event world event)) [:item (:id made)])))))
      (testing "placement in a container, at a named place-bound focus, or in the hands"
        (doseq [[focus entry expected] [[["saucepan"] (assoc tin :in "saucepan") {:in "saucepan"}]
                                        [["hall"] tin {:place "hall"}]
                                        [["beef"] tin {:place "hall"}]
                                        [["apple"] tin {:holder "ben"}]
                                        [[] (assoc tin :consumable true) {:holder "ben"}]]]
          (let [activity (assoc activity :items focus)
                done (item-work/work-done world activity {:minutes 30} (:verdict (check activity {:made [entry]})))
                made (first (get-in done [:data :made]))]
            (is (= expected (select-keys made [:in :place :holder])))
            (is (= (not (:consumable entry)) (contains? made :drawn-from))))))
      (testing "only in-scope stores authorize drawing; source-free products are allowed"
        (is (:error (check activity {:made [(assoc tin :from "remote")]})))
        (is (:error (check activity {:made [(assoc tin :from "saucepan")]})))
        (is (:verdict (check activity {:made [(dissoc tin :from)]})))
        (is (:error (check activity {:made [(dissoc tin :consumable)]}))))
      (testing "absorb only eligible visible things into in-scope stores"
        (doseq [thing ["woodpile" "pipe" "pantry" "clock" "remote" "unknown"]]
          (is (:error (check activity {:absorbed [{:thing thing :into "pantry"}]})) thing))
        (is (:error (check activity {:absorbed [{:thing "beef" :into "remote"}]})))
        (is (:error (check activity {:absorbed [{:thing "beef" :into "saucepan"}]})))
        (is (:error (check activity {:gone (used "beef") :absorbed [{:thing "beef" :into "pantry"}]})))
        (is (:error (check activity {:absorbed [{:thing "beef" :into "pantry"} {:thing "beef" :into "pantry"}]})))
        (is (:verdict (check activity {:absorbed [{:thing "beef" :into "pantry"}] :made [(dissoc tin :from)]})))
        (let [verdict (:verdict (check activity {:absorbed [{:thing "beef" :into "pantry"} {:thing "tin" :into "pantry"}]
                                                 :changes [{:thing "pantry" :aspect "stock" :value "well stocked"}
                                                           {:thing "beef" :aspect "state" :value "stored"}]}))
              after (w/apply-event world (item-work/work-done world activity {:minutes 30} verdict))]
          (is (= {:name "Rib of beef" :place "hall" :round 0 :fate "put away in the Pantry shelves"}
                 (get-in after [:items-gone "beef"])))
          (is (nil? (items/item after "tin")))
          (is (= "well stocked" (get-in (items/item after "pantry") [:aspects :stock])))
          (is (= 1 (count (:changes verdict))))))
      (testing "containers require all world contents absorbed, including hidden contents"
        (is (:error (check activity {:absorbed [{:thing "bag" :into "pantry"}]})))
        (let [args {:absorbed [{:thing "bag" :into "pantry"} {:thing "bun" :into "pantry"}]}
              event (item-work/work-done world activity {:minutes 30} (:verdict (check activity args)))
              after (w/apply-event world event)
              hidden (assoc-in world [:items "bag" :container] :closed)
              refused (item-judge/judgement (judge-says (assoc args :summary "Put away.")) hidden (item-work/judged-things hidden activity))]
          (is (= ["hall" "hall"] (map #(get-in after [:items-gone % :place]) ["bag" "bun"])))
          (is (:error refused))
          (is (not (str/includes? (:error refused) "Bun")))))
      (testing "fates, including a carried item's location, keep old lines named"
        (let [verdict (:verdict (check (assoc activity :items ["apple"]) {:gone [{:thing "beef" :fate "eaten at supper"}
                                                                                 {:thing "apple" :fate "eaten"}]}))
              after (w/apply-event world (item-work/work-done world activity {:minutes 30} verdict))]
          (is (= "eaten at supper" (get-in after [:items-gone "beef" :fate])))
          (is (= {:name "Apple" :place "hall" :round 0 :fate "eaten"}
                 (get-in after [:items-gone "apple"])))
          (is (= "Ben picks up the rib of beef." (n/describe {:type :item/taken :actor "ben" :data {:item "beef"}} (n/names after))))))
      (testing "work narration distinguishes things put away and fetched"
        (let [verdict (:verdict (check activity {:absorbed [{:thing "beef" :into "pantry"}] :made [tin]}))
              done (item-work/work-done world activity {:minutes 30} verdict)]
          (is (= "Ben finishes putting things away. Things were put away. Put away: Rib of beef (in the Pantry shelves). Fetched: Dripping tin."
                 (n/describe done (n/names (w/apply-event world done)))))))
      (finally (e/stop-run run)))))

(deftest leaving-things-on-others
  (let [run (start)
        world (e/world run)
        reach (item-work/judged-things world job)
        judge #(item-judge/judgement (judge-says (assoc % :summary "Done.")) world reach nil job)
        error #(:error (judge %))
        hang (fn [thing on aspect value] {:thing thing :fate (str "hung on the " on) :on on :aspect aspect :value value})]
    (testing "a thing left on another is gone, and the host's aspect records it; it needn't be consumable"
      (let [verdict (:verdict (judge {:gone [(hang "umbrella" "clock" "state" "ticking loudly, an umbrella hooked on it")]}))]
        (is (= [{:thing "umbrella" :fate "hung on the clock"}] (:gone verdict)))
        (is (= [{:item "clock" :aspect :state :from "ticking loudly" :to "ticking loudly, an umbrella hooked on it"}] (:changes verdict)))
        (let [done (item-work/work-done world job {:minutes 60 :luck 50} verdict)
              after (w/apply-event world done)]
          (is (nil? (items/item after "umbrella")))
          (is (= "ticking loudly, an umbrella hooked on it" (get-in (items/item after "clock") [:aspects :state])))
          (is (= {:name "Umbrella" :place "hall" :round 0 :fate "hung on the clock"} (get-in after [:items-gone "umbrella"])))
          (is (= (str "Ben finishes making stock. Done. (Grandfather clock: ticking loudly, an umbrella hooked on it). "
                      "Umbrella: hung on the clock.")
                 (n/describe done (n/names after)))))))
    (testing "what the worker carries counts even unnamed, and a person can be the host"
      (is (some #(= "pipe" (:id %)) reach))
      (is (= [{:person "ben" :aspect :mouth :from nil :to "a briar pipe clenched in his teeth"}]
             (:changes (:verdict (judge {:gone [{:thing "pipe" :fate "clenched in his teeth" :on "ben" :aspect "mouth"
                                                 :value "a briar pipe clenched in his teeth"}]}))))))
    (testing "the host's aspect needs on, aspect and a value"
      (doseq [entry [(dissoc (hang "umbrella" "clock" "state" "x") :aspect)
                     (dissoc (hang "umbrella" "clock" "state" "x") :value)
                     (hang "umbrella" "clock" "state" "")]]
        (is (= "to leave a thing on something, give `on` (its id), `aspect` (the key) and `value` (the phrase there that records it)"
               (error {:gone [entry]})))))
    (testing "the host must stay: not gone itself, not the thing, and in the scene"
      (is (str/ends-with? (error {:gone [(hang "beef" "bag" "state" "x") {:thing "bag" :fate "burnt"} {:thing "bun" :fate "eaten"}]}) "; not bag"))
      (is (str/ends-with? (error {:gone [(hang "umbrella" "umbrella" "state" "x")]}) "; not umbrella"))
      (is (str/ends-with? (error {:gone [(hang "umbrella" "cellar" "state" "x")]}) "; not cellar")))
    (testing "fixed things and things a mechanic tracks can't be left on anything"
      (is (= "the Woodpile (woodpile) is fixed in place; leave it as it is and change its aspects instead"
             (error {:gone [(hang "woodpile" "clock" "state" "x")]})))
      (let [tracked (assoc-in world [:items "umbrella" :order-id] "order-umbrella")]
        (is (= "the Umbrella (umbrella) must stay a separate thing; leave it as it is"
               (:error (item-judge/judgement (judge-says {:gone [(hang "umbrella" "clock" "state" "x")] :summary "x"})
                                             tracked (item-work/judged-things tracked job) nil job))))))
    (testing "only with the job: lying at its place, or carried by the worker while there"
      (let [away (assoc job :place "porch")]
        (is (= "the Apple (apple) isn't here, so it can't be left on anything here"
               (:error (item-judge/judgement (judge-says {:gone [(hang "apple" "porch" "state" "x")] :summary "x"})
                                             world (item-work/judged-things world away) nil away))))))
    (testing "a container goes with all it holds"
      (is (= "the Paper bag (bag) still holds bun: say what became of those too, or leave it"
             (error {:gone [(hang "bag" "clock" "state" "a paper bag on top")]})))
      (is (nil? (error {:gone [(hang "bag" "clock" "state" "a paper bag on top, a bun in it") (hang "bun" "clock" "cleanliness" "crumbs")]}))))
    (e/stop-run run)))

(deftest making-a-thing-from-an-aspect
  (let [run (start)
        world (assoc-in (e/world run) [:items "clock" :aspects] {:state "ticking, a goose hung from its case" :cleanliness "dusty"})
        reach (item-work/judged-things world job)
        judge #(item-judge/judgement (judge-says (assoc % :summary "Done.")) world reach nil job)
        goose {:name "Goose" :desc "A plucked goose." :consumable true :held true}]
    (testing "taken rewrites or clears the host's aspect as the thing is made"
      (let [verdict (:verdict (judge {:made [(assoc goose :taken {:thing "clock" :aspect "state" :value "ticking"})]}))
            done (item-work/work-done world job {:minutes 30 :luck 50} verdict)
            after (w/apply-event world done)]
        (is (= [{:item "clock" :aspect :state :from "ticking, a goose hung from its case" :to "ticking"}] (:changes verdict)))
        (is (= "ben" (:holder (items/item after "goose"))))
        (is (= {:state "ticking" :cleanliness "dusty"} (:aspects (items/item after "clock")))))
      (is (= [{:item "clock" :aspect :state :from "ticking, a goose hung from its case" :to nil}]
             (:changes (:verdict (judge {:made [(assoc goose :taken {:thing "clock" :aspect "state" :value ""})]}))))))
    (testing "taken names a thing that stays, and an aspect it has"
      (is (= "taken must name an aspect the Grandfather clock (clock) has: state, cleanliness; not repair"
             (:error (judge {:made [(assoc goose :taken {:thing "clock" :aspect "repair" :value ""})]}))))
      (is (str/ends-with? (:error (judge {:made [(assoc goose :taken {:thing "cellar" :aspect "state" :value ""})]})) "; not cellar"))
      (is (str/ends-with? (:error (judge {:gone [{:thing "beef" :fate "eaten"}]
                                          :made [(assoc goose :taken {:thing "beef" :aspect "state" :value ""})]}))
                          "; not beef")))
    (e/stop-run run)))

(deftest everyday-work-products-and-placement
  (let [letter {:name "Household list" :desc "A list written in ink." :consumable false :held true}
        dish {:name "Clear soup" :desc "A tureen of soup." :consumable true :held false}
        policy (fn [{:keys [role world agent-id] :as ctx}]
                 (cond (= :items/activity-check (:task ctx)) {:tool :check-activity :args {:allowed true}}
                       (= :judge role) {:tool :judge :args {:made [letter dish] :summary "Ben wrote the list and made soup."}}
                       (and (= "ben" agent-id) (= 1 (:round world)))
                       {:tool :spend-time :args {:doing "writing a list and cooking soup" :on ["hall"]}}
                       :else (hamlet/mock-policy ctx)))
        run (rounds! (start {:llm-impl (llm/mock policy)}) 2)]
    (try
      (let [world (e/world run)
            [done] (of-type run :item/work-done)]
        (is (= {:holder "ben" :consumable? false}
               (select-keys (items/item world "household-list") [:holder :place :consumable? :drawn-from])))
        (is (= {:place "hall" :consumable? true}
               (select-keys (items/item world "clear-soup") [:holder :place :consumable? :drawn-from])))
        (is (empty? (get-in done [:data :gone])))
        (is (empty? (of-type run :item/judge-failed)))
        (is (str/ends-with? (n/describe done (n/names world)) "Made: Household list; Clear soup."))
        (is (= world (w/replay (store/read-events (:dir run)))))
        (doseq [[focus entry expected] [[[] (dissoc letter :held) {:holder "ben"}]
                                        [["hall"] (dissoc letter :held) {:place "hall"}]
                                        [[] dish {:place "hall"}]
                                        [["hall"] (assoc letter :in "saucepan") {:in "saucepan"}]]]
          (let [activity (assoc job :items focus)
                verdict (:verdict (item-judge/judgement (judge-says {:made [entry] :summary "Made."}) world
                                                        (item-work/judged-things world (assoc activity :items ["saucepan"]))))
                made (first (get-in (item-work/work-done world activity {:minutes 30} verdict) [:data :made]))]
            (is (= expected (select-keys made [:holder :place :in])))))
        (let [bad (item-judge/judgement (judge-says {:made [(assoc letter :held "yes")] :summary "Made."}) world
                                        (item-work/judged-things world job))]
          (is (:error bad))))
      (finally (e/stop-run run)))))

(deftest carrying-a-dish-does-not-use-it-up
  (let [run (start)
        action (fn [name] (first (filter #(= name (:name %)) (:actions run))))
        perform! (fn [name args]
                   (is (nil? (a/check (action name) (e/world run) "ben" args)))
                   (e/emit! run (a/effect-events (action name) (e/world run) "ben" args)))]
    (try
      (let [activity (assoc job :items ["beef"] :doing "carrying the joint to the porch")
            verdict (:verdict (item-judge/judgement (judge-says {:summary "The joint was left for Ben to carry."})
                                                    (e/world run) (item-work/judged-things (e/world run) activity)))]
        (e/emit! run [(item-work/work-done (e/world run) activity {:minutes 30} verdict)])
        (is (= "hall" (:place (items/item (e/world run) "beef")))))
      (perform! :take {:item "beef"})
      (perform! :move {:to "porch"})
      (is (= "ben" (:holder (items/item (e/world run) "beef"))))
      (let [world (e/world run)
            activity (assoc job :items ["beef"] :place "porch" :doing "eating the joint")
            verdict (:verdict (item-judge/judgement (judge-says {:gone (used "beef") :summary "Ben ate the joint."})
                                                    world (item-work/judged-things world activity)))]
        (e/emit! run [(item-work/work-done world activity {:minutes 30} verdict)]))
      (is (= "porch" (get-in (e/world run) [:items-gone "beef" :place])))
      (is (= (e/world run) (w/replay (store/read-events (:dir run)))))
      (finally (e/stop-run run)))))
