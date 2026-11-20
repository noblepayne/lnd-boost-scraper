(ns boost-scraper.campaigns-test
  "Contract test for the campaign meter. Every number here is hand-computed at a
   deliberately round rate (100,000 USD/BTC => 1,000 sats = $1) so a wrong
   conversion cannot hide behind an ugly float."
  (:require [boost-scraper.campaigns :as campaigns]
            [boost-scraper.db :as db]
            [boost-scraper.test-utils :as test-utils]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]))

(def ^:dynamic *conn* nil)

(defn- delete-dir [dir]
  (when (.exists (java.io.File. dir))
    (test-utils/delete-dir-recursively dir)))

(defn- each-test-db [f]
  (let [dir (str "/tmp/test-campaign-" (java.util.UUID/randomUUID))
        conn (d/get-conn dir db/schema)]
    (try
      (binding [*conn* conn] (f))
      (finally
        (d/close conn)
        (Thread/sleep 50)
        (delete-dir dir)))))

(use-fixtures :each each-test-db)

(def T0 1767254400)                        ; 2026-01-01 00:00 LA
(def OPEN T0)
(def CLOSE (+ T0 (* 19 86400)))            ; 19 days after open
(def NOW (+ OPEN 3600))                    ; one hour in => 19 days left
(def RATE {:btc_usd 100000.0 :at NOW :source "test"})

(def test-campaign
  {:campaign/slug "test-camp"
   :campaign/title "Test Campaign"
   :campaign/subtitle "One line of copy."
   :campaign/window {:opens OPEN :closes CLOSE}
   :campaign/tag "#700club"
   :campaign/also-episode "700"
   :campaign/target {:fiat-cents 200000}                 ; $2,000
   :campaign/policy {:actions #{:boost}
                     :count-fiat? true
                     :count-member-free? false
                     :exclude-self? true}
   :campaign/cta {:label "Boost with #700club"
                  :url "https://example.test/"
                  :hint "not part of the contract"}})

(defn- boost
  [{:keys [sender sats type cents action msg episode epoch oid iid podcast]
    :or   {sender "alice" sats 1000 type :sat action "boost"
           epoch (inc OPEN) episode "695" podcast "LINUX Unplugged"
           iid (str "inv-" (java.util.UUID/randomUUID))}}]
  (cond-> {:invoice/identifier iid
           :invoice/creation_date epoch
           :boostagram/action action
           :boostagram/podcast podcast
           :boostagram/episode episode
           :boostagram/sender_name_normalized sender
           :boostagram/type type
           :boostagram/value_sat_total sats}
    msg (assoc :boostagram/message msg)
    cents (assoc :boostagram/amount_fiat_cents cents)
    oid (assoc :boostagram/zaprite_order_id oid)))

(defn- insert! [& rows] (d/transact! *conn* (vec rows)))

(defn- progress
  ([campaign] (progress campaign NOW RATE))
  ([campaign now rate]
   (campaigns/progress *conn* campaign now rate)))

;; ---------------------------------------------------------------------------
;; Config
;; ---------------------------------------------------------------------------

(deftest test-campaigns-resource-loads
  (testing "the shipped EDN is readable and carries the fields the meter needs"
    (let [c (campaigns/find-campaign "lup-700-brent")]
      (is (seq (campaigns/campaigns)))
      (is (some? c))
      (is (= "#700club" (:campaign/tag c)))
      (is (= 200000 (get-in c [:campaign/target :fiat-cents])))
      (is (= "700" (:campaign/also-episode c)))
      (is (some? (get-in c [:campaign/window :opens])))
      (is (some? (get-in c [:campaign/window :closes])))))
  (testing "keyword slugs resolve too"
    (is (= "lup-700-brent" (:campaign/slug (campaigns/find-campaign :lup-700-brent)))))
  (testing "an unknown slug resolves to nothing rather than a guess"
    (is (nil? (campaigns/find-campaign "no-such-campaign")))))

;; ---------------------------------------------------------------------------
;; Golden contract
;; ---------------------------------------------------------------------------

(deftest test-golden-contract
  (insert! (boost {:sender "alice" :sats 1000000 :msg "for the #700club!"
                   :episode "695" :iid "g-1"})
           (boost {:sender "bob" :type :fiat :sats 0 :cents 25000
                   :msg "count me in, #700Club" :episode "696" :iid "g-2"})
           (boost {:sender "anonymous" :sats 10000 :msg "#700club"
                   :episode "697" :iid "g-3"})
           ;; not members, and must move nothing:
           (boost {:sender "dave" :sats 999999 :msg "great show" :iid "g-4"})
           (boost {:sender "chrislas" :sats 777777 :msg "#700club" :iid "g-5"})
           (boost {:sender "erin" :sats 500000 :msg "#700club"
                   :action "stream" :iid "g-6"}))
  (let [p (progress test-campaign)]
    (testing "shape: the contract minus `recent`, and nothing else"
      (is (= #{:slug :title :subtitle :pitch :tag :stretch :goal :raised :split :remaining_usd
               :percent :days_left :opens_at :closes_at :generated_at :rate :cta}
             (set (keys p))))
      (is (= "test-camp" (:slug p)))
      (is (= "Test Campaign" (:title p)))
      (is (= "One line of copy." (:subtitle p)))
      (is (= "#700club" (:tag p)))
      (is (= "" (:stretch p)) "a campaign without stretch copy publishes empty")
      (is (= "" (:pitch p)) "a campaign without a pitch publishes empty")
      (is (= {:label "Boost with #700club" :url "https://example.test/"} (:cta p)))
      (is (= NOW (:generated_at p)))
      (is (= OPEN (:opens_at p)))
      (is (= CLOSE (:closes_at p)))
      (is (= RATE (:rate p))))
    (testing "goal: cents are canonical, sats are derived at the same rate"
      (is (= 2000.0 (get-in p [:goal :usd])))
      (is (= 2000000 (get-in p [:goal :sats]))))
    (testing "raised: 1,010,000 sats = $1010, plus $250 fiat = $1260"
      (is (= 1010000 (get-in p [:raised :sats])))
      (is (= 1260.0 (get-in p [:raised :usd])))
      (is (= 3 (get-in p [:raised :contributions])))
      (is (= 2 (get-in p [:raised :people])) "alice + bob are named people")
      (is (= 1 (get-in p [:raised :anonymous]))))
    (testing "the split says which rail the money came in on"
      (is (= {:usd 1010.0 :sats 1010000 :contributions 2} (get-in p [:split :sats])))
      (is (= {:usd 250.0 :sats 0 :contributions 1} (get-in p [:split :fiat])))
      (testing "and it reconciles with the headline total to the cent"
        (is (= (get-in p [:raised :usd])
               (+ (get-in p [:split :sats :usd]) (get-in p [:split :fiat :usd]))))
        (is (= (get-in p [:raised :sats]) (get-in p [:split :sats :sats])))))
    (testing "percent is exact, remaining is goal minus raised"
      (is (= 63.0 (:percent p)))
      (is (= 740.0 (:remaining_usd p))))
    (testing "days left is a countdown from `now`"
      (is (= 19 (:days_left p))))))

;; ---------------------------------------------------------------------------
;; Membership
;; ---------------------------------------------------------------------------

(deftest test-tag-membership-is-case-insensitive
  (insert! (boost {:sender "alice" :sats 1000 :msg "go #700CLUB go" :iid "c-1"})
           (boost {:sender "bob" :sats 2000 :msg "#700club" :iid "c-2"})
           (boost {:sender "carol" :sats 4000 :msg "no tag here" :iid "c-3"}))
  (let [p (progress test-campaign)]
    (is (= 3000 (get-in p [:raised :sats])))
    (is (= 2 (get-in p [:raised :contributions])))))

(deftest test-tag-survives-trailing-punctuation
  ;; Listeners type the tag by hand, usually at the end of a sentence. The rule
  ;; is case-insensitive containment, so terminal punctuation cannot break it —
  ;; pinned here because an "exact tag" refactor would silently stop counting
  ;; real boosts, and the loss would be invisible (the meter just sits lower).
  ;; The trade is accepted: a longer word containing the tag also matches.
  (insert! (boost {:sender "a" :sats 1000 :msg "lets go #700club!" :iid "p-1"})
           (boost {:sender "b" :sats 2000 :msg "sent. #700club." :iid "p-2"})
           (boost {:sender "c" :sats 3000 :msg "#700club, obviously" :iid "p-3"})
           (boost {:sender "d" :sats 4000 :msg "(#700club)" :iid "p-4"})
           (boost {:sender "e" :sats 5000 :msg "#700CLUB?" :iid "p-5"})
           (boost {:sender "f" :sats 6000 :msg "no tag here" :iid "p-6"}))
  (let [p (progress test-campaign)]
    (is (= 15000 (get-in p [:raised :sats])) "every punctuated form counts")
    (is (= 5 (get-in p [:raised :contributions])) "the untagged boost still does not")))

(deftest test-episode-membership
  (testing "the finale episode counts even with no tag in the message"
    (insert! (boost {:sender "alice" :sats 5000 :msg "no tag" :episode "700" :iid "e-1"}))
    (is (= 5000 (get-in (progress test-campaign) [:raised :sats]))))
  (testing "a nearby episode does not"
    (insert! (boost {:sender "bob" :sats 9000 :msg "no tag" :episode "699" :iid "e-2"}))
    (is (= 5000 (get-in (progress test-campaign) [:raised :sats])))))

(deftest test-window-boundaries
  (insert! (boost {:sender "before" :sats 100000 :msg "#700club"
                   :epoch (dec OPEN) :iid "w-1"})
           (boost {:sender "at-open" :sats 2000 :msg "#700club"
                   :epoch OPEN :iid "w-2"})
           (boost {:sender "last-second" :sats 3000 :msg "#700club"
                   :epoch (dec CLOSE) :iid "w-3"})
           (boost {:sender "at-close" :sats 400000 :msg "#700club"
                   :epoch CLOSE :iid "w-4"}))
  (testing "opens inclusive, closes exclusive"
    (let [p (progress test-campaign)]
      (is (= 5000 (get-in p [:raised :sats])))
      (is (= 2 (get-in p [:raised :contributions]))))))

(deftest test-self-boosts-excluded
  (insert! (boost {:sender "chrislas" :sats 9999 :msg "#700club" :iid "s-1"})
           (boost {:sender "noblepayne" :sats 8888 :msg "#700club" :iid "s-2"})
           (boost {:sender "alice" :sats 100 :msg "#700club" :iid "s-3"}))
  (is (= 100 (get-in (progress test-campaign) [:raised :sats])))
  (testing "the policy flag is what does it, not a hardcode in the lens"
    (is (= 18987 (get-in (progress (assoc-in test-campaign
                                            [:campaign/policy :exclude-self?] false))
                         [:raised :sats])))))

(deftest test-streams-excluded-by-policy
  (insert! (boost {:sender "alice" :sats 100 :msg "#700club" :iid "st-1"})
           (boost {:sender "bob" :sats 5000 :msg "#700club"
                   :action "stream" :iid "st-2"}))
  (is (= 100 (get-in (progress test-campaign) [:raised :sats]))))

(deftest test-anonymous-counted-unnamed
  (insert! (boost {:sender "anonymous" :sats 2000 :msg "#700club" :iid "a-1"})
           (boost {:sender "alice" :sats 3000 :msg "#700club" :iid "a-2"}))
  (let [p (progress test-campaign)]
    (testing "anonymous money is real money"
      (is (= 5000 (get-in p [:raised :sats])))
      (is (= 5.0 (get-in p [:raised :usd]))))
    (testing "but the anonymity bucket is not a person"
      (is (= 1 (get-in p [:raised :people])))
      (is (= 1 (get-in p [:raised :anonymous])))
      (is (= 2 (get-in p [:raised :contributions]))))))

;; ---------------------------------------------------------------------------
;; Policy: what counts as value
;; ---------------------------------------------------------------------------

(deftest test-member-free-is-presence-not-value
  (insert! (boost {:sender "alice" :sats 1000 :msg "#700club" :iid "m-1"})
           (boost {:sender "freeguy" :type :member-free :sats 0 :cents 5000
                   :msg "#700club" :iid "m-2"}))
  (let [p (progress test-campaign)]
    (testing "a free member boost adds a person, never a dollar"
      (is (= 1.0 (get-in p [:raised :usd])))
      (is (= 1000 (get-in p [:raised :sats])))
      (is (= 2 (get-in p [:raised :contributions])))
      (is (= 2 (get-in p [:raised :people])))))
  (testing "unless the campaign says member-free carries value"
    (let [p (progress (assoc-in test-campaign
                                [:campaign/policy :count-member-free?] true))]
      (is (= 51.0 (get-in p [:raised :usd]))))))

(deftest test-fiat-policy
  (insert! (boost {:sender "alice" :sats 1000 :msg "#700club" :iid "f-1"})
           (boost {:sender "bob" :type :fiat :sats 0 :cents 10000
                   :msg "#700club" :iid "f-2"}))
  (testing "fiat dollars count toward a dollar goal by default"
    (is (= 101.0 (get-in (progress test-campaign) [:raised :usd]))))
  (testing "a sats-only campaign ignores fiat value but keeps the person"
    (let [p (progress (assoc-in test-campaign [:campaign/policy :count-fiat?] false))]
      (is (= 1.0 (get-in p [:raised :usd])))
      (is (= 2 (get-in p [:raised :people]))))))

;; ---------------------------------------------------------------------------
;; The accounting seam
;; ---------------------------------------------------------------------------

(deftest test-bare-invoice-shell-does-not-double-count
  (testing "the live shape of a web-boost: an untyped LND invoice shell beside
            the fiat order contributes nothing on its own"
    (insert! (boost {:sender "bob" :type :fiat :sats 0 :cents 2500
                     :msg "#700club" :iid "shell-1"})
             {:invoice/identifier "shell-343910"
              :invoice/creation_date (inc OPEN)
              :boostagram/action "boost"
              :boostagram/podcast "LINUX Unplugged"
              :boostagram/episode "695"
              :boostagram/value_sat_total 0})
    (let [p (progress test-campaign)]
      (is (= 1 (get-in p [:raised :contributions])))
      (is (= 25.0 (get-in p [:raised :usd]))))))

;; ---------------------------------------------------------------------------
;; Progress semantics
;; ---------------------------------------------------------------------------

(deftest test-percent-not-clamped-and-remaining-floored
  (insert! (boost {:sender "alice" :sats 2500000 :msg "#700club" :iid "p-1"}))
  (let [p (progress test-campaign)]
    (testing "125% is information, not a bug to hide in the computation"
      (is (= 125.0 (:percent p))))
    (testing "remaining never goes negative"
      (is (= 0.0 (:remaining_usd p))))
    (is (= 2500.0 (get-in p [:raised :usd])))))

(deftest test-days-left
  (testing "part of a day still counts as a day left"
    (is (= 19 (:days_left (progress test-campaign NOW RATE)))))
  (testing "at close it is zero, not negative"
    (is (= 0 (:days_left (progress test-campaign CLOSE RATE)))))
  (testing "after close it stays zero"
    (is (= 0 (:days_left (progress test-campaign (+ CLOSE 999) RATE))))))

;; ---------------------------------------------------------------------------
;; Refusals — the whole point
;; ---------------------------------------------------------------------------

(deftest test-no-rate-refusal
  (insert! (boost {:sender "alice" :sats 1000 :msg "#700club" :iid "r-1"}))
  (testing "nil is a refusal, never a zero-rate conversion"
    (is (= {:status :no-rate} (progress test-campaign NOW nil))))
  (testing "a zero/invalid rate is the same refusal"
    (is (= {:status :no-rate} (progress test-campaign NOW {:rate 0 :source "test"})))
    (is (= {:status :no-rate} (progress test-campaign NOW {:btc_usd nil :source "test"}))))
  (testing "no money is computed at all — no partial map leaks out"
    (is (= [:status] (keys (progress test-campaign NOW nil))))))

(deftest test-unknown-slug-refusal
  (is (= {:status :not-found} (progress "no-such-campaign")))
  (is (= {:status :not-found} (progress "no-such-campaign" NOW nil))
      "not-found wins over no-rate: there is nothing to price"))

(deftest test-rate-shapes
  (insert! (boost {:sender "alice" :sats 1000000 :msg "#700club" :iid "rs-1"}))
  (testing "the raw price-feed shape is accepted and priced identically"
    (let [p (progress test-campaign NOW {:rate 100000.0 :source "mempool.space"})]
      (is (= 1000.0 (get-in p [:raised :usd])))
      (is (= 100000.0 (get-in p [:rate :btc_usd])))
      (is (= NOW (get-in p [:rate :at])) "the emit moment is stamped, not invented")
      (is (= "mempool.space" (get-in p [:rate :source]))))))

(deftest test-real-config-smoke
  (testing "the shipped campaign computes end to end from its own window"
    (let [c (campaigns/find-campaign "lup-700-brent")
          opens (get-in c [:campaign/window :opens])
          now (+ opens 60)]
      (insert! (boost {:sender "alice" :sats 100000 :msg "for #700club"
                       :epoch (+ opens 60) :iid "real-1"}))
      (let [p (progress "lup-700-brent" now RATE)]
        (is (= "lup-700-brent" (:slug p)))
        (is (= 100000 (get-in p [:raised :sats])))
        (is (= 100.0 (get-in p [:raised :usd])) "100k sats at 100k USD/BTC")
        (is (= 2000.0 (get-in p [:goal :usd])))
        (is (pos? (:days_left p)))
        (is (= opens (:opens_at p)))
        (is (string? (:stretch p)))
        (is (seq (:stretch p)) "the shipped campaign carries over-goal copy")
        (is (seq (:pitch p)) "the shipped campaign carries a one-line pitch")))))

(deftest test-also-episode-matches-by-prefix
  (testing "episode labels carry titles, so the finale matches as a prefix"
    (insert! (boost {:sender "alice" :sats 5000 :episode "700: The Big One" :iid "ep-1"}))
    (let [p (progress test-campaign NOW RATE)]
      (is (= 5000 (get-in p [:raised :sats]))
          "an untagged boost on ep 700 counts, despite the title suffix"))))

(deftest test-scope-excludes-other-shows
  (testing "another show's episode 700 is not this campaign"
    (insert! (boost {:sender "alice" :sats 9000 :episode "700: Elsewhere"
                     :podcast "This Week in Bitcoin" :iid "scope-1"}))
    (let [c (assoc test-campaign :campaign/scope {:shows #{"lup"}})
          p (progress c NOW RATE)]
      (is (zero? (get-in p [:raised :sats]))))))
