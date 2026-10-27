(ns manor.positions-test
  "Positions, posts, situations, capabilities, forms of address and the
  household's bedrooms, on the bundled Claudeworth world (mock and offline)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [manor.actions :as a]
            [manor.engine :as e]
            [manor.io.llm :as llm]
            [manor.io.store :as store]
            [manor.io.worldfile :as wf]
            [manor.main :as main]
            [manor.mechanics.builtins :as b]
            [manor.mechanics.people :as people]
            [manor.mechanics.positions :as positions]
            [manor.prompt :as prompt]
            [manor.scenarios.claudeworth :as cw]
            [manor.scenarios.claudeworth.shop :as shop]
            [manor.world :as w]))

;; Every run here lives under test-runs/.
(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

(def catalogue-source "positions/england-1904.edn")

(defn- start
  "A Claudeworth run starting at 08:00 on 15 October, which the tests' rounds
  and opening hours assume, whatever the world file's own start."
  [& [opts]]
  (e/start-run :claudeworth (merge {:llm :mock :offline? true :test? true :gm :off :start "1904-10-15T08:00"
                                    :run-id (str (gensym "positions-"))}
                                   opts)))

(defn- system-prompt [run world id]
  (:content (first (prompt/agent-messages (:scenario run) world (e/events run) id (:opts run)))))

(defn- whole-prompt
  "Everything `id` reads on a turn: both messages (which hold their view)."
  [run world id]
  (str/join "\n" (map :content (prompt/agent-messages (:scenario run) world (e/events run) id
                                                      (assoc (:opts run) :event-window 1000)))))

(defn- tools [run world id]
  (set (map :name (a/for-role (:actions run) (:role (w/agent world id)) (positions/capabilities world id)))))

;; ---------------------------------------------------------------------------
;; The catalogue

(deftest the-shared-catalogue
  (let [catalogue (positions/read-catalogue catalogue-source)]
    (testing "positions and :none, each with a title, duties, a standing and where they sleep"
      (is (seq catalogue))
      (is (contains? catalogue :none))
      (doseq [[id entry] catalogue]
        (is (empty? (positions/entry-problems id entry)) (str id))
        ;; A visitor (the :guest) lives elsewhere, so the position doesn't say where they sleep.
        (is (every? #(contains? entry %) (cond-> [:title :about :standing :ages]
                                           (not= :visitor (:standing entry)) (conj :sleeps)))
            (str id))))
    (testing "the butcher and the shopkeeper are separate positions, both shop clerks, each with its own trade"
      (is (= #{:shop-clerk} (get-in catalogue [:butcher :capabilities]) (get-in catalogue [:shopkeeper :capabilities])))
      (is (str/includes? (get-in catalogue [:butcher :about]) "Meat is your trade"))
      (is (str/includes? (get-in catalogue [:shopkeeper :about]) "general shop"))
      (is (= #{:architect} (get-in catalogue [:architect :capabilities]))))
    (testing "duties name nobody and give no form of address: :addressed-as does that"
      (doseq [[id {:keys [about]}] catalogue]
        (is (not (re-find #"(?i)my lord|my lady|\bMrs? [A-Z]|Claudeworth|Pugh|Hale|Nellie|address" about)) (str id))))
    (testing "counter trades share a capability; service-only trades do not sell services as goods"
      (doseq [id [:draper :publican :postmistress :baker :poulterer :fishmonger]]
        (is (= #{:shop-clerk} (get-in catalogue [id :capabilities])) (str id)))
      (doseq [id [:blacksmith :chimney-sweep :barber :laundress :cobbler :police-constable]]
        (is (contains? catalogue id))
        (is (not (contains? (get-in catalogue [id :capabilities] #{}) :shop-clerk)) (str id))))
    (testing "the people server's ages and sex are in the entries"
      (is (= {:ages [13 20] :sex :female} (select-keys (:scullery-maid catalogue) [:ages :sex]))))))

(deftest a-world-adds-to-and-overrides-its-catalogue
  (let [wf {:meta {:positions catalogue-source}
            :roles {:butcher {:about "You sell fish, not meat." :workplace ["quay"]}
                    :ferryman {:title "Ferryman" :about "You row people across the river." :standing :villager}}}
        table (positions/table wf {})]
    (testing "an override replaces only the keys it gives"
      (is (= "You sell fish, not meat." (get-in table [:butcher :about])))
      (is (= ["quay"] (get-in table [:butcher :workplace])))
      (is (= #{:shop-clerk} (get-in table [:butcher :capabilities])))
      (is (= "Mr {surname}" (get-in table [:butcher :addressed-as]))))
    (testing "a new entry is added, and the rest of the catalogue is there"
      (is (= "Ferryman" (get-in table [:ferryman :title])))
      (is (= (get (positions/read-catalogue catalogue-source) :cook) (:cook table))))
    (testing "with the address flag off, no position has a form of address"
      (is (not-any? :addressed-as (vals (positions/table wf {:addressed-as? false})))))
    (testing "a world without a catalogue has only its :roles"
      (is (= {:ferryman (get-in wf [:roles :ferryman])}
             (positions/table (-> wf (update :meta dissoc :positions) (update :roles dissoc :butcher)) {}))))
    (testing "the scenario names bad entries and unknown workplaces"
      (let [ps (str/join "\n" (cw/problems (-> (:world-file (wf/load-world "worlds/claudeworth.edn"))
                                               (assoc-in [:roles :butcher :standing] :grand)
                                               (assoc-in [:roles :butcher :workplace] ["quay"]))))]
        (is (str/includes? ps "position :butcher: :standing must be one of"))
        (is (str/includes? ps "position :butcher: :workplace names unknown location \"quay\""))))))

;; ---------------------------------------------------------------------------
;; Claudeworth's positions and situations

(deftest claudeworths-positions-and-situations
  (let [run (start)
        world (e/world run)]
    (try
      (testing "the world file is fine, and everyone holds their role as a post"
        (is (empty? (cw/problems (:world-file (wf/load-world "worlds/claudeworth.edn")))))
        (is (= :butcher (:role (w/agent world "hobbs"))))
        (doseq [a (w/active-agents world)]
          (is (= (:role a) (:position (positions/post world (:id a)))) (:id a))
          (is (= 0 (:since (positions/post world (:id a))))))
        (is (nil? (positions/post world "gm"))))
      (testing "the family and servants are of the household; the tradesmen keep their own"
        (is (= #{"lord-claudeworth" "lady-claudeworth" "hale" "nellie" "mrs-finch" "daisy" "bert"}
               (set (filter #(positions/household-member? world %) (map :id (w/active-agents world)))))))
      (testing "each brief gives the position, its duties, the setting and the rules"
        (let [earl (system-prompt run world "lord-claudeworth")
              hale (system-prompt run world "hale")]
          (is (str/includes? earl "## Your role\nYour position: Earl. You live in.\nYou are a peer and the master of the house."))
          (is (str/includes? hale "Your position: Private secretary. You live in. You work at: Study (study)."))
          (is (str/includes? earl (get-in world [:state :setting])))
          (is (str/includes? earl "Each turn is half an hour of game time"))))
      (testing "duties say nothing of who holds them or their plans"
        (doseq [id ["lord-claudeworth" "lady-claudeworth" "mrs-finch" "pugh"]
                :let [about (:about (positions/position world id))]]
          (is (not (re-find #"(?i)enlarge|recently joined|Pugh|Claudeworth|manor's custom" about)) id)))
      (testing "a situation is shown in its owner's prompt, after who they are"
        (let [earl (system-prompt run world "lord-claudeworth")]
          (is (re-find #"(?s)## Who you are\n.*## Your situation\nYou are the Earl of Claudeworth.*intend to enlarge it" earl))
          (is (str/includes? (system-prompt run world "mrs-finch") "you have only recently joined the household"))))
      (testing "and nobody else's"
        (doseq [owner ["lord-claudeworth" "lady-claudeworth" "mrs-finch" "pugh"]
                :let [situation (:situation (w/agent world owner))]
                other (map :id (w/active-agents world))
                :when (not= owner other)]
          (is (not (str/includes? (whole-prompt run world other) situation)) (str owner " in " other))))
      (finally (e/stop-run run)))))

(deftest a-new-position-needs-no-code
  (let [run (start)]
    (try
      @(e/spawn! run {:role :footman :name "James Ward" :location "entrance-hall" :bedroom "maids-room"
                      :situation "You have just come to Claudeworth Manor from a house in town."})
      (let [world (e/world run)
            system (system-prompt run world "james-ward")]
        (testing "an operator's footman gets the footman's brief, address and situation"
          (is (str/includes? system "Your position: Footman. You live in.\nYou wait at table, answer the door"))
          (is (str/includes? system "## Who you are\nYou are James Ward (id: james-ward), the footman. People address you as James."))
          (is (str/includes? system "## Your situation\nYou have just come to Claudeworth Manor")))
        (testing "his post is in the household, and he sleeps in the room he was given"
          (is (= {:position :footman :household "household" :since 0 :terms nil} (positions/post world "james-ward")))
          (is (= "maids-room" (get-in world [:state :bedrooms "james-ward"]))))
        (testing "he can't quote goods"
          (is (not (contains? (tools run world "james-ward") :quote-goods))))
        (is (= world (w/replay (store/read-events (:dir run))))))
      (finally (e/stop-run run)))))

;; ---------------------------------------------------------------------------
;; Capabilities

(deftest tools-and-code-go-by-capability
  (let [run (start)
        world (-> (e/world run)
                  (assoc :round 3)                       ; 09:00, the shops open
                  (assoc-in [:agents "lord-claudeworth" :location] "butchers-shop")
                  (update-in [:agents "hobbs"] dissoc :busy)
                  (assoc-in [:agents "hobbs" :location] "butchers-shop"))]
    (try
      (testing "both the butcher and the shopkeeper can quote; the earl can't"
        (is (contains? (tools run world "hobbs") :quote-goods))
        (is (contains? (tools run world "briggs") :quote-goods))
        (is (not (contains? (tools run world "lord-claudeworth") :quote-goods))))
      (testing "and the shop's own check says the same"
        (is (nil? (shop/quote-reason world "hobbs" {:for "lord-claudeworth" :goods [{:item "a leg of mutton" :amount "1"}]})))
        (is (= "only someone who keeps a shop can quote goods"
               (shop/service-reason world "lord-claudeworth"))))
      (testing ":roles and :needs are checked together"
        (let [action {:name :x :roles #{:butcher} :needs :shop-clerk}]
          (is (a/allowed? action :butcher #{:shop-clerk}))
          (is (not (a/allowed? action :butcher #{})))
          (is (not (a/allowed? action :earl #{:shop-clerk})))
          (is (not (a/allowed? (assoc action :roles :all) :gm #{:shop-clerk})))))
      (testing "Mr Pugh is the architect by capability: his purse and the office's drawing"
        (is (= {"pugh" 0} (get-in world [:state :purses])))
        (is (contains? (:you (cw/observe world "pugh")) :purse))
        (is (not (contains? (:you (cw/observe world "hale")) :purse))))
      (finally (e/stop-run run)))))

;; ---------------------------------------------------------------------------
;; Posts

(deftest changing-a-post
  (let [run (start)]
    (try
      @(e/change-post! run "nellie" :cook {:terms "£28 a year, all found"})
      (let [world (e/world run)
            change (last (filter #(= :people/post-changed (:type %)) (e/events run)))]
        (testing "the post and the role change together"
          (is (= :cook (:role (w/agent world "nellie"))))
          (is (= {:position :cook :household "household" :since 0 :terms "£28 a year, all found"}
                 (positions/post world "nellie"))))
        (testing "her brief follows, and she sees the change"
          (is (str/includes? (system-prompt run world "nellie") "Your position: Cook."))
          (is (= #{"nellie"} (:visible-to change)))
          (is (str/includes? (whole-prompt run world "nellie") "Nellie now holds the post of Cook")))
        (testing "replay gives the same world"
          (is (= world (w/replay (store/read-events (:dir run)))))))
      @(e/change-post! run "nellie" :none {:household nil})
      (let [world (e/world run)]
        (testing "a post outside the household: no longer of it, and no view of its bedrooms"
          (is (= :none (:role (w/agent world "nellie"))))
          (is (not (positions/household-member? world "nellie")))
          (is (not (contains? (:household (cw/observe world "nellie")) :bedrooms))))
        (is (= world (w/replay (store/read-events (:dir run))))))
      (testing "an unknown position or person is refused"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no position :duke" (e/change-post! run "nellie" :duke)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no active participant" (e/change-post! run "zed" :cook))))
      (finally (e/stop-run run)))))

;; ---------------------------------------------------------------------------
;; Forms of address

(defn- with-hale-in-the-earls-bedroom [world]
  (assoc-in world [:agents "hale" :location] "earls-bedroom"))

(deftest forms-of-address-behind-a-flag
  (testing "on by default; --no-address turns them off"
    (is (true? (:addressed-as? e/default-opts)))
    (is (= {:addressed-as? false :llm :mock} (:opts (#'main/parse-args ["claudeworth" "--no-address" "--mock"])))))
  (testing "on (the default): your own prompt says how you're addressed, and the view says how to address others"
    (let [run (start)
          world (with-hale-in-the-earls-bedroom (e/world run))]
      (try
        (is (str/includes? (system-prompt run world "lord-claudeworth") "People address you as my lord."))
        (is (str/includes? (system-prompt run world "hale") "People address you as Mr Hale."))
        (is (str/includes? (system-prompt run world "mrs-finch") "People address you as Mrs Finch."))
        (is (str/includes? (system-prompt run world "nellie") "People address you as Nellie."))
        (is (= [{:id "lord-claudeworth" :name "Lord Claudeworth" :role :earl :address-as "my lord" :carrying ["Pocket watch"]}]
               (map #(dissoc % :appearance) (get-in (prompt/agent-view (:scenario run) world "hale") [:location :people-here]))))
        (is (= "Mr Hale" (:address-as (first (get-in (prompt/agent-view (:scenario run) world "lord-claudeworth")
                                                     [:location :people-here])))))
        (finally (e/stop-run run)))))
  (testing "off: no address fields or engine-added prompt line, irrespective of character prose"
    (let [run (start {:addressed-as? false})
          world (with-hale-in-the-earls-bedroom (e/world run))]
      (try
        (doseq [a (w/active-agents world)
                :let [id (:id a)
                      text (system-prompt run world id)
                      with-address (with-redefs [positions/addressed-as (constantly "test-address-sentinel")]
                                     (system-prompt run world id))
                      people (get-in (prompt/agent-view (:scenario run) world id) [:location :people-here])]]
          (is (nil? (positions/addressed-as world id)) id)
          (is (not-any? #(contains? % :address-as) people) id)
          ;; Compare the code-owned addition, never search arbitrary generated prose
          ;; for words such as 'address' (which also matches 'addressed invitations').
          (is (= text (str/replace with-address " People address you as test-address-sentinel." "")) id))
        (is (= [{:id "lord-claudeworth" :name "Lord Claudeworth" :role :earl :carrying ["Pocket watch"]}]
               (map #(dissoc % :appearance) (get-in (prompt/agent-view (:scenario run) world "hale") [:location :people-here]))))
        (finally (e/stop-run run))))))

;; ---------------------------------------------------------------------------
;; Bedrooms, routines and the cap

(deftest forms-of-address-skip-honorifics
  (let [fill #'positions/fill]
    (is (= "Father Anselm" (fill "Father {first-name}" "Father Anselm"))
        "a religious title is an honorific, not the first name")
    (is (= "Mr Hale" (fill "Mr {surname}" "Mr John Hale")))))

(deftest the-households-bedrooms
  (let [run (start)]
    (try
      (let [world (e/world run)
            bedrooms (get-in (cw/observe world "nellie") [:household :bedrooms])]
        (testing "the household sees the house's bedrooms and who sleeps in each"
          (is (= {"cooks-room" ["Mrs Finch"] "countess-bedroom" ["Lady Claudeworth"] "earls-bedroom" ["Lord Claudeworth"]
                  "maids-room" ["Nellie"] "scullery-maids-room" ["Daisy"] "secretarys-room" ["Hale"]
                  "kitchen-box-room" ["Bert"]}
                 (into {} (map (juxt :id :sleeps)) bedrooms)))
          (is (= "Maid's room" (:name (first (filter #(= "maids-room" (:id %)) bedrooms))))))
        (testing "the tradesmen don't, though they have their own bedrooms"
          (doseq [id ["pugh" "briggs" "hobbs"]]
            (is (not (contains? (:household (cw/observe world id)) :bedrooms)) id)
            (is (some? (get-in (cw/observe world id) [:you :bedroom])) id)))
        (testing "the rules say someone living in needs a bed, and where to look"
          (is (str/includes? (cw/rules world) "Someone who lives in needs a bed in the house"))
          (is (str/includes? (cw/rules world) "`household.bedrooms`")))
        (testing "routines are set by events too"
          (is (= "07:00" (:from (first (get-in world [:state :routines "hobbs"])))))
          (is (some #(and (= :people/routine-set (:type %)) (= "pugh" (get-in % [:data :id]))) (e/events run)))))
      @(e/spawn! run {:role :between-maid :name "Ivy Platt" :location "kitchen" :bedroom "maids-room"})
      (let [world (e/world run)]
        (testing "a newcomer sharing a room is shown there; nothing stops it"
          (is (= ["Nellie" "Ivy Platt"]
                 (:sleeps (first (filter #(= "maids-room" (:id %))
                                         (get-in (cw/observe world "nellie") [:household :bedrooms])))))))
        (is (= world (w/replay (store/read-events (:dir run))))))
      (finally (e/stop-run run)))))

(deftest the-cap-counts-active-agents
  (let [spawnable {:roles {:extra {:spawnable? true}}}
        spawn (first (filter #(= :spawn-agent (:name %)) (b/gm-actions spawnable)))
        world-of (fn [n] (reduce w/apply-event (w/new-world)
                                 (for [i (range n)]
                                   {:type :agent/spawned :id i :data {:agent {:id (str "a" i) :name (str "A" i) :role :extra}}})))
        args {:role "extra" :name "New"}]
    (is (= 50 b/default-max-agents))
    (is (nil? ((:valid? spawn) (world-of 49) "gm" args)))
    (is (= "the world is at its limit of 50 participants" ((:valid? spawn) (world-of 50) "gm" args)))
    (testing "retired agents don't count"
      (is (nil? ((:valid? spawn) (assoc-in (world-of 50) [:agents "a0" :status] :retired) "gm" args))))))

(deftest public-appearance-in-a-mock-meeting
  (let [dir (doto (store/run-dir (str (gensym "appearance-world-")) true) .mkdirs)
        file (io/file dir "claudeworth.edn")
        appearance (get-in (people/mock-polish {:record {:name "Daisy" :position "scullery-maid"}})
                           [:args :appearance])
        ;; Own the private sentinels: two rolled characters can legitimately
        ;; share a circumstance, so the real cast's wording is not a leak test.
        _ (spit file (wf/set-agent-keys (slurp "resources/worlds/claudeworth.edn") "Daisy"
                                       [[:appearance appearance]
                                        [:persona "Daisy's private persona sentinel."]
                                        [:manner "Daisy's private manner sentinel."]
                                        [:circumstances ["Daisy's private circumstance sentinel."]]
                                        [:faculties {"Private faculty sentinel" :small}]]))
        policy (fn [{:keys [agent-id step]}]
                 (if (= "mrs-finch" agent-id)
                   (case step
                     0 {:tool :walk :args {:to "scullery"}}
                     1 {:tool :look :args {}}
                     {:tool :pass :args {}})
                   {:tool :pass :args {}}))
        run (start {:world-file (str file) :llm-impl (llm/mock policy)})]
    (try
      (e/step! run)
      (e/wait-idle run)
      (let [world (e/world run)
            daisy (w/agent world "daisy")
            entry (first (filter #(= "daisy" (:id %))
                                (get-in (prompt/agent-view (:scenario run) world "mrs-finch")
                                        [:location :people-here])))
            calls (filter #(= "mrs-finch" (:agent %)) (store/read-llm (:dir run)))
            replies (map #(json/read-value (:content (first (:replies %))) json/keyword-keys-object-mapper)
                         (take 2 calls))
            walk-entry (first (filter #(= "daisy" (:id %)) (:people-here (first replies))))
            look-entry (first (filter #(= "Daisy" (:name %)) (:people (second replies))))]
        (testing "Mrs Finch sees Daisy's appearance in her view, walk reply and look reply"
          (is (= "scullery" (get-in world [:agents "mrs-finch" :location])))
          (is (= {:id "daisy" :name "Daisy" :role :scullery-maid :address-as "Daisy" :appearance appearance}
                 entry))
          (is (= appearance (:appearance walk-entry)))
          (is (= appearance (:appearance look-entry)))
          (is (str/includes? (system-prompt run world "daisy") (str "What people see: " appearance))))
        (testing "none of Daisy's private fields or their text leak through these public surfaces"
          (doseq [public [entry walk-entry look-entry]]
            (is (not-any? #(contains? public %) [:persona :manner :temperament :faculties :circumstances :sketch])))
          (let [seen (str (whole-prompt run world "mrs-finch") replies)]
            (doseq [secret (concat (map daisy [:persona :manner]) (:circumstances daisy)
                                   (keys (:faculties daisy)))]
              (is (not (str/includes? seen secret)) secret))))
        (testing "the GM still sees the cast, including persona and appearance"
          (let [gm-daisy (first (filter #(= "daisy" (:id %)) (:agents (prompt/default-gm-view world))))]
            (is (= (:persona daisy) (:persona gm-daisy)))
            (is (= appearance (:appearance gm-daisy)))
            (is (seq (prompt/gm-messages (:scenario run) world (e/events run) [] (:opts run))))))
        (is (= world (w/replay (store/read-events (:dir run))))))
      (finally (e/stop-run run)))))
