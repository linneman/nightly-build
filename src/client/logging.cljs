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

(def logger (Logger/getLogger "client.core"))
(. logger (setLevel goog.debug.Logger.Level.ALL))



(defn loginfo
  "creates and info message of given string"
  [msg]
  (. logger (log goog.debug.Logger.Level.INFO msg)))

(defn logerror
  "creates and error message of given string"
  [msg]
  (. logger (log goog.debug.Logger.Level.ERROR msg)))
