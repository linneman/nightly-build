;; Utility functions for controlling and  monitoring long running background system processes
;;
;; The use and distribution terms for this software are covered by
;; the GNU General Public License
;;
;; (C) 2019, Otto Linnemann
;;
;; setup of cron jobs

(ns client.cron-jobs-tab
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
   [client.html-table :only [clear-table copy-table-row delete-table-row html-tab-get-row
                             html-tab-insert-cells htmlcoll2array]]
   [client.ajax :only [send-request]]
   [client.utils :only [get-element render-css3-button]]
   [client.logging :only [loginfo]]
   [client.auth :only [controll-access? logged-in?]])
  (:use-macros [crossover.macros :only [hash-args]]))


(def ^{:private true
       :doc "html table which provides overview about all builds"}
  table (dom/get-element "list-of-all-cron-jobs"))

(defn- send-post-request
  [build-description action]
  (send-request (str "/" action "/" build-description)
                {}
                (fn [ajax-evt]
                  (let [resp (. (. ajax-evt -target) (getResponseText))]
                    (loginfo resp)))
                "POST"))


(declare redraw-cron-table)

(def action-button-reactor
  (dispatch/react-to
   #{:cron-table-action}
   (fn [evt {:keys [build-description idx action]}]
     (loginfo (str "react-to event: " action " with index: " idx))
     (dorun
      (case action
        :new-cron-entry (do (copy-table-row "list-of-all-cron-jobs" idx) (redraw-cron-table))
        :delete-cron-entry (do (delete-table-row "list-of-all-cron-jobs" idx) (redraw-cron-table))
        :start-build (do (send-post-request build-description "start-new-build-from-desc")
                         (dispatch/fire :tab-selected :tab-status-selected)))))))

(comment
  (dispatch/delete-reaction action-button-reactor)
  )

(defn- create-action-button
  "create the action button to open build console, start, stop builds, etc."
  [build-description idx enable-delete-entry enable-start-build]
  (let [m1 (goog.ui.Menu.)
        b1 (goog.ui.MenuButton. "Action" m1)
        e (gdom/createElement build-description)
        items (into {}
                    (map
                     (fn [[id label]]
                       (let [item (goog.ui.MenuItem. (str label "..."))]
                         (. item (setId id))
                         (. item (setDispatchTransitionEvents goog.ui.Component.State.ALL true))
                         (. m1 (addItem item))
                         [id item]))
                     {:new-cron-entry "New Entry"
                      :delete-cron-entry "Delete Entry"
                      :start-build "Start"}))]
    (. (:delete-cron-entry items) (setEnabled enable-delete-entry))
    (. (:start-build items) (setEnabled enable-start-build))
    (. b1 (setDispatchTransitionEvents goog.ui.Component.State.ALL true))
    (. b1 (setId idx))
    (. b1 (render e))
    (events/listen b1 goog.ui.Component.EventType.ACTION
                   (fn [e] (let [action (.getId (.-target e))]
                             (dispatch/fire :cron-table-action
                                            (hash-args build-description idx action)))))
    e))

(comment
  (def x (create-action-button "test" 0 true true))
  )


(defn- form-input-html
  "creates a form input html entry with typ and val"
  [typ val & [attribute-str]]
  (str "<form><input type=\"" typ "\" " (if (boolean? val) "" (str "value=\"" val "\" "))
       (if (and (= typ "checkbox") val) "checked=\"checked\"" "")
       (if attribute-str (str " " attribute-str ""))
       "</form>"))

(defn- hash-values-to-form-input
  "transforms data elements to html form entries"
  [row-cells]
  (into (hash-map)
        (map
         (fn [[k v]]
           [k (if (string? k)
                (case k
                  "desc" (form-input-html "text" v "readonly size=\"60\"")
                  "enabled" (form-input-html "checkbox" v)
                  "m" (form-input-html "number" v "min=\"0\" max=\"59\"")
                  "h" (form-input-html "number" v "min=\"0\" max=\"23\"")
                  "dom" (form-input-html "number" v "min=\"1\" max=\"31\"")
                  "dow" (form-input-html "number" v "min=\"1\" max=\"7\"")
                  "mon" (form-input-html "number" v "min=\"1\" max=\"12\"")
                  "action" v)
                v)])
         row-cells)))

(defn- insert-row
  "insert new build cronttab row on top of table 'list-of-all-cron-jobs'
   row-cells: hash map with the following key values:
              'm': start at given minute
              'h': start at given hour
              'dom': start at given day of month
              'mon': start at given month
              'dow': start at given day of week
              'build-description': build description for the build to start
              'action': action button
              :color: background color of the given row"
  [row-cells & [insert-into-row]]
  (let [table-body (gdom/getFirstElementChild table)
        prototype (html-tab-get-row "list-of-all-cron-jobs" "prototype-row")
        row (or insert-into-row (. prototype (cloneNode true)))
        bg-color (or (:color row-cells) "white")
        row-cells (hash-values-to-form-input row-cells)]
    (set! (. row -id) "data-row")
    (set! (. row -bgColor) bg-color)
    (html-tab-insert-cells row row-cells)
    (when-not insert-into-row
      (gdom/insertChildAt table-body row 3))))


(comment
  (insert-row {"enabled" true "m" "1" "h" "2" "dom" "3" "mon" "4" "dow" "5" "desc" "test" "action"
               (create-action-button "test" 0 true true) :color "#EEE"})
  )


(defn- parse-table-in-dom
  "reads back table elements"
  []
  (let [table-body (gdom/getFirstElementChild table)
        rows (htmlcoll2array (gdom/getChildren table-body))]
    (remove nil?
            (map
             (fn [row]
               (when (= (. row -id) "data-row")
                 (let [cells (htmlcoll2array (gdom/getChildren row))]
                   (into {}
                         (map
                          (fn [cell]
                            (when-let [cell-childs (-> cell (gdom/getChildren)
                                                       (htmlcoll2array) (first))]
                              (let [id (. cell -id)
                                    inp (first (htmlcoll2array (. cell-childs -childNodes)))
                                    parse-int (fn [s] (if (empty? s) false (js/parseInt s)))
                                    value (case (. inp -type)
                                            "checkbox" (. inp -checked)
                                            "number" (parse-int (. inp -value))
                                            (. inp -value))]
                                [id value])))
                          cells)))))
             rows))))


(defn- render-cron-table
  "renders the cron-table with given json configuration file

   + disables 'delete' button when amount of assoc. build descriptions is less than 2
   + inserts line by line from top the bottom, crontable needs to be reversed becasuse of that
   + associate to each line a different background color"
  [cron-table-json]
  (clear-table "list-of-all-cron-jobs")
  (let [color-idx-offset (if (even? (count cron-table-json)) 0 1)
        desc-freq-hash (reduce (fn [h e] (update h (e "desc") inc)) {} cron-table-json)]
      (doseq [[idx row] (map vector (iterate inc 0) (reverse cron-table-json))]
        (insert-row (assoc row :color (if (even? (+ idx color-idx-offset)) "#DDD" "#EEE")
                           "action" (create-action-button
                                     (row "desc") (- (count cron-table-json) idx 1)
                                     (> (desc-freq-hash (row "desc")) 1)
                                     (and (logged-in?) (controll-access?))))))))

(defn- redraw-cron-table
  "re-rendering of the cron table which is necessary when table lines are copied"
  []
  (render-cron-table (parse-table-in-dom)))


(defn- post-crontab
  []
  (let [crontab (->> (parse-table-in-dom) (map #(dissoc % "action")))
        crontab (into #{} crontab)
        json-content (json/generate {:crontab crontab})]
    (def _json-content json-content)
    (send-request (str "/set-cron-build-descriptions")
                  json-content
                  (fn [ajax-evt]
                    (let [resp (. (. ajax-evt -target) (getResponseText))]
                      (loginfo resp)
                      (if (= resp "OK")
                        (js/alert "SUCCESS!")
                        (js/alert (str "Error: " resp)))))
                  "POST")))


(defn- initial-cron-jobs-request
  "initial request for all build status rows"
  []
  (send-request "/get-cron-build-descriptions"
                {}
                (fn [ajax-evt]
                  (let [resp (. (. ajax-evt -target) (getResponseText))]
                    (when-let [resp (json/parse resp)]
                      (def _resp resp)
                      (render-cron-table resp))))
                "GET"))


(render-css3-button "publish-crontab-button" :publish-crontab)
(render-css3-button "reset-crontab-button" :reset-crontab)

(comment
  (dispatch/delete-reaction button-reactor)
  (post-crontab)
  )

(def button-reactor (dispatch/react-to
             #{:publish-crontab :reset-crontab}
             (fn [evt data]
               (loginfo (str "react-to event: " evt))
               (case evt
                 :reset-crontab (initial-cron-jobs-request)
                 :publish-crontab (if (and (logged-in?) (controll-access?))
                                    (post-crontab)
                                    (js/alert "You need control or admin permissions to upload the crontab!"))))))


(def tab-content (dom/get-element "nightly-build-tab-content"))
(def cron-tab (get-element "tab-cron" tab-content))


(defn- enable-cron-pane
  "shows the tab-pane"
  []
  (style/setOpacity cron-tab 1)
  (style/showElement cron-tab true)
  (initial-cron-jobs-request)
  (loginfo "crone pane enabled"))


(defn- disable-cron-pane
  "hides the crone-pane"
  []
  (style/showElement cron-tab false)
  (loginfo "cron pane disabled"))


(def pane-enabled-reactor (dispatch/react-to
                           #{:tab-selected}
                           (fn [evt data]
                             (loginfo (str "react-to event: " evt " data: " data))
                             (if (= data :tab-cron-selected)
                               (enable-cron-pane)
                               (disable-cron-pane)))))


(defn- init
  "evaluated only once after page load"
  [e])


(events/listen
 (js/eval "window")
 goog.events.EventType.LOAD
 init)
