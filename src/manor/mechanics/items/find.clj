(ns manor.mechanics.items.find
  "Finding: local facts first, otherwise a narrow oracle rates the odds.
  Its events are reduced and narrated in manor.mechanics.items."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [malli.json-schema :as mjs]
            [manor.actions :as a]
            [manor.mechanics.adjudication :as adjudication]
            [manor.mechanics.items :as items]
            [manor.mechanics.items.judge :as item-judge]
            [manor.mechanics.physical :as physical]
            [manor.mechanics.space :as space]
            [manor.prompt :as prompt]
            [manor.prompts :as prompts]
            [manor.rng :as rng]
            [manor.world :as w]))

(def finder-schema
  [:map
   [:person {:optional true} :boolean]
   [:existing {:optional true} :string]
   [:likelihood {:optional true} [:enum "certain" "likely" "even" "unlikely" "impossible"]]
   [:found {:optional true} [:map
                             [:name [:string {:min 1 :max items/max-name}]]
                             [:desc [:string {:min 1 :max items/max-desc}]]
                             [:aspects {:optional true} [:map-of {:max items/max-aspects} :keyword :string]]
                             [:consumable :boolean]
                             [:portable {:optional true} :boolean]
                             [:from {:optional true} :string]]]
   [:lacking {:optional true} [:string {:min 1 :max items/max-aspect :json-schema/maxLength items/advised-aspect}]]])

(defn finder-instructions
  "The finder's system message."
  [world]
  (prompts/render world "judges/finder.md" {:advised-aspect items/advised-aspect}))

(defn- bare
  "`what` in lower case without a leading article: \"a dripping tin\" -> \"dripping tin\"."
  [what]
  (str/replace (str/trim (str/lower-case what)) #"^(a|an|the|some)\s+" ""))

(defn- name-match? [what item-name]
  (str/includes? (str/lower-case item-name) (bare what)))

(defn- find-hit
  "Identify only things here, including things carried by people here."
  [world actor it]
  (let [outer (when it (items/outermost world it))
        holder (:holder outer)
        place (space/location-of world actor)]
    (when (and it (or (= place (:place outer))
                      (and holder (space/together? world actor holder))))
      (cond-> (select-keys it [:id :name :in]) holder (assoc :holder holder)))))

(defn- local-find [world actor what]
  (let [place (space/location-of world actor)
        stores (items/stores-for world place)
        fate-places (into #{place} (mapcat #(cons (:place %) (get-in % [:store :serves])) stores))]
    (or (when-let [hit (first (keep #(when (name-match? what (:name %)) (find-hit world actor %)) (items/all world)))]
          {:hit hit})
        (when-let [[id fate] (first (filter (fn [[_ fate]] (and (fate-places (:place fate)) (name-match? what (:name fate))))
                                            (sort-by key (:items-gone world))))]
          {:gone (assoc fate :id id)}))))

(defn- finder-candidates
  "Things the request might mean, id and name only: everything here
  (including closed containers' contents and what people here carry), then
  the 60 most recently touched anywhere."
  ([world] (finder-candidates world nil))
  ([world actor]
   (let [movable (remove #(or (:fixed? %) (items/store? %)) (items/all world))
         here (when actor (filter #(find-hit world actor %) movable))
         recent (take 60 (sort-by (juxt #(- (or (:touched %) 0)) :id) movable))]
     (mapv #(select-keys % [:id :name]) (distinct (concat here recent))))))

(def ^:private history-types
  #{:item/added :item/taken :item/put-down :item/put-in :item/work-done :person/acted-on
    :items/stewarded :item/found :item/not-found})

(defn- event-place
  "Where an item event happened: the place in its data, or for things put in
  or added to a container, where that container is now."
  [world {:keys [type data]}]
  (let [container-place #(some->> % (items/item world) (items/outermost world) :place)]
    (case type
      :item/taken (:from-place data)
      :item/put-in (container-place (:into data))
      :item/added (or (get-in data [:item :place]) (container-place (get-in data [:item :in])))
      (:place data))))

(defn place-history
  "At most `n` narrated lines of what was done to things at `place` after the
  world began, whoever saw it: evidence of what is really here."
  [world events place n]
  (->> events
       (filter #(and (history-types (:type %)) (pos? (or (:round %) 0)) (= place (event-place world %))))
       (take-last n)
       (prompt/narrated-lines world)
       (take-last n)
       vec))

(defn- finder-check [world actor place candidates response]
  (adjudication/answer
   "finder" response
   (fn [raw]
     (let [{:keys [args error]} (a/decode-args {:args finder-schema} raw)
           existing (:existing args)
           found (when (:found args) (first (item-judge/made-entries [(:found args)])))
           stores (items/stores-for world place)]
       (cond
         error {:error error}
         (:person args) {:person true}
         existing (if (some #(= existing (:id %)) candidates)
                    (if-let [hit (find-hit world actor (items/item world existing))]
                      {:hit hit}
                      {:existing existing})
                    {:error "existing must name one of the listed candidates"})
         :else
         (if-let [problem (or (when-not (and (:likelihood args) found) "give existing, or likelihood and found")
                              (when (or (str/blank? (:name found)) (str/blank? (:desc found)))
                                "name and desc must not be blank")
                              (when (and (contains? args :lacking) (str/blank? (:lacking args)))
                                "lacking must not be blank when supplied")
                              (when (and (:lacking args) (not (:from found)))
                                "lacking needs found.from naming a store in scope")
                              (item-judge/made-problem [] stores [] [found]))]
           {:error problem}
           (assoc args :found found)))))))

(def history-lines
  "How many narrated lines the finder gets of the searcher's own recent view,
  and of the place's history."
  20)

(defn finder-request
  "No call for local item names or fates. Otherwise the searcher's own context
  and recent lines, the place's history, visible items here, serving stores and
  identity-only candidates reach the oracle. A searcher who can't see
  (manor.mechanics.physical) is marked so under :sight."
  ([world actor args] (finder-request world actor args []))
  ([world actor {:keys [what]} events]
   (if-let [result (local-find world actor what)]
     {:result result}
     (let [place (space/location-of world actor)
           asker (w/agent world actor)
           carrying (items/by-name (items/held-by world actor))
           here (items/by-name (concat (items/visible-at world place)
                                      (mapcat #(items/held-by world %)
                                              (sort (disj (space/present world place) actor)))))
           candidates (finder-candidates world actor)]
       {:messages [{:role "system" :content (finder-instructions world)}
                   {:role "user" :content (json/write-value-as-string
                                           {:searcher (cond-> {:name (:name asker) :nature (:persona asker)
                                                               :carrying (mapv #(select-keys % [:id :name]) carrying)
                                                               :notes (mapv #(select-keys % [:round :text]) (take-last 15 (:notes asker)))
                                                               :recent (prompt/recent-lines world events actor history-lines)}
                                                      (not (physical/can? world actor :sight))
                                                      (assoc :sight (str "can't see"
                                                                         (when-let [c (:cause (physical/entry world actor :sight))] (str " (" c ")"))
                                                                         ": searching by touch, only within reach")))
                                            :place (select-keys (space/location world place) [:id :name :desc :aspects])
                                            :history (place-history world events place history-lines)
                                            :here (mapv #(select-keys % [:id :name]) here)
                                            :stores (mapv #(assoc (items/store-view place %) :keeps (get-in % [:store :keeps]))
                                                          (items/stores-for world place))
                                            :what what :candidates candidates} item-judge/pretty)}]
        :tools [{:type "function" :function {:name "finder" :description (prompts/render world "judges/tools/finder.md" {})
                                             :parameters (mjs/transform finder-schema)}}]
        :ctx {:world world :place place :what what}
        :check #(finder-check world actor place candidates %)}))))

(defn mock-finder [{:keys [world place what]}]
  (if-let [existing (first (filter #(name-match? what (:name %)) (finder-candidates world)))]
    {:tool :finder :args {:existing (:id existing)}}
    (let [store (first (items/stores-for world place))
          recorded (get-in store [:aspects :lacking])]
      {:tool :finder
       :args (cond-> {:likelihood "certain"
                      :found (cond-> {:name what :desc (str "A plain " what ".") :consumable false
                                      :portable (boolean store)}
                               store (assoc :from (:id store)))}
               (and store (not (str/includes? (str recorded) what)))
               (assoc :lacking (str/join "; " (remove str/blank? [recorded what]))))})))

(def adjudicators {:items/find {:request finder-request :mock mock-finder :failure-reply "you don't find it"}})

(defn- room-for-lacking? [aspects]
  (or (contains? aspects :lacking) (< (count aspects) items/max-aspects)))

(defn find-events
  "Record oracle odds, seeded roll and decided outcome, never re-rolled by
  reducers. Direct item/fate replies and requests for a person have no
  world effects."
  [world actor {:keys [what]} {:keys [hit gone person existing found likelihood lacking]}]
  (cond
    (or hit gone person) nil
    existing [{:type :item/not-found :actor actor :visible-to (space/here world actor)
               :data {:what what :place (space/location-of world actor) :existing existing :outcome :not-found}}]
    :else
    (let [place (space/location-of world actor)
          seq (inc (:seq world))
          roll (rng/roll world [:find seq] 100)
          yes? (< roll ({"certain" 100 "likely" 75 "even" 50 "unlikely" 25 "impossible" 0} likelihood))
          base {:what what :place place :likelihood likelihood :roll roll :roll-key [:find seq] :outcome (if yes? :found :not-found)}
          store (when (:from found) (items/item world (:from found)))
          source (when store (:id store))]
      [(if yes?
         {:type :item/found :actor actor :visible-to (space/here world actor)
          :data (assoc base :from source
                       :item (items/new-item world
                                             (cond-> (assoc (select-keys found [:name :desc :aspects]) :consumable? (:consumable found))
                                               source (assoc :drawn-from source)
                                               (not= false (:portable found)) (assoc :holder actor)
                                               (= false (:portable found)) (assoc :place place))))}
         (let [set? (and lacking store (room-for-lacking? (:aspects store)))]
           {:type :item/not-found :actor actor :visible-to (space/here world actor)
            :data (cond-> (assoc base :from source)
                    set? (assoc :path [:items source] :aspects (assoc (:aspects store) :lacking lacking)))}))])))

(defn find-reply [world _ _ {:keys [hit gone person events]}]
  (cond
    person "`find` looks for things, not people: to look for someone, use `find-person`."
    hit (str (if (:holder hit)
               (str (:name (w/agent world (:holder hit))) " has the " (:name hit))
               (str "The " (:name hit) " is here"))
             " (" (:id hit) ")"
             (when (:in hit) (str ", in the " (:name (items/item world (:in hit))) " (" (:in hit) ")")) ".")
    gone (str "The " (:name gone) " was " (:fate gone) ".")
    :else (let [event (last events)
                it (get-in event [:data :item])]
            (if it
              (str "You find " (:name it) " (" (:id it) ")"
                   (if (:holder it) "; you now carry it." "; it lies here."))
              "You don't find it."))))
