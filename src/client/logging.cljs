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
            [goog.debug.Logger :as Logger]
            [goog.debug.Logger.Level :as LogLevel]))


(def debugConsole (goog.debug.Console. "core"))
(. debugConsole (setCapturing true))

(def logger (Logger/getLogger "project-alpha.core"))
(. logger (setLevel LogLevel/ALL))



(defn loginfo
  "creates and info message of given string"
  [msg]
  (. logger (log LogLevel/INFO msg)))

(defn logerror
  "creates and error message of given string"
  [msg]
  (. logger (log LogLevel/ERROR msg)))
