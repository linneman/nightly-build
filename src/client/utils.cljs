;;;
;;; Clojure based web application
;;; https://github.com/clojure/clojurescript for further information.
;;;
;;; The use and distribution terms for this software are covered by
;;; the GNU General Public License
;;;
;;; ====== utility functions ======
;;;
;;; 2011-11-23, Otto Linnemann


(ns client.utils
  (:require [client.json :as json]
            [client.dispatch :as dispatch]
            [clojure.browser.event :as event]
            [clojure.browser.dom   :as dom]
            [clojure.string :as str]
            [goog.dom :as gdom]
            [goog.ui.Dialog :as Dialog]
            [goog.ui.Button :as Button]
            [goog.ui.FlatButtonRenderer :as FlatButtonRenderer]
            [goog.ui.Css3ButtonRenderer :as gCss3ButRenderer]
            [goog.ui.CustomButton :as gCustomButton]
            [goog.ui.ToggleButton :as gToggleButton]
            [goog.ui.decorate :as gDecorate]
            [goog.net.XhrIo :as ajax]
            [goog.events :as events]
            [goog.events.EventType :as event-type]
            [goog.style :as style]
            [goog.string :as gstring]
            [goog.string.format :as gformat]
            [goog.html.SafeHtml :as safehtml]
            [goog.html.uncheckedconversions :as unchecked]
            (goog.string.Const :as strconst))
  (:use [client.logging :only [loginfo]]))


(defn txt2html
  "transform ASCII text to primitive html"
  [txt]
  (-> txt
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\n" "<br />")))


(defn make-safe-html
  "function which transforms string to self html

   from https://stackoverflow.com/questions/12318667/closure-library-dom-node-from-html-text"
  [html]
  (unchecked/safeHtmlFromStringKnownToSatisfyTypeContract
   (strconst/from "Output of HTML sanitizer") html))


(defn get-element
  "similar to dom/get-element but the search can be
   restricted to a given node (2nd argument) If no
   node is specified the document object is searched."
  ([element] (get-element element (gdom/getDocument)))
  ([element node]
      (gdom/findNode node
                     (fn [e] (= (. e -id) element)))))


(defn- setup-modal-dialog-panel
  "retrieves an invisible dom element for dialog pane
   for given id string, moves the element to a
   foo.ui.Dialog element which is returned."
  [dom-id-str]
  (if-let [pane-element (dom/get-element dom-id-str)]
    (let [dialog (goog.ui.Dialog.)]
      (. dialog (setSafeHtmlContent (make-safe-html (goog.dom.getOuterHtml pane-element))))
      (goog.dom.removeNode pane-element)
      (. dialog (render))
      (style/setOpacity (dom/get-element dom-id-str) 1)
      dialog)))



(defn get-modal-dialog
  "constructs and setup a modal dialog panel from
   the specified dom element identifiers (given
   as strings) and returns vector with corresponding
   document objects for pane, ok-button and cancel
   button if given. Fires the event :dialog-closed
   when cancel or the close box is clicked."
  [& {:keys [panel-id
             title-string
             ok-button-id
             cancel-button-id
             dispatched-event
             dispatched-data
             keep-open]}]
  (let [dialog (setup-modal-dialog-panel panel-id)]
    (when title-string
      (. dialog (setTitle title-string)))
    (. dialog (setButtonSet nil))
    (set! (. dialog -panel-id) panel-id)
    (events/listen dialog "afterhide"
                   #(dispatch/fire :dialog-closed panel-id))
    (let [cancel-button
          (when cancel-button-id
            (let [button (goog.ui.decorate (dom/get-element cancel-button-id))]
              (events/listen button "action" #(. dialog (setVisible false)))
              (. button (setEnabled true))
              button))
          ok-button
          (when ok-button-id
            (let [button (goog.ui.decorate (dom/get-element ok-button-id))]
              (events/listen button "action"
                             #(do
                                (when-not keep-open (. dialog (setVisible false)))
                                (dispatch/fire dispatched-event dispatched-data)))
              (. button (setEnabled true))
              button))]
      [dialog ok-button cancel-button])))


(defn open-modal-dialog
  "Opens the given dialog and fires the event :dialog-opened"
  [dialog & {:keys [evt] :or {evt :dialog-opened}}]
  (. dialog (setVisible true))
  (dispatch/fire evt (. dialog -panel-id)))


(defn render-css3-button
  "renders a css3 google button (class goog-css3-button) of given ID

   and fires the given action event via the dispatcher when button is clicked"
  [dom-id fired-action-event]
  (let [button (goog.ui.decorate (gdom/getElement dom-id))]
    (. button (setDispatchTransitionEvents goog.ui.Component.State.ALL true))
    (goog.events.listen button "action" (fn [e] (dispatch/fire fired-action-event)))
    button))


(defn get-date-str
  ([] (get-date-str (js/Date.)))
  ([local-date-time]
  (let [s (.getSeconds local-date-time)
        m (.getMinutes local-date-time)
        h (.getHours local-date-time)
        year (.getFullYear local-date-time)
        dom (.getDate local-date-time)
        mon (inc (.getMonth local-date-time))]
    (gstring/format "%4d-%02d-%02d-%02dh%02ds%02d" year mon dom h m s))))


(defn hash-str-keys-to-keywords
  "replaces all hash-table keys given as string with
   associated keywords"
  [h]
  (map (fn [entry]
         (into {}
               (map (fn [[k v]] {(keyword k) v}) entry)))
       h))


(defn reload-url
  "reloads the page (page is loaded from server)"
  [url]
  (js/eval (str "window.location.href='" url "';")))


(defn load-url-in-new-tab
  "loads the page in a new browser tab"
  [url]
  (when-not (js/window.open url)
    (js/alert "You need to disable popup blocking first!")))
