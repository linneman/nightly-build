;; Utility functions for controlling and  monitoring long running background system processes
;;
;; The use and distribution terms for this software are covered by
;; the GNU General Public License
;;
;; (C) 2019, Otto Linnemann

(ns server.shell-utils
  (:require [clojure.string :as str]
            [me.raynes.conch.low-level :as sh])
  (:import [java.io ByteArrayOutputStream]))


(defn println-err
  "println to stderr"
  [& args]
  (binding [*out* *err*] (apply println args)))


(def process-store (atom {}))


(defn update-process-store
  "clean and update the process store"
  ([store]
   (update-process-store store nil nil))
  ([store new-process cmd-line]
   (let [store (into {} (filter #(.isAlive (:process (key %))) store))]
     (if new-process
       (assoc store new-process {:cmd cmd-line})
       store))))


(defn sh-cmd
  "executes long running system commands and redirects stdout and stderr
   to provided instances of ByteArrayOutputStream

   refer to https://github.com/Raynes/conch for more detailed information "
  [command-line output-stream error-stream & {:keys [interactive]
                                              :or {interactive false}}]
  (let [args ["/bin/sh"]
        args (if interactive (conj args "-i") args)
        args (concat args ["-c" command-line])
        p (apply sh/proc args)
        _ (swap! process-store update-process-store p command-line)
        _ (future (sh/stream-to p :out output-stream))
        _ (future (sh/stream-to p :err error-stream))]
    p))


(defn start-stream-observer-thread
  "starts and returns background thread  which copies and resets given
  instance of  class ByteArrayOutputStream with given  frequency [1/s]
  and  invokes periodically  call-back  function  with new  associated
  string."
  [stream call-back & {:keys [freq] :or {freq 1.0}}]
  (let [idle_time_ms (/ 1000.0 freq)
        t (Thread.
           (fn []
             (let [out (.toString stream)]
               (.reset stream)
               (call-back out)
               (Thread/sleep idle_time_ms)
               (recur))))]
    (dorun (.start t))
    t))


(defn stop-stream-observer-thread
  "stops the provided background observer thread"
  [t]
  (.stop t))


(defn is-alive
  "returns true when process is alive"
  [proc]
  (.isAlive (:process proc)))


(defn wait-for-process-exit
  "wait for process to exit and returns exit code"
  ([proc]
   (wait-for-process-exit proc 0))
  ([proc timeout]
   (let [ret (if (= 0 timeout)
               (sh/exit-code proc)
               (sh/exit-code proc timeout))]
     (swap! process-store update-process-store)
     ret)))


(defn destroy-process
  "forces process to stop"
  [proc]
  (sh/destroy proc)
  (wait-for-process-exit proc)
  (swap! process-store update-process-store))


(defn destroy-all-processes
  "forces all running processes to stop"
  []
  (dorun (map (fn [[k v]]
                (destroy-process k)
                (wait-for-process-exit k))
              @process-store))
  (swap! process-store update-process-store))


(comment

  (def stdout-stream (ByteArrayOutputStream. 10000))
  (def stderr-stream (ByteArrayOutputStream. 10000))
  (def stdout-observer (start-stream-observer-thread stdout-stream println :freq 3))
  (def stderr-observer (start-stream-observer-thread stderr-stream println-err))

  (def loop-script-file (.getFile (clojure.java.io/resource "loop.sh")))
  (def command-line-short (str loop-script-file " 10"))
  (def command-line-long (str loop-script-file " 1000"))

  (def p1 (sh-cmd command-line-short stdout-stream stderr-stream))
  (is-alive p1)
  (wait-for-process-exit p1)
  (wait-for-process-exit p1 2000)

  (def p2 (sh-cmd command-line-long stdout-stream stderr-stream))
  (is-alive p2)
  (destroy-process p2)
  (wait-for-process-exit p2)

  (def p-err (sh-cmd "/usr/bin/xyz999" stdout-stream stderr-stream))
  (is-alive p-err)
  (wait-for-process-exit p-err)

  (stop-stream-observer-thread stdout-observer)
  (stop-stream-observer-thread stderr-observer)

  (destroy-all-processes)

  )
