(ns manor.scene-integration-test
  "Cross-seam, read-only rendering and optional-hook regression checks."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [manor.engine :as e]
            [manor.mechanics.items :as items]
            [manor.scene-boundary-probe :as probe]
            [manor.tools.scene :as scene]
            [manor.tools.scene-inspect :as inspect]
            [manor.tools.scene-preview :as preview]
            [manor.tools.web :as web]
            [manor.world :as w]))

(defn- request [uri & [query]]
  (#'web/handler {:uri uri :query-string query :request-method :get}))

(defn render-run!
  "Exercise actual GET handlers without a port, plus pure preview rendering.
  Called at every compared event prefix, including before the first round."
  [run]
  (web/show! run)
  (doseq [uri ["/" "/panel" "/partial/status" "/partial/scene" "/partial/scene-state"
               "/world" "/static/scene.js" "/static/scene.css"]]
    (when-not (= 200 (:status (request uri)))
      (throw (ex-info "Failed presentation route" {:uri uri}))))
  (let [world (e/world run)
        selections (concat (for [agent (w/agents-in-order world)] ["agent" (:id agent)])
                           (for [[id _] (:rooms (scene/presentation (scene/snapshot run)))] ["place" id])
                           (for [item (items/items-where world (constantly true))] ["item" (:id item)])
                           [["place" "missing /<&>"]])]
    (doseq [[kind id] selections]
      (let [response (request (str "/partial/inspect/" kind "/" (inspect/encode-id id)))
            state (request "/partial/scene-state" (str "kind=" kind "&id=" (inspect/encode-id id)))
            fragments (json/read-value (:body state) json/keyword-keys-object-mapper)]
        (when-not (and (= 200 (:status response) (:status state))
                       (= {:kind kind :id id} (:selection fragments))
                       (= (:body response) (:inspector fragments)))
          (throw (ex-info "Inspector and coherent refresh disagree" {:kind kind :id id})))))
    (doseq [agent (w/active-agents world)]
      (request (str "/agent/" (:id agent)))
      (request "/panel" (str "as=" (inspect/encode-id (:id agent)))))
    (preview/svg (scene/presentation (scene/snapshot run)))))

(deftest rendering-and-hooks-preserve-complete-mock-evidence
  ;; Household crosses commissioning/completion; quiz proves the no-space seam.
  ;; The standalone base comparison also covers every bundled scenario.
  (doseq [[scenario rounds] [[:household 13] [:quiz 2]]]
    (testing (name scenario)
      (is (= (probe/comparable (probe/capture scenario rounds :remove nil))
             (probe/comparable (probe/capture scenario rounds :keep render-run!)))
          "Every prefix, visible event, agent view, model request and log reply agrees"))))

(deftest comparison-keeps-meaningful-request-differences
  (let [a {:ctx {:role :judge :world {:seq 10} :job {:id "a"}} :tools [{:name "verdict"}]}
        b (assoc-in a [:ctx :job :id] "b")
        agent {:ctx {:role :guest} :messages ["private"]}
        compare! #(probe/comparable {:requests %})]
    (is (= (compare! [a b agent]) (compare! [b a agent])))
    (doseq [changed [[a agent b] [a a b agent] [a (assoc b :tools []) agent]
                     [a (assoc-in b [:ctx :world :seq] 11) agent]
                     [a b (assoc agent :messages ["different"])]]]
      (is (not= (compare! [b a agent]) (compare! changed))))))

(deftest offline-preview-writes-only-its-requested-output
  (let [output (java.io.File/createTempFile "manor-scene-preview-" ".svg")]
    (try
      ;; If preview tried to start a run it would fail, rather than silently
      ;; exercising simulation initialization as part of rendering.
      (with-redefs [e/start-run (fn [& _] (throw (ex-info "Preview started a run" {})))]
        (let [result (preview/scene-preview! "resources/worlds/claudeworth.edn" (str output))]
          (is (= (str output) (:path result)))
          (is (pos? (.length output)))
          (is (map? (:quality result)))))
      (finally (io/delete-file output)))))
