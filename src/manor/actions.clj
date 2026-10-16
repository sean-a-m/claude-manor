(ns manor.actions
  "Actions are data. Each action becomes an LLM tool for the roles allowed
  to use it, and every call is decoded and validated in code before it can
  change anything.

  Action spec:
    {:name    :plant                        ; tool name
     :doc     \"Plant grain in your field.\"  ; tool description
     :args    [:map [:grain pos-int?]]      ; malli schema (default [:map])
     :roles   #{:farmer} | :all             ; :all = every non-GM role
     :kind    :final | :free | :query | :act
     :available? (fn [world actor]) => bool ; optional: offer the tool only when true
     :valid?  (fn [world actor args]) => nil or a reason string
     :effect  (fn [world actor args]) => [events]

     ;; :query and :act actions only
     :bridge      :open-meteo/daily
     :bridge-args (fn [world actor args]) => bridge args
     :on-result   (fn [world actor args result]) => [events]}

  Kinds:
    :final  the agent's action for the round; ends the turn
    :free   does not end the turn (notes, declining an offer)
    :query  a real-world bridge resolved during the turn, without approval;
            the result goes back to the agent (e.g. an MCP tool)
    :act    a real-world bridge with side effects; ends the turn and waits
            for operator approval, then runs at the next round boundary"
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [malli.core :as m]
            [malli.error :as me]
            [malli.json-schema :as mjs]
            [malli.transform :as mt]))

(defn kind [action] (or (:kind action) :final))

(defn ends-turn? [action] (#{:final :act} (kind action)))

(defn args-schema [action] (or (:args action) [:map]))

(defn allowed? [action role]
  (let [roles (or (:roles action) :all)]
    (if (= roles :all)
      (not= role :gm)
      (contains? roles role))))

(defn merge-actions
  "Combine action lists; later lists override earlier ones by name."
  [& action-lists]
  (vals (reduce (fn [acc a] (assoc acc (:name a) a)) (array-map) (apply concat action-lists))))

(defn for-role [actions role]
  (filter #(allowed? % role) actions))

(defn available
  "Actions whose :available? check passes for `actor` right now. Unavailable
  actions are left out of the agent's tools for this turn."
  [actions world actor]
  (filter #(if-let [f (:available? %)] (f world actor) true) actions))

(defn by-name [actions]
  (into {} (map (juxt (comp name :name) identity)) actions))

;; ---------------------------------------------------------------------------
;; Tools

(def ^:private kind-hint
  {:final "Ends your turn."
   :free  "Free: does not end your turn."
   :query "Query: returns information and does not end your turn (limited per turn)."
   :act   "Real-world action: ends your turn and needs operator approval before it happens."})

(defn ->tool [action]
  {:type "function"
   :function {:name (name (:name action))
              :description (str (:doc action) " " (kind-hint (kind action)))
              :parameters (mjs/transform (args-schema action))}})

;; ---------------------------------------------------------------------------
;; Decoding and validation

(defn- parse-json-args [s]
  (cond
    (map? s) s
    (str/blank? s) {}
    :else (json/read-value s json/keyword-keys-object-mapper)))

(defn- kebab-key
  "Models often rewrite parameter names as snake_case or camelCase."
  [k]
  (-> (name k)
      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
      (str/replace "_" "-")
      str/lower-case
      keyword))

(defn- param-names [schema]
  (let [s (m/schema schema)]
    (when (= :map (m/type s))
      (map (comp name first) (m/children s)))))

(def ^:private decoder
  (mt/transformer mt/strip-extra-keys-transformer mt/json-transformer))

(defn decode-args
  "Decode raw tool arguments (a JSON string or a map) against the action's
  schema. Keys the schema doesn't declare are dropped (models invent them,
  especially for tools without parameters). Returns {:args args} or
  {:error reason}."
  [action raw]
  (try
    (let [schema (args-schema action)
          raw-args (update-keys (parse-json-args raw) kebab-key)
          args (m/decode schema raw-args decoder)
          ignored (remove (set (keys args)) (keys raw-args))]
      (if (m/validate schema args)
        {:args args}
        {:error (str "invalid arguments: " (pr-str (me/humanize (m/explain schema args)))
                     "; expected parameters: " (str/join ", " (param-names schema))
                     (when (seq ignored) (str "; ignored unknown keys: " (str/join ", " (map name ignored)))))}))
    (catch Exception e
      {:error (str "could not parse arguments: " (ex-message e))})))

(defn check
  "Run the action's own precondition. Returns nil if allowed, else a reason."
  [action world actor args]
  (when-let [valid? (:valid? action)]
    (valid? world actor args)))

(defn effect-events
  "Events produced by a valid action, with :actor filled in."
  [action world actor args]
  (let [effect (or (:effect action) (constantly []))]
    (mapv #(cond-> % (not (contains? % :actor)) (assoc :actor actor))
          (effect world actor args))))
