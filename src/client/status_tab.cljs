;; Utility functions for controlling and  monitoring long running background system processes
;;
;; The use and distribution terms for this software are covered by
;; the GNU General Public License
;;
;; (C) 2019, Otto Linnemann
;;
;; overview about all builds

(ns client.status-tab
  (:require
   [client.repl :as repl]
   [client.dispatch :as dispatch]
   [clojure.browser.dom :as dom]
   [clojure.string :as str]
   [goog.dom :as gdom]
   [goog.events :as events]
   [goog.ui.Menu :as menu]
   [goog.ui.Button :as Button]
   [goog.ui.MenuButton :as MenuButton]
   [goog.ui.ButtonRenderer :as ButtonRenderer]
   [goog.ui.FlatButtonRenderer :as FlatButtonRenderer]
   [goog.ui.Tab :as tab]
   [goog.ui.TabBar :as tabbar]
   [goog.ui.decorate :as decorate]
   [goog.style :as style]
   [goog.Timer :as timer]
   [client.json :as json])
  (:use
   [client.html-table :only [clear-table html-tab-get-row html-tab-insert-cells]]
   [client.ajax :only [send-request]]
   [client.utils :only [get-element get-date-str]]
   [client.logging :only [loginfo]]
   [client.build-console :only [open-build-console-dialog open-changelog-console-dialog]]
   [client.auth :only [logged-in? controll-access?]])
  (:use-macros [crossover.macros :only [hash-args]]))


(def ^{:private true
       :doc "html table which provides overview about all builds"}
  table (dom/get-element "list-of-all-build-processes"))


(defn- send-post-request
  [build-id action]
  (send-request (str "/" action "/" build-id)
                {}
                (fn [ajax-evt]
                  (let [resp (. (. ajax-evt -target) (getResponseText))]
                    (loginfo resp)))
                "POST"))


(def action-button-reactor
  (dispatch/react-to
   #{:build-table-action}
   (fn [evt {:keys [build-id action]}]
     (loginfo (str "react-to event: " action " with: " build-id))
     (dorun
      (case action
        :open-build-console (open-build-console-dialog build-id)
        :open-changelog-console (open-changelog-console-dialog build-id)
        :start-build (send-post-request build-id "start-build")
        :stop-build (send-post-request build-id "stop-build"))))))

(comment
  (dispatch/delete-reaction action-button-reactor)
  )


(defn- create-action-button
  "create the action button to open build console, start, stop builds, etc."
  [build-id enable-start-build enable-stop-build]
  (let [m1 (goog.ui.Menu.)
        b1 (goog.ui.MenuButton. "Show Details" m1)
        e (gdom/createElement build-id)
        items (into {}
                    (map
                     (fn [[id label]]
                       (let [item (goog.ui.MenuItem. (str label "..."))]
                         (. item (setId id))
                         (. item (setDispatchTransitionEvents goog.ui.Component.State.ALL true))
                         (. m1 (addItem item))
                         [id item]))
                     {:open-build-console "Console"
                      :open-changelog-console "Changelog"
                      :start-build "Start" :stop-build "Stop"}))]
    (. (:start-build items) (setEnabled enable-start-build))
    (. (:stop-build items) (setEnabled enable-stop-build))
    (. b1 (setDispatchTransitionEvents goog.ui.Component.State.ALL true))
    (. b1 (setId build-id))
    (. b1 (render e))
    (events/listen b1 goog.ui.Component.EventType.ACTION
                   (fn [e] (let [action (.getId (.-target e))]
                             (dispatch/fire :build-table-action (hash-args build-id action)))))
    e))


(defn- insert-row
  "insert new build status row on top of table 'list-of-all-build-processes

   row-cells: hash map with the following key values:
              'id': build ID which assigned to dom ID of the new row as well
              'sw-version': software version of the new row if given
               bg-color:  background color of the given row"
  [row-cells & [insert-into-row]]
  (let [table-body (gdom/getFirstElementChild table)
        prototype (html-tab-get-row "list-of-all-build-processes" "prototype-row")
        row (or insert-into-row (. prototype (cloneNode true)))
        build-id (row-cells "id")
        bg-color (:color row-cells)]
    (set! (. row -id) build-id)
    (set! (. row -bgColor) bg-color)
    (html-tab-insert-cells row row-cells)
    (when-not insert-into-row
      (gdom/insertChildAt table-body row 3))))


(defn- build-status-ajax-to-dom-row
  "transforms build-status as received from AJAX request to dom representation (table row)"
  [values]
  (let [id (values "task-uuid")
        sw-version (values "sw-version")
        error (values "error")
        running (values "running?")
        state (or (get-in values ["state" "id"] ) "none")
        enable-start-build (and (not= state "none") (not running) (logged-in?) (controll-access?))
        enable-stop-build (and running (logged-in?) (controll-access?))
        red "red"
        green "#3F3"
        yellow "yellow"
        color (if running
                yellow
                (if error red green))]
    {"id" id "version" sw-version "state" state "details" (create-action-button id enable-start-build enable-stop-build) :color color}))


(def ^{:private true
         :doc "Clojurescript atom for caching timestamp of last ajax update"}
  ts-last-status-tab-update (atom (get-date-str)))


(defn- update-build-status-request
  "request for new build status rows which have not been inserted yet"
  []
  (loginfo "send build status request")
  (send-request (str "/builds-since/" @ts-last-status-tab-update)
                {}
                (fn [ajax-evt]
                  (let [resp (. (. ajax-evt -target) (getResponseText))]
                    (when-let [resp (json/parse resp)]
                      (loginfo (str "got build status reply: " resp))
                      (let [[ts builds] (map resp ["ts" "builds"])]
                        (reset! ts-last-status-tab-update ts)
                        (when-not (empty? builds)
                          (def upd-builds builds)
                          (doseq [[id values] (into (sorted-map-by #(compare %1 %2)) builds)]
                            (let [row (html-tab-get-row "list-of-all-build-processes" id)]
                              (insert-row (build-status-ajax-to-dom-row values) row))))))))
                "GET"))


(def ^{:private true
       :doc "Ajax Polling Timer"}
  update-status-request-timer (goog.Timer. 500))

(events/listen update-status-request-timer goog.Timer/TICK update-build-status-request)


(def ^{:private true}
  spinner-pane (dom/get-element "request_build_processes"))


(defn- initial-build-status-request
  "initial request for all build status rows"
  []
  (clear-table "list-of-all-build-processes")
  (style/showElement spinner-pane true)
  (send-request "/all-builds"
                {}
                (fn [ajax-evt]
                  (let [resp (. (. ajax-evt -target) (getResponseText))]
                    (when-let [resp (json/parse resp)]
                      (let [[ts builds] (map resp ["ts" "builds"])]
                        (reset! ts-last-status-tab-update ts)
                        (doseq [row (into (sorted-map-by #(compare %1 %2)) builds)]
                          (insert-row (build-status-ajax-to-dom-row (val row))))
                        (style/showElement spinner-pane false)
                        (. update-status-request-timer (start))))))
                "GET"))


(comment
  (initial-build-status-request)
  (update-build-status-request)
  (all-build-tasks-running-request)
  (clear-table "list-of-all-build-processes")
  (. update-status-request-timer (start))
  (. update-status-request-timer (stop))
  )


(def tab-content (dom/get-element "nightly-build-tab-content"))
(def status-tab (get-element "tab-status" tab-content))


(defn- enable-status-pane
  "shows the status-pane"
  []
  (style/setOpacity status-tab 1) ;; important for first load only
  (style/showElement status-tab true)
  (initial-build-status-request)
  (loginfo "status pane enabled"))


(defn- disable-status-pane
  "hides the status-pane"
  []
  (style/showElement status-tab false)
  (. update-status-request-timer (stop))
  (loginfo "status pane disabled"))


(def pane-enabled-reactor (dispatch/react-to
                           #{:tab-selected}
                           (fn [evt data]
                             (loginfo (str "react-to event: " evt " data: " data))
                             (if (= data :tab-status-selected)
                               (enable-status-pane)
                               (disable-status-pane)))))


(defn- init
  "evaluated only once after page load"
  [e]
  nil)


(events/listen
 (js/eval "window")
 goog.events.EventType.LOAD
 init)
