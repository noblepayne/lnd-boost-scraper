(ns boost-scraper.schemas
  (:require [malli.util :as mu]))

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
   [:scraper/source {:optional true} :string]
   [:boostagram/payment_rail {:optional true} :string]
   [:boostagram/amount_fiat_cents {:optional true} :long]
   [:boostagram/amount_fiat_currency {:optional true} :string]
   [:boostagram/received_at {:optional true} inst?]
   [:boostagram/podcast_slug {:optional true} :string]
   [:boostagram/episode_guid {:optional true} :string]])

(defn as-optional [schema]
  (mu/optional-keys schema))
