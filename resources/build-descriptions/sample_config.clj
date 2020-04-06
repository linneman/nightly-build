;; Utility functions for controlling and  monitoring long running background system processes
;;
;; The use and distribution terms for this software are covered by
;; the GNU General Public License
;;
;; (C) 2019, Otto Linnemann
;;
;; Example Configuration
;; This simply invokes a remotely executed shell loop command


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

(def ^:dynamic *email-host-name* "smtp.gmail.com")
(def ^:dynamic *email-ssl-smtp-port* "465")
(def ^:dynamic *email-smtp-port* 587)
(def ^:dynamic *email-set-ssl* true)
(def ^:dynamic *email-from-name* "<from-name>")
(def ^:dynamic *email-from-email* "<from-name@gmail.com>")
(def ^:dynamic *email-auth-name* "<from-name@gmail.com>")
(def ^:dynamic *email-auth-password* "<gmail-passwd>")


(comment
  ;; for test
  (sendmail ["fram-name@googlemail.com"] "test subject 3" "DEF")
  )


(def #^{:doc "recipients list of email addresses where build status information is sent to"
        :dynamic true}
  *email-build-status-recipients*
  ["recipient1@yoyodyne.org" "recipient2@yoyodyne.org"])


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


(defn create-build-steps-sample-1
  "build steps creation example"
  [{:keys [build-name build-uuid build-machine work-spaces build-dir
           current-task build-log-filename change-log-filename version-filename]}]
  (let [cleanup-script (-> "cleanup.py" get-resource quote-string-for-echoing)

        task-list
        (filter
         identity

         (list

          [:init
           (format
            "for i in {1..%d}; do echo \"processing \\$i\"; sleep .2; done"
            10) 3]
          [:anything-changed?
           (format
            (lstr "exit 0;")) 10]
          [:second
           (format
            "for i in {1..%d}; do echo \"processing \\$i\"; sleep .2; done"
            100) 22]
          [:third
           (format
            "for i in {1..%d}; do echo \"processing \\$i\"; sleep .2; done"
            100) 22]
          [:success
           "echo '+++ all build steps have been successfully executed! +++'" 3]
          ))

        terminate
        ;; copy the local build log file back to buid host
        ;; copy from the build host the changelog and version files
        [:cleanup
         "echo 'do the cleanup'" 20
         :term-cb ;; clojure handler for sending status email
         (fn [session error]
           (let [{:keys [build-uuid build-name]} session
                 current-task (-> session :current-task deref)
                 sw-version "v1.2.3"
                 anything-changed? (not= :anything-changed? (:id current-task))
                 changelog "changelog.txt"
                 [subject mail-txt]
                 (if anything-changed?
                   (if (empty? error)
                     [(format "nightly build %s, sw version %s successful!" build-uuid sw-version)
                      (format (lstr
                               "Dear Colleagues,\n\n"
                               "This message is to inform you that the nightly build %s, sw version %s\n"
                               "has been successfully compiled:\n\n"
                               "The following changes have been introduced:\n\n"
                               "%s")
                              build-uuid sw-version changelog)]
                     [(format "nightly build %s, sw version %s failed!" build-uuid sw-version)
                      (format (lstr
                               "Dear Colleagues,\n\n"
                               "This message is to inform you that the nightly build %s, sw version %s\n"
                               "failed within the build task %s with the following error:\n\n"
                               "Error: %s\n\n"
                               "The following changes have been introduced:\n\n"
                               "%s")
                              build-uuid sw-version (:id current-task) error changelog)])
                   [(format "no changes detected in nightly build %s" build-uuid)
                    (format (lstr
                             "Dear Colleagues,\n\n"
                             "There have been no new changes discovered within the nightly build %s.\n")
                            build-uuid)])]
             (println "___ send email: " mail-txt)
             [(if (= error "processing stopped!")
                0
                (sendmail *email-build-status-recipients* subject mail-txt))
              (hash-args sw-version changelog anything-changed?)]))]]
    (hash-args task-list terminate)))


(defn create-build-description-sample-1
  []
  (let [build-machine {:host "localhost" :port 22}
        work-spaces "/mnt/ssd1/ol/nightly-builds"
        build-name "build-example"
        current-task (atom nil)
        date-str (get-date-str)
        build-uuid (str build-name "_" date-str)
        build-dir (str work-spaces "/" build-name "/" date-str)
        log-dir (str (System/getProperty "user.home") "/nightly-build-logs")
        _ (mkdirs log-dir)
        build-log-filename (str log-dir "/nightly_" date-str ".log")
        change-log-filename (str log-dir "/changelog_" date-str ".log")
        version-filename (str log-dir "/version_" date-str ".log")

        env (hash-args build-name build-uuid build-machine work-spaces build-dir
                       current-task
                       build-log-filename
                       change-log-filename version-filename)]
    (merge env (create-build-steps-sample-1 env))))




(comment
  (println (:task-list (create-build-description-sample-1)))
  )


;; --- crontab ---

(def cron-build-descriptions
  [{:m 0 :h 00 :dom false :mon false :dow false
    :desc create-build-description-sample-1}])
