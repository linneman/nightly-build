;; Utility functions for controlling and  monitoring long running background system processes
;;
;; The use and distribution terms for this software are covered by
;; the GNU General Public License
;;
;; (C) 2019, Otto Linnemann

;; Server configuration is overloaded at runtime


(ns server.config
  (:use [server.utils]
        [crossover.macros])
  (:import [java.time LocalDateTime]))


(def #^{:doc "environemnt variable definition invoked before any other build command"
        :dynamic true}
  *build-env-decl*
  (lstr
    "export LANG=en_US.UTF-8 &&"
    "export GIT_SSL_NO_VERIFY=1"))


;; --- email setup ---
;;
;; Now configured to localhost in order to be used with ssh forwarding to a jump
;; server with unrestricted access to a given mail server
;;
;; On the jump server  with acces to in example gmail  the most straight forward
;; approach is much  probably the emailrelay (http://emailrelay.sourceforge.net)
;; utility which is invoked in the following way:
;;
;;    emailrelay --as-server --port 10025 --spool-dir /tmp --as-proxy=smtp.gmail.com:587 \
;;               --client-tls --client-auth=/home/ol/etc/emailrelay.auth
;;
;; On the build server within the  intranet and all ports but http/https blocked
;; use autossh to repeatedly  create a tunnel to the jump  server which needs to
;; be configured within your ssh config files e.g. using ssh proxy and corkscrew:
;;
;;    autossh -M 0 -o "ServerAliveInterval 30" -o "ServerAliveCountMax 3" \
;;            -L 10025:localhost:10025 <jumpserver>

(def ^:dynamic *email-host-name* "localhost")
(def ^:dynamic *email-ssl-smtp-port* "465")
(def ^:dynamic *email-smtp-port* 10025)
(def ^:dynamic *email-set-ssl* false)
(def ^:dynamic *email-from-name* "User Name")
(def ^:dynamic *email-from-email* "user.name@gmail.com")
(def ^:dynamic *email-auth-name* "user.name@gmail.com")
(def ^:dynamic *email-auth-password* "user-password")


(def #^{:doc "recipients list of email addresses where build status information is sent to"
        :dynamic true}
  *email-build-status-recipients*
  ["user.name@gmail.com"])


;; --- miscellaneous ---

(def #^{:doc "sleep time in ms to ensure that last messages/error messages can be fetched"
        :dynamic true}
  *remote-termination-follow-up-time* 3000)


;; --- build configurations ---


;; we cannot include sendmail due to cyclic dependencies. Instead
;; we refer to sendmail at runtime
;; https://stackoverflow.com/questions/3084205/resolving-clojure-circular-dependencies
(defn- sendmail [& args]
  (apply (resolve 'server.email/sendmail) args))


;; --- crontab ---

(def cron-build-descriptions
  [{:m 0 :h 00 :dom false :mon false :dow false :desc nil}])
