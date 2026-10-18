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
              circumstance-groups (into {} (for [{:keys [group circumstances]} (:groups (data "circumstances.edn"))
                                                 c circumstances]
                                             [(name (:id c)) group]))
              exclusions (set (map #(set (map name %)) (:exclusions (data "circumstances.edn"))))
              taken-words #{"Claudeworth" "Finch" "Hale" "Nellie"}]
          (testing "deterministic from the seed, and a new seed gives new people"
            (is (= people (:people (call "generate_people" args))))
            (is (not= people (:people (call "generate_people" (assoc args :seed "s2"))))))
          (testing "five records, each with its name untaken, the five axes, one faculty per group and descriptor texts"
            (is (= 5 (count people)))
            (is (apply distinct? (map :name people)))
            (doseq [{:keys [name sex age position lives temperament faculties circumstances]} people]
              (is (not-any? taken-words (str/split name #" ")))
              (is (= "male" sex))                      ; as asked
              (is (<= 18 age 50))
              (is (= "groom" position))
              (is (str/ends-with? lives ", Claudeworth"))
              (is (= ["openness" "conscientiousness" "extraversion" "agreeableness" "neuroticism"] (map :axis temperament)))
              (is (every? #{"low" "average" "high"} (map :level temperament)))
              (is (= groups (map :group faculties)))
              (is (every? #{"small" "moderate" "large"} (map :size faculties)))
              (is (every? (comp not str/blank? :text) (concat temperament faculties circumstances)))
              (is (<= 1 (count circumstances) 2))))
          (testing "never two circumstances from one group, nor an excluded pair, over many seeds"
            (doseq [seed (range 30)
                    {:keys [circumstances]} (:people (call "generate_people" (assoc args :count 10 :seed seed)))
                    :let [ids (map :id circumstances)]]
              (is (apply distinct? (map circumstance-groups ids)))
              (is (not (exclusions (set ids))))))
          (testing "position weights: an earl never cannot read"
            (is (not-any? #{"cannot-read" "workhouse"}
                          (for [seed (range 20)
                                p (:people (call "generate_people" {:position "earl" :count 10 :seed seed}))
                                c (:circumstances p)]
                            (:id c)))))
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
        (let [person (first (:people (call "generate_people" {:position "chauffeur" :seed 9})))
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
   :temperament [{:axis "openness" :level "low" :text "Distrusts anything new-fangled."}]
   :faculties [{:group "External senses" :faculty "Hearing" :size "small" :text "Hard of hearing in one ear."}
               {:group "Propensities" :faculty "Acquisitiveness" :size "large" :text "Counts every penny."}]
   :circumstances [{:id "drinks" :label "drinks" :text "Fond of a drink."}]})

(defn- polish-response [args]
  {:message {:role "assistant" :tool_calls [{:id "c" :type "function"
                                             :function {:name "polish" :arguments (json/write-value-as-string args)}}]}
   :tool-calls [{:id "c" :name "polish" :arguments (json/write-value-as-string args)}]
   :usage {:cost 0.001}})

(defn- scripted [& responses]
  (let [queue (atom responses)]
    (reify llm/LLM
      (complete [_ _] (let [r (first @queue)] (swap! queue rest) r)))))

(def good {:persona "You are a little deaf in the left ear, and you keep a tin of savings under the boards."
           :manner "You seem quiet and wary, and you look a stranger over before you speak."
           :appearance "A broad man of thirty-one in a patched coat, with a weathered face and close-cropped hair."})

(deftest polish-is-checked
  (let [verdict #(:verdict (people/check-polish record (polish-response %)))
        error #(:error (people/check-polish record (polish-response %)))]
    (testing "a good answer is kept, trimmed"
      (is (= good (verdict (update-vals good #(str "  " % " "))))))
    (testing "lengths: the model is asked for less persona than is accepted"
      (is (verdict (assoc good :persona (apply str "You " (repeat 580 "x")))))
      (is (= 450 (get-in (people/polish-request {:record record}) [:tools 0 :function :parameters :properties :persona :maxLength])))
      (is (error (assoc good :persona (apply str "You " (repeat 600 "x")))))
      (is (error (assoc good :manner (apply str (repeat 201 "x")))))
      (is (error (assoc good :manner "   "))))
    (testing "both are written to the person, in the second person"
      (is (= "write persona to the person, in the second person: begin it with \"You\""
             (error (assoc good :persona "Tom Ridding, a groom: a little deaf."))))
      (is (= "write manner to the person, in the second person: begin it with \"You\""
             (error (assoc good :manner "A quiet, wary man who looks you over before he speaks.")))))
    (testing "and never speak of them in the third person after the opening"
      (doseq [[text found] [["You are a groom. He is a little deaf." "He"]
                            ["You are a groom; She keeps a tin." "She"]
                            ["You are a groom. Tom drinks." "Tom"]
                            ["You are a groom! Mr Ridding drinks." "Mr Ridding"]]]
        (is (= (str "write persona to the person as \"you\" throughout; don't write \"" found "\" of them")
               (error (assoc good :persona text)))
            text))
      (is (str/includes? (error (assoc good :manner "You seem wary. He looks you over.")) "write manner"))
      (testing "though others may be named, and words that merely begin like a pronoun or a name are fine"
        (is (verdict (assoc good :persona "You are a groom. Mrs Webb at the laundry says you drink. Tommy the stable cat follows you. Here you are content.")))))
    (testing "traits in plain words, never the record's categories"
      (is (str/includes? (error (assoc good :persona "You have a large Acquisitiveness."))
                         "Acquisitiveness")))
    (testing "appearance is advised at 200 characters but accepted up to 300"
      (is (= 200 (get-in (people/polish-request {:record record}) [:tools 0 :function :parameters :properties :appearance :maxLength])))
      (is (verdict (assoc good :appearance (apply str (repeat 300 "x")))))
      (doseq [bad [nil "" "   " (apply str (repeat 301 "x"))]]
        (is (error (assoc good :appearance bad)) (pr-str bad)))
      (is (error (dissoc good :appearance))))
    (testing "appearance is public third-person prose without the person's name or trait labels"
      (doseq [bad ["You are broad." "you wear spectacles." "A man called Tom." "A Ridding in a patched coat."
                   "A man with large Acquisitiveness." "A man of high neuroticism." "A phrenological specimen."
                   "A man of great openness." "A man of high conscientiousness." "An honest man."
                   "A man who drinks." "A man who owes money."]]
        (is (error (assoc good :appearance bad)) bad))
      (is (verdict (assoc good :appearance "A man in spectacles, squinting at the doorway."))))
    (testing "the mock passes its own check"
      (is (:verdict (people/check-polish record (polish-response (:args (people/mock-polish {:record record})))))))))

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
      (let [{:keys [polish calls cost]} (people/polish! (scripted (polish-response (assoc good :persona "Tom is a groom."))
                                                                  (polish-response good))
                                                        {:model "m"} input)]
        (is (= good polish))
        (is (= 2 (count calls)))
        (is (str/includes? (:content (last (:messages (second calls)))) "in the second person: begin it with \"You\""))
        (is (== 0.002 cost))))
    (testing "two bad answers fail"
      (is (str/includes? (:failure (people/polish! (scripted (polish-response (assoc good :persona "x"))
                                                             (polish-response (assoc good :persona "y")))
                                                   {} input))
                         "second bad answer")))
    (testing "a bad appearance gets one correction, or fails without keeping private prose"
      (let [bad (polish-response (assoc good :appearance "You keep your debts secret."))
            fixed (people/polish! (scripted bad (polish-response good)) {} input)
            failed (people/polish! (scripted bad bad) {} input)]
        (is (= good (:polish fixed)))
        (is (= 2 (count (:calls fixed))))
        (is (str/includes? (:content (last (:messages (second (:calls fixed))))) "third person"))
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
                                "Phrenological faculties:\n"
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

(deftest roll-cast-with-the-mock-polish
  (if-not (bb-available?)
    (println "Skipping roll-cast: bb unavailable")
    (let [dir (scratch-dir "roll-cast")
          file (io/file dir "claudeworth.edn")
          original (slurp "resources/worlds/claudeworth.edn")
          _ (spit file original)
          roll #(people/roll-cast! (str file) {:llm (llm/mock people/mock-polish) :dir dir})
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
      (testing "each keeps name, role, sex and the rest; the old persona (or sketch) is the sketch; the traits are rolled"
        (doseq [[a b] (map vector (:agents before) (:agents after))
                :let [rolled-keys [:persona :sketch :manner :appearance :temperament :faculties :circumstances]]]
          (is (= (apply dissoc a rolled-keys) (apply dissoc b rolled-keys)))
          (is (= (or (:sketch a) (:persona a)) (:sketch b)))
          (is (= (:situation a) (:situation b)))
          (is (str/starts-with? (:persona b) "You are the "))
          (is (str/starts-with? (:manner b) "You "))
          (is (= (:appearance (:args (people/mock-polish {:record {}}))) (:appearance b)))
          (is (= 5 (count (:temperament b))))
          (is (= 7 (count (:faculties b))))
          (is (<= 1 (count (:circumstances b)) 2))))
      (testing "seed 1 rolls exactly the traits the cast has now"
        (let [trait-keys [:sex :temperament :faculties :circumstances]]
          (doseq [[a b] (map vector (:agents before) (:agents after))]
            (is (= (select-keys a trait-keys) (select-keys b trait-keys)) (:name a)))))
      (testing "the rest of the file, comments included, is as it was"
        (is (= (dissoc before :agents) (dissoc after :agents)))
        (is (str/includes? rolled ";; his day when nobody else is in the village")))
      (testing "the rolled file loads, and loading calls nothing"
        (is (= 9 (count (:agents (wf/load-world (str file)))))))
      (testing "rolling again with the same seed changes nothing: the sketch is kept"
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
