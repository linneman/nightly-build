;; Utility functions for controlling and  monitoring long running background system processes
;;
;; The use and distribution terms for this software are covered by
;; the GNU General Public License
;;
;; (C) 2019, Otto Linnemann
;;
;; shell output of running or executed build

(ns client.build-console
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
   [goog.style :as style]
   [goog.Timer :as timer]
   [client.json :as json])
  (:use
   [client.utils :only [get-modal-dialog open-modal-dialog txt2html]]
   [client.ajax :only [send-request]]
   [client.logging :only [loginfo]]))



;; define build console and build console dialog
(let [[dialog ok-button cancel-button]
      (get-modal-dialog :panel-id "build-console-dialog"
                        :title-string "build console"
                        :keep-open false)]
  (def build-console-dialog dialog)
  (def build-console (gdom/getElement "build-console")))


(defn- init-build-console
  [txt]
  (set! (. build-console -innerHTML) (txt2html txt))
  (set! (. build-console -scrollTop) (. build-console -scrollHeight)))


(defn- update-build-console
  [upd]
  (let [txt (. build-console -innerHTML)]
    (set! (. build-console -innerHTML) (str txt (txt2html upd)))
    (set! (. build-console -scrollTop) (. build-console -scrollHeight))))


(defn- initial-build-console-request
  [build-id & args]
  (let [request (or (first args) "/build-log-all/")]
    (send-request (str request build-id)
                {}
                (fn [ajax-evt]
                  (let [resp (. (. ajax-evt -target) (getResponseText))]
                    (init-build-console resp)))
                "GET")))


(def ^{:private true
         :doc "Clojurescript atom for caching timestamp of last ajax update"}
  ts-last-console-update (atom 0))


(def ^{:private true
         :doc "Clojurescript atom for caching buid id for ajax request"}
  requested-build-id
  (atom ""))


(defn- update-build-console-request
  []
  (send-request (str "/build-log-upd/" @requested-build-id)
                {}
                (fn [ajax-evt]
                  (let [resp (. (. ajax-evt -target) (getResponseText))]
                    (when-let [resp (json/parse resp)]
                      (let [msg (resp "messages")
                            _ (def _msg msg)
                            ts (resp "ts")]
                        (loginfo (str  "timestamp build console upd: " ts))
                        (swap! ts-last-console-update
                               (fn [prev-ts]
                                 (if (not= ts prev-ts)
                                   (do
                                     (loginfo "*** DO UPDATE ***")
                                     (update-build-console msg)
                                     ts)
                                   prev-ts)))))))
                "GET"))

(def ^{:private true
       :doc "Ajax Polling Timer"}
  update-build-log-timer (goog.Timer. 500))


(events/listen update-build-log-timer goog.Timer/TICK update-build-console-request)


(comment
  (reset! requested-build-id "ltenad9607-bl2_2_0_2019-10-24-15h04")
  (. update-build-log-timer (start))
  (. update-build-log-timer (stop))
  )


(defn open-build-console-dialog
  "Open build console modal dialog and start polling"
  [build-id]
  (let [title (-> "build-console-title" (dom/get-element) (goog.dom/getTextContent) (str " " build-id))]
    (. build-console-dialog (setTitle title))
    (reset! requested-build-id build-id)
    (initial-build-console-request build-id)
    (open-modal-dialog build-console-dialog)))


(def ^{:private true
         :doc "event handler for start/stop of build message polling"}
    build-console-dialog-reactor
    (dispatch/react-to
     #{:dialog-closed :dialog-opened}
     (fn [evt data]
       (when (= data "build-console-dialog")
         (case evt
           :dialog-opened (. update-build-log-timer (start))
           :dialog-closed (. update-build-log-timer (stop)))))))


(defn open-changelog-console-dialog
  "Open build console modal dialog and start polling"
  [build-id]
  (let [title (-> "changelog-console-title" (dom/get-element) (goog.dom/getTextContent) (str " " build-id))]
    (. build-console-dialog (setTitle title))
    (reset! requested-build-id build-id)
    (initial-build-console-request build-id "/get-changelog/")
    (open-modal-dialog build-console-dialog :evt :changelog-dialog-opened)))


(defn- init
  "evaluated only once after page load"
  [e])


(comment

  (open-build-console-dialog "ltenad9607-bl2_2_0_2019-10-24-15h04")
  (open-changelog-console-dialog "ltenad9607-bl2_2_0_2019-11-18-16h57s18")

  (set! (. build-console -innerHTML) (txt2html "abc"))

  (initial-build-console-request "ltenad9607-bl2_2_0_2019-10-24-15h04")
  (update-build-console-request "ltenad9607-bl2_2_0_2019-10-24-15h04")
  (println  (apply str (take 300 (_resp "messages")))  )

  (update-build-console-requester)

  )


(events/listen
 (js/eval "window")
 goog.events.EventType.LOAD
 init)
