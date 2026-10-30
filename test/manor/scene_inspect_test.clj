(ns manor.scene-inspect-test
  (:refer-clojure :exclude [agent])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hiccup2.core :as h]
            [jsonista.core :as json]
            [manor.engine :as e]
            [manor.test-support :as support]
            [manor.mechanics.items :as items]
            [manor.mechanics.space :as space]
            [manor.prompt :as prompt]
            [manor.scenarios-test :refer [bundled]]
            [manor.tools.scene :as scene]
            [manor.tools.scene-inspect :as inspect]
            [manor.tools.web :as web]
            [manor.world :as w]))

(use-fixtures :once support/with-runs)

(defn- html [x] (str (h/html x)))
(defn- request [uri & [query]] (#'web/handler {:uri uri :query-string query :request-method :get}))
(defn- run-of [scenario]
  (e/start-run scenario {:llm :mock :offline? true :test? true :run-id (str (gensym "inspect-"))}))
(defn- snap-of [specs]
  (let [{:keys [events world]}
        (reduce (fn [{:keys [events world]} event]
                  (let [event (w/stamp world event)]
                    {:events (conj events event) :world (w/apply-stamped world event)}))
                {:events [] :world (w/new-world)} specs)]
    {:events events :world world}))
(defn- location [id] {:type :space/location-added :data {:location {:id id :name id}}})
(defn- agent [id & [extra]]
  {:type :agent/spawned :data {:agent (merge {:id id :name id :role :guest :controller :llm :location "hall"} extra)}})
(defn- tool-call [id name args] {:id id :type "function" :function {:name name :arguments args}})
(defn- reply [id text] {:role "tool" :tool_call_id id :content text})
(defn- call [round requests replies]
  {:agent "a" :round round :model "recorded/model" :messages [{:role "system" :content "root"}]
   :response {:role "assistant" :tool_calls requests} :replies replies})

(deftest household-inspectors-and-read-only-boundary
  (let [run (run-of :household)]
    (try
      (web/show! run)
      (let [snap (scene/snapshot run)
            world (:world snap)
            views (into {} (for [ag (w/agents-in-order world)]
                             [(:id ag) (prompt/agent-view (:scenario run) world (:id ag))]))
            pugh (first (filter #(= :architect (:role %)) (w/agents-in-order world)))
            lord (first (filter #(= :earl (:role %)) (w/agents-in-order world)))
            person (:body (request (str "/partial/inspect/agent/" (:id lord))))
            room (:body (request "/partial/inspect/place/drawing-room"))
            item (:body (request "/partial/inspect/item/pocket-watch"))]
        (doseq [text ["Omniscient operator" "Recently" "Current inner state" "Carrying" "Post, bedroom" "Notes and last seen" "Persona, manner" "their view" "feed as them" "Pocket watch"]]
          (is (str/includes? person text) text))
        (is (str/includes? room "Faded rose wallpaper"))
        (is (str/includes? room "data-id=\"piano\" data-kind=\"item\""))
        (is (str/includes? item "data-kind=\"agent\""))
        (is (str/includes? item "Last touched"))
        (is (str/includes? (:body (request (str "/partial/inspect/agent/" (:id pugh)))) "routine-current"))
        (let [state (json/read-value (:body (request "/partial/scene-state" (str "kind=agent&id=" (:id lord))))
                                     json/keyword-keys-object-mapper)]
          (is (= {:kind "agent" :id (:id lord)} (:selection state)))
          (is (= person (:inspector state))))
        (is (= snap (scene/snapshot run)))
        (is (= views (into {} (for [ag (w/agents-in-order (e/world run))]
                               [(:id ag) (prompt/agent-view (:scenario run) (e/world run) (:id ag))])))))
      (finally (e/stop-run run)))))

(deftest generic-inspectors-for-all-bundled-scenarios
  (doseq [scenario bundled]
    (testing (name scenario)
      (let [run (run-of scenario)]
        (try
          (web/show! run)
          (doseq [ag (w/agents-in-order (e/world run))]
            (is (= 200 (:status (request (str "/partial/inspect/agent/" (:id ag)))))))
          (doseq [loc (keys (get-in (e/world run) [:space :locations]))]
            (is (= 200 (:status (request (str "/partial/inspect/place/" loc))))))
          (doseq [it (items/all (e/world run))]
            (is (= 200 (:status (request (str "/partial/inspect/item/" (:id it)))))))
          (finally (e/stop-run run)))))))

(deftest current-private-state-dialogue-and-unchanged-agent-feed-projections
  (let [run (run-of :village)
        snap (snap-of [(location "hall") (agent "a" {:inner {:mood "private inner"}}) (agent "b")
                      {:type :agent/noted :actor "a" :visible-to #{"a"} :data {:text "private note A"}}
                      {:type :agent/noted :actor "b" :visible-to #{"b"} :data {:text "private note B"}}
                      {:type :agent/said :actor "b" :visible-to #{"a" "b"} :data {:text "heard public line"}}
                      {:type :agent/whispered :actor "b" :visible-to #{"b"} :data {:to "b" :text "not heard by A"}}])
        fixture (assoc run :events (atom (:events snap)) :world (atom (:world snap)))
        before (prompt/agent-view (:scenario run) (:world snap) "b")]
    (try
      (web/show! fixture)
      (let [operator (:body (request "/partial/inspect/agent/a"))
            panel (:body (request "/panel" "as=b"))
            view (:body (request "/agent/b"))]
        (is (str/includes? operator "private inner"))
        (is (str/includes? operator "private note A"))
        (is (str/includes? operator "heard public line"))
        (is (not (str/includes? operator "not heard by A")))
        (is (str/includes? panel "private note B"))
        (is (not (str/includes? panel "private note A")))
        (is (not (str/includes? view "private inner")))
        (is (not (str/includes? view "private note A")))
        (is (= before (prompt/agent-view (:scenario run) (e/world fixture) "b")))
        (is (= (:world snap) (e/world fixture))))
      (finally (e/stop-run run)))))

(deftest carrying-closed-container-stores-jobs-gone-and-history
  (let [snap (snap-of [(location "hall") (location "kitchen") (agent "a")
                      {:type :item/added :data {:item {:id "bag" :name "Bag" :holder "a" :container :closed}}}
                      {:type :item/added :data {:item {:id "key" :name "Key" :in "bag" :owner "a"}}}
                      {:type :item/added :data {:item {:id "store" :name "Pantry" :place "hall" :fixed? true
                                                     :store {:keeps "food" :serves ["kitchen"]}
                                                     :aspects {:stock "plenty" :lacking "tea"}}}}
                      {:type :item/added :data {:item {:id "coal" :name "Coal" :place "hall" :consumable? true}}}
                      {:type :round/started :data {:round 1}}
                      {:type :item/work-begun :data {:id "job" :actor "a" :place "hall" :items ["store"]
                                                    :doing "tidying" :started 1 :due 3}}
                      {:type :items/stewarded :data {:used-up [{:item "coal" :name "Coal" :fate "burned"}]}}])
        render #(html (inspect/fragment snap [] %1 %2))
        bag (render "item" "bag") room (render "place" "hall")
        person (render "agent" "a") gone (render "item" "coal")]
    (doseq [text ["closed" "Key" "data-id=\"key\""]]
      (is (str/includes? bag text)) (is (str/includes? person text)))
    (doseq [text ["fixed" "Keeps" "food" "Stock" "plenty" "Lacking" "tea" "tidying" "due r3" "burned"]]
      (is (str/includes? room text) text))
    (is (str/includes? (render "place" "kitchen") "Pantry"))
    (is (str/includes? gone "No longer present"))
    (is (str/includes? gone "burned"))
    (is (str/includes? (render "item" "key") "data-id=\"bag\""))
    (is (str/includes? (render "item" "store") "tidying"))
    (is (= [:item/added :items/stewarded] (mapv :type (inspect/item-history (:events snap) "coal"))))
    (let [events (conj (:events snap) {:type :item/work-done :round 3 :data {:job "job"}})]
      (is (= :item/work-done (:type (last (inspect/item-history events "store"))))))))

(deftest multiple-calls-rejections-conversation-context-and-final-resolution
  (let [one (assoc (call 2 [(tool-call "bad" "take" "{\"item\":\"missing\"}")]
                        [(reply "bad" "rejected: no such item")]) :thoughts "Only actually returned reasoning")
        two (assoc (call 2 [(tool-call "ok" "take" "{\"item\":\"coin\"}") (tool-call "extra" "pass" "{}")]
                        [(reply "ok" "done") (reply "extra" "ignored: your turn is already over")])
                   :messages (into (conj (:messages one) (:response one)) (:replies one)))
        speaking (assoc (call 2 [(tool-call "speech" "reply" "{\"text\":\"hello\"}")] [(reply "speech" "done")])
                        :conversation {:id "conv-1" :pass 1})
        snap (snap-of [(agent "a") {:type :round/started :data {:round 2}}
                      {:type :action/rejected :actor "a" :visible-to #{"a"} :data {:action "take" :reason "coin already taken"}}])
        calls [(call 1 [(tool-call "old" "pass" "{}")] []) one speaking two]
        recent (inspect/recently snap calls "a")
        rendered (html (inspect/fragment snap calls "agent" "a"))]
    (is (= 2 (count (:groups recent))))
    (is (= [2 1] (mapv count (:groups recent))))
    (doseq [text ["rejected: no such item" "ignored: your turn is already over" "coin already taken"
                  "conv-1" "Only actually returned reasoning" "not proof of final resolution"]]
      (is (str/includes? rendered text) text))
    (is (= 1 (count (re-seq #"Thoughts · returned reasoning only" rendered))))
    (is (= ["done"] (get-in recent [:groups 0 1 :requests 0 :replies])))))

(deftest old-call-replies-are-inferred-only-from-matching-continuations
  (let [one (dissoc (call 1 [(tool-call "x" "move" "{}")] []) :replies)
        two (assoc (call 1 [(tool-call "y" "pass" "{}")] [])
                   :messages (conj (conj (:messages one) (:response one)) (reply "x" "rejected: closed")))
        unrelated (assoc two :conversation {:id "different" :pass 1})
        snap (snap-of [(agent "a") {:type :round/started :data {:round 1}}])]
    (is (= ["rejected: closed"] (get-in (inspect/recently snap [one unrelated two] "a") [:groups 0 0 :requests 0 :replies])))
    (is (= [] (get-in (inspect/recently snap [one unrelated] "a") [:groups 0 0 :requests 0 :replies])))
    (is (str/includes? (html (inspect/fragment snap [one] "agent" "a")) "Engine reply not recorded"))))

(deftest incomplete-and-text-only-calls-do-not-invent-reasoning-or-tools
  (let [snap (snap-of [(agent "a") {:type :round/started :data {:round 1}}])
        empty-call {:agent "a" :round 1 :messages [{:role "system" :content "root"}]
                    :response {:role "assistant"} :finish-reason "length"}
        next-call (assoc (call 1 [] [{:role "user" :content "Your text was taken as reply. done"}])
                         :messages (conj (:messages empty-call) {:role "user" :content "think briefly"})
                         :response {:role "assistant" :content "A spoken line"})
        rendered (html (inspect/fragment snap [empty-call next-call] "agent" "a"))]
    (is (= 1 (count (inspect/call-groups [empty-call next-call]))))
    (doseq [text ["No tool call returned" "A spoken line" "Your text was taken as reply" "think briefly"]]
      (is (str/includes? rendered text)))
    (is (not (str/includes? rendered "Thoughts · returned reasoning")))
    (is (seq (html (inspect/fragment snap [{:agent "a" :round 1}] "agent" "a"))))))

(deftest missing-non-llm-retired-and-safe-identifiers
  (let [run (run-of :quiz)
        id "odd /\"<>&?[]+%"
        snap (snap-of [(agent id {:name "<script>alert(1)</script>" :controller :human
                                 :inner {:mood "<img onerror=bad>"}})
                      {:type :agent/retired :data {:id id}}])
        fixture (assoc run :dir nil :world (atom (:world snap)) :events (atom (:events snap)))]
    (try
      (web/show! fixture)
      (let [rendered (:body (request (str "/partial/inspect/agent/" (inspect/encode-id id))))]
        (is (str/includes? rendered "&lt;script&gt;alert(1)&lt;/script&gt;"))
        (is (str/includes? rendered "&lt;img onerror=bad&gt;"))
        (is (not (str/includes? rendered "<script>")))
        (is (str/includes? rendered "retired"))
        (is (str/includes? rendered "No model calls recorded yet"))
        (is (str/includes? rendered (str "/panel?as=" (inspect/encode-id id)))))
      (doseq [kind ["agent" "place" "item"]]
        (is (str/includes? (:body (request (str "/partial/inspect/" kind "/missing"))) "Selection no longer available")))
      (doseq [uri ["/partial/inspect/agent/%ZZ" "/partial/inspect/unknown/id" "/partial/inspect/agent/"]]
        (is (= 404 (:status (request uri)))))
      (is (= [] (inspect/read-calls fixture)))
      (finally (e/stop-run run)))))

(deftest loaded-run-recently-reads-durable-log-not-last-calls
  (let [run (run-of :village)]
    (try
      (e/auto! run 2) (e/wait-idle run 60000)
      (let [calls (inspect/read-calls run)
            id (:agent (first (filter #(not= "gm" (:agent %)) calls)))
            live (inspect/recently (scene/snapshot run) calls id)
            loaded (e/load-run (:id run) {:llm :mock :offline? true :test? true})]
        (try
          (is (seq calls)) (is (some? id))
          (is (empty? @(:last-calls loaded)))
          (is (= live (inspect/recently (scene/snapshot loaded) (inspect/read-calls loaded) id)))
          (web/show! loaded)
          (is (str/includes? (:body (request (str "/partial/inspect/agent/" id))) "Latest recorded round"))
          (finally (e/stop-run loaded))))
      (finally (e/stop-run run)))))

(deftest completed-log-lines-and-partial-append
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "manor-inspect-" (make-array java.nio.file.attribute.FileAttribute 0)))
        f (io/file dir "llm.edn")]
    (try
      (is (= [] (inspect/read-calls {:dir dir})))
      (spit f (str (pr-str {:agent "a" :round 1}) "\n{:agent"))
      (is (= [{:agent "a" :round 1}] (inspect/read-calls {:dir dir})))
      (spit f " \"b\" :round 2}\n" :append true)
      (is (= [{:agent "a" :round 1} {:agent "b" :round 2}] (inspect/read-calls {:dir dir})))
      (finally (.delete f) (.delete dir)))))
