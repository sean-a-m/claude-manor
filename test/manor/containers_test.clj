(ns manor.containers-test
  "Containers (manor.items): items that hold items, in a variant of the
  hamlet world with an open shelf and a closed cupboard in the hall. World
  files, accessors, views, open, take, put-in, privacy, work and the judge,
  a mock run, replay and the exported map."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [manor.actions :as a]
            [manor.engine :as e]
            [manor.items :as items]
            [manor.llm :as llm]
            [manor.narrate :as n]
            [manor.prompt :as prompt]
            [manor.scenarios.hamlet :as hamlet]
            [manor.store :as store]
            [manor.world :as w]
            [manor.worldfile :as wf]))

;; All runs here use {:test? true}, so they live under test-runs/.
(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

(def containers
  "In the hall: an open shelf with a cup and an open tray (holding a spoon),
  and a closed cupboard with a candle, a fixed hook and a closed box (holding
  a key)."
  [{:id "shelf" :name "Shelf" :place "hall" :fixed? true :container :open :aspects {:cleanliness "dusty"} :desc "A plank shelf on brackets."}
   {:id "cup" :name "Cup" :in "shelf" :aspects {:state "chipped"} :desc "A tin cup."}
   {:id "tray" :name "Tray" :in "shelf" :container :open :aspects {:state "sticky"} :desc "A japanned tray."}
   {:id "spoon" :name "Spoon" :in "tray" :aspects {:state "bent"} :desc "A pewter spoon."}
   {:id "cupboard" :name "Cupboard" :place "hall" :fixed? true :container :closed :aspects {:state "shut tight"} :desc "A tall painted cupboard."}
   {:id "candle" :name "Candle" :in "cupboard" :aspects {:state "half burnt"} :desc "A tallow candle."}
   {:id "hook" :name "Hook" :in "cupboard" :fixed? true :aspects {:state "firm"} :desc "A brass hook inside the door."}
   {:id "box" :name "Box" :in "cupboard" :container :closed :aspects {:state "dented"} :desc "A small tin box."}
   {:id "key" :name "Key" :in "box" :aspects {:state "rusty"} :desc "An iron key."}])

(def world-file
  (update (wf/read-file "test/fixtures/hamlet.edn") :items into containers))

(defn- file-of
  "`wf` written under test-runs/, as a path."
  [wf]
  (let [file-name (str (gensym "containers-") ".edn")]
    (store/write-text! store/test-runs-root file-name (wf/->edn-string wf))
    (str (io/file store/test-runs-root file-name))))

(defn- start [& [opts]]
  (e/start-run :hamlet (merge {:llm :mock :test? true :run-id (str (gensym "containers-"))
                               :world-file (file-of world-file)}
                              opts)))

(defn- rounds! [run n]
  (e/auto! run n)
  (is (= :idle (e/wait-idle run 60000)))
  run)

(defn- action [run n] (first (filter #(= n (:name %)) (:actions run))))

(defn- perform
  "Check and apply an action purely. Returns [world events] or a rejection reason."
  [run world actor n args]
  (let [act (action run n)]
    (or (a/check act world actor args)
        (let [evs (a/effect-events act world actor args)]
          [(reduce w/apply-event world evs) evs]))))

(defn- at [world actor location]
  (w/apply-event world {:type :agent/placed :data {:id actor :to location}}))

(defn- ids [items] (mapv :id items))

(defn- of-type [run t] (filter #(= t (:type %)) (e/events run)))

(def hall-list
  "What Ada can see in the hall: never the cupboard's contents."
  "items here: cup (Cup), cupboard (Cupboard), clock (Grandfather clock), shelf (Shelf), spoon (Spoon), tray (Tray), umbrella (Umbrella)")

;; ---------------------------------------------------------------------------
;; World files

(deftest world-file-checks
  (let [problems-of #(str/join "\n" (wf/problems %))
        change (fn [id f] (update world-file :items (fn [its] (mapv #(if (= id (:id %)) (f %) %) its))))]
    (is (empty? (wf/problems world-file)))
    (doseq [[wf expected]
            [[(change "cup" #(assoc % :place "hall")) "item \"cup\": give exactly one of :place, :holder and :in"]
             [(change "cup" #(assoc % :in "attic")) "item \"cup\": unknown container \"attic\""]
             [(change "cup" #(assoc % :in "umbrella")) "item \"cup\": \"umbrella\" is not a container"]
             [(change "cup" #(assoc % :container :ajar)) "item \"cup\": {:container"]
             [(change "shelf" #(-> % (dissoc :place) (assoc :in "tray"))) "item \"shelf\": its containers go round in a loop"]
             [(change "box" #(assoc % :in "box")) "item \"box\": its containers go round in a loop"]]]
      (is (str/includes? (problems-of wf) expected) expected))
    (testing "contents may come before their container"
      (is (empty? (wf/problems (update world-file :items #(vec (reverse %)))))))))

(deftest new-items-in-containers
  (let [run (start)
        world (e/world run)
        e (items/added world {:name "Candle" :desc "Another candle." :aspects {:state "new"} :in "cupboard"} :gm)]
    (is (= "candle-2" (get-in e [:data :item :id])))
    (is (= "Item added to the cupboard: Candle." (n/describe e (n/names world))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"\"umbrella\" is not a container"
                          (items/added world {:name "Candle" :desc "x" :aspects {:state "new"} :in "umbrella"} :gm)))
    (e/stop-run run)))

;; ---------------------------------------------------------------------------
;; Accessors, views and look

(deftest accessors
  (let [run (start)
        world (e/world run)]
    (is (= ["cup" "tray"] (ids (items/contents world "shelf"))))
    (is (= ["box" "key" "candle" "hook"] (ids (items/within world "cupboard"))) "contents follow their container")
    (is (= "cupboard" (:id (items/outermost world (items/item world "key")))))
    (is (= "hall" (items/place-of world (items/item world "key"))))
    (is (= ["clock" "cupboard" "shelf" "umbrella"] (sort (ids (items/items-at world "hall")))) "items-at is what lies there")
    (is (= ["cupboard" "clock" "shelf" "cup" "tray" "spoon" "umbrella"] (ids (items/visible-at world "hall"))))
    (testing "a carried container's contents go with its holder"
      (let [[moved] (perform run (at (assoc world :round 1) "ben" "hall") "ben" :take {:item "box"})]
        (is (= "ben" (:holder (items/outermost moved (items/item moved "key")))))
        (is (nil? (items/place-of moved (items/item moved "key"))))))
    (e/stop-run run)))

(deftest views-and-look
  (let [run (start)
        world (assoc (e/world run) :round 1)]          ; Ada and Ben in the hall
    (testing "the view: open containers with their contents nested, closed ones marked"
      (is (= [{:id "cupboard" :name "Cupboard" :closed true}
              {:id "clock" :name "Grandfather clock"}
              {:id "shelf" :name "Shelf"
               :contents [{:id "cup" :name "Cup"}
                          {:id "tray" :name "Tray" :contents [{:id "spoon" :name "Spoon"}]}]}
              {:id "umbrella" :name "Umbrella"}]
             (get-in (prompt/agent-view (:scenario run) world "ada") [:location :here :items]))))
    (testing "an empty open container shows empty contents"
      (let [[taken] (perform run world "ada" :take {:item "cup"})
            [emptied] (perform run taken "ada" :take {:item "tray"})]
        (is (= {:id "shelf" :name "Shelf" :contents []}
               (nth (get-in (prompt/agent-view (:scenario run) emptied "ada") [:location :here :items]) 2)))))
    (testing "look: the same, in detail"
      (let [seen (:items (items/look world "ada"))]
        (is (= {:id "cupboard" :name "Cupboard" :desc "A tall painted cupboard." :aspects {:state "shut tight"}
                :fixed? true :closed true}
               (first seen)))
        (is (= {:id "tray" :name "Tray" :desc "A japanned tray." :aspects {:state "sticky"}
                :contents [{:id "spoon" :name "Spoon" :desc "A pewter spoon." :aspects {:state "bent"}}]}
               (get-in (nth seen 2) [:contents 1])))))
    (testing "nothing in the cupboard reaches a view or look"
      (doseq [s [(pr-str (prompt/agent-view (:scenario run) world "ada")) (pr-str (items/look world "ada"))]
              word ["candle" "key" "hook" "\"box\""]]
        (is (not (str/includes? s word)) word)))
    (e/stop-run run)))

;; ---------------------------------------------------------------------------
;; open

(deftest opening
  (let [run (start)
        world (assoc (e/world run) :round 1)
        open (action run :open)
        reply #((:reply open) %1 "ada" {:container %2})]
    (testing "free, for everyone but the GM, and changing nothing"
      (is (= :free (a/kind open)))
      (is (not (a/allowed? open :gm)))
      (is (empty? (a/effect-events open world "ada" {:container "cupboard"}))))
    (testing "the contents in detail; an inner closed container stays closed"
      (is (= {:name "Cupboard" :aspects {:state "shut tight"}
              :contents [{:id "box" :name "Box" :desc "A small tin box." :aspects {:state "dented"} :closed true}
                         {:id "candle" :name "Candle" :desc "A tallow candle." :aspects {:state "half burnt"}}
                         {:id "hook" :name "Hook" :desc "A brass hook inside the door." :aspects {:state "firm"} :fixed? true}]}
             (reply world "cupboard")))
      (is (= [{:id "key" :name "Key" :desc "An iron key." :aspects {:state "rusty"}}]
             (:contents (reply world "box"))) "a known id inside a closed container opens too"))
    (testing "open containers can be opened as well"
      (is (= ["cup" "tray"] (ids (:contents (reply world "shelf"))))))
    (testing "refusals"
      (is (= "the Umbrella is not something you can open" (a/check open world "ada" {:container "umbrella"})))
      (is (= (str "basket is not here; " hall-list) (a/check open world "ada" {:container "basket"})))
      (is (= "you can't open things while travelling"
             (a/check open (first (perform run (at world "ada" "green") "ada" :move {:to "farm"})) "ada" {:container "cupboard"}))))
    (e/stop-run run)))

;; ---------------------------------------------------------------------------
;; take and put-in

(deftest taking-from-containers
  (let [run (start)
        world (assoc (e/world run) :round 1)
        check (fn [world actor n args] (a/check (action run n) world actor args))]
    (testing "from an open container, nested, and from a closed one (no need to open it first)"
      (doseq [[id from] [["cup" "shelf"] ["spoon" "tray"] ["candle" "cupboard"] ["key" "box"]]]
        (let [[taken [e]] (perform run world "ben" :take {:item id})]
          (is (= {:item id :from-place "hall" :from-item from} (:data e)))
          (is (= #{"ada" "ben"} (:visible-to e)))
          (is (= {:holder "ben"} (select-keys (items/item taken id) [:holder :in :place]))))))
    (testing "narration"
      (let [[taken [e]] (perform run world "ben" :take {:item "key"})]
        (is (= "Ben takes the key from the box." (n/describe e (n/names taken))))))
    (testing "fixed things stay put, wherever they are"
      (is (= "the Hook is fixed in place" (check world "ben" :take {:item "hook"}))))
    (testing "not from another room, or from what someone else holds"
      (is (= "key is not here; items here: none" (check (at world "ada" "porch") "ada" :take {:item "key"})))
      (let [[held] (perform run world "ben" :take {:item "box"})]
        (is (= "Ben is holding the Box" (check held "ada" :take {:item "box"})))
        (is (= (str "key is not here; " hall-list) (check held "ada" :take {:item "key"})))
        (is (nil? (check held "ben" :take {:item "key"})) "out of a box you carry")))
    (e/stop-run run)))

(deftest putting-things-in
  (let [run (start)
        world (assoc (e/world run) :round 1)
        check (fn [world actor args] (a/check (action run :put-in) world actor args))]
    (testing "free, and from your hand"
      (is (= :free (a/kind (action run :put-in))))
      (let [[stowed [e]] (perform run world "ben" :put-in {:item "pipe" :container "cupboard"})]
        (is (= {:item "pipe" :into "cupboard"} (:data e)))
        (is (= #{"ada" "ben"} (:visible-to e)))
        (is (= {:in "cupboard"} (select-keys (items/item stowed "pipe") [:holder :in :place])))
        (is (= "Ben puts the pipe in the cupboard." (n/describe e (n/names stowed))))
        (is (not (str/includes? (pr-str (prompt/agent-view (:scenario run) stowed "ada")) "pipe")) "out of sight")))
    (testing "straight from the room, from one container to another, and into a nested one"
      (doseq [[id to] [["umbrella" "shelf"] ["cup" "cupboard"] ["candle" "box"] ["spoon" "box"]]]
        (let [[moved] (perform run world "ben" :put-in {:item id :container to})]
          (is (= to (:in (items/item moved id)))))))
    (testing "refusals"
      (is (= "you can't put the Box inside itself" (check world "ben" {:item "box" :container "box"})))
      (let [[world] (perform run world "ben" :put-in {:item "tray" :container "box"})]
        (is (= "you can't put the Box inside itself" (check world "ben" {:item "box" :container "tray"}))))
      (is (= "the Cup is already in the Shelf" (check world "ben" {:item "cup" :container "shelf"})))
      (is (= "the Umbrella is not something you can put things in" (check world "ben" {:item "cup" :container "umbrella"})))
      (is (= (str "attic is not here; " hall-list) (check world "ben" {:item "cup" :container "attic"})))
      (is (= "the Hook is fixed in place" (check world "ben" {:item "hook" :container "shelf"})))
      (is (= "the Shelf is fixed in place" (check world "ben" {:item "shelf" :container "cupboard"})))
      (is (= (str "basket is not here; " hall-list) (check world "ben" {:item "basket" :container "shelf"}))))
    (testing "put-down still empties your hands onto the floor, not into containers"
      (let [[taken] (perform run world "ben" :take {:item "key"})
            [down] (perform run taken "ben" :put-down {:item "key"})]
        (is (= {:place "hall"} (select-keys (items/item down "key") [:holder :in :place])))))
    (e/stop-run run)))

;; ---------------------------------------------------------------------------
;; Work and the judge

(deftest working-on-a-container
  (let [run (start)
        world (assoc (e/world run) :round 4)
        job {:id "job-9" :actor "ben" :items ["cupboard" "candle"] :doing "cleaning out the cupboard"
             :place "hall" :started 1 :due 4}
        reach (items/in-reach world job)
        user (:content (second (:messages (items/judge-request (:scenario run) world job {:minutes 60 :luck 50} reach))))
        judged (json/read-value (second (re-find #"(?s)```json\n(.*)\n```" user)) json/keyword-keys-object-mapper)]
    (testing "the judge's reach is the container and everything inside it, once each"
      (is (= ["cupboard" "box" "key" "candle" "hook"] (ids reach))))
    (testing "the judge sees what's in what"
      (is (= [nil "cupboard" "box" "cupboard" "cupboard"] (map :in judged)))
      (is (str/includes? (:content (first (:messages (items/judge-request (:scenario run) world job {:minutes 60} reach))))
                         "A thing listed with `in` is inside another")))
    (testing "an item named alone shows no :in, since its container isn't in reach"
      (is (= [{:id "key" :name "Key" :desc "An iron key." :aspects {:state "rusty"}}]
             (-> (items/judge-request (:scenario run) world (assoc job :items ["key"]) {:minutes 30}
                                      (items/in-reach world (assoc job :items ["key"])))
                 :messages second :content (->> (re-find #"(?s)```json\n(.*)\n```") second)
                 (json/read-value json/keyword-keys-object-mapper)))))
    (testing "the judge may change what's inside"
      (let [{:keys [verdict]} (items/judgement {:tool-calls [{:id "j" :name "judge"
                                                              :arguments (json/write-value-as-string
                                                                          {:changes [{:thing "key" :aspect "state" :value "oiled"}]
                                                                           :summary "Ben cleaned it out."})}]}
                                               reach)]
        (is (= [{:item "key" :aspect :state :from "rusty" :to "oiled"}] (:changes verdict)))))
    (testing "work-on accepts things inside containers here"
      (is (nil? (a/check (action run :work-on) world "ben" {:items ["key" "cup"] :doing "oiling the key"}))))
    (e/stop-run run)))

;; ---------------------------------------------------------------------------
;; A short mock run: Ben rummages in the cupboard, stocks the shelf and cleans
;; the cupboard out; Ada goes to bed.

(defn- policy [{:keys [world agent-id role step] :as ctx}]
  (cond
    (= :judge role) (items/mock-judge ctx)
    (not= agent-id "ben") (hamlet/mock-policy ctx)
    :else (get-in {1 [{:tool :open :args {:container "cupboard"}}
                      {:tool :take :args {:item "candle"}}
                      {:tool :put-in :args {:item "candle" :container "shelf"}}
                      {:tool :put-in :args {:item "pipe" :container "box"}}
                      {:tool :pass}]
                   2 [{:tool :work-on :args {:items ["cupboard"] :doing "cleaning out the cupboard" :hours 0.5}}]}
                  [(:round world) step]
                  {:tool :pass})))

(deftest a-run-with-containers
  (let [run (rounds! (start {:llm-impl (llm/mock policy)}) 4)
        world (e/world run)
        evs (e/events run)
        replies (into {} (for [entry (store/read-llm (:dir run))
                               :when (= "ben" (:agent entry))
                               r (:replies entry)
                               :when (= "tool" (:role r))]
                           [(:tool_call_id r) (:content r)]))]
    (testing "no forfeits or bad replies"
      (is (empty? (of-type run :action/forfeited)))
      (is (not-any? #(re-find #"^(error:|rejected:)" %) (vals replies))))
    (testing "open replied with the contents, and recorded nothing"
      (is (= ["box" "candle" "hook"]
             (ids (:contents (json/read-value (replies "mock-0-open") json/keyword-keys-object-mapper))))))
    (testing "the candle is on the shelf and the pipe in the box"
      (is (= "shelf" (:in (items/item world "candle"))))
      (is (= "box" (:in (items/item world "pipe")))))
    (testing "the cleaning reached the cupboard's contents, the pipe included"
      (let [[done] (of-type run :item/work-done)]
        (is (= #{"cupboard" "box" "key" "hook" "pipe"} (set (map :item (get-in done [:data :changes])))))))
    (testing "replay gives the same world"
      (is (= world (w/replay (store/read-events (:dir run))))))
    (testing "Ada, upstairs, saw none of it"
      (is (empty? (filter #(= "item" (namespace (:type %))) (prompt/visible-events evs "ada" 1000)))))
    (testing "the exported map keeps :in and :container, and starts a run with the same items"
      (let [path (str (io/file (:dir run) "map.edn"))
            m (wf/read-file path)
            by-id (into {} (map (juxt :id identity)) (:items m))]
        (is (= {:in "box"} (select-keys (by-id "pipe") [:in :holder :place])))
        (is (= :closed (:container (by-id "cupboard"))))
        (is (str/includes? (slurp path) "{:id \"shelf\" :name \"Shelf\" :place \"hall\" :fixed? true :container :open"))
        (let [r2 (start {:world-file path})]
          (is (= (map #(dissoc % :touched) (items/all world)) (items/all (e/world r2))))
          (e/stop-run r2))))
    (e/stop-run run)))
