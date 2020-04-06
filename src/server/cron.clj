;; Utility functions for controlling and  monitoring long running background system processes
;;
;; The use and distribution terms for this software are covered by
;; the GNU General Public License
;;
;; (C) 2019, Otto Linnemann

(ns server.cron
  (:use [crossover.macros])
  (:import [java.util TimerTask Timer]
           [java.time LocalDateTime]))


(defn- get-now
  []
  (let [now (LocalDateTime/now)
        s (.getSecond now)
        m (.getMinute now)
        h (.getHour now)
        dom (.getDayOfMonth now)
        mon (.getMonthValue now)
        dow (.getValue (.getDayOfWeek now))]
    (hash-args s m h dom mon dow)))


(def ^{:private true :dynamic true
       :doc "scheduling check routing is invoked every 10 seconds to cope with gc"}
  *schedule-base-time-period* 60)

(defn- schedule
  [crontab]
  (let [now (get-now)
        base-tick (quot (:s now) *schedule-base-time-period*)]
    (comment println "scheduling timer task at ->" now ", base tick: " base-tick)
    (loop [rem crontab]
      (when-let [entry (first rem)]
        (let [{:keys [m h dom mon dow enabled] :or {enabled true}} entry]

          (when (and enabled
                     (= 0 base-tick)
                     (or (not m)   (= (:m   now) m))
                     (or (not h)   (= (:h   now) h))
                     (or (not dom) (= (:dom now) dom))
                     (or (not mon) (= (:mon now) mon))
                     (or (not dow) (= (:dow now) dow)))
            ((:handler entry)))
          (recur (rest rem)))))))


(defn- calcuate-start-offset
  "calculate the start offset for the currently given second s

   This   is   done   such   that    the   base   timer   is   started
   just  between   two  trigger   (counting)  events  at   the  period
   *schedule-base-time-period*.  In example  when we  invoke the  base
   timer's  scheduling function  every 60  seconds, we  would like  to
   ensure that the first scheduling function is invoked exactly at the
   30th  second  of the  system  time.  That  allows  a jitter  of  +-
   30  seconds until  we  miss resprectively  duplicate one  scheduled
   event which  should be feasible  even with java's  non-real garbage
   collector."
  [s]
  (let [current-sec s
        delta-start (- *schedule-base-time-period* (rem current-sec *schedule-base-time-period*))
        half-base-period (quot *schedule-base-time-period* 2)
        offset (+ delta-start half-base-period)
        next-offset (rem offset *schedule-base-time-period*)]
    [offset next-offset]))


(comment illustration
  (doseq [s (range 0 60)]
    (let [[offset next-offset] (calcuate-start-offset s)]
      (println "second: " s ", offset: " offset , " , next offset: " next-offset)))
  )


(defn start-cron
  [crontab]
  (let [task (proxy [TimerTask] []
               (run [] (schedule crontab)))
        timer (Timer.)
        period (long (* *schedule-base-time-period* 1000))
        {:keys [s m h]} (get-now)
        [_  delay] (calcuate-start-offset s)]
    (println (format "first cron table check will be at %02d:%02d:%02d in %02d seconds ..."
                     h m 30 delay))
    (. timer (scheduleAtFixedRate task
                                  (long (* delay 1000))
                                  (long (* *schedule-base-time-period* 1000))))
    timer))


(defn stop-cron
  [cron-timer]
  (.cancel cron-timer))



(comment

  (get-now)

  (def test-crontab
    [{:m 0 :h 2 :dom false :mon false :dow false :handler #(println "do it " (str (get-now)))}
     {:m 1 :h 2 :dom false :mon false :dow false :handler #(println "do it " (str (get-now)))}
     {:m 25 :h 20 :dom false :mon false :dow false :handler #(println "do it " (str (get-now)))}])

  (def cron-timer (start-cron test-crontab))

  (stop-cron cron-timer)

  )
