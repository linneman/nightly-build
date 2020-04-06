;; Utility functions for controlling and  monitoring long running background system processes
;;
;; The use and distribution terms for this software are covered by
;; the GNU General Public License
;;
;; (C) 2019, Otto Linnemann
;;
;; main entry point

(ns client.core
  (:require
   [client.repl :as repl]
   [clojure.browser.dom :as dom]
   [clojure.string :as str]
   [goog.dom :as gdom]
   [goog.events :as events]
   [goog.ui.Button :as Button]
   [goog.ui.ButtonRenderer :as ButtonRenderer]
   [goog.ui.FlatButtonRenderer :as FlatButtonRenderer]
   [goog.style :as style]
   [goog.Timer :as timer]
   [client.json :as json])
  (:use
   [client.ajax :only [send-request]]
   [client.logging :only [loginfo]]))


(defn- init
  "evaluated only once after page load"
  [e])


(events/listen
 (js/eval "window")
 goog.events.EventType.LOAD
 init)
