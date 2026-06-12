(ns boost-scraper.test-utils
  (:require [clojure.java.io :as io]))

(defn delete-dir-recursively
  "Delete a directory and all its contents recursively."
  [f]
  (doseq [child (.listFiles (io/file f))]
    (if (.isDirectory child)
      (delete-dir-recursively child)
      (io/delete-file child :silently)))
  (io/delete-file f :silently))
