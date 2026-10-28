(ns manor.prompts-test
  (:require [clojure.test :refer [deftest is testing]]
            [manor.prompts :as prompts]))

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

(deftest one-final-newline-is-not-part-of-the-template
  (is (= "No newline at the end." (prompts/render "test/no-newline.md")))
  (is (= "A blank line follows.\n" (prompts/render "test/blank-line.md"))))
