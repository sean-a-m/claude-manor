(ns manor.viewer.web
  "The viewer's HTTP routes.

  Server-side rendered: every page is a plain GET with query parameters, so
  links are shareable and the browser needs no JavaScript. A request only reads
  files inside the run root (path traversal is checked in `data/resolve-run-dir`)."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [manor.viewer.data :as data]
            [manor.viewer.render :as render]
            [org.httpkit.server :as http]))

(def page-size 200)

;;; ---------------------------------------------------------------------------
;;; query strings

(defn parse-query [qs]
  (let [params (into {}
                     (for [pair (remove str/blank? (str/split (str qs) #"&"))
                           :let [[k v] (str/split pair #"=" 2)]]
                       [(keyword k) (java.net.URLDecoder/decode (or v "") "UTF-8")]))]
    (cond-> params
      (:flag params) (update :flag (comp keyword))
      (:in params) (update :in (comp keyword)))))

(defn- int-param [params k]
  (some-> (get params k) str/trim not-empty parse-long))

;;; ---------------------------------------------------------------------------
;;; state

(defn- runs-in [state]
  (for [d (data/list-run-dirs (:root state))]
    (let [canon (fs/canonicalize d)
          rel (if (= (str canon) (str (fs/canonicalize (:root state)))) "" (str (fs/file-name canon)))]
      (data/summarise (:root state) rel (data/read-run canon)))))

(defn- find-run [state rel]
  (when-let [dir (data/resolve-run-dir (:root state) rel)]
    (data/summarise (:root state) (str (or rel "")) (data/read-run dir))))

(defn- base-ctx [state params]
  (let [rel (str (or (:dir params) ""))]
    (when-let [run (find-run state rel)]
      ;; the summarised run carries its own figures (:stats, :replies, ...)
      (merge run {:root (:root state) :rel rel :run run :name (:name run)}))))

;;; ---------------------------------------------------------------------------
;;; shared filters

(defn- text-hits
  "Indices of calls matching the free-text `q` (nil when there is no query)."
  [calls params]
  (when-not (str/blank? (str (:q params)))
    (set (map :n (data/search-calls calls {:query (:q params) :in (:in params)})))))

(defn- flag-matches? [{:keys [forfeits]} call flag]
  (case flag
    :errors (some #(str/starts-with? (str (:content %)) "error:") (:replies call))
    :rejections (some #(str/starts-with? (str (:content %)) "rejected:") (:replies call))
    :notool (empty? (:tool_calls (:response call)))
    :length (= "length" (:finish-reason call))
    :thoughts (seq (:thoughts call))
    :forfeit (contains? forfeits [(:agent call) (:round call)])
    false))

(defn- filter-calls [run params]
  (let [calls (:calls run)
        hits (text-hits calls params)
        agent (:agent params)
        round (int-param params :round)
        flag (:flag params)
        forfeits (set (for [e (:events run) :when (= :action/forfeited (:type e))]
                        [(:actor e) (:round e)]))]
    (for [[n call] (map-indexed vector calls)
          :when (and (or (nil? hits) (hits n))
                     (or (str/blank? agent) (= agent (:agent call)))
                     (or (nil? round) (= round (:round call)))
                     (or (nil? flag) (flag-matches? {:forfeits forfeits} call flag)))]
      [n call])))

(defn- with-page-params [params page pages]
  (let [p (fn [n] (render/url "/calls" (assoc params :page n)))]
    {:prev-page (when (> page 1) (p (dec page)))
     :next-page (when (< page pages) (p (inc page)))}))

(defn- html-response
  ([body] (html-response 200 body))
  ([status body]
   {:status status :headers {"Content-Type" "text/html; charset=utf-8"} :body (str body)}))

(defn- error-response
  "A ring response carrying an error page and its real status code."
  [{:keys [status message home]}]
  (html-response status (render/error-page {:status status :message message :home home})))

;;; ---------------------------------------------------------------------------
;;; pages

(defn- index-page [state params]
  (let [runs (vec (runs-in state))
        search (when-not (str/blank? (str (:q params)))
                 (vec (data/search-runs runs {:query (:q params) :in (:in params)})))]
    (render/runs-page {:root (:root state) :runs runs :search search})))

(defn- run-page [state params]
  (if-let [ctx (base-ctx state params)]
    (render/run-page ctx)
    (error-response {:status 404 :message "no such run" :home "/"})))

(defn- calls-page [state params]
  (if-let [ctx (base-ctx state params)]
    (let [filtered (vec (filter-calls (:run ctx) params))
          total (count filtered)
          pages (max 1 (int (Math/ceil (/ total (double page-size)))))
          page (min pages (max 1 (or (int-param params :page) 1)))
          slice (subvec (vec filtered) (* (dec page) page-size) (min total (* page page-size)))]
      (render/calls-page (merge ctx {:query params :calls slice :total total :page (dec page) :pages pages}
                                (with-page-params params page pages))))
    (error-response {:status 404 :message "no such run" :home "/"})))

(defn- call-page [state params]
  (if-let [ctx (base-ctx state params)]
    (let [calls (:calls (:run ctx))
          n (or (int-param params :n) 0)]
      (if (or (neg? n) (>= n (count calls)))
        (error-response {:status 404 :message (str "no call " n) :home (str "/run?dir=" (:rel ctx))})
        (let [call (nth calls n)
              same-agent (keep-indexed (fn [i c] (when (= (:agent call) (:agent c)) i)) calls)
              prev-same (last (take-while #(< % n) same-agent))
              next-same (first (filter #(> % n) same-agent))]
          (render/call-page (merge ctx
                                   {:n n :call call
                                    :prev (when (pos? n) (dec n))
                                    :next (when (< n (dec (count calls))) (inc n))
                                    :prev-same prev-same :next-same next-same
                                    :turn-n (data/turn-number calls n)
                                    :turn-size (count (filterv #(= (data/turn-key call) (data/turn-key %)) calls))
                                    :calls-count (count calls)
                                    :call-time (data/call-time call)})))))
    (error-response {:status 404 :message "no such run" :home "/"})))

(def ^:private transcript-hidden #{:llm/usage :round/started :round/ended})

(defn- events-page [state params]
  (if-let [ctx (base-ctx state params)]
    (let [run (:run ctx)
          round (int-param params :round)
          type (:type params)
          q (str/lower-case (str (:q params)))
          all? (contains? params :all)
          events (for [e (:events run)
                       :when (and (or all? (not (transcript-hidden (:type e))))
                                  (or (nil? round) (= round (:round e)))
                                  (or (str/blank? type) (str/includes? (str (:type e)) type))
                                  (or (str/blank? q)
                                      (str/includes? (str/lower-case (str (data/describe-event run e)))
                                                     q)))]
                   e)]
      (render/events-page (merge ctx
                                 {:query params
                                  :total (count (:events run))
                                  :rounds (for [[r evs] (sort-by key (group-by :round events))]
                                            [(data/round-label run r) evs])})))
    (error-response {:status 404 :message "no such run" :home "/"})))

(defn- reasoning-page [state params]
  (if-let [ctx (base-ctx state params)]
    (render/reasoning-page (merge ctx
                                  {:query params
                                   :hits (vec (data/reasoning-hits (:calls (:run ctx))
                                                                   {:agent (:agent params)
                                                                    :round (int-param params :round)
                                                                    :query (:q params)}))}))
    (error-response {:status 404 :message "no such run" :home "/"})))

(defn- search-page [state params]
  (if-let [ctx (base-ctx state params)]
    (render/search-page (merge ctx {:query params
                                    :hits (vec (data/search-calls (:calls (:run ctx))
                                                                  {:query (:q params) :in (:in params)}))}))
    (error-response {:status 404 :message "no such run" :home "/"})))

(defn- file-page [state params]
  (if-let [ctx (base-ctx state params)]
    (let [name (str (:name params))
          text (data/read-file-in (:dir (:run ctx)) name)]
      (cond
        (nil? text) (error-response {:status 404 :message (str "no file " name) :home (str "/run?dir=" (:rel ctx))})
        (str/ends-with? name ".md") (render/transcript-page (assoc ctx :text text))
        :else (render/raw-page {:title (str (:name ctx) " · " name) :text text})))
    (error-response {:status 404 :message "no such run" :home "/"})))

(defn- raw-file-page [state params]
  (if-let [ctx (base-ctx state params)]
    (let [name (str (:name params))
          text (data/read-file-in (:dir (:run ctx)) name)]
      (if text
        {:status 200 :headers {"Content-Type" "text/plain; charset=utf-8"} :body text}
        (error-response {:status 404 :message (str "no file " name) :home "/"})))
    (error-response {:status 404 :message "no such run" :home "/"})))

(defn- raw-call-page [state params]
  (if-let [ctx (base-ctx state params)]
    (let [n (or (int-param params :n) 0)
          call (nth (:calls (:run ctx)) n nil)]
      (if call
        {:status 200 :headers {"Content-Type" "text/plain; charset=utf-8"}
         :body (with-out-str (pp/pprint call))}
        (error-response {:status 404 :message (str "no call " n) :home "/"})))
    (error-response {:status 404 :message "no such run" :home "/"})))

;;; ---------------------------------------------------------------------------
;;; routing

(defn handler
  "A ring-style handler for the viewer, closed over `state` ({:root ...})."
  [state]
  (fn [{:keys [uri query-string] :as _request}]
    (try
      (let [params (parse-query query-string)
            body (case uri
                   "/" (index-page state params)
                   "/run" (run-page state params)
                   "/calls" (calls-page state params)
                   "/call" (call-page state params)
                   "/events" (events-page state params)
                   "/search" (search-page state params)
                   "/reasoning" (reasoning-page state params)
                   "/file" (file-page state params)
                   "/raw-file" (raw-file-page state params)
                   "/raw" (raw-call-page state params)
                   (error-response {:status 404 :message (str "no page " uri) :home "/"}))]
        (if (map? body) body (html-response body)))
      (catch Throwable e
        (error-response {:status 500 :message (str (class e) " " (.getMessage e)) :home "/"})))))

(defn start!
  "Start the viewer.

  Options: :root (run root, required), :port (default 8787, 0 picks a free
  port), :host (bind address, default \"localhost\"; use \"0.0.0.0\" to expose it
  on the network). Returns {:server s :root dir :url \"http://localhost:PORT\"}."
  [{:keys [root port host]}]
  (let [root (fs/canonicalize (fs/file root))
        host (or host "localhost")
        server (http/run-server (handler {:root root})
                                {:port (int (or port 8787))
                                 :ip host
                                 :legacy-return-value? false})]
    {:server server
     :root root
     :url (str "http://" (if (= host "0.0.0.0") "localhost" host) ":"
               (http/server-port server))}))

(defn stop! [{:keys [server]}] (when server (http/server-stop! server)))
