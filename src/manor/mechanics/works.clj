(ns manor.mechanics.works
  "Works (docs/fixtures-design.md): lasting changes to the things of a
  place, planned, given to someone and carried out. A work is an
  engine-owned record in [:state :work-orders], keyed by its id (\"work-N\").
  `plan-work`, a query, asks the works judge (manor.mechanics.works.judge)
  for a private plan, `install`, `move` or `remove`. `order-work` offers a
  plan someone knows to a worker with them at a price, paid from the
  household or the orderer's own purse, or takes it on oneself (paying the
  materials at once); the two haggle with `revise-work` until whoever
  didn't set the current price answers with `accept-work`, which pays the
  price and puts the labour (price less materials) in the worker's purse;
  the worker may `decline-work`; `cancel-work` drops a plan not yet
  accepted. Planner, orderer and worker
  see it under `works` in their views. The worker does it with `carry-out`:
  each phase an ordinary timed item job with a :work-order reference,
  which can be stopped and resumed. The scene judge's `work` entry on the
  last phase decides the outcome (:works/done or :works/failed), which
  applies the install, move or removal. Accepted work keeps its worker on
  task: a calendar entry, the early reminder, no hibernation. Money is kept in the setting's smallest unit; tools take
  prices as money text (\"£15 10s\"), read by `money/parse`. Works paid
  from the household's account are in its order book
  (manor.mechanics.purchases, :kind :work records)."
  (:require [clojure.string :as str]
            [manor.clock :as clock]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.calendar :as calendar]
            [manor.mechanics.items :as items]
            [manor.mechanics.money :as money]
            [manor.mechanics.payments :as payments]
            [manor.mechanics.purchases :as purchases]
            [manor.mechanics.space :as space]
            [manor.mechanics.works.judge :as judge]
            [manor.narrate :as n]
            [manor.prompts :as prompts]
            [manor.world :as w]))

;; Plans not yet given to anyone, per planner (decision 11).
(def max-open-plans 3)
(def kinds #{"install" "move" "remove"})

(defn work-orders [world] (get-in world [:state :work-orders] {}))
(defn work-order [world id] (get (work-orders world) id))

(defn- ordered [world] (sort-by :n (vals (work-orders world))))

(defn open-plans
  "`actor`'s plans that haven't yet been given to anyone."
  [world actor]
  (filter #(and (= actor (:planner %)) (= :planned (:status %))) (ordered world)))

(defn- next-n [world] (inc (get-in world [:state :work-order-count] 0)))

;; ---------------------------------------------------------------------------
;; The record

(defmethod w/apply-event :works/planned [world {:keys [data]}]
  (-> world
      (assoc-in [:state :work-orders (:id data)] data)
      (assoc-in [:state :work-order-count] (:n data))))

(defn- show-money
  "`n` in the setting's money, or as a plain number without one."
  [names n]
  (if-let [m (get names [:money])] (money/display m n) (str n)))

(defn- materials-text
  "The plan's materials and estimate, in the setting's money."
  [names {:keys [estimate materials]}]
  (let [show #(show-money names %)]
    (cond (zero? estimate) "Materials: none; it costs nothing."
          (zero? materials) (str "Materials: none. Estimate: " (show estimate) ".")
          :else (str "Materials: " (show materials) ". Estimate: " (show estimate) ", materials included."))))

(defn where-text
  "Where the work is done, so a plan made in the wrong place shows it: an
  install goes in the planner's place; a move takes the thing from there to
  `to`; a removal takes it from there."
  [{:keys [kind place to]} names]
  (case kind
    :install (str "It goes in the " (n/place names place) " (" place "), where it was planned.")
    :move (str "From the " (n/place names place) " (" place ") to the " (n/place names to) " (" to ").")
    :remove (str "From the " (n/place names place) " (" place ").")
    nil))

(defn- plan-text
  "What a plan says: the work and where, who should do it, its stages,
  what it makes or what becomes of the old thing, and its money."
  [{:keys [id summary who phases item fate] :as data} names]
  (str "Work plan " id ": " summary " " (where-text data names) " Who should do it: " who
       " " (count phases) (if (= 1 (count phases)) " stage, " " stages, ")
       (str/join "; " (map #(str (quot (:minutes %) 60) " hours: \"" (:doing %) "\"") phases)) ". "
       (when item (str "It makes: " (:name item) ", " (:desc item) " "))
       (when fate (str "The old thing: " fate ". "))
       (materials-text names data)))

(defn trade-hint
  "How to find the tradesperson a plan wants, or nil for household work. A
  hint only: the plan may still be given to anyone."
  [{:keys [id trade]}]
  (when trade
    (str " It wants a " trade ": to find one, ask someone who might know to recommend with wanted \"" trade
         "\", then send-for them with the contact's id and an about naming " id
         ", and give them the plan with order-work when they come.")))

(defn describe-plan
  "A plan as its knowers read it."
  [{:keys [id] :as data} names]
  (str (plan-text data names)
       (trade-hint data)
       " Use order-work with id \"" id "\" to give it to someone to carry out; it is offered at the estimate"
       " unless you give price, so if you've agreed another price with them, give that."
       (when (= :install (:kind data))
         (str " If this isn't where it should go, cancel-work it and plan it again in the right place."))))

(defmethod n/describe :works/planned [{:keys [data]} names]
  (describe-plan data names))

;; ---------------------------------------------------------------------------
;; plan-work

(defn target-reason
  "Why `target` can't be the target of a work by `actor`, or nil."
  [world actor target]
  (let [it (items/item world target)
        here (space/location-of world actor)]
    (cond (nil? it) (str "there is no thing " target "; give the id of a thing here")
          (:holder (items/outermost world it)) (str "the " (:name it) " is being carried; set it down first, or move it yourself")
          (not= here (items/place-of world it)) (str "the " (:name it) " isn't here; plan a work where the thing is")
          (:in it) (str "the " (:name it) " is inside something; plan the work on what holds it")
          (items/store? it) (str "the " (:name it) " stands for supplies and can't be worked on")
          (items/identity-bound? it) (str "the " (:name it) " can't be worked on now"))))

(defn plan-reason
  "Why `actor` can't plan this work, or nil."
  [world actor {:keys [kind target to request]}]
  (let [here (space/location-of world actor)]
    (cond (nil? here) "you must be somewhere to plan a work there"
          (not (kinds kind)) "kind must be install, move or remove"
          (str/blank? request) "say in request what you want done, in your words"
          (and (= "install" kind) target) "an install needs no target: it puts something new here"
          (and (not= "install" kind) (nil? target)) (str "a " kind " needs target, the id of a thing here")
          (and (= "move" kind) (nil? to)) "a move needs to, a place in the home to move it to"
          (and (not= "move" kind) to) "only a move takes to"
          (and to (not (space/home-location? world to))) (str to " is not a place in the home; to must be one")
          (and to (= to here)) "it is here already; to must be another place"
          target (target-reason world actor target)
          (>= (count (open-plans world actor)) max-open-plans)
          (str "you have " max-open-plans " plans not yet given to anyone; give them out or let them go first"))))

(defn planned
  "The event for a checked plan: the record, visible to the planner only."
  [world actor {:keys [kind request target to]} plan]
  (let [n (next-n world)]
    {:type :works/planned :visible-to #{actor}
     :data (merge {:id (str "work-" n) :n n :planner actor :kind (keyword kind)
                   :place (space/location-of world actor) :target target :to to
                   :request request :item nil :fate nil
                   :known-to #{actor} :status :planned}
                  (update plan :phases (fn [ps] (mapv #(assoc % :done 0) ps))))}))

(defn- plan-events
  "On the world after the query: the plan, if it's still allowed."
  [world actor args result]
  (when (and (:plan result) (nil? (plan-reason world actor args)))
    [(planned world actor args (:plan result))]))

(defn- plan-reply [world _ _ {:keys [refused events]}]
  (cond (seq events) (n/describe (first events) (n/names world))
        refused (str "No plan was made: " refused)
        :else "No plan was made: the work can't be planned now."))

(def plan-action
  {:name :plan-work
   :doc (prompts/doc "docs/works/plan-work.md" {:max-open-plans max-open-plans})
   :roles :all
   :kind :query
   :args [:map
          [:kind [:enum "install" "move" "remove"]]
          [:request [:string {:min 1 :max judge/max-request}]]
          [:target {:optional true} :string]
          [:to {:optional true} :string]]
   :valid? plan-reason
   :adjudicate :works/plan
   :on-result plan-events
   :reply plan-reply})

;; ---------------------------------------------------------------------------
;; Offering: order-work, accept-work, decline-work, cancel-work

(defn- show-money-in [world n] (show-money (n/names world) n))

(defn- known-reason
  "Why `actor` can't name work `id`, or nil. A plan they don't know of is
  as unknown as one that doesn't exist."
  [world actor id]
  (when-not (contains? (:known-to (work-order world id)) actor)
    (str "you know of no work " id "; use the id of a plan under works in your view")))

(defn- status-reason
  "Why work `id` isn't open to `wanted` status, or nil."
  [world id wanted what]
  (let [{:keys [status worker]} (work-order world id)]
    (when (not= wanted status)
      (str "work " id " is " (name status)
           (when (and worker (#{:offered :accepted} status)) (str " to " (n/who (n/names world) worker)))
           "; only a " (name wanted) " work can be " what))))

(defn- worker-reason
  "Why `worker` can't be offered work by `actor`, or nil: they must be a
  living participant here, or the actor. Never their fitness (decision 5)."
  [world actor worker]
  (when-not (or (= worker actor)
                (and (w/active? world worker) (not= :gm (:role (w/agent world worker)))
                     (space/together? world actor worker)))
    (str "no one with id " worker " is here with you; offer work to someone present, or to yourself")))

(defn price-of
  "A price as a tool takes it, money text in the setting's money (\"£15 10s\"),
  in the smallest unit; nil if it isn't money."
  [world price]
  (money/parse (money/spec world) price))

(defn- price-reason
  "Why `price` (money text) won't do for work `id`, or nil: money as people
  write it, at least the work's materials, at most the bound on estimates."
  [world id price]
  (let [spec (money/spec world)
        {:keys [materials]} (work-order world id)
        most (judge/max-estimate world)]
    (if-let [n (price-of world price)]
      (cond (< n materials) (str "the price must cover the materials, " (show-money-in world materials))
            (> n most) (str "the price is at most " (show-money-in world most)))
      (str "price: " (money/parse-reason spec price)))))

(defn- self-order? [actor worker] (= actor worker))

(defn- account-reason
  "Why `account` can't pay `n` for a work, or nil; nothing to pay needs no
  account. The orderer (`payer?`) hears the balance, as `buy` says it;
  the worker hears only that it can't be covered, never the balance."
  [world actor payer? account n]
  (when (pos? n)
    (if payer?
      (some->> (money/spend-reason world actor (if (= account actor) "own" "household") n)
               (str "it costs " (show-money-in world n) "; "))
      (when (> n (get-in world [:state :accounts account] 0))
        (str (if (= "household" account) "the household account" (str (n/who (n/names world) account) "'s purse"))
             " can't cover " (show-money-in world n))))))

(defn order-reason
  "Why `actor` can't order work `id` to `worker`, or nil. Ordering to
  oneself pays the materials at once, so the account must cover them."
  [world actor {:keys [id worker price from]}]
  (or (known-reason world actor id)
      (status-reason world id :planned "ordered")
      (worker-reason world actor worker)
      (when price (price-reason world id price))
      (when (self-order? actor worker)
        (account-reason world actor true (money/account actor from) (:materials (work-order world id))))
      (when (and (= "own" from) (nil? (get-in world [:state :accounts actor])))
        "you have no personal account; use from \"household\" instead")))

(defn- payment
  "What accepting work `wo` pays: the price from its account, the labour
  (price less materials) to the worker's purse. Someone working for
  themselves pays only the materials, and no labour moves."
  [{:keys [orderer worker price materials account]}]
  (if (= orderer worker)
    {:account account :paid materials :labour 0}
    {:account account :paid price :labour (- price materials)}))

(defn offered
  "The event for a checked order: offered to `worker`, seen by both; or, to
  oneself, taken on and paid for at once (the materials only)."
  [world actor {:keys [id worker price from]}]
  (let [{:keys [estimate materials]} (work-order world id)
        self? (self-order? actor worker)
        data {:id id :orderer actor :worker worker :account (money/account actor from)
              :price (if self? materials (or (price-of world price) estimate)) :priced-by actor}]
    (if self?
      {:type :works/accepted :visible-to #{actor}
       :data (merge data (payment (assoc data :materials materials)))}
      {:type :works/offered :visible-to #{actor worker} :data data})))

(defn answerer
  "Who is to answer offered work `wo`: whoever didn't set its current price."
  [{:keys [orderer worker priced-by]}]
  (if (= priced-by worker) orderer worker))

(defn- offer-of
  "Work `id` as offered to `actor`, or nil."
  [world actor id]
  (let [wo (work-order world id)]
    (when (and (= :offered (:status wo)) (= actor (:worker wo))) wo)))

(defn answer-reason
  "Why `actor` can't decline the offer of work `id`, or nil: only its worker can."
  [world actor {:keys [id]}]
  (or (known-reason world actor id)
      (when-not (offer-of world actor id)
        (str "work " id " isn't offered to you; only the person it's offered to can decline it"))))

(defn- other-party [{:keys [orderer worker]} actor] (if (= actor orderer) worker orderer))

(defn- together-reason
  "Why `actor` and the other side of offered work `wo` can't settle it now, or nil."
  [world actor wo]
  (let [other (other-party wo actor)]
    (when-not (and (w/active? world other) (space/together? world actor other))
      (str (n/who (n/names world) other) " (" other ") must be here with you to settle work " (:id wo)
           (when (= actor (:worker wo)) "; go to them, or decline-work")))))

(defn- party-reason
  "Why `actor` isn't a side of offered work `id`, or nil."
  [world actor id what]
  (let [{:keys [orderer worker status] :as wo} (work-order world id)]
    (or (known-reason world actor id)
        (when-not (= :offered status) (str "work " id " isn't on offer; only an offered work can be settled"))
        (when-not (some #{actor} [orderer worker])
          (str "work " id " is between " (n/who (n/names world) orderer) " and " (n/who (n/names world) worker)
               "; only they can " what " it"))
        (together-reason world actor wo))))

(defn accept-reason
  "Why `actor` can't accept work `id`, or nil: they must be the side that
  didn't set the current price, with the other side, and its account must
  cover the price."
  [world actor {:keys [id] :as args}]
  (let [wo (work-order world id)]
    (or (party-reason world actor id "accept")
        (when-not (= actor (answerer wo))
          (str "you set the price of work " id "; " (n/who (n/names world) (other-party wo actor))
               " is to answer it with accept-work or revise-work"))
        (let [{:keys [account paid]} (payment wo)]
          (account-reason world actor (= actor (:orderer wo)) account paid)))))

(defn revise-reason
  "Why `actor` can't revise the price of work `id` to `price`, or nil: they
  must be its orderer or worker, with the other, while it's offered; the
  price at least the materials and at most the bound."
  [world actor {:keys [id price]}]
  (or (party-reason world actor id "revise")
      (price-reason world id price)))

(defn- revised [world actor {:keys [id price]}]
  (let [wo (work-order world id)]
    [{:type :works/revised :visible-to #{(:orderer wo) (:worker wo)}
      :data {:id id :price (price-of world price) :priced-by actor :orderer (:orderer wo) :worker (:worker wo)}}]))

(defn- accepted [world _ {:keys [id]}]
  (let [wo (work-order world id)]
    [{:type :works/accepted :visible-to #{(:orderer wo) (:worker wo)}
      :data (merge (select-keys wo [:id :orderer :worker :price :priced-by :account]) (payment wo))}]))

(defn- declined [world _ {:keys [id]}]
  (let [wo (work-order world id)]
    [{:type :works/declined :visible-to #{(:orderer wo) (:worker wo)}
      :data (select-keys wo [:id :orderer :worker])}]))

(defn cancel-reason
  "Why `actor` can't cancel work `id`, or nil: only its planner or orderer,
  and only before it's accepted."
  [world actor {:keys [id]}]
  (let [{:keys [planner orderer status]} (work-order world id)]
    (or (known-reason world actor id)
        (when-not (some #{actor} [planner orderer]) "only the person who planned or ordered a work can cancel it")
        (when-not (#{:planned :offered} status)
          (str "work " id " is " (name status) "; only a work not yet accepted can be cancelled")))))

(defn- cancelled [world actor {:keys [id]}]
  [{:type :works/cancelled :visible-to (:known-to (work-order world id)) :data {:id id :by actor}}])

(def ^:private terms [:orderer :worker :price :priced-by :account])

(defn- given
  "Work `id` with the terms in `data`, at `status`, known to the worker."
  [world data status]
  (update-in world [:state :work-orders (:id data)]
             #(-> (merge % (select-keys data terms)) (assoc :status status) (update :known-to conj (:worker data)))))

(defmethod w/apply-event :works/offered [world {:keys [data]}] (given world data :offered))

(defn- pay
  "The payment of an acceptance: `paid` from the account, `labour` to the
  worker's purse (opened if they have none)."
  [world {:keys [account paid labour worker]}]
  (cond-> world
    (pos? (or paid 0)) (update-in [:state :accounts account] (fnil - 0) paid)
    (pos? (or labour 0)) (update-in [:state :accounts worker] (fnil + 0) labour)))

(defmethod w/apply-event :works/accepted [world {:keys [data]}]
  (-> world (given data :accepted) (pay data) (purchases/work-accepted data)))

(defmethod w/apply-event :works/revised [world {:keys [data]}]
  (update-in world [:state :work-orders (:id data)] merge (select-keys data [:price :priced-by])))

(defmethod w/apply-event :works/declined [world {:keys [data]}]
  (update-in world [:state :work-orders (:id data)]
             #(-> (apply dissoc % terms) (assoc :status :planned))))

(defmethod w/apply-event :works/cancelled [world {:keys [data]}]
  (assoc-in world [:state :work-orders (:id data) :status] :cancelled))

(defn- paid-by
  "Who pays, as the narration says it."
  [names account]
  (if (= "household" account) "the household account" (str (n/who names account) "'s own purse")))

(defmethod n/describe :works/offered [{:keys [data]} names]
  (let [{:keys [id orderer worker price account]} data]
    (str (n/who names orderer) " offers " (n/who names worker) " work " id " at a price of "
         (show-money names price) ", paid from " (paid-by names account) ". "
         (plan-text (assoc (:plan data) :id id) names)
         " " (n/who names worker) " answers with accept-work, revise-work (another price) or decline-work with id \"" id "\"; "
         "the two must be together to settle it, and " (n/who names orderer) " may cancel-work before it is accepted.")))

(defmethod n/describe :works/revised [{:keys [data]} names]
  (let [{:keys [id price priced-by orderer worker]} data
        other (if (= priced-by worker) orderer worker)]
    (str (n/who names priced-by) " asks a price of " (show-money names price) " for work " id ". "
         (n/who names other) " is to answer with accept-work, or revise-work with another price"
         (when (= other worker) ", or decline-work") ".")))

(defmethod n/describe :works/accepted [{:keys [data]} names]
  (let [{:keys [id orderer worker price summary account paid labour]} data]
    (if (= orderer worker)
      (str (n/who names worker) " takes on work " id " themselves: " summary
           (when (pos? (or paid 0))
             (str " " (payments/paid-line names {:amount paid :account account :for (str "for the materials of work " id ", which are there")
                                                 :owed " for this work"})))
           " It is theirs to carry out.")
      (str "Work " id " is agreed between " (n/who names orderer) " and " (n/who names worker) " at a price of "
           (show-money names price) ": " summary
           (when (pos? (or paid 0))
             (str " " (payments/paid-line names {:amount paid :account account
                                                 :for (str "for work " id
                                                           (when (pos? (or labour 0)) (str ", " (show-money names labour) " of it to " (n/who names worker) " for the labour"))
                                                           "; the materials are there")
                                                 :owed " for this work"})))
           " It is now " (n/who names worker) "'s to carry out."))))

(defmethod n/describe :works/declined [{:keys [data]} names]
  (let [{:keys [id orderer worker]} data]
    (str (n/who names worker) " declines work " id ". It is a plan again; " (n/who names orderer)
         " may offer it to someone else with order-work.")))

(defmethod n/describe :works/cancelled [{:keys [data]} names]
  (str (n/who names (:by data)) " cancels work " (:id data) "; it will not be done."))

(defn- with-plan
  "Event `ev` with the work's plan in its data, so its narration can show
  it and a summary: what the worker learns."
  [world ev]
  (let [wo (work-order world (get-in ev [:data :id]))]
    (update ev :data assoc :summary (:summary wo)
            :plan (select-keys wo [:summary :who :phases :item :fate :estimate :materials :kind :place :to]))))

(declare work-place place-name)

(def calendar-owner
  "How works own their calendar entries (see manor.mechanics.calendar): ids
  starting works- are theirs, one per accepted work, for its worker."
  {:id-prefix "works-" :hint "it is kept by the work until it's done"})

(defn- entry-id [id] (str "works-" id))

(defn- clip [s] (if (<= (count s) 600) s (str (subs s 0 599) "…")))

(defn- calendar-entry
  "`worker`'s calendar entry for work `id` they have just accepted, dated
  today (all day), saying what to carry out and where; nil without a
  calendar."
  [world id worker]
  (let [{:keys [summary] :as wo} (work-order world id)
        place (work-place world wo)]
    (when (:calendar world)
      (calendar/managed-command-events
       world :add {:id (entry-id id) :date (str (.toLocalDate (calendar/now world))) :visible-to #{worker}
                   :description (clip (str "Carry out work " id ": " summary
                                           (when place (str " (in the " (place-name world place) ")"))))}
       :works))))

(defn- entry-removed
  "The events that cancel work `id`'s calendar entry, if it has one still standing."
  [world id]
  (let [entry (get-in world [:calendar :entries (entry-id id)])]
    (when (and entry (not= :cancelled (get-in entry [:lifecycle :status])))
      (calendar/managed-command-events world :cancel {:id (entry-id id)} :works))))

(defn- with-entry
  "`events`, and the worker's calendar entry if they accept a work."
  [world events]
  (into (vec events)
        (mapcat #(when (= :works/accepted (:type %)) (calendar-entry world (get-in % [:data :id]) (get-in % [:data :worker]))))
        events))

(defn- money-doc
  "A tool's description from the template at `path`, with {{money-example}}:
  a price in the setting's money as people write it."
  [path]
  (fn [world] (prompts/render world path {:money-example (money/example (money/spec world))})))

(def order-action
  {:name :order-work
   :doc (money-doc "docs/works/order-work.md")
   :roles :all
   :kind :free
   :available? (fn [world actor] (boolean (some #(and (= :planned (:status %)) (contains? (:known-to %) actor))
                                                (vals (work-orders world)))))
   :args [:map [:id :string] [:worker :string] [:price {:optional true} :string]
          [:from {:optional true} [:enum "household" "own"]]]
   :valid? order-reason
   :effect (fn [world actor args] (with-entry world [(with-plan world (offered world actor args))]))
   :reply (fn [world actor {:keys [id worker]}]
            (let [{:keys [price]} (work-order world id)]
              (if (= actor worker)
                (str "You have taken on work " id " yourself"
                     (when (pos? price) (str ", its materials paid (" (show-money-in world price) ")"))
                     ": it is yours to carry out.")
                (str "You have offered work " id " to " (n/who (n/names world) worker) " at " (show-money-in world price)
                     ". Stay with them until they answer with accept-work, revise-work or decline-work: you must be together to settle it."))))})

(defn- offered-to? [world actor]
  (boolean (some #(and (= :offered (:status %)) (= actor (:worker %))) (vals (work-orders world)))))

(defn- to-answer? [world actor]
  (boolean (some #(and (= :offered (:status %)) (= actor (answerer %))) (vals (work-orders world)))))

(defn- haggling? [world actor]
  (boolean (some #(and (= :offered (:status %)) (some #{actor} [(:orderer %) (:worker %)])) (vals (work-orders world)))))

(def accept-action
  {:name :accept-work
   :doc (prompts/doc "docs/works/accept-work.md")
   :roles :all
   :kind :free
   :available? to-answer?
   :args [:map [:id :string]]
   :valid? accept-reason
   :effect (fn [world actor args] (with-entry world (mapv #(with-plan world %) (accepted world actor args))))
   :reply (fn [world actor {:keys [id]}]
            (let [{:keys [worker price]} (work-order world id)]
              (str "You have accepted work " id " at " (show-money-in world price) ": "
                   (if (= actor worker) "it is yours to carry out." (str "it is " (n/who (n/names world) worker) "'s to carry out.")))))})

(def revise-action
  {:name :revise-work
   :doc (money-doc "docs/works/revise-work.md")
   :roles :all
   :kind :free
   :available? haggling?
   :args [:map [:id :string] [:price :string]]
   :valid? revise-reason
   :effect revised
   :reply (fn [world actor {:keys [id price]}]
            (str "You have asked " (show-money-in world (price-of world price)) " for work " id "; "
                 (n/who (n/names world) (other-party (work-order world id) actor)) " is to answer."))})

(def decline-action
  {:name :decline-work
   :doc (prompts/doc "docs/works/decline-work.md")
   :roles :all
   :kind :free
   :available? offered-to?
   :args [:map [:id :string]]
   :valid? answer-reason
   :effect declined
   :reply (fn [_ _ {:keys [id]}] (str "You have declined work " id "."))})

(def cancel-action
  {:name :cancel-work
   :doc (prompts/doc "docs/works/cancel-work.md")
   :roles :all
   :kind :free
   :available? (fn [world actor] (boolean (some #(and (#{:planned :offered} (:status %)) (some #{actor} [(:planner %) (:orderer %)]))
                                                (vals (work-orders world)))))
   :args [:map [:id :string]]
   :valid? cancel-reason
   :effect cancelled
   :reply (fn [_ _ {:keys [id]}] (str "You have cancelled work " id "."))})

(defn speaking-actions
  "Tools for a line in a conversation: ordering a work to someone in it
  (or oneself), and answering an offer made by someone in it."
  [_world _speaker {:keys [members]}]
  [(update order-action :valid?
           (fn [valid?]
             (fn [world actor args]
               (or (when-not (some #{(:worker args)} members) "order work on your line only to someone in this conversation, or yourself")
                   (valid? world actor args)))))
   revise-action
   accept-action
   decline-action])

;; ---------------------------------------------------------------------------
;; carry-out: a phase as an ordinary timed item job

(defn current-phase
  "The index of work `wo`'s first phase not yet complete, or nil."
  [wo]
  (first (keep-indexed (fn [i {:keys [minutes done]}] (when (< done minutes) i)) (:phases wo))))

(defn- remaining [phase] (- (:minutes phase) (:done phase)))

(defn work-place
  "Where work `wo` is done: an install at its place, a move or removal
  where its target lies now (nil if it's carried or gone)."
  [world {:keys [kind place target]}]
  (if (= :install kind)
    place
    (some->> (items/item world target) (items/place-of world))))

(defn job-for
  "The item job carrying out work `id`, or nil."
  [world id]
  (first (filter #(= id (get-in % [:work-order :id])) (items/jobs world))))

(defn- place-name [world place] (:name (space/location world place)))

(defn carry-out-reason
  "Why `actor` can't carry out work `id` now, or nil: they must be its
  worker, it accepted, they at the work's place, its target still there,
  and nobody already at it."
  [world actor {:keys [id]}]
  (let [{:keys [worker status kind target] :as wo} (work-order world id)
        place (when wo (work-place world wo))]
    (or (known-reason world actor id)
        (when (nil? (:clock world)) "there is no game time to work in")
        (when (not= actor worker) (str "work " id " isn't yours to carry out"))
        (when (not= :accepted status) (str "work " id " is " (name status) "; only an accepted work can be carried out"))
        (when (and (not= :install kind) (nil? (items/item world target)))
          (str "the thing work " id " was for is no longer there"))
        (when (nil? place)
          (str "the " (:name (items/item world target)) " is being carried; it must be set down to be worked on"))
        (when (not= place (space/location-of world actor))
          (str "work " id " is done in the " (place-name world place) " (" place "); go there first"))
        (when-let [job (job-for world id)]
          (str "work " id " is already being carried out (" (:id job) "); it ends as the next round starts, "
               "then use carry-out again to go on")))))

(defn carry-out-events
  "The job for the current phase (seen by the GM), with a :work-order
  reference {:id :phase}, its :doing the phase's text and its length the
  phase's minutes not yet worked, and the occupation that carries the job
  id (seen by everyone here). The target is the job's item; an install
  works on the place itself. Job ids come from the world's event count, as
  for spend-time."
  [world actor {:keys [id]}]
  (let [{:keys [kind target phases] :as wo} (work-order world id)
        i (current-phase wo)
        {:keys [doing] :as phase} (nth phases i)
        place (space/location-of world actor)
        job-id (str "job-" (inc (:seq world)))
        due (+ (:round world) (clock/rounds-for (:clock world) (remaining phase)))]
    [{:type :item/work-begun :visible-to :gm
      :data {:id job-id :actor actor :items [(if (= :install kind) place target)] :doing doing
             :place place :started (:round world) :due due :work-order {:id id :phase i}}}
     (assoc-in (b/occupy world actor doing due) [:data :job] job-id)]))

(defn- yours-to-carry-out? [world actor]
  (boolean (some #(and (= :accepted (:status %)) (= actor (:worker %))) (vals (work-orders world)))))

(def carry-out-action
  {:name :carry-out
   :doc (prompts/doc "docs/works/carry-out.md")
   :roles :all
   :kind :final
   :available? yours-to-carry-out?
   :args [:map [:id :string]]
   :valid? carry-out-reason
   :effect carry-out-events})

;; Ending a phase's job: progress, or the phase done.

(defmethod w/apply-event :works/progressed [world {:keys [data]}]
  (assoc-in world [:state :work-orders (:id data) :phases (:phase data) :done] (:done data)))

(defmethod w/apply-event :works/phase-done [world {:keys [data]}]
  (update-in world [:state :work-orders (:id data)]
             #(-> % (assoc-in [:phases (:phase data) :done] (get-in % [:phases (:phase data) :minutes]))
                  (assoc :status :finishing))))

(defmethod n/describe :works/progressed [{:keys [data]} _]
  (let [{:keys [id done minutes]} data]
    (str "Work " id ": " done " of " minutes " minutes done. Use carry-out with id \"" id "\" to go on with it.")))

(defmethod n/describe :works/phase-done [{:keys [data]} _]
  (str "Work " (:id data) ": the work is carried out; what came of it remains to be seen."))

;; ---------------------------------------------------------------------------
;; The scene judge's brief for a work's job

(defn- worker-fitness
  "What the worker is, for weighing them against the work: their contact's
  line if they're known as a contact, else their position's title."
  [world worker]
  (let [contact (first (filter #(= worker (:agent-id %)) (vals (get-in world [:state :contacts]))))
        title (get-in world [:state :positions (:role (w/agent world worker)) :title])]
    (cond contact (str "a " (:line contact) " (by trade)")
          title title
          :else "no particular position or trade")))

(defn work-section
  "The scene judge's \"## The work\" section for `job`, a phase of work `wo`."
  [world job wo]
  (let [{:keys [kind summary who phases item target to]} wo
        phase (get-in job [:work-order :phase])
        thing-name #(:name (items/item world %) %)]
    (str "## The work\n\n"
         "- Kind: " (name kind) "\n"
         "- Plan: " summary "\n"
         "- Who should do it: " who "\n"
         "- This phase: " (get-in phases [phase :doing]) " (phase " (inc phase) " of " (count phases) ")\n"
         (when item (str "- Planned thing: " (:name item) ": " (:desc item)
                         (when (seq (:aspects item))
                           (str " (" (str/join "; " (map (fn [[k v]] (str (name k) ": " v)) (:aspects item))) ")"))
                         "\n"))
         (when target (str "- Worked on: " (thing-name target) " (" target ")\n"))
         (when to (str "- To: " (place-name world to) " (" to ")\n"))
         "- The worker is: " (worker-fitness world (:worker wo)))))

(defn work-brief
  "The scenario's :items :work-brief: for a work's job, the scene judge's
  instructions on works and the work's section."
  [world job]
  (when-let [wo (work-order world (get-in job [:work-order :id]))]
    {:instructions (prompts/render world "judges/scene-work.md" {})
     :section (work-section world job wo)}))

;; ---------------------------------------------------------------------------
;; The outcome (decision 6): the scene judge's `work` entry applied

(defn- outcome-audience
  "Who sees a work's outcome: everyone at the job's place, the worker, the
  planner and the orderer."
  [world {:keys [place]} wo]
  (into (space/present world place) (remove nil? [(:worker wo) (:planner wo) (:orderer wo)])))

(defn- installed-item
  "The new fixed thing an install leaves at `place`: the judge's `item` for
  a flawed one if it gave one, else the planned one; owned by the
  paying account. A fresh id on `world`."
  [world wo place {:keys [outcome item]}]
  (let [fields (if (and (= :flawed outcome) item) item (:item wo))]
    (items/new-item world (assoc (select-keys fields [:name :desc :aspects])
                                 :place place :fixed? true :owner (:account wo "household")))))

(defn- left-behind
  "Ids of the things directly inside `target`: a removal leaves them at its place."
  [world target]
  (vec (sort (map :id (filter #(= target (:in %)) (items/all world))))))

(defn outcome-events
  "The events that apply work `wo`'s outcome after its last phase's `job`:
  :works/done or :works/failed, from the judge's `work` ({:outcome :item?}).
  With no checked verdict (the judge failed), the work counts as done as
  planned, as the job's neutral summary says. A move or removal whose
  target is gone fails whatever the judge said."
  [world job wo work]
  (let [{:keys [id kind target to fate]} wo
        {:keys [outcome] :as work} (or work {:outcome :as-planned})
        it (when target (items/item world target))
        place (:place job)
        base {:id id :kind kind :worker (:worker wo) :place place :outcome outcome}
        ev (fn [type data] {:type type :visible-to (outcome-audience world job wo) :data (merge base data)})]
    (cond
      (and (not= :install kind) (nil? it))
      [(ev :works/failed {:target target :reason :gone})]

      (= :failed outcome)
      [(ev :works/failed (cond-> {:reason :judged}
                           it (assoc :target target :target-name (:name it))
                           (= :install kind) (assoc :item-name (get-in wo [:item :name]))))]

      (= :install kind)
      [(ev :works/done {:item (installed-item world wo place work)})]

      (= :move kind)
      [(ev :works/done {:target target :target-name (:name it) :to to})]

      :else
      [(ev :works/done {:target target :target-name (:name it) :fate fate
                        :from (items/place-of world it) :left (left-behind world target)})])))

(defn- settle
  "Work `id` at `status`, with its outcome recorded."
  [world {:keys [id outcome reason]} status]
  (update-in world [:state :work-orders id]
             #(cond-> (assoc % :status status :outcome outcome) reason (assoc :failure reason))))

(defn- apply-done
  "The structural change of a work done: a new fixed item, a thing moved,
  or a thing removed with what it held left at its place."
  [world {:keys [kind item target to fate from left]}]
  (let [round (:round world)]
    (case kind
      :install (assoc-in world [:items (:id item)] (assoc item :touched round))
      :move (update-in world [:items target] #(-> % (dissoc :holder :in) (assoc :place to :touched round)))
      :remove (-> (reduce (fn [w id] (update-in w [:items id] #(-> % (dissoc :in) (assoc :place from :touched round))))
                          world left)
                  (items/remove-items [target] fate)))))

(defmethod w/apply-event :works/done [world {:keys [data]}]
  (-> world
      (apply-done data)
      (settle data :done)
      (cond-> (:item data) (assoc-in [:state :work-orders (:id data) :made] (get-in data [:item :id])))
      (purchases/work-settled (:id data) :done (:outcome data))))

(defmethod w/apply-event :works/failed [world {:keys [data]}]
  (-> world
      (settle data :failed)
      (purchases/work-settled (:id data) :failed nil)))

(defn- flaw-note [outcome] (when (= :flawed outcome) ", though not as well as it should be"))

(defmethod n/describe :works/done [{:keys [data]} names]
  (let [{:keys [id kind worker place item target-name to fate left outcome]} data
        who (n/who names worker)]
    (str who " finishes work " id ": "
         (case kind
           :install (str "the " (:name item) " (" (:id item) ") is put in, in the " (n/place names place))
           :move (str "the " target-name " is moved to the " (n/place names to))
           :remove (str "the " target-name " is gone (" fate ")"
                        (when (seq left) (str "; what was in it is left in the " (n/place names place)))))
         (flaw-note outcome) ".")))

(defmethod n/describe :works/failed [{:keys [data]} names]
  (let [{:keys [id kind worker place target target-name item-name reason]} data]
    (str "Work " id " by " (n/who names worker) " comes to nothing: "
         (cond (= :gone reason) (str "the " (get names [:item target] target) " it was for is no longer there")
               (= :install kind) (str "no " item-name " is put in, in the " (n/place names place))
               :else (str "the " target-name " stays where it was"))
         ".")))

(defn after-job
  "A scenario :after-job hook (manor.engine.work): when the job of a work's
  phase ends, the events that record it. Stopped early, the minutes worked
  add up on the phase (:works/progressed); finished, the phase is complete
  (:works/phase-done) and, for the last phase, the outcome follows from the
  scene judge's `work` (`outcome-events`). Nothing for other jobs, or a job
  dropped or ended with no time worked."
  [world job {:keys [outcome minutes]} result]
  (when-let [{:keys [id phase]} (:work-order job)]
    (let [wo (work-order world id)
          {:keys [done] total :minutes} (get-in wo [:phases phase])
          worker #{(:worker wo)}
          last? (= phase (dec (count (:phases wo))))]
      (cond
        (= :finished outcome)
        (cond-> [{:type :works/phase-done :visible-to worker :data {:id id :phase phase}}]
          last? (-> (into (outcome-events world job wo (get-in result [:verdict :work])))
                    (into (entry-removed world id))))

        (and (= :stopped outcome) (pos? minutes))
        [{:type :works/progressed :visible-to worker
          :data {:id id :phase phase :minutes total :done (min total (+ done minutes))}}]))))

;; ---------------------------------------------------------------------------
;; Views

(defn- phase-entry
  "The current phase, or the last one, and its progress, for the view."
  [{:keys [phases] :as wo}]
  (let [i (or (current-phase wo) (dec (count phases)))
        {:keys [doing minutes done]} (nth phases i)]
    {:phase (str (inc i) " of " (count phases) ": " doing)
     :progress (str done " of " minutes " minutes done")}))

(defn- result-text
  "How a done or failed work came out, for the view."
  [world {:keys [status outcome failure made kind to]}]
  (cond (= :gone failure) "failed: the thing it was for was gone"
        (= :failed status) "failed: nothing came of it"
        :else (str (if (= :flawed outcome) "done, but with faults" "done as planned")
                   (case kind
                     :install (str ": " (:name (items/item world made) made) " (" made ")")
                     :move (str ": now in the " (place-name world to))
                     ""))))

(defn- offer-next
  "For an offered work: what `id` is to do, or whom they're waiting for."
  [world id wo]
  (let [to (answerer wo)]
    (if (= id to)
      (str "answer with accept-work, or revise-work with another price"
           (when (= id (:worker wo)) ", or decline-work") " (id \"" (:id wo) "\")")
      (str "waiting for " (n/who (n/names world) to) " to accept or revise the price"))))

(defn- view-entry
  "A work as `id` sees it under `works`."
  [world id {:keys [orderer worker price status] :as wo}]
  (let [who #(n/who (n/names world) %)
        place (work-place world wo)]
    (cond-> (-> (select-keys wo [:id :kind :summary :who :trade :status])
                (update :kind name) (update :status name)
                (assoc :where (where-text wo (n/names world))))
      (and orderer (not= id orderer)) (assoc :ordered-by (who orderer))
      (and worker (not= id worker)) (assoc :worker (who worker))
      (and worker (= id worker)) (assoc :worker "you")
      price (assoc :price (show-money-in world price))
      (and price (:priced-by wo)) (assoc :priced-by (if (= id (:priced-by wo)) "you" (who (:priced-by wo))))
      (:account wo) (assoc :paid-from (if (= "household" (:account wo)) "household" (if (= id (:account wo)) "your purse" (str (who (:account wo)) "'s purse"))))
      (and (#{:accepted :finishing :done :failed} status) (pos? (or price 0)))
      (assoc :paid "in full when accepted; nothing more is owed for it")
      (= :offered status) (assoc :next (offer-next world id wo))
      (#{:done :failed} status) (assoc :result (result-text world wo))
      (#{:accepted :finishing} status) (merge (phase-entry wo))
      (and (= id worker) (= :accepted status))
      (assoc :next (str "use carry-out with id \"" (:id wo) "\""
                        (when place (str " in the " (place-name world place) " (" place ")")))))))

(defn add-to-view
  "`view` with `works`: the plans `id` knows of, except cancelled ones."
  [view world id]
  (let [known (filter #(and (contains? (:known-to %) id) (not= :cancelled (:status %))) (ordered world))]
    (cond-> view
      (seq known) (assoc :works (mapv #(view-entry world id %) known)))))

;; ---------------------------------------------------------------------------
;; For the operator (the panel's scene view): every work, omniscient

(def open-statuses #{:planned :offered :accepted :finishing})

(defn operator-entry
  "Work `wo` as the operator sees it: everything, in plain fields. Never
  shown to a character."
  [world {:keys [planner orderer worker price account status] :as wo}]
  (let [who #(n/who (n/names world) %)
        job (job-for world (:id wo))]
    (cond-> {:id (:id wo) :kind (name (:kind wo)) :status (name status) :summary (:summary wo)
             :where (where-text wo (n/names world)) :place (work-place world wo)
             :planner (who planner) :who (:who wo)
             :estimate (show-money-in world (:estimate wo)) :materials (show-money-in world (:materials wo))
             :people (set (remove nil? [planner orderer worker]))}
      (:trade wo) (assoc :trade (:trade wo))
      orderer (assoc :ordered-by (who orderer))
      worker (assoc :worker (who worker))
      price (assoc :price (show-money-in world price))
      (:priced-by wo) (assoc :priced-by (who (:priced-by wo)))
      account (assoc :paid-from (if (= "household" account) "household" (str (who account) "'s purse")))
      (#{:accepted :finishing} status) (merge (phase-entry wo))
      job (assoc :under-way (str (who (:actor job)) ": " (:doing job)))
      (#{:done :failed} status) (assoc :result (result-text world wo)))))

(defn operator-entries
  "Every work, oldest first, as the operator sees them."
  [world]
  (mapv #(operator-entry world %) (ordered world)))

(def actions [plan-action order-action revise-action accept-action decline-action cancel-action carry-out-action])

;; ---------------------------------------------------------------------------
;; Staying on task (docs/fixtures-design.md 5.5)

(defn open-works
  "The works `id` has accepted and not yet finished, oldest first."
  [world id]
  (filter #(and (= id (:worker %)) (#{:accepted :finishing} (:status %))) (ordered world)))

(defn open-work?
  "Has `id` accepted work not yet finished? It keeps them out of
  hibernation."
  [world id]
  (boolean (seq (open-works world id))))

(defn early-reminder
  "A scenario :early-reminder: for each work `id` has accepted and isn't
  at now, a sentence (agent/reminder-work.md) saying where to carry it
  out; nil if there's none."
  [world id]
  (let [lines (for [wo (open-works world id)
                    :when (and (= :accepted (:status wo)) (nil? (job-for world (:id wo))))
                    :let [place (work-place world wo)]]
                (prompts/render world "agent/reminder-work.md"
                                {:id (:id wo)
                                 :where (if place (str "the " (place-name world place) " (" place ")") "where its thing is")}))]
    (when (seq lines) (str/join " " lines))))

(def fixed-hint
  "The end of `take`'s refusal of a fixed thing (the scenario's :items :fixed-hint)."
  "to move it, plan a work with plan-work")

(defn install
  "Add works to a scenario: its actions, the works judge, the hook that
  records a phase's job ending, the early reminder of accepted work, and
  take's pointer to plan-work for a fixed thing. Idempotent."
  [scenario]
  (let [names (set (map :name actions))]
    (-> scenario
        (update :actions (fn [as] (into (vec (remove #(names (:name %)) as)) actions)))
        (update :adjudicators merge judge/adjudicators)
        (assoc :early-reminder early-reminder)
        (cond-> (:calendar scenario) (assoc-in [:calendar :managed :works] calendar-owner))
        (cond-> (:items scenario) (update :items assoc :work-brief work-brief :fixed-hint fixed-hint))
        (update :after-job (fn [hooks] (conj (vec (remove #{after-job} hooks)) after-job))))))
