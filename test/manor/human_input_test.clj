(ns manor.human-input-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hiccup2.core :as h]
            [jsonista.core :as json]
            [manor.actions :as a]
            [manor.engine :as e]
            [manor.io.store :as store]
            [manor.tools.human-input :as human]
            [manor.tools.web :as web]
            [manor.world :as w])
  (:import (java.io ByteArrayInputStream)
           (java.net URLEncoder)))

(use-fixtures :once (fn [t] (try (t) (finally (store/delete-test-runs!)))))

(def form-action
  {:name :form-test :kind :free :roles #{:player}
   :doc "Exercise fields safely: <script> is only text."
   :args [:map
          [:mode [:enum "a" "b"]]
          [:count [:int {:min 0 :max 9}]]
          [:hours {:optional true} [:double {:min 0.5 :max 6}]]
          [:flag {:optional true} :boolean]
          [:names [:vector {:min 1 :max 3} :string]]
          [:empty-lines [:vector :string]]
          [:message [:string {:min 1 :max 50}]]
          [:settings {:optional true} [:map [:level [:enum :low :high]]]]]
   :reply (fn [_ _ args] args)})

(defn start-human-run
  "Free fixture shared with the real-browser check; no panel opened here."
  []
  (let [run (e/start-run :prize {:llm :mock :offline? true :test? true :gm :off
                                :run-id (str "human-ui-" (random-uuid))})
        unlocked? (fn [world actor] (seq (:notes (w/agent world actor))))
        run (update run :actions concat
                    [form-action
                     {:name :after-note :kind :free :roles #{:player}
                      :doc "Available after making a note." :available? unlocked?}
                     {:name :before-note :kind :free :roles #{:player}
                      :doc "Available until making a note."
                      :available? (fn [world actor] (not (unlocked? world actor)))}
                     {:name :form-query :kind :query :roles #{:player}
                      :doc "Return a test result."
                      :query-fn (fn [_ _ _ _] {:value "queried"})}])]
    @(e/update-agent! run "p2" {:controller :human})
    (e/step! run)
    (assert (= :awaiting-human (e/wait-idle run 10000)))
    run))

(defn- post! [run params]
  (web/show! run)
  (#'web/handler {:uri "/cmd/act" :request-method :post
                 :body (ByteArrayInputStream.
                        (.getBytes (str/join "&" (for [[k v] params]
                                                   (str (name k) "=" (URLEncoder/encode (str v) "UTF-8"))))
                                   "UTF-8"))}))

(defn- next-input [run previous]
  (let [deadline (+ (System/currentTimeMillis) 5000)]
    (loop []
      (let [input @(:input run)]
        (cond
          (not= (:request-id previous) (:request-id input)) input
          (< (System/currentTimeMillis) deadline) (do (Thread/sleep 10) (recur))
          :else (throw (ex-info "Human input did not advance" {})))))))

(defn- submit! [run action args]
  (let [previous @(:input run)]
    (is (= 204 (:status (post! run {:agent "p2" :action action
                                    :request-id (:request-id previous)
                                    :args (if (string? args) args (json/write-value-as-string args))}))))
    (next-input run previous)))

(deftest schema-derived-fields
  (let [markup (str (h/html (human/fragment {:agent "odd +/<id>" :request-id "test"
                                           :tool-specs [(a/->tool form-action)
                                                        (a/->tool {:name :pass :doc "Wait."})]})))]
    (is (str/includes? markup "&lt;script&gt;"))
    (is (not (str/includes? markup "<script>")))
    (doseq [s ["data-kind=\"choice\"" "data-kind=\"boolean\"" "data-kind=\"lines\""
               "data-kind=\"json\"" "data-arg=\"empty-lines\"" "min=\"0\"" "max=\"9\""
               "step=\"any\"" "maxlength=\"50\"" "(required)" "(optional)"
               "No arguments needed." "Advanced: JSON arguments" "One entry per line"
               "odd%20%2B%2F%3Cid%3E"]]
      (is (str/includes? markup s) s))))

(deftest human-panel-end-to-end
  (let [run (start-human-run)]
    (try
      (testing "offered forms describe exactly the tools the human engine accepts"
        (let [input @(:input run)
              world (e/world run)
              events (e/events run)]
          (is (= (set (:tools input)) (set (map #(get-in % [:function :name]) (:tool-specs input)))))
          (is (some #{"before-note"} (:tools input)))
          (is (not (some #{"after-note" "done" "converse"} (:tools input))))
          (is (str/includes? (#'web/status-fragment run) "data-tool=\"form-test\""))
          (is (= world (e/world run)))
          (is (= events (e/events run)))))
      (testing "stale requests and wrong seats are rejected without delivering input"
        (is (= 409 (:status (post! run {:agent "p2" :action "pass" :request-id "stale"}))))
        (is (= 409 (:status (post! run {:agent "p1" :action "pass"}))))
        (is (not (realized? (:promise @(:input run))))))
      (testing "schema errors return feedback and keep the seat waiting"
        (is (str/includes? (:feedback (submit! run "form-test" {})) "invalid arguments"))
        (is (str/includes? (:feedback (submit! run "form-test" "not json")) "could not parse")))
      (testing "typed values, zero, false and empty lists survive decoding"
        (let [args {:mode "b" :count 0 :hours 1.5 :flag false :names ["p1" "p2"]
                    :empty-lines [] :message "A human action" :settings {:level "high"}}
              input (submit! run "form-test" args)]
          (is (= args (json/read-value (:feedback input) json/keyword-keys-object-mapper)))
          (is (= "p2" (:agent input)))))
      (testing "queries return results without ending the turn"
        (is (= {:value "queried"}
               (json/read-value (:feedback (submit! run "form-query" {})) json/keyword-keys-object-mapper))))
      (testing "a free action refreshes available tools, and legacy JSON posts still work"
        (let [previous @(:input run)]
          (is (= 204 (:status (post! run {:agent "p2" :action "note" :args "{\"text\":\"Remember this\"}"}))))
          (let [input (next-input run previous)]
            (is (some #{"after-note"} (:tools input)))
            (is (not (some #{"before-note"} (:tools input)))))))
      (testing "a tool no longer offered is rejected"
        (is (str/includes? (:feedback (submit! run "before-note" {})) "no tool called")))
      (testing "a parameterless final action ends the turn, and replay matches"
        (submit! run "pass" {})
        (is (= :idle (e/wait-idle run 10000)))
        (is (nil? @(:input run)))
        (is (= (e/world run) (w/replay (e/events run))))
        (is (str/includes? (#'web/status-fragment run) "last human action: done")))
      (finally
        ;; Ensure a failed assertion can't leave the executor waiting on a human.
        (when @(:input run) (e/act! run "p2" "pass" {}))
        (e/wait-idle run 10000)
        (e/stop-run run)))))
