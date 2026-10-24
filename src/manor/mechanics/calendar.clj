(ns manor.mechanics.calendar
  "The in-world schedule: validation, commands, lifecycle and safe projections.
  Review acknowledges an arrangement, never performs its promised effects.
  An entry may be owned by a mechanic (:managed-by, engine-owned, such as
  :visits): it is never queued for GM review, and only its owner edits it.
  An owner names itself in the scenario's :calendar config, under :managed:
  {:visits {:id-prefix \"visit-\" :hint \"use cancel-visit\"}}. Nobody else may
  add an entry whose id starts with its :id-prefix, and its :hint ends the
  refusal of an edit."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [manor.clock :as clock]
            [manor.world :as w])
  (:import (java.time LocalDate LocalDateTime)
           (java.time.format DateTimeFormatter)
           (java.util Locale)))

(def Entry
  [:map {:closed true}
   [:id [:re #"^[a-z0-9][a-z0-9_-]*$"]]
   [:date {:optional true} :string]
   [:at {:optional true} :string]
   [:description [:string {:min 1 :max 600}]]
   [:visible-to {:optional true} [:or [:enum :all :gm] [:set :string]]]
   [:gm-notes {:optional true} [:string {:max 1200}]]])

(def Lifecycle
  [:map {:closed true}
   [:status [:enum :pending :due :reviewed :historical :cancelled]]
   [:revision pos-int?]])

(defn now [world] (clock/time-of (:clock world) (:round world)))
(defn- date-of [entry]
  (if (:date entry) (LocalDate/parse (:date entry))
      (.toLocalDate (LocalDateTime/parse (:at entry)))))
(defn- review-at [entry]
  (if (:date entry) (.atTime (date-of entry) 5 0)
      (LocalDateTime/parse (:at entry))))
(defn- sort-key [entry]
  [(str (date-of entry)) (if (:date entry) 0 1) (review-at entry) (:id entry)])

(defn entry-problem
  "Shared structural/date/audience check. Audience values are character IDs, not names."
  [world entry]
  (cond
    (not (m/validate Entry entry)) "invalid calendar entry fields or text lengths"
    (= (contains? entry :date) (contains? entry :at)) "supply exactly one of date or at"
    (not (try (review-at entry) (catch Exception _ nil))) "invalid ISO local date or datetime"
    (and (set? (:visible-to entry))
         (some #(let [a (w/agent world %)] (or (nil? a) (= :gm (:role a)))) (:visible-to entry)))
    "calendar audience must contain existing character IDs"))

(defn- past-problem [world entry]
  (if (:date entry)
    (when (.isBefore (date-of entry) (.toLocalDate (now world))) "calendar date is before today")
    (when (.isBefore (review-at entry) (now world)) "calendar appointment is in the past")))

(defn- event [type data] {:type type :visible-to :gm :data data})

(defn- reserving-owner
  "The managed owner whose :id-prefix `id` starts with, as [owner config], or nil."
  [world id]
  (first (filter (fn [[_ {:keys [id-prefix]}]] (and id-prefix (str/starts-with? (str id) id-prefix)))
                 (sort-by key (get-in world [:calendar :managed])))))

(defn- managed-refusal [world owner]
  (str "that entry is managed by " (name owner)
       (when-let [hint (get-in world [:calendar :managed owner :hint])] (str "; " hint))))

(defn- commands
  "`command-events` for an `owner` (nil for the GM, operator and init, or a
  mechanic such as :visits). Only the owner of a managed entry may change it."
  [world op args initial? owner]
  (when-not (:calendar world) (throw (ex-info "calendar is not enabled" {})))
  (let [id (:id args)
        old (get-in world [:calendar :entries id])
        fields (dissoc args :lifecycle :managed-by)
        entry (case op
                :add (merge {:visible-to :gm} fields)
                :update (cond-> (merge (dissoc old :lifecycle :managed-by) fields)
                          (:date fields) (dissoc :at)
                          (:at fields) (dissoc :date))
                :cancel (dissoc old :lifecycle :managed-by))
        rescheduled? (not= (select-keys old [:date :at]) (select-keys entry [:date :at]))
        reserver (when (= op :add) (first (reserving-owner world id)))
        problem (or (when (and (= op :add) old) "calendar ID is already reserved")
                    (when (and reserver (not= owner reserver))
                      (str "calendar IDs starting \"" (get-in world [:calendar :managed reserver :id-prefix])
                           "\" are kept for " (name reserver) "; choose another ID"))
                    (when (and (not= op :add) (or (nil? old) (= :cancelled (get-in old [:lifecycle :status]))))
                      "no non-cancelled calendar entry with that ID")
                    (when (and (not= op :add) (not= owner (:managed-by old)))
                      (if (:managed-by old)
                        (managed-refusal world (:managed-by old))
                        "that entry is not a managed entry"))
                    (when (and (not initial?) (contains? args :lifecycle)) "lifecycle is engine-owned")
                    (when (contains? args :managed-by) "managed-by is engine-owned")
                    (entry-problem world entry)
                    (when (and (not initial?) (not= op :cancel) rescheduled?) (past-problem world entry))
                    (when (and initial? (:lifecycle args) (not (m/validate Lifecycle (:lifecycle args))))
                      "invalid persisted calendar lifecycle"))]
    (when problem (throw (ex-info problem {:id id})))
    (let [status (cond
                   (= op :cancel) :cancelled
                   (and initial? (:lifecycle args)) (get-in args [:lifecycle :status])
                   initial? (if (and (.isBefore (review-at entry) (now world))
                                     (not (and (:date entry) (= (date-of entry) (.toLocalDate (now world))))))
                              :historical :pending)
                   rescheduled? :pending
                   :else (get-in old [:lifecycle :status] :pending))
          revision (if (and initial? (:lifecycle args)) (get-in args [:lifecycle :revision])
                       (inc (get-in old [:lifecycle :revision] 0)))
          record (cond-> (assoc entry :lifecycle {:status status :revision revision})
                   owner (assoc :managed-by owner))]
      [(event (case op :add :calendar/added :update :calendar/updated :cancel :calendar/cancelled)
              {:entry record})])))

(defn command-events
  "Checked commands shared by init, operator and GM. Throws on invalid commands.
  Only initialization accepts persisted lifecycle metadata from an exported map.
  Entries managed by a mechanic are refused here: see `managed-command-events`."
  ([world op args] (command-events world op args false))
  ([world op args initial?] (commands world op args initial? nil)))

(defn managed-command-events
  "`command-events` for entries owned by `owner` (a keyword such as :visits),
  which marks what it adds with :managed-by and alone may change it."
  [world op args owner]
  (commands world op args false owner))

(defn- store-entry [world entry]
  (let [id (:id entry)]
    (cond-> (-> world (assoc-in [:calendar :entries id] entry) (update-in [:calendar :due] dissoc id))
      (= :due (get-in entry [:lifecycle :status]))
      (assoc-in [:calendar :due id] (get-in entry [:lifecycle :revision])))))

(defmethod w/apply-event :calendar/initialized [world {:keys [data]}]
  (assoc world :calendar (cond-> {:lookahead-days (:lookahead-days data) :entries {} :due {}}
                           (:managed data) (assoc :managed (:managed data)))))
(doseq [type [:calendar/added :calendar/updated :calendar/cancelled]]
  (defmethod w/apply-event type [world {:keys [data]}] (store-entry world (:entry data))))

(defmethod w/apply-event :calendar/due [world {:keys [data]}]
  (reduce (fn [world [id revision]]
            (if (and (= :pending (get-in world [:calendar :entries id :lifecycle :status]))
                     (= revision (get-in world [:calendar :entries id :lifecycle :revision])))
              (-> world (assoc-in [:calendar :entries id :lifecycle :status] :due)
                  (assoc-in [:calendar :due id] revision)) world))
          world (:revisions data)))

(defmethod w/apply-event :calendar/reviewed [world {:keys [data]}]
  (reduce (fn [world [id revision]]
            (if (= revision (get-in world [:calendar :due id]))
              (-> world (assoc-in [:calendar :entries id :lifecycle :status] :reviewed)
                  (update-in [:calendar :due] dissoc id)) world))
          world (:revisions data)))

(defn due-events [world]
  (when (:calendar world)
    (let [revisions (into (sorted-map)
                          (for [[id entry] (get-in world [:calendar :entries])
                                :when (and (= :pending (get-in entry [:lifecycle :status]))
                                           (not (:managed-by entry))
                                           (not (.isAfter (review-at entry) (now world))))]
                            [id (get-in entry [:lifecycle :revision])]))]
      (when (seq revisions) [(event :calendar/due {:revisions revisions})]))))

(defn init-events
  "Fresh initialization, after the final clock and cast. Same-day all-day catch-up
  queues here; no model call. Exported lifecycle is preserved, not reinitialized."
  [world config entries]
  (when-not (:clock world) (throw (ex-info "calendar requires a simulation clock" {})))
  (when-not (and (map? config) (nat-int? (:lookahead-days config 30)))
    (throw (ex-info "calendar lookahead-days must be a non-negative integer" {})))
  (when-not (or (nil? entries) (vector? entries)) (throw (ex-info "initial calendar must be a vector" {})))
  (let [start (event :calendar/initialized (cond-> {:lookahead-days (:lookahead-days config 30)}
                                             (:managed config) (assoc :managed (:managed config))))
        [world events] (reduce (fn [[world events] entry]
                                 (let [evs (command-events world :add entry true)]
                                   [(reduce w/apply-event world evs) (into events evs)]))
                               [(w/apply-event world start) [start]] entries)]
    (into events (due-events world))))

(defn due-records [world]
  (->> (keys (get-in world [:calendar :due]))
       (map #(get-in world [:calendar :entries %])) (sort-by sort-key) vec))

(defn reviewed-event [batch]
  (event :calendar/reviewed {:revisions (into (sorted-map) (map (juxt :id #(get-in % [:lifecycle :revision]))) batch)}))

(defn export-entries [world]
  (vec (sort-by :id (vals (get-in world [:calendar :entries])))))

(def ^:private date-format (DateTimeFormatter/ofPattern "d MMMM yyyy" Locale/ENGLISH))
(defn character-section
  "Only permitted descriptions and absolute dates/times. Stable throughout a day."
  [world id]
  (when (:calendar world)
    (let [today (.toLocalDate (now world))
          end (.plusDays today (get-in world [:calendar :lookahead-days]))
          entries (->> (vals (get-in world [:calendar :entries]))
                       (filter #(and (not= :cancelled (get-in % [:lifecycle :status]))
                                     (or (= :all (:visible-to %)) (and (set? (:visible-to %)) ((:visible-to %) id)))
                                     (not (.isBefore (date-of %) today)) (not (.isAfter (date-of %) end))))
                       (sort-by sort-key))]
      (str "## Your calendar\nYour in-world calendar: dates and arrangements known to you. These are scheduled or expected occasions, not a record that they have happened.\n"
           (if (seq entries)
             (str/join "\n" (for [entry entries]
                               (str (.format (date-of entry) date-format)
                                    (when (:at entry) (str ", " (.toLocalTime (review-at entry))))
                                    " — " (:description entry))))
             "(none)") "\n\n"))))

(def ^:private UpdateEntry
  ;; The authored fields are defined once; only description becomes optional.
  (into [:map {:closed true}]
        (map #(if (= :description (first %)) [:description {:optional true} (last %)] %))
        (drop 2 Entry)))

(defn actions []
  (for [[op tool doc] [[:add :add-calendar-entry "Record a known scheduled or expected occasion."]
                       [:update :update-calendar-entry "Edit a calendar entry or its audience."]
                       [:cancel :cancel-calendar-entry "Cancel a calendar entry."]]]
    {:name tool :doc doc :roles #{:gm} :kind :final
     :args (case op
             :add Entry
             :update UpdateEntry
             :cancel [:map (nth Entry 2)])
     :valid? (fn [world _ args]
               (try (command-events world op args) nil (catch Exception e (ex-message e))))
     :effect (fn [world _ args] (command-events world op args))}))
