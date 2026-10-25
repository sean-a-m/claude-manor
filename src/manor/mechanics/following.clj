(ns manor.mechanics.following
  "Following someone: `follow {who}` keeps you with another person here, so
  that whenever they walk or travel you go too, at once and within their
  turn, until you `follow-stop`, go somewhere on your own, or can't keep up.
  Both are free actions, offered in conversations' speaking turns too, so
  someone asked to come along can say yes and follow in the same line.

  A follower who is busy (spending time on something) when their leader goes
  stops it and goes with them: the work is judged on the time done, as when
  an interrupted job is stopped. One who can't go (asleep, unconscious,
  unable to move, or no longer in the place the leader leaves) is left
  behind and stops following. Followers of followers go too. Nobody can
  follow someone who, directly or through others, follows them.

  World shape: :following on the follower's agent, the leader's id. The
  scenario's moving actions wrap their events with `along`; the agent's view
  gets `you.following` and `you.followed-by` (`add-to-view`)."
  (:require [manor.mechanics.builtins :as b]
            [manor.mechanics.physical :as physical]
            [manor.mechanics.space :as space]
            [manor.narrate :as narrate]
            [manor.world :as w]))

;; ---------------------------------------------------------------------------
;; Accessors

(defn leader
  "The id of the person `id` follows, or nil."
  [world id]
  (get-in world [:agents id :following]))

(defn followers
  "Ids of the active people following `id`, in spawn order."
  [world id]
  (keep #(when (= id (:following %)) (:id %)) (w/active-agents world)))

(defn- leads-to?
  "Does following on from `id` (their leader, their leader's leader, ...)
  reach `target`?"
  [world id target]
  (loop [x id seen #{}]
    (cond (nil? x) false
          (= x target) true
          (seen x) false
          :else (recur (leader world x) (conj seen x)))))

(defn- name-of [world id] (:name (w/agent world id) id))

;; ---------------------------------------------------------------------------
;; Events

(defn started
  "Event: `actor` starts following `who`. Everyone here sees it."
  [world actor who]
  {:type :agent/following :actor actor :visible-to (space/here world actor) :data {:leader who}})

(defn stopped
  "Event: `actor` stops following their leader, for `reason`: :chose
  (follow-stop), :own-way (they went somewhere themselves), :left-behind
  (they couldn't go with them) or :apart (they weren't with them when they
  went; only they know)."
  [world actor reason]
  {:type :agent/stopped-following :actor actor
   :visible-to (if (= :apart reason) #{actor} (space/here world actor))
   :data {:leader (leader world actor) :reason reason}})

(defmethod w/apply-event :agent/following [world {:keys [actor data]}]
  (assoc-in world [:agents actor :following] (:leader data)))

(defmethod w/apply-event :agent/stopped-following [world {:keys [actor]}]
  (update-in world [:agents actor] dissoc :following))

(defmethod w/perceived-by :agent/following [_] :sight)
(defmethod w/perceived-by :agent/stopped-following [_] :sight)

(defmethod narrate/describe :agent/following [{:keys [actor data]} names]
  (str (narrate/who names actor) " follows " (narrate/who names (:leader data)) ", to go where they go."))

(defmethod narrate/describe :agent/stopped-following [{:keys [actor data]} names]
  (let [them (narrate/who names (:leader data))]
    (str (narrate/who names actor)
         (case (:reason data)
           :left-behind (str " can't go with " them ", and stops following them.")
           :apart (str " is no longer with " them ", and stops following them.")
           (str " stops following " them ".")))))

;; ---------------------------------------------------------------------------
;; Going along

(defn- cant-go?
  "Can't `id` go with their leader on a journey of `travel` rounds (0 for a
  walk)?"
  [world id travel]
  (boolean (or (b/asleep? world id)
               (b/hibernating? world id)
               (space/in-transit? world id)
               (physical/movement-reason world id travel))))

(defn- going-with
  "Events for everyone following `mover` going with them on `move` (their
  :agent/moved or :agent/departed), and for their followers in turn.
  `seen` holds everyone already going. Returns [events seen]."
  [world mover move travel seen]
  (reduce (fn [[events seen] f]
            (cond
              (seen f) [events seen]
              (not= (get-in move [:data :from]) (space/location-of world f))
              [(conj events (stopped world f :apart)) seen]
              (cant-go? world f travel) [(conj events (stopped world f :left-behind)) seen]
              :else
              (let [own (assoc move :actor f :data (assoc (:data move) :with mover))
                    [more seen] (going-with world f own travel (conj seen f))]
                [(-> events
                     (cond-> (w/busy world f) (conj (b/free world f mover)))
                     (conj own)
                     (into more))
                 seen])))
          [[] seen]
          (followers world mover)))

(defn along
  "`events`, an action of `actor`'s that takes them `travel` rounds away (0
  for a walk), with what following does to it, all computed on `world`
  before the move: if `actor` was following someone, they stop first (they
  went their own way); then, after their :agent/moved or :agent/departed,
  everyone following them goes too, with :with naming whom they went with."
  [world actor travel events]
  (if-let [move (first (filter #(#{:agent/moved :agent/departed} (:type %)) events))]
    (let [move (assoc move :actor actor)
          [going _] (going-with world actor move travel #{actor})]
      (-> (if (leader world actor) [(stopped world actor :own-way)] [])
          (into events)
          (into going)))
    events))

;; ---------------------------------------------------------------------------
;; Actions

(defn follow-reason
  "Why `actor` can't follow `who`, or nil."
  [world actor {:keys [who]}]
  (let [a (w/agent world who)]
    (cond (= who actor) "you can't follow yourself"
          (not (and a (w/active? world who) (not= :gm (:role a)))) (str "no one has the id " who)
          (nil? (space/location-of world actor)) "you can't follow anyone while travelling"
          (not (space/together? world actor who)) (str (name-of world who) " is not here")
          (= who (leader world actor)) (str "you are already following " (name-of world who))
          (leads-to? world who actor) (str (name-of world who) " is following you, so you can't follow them")
          :else (physical/movement-reason world actor 0))))

(def actions
  [{:name :follow
    :kind :free :per-turn 1
    :doc (str "Follow someone here (use their id): from now on, whenever they walk or travel, you go with them at once, "
              "until you use follow-stop or go somewhere yourself. If you're busy when they go, you stop what you're "
              "doing to go with them; asleep, or unable to move, you're left behind. You can follow during a "
              "conversation too, when you're asked to come along.")
    :args [:map [:who :string]]
    :valid? follow-reason
    :effect (fn [world actor {:keys [who]}]
              (cond-> []
                (leader world actor) (conj (stopped world actor :chose))
                true (conj (started world actor who))))
    :reply (fn [world actor {:keys [who]}]
             (str "You are following " (name-of world who) ": you go wherever they go until you use follow-stop."))}

   {:name :follow-stop
    :kind :free :per-turn 1
    :doc "Stop following the person you follow: you stay where you are when they go."
    :args [:map]
    :available? (fn [world actor] (some? (leader world actor)))
    :valid? (fn [world actor _] (when-not (leader world actor) "you aren't following anyone"))
    :effect (fn [world actor _] [(stopped world actor :chose)])}])

;; ---------------------------------------------------------------------------
;; Views

(defn add-to-view
  "`view` with, under :you, whom `id` follows, if they're together
  (:following {:id :name}), and who here follows them (:followed-by [{:id :name}]), where there are any."
  [view world id]
  (let [person #(hash-map :id % :name (name-of world %))
        led (when (space/together? world id (leader world id)) (leader world id))
        by (seq (filter #(space/together? world id %) (followers world id)))]
    (cond-> view
      led (assoc-in [:you :following] (person led))
      by (assoc-in [:you :followed-by] (mapv person by)))))
