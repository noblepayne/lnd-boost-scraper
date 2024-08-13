(ns boost-scraper.utils)

(defn format-date [unix-time]
  (-> (java.time.Instant/ofEpochSecond unix-time)
      (.atZone (java.time.ZoneId/of "America/Los_Angeles"))
      (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy/MM/dd h:mm:ss a zzz"))))

(comment
  (defn seconds-between [inst1 inst2]
    (.toSeconds
     (java.time.Duration/between
      (.toInstant inst1)
      (.toInstant inst2))))
    ;; basic base64
  (.encodeToString (java.util.Base64/getEncoder) (.getBytes "wes"))
  (java.lang.String. (.decode (java.util.Base64/getDecoder) "d2Vz")))