;; Utility functions for controlling and  monitoring long running background system processes
;;
;; The use and distribution terms for this software are covered by
;; the GNU General Public License
;;
;; (C) 2019, Otto Linnemann
;;;
;;; advanced functions menu (repl, setup, etc.)
;;;

(ns client.about-tab
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
(def about-tab (get-element "tab-about" tab-content))


(defn- enable-about-pane
  "shows the tab-pane"
  []
  (style/setOpacity about-tab 1)
  (style/showElement about-tab true)
  (loginfo "aboute pane enabled"))


(defn- disable-about-pane
  "hides the aboute-pane"
  []
  (style/showElement about-tab false)
  (loginfo "about pane disabled"))


(def pane-enabled-reactor (dispatch/react-to
                           #{:tab-selected}
                           (fn [evt data]
                             (loginfo (str "react-to event: " evt " data: " data))
                             (if (= data :tab-about-selected)
                               (enable-about-pane)
                               (disable-about-pane)))))


(defn- init
  "evaluated only once after page load"
  [e])


(events/listen
 (js/eval "window")
 goog.events.EventType.LOAD
 init)
