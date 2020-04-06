;; Utility functions for controlling and  monitoring long running background system processes
;;
;; The use and distribution terms for this software are covered by
;; the GNU General Public License
;;
;; (C) 2019, Otto Linnemann

(ns server.auth
  (:require [server.local-settings :as setup]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :as stest]
            [ring.util.codec :as codec]
            [clojure.string :as str])
  (:use [server.utils]
        [server.shell-utils]
        [server.crypto :only [get-secret-key get-encrypt-pass-and-salt hash-password]]
        [server.email]
        [ring.util.response :only [response status set-cookie content-type charset]]
        [crossover.macros]))


(defn- save-user-store
  "serializes and saves the given userstore hash table to disk"
  [filename content]
  (try
    (do
      (mkdirs setup/*cache-directory*)
      (spit filename (pr-str content)))
    (catch Exception e (println-err "caught exception: " (.getMessage e)))))


(defn- load-user-store
  "loads and deserializes the given user store hash table from disk"
  [filename]
  (try
    (-> filename slurp read-string eval)
    (catch java.io.FileNotFoundException e
      (do (println-err "caught exception: " (.getMessage e)) {}))))


(defn- init-user-store
  "read the user log store from disk. If this is not possible
   return an emptry and freshly initialized store."
  []
  (load-user-store setup/*user-store-filename*))


(defonce ^{:private true :dynamic true
           :doc "session store which is backed up to the filesystem"}
  user-store (agent (init-user-store)))



(def email-regex #"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,63}$")

(def all-roles #{:admin :control :view :none})

(s/def ::email-type (s/and string? #(re-matches email-regex %)))
(s/def ::email ::email-type)
(s/def ::name string?)
(s/def ::role all-roles)
(s/def ::confirmed boolean?)
(s/def ::password (s/and string? #(> (count %) 4)))
(s/def ::salt string?)
(s/def ::confirmation-key string?)
(s/def ::error string?)
(s/def ::user (s/keys :req-un [::name ::email ::role ::confirmed
                               ::password ::salt ::confirmation-key]))


(comment
  (ns-unmap *ns* 'user-store)
  (s/explain ::user (-> @user-store first val))
  )


(defn- trim-lower-case
  "trims the string and transform all characters to lower case"
  [s]
  (when s
    (-> s str/lower-case str/trim)))


(defn- find-user-by-email
  "retrieves a user by given email address"
  [email]
  (let [email (trim-lower-case email)
        email-user-hash (reduce (fn [res [k v]] (assoc res (:email v) v)) {} @user-store)]
    (email-user-hash email)))


(defn- find-user-by-name
  "retrieves a user by name"
  [name]
  (@user-store (trim-lower-case name)))


(defn send-confirmation-email
  [user]
  (println "send confirmation email to " (:email user))
  (let [{:keys [subject content]}
        (setup/create-confirmation-mail (:name user)
                                        (:confirmation-key user))]
    (sendmail [(:email user)] subject content)))


(defn add-user-to-db
  "add a new user with keyword args :name, :email, :password and :role

   :role must be :view, :control or :admin"
  [& {:keys [name email password role send-confirm-email] :or {send-confirm-email true}}]
  (let [name (trim-lower-case name)
        email (trim-lower-case email)]
    (if (or (find-user-by-name name) (find-user-by-email email))
      {:error "user already exists!"}
      (let [{:keys [password salt]} (get-encrypt-pass-and-salt password)
            confirmed false
            confirmation-key (codec/base64-encode (get-secret-key {}))]
        (send-off user-store
                  (fn [content]
                    (let [user (hash-args name email role password salt
                                          confirmed confirmation-key)
                          upd (assoc content name user)]
                      (save-user-store setup/*user-store-filename* upd)
                      (when send-confirm-email (send-confirmation-email user))
                      upd)))))))


(s/fdef add-user-to-db
        :args (s/keys* :req-un [::name ::email ::role ::password])
        :ret (s/or :agent #(instance? clojure.lang.Agent %)
                   :error (s/keys :req-un [::error])))

(stest/instrument `add-user-to-db)


(defn update-user-in-db
  "update user db with given keys"
  [name & {:keys [email password role confirmed]}]
  (if-let [user (find-user-by-name name)]
    (let [{:keys [password salt]} (if password (get-encrypt-pass-and-salt password) user)
          name (trim-lower-case name)
          email (trim-lower-case (or email (:email user)))
          role (or role (:role user))
          confirmed (or confirmed (:confirmed user))]
      (send-off user-store
                (fn [content]
                  (let [upd (update-in content [name]
                                       #(merge % (hash-args name email role
                                                            password salt confirmed)))]
                    (save-user-store setup/*user-store-filename* upd)
                    upd))))
    {:error "user does not exist!"}))

(s/fdef update-user-in-db
        :args (s/cat :name ::name
                     :update (s/keys* :opt-un
                                      [::name ::email ::role ::confirmed ::password]))
        :ret (s/or :agent #(instance? clojure.lang.Agent %)
                   :error (s/keys :req-un [::error])))

(stest/instrument `update-user-in-db)


(defn delete-user-in-db
  "remove user with given name from user db"
  [name]
  (if-let [user (find-user-by-name name)]
    (send-off user-store
              (fn [content]
                (let [name (trim-lower-case name)
                      upd (dissoc content name)]
                  (save-user-store setup/*user-store-filename* upd)
                  upd)))
    {:error "user does not exist!"}))


(defn keep-users-in-db
  "keeps only users with given names in user db"
  [names]
  (send-off user-store
              (fn [content]
                (let [upd (select-keys content names)]
                  (save-user-store setup/*user-store-filename* upd)
                  upd))))


(defn check-user-password
  "check the user password against the login and
   return the user data map when existing."
  [login-id pass-entered]
  (let [user (or (find-user-by-name login-id) (find-user-by-email login-id))]
    (if (empty? user)
      false
      (if (= 0 (compare (:password user) (hash-password pass-entered (:salt user))))
        user
        nil))))


(defn update-confirmation-link-for-user
  "renews confirmation key in database for given user"
  [name]
  (if-let [user (find-user-by-name name)]
    (let [confirmation-key (codec/base64-encode (get-secret-key {}))]
      (send-off user-store
                (fn [content]
                  (let [upd (assoc-in content [name :confirmation-key]
                                      confirmation-key)]
                    (save-user-store setup/*user-store-filename* upd)
                    upd))))
    {:error "user does not exist!"}))


(defn create-admin-user-if-not-existing
  []
  (when-not (find-user-by-name "admin")
    (add-user-to-db
     :name "admin"
     :email "admin@nightly-build.org"
     :password (codec/base64-encode (get-secret-key {}))
     :role :admin
     :send-confirm-email false)
    (await user-store))
  (let [admin (find-user-by-name "admin")
        {:keys [url]} (setup/create-confirmation-mail
                       (:name admin)
                       (:confirmation-key admin))]
    (println
     (format
      (lstr
       "\n"
       "______________ Administration Account ______________\n"
       "Copy and paste the following URL into your brower to\n"
       "active the administration account:\n\n"
       "%s\n\n"
       "____________________________________________________\n")
      url))))


(comment "spec tests"
  (create-admin-user-if-not-existing)
  (add-user-to-db :name "Konrad" :email "otto.linnemann@gmx.de" :password "hoppla" :role :admin)
  (add-user-to-db :name "Otto" :email "linneman@gmx.de" :password "hoppla" :role :admin)
  (check-user-password "Otto" "hoppla")
  (add-user-to-db :name "Otto" :email "linneman@gmx.de" :password "tst" :role :admin)
  (update-user-in-db "Otto" :email "otto.linneman@gmx.de" :role :view)
  (update-user-in-db "Otto" :email "otto.linneman@gmx.de" :role :admin :confirmed false)
  (update-user-in-db "Otto" :confirmed true)
  (update-user-in-db "Otto" :role :admin)
  (update-user-in-db "Otto" :password "newer")
  (update-user-in-db "Otto" :password "new")
  (delete-user-in-db "Otto")
  (update-user-in-db "Konrad" :email "konrad.linnemann@gmail.com" :password "hoppla" :role :admin)
  (add-user-to-db :name "Konrad" :email "konrad.linnemann@gmail.com" :password "hoppla" :role :admin)
  (add-user-to-db :name "Otto" :email "linneman@gmx.de" :password "hop" :role :admin)
  (add-user-to-db :name 21 :email "linneman@gmx.de" :role :admin :password "hoppla")
  (add-user-to-db :email "linneman@gmx.de" :role :admin)
  (add-user-to-db :name "Otto" :email "linneman" :role :admin)
  (update-confirmation-link-for-user "otto")
  )



(defn login
  "utility function for processing POST requests comming
   from login forms currently based on user name and password."
  [{:keys [params session cookies] :as ring-args}]
  (let [name (trim-lower-case (params "name"))
        password (params "password")
        user (check-user-password name password)
        id name
        session-id (session :id)]
    (if (and user (or (:confirmed user)
                      (not setup/email-confirmation-required)))
      (let [{:keys [confirmed role]} user
            id (:name user)
            role (:role user)
            logged-in true
            session (merge session (hash-args id logged-in confirmed role))
            cookies (assoc cookies
                      "logged-in" {:value "true" :max-age setup/cookie-max-age :path "/"}
                      "confirmed" {:value "true" :max-age setup/cookie-max-age :path "/"}
                      "role" {:value (clojure.core/name role)
                              :max-age setup/cookie-max-age :path "/"})]
        (if (or true (not session-id) (= session-id id))
          (-> (response "OK")
              (assoc :session session)
              (assoc :cookies cookies))
          (status (response "WRONG ACCOUNT") 403)))
      (if user
        (status (response "NOT CONFIRMED") 403)
        (status (response "WRONG PASSWORD") 403)))))


(defn confirm
  "invoked handler when user clicked email confirmation link"
  [ring-args]
  (def _ring-args ring-args)
  (let [params (:params ring-args)
        {:keys [name key]} params
        user (find-user-by-name name)
        session (:session ring-args)
        cookies (:cookies ring-args)]
    (if (= key (:confirmation-key user))
      (let [logged-in true
            confirmed true
            role (:role user)
            id name
            session (merge session (hash-args id logged-in confirmed role))
            cookies (assoc cookies
                      "logged-in" {:value "true" :max-age setup/cookie-max-age :path "/"}
                      "confirmed" {:value "true" :max-age setup/cookie-max-age :path "/"}
                      "role" {:value (clojure.core/name role)
                              :max-age setup/cookie-max-age :path "/"})]
        (update-user-in-db name :confirmed true)
        (update-confirmation-link-for-user name)
        (-> (response (forward-url setup/host-url))
            (assoc :session session)
            (assoc :cookies cookies)
            (content-type "text/html")
            (charset "utf-8")))
      (status (response "WRONG KEY") 403))))


(defn logout
  "middleware for logout out

   This will removes the session cookie"
  [ring-args]
  (def _ring-args ring-args)
  (let [{:keys [session cookies]} ring-args
        session (assoc session :logged-in false :role :none)
        cookies (assoc cookies "logged-in" {:value "false" :path "/"} "role" {:value "none" :path "/"})]
    (-> (response "OK")
        (assoc :session session)
        (assoc :cookies cookies))))


(defn set-password
  "set new password"
  [ring-args]
  (let [params (:params ring-args)
        session (:session ring-args)
        user-name (:id session)
        password (params "password")]
    (let [error (if (> (count password) 4) nil "PASSWORD TOO SHORT")
          error (or error (-> (update-user-in-db user-name :password password) :error))]
      (response (or error "OK")))))


(defn forgot-password
  "utility function for requesting a new activation link"
  [{:keys [params session cookies] :as ring-args}]
  (let [login-name (trim-lower-case (params "name"))]
    (if-let [user (or (find-user-by-name login-name) (find-user-by-email login-name))]
      (do (send-confirmation-email user)
          (response "OK"))
      (status (response "USER NOT FOUND") 403))))


(defn add-user
  "add new user"
  [ring-args]
  (let [params (:params ring-args)
        name (params "name")
        role (keyword (params "role"))
        email (params "email")
        password (codec/base64-encode (get-secret-key {}))]
    (let [{:keys [error]} (add-user-to-db :name name :email email :role role
                                          :password password)]
      (response (or error "OK")))))


(defn update-user
  "update role and/or email of existing user"
  [ring-args]
  (def _ring-args ring-args)
  (let [params (:params ring-args)
        session (:session ring-args)
        login-name (:id session)
        login-user (find-user-by-name login-name)
        name (params "name")
        role (keyword (params "role"))]
    (if-let [user (find-user-by-name name)]
      (if (or (and role (not= (:role login-user) :admin))
              (and (not= name login-name) (not= (:role login-user) :admin)))
        (status (response "NOT ALLOWED TO CHANGE ROLE OR OTHER USER") 403)
        (let [role (or role (:role user))
              email (or (params "email") (:email user))]
          (let [{:keys [error]} (update-user-in-db name :email email :role role)]
            (response (or error "OK")))))
      (status (response "USER NOT FOUND") 404))))


(defn get-user-info
  "rerieves login name, email address and access role for logged in user"
  [ring-args]
  (def _ring-args ring-args)
  (let [params (:params ring-args)
        session (:session ring-args)
        login-name (:id session)]
    (if-let [user (find-user-by-name login-name)]
      (select-keys user [:name :email :role])
      (status (response "USER NOT FOUND") 404))))


(defn get-user-list
  "retrieve list of all users"
  []
  (map #(-> % val (select-keys [:name :email :role])) @user-store))


(defn- check-user-list
  "returns error string for all wrongly specified users, otherwise nil"
  [user-list]
  (let [error-str
        (reduce
         (fn [all-errors user]
           (let [{:keys [name email role]} user
                 name (str/trim name)
                 email (str/trim email)
                 name-error (when-not (> (count name) 3) (str "wrong name: " name))
                 email-error (when-not (re-matches email-regex email) (str "wrong email address: " email))
                 role-error (when-not (contains? all-roles role) (str "wrong role: " role))
                 errors (remove nil? [name-error email-error role-error])]
             (if (or (> (count name) 0) (> (count email) 0))
               (str all-errors
                    (when-not (empty? errors)
                      (str (apply str (interpose ", " errors)) " for user: " name "; ")))
               all-errors)))
         ""
         user-list)]
    (when (not-empty error-str) error-str)))


(comment
  (check-user-list [{:name "otto" :email "linneman@gmx.de" :role :admin}
                    {:name "konrad" :email "konrad.linnemann@gmail.com" :role :view}])
  )


(defn update-all-users
  "updates user db based on list with all users.

   creates new user when not existing, otherwise updates existing one."
  [ring-args]
  (def _ring-args ring-args)
  (let [params (:params ring-args)
        user-list (params "user-list")
        user-list (hash-str-keys-to-keywords user-list)
        user-list (map
                   (fn [user]
                     (update-in user [:role] #(-> % str/trim str/lower-case keyword)))
                   user-list)]
    (def _user-list user-list)
    (if-let [error-string (check-user-list user-list)]
      {:error error-string}
      (do
        (doseq [{:keys [name email role]} user-list]
          (when (> (count (str/trim name)) 0)
            (if (find-user-by-name name)
              (update-user-in-db name :email email :role role)
              (add-user-to-db :name name :email email
                              :password (codec/base64-encode (get-secret-key {}))
                              :role role))))
        (keep-users-in-db (map :name user-list))
        (await user-store)
        {:user-list (get-user-list)}))))

(comment
  (map :name _user-list)
 )


(defn wrap-authentication
  "middleware for user authentication.

  The first argument is  the ring handler followed by a hash  map where the keys
  denote the http method and the values  declare a set of allowed methods. If no
  access method  is specified  get operations  are allowed  by default  and post
  operation require administration or control priviledges."
  [handler access-method-hash]
  (fn [request]
    (let [{:keys [uri session cookies request-method]} request
          {:keys [id confirmed]} session
          {:keys [role]} (or (find-user-by-name id) {:role :none})
          uri-method (->> uri (re-matches #"(/.+?)(?:/.*)?") last)
          access-role (access-method-hash uri-method)
          granted (or (and access-role (contains? access-role role))
                      (and (not access-role)
                           (or (= request-method :get) (contains? #{:admin :control} role))))
          session-changed (not= role (:role session))
          session (assoc session :role role)
          cookies (assoc cookies "role" {:value (clojure.core/name role)
                                         :max-age setup/cookie-max-age
                                         :path "/"})
          resp (if granted
                 (handler request)
                 (status (response "ACCESS DENIED") 403))]
      (comment
        (println "requested url:" uri)
        (println "uri method:" uri-method)
        (println "request-method: " request-method)
        (println "cookies: " cookies)
        (println "id: " id)
        (println "confirmed: " confirmed)
        (println "db role: " role)
        (println "session role: " (:role session))
        (println "session changed: " session-changed)
        (println "access-granted: " granted))
      (if (or (not session-changed)
              (contains? #{"/login" "/logout" "/confirm"} uri-method))
        resp
        (-> resp
            (assoc :session session)
            (assoc :cookies cookies))))))
