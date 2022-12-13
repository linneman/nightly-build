;;;
;;; Clojure based web application
;;; https://github.com/clojure/clojurescript for further information.
;;;
;;; The use and distribution terms for this software are covered by
;;; the GNU General Public License
;;;
;;; 2011-11-23, Otto Linnemann

(ns client.logging
  (:require [goog.debug.Console :as Console]
            [goog.log :as Log]
            [goog.log.Level :as LogLevel]))


(def debugConsole (goog.debug.Console. "core"))
(. debugConsole (setCapturing true))

(def logger (Log/getLogger "client.core" LogLevel/ALL))


(defn loginfo
  "creates and info message of given string"
  [msg]
  ;(. logger (log LogLevel/INFO msg))
  (Log/log logger LogLevel/INFO msg nil)
  )

(defn logerror
  "creates and error message of given string"
  [msg]
  ;(. logger (log LogLevel/SEVERE msg))
  (Log/log logger LogLevel/SEVERE msg nil)
  )
