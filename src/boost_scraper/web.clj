(ns boost-scraper.web
  (:require [aleph.http :as http]
            [babashka.http-client :as httpc]
            [boost-scraper.reports :as reports]
            [boost-scraper.shows :as shows]
            [boost-scraper.client-state :as client-state]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.math :as math]
            [clojure.string :as str]
            [cybermonday.core :as markdown]
            [dev.onionpancakes.chassis.core :as html]
            [manifold.deferred :as mf]
            [muuntaja.core :as m]
            [reitit.ring :as ring]
            [reitit.ring.middleware.parameters]
            [reitit.ring.middleware.muuntaja :as muuntaja]))

;; Routes
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
                        report (reports/boost-report db-conn show-pattern since)]
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

;; Top Level HTTP
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
   ["/boosts" {:get {:handler (get-boosts db-conn)}}]])

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
