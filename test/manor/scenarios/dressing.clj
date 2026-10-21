(ns manor.scenarios.dressing
  "Test scenario for person aspects (manor.mechanics.items): a lady, her maid and a
  cook in a small house loaded from test/fixtures/dressing.edn, starting at
  18:00, with the hamlet's sleep, rouse and check-time. Its mock judge
  changes people: work on others dresses their hair, and work on nothing
  else changes the worker's dress; its mock steward lets sleepers' hair
  down overnight. With the mock policy Dawson does her
  ladyship's hair, Mrs Pell cooks, and Lady Ashby changes for dinner and
  goes to bed.

    clojure -M:dev:run dressing --mock --test --port 8091"
  (:require [manor.io.worldfile :as worldfile]
            [manor.mechanics.items :as items]
            [manor.scenarios.hamlet :as hamlet]
            [manor.world :as w]))

(def world-file "test/fixtures/dressing.edn")

(defn- spend [on doing hours]
  {:tool :spend-time :args (cond-> {:doing doing :hours hours} (seq on) (assoc :on on))})

(def mock-day
  "Agent id -> round -> the call of that turn."
  {"dawson" {1 (spend ["lady-ashby"] "dressing her ladyship's hair" 1.0)}
   "mrs-pell" {1 (spend ["range"] "cooking dinner at the range" 2.0)}
   "lady-ashby" {3 (spend [] "changing for dinner" 0.5)
                 6 {:tool :sleep :args {:until "07:00"}}}})

(defn mock-policy [{:keys [world agent-id role]}]
  (cond
    (= role :gm) {:tool :done}
    (:interrupted-by (w/busy world agent-id)) {:tool :carry-on}
    :else (get-in mock-day [agent-id (:round world)] {:tool :pass})))

(defn- cap [s] (subs s 0 (min 80 (count s))))

(defn mock-judge
  "items/mock-judge for things; for people, each other person named and
  still here gets their hair done by the worker, and when nobody else was
  named the worker's own dress changes."
  [{:keys [world job] :as ctx}]
  (let [{:keys [actor doing]} job
        named (remove #{actor} (:people job))
        here (filter (set named) (items/judged-people world job))
        people (if (seq named)
                 (for [id here] {:thing id :aspect "hair" :value (str "dressed by " (:name (w/agent world actor)))})
                 [{:thing actor :aspect "dress" :value (cap (str "changed: " doing))}])]
    (update-in (items/mock-judge ctx) [:args :changes]
               #(into (vec (remove (fn [c] (w/agent world (:thing c))) %)) people))))

(defn mock-steward
  "items/mock-steward for things; each sleeper's hair is let down, and
  their other aspects stay."
  [{:keys [sleepers] :as ctx}]
  (update-in (items/mock-steward ctx) [:args :changes] (fnil into [])
             (for [{:keys [id aspects]} sleepers :when (:hair aspects)]
               {:thing id :aspect "hair" :value ""})))

(def scenario
  {:name :dressing
   :init (fn [opts] (assoc (worldfile/load-world (or (:world-file opts) world-file)) :state {}))
   :roles {:lady {:brief "You are the lady of the house. Dinner is at eight."}
           :maid {:brief "You are her ladyship's maid: you dress her and do her hair."}
           :cook {:brief "You are the cook. Dinner is at eight."}
           :gm {:brief "Test GM."}}
   :actions hamlet/actions
   :items {:steward {:at "02:00" :idle-hours 4 :max-per-call 8}}
   :mock-policy mock-policy
   :mock-judge mock-judge
   :mock-steward mock-steward})
