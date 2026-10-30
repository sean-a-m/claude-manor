(ns manor.mechanics.goals
  "Goals: an agent's aspirations, plans, and long term desires, kept on the 
   agent as :goals, a vector of at most
  `limit` short texts, with :goals-reviewed the round they last set them.
  Only they see them, in their own prompt. `set-goals` (free) replaces the
  whole list whenever they like, and once every `:goals-every` rounds (an
  engine option; 0 turns it off) their turn asks them to review it."
  (:require [manor.narrate :as narrate]
            [manor.prompts :as prompts]
            [manor.world :as w]))

(def limit
  "How many goals an agent keeps at once."
  10)

(def goal-limit
  "The length `set-goals` tells models to keep each goal to. Its schema
  allows up to 300 characters, as slack, and advises longer goals down to this."
  200)

(defn goals
  "`agent-id`'s current goals, a vector (empty if none)."
  [world agent-id]
  (vec (:goals (w/agent world agent-id))))

(defn due?
  "Should `agent-id`'s turn ask them to review their goals: `every` is
  positive, and they have never set them or last did `every` rounds ago or
  more?"
  [world agent-id every]
  (and (pos? (or every 0))
       (let [reviewed (:goals-reviewed (w/agent world agent-id))]
         (or (nil? reviewed) (>= (- (:round world) reviewed) every)))))

(defn review-request
  "The part of a turn's closing that asks for a review."
  [world]
  (prompts/render world "agent/goals-review.md" {:goals-max limit}))

(defn actions []
  [{:name :set-goals
    :kind :free :per-turn 1
    :doc (prompts/doc "docs/builtins/set-goals.md" {:goals-max limit :goal-limit goal-limit})
    :args [:map [:goals [:vector {:max limit} [:string {:min 1 :max 300 :advised-max goal-limit}]]]]
    :effect (fn [_ actor {goals :goals}]
              [{:type :agent/goals-set :data {:goals (vec goals)} :visible-to #{actor}}])}])

(defmethod w/apply-event :agent/goals-set [world {:keys [actor round data]}]
  (update-in world [:agents actor] assoc :goals (vec (:goals data)) :goals-reviewed round))

(defmethod narrate/describe :agent/goals-set [{:keys [actor data]} names]
  (let [n (count (:goals data))]
    (str (narrate/who names actor) " reviews private goals ("
         (if (zero? n) "none kept" (str n " kept")) ").")))
