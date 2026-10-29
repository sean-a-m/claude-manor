(ns manor.ruleset.mock
  "The scripted mock policy, so a ruleset scenario runs a day with no model
  calls. A day is agent id -> time of day -> the tool calls of that turn, in
  order (free ones first); turns not listed pass. A scenario's day is data:
  an EDN file such as resources/worlds/claudeworth-day.edn, or a map."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [manor.mechanics.positions :as positions]
            [manor.mechanics.shop :as shop]
            [manor.mechanics.space :as space]
            [manor.ruleset.places :as places]
            [manor.world :as w]))

(defn read-day
  "A mock day from a classpath resource, such as \"worlds/lodge-day.edn\"."
  [resource]
  (edn/read-string (slurp (io/resource resource))))

(defn- scripted-done
  "How many of `script`'s calls are done, given `used` (the tools called this
  turn, in order). Calls off the script, like a walk to bed, don't count."
  [script used]
  (reduce (fn [i tool] (if (= tool (some-> (get script i) first name)) (inc i) i)) 0 used))

(defn- way-to-bed
  "The call that takes `id` to their bedroom: a walk, or a travel if it's in
  another area (which takes the turn). nil if they're there already."
  [world id]
  (let [here (space/location-of world id)
        bed (places/bedroom-of world id)]
    (when (and bed (not= here bed))
      [(if (contains? (space/area world here) bed) :walk :travel) {:to bed}])))

(defn- remove-absent-cast
  "Scripted calls to someone not in the run (see :leave-out) are dropped,
  such as the countess's gift to the housemaid, and her call for her."
  [world script]
  (remove (fn [[tool {:keys [to]}]] (and (#{:give :call} tool) to (not (w/agent world to)))) script))

(defn- own-plan
  "The first plan (by id) that `id` holds, drawn for them or by them, or nil."
  [world id]
  (first (sort-by :id (filter #(contains? (:holders %) id) (vals (get-in world [:state :plans]))))))

(defn- with-plan
  "Plans in a script: `commission` and `show-plans` take the id of your own
  plan, and are dropped while you have none (with no architect in the run,
  none is drawn); a walk to \"@plan\" goes into the room built from it, and
  is dropped until it is built."
  [world agent-id script]
  (let [plan (own-plan world agent-id)
        built? (and plan (space/location world (:id plan)))]
    (keep (fn [[tool args]]
            (cond (and (= :walk tool) (= "@plan" (:to args))) (when built? [tool (assoc args :to (:id plan))])
                  (#{:commission :show-plans} tool) (when plan [tool (assoc args :plan-id (:id plan))])
                  :else [tool args]))
          script)))

(defn scripted-policy
  "The next call of `day` (agent id -> time -> tool calls) for this turn.
  A scripted `sleep` away from your bedroom goes there first, e.g. after a
  late :start. A shop's clerk with nothing scripted serves the customers
  scripted to ask there (manor.mechanics.shop/scripted-clerk)."
  [day {:keys [world agent-id role used]}]
  (cond
    (= role :gm) {:tool :done}
    (and (positions/capable? world agent-id :shop-clerk)
         (nil? (get-in day [agent-id (str (places/now world))]))) (shop/scripted-clerk day (str (places/now world)) world agent-id used)
    :else
    (let [time (str (places/now world))
          script (remove-absent-cast world (get-in day [agent-id time]))
          ;; A late start may already put someone in their bedroom.
          script (if (some #(= :sleep (first %)) script)
                   (remove (fn [[tool {:keys [to]}]]
                             (and (= :walk tool) (= to (places/bedroom-of world agent-id))
                                  (= to (space/location-of world agent-id)))) script)
                   script)
          script (with-plan world agent-id script)
          script (mapv (fn [[tool args]] (shop/scripted-customer world agent-id tool args)) script)
          [tool args] (get script (scripted-done script used))
          [tool args] (or (when (= :sleep tool) (way-to-bed world agent-id)) [tool args])]
      (if tool {:tool tool :args args}
          {:tool (if (:interrupted-by (w/busy world agent-id)) :carry-on :pass)}))))
