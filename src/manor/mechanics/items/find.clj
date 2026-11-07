(ns manor.mechanics.items.find
  "Finding: a narrow oracle identifies what's meant or rates the odds, and
  the engine rolls. Its events are reduced and narrated in
  manor.mechanics.items."
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

(def max-existing
  "How many existing things one `find` may identify."
  8)

(def finder-schema
  [:map
   [:person {:optional true} :boolean]
   [:existing {:optional true} [:vector {:min 1 :max max-existing :decode/json #(if (string? %) [%] %)} :string]]
   [:likelihood {:optional true} [:enum "certain" "likely" "even" "unlikely" "impossible"]]
   [:found {:optional true} [:map
                             [:name [:string {:min 1 :max items/max-name}]]
                             [:desc [:string {:min 1 :max items/max-desc}]]
                             [:aspects {:optional true} [:map-of {:max items/max-aspects} :keyword :string]]
                             [:consumable :boolean]
                             [:portable {:optional true} :boolean]
                             [:from {:optional true} :string]]]
   item-judge/taken-entry
   [:lacking {:optional true} [:string {:min 1 :max items/max-aspect :json-schema/maxLength items/advised-aspect}]]])

(defn finder-instructions
  "The finder's system message."
  [world]
  (prompts/render world "judges/finder.md" {:advised-aspect items/advised-aspect :max-existing max-existing}))

(defn- mock-match?
  "For the mock finder only: does `item-name` contain `what`, without a
  leading article?"
  [what item-name]
  (str/includes? (str/lower-case item-name)
                 (str/replace (str/trim (str/lower-case what)) #"^(a|an|the|some)\s+" "")))

(defn- find-hit
  "Identify only things here, including things carried by people here."
  [world actor it]
  (let [outer (when it (items/outermost world it))
        holder (:holder outer)
        place (space/location-of world actor)]
    (when (and it (or (= place (:place outer))
                      (and holder (space/together? world actor holder))))
      (cond-> (select-keys it [:id :name :in]) holder (assoc :holder holder)))))

(defn finder-candidates
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

(defn hosts
  "What a found thing may be taken from, as one of its aspects: the place,
  the things lying here and the stores in scope, with their aspects."
  [world place]
  (into [(assoc (select-keys (space/location world place) [:id :aspects]) :room? true)]
        (map #(select-keys % [:id :aspects]) (concat (items/visible-at world place) (items/stores-for world place)))))

(defn- taken-problem
  "Why `taken` won't do, or nil: it names one of the `hosts` and an
  aspect it has."
  [hosts {:keys [thing aspect]}]
  (if-let [host (first (filter #(= thing (:id %)) hosts))]
    (when-not (contains? (:aspects host) (a/kebab-key aspect))
      (str "taken must name an aspect " thing " has: "
           (if (seq (:aspects host)) (str/join ", " (sort (map name (keys (:aspects host))))) "it has none") "; not " aspect
           ". Use taken only to extract something already recorded in an existing aspect. "
           "Omitting taken is valid: if no recorded aspect is being extracted, omit taken and give likelihood and found as usual"))
    (str "taken must name the place or a thing or store here: " (str/join ", " (map :id hosts)) "; not " thing)))

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
         existing (let [ids (vec (distinct existing))]
                    (if-let [unknown (seq (remove (set (map :id candidates)) ids))]
                      {:error (str "existing must name only listed candidates, not " (str/join ", " unknown))}
                      (let [hits (vec (keep #(find-hit world actor (items/item world %)) ids))]
                        {:hits hits :existing (vec (remove (set (map :id hits)) ids))})))
         :else
         (if-let [problem (or (when-not (and (:likelihood args) found) "give existing, or likelihood and found")
                              (when (or (str/blank? (:name found)) (str/blank? (:desc found)))
                                "name and desc must not be blank")
                              (when (and (contains? args :lacking) (str/blank? (:lacking args)))
                                "lacking must not be blank when supplied")
                              (when (and (:lacking args) (not (:from found)))
                                "lacking needs found.from naming a store in scope")
                              (when (:taken args) (taken-problem (hosts world place) (:taken args)))
                              (item-judge/made-problem [] stores [] [found]))]
           {:error problem}
           (assoc args :found found)))))))

(def history-lines
  "How many narrated lines the finder gets of the searcher's own recent view,
  and of the place's history."
  20)

(def max-inspect
  "How many candidates one `inspect` call may look at."
  8)

(def inspect-lines
  "How many narrated lines of each inspected thing's history `inspect` gives."
  5)

(def inspect-desc
  "How much of an inspected thing's description `inspect` gives."
  200)

(def inspect-schema
  [:map [:ids [:vector {:min 1 :max max-inspect :decode/json #(if (string? %) [%] %)} :string]]])

(defn- event-items
  "The ids of the things an item event did something to."
  [{:keys [type data]}]
  (case type
    (:item/taken :item/put-down :item/put-in :item/given) [(:item data)]
    :item/added [(get-in data [:item :id])]
    :item/found [(get-in data [:item :id])]
    :item/work-done (concat (keep :item (:changes data)) (map :item (:gone data))
                            (map :item (:absorbed data)) (map :id (:made data)))
    :person/acted-on (concat (:with data) (map :item (:moved data)) (keep :item (:changes data)))
    :items/stewarded (concat (keep :item (:changes data)) (map :item (:gone data)) (map :item (:absorbed data)))
    []))

(defn item-history
  "At most `n` narrated lines of what was done to the thing `id` after the
  world began, whoever saw it."
  [world events id n]
  (->> events
       (filter #(and (pos? (or (:round %) 0)) (some #{id} (event-items %))))
       (take-last n)
       (prompt/narrated-lines world)
       vec))

(defn- where
  "Where the thing `it` is, as `actor` would put it: here (in what), carried
  by them or by someone here or elsewhere, or lying elsewhere."
  [world actor it]
  (let [place (space/location-of world actor)
        o (items/outermost world it)
        loc-name #(:name (space/location world %) %)
        within (when (not= (:id o) (:id it)) (str ", in the " (:name (items/item world (:in it)))))]
    (cond
      (= actor (:holder o)) (str "carried by you" within)
      (:holder o) (str "carried by " (:name (w/agent world (:holder o)) (:holder o))
                       (if (space/together? world actor (:holder o)) ", here"
                           (if-let [p (space/location-of world (:holder o))] (str ", elsewhere, in the " (loc-name p)) ", elsewhere"))
                       within)
      (= place (:place o)) (str "here" within)
      :else (str "elsewhere, in the " (loc-name (:place o)) within))))

(defn inspect-view
  "What `inspect` tells the finder of each listed candidate in `ids`: its
  name, the start of its description, aspects, owner, where it is now and
  its recent history, whoever saw it."
  [world events actor ids]
  (vec (for [id (distinct ids)
             :let [it (items/item world id)]]
         (cond-> {:id id :name (:name it)
                  :desc (let [d (str (:desc it))] (if (> (count d) inspect-desc) (str (subs d 0 inspect-desc) "…") d))
                  :where (where world actor it)
                  :history (item-history world events id inspect-lines)}
           (seq (:aspects it)) (assoc :aspects (:aspects it))
           (:owner it) (assoc :owner (:name (w/agent world (:owner it)) (:owner it)))))))

(defn inspect-lookup
  "The finder's `inspect` tool: checked ids from `candidates`, answered by
  `inspect-view`."
  [world events actor candidates raw]
  (let [{:keys [args error]} (a/decode-args {:args inspect-schema} raw)
        unknown (remove (set (map :id candidates)) (:ids args))]
    (cond
      error {:error error}
      (seq unknown) {:error (str "inspect only listed candidates, not " (str/join ", " unknown))}
      :else {:reply (json/write-value-as-string (inspect-view world events actor (:ids args)) item-judge/pretty)})))

(defn inspect-tool
  "The `inspect` lookup's tool, for a judge that answers with `answer` (a
  tool name)."
  [world answer]
  {:type "function" :function {:name "inspect"
                               :description (prompts/render world "judges/tools/inspect.md" {:max-inspect max-inspect :answer answer})
                               :parameters (mjs/transform inspect-schema)}})

(defn finder-request
  "The searcher's own context and recent lines, the place's history, the
  things here with their aspects, serving stores and candidates by id and
  name reach the oracle. It may first `inspect` some candidates once (see
  `inspect-view`), then answers with `finder`. A searcher who can't see
  (manor.mechanics.physical) is marked so under :sight. `config`, the
  scenario's :items, may add the searcher's order books and pending orders
  (:orders, a map of keys, see deliveries/judge-orders) and a brief to the
  instructions (:finder-brief)."
  ([world actor args] (finder-request world actor args []))
  ([world actor args events] (finder-request {} world actor args events))
  ([config world actor {:keys [what]} events]
   (let [orders (when-let [f (:orders config)] (f world actor))
         brief (when-let [f (:finder-brief config)] (f world))
         place (space/location-of world actor)
         asker (w/agent world actor)
         carrying (items/by-name (items/held-by world actor))
         here (items/by-name (concat (items/visible-at world place)
                                    (mapcat #(items/held-by world %)
                                            (sort (disj (space/present world place) actor)))))
         candidates (finder-candidates world actor)]
     {:messages [{:role "system" :content (str (finder-instructions world)
                                               (when-not (str/blank? brief) (str "\n\n" brief)))}
                 {:role "user" :content (json/write-value-as-string
                                         {:searcher (cond-> {:name (:name asker) :nature (:persona asker)
                                                             :carrying (mapv #(select-keys % [:id :name]) carrying)
                                                             :notes (mapv #(select-keys % [:round :text]) (take-last 15 (:notes asker)))
                                                             :recent (prompt/recent-lines world events actor history-lines)}
                                                    (seq orders) (merge orders)
                                                    (not (physical/can? world actor :sight))
                                                    (assoc :sight (str "can't see"
                                                                       (when-let [c (:cause (physical/entry world actor :sight))] (str " (" c ")"))
                                                                       ": searching by touch, only within reach")))
                                          :place (select-keys (space/location world place) [:id :name :desc :aspects])
                                          :history (place-history world events place history-lines)
                                          :here (mapv #(select-keys % [:id :name :aspects]) here)
                                          :stores (mapv #(items/store-view place %)
                                                        (items/stores-for world place))
                                          :what what :candidates candidates} item-judge/pretty)}]
      :tools [{:type "function" :function {:name "finder" :description (prompts/render world "judges/tools/finder.md" {})
                                           :parameters (mjs/transform finder-schema)}}
              (inspect-tool world "finder")]
      :lookups {"inspect" #(inspect-lookup world events actor candidates %)}
      :max-lookups 1
      :ctx {:world world :place place :what what}
      :check #(finder-check world actor place candidates %)})))

(defn mock-finder [{:keys [world place what]}]
  (if-let [existing (first (filter #(mock-match? what (:name %)) (finder-candidates world)))]
    {:tool :finder :args {:existing [(:id existing)]}}
    (let [store (first (items/stores-for world place))
          recorded (get-in store [:aspects :lacking])]
      {:tool :finder
       :args (cond-> {:likelihood "certain"
                      :found (cond-> {:name what :desc (str "A plain " what ".") :consumable false
                                      :portable (boolean store)}
                               store (assoc :from (:id store)))}
               (and store (not (str/includes? (str recorded) what)))
               (assoc :lacking (str/join "; " (remove str/blank? [recorded what]))))})))

(defn adjudicators
  "The finder, given the scenario's :items `config` (see `finder-request`)."
  [config]
  {:items/find {:request (partial finder-request config) :mock mock-finder :failure-reply "you don't find it"}})

(defn- room-for-lacking? [aspects]
  (or (contains? aspects :lacking) (< (count aspects) items/max-aspects)))

(defn- taken-change
  "The change to the aspect a found thing was taken from, as work's changes
  are recorded: of the place or of an item."
  [world place {:keys [thing aspect value]}]
  (let [k (a/kebab-key aspect)
        host (first (filter #(= thing (:id %)) (hosts world place)))]
    {(if (:room? host) :place :item) thing :aspect k :from (get-in host [:aspects k])
     :to (when-not (str/blank? value) value)}))

(defn find-events
  "Record oracle odds, seeded roll and decided outcome, never re-rolled by
  reducers. A thing found that was only an aspect (`taken`) changes that
  aspect as it's found. Things identified here and requests for a person
  have no world effects; things identified only elsewhere are not found."
  [world actor {:keys [what]} {:keys [hits person existing found likelihood lacking taken]}]
  (cond
    (or (seq hits) person) nil
    (seq existing) [{:type :item/not-found :actor actor :visible-to (space/here world actor)
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
          :data (cond-> (assoc base :from source
                               :item (items/new-item world
                                             (cond-> (assoc (select-keys found [:name :desc :aspects]) :consumable? (:consumable found))
                                               source (assoc :drawn-from source)
                                               (not= false (:portable found)) (assoc :holder actor)
                                               (= false (:portable found)) (assoc :place place))))
                  taken (assoc :changes [(taken-change world place taken)]))}
         (let [set? (and lacking store (room-for-lacking? (:aspects store)))]
           {:type :item/not-found :actor actor :visible-to (space/here world actor)
            :data (cond-> (assoc base :from source)
                    set? (assoc :path [:items source] :aspects (assoc (:aspects store) :lacking lacking)))}))])))

(defn- hit-reply [world hit]
  (str (if (:holder hit)
         (str (:name (w/agent world (:holder hit))) " has the " (:name hit))
         (str "The " (:name hit) " is here"))
       " (" (:id hit) ")"
       (when (:in hit) (str ", in the " (:name (items/item world (:in hit))) " (" (:in hit) ")")) "."))

(defn find-reply [world _ _ {:keys [hits person events]}]
  (cond
    person "`find` looks for things, not people: to look for someone, use `find-person`."
    (seq hits) (str/join "\n" (map #(hit-reply world %) hits))
    :else (let [event (last events)
                it (get-in event [:data :item])]
            (if it
              (str "You find " (:name it) " (" (:id it) ")"
                   (if (:holder it) "; you now carry it." "; it lies here."))
              "You don't find it."))))
