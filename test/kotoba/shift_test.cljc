(ns kotoba.shift-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.shift :as sh]))

(def ^:private hour 3600000)
(def ^:private t0 1767225600000) ;; 2026-01-01T00:00:00Z

(defn- h [n] (+ t0 (long (* n hour))))

(defn- p [at kind] (sh/punch "w-1" (h at) kind))

;; ---------------------------------------------------------------------------
;; Punch construction
;; ---------------------------------------------------------------------------

(deftest punch-rejects-an-unknown-kind
  (is (nil? (sh/punch "w-1" (h 9) :vibes)))
  (is (some? (sh/punch "w-1" (h 9) :in))))

;; ---------------------------------------------------------------------------
;; Pairing — the happy path
;; ---------------------------------------------------------------------------

(deftest a-clean-shift-pairs-into-one-span
  (let [{:keys [worked anomalies]} (sh/pair-punches [(p 9 :in) (p 17 :out)])]
    (is (empty? anomalies))
    (is (= 1 (count worked)))
    (is (= (* 8 hour) (:worked/ms (first worked))))
    (is (zero? (:worked/break-ms (first worked))))))

(deftest breaks-are-deducted
  (let [{:keys [worked]} (sh/pair-punches
                          [(p 9 :in) (p 12 :break-start) (p 13 :break-end) (p 18 :out)])
        w (first worked)]
    (is (= (* 9 hour) (:worked/gross-ms w)))
    (is (= (* 1 hour) (:worked/break-ms w)))
    (is (= (* 8 hour) (:worked/ms w)))))

(deftest two-shifts-in-a-day-pair-separately
  (let [{:keys [worked anomalies]} (sh/pair-punches
                                    [(p 6 :in) (p 10 :out) (p 17 :in) (p 21 :out)])]
    (is (empty? anomalies))
    (is (= 2 (count worked)))
    (is (= (* 8 hour) (sh/worked-ms worked)))))

(deftest punches-are-sorted-before-pairing
  (is (= (sh/pair-punches [(p 9 :in) (p 17 :out)])
         (sh/pair-punches [(p 17 :out) (p 9 :in)]))))

;; ---------------------------------------------------------------------------
;; The invariant — an unpaired punch is never completed
;; ---------------------------------------------------------------------------

(deftest a-missing-clock-out-produces-no-worked-time
  (let [{:keys [worked anomalies]} (sh/pair-punches [(p 9 :in)])]
    (testing "not filled with end of day, end of shift, or now"
      (is (empty? worked))
      (is (zero? (sh/worked-ms worked))))
    (is (= [:missing-out] (mapv :anomaly/rule anomalies)))
    (is (= (h 9) (:anomaly/at (first anomalies))))))

(deftest a-missing-clock-out-does-not-swallow-the-next-shift
  (let [{:keys [worked anomalies]} (sh/pair-punches
                                    [(p 9 :in) (p 14 :in) (p 18 :out)])]
    (testing "the second :in is an anomaly; the day still yields one honest span"
      (is (= 1 (count worked)))
      (is (= (* 9 hour) (:worked/ms (first worked))))
      (is (= [:double-in] (mapv :anomaly/rule anomalies))))))

(deftest an-out-with-no-in-is-an-anomaly-not-a-shift-from-midnight
  (let [{:keys [worked anomalies]} (sh/pair-punches [(p 17 :out)])]
    (is (empty? worked))
    (is (= [:out-without-in] (mapv :anomaly/rule anomalies)))))

(deftest an-unclosed-break-voids-the-span-rather-than-crediting-it
  (let [{:keys [worked anomalies]} (sh/pair-punches
                                    [(p 9 :in) (p 12 :break-start) (p 18 :out)])]
    (testing "how long the break was is unreconstructable, so the span is not credited"
      (is (empty? worked))
      (is (= [:unclosed-break] (mapv :anomaly/rule anomalies))))))

(deftest an-impossible-in-out-order-yields-no-worked-time
  (testing "an :out before its :in, and an :in/:out at the same instant, both
            surface as anomalies rather than as a span of some invented length"
    (doseq [out-at [8 9]]
      (let [{:keys [worked anomalies]} (sh/pair-punches [(p 9 :in) (p out-at :out)])]
        (is (empty? worked) (str "out-at " out-at))
        (is (= [:out-without-in :missing-out] (mapv :anomaly/rule anomalies))
            (str "out-at " out-at))))))

(deftest a-shift-ending-as-the-next-begins-pairs-cleanly
  (testing "the same-instant ordering rule exists for this case"
    (let [{:keys [worked anomalies]} (sh/pair-punches
                                      [(p 9 :in) (p 13 :out) (p 13 :in) (p 17 :out)])]
      (is (empty? anomalies))
      (is (= 2 (count worked)))
      (is (= (* 8 hour) (sh/worked-ms worked))))))

(deftest break-punches-while-clocked-out-are-anomalies
  (let [{:keys [anomalies]} (sh/pair-punches [(p 12 :break-start) (p 13 :break-end)])]
    (is (= [:break-outside :break-outside] (mapv :anomaly/rule anomalies)))))

(deftest describe-anomalies-is-empty-on-a-clean-day
  (is (= "" (sh/describe-anomalies (sh/pair-punches [(p 9 :in) (p 17 :out)]))))
  (is (= "1× missing-out"
         (sh/describe-anomalies (sh/pair-punches [(p 9 :in)])))))

;; ---------------------------------------------------------------------------
;; Timesheet emission
;; ---------------------------------------------------------------------------

(deftest worked-spans-emit-the-shared-ts-shape
  (let [{:keys [worked]} (sh/pair-punches [(p 9 :in) (p 12 :break-start)
                                           (p 13 :break-end) (p 18 :out)])
        entries (sh/->timesheet-entries worked (constantly "2026-01-01"))]
    (is (= [{:ts/worker "w-1" :ts/date "2026-01-01" :ts/hours 8.0}] entries))))

(deftest attendance-is-not-rounded
  (testing "unlike activity capture, a punch is an assertion — 7h13m stays 7h13m"
    (let [{:keys [worked]} (sh/pair-punches
                            [(sh/punch "w-1" t0 :in)
                             (sh/punch "w-1" (+ t0 (* 7 hour) (* 13 60000)) :out)])
          entries (sh/->timesheet-entries worked (constantly "2026-01-01"))]
      (is (< 7.216 (:ts/hours (first entries)) 7.217)))))

;; ---------------------------------------------------------------------------
;; Coverage
;; ---------------------------------------------------------------------------

(def ^:private roster
  [(sh/shift "s-1" "w-1" :nurse (h 8) (h 20))
   (sh/shift "s-2" "w-2" :nurse (h 8) (h 20))
   (sh/shift "s-3" "w-3" :nurse (h 8) (h 12))])   ;; partial

(deftest coverage-reports-the-gap-and-does-not-close-it
  (let [c (sh/coverage roster (sh/demand :nurse (h 8) (h 20) 4))]
    (is (= 2 (:coverage/staffed c)))
    (is (= 2 (:coverage/gap c)))
    (testing "the half-shift is listed as partial, not counted as half a nurse"
      (is (= ["s-3"] (:coverage/partial c))))))

(deftest coverage-reports-surplus-too
  (let [c (sh/coverage roster (sh/demand :nurse (h 8) (h 20) 1))]
    (is (= 1 (:coverage/surplus c)))
    (is (zero? (:coverage/gap c)))))

(deftest a-different-role-does-not-cover-the-demand
  (let [c (sh/coverage roster (sh/demand :pharmacist (h 8) (h 20) 1))]
    (is (zero? (:coverage/staffed c)))
    (is (= 1 (:coverage/gap c)))))

;; ---------------------------------------------------------------------------
;; Variance
;; ---------------------------------------------------------------------------

(deftest variance-reports-early-start-and-late-finish
  (let [{:keys [worked]} (sh/pair-punches [(p 8 :in) (p 21 :out)])
        [v] (sh/variance [(sh/shift "s-1" "w-1" :nurse (h 9) (h 20))] worked)]
    (is (= :worked (:variance/kind v)))
    (is (= (- hour) (:variance/start-delta-ms v)))
    (is (= hour (:variance/end-delta-ms v)))))

(deftest a-shift-nobody-worked-is-absent
  (let [[v] (sh/variance [(sh/shift "s-1" "w-1" :nurse (h 9) (h 20))] [])]
    (is (= :absent (:variance/kind v)))))

(deftest worked-time-under-no-shift-is-unrostered
  (let [{:keys [worked]} (sh/pair-punches [(p 22 :in) (p 23 :out)])
        vs (sh/variance [] worked)]
    (is (= [:unrostered] (mapv :variance/kind vs)))
    (is (= hour (:variance/worked-ms (first vs))))))

(deftest one-worked-span-is-not-matched-to-two-shifts
  (let [{:keys [worked]} (sh/pair-punches [(p 9 :in) (p 17 :out)])
        vs (sh/variance [(sh/shift "s-1" "w-1" :nurse (h 9) (h 13))
                         (sh/shift "s-2" "w-1" :nurse (h 13) (h 17))]
                        worked)]
    (testing "the second shift reads as absent rather than being credited twice"
      (is (= [:worked :absent] (mapv :variance/kind vs))))))
