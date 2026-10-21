(ns manor.io.http
  "Outbound HTTP on the JDK's java.net.http client (no compression, no
  extra dependencies). Used by the LLM client and by bridges."
  (:require [clojure.string :as str]
            [jsonista.core :as json])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.time Duration)))

(def ^:private client
  (delay (-> (HttpClient/newBuilder)
             (.connectTimeout (Duration/ofSeconds 15))
             (.followRedirects HttpClient$Redirect/NORMAL)
             (.build))))

(defn- query-string [params]
  (str/join "&" (for [[k v] params]
                  (str (URLEncoder/encode (name k) StandardCharsets/UTF_8) "="
                       (URLEncoder/encode (str v) StandardCharsets/UTF_8)))))

(defn request
  "Send a request. Returns {:status n :body string}; throws on network errors."
  [{:keys [method url query-params headers body timeout-ms]
    :or {method :get timeout-ms 30000}}]
  (let [uri (URI/create (cond-> url (seq query-params) (str "?" (query-string query-params))))
        builder (-> (HttpRequest/newBuilder uri)
                    (.timeout (Duration/ofMillis timeout-ms)))
        _ (doseq [[k v] headers] (.header builder k v))
        req (-> (case method
                  :get (.GET builder)
                  :post (.POST builder (HttpRequest$BodyPublishers/ofString (or body ""))))
                (.build))
        resp (.send ^HttpClient @client req (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp) :body (.body resp)}))

(defn get-json
  "GET a URL and parse a JSON body with keyword keys. Throws on non-200."
  ([url] (get-json url nil))
  ([url query-params]
   (let [{:keys [status body]} (request {:url url :query-params query-params :timeout-ms 15000})]
     (if (= 200 status)
       (json/read-value body json/keyword-keys-object-mapper)
       (throw (ex-info (str "HTTP " status) {:status status :body body}))))))
