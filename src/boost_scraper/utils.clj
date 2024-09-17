(ns boost-scraper.utils
  (:import [java.util.concurrent CompletableFuture]))

(defn format-date [unix-time]
  (-> (java.time.Instant/ofEpochSecond unix-time)
      (.atZone (java.time.ZoneId/of "America/Los_Angeles"))
      (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy/MM/dd h:mm:ss a zzz"))))

(defn apply-virtual
  "Apply f to args on a virtual thread. Returns a CompletableFuture eventually
   containing (apply f args)."
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
