(ns manor.prompts-test
  (:require [clojure.test :refer [deftest is testing]]
            [manor.prompts :as prompts]
            [manor.actions :as a]
            [manor.clock :as clock]
            [manor.mechanics.find-person :as find-person]
            [manor.mechanics.hiring :as hiring]
            [manor.mechanics.visits :as visits]
            [manor.ruleset.rules :as rules]
            [manor.mechanics.deliveries :as deliveries]
            [manor.mechanics.people :as people]
            [manor.prompt :as prompt]
            [manor.settings :as settings]))

;; The templates are test/prompts/test/*.md.

(deftest fills-keys-and-dotted-paths
  (is (= "Good morning, Ann. You have 12 in pence."
         (prompts/render "test/greeting.md" {:name "Ann" :money {:purse 12 :unit "pence"}})))
  (testing "keys may be strings, and extra parameters are ignored"
    (is (= "Good morning, Ann. You have 12 in pence."
           (prompts/render "test/greeting.md" {"name" "Ann" "money" {"purse" 12 "unit" "pence"} :unused 1}))))
  (testing "a value is put in as it is, not filled again"
    (is (= "a {{b}} c" (prompts/fill "inline" "a {{x}} c" {:x "{{b}}"}))))
  (testing "spaces inside the braces are allowed, and $ in values is plain text"
    (is (= "costs $5" (prompts/fill "inline" "costs {{ price }}" {:price "$5"})))))

(deftest a-missing-key-throws-naming-the-template-and-key
  (let [e (try (prompts/render "test/greeting.md" {:name "Ann" :money {:purse 12}}) nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= {:template "test/greeting.md" :key "money.unit"} (ex-data e)))
    (is (re-find #"test/greeting.md.*money\.unit" (ex-message e))))
  (is (thrown? clojure.lang.ExceptionInfo (prompts/fill "inline" "{{x}}" {:x nil})))
  (is (thrown? clojure.lang.ExceptionInfo (prompts/render "test/no-such-template.md"))))

(deftest pack-first-rendering-does-not-mix-worlds
  (let [pack (settings/load-pack "settings/test")
        world {:state {:setting-pack pack}
               :clock {:start "2000-01-02T08:00" :minutes-per-round 30}}
        england (prompt/agent-preamble {})
        action find-person/action]
    (is (= "An agent in a test setting." (prompt/agent-preamble world)))
    (is (= england (prompt/agent-preamble {})))
    (is (= "Find someone at the test court; at most 80 characters."
           (a/description action world)))
    (is (= (str (a/description action world) " Query: returns information and does not end your turn (limited per turn). Once per turn.")
           (get-in (a/->tool action world) [:function :description])))
    (is (= "Find the person in a test setting." (find-person/instructions world)))
    (is (= "Visible appearance in a test setting, at most 600 characters."
           (get-in (people/appearance-request world {}) [:messages 0 :content])))
    (is (= "2000-01-02 08:30" (clock/world-label world 2)))
    (is (= "Sunday 2 January 2000, 08:30" (clock/world-label (update world :state dissoc :setting-pack) 2)))
    (is (= "2000-01-02" (prompts/example-date world)))
    (is (= "1970-01-01" (prompts/example-date {})))
    (testing "explicit parameters win; shared templates still render"
      (is (= "An agent in another era."
             (prompts/render world "agent/preamble.md" {:era "another era"})))
      (is (= "No newline at the end." (prompts/render world "test/no-newline.md" {}))))
    (testing "the snapshot is sufficient; rendering never reloads the pack"
      (with-redefs [settings/load-pack (fn [_] (throw (Exception. "unexpected reload")))]
        (is (= "An agent in a test setting." (prompt/agent-preamble world)))))
    (testing "catalogues and prompt text are not template parameters"
      (is (nil? (:positions (settings/parameters world))))
      (is (thrown? clojure.lang.ExceptionInfo
                   (prompts/render world "agent/preamble.md" {:era nil}))))))

(deftest judge-brief-includes-only-present-mechanics
  (let [brief rules/judge-brief
        world {:state {:setting "Test setting."}
               :space {:locations {}}}
        base (str "Test setting.\n\nAt the house, assume the everyday things the work needs "
                  "(cloths, paper, pans, salt, matches and the like) are to hand unless what you're shown says otherwise. "
                  "The things listed are the notable ones, not everything there is.")
        architect {:services #{:architect}}
        shop {:services #{:general-store} :delivery-profile "We deliver."}]
    (is (= base (brief world)))
    (is (= (str base "\n\n" (prompts/render "judges/brief-architect.md"))
           (brief (assoc-in world [:space :locations "office"] architect))))
    (is (= (str base "\n\n" (prompts/render "judges/brief-deliveries.md"))
           (brief (assoc-in world [:space :locations "shop"] shop))))
    (is (= (str base "\n\n" (prompts/render "judges/brief-architect.md")
                    "\n\n" (prompts/render "judges/brief-deliveries.md"))
           (brief (assoc-in world [:space :locations] {"office" architect "shop" shop}))))))

(deftest invitation-channels-and-format-examples-follow-the-world
  (let [world {:clock {:start "1780-06-12T08:00" :minutes-per-round 30}
               :space {:locations {}}}
        with-post (assoc-in world [:space :locations "post"] {:services #{:post-office}})
        doc #(a/description (first (visits/actions {})) %)]
    (is (not (clojure.string/includes? (visits/rules world) "by post")))
    (is (clojure.string/includes? (visits/rules with-post) "by post (at a post office)"))
    (is (not (clojure.string/includes? (doc world) "\"post\"")))
    (is (clojure.string/includes? (doc with-post) "\"post\" (you're at a post office)"))
    (is (clojure.string/includes? (doc world) "date (\"1780-06-12\")"))
    (testing "the return-date examples are the world's date"
      (is (clojure.string/includes? (hiring/take-leave-doc world) "\"1780-06-12\""))
      (is (clojure.string/includes? (hiring/return-reason world {:return-date "bad" :return-time "bad"}) "\"1780-06-12\"")))
    (is (= :errand-boy (deliveries/courier-position world "shop")))
    (is (= :runner (deliveries/courier-position
                    (assoc-in world [:state :setting-pack] {:courier-position :runner}) "shop")))))

(deftest one-final-newline-is-not-part-of-the-template
  (is (= "No newline at the end." (prompts/render "test/no-newline.md")))
  (is (= "A blank line follows.\n" (prompts/render "test/blank-line.md"))))

(deftest tool-hints-come-from-templates
  ;; Which hint templates a tool gets, in what order and with what numbers;
  ;; their wording is the templates' own, so it isn't pinned here.
  (let [tool #(get-in (a/->tool (merge {:name :x :doc "Do it."} %)) [:function :description])
        hint (fn [& names-and-params]
               (clojure.string/join " " (cons "Do it." (for [[n params] (partition-all 2 names-and-params)]
                                                         (prompts/render (str "docs/hints/" n ".md") (or params {}))))))]
    (is (= (hint "final") (tool {})))
    (is (= (hint "free" {} "once-per-turn") (tool {:kind :free :per-turn 1})))
    (is (= (hint "query" {} "times-per-turn" {:n 2}) (tool {:kind :query :per-turn 2})))
    (is (clojure.string/includes? (tool {:kind :query :per-turn 2}) "2"))
    (is (= (hint "act") (tool {:kind :act})))
    (is (= (hint "converse") (tool {:kind :converse})))
    (testing "an action's own :hint, a string or a function of the world"
      (is (= "Do it. Mine." (tool {:hint "Mine."})))
      (is (= (hint "speaking-turn") (tool {:hint (prompts/doc "docs/hints/speaking-turn.md")}))))))
