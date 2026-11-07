(ns manor.mechanics.appointments
  "Changing posts (docs/role-change-design.md). Someone whose position has
  the :appoints capability can `appoint` anyone to another post or
  `dismiss` them, at once and from anywhere, after the post check
  (manor.mechanics.appointments.post-check) has let it through, and can
  `assign-bedroom` without one. The GM's `change-post` does the same as the
  operator's `engine/change-post!`. Code checks only structure: who the
  people are and what the positions and rooms are. Every change is seen by
  the two people concerned, and nobody else."
  (:require [clojure.string :as str]
            [manor.mechanics.appointments.post-check :as post-check]
            [manor.mechanics.hiring :as hiring]
            [manor.mechanics.positions :as positions]
            [manor.mechanics.space :as space]
            [manor.mechanics.visits :as visits]
            [manor.prompts :as prompts]
            [manor.world :as w]))

(def text-arg [:string {:min 1 :max 1000}])

(defn- agent-name [world id] (:name (w/agent world id)))

(defn- title [world position]
  (str/lower-case (get-in world [:state :positions position :title] (name position))))

(defn- participant-reason
  "Why `who` isn't someone whose post or bed can be changed, or nil."
  [world who]
  (when-not (and (w/active? world who) (not= :gm (:role (w/agent world who))))
    (str "no active participant has the id " who)))

(def visitor-roles
  "The positions of people here on a visit (manor.mechanics.visits and
  trades), who hold no post to change: their visit decides when they go."
  #{:guest :tradesman})

(defn- visitor-reason
  "Why `who` is here on a visit or for a place rather than in a post, or nil:
  a guest or tradesperson, or a candidate who hasn't been engaged (hiring's
  `engage` takes them on)."
  [world who]
  (cond (visitor-roles (:role (w/agent world who)))
        (str (agent-name world who) " is a visitor here, not someone in a post")
        (= :here (:status (hiring/for-agent world who)))
        (str (agent-name world who) " is a candidate: use engage to offer them a place")))

(defn- other-person-reason
  "Why `actor` can't appoint or dismiss `who`, or nil."
  [world actor who]
  (or (participant-reason world who)
      (when (= actor who) "you cannot appoint or dismiss yourself")
      (visitor-reason world who)))

(defn appoint-reason [world actor {:keys [who position]}]
  (or (other-person-reason world actor who)
      (hiring/not-a-post-reason world position)
      (when (= (keyword position) (:role (w/agent world who)))
        (str (agent-name world who) " already holds that post"))))

(defn dismiss-reason [world actor {:keys [who]}]
  (or (other-person-reason world actor who)
      (when (#{:none nil} (:role (w/agent world who)))
        (str (agent-name world who) " holds no post to be dismissed from"))))

(defn- situation-replaced [id situation]
  {:type :agent/updated :visible-to :system :data {:id id :changes {:situation situation}}})

(defn appointed
  "`actor` puts `who` in the post of `position`, in the household of
  `who`'s present post (or the actor's, if they have none)."
  [world actor {:keys [who position terms]}]
  (let [position (keyword position)
        household (or (:household (positions/post world who)) (:household (positions/post world actor)))]
    [(positions/post-changed world who position {:household household :terms terms :employer actor
                                                 :visible-to #{actor who}})
     (situation-replaced who (prompts/render world "situations/appointed.md"
                                             {:by (agent-name world actor) :post (title world position)
                                              :terms (if terms (prompts/render world "situations/appointed-terms.md" {:terms terms}) "")}))]))

(defn dismissed
  "`actor` ends `who`'s post: no post, no household and no bedroom."
  [world actor {:keys [who]}]
  (let [audience #{actor who}]
    [(positions/post-changed world who :none {:household nil :employer actor :visible-to audience})
     (assoc (positions/bedroom-assigned who nil) :visible-to audience)
     (situation-replaced who (prompts/render world "situations/dismissed.md"
                                             {:by (agent-name world actor)
                                              :post (title world (:role (w/agent world who)))}))]))

(defn assign-bedroom-reason [world _actor {:keys [who bedroom]}]
  (or (participant-reason world who)
      (when-not (or (positions/household-member? world who) (visits/from-elsewhere? world who))
        (str (agent-name world who) " is neither of the household nor a guest staying with it"))
      (when-not (space/home-location? world bedroom)
        "bedroom must name an existing location in the household's home area; any room there may be assigned")
      (when (= bedroom (get-in world [:state :bedrooms who]))
        (str (agent-name world who) " already sleeps there"))))

(defn bedroom-given [_world actor {:keys [who bedroom]}]
  [(assoc (positions/bedroom-assigned who bedroom actor) :visible-to (conj #{} actor who))])

(defn change-post-reason [world _ {:keys [who position]}]
  (or (participant-reason world who)
      (when-not (get-in world [:state :positions (keyword position)])
        (str "there is no position " position))
      (when (= :gm (keyword position)) "nobody can be made the GM")))

(defn post-changed-by-gm
  "The GM's `change-post`: as the operator's `engine/change-post!`, seen by
  `who` alone. An empty `household` means none."
  [world _ {:keys [who position household terms]}]
  [(positions/post-changed world who (keyword position)
                           {:household (cond (nil? household) (:household (positions/post world who))
                                             (str/blank? household) nil
                                             :else household)
                            :terms terms :visible-to #{who}})])

(def actions
  [{:name :appoint :kind :final :roles :all :needs :appoints
    :doc (prompts/doc "docs/appointments/appoint.md")
    :args [:map [:who :string] [:position :string] [:terms {:optional true} text-arg] [:reason {:optional true} text-arg]]
    :valid? appoint-reason
    :admission :people/post-check
    :effect appointed}
   {:name :dismiss :kind :final :roles :all :needs :appoints
    :doc (prompts/doc "docs/appointments/dismiss.md")
    :args [:map [:who :string] [:reason {:optional true} text-arg]]
    :valid? dismiss-reason
    :admission :people/post-check
    :effect dismissed}
   {:name :assign-bedroom :kind :final :roles :all :needs :appoints
    :doc (prompts/doc "docs/appointments/assign-bedroom.md")
    :args [:map [:who :string] [:bedroom :string]]
    :valid? assign-bedroom-reason
    :effect bedroom-given}
   {:name :change-post :kind :final :roles #{:gm}
    :doc (prompts/doc "docs/gm/change-post.md")
    :args [:map [:who :string] [:position :string] [:household {:optional true} :string] [:terms {:optional true} text-arg]]
    :valid? change-post-reason
    :effect post-changed-by-gm}])

(def adjudicators post-check/adjudicators)
