(ns boost-scraper.schemas
  (:require [malli.core :as m]
            [malli.util :as mu]))

(def ClientState
  [:map
   [:client-state/key :string]
   [:client-state/client-id :string]
   [:client-state/show-slug :string]
   [:client-state/last-seen-tx :long]
   [:client-state/last-accessed-tx :long]])

(def Boost
  [:map
   [:invoice/identifier :string]
   [:invoice/source {:optional true} :string]
   [:invoice/creation_date :long]
   [:invoice/created_at inst?]
   [:boostagram/sender_name {:optional true} :string]
   [:boostagram/sender_name_normalized {:optional true} :string]
   [:boostagram/episode {:optional true} :string]
   [:boostagram/podcast {:optional true} :string]
   [:boostagram/app_name {:optional true} :string]
   [:boostagram/action :string]
   [:boostagram/message {:optional true} :string]
   [:boostagram/value_sat_total :long]
   [:scraper/source {:optional true} :string]])

(defn as-optional [schema]
  (mu/optional-keys schema))
