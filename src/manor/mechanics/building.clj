(ns manor.mechanics.building
  "The household's money, plans and construction (the architect). All changes are events."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [manor.clock :as clock]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.positions :as positions]
            [manor.mechanics.space :as space]
            [manor.narrate :as n]
            [manor.prompts :as prompts]
            [manor.settings :as settings]
            [manor.mechanics.money :as money]
            [manor.world :as w])
  (:import (java.time LocalTime)))

(def fee-percent 10)
(def query-record-limit 600)

(defn read-price-list
  "An architect's price list, {:sizes [{:size :price :build-days}]} with
  prices in the pack's authored unit: a file path, or a classpath resource such as
  \"architect/prices-1904.edn\". A world file names it in :meta :architect-prices."
  [source]
  (let [f (io/file (str source))
        r (when-not (.exists f) (io/resource (str source)))]
    (when-not (or (.exists f) r)
      (throw (ex-info (str "no architect's price list " source " (looked for a file and a classpath resource)")
                      {:source source})))
    (edn/read-string (slurp (or r f)))))

(defn- price-list [world] (get-in world [:state :architect-prices]))
(defn- sizes [world] (mapv :size (:sizes (price-list world))))
(defn- price-for [price-sizes size] (first (filter #(= size (:size %)) price-sizes)))

(defn open-now? [world [open close]]
  (let [t (.toLocalTime (clock/time-of (:clock world) (:round world)))]
    (and (not (.isBefore t (LocalTime/parse open))) (.isBefore t (LocalTime/parse close)))))

(defn- loc-name [world id] (:name (space/location world id) id))
(defn- here [world actor] (space/location world (space/location-of world actor)))
(defn- work-in-progress [world] (first (get-in world [:state :works])))

(defn- floor-key
  "The house floor as a keyword, tolerating \"Ground floor\" as well as \"ground\"."
  [floor]
  (-> (if (keyword? floor) (name floor) (str floor))
      str/lower-case str/trim (str/replace #"\s*floor$" "") keyword))

(defn- plan [world id] (get-in world [:state :plans id]))
(defn- house
  "The rooms of the home area (manor.mechanics.space/home-domain), which plans build onto."
  [world]
  (let [home (space/home-domain world)]
    (filter #(= home (:domain %)) (vals (get-in world [:space :locations])))))
(defn- floors [world] (set (remove #{nil :outside} (map :floor (house world)))))
(defn taken [world]
  (set (concat (keys (get-in world [:state :plans])) (keys (get-in world [:space :locations]))
               (map :room (get-in world [:state :works])))))
(defn fresh-id [base taken]
  (first (remove (set taken) (cons base (map #(str base "-" %) (iterate inc 2))))))

(defn- closed [world loc]
  (when-not (and (:hours loc) (open-now? world (:hours loc)))
    (str "the " (:name loc) " is closed; it is open " (str/join "-" (:hours loc)))))

(defn office?
  "Is there an architect's office (a location with the :architect service)?
  Without one, the architect's actions aren't offered."
  [world]
  (some #(contains? (:services %) :architect) (vals (get-in world [:space :locations]))))

(defn office-reason [world actor]
  (let [loc (here world actor)]
    (if (contains? (:services loc) :architect)
      (closed world loc)
      (str "go to an architect's office first: "
           (str/join ", " (for [l (vals (get-in world [:space :locations]))
                                :when (contains? (:services l) :architect)]
                            (str (:name l) " (" (:id l) ")")))))))

;; Pure reducers. Monetary state is always integer base units.
(defmethod w/apply-event :building/consulted [world _] world)
(defmethod w/apply-event :building/prices-quoted [world _] world)
(defmethod w/apply-event :building/draft-refused [world _] world)
(defmethod w/apply-event :building/plans-drawn [world {:keys [data]}]
  (assoc-in world [:state :plans (get-in data [:plan :id])] (:plan data)))
(defmethod w/apply-event :building/plans-shown [world {:keys [data]}]
  (update-in world [:state :plans (:plan-id data) :holders] into (:to data)))
(defmethod w/apply-event :building/commissioned [world {:keys [data]}]
  (cond-> (-> world (update-in [:state :accounts (:account data)] - (:price data))
              (update-in [:state :works] conj (select-keys data [:plan-id :room :name :domain :due :connects-to :by])))
    (:payee data) (update-in [:state :accounts (:payee data)] (fnil + 0) (:fee data))))
(defmethod w/apply-event :building/works-completed [world {:keys [data]}]
  (-> world (update-in [:state :works] #(vec (remove (fn [work] (= (:room data) (:room work))) %)))
      (assoc-in [:state :plans (:plan-id data) :built?] true)))

(defn- clip [s] (subs s 0 (min query-record-limit (count s))))
(defn- beside [names ids] (str/join ", " (map #(n/place names %) ids)))
(defmethod n/describe :building/consulted [{:keys [actor data]} names]
  (str (n/who names actor) " consults the reference books about " (:query data) ": "
       (clip (str/join "; " (map #(str (:title %) ": " (:text %)) (:matches data))))))
(defmethod n/describe :building/prices-quoted [{:keys [actor data]} names]
  (str (n/who names actor) " receives the price list (prices in " (:authored-name (get names [:money])) "): " (clip (pr-str (:sizes data)))))
(defmethod n/describe :building/draft-refused [{:keys [data]} _]
  (str "The draft could not be stored: " (:reason data)))
(defmethod n/describe :building/plans-drawn [{:keys [actor data]} names]
  (str (n/who names actor) " has plans drawn for " (get-in data [:plan :name])
       " (" (get-in data [:plan :id]) "), " (money/display (get names [:money]) (get-in data [:plan :price])) "; "
       (let [holders (sort (get-in data [:plan :holders]))]
         (str (str/join " and " (map #(n/who names %) holders))
              (if (next holders) " now hold them." " now holds them.")))))
(defmethod n/describe :building/plans-shown [{:keys [actor data]} names]
  (str (n/who names actor) " spreads out the plans for " (:plan-id data) " for the room to see."))
(defmethod n/describe :building/commissioned [{:keys [data]} names]
  (str (n/who names (:by data)) " commissions the " (:name data) ", beside "
       (beside names (:connects-to data)) "; completion " (n/at names (:due data)) "."))
(defmethod n/describe :building/works-completed [{:keys [data]} names]
  (str "Builders finish the " (:name data) ", beside " (beside names (:connects-to data)) "."))

;; The offline bridge duplicates the small, deterministic server contract, not its I/O.
(defn- tidy [s] (str/trim (str/replace (str s) #"\s+" " ")))
(defn draft-stub [{:keys [house taken request prices]} _]
  (let [{:keys [name floor desc connects-to size]} request
        name (tidy name)
        quote (price-for prices size)
        rooms (set (map :id house))
        base (-> name str/lower-case (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-|-$" ""))]
    (when (str/blank? name) (throw (ex-info "give the room a non-empty name" {})))
    (when-not quote (throw (ex-info (str "size must be one of: " (str/join ", " (map :size prices))) {})))
    (when-let [bad (first (remove rooms connects-to))]
      (throw (ex-info (str bad " is not in the house; use: " (str/join ", " (sort rooms))) {})))
    {:plan {:id (fresh-id (if (str/blank? base) "room" base) taken) :name name
            :floor (tidy (if (keyword? floor) (clojure.core/name floor) floor))
            :desc (tidy desc) :connects-to (vec connects-to)}
     :price (:price quote) :build-days (:build-days quote)}))

(def bridges
  {:architect/references {:doc "Search the architect's reference books"
                          :mcp {:server :architect :tool "consult_references"} :timeout-ms 20000 :max-chars 4000}
   :architect/draft {:doc "Draft a room and price it"
                     :mcp {:server :architect :tool "draft_plans"} :timeout-ms 20000 :max-chars 6000}})
(def mcp-servers {:architect {:command ["bb" "-m" "architect.server"] :cwd "mcp-servers/architect"}})
(def offline-stubs
  {:architect/references (fn [_ _] {:matches [{:source "offline" :title "Library"
                                              :text "A panelled library beside the study, with shelves and a quiet reading table."}]})
   :architect/draft draft-stub})

(defn draw-reason
  "Why the architect can't draw these plans now, or nil. Only an architect
  draws (the action `:needs :architect`), at an open office, for a client
  who is there with them."
  [world actor {client :for :as args}]
  (or (office-reason world actor)
      (when-not (positions/capable? world actor :architect) "only the architect draws plans")
      (when-not (and (not= client actor) (w/active? world client) (space/together? world actor client))
        (str "the client " client " must be present in this office"))
      (when-not (price-for (:sizes (price-list world)) (:size args))
        (str "size must be one of: " (str/join ", " (sizes world))))
      (when-not (contains? (floors world) (floor-key (:floor args)))
        (str "floor must be one of: " (str/join ", " (sort (map name (floors world))))))
      (when-let [bad (first (remove (set (map :id (house world))) (:connects-to args)))]
        (str bad " is not a room of the house; use: " (str/join ", " (sort (map :id (house world))))))))

(defn draft-args [world _ args]
  {:house (mapv (fn [l] {:id (:id l) :name (:name l) :floor (:floor l)
                         :connects-to (mapv :to (filter #(zero? (:travel %)) (space/exits world (:id l))))})
                (sort-by :id (house world)))
   :taken (vec (sort (taken world)))
   :prices (:sizes (price-list world))
   :request (-> (select-keys args [:name :floor :desc :connects-to :size]) (update :floor floor-key))})

(defn- short-text? [x max-length]
  (and (string? x) (not (str/blank? x)) (<= (count x) max-length)))

(defn draft-reason
  "Validate the entire untrusted result, including money and delay, before storing anything."
  [world args result]
  (let [p (:plan (when (map? result) result))
        quote (price-for (:sizes (price-list world)) (:size args))]
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
                (integer? (:build-days result)) (pos? (:build-days result)))) (str "the draft needs positive integer " (:authored-name (money/spec world)) " and build days"))))

(defn store-draft [world actor args result]
  (if-let [reason (draft-reason world args result)]
    [{:type :building/draft-refused :visible-to #{actor} :data {:reason reason}}]
    (let [client (:for args)
          p (merge (select-keys (:plan result) [:id :name :floor :desc :connects-to])
                   {:price (money/authored->units (money/spec world) (:price result)) :build-days (:build-days result)}
                   {:size (:size args) :drawn-by actor :for client :holders (set [actor client])})
          p (update p :floor floor-key)]
      [{:type :building/plans-drawn :visible-to (set [actor client]) :data {:plan p}}])))

(defn- held-reason [world actor id]
  (when-not (contains? (:holders (plan world id)) actor) "you have not seen those plans; ask to have them shown to you"))

;; The architect's actions, offered only where there's an office (`office?`).
(def actions
  [{:name :consult-references :kind :query :roles :all :available? (fn [world _] (office? world))
    :doc (prompts/doc "docs/architect/consult-references.md")
    :args [:map [:query [:string {:min 2 :max 200}]]]
    :valid? (fn [world actor _] (office-reason world actor))
    :bridge :architect/references :bridge-args (fn [_ _ args] (select-keys args [:query]))
    :on-result (fn [_ actor args result]
                 [{:type :building/consulted :visible-to #{actor} :data (assoc result :query (:query args))}])}
   {:name :price-list :kind :query :roles :all :available? (fn [world _] (office? world))
    :doc (prompts/doc "docs/architect/price-list.md")
    :args [:map] :valid? (fn [world actor _] (office-reason world actor))
    :query-fn (fn [world _ _ _] (price-list world))
    :on-result (fn [_ actor result-args result]
                 [{:type :building/prices-quoted :visible-to #{actor} :data result}])}
   {:name :draw-plans :kind :query :roles :all :needs :architect :available? (fn [world _] (office? world))
    :doc (prompts/doc "docs/architect/draw-plans.md")
    :args [:map [:for :string] [:name [:string {:min 1 :max 60}]] [:floor :string]
           [:desc [:string {:min 1 :max 600}]] [:connects-to [:vector {:min 1 :max 4} :string]]
           [:size [:string {:min 1 :max 40}]]]
    :valid? draw-reason :bridge :architect/draft :bridge-args draft-args :on-result store-draft}
   {:name :show-plans :kind :free :roles :all :available? (fn [world _] (office? world))
    :doc (prompts/doc "docs/architect/show-plans.md")
    :args [:map [:plan-id :string]]
    :valid? (fn [world actor {:keys [plan-id]}]
              (or (held-reason world actor plan-id)
                  (when (empty? (disj (space/here world actor) actor)) "someone else must be here to see the plans")))
    :effect (fn [world actor {:keys [plan-id]}]
              [{:type :building/plans-shown :visible-to (space/here world actor)
                :data {:plan-id plan-id :to (vec (sort (space/here world actor)))}}])}
   {:name :commission :kind :final :roles :all :available? (fn [world _] (office? world))
    :doc (prompts/doc "docs/architect/commission.md")
    :args [:map [:plan-id :string] [:from {:optional true} [:enum "household" "own"]]]
    :valid? (fn [world actor {:keys [plan-id from]}]
              (or (office-reason world actor) (held-reason world actor plan-id)
                  (when-let [work (work-in-progress world)]
                    (str "the " (:name work) " is already being built; it will be finished "
                         (clock/short-label (:clock world) (:due work) (:round world))
                         ". Only one room is built at a time: commission the "
                         (:name (plan world plan-id)) " then."))
                  (when-let [reason (money/spend-reason world actor from (:price (plan world plan-id)))]
                    (str "the " (:name (plan world plan-id)) " would cost " (money/display (money/spec world) (:price (plan world plan-id)))
                         "; " reason))))
    :effect (fn [world actor {:keys [plan-id from]}]
              (let [p (plan world plan-id) price (:price p)
                    delay (or (get-in world [:state :build-rounds]) (* (clock/rounds-per-day (:clock world)) (:build-days p)))
                    payee (when (positions/capable? world (:drawn-by p) :architect) (:drawn-by p))
                    room (fresh-id plan-id (concat (keys (get-in world [:space :locations]))
                                                    (map :room (get-in world [:state :works]))))]
                [{:type :building/commissioned :visible-to :all
                  :data {:plan-id plan-id :room room :name (:name p) :domain (space/home-domain world) :by actor :price price
                         :account (money/account actor from)
                         :due (+ (:round world) delay) :fee (if payee (quot (* price fee-percent) 100) 0)
                         :payee payee :connects-to (:connects-to p)}}]))}])

(def construction
  {:name :construction
   :tick (fn [world _]
           (mapcat (fn [{:keys [plan-id room name domain due connects-to]}]
                     (when (<= due (:round world))
                       (let [p (plan world plan-id)]
                         (concat
                          [{:type :building/works-completed :visible-to :all
                            :data {:plan-id plan-id :room room :name name :domain domain :connects-to connects-to}}
                           {:type :space/location-added
                            :data {:location {:id room :name name :desc (:desc p) :domain domain
                                              :floor (:floor p) :kind :room
                                              :aspects {:cleanliness "new, smelling of plaster and paint"}}}}]
                          (for [to connects-to]
                            {:type :space/route-opened :data {:from room :to to :travel 0 :one-way? false}})))))
                   (get-in world [:state :works])))})

(defn observe [world id]
  (let [works (get-in world [:state :works])]
    (cond-> {:household {:funds (money/display (money/spec world) (get-in world [:state :accounts "household"]))}}
      (contains? (get-in world [:state :accounts]) id)
      (assoc :you {:purse (money/display (money/spec world) (get-in world [:state :accounts id]))})
      (seq works) (assoc :building (mapv (fn [work] {:name (:name work)
                                                    :beside (mapv #(loc-name world %) (:connects-to work))
                                                    :finish (clock/short-label (:clock world) (:due work) (:round world))}) works))
      true (assoc :plans (vec (for [p (sort-by :id (vals (get-in world [:state :plans])))
                                   :when (contains? (:holders p) id)]
                               (assoc (select-keys p [:id :name :size :floor :connects-to])
                                      :price (money/display (money/spec world) (:price p))
                                      :status (cond (some #(= (:id p) (:plan-id %)) works) "being built"
                                                    (:built? p) "built" :else "drawn"))))))))

(defn initial-state
  "Money, plans, works and the architect's price list at the start.
  `positions` say who is an architect; they have a zero account for fees
  unless the world gives them a starting :purse, in authored units."
  [wf agents positions opts]
  (let [spec (:money (settings/for-worldfile wf))]
    {:accounts (into {"household" (money/authored->units spec (get-in wf [:meta :starting-funds]))}
                     (for [a agents
                           :when (or (contains? a :purse)
                                     (contains? (get-in positions [(:role a) :capabilities]) :architect))]
                       [(b/slug (:name a)) (money/authored->units spec (:purse a 0))]))
     :architect-prices (some-> (get-in wf [:meta :architect-prices]) read-price-list)
     :plans {} :works [] :build-rounds (:build-rounds opts)}))

(defn- price-list-problems
  "What's wrong with the price list a world file names, or nil."
  [spec source]
  (let [{:keys [sizes] :as pl} (try (read-price-list source) (catch Exception e (ex-message e)))]
    (cond
      (string? pl) [pl]
      (not (and (vector? sizes) (seq sizes)
                (every? #(and (string? (:size %)) (not (str/blank? (:size %)))
                              (pos-int? (:price %)) (pos-int? (:build-days %))) sizes)
                (apply distinct? (map :size sizes))))
      [(str "price list " source " must be {:sizes [{:size \"small\" :price 200 :build-days 1} ...]}"
            " with distinct sizes and positive " (:authored-name spec) " and days")])))

(defn problems [{:keys [locations meta] :as wf}]
  (concat
   (for [a (:agents wf) :when (= "household" (b/slug (:name a)))]
     (str "agent " (pr-str (:name a)) ": household is reserved for the shared account; choose another name"))
   (when (some #(and (set? (:services %)) (contains? (:services %) :architect)) locations)
     (if-let [source (:architect-prices meta)]
       (price-list-problems (:money (settings/for-worldfile wf)) source)
       [":meta :architect-prices must name a price list, such as \"architect/prices-1904.edn\", when there is an architect's office"]))
   (when-not (and (integer? (:starting-funds meta)) (<= 0 (:starting-funds meta)))
     [(str ":meta :starting-funds must be non-negative integer " (:authored-name (:money (settings/for-worldfile wf))))])
   (for [loc locations :when (and (contains? loc :services)
                                  (not (and (set? (:services loc)) (every? keyword? (:services loc)))))]
     (str "location " (:id loc) ": :services must be a set of keywords"))
   (when (and (some #(and (set? (:services %)) (contains? (:services %) :architect)) locations)
              (not (some #(and (set? (:services %)) (contains? (:services %) :architect) (:hours %)) locations)))
     ["at least one architect service needs :hours"])))
