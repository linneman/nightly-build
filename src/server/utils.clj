;; Utility functions for controlling and  monitoring long running background system processes
;;
;; The use and distribution terms for this software are covered by
;; the GNU General Public License
;;
;; (C) 2019, Otto Linnemann

(ns server.utils
  (:require [clojure.java.io :as io]
            [ring.util.codec :as codec])
  (:use [crossover.macros]
        [clojure.string :only [split split-lines]]
        [clojure.repl])
  (:import [java.time LocalDateTime]))


(defn get-date-str
  ([] (get-date-str (LocalDateTime/now)))
  ([local-date-time]
  (let [s (.getSecond local-date-time)
        m (.getMinute local-date-time)
        h (.getHour local-date-time)
        year (.getYear local-date-time)
        dom (.getDayOfMonth local-date-time)
        mon (.getMonthValue local-date-time)]
    (format "%4d-%02d-%02d-%02dh%02ds%02d" year mon dom h m s))))


(defn get-date-str-with-nano-secs
  []
  (let [now (LocalDateTime/now)
        date-str (get-date-str now)]
    (format "%sn%09d" date-str (.getNano now))))


(defn lstr
  "allows generation  of a long string  by just concattenating all  strings in a
  list separted by a blank character."
  [& strings]
  (clojure.string/join " " strings))


(defn quote-string-for-echoing
  "quotes a give string to allow processing by system command echo \"s\"

   replaces sinlge quotes by a sequence of  a quote followed by a 'quoted' quote
   and another quote replace double quotes by just quoted double quotes"
  [s]
  (-> s
      (clojure.string/replace #"[']" "'\\\\''")
      (clojure.string/replace #"[\"]" "\\\\\"")))


(defn file-exists?
  "check whether file with given name exists"
  [filename]
  (.exists (clojure.java.io/file filename)))


(defn mkdirs
  "create a potentially nested directory if it does not exist"
  [dirname]
  (.mkdirs (java.io.File. dirname)))


(defn get-resource
  "copy specified file from resources to tmpfile and returns its name"
  [resource-filename]
  (-> resource-filename io/resource io/input-stream slurp))


(defn get-resource-manifest
  "reads specified subdirectory from resource and returns elements as list"
  [manifest-filename]
  (let [manifest (get-resource manifest-filename)
        manifest (->> manifest
                      split-lines
                      (filter not-empty)
                      (filter #(not (re-matches #"^[\space]?;.*" %))))
        manifest (map #(split % #" ") manifest)]
    manifest))


(defn copy-resource-manifest
  "copies all files in manifest directory to specified dest path (usually under home)

   If the file already exists, do not overwrite it."
  [dest-path]
  (doseq [[source dest-dir] (get-resource-manifest "manifest")]
    (let [dest-dir (str dest-path "/" dest-dir)
          filename (->> (split source #"[/]") last)
          dest (str dest-dir "/" filename)]
      (mkdirs dest-dir)
      (if (file-exists? dest)
        (println (format "File %s exists already, do not overwrite!" dest))
        (do
          (println (format "Copy %s to %s ..." source dest))
          (with-open [in (io/input-stream (io/resource source))]
            (io/copy in (io/file dest))))))))


(defn first-file-that-exists
  "returns from a given vector of file names the first one that exists"
  [file-vec]
  (some #(when (file-exists? %) %) file-vec))


(defn fn-obj-to-str
  "transforms a function object to the (local) function name

   Be aware about  Clojure's mapping of hyphens to underscores  when compiling a
   clojure symbol  to a Java object.  Since Clojure symbol name  may include the
   underscope character as  well, the mapping from Clojure names  to JVM objects
   is not  reversible. We  circumvent this  problem by  using the  repl function
   aprospos to match  all function names with either hyphens  or underscores and
   decide for the shortest match."
  [f]
  (let [[_ ns fn-str] (->> f type str (re-matches #"(?:.*)(?:[ ])(.*)(?:[$])(.*)"))
        pattern (re-pattern (clojure.string/replace fn-str #"[-_]" "[-_]"))
        matches (apropos pattern)]
    (when-let [matches (apropos pattern)]
      (let [matches-ascending-sorted-by-len (sort (map #(vector (-> % (str) (count)) %) matches))
            [match-len shortest-match] (first matches-ascending-sorted-by-len)
            [all ns local-func] (re-matches #"(.*)(?:[/])(.*)" (str shortest-match))]
        local-func))))


(defn forward-url
  "utility function for forwarding to given url."
  [url]
  (format "<html><head><meta  http-equiv=\"refresh\" content=\"0; URL=%s\"></head><body>forwarding ...</body></html>" url))


(defn- double-escape [^String x]
  (.replace x "\\" "\\\\"))


(defn url-encode
  "same as codec/url-encode but plus sign is encoded, too!"
  [unencoded & [encoding]]
  (let [encoding (or encoding "UTF-8")]
    (clojure.string/replace
     unencoded
     #"[^A-Za-z0-9_~.-]+"
     #(double-escape (codec/percent-encode % encoding)))))


(defn hash-str-keys-to-keywords
  "replaces all hash-table keys given as string with
   associated keywords"
  [h]
  (map (fn [entry]
         (into {}
               (map (fn [[k v]] {(keyword k) v}) entry)))
       h))
