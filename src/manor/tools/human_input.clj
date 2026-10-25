(ns manor.tools.human-input
  "Human-seat forms, derived from the exact JSON schemas offered by the engine.
  Rendering only: arguments still go through the engine's decoder and checks."
  (:require [clojure.string :as str]
            [jsonista.core :as json]))

(defn- value-label [v] (if (keyword? v) (name v) (str v)))

(defn- field-kind [{:keys [type enum items]}]
  (cond
    (seq enum) :choice
    (= type "boolean") :boolean
    (#{"integer" "number"} type) :number
    (= type "string") :text
    (and (= type "array") (= "string" (:type items))) :lines
    :else :json))

(defn- field
  "One property. Optional blank fields are omitted, not sent as empty values."
  [tool k schema required?]
  (let [{:keys [enum minimum maximum minLength maxLength minItems maxItems description]} schema
        kind (field-kind schema)
        id (str "human-" tool "-" (name k))
        attrs {:id id :data-arg (name k) :data-kind (name kind) :data-required (str required?) :required required?}
        choices (if (= kind :boolean) [true false] enum)]
    [:div.human-field
     [:label {:for id} (name k) (if required? " (required)" " (optional)")]
     (case kind
       (:choice :boolean)
       [:select attrs
        [:option {:value ""} (if required? "Choose…" "Not supplied")]
        (for [v choices] [:option {:value (json/write-value-as-string v)} (value-label v)])]
       :number
       [:input (merge attrs {:type "number" :step (if (= "integer" (:type schema)) "1" "any")
                            :min minimum :max maximum})]
       :text
       [:textarea (merge attrs {:rows 2 :minlength minLength :maxlength maxLength})]
       :lines
       [:textarea (assoc attrs :rows 3 :required (and required? (pos? (or minItems 0))))]
       :json
       [:textarea (assoc attrs :rows 3 :placeholder "JSON value")])
     (when description [:div.human-help description])
     (when (= kind :number)
       [:div.human-help (str (if (= "integer" (:type schema)) "Whole number" "Number")
                            (when (some? minimum) (str "; minimum " minimum))
                            (when (some? maximum) (str "; maximum " maximum)) ".")])
     (when (or minLength maxLength)
       [:div.human-help (str "Characters: " (or minLength 0) "–" (or maxLength "unlimited") ".")])
     (when (= kind :lines)
       [:div.human-help (str "One entry per line, without quotes or brackets."
                            (when minItems (str " Minimum " minItems " entries."))
                            (when maxItems (str " Maximum " maxItems " entries.")))])
     (when (= kind :json)
       [:details [:summary "Expected JSON structure"]
        [:pre (json/write-value-as-string schema)]])]))

(defn- tool-fields [{:keys [name description parameters]} selected?]
  (let [{:keys [properties required]} parameters
        required (set (map value-label required))]
    [:fieldset.human-tool {:data-tool name :hidden (not selected?) :disabled (not selected?)}
     [:div.human-description description]
     [:fieldset.human-fields {:data-fields true}
      (if (seq properties)
        (for [[k schema] properties] (field name k schema (contains? required (value-label k))))
        [:p "No arguments needed."])]
     [:details.human-advanced
      [:summary "Advanced: JSON arguments"]
      [:label [:input {:type "checkbox" :data-raw-toggle true :onchange "humanMode(this.form)"}]
       " Use raw JSON instead of the fields"]
      [:textarea {:data-raw true :rows 4 :disabled true :placeholder "{}" :aria-label "Raw JSON arguments"}]
      [:details [:summary "Argument schema"] [:pre (json/write-value-as-string parameters)]]]]))

(defn fragment
  "The current wait, keyed by its request id so SSE refreshes can retain drafts.
  `reply` is selected first when it's offered (a conversation line)."
  [{:keys [agent request-id feedback tool-specs context]}]
  (let [tools (sort-by :name (map :function tool-specs))
        names (set (map :name tools))
        selected (if (names "reply") "reply" (:name (first tools)))]
    [:section#human-turn {:data-request-id request-id :style "outline:2px solid #fc8"}
     [:h2 "Waiting for " agent]
     (when context [:div.human-description {:style "margin-bottom:8px"} context])
     (when feedback [:div.human-feedback {:role "status"} "Last result: " feedback])
     [:form#human-action {:method "post" :action "/cmd/act" :onsubmit "return sendHuman(this)"}
      [:input {:type "hidden" :name "agent" :value agent}]
      [:input {:type "hidden" :name "request-id" :value request-id}]
      [:label {:for "human-tool"} "Action / tool"]
      [:select#human-tool {:name "action" :onchange "humanMode(this.form)"}
       (for [{:keys [name]} tools] [:option {:value name :selected (= name selected)} name])]
      (for [tool tools] (tool-fields tool (= (:name tool) selected)))
      [:div.human-error {:role "alert"}]
      [:button {:type "submit"} "Send action"]]
     [:div.human-help "Free actions and queries keep your turn open; a turn-ending action finishes it. "
      "The engine checks arguments, permissions and turn limits."]
     [:div [:a {:href (str "/agent/" (str/replace (java.net.URLEncoder/encode agent "UTF-8") "+" "%20")) :target "_blank"}
            "See their view (including visible IDs)"]]]))
