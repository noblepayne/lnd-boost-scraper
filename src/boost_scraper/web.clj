(ns boost-scraper.web
  (:require [aleph.http :as http]
            [babashka.http-client :as httpc]
            [boost-scraper.price-feed :as price-feed]
            [boost-scraper.reports :as reports]
            [boost-scraper.shows :as shows]
            [boost-scraper.client-state :as client-state]
            [boost-scraper.analysis :as analysis]
            [boost-scraper.feed :as feed]
            [boost-scraper.query-proxy :as qp]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.math :as math]
            [clojure.string :as str]
            [cybermonday.core :as markdown]
            [dev.onionpancakes.chassis.core :as html]
            [manifold.deferred :as mf]
            [muuntaja.core :as m]
            [reitit.ring :as ring]
            [reitit.ring.middleware.parameters]
            [reitit.ring.middleware.muuntaja :as muuntaja])
  (:import (java.time Instant ZoneId)
           (java.time.format DateTimeFormatter)))

;; Routes

(def query-templates
  "Lazily loaded query templates from resources."
  (delay
    (when-let [resource (io/resource "query_templates.edn")]
      (edn/read-string (slurp resource)))))

(defn two-weeks-ago []
  (let [now (/ (System/currentTimeMillis) 1000)
        two-weeks-ago (- now (* 2 60 60 24 7))]
    (math/round two-weeks-ago)))

;; CSS
(def pico-classless
  (str "\n"
       (str/trim
        (slurp
         (io/resource "pico.classless.min.css")))
       "\n"))

(def js
  (str "\n"
       (str/trim
        (slurp (io/resource "boost_report.js")))
       "\n"))

;; Feed page resources
(def feed-css
  (str "\n"
       (str/trim
        (slurp (io/resource "feed.css")))
       "\n"))

(def feed-js
  (str "\n"
       (str/trim
        (slurp (io/resource "feed.js")))
       "\n"))

(defn format-csv-time
  "Format epoch seconds as YYYY-MM-DD HH:MM:SS."
  [epoch]
  (-> (Instant/ofEpochSecond epoch)
      (.atZone (ZoneId/of "UTC"))
      (.format (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"))))

(defn csv-quote
  "Quote and escape a value for CSV. Always wraps in double quotes.
   Doubles any existing double quotes, and prefixes with single quote
   to prevent Excel formula injection."
  [value]
  (let [s (str value)
        escaped (str/replace s "\"" "\"\"")]
    (str "\"" escaped "\"")))

(defn get-boosts [db-conn]
  (fn [request]
    (let [{{:strs [show since json include-unknown client]} :params} request
          default-since (two-weeks-ago)
          json-mode (= json "true")
          include-unknown (not= include-unknown "false")
          show-regex (shows/regex-for show include-unknown)
          client-id (when (and client (seq client) (< (count client) 256)) client)
          show-slug (when show-regex (or (some-> show shows/resolve-show :slug) show))
          resolved-since (cond
                           (and since (re-matches #"^\d+$" since)) (Integer/parseInt since)
                           (and client-id show-slug)
                           (some-> (client-state/get-client-state db-conn client-id show-slug)
                                   :client-state/last-seen-tx)
                           :else default-since)]
      (cond
        (and json-mode show show-regex resolved-since)
        (let [show-pattern (re-pattern show-regex)
              data (-> (reports/get-boost-summary-for-report db-conn show-pattern resolved-since)
                       reports/sort-report
                       reports/normalize-report)]
          (when client-id
            (let [new-hwm (some-> data :summary :last_seen_id)]
              (if (and new-hwm (not= new-hwm resolved-since))
                (client-state/update-last-seen! db-conn client-id show-slug new-hwm)
                (client-state/touch-accessed! db-conn client-id show-slug))))
          {:status 200
           :headers {"content-type" "application/json"}
           :body (json/generate-string data)})

        (and show (not show-regex))
        {:status 400
         :headers {"content-type" "application/json"}
         :body (json/generate-string {:error (str "Invalid show: " show)})}

        :else
        {:status 200
         :headers {"content-type" "text/html; charset=utf-8"}
         :body
         (html/html
          [html/doctype-html5
           [:html
            [:head
             [:meta {:charset "utf-8"}]
             [:meta {:name "viewport", :content "width=device-width, initial-scale=1"}]
             [:meta {:name "color-scheme", :content "light dark"}]
             [:meta {:http-equiv "refresh", :content "60"}]
             [:title "Boosts!"]
             [:style (html/raw pico-classless)]
             [:style (html/raw "div#report blockquote {padding-bottom: 0px;
                                                           padding-top: 0px;}")]
             [:script {:type "text/javascript"}
              (html/raw js)]
             [:body
              [:main
               [:div
                [:h1 [:a {:href "/boosts" :style {:color "inherit" :text-decoration-color "inherit"}} "Boost Report"]]
                (if (not (and show since))
                  ;; Query form
                  [:form {:action "/boosts"}
                   [:label {:for "showselect"} "Show:"]
                   [:select#showselect {:name "show"}
                    (for [show-option (shows/show-options include-unknown)]
                      [:option {:value (:slug show-option)
                                :selected (= (some-> show str/lower-case) (:slug show-option))} (:name show-option)])]
                   [:label {:for "include-unknown"}
                    [:input#include-unknown {:name "include-unknown" :type "checkbox" :checked include-unknown}]
                    " Include Unknown:"]
                   [:label {:for "since"} " Last Seen Timestamp:"]
                   [:input#since {:name "since" :type "text" :value default-since}]
                   [:input {:type "submit" :value "Get Boosts!"}]]
                  ;; Query results
                  (let [show-pattern (re-pattern show-regex)
                        since resolved-since
                        report (reports/boost-report db-conn show-pattern since
                                                     :fiat-sats-rate (price-feed/fiat-sats-rate))]
                    [:div#boosts {:style {:margin-top "10px" :margin-bottom "10px"}}
                     [:div {:style {"padding" "10px"}}
                      [:button#copyMarkdown {:onClick "copyMarkdown()"} "Copy Markdown"]
                      [:button#downloadMarkdown {:onClick "downloadMarkdown()" :style {:display "inline" :margin-left "10px"}} "Download Markdown"]]
                     [:textarea#markdown {:style {:display "none" :position "absolute" :left "-1000px" :top "-1000px"}} report]
                     [:div#report {:style {:margin-top "10px"
                                           :margin-bottom "10px"
                                           :margin-left "50px"
                                           :margin-right "50px"}}
                      (markdown/parse-body report)]]))]]]]]])}))))

(defn routes [db-conn]
  [["/ping"
    {:get {:handler (fn [_] {:status 200 :body "pong\n"})}}]
   ["/api/v1/shows"
    {:get {:handler (fn [request]
                      (let [include-unknown-param (get-in request [:params "include-unknown"])
                            include-unknown (or (= include-unknown-param "true") (nil? include-unknown-param))]
                        {:status 200
                         :headers {"content-type" "application/json"}
                         :body (json/generate-string {:shows (shows/show-options include-unknown)})}))}}]
   ["/api/v1/client-states"
    {:get {:handler (fn [_]
                      {:status 200
                       :headers {"content-type" "application/json"}
                       :body (json/generate-string
                              {:client-states
                               (for [state (client-state/list-client-states db-conn)]
                                 {:client (:client-state/client-id state)
                                  :show (:client-state/show-slug state)
                                  :last-seen-tx (:client-state/last-seen-tx state)
                                  :last-accessed-tx (:client-state/last-accessed-tx state)})})})}
     :delete {:handler (fn [{params :params}]
                         (let [client (get params "client")
                               show (get params "show")]
                           (if (and client show)
                             (do
                               (client-state/delete-client-state! db-conn client show)
                               {:status 200
                                :headers {"content-type" "application/json"}
                                :body (json/generate-string {:deleted true})})
                             {:status 400
                              :headers {"content-type" "application/json"}
                              :body (json/generate-string {:error "client and show params required"})})))}}]
   ["/api/v1/client-states/cleanup"
    {:post {:handler (fn [_]
                       (let [deleted (client-state/cleanup-old-states! db-conn 90)]
                         {:status 200
                          :headers {"content-type" "application/json"}
                          :body (json/generate-string {:deleted deleted})}))}}]
   ;; Analysis endpoints
   ["/api/v1/analysis/top-boosters"
    {:get {:handler (fn [request]
                      (let [{{:strs [show start end limit type]} :params} request
                            show-regex (when show (re-pattern (or (shows/regex-for show) show)))
                            start (when start (Long/parseLong start))
                            end (when end (Long/parseLong end))
                            limit (when limit (Integer/parseInt limit))
                            boost-type (when type (keyword type))]
                        (if-not show-regex
                          {:status 400
                           :headers {"content-type" "application/json"}
                           :body (json/generate-string {:error (str "Invalid show: " show)})}
                          (try
                            (let [results (analysis/top-boosters db-conn show-regex start end limit boost-type)]
                              {:status 200
                               :headers {"content-type" "application/json"}
                               :body (json/generate-string {:boosters results})})
                            (catch Exception e
                              {:status 500
                               :headers {"content-type" "application/json"}
                               :body (json/generate-string {:error (.getMessage e)})})))))}}]
   ["/api/v1/analysis/monday-summary"
    {:get {:handler (fn [request]
                      (let [{{:strs [show type]} :params} request
                            show-regex (when show (re-pattern (or (shows/regex-for show) show)))
                            boost-type (when type (keyword type))]
                        (if-not show-regex
                          {:status 400
                           :headers {"content-type" "application/json"}
                           :body (json/generate-string {:error (str "Invalid show: " show)})}
                          (try
                            (let [results (analysis/monday-boost-summary db-conn show-regex boost-type)]
                              {:status 200
                               :headers {"content-type" "application/json"}
                               :body (json/generate-string results)})
                            (catch Exception e
                              {:status 500
                               :headers {"content-type" "application/json"}
                               :body (json/generate-string {:error (.getMessage e)})})))))}}]
   ["/api/v1/analysis/monthly-leaderboard"
    {:get {:handler (fn [request]
                      (let [{{:strs [show type]} :params} request
                            show-regex (when show (re-pattern (or (shows/regex-for show) show)))
                            boost-type (when type (keyword type))]
                        (if-not show-regex
                          {:status 400
                           :headers {"content-type" "application/json"}
                           :body (json/generate-string {:error (str "Invalid show: " show)})}
                          (try
                            (let [results (analysis/top-booster-per-month db-conn show-regex boost-type)]
                              {:status 200
                               :headers {"content-type" "application/json"}
                               :body (json/generate-string results)})
                            (catch Exception e
                              {:status 500
                               :headers {"content-type" "application/json"}
                               :body (json/generate-string {:error (.getMessage e)})})))))}}]
   ["/api/v1/analysis/app-percentages"
    {:get {:handler (fn [_]
                      (try
                        (let [results (analysis/app-percentages db-conn)]
                          {:status 200
                           :headers {"content-type" "application/json"}
                           :body (json/generate-string {:apps results})})
                        (catch Exception e
                          {:status 500
                           :headers {"content-type" "application/json"}
                           :body (json/generate-string {:error (.getMessage e)})})))}}]
   ;; Query proxy endpoint
   ["/api/v1/query"
    {:post {:handler (fn [request]
                       (let [body (:body-params request)
                             query-str (:query body)
                             params (:params body)
                             timeout (:timeout body)
                             limit (:limit body)]
                         (if-not query-str
                           {:status 400
                            :headers {"content-type" "application/json"}
                            :body (json/generate-string {:error "Missing required field: query"})}
                           (let [result (qp/execute-query db-conn query-str
                                                          (cond-> {}
                                                            params (assoc :params params)
                                                            timeout (assoc :timeout timeout)
                                                            limit (assoc :limit limit)))]
                             {:status (if (= :ok (:status result)) 200 400)
                              :headers {"content-type" "application/json"}
                              :body (json/generate-string result)}))))}}]
   ;; Query templates
   ["/api/v1/query/templates"
    {:get {:handler (fn [_]
                      (let [templates @query-templates]
                        {:status 200
                         :headers {"content-type" "application/json"}
                         :body (json/generate-string
                                {:templates (mapv (fn [t]
                                                    {:name (:name t)
                                                     :description (:description t)})
                                                  (:templates templates))})}))}}]
   ["/api/v1/query/templates/:name"
    {:get {:handler (fn [request]
                      (let [name (get-in request [:path-params :name])
                            templates @query-templates
                            template (first (filter #(= (:name %) name) (:templates templates)))]
                        (if template
                          {:status 200
                           :headers {"content-type" "application/json"}
                           :body (json/generate-string {:template template})}
                          {:status 404
                           :headers {"content-type" "application/json"}
                           :body (json/generate-string {:error (str "Template not found: " name)})})))}}]
   ;; Feed API endpoint — with podcast filter
   ["/api/v1/feed"
    {:get {:handler (fn [request]
                      (let [{{:strs [show podcast since before_time before_index limit]} :params} request
                            show-regex (when show (shows/regex-for show true))
                            podcast (when (and podcast (seq podcast)) podcast)
                            since (when since (try (Long/parseLong since) (catch NumberFormatException _ nil)))
                            before-time (when before_time (try (Long/parseLong before_time) (catch NumberFormatException _ nil)))
                            before-index (when before_index (try (Long/parseLong before_index) (catch NumberFormatException _ nil)))
                            limit (when limit (try (Integer/parseInt limit) (catch NumberFormatException _ nil)))]
                        (if-not show-regex
                          {:status 400
                           :headers {"content-type" "application/json"}
                           :body (json/generate-string {:error (str "Invalid show: " show)})}
                          (try
                            (let [boosts (feed/get-boosts-for-feed-v2 db-conn show-regex podcast since before-time before-index limit)]
                              {:status 200
                               :headers {"content-type" "application/json"}
                               :body (json/generate-string boosts)})
                            (catch Exception e
                              {:status 500
                               :headers {"content-type" "application/json"}
                               :body (json/generate-string {:error (.getMessage e)})})))))}}]
   ;; Podcast list endpoint
   ["/api/v1/feed/podcasts"
    {:get {:handler (fn [request]
                      (let [{{:strs [show]} :params} request
                            show-regex (when show (shows/regex-for show true))]
                        (if-not show-regex
                          {:status 400
                           :headers {"content-type" "application/json"}
                           :body (json/generate-string {:error (str "Invalid show: " show)})}
                          (try
                            (let [podcasts (feed/get-podcasts-for-feed db-conn show-regex)]
                              {:status 200
                               :headers {"content-type" "application/json"}
                               :body (json/generate-string {:podcasts podcasts})})
                            (catch Exception e
                              {:status 500
                               :headers {"content-type" "application/json"}
                               :body (json/generate-string {:error (.getMessage e)})})))))}}]
   ;; CSV export endpoint
   ["/feed.csv"
    {:get {:handler (fn [request]
                      (let [{{:strs [show podcast since end]} :params} request
                            show-regex (when show (shows/regex-for show true))
                            podcast (when (and podcast (seq podcast)) podcast)
                            since (when since (try (Long/parseLong since) (catch NumberFormatException _ nil)))
                            end (when end (try (Long/parseLong end) (catch NumberFormatException _ nil)))]
                        (if-not show-regex
                          {:status 400
                           :headers {"content-type" "text/csv"}
                           :body "Invalid show parameter"}
                          (try
                            (let [boosts (feed/get-boosts-for-csv db-conn show-regex podcast since end)
                                  csv-header "time,sender,sats,app,podcast,episode,message\n"
                                  csv-rows (str/join
                                            (map (fn [b]
                                                   (str (csv-quote (format-csv-time (:time b)))
                                                        "," (csv-quote (:sender b))
                                                        "," (csv-quote (:sats b))
                                                        "," (csv-quote (:app b))
                                                        "," (csv-quote (:podcast b))
                                                        "," (csv-quote (:episode b))
                                                        "," (csv-quote (:message b)) "\n"))
                                                 boosts))
                                  csv-content (str csv-header csv-rows)]
                              {:status 200
                               :headers {"content-type" "text/csv"
                                         "content-disposition" (str "attachment; filename=\"boosts-" show ".csv\"")}
                               :body csv-content})
                            (catch Exception e
                              {:status 500
                               :headers {"content-type" "text/csv"}
                               :body (str "Error: " (.getMessage e))})))))}}]
   ["/boosts" {:get {:handler (get-boosts db-conn)}}]
   ;; Feed HTML page
   ["/feed" {:get {:handler (fn [request]
                              (let [{{:strs [show]} :params} request
                                    include-unknown true]
                                (try
                                  {:status 200
                                   :headers {"content-type" "text/html; charset=utf-8"}
                                   :body
                                   (html/html
                                    [html/doctype-html5
                                     [:html
                                      [:head
                                       [:meta {:charset "utf-8"}]
                                       [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
                                       [:meta {:name "color-scheme" :content "dark"}]
                                       [:title "Boost Feed"]
                                       [:style (html/raw feed-css)]]
                                      [:body
                                       [:div.feed-header
                                        [:h1 [:a {:href "/feed"} "Boost Feed"]]
                                        [:ul.nav-tabs
                                         [:li [:a {:href "/boosts"} "Report"]]
                                         [:li [:a.active {:href "/feed"} "Feed"]]]]
                                       [:form.feed-filters {:id "feed-filters"}
                                        [:label {:for "filter-show"} "Show:"]
                                        [:select#filter-show {:name "show"}
                                         (for [show-option (shows/show-options include-unknown)]
                                           [:option {:value (:slug show-option)
                                                     :selected (= (some-> show str/lower-case) (:slug show-option))} (:name show-option)])]
                                        [:label {:for "filter-podcast"} "Podcast:"]
                                        [:select#filter-podcast {:name "podcast"}
                                         [:option {:value ""} "All Podcasts"]]
                                        [:label {:for "filter-since"} "Since:"]
                                        [:input#filter-since {:name "since" :type "number" :placeholder "Epoch timestamp"}]
                                        [:button.btn.btn-primary {:type "submit"} "Filter"]
                                        [:a.btn.btn-outline {:href "/feed"} "Clear"]]
                                       [:div.feed-container {:id "feed-container"}
                                        [:div.feed-empty
                                         [:p "Loading boosts..."]]]
                                       [:div.load-more {:id "load-more" :style {:display "none"}}
                                        [:a {:href "#"} "Load older boosts..."]]
                                       [:div.feed-footer
                                        [:a {:href "https://github.com/Podcastindex-org/helipad" :target "_blank"} "Inspired by Helipad"]]
                                       [:script {:type "text/javascript"} (html/raw feed-js)]]]])}
                                  (catch Exception e
                                    {:status 500
                                     :headers {"content-type" "text/html; charset=utf-8"}
                                     :body (html/html
                                            [html/doctype-html5
                                             [:html
                                              [:head
                                               [:meta {:charset "utf-8"}]
                                               [:title "Error"]]
                                              [:body
                                               [:main
                                                [:h1 "Error"]
                                                [:p (.getMessage e)]]]]])}))))}}]])

(defn http-handler [db-conn]
  (ring/ring-handler
   (ring/router
    (routes db-conn)
    {:data {:muuntaja m/instance
            :middleware [muuntaja/format-middleware
                         reitit.ring.middleware.parameters/parameters-middleware]}})
   (ring/routes (ring/create-default-handler))))

(defn make-virtual
  "Like utils/make-virtual but returns a Manifold deferred instead
   of a CompletableFuture."
  [f]
  (fn [& args]
    (let [deferred (mf/deferred)]
      (Thread/startVirtualThread
       (fn []
         (try
           (mf/success! deferred (apply f args))
           (catch Exception e (mf/error! deferred e)))))
      deferred)))

(defn serve
  [db-conn]
  (http/start-server
   (make-virtual (http-handler db-conn))
   {:port (Integer/parseInt (or (System/getenv "SCRAPER_UIPORT") "3223"))
    ;; When other than :none our handler is run on a thread pool.
    ;; As we are wrapping our handler in a new virtual thread per request
    ;; on our own, we have no risk of blocking the (aleph) handler thread and
    ;; don't need an additional threadpool onto which to offload.
    :executor :none}))

(comment
  (require '[boost-scraper.core])
  (def http-handler' (make-virtual (http-handler boost-scraper.core/nodecan-conn)))
  ;; `#'` allows reloading by redef-ing http-handler'
  (def server (http/start-server #'http-handler' {:port 9999 :executor :none}))
  (.close server)
  (-> "http://localhost:9999/ping" httpc/get :body print))
