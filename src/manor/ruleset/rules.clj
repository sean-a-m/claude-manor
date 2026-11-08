(ns manor.ruleset.rules
  "Roles and briefs. Who's who and the setting come from the world file
  (:meta :setting and :roles, kept in :state at :init); the rules are
  templates under resources/prompts/rules/, with their numbers taken from
  the world and the actions. Only the GM has a role of its own: everyone
  else gets `agent-brief`, which reads their position from the world."
  (:require [clojure.string :as str]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.building :as building]
            [manor.mechanics.conversation :as conversation]
            [manor.mechanics.deliveries :as deliveries]
            [manor.mechanics.hiring :as hiring]
            [manor.mechanics.items :as items]
            [manor.mechanics.physical :as physical]
            [manor.mechanics.positions :as positions]
            [manor.mechanics.space :as space]
            [manor.mechanics.trades :as trades]
            [manor.mechanics.visits :as visits]
            [manor.prompts :as prompts]
            [manor.ruleset.places :as places]))

(defn- duration
  "\"half an hour\", \"an hour\", \"2 hours\" or \"45 minutes\"."
  [minutes]
  (cond (= 30 minutes) "half an hour"
        (= 60 minutes) "an hour"
        (zero? (mod minutes 60)) (str (quot minutes 60) " hours")
        :else (str minutes " minutes")))


(defn- find-everywhere?
  "Is `find` allowed at places of trade too? Yes unless :find-everywhere? was false."
  [world]
  (not (false? (get-in world [:state :find-everywhere?]))))

(defn find-allowed?
  "Why `find` is refused at `place`, or nil: only at places with services
  (shops, the public house, the trades), and only when :find-everywhere? is
  false, so that goods for sale are bought rather than found."
  [world _actor place]
  (when (and (not (find-everywhere? world)) (seq (:services (space/location world place))))
    "these aren't your things to search; ask for it here instead"))

(defn- section
  "A section of the rules, or a part of one chosen in code: the template
  rules/sections/`file`.md, filled from `params`."
  ([world file] (section world file {}))
  ([world file params] (prompts/render world (str "rules/sections/" file ".md") params)))

(defn- order-book
  "The shopping rules' paragraph on the order books (manor.mechanics.purchases),
  where the world has shops."
  [world]
  (if (some #(contains? (:services %) :general-store) (vals (get-in world [:space :locations])))
    (str "\n\n" (section world "shopping-order-book"))
    ""))

(defn- appointers?
  "Does any position in the run have the :appoints capability
  (manor.mechanics.appointments)?"
  [world]
  (boolean (some #(contains? (set (:capabilities %)) :appoints) (vals (get-in world [:state :positions])))))

(defn rules
  "How actions work, for every role: a section for each subject, so a model
  (and whoever reads the prompt) can find what applies. Each section is a
  template under resources/prompts/rules/sections/; this chooses which
  apply and passes their numbers."
  [world]
  (let [turn (duration (:minutes-per-round (:clock world)))
        max-queries (get-in world [:state :max-queries] 2)
        architect? (building/office? world)
        visits? (visits/config world)]
    (prompts/render world
     "rules/how-things-work.md"
     {:sections
      (str/join
       "\n\n"
       (remove nil?
               [(section world "turns" {:turn turn :max-queries max-queries
                                        :counted (section world (if architect? "turns-counted-architect" "turns-counted"))})
                (section world "getting-about")
                (section world "speaking" {:speech-limit b/speech-limit :max-others conversation/max-others
                                           :passes conversation/passes})
                (section world "sleep")
                (section world "work" {:max-work-hours items/max-work-hours})
                (section world "things" {:find-for-sale (section world (if (find-everywhere? world)
                                                                          "things-find-everywhere"
                                                                          "things-find-not-at-trade"))})
                (section world "works")
                (section world "people" {:physical (physical/rules world)})
                (section world "shopping" {:paid (section world (if architect? "shopping-paid-commissions" "shopping-paid"))
                                           :spending-tools (section world (if architect? "shopping-spending-tools-commissions" "shopping-spending-tools"))
                                           :money (prompts/render world "money.md" {})
                                           :order-book (order-book world)})
                (when architect?
                  (section world "building" {:sizes (str/join ", " (map :size (get-in world [:state :architect-prices :sizes])))
                                             :fee-percent building/fee-percent}))
                (when (appointers? world) (section world "posts"))
                (section world "hiring" {:hiring ((if visits? hiring/rules-beside-visits hiring/rules) world)})
                (when visits? (section world "invitations" {:visits (visits/rules world)}))
                (when visits? (trades/rules world))]))})))

(defn- setting [world] (get-in world [:state :setting]))

(defn judge-brief
  "The setting, and that everyday things are to hand."
  [world]
  (let [locations (vals (get-in world [:space :locations]))
        mechanics (cond-> []
                    (building/office? world)
                    (conj "judges/brief-architect.md")
                    (some deliveries/delivers? locations)
                    (conj "judges/brief-deliveries.md"))]
    (prompts/render world "judges/brief.md"
                    {:setting (or (setting world) "")
                     :mechanics (apply str (map #(str "\n\n" (prompts/render world % {})) mechanics))})))

(defn- about [world role] (get-in world [:state :positions role :about]))

(defn- position-line
  "\"Your position: Private secretary. You live in. You work at: Study
  (study).\" from the position's title, :sleeps, :workplace and whether
  it :appoints."
  [world {:keys [title sleeps workplace capabilities]}]
  (when title
    (str "Your position: " title "."
         (case sleeps :in-house " You live in." :out " You live out." nil)
         (when (contains? (set capabilities) :appoints)
           " You can appoint people to posts, dismiss them and assign their bedrooms.")
         (when (seq workplace)
           (str " You work at: " (str/join ", " (for [id workplace] (str (places/loc-name world id) " (" id ")"))) ".")))))

(defn agent-brief
  "What this agent's position is and its duties, the setting, and the
  rules. The position is read from the world, so a new one needs no code."
  [world agent-id]
  (let [p (positions/position world agent-id)]
    (str/join "\n\n" (remove str/blank? [(str/join "\n" (remove nil? [(position-line world p) (:about p)]))
                                         (setting world) (rules world)]))))

(defn gm-brief [world]
  (str/join "\n\n" (remove str/blank? [(setting world) (about world :gm)
                                       "This slice has no income credit action: scheduled expectations may be recorded in a calendar when available, but never narrate money arriving without an engine-recorded credit."
                                       "The engine keeps time and runs the world. Intervene only when asked, and lightly; otherwise call done."])))

(def roles
  "Only the GM has a role of its own: everyone else gets `agent-brief`, which
  reads their position from the world."
  {:gm {:brief gm-brief}})

