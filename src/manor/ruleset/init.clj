(ns manor.ruleset.init
  "A ruleset world from its world file: the scenario's own checks
  (`problems`), the starting state (`init`) and the setting pack's holidays
  (`add-holidays`)."
  (:require [clojure.string :as str]
            [manor.clock :as clock]
            [manor.io.worldfile :as worldfile]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.building :as building]
            [manor.mechanics.deliveries :as deliveries]
            [manor.mechanics.positions :as positions]
            [manor.mechanics.shop :as shop]
            [manor.settings :as settings]))

(defn problems
  "The scenario's own checks on a world file: the meeting place and home
  area, bedrooms, opening hours, the setting, the positions (its catalogue
  and :roles), with an :about for every position an agent holds, and where
  they work."
  [{:keys [locations agents meta] :as wf}]
  (let [by-id (into {} (map (juxt :id identity)) locations)
        table (positions/table wf {})
        pack (settings/for-worldfile wf)]
    (concat
     (building/problems wf)
     (shop/problems wf)
     (for [{:keys [name bedroom]} agents
           :when (not= :bedroom (:kind (by-id bedroom)))]
       (str "agent " (pr-str name) ": :bedroom " (pr-str bedroom)
            (if (by-id bedroom) " is not a bedroom (:kind :bedroom)" " is not a location")))
     (for [{:keys [id hours]} locations
           :when (and (some? hours)
                      (not (and (vector? hours) (= 2 (count hours)) (every? clock/hh-mm? hours))))]
       (str "location " (pr-str id) ": :hours must be two times like [\"09:00\" \"18:00\"], not " (pr-str hours)))
     (for [{:keys [name routine]} agents
           {:keys [from at doing] :as entry} routine
           :when (not (and (clock/hh-mm? from) (by-id at) (string? doing) (not (str/blank? doing))))]
       (str "agent " (pr-str name) ": :routine entry " (pr-str entry)
            " needs :from (a time like \"09:00\"), :at (a location id) and :doing (what they're doing there)"))
     (when-not (by-id (:meeting-place meta))
       [(str ":meta :meeting-place must be the id of a location, where people from elsewhere arrive, not "
             (pr-str (:meeting-place meta)))])
     (when-let [domain (:home-domain meta)]
       (when-not (some #(= domain (:domain %)) locations)
         [(str ":meta :home-domain " (pr-str domain) " is not the :domain of any location")]))
     (when-not (string? (:setting meta)) [":meta :setting must be a paragraph about the place"])
     (mapcat (fn [[id entry]] (positions/entry-problems (positions/standings pack) id entry)) (sort-by key table))
     (for [role (distinct (map :role agents)) :when (not (contains? table role))]
       (str "position " role " has no :about paragraph: add it to :roles or the catalogue"))
     (for [{:keys [id] :as loc} locations
           :when (deliveries/delivers? loc)
           :let [courier (or (:courier loc) (:courier-position pack))]
           :when (not (contains? table courier))]
       (str "location " (pr-str id) ": its courier's position " courier " is not in the positions catalogue"))
     (for [[id {:keys [workplace]}] (sort-by key table)
           place workplace
           :when (not (by-id place))]
       (str "position " id ": :workplace names unknown location " (pr-str place))))))

(defn init
  "The world file, its agents (without any whose id is in :leave-out, and
  without anything they hold), its items and authored visits (dropping
  left-out guests and removing left-out ids from :known-to), and in :state
  the positions (without forms of address if :addressed-as? is false), the
  setting, the meeting place, the home area if the world names one
  (manor.mechanics.space/home-domain), the query limit the rules quote and
  :find-everywhere? (default true). The clock takes the setting pack's
  :clock :day-label, if it has one (manor.clock/short-label).
  Everyone's post, bedroom and routine are set as they arrive (`on-spawn`)."
  [{:keys [world-file leave-out] :as opts}]
  (let [source (or world-file (throw (ex-info "no :world-file: a ruleset scenario names one in its :defaults" {})))
        {wf :world-file :as loaded} (worldfile/load-world source)]
    (when-let [ps (seq (problems wf))]
      (throw (ex-info (str "world file " source " has problems:\n- " (str/join "\n- " ps)) {:problems (vec ps)})))
    (let [left-out (set (filter (set leave-out) (map (comp b/slug :name) (:agents loaded))))
          agents (remove #(left-out (b/slug (:name %))) (:agents loaded))
          ids (set (map (comp b/slug :name) agents))
          table (positions/table wf opts)
          pack (settings/for-worldfile wf)]
      (assoc (cond-> loaded
               (and (:clock loaded) (:day-label (:clock pack)))
               (assoc-in [:clock :day-label] (:day-label (:clock pack)))
               (:visits loaded)
               (update :visits #(mapv (fn [v]
                                       (cond-> v
                                         (vector? (:known-to v))
                                         (update :known-to (fn [ids] (filterv (complement left-out) ids)))))
                                     (remove (comp left-out :who) %))))
             :agents (vec agents)
             :items (filterv #(or (nil? (:holder %)) (ids (:holder %))) (:items loaded))
             :state (cond-> (merge (building/initial-state wf agents table opts)
                                   {:positions table
                                    :setting-pack pack
                                    :posts {} :bedrooms {} :routines {}
                                    :setting (get-in wf [:meta :setting])
                                    :meeting-place (get-in wf [:meta :meeting-place])
                                    :quotes {}
                                    :max-queries (:max-queries opts 2)
                                    :find-everywhere? (not (false? (:find-everywhere? opts)))})
                      (get-in wf [:meta :home-domain]) (assoc :home-domain (get-in wf [:meta :home-domain])))))))

(defn add-holidays
  "Add the setting pack's holidays (explicitly dated starting calendar
  records, not recurring rules or world effects) to an init result. A world
  file entry with the same id replaces the pack's entirely, including its
  audience and lifecycle. The engine still validates and emits every entry
  after the final clock and cast."
  [init]
  (let [entries (:calendar (settings/pack init))
        authored (:calendar init)
        ids (set (map :id authored))]
    (assoc init :calendar (into (filterv #(not (ids (:id %))) entries) authored))))
