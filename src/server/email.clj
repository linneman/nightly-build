;; Utility functions for controlling and  monitoring long running background system processes
;;
;; The use and distribution terms for this software are covered by
;; the GNU General Public License
;;
;; (C) 2019, Otto Linnemann

(ns server.email
  (:use [server.utils]
        [server.config])
  (:import [org.apache.commons.mail SimpleEmail]))


(defn sendmail
  "sends an  email to the  all addresses in the  specified array with  the setup
  data configured in config.clj."
  [to-addresses subject message]
  (let [mail (doto (SimpleEmail.)
               (.setHostName *email-host-name*)
               (.setSslSmtpPort *email-ssl-smtp-port*)
               (.setSmtpPort *email-smtp-port*)
               (.setSSL *email-set-ssl*)
               (.addTo (first to-addresses))
               (.setFrom *email-from-email* *email-from-name*)
               (.setSubject subject)
               (.setCharset "UTF8")
               (.setMsg message)
               (.setAuthentication *email-auth-name* *email-auth-password*))]
    (dorun (map #(.addTo mail %) (rest to-addresses)))
    (try
      (do (.send mail) 0)
      (catch org.apache.commons.mail.EmailException e
        (str (.getCause e))))))


(comment

  (sendmail *email-build-status-recipients* "Test 14" "Eine Nachricht mit Umlauten: ÄÖÜ äöü ß:")

  )
