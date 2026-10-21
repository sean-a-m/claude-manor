(ns manor.scenarios.claudeworth.building
  "Claudeworth's household money, plans and construction. All changes are events."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [manor.clock :as clock]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.positions :as positions]
            [manor.mechanics.space :as space]
            [manor.narrate :as n]
            [manor.scenarios.claudeworth.money :as money]
            [manor.world :as w])
  (:import (java.time LocalTime)))

(def fee-percent 10)
(def query-record-limit 600)
(def prices (edn/read-string (slurp "mcp-servers/architect/prices.edn")))
(def sizes (mapv :size (:sizes prices)))

(defn open-now? [world [open close]]
  (let [t (.toLocalTime (clock/time-of (:clock world) (:round world)))]
    (and (not (.isBefore t (LocalTime/parse open))) (.isBefore t (LocalTime/parse close)))))

(defn- loc-name [world id] (:name (space/location world id) id))
(defn- here [world actor] (space/location world (space/location-of world actor)))
(defn- funds [world] (get-in world [:state :household :funds]))
(defn- work-in-progress [world] (first (get-in world [:state :works])))

(defn- floor-key
  "The house floor as a keyword, tolerating \"Ground floor\" as well as \"ground\"."
  [floor]
  (-> (if (keyword? floor) (name floor) (str floor))
      str/lower-case str/trim (str/replace #"\s*floor$" "") keyword))

(defn- plan [world id] (get-in world [:state :plans id]))
(defn- house [world] (filter #(= :house (:domain %)) (vals (get-in world [:space :locations]))))
(defn- floors [world] (set (remove #{nil :outside} (map :floor (house world)))))
(defn taken [world]
  (set (concat (keys (get-in world [:state :plans])) (keys (get-in world [:space :locations]))
               (map :room (get-in world [:state :works])))))
(defn fresh-id [base taken]
  (first (remove (set taken) (cons base (map #(str base "-" %) (iterate inc 2))))))

(defn architect-here [world actor]
  (first (filter #(and (positions/capable? world (:id %) :architect) (space/together? world actor (:id %)))
                 (w/active-agents world))))

(defn- closed [world loc]
  (when-not (and (:hours loc) (open-now? world (:hours loc)))
    (str "the " (:name loc) " is closed; it is open " (str/join "-" (:hours loc)))))

(defn office-reason [world actor]
  (let [loc (here world actor)]
    (if (contains? (:services loc) :architect)
      (closed world loc)
      (str "go to an architect's office first: "
           (str/join ", " (for [l (vals (get-in world [:space :locations]))
                                :when (contains? (:services l) :architect)]
                            (str (:name l) " (" (:id l) ")")))))))

;; Pure reducers. Monetary state is always integer old pence.
(defmethod w/apply-event :claudeworth/consulted [world _] world)
(defmethod w/apply-event :claudeworth/prices-quoted [world _] world)
(defmethod w/apply-event :claudeworth/draft-refused [world _] world)
(defmethod w/apply-event :claudeworth/plans-drawn [world {:keys [data]}]
  (assoc-in world [:state :plans (get-in data [:plan :id])] (:plan data)))
(defmethod w/apply-event :claudeworth/plans-shown [world {:keys [data]}]
  (update-in world [:state :plans (:plan-id data) :holders] into (:to data)))
(defmethod w/apply-event :claudeworth/commissioned [world {:keys [data]}]
  (cond-> (-> world (update-in [:state :household :funds] - (:price data))
              (update-in [:state :works] conj (select-keys data [:plan-id :room :name :due :connects-to :by])))
    (:payee data) (update-in [:state :purses (:payee data)] (fnil + 0) (:fee data))))
(defmethod w/apply-event :claudeworth/works-completed [world {:keys [data]}]
  (-> world (update-in [:state :works] #(vec (remove (fn [work] (= (:room data) (:room work))) %)))
      (assoc-in [:state :plans (:plan-id data) :built?] true)))

(defn- clip [s] (subs s 0 (min query-record-limit (count s))))
(defn- beside [names ids] (str/join ", " (map #(n/place names %) ids)))
(defmethod n/describe :claudeworth/consulted [{:keys [actor data]} names]
  (str (n/who names actor) " consults the reference books about " (:query data) ": "
       (clip (str/join "; " (map #(str (:title %) ": " (:text %)) (:matches data))))))
(defmethod n/describe :claudeworth/prices-quoted [{:keys [actor data]} names]
  (str (n/who names actor) " receives the price list (prices in pounds): " (clip (pr-str (:sizes data)))))
(defmethod n/describe :claudeworth/draft-refused [{:keys [data]} _]
  (str "The draft could not be stored: " (:reason data)))
(defmethod n/describe :claudeworth/plans-drawn [{:keys [actor data]} names]
  (str (n/who names actor) " has plans drawn for " (get-in data [:plan :name])
       " (" (get-in data [:plan :id]) "), " (money/display (get-in data [:plan :price])) "; "
       (let [holders (sort (get-in data [:plan :holders]))]
         (str (str/join " and " (map #(n/who names %) holders))
              (if (next holders) " now hold them." " now holds them.")))))
(defmethod n/describe :claudeworth/plans-shown [{:keys [actor data]} names]
  (str (n/who names actor) " spreads out the plans for " (:plan-id data) " for the room to see."))
(defmethod n/describe :claudeworth/commissioned [{:keys [data]} names]
  (str (n/who names (:by data)) " commissions the " (:name data) ", beside "
       (beside names (:connects-to data)) "; completion " (n/at names (:due data)) "."))
(defmethod n/describe :claudeworth/works-completed [{:keys [data]} names]
  (str "Builders finish the " (:name data) ", beside " (beside names (:connects-to data)) "."))

;; The offline bridge duplicates the small, deterministic server contract, not its I/O.
(defn- tidy [s] (str/trim (str/replace (str s) #"\s+" " ")))
(defn draft-stub [{:keys [house taken request]} _]
  (let [{:keys [name floor desc connects-to size]} request
        name (tidy name)
        quote (first (filter #(= size (:size %)) (:sizes prices)))
        rooms (set (map :id house))
        base (-> name str/lower-case (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-|-$" ""))]
    (when (str/blank? name) (throw (ex-info "give the room a non-empty name" {})))
    (when-not quote (throw (ex-info (str "size must be one of: " (str/join ", " sizes)) {})))
    (when-let [bad (first (remove rooms connects-to))]
      (throw (ex-info (str bad " is not in the house; use: " (str/join ", " (sort rooms))) {})))
    {:plan {:id (fresh-id (if (str/blank? base) "room" base) taken) :name name
            :floor (tidy (if (keyword? floor) (clojure.core/name floor) floor))
            :desc (tidy desc) :connects-to (vec connects-to)}
     :price (:price quote) :build-days (:build-days quote)}))

(def bridges
  {:architect/references {:doc "Search the architect's reference books"
                          :mcp {:server :architect :tool "consult_references"} :timeout-ms 20000 :max-chars 4000}
   :architect/prices {:doc "The firm's price list for new rooms"
                      :mcp {:server :architect :tool "price_list"} :timeout-ms 20000 :max-chars 2000}
   :architect/draft {:doc "Draft a room and price it"
                     :mcp {:server :architect :tool "draft_plans"} :timeout-ms 20000 :max-chars 6000}})
(def mcp-servers {:architect {:command ["bb" "-m" "architect.server"] :cwd "mcp-servers/architect"}})
(def offline-stubs
  {:architect/references (fn [_ _] {:matches [{:source "offline" :title "Library"
                                              :text "A panelled library beside the study, with shelves and a quiet reading table."}]})
   :architect/prices (fn [_ _] prices)
   :architect/draft draft-stub})

(defn draw-reason [world actor args]
  (or (office-reason world actor)
      (let [architect (architect-here world actor) client (get args :for actor)]
        (cond
          (positions/capable? world actor :architect)
          (when-not (and (w/active? world client) (space/together? world actor client))
            (str "the client " client " must be present in this office"))
          architect (str (:name architect) " draws the plans here; ask him")
          (not= client actor) "the clerk can draw only for you; omit for or use your own id"))
      (when-not (contains? (floors world) (floor-key (:floor args)))
        (str "floor must be one of: " (str/join ", " (sort (map name (floors world))))))
      (when-let [bad (first (remove (set (map :id (house world))) (:connects-to args)))]
        (str bad " is not a room of the house; use: " (str/join ", " (sort (map :id (house world))))))))

(defn draft-args [world _ args]
  {:house (mapv (fn [l] {:id (:id l) :name (:name l) :floor (:floor l)
                         :connects-to (mapv :to (filter #(zero? (:travel %)) (space/exits world (:id l))))})
                (sort-by :id (house world)))
   :taken (vec (sort (taken world)))
   :request (-> (select-keys args [:name :floor :desc :connects-to :size]) (update :floor floor-key))})

(defn- short-text? [x max-length]
  (and (string? x) (not (str/blank? x)) (<= (count x) max-length)))

(defn draft-reason
  "Validate the entire untrusted result, including money and delay, before storing anything."
  [world args result]
  (let [p (:plan (when (map? result) result))
        quote (first (filter #(= (:size args) (:size %)) (:sizes prices)))]
    (cond
      (not (map? p)) "expected a structured plan; ask for a fresh draft"
      (not (and (string? (:id p)) (re-matches #"[a-z0-9]+(?:-[a-z0-9]+)*" (:id p))
                (not (contains? (taken world) (:id p))))) "the draft needs a fresh kebab-case id"
      (not (short-text? (:name p) 60)) "the draft name must be 1–60 characters"
      (not (short-text? (:desc p) 600)) "the draft description must be 1–600 characters"
      (not (and (or (string? (:floor p)) (keyword? (:floor p)))
                (contains? (floors world) (floor-key (:floor p))))) "the draft floor must be a house floor"
      (not (and (vector? (:connects-to p)) (<= 1 (count (:connects-to p)) 4)
                (every? (set (map :id (house world))) (:connects-to p)))) "the draft must join 1–4 existing house rooms"
      (not quote) "the draft needs a size from the price list"
      (not (and (integer? (:price result)) (pos? (:price result))
                (integer? (:build-days result)) (pos? (:build-days result)))) "the draft needs positive integer pounds and build days")))

(defn store-draft [world actor args result]
  (if-let [reason (draft-reason world args result)]
    [{:type :claudeworth/draft-refused :visible-to #{actor} :data {:reason reason}}]
    (let [client (get args :for actor)
          p (merge (select-keys (:plan result) [:id :name :floor :desc :connects-to])
                   {:price (money/pounds->pence (:price result)) :build-days (:build-days result)}
                   {:size (:size args) :drawn-by actor :for client :holders (set [actor client])})
          p (update p :floor floor-key)]
      [{:type :claudeworth/plans-drawn :visible-to (set [actor client]) :data {:plan p}}])))

(defn- held-reason [world actor id]
  (when-not (contains? (:holders (plan world id)) actor) "you have not seen those plans; ask to have them shown to you"))

(def actions
  [{:name :consult-references :kind :query :roles :all
    :doc "Search reference books at an open architect's office. The result is private and remembered next turn."
    :args [:map [:query [:string {:min 2 :max 200}]]]
    :valid? (fn [world actor _] (office-reason world actor))
    :bridge :architect/references :bridge-args (fn [_ _ args] (select-keys args [:query]))
    :on-result (fn [_ actor args result]
                 [{:type :claudeworth/consulted :visible-to #{actor} :data (assoc result :query (:query args))}])}
   {:name :price-list :kind :query :roles :all
    :doc "Ask for the price list at an open architect's office. All prices in this bridge result are whole pounds (£), not pence. The result is private and remembered next turn."
    :args [:map] :valid? (fn [world actor _] (office-reason world actor))
    :bridge :architect/prices :bridge-args (fn [_ _ _] {})
    :on-result (fn [_ actor result-args result]
                 [{:type :claudeworth/prices-quoted :visible-to #{actor} :data result}])}
   {:name :draw-plans :kind :query :roles :all
    :doc "Draft and price a room at an open architect's office. If an architect is here, only that agent can draw, naming a client with for; ask him with say. With no architect here, the clerk draws for you. Use house room ids from places for connects-to. Bridge prices are whole pounds (£); stored plans show formatted sterling. Plans are stored, not commissioned."
    :args [:map [:for {:optional true} :string] [:name [:string {:min 1 :max 60}]] [:floor :string]
           [:desc [:string {:min 1 :max 600}]] [:connects-to [:vector {:min 1 :max 4} :string]]
           [:size (into [:enum] sizes)]]
    :valid? draw-reason :bridge :architect/draft :bridge-args draft-args :on-result store-draft}
   {:name :show-plans :kind :free :roles :all
    :doc "Spread out a plan you hold for everyone here to see, anywhere. Use a plan id from plans."
    :args [:map [:plan-id :string]]
    :valid? (fn [world actor {:keys [plan-id]}]
              (or (held-reason world actor plan-id)
                  (when (empty? (disj (space/here world actor) actor)) "someone else must be here to see the plans")))
    :effect (fn [world actor {:keys [plan-id]}]
              [{:type :claudeworth/plans-shown :visible-to (space/here world actor)
                :data {:plan-id plan-id :to (vec (sort (space/here world actor)))}}])}
   {:name :commission :kind :final :roles :all
    :doc "Commission a plan you hold at an open architect's office, paid from household funds. Only one room is built at a time: while any room is being built, a commission is refused until it is finished (see building for the completion time)."
    :args [:map [:plan-id :string]]
    :valid? (fn [world actor {:keys [plan-id]}]
              (or (office-reason world actor) (held-reason world actor plan-id)
                  (when-let [work (work-in-progress world)]
                    (str "the " (:name work) " is already being built; it will be finished "
                         (clock/short-label (:clock world) (:due work) (:round world))
                         ". Only one room is built at a time: commission the "
                         (:name (plan world plan-id)) " then."))
                  (when (> (:price (plan world plan-id)) (funds world))
                    (str "the " (:name (plan world plan-id)) " would cost " (money/display (:price (plan world plan-id)))
                         "; the household has " (money/display (funds world))))))
    :effect (fn [world actor {:keys [plan-id]}]
              (let [p (plan world plan-id) price (:price p)
                    delay (or (get-in world [:state :build-rounds]) (* (clock/rounds-per-day (:clock world)) (:build-days p)))
                    payee (when (positions/capable? world (:drawn-by p) :architect) (:drawn-by p))
                    room (fresh-id plan-id (concat (keys (get-in world [:space :locations]))
                                                    (map :room (get-in world [:state :works]))))]
                [{:type :claudeworth/commissioned :visible-to :all
                  :data {:plan-id plan-id :room room :name (:name p) :by actor :price price
                         :due (+ (:round world) delay) :fee (if payee (quot (* price fee-percent) 100) 0)
                         :payee payee :connects-to (:connects-to p)}}]))}])

(def construction
  {:name :construction
   :tick (fn [world _]
           (mapcat (fn [{:keys [plan-id room name due connects-to]}]
                     (when (<= due (:round world))
                       (let [p (plan world plan-id)]
                         (concat
                          [{:type :claudeworth/works-completed :visible-to :all
                            :data {:plan-id plan-id :room room :name name :connects-to connects-to}}
                           {:type :space/location-added
                            :data {:location {:id room :name name :desc (:desc p) :domain :house
                                              :floor (:floor p) :kind :room
                                              :aspects {:cleanliness "new, smelling of plaster and paint"}}}}]
                          (for [to connects-to]
                            {:type :space/route-opened :data {:from room :to to :travel 0 :one-way? false}})))))
                   (get-in world [:state :works])))})

(defn observe [world id]
  (let [works (get-in world [:state :works])]
    (cond-> {:household (update (get-in world [:state :household]) :funds money/display)}
      (positions/capable? world id :architect) (assoc :you {:purse (money/display (get-in world [:state :purses id] 0))})
      (seq works) (assoc :building (mapv (fn [work] {:name (:name work)
                                                    :beside (mapv #(loc-name world %) (:connects-to work))
                                                    :finish (clock/short-label (:clock world) (:due work) (:round world))}) works))
      true (assoc :plans (vec (for [p (sort-by :id (vals (get-in world [:state :plans])))
                                   :when (contains? (:holders p) id)]
                               (assoc (select-keys p [:id :name :size :floor :connects-to])
                                      :price (money/display (:price p))
                                      :status (cond (some #(= (:id p) (:plan-id %)) works) "being built"
                                                    (:built? p) "built" :else "drawn"))))))))

(defn initial-state
  "Money, plans and works at the start. `positions` say who is an architect,
  with a purse for their fees."
  [wf agents positions opts]
  {:money-unit :old-pence
   :household {:funds (money/pounds->pence (get-in wf [:meta :starting-funds]))}
   :purses (into {} (for [a agents :when (contains? (get-in positions [(:role a) :capabilities]) :architect)]
                      [(b/slug (:name a)) 0]))
   :plans {} :works [] :build-rounds (:build-rounds opts)})

(defn problems [{:keys [locations meta]}]
  (concat
   (when-not (and (integer? (:starting-funds meta)) (<= 0 (:starting-funds meta)))
     [":meta :starting-funds must be non-negative integer pounds"])
   (for [loc locations :when (and (contains? loc :services)
                                  (not (and (set? (:services loc)) (every? keyword? (:services loc)))))]
     (str "location " (:id loc) ": :services must be a set of keywords"))
   (when (and (some #(and (set? (:services %)) (contains? (:services %) :architect)) locations)
              (not (some #(and (set? (:services %)) (contains? (:services %) :architect) (:hours %)) locations)))
     ["at least one architect service needs :hours"])))
