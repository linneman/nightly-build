;; Utility functions for controlling and  monitoring long running background system processes
;;
;; The use and distribution terms for this software are covered by
;; the GNU General Public License
;;
;; (C) 2019, Otto Linnemann

(ns server.core
  (:require [clojure.tools.cli :refer [parse-opts]]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.json-params :as json-params]
            [compojure.core :as compojure]
            [compojure.route :as route]
            [compojure.handler :as handler]
            [server.local-settings :as setup])
  (:use [server.build-task]
        [server.cron]
        [server.shell-utils]
        [server.utils]
        [server.config]
        [server.build-log-handler]
        [server.session]
        [server.auth]
        [crossover.macros]
        [org.satta.glob]
        [clojure.tools.nrepl.server :only (start-server stop-server)]
        [clojure.string :only [split]]
        [compojure.core :only [GET POST PUT DELETE]]
        [ring.util.response :only [response content-type charset redirect]]
        [ring.util.codec :only [url-decode]]
        [ring.middleware.session :only [wrap-session]]
        [ring.middleware.cookies :only [wrap-cookies]]
        [ring.middleware.multipart-params :only [wrap-multipart-params]]
        [ring.middleware.resource]
        [ring.middleware.file-info]
        [clojure.data.json :only [json-str write-json read-json]])
  (:import [java.io ByteArrayOutputStream])
  (:gen-class))


(defn stop-cron-processing
  []
  (defonce cron-timer-ref (agent nil))
  (send-off cron-timer-ref
         (fn [cron-timer]
           (when cron-timer
             (stop-cron cron-timer)
             (println "processing of cronjobs disabled!")))))


(defn restart-cron-processing
  []
  (defonce cron-timer-ref (agent nil))
  (send-off cron-timer-ref
         (fn [cron-timer]
           (when cron-timer
             (stop-cron cron-timer))
           (let [cron-tab (create-cron-tab cron-build-descriptions)]
             (println "processing of cronjobs enabled!")
             (start-cron cron-tab)))))


(defn cron-processing-running?
  []
  (if @cron-timer-ref true false))


(defn run []
  (defonce server (start-server :bind setup/*repl-host* :port setup/*repl-port*))
  (restart-cron-processing)
  (create-admin-user-if-not-existing)
  0)


(defn- help
  [arguments invalid-opts errors summary]
  (println "  Invocation:  java -jar nightly-build-uberjar.jar -c config.clj\n")
  (println " opts:\n")
  (println summary)
  (if errors (println-err errors)
      (if invalid-opts (println-err invalid-opts)))
  -1)


(def cli-options
  [["-c" "--config filename" (format "build configuration file. If not specified, a sample configuration is copied to %s"
                                     (str (System/getProperty "user.home") "/build-configurations/sample_config.clj"))
    :validate [#(.exists (java.io.File. %)) "file or directory must exist"]]
   ["-h" "--help" "this help string"]])


(defn- init-config-file
  "reads actived configuration from file system cache"
  []
  (let [config-setup-file (str setup/*cache-directory* "/active-config")]
    (when (file-exists? config-setup-file)
      (slurp config-setup-file))))


(def #^{:doc "configuration file including path. Other configs are supposed
              to be located in the same directory."}
  config-file (agent (init-config-file)))


(defn- set-config-file
  "enables new configuration file persistently"
  [new-filename]
  (let [config-setup-file (str setup/*cache-directory* "/active-config")]
    (send-off
     config-file
     (fn [_]
       (spit config-setup-file new-filename)
       new-filename))))


(defn- get-config-files-dir
  "gets the configuration directory path"
  []
  (. (java.io.File. @config-file) (getParent)))


(defn- get-active-config-file
  "gets the name of the active configuration file without path"
  []
  (.getName (java.io.File. @config-file)))


(defn- get-config-file-list
  "retrieves the list of all available configuration files"
  []
  (let [config-dir-name (get-config-files-dir)
        config-dir (clojure.java.io/file config-dir-name)]
    (filter
     #(re-matches  #".+[.]clj$" %)
     (map #(.getName %) (file-seq config-dir)))))


(defn- cli
  "process command line arguments and execute commands"
  [& args]
  (let [opts (parse-opts args cli-options)
        options (:options opts)
        arguments (:arguments opts)
        summary (:summary opts)
        errors (:errors opts)
        manifests (:manifest options)
        output-dir (:output options)
        prefix-string (:prefix options)
        config (:config options)
        invalid-opts (not-empty errors)
        title-str (str "Nightly Build Server\n" "June 2019 - March 2020 by Otto Linnemann\n\n")
        home-dir (System/getProperty "user.home")
        local-settings (str home-dir "/.nightly-build/local_settings.clj")]
    (println title-str)
    (if (or (:help options) invalid-opts errors)
      (help arguments invalid-opts errors summary)
      (do
        (copy-resource-manifest home-dir)
        (println (format "Load server configuration from %s ..." local-settings))
        (load-file local-settings)
        (let [config (or config
                         @config-file
                         (str home-dir "/build-descriptions/" setup/*default-build-configuration*))]
          (println (format "Load build description from %s ..." config))
          (load-file config)
          (set-config-file config)
          (run))))))


(defn- all-builds
  []
  (let [builds (into {} (all-task-states-sorted-by-date))
        ts (last-task-update)]
    (json-str (hash-args builds ts))))

(defn- upd-builds-since
  [ts]
  (let [{:keys [builds ts]} (into {} (updated-task-states-since ts))]
    (json-str (hash-args builds ts))))

(defn- all-builds-running
  []
  (let [builds (into {} (updated-task-states-running))
        ts (last-task-update)]
    (json-str (hash-args builds ts))))

(defn- builds-for-keys
  [k]
  (let [builds (into {} (task-states-for-keys k))
        ts (last-task-update)]
    (json-str (hash-args builds ts))))

(defn build-log-all
  "return complete build log when it fits in max-length

   otherwise we cut off initial log segment"
  [build-uuid max-length]
  (let [agent-error (processing-error build-uuid)
        log (or agent-error (get-processed-log build-uuid))
        len (count log)]
    (if (> len max-length)
      (str "...\n" (subs log (- len max-length) len))
      log)))

(defn get-cron-build-descriptions
  "returns the cron build descriptions in json format"
  []
  (json-str
   (map #(-> %
             (update :desc fn-obj-to-str)
             (update :enabled (fn [x] (if (= x nil) true x))))
        cron-build-descriptions)))

(defn start-new-build-with
  "starts new build task with string refering to build description handler from ns server.config"
  [description-string]
  (let [build-description-fn (load-string (str "server.config/" description-string))
        task-sequence-id (gen-build-task-sequence build-description-fn build-log-handler)]
    (start-build-task-sequence task-sequence-id)
    task-sequence-id))

(defn update-cron-build-descriptions
  "updates 'server.config/cron-build-descriptions with given ajax definition"
  [args]
  (let [crontab (args "crontab")
        transform (fn [crontab-e]
                    (into {}
                          (map (fn [[k v]]
                                 [(keyword k)
                                  (if (= k "desc") (symbol v) v)])
                               crontab-e)))
        crontab (mapv transform crontab)
        eval-str (format "(ns server.config) (def cron-build-descriptions %s)" (pr-str crontab))]
    (load-string eval-str)
    (restart-cron-processing)
    "OK"))


(defn save-build-description-as
  "save build description under given filename"
  [{:strs [filename content]}]
  (spit (str (get-config-files-dir) "/" filename) content)
  "OK")


(defn activate-config
  "activates the given configuration file"
  [{:strs [filename] :as args}]
  (let [filename (str (get-config-files-dir) "/" filename)]
    (try
      (load-file filename)
      (set-config-file filename)
      (restart-cron-processing)
      (json-str {:result "OK"})
      (catch Exception e
        (json-str {:error (. e (getMessage))})))))


(defn evaluate-string
  [{:strs [ns s] :as args}]
  (let [s (if ns (str "(ns " ns ")\n" s) s)]
    (def _ns ns)
    (def _s s)
    (try
      (json-str {:result (pr-str (load-string s))})
      (catch Exception e
        (json-str {:error (.getMessage e)})))))


(defn log-request-handler
  "simple logger (debugging purposes)"
  [handler]
  (fn [request]
    (let [response (handler request)]
      (println "--- REQUEST ----\n" request)
      ;(println (str "REQUEST -> URI: " (request :uri)))
      ;(println (str "REQUEST -> HEADERS:\n" (request :headers)))
      ;(println "RESPONSE:\n")
      ;(println response)
      (println "_________________________")
      response)))


(compojure/defroutes main-routes
  ;(GET "/index.html" args (str "<body>" args "</body>"))
  (GET "/" args (redirect "index.html"))
  (GET "/build-log-all/:id" {params :route-params :as args} (build-log-all (:id params) 250000))
  (GET "/build-log-upd/:id" {params :route-params :as args} (json-str (get-last-log (:id params))))
  ;(GET "/build-log-upd/:id" {params :route-params :as args} (json-str {:messages "messages" :ts 100}))
  (GET "/all-builds" args (all-builds))
  (GET "/all-builds-running" args (all-builds-running))
  (GET "/builds-since/:ts" {params :route-params :as args} (upd-builds-since (:ts params)))
  (GET "/builds-for-keys" {:keys [query-string]} (builds-for-keys (set (split query-string #"[\\&]"))))
  (GET "/get-changelog/:id" {params :route-params :as args} (get-changelog (:id params)))
  (POST "/start-build/:id" {params :route-params :as args} (json-str (-> (start-build-task-sequence (:id params)) deref :seq-id)))
  (POST "/stop-build/:id" {params :route-params :as args} (json-str (-> (stop-build-task-sequence (:id params)) deref :seq-id)))
  (POST "/start-new-build-from-desc/:desc" {params :route-params :as args} (json-str (start-new-build-with (:desc params))))
  (GET "/get-cron-build-descriptions" args (get-cron-build-descriptions))
  (POST "/set-cron-build-descriptions" {params :params session :session} (update-cron-build-descriptions params))
  (GET "/get-user-info" args (json-str (get-user-info args)))
  (GET "/get-user-list" args (json-str (get-user-list)))
  (POST "/login" args (login args))
  (POST "/logout" args (logout args))
  (GET "/confirm" args (confirm args))
  (POST "/forgot-password" args (forgot-password args))
  (POST "/set-password" args (set-password args))
  (POST "/add-user" args (add-user args))
  (POST "/update-user" args (update-user args))
  (POST "/update-all-users" args (json-str (update-all-users args)))
  (GET "/get-current-config" args (json-str {:filename (get-active-config-file)
                                             :data (slurp @config-file)}))
  (GET "/get-config/:id" {params :route-params :as args}
       (json-str {:filename (:id params) :data (slurp (str (get-config-files-dir) "/"
                                                           (:id params)))}))
  (GET "/get-config-file-list" args (json-str {:file-list (get-config-file-list)}))
  (POST "/save-as" {params :params session :session} (save-build-description-as params))
  (POST "/activate-config" {params :params session :session} (activate-config params))
  (POST "/load-string" {params :params sessions :session} (evaluate-string params))
  (route/resources "/")
  (route/not-found "Page not found"))


(def access-method-hash
  ; for method get: view permission is default
  ; for method post: control or admin permission is required
  {"/login" all-roles
   "/logout" all-roles
   "/set-password" #{:admin :control :view}
   "/add-user" #{:admin}
   "/update-user" all-roles
   "/forgot-password" all-roles
   "/get-user-list" #{:admin}
   "/update-all-users" #{:admin}
   "/save-as" #{:admin}
   "/activate-config" #{:admin}
   "/load-string" #{:admin}})


(def app
  (-> main-routes
      ; log-request-handler
      (wrap-authentication access-method-hash)
      (wrap-session {:store (db-session-store)
                     :cookie-attrs {:max-age setup/cookie-max-age}})
      json-params/wrap-json-params
      handler/api))

(comment
  ;check login handlers with httpie:
  ;http POST localhost:3001/login name=Otto password=hoppla
  ;http POST localhost:3001/logout name=Otto
  )

(defn start-web-server
  "starts the websever"
  []
  (defonce web-server (jetty/run-jetty #'app
                                   setup/jetty-setup))
  (.start web-server))



; :configurator remove-non-ssl-connectors

(defn stop-web-server
  "stop the webserver"
  []
  (.stop web-server)
  )



(defn -main
  "main function  wrapper"
  [& args]
  (try
    (let [res (apply cli args)]
      (when (not= 0 res)
        (println (format "Error %d occured, exit!" res))
        (System/exit res))
      (dorun (create-task-store))
      (start-web-server)
      (println "Server running ...")
      (Thread/sleep Long/MAX_VALUE))
    (catch Throwable t
      (let [msg (format "%s" (.getMessage t))
            cause (.getCause t)
            st (.getStackTrace t)]
        (println msg)
        (when cause (println "cause: " cause))
        (dorun (map #(println %) st))
        (shutdown-agents)))))


(comment
  (cli)
  (cli "-c" "/home/ol/development/nightly-build/src/nightly_build/config.clj" )

  (restart-cron-processing)
  (stop-cron-processing)
  (cron-processing-running?)
  )
