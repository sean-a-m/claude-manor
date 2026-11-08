(ns manor.mechanics.works.judge
  "The works judge's planning call (docs/fixtures-design.md 5.2): what a
  `plan-work` request means as a work plan. One checked narrow call, built
  like the trade judge: it sees the setting's period and money guidance, the
  planner (name, position), the place (name, desc, aspects, the things there
  by name), the target in full, the destination's name, the household's
  positions, and the request as data. It answers {refused} or a plan. Code
  checks only the answer's structure: amounts, lengths, an item for install,
  a fate for remove, and exactly `max-phases` phases of `phase-minutes`."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [malli.json-schema :as mjs]
            [manor.actions :as a]
            [manor.mechanics.adjudication :as adjudication]
            [manor.mechanics.items :as items]
            [manor.mechanics.money :as money]
            [manor.mechanics.space :as space]
            [manor.prompts :as prompts]
            [manor.world :as w]))

(def max-request 300)
(def max-summary 300)
(def max-who 300)
(def max-doing 200)
;; For now a work has exactly one phase of two hours (decision 7).
(def max-phases 1)
(def phase-minutes 120)
;; The most a work may be estimated at, in whole authored units (£500).
(def max-estimate-authored 500)

(defn max-estimate
  "The most an estimate may be, in the setting's smallest unit."
  [world]
  (money/authored->units (money/spec world) max-estimate-authored))

(defn- text [n] [:string {:min 1 :max n}])

(def item-schema
  "The thing an install makes, as planned."
  [:map
   [:name (text items/max-name)]
   [:desc (text items/max-desc)]
   ;; value lengths are checked by items/aspects-problem
   [:aspects {:optional true} [:map-of :keyword :string]]])

(def answer-schema
  [:map
   [:refused {:optional true} (text 300)]
   [:summary {:optional true} (text max-summary)]
   [:who {:optional true} (text max-who)]
   [:item {:optional true} item-schema]
   [:fate {:optional true} (text items/max-aspect)]
   [:estimate {:optional true} [:int {:min 0}]]
   [:materials {:optional true} [:int {:min 0}]]
   [:phases {:optional true} [:vector [:map [:doing (text max-doing)] [:minutes :int]]]]])

(defn instructions [world]
  (prompts/render world "judges/works.md"
                  {:phase-minutes phase-minutes :max-phases max-phases
                   :max-estimate (money/display (money/spec world) (max-estimate world))
                   :money (prompts/render world "money-pricing.md" {})}))

;; ---------------------------------------------------------------------------
;; What the judge sees

(defn- title [world role] (get-in world [:state :positions role :title] (some-> role name)))

(defn planner
  "The planner as the judge sees them: name and position."
  [world actor]
  (let [a (w/agent world actor)]
    (cond-> {:name (:name a)}
      (title world (:role a)) (assoc :position (title world (:role a))))))

(defn place-view
  "The place: name, desc, aspects and the things lying there, by name."
  [world place]
  (let [l (space/location world place)]
    (cond-> {:name (:name l place)}
      (:desc l) (assoc :desc (:desc l))
      (seq (:aspects l)) (assoc :aspects (:aspects l))
      true (assoc :things (mapv :name (items/by-name (items/items-at world place)))))))

(defn household-positions
  "The titles of the positions the household's people hold."
  [world]
  (vec (distinct (keep #(title world (:position %))
                       (filter #(= "household" (:household %)) (vals (get-in world [:state :posts])))))))

(defn input
  "The judge's user message for `actor`'s `args`, at their place."
  [world actor {:keys [kind request target to]}]
  (let [place (space/location-of world actor)
        it (some->> target (items/item world))]
    (cond-> {:kind kind :request request
             :planner (planner world actor)
             :place (place-view world place)
             :household-positions (household-positions world)}
      it (assoc :target (select-keys it [:name :desc :aspects :fixed?]))
      to (assoc :to (:name (space/location world to) to)))))

;; ---------------------------------------------------------------------------
;; The checked answer

(defn- parsed [raw]
  (let [m (cond (map? raw) raw
                (str/blank? raw) {}
                :else (json/read-value raw json/keyword-keys-object-mapper))]
    (cond-> m (map? (:item m)) (update :item update-keys a/kebab-key))))

(defn- phases-problem [phases]
  (cond (not= max-phases (count phases)) (str "give exactly " max-phases " phase in phases")
        (some #(not= phase-minutes (:minutes %)) phases) (str "each phase takes exactly " phase-minutes " minutes")
        (some #(str/blank? (:doing %)) phases) "each phase needs doing, what the worker does"))

(defn plan-problem
  "Why the plan `p` for `kind` isn't well formed, or nil."
  [world kind {:keys [summary who item fate estimate materials phases]}]
  (cond (some str/blank? [summary who]) "a plan needs a summary and who"
        (not (and (int? estimate) (int? materials))) "a plan needs estimate and materials, whole numbers"
        (> materials estimate) "materials can't be more than the estimate, which includes them"
        (> estimate (max-estimate world)) (str "the estimate is at most " (max-estimate world))
        (and (= "install" kind) (nil? item)) "an install needs item, the thing as it will be"
        (and (= "install" kind) (some str/blank? [(:name item) (:desc item)])) "item needs a name and desc"
        (and (= "install" kind) (:aspects item) (items/aspects-problem (:aspects item)))
        (str "item: " (items/aspects-problem (:aspects item)))
        (and (= "remove" kind) (str/blank? fate)) "a remove needs fate, what becomes of the thing"
        :else (phases-problem phases)))

(defn- trimmed [v] (if (string? v) (str/trim v) v))

(defn- plan-of
  "The parts of a checked plan that `kind` uses."
  [kind args]
  (cond-> (select-keys args [:summary :who :estimate :materials])
    true (update-vals trimmed)
    true (assoc :phases (mapv #(-> % (select-keys [:doing :minutes]) (update :doing str/trim)) (:phases args)))
    (= "install" kind) (assoc :item (-> (:item args) (update :name str/trim) (update :desc str/trim)
                                        (cond-> (empty? (:aspects (:item args))) (dissoc :aspects))))
    (= "remove" kind) (assoc :fate (str/trim (:fate args)))))

(defn check-answer
  "The judge's answer for `kind`: {:refused reason}, {:plan plan}, or {:error why}."
  [world kind raw]
  (let [{:keys [args error]} (try (a/decode-args {:args answer-schema} (parsed raw))
                                  (catch Exception e {:error (str "could not parse arguments: " (ex-message e))}))]
    (cond error {:error error}
          (contains? args :refused) (if (str/blank? (:refused args))
                                      {:error "refused needs a reason"}
                                      {:refused (str/trim (:refused args))})
          :else (if-let [problem (plan-problem world kind args)]
                  {:error problem}
                  {:plan (plan-of kind args)}))))

(defn request
  "The judge's request for `actor`'s `plan-work` `args`."
  [world actor args]
  (let [in (input world actor args)]
    {:messages [{:role "system" :content (instructions world)}
                {:role "user" :content (json/write-value-as-string in)}]
     :tools [{:type "function"
              :function {:name "work_plan"
                         :description (prompts/render world "judges/tools/work-plan.md" {})
                         :parameters (mjs/transform answer-schema)}}]
     :ctx {:world world :input in}
     :check #(adjudication/answer "work_plan" % (partial check-answer world (:kind args)))}))

;; ---------------------------------------------------------------------------
;; The mock, for --mock runs and tests

(defn mock-judge
  "A stand-in judge: it plans any request as household work, for the
  request's own words, costing nothing but an install's materials (480 in
  the smallest unit, £2 in England); it refuses a request that starts with
  \"refuse\"."
  [{:keys [input]}]
  (let [{:keys [kind request target to]} input
        what (or (:name target) request)]
    {:tool :work_plan
     :args (if (str/starts-with? (str/lower-case request) "refuse")
             {:refused "That is not a work: use spend-time for it."}
             (cond-> {:summary (case kind
                                 "install" (str "Install " request ".")
                                 "move" (str "Move the " what " to the " to ".")
                                 (str "Remove the " what "."))
                      :who "A strong servant of the household; no tradesman needed."
                      :estimate (if (= "install" kind) 480 0)
                      :materials (if (= "install" kind) 480 0)
                      :phases [{:doing (str "working at it: " request) :minutes phase-minutes}]}
               (= "install" kind) (assoc :item {:name "New fitting" :desc (str "Newly put in: " request)})
               (= "remove" kind) (assoc :fate "carted away")))}))

(def adjudicators
  {:works/plan {:request (fn [world actor args _] (request world actor args)) :mock mock-judge}})
