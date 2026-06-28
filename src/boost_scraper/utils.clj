(ns boost-scraper.utils
  (:import [java.util.concurrent CompletableFuture])
  (:require [clojure.math :as math]))

(defn format-date [unix-time]
  (-> (java.time.Instant/ofEpochSecond unix-time)
      (.atZone (java.time.ZoneId/of "America/Los_Angeles"))
      (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy/MM/dd h:mm:ss a zzz"))))

(defn format-seconds [seconds]
  (let [seconds (long seconds)
        hours (quot seconds 3600)
        mins (quot (mod seconds 3600) 60)
        secs (mod seconds 60)]
    (if (pos? hours)
      (format "%d:%02d:%02d" hours mins secs)
      (format "%d:%02d" mins secs))))

(defn apply-virtual
  "Returns a CompletableFuture eventually containing (apply f args)."
  [f & args]
  (let [fut (CompletableFuture.)]
    (Thread/startVirtualThread
     (fn []
       (try
         (.complete ^CompletableFuture fut (apply f args))
         (catch Exception e (.completeExceptionally ^CompletableFuture fut e)))))
    fut))

(defn make-virtual
  "Wrap f in a new function that runs f on a virtual thread with apply-virtual."
  [f]
  (fn [& args] (apply-virtual f args)))

(defn with-retries
  "Execute f with exponential backoff and full jitter.
   Options: :max-retries (default 3), :base-delay-ms (default 1000)."
  [f & {:keys [max-retries base-delay-ms] :or {max-retries 3 base-delay-ms 1000}}]
  (loop [attempt 1]
    (let [result (try
                   {:ok (f)}
                   (catch Exception e
                     (if (< attempt max-retries)
                       {:retry e}
                       {:error e})))]
      (if-let [e (:retry result)]
        (let [exp-delay (* base-delay-ms (long (math/pow 2 (dec attempt))))
              jitter-delay (long (rand exp-delay))]
          (println (format "Attempt %d failed, retrying in %dms (jittered from %dms)... Error: %s"
                           attempt jitter-delay exp-delay (.getMessage e)))
          (Thread/sleep jitter-delay)
          (recur (inc attempt)))
        (if-let [e (:error result)]
          (throw e)
          (:ok result))))))

(defn check-http-status
  "Throw if the HTTP response indicates an error.
   context is a human-readable source name for error messages."
  [resp context]
  (let [status (:status resp)]
    (when (>= status 400)
      (throw (ex-info (str context " API error: " status " " (:body resp))
                      {:status status :body (:body resp)}))))
  resp)

(comment
  (defn seconds-between [inst1 inst2]
    (.toSeconds
     (java.time.Duration/between
      (.toInstant inst1)
      (.toInstant inst2))))
    ;; basic base64
  (.encodeToString (java.util.Base64/getEncoder) (.getBytes "wes"))
  (java.lang.String. (.decode (java.util.Base64/getDecoder) "d2Vz")))

#_(defn wrap-aleph-handler
    "Converts given Aleph-compliant hanlder to asynchronous Ring handler.

   More information about asynchronous Ring handlers and middleware:
   https://www.booleanknot.com/blog/2016/07/15/asynchronous-ring.html"
    [handler]
    (fn
      ([request]
       (let [resp (handler request)]
         (if (mf/deferred? resp)
           (throw (ex-info "Sync route returned deferred." {:request request}))
           resp)))
      ([request respond raise]
       (let [resp (handler request)
             respd (if (mf/deferred? resp) resp (mf/success-deferred resp))]
         (mf/on-realized respd respond raise)))))

#_(def wrap-ring-async-handler
    {:name ::wrap-ring-async
     :compile
     (fn [{:keys [:data]} _]
       (when (not= false (get data :async?))
         {:wrap http/wrap-ring-async-handler}))})
