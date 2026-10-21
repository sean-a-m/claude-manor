(ns manor.cache-report-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [manor.tools.cache-report :as c]))

;; inferred/: six calls with costs but no cached tokens, one of each kind;
;; the first two off-peak, the rest peak.
;; digests/: four calls with :tool-digests, :at and logged cached tokens,
;; sized so each request renders to a round number of characters.
(def inferred-dir "test/fixtures/cache/inferred")
(def digests-dir "test/fixtures/cache/digests")

(deftest tier-and-cached-tokens-from-cost
  (let [calls (c/load-calls inferred-dir)]
    (is (= [:off-peak :off-peak :peak :peak :peak :peak] (map :tier calls)))
    (is (= [0 1024 512 640 0 1536] (map :cached-tokens calls)))
    (is (= [1000 176 588 260 300 464] (map :uncached-tokens calls)))
    (is (every? #(= :inferred (:cached-source %)) calls))
    (testing "the priced split adds up to the logged cost"
      (doseq [call calls]
        (is (< (abs (- (:cost call) (c/total-cost call))) 1e-12))))
    (testing "a cost no tier explains as whole cache blocks is left alone"
      (is (nil? (c/infer-cached (c/model-pricing "deepseek/deepseek-v4.1-flash") nil
                                {:prompt-tokens 1000 :completion-tokens 100 :cost 1.234567E-4}))))))

(deftest tier-from-the-time
  (let [mp (c/model-pricing "deepseek/deepseek-v4.1-flash")]
    (is (= :peak (c/tier-at mp "2026-10-19T01:00:00Z")))       ; Monday
    (is (= :off-peak (c/tier-at mp "2026-10-19T04:00:00Z")))
    (is (= :peak (c/tier-at mp "2026-10-23T09:59:59Z")))       ; Friday
    (is (= :off-peak (c/tier-at mp "2026-10-18T02:00:00Z")))   ; Sunday
    (is (nil? (c/tier-at mp nil))))
  (let [calls (c/load-calls digests-dir)]
    (is (= [:peak :peak :off-peak :off-peak] (map :tier calls)))
    (is (every? #(= :logged (:tier-source %)) calls))
    (is (= [0 768 128 896] (map :cached-tokens calls)))
    (is (every? #(= :logged (:cached-source %)) calls))))

(deftest kinds-of-call
  (is (= [:turn-first :turn-follow-up :conversation :judge :adjudicator :gm]
         (map :kind (c/load-calls inferred-dir)))))

(defn- entry
  "A small request: `system` and `user` messages and `tools` (names)."
  [system user tools]
  {:messages [{:role "system" :content system} {:role "user" :content user}] :tools tools})

(defn- differs-at [a b]
  (c/divergence (c/segments a {:tool-chars 100}) (c/segments b {:tool-chars 100})))

(deftest where-prompts-differ
  (let [system "You are a participant.\n\n## Your role\nA maid.\n\n## Who you are\nYou are Ann."
        user "# Day 1, 09:00\n\n## Your view\n```json\n{\n  \"time\" : \"09:00\",\n  \"location\" : {\n    \"id\" : \"hall\"\n  }\n}\n```\n\n## Your notes\n(none)"
        base (entry system user ["walk" "say"])]
    (testing "the system message, by its section"
      (is (= "system > ## Who you are"
             (differs-at (entry (str/replace system "Ann" "Bob") user ["walk" "say"]) base)))
      (is (= "system" (differs-at (entry (str "Hello. " system) user ["walk" "say"]) base))))
    (testing "tools, by name"
      (is (= "tools[2] note (added)" (differs-at (entry system user ["walk" "say" "note"]) base)))
      (is (= "tools[1] say (removed)" (differs-at (entry system user ["walk"]) base)))
      (is (= "tools[0] say (moved)" (differs-at (entry system user ["say" "walk"]) base)))
      (let [digested (fn [hash] (assoc base :tool-digests [{:name "walk" :hash hash :chars 100}
                                                           {:name "say" :hash "b" :chars 100}]))]
        (is (= "tools[0] walk (changed)" (differs-at (digested "a2") (digested "a1"))))))
    (testing "a message, by the heading above the change"
      (is (= "messages[1] user > # Day 1, 09:30"
             (differs-at (entry system (str/replace user "09:00\n\n" "09:30\n\n") ["walk" "say"]) base)))
      (is (= "messages[1] user > ## Your notes"
             (differs-at (entry system (str/replace user "(none)" "- buy a hat") ["walk" "say"]) base))))
    (testing "inside a json block, by its top-level key, pretty or compact"
      (is (= "messages[1] user > ## Your view > json location"
             (differs-at (entry system (str/replace user "hall" "yard") ["walk" "say"]) base)))
      (let [compact #(str "# Day 1\n\n## Your view\n```json\n{\"time\":\"09:00\",\"location\":{\"id\":\"" % "\"}}\n```")]
        (is (= "messages[1] user > ## Your view > json location"
               (differs-at (entry system (compact "yard") ["walk" "say"]) (entry system (compact "hall") ["walk" "say"]))))))
    (testing "a message the earlier call didn't have"
      (is (= "messages[2] tool (new)"
             (differs-at (update base :messages conj {:role "tool" :tool_call_id "c1" :content "done"}) base))))))

(deftest json-keys
  (is (= "b" (c/json-key-at "{\"a\": 1, \"b\": {\"c\": [1, 2]}}" 0 18)))
  (testing "commas and escaped quotes in strings don't count"
    (is (= "a" (c/json-key-at "{\"a\": \"x,\\\"y\", \"b\": 2}" 0 9)))
    (is (= "b" (c/json-key-at "{\"a\": \"x,\\\"y\", \"b\": 2}" 0 20))))
  (testing "a change inside a key names that key"
    (is (= "bat" (c/json-key-at "{\"a\": 1, \"bat\": 2}" 0 11)))))

(deftest prefix-simulation
  (let [calls (c/load-calls digests-dir)
        {:keys [calls requests]} (c/simulate calls {:order :system-first})]
    (testing "the fixture renders to round numbers of characters"
      (is (= [900 900 900 1000] (map #(reduce + (map c/seg-chars %)) requests))))
    (testing "shared characters, floored to 128-token blocks"
      ;; 1: 850 chars shared with 0 -> 768. 2: walk's digest changed, so only
      ;; the system message (200) counts -> 128. 3: all of 1 (900) -> 896.
      (is (= [0 768 128 896] (map :cached-tokens calls))))
    (is (= {:n 4 :mae 0.0 :bias 0.0 :within-block 4 :exact 4} (c/errors calls 128)))
    (testing "the prefix is with any earlier request"
      (is (= 1 (:with (c/best-prefix requests 3))))
      (is (= 850 (:chars (c/best-prefix requests 1))))))
  (testing "tools first: a changed first tool shares nothing"
    (is (= [0 768 0 896]
           (map :cached-tokens (:calls (c/simulate (c/load-calls digests-dir) {:order :tools-first}))))))
  (testing "a tool is all or nothing, even when another has the same size"
    (let [a (c/segments {:messages [{:role "system" :content "s"}] :tools ["walk" "say"]} {:tool-chars 300})
          b (c/segments {:messages [{:role "system" :content "s"}] :tools ["walk" "sit"]} {:tool-chars 300})]
      (is (= {:chars 308 :at 2 :offset 0} (c/common-prefix a b))))))

(deftest mock-runs-are-estimated
  (let [entry {:agent "ann" :model "deepseek/deepseek-v4.1-flash" :tools ["walk"]
               :messages [{:role "system" :content (apply str (repeat 2000 "a"))} {:role "user" :content "b"}]
               :usage {:prompt-tokens 0 :completion-tokens 0 :cost 0}}
        {:keys [calls calibration]} (c/simulate (c/account [entry (assoc-in entry [:messages 1 :content] "c")]) {})]
    (is (false? (:tokens-per-char-fitted? calibration)))
    (is (every? :prompt-estimated? calls))
    (is (every? #(= :unknown (:cached-source %)) (c/account [entry])))
    (testing "the second shares the system message, the tool and \"user\\n\": 2007 + 431 + 5 chars at 0.2869"
      (is (= [0 640] (map :cached-tokens calls))))))
