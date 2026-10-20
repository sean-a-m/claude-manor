(ns manor.people-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [manor.engine :as e]
            [manor.llm :as llm]
            [manor.mcp :as mcp]
            [manor.people :as people]
            [manor.positions :as positions]
            [manor.prompt :as prompt]
            [manor.store :as store]
            [manor.worldfile :as wf]))

;; Everything here lives under test-runs/.
(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

(defn- bb-available? []
  (try (zero? (:exit (shell/sh "bb" "-e" ""))) (catch Exception _ false)))

(defn- scratch-dir [base]
  (doto (store/run-dir (str (gensym (str base "-"))) true) .mkdirs))

(defn- data [file] (edn/read-string (slurp (str "mcp-servers/people/" file))))

;; ---------------------------------------------------------------------------
;; The people server

(defn- with-server [f]
  (let [dir (scratch-dir "people-server")
        conn (mcp/connect :people (:people people/mcp-servers) {:run-id "test" :run-dir (str dir)})]
    (try (f (fn [tool args] (:value (mcp/call-tool conn tool args 20000))))
         (finally (mcp/close! conn)))))

(deftest people-server-generates
  (if-not (bb-available?)
    (println "Skipping people MCP contracts: bb unavailable")
    (with-server
      (fn [call]
        (let [taken ["Lord Claudeworth" "Mrs Finch" "Hale" "Nellie"]
              args {:position "groom" :count 5 :area "Claudeworth" :seed "s1" :taken-names taken
                    :ages [18 50] :sex "male"}
              people (:people (call "generate_people" args))
              groups (mapv :group (:groups (data "faculties.edn")))
              taken-words #{"Claudeworth" "Finch" "Hale" "Nellie"}]
          (testing "deterministic from the seed, and a new seed gives new people"
            (is (= people (:people (call "generate_people" args))))
            (is (not= people (:people (call "generate_people" (assoc args :seed "s2"))))))
          (testing "five basic records, each with its name untaken, five axes and one faculty per group"
            (is (= 5 (count people)))
            (is (apply distinct? (map :name people)))
            (doseq [{:keys [name sex age position lives temperament faculties] :as person} people]
              (is (not-any? taken-words (str/split name #" ")))
              (is (= "male" sex))                      ; as asked
              (is (<= 18 age 50))
              (is (= "groom" position))
              (is (str/ends-with? lives ", Claudeworth"))
              (is (= ["openness" "conscientiousness" "extraversion" "agreeableness" "neuroticism"] (map :axis temperament)))
              (is (every? #{"low" "average" "high"} (map :level temperament)))
              (is (= groups (map :group faculties)))
              (is (every? #{"small" "moderate" "large"} (map :size faculties)))
              (is (= #{:name :sex :age :position :origin :lives :temperament :faculties} (set (keys person))))
              (is (every? #(= #{:axis :level :measures} (set (keys %))) temperament))
              (is (every? #(= #{:group :faculty :means :size} (set (keys %))) faculties))
              (is (every? (comp not str/blank? :measures) temperament))
              (is (every? (comp not str/blank? :means) faculties))))
          (testing "source catalogues contain definitions, not behaviour templates"
            (is (every? #(= #{:axis :measures} (set (keys %))) (:axes (data "temperament.edn"))))
            (is (every? #(= #{:faculty :means} (set (keys %)))
                        (mapcat :faculties (:groups (data "faculties.edn")))))
            (is (not (.exists (io/file "mcp-servers/people/circumstances.edn")))))
          (testing "age and sex come from the request: the catalogue's entry, as the engine passes it"
            (let [catalogue (positions/read-catalogue "positions/england-1904.edn")
                  maids (:people (call "generate_people"
                                       (assoc (people/generate-args :scullery-maid (:scullery-maid catalogue)
                                                                    {:seed 3 :taken-names []})
                                              :count 10)))]
              (is (= #{"female"} (set (map :sex maids))))
              (is (every? #(<= 13 (:age %) 20) maids)))
            (is (= #{"female"} (set (map :sex (:people (call "generate_people" (people/generate-args :groom {:ages [18 50] :sex :male}
                                                                                                      {:sex :female :seed 3})))))))
            (let [anyone (:people (call "generate_people" {:position "groom" :count 10 :seed 4}))]
              (is (= #{"female" "male"} (set (map :sex anyone))))
              (is (every? #(<= 18 (:age %) 55) anyone))))
          (testing "bad requests are tool errors"
            (doseq [bad [{:position "groom"} {:position "groom" :seed 1 :count 11}
                         {:position "groom" :seed 1 :setting "mars-2100"} {:seed 1}
                         {:position "groom" :seed 1 :ages [40 20]} {:position "groom" :seed 1 :ages [18]}]]
              (is (thrown? clojure.lang.ExceptionInfo (call "generate_people" bad))))))))))

(deftest people-server-reputation
  (if-not (bb-available?)
    (println "Skipping people MCP contracts: bb unavailable")
    (with-server
      (fn [call]
        (let [person (assoc (first (:people (call "generate_people" {:position "chauffeur" :seed 9})))
                            :circumstances ["A written background fact." "A written commitment."])
              ask #(call "reputation" {:person person :knower "Hobbs" :relation % :seed 1})
              subset? (fn [k known] (every? (set (get person k)) (get-in known [:knows k])))]
          (testing "deterministic, and only the person's own traits"
            (is (= (ask "neighbour") (ask "neighbour")))
            (doseq [relation ["by-sight" "neighbour" "acquaintance" "fellow-servant" "employer" "family"]
                    :let [known (ask relation)]]
              (is (str/includes? (:acquaintance known) "Hobbs"))
              (is (str/includes? (:acquaintance known) (:name person)))
              (is (every? #(subset? % known) [:temperament :faculties :circumstances]))))
          (testing "how much is known follows the relation"
            (is (= [1 1 0] (map #(count (get-in (ask "by-sight") [:knows %])) [:temperament :faculties :circumstances])))
            (is (= (:temperament person) (get-in (ask "family") [:knows :temperament])))
            (is (= (:faculties person) (get-in (ask "family") [:knows :faculties]))))
          (testing "an unknown relation is a tool error naming the relations"
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"by-sight"
                                  (call "reputation" {:person person :knower "Hobbs" :relation "cousin" :seed 1})))))))))

;; ---------------------------------------------------------------------------
;; The polish

(def record
  {:name "Tom Ridding" :sex "male" :age 31 :position "groom"
   :temperament [{:axis "openness" :level "low" :measures (people/axis-measures :openness)}]
   :faculties [{:group "External senses" :faculty "Hearing" :size "small" :means (people/faculty-means "Hearing")}
               {:group "Propensities" :faculty "Acquisitiveness" :size "large" :means (people/faculty-means "Acquisitiveness")}]})

(defn- polish-response [args]
  {:message {:role "assistant" :tool_calls [{:id "c" :type "function"
                                             :function {:name "polish" :arguments (json/write-value-as-string args)}}]}
   :tool-calls [{:id "c" :name "polish" :arguments (json/write-value-as-string args)}]
   :usage {:cost 0.001}})

(defn- scripted [& responses]
  (let [queue (atom responses)]
    (reify llm/LLM
      (complete [_ _] (let [r (first @queue)] (swap! queue rest) r)))))

(def good {:circumstances ["Your mother lives in another parish and expects a letter each Sunday."]
           :persona "You are a little deaf in the left ear, and you keep a tin of savings under the boards."
           :manner "You seem quiet and wary, and you look a stranger over before you speak."
           :appearance "A broad man of thirty-one in a patched coat, with a weathered face and close-cropped hair."})

(deftest polish-is-checked
  (let [verdict #(:verdict (people/check-polish record (polish-response %)))
        error #(:error (people/check-polish record (polish-response %)))]
    (testing "a good answer is kept, trimmed"
      (is (= good (verdict (-> (update-vals (dissoc good :circumstances) #(str "  " % "  "))
                                (assoc :circumstances (mapv #(str "  " % "  ") (:circumstances good))))))))
    (testing "lengths: the model is asked for less persona than is accepted"
      (is (verdict (assoc good :persona (apply str "You " (repeat 580 "x")))))
      (is (= 450 (get-in (people/polish-request {:record record}) [:tools 0 :function :parameters :properties :persona :maxLength])))
      (is (error (assoc good :persona (apply str "You " (repeat 600 "x")))))
      (is (error (assoc good :manner (apply str (repeat 201 "x")))))
      (is (error (assoc good :manner "   "))))
    (testing "appearance is advised at 200 characters but accepted up to 300"
      (is (= 200 (get-in (people/polish-request {:record record}) [:tools 0 :function :parameters :properties :appearance :maxLength])))
      (is (verdict (assoc good :appearance (apply str (repeat 300 "x")))))
      (doseq [bad [nil "" "   " (apply str (repeat 301 "x"))]]
        (is (error (assoc good :appearance bad)) (pr-str bad)))
      (is (error (dissoc good :appearance))))
    (testing "wording is never pattern-checked: period jargon and any point of view pass"
      (is (verdict (assoc good :persona "You have a large Acquisitiveness and a small organ of Hope."
                               :appearance "A man of high colour in a patched coat."))))
    (testing "the instructions welcome period vocabulary"
      (is (str/includes? (get-in (people/polish-request {:record record}) [:messages 0 :content])
                         "Period vocabulary is welcome")))
    (testing "the mock passes its own check"
      (is (:verdict (people/check-polish record (polish-response (:args (people/mock-polish {:record record})))))))))

(deftest character-writing-input-is-basic-only
  (let [legacy (-> record
                   (assoc :persona "old-persona" :manner "old-manner" :appearance "old-appearance"
                          :circumstances [{:id "old-circumstance" :text "old-biography"}])
                   (assoc-in [:temperament 0 :text] "old-temperament")
                   (assoc-in [:temperament 0 :label] "old-label")
                   (assoc-in [:faculties 0 :text] "old-faculty"))
        request (people/polish-request {:setting "England, 1904." :position "groom" :record legacy})
        sent (json/read-value (get-in request [:messages 1 :content]) json/keyword-keys-object-mapper)]
    (is (= record (:record sent)))
    (is (= record (get-in request [:ctx :record])))
    (is (= #{:setting :position :record} (set (keys sent))))
    (is (= "low" (get-in sent [:record :temperament 0 :level])))
    (is (= "small" (get-in sent [:record :faculties 0 :size])))))

(deftest circumstances-are-written-and-structurally-checked
  (let [check #(:verdict (people/check-polish record (polish-response %)))]
    (doseq [facts [["A personal background fact."] ["A background fact." "An external commitment."]]]
      (is (= facts (:circumstances (check (assoc good :circumstances facts))))))
    (doseq [bad [nil [] ["a" "b" "c"] [""] ["   "] [(apply str (repeat 241 "x"))]
                 [{:text "A map is not a circumstance string."}]]]
      (is (nil? (check (assoc good :circumstances bad))) (pr-str bad)))
    (is (nil? (check (dissoc good :circumstances))))
    ;; A model cannot overwrite the rolled traits through extra output keys.
    (is (= good (check (assoc good :temperament [] :faculties [] :money 100))))))

(deftest character-writing-budget-covers-corrections
  (let [requests (atom [])
        model (reify llm/LLM
                (complete [_ request]
                  (swap! requests conj request)
                  (polish-response (dissoc good :circumstances))))
        result (people/polish! model {:max-cost 0.001} {:record record})]
    (is (:failure result))
    (is (nil? (:polish result)))
    (is (= 1 (count @requests)))
    (is (= 1 (count (:calls result))))
    (is (== 0.001 (:cost result)))))

(deftest appearance-baseline-instructions
  (let [input {:setting "A village." :position "groom" :record record}
        result (people/polish! (llm/mock people/mock-polish) {} input)
        instructions (get-in result [:calls 0 :messages 0 :content])]
    (is (str/includes? instructions "Do not place the person somewhere or describe a passing action"))
    (is (str/includes? instructions "appearance is shown wherever they are"))
    (is (seq (get-in result [:polish :appearance])))
    (is (= 1 (count (:calls result))))
    (is (zero? (:cost result)))))

(deftest polish-calls-once-and-corrects-once
  (let [input {:setting "A village." :position "groom" :record record}]
    (testing "a bad answer is sent back once with the reason"
      (let [{:keys [polish calls cost]} (people/polish! (scripted (polish-response (assoc good :persona "   "))
                                                                  (polish-response good))
                                                        {:model "m"} input)]
        (is (= good polish))
        (is (= 2 (count calls)))
        (is (str/includes? (:content (last (:messages (second calls)))) "non-blank"))
        (is (== 0.002 cost))))
    (testing "two bad answers fail"
      (is (str/includes? (:failure (people/polish! (scripted (polish-response (assoc good :persona ""))
                                                             (polish-response (assoc good :persona "")))
                                                   {} input))
                         "second bad answer")))
    (testing "a bad appearance gets one correction, or fails without keeping private prose"
      (let [bad (polish-response (assoc good :appearance (apply str (repeat 301 "x"))))
            fixed (people/polish! (scripted bad (polish-response good)) {} input)
            failed (people/polish! (scripted bad bad) {} input)]
        (is (= good (:polish fixed)))
        (is (= 2 (count (:calls fixed))))
        (is (str/includes? (:content (last (:messages (second (:calls fixed))))) "appearance"))
        (is (:failure failed))
        (is (nil? (:polish failed)))
        (is (= 2 (count (:calls failed))))))
    (testing "only the setting, position, record and notes reach the model"
      (let [{:keys [messages]} (people/polish-request (assoc input :notes "Groom to the earl."))
            sent (json/read-value (:content (second messages)) json/keyword-keys-object-mapper)]
        (is (= #{:setting :position :record :notes} (set (keys sent))))))))

;; ---------------------------------------------------------------------------
;; Traits on agents

(def ada-traits
  [[:manner "Brisk with strangers, and plainly busy."]
   [:appearance "A sturdy woman in a faded apron, with red hands and iron-grey hair."]
   [:sex :female]
   [:temperament {:openness :low :conscientiousness :high :extraversion :average :agreeableness :low :neuroticism :high}]
   [:faculties {"Secretiveness" :large "Cautiousness" :small "Veneration" :large "Hearing" :small
                "Form" :large "Number" :moderate "Causality" :small}]
   [:circumstances ["a gambler (horses, cards)" "owes money"]]])

(defn- system-prompt [run id]
  (:content (first (prompt/agent-messages (:scenario run) (e/world run) (e/events run) id {}))))

(defn- whole-prompt [run id]
  (str (pr-str (prompt/agent-view (:scenario run) (e/world run) id))
       (str/join "\n" (map :content (prompt/agent-messages (:scenario run) (e/world run) (e/events run) id {:event-window 1000})))))

(deftest traits-are-private
  (let [dir (scratch-dir "traits")
        file (io/file dir "hamlet.edn")
        _ (spit file (wf/set-agent-keys (slurp "test/fixtures/hamlet.edn") "Ada" ada-traits))
        start #(e/start-run :hamlet {:llm :mock :test? true :run-id (str (gensym "traits-")) :world-file %})
        run (start (str file))
        plain (start "test/fixtures/hamlet.edn")]
    (try
      (e/step! run)
      (e/wait-idle run)
      (testing "Ada's own prompt shows who she is, her persona, then how strangers see her and her nature, a reading a line"
        (is (str/includes? (system-prompt run "ada")
                           (str "## Who you are\nYou are Ada (id: ada).\n\n"
                                "An early riser who likes things orderly.\n\n"
                                "How strangers see you: Brisk with strangers, and plainly busy.\n\n"
                                "What people see: A sturdy woman in a faded apron, with red hands and iron-grey hair.\n\n"
                                "### Your nature (known only to you)\n"
                                "Temperament:\n"
                                "- Openness: low (curiosity, imagination, willingness to entertain new ideas)\n"
                                "- Conscientiousness: high (self-control, diligence, attention to detail)\n"
                                "- Extraversion: average (boldness, energy, sociability)\n"
                                "- Agreeableness: low (kindness, helpfulness, willingness to cooperate)\n"
                                "- Neuroticism: high (irritability, anxiety, low spirits)\n"
                                "Faculties:\n"
                                "- Secretiveness: large (tact, concealment)\n"
                                "- Cautiousness: small (fear, prudence)\n"
                                "- Veneration: large (reverence, respect for authority)\n"
                                "- Hearing: small (the sense of hearing)\n"
                                "- Form: large (memory for shapes and faces)\n"
                                "- Number: moderate (arithmetic)\n"
                                "- Causality: small (cause and effect)\n"
                                "Circumstances:\n"
                                "- a gambler (horses, cards)\n"
                                "- owes money"))))
      (testing "every term's meaning comes from the people server's data"
        (is (= (set (map :axis (:axes (data "temperament.edn")))) (set (keys people/axis-measures))))
        (is (= (count (mapcat :faculties (:groups (data "faculties.edn")))) (count people/faculty-means)))
        (is (every? (comp not str/blank?) (concat (vals people/axis-measures) (vals people/faculty-means)))))
      (testing "nobody else's view or prompt contains them"
        (let [ben (whole-prompt run "ben")]
          (doseq [secret ["Brisk with strangers" "Secretiveness" "Veneration" "owes money" "gambler" "Neuroticism"
                          "tact, concealment" "Your nature" "early riser"]]
            (is (not (str/includes? ben secret)) secret))))
      (testing "her own view (the JSON) doesn't carry them either; the prompt says them once"
        (is (not (str/includes? (pr-str (prompt/agent-view (:scenario run) (e/world run) "ada")) "Secretiveness"))))
      (testing "a character with no traits is prompted as before: their name, then their persona"
        (is (= (system-prompt plain "ben") (system-prompt run "ben")))
        (is (str/includes? (system-prompt plain "ben") "## Who you are\nYou are Ben (id: ben).\n\nA night owl who hates going to bed."))
        (is (not (str/includes? (system-prompt plain "ada") "Your nature")))
        (is (not (str/includes? (system-prompt plain "ada") "How strangers see you")))
        (is (not (str/includes? (system-prompt plain "ada") "What people see"))))
      (finally (e/stop-run run) (e/stop-run plain)))))

;; ---------------------------------------------------------------------------
;; Editing world files and rolling a cast

(deftest set-agent-keys-edits-in-place
  (let [text (slurp "test/fixtures/hamlet.edn")
        edited (wf/set-agent-keys text "Ben" [[:persona "A changed man."] [:manner "Cheerful."] [:sex :male]
                                              [:faculties {"Tune" :large}]])
        before (edn/read-string text)
        after (edn/read-string edited)]
    (testing "the agent gets the keys, and nothing else in the data changes"
      (is (= (assoc-in before [:agents 1] (merge (get-in before [:agents 1])
                                                 {:persona "A changed man." :manner "Cheerful." :sex :male
                                                  :faculties {"Tune" :large}}))
             after)))
    (testing "new keys go on their own lines after :persona; other lines are untouched"
      (is (str/includes? edited (str ":persona \"A changed man.\"\n   :manner \"Cheerful.\"\n   :sex :male\n"
                                     "   :faculties {\"Tune\" :large}}]")))
      (is (= (remove #(str/includes? % "Ben") (str/split-lines text))
             (remove #(or (str/includes? % "Ben") (re-find #"^\s+:(?:manner|sex|faculties) " %)) (str/split-lines edited)))))
    (testing "setting keys again replaces them where they stand"
      (is (= edited (wf/set-agent-keys edited "Ben" [[:persona "A changed man."] [:manner "Cheerful."]]))))
    (testing "an unknown agent is an error"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no agent called Zed" (wf/set-agent-keys text "Zed" [[:manner "x"]]))))))

(deftest remove-agent-keys-preserves-other-data-and-layout
  (doseq [text [";; intro\n{:agents [{:name \"Ada\"\n   :sketch \"An old\\\" description\"\n   :persona \"Keep me\"}]}\n"
                "{:agents [{:sketch \"old\" :name \"Ada\" :persona \"Keep me\"}]}"
                "{:agents [{:name \"Ada\"\n   :sketch \"old\" ; keep this comment\n   :persona \"Keep me\"}]}"
                "{:agents [{:name \"Ada\" :sketch \"old\"}]}"
                "{:agents [{:name \"Ada\" :sketch \"a multiline\ndescription\"}]}"
                "{:agents [{:name \"Ada\" :persona \"Keep me\"}]}\n"]]
    (let [edited (wf/remove-agent-keys text "Ada" [:sketch :absent])]
      (is (= (update-in (edn/read-string text) [:agents 0] dissoc :sketch) (edn/read-string edited)))
      (is (= edited (wf/remove-agent-keys edited "Ada" [:sketch])))))
  (let [text "{:agents [{:name \"Ada\"\n   :sketch \"old\"\n   :persona \"Keep me\"}]}\n"]
    (is (= "{:agents [{:name \"Ada\"\n   :persona \"Keep me\"}]}\n" (wf/remove-agent-keys text "Ada" [:sketch])))))

(deftest reroll-never-feeds-back-old-character-descriptions
  (let [dir (scratch-dir "fresh-personality")
        file (io/file dir "world.edn")
        old {:name "Ada" :role :groom :sex :male :location "stable"
             :situation "You serve at the stable." :bedroom "room"
             :persona "old-persona-sentinel" :sketch "original-description-sentinel"
             :manner "old-manner-sentinel" :appearance "old-appearance-sentinel"
             :temperament {:openness :high} :faculties {"Hope" :small}
             :circumstances ["old-circumstance-sentinel"]}
        template {:meta {:setting "England, 1904."} :roles {:groom {:title "Groom"}}
                  :agents [old (assoc old :name "Ben")]}
        requests (atom [])
        calls (atom [])
        model (reify llm/LLM
                (complete [_ request]
                  (swap! requests conj request)
                  (polish-response good)))
        roll (fn [opts] (people/roll-cast! (str file) (merge {:llm model :dir dir :only ["Ada"]} opts)))
        input #(json/read-value (get-in % [:messages 1 :content]) json/keyword-keys-object-mapper)]
    (spit file (pr-str template))
    (with-redefs [mcp/connect (fn [& _] ::fake-connection)
                  mcp/close! (fn [_])
                  mcp/call-tool (fn [_ tool args _]
                                  (swap! calls conj {:tool tool :args args})
                                  {:value {:people [(assoc record :origin "A newly rolled origin" :lives "A generated cottage")]}})]
      (let [result (roll {:seed 7})
            after (edn/read-string (slurp file))
            expected {:setting "England, 1904." :position "groom"
                      :record (assoc record :name "Ada" :origin "A newly rolled origin")}]
        (is (= expected (input (first @requests))))
        (is (= (:record expected) (get-in (first @requests) [:ctx :record])))
        (is (= 7 (:seed result)))
        (is (= 7 (:seed (edn/read-string (slurp (io/file dir "roll-cast.edn"))))))
        (is (= "7/Ada" (get-in (first @calls) [:args :seed])))
        (is (= (merge (dissoc old :sketch) good (people/traits (assoc record :circumstances (:circumstances good))))
               (first (:agents after))))
        (is (= (second (:agents template)) (second (:agents after))))
        (is (= (dissoc template :agents) (dissoc after :agents)))
        (roll {:seed 7})
        (is (= expected (input (second @requests)))))
      (testing "absent sketch does not make the existing persona a fallback input"
        (spit file (pr-str (update template :agents #(mapv (fn [a] (dissoc a :sketch)) %))))
        (roll {:seed 7})
        (is (= (input (first @requests)) (input (last @requests)))))
      (testing "each default invocation gets a fresh seed, recorded and returned"
        (let [a (roll {}) b (roll {})]
          (is (string? (:seed a)))
          (is (not= (:seed a) (:seed b)))
          (is (= (:seed b) (:seed (edn/read-string (slurp (io/file dir "roll-cast.edn"))))))))
      (testing "the cast budget is shared across agents and prevents later model calls"
        (spit file (pr-str template))
        (let [before (count @requests)
              result (people/roll-cast! (str file) {:llm model :dir dir :max-cost 0.001 :seed 9})
              after (edn/read-string (slurp file))]
          (is (= ["Ada"] (:rolled result)))
          (is (= #{"Ben"} (set (keys (:failed result)))))
          (is (= 1 (- (count @requests) before)))
          (is (== 0.001 (:cost result)))
          (is (= (second (:agents template)) (second (:agents after))))))
      (testing "a failed polish leaves the selected agent, including legacy keys, untouched"
        (spit file (pr-str template))
        (let [result (people/roll-cast! (str file) {:llm (scripted (polish-response {}) (polish-response {}))
                                                  :dir dir :seed 8 :only ["Ada"]})]
          (is (= template (edn/read-string (slurp file))))
          (is (empty? (:rolled result)))
          (is (= #{"Ada"} (set (keys (:failed result))))))))))

(deftest roll-cast-with-the-mock-polish
  (if-not (bb-available?)
    (println "Skipping roll-cast: bb unavailable")
    (let [dir (scratch-dir "roll-cast")
          file (io/file dir "claudeworth.edn")
          original (slurp "resources/worlds/claudeworth.edn")
          _ (spit file original)
          roll #(people/roll-cast! (str file) {:llm (llm/mock people/mock-polish) :dir dir :seed 1})
          result (roll)
          rolled (slurp file)
          before (edn/read-string original)
          after (edn/read-string rolled)]
      (testing "every agent is rolled, at no cost, with the calls on record"
        (is (= (mapv :name (:agents before)) (:rolled result)))
        (is (= {} (:failed result)))
        (is (zero? (:cost result)))
        (is (= 9 (count (:results (edn/read-string (slurp (io/file dir "roll-cast.edn")))))))
        (testing "with each position's ages from the catalogue, and the agent's own sex"
          (let [hobbs (first (filter #(= "Hobbs" (:name %)) (:results (edn/read-string (slurp (io/file dir "roll-cast.edn"))))))]
            (is (= {:position "butcher" :ages [25 65] :sex "male"} (select-keys (:args hobbs) [:position :ages :sex])))
            (is (= "butcher" (:position (:record hobbs)))))))
      (testing "each keeps name, role, sex and the rest; no sketch is saved and the traits are rolled"
        (doseq [[a b] (map vector (:agents before) (:agents after))
                :let [rolled-keys [:persona :sketch :manner :appearance :temperament :faculties :circumstances]]]
          (is (= (apply dissoc a rolled-keys) (apply dissoc b rolled-keys)))
          (is (not (contains? b :sketch)))
          (is (= (:situation a) (:situation b)))
          (is (str/starts-with? (:persona b) "You are the "))
          (is (str/starts-with? (:manner b) "You "))
          (is (= (:appearance (:args (people/mock-polish {:record {}}))) (:appearance b)))
          (is (= 5 (count (:temperament b))))
          (is (= 7 (count (:faculties b))))
          (is (<= 1 (count (:circumstances b)) 2))))
      (testing "traits are taken from the fresh server record, not the bundled cast"
        (let [trait-keys [:sex :temperament :faculties :circumstances]
              results (:results (edn/read-string (slurp (io/file dir "roll-cast.edn"))))]
          (doseq [[result agent] (map vector results (:agents after))]
            (is (= (people/traits (:record result)) (select-keys agent trait-keys)) (:name agent)))))
      (testing "the rest of the file, comments included, is as it was"
        (is (= (dissoc before :agents) (dissoc after :agents)))
        (is (str/includes? rolled ";; his day when nobody else is in the village")))
      (testing "the rolled file loads, and loading calls nothing"
        (is (= 9 (count (:agents (wf/load-world (str file)))))))
      (testing "rolling again with the same explicit seed changes nothing with the mock writer"
        (roll)
        (is (= rolled (slurp file))))
      (testing ":only rolls just the agents named"
        (let [{:keys [rolled]} (people/roll-cast! (str file) {:llm (llm/mock people/mock-polish) :dir dir
                                                             :only ["Hobbs"] :seed 2})
              again (edn/read-string (slurp file))]
          (is (= ["Hobbs"] rolled))
          (is (= (remove #(= "Hobbs" (:name %)) (:agents after)) (remove #(= "Hobbs" (:name %)) (:agents again))))
          (is (not= (first (filter #(= "Hobbs" (:name %)) (:agents after)))
                    (first (filter #(= "Hobbs" (:name %)) (:agents again))))))))))
