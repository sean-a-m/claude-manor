(ns manor.mechanics.payments
  "Paying people (docs/payments-design.md). `pay {to amount for from?}`
  hands money to another person, here or not: from the household account
  or the payer's own purse to the payee's purse, opened if they have none.
  It's free and offered on speaking turns. A household payment goes in the
  household book (manor.mechanics.purchases, :kind :payment records).

  Every payment reads the same: `pay`'s event and the events that pay by
  themselves (purchases, deliveries, send-up, works on acceptance,
  commissions) end their narration with the line `paid-line` makes, which
  says what was paid, from which account, to whom, and that nothing more is
  owed where nothing is. The other mechanisms keep their own events and
  reducers; only their words are shared."
  (:require [manor.mechanics.money :as money]
            [manor.mechanics.purchases :as purchases]
            [manor.mechanics.space :as space]
            [manor.narrate :as n]
            [manor.prompts :as prompts]
            [manor.world :as w]))

(def max-for "Characters in `for`: what a payment is for." 200)

;; ---------------------------------------------------------------------------
;; The shared line

(defn- show [names amount]
  (if-let [m (get names [:money])] (money/display m amount) (str amount)))

(defn account-text
  "Who pays, as narration says it: \"the household account\" or \"Hale's own purse\"."
  [names account]
  (if (= "household" account) "the household account" (str (n/who names account) "'s own purse")))

(defn paid-line
  "The one line every payment ends with: \"Paid: £8 2s from the household
  account, by Lady Claudeworth to Levi Quince, for the chairs. Nothing more
  is owed.\" `to` and `by` are display text (a person, \"the Draper's
  shop\"); `for`, the rest of its sentence; `owed`, nil for no closing
  sentence, \"\" for \"Nothing more is owed.\", or what it isn't owed for
  (\" for this work\"). Without an account, it leaves the account out."
  [names {:keys [amount account by to for owed]}]
  (str "Paid: " (show names amount)
       (when account (str " from " (account-text names account)))
       (when by (str ", by " by))
       (when to (str " to " to))
       (when for (str ", " for))
       "."
       (when owed (str " Nothing more is owed" owed "."))))

;; ---------------------------------------------------------------------------
;; pay

(defn- payee-reason
  "Why `actor` can't pay `to`, or nil: someone in the world (here or not,
  even away), not the GM, the dead or the retired, and not themselves."
  [world actor to]
  (let [a (w/agent world to)]
    (cond (= to actor) "you can't pay yourself; give `to` another person's id"
          (or (nil? a) (= :gm (:role a)) (not (#{:active :away} (:status a))))
          (str "no one with id " to " can be paid; use a person's id"))))

(defn- amount-reason [world amount]
  (if-let [n (money/parse (money/spec world) amount)]
    (when-not (pos? n) "the amount must be more than nothing")
    (str "amount: " (money/parse-reason (money/spec world) amount))))

(defn pay-reason
  "Why `actor` can't pay: the payee, the amount as money text, and the
  account's balance (`money/spend-reason`, the payer's own only)."
  [world actor {:keys [to amount from]}]
  (or (payee-reason world actor to)
      (amount-reason world amount)
      (let [n (money/parse (money/spec world) amount)]
        (some->> (money/spend-reason world actor from n)
                 (str "you'd pay " (money/display (money/spec world) n) "; ")))))

(defn paid
  "The event for a checked `pay`: seen by those with the payer and by the payee."
  [world actor {:keys [to amount from] :as args}]
  (let [audience (space/here world actor)
        present? (space/together? world actor to)]
    {:type :money/paid
     :visible-to (if (set? audience) (conj audience to) #{actor to})
     :data (cond-> {:payer actor :payee to :account (money/account actor from)
                    :amount (money/parse (money/spec world) amount) :for (:for args)}
             (not present?) (assoc :sent? true))}))

(defmethod w/apply-event :money/paid [world {:keys [data]}]
  (let [{:keys [account payee amount]} data]
    (-> world
        (update-in [:state :accounts account] (fnil - 0) amount)
        (update-in [:state :accounts payee] (fnil + 0) amount)
        (purchases/paid data))))

(defmethod w/done-to :money/paid [{:keys [data]}] [(:payee data)])

(defmethod n/describe :money/paid [{:keys [data]} names]
  (let [{:keys [payer payee account amount sent?]} data]
    (str (paid-line names {:amount amount :account account :by (n/who names payer) :to (n/who names payee)
                           :for (str "for " (:for data))})
         (when sent? (str " " (n/who names payee) " isn't here; it reaches them where they are.")))))

(defn- pay-reply
  "What the payer hears: the payment, and what's left in the account."
  [world actor {:keys [to amount from] :as args}]
  (let [names (n/names world)
        account (money/account actor from)
        left (get-in world [:state :accounts account])]
    (str (paid-line names {:amount (money/parse (money/spec world) amount) :account account
                           :to (n/who names to) :for (str "for " (:for args))})
         " " (if (= "household" account) "The household account" "Your purse") " has "
         (money/display (money/spec world) left) " left.")))

(def action
  {:name :pay
   :kind :free
   :roles :all
   :doc (fn [world] (prompts/render world "docs/money/pay.md"
                                    {:money-example (money/example (money/spec world)) :max-for max-for}))
   :args [:map [:to :string] [:amount :string] [:for [:string {:min 1 :max max-for}]]
          [:from {:optional true} [:enum "household" "own"]]]
   :valid? pay-reason
   :effect (fn [world actor args] [(paid world actor args)])
   :reply pay-reply})
