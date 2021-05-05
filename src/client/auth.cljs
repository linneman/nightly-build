;; Utility functions for controlling and  monitoring long running background system processes
;;
;; The use and distribution terms for this software are covered by
;; the GNU General Public License
;;
;; (C) 2019, Otto Linnemann
;;
;; authentication

(ns client.auth
  (:require
   [client.repl :as repl]
   [client.dispatch :as dispatch]
   [client.json :as json]
   [clojure.browser.dom :as dom]
   [goog.dom :as gdom]
   [goog.events :as events]
   [goog.events.EventType :as event-type]
   [clojure.string :as str]
   [goog.Timer :as timer]
   [goog.net.cookies :as cookies]
   [goog.positioning.ClientPosition :as clientpos]
   [goog.positioning.Corner :as corner]
   [goog.positioning.AnchoredViewportPosition :as viewpos]
   [goog.ui.Popup])
  (:use
   [client.ajax :only [send-request]]
   [client.logging :only [loginfo]]
   [client.html-table :only [htmlcoll2array]]
   [client.utils :only [get-element render-css3-button
                        get-modal-dialog open-modal-dialog reload-url]])
  (:use-macros [crossover.macros :only [hash-args]]))


(defn login
  [name password & [cb]]
  (send-request "/login"
                (json/generate (hash-args name password))
                (fn [ajax-evt]
                  (let [resp (. (. ajax-evt -target) (getResponseText))]
                    (loginfo resp)
                    (when cb (cb resp))))
                "POST"))

(defn logout
  []
  (send-request "/logout"
                {}
                (fn [ajax-evt]
                  (let [resp (. (. ajax-evt -target) (getResponseText))]
                    (loginfo resp)
                    (. goog.net.cookies (clear))
                    (reload-url "/")))
                "POST"))

(defn set-password
  [password & [cb]]
  (send-request "/set-password"
                (json/generate (hash-args name password))
                (fn [ajax-evt]
                  (let [resp (. (. ajax-evt -target) (getResponseText))]
                    (loginfo resp)
                    (when cb (cb resp))))
                "POST"))

(defn forgot-password
  [name & [cb]]
  (send-request "/forgot-password"
                (json/generate (hash-args name))
                (fn [ajax-evt]
                  (let [resp (. (. ajax-evt -target) (getResponseText))]
                    (loginfo resp)
                    (when cb (cb resp))))
                "POST"))


(defn add-user
  [name email role]
  (send-request "/add-user"
                (json/generate (hash-args name email role))
                (fn [ajax-evt]
                  (let [resp (. (. ajax-evt -target) (getResponseText))]
                    (loginfo resp)))
                "POST"))

(defn update-user
  [name & {:keys [email role]}]
  (send-request "/update-user"
                (json/generate (hash-args name email role))
                (fn [ajax-evt]
                  (let [resp (. (. ajax-evt -target) (getResponseText))]
                    (loginfo resp)))
                "POST"))


(defn logged-in?
  "returns true when user has been logged-in"
  []
  (let [logged-in-cookie (. goog.net.cookies (get "logged-in"))]
    (= logged-in-cookie "true")))


(defn confirmed?
  "returns true when user has clicked the confirmation link"
  []
  (let [confirmed (. goog.net.cookies (get "confirmed"))]
    (= confirmed "true")))


(defn get-role
  "returns the user access credentials"
  []
  (let [role (. goog.net.cookies (get "role"))]
    (or (keyword role) :none)))


(defn controll-access?
  "return "
  []
  (let [role (get-role)]
    (contains? #{:admin :control} role)))


(defn admin-access?
  "return "
  []
  (let [role (get-role)]
    (contains? #{:admin} role)))


(render-css3-button "popup-login-logout" :login-logout-pressed)
(render-css3-button "popup-reset-password" :reset-password)
(render-css3-button "popup-forgot-password" :forgot-password)


(defn- create-popup
  "makes popup with given id visible at anchored element
   with given id."
  [popup-id anchor-id]
  (let [anchor-element (dom/get-element anchor-id)
        popup-element (dom/get-element popup-id)
        popup (goog.ui.Popup. popup-element)
        popup-margin (goog.math.Box. 0 0 0 -100)
        menu-corner goog.positioning.Corner/TOP_LEFT
        button-corner goog.positioning.Corner/BOTTOM_LEFT]
    (. popup (setHideOnEscape true))
    (. popup (setAutoHide true))
    (. popup (setVisible false))
    (. popup (setPinnedCorner menu-corner))
    (. popup (setMargin popup-margin))
    (. popup
       (setPosition (goog.positioning.AnchoredViewportPosition.
                     anchor-element button-corner)))
   popup))

(def user-credentials-popup (create-popup "setup-popup" "login-logo"))


(defn- show-popup
  "makes popup object visible"
  [popup]
  (set! (. (get-element "popup-login-logout") -innerText)
        (if (logged-in?) "Logout" "Login"))
  (. popup (setVisible true)))


(defn- hide-popup
  "makes popup object invisible"
  [popup]
  (. popup (setVisible false)))


(defn- render-login-popup-dialog
  [user-info]
  (let [name (dom/get-element "popup-name")
        email (dom/get-element "popup-email")
        role (dom/get-element "popup-role")]
    (set! (. name -innerText) (user-info "name"))
    (set! (. email -innerText) (user-info "email"))
    (set! (. role -innerText) (str "Credentials: " (user-info "role")))
    (show-popup user-credentials-popup)))


(defn- update-and-show-logged-in-user-info
  []
  (if (logged-in?)
    (send-request "/get-user-info"
                  {}
                  (fn [ajax-evt]
                    (let [resp (. (. ajax-evt -target) (getResponseText))]
                      (let [resp (json/parse resp)]
                        (if (and (logged-in?) (-> "name" resp empty?))
                          (do (js/alert "Cookies were removed, relogin required!")
                              (logout))
                          (render-login-popup-dialog resp)))))
                  "GET")
    (render-login-popup-dialog
     {"name" "Anonym" "email" "" "role" "none"})))


(def show-login-popup-button-reactor
  (dispatch/react-to
   #{:show-login-popup}
   (fn [evt data]
     (loginfo (str "react-to event: " evt))
     (update-and-show-logged-in-user-info))))


(events/listen (dom/get-element "login-logo")
               event-type/MOUSEDOWN #(dispatch/fire
                                      :show-login-popup nil))

;; define enter password dialog
(let [[dialog ok-button cancel-button]
      (get-modal-dialog :panel-id "enter-user-passwd-dialog"
                        :title-string "Login"
                        :ok-button-id "confirm-login"
                        :dispatched-event :entered-login-name-pw
                        false)]
  (def login-dialog dialog))


;; define reset password dialog
(let [[dialog ok-button cancel-button]
      (get-modal-dialog :panel-id "reset-passwd-dialog"
                        :title-string "Reset Password"
                        :ok-button-id "confirm-reset-passwd"
                        :cancel-button-id "cancel-reset-passwd"
                        :dispatched-event :entered-new-pw
                        false)]
  (def reset-passwd-dialog dialog))


;; define forgot password dialog
(let [[dialog ok-button cancel-button]
      (get-modal-dialog :panel-id "forgot-passwd-dialog"
                        :title-string "Forgot Password"
                        :ok-button-id "confirm-forgot-passwd"
                        :cancel-button-id "cancel-forgot-passwd"
                        :dispatched-event :entered-username-for-act-link
                        false)]
  (def forgot-password-dialog dialog))


(def entered-login-name-pw-reactor
  (dispatch/react-to
   #{:entered-login-name-pw}
   (fn [evt data]
     (let [name (. (get-element "login-name") -value)
           passwd (. (get-element "login-password") -value)]
       (if (> (count passwd) 4)
         (login name passwd
                (fn [resp]
                  (if (= resp "OK")
                    (do (js/alert "Success!") (reload-url "/"))
                    (js/alert "Wrong Password!"))))
         (js/alert "Wrong Password!"))))))


; (dispatch/delete-reaction entered-new-pw-reactor)

(def entered-new-pw-reactor
  (dispatch/react-to
   #{:entered-new-pw}
   (fn [evt data]
     (let [new-passwd-1 (. (get-element "new-passwd-1") -value)
           new-passwd-2 (. (get-element "new-passwd-2") -value)]
       (if (logged-in?)
         (if (= new-passwd-1 new-passwd-2)
           (if (> (count new-passwd-1) 4)
             (set-password
              new-passwd-1
              (fn [resp]
                (if (= resp "OK")
                  (js/alert "Success!")
                  (js/alert "Setup of new password failed!"))))
             (js/alert "The password must be longer than 4 chars!"))
           (js/alert "1st and 2nd try do not match error!"))
         (js/alert "You must log in first!"))))))


(comment
  (open-modal-dialog login-dialog)
  (. (get-element "login-name") -value)
  (. (get-element "login-password") -value)
  )


(def entered-user-for-new-act-link-reactor
  (dispatch/react-to
   #{:entered-username-for-act-link}
   (fn [evt data]
     (loginfo (str "react-to event: " evt))
     (let [name (. (get-element "forgot-pw-name-or-email") -value)]
       (forgot-password name
                        (fn [resp]
                          (if (= resp "OK")
                            (js/alert "You will receive another activation link.")
                            (js/alert "User not found!"))))))))


; (dispatch/delete-reaction login-logout-popup-button-reactor)

(def login-logout-popup-button-reactor
  (dispatch/react-to
   #{:login-logout-pressed}
   (fn [evt data]
     (loginfo (str "react-to event: " evt))
     (if (logged-in?)
       (logout)
       (open-modal-dialog login-dialog))
     (hide-popup user-credentials-popup))))


;(dispatch/delete-reaction reset-password-button-reactor)

(def reset-password-button-reactor
  (dispatch/react-to
   #{:reset-password}
   (fn [evt data]
     (loginfo (str "react-to event: " evt))
     (if (logged-in?)
       (open-modal-dialog reset-passwd-dialog)
       (js/alert "You must log in first!")))))


; (dispatch/delete-reaction forgot-password-button-reactor)

(def forgot-password-button-reactor
  (dispatch/react-to
   #{:forgot-password}
   (fn [evt data]
     (loginfo (str "react-to event: " evt))
     (if (logged-in?)
       (js/alert "You are already logged in. Click 'Reset Password' to setup a new password.")
       (open-modal-dialog forgot-password-dialog)))))


(comment
  (update-and-show-logged-in-user-info)
  (login "Otto" "hoppla")
  (login "otto" "hoppla")
  (login "Otto" "wrong")
  (login "Otto" "oioioi")
  (logout)
  (set-password "oioioi")
  (set-password "hoppla")
  (set-password "no")

  (add-user "Paul" "Otto.Linnemann@googlemail.com" "control")
  (update-user "Paul" :email "paulchen@panther.com" :role "control")
  (update-user "Otto" :role "admin")

  (logged-in?)
  (confirmed?)
  (get-role)
  (controll-access?)
  )
