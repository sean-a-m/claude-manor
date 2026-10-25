(ns manor.scene-review-regressions-test
  "No-run regressions for independent review findings: geometry, paint and links."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hiccup2.core :as h]
            [manor.scene-layout :as layout]
            [manor.tools.scene :as scene]
            [manor.tools.scene-inspect :as inspect]
            [manor.tools.scene-preview :as preview]
            [manor.tools.web :as web]
            [manor.world :as w]))

(defn- crosses-interior? [[[ax ay] [bx by]] {:keys [x y w h]}]
  (cond
    (= ax bx) (and (< x ax (+ x w)) (< (max (min ay by) y) (min (max ay by) (+ y h))))
    (= ay by) (and (< y ay (+ y h)) (< (max (min ax bx) x) (min (max ax bx) (+ x w))))
    :else true))

(deftest later-cross-link-is-visible-without-moving-rooms
  (let [initial {:locations [{:id "a" :domain :h} {:id "b" :domain :h} {:id "c" :domain :h}]
                 :routes [{:from "a" :to "b" :travel 0} {:from "b" :to "c" :travel 0}]}
        current (update initial :routes conj {:from "a" :to "c" :travel 0})
        before (layout/layout {:initial initial})
        after (layout/layout {:initial initial :current current})
        connection (last (:connections after))
        middle (get-in after [:rooms "b" :global])
        snap {:world (w/new-world) :events [] :status {} :scenario {}}
        svg (str (h/html (scene/svg snap after)))
        basic (preview/svg after)]
    (is (= (:placement before) (:placement after)))
    (is (= :connector (:kind connection)))
    (is (> (count (:path connection)) 2) "route goes around the intervening room")
    (is (not-any? #(crosses-interior? % middle) (partition 2 1 (:path connection))))
    ;; Paths along shared walls and unavoidable straight fallbacks must not be
    ;; painted underneath fills. People remain above paths for safe selection.
    (is (< (.lastIndexOf svg "class=\"room\"")
           (.indexOf svg "class=\"connection connector\"")
           (.indexOf svg "class=\"room-people\"")))
    (is (< (.lastIndexOf basic "<rect") (.indexOf basic "<polyline")))
    (let [looped (layout/layout {:initial (update initial :routes conj {:from "b" :to "b" :travel 0})})
          loop-svg (str (h/html (scene/svg snap looped)))]
      (is (< (.lastIndexOf loop-svg "class=\"room\"") (.indexOf loop-svg "class=\"connection loop\""))))))

(deftest doors-use-the-shared-wall-overlap-in-either-direction
  (doseq [[a b expected]
          [[{:x 0 :y 0 :w 9 :h 1} {:x 0 :y 1 :w 1 :h 1} [0.5 1]]
           [{:x 0 :y 0 :w 1 :h 9} {:x 1 :y 0 :w 1 :h 1} [1 0.5]]
           [{:x -4 :y 3 :w 9 :h 1} {:x 3 :y 4 :w 2 :h 2} [4.0 4]]]]
    (let [room (fn [r] (assoc r :global r :region :h :panel :ground))
          rooms {"a" (room a) "b" (room b)}
          routes [{:from "a" :to "b" :travel 0 :open? true}
                  {:from "b" :to "a" :travel 0 :open? false}]
          connections (layout/connections rooms routes)]
      (is (layout/touching? a b))
      (doseq [connection connections]
        (is (= :door (:kind connection)))
        (is (= [expected expected] (:path connection)))
        (is (zero? (:length connection))))
      (is (= routes (mapv :route connections))))))

(defn- fixture [id]
  (let [world (assoc (w/new-world)
                     :agents {id {:id id :name id :role :gm :status :active :controller :human}}
                     :agent-order [id])]
    {:id "synthetic" :world (atom world) :events (atom []) :last-calls (atom {})
     :status (atom {:state :idle :mode :manual}) :input (atom nil)
     :scenario {:name :fixture} :actions [] :opts {}}))

(deftest inspector-their-view-links-decode-once-and-keep-agent-view-unchanged
  (doseq [id ["odd space" "odd+plus" "odd/slash" "odd%20literal" "odd \"<>&?[]+%" "é"]]
    (testing id
      (let [run (fixture id)
            world @(:world run)
            before [world @(:events run)]
            encoded (inspect/encode-id id)
            link (str "/agent/" encoded)
            fragment (str (h/html (inspect/fragment {:world world :events [] :scenario {}} [] "agent" id)))]
        (is (str/includes? fragment (str "href=\"" link "\"")))
        (with-redefs-fn {#'web/current-run (atom run)}
          (fn []
            (let [response (#'web/handler {:uri link :request-method :get})]
              (is (= 200 (:status response)))
              (is (= (:body (#'web/agent-page run id)) (:body response))))
            (doseq [malformed ["%" "%ZZ" "%2"]]
              (is (= 404 (:status (#'web/handler {:uri (str "/agent/" malformed) :request-method :get})))))))
        (is (= before [@(:world run) @(:events run)]))))))

(deftest retained-panel-keeps-its-existing-live-feed-behaviour
  (let [run (fixture "gm")]
    (with-redefs-fn {#'web/current-run (atom run)}
      (fn []
        (let [panel (:body (#'web/handler {:uri "/panel" :request-method :get}))
              script (:body (#'web/handler {:uri "/static/panel.js" :request-method :get}))]
          (is (str/includes? panel "src=\"/static/panel.js\""))
          (is (str/includes? script "document.getElementById('feed').insertAdjacentHTML('afterbegin', message.data);\n  refresh();"))
          (is (not (str/includes? script "children.length"))))))))
