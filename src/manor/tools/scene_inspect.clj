(ns manor.tools.scene-inspect
  "Read-only, omniscient operator inspectors. Never an agent-view projection."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [manor.clock :as clock]
            [manor.engine.models :as models]
            [manor.mechanics.items :as items]
            [manor.mechanics.positions :as positions]
            [manor.mechanics.space :as space]
            [manor.narrate :as narrate]
            [manor.mechanics.money :as money]
            [manor.world :as w])
  (:import (java.net URLEncoder)))

(defn encode-id [id]
  (str/replace (URLEncoder/encode (str id) "UTF-8") "+" "%20"))

(def ^:private calls-cache
  ;; Keep only the most recently inspected log, not every run ever opened.
  (atom nil))

(defn- completed-calls [file]
  (let [text (slurp file)
        end (str/last-index-of text "\n")]
    (if end
      (with-open [reader (java.io.BufferedReader. (java.io.StringReader. (subs text 0 end)))]
        (into [] (comp (remove str/blank?) (map edn/read-string)) (line-seq reader)))
      [])))

(defn read-calls
  "Read completed llm.edn lines, including on loaded runs. A concurrently
  appended, unterminated line waits for the next refresh. No log is ordinary.
  Reuse parsed calls while the file is unchanged; repeated inspector requests
  should not parse every historical prompt again. The cache holds one log."
  [run]
  (let [f (when (:dir run) (io/file (:dir run) "llm.edn"))]
    (if (and f (.exists f))
      (let [attrs (java.nio.file.Files/readAttributes
                   (.toPath f) java.nio.file.attribute.BasicFileAttributes
                   (make-array java.nio.file.LinkOption 0))
            stamp [(.getCanonicalPath f) (.fileKey attrs) (.size attrs) (.lastModifiedTime attrs)]
            cached @calls-cache]
        (if (= stamp (:stamp cached))
          (:calls cached)
          ;; Sample metadata before reading: an append during the read makes
          ;; the next request miss, rather than hiding a newly completed line.
          (let [calls (completed-calls f)]
            (reset! calls-cache {:stamp stamp :calls calls})
            calls)))
      [])))

(defn- continues? [before after]
  (let [response (:response before)
        prefix (cond-> (vec (:messages before))
                 (or (not (str/blank? (:content response))) (seq (:tool_calls response))) (conj response))
        messages (:messages after)]
    (and (= (select-keys before [:agent :round :conversation])
            (select-keys after [:agent :round :conversation]))
         (seq (:messages before))
         (> (count messages) (count prefix))
         (= prefix (vec (take (count prefix) messages))))))

(defn call-groups
  "Group continuations by actual request prefix and conversation context, in log
  order. No persisted turn id exists; separate roots remain separate groups."
  [calls]
  (reduce (fn [groups call]
            (if-let [i (last (keep-indexed (fn [i group]
                                            (when (continues? (peek group) call) i)) groups))]
              (update groups i conj call)
              (conj groups [call]))) [] calls))

(defn- call-summary [call next-call]
  (let [replies (if (contains? call :replies) (:replies call)
                    (when next-call
                      (drop (+ (count (:messages call))
                               (if (or (not (str/blank? (get-in call [:response :content])))
                                       (seq (get-in call [:response :tool_calls]))) 1 0))
                            (:messages next-call))))]
    {:model (:model call) :at (:at call) :conversation (:conversation call)
     :thoughts (:thoughts call) :text (get-in call [:response :content])
     :finish-reason (:finish-reason call)
     :requests (mapv (fn [tc]
                       {:id (:id tc) :name (get-in tc [:function :name])
                        :arguments (get-in tc [:function :arguments])
                        :replies (mapv :content (filter #(and (= "tool" (:role %))
                                                                             (= (:id tc) (:tool_call_id %))) replies))})
                     (get-in call [:response :tool_calls]))
     :other-replies (mapv :content (remove #(= "tool" (:role %)) replies))}))

(defn recently
  "Actual calls in the latest recorded round, retaining separate request roots
  and speaking turns. Outcomes are separate event evidence, never inferred from
  a tool reply of 'done' (simultaneous resolution may reject it afterwards)."
  [{:keys [world events]} calls id]
  (let [calls (filter #(and (= id (:agent %)) (number? (:round %))
                            (<= (:round %) (:round world))) calls)
        round (when (seq calls) (apply max (map :round calls)))
        groups (call-groups (filter #(= round (:round %)) calls))]
    {:round round
     :groups (mapv (fn [group] (mapv call-summary group (concat (rest group) [nil]))) groups)
     :outcomes (when round (filterv #(and (= round (:round %)) (= id (:actor %))
                                          (not= :system (:visible-to %))
                                          (not= :llm/usage (:type %))) events))
     :dialogue (->> events
                    (filter #(and (or (#{:agent/said :agent/whispered :person/muffled} (:type %))
                                      (= "conversation" (namespace (:type %))))
                                  (w/visible-to? % id)))
                    (take-last 8) vec)}))

(defn item-history
  "Existing item event evidence only; no text matching or invented history."
  [events id]
  (:history
   (reduce
    (fn [{:keys [jobs] :as acc} {:keys [type data] :as event}]
      (let [focus (get jobs (:job data))
            related? (and (#{"item" "items"} (namespace type))
                          (or (= id (:item data)) (= id (get-in data [:item :id]))
                              (= id (:into data)) (some #{id} (:items data)) (some #{id} focus)
                              (= [:items id] (:path data))
                              (some #(= id (:item %)) (concat (:changes data) (:gone data) (:absorbed data)))
                              (some #(= id (:id %)) (:made data))))]
        (cond-> acc
          (= :item/work-begun type) (assoc-in [:jobs (:id data)] (:items data))
          (= :item/work-extended type) (assoc-in [:jobs (:job data)] (:items data))
          (#{:item/work-done :item/work-ended} type) (update :jobs dissoc (:job data))
          related? (update :history conj event))))
    {:jobs {} :history []} events)))

(defn person
  "Operator data through existing world/space/position/item accessors."
  [{:keys [world] :as snap} calls id]
  (when-let [ag (w/agent world id)]
    {:agent ag :position (positions/position world id) :post (positions/post world id)
     :model (models/model-label (:opts snap) ag)
     :usage (get-in world [:usage id]) :carrying (items/by-name (items/held-by world id))
     :bedroom (get-in world [:state :bedrooms id]) :routine (get-in world [:state :routines id])
     :purse (get-in world [:state :accounts id]) :last-seen (space/last-seen world id)
     :recently (recently snap calls id)}))

(defn place [{:keys [world]} id]
  (when-let [loc (space/location world id)]
    {:location loc :people (sort (space/present world id))
     :items (items/by-name (items/items-at world id)) :stores (items/stores-for world id)
     :jobs (filterv #(= id (:place %)) (items/jobs world))
     :exits (space/exits world id)
     :gone (sort-by key (filter #(= id (:place (val %))) (:items-gone world)))}))

(defn item [{:keys [world events]} id]
  (let [it (items/item world id) gone (get-in world [:items-gone id])]
    (when (or it gone)
      {:item it :gone gone :history (item-history events id)
       :jobs (filterv #(some #{id} (:items %)) (items/jobs world))})))

(defn- label [x] (cond (nil? x) "not recorded" (keyword? x) (name x) (string? x) x :else (pr-str x)))
(defn- at [world round]
  (if (number? round)
    (str "r" round (when-let [c (:clock world)] (str " · " (clock/world-label world round))))
    "not recorded"))
(defn- duration [world rounds]
  (if (number? rounds)
    (if-let [c (:clock world)] (str (* rounds (:minutes-per-round c)) " min") (str rounds " rounds"))
    "not recorded"))
(defn- field [title value] [:div [:b (str title ": ")] (label value)])
(defn- fields [values]
  (if (seq values)
    [:dl (for [[k v] (sort-by (comp str key) values)] (list [:dt (label k)] [:dd (label v)]))]
    [:p "None recorded."]))
(defn- folded [title body] [:details [:summary title] body])
(defn- link [world kind id]
  (when id
    [:button.inspect-link {:type "button" :data-kind kind :data-id (str id)}
     (or (case kind "agent" (:name (w/agent world id)) "place" (:name (space/location world id))
               "item" (or (:name (items/item world id)) (get-in world [:items-gone id :name])) nil)
         (str id))]))
(defn- feelings-view
  "An agent's relations (manor.mechanics.relations): each person they feel
  something toward, with every entry kept, latest first."
  [world relations]
  (if (seq relations)
    [:dl (for [[id entries] (sort-by #(:name (w/agent world (key %))) relations)]
           (list [:dt (link world "agent" id)]
                 [:dd (for [{:keys [round feeling because]} (reverse entries)]
                        [:div (at world round) ": " feeling ", because " because])]))]
    [:p "None recorded."]))
(defn- events-view [world events]
  (if (seq events)
    [:ul (for [event events]
           [:li [:span.vis (str (at world (:round event)) " · ")]
            (narrate/describe event (narrate/names world))])]
    [:p "None recorded."]))

(defn- store-view [world it]
  (when (items/store? it)
    [:div.store
     (field "Keeps" (get-in it [:store :keeps]))
     (field "Stock" (get-in it [:aspects :stock]))
     (field "Lacking" (get-in it [:aspects :lacking]))
     (when (seq (get-in it [:store :serves]))
       [:div "Serves: " (for [id (get-in it [:store :serves])] (link world "place" id))])]))

(defn- item-tree
  ([world it] (item-tree world it #{}))
  ([world it seen]
   [:li (link world "item" (:id it))
    (when (:fixed? it) " · fixed")
    (when (:container it) (str " · " (label (:container it))))
    (store-view world it)
    (when (and (:container it) (not (seen (:id it))))
      [:details {:open true} [:summary "Contents · operator"]
       (if-let [children (seq (items/contents world (:id it)))]
         [:ul (for [child children] (item-tree world child (conj seen (:id it))))]
         [:p "Empty."])])]))

(defn- trees [world things]
  (if (seq things) [:ul.item-tree (for [it things] (item-tree world it))] [:p "None recorded."]))

(defn- jobs-view [world jobs]
  (if (seq jobs)
    [:ul (for [job jobs]
           [:li (link world "agent" (:actor job)) " · " (:doing job)
            [:div "Started " (at world (:started job)) " · due " (at world (:due job))]
            (for [id (:items job)] (link world "item" id))])]
    [:p "None in progress."]))

(defn- recent-view [world {:keys [round groups outcomes dialogue]}]
  [:div
   [:h3 "Requested tools and recorded replies"]
   [:p "A recorded reply of ‘done’ is not proof of final resolution. Simultaneous actions may be rejected later; see recorded outcomes below."]
   (if round
     [:div [:p (str "Latest recorded round: " (at world round))]
      (for [[i group] (map-indexed vector groups)]
        [:div.call-group [:h4 (str "Request context " (inc i))]
         (for [call group]
           [:div.model-call
            (field "Model" (:model call))
            (when (:at call) (field "Sent" (:at call)))
            (when (:conversation call) (field "Conversation" (:conversation call)))
            (when (:finish-reason call) (field "Finish" (:finish-reason call)))
            (if (seq (:requests call))
              [:ul (for [{:keys [name arguments replies]} (:requests call)]
                     [:li [:b (or name "unnamed tool")] [:pre (label arguments)]
                      (if (seq replies) (for [reply replies] [:div "Engine reply: " [:pre (label reply)]])
                          [:div "Engine reply not recorded."])])]
              [:p "No tool call returned."])
            (when (not-empty (:text call)) [:div "Returned text" [:pre (:text call)]])
            (for [reply (:other-replies call)] [:div "Engine context: " [:pre (label reply)]])
            (when (not-empty (:thoughts call))
              (folded "Thoughts · returned reasoning only" [:pre (:thoughts call)]))])])
      (folded "Recorded outcomes · events, not inferred tool success" (events-view world outcomes))]
     [:p "No model calls recorded yet. Human and inactive seats may have no calls."])
   (folded "Dialogue · said, whispered or heard" (events-view world dialogue))])

(defn- routine-view [world routine]
  (let [now (when-let [c (:clock world)] (.toString (.toLocalTime (clock/time-of c (:round world)))))
        entries (sort-by :from routine)
        current (when (and now (seq entries))
                  (or (last (filter #(and (clock/hh-mm? (:from %)) (<= (compare (:from %) now) 0)) entries))
                      (last entries)))]
    (if (seq entries)
      [:ul (for [entry entries]
             [:li {:class (when (= entry current) "routine-current")}
              (:from entry) " · " (:doing entry) " · " (link world "place" (:at entry))
              (when (= entry current) " · current")])]
      [:p "None recorded."])))

(defn- person-view [world {:keys [agent position post model usage carrying bedroom routine purse last-seen recently]}]
  (let [{:keys [id name role status controller busy travel location]} agent]
    [:article
     [:h2 (or name id)]
     (field "Position" (or (:title position) role)) (field "Standing" (:standing position))
     (field "Status" status) (field "Controller" controller) (field "Model" model)
     (field "Cost so far" (format "$%.4f" (double (or (:cost usage) 0))))
     [:h3 "Where and what"]
     [:div "Location: " (or (link world "place" location) "unplaced / in transit")]
     (if busy [:div (field "Activity" (:activity busy)) (field "Until" (at world (:until busy)))]
         [:p "No current occupation."])
     (when travel [:div "Travelling from " (link world "place" (:from travel)) " to " (link world "place" (:to travel))
                   [:div "Arrives " (at world (:arrives travel))]])
     [:h3 "Recently"] (recent-view world recently)
     [:h3 "Appearance and aspects"] (field "Appearance" (:appearance agent)) (fields (:aspects agent))
     [:h3 "Current inner state · private"] (fields (:inner agent))
     (folded "Feelings toward others · private" (feelings-view world (:relations agent)))
     (folded "Carrying" (trees world carrying))
     (folded "Post, bedroom, routine and purse"
             [:div (fields post) [:div "Bedroom: " (or (link world "place" bedroom) "none")]
              (routine-view world routine)
              (field "Purse (when recorded)" (when (some? purse) (money/display (money/spec world) purse)))])
     (folded "Notes and last seen"
             [:div [:ul (for [note (:notes agent)] [:li (at world (:round note)) " · " (:text note)])]
              [:ul (for [seen last-seen] [:li (link world "agent" (:id seen)) " · "
                                         (label (dissoc seen :id :name))])]])
     (folded "Persona, manner, temperament and circumstances"
             (fields (select-keys agent [:persona :manner :temperament :faculties :circumstances :situation :sex])))
     [:p [:a {:href (str "/agent/" (encode-id id))} "their view"] " · "
      [:a {:href (str "/panel?as=" (encode-id id))} "feed as them"]]]))

(defn- place-view [world {:keys [location people items stores jobs exits gone]}]
  [:article [:h2 (or (:name location) (:id location))]
   (fields (select-keys location [:kind :floor :domain]))
   [:p (:desc location)] (when (:detail location) (folded "Detail" [:p (:detail location)]))
   [:h3 "Aspects"] (fields (:aspects location))
   (field "Opening hours" (:hours location)) (field "Services" (:services location))
   [:h3 "Who's here"] [:div (if (seq people) (for [id people] (link world "agent" id)) "Nobody.")]
   [:h3 "Items · operator (including closed containers)"] (trees world items)
   (folded "Stores here or serving this place" (trees world stores))
   [:h3 "Jobs in progress"] (jobs-view world jobs)
   [:h3 "Open exits"] [:ul (for [{:keys [to travel]} exits]
                                 [:li (link world "place" to) " · " (duration world travel)])]
   (folded "Items used up / put away here"
           [:ul (for [[id fate] gone]
                  [:li (link world "item" id) " · " (:fate fate) " · " (at world (:round fate))])])])

(defn- item-view [world id {:keys [item gone history jobs]}]
  [:article [:h2 (or (:name item) (:name gone) id)]
   (if item
     [:div [:p (:desc item)] (fields (:aspects item))
      (when (:fixed? item) [:p "Fixed item."])
      [:div "Owner: " (if (w/agent world (:owner item)) (link world "agent" (:owner item)) (label (:owner item)))]
      [:div "Where: " (cond (:in item) (link world "item" (:in item))
                                 (:holder item) (link world "agent" (:holder item))
                                 (:place item) (link world "place" (:place item)) :else "not recorded")]
      (when-let [place (items/place-of world item)] [:div "Place: " (link world "place" place)])
      (when (:container item) (field "Container" (:container item)))
      (store-view world item)
      [:h3 "Contents · operator"] (trees world (items/contents world id))
      (field "Last touched" (at world (:touched item)))
      [:h3 "Jobs on this item"] (jobs-view world jobs)]
     [:div [:p "No longer present."] (field "Fate" (:fate gone))
      [:div "Last place: " (link world "place" (:place gone))] (field "Removed" (at world (:round gone)))])
   (folded "Item history · recorded events" (events-view world history))])

(defn fragment
  "Escapable Hiccup for a selection. Missing entities retain a useful sidebar.
  Pass calls sampled from read-calls; pure once snapshot and calls are supplied."
  [{:keys [world] :as snap} calls kind id]
  (let [extra (when-let [f (get-in snap [:scenario :scene :inspect])] (f world kind id))
        generic (case kind
                  "agent" (some->> (person snap calls id) (person-view world))
                  "place" (some->> (place snap id) (place-view world))
                  "item" (some->> (item snap id) (item-view world id))
                  nil)]
    [:div.inspector-content
     [:p.vis "Omniscient operator inspector · not the character's view"]
     generic extra
     (when-not (or generic extra)
       [:p "Selection no longer available: " (str kind) " · " (str id)])]))
