;; Utility functions for controlling and  monitoring long running background system processes
;;
;; The use and distribution terms for this software are covered by
;; the GNU General Public License
;;
;; (C) 2019, Otto Linnemann
;;;
;;; functions for triggering AJAX POST and GET
;;;

(ns client.ajax
  (:require [client.json :as json]
            [client.dispatch :as dispatch]
            [clojure.browser.event :as event]
            [clojure.browser.dom   :as dom]
            [goog.events :as events]
            [goog.events.EventType :as event-type]
            [goog.Timer :as timer]
            [goog.net.XhrIo :as xhrio])
  (:use [client.logging :only [loginfo]]))


(defn send-request
  "send XHTTP request as string"
  ([url str] (send-request url str (fn [e] nil) "GET"))
  ([url str function] (send-request url str function "GET"))
  ([url str function method]
     (goog.net.XhrIo.send
      url
      function
      method
      str
      (clj->js {"Content-Type" ["application/json"]}))))



(comment


  (goog.net.XhrIo/send
   "/build-log/my-build-id"
   (fn [ajax-evt] (let [resp (. (. ajax-evt -target) (getResponseText))]
                    (loginfo resp)
                    (def resp resp)))
   "GET"
   {:a "Otto"}
   (clj->js {"Content-Type" ["application/json"]}))



  (goog.net.XhrIo/send
   "/build-log"
   (fn [ajax-evt] (loginfo "hello"))
   "GET"
   {}
   (clj->js {:XXXXXXXXXXXXXXXXXX 42}))




  (loginfo "hello")
  (send-request "/build-log/my-build-id"
                {}
                (fn [ajax-evt]
                  (let [resp (. (. ajax-evt -target) (getResponseText))]
                    (loginfo resp)
                    (def resp resp)))
                "GET")

  (load-build-console)
  (map #(update-build-console) (range 10))

  (str/replace "Hello\nWorld" "\n" "<br />")
  (.stringify js/JSON (clj->js {:name "Otto Linnemann" :age 48})))
