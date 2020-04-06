;; Caching of Logmessages for transmission via REST services
;;
;; The use and distribution terms for this software are covered by
;; the GNU General Public License
;;
;; (C) 2019, Otto Linnemann

(ns server.build-log-handler
  (:use [clojure.string :only [trim]]
        [server.utils]
        [server.shell-utils]
        [server.local-settings])
  (:import [java.io ByteArrayOutputStream]
           [java.time LocalDateTime]))


(defn- serialize-store-items
  "helper function for removing var entries from hash table

   Atoms, vars and agents shall and cannot be serialized. So this
   function removes the keys ':update', ':processed-entries' and
   ':last-update' from a log item hash map."
  [items]
  (let [items-without-refs
        (->>
         (map (fn [[k v]]
                (let [v-without-refs (-> v (dissoc :update)
                                         (dissoc :processed-entries)
                                         (dissoc :last-updated))]
                  [k v-without-refs]))
              items)
         (into {}))]
    (pr-str items-without-refs)))


(defn- deserialize-store-items
  "helper function for add empty var entries to hash table

   Atoms, vars and agents shall and cannot be serialized. So this
   function adds the keys ':update', ':processed-entries' and
   ':last-update' to a log item hash map which is fetched from
   a string."
  [s]
  (let [items (-> s read-string eval)
        items-with-refs
        (->>
         (map (fn [[k v]]
                (let [v-with-refs (-> v (assoc :update (ref nil))
                                         (assoc :processed-entries (ref nil))
                                         (assoc :last-updated (ref nil)))]
                  [k v-with-refs]))
              items)
         (into {}))]
    items-with-refs))


(defn- save-log-store
  "serializes and saves the given logstore hash table to disk"
  [filename store]
  (let [s (serialize-store-items (merge (:cached store) (:uncached store)))]
    (try
      (do
        (mkdirs *cache-directory*)
        (spit filename s))
      (catch Exception e (println-err "caught exception: " (.getMessage e))))))


(defn- load-log-store
  "deserializes and saves the given logstore hash table from disk"
  [filename]
  {:uncached
   (-> filename slurp deserialize-store-items)
   :cached {}})


(defn- init-log-store
  "tries to read the log store from disk. If this is not possible
   return an emptry and freshly initialized store."
  []
  (try
    (load-log-store *log-atom-store-filename*)
    (catch Exception e
      (do
        (println-err "caught exception: " (.getMessage e))
        {:uncached {} :cached {}}))))


(defn- release-processed-log-entries
  "interates over log entry map and removes all
   var references to large processed entries"
  [entries]
  (reduce
   (fn [res [k v]]
     (assoc res k
            (update-in v [:processed-entries]
                       #(dosync (ref-set % nil)))))
   {}
   entries))


(defonce ^{:dynamic true} log-atom-store (atom (init-log-store)))

(defn- get-log-atom
  "retrieves logging cache references for given build-id and file name

   When not found a new cache value is created."
  [& {:keys [build-id logfilename]}]
  (let [max-elements-in-cache *max-parallel-builds*
        store (swap! log-atom-store
                     (fn [{:keys [cached uncached] :as store} build-id filename]
                       (if (cached build-id)
                         store ;; we have the key in cached store, do nothing
                         (let [upd-cached
                               (-> cached
                                   (assoc build-id {:build-id build-id
                                                    :created (str (java.time.LocalDateTime/now))
                                                    :update (ref nil)
                                                    :last-updated (ref (.getTime (java.util.Date.)))
                                                    :processed-entries (ref nil)
                                                    :logfilename logfilename}))
                               entries-by-date (->> upd-cached vals (group-by :created) sort reverse)
                               kept (take max-elements-in-cache entries-by-date)
                               dropped (drop max-elements-in-cache entries-by-date)
                               to-id-hash (fn [m] (reduce (fn [res [k [e]]]
                                                            (assoc res (:build-id e) e)) {} m))]
                           {:cached (to-id-hash kept)
                            :uncached (merge
                                       (-> dropped to-id-hash release-processed-log-entries)
                                       (dissoc uncached build-id))})))
                     build-id logfilename)]
    (save-log-store *log-atom-store-filename* store)
    ((store :cached) build-id)))


(defn build-log-handler
  "creates  a more  sophisticated  log  handler which  writes  out  log data  to
  standard out  and to  the specified file.  Futhermore the data  is kept  to an
  updated cache for the specified numer of milliseconds where one seconds is the
  default. The  cache is transfered  to the  processed entries var  whenever the
  invocation period between to logger functions is bigger than that."
  [build-id filename & {:keys [cache-time-ms] :or {cache-time-ms 1500}}]
  (let [{:keys [build-id]} (get-log-atom :build-id build-id :logfilename filename)]
    (fn [logstr]
      (when (and logstr (> (count logstr) 0))
        (print logstr)
        (flush)
        (when-let [{:keys [build-id logfilename update last-updated processed-entries]}
                   (get-in @log-atom-store [:cached build-id])]
          (dosync
           (let [now (.getTime (java.util.Date.))
                 period (- now @last-updated)]
             (if (> period cache-time-ms)
               (do (alter processed-entries str @update)
                   (ref-set update logstr)
                   (ref-set last-updated now))
               (alter update str logstr))))
          (when logfilename
            (spit logfilename logstr :append true)))))))


(defn get-processed-log
  "get the processed log messages which are older than 10 seconds

   Retreive all processed log messages from cache when present.
   Otherwise read these messages from the associated log file."
  [build-id]
  (when log-atom-store
    (if-let [proc (get-in @log-atom-store [:cached build-id :processed-entries])]
      @proc
      (when-let [logfilename (get-in @log-atom-store [:uncached build-id :logfilename])]
        (try
          (slurp logfilename)
          (catch Exception e (.getMessage e)))))))


(defn get-last-log
  "get hashmap of the last log messages of the past 10 seconds
   and the associated timestamp when the message has been generated"
  [build-id]
  (when log-atom-store
    (when-let [logh (get-in @log-atom-store [:cached build-id])]
      (let [last-updated (-> logh :last-updated)
            update (-> logh :update)]
        {:messages @update :ts @last-updated}))))


(comment

  (defn print-store
    [l]
    (println "----")
    (dorun
     (map
      #(println %)
      l)))

  (print-store (:cached @log-atom-store))
  (print-store (:uncached @log-atom-store))


  (keys (:cached @log-atom-store))
  (keys (:uncached @log-atom-store))

  (reset! log-atom-store {:cached {} :uncached {}})

  ;(ns-unmap *ns* 'log-atom-store)

  (get-log-atom :build-id 2 :logfilename "file2.txt")
  (get-log-atom :build-id 3 :logfilename "file3.txt")
  (get-log-atom :build-id 10 :logfilename "file10.txt")
  (get-log-atom :build-id 9 :logfilename "file9.txt")
  (get-log-atom :build-id 4 :logfilename "file4.txt")
  (get-log-atom :build-id 5 :logfilename "file5.txt")
  (get-log-atom :build-id 6 :logfilename "file6.txt")
  (get-log-atom :build-id 7 :logfilename "file7.txt")

  (print-str @log-atom-store)


  (def logger (build-log-handler 4 "file4.txt"))

  (logger "Hello\n")
  (logger "World\n")
  (logger "my\n")
  (logger "god\n")
  (logger "end\n")

  @((get-log-atom :build-id 4 :logfilename "file4.txt") :update)
  @((get-log-atom :build-id 4 :logfilename "file4.txt") :processed-entries)


  (get-in @log-atom-store [:uncached 4 :logfilename])

  (get-last-log 4)
  (get-last-log 5)
  (get-processed-log 4)
  (get-processed-log 5)

  (get-last-log "ltenad9607-bl2_2_0_2019-10-24-15h04")
  (get-processed-log "ltenad9607-bl2_2_0_2019-10-24-12h35")

  )
