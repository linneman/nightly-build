;; Utility functions for controlling and  monitoring long running background system processes
;;
;; The use and distribution terms for this software are covered by
;; the GNU General Public License
;;
;; (C) 2019, Otto Linnemann
;;
;; main menu as tabs on the left

(ns client.tabbar
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
   [client.auth :only [logged-in? get-role]]
   [client.ajax :only [send-request]]
   [client.utils :only [get-element]]
   [client.logging :only [loginfo]]))


(def tabbar (goog.ui.TabBar.))
(. tabbar (decorate (gdom/getElement "nightly-build-tab-menu")))
(def admin-tab (. tabbar (getChildAt 4)))


;; apply fixed position to header element (no scrolling)
;; refer to: https://www.w3schools.com/howto/tryit.asp?filename=tryhow_js_sticky_header
(def header (gdom/getElement "header-container"))
(def sticky (. header -offsetTop))

(defn adapt-scrollers
  []
  (let [header-classlist (. header -classList)]
    (. header-classlist (add "sticky"))))

;; (set! (. js/window -onscroll) adapt-scrollers)


(defn- update-tabbar-access-credentials
  "enables/disables user administration tab"
  []
  (let [admin-access (and (logged-in?) (= (get-role) :admin))]
    (. admin-tab (setEnabled admin-access))))


(def ^{:private true
       :doc "to avoid event triggering twice we unfortunately need this"}
  tabbar-events-blocked (atom false))

;; react on tab bar events
(events/listen tabbar goog.ui.Component.EventType.SELECT
               (fn [event]
                 (let [target (.-target event)
                       id (case (.getId target)
                            "build-status"   :tab-status-selected
                            "cron-jobs"      :tab-cron-selected
                            "configuration"  :tab-build-config-selected
                            "advanced"       :tab-advanced-selected
                            "administration" :tab-administration-selected
                            "about"          :tab-about-selected)]
                   (when-not @tabbar-events-blocked
                     (loginfo (str "selected tab id: " id))
                     (dispatch/fire :tab-selected id)))))


;; react on application events to update tab bar as well
(def tab-select-reactor
  (dispatch/react-to
   #{:tab-selected}
   (fn [evt data]
     (let [evt-tab-hash (zipmap
                         [:tab-status-selected :tab-cron-selected
                          :tab-build-config-selected :tab-advanced-selected
                          :tab-administration-selected :tab-about-selected]
                         (map
                          #(. tabbar (getChildAt %))
                          (range (. tabbar (getChildCount)))))
           tab-to-activate (evt-tab-hash data)]
       (loginfo (str "react-to event: " evt " data: " data))
       (update-tabbar-access-credentials)
       (when (not= (. tabbar (getSelectedTab)) tab-to-activate)
         (reset! tabbar-events-blocked true)
         (. tabbar (setSelectedTab tab-to-activate))
         (reset! tabbar-events-blocked false))))))


(defn- init
  "evaluated only once after page load"
  [e]
  (adapt-scrollers)
  (dispatch/fire :tab-selected :tab-status-selected))


(events/listen
 (js/eval "window")
 goog.events.EventType.LOAD
 init)
