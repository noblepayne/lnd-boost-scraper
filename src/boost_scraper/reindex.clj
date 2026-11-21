(ns boost-scraper.reindex
  "One-off: rebuild the datalog indexes after a schema change.

   Datalevin cannot add an index to an existing attribute in place — the store is
   dumped, reloaded and re-indexed, which closes the connection it was handed. So
   this runs as its own process, with the scraper stopped, one database at a time.

   Usage:
     java -cp <jar> clojure.main -m boost-scraper.reindex <db-dir> [<db-dir> ...]

   Idempotent: re-indexing an already-indexed database is wasted time, not damage."
  (:require [boost-scraper.db :as db]
            [datalevin.core :as d]))

(defn reindex-one!
  "Re-index `dir` with the current schema and verify the index actually landed.
   Returns the elapsed milliseconds. Throws if the index is missing afterwards,
   because a silent no-op here looks exactly like a successful migration."
  [dir]
  (let [started (System/currentTimeMillis)]
    (println "Re-indexing" dir)
    (let [conn (d/get-conn dir db/schema)]
      (d/re-index conn db/schema {})
      ;; re-index closes the store it was given; closing again is harmless but
      ;; not guaranteed, so it is guarded.
      (try (d/close conn) (catch Throwable _ nil)))
    (let [conn (d/get-conn dir db/schema)
          attr (get (d/schema conn) :invoice/creation_date)
          elapsed (- (System/currentTimeMillis) started)]
      (d/close conn)
      (println (format "  done in %.1fs; :invoice/creation_date -> %s" (/ elapsed 1000.0) attr))
      (when-not (:db/index attr)
        (throw (ex-info "index missing after re-index" {:dir dir :attr attr})))
      elapsed)))

(defn -main [& dirs]
  (when (empty? dirs)
    (println "usage: reindex <db-dir> [<db-dir> ...]")
    (System/exit 2))
  (doseq [dir dirs]
    (reindex-one! dir))
  (println "All done."))
