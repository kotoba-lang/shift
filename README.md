# kotoba-shift

**Attendance in pure Clojure** — punches, shifts, rosters, coverage. A
[kotoba-lang](https://github.com/kotoba-lang) capability library for the 打刻 /
rostering half of workforce management (the UKG / Dayforce / ADP / Deputy /
QuickBooks Time category).

```
punches ─pair-punches→ worked spans ─→ :ts/* entries
                    ╰→ anomalies
roster + demand ─coverage→ gaps        roster + worked ─variance→ absent / unrostered
```

## Sibling, not duplicate, of `kotoba.activity`

The two look alike and mean different things.
[`activity`](https://github.com/kotoba-lang/activity) **samples** — it asks "what
is in front of you just now", many times. This **records** — a punch is a
deliberate assertion that a shift began.

So `activity` segments a stream and undercounts by one sample interval; this
pairs discrete events and refuses to complete an unpaired one. And where
`activity` rounds to a billing increment, attendance is not rounded at all: 7h13m
stays 7h13m, because a punch is an assertion and rounding an assertion changes
what someone said.

The statutory side is deliberately **not** here — see
[`worklaw`](https://github.com/kotoba-lang/worklaw). Labour law applies to worked
time from any source, not just to time that came from a punch clock.

## Maturity

| | |
|---|---|
| Role | capability |
| Dependencies | none |
| Tests | 22 tests, 50 assertions, all green |
| Runtime | `.cljc`, JVM + ClojureScript |
| Actor | `cloud-itonami/kintai` (勤怠) |

## The invariant

**An unpaired punch is never completed.** A missing clock-out does not become
"end of shift", "end of day" or "now". It becomes an anomaly with no worked time
attached, because the alternative is inventing the most consequential number on
the timesheet.

```clojure
(sh/pair-punches [(sh/punch "w-1" nine :in)])
;; => {:worked []                                  ; not 8 hours. not anything.
;;     :anomalies [{:anomaly/rule :missing-out :anomaly/at nine}]}
```

The same refusal covers an unclosed break: how long the break was is
unreconstructable after the fact, so the span is reported rather than credited
whole.

## Contract

```clojure
(require '[kotoba.shift :as sh])

(sh/punch "w-1" at :in)          ;; :in :out :break-start :break-end
(sh/pair-punches punches)
;; => {:worked [{:worked/person "w-1" :worked/start .. :worked/end ..
;;               :worked/gross-ms .. :worked/break-ms .. :worked/ms ..}]
;;     :anomalies [...]}
```

| anomaly | |
|---|---|
| `:double-in` | a second `:in` while already clocked in |
| `:out-without-in` | an `:out` with no open `:in` |
| `:missing-out` | the period ends with an `:in` still open |
| `:break-outside` | a break punch while clocked out |
| `:double-break` | a `:break-start` while already on break |
| `:unclosed-break` | a `:break-start` with no `:break-end` before the `:out` |

Punches at the same instant are ordered `:out, :break-end, :break-start, :in`, so
a shift ending exactly as the next begins pairs correctly instead of reading as a
double clock-in. The consequence, which is real: an `:in` and an `:out` stamped
at the *same* instant read as `:out-without-in` then `:missing-out`, not as a
zero-length shift. Both readings say the terminal recorded something impossible;
this one says it without inventing an ordering the data does not contain.

```clojure
(sh/worked-ms worked)
(sh/worked-hours worked)
(sh/->timesheet-entries worked date-of)   ;; caller owns the timezone
(sh/describe-anomalies paired)            ;; "" on a clean day

;; Rostering
(sh/shift "s-1" "w-1" :nurse start end)
(sh/coverage roster (sh/demand :nurse from to 4))
;; => {:coverage/staffed 2 :coverage/gap 2 :coverage/surplus 0
;;     :coverage/partial ["s-3"]}

(sh/variance roster worked)
;; => [{:variance/kind :worked :variance/start-delta-ms -3600000 ...}
;;     {:variance/kind :absent ...}
;;     {:variance/kind :unrostered ...}]
```

`coverage` reports the gap and does not close it. A rostering tool that quietly
reassigns someone to fill a hole is how a person ends up working a shift they
never agreed to. A shift that only partly spans the demand window is listed
under `:coverage/partial` rather than counted as a fraction of a person, which
no shift can be staffed with.

## Test

```bash
clojure -M:test
clojure -M:lint
```

## License

Apache-2.0.
