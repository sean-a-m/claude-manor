(ns manor.scenarios.village
  "A toy village near Lisbon, used to exercise the engine.

  Hard state: coins, grain, fields and hunger per villager (all private),
  plus a public treasury and tax rate. Each round is one day. The day's
  weather is real historical weather from Open-Meteo, and it drives crop
  growth. Villagers eat one grain a day; anyone hungry for 4 days leaves.

  Places: the square (town hall), the market, the fields, and Lisbon, two
  rounds down the road from the square. Farming happens in the fields,
  trades need both people in the same place, the mayor works from the
  square, and the merchant buys grain wholesale in Lisbon.

  Exercises: locations and travel, private information, agent-to-agent
  trade, a query bridge (check-forecast), an act bridge that needs operator
  approval (send-letter), world systems, and the GM spawning newcomers."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [manor.bridge :as bridge]
            [manor.narrate :as narrate]
            [manor.rng :as rng]
            [manor.space :as space]
            [manor.world :as w])
  (:import (java.time LocalDate)))

(def ready-growth 8)
(def import-price 4)
(def offer-ttl 3)
(def starve-at 4)

;; ---------------------------------------------------------------------------
;; Helpers

(defn- hold [world id k] (get-in world [:state :holdings id k] 0))

(defn- date-for-round [world round]
  (str (.plusDays (LocalDate/parse (get-in world [:state :start-date])) (dec round))))

(defn- villagers [world] (filter #(not= :gm (:role %)) (w/active-agents world)))

(defn- villager-ids [world] (set (map :id (villagers world))))

(defn- at [world id loc] (= loc (space/location-of world id)))

(defn- require-at
  "nil if `id` is at `loc`, else a reason."
  [world id loc what]
  (when-not (at world id loc)
    (str "you must be at " (:name (space/location world loc)) " (" loc ") to " what)))

(defn- can-pay? [world id {:keys [coins grain]}]
  (and (<= (or coins 0) (hold world id :coins))
       (<= (or grain 0) (hold world id :grain))))

(defn- tax-on [world coins]
  (quot (* (or coins 0) (get-in world [:state :tax-percent] 0)) 100))

(defn- transfer [state from to {:keys [coins grain]}]
  (-> state
      (update-in [:holdings from :coins] - (or coins 0))
      (update-in [:holdings to :coins] + (or coins 0))
      (update-in [:holdings from :grain] - (or grain 0))
      (update-in [:holdings to :grain] + (or grain 0))))

(def ^:private wmo
  {0 "clear sky" 1 "mainly clear" 2 "partly cloudy" 3 "overcast" 45 "fog" 48 "fog"
   51 "light drizzle" 53 "drizzle" 55 "heavy drizzle" 61 "light rain" 63 "rain" 65 "heavy rain"
   71 "light snow" 73 "snow" 75 "heavy snow" 80 "rain showers" 81 "rain showers" 82 "violent showers"
   95 "thunderstorm" 96 "thunderstorm with hail" 99 "thunderstorm with hail"})

;; ---------------------------------------------------------------------------
;; Bridges

(defn open-meteo-daily
  "Real historical daily weather for a place and date range."
  [{:keys [lat lon start end]} _ctx]
  (let [d (:daily (bridge/get-json "https://archive-api.open-meteo.com/v1/archive"
                                   {:latitude lat :longitude lon :start_date start :end_date end
                                    :daily "temperature_2m_max,temperature_2m_min,precipitation_sum,weather_code"
                                    :timezone "UTC"}))]
    (mapv (fn [i]
            {:date (nth (:time d) i)
             :tmax (nth (:temperature_2m_max d) i)
             :tmin (nth (:temperature_2m_min d) i)
             :rain-mm (nth (:precipitation_sum d) i)
             :desc (wmo (nth (:weather_code d) i) "unknown")})
          (range (count (:time d))))))

(defn synthetic-daily
  "Offline stand-in for open-meteo-daily: plausible spring weather, fixed per date."
  [{:keys [start end]} _ctx]
  (let [s (LocalDate/parse start) e (LocalDate/parse end)]
    (vec (for [d (take-while #(not (.isAfter % e)) (iterate #(.plusDays % 1) s))
               :let [h (Math/abs (long (hash (str d))))
                     rain (if (< (mod h 10) 4) (double (mod (quot h 10) 22)) 0.0)]]
           {:date (str d) :tmax (+ 13.0 (mod (quot h 7) 14)) :tmin (+ 6.0 (mod (quot h 3) 7))
            :rain-mm rain :desc (if (pos? rain) "rain" "clear sky")}))))

(defn write-letter
  "Act bridge: append a letter to runs/<id>/outbox/letters.md. A real side
  effect outside the simulation, so it runs only after operator approval."
  [{:keys [from date text]} {:keys [run-dir]}]
  (let [f (io/file run-dir "outbox" "letters.md")]
    (io/make-parents f)
    (spit f (str "## Letter from " from " (" date ")\n\n" text "\n\n") :append true)
    {:delivered true :file (str f)}))

;; ---------------------------------------------------------------------------
;; Systems (run at the start of every round, in order)

(def weather-system
  {:name :weather
   :bridge :open-meteo/daily
   :bridge-args (fn [world]
                  (let [{:keys [lat lon]} (get-in world [:state :place])
                        d (date-for-round world (:round world))]
                    {:lat lat :lon lon :start d :end d}))
   :tick (fn [world {:keys [result]}]
           (let [day (or (first result)
                         {:date (date-for-round world (:round world))
                          :tmax 18.0 :tmin 10.0 :rain-mm 0.0 :desc "unremarkable (no weather data)"})]
             [{:type :village/weather :data day :visible-to :all}]))})

(defn- growth-delta [{:keys [tmax rain-mm]}]
  (cond-> 1
    (<= 1 rain-mm 20) inc
    (<= 14 tmax 28) inc
    (> rain-mm 30) (- 3)))

(def growth-system
  {:name :growth
   :tick (fn [world _]
           (let [weather (get-in world [:state :weather])]
             (for [[id {:keys [planted growth]}] (get-in world [:state :fields])
                   :when (and (contains? (villager-ids world) id) (pos? planted) (< growth ready-growth))
                   :let [delta (max 0 (growth-delta weather))]]
               {:type :village/crops-grew :actor id :visible-to #{id}
                :data {:delta delta :growth (min ready-growth (+ growth delta))}})))})

(def upkeep-system
  {:name :upkeep
   :tick (fn [world _]
           (mapcat (fn [{:keys [id]}]
                     (if (pos? (hold world id :grain))
                       [{:type :village/ate :actor id :visible-to :gm}]
                       (let [hunger (inc (get-in world [:state :hunger id] 0))]
                         (cond-> [{:type :village/hungry :actor id :visible-to #{id} :data {:hunger hunger}}]
                           (>= hunger starve-at)
                           (conj {:type :agent/retired :visible-to :all
                                  :data {:id id :reason "left the village, starving"}})))))
                   (villagers world)))})

(def offers-system
  {:name :offers
   :tick (fn [world _]
           (for [[oid {:keys [from to round]}] (get-in world [:state :offers])
                 :when (>= (- (:round world) round) offer-ttl)]
             {:type :village/offer-expired :visible-to #{from to} :data {:offer-id oid}}))})

;; ---------------------------------------------------------------------------
;; Reducers

(defmethod w/apply-event :village/weather [world {:keys [data]}]
  (-> world (assoc-in [:state :weather] data) (assoc-in [:state :date] (:date data))))

(defmethod w/apply-event :village/settled [world {:keys [actor data]}]
  (cond-> (assoc-in world [:state :holdings actor] (:holdings data))
    (:field data) (assoc-in [:state :fields actor] (:field data))))

(defmethod w/apply-event :village/crops-grew [world {:keys [actor data]}]
  (assoc-in world [:state :fields actor :growth] (:growth data)))

(defmethod w/apply-event :village/ate [world {:keys [actor]}]
  (-> world (update-in [:state :holdings actor :grain] dec) (assoc-in [:state :hunger actor] 0)))

(defmethod w/apply-event :village/hungry [world {:keys [actor data]}]
  (assoc-in world [:state :hunger actor] (:hunger data)))

(defmethod w/apply-event :village/planted [world {:keys [actor data]}]
  (-> world
      (update-in [:state :holdings actor :grain] - (:grain data))
      (assoc-in [:state :fields actor] {:planted (:grain data) :growth 0})))

(defmethod w/apply-event :village/harvested [world {:keys [actor data]}]
  (-> world
      (update-in [:state :holdings actor :grain] + (:yield data))
      (assoc-in [:state :fields actor] {:planted 0 :growth 0})))

(defmethod w/apply-event :village/offered [world {:keys [actor round data]}]
  (assoc-in world [:state :offers (:offer-id data)] (assoc data :from actor :round round)))

(defmethod w/apply-event :village/traded [world {:keys [data]}]
  (let [{:keys [offer-id from to give want tax]} data]
    (update world :state
            (fn [s]
              (-> s
                  (transfer from to give)
                  (transfer to from want)
                  (update-in [:holdings from :coins] - (get tax from 0))
                  (update-in [:holdings to :coins] - (get tax to 0))
                  (update :treasury + (reduce + (vals tax)))
                  (update :offers dissoc offer-id))))))

(defmethod w/apply-event :village/offer-declined [world {:keys [data]}]
  (update-in world [:state :offers] dissoc (:offer-id data)))

(defmethod w/apply-event :village/offer-expired [world {:keys [data]}]
  (update-in world [:state :offers] dissoc (:offer-id data)))

(defmethod w/apply-event :village/imported [world {:keys [actor data]}]
  (-> world
      (update-in [:state :holdings actor :grain] + (:qty data))
      (update-in [:state :holdings actor :coins] - (* import-price (:qty data)))))

(defmethod w/apply-event :village/tax-set [world {:keys [data]}]
  (assoc-in world [:state :tax-percent] (:percent data)))

(defmethod w/apply-event :village/granted [world {:keys [data]}]
  (-> world
      (update-in [:state :treasury] - (:coins data))
      (update-in [:state :holdings (:to data) :coins] + (:coins data))))

(defmethod w/apply-event :village/caravan [world {:keys [data]}]
  (-> world
      (update-in [:state :holdings (:to data) :coins] + (or (:coins data) 0))
      (update-in [:state :holdings (:to data) :grain] + (or (:grain data) 0))))

;; ---------------------------------------------------------------------------
;; Narration

(defn- who [names id] (get names id id))

(defn- goods [{:keys [coins grain]}]
  (let [parts (cond-> [] (pos? (or coins 0)) (conj (str coins " coins")) (pos? (or grain 0)) (conj (str grain " grain")))]
    (if (seq parts) (str/join " and " parts) "nothing")))

(defmethod narrate/describe :village/weather [{:keys [data]} _]
  (format "Weather for %s: %s, %.0f-%.0f°C, %.1f mm rain." (:date data) (:desc data)
          (double (:tmin data)) (double (:tmax data)) (double (:rain-mm data))))
(defmethod narrate/describe :village/settled [{:keys [actor data]} n]
  (str (who n actor) " settles in with " (goods (:holdings data)) "."))
(defmethod narrate/describe :village/crops-grew [{:keys [actor data]} n]
  (str (who n actor) "'s crops grew by " (:delta data) " (now " (:growth data) "/" ready-growth ")."))
(defmethod narrate/describe :village/ate [{:keys [actor]} n] (str (who n actor) " ate one grain."))
(defmethod narrate/describe :village/hungry [{:keys [actor data]} n]
  (str (who n actor) " has no grain and goes hungry (hunger " (:hunger data) "/" starve-at ")."))
(defmethod narrate/describe :village/planted [{:keys [actor data]} n]
  (str (who n actor) " planted " (:grain data) " grain."))
(defmethod narrate/describe :village/harvested [{:keys [actor data]} n]
  (str (who n actor) " harvested " (:yield data) " grain."))
(defmethod narrate/describe :village/offered [{:keys [actor data]} n]
  (str (who n actor) " offers " (who n (:to data)) " " (goods (:give data)) " in exchange for "
       (goods (:want data)) " (" (:offer-id data) ")."))
(defmethod narrate/describe :village/traded [{:keys [data]} n]
  (str (who n (:to data)) " accepted " (:offer-id data) ": " (who n (:from data)) " gave " (goods (:give data))
       " for " (goods (:want data)) (when (some pos? (vals (:tax data))) (str " (tax " (pr-str (:tax data)) ")")) "."))
(defmethod narrate/describe :village/offer-declined [{:keys [actor data]} n]
  (str (who n actor) " declined " (:offer-id data) "."))
(defmethod narrate/describe :village/offer-expired [{:keys [data]} _]
  (str (:offer-id data) " expired."))
(defmethod narrate/describe :village/imported [{:keys [actor data]} n]
  (str (who n actor) " imported " (:qty data) " grain for " (* import-price (:qty data)) " coins."))
(defmethod narrate/describe :village/tax-set [{:keys [actor data]} n]
  (str (who n actor) " set the trade tax to " (:percent data) "%."))
(defmethod narrate/describe :village/granted [{:keys [actor data]} n]
  (str (who n actor) " granted " (:coins data) " coins from the treasury to " (who n (:to data)) "."))
(defmethod narrate/describe :village/caravan [{:keys [data]} n]
  (str "A caravan arrives and gives " (who n (:to data)) " " (goods data) "."
       (when-let [r (:reason data)] (str " " r))))
(defmethod narrate/describe :village/letter-sent [{:keys [actor]} n]
  (str (who n actor) "'s letter has been sent to the outside world."))

;; ---------------------------------------------------------------------------
;; Actions

(def ^:private goods-args
  [[:give-coins {:optional true} [:int {:min 0}]]
   [:give-grain {:optional true} [:int {:min 0}]]
   [:want-coins {:optional true} [:int {:min 0}]]
   [:want-grain {:optional true} [:int {:min 0}]]])

(def actions
  [{:name :plant
    :roles #{:farmer}
    :doc (str "Plant grain from your stores in your field. It is ready to harvest at growth " ready-growth
              " and yields about 3x what you planted. Growth depends on the weather: mild temperatures (14-28°C) and some rain (1-20 mm) help; floods (>30 mm) hurt.")
    :args [:map [:grain [:int {:min 1 :max 30}]]]
    :valid? (fn [world actor {:keys [grain]}]
              (or (require-at world actor "fields" "plant")
                  (cond (pos? (get-in world [:state :fields actor :planted] 0)) "your field is already planted"
                        (> grain (hold world actor :grain)) (str "you only have " (hold world actor :grain) " grain"))))
    :effect (fn [_ actor {:keys [grain]}]
              [{:type :village/planted :data {:grain grain} :visible-to #{actor}}])}

   {:name :harvest
    :roles #{:farmer}
    :doc "Harvest your field once it has reached full growth."
    :valid? (fn [world actor _]
              (let [{:keys [planted growth]} (get-in world [:state :fields actor])]
                (or (require-at world actor "fields" "harvest")
                    (cond (not (pos? (or planted 0))) "nothing is planted"
                          (< growth ready-growth) (str "the crop is not ready (growth " growth "/" ready-growth ")")))))
    :effect (fn [world actor _]
              (let [planted (get-in world [:state :fields actor :planted])
                    yield (+ (* 3 planted) (rng/roll world [(:round world) actor :harvest] (inc planted)))]
                [{:type :village/harvested :data {:yield yield} :visible-to #{actor}}]))}

   {:name :offer
    :doc "Privately offer a trade to another villager in the same place (by id): give-* is what you hand over, want-* is what you get in return. Only the two of you see it, and it can only be accepted while you are together. They can accept or decline; offers expire after 3 days. A trade tax on coins received goes to the treasury."
    :args (into [:map [:to :string]] goods-args)
    :valid? (fn [world actor {:keys [to give-coins give-grain want-coins want-grain]}]
              (cond (= to actor) "you cannot trade with yourself"
                    (not (contains? (villager-ids world) to)) (str "no villager with id " to)
                    (not (space/together? world actor to)) (str to " is not here")
                    (every? #(zero? (or % 0)) [give-coins give-grain want-coins want-grain]) "the offer is empty"
                    (not (can-pay? world actor {:coins give-coins :grain give-grain})) "you do not have what you are offering"))
    :effect (fn [world actor {:keys [to give-coins give-grain want-coins want-grain]}]
              [{:type :village/offered :visible-to #{actor to}
                :data {:offer-id (str "offer-" (inc (:seq world))) :to to
                       :give {:coins (or give-coins 0) :grain (or give-grain 0)}
                       :want {:coins (or want-coins 0) :grain (or want-grain 0)}}}])}

   {:name :accept
    :doc "Accept a trade offer made to you."
    :args [:map [:offer-id :string]]
    :valid? (fn [world actor {:keys [offer-id]}]
              (let [{:keys [from to give want] :as o} (get-in world [:state :offers offer-id])]
                (cond (nil? o) (str "no open offer " offer-id)
                      (not= to actor) "that offer is not addressed to you"
                      (not (contains? (villager-ids world) from)) "the offerer has left the village"
                      (not (space/together? world actor from)) (str from " is not here; you must be together to trade")
                      (not (can-pay? world from give)) "the offerer no longer has the goods"
                      (not (can-pay? world actor want)) "you do not have what they want")))
    :effect (fn [world _ {:keys [offer-id]}]
              (let [{:keys [from to give want]} (get-in world [:state :offers offer-id])]
                [{:type :village/traded :visible-to #{from to}
                  :data {:offer-id offer-id :from from :to to :give give :want want
                         :tax {from (tax-on world (:coins want)) to (tax-on world (:coins give))}}}]))}

   {:name :decline
    :kind :free
    :doc "Decline a trade offer made to you."
    :args [:map [:offer-id :string]]
    :valid? (fn [world actor {:keys [offer-id]}]
              (when-not (= actor (get-in world [:state :offers offer-id :to])) (str "no open offer " offer-id " to you")))
    :effect (fn [world _ {:keys [offer-id]}]
              (let [{:keys [from to]} (get-in world [:state :offers offer-id])]
                [{:type :village/offer-declined :visible-to #{from to} :data {:offer-id offer-id}}]))}

   {:name :import-grain
    :roles #{:merchant}
    :doc (str "Buy grain wholesale in Lisbon at " import-price " coins each.")
    :args [:map [:qty [:int {:min 1 :max 20}]]]
    :valid? (fn [world actor {:keys [qty]}]
              (or (require-at world actor "lisbon" "buy wholesale grain")
                  (when (> (* qty import-price) (hold world actor :coins)) "you cannot afford that")))
    :effect (fn [_ actor {:keys [qty]}]
              [{:type :village/imported :data {:qty qty} :visible-to #{actor}}])}

   {:name :set-tax
    :roles #{:mayor}
    :doc "Set the trade tax (percent of coins received in each trade, paid into the treasury). Done at the town hall in the square."
    :args [:map [:percent [:int {:min 0 :max 30}]]]
    :valid? (fn [world actor _] (require-at world actor "square" "set the tax"))
    :effect (fn [_ _ {:keys [percent]}]
              [{:type :village/tax-set :data {:percent percent} :visible-to :all}])}

   {:name :grant
    :roles #{:mayor}
    :doc "Give coins from the treasury to a villager. Done at the town hall in the square; grants are public."
    :args [:map [:to :string] [:coins [:int {:min 1}]]]
    :valid? (fn [world actor {:keys [to coins]}]
              (or (require-at world actor "square" "make grants")
                  (cond (not (contains? (villager-ids world) to)) (str "no villager with id " to)
                        (> coins (get-in world [:state :treasury])) "the treasury cannot afford that")))
    :effect (fn [_ _ {:keys [to coins]}]
              [{:type :village/granted :data {:to to :coins coins} :visible-to :all}])}

   {:name :check-forecast
    :kind :query
    :doc "Check the weather forecast for the next three days."
    :bridge :open-meteo/daily
    :bridge-args (fn [world _ _]
                   (let [{:keys [lat lon]} (get-in world [:state :place])
                         r (:round world)]
                     {:lat lat :lon lon :start (date-for-round world (inc r)) :end (date-for-round world (+ r 3))}))}

   {:name :send-letter
    :kind :act
    :roles #{:mayor}
    :doc "Send a letter to the outside world (for example, to the regional governor) from the town hall in the square."
    :args [:map [:text [:string {:min 1 :max 2000}]]]
    :valid? (fn [world actor _] (require-at world actor "square" "send letters"))
    :bridge :outbox/letter
    :bridge-args (fn [world actor {:keys [text]}]
                   {:from (:name (w/agent world actor)) :date (get-in world [:state :date]) :text text})
    :on-result (fn [_ actor _ _]
                 [{:type :village/letter-sent :actor actor :visible-to #{actor}}])}

   {:name :caravan
    :roles #{:gm}
    :doc "A trading caravan passes through and gives a villager goods. Use sparingly."
    :args [:map [:to :string] [:coins {:optional true} [:int {:min 0 :max 30}]]
           [:grain {:optional true} [:int {:min 0 :max 15}]] [:reason {:optional true} :string]]
    :valid? (fn [world _ {:keys [to]}]
              (when-not (contains? (villager-ids world) to) (str "no villager with id " to)))
    :effect (fn [_ _ data]
              [{:type :village/caravan :data data :visible-to :all}])}])

;; ---------------------------------------------------------------------------
;; Views

(defn observe
  "What one villager can see: public village facts and their own affairs."
  [world id]
  (let [s (:state world)
        role (:role (w/agent world id))]
    {:date (:date s)
     :weather-today (:weather s)
     :village {:place (get-in s [:place :name])
               :tax-percent (:tax-percent s)
               :treasury (:treasury s)
               :residents (mapv #(select-keys % [:id :name :role]) (villagers world))}
     :you (cond-> {:holdings (get-in s [:holdings id])
                   :hunger (get-in s [:hunger id] 0)}
            (= :farmer role) (assoc :field (get-in s [:fields id])))
     :open-offers (vec (for [[oid o] (:offers s)
                             :when (#{(:from o) (:to o)} id)]
                         (assoc (select-keys o [:from :to :give :want :round]) :offer-id oid)))}))

;; ---------------------------------------------------------------------------
;; Roles

(def rules
  (str "Village rules: each round is one day. Everyone eats 1 grain per day; with no grain you go hungry, and after "
       starve-at " hungry days in a row you leave the village. Holdings are private. Trade happens through private offers between people in the same place. "
       "Moving to a neighbouring place uses your action for the day; you only hear what is said where you are."))

(def roles
  {:farmer {:brief (str "You are a farmer. You grow grain in the fields: plant it, wait for it to grow, harvest it, and trade the surplus. " rules)
            :spawnable? true
            :start-location "fields"
            :on-spawn (fn [_ id] [{:type :village/settled :actor id :visible-to #{id}
                                   :data {:holdings {:coins 10 :grain 8} :field {:planted 0 :growth 0}}}])}
   :merchant {:brief (str "You are the village merchant. You buy grain wholesale in Lisbon (two days' travel from the square) at "
                          import-price " coins each and trade it with villagers for profit, usually at the market. You depend on the village thriving. " rules)
              :spawnable? true
              :start-location "market"
              :on-spawn (fn [_ id] [{:type :village/settled :actor id :visible-to #{id}
                                     :data {:holdings {:coins 60 :grain 4}}}])}
   :mayor {:brief (str "You are the mayor. From the town hall in the square you set the trade tax, manage the public treasury, make grants, and write to the outside world. Keep the village alive and growing. " rules)
           :start-location "square"
           :on-spawn (fn [_ id] [{:type :village/settled :actor id :visible-to #{id}
                                  :data {:holdings {:coins 20 :grain 6}}}])}
   :gm {:brief "This is a small farming village near Lisbon. The engine runs the economy and the weather. Respond to villagers' requests sensibly (for example, invite a newcomer if the village really needs one), and rarely help a starving villager with a caravan. You can close or reopen routes for events like storms, but only rarely. Do not intervene otherwise."}})

(defn init [{:keys [start-date]}]
  {:state {:place {:name "Sintra, near Lisbon" :lat 38.80 :lon -9.38}
           :start-date (or start-date "2025-04-01")
           :date nil
           :weather nil
           :tax-percent 0
           :treasury 30
           :holdings {}
           :fields {}
           :hunger {}
           :offers {}}
   :space {:locations [{:id "square" :name "Village square" :desc "The heart of the village, with the town hall."}
                       {:id "market" :name "Market" :desc "Stalls and Clara's shop."}
                       {:id "fields" :name "Fields" :desc "The village farmland, just outside the houses."}
                       {:id "lisbon" :name "Lisbon" :desc "The city, where grain can be bought wholesale."}]
           :routes [["square" "market" 1] ["square" "fields" 1] ["market" "fields" 1] ["square" "lisbon" 2]]}
   :agents [{:name "Ana" :role :farmer :persona "Hardworking and careful, she worries about the weather."}
            {:name "Bruno" :role :farmer :persona "Optimistic and a little reckless, he likes to bargain."}
            {:name "Clara" :role :merchant :persona "Shrewd but fair; she remembers who pays their debts."}
            {:name "Duarte" :role :mayor :persona "Earnest and talkative, keen to grow the village."}]})

;; ---------------------------------------------------------------------------
;; Mock policy: lets the engine run with no model calls (opts {:llm :mock}).

(defn- affordable-offer [world id]
  (->> (vals (get-in world [:state :offers]))
       (filter #(and (= id (:to %)) (space/together? world id (:from %))
                     (can-pay? world id (:want %)) (can-pay? world (:from %) (:give %))))
       (sort-by :offer-id)
       first))

(defn- go-toward [world id dest]
  (when-let [step (space/next-step world (space/location-of world id) dest)]
    {:tool :move :args {:to step}}))

(defn mock-policy [{:keys [world agent-id role used]}]
  (let [used (set used)
        round (:round world)
        here (space/location-of world agent-id)
        grain (hold world agent-id :grain)
        coins (hold world agent-id :coins)
        field (get-in world [:state :fields agent-id])
        offer (affordable-offer world agent-id)
        merchant (:id (first (filter #(= :merchant (:role %)) (villagers world))))
        merchant-at (some->> merchant (space/location-of world))
        hungry (first (filter #(pos? (get-in world [:state :hunger (:id %)] 0)) (villagers world)))]
    (case role
      :farmer
      (cond (and field (pos? (:planted field)) (>= (:growth field) ready-growth))
            (if (= here "fields") {:tool :harvest} (go-toward world agent-id "fields"))
            offer {:tool :accept :args {:offer-id (:offer-id offer)}}
            (and field (zero? (:planted field)) (>= grain 4))
            (if (= here "fields") {:tool :plant :args {:grain (- grain 2)}} (go-toward world agent-id "fields"))
            (and (< grain 2) (#{"market" "square" "fields"} merchant-at) (>= coins 5))
            (cond (not (space/together? world agent-id merchant)) (go-toward world agent-id merchant-at)
                  (some #(= agent-id (:from %)) (vals (get-in world [:state :offers]))) {:tool :pass}
                  :else {:tool :offer :args {:to merchant :give-coins 5 :want-grain 1}})
            :else {:tool :pass})

      :merchant
      (cond offer {:tool :accept :args {:offer-id (:offer-id offer)}}
            (and (< grain 4) (>= coins (* 3 import-price)))
            (if (= here "lisbon") {:tool :import-grain :args {:qty 4}} (go-toward world agent-id "lisbon"))
            (not= here "market") (go-toward world agent-id "market")
            (zero? (mod round 3)) {:tool :say :args {:text "Fresh grain at my stall, fair prices!"}}
            :else {:tool :pass})

      :mayor
      (cond (and (= round 1) (not (used "check-forecast"))) {:tool :check-forecast}
            (= round 1) {:tool :say :args {:text "Welcome, everyone. The forecast is in; plan your planting."}}
            (= round 2) {:tool :set-tax :args {:percent 10}}
            (= round 3) {:tool :send-letter :args {:text "To the governor: our village is small but growing."}}
            (and (= round 4) (not (used "request-gm"))) {:tool :request-gm :args {:text "We could use another farmer."}}
            (and hungry (>= (get-in world [:state :treasury]) 5)) {:tool :grant :args {:to (:id hungry) :coins 5}}
            :else {:tool :pass})

      :gm
      (cond (and (seq (get-in world [:gm :inbox])) (not (used "spawn-agent")) (< (count (villagers world)) 5))
            {:tool :spawn-agent :args {:role "farmer" :name "Eva" :persona "A young farmer from the hills."}}
            :else {:tool :done})

      {:tool :pass})))

;; ---------------------------------------------------------------------------

(def scenario
  {:name :village
   :doc "A small farming village with real weather, private holdings and trade."
   :init init
   :roles roles
   :actions actions
   :bridges {:open-meteo/daily {:doc "Historical daily weather (Open-Meteo archive)"
                                :call open-meteo-daily :timeout-ms 20000}
             :outbox/letter {:doc "Append a letter to the run's outbox" :call write-letter}}
   :systems [weather-system growth-system upkeep-system offers-system]
   :observe observe
   :schedule :round-robin
   :gm-triggers [(fn [world]
                   (when-let [a (first (filter #(= 2 (get-in world [:state :hunger (:id %)])) (villagers world)))]
                     (str (:name a) " has gone hungry for 2 days")))]
   :limits {:max-agents 8}
   :mock-policy mock-policy
   :offline-stubs {:open-meteo/daily synthetic-daily}})
