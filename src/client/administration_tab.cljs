;; Utility functions for controlling and  monitoring long running background system processes
;;
;; The use and distribution terms for this software are covered by
;; the GNU General Public License
;;
;; (C) 2019, Otto Linnemann
;;;
;;; advanced functions menu (repl, setup, etc.)
;;;

(ns client.administration-tab
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
   [goog.ui.Select :as select]
   [goog.style :as style]
   [goog.Timer :as timer]
   [client.json :as json])
  (:use
   [client.html-table :only [clear-table copy-table-row delete-table-row html-tab-get-row
                             html-tab-insert-cells htmlcoll2array]]
   [client.ajax :only [send-request]]
   [client.utils :only [get-element render-css3-button
                        hash-str-keys-to-keywords]]
   [client.logging :only [loginfo]]))


(def ^{:private true
       :doc "html table with user credentials list"}
  table (dom/get-element "list-of-all-users"))




(defn- create-select-button
  "creates role selection button"
  [id items selected-item]
  (let [item-idx-hash (into {} (map-indexed (fn [idx item]
                                              [(str/lower-case item) idx])
                                            items))
        e (gdom/createElement "label")
        select-role (goog.ui.Select.)]
    (def _items items)
    (def _selected-item selected-item)
    (def _item-idx-hash item-idx-hash)
    (doseq [item items]
      (. select-role (addItem (goog.ui.MenuItem. item))))
    (. select-role (setSelectedIndex (item-idx-hash selected-item)))
    (. select-role (render e))
    (set! (. e -id) id)
    e))


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
                  "select" (form-input-html "checkbox" v)
                  "name" (form-input-html "text" v
                                          (if (= (str/lower-case v) "admin")
                                            "readonly size=\"50\"" "size=\"50\""))
                  "email" (form-input-html "text" v "size=\"50\"")
                  "role" v)
                v)])
         row-cells)))


(defn- insert-row
  "insert new user row on top of table 'list-of-all-users'
   row-cells: hash map with the following key values:
              'select': select given user
              'name': login name
              'email': email
              'role': access credentials
              :color: background color of the given row"
  [row-cells & [insert-into-row]]
  (let [table-body (gdom/getFirstElementChild table)
        prototype (html-tab-get-row "list-of-all-users" "prototype-row")
        row (or insert-into-row (. prototype (cloneNode true)))
        bg-color (or (:color row-cells) "white")
        id (row-cells "name")
        row-cells (hash-values-to-form-input row-cells)]
    (set! (. row -id) id)
    (set! (. row -bgColor) bg-color)
    (html-tab-insert-cells row row-cells)
    (when-not insert-into-row
      (gdom/insertChildAt table-body row 3))))


(defn- append-row
  "appens new user row on top of table 'list-of-all-users'
   row-cells: hash map with the following key values:
              'select': select given user
              'name': login name
              'email': email
              'role': access credentials
              :color: background color of the given row"
  [row-cells]
  (let [table-body (gdom/getFirstElementChild table)
        prototype (html-tab-get-row "list-of-all-users" "prototype-row")
        row (. prototype (cloneNode true))
        bg-color (or (:color row-cells) "white")
        id (row-cells "name")
        row-cells (hash-values-to-form-input row-cells)]
    (set! (. row -id) id)
    (set! (. row -bgColor) bg-color)
    (html-tab-insert-cells row row-cells)
    (gdom/append table-body row)))


(comment
  (insert-row {"select" false "name" "Otto" "email" "linneman@gmx.de" "role" "admin" :color "#EEE"})
  (insert-row {"select" false "name" "Admin" "email" "linneman@gmx.de" "role" "admin" :color "#EEE"})
  (insert-row {"select" false "name" "Admin" "email" "linneman@gmx.de" "role" (create-select-button "Otto" ["View" "Control" "Admin"] "Admin") :color "#EEE"})
  )


(defn- parse-table-in-dom
  "reads back table elements"
  []
  (let [table-body (gdom/getFirstElementChild table)
        rows (htmlcoll2array (gdom/getChildren table-body))]
    (remove nil?
            (map
             (fn [row]
               (let [row-id (. row -id)]
                 (when (and (not= row-id "header-row")
                            (not= row-id "prototype-row"))
                   (let [cells (htmlcoll2array (gdom/getChildren row))]
                     (into {}
                           (map
                            (fn [cell]
                              (when-let [cell-childs (-> cell (gdom/getChildren) (htmlcoll2array) first)]
                                (let [id (. cell -id)
                                      inp (first (htmlcoll2array (. cell-childs -childNodes)))
                                      label2str (fn [l] (-> l str/trim str/lower-case))
                                      parse-int (fn [s] (if (empty? s) false (js/parseInt s)))
                                      value (if (= id "role")
                                              (label2str ( . cell-childs -innerText))
                                              (case (. inp -type)
                                                "checkbox" (. inp -checked)
                                                "number" (parse-int (. inp -value))
                                                (. inp -value)))]
                                  [(keyword id) value])))
                            cells))))))
             rows))))


(comment
  (parse-table-in-dom)
  )


(defn- get-user-list
  [cb]
  (send-request "/get-user-list"
                {}
                (fn [ajax-evt]
                  (let [resp (. (. ajax-evt -target) (getResponseText))
                        resp (json/parse resp)
                        user-list (hash-str-keys-to-keywords resp)]
                    (loginfo (str user-list))
                    (cb user-list)))
                "GET"))


(defn- send-request-to-update-all-users
  [cb]
  (send-request "/update-all-users"
                (json/generate
                 {:user-list
                  (map #(-> % (select-keys [:name :email :role]))
                       (parse-table-in-dom))})
                (fn [ajax-evt]
                  (let [resp (. (. ajax-evt -target) (getResponseText))
                        resp (json/parse resp)]
                    (if-let [error (resp "error")]
                      (js/alert (str "The following error occured: " error))
                      (let [user-list (hash-str-keys-to-keywords (resp "user-list"))]
                        (def _user-list user-list)
                        (cb user-list)
                        (js/alert "Success!")))))
                "POST"))


(comment
  (send-request-to-update-all-users render-user-table)
  )


(defn- render-user-table
  [user-table]
  (clear-table "list-of-all-users")
  (doseq [{:keys [name email role]}
          (->> user-table (sort-by :name) reverse)]
    (insert-row {"select" false
                 "name" name
                 "email" email
                 "role" (create-select-button
                         name ["View" "Control" "Admin"] role)})))

(comment
  (render-user-table
   [{:name "Admin" :email "linnemann.gmx.de" :role "admin"}
    {:name "Otto" :email "linnemann.gmx.de" :role "control"}
    {:name "Konrad" :email "konrad@gmail.com" :role "view"}])
  )


(defn- delete-selected-users
  "removes the selected users from DOM table"
  []
  (render-user-table
   (filter #(or (not (:select %))
                (= (str/lower-case (:name %)) "admin"))
           (parse-table-in-dom))))


(render-css3-button "publish-user-table-button" :publish-user-table)
(render-css3-button "reset-user-table-button" :reset-user-table)
(render-css3-button "add-user-button" :add-line-to-user-table)
(render-css3-button "delete-selected-users-button" :delete-selected-users)


(def add-user-reactor
  (dispatch/react-to
   #{:add-line-to-user-table}
   (fn [evt data]
     (append-row {"select" false "name" "" "email" ""
                  "role" (create-select-button
                          "" ["View" "Control" "Admin"] "view")}))))


(def delete-user-reactor
  (dispatch/react-to
   #{:delete-selected-users}
   (fn [evt data]
     (delete-selected-users))))


(def reset-user-reactor
  (dispatch/react-to
   #{:reset-user-table}
   (fn [evt data]
     (get-user-list render-user-table))))


(def publish-user-reactor
  (dispatch/react-to
   #{:publish-user-table}
   (fn [evt data]
     (send-request-to-update-all-users render-user-table))))


(def tab-content (dom/get-element "nightly-build-tab-content"))
(def administration-tab (get-element "tab-administration" tab-content))


(defn- enable-administration-pane
  "shows the tab-pane"
  []
  (style/setOpacity administration-tab 1)
  (style/showElement administration-tab true)
  (get-user-list render-user-table)
  (loginfo "administratione pane enabled"))


(defn- disable-administration-pane
  "hides the administratione-pane"
  []
  (style/showElement administration-tab false)
  (loginfo "administration pane disabled"))


(def pane-enabled-reactor (dispatch/react-to
                           #{:tab-selected}
                           (fn [evt data]
                             (loginfo (str "react-to event: " evt " data: " data))
                             (if (= data :tab-administration-selected)
                               (enable-administration-pane)
                               (disable-administration-pane)))))




(defn- init
  "evaluated only once after page load"
  [e])


(events/listen
 (js/eval "window")
 goog.events.EventType.LOAD
 init)
