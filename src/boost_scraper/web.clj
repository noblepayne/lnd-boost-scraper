(ns boost-scraper.web
  (:require [aleph.http :as http]
            [babashka.http-client :as httpc]
            [boost-scraper.reports :as reports]
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

;; CSS
(def pico-classless
  (str "\n"
       (str/trim
        (slurp
         (io/resource "pico.classless.min.css")))
       "\n"))

;; Routes
(def show->regex
  {"All Shows" ".*"
   "LINUX Unplugged" "(?i).*unplugged.*"
   "Coder Radio" "(?i).*coder.*"
   "Self-Hosted" "(?i).*hosted.*"
   "This Week in Bitcoin" "(?i).*bitcoin.*"})

(defn two-weeks-ago []
  (let [now (/ (System/currentTimeMillis) 1000)
        two-weeks-ago (- now (* 2 60 60 24 7))]
    (math/round two-weeks-ago)))

;; TODO: consolidate CSS?
(defn get-boosts [db-conn]
  (fn [request]
    (let [{{:strs [show since]} :params} request
          default-since (two-weeks-ago)]
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
           [:style (html/raw (str "div#report blockquote {padding-bottom: 0px;
                                                          padding-top: 0px;}"))]
           [:script {:type "text/javascript"}
            (html/raw
             (str
              "function copyMarkdown () {
                 navigator.clipboard.writeText(document.getElementById('markdown').textContent);
                 const button = document.getElementById('copyMarkdown')
                 setTimeout(() => {
                   button.textContent = 'Copied!';
                   setTimeout(() => {
                     button.textContent = 'Copy Markdown';
                   }, 1500);
                 }, 200);
               }"))]
           [:body
            [:main
             [:div
              [:h1 [:a {:href "/boosts" :style {:color "inherit" :text-decoration-color "inherit"}} "Boost Report"]]
              (if (not (and show since))
                ;; Query form
                [:form {:action "/boosts"}
                 [:label {:for "showselect"} "Show:"]
                 [:select#showselect {:name "show"}
                  (for [show-option (keys show->regex)]
                    [:option {:value (show->regex show-option)
                              :selected (= show show-option)} show-option])]
                 [:label {:for "since"} "Last Seen Timestamp:"]
                 [:input#since {:name "since" :type "text" :value default-since}]
                 [:input {:type "submit" :value "Get Boosts!"}]]
                ;; Query results
                (let [show (re-pattern show)
                      since (Integer/parseInt since)
                      report (reports/boost-report db-conn show since)
                      ;; hacky prototype helipad replacement
                      #_(str/join "\n" (map #(reports/format-boost-batch-details [%])
                                            (sort-by :invoice/creation_date
                                                     #(compare %2 %1)
                                                     (into [] cat (reports/get-boost-summary-for-report' db-conn show since)))))]
                  [:div#boosts {:style {:margin-top "10px" :margin-bottom "10px"}}
                   [:div {:style {"padding" "10px"}}
                    [:button#copyMarkdown {:onClick "copyMarkdown()"} "Copy Markdown"]]
                   [:div#markdown {:style {:display "none"}} report]
                   [:div#report {:style {:margin-top "10px"
                                         :margin-bottom "10px"
                                         :margin-left "50px"
                                         :margin-right "50px"}}
                    (markdown/parse-body report)]]))]]]]]])})))

;; Top Level HTTP
(defn routes [db-conn]
  [["/ping"
    {:get {:handler (fn [_] {:status 200 :body "pong\n"})}}]
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
   {:port (Integer/parseInt (or (System/getenv "SCRAPER_UIPORT") 3223))
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
  (->  "http://localhost:9999/ping" httpc/get :body print))