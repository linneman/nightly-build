;; Utility functions for controlling and  monitoring long running background system processes
;;
;; The use and distribution terms for this software are covered by
;; the GNU General Public License
;;
;; (C) 2019, Otto Linnemann

(ns server.local-settings
  (:use [server.utils]
        [crossover.macros])
  (:import [java.net InetAddress]))


;;; --- REPL Configuration ---

(def #^{:doc "hostname for the repl (locathost or 0.0.0.0)"
        :dynamic true}
  *repl-host* "localhost")


(def #^{:doc "tcp port number where the repl listens to"
        :dynamic true}
  *repl-port* 7888)


;;; --- Port and domain setup ---
;;;
;;; Replace "localhost" with (. (InetAddress/getLocalHost) (getHostName))
;;; Since the machines hostname is not always resolved, we use "localhost"
;;; for initial setup.
;;;
(def #^{:doc "http host server which accessible at least from the LAN"
        :dynamic true}
  *http-host* "localhost")

(def use-https false)
(def http-port 3000)
(def https-port 3443)
(def host-url (str (if use-https "https" "http") "://" *http-host*
                   (if use-https
                     (when-not (= https-port 443) (str ":" https-port))
                     (when-not (= http-port 80) (str ":" http-port))) "/"))

(def home-dir (System/getProperty "user.home"))

(def jetty-setup
  {:port http-port
   :join? false
   :ssl? true
   :ssl-port https-port
   :keystore (first-file-that-exists [(str home-dir "/.nightly-build/key_crt.jks")
                                      "resources/keys/key_crt.jks"])
   :key-password "password"})

(def cookie-max-age (* 30 24 3600))
(def email-confirmation-required true)

(defn create-confirmation-mail
  "email text for confirmation"
  [name key]
  (let [subject "Confirm registration to nightly build system"
        url (format (str host-url "confirm?name=%s&key=%s") name (url-encode key))
        content
        (format
         (lstr
          "Dear %s\n\n"
          "You have been registered to the nightly build system.\n\n"
          "Please click on this URL to confirm the registration: %s")
         name url)]
    (hash-args subject content url)))


(def ^{:doc "when true display maintenance page"} maintencance-mode false)


;;; --- build configuration ---

(def ^{:doc "default build configuration"
       :dynamic true}
  *default-build-configuration* "sample_config.clj")


(def ^{:doc "maximum number of allowed parallel build processes"
       :dynamic true}
 *max-parallel-builds* 20)


;;; --- cache files ---

(def file-sep (java.io.File/separator) )


(def ^{:doc "directory where nightly build puts its cached data"
       :dynamic true}
  *cache-directory*  (str home-dir file-sep ".nightly-build"))


(def ^{:doc "file for backing up the log store"
       :dynamic true}
  *log-atom-store-filename* (str *cache-directory* file-sep "log-atom-store.clj"))


(def ^{:doc "directory where nightly build puts its task cache"
       :dynamic true}
  *task-store-dir* (str *cache-directory* file-sep "tasks"))


(def ^{:doc "user data basebase"
       :dynamic true}
  *sesion-store-filename* (str *cache-directory* file-sep "session-store.clj"))


(def ^{:doc "user data basebase"
       :dynamic true}
  *user-store-filename* (str *cache-directory* file-sep "user-store.clj"))
