(ns manor.scene-temporal-test
  "Presentation history survives missed refreshes, reload and disposable caches."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hiccup2.core :as h]
            [manor.engine :as e]
            [manor.prompt :as prompt]
            [manor.tools.scene :as scene]
            [manor.tools.scene-ruleset :as household]
            [manor.tools.scene-inspect :as inspect]
            [manor.tools.scene-time :as time]
            [manor.world :as w]))

(defn- html [x] (str (h/html x)))
(defn- location [id & [extra]]
  {:type :space/location-added :data {:location (merge {:id id :name id :domain :house :floor :ground} extra)}})
(defn- route [a b & [extra]]
  {:type :space/route-opened :data (merge {:from a :to b :travel 0 :one-way? false} extra)})
(defn- events-of [specs]
  (:events (reduce (fn [{:keys [world events]} event]
                     (let [event (w/stamp world event)]
                       {:world (w/apply-stamped world event) :events (conj events event)}))
                   {:world (w/new-world) :events []} specs)))
(defn- snap [events]
  {:id "temporal" :events events :world (w/replay events) :scenario {:scene household/hooks}
   :status {:state :idle :mode :manual :acting #{}}})
(defn- geometry [result]
  (update-vals (:rooms result) #(select-keys % [:x :y :w :h :panel :region :floor])))

(def plan {:id "extension" :name "Morning room" :floor :ground :size "large"
           :desc "Fresh plaster." :connects-to ["hall"] :holders #{"owner"} :price 240})
(def commissioned {:type :building/commissioned
                   :data {:plan-id "extension" :room "extension-2" :name "Morning room" :domain :house
                          :price 240 :account "household" :due 5 :by "owner" :connects-to ["hall"]}})
(def completed {:type :building/works-completed
                :data {:plan-id "extension" :room "extension-2" :name "Morning room" :domain :house :connects-to ["hall"]}})
(def initial [{:type :world/initialized :data {:state {:accounts {"household" 5000} :works [] :plans {}}}}
              (location "hall") (location "study") (route "hall" "study")
              {:type :round/started :data {:round 1}}
              {:type :building/plans-drawn :data {:plan plan}}])

(deftest replayable-reservation-and-immutable-plan-evidence
  (let [ghost-prefix (conj initial commissioned)
        ;; Even a later replacement of a plan cannot resize/re-floor the old reservation.
        changed-plan {:type :building/plans-drawn
                      :data {:plan (assoc plan :size "grand" :floor :first :connects-to ["study"])}}
        specs (into ghost-prefix [changed-plan (location "unrelated")
                                 (route "unrelated" "study")
                                 {:type :round/started :data {:round 5}} completed
                                 (location "extension-2" {:name "Morning room" :kind :room})
                                 (route "extension-2" "hall")])
        cache (atom nil)
        ghost (scene/presentation (snap (events-of ghost-prefix)) cache)
        ghost-room (get-in ghost [:rooms "extension-2"])]
    (is (= [2 2 :ground :house ["hall"]]
           ((juxt :w :h :floor :domain :connects-to) ghost-room)))
    (is (:reservation? ghost-room))
    (is (str/includes? (html (scene/svg (snap (events-of ghost-prefix)) ghost)) "room ghost"))
    (is (str/includes? (html (scene/svg (snap (events-of ghost-prefix)) ghost)) "planned connection"))
    (is (str/includes? (html (inspect/fragment (snap (events-of ghost-prefix)) [] "place" "extension-2"))
                       "Under construction"))
    (doseq [n (range (count ghost-prefix) (inc (count specs)))]
      (let [snapshot (snap (events-of (take n specs)))
            cold (scene/presentation snapshot)
            warm (scene/presentation snapshot cache)]
        (is (= cold warm) (str "cold/warm prefix " n))
        (is (= (geometry ghost) (select-keys (geometry warm) (keys (geometry ghost)))))
        (is (= :large (get-in warm [:rooms "extension-2" :size])))))
    (let [finished (snap (events-of specs))
          live (scene/presentation finished cache)
          missed (scene/presentation finished (atom nil))]
      (is (= live missed) "browser first opened after both commission and completion")
      (reset! cache nil)
      (is (= live (scene/presentation finished cache)) "eviction loses no historical geometry")
      (is (not (get-in live [:rooms "extension-2" :reservation?])))
      (is (nil? (get-in finished [:world :space :locations "extension-2" :size])))
      (is (empty? (get-in finished [:world :state :works])))
      (is (= (:world finished) (w/replay (:events finished))))
      (let [after-routes (snap (events-of (into specs [(route "study" "extension-2" {:one-way? true :travel 2})
                                                      {:type :space/route-closed
                                                       :data {:from "extension-2" :to "hall" :one-way? true}}])))
            before-cache @cache
            updated (scene/presentation after-routes cache)]
        (is (= (geometry live) (geometry updated)))
        (is (identical? (:placement before-cache) (:placement @cache)))
        (is (not= (:connections live) (:connections updated)))))
    (let [between (snap (events-of (take (+ (count ghost-prefix) 5) specs)))]
      (is (empty? (get-in between [:world :state :works])))
      (is (get-in (scene/presentation between) [:rooms "extension-2" :reservation?])
          "completion precedes location event; no disappearing reservation"))))

(deftest completion-only-and-incomplete-records
  (let [specs (into initial [completed (location "extension-2")])
        result (scene/presentation (snap (events-of specs)))]
    (is (= [2 2] ((juxt :w :h) (get-in result [:rooms "extension-2"]))))
    (is (not (get-in result [:rooms "extension-2" :reservation?])))
    (is (= 1 (count (:steps (scene/placement-history (events-of specs) (household/history (events-of specs))))))))
  (doseq [size [nil "cottage"]]
    (let [specs (into (pop initial) [{:type :building/plans-drawn :data {:plan (assoc plan :size size)}}
                                    commissioned completed (location "extension-2")])
          result (scene/presentation (snap (events-of specs)))]
      (is (= [1 1] ((juxt :w :h) (get-in result [:rooms "extension-2"]))))))
  (let [specs (into (pop initial) [commissioned completed (location "extension-2")
                                    {:type :building/plans-drawn :data {:plan plan}}])]
    (is (= [1 1] ((juxt :w :h) (get-in (scene/presentation (snap (events-of specs))) [:rooms "extension-2"])))
        "future plan evidence never resizes an earlier unknown-size room")))

(deftest closed-hours-and-recorded-progress
  (let [specs [{:type :world/initialized :data {:state {} :clock {:start "1904-10-15T08:00" :minutes-per-round 30}}}
               (location "shop" {:hours ["09:00" "18:00"]})
               {:type :agent/spawned :data {:agent {:id "worker" :name "Worker" :role :guest :location "shop"}}}
               {:type :round/started :data {:round 1}}
               {:type :item/work-begun :actor "worker"
                :data {:id "job" :actor "worker" :doing "polishing" :items [] :place "shop" :started 1 :due 5}}
               {:type :agent/occupied :actor "worker" :data {:activity "polishing" :job "job" :until 5}}]
        snapshot (snap (events-of (conj specs {:type :round/started :data {:round 3}})))
        worker (w/agent (:world snapshot) "worker")
        rendered (html (scene/svg snapshot (scene/presentation snapshot)))]
    (is (= 1/2 (time/busy-progress snapshot worker)))
    (is (str/includes? rendered "busy-progress"))
    (is (str/includes? rendered "data-progress=\"0.5\""))
    (is (not (str/includes? rendered "closed-shop")))
    (let [early (snap (events-of specs))]
      (is (str/includes? (html (scene/svg early (scene/presentation early))) "closed-shop")))
    (is (false? (time/closed? (:world snapshot) {:hours ["09:00" "18:00"]})))
    (is (true? (time/closed? (assoc (:world snapshot) :round 21) {:hours ["09:00" "18:00"]})))
    (is (nil? (time/closed? (dissoc (:world snapshot) :clock) {:hours ["09:00" "18:00"]})))
    (is (nil? (time/closed? (:world snapshot) {:hours ["bad"]})))
    (let [occupation (snap (events-of (conj specs
                                           {:type :agent/occupied :actor "worker" :data {:activity "resting" :until 5}}
                                           {:type :round/started :data {:round 2}})))]
      (is (= 1/4 (time/busy-progress occupation (w/agent (:world occupation) "worker"))))
      (let [resumed (snap (events-of (conj specs
                                           {:type :agent/interrupted :actor "worker" :data {:by "visitor"}}
                                           {:type :agent/resumed :actor "worker"}
                                           {:type :round/started :data {:round 4}})))]
        (is (= 3/4 (time/busy-progress resumed (w/agent (:world resumed) "worker")))))
      (is (nil? (time/busy-progress (assoc occupation :events []) (w/agent (:world occupation) "worker"))))
      (is (= 1 (time/busy-progress (assoc-in occupation [:world :round] 10)
                                   (w/agent (:world occupation) "worker"))))
      (is (= 0 (time/busy-progress (assoc-in occupation [:world :round] 0)
                                   (w/agent (:world occupation) "worker")))))
    (let [indefinite (snap (events-of (conj specs {:type :agent/occupied :actor "worker"
                                                  :data {:activity "keeping shop" :hibernating? true}})))
          rendered (html (scene/svg indefinite (scene/presentation indefinite)))]
      (is (nil? (time/busy-progress indefinite (w/agent (:world indefinite) "worker"))))
      (is (str/includes? rendered "busy-mark"))
      (is (not (str/includes? rendered "data-progress"))))))

(deftest optional-hooks-and-escaped-overlays
  (let [snapshot (snap (events-of (conj initial commissioned)))
        generic (dissoc snapshot :scenario)
        overlay (assoc snapshot :scenario
                       {:scene {:toolbar (fn [_] [:p "<funds>"])
                                :badges (fn [_ _] ["<badge>"])
                                :inspect (fn [_ _ _] [:p "<private plans>"])}})]
    (is (not (contains? (:rooms (scene/presentation generic)) "extension-2")))
    (is (not (str/includes? (html (scene/toolbar generic)) "Household funds")))
    (is (str/includes? (html (scene/toolbar snapshot)) "Household funds"))
    (is (str/includes? (html (scene/toolbar overlay)) "&lt;funds&gt;"))
    (is (str/includes? (html (scene/svg overlay (scene/presentation overlay))) "&lt;badge&gt;"))
    (is (str/includes? (html (inspect/fragment overlay [] "place" "hall")) "&lt;private plans&gt;"))
    (is (str/includes? (html (scene/svg snapshot (scene/presentation snapshot))) "adjoining works")))
  (let [world {:state {:accounts {"pugh" 258} :plans {"p" (assoc plan :holders #{"pugh"})}}}]
    (is (str/includes? (html (household/inspect world "agent" "pugh")) "£1 1s 6d"))
    (is (str/includes? (html (household/inspect world "agent" "pugh")) "Morning room"))))

(deftest mock-construction-live-load-and-cache-eviction
  (let [run (e/start-run :household {:llm :mock :offline? true :test? true :build-rounds 6
                                    :run-id (str (gensym "scene-construction-"))})
        cache (atom nil)]
    (try
      (e/auto! run 7)
      (is (= :idle (e/wait-idle run 60000)))
      (let [before (scene/snapshot run)
            ghost (scene/presentation before cache)
            loaded (e/load-run (:id run) {:llm :mock :offline? true :test? true})]
        (try
          (is (get-in ghost [:rooms "library" :reservation?]))
          (is (= ghost (scene/presentation (scene/snapshot loaded))))
          (finally (e/stop-run loaded)))
        (e/auto! run 6)
        (is (= :idle (e/wait-idle run 60000)))
        (let [after (scene/snapshot run)
              views (into {} (for [ag (w/active-agents (:world after))]
                               [(:id ag) (prompt/agent-view (:scenario run) (:world after) (:id ag))]))
              live (scene/presentation after cache)
              loaded (e/load-run (:id run) {:llm :mock :offline? true :test? true})]
          (try
            (is (= (geometry ghost) (geometry live)))
            (is (= [2 2] ((juxt :w :h) (get-in live [:rooms "library"]))))
            (is (not (get-in live [:rooms "library" :reservation?])))
            (is (= live (scene/presentation (scene/snapshot loaded))))
            (is (= live (scene/presentation after (atom nil))) "missed creation AND completion")
            (reset! cache nil)
            (is (= live (scene/presentation after cache)))
            (html (scene/svg after live))
            (html (scene/toolbar after))
            (doseq [id (keys views)] (html (inspect/fragment after [] "agent" id)))
            (is (= after (scene/snapshot run)) "rendering does not mutate the run")
            (is (= views (into {} (for [ag (w/active-agents (e/world run))]
                                   [(:id ag) (prompt/agent-view (:scenario run) (e/world run) (:id ag))]))))
            (is (= (e/world run) (w/replay (e/events run))))
            (is (not-any? #(contains? (get-in % [:data :location]) :size)
                          (filter #(= :space/location-added (:type %)) (e/events run))))
            (finally (e/stop-run loaded)))))
      (finally (e/stop-run run)))))
