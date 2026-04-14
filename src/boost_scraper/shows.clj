(ns boost-scraper.shows
  "Show registry with slug-based lookup.
   Registry pattern - data-driven configuration for show matching."
  (:require [clojure.string :as str]))

(def shows
  "Map of slug -> show configuration.
   Uses sorted-map to preserve deterministic iteration order for UI."
  (sorted-map
   "all"    {:slug "all"    :name "All Shows"          :regex ".*"}
   "lup"    {:slug "lup"    :name "LINUX Unplugged"    :regex "(?i).*unplugged.*"}
   "launch" {:slug "launch" :name "The Launch 🚀"       :regex "(?i).*launch.*"}
   "twib"   {:slug "twib"   :name "This Week in Bitcoin" :regex "(?i).*bitcoin.*"}
   "ssh"    {:slug "ssh"    :name "Self-Hosted"        :regex "(?i).*hosted.*"}
   "coder"  {:slug "coder"  :name "Coder Radio"        :regex "(?i).*coder.*"}))

(defn resolve-show
  "Resolve a show identifier (slug or name) to show config.
   Returns nil if not found.
   Lookup order: slug (case-insensitive), then name (case-insensitive)."
  [id]
  (when (seq id)
    (let [id-normalized (str/lower-case id)]
      (or (get shows id-normalized)
          (some #(when (= (str/lower-case (:name %)) id-normalized) %) (vals shows))))))

(defn regex-for
  "Get regex pattern string for show identifier.
   Handles include-unknown flag.
   Returns nil if show not found."
  ([id]
   (regex-for id true))
  ([id include-unknown?]
   (when-let [show (resolve-show id)]
     (let [base (:regex show)]
       (if include-unknown?
         (str base "|Unknown Podcast")
         base)))))

(defn slug?
  "Check if identifier is a known slug."
  [id]
  (when (seq id)
    (contains? shows (str/lower-case id))))

(defn show-slugs
  "Return all available slugs in display order."
  []
  (keys shows))

(defn show-options
  "Return shows for UI dropdown or API.
   Each entry: {:slug :name :regex}"
  ([]
   (show-options true))
  ([include-unknown?]
   (for [[slug cfg] shows]
     {:slug slug
      :name (:name cfg)
      :regex (regex-for slug include-unknown?)})))