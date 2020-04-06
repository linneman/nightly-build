;; Utility functions for controlling and  monitoring long running background system processes
;;
;; The use and distribution terms for this software are covered by
;; the GNU General Public License
;;
;; (C) 2019, Otto Linnemann
;;;
;;; advanced functions menu (repl, setup, etc.)
;;;

(ns client.advanced-tab
  (:require
   [client.repl :as repl]
   [client.dispatch :as dispatch]
   [clojure.browser.dom :as dom]
   [clojure.string :as str]
   [goog.dom :as gdom]
   [goog.events :as events]
   [goog.ui.Button :as Button]
   [goog.ui.ButtonRenderer :as ButtonRenderer]
   [goog.ui.FlatButtonRenderer :as FlatButtonRenderer]
   [goog.ui.Tab :as tab]
   [goog.ui.TabBar :as tabbar]
   [goog.style :as style]
   [goog.Timer :as timer]
   [client.json :as json])
  (:use
   [client.ajax :only [send-request]]
   [client.utils :only [get-element]]
   [client.logging :only [loginfo]]))


(def tab-content (dom/get-element "nightly-build-tab-content"))
(def advanced-tab (get-element "tab-advanced" tab-content))


(defn- enable-advanced-pane
  "shows the tab-pane"
  []
  (style/setOpacity advanced-tab 1)
  (style/showElement advanced-tab true)
  (loginfo "advancede pane enabled"))


(defn- disable-advanced-pane
  "hides the advancede-pane"
  []
  (style/showElement advanced-tab false)
  (loginfo "advanced pane disabled"))


(def pane-enabled-reactor (dispatch/react-to
                           #{:tab-selected}
                           (fn [evt data]
                             (loginfo (str "react-to event: " evt " data: " data))
                             (if (= data :tab-advanced-selected)
                               (enable-advanced-pane)
                               (disable-advanced-pane)))))


(defn- init
  "evaluated only once after page load"
  [e])


(events/listen
 (js/eval "window")
 goog.events.EventType.LOAD
 init)
