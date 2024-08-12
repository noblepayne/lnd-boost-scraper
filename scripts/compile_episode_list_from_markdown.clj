(ns markdown.link.finder
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(defn find-links [text]
  (let [link-regex #"(?:\[(.+?)\]\((.+?)(?: \"(.+?)\")?\))"]
    (re-seq link-regex text)))

(defn get-links [text]
  (->> text
       find-links
       (map (fn [[_ text link]]
              {:text text :link link}))))

(defn get-markdown-files [dir]
  (filter #(.endsWith (str %) ".md") (fs/list-dir dir)))

(defn process-file [file]
  (let [filename (fs/file file)
        text (slurp filename)
        links (get-links text)]
    {(str filename) links}))

(defn extract-number [filename]
  (-> filename
      (clojure.string/replace #"[^\d]" "")
      (Integer.)
      str))

(defn run [dir]
  (let [markdown-files (into (sorted-set) (get-markdown-files dir))
        file-maps (flatten (map process-file markdown-files))]
    (doseq [file-map file-maps]
      (let [ep (extract-number (str (first (keys file-map))))
            links (first (vals file-map))]
        (println "## " ep)
        (doseq [link links]
          (let [text (:text link)
                link (:link link)]
            (println "+ [" text "](" link ")")))
        (println)))))

(comment
  (run "."))