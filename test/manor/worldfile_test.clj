(ns manor.worldfile-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [manor.engine :as e]
            [manor.io.store :as store]
            [manor.io.worldfile :as wf]
            [manor.mechanics.items :as items]
            [manor.mechanics.space :as space]
            [manor.world :as w]))

(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

(def hamlet "test/fixtures/hamlet.edn")

(defn- hamlet-run [& [opts]]
  (e/start-run :hamlet (merge {:llm :mock :test? true :run-id (str (gensym "hamlet-"))} opts)))

(deftest loading
  (let [{:keys [space clock agents world-file]} (wf/load-world hamlet)]
    (testing "the clock comes from :meta"
      (is (= {:start "1904-10-15T21:00" :minutes-per-round 30} clock)))
    (testing "connects-to pairs become travel 0 routes, journeys keep their rounds"
      (is (= #{["bedroom" "hall" 0] ["hall" "porch" 0] ["green" "shop" 0] ["porch" "green" 1] ["green" "farm" 3]}
             (set (:routes space)))))
    (testing "locations keep their extra keys but not connects-to"
      (is (= {:id "bedroom" :name "Bedroom" :domain :house :floor :first :kind :bedroom}
             (second (:locations space))))
      (is (not-any? :connects-to (:locations space))))
    (is (= ["Ada" "Ben"] (map :name agents)))
    (testing "the scenario's own sections are passed through"
      (is (= 1 (count (:notices world-file))))))
  (testing "classpath resources work too (test/ is on the classpath)"
    (is (some? (:space (wf/load-world "fixtures/hamlet.edn")))))
  (testing "the file must hold exactly one form: trailing data is refused, not ignored"
    (let [dir (store/run-dir (str (gensym "trailing-")) true)
          _ (store/write-text! dir "extra.edn" (str (slurp hamlet) "\n{:agents []}\n"))
          _ (store/write-text! dir "stray.edn" (str (slurp hamlet) "\n]\n"))]
      (is (thrown-with-msg? Exception #"more than one form"
                            (wf/load-world (str (io/file dir "extra.edn")))))
      (is (thrown-with-msg? Exception #"not valid EDN"
                            (wf/load-world (str (io/file dir "stray.edn"))))))))

(deftest problems-are-readable
  (let [good (wf/read-file hamlet)
        problems-of #(str/join "\n" (wf/problems %))]
    (is (empty? (wf/problems good)))
    (is (str/includes? (problems-of (assoc-in good [:locations 0 :connects-to] ["bedroom" "sculery"]))
                       "location \"hall\": :connects-to names unknown location \"sculery\""))
    (is (str/includes? (problems-of (update good :locations conj {:id "hall" :name "Second hall"}))
                       "location \"hall\" is defined 2 times"))
    (is (str/includes? (problems-of (assoc good :routes [["porch" "moon" 1]]))
                       "unknown location \"moon\""))
    (is (str/includes? (problems-of (assoc good :routes [["porch" "green" -1]]))
                       "rounds must be a whole number"))
    (is (str/includes? (problems-of (assoc-in good [:agents 0 :location] "attic"))
                       "agent \"Ada\": unknown location \"attic\""))
    (is (str/includes? (problems-of (assoc-in good [:meta :start] "Saturday"))
                       ":meta clock: :start must look like"))
    (is (str/includes? (problems-of (assoc-in good [:locations 1 :id] "Bed Room"))
                       "malformed"))
    (testing "appearance is optional and must be a nonempty string of at most 600 characters"
      (is (empty? (wf/problems (assoc-in good [:agents 0 :appearance] "A small figure in a faded apron."))))
      (is (empty? (wf/problems (assoc-in good [:agents 0 :appearance] (apply str (repeat 600 "x"))))))
      (doseq [bad [42 "" (apply str (repeat 601 "x"))]]
        (is (str/includes? (problems-of (assoc-in good [:agents 0 :appearance] bad)) ":appearance"))))
    (testing "load-world throws with the whole list"
      (let [dir (store/run-dir (str (gensym "problems-")) true)
            _ (store/write-text! dir "bad.edn" (pr-str (assoc good :routes [["nowhere" "elsewhere" 1]])))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"has problems:\n- "
                              (wf/load-world (str (io/file dir "bad.edn")))))))))

(deftest export-round-trip
  (let [run (hamlet-run)
        start (wf/read-file hamlet)
        exported (wf/export (e/world run) start)]
    (testing "exporting an unchanged world gives the same map back"
      (is (= (:routes start) (:routes exported)))
      (is (= (set (:routes (wf/->space start))) (set (:routes (wf/->space exported)))))
      (is (= (map :id (:locations start)) (map :id (:locations exported))))
      (is (empty? (wf/problems exported))))
    (testing "the printed form reads back as the same data"
      (is (= exported (edn/read-string (wf/->edn-string exported)))))
    (testing "a location's :detail is kept and printed last, after :desc"
      (is (= "Coats on pegs, a worn rug, and a smell of pipe smoke." (:detail (first (:locations exported)))))
      (is (str/includes? (wf/->edn-string exported)
                         ":desc \"A small hall.\" :detail \"Coats on pegs, a worn rug, and a smell of pipe smoke.\"}")))
    (testing "other sections are kept"
      (is (= (:notices start) (:notices exported))))
    (testing "aspects of places and items are kept and printed; :touched is the run's, not the map's"
      (is (= {:cleanliness "muddy boot prints by the door" :hearth "cold ash in the grate"} (:aspects (first (:locations exported)))))
      (is (str/includes? (wf/->edn-string exported)
                         "{:id \"clock\" :name \"Grandfather clock\" :place \"hall\" :owner \"Ada\" :fixed? true :aspects {:state \"ticking loudly\"} :desc"))
      (let [changed (reduce w/apply-event (assoc (e/world run) :round 3)
                            [{:type :item/taken :actor "ada" :data {:item "umbrella" :from-place "hall"}}
                             {:type :item/work-done :actor "ben"
                              :data {:job "job-1" :changes [{:place "hall" :aspect :hearth :from "cold ash in the grate" :to nil}
                                                            {:item "clock" :aspect :cleanliness :from nil :to "dusted"}]}}])
            again (wf/export changed start)]
        (is (= 3 (:touched (some #(when (= "umbrella" (:id %)) %) (items/all changed)))))
        (is (= {:cleanliness "muddy boot prints by the door"} (:aspects (first (:locations again)))))
        (is (= {:state "ticking loudly" :cleanliness "dusted"} (:aspects (first (:items again)))))
        (is (not-any? :touched (:items again)))
        (is (empty? (wf/problems again)))
        (is (= again (edn/read-string (wf/->edn-string again))))))
    (e/stop-run run)))

(deftest run-files
  (let [template (slurp hamlet)
        run (hamlet-run)
        dir (:dir run)]
    (testing "the starting world is saved with the run"
      (is (= (wf/read-file hamlet) (store/read-world-start dir))))
    (testing "the live map is written as a world file and stays in step"
      (is (.exists (io/file dir "map.edn")))
      (e/emit! run [{:type :space/location-added :data {:location {:id "library" :name "Library" :domain :house}}}
                    {:type :space/route-opened :data {:from "hall" :to "library" :travel 0}}])
      (let [m (wf/read-file (str (io/file dir "map.edn")))]
        (is (= "library" (:id (last (:locations m)))))
        (is (some #{"library"} (:connects-to (first (:locations m)))))
        (testing "and can start a new run"
          (let [r2 (hamlet-run {:world-file (str (io/file dir "map.edn"))})]
            (is (= #{"hall" "bedroom" "porch" "library"} (space/area (e/world r2) "hall")))
            (e/stop-run r2)))))
    (testing "the template itself is never written"
      (is (= template (slurp hamlet))))
    (testing "branches keep the starting world"
      (e/auto! run 1)
      (e/wait-idle run 30000)
      (let [b (e/branch-run run 1)]
        (is (= (store/read-world-start dir) (store/read-world-start (:dir b))))
        (e/stop-run b)))
    (e/stop-run run)))

(deftest scenarios-without-world-files-are-unchanged
  (let [run (e/start-run :village {:llm :mock :offline? true :test? true :run-id (str (gensym "village-"))})]
    (is (nil? (store/read-world-start (:dir run))))
    (is (not (.exists (io/file (:dir run) "map.edn"))))
    (is (nil? (:clock (e/world run))))
    (e/stop-run run)))
