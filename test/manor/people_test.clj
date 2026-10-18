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
              args {:position "groom" :count 5 :area "Claudeworth" :seed "s1" :taken-names taken}
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
              (is (= "male" sex))                      ; a groom, in 1904
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
          (testing "a requested sex overrides the position's"
            (is (= #{"female"} (set (map :sex (:people (call "generate_people" {:position "groom" :count 4 :seed 3 :sex "female"}))))))
            (is (= #{"female"} (set (map :sex (:people (call "generate_people" {:position "Scullery maid" :count 4 :seed 3})))))))
          (testing "bad requests are tool errors"
            (doseq [bad [{:position "groom"} {:position "groom" :seed 1 :count 11}
                         {:position "groom" :seed 1 :setting "mars-2100"} {:seed 1}]]
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

(def good {:persona "Tom Ridding, a groom: a little deaf in the left ear, and keeps a tin of savings under the boards."
           :manner "A quiet, wary man who looks you over before he speaks."})

(deftest polish-is-checked
  (let [verdict #(:verdict (people/check-polish record (polish-response %)))
        error #(:error (people/check-polish record (polish-response %)))]
    (testing "a good answer is kept, trimmed"
      (is (= good (verdict (update good :persona #(str "  " % " "))))))
    (testing "lengths"
      (is (error (assoc good :persona (apply str "Tom Ridding " (repeat 600 "x")))))
      (is (error (assoc good :manner (apply str (repeat 201 "x")))))
      (is (error (assoc good :manner "   "))))
    (testing "the persona names the person, by any word of the name"
      (is (str/includes? (error (assoc good :persona "A groom, a little deaf.")) "must name Tom Ridding"))
      (is (verdict (assoc good :persona "Ridding, a groom."))))
    (testing "traits in plain words, never the record's categories"
      (is (str/includes? (error (assoc good :persona "Tom Ridding, with large Acquisitiveness."))
                         "Acquisitiveness")))
    (testing "the mock passes its own check"
      (is (:verdict (people/check-polish record (polish-response (:args (people/mock-polish {:record record})))))))))

(deftest polish-calls-once-and-corrects-once
  (let [input {:setting "A village." :position "groom" :record record}]
    (testing "a bad answer is sent back once with the reason"
      (let [{:keys [polish calls cost]} (people/polish! (scripted (polish-response {:persona "no name" :manner "x"})
                                                                  (polish-response good))
                                                        {:model "m"} input)]
        (is (= good polish))
        (is (= 2 (count calls)))
        (is (str/includes? (:content (last (:messages (second calls)))) "must name Tom Ridding"))
        (is (== 0.002 cost))))
    (testing "two bad answers fail"
      (is (str/includes? (:failure (people/polish! (scripted (polish-response {:persona "x" :manner "x"})
                                                             (polish-response {:persona "y" :manner "y"}))
                                                   {} input))
                         "second bad answer")))
    (testing "only the setting, position, record and notes reach the model"
      (let [{:keys [messages]} (people/polish-request (assoc input :notes "Groom to the earl."))
            sent (json/read-value (:content (second messages)) json/keyword-keys-object-mapper)]
        (is (= #{:setting :position :record :notes} (set (keys sent))))))))

;; ---------------------------------------------------------------------------
;; Traits on agents

(def ada-traits
  [[:manner "Brisk with strangers, and plainly busy."]
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
      (testing "Ada's own prompt shows her persona, then her manner and her readings"
        (is (str/includes? (system-prompt run "ada")
                           (str "An early riser who likes things orderly."
                                "\nTo a stranger you come across as: Brisk with strangers, and plainly busy."
                                "\nYour traits, known only to you. Temperament: openness low, conscientiousness high, "
                                "extraversion average, agreeableness low, neuroticism high. Faculties: large Secretiveness, "
                                "small Cautiousness, large Veneration, small Hearing, large Form, moderate Number, "
                                "small Causality. Circumstances: a gambler (horses, cards); owes money."))))
      (testing "nobody else's view or prompt contains them"
        (let [ben (whole-prompt run "ben")]
          (doseq [secret ["Brisk with strangers" "Secretiveness" "Veneration" "owes money" "gambler" "neuroticism"
                          "early riser"]]
            (is (not (str/includes? ben secret)) secret))))
      (testing "her own view (the JSON) doesn't carry them either; the prompt says them once"
        (is (not (str/includes? (pr-str (prompt/agent-view (:scenario run) (e/world run) "ada")) "Secretiveness"))))
      (testing "a character with no traits is prompted exactly as before"
        (is (= (system-prompt plain "ben") (system-prompt run "ben")))
        (is (not (str/includes? (system-prompt plain "ada") "Your traits"))))
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
        (is (= 9 (count (:results (edn/read-string (slurp (io/file dir "roll-cast.edn"))))))))
      (testing "each keeps name, role and sex; the old persona becomes the sketch; the traits are rolled"
        (doseq [[a b] (map vector (:agents before) (:agents after))]
          (is (= (dissoc a :persona) (select-keys b (keys (dissoc a :persona)))))
          (is (= (:persona a) (:sketch b)))
          (is (str/starts-with? (:persona b) (:name a)))
          (is (= 5 (count (:temperament b))))
          (is (= 7 (count (:faculties b))))
          (is (<= 1 (count (:circumstances b)) 2))))
      (testing "the rest of the file, comments included, is as it was"
        (is (= (dissoc before :agents) (dissoc after :agents)))
        (is (str/includes? rolled ";; his day when nobody else is in the village")))
      (testing "the rolled file loads, and loading calls nothing"
        (is (= 9 (count (:agents (wf/load-world (str file)))))))
      (testing "rolling again with the same seed changes nothing: the sketch is kept"
        (roll)
        (is (= rolled (slurp file)))))))
