(ns boost-scraper.ws
  "WebSocket broadcast for real-time boost updates."
  (:require [aleph.http :as http]
            [cheshire.core :as json]
            [manifold.bus :as bus]
            [manifold.deferred :as d]
            [manifold.stream :as s]))

(defonce boost-bus (bus/event-bus))

(defonce clients (atom #{}))

(defn broadcast!
  "Send boost data to all connected WebSocket clients."
  [boost]
  (let [msg (json/generate-string boost)]
    (bus/publish! boost-bus :boosts msg)))

(defn client-count
  "Number of connected WebSocket clients."
  []
  (count @clients))

(defn ws-handler
  "WebSocket handler — upgrades connection and subscribes to broadcast topic."
  [req]
  (d/let-flow [conn (d/catch
                     (http/websocket-connection req)
                     (fn [_] nil))]
              (if-not conn
                {:status 400
                 :headers {"content-type" "text/plain"}
                 :body "Expected a websocket request."}
                (do
                  (swap! clients conj conn)
                  (s/on-closed conn (fn [] (swap! clients disj conn)))
                  (s/connect (bus/subscribe boost-bus :boosts) conn)
                  nil))))
