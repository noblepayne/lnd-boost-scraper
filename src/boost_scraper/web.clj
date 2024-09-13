(ns boost-scraper.web
  (:require [aleph.http :as http]
            [reitit.ring :as ring]
            [manifold.deferred :as md]
            [cybermonday.core :as cm]
            [muuntaja.core :as m]
            [reitit.ring.middleware.parameters]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [dev.onionpancakes.chassis.core :as html]
            [hato.client :as httpc]
            [clojure.java.io :as io]
            [clojure.math :as math]
            [clojure.string :as str]
            [boost-scraper.reports :as reports]))

;; CSS
(def pico-fluid-classless
  (str "\n"
       (str/trim
        (slurp
         (io/resource "pico.fluid.classless.min.css")))
       "\n"))

(def pico-classless
  (str "\n"
       (str/trim
        (slurp
         (io/resource "pico.classless.min.css")))
       "\n"))

;; Routes
(def show->regex
  {"All Shows" "(?i).*"
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
                [:form {:action "/boosts"}
                 [:label {:for "showselect"} "Show:"]
                 [:select#showselect {:name "show"}
                  (for [show-option (keys show->regex)]
                    [:option {:value (show->regex show-option)
                              :selected (= show show-option)} show-option])]
                 [:label {:for "since"} "Last Seen Timestamp:"]
                 [:input#since {:name "since" :type "text" :value default-since}]
                 [:input {:type "submit" :value "Get Boosts!"}]]
                (let [show (re-pattern show)
                      since (Integer/parseInt since)
                      report (reports/boost-report db-conn show since)]
                  [:div#boosts {:style {:margin-top "10px" :margin-bottom "10px"}}
                   [:div {:style {"padding" "10px"}}
                    [:button#copyMarkdown {:onClick "copyMarkdown()"} "Copy Markdown"]]
                   [:div#markdown {:style {:display "none"}} report]
                   [:div#report {:style {:margin-top "10px"
                                         :margin-bottom "10px"
                                         :margin-left "50px"
                                         :margin-right "50px"}}
                    (cm/parse-body report)]]))]]]]]])})))

;; Top Level HTTP
(defn routes [db-conn]
  [["/ping"
    {:get {:handler (fn [_] {:status 200 :body "pong\n"})}}]
   ["/boosts" {:get {:handler (get-boosts db-conn)
                     :data {:async? false}}}]])

(defn wrap-aleph-handler
  "Converts given Aleph-compliant hanlder to asynchronous Ring handler.

   More information about asynchronous Ring handlers and middleware:
   https://www.booleanknot.com/blog/2016/07/15/asynchronous-ring.html"
  [handler]
  (fn
    ([request]
     (let [resp (handler request)]
       (if (md/deferred? resp)
         (throw (ex-info "Sync route returned deferred." {:request request}))
         resp)))
    ([request respond raise]
     (let [resp (handler request)
           respd (if (md/deferred? resp) resp (md/success-deferred resp))]
       (md/on-realized respd respond raise)))))

(def wrap-ring-async-handler
  {:name ::wrap-ring-async
   :compile
   (fn [{:keys [:data]} _]
     (when (not= false (get data :async?))
       {:wrap http/wrap-ring-async-handler}))})

(defn app [db-conn]
  (ring/ring-handler
   (ring/router
    (routes db-conn)
    {:data {:muuntaja m/instance
            :middleware [wrap-ring-async-handler
                         muuntaja/format-middleware
                         reitit.ring.middleware.parameters/parameters-middleware
                         wrap-aleph-handler]}})
   (ring/routes (ring/create-default-handler))))

(defn -main
  [db-conn]
  (http/start-server (app db-conn) {:port 9999}))

(comment
  (def app_ (app boost-scraper.core/lnd-conn))
  (def server (http/start-server #'app_ {:port 9999})) ;; `#'` allows reloading by redef-ing app
  (.close server)
  (->  "http://localhost:9999/ping" httpc/get :body print))