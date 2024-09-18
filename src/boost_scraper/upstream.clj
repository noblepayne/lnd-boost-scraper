(ns boost-scraper.upstream)

(defprotocol IBoostScrape
  (get-boosts [_ {:keys [:token :wait :offset]}]))

(defonce scrape (atom true))