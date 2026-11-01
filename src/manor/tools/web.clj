(ns manor.tools.web
  "A small control panel: run controls, roster, pending approvals, human
  input, and a live event feed (server-sent events). `?as=<agent-id>` shows
  the feed exactly as that agent sees it, which is useful for checking
  private information doesn't leak."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [jsonista.core :as json]
            [manor.actions :as a]
            [manor.clock :as clock]
            [manor.engine :as e]
            [manor.engine.models :as models]
            [manor.mechanics.positions :as positions]
            [manor.mechanics.space :as space]
            [manor.narrate :as narrate]
            [manor.prompt :as prompt]
            [manor.tools.human-input :as human-input]
            [manor.tools.scene :as scene]
            [manor.tools.scene-inspect :as inspect]
            [manor.world :as w]
            [org.httpkit.server :as hk])
  (:import (java.net URLDecoder)))

(defonce ^:private server (atom nil))
(defonce ^:private current-run (atom nil))
;; One disposable placement entry, never persisted or visible to the simulation.
(defonce ^:private scene-placement (atom nil))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- parse-params [s]
  (if (str/blank? s)
    {}
    (into {} (for [pair (str/split s #"&")
                   :let [[k v] (str/split pair #"=" 2)]]
               [(keyword (URLDecoder/decode k "UTF-8")) (URLDecoder/decode (or v "") "UTF-8")]))))

(defn- form-params [req]
  (parse-params (some-> (:body req) slurp)))

(defn- query-params [req] (parse-params (:query-string req)))

(defn- html [& body] (str (h/html body)))

(defn- page [title & body]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (str "<!doctype html>"
              (h/html
               [:html
                [:head [:meta {:charset "utf-8"}] [:title title]
                 [:style (h/raw "
body{font:14px/1.4 system-ui,sans-serif;margin:0;background:#16181d;color:#ddd}
a{color:#8cf} header{padding:8px 14px;background:#20232b;display:flex;gap:16px;align-items:center}
main{display:grid;grid-template-columns:420px 1fr;gap:14px;padding:14px} main>*{min-width:0}
.scroll-x{overflow-x:auto} button.small{padding:1px 6px;font-size:12px;white-space:nowrap}
section{background:#20232b;border-radius:6px;padding:10px;margin-bottom:12px}
h2{font-size:13px;text-transform:uppercase;color:#999;margin:0 0 8px}
table{border-collapse:collapse;width:100%} td,th{padding:3px 6px;text-align:left;border-bottom:1px solid #2c3039}
button{background:#3a6df0;color:#fff;border:0;border-radius:4px;padding:4px 10px;cursor:pointer}
button.alt{background:#444} input,select,textarea{background:#111;color:#ddd;border:1px solid #444;border-radius:4px;padding:3px}
#feed{list-style:none;margin:0;padding:0;font-family:ui-monospace,monospace;font-size:12.5px;max-height:80vh;overflow:auto}
#feed li{padding:2px 4px;border-bottom:1px solid #262a33} .vis{color:#888;font-size:11px}
.private{background:#2a2536} .gm{background:#23302a} .system{color:#777}
pre{white-space:pre-wrap;font-size:12px;background:#111;padding:8px;border-radius:4px;max-height:60vh;overflow:auto}
form.inline{display:inline}
.human-tool,.human-fields{border:0;padding:0;margin:8px 0;min-width:0}
.human-tool[hidden]{display:none}
.human-field{margin:10px 0}.human-field label,#human-action>label{display:block;margin-bottom:3px}
.human-field textarea,.human-advanced textarea{box-sizing:border-box;width:100%;resize:vertical}
.human-description,.human-feedback{white-space:pre-wrap;overflow-wrap:anywhere}
.human-help{font-size:12px;color:#aaa;margin:5px 0}.human-advanced{margin:10px 0}
.human-error,#panel-client-error{color:#f88;white-space:pre-wrap}
button:disabled{opacity:.5;cursor:default}")]]
                [:body body]]))})

(defn- vis-label [v]
  (cond (= v :all) "all" (set? v) (str/join "," (sort v)) :else (name v)))

(defn- vis-class [v]
  (cond (= v :all) "" (set? v) "private" (= v :gm) "gm" :else "system"))

(defn- event-li [event names]
  [:li {:class (vis-class (:visible-to event))}
   [:span.vis (format "#%d r%d%s [%s] " (:id event) (:round event)
                      (if (get names [:clock]) (str " " (narrate/at names (:round event))) "")
                      (vis-label (:visible-to event)))]
   (narrate/describe event names)])

(defn- viewer [req]
  (let [as (:as (query-params req))]
    (if (str/blank? as) :operator as)))

(defn- feed-events [run viewer n]
  (->> (e/events run)
       (filter #(w/visible-to? % viewer))
       (take-last n)
       reverse))

;; ---------------------------------------------------------------------------
;; Map

(defn- clip [s n] (if (> (count s) n) (str (subs s 0 (dec n)) "…") s))

(defn- people-at
  "Names of the people at `loc`, with what they are busy with."
  [world loc]
  (for [id (sort (space/present world loc))
        :let [{:keys [name busy]} (w/agent world id)]]
    (if busy (str name " (" (:activity busy) ")") name)))

(defn- travel-label [world travel]
  (if-let [c (:clock world)]
    (str (* travel (:minutes-per-round c)) " min")
    (str travel " rounds")))

(defn- edges
  "Each open or closed route once: [[a b] travel open?]."
  [world]
  (distinct (for [[from tos] (get-in world [:space :routes])
                  [to {:keys [travel open?]}] tos]
              [(sort [from to]) travel open?])))

(defn- circle-layout
  "Locations on a circle (small worlds without areas)."
  [world locs]
  (let [n (count locs)]
    {:width 400 :height 300
     :pos (into {} (map-indexed (fn [i id]
                                  (let [a (- (* 2 Math/PI (/ i n)) (/ Math/PI 2))]
                                    [id [(+ 200 (* 110 (Math/cos a))) (+ 150 (* 110 (Math/sin a)))]]))
                                locs))}))

(defn- area-order
  "A group's locations, neighbours near each other (breadth-first)."
  [world ids]
  (let [in-group (set ids)]
    (loop [order [] seen #{} todo []]
      (cond
        (seq todo) (let [[x & more] todo
                         nxt (for [{:keys [to]} (space/exits world x)
                                   :when (and (in-group to) (not (seen to)))]
                               to)]
                     (recur (conj order x) (into seen nxt) (into (vec more) nxt)))
        :else (if-let [start (first (remove seen (sort ids)))]
                (recur order (conj seen start) [start])
                order)))))

(def ^:private cols 3)
(def ^:private row-h 36)
(def ^:private head-h 20)

(defn- grid-layout
  "Locations grouped by :domain, each group a labelled grid, largest first."
  [world locs]
  (let [groups (->> (group-by #(or (some-> (:domain (space/location world %)) name) "other") locs)
                    (sort-by (fn [[d ids]] [(- (count ids)) d])))
        col-w (/ 400 cols)]
    (loop [[[domain ids] & more] groups, top 0, pos {}, labels []]
      (if-not domain
        {:width 400 :height (+ top 20) :pos pos :labels labels}
        (let [order (area-order world ids)
              rows (long (Math/ceil (/ (count order) cols)))
              pos (into pos (map-indexed (fn [i id]
                                           [id [(+ 12 (* col-w (mod i cols)))
                                                (+ top head-h 10 (* row-h (quot i cols)))]])
                                         order))]
          (recur more (+ top head-h (* rows row-h) 6) pos
                 (conj labels [domain (+ top 14)])))))))

(defn- map-svg
  "Locations, routes as lines (dashed when closed; journeys labelled with
  their length), and who is where. Worlds whose locations have a :domain
  are drawn as one grid per domain; others on a circle."
  [world]
  (let [locs (sort (keys (get-in world [:space :locations])))
        grid? (some #(:domain (space/location world %)) locs)
        {:keys [width height pos labels]} (if grid? (grid-layout world locs) (circle-layout world locs))
        travellers (filter :travel (w/active-agents world))
        arrives #(if-let [c (:clock world)] (clock/world-label world %) (str "r" %))]
    [:svg {:width width :height height :style "background:#111;border-radius:4px"}
     (for [[domain y] labels]
       [:text {:x 4 :y y :fill "#999" :font-size 11} (str/upper-case domain)])
     (for [[[a b] travel open?] (edges world)
           :let [[x1 y1] (pos a) [x2 y2] (pos b)]
           :when (and x1 x2)]
       [:g
        [:line {:x1 x1 :y1 y1 :x2 x2 :y2 y2 :stroke (cond (not open?) "#733" (zero? travel) "#3a3f4d" :else "#557")
                :stroke-width (if (zero? travel) 1 2) :stroke-dasharray (if open? "" "5,4")}]
        (when (or (> travel 1) (and grid? (pos? travel)))
          [:text {:x (/ (+ x1 x2) 2) :y (- (/ (+ y1 y2) 2) 4) :fill "#889" :font-size 10 :text-anchor "middle"}
           (travel-label world travel)])])
     (for [id locs
           :let [[x y] (pos id)
                 people (people-at world id)]]
       (if grid?
         [:g
          [:circle {:cx x :cy y :r 5 :fill "#3a6df0"}]
          [:text {:x (+ x 9) :y (+ y 4) :fill "#ddd" :font-size 11} (clip (:name (space/location world id)) 20)]
          (when (seq people)
            [:text {:x (+ x 9) :y (+ y 17) :fill "#9c9" :font-size 10} (clip (str/join ", " people) 24)])]
         [:g
          [:circle {:cx x :cy y :r 7 :fill "#3a6df0"}]
          [:text {:x x :y (- y 12) :fill "#ddd" :font-size 12 :text-anchor "middle"}
           (:name (space/location world id))]
          (map-indexed (fn [i p] [:text {:x x :y (+ y 22 (* i 13)) :fill "#9c9" :font-size 11 :text-anchor "middle"} p])
                       people)]))
     (map-indexed (fn [i a]
                    [:text {:x 8 :y (- height 8 (* i 13)) :fill "#fc8" :font-size 11}
                     (str (:name a) " travelling to " (get-in a [:travel :to]) " (arrives " (arrives (get-in a [:travel :arrives])) ")")])
                  travellers)]))

;; ---------------------------------------------------------------------------
;; Fragments

(defn- cmd-button [cmd label & [fields cls]]
  [:form.inline {:method "post" :action (str "/cmd/" cmd) :onsubmit "return send(this)"}
   (for [[k v] fields] [:input {:type "hidden" :name (name k) :value v}])
   [:button {:class cls} label]])

(defn- controller-switch
  "A button handing `ag`'s seat to the other controller, for seats held by
  a model or a human. `onsubmit` is the page's script for posting it."
  [ag onsubmit]
  (when (#{:llm :human} (:controller ag))
    (let [to (if (= :human (:controller ag)) "llm" "human")]
      [:form.inline {:method "post" :action "/cmd/controller" :onsubmit onsubmit}
       [:input {:type "hidden" :name "agent" :value (:id ag)}]
       [:input {:type "hidden" :name "to" :value to}]
       [:button.alt.small (str "→ " to)]])))

(defn- whereabouts
  "Where `ag` is or is going, or their status when they aren't active, plus
  what keeps them busy."
  [ag]
  (list (cond (not= :active (:status ag)) (name (:status ag))
              (:travel ag) (str "→ " (get-in ag [:travel :to]))
              :else (or (:location ag) ""))
        (when-let [b (:busy ag)] [:div.vis (:activity b)])))

(defn- keyed-phrases [m]
  (str/join "; " (for [[k v] m] (str (name k) ": " v))))

;; ---------------------------------------------------------------------------
;; Relations (manor.mechanics.relations): operator views of judged feelings

(defn- when-said [world round]
  (if (:clock world) (clock/world-label world round) (str "r" round)))

(defn- agent-name [world id] (:name (w/agent world id) id))

(defn- feeling-line [world {:keys [round feeling because]}]
  [:div [:b feeling] ", because " because " " [:span.vis (when-said world round)]])

(defn- feelings-section
  "An agent's feelings toward others, every kept entry latest first, and the
  latest feeling each other person has toward them."
  [world id]
  (let [toward (sort-by #(agent-name world (key %)) (:relations (w/agent world id)))
        from (sort-by first (for [ag (w/agents-in-order world)
                                  :let [entries (get-in ag [:relations id])]
                                  :when (seq entries)]
                              [(:name ag) (:id ag) (peek entries)]))]
    [:section [:h2 "Feelings · private, judged"]
     [:h3 "Toward others"]
     (if (seq toward)
       [:table (for [[other entries] toward]
                 [:tr [:th [:a {:href (str "/agent/" other)} (agent-name world other)]]
                  [:td (map #(feeling-line world %) (reverse entries))]])]
       [:div.vis "None yet."])
     [:h3 "Others toward them (latest)"]
     (if (seq from)
       [:table (for [[nm other entry] from]
                 [:tr [:th [:a {:href (str "/agent/" other)} nm]] [:td (feeling-line world entry)]])]
       [:div.vis "None yet."])]))

(defn- relations-page
  "Everyone's latest feeling toward everyone else: rows feel, columns are
  felt toward, each only those with any entry. A cell's title holds the
  earlier entries."
  [run]
  (let [world (e/world run)
        holders (filter #(seq (:relations %)) (w/agents-in-order world))
        felt (set (mapcat (comp keys :relations) holders))
        people (filter #(felt (:id %)) (w/agents-in-order world))]
    (page "manor - relations"
          [:header [:a {:href "/panel"} "back"] [:b "relations"]
           [:span.vis "row feels toward column; latest entry, earlier ones on hover"]]
          ;; The table keeps its natural width, fixed-width cells and all, so the section scrolls
          ;; sideways; the names column stays put.
          [:style (h/raw "
table.relations{width:max-content} .relations td>div,.relations th>div{width:16em}
.relations th,.relations td{vertical-align:top}
.relations tr>th:first-child{position:sticky;left:0;background:#20232b;z-index:1}
.relations tr>th:first-child>div{width:10em}")]
          [:main {:style "grid-template-columns:1fr"}
           [:section.scroll-x
            (if (empty? holders)
              [:div.vis "Nobody has any judged feelings yet."]
              [:table.relations
               [:tr [:th [:div]] (for [p people] [:th [:div [:a {:href (str "/agent/" (:id p))} (:name p)]]])]
               (for [holder holders]
                 [:tr [:th [:div [:a {:href (str "/agent/" (:id holder))} (:name holder)]]]
                  (for [p people
                        :let [entries (get-in holder [:relations (:id p)])]]
                    [:td {:title (str/join "\n" (for [{:keys [round feeling because]} (reverse (butlast entries))]
                                                  (str (when-said world round) ": " feeling ", because " because)))}
                     [:div
                      (cond (= (:id p) (:id holder)) [:span.vis "—"]
                            (seq entries) (list (feeling-line world (peek entries))
                                                (when (next entries) [:div.vis (str (count entries) " entries")]))
                            :else "")]])])])]])))

(defn- status-fragment [run]
  (let [world (e/world run)
        {:keys [state mode remaining acting awaiting pause-reasons error last-human-feedback]} (e/status run)
        input @(:input run)
        ;; the scenario's roles, and the world's positions (manor.mechanics.positions)
        roles (sort (map name (keys (dissoc (merge (:roles (:scenario run)) (get-in world [:state :positions])) :gm))))]
    (html
     [:section
      [:h2 "Run"]
      [:div [:b (:id run)] " - round " [:b (:round world)]
       (when-let [c (:clock world)] (list " - " [:b (clock/world-label world (:round world))]))
       " - " (name state) " / " (name mode)
       (when remaining (str " (" remaining " left)"))
       (format " - cost $%.4f" (double (w/total-cost world)))]
      (when (seq acting) [:div "acting: " (str/join ", " acting)])
      (when (seq pause-reasons) [:div {:style "color:#fc8"} "paused: " (str/join "; " pause-reasons)])
      (when error [:div {:style "color:#f88"} "error: " error])
      [:div {:style "margin-top:6px"}
       (cmd-button "step" "Step") " "
       [:form.inline {:method "post" :action "/cmd/auto" :onsubmit "return send(this)"}
        [:input {:name "n" :size 3 :placeholder "∞"}] " " [:button "Auto"]] " "
       (cmd-button "pause" "Pause" nil "alt")]]

     (when input (human-input/fragment input))
     (when (and (not input) last-human-feedback)
       [:section [:div "last human action: " last-human-feedback]])

     [:section
      [:h2 "Roster"]
      ;; Narrow columns only; aspects, inner state and model are on each agent's page.
      [:div.scroll-x
       [:table
        [:tr [:th "who"] [:th "where"] [:th "controller"] [:th "cost"]]
        (for [ag (w/agents-in-order world)
              :let [u (get-in world [:usage (:id ag)])]]
          [:tr
           [:td [:a {:href (str "/agent/" (:id ag))} (:id ag)] [:div.vis (name (:role ag))]]
           [:td (whereabouts ag)]
           [:td [:div (name (:controller ag))] (controller-switch ag "return send(this)")]
           [:td (if u (format "$%.4f" (double (:cost u))) "")]])]]]

     (when (space/enabled? world)
       [:section [:h2 "Map"] (map-svg world)])

     (let [pending (:pending-bridges world)]
       (when (seq pending)
         [:section
          [:h2 "Awaiting approval"]
          (for [[req-id {:keys [bridge requester bridge-args status]}] pending]
            [:div {:style "margin-bottom:6px"}
             [:b req-id] " " (narrate/kw-str bridge) " from " requester " - " (name status)
             [:pre (with-out-str (pp/pprint bridge-args))]
             (when (= status :pending)
               [:span (cmd-button "approve" "Approve" {:req req-id}) " "
                (cmd-button "deny" "Deny" {:req req-id} "alt")])])]))

     [:section
      [:h2 "Operator"]
      [:form {:method "post" :action "/cmd/spawn" :onsubmit "return send(this)"}
       [:select {:name "role"} (for [r roles] [:option r])] " "
       [:input {:name "name" :size 8 :placeholder "name"}] " "
       [:select {:name "controller"} [:option "llm"] [:option "human"]] " "
       [:button "Spawn"]
       [:div [:input {:name "persona" :size 40 :placeholder "persona (optional)"}]]
       [:div [:input {:name "model" :size 40 :placeholder "model (optional)"}]]]
      [:form {:method "post" :action "/cmd/announce" :onsubmit "return send(this)" :style "margin-top:6px"}
       [:input {:name "text" :size 36 :placeholder "announcement to everyone"}] " " [:button "Announce"]]])))

;; ---------------------------------------------------------------------------
;; Pages

(defn- panel-page [run req]
  (let [world (e/world run)
        v (viewer req)
        names (narrate/names world)]
    (page (str "manor - " (:id run))
          [:header [:b "manor"] [:span (name (:name (:scenario run)))]
           [:a {:href "/"} "scene"]
           [:a {:href "/world"} "world state"]
           (when (:relations (:scenario run)) [:a {:href "/relations"} "relations"])
           [:form.inline {:method "get"}
            "feed as: "
            [:select {:name "as" :onchange "this.form.submit()"}
             [:option {:value ""} "operator (everything)"]
             [:option {:value "gm" :selected (= v "gm")} "gm"]
             (for [ag (w/agents-in-order world) :when (not= :gm (:role ag))]
               [:option {:value (:id ag) :selected (= v (:id ag))} (:id ag)])]]]
          [:main
           [:div [:div#panel-client-error {:role "alert"}]
            [:div#status (h/raw (status-fragment run))]]
           [:section [:h2 "Events" (when (string? v) (str " visible to " v))]
            [:ul#feed (for [ev (feed-events run (if (= v "gm") :gm v) 300)] (event-li ev names))]]]
          [:script {:src "/static/panel.js" :defer true}])))

(defn- scene-fragments
  ([run] (scene-fragments run nil))
  ([run selection]
   ;; Read completed calls first: never pair newer call evidence with an older
   ;; world prefix. The log may lag an in-flight turn; rendering never waits on it.
   (let [calls (when selection (inspect/read-calls run))
         snap (assoc (scene/snapshot run) :opts (:opts run))
         result (scene/presentation snap scene-placement)
         names (narrate/names (:world snap))]
     (cond-> {:scene (html (scene/svg snap result))
              :toolbar (html (scene/toolbar snap))
              :navigation (html (scene/navigation result))
              :away (html (scene/away-strip snap result))
              :feed (html (for [event (reverse (take-last 70 (:events snap)))] (event-li event names)))}
       selection (assoc :selection selection
                        :inspector (html (inspect/fragment snap calls (:kind selection) (:id selection))))))))

(defn- scene-page [run]
  (let [fragments (scene-fragments run)]
    (page (str "manor scene - " (:id run))
          [:link {:rel "stylesheet" :href "/static/scene.css"}]
          [:header [:b "manor"] [:span (name (:name (:scenario run)))]
           [:a {:href "/panel"} "panel"] [:a {:href "/world"} "world state"]
           (when (:relations (:scenario run)) [:a {:href "/relations"} "relations"])]
          [:main#scene-page {:data-inspect-base "/partial/inspect/"}
           [:div#scene-toolbar (h/raw (:toolbar fragments))]
           [:div#scene-client-error {:role "status"}]
           [:section#scene-stage
            [:div#scene-navigation (h/raw (:navigation fragments))]
            [:div#scene-container (h/raw (:scene fragments))]
            [:div#scene-away (h/raw (:away fragments))]]
           [:section#scene-sidebar [:h2 "Selection"]
            [:div#inspector {:aria-live "polite"} "Select a person or place."]]
           [:section#scene-feed [:h2 "Recent events · operator"]
            [:ul#feed (h/raw (:feed fragments))]]]
          [:script {:src "/static/scene.js" :defer true}])))

(def ^:private static-assets
  {"/static/panel.js" ["manor/panel.js" "text/javascript; charset=utf-8"]
   "/static/scene.js" ["manor/scene.js" "text/javascript; charset=utf-8"]
   "/static/scene.css" ["manor/scene.css" "text/css; charset=utf-8"]})

(defn- asset-response [uri]
  (let [[path content-type] (get static-assets uri)]
    {:status 200 :headers {"Content-Type" content-type}
     :body (slurp (io/resource path))}))

(defn- agent-page [run id]
  (let [world (e/world run)
        ag (w/agent world id)
        call (get @(:last-calls run) id)]
    (if-not ag
      {:status 404 :body "no such agent"}
      (page (str "manor - " id)
            [:header [:a {:href "/panel"} "back"] [:b (:name ag)] [:span (name (:role ag))]]
            [:main {:style "grid-template-columns:1fr 1fr"}
             [:div
              [:section [:h2 "Seat"]
               [:table
                [:tr [:th "role"] [:td (name (:role ag))]]
                [:tr [:th "where"] [:td (whereabouts ag)]]
                [:tr [:th "controller"]
                 [:td (name (:controller ag)) " "
                  (controller-switch ag "fetch(this.action,{method:'POST',body:new URLSearchParams(new FormData(this))}).then(()=>location.reload());return false")]]
                [:tr [:th "model"] [:td (models/model-label (:opts run) ag)]]
                (when (seq (:aspects ag)) [:tr [:th "aspects"] [:td (keyed-phrases (:aspects ag))]])
                (when (seq (:inner ag)) [:tr [:th "inner"] [:td (keyed-phrases (:inner ag))]])]
               [:div.vis "A switch lands between rounds; a seat waiting for your input stays yours until you end its turn."]]
              (when (and (:relations (:scenario run)) (not= :gm (:role ag)))
                (feelings-section world id))
              [:section [:h2 "Their view of the world"]
               [:pre (if (= :gm (:role ag))
                       "The GM sees the full world state."
                       (prompt/->pretty-json (prompt/agent-view (:scenario run) world id)))]]
              (when (seq (:goals ag))
                [:section [:h2 "Goals"]
                 [:pre (str "reviewed r" (:goals-reviewed ag) "\n"
                            (str/join "\n" (map #(str "- " %) (:goals ag))))]])
              [:section [:h2 "Notes"]
               [:pre (str/join "\n" (map #(str "[r" (:round %) "] " (:text %)) (:notes ag)))]]
              [:section [:h2 "Tools"]
               [:pre (str/join "\n" (map #(str (name (:name %)) " (" (name (a/kind %)) ")")
                                        (a/available (a/for-role (:actions run) (:role ag) (positions/capabilities world id))
                                                     world id)))]]
              [:section [:h2 "Usage"] [:pre (pr-str (get-in world [:usage id]))]]]
             [:div
              [:section [:h2 "Last model call" (when call (str " (round " (:round call) ", " (:model call) ")"))]
               (if call
                 [:div
                  (for [m (:messages call)]
                    [:div [:b (:role m)]
                     [:pre (or (:content m) (json/write-value-as-string (:tool_calls m)))]])
                  [:b "response" (when-let [fr (:finish-reason call)] (str " (" fr ")"))]
                  [:pre (json/write-value-as-string (:response call))]]
                 [:div "none yet"])]]]))))

(defn- world-page [run]
  (page "manor - world"
        [:header [:a {:href "/panel"} "back"] [:b "world state"]]
        [:main {:style "grid-template-columns:1fr"}
         [:pre (with-out-str (pp/pprint (dissoc (e/world run) :usage)))]]))

;; ---------------------------------------------------------------------------
;; Commands and streaming

(defn- human-command
  "Reject stale browser forms before delivering to the engine. Legacy JSON
  clients without a request id still use the same act! endpoint."
  [run p]
  (let [input @(:input run)]
    (if (and (:request-id p) (not= (:request-id p) (:request-id input)))
      {:status 409 :body "This turn has changed. Refresh the panel before sending an action."}
      (let [result (e/act! run (:agent p) (:action p) (if (str/blank? (:args p)) {} (:args p)))]
        (if-let [error (:error result)]
          {:status 409 :body error}
          {:status 204})))))

(defn- command [run cmd p]
  (if (= cmd "act")
    (human-command run p)
    (do
      (case cmd
        "step" (e/step! run)
        "auto" (e/auto! run (some-> (:n p) str/trim not-empty parse-long))
        "pause" (e/pause! run)
        "approve" (e/approve! run (:req p))
        "deny" (e/deny! run (:req p))
        "controller" (when (#{"llm" "human"} (:to p))
                       (e/update-agent! run (:agent p) {:controller (keyword (:to p))}))
        "announce" (when-not (str/blank? (:text p)) (e/announce! run (:text p)))
        "spawn" (when-not (str/blank? (:name p))
                  (e/spawn! run (cond-> {:role (keyword (:role p)) :name (:name p)
                                        :controller (keyword (or (:controller p) "llm"))}
                                 (not (str/blank? (:persona p))) (assoc :persona (:persona p))
                                 (not (str/blank? (:model p))) (assoc :model (:model p)))))
        nil)
      {:status 204})))

(defn- sse-message [kind data]
  (str "event: " kind "\ndata: " (str/replace data "\n" "\ndata: ") "\n\n"))

(defn- stream [run req]
  (let [v (viewer req)
        v (if (= v "gm") :gm v)
        k (gensym "sse")]
    (hk/as-channel req
                   {:on-open (fn [ch]
                               (hk/send! ch {:status 200
                                             :headers {"Content-Type" "text/event-stream"
                                                       "Cache-Control" "no-cache"}} false)
                               (e/listen! run k
                                          (fn [{:keys [kind event]}]
                                            (case kind
                                              :event (when (w/visible-to? event v)
                                                       (hk/send! ch (sse-message "ev" (html (event-li event (narrate/names (e/world run))))) false))
                                              :status (hk/send! ch (sse-message "status" "x") false)))))
                    :on-close (fn [_ _] (e/unlisten! run k))})))

(defn- decode-path-id [encoded]
  (when encoded
    (try (URLDecoder/decode (str/replace encoded "+" "%2B") "UTF-8")
         (catch IllegalArgumentException _ nil))))

(defn- inspect-response [run uri]
  (let [[_ kind encoded] (re-matches #"/partial/inspect/(agent|place|item)/(.+)" uri)
        id (decode-path-id encoded)]
    {:status (if id 200 404)
     :headers {"Content-Type" "text/html; charset=utf-8" "Cache-Control" "no-store"}
     :body (if id
             (let [calls (inspect/read-calls run)
                   snap (assoc (scene/snapshot run) :opts (:opts run))]
               (html (inspect/fragment snap calls kind id)))
             "Selection no longer available.")}))

(defn- handler [req]
  (let [run @current-run
        uri (:uri req)]
    (cond
      (nil? run) {:status 503 :body "no run loaded"}
      (= uri "/") (scene-page run)
      (= uri "/panel") (panel-page run req)
      (= uri "/partial/scene") (let [snap (scene/snapshot run)]
                                  {:status 200 :headers {"Content-Type" "image/svg+xml; charset=utf-8" "Cache-Control" "no-store"}
                                   :body (html (scene/svg snap (scene/presentation snap scene-placement)))})
      (= uri "/partial/scene-state") {:status 200 :headers {"Content-Type" "application/json; charset=utf-8" "Cache-Control" "no-store"}
                                        :body (let [{:keys [kind id]} (query-params req)]
                                                (json/write-value-as-string
                                                 (scene-fragments run (when (and (#{"agent" "place" "item"} kind) id)
                                                                        {:kind kind :id id}))))}
      (str/starts-with? uri "/partial/inspect/") (inspect-response run uri)
      (contains? static-assets uri) (asset-response uri)
      (= uri "/partial/status") {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"}
                                 :body (status-fragment run)}
      (= uri "/stream") (stream run req)
      (= uri "/world") (world-page run)
      (= uri "/relations") (relations-page run)
      (str/starts-with? uri "/agent/") (if-let [id (decode-path-id (subs uri 7))]
                                         (agent-page run id)
                                         {:status 404 :body "no such agent"})
      (and (= :post (:request-method req)) (str/starts-with? uri "/cmd/"))
      (command run (subs uri 5) (form-params req))
      :else {:status 404 :body "not found"})))

(defn show!
  "Point the panel at `run`."
  [run]
  (reset! scene-placement nil)
  (reset! current-run run))

(defn start!
  "Start the panel on `port` (default 8080), showing `run`."
  ([run] (start! run 8080))
  ([run port]
   (show! run)
   (when-let [stop @server] (stop))
   (reset! server (hk/run-server #'handler {:port port :ip "127.0.0.1"}))
   (println (str "Control panel: http://localhost:" port))
   port))

(defn stop! []
  (when-let [stop @server] (stop) (reset! server nil)))
