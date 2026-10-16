(ns manor.clock
  "Game time for scenarios that want it. A clock maps rounds to times:

    {:start \"1904-10-15T08:00\" :minutes-per-round 30}

  Round 1 starts at :start, round 2 one round later, and so on. A scenario's
  :init returns the clock; the engine stores it in the world (:clock) through
  :world/initialized, so it is replayed like everything else. Without a
  clock, prompts and the panel show round numbers as before."
  (:import (java.time Duration LocalDateTime LocalTime)
           (java.time.format DateTimeFormatter)
           (java.util Locale)))

(defn- fmt [pattern] (DateTimeFormatter/ofPattern pattern Locale/ENGLISH))

(def ^:private long-format (fmt "EEEE d MMMM yyyy, HH:mm"))
(def ^:private day-format (fmt "EEE HH:mm"))
(def ^:private time-format (fmt "HH:mm"))

(defn problems
  "Reasons `clock` is malformed, or nil."
  [{:keys [start minutes-per-round] :as clock}]
  (seq (concat
        (when-not (map? clock) ["the clock must be a map {:start .. :minutes-per-round ..}"])
        (when-not (and (int? minutes-per-round) (<= 1 minutes-per-round 1440))
          [(str ":minutes-per-round must be a whole number from 1 to 1440, not " (pr-str minutes-per-round))])
        (when-not (try (LocalDateTime/parse (str start)) (catch Exception _ nil))
          [(str ":start must look like \"1904-10-15T08:00\", not " (pr-str start))]))))

(defn rounds-per-day [{:keys [minutes-per-round]}]
  (quot 1440 minutes-per-round))

(defn rounds-for
  "Whole rounds needed for `minutes` (at least 1)."
  [{:keys [minutes-per-round]} minutes]
  (max 1 (long (Math/ceil (/ minutes minutes-per-round)))))

(defn time-of
  "When `round` starts, as a LocalDateTime. Round 0 (setup) counts as round 1."
  ^LocalDateTime [{:keys [start minutes-per-round]} round]
  (.plusMinutes (LocalDateTime/parse start) (* minutes-per-round (dec (max 1 round)))))

(defn label
  "\"Saturday 15 October 1904, 09:30\""
  [clock round]
  (.format (time-of clock round) long-format))

(defn short-label
  "\"09:30\" if `round` is on the same day as `today`, else \"Sun 07:00\"."
  [clock round today]
  (let [t (time-of clock round)]
    (.format t (if (= (.toLocalDate t) (.toLocalDate (time-of clock today))) time-format day-format))))

(defn round-at
  "The first round after `round` that starts at or after the next `hh:mm`
  (e.g. \"07:00\"): the round in which someone who waits until then acts again."
  [{:keys [minutes-per-round] :as clock} round hh-mm]
  (let [now (time-of clock round)
        at (LocalTime/parse hh-mm)
        target (cond-> (.atTime (.toLocalDate now) at)
                 (not (.isAfter (.atTime (.toLocalDate now) at) now)) (.plusDays 1))
        minutes (.toMinutes (Duration/between now target))]
    (+ round (rounds-for clock minutes))))

(defn hh-mm?
  "Is `s` a time of day like \"07:00\"?"
  [s]
  (boolean (try (LocalTime/parse (str s)) (catch Exception _ nil))))
