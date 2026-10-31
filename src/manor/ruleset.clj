(ns manor.ruleset
  "The shared ruleset of the household scenarios (Claudeworth, its medium
  household, Ashby Lodge): every mechanic, installed once. A scenario is
  its world file and its mock script:

    (ruleset/scenario {:name :claudeworth
                       :doc \"Claudeworth Manor, 1904: ...\"
                       :defaults {:world-file \"worlds/claudeworth.edn\"}
                       :mock-day \"worlds/claudeworth-day.edn\"})

  The world file holds the map, the clock, the agents with their personas
  and situations, the things there (:items, see manor.mechanics.items), the
  setting (:meta :setting), its setting pack, and the positions they hold
  (see manor.mechanics.positions). Briefs and tool docs say nothing a world
  file could make false. Areas are the locations' :domains. `walk` (free)
  goes anywhere in the current area; `travel` (a turn) goes to another area.
  `sleep` and `spend-time` occupy an agent for several rounds without turns;
  activities are judged when they end. `rouse` asks someone busy whether to
  stop. Run options: :leave-out leaves any of the world file's characters out
  of a run, by id; `find` works everywhere unless :find-everywhere? is false,
  which refuses it at shops and other places of trade
  (manor.ruleset.rules/find-allowed?). An agent with a :routine in the world
  file hibernates whenever nobody else is near (manor.ruleset.hibernation).

  Its parts, under ruleset/: places (areas and bedrooms), actions (walk,
  call, travel, sleep, rouse), views, hibernation, rules (the rules prompt,
  the judge brief and the briefs), init (the world file's checks, the
  starting state, holidays) and mock (the scripted mock policy)."
  (:require [manor.mechanics.building :as building]
            [manor.mechanics.deliveries :as deliveries]
            [manor.mechanics.find-person :as find-person]
            [manor.mechanics.following :as following]
            [manor.mechanics.hiring :as hiring]
            [manor.mechanics.people :as people]
            [manor.mechanics.positions :as positions]
            [manor.mechanics.shop :as shop]
            [manor.mechanics.visits :as visits]
            [manor.ruleset.actions :as actions]
            [manor.ruleset.hibernation :as hibernation]
            [manor.ruleset.init :as init]
            [manor.ruleset.mock :as mock]
            [manor.ruleset.rules :as rules]
            [manor.ruleset.views :as views]
            [manor.tools.scene-ruleset :as scene]))

(defn- hiring-action [action-name] (first (filter #(= action-name (:name %)) hiring/actions)))

(defn- to-a-member
  "Hiring's `action-name` for a line in a conversation, with the person its
  argument `k` names limited to the conversation's `members`: `refusal` otherwise."
  [action-name k refusal members]
  (update (hiring-action action-name) :valid?
          (fn [valid?]
            (fn [world actor args]
              (or (when-not (some #{(get args k)} members) refusal)
                  (valid? world actor args))))))

(defn speaking-actions
  "Tools for a line, before speaking: anyone can recommend a candidate to
  someone in the conversation, offer a candidate in it a place, or accept or
  decline a place offered; an open counter's clerk can check goods; the
  keeper of a shop that sends goods out can send goods up from it, open or
  not; a customer with
  quotes made for them at this shop can buy."
  [world speaker {:keys [members]}]
  (cond-> [(to-a-member :recommend :for "recommend to someone in this conversation" members)
           (to-a-member :engage :who "offer a place to someone in this conversation" members)
           (hiring-action :accept-place)
           (hiring-action :decline-place)]
    (not (shop/service-reason world speaker))
    (into (filter #(= :quote-goods (:name %)) shop/actions))
    (nil? (shop/sending-reason world speaker))
    (into (filter #(= :send-up (:name %)) shop/actions))
    (seq (shop/quotes-here world speaker))
    (into (filter #(= :buy (:name %)) shop/actions))))

(defn- speaking-actions-and-following
  "`speaking-actions`, after `follow` and `follow-stop`: anyone can start or
  stop following someone while they talk, so agreeing to come along takes
  them along."
  [world speaker conv]
  (into following/actions (speaking-actions world speaker conv)))

(def items
  "The items mechanic's settings: the nightly steward, `find`'s limits, the
  immediate `act-on`, the judge brief and the aspect keys judges may use."
  {:steward {:at "02:00" :idle-hours 4 :max-per-call 8}
   :find-allowed? rules/find-allowed?
   :act-on? true
   :judge-brief rules/judge-brief
   :aspect-keys {:cleanliness "how clean or dirty it is" :repair "damage, wear or needed repairs"
                 :state "its present condition, where no other key fits"
                 :amount "how much there is of a single thing"
                 :stock "a store's general level of supplies" :lacking "what a store has run out of"
                 :dress "what a person is wearing, where it differs from usual"
                 :hair "how a person's hair is done, where it differs from usual"
                 :grime "dirt or stains on a person, where they show"}})

(def inner
  "Inner state: the keys judges may change, and how many are open at once."
  {:keys {:mood "their spirits and emotions now"
          :body "how their body is, as only they know it"
          :mind "what they're preoccupied with, anticipating or dreading"}
   :open 2})

(defn- mock-policy
  "The scripted policy for `mock-day`: a map, or a classpath EDN resource."
  [mock-day]
  (let [day (if (string? mock-day) (mock/read-day mock-day) (or mock-day {}))]
    (fn [ctx] (mock/scripted-policy day ctx))))

(defn scenario
  "A full scenario on the ruleset: every action, judge, bridge and system,
  with hiring, deliveries and visits installed, in that order. `opts`:
  - :name, :doc, :defaults (its :world-file names the world)
  - :mock-day, a day for the scripted mock policy (a map, or a classpath EDN
    file such as \"worlds/claudeworth-day.edn\"); or :mock-policy, a policy
    of the scenario's own (manor.ruleset.mock/scripted-policy helps).
    Without either, everyone passes.
  - :mock-conversation, the mock's speaking turns (default: the initiator
    ends it, the others stay silent)
  - :calendar? false for no calendar: no holidays, and calendar entries in
    the world file are ignored
  Lodge has no calendar."
  [{:keys [name doc defaults mock-day mock-conversation calendar?]
    :or {calendar? true} :as opts}]
  (-> {:name name
       :doc doc
       :defaults defaults
       :scene scene/hooks
       :init (if calendar? (comp init/add-holidays init/init) init/init)
       :roles rules/roles
       :agent-brief rules/agent-brief
       :on-spawn positions/starting-events
       :actions (vec (concat actions/actions [find-person/action] following/actions building/actions
                             shop/actions hiring/actions))
       :adjudicators (merge shop/adjudicators hiring/adjudicators find-person/adjudicators)
       :bridges (merge building/bridges hiring/bridges)
       :offline-stubs (merge building/offline-stubs hiring/offline-stubs)
       :mcp-servers (merge building/mcp-servers people/mcp-servers)
       :systems [building/construction]
       :items items
       :inner inner
       :relations {:keep 20}
       :exclude-actions #{:move :work-on}
       :speaking-actions speaking-actions-and-following
       :hibernate hibernation/hibernate
       :reference views/reference
       :observe views/observe
       :mock-policy (or (:mock-policy opts) (mock-policy mock-day))}
      (cond-> calendar? (assoc :calendar {:lookahead-days 30})
              mock-conversation (assoc :mock-conversation mock-conversation))
      hiring/install
      deliveries/install
      visits/install))
