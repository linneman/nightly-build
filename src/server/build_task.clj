;; Utility functions for controlling and  monitoring long running background system processes
;;
;; The use and distribution terms for this software are covered by
;; the GNU General Public License
;;
;; (C) 2019, Otto Linnemann

(ns server.build-task
  (:use [server.tasks]
        [server.shell-utils]
        [crossover.macros]
        [server.config]
        [server.utils]
        [server.email]
        [server.build-log-handler]
        [server.local-settings]
        [clojure.string :only [trim]])
  (:require [clojure.edn :as edn])
  (:import [java.io ByteArrayOutputStream]
           [java.time LocalDateTime]))


(defn- create-build-session
  "creates environment for remote execution of build tasks"
  [build-name build-uuid current-task url port init-sequence stdout-cb stderr-cb]
  (let [stdout-stream (ByteArrayOutputStream. 10000)
        stderr-stream (ByteArrayOutputStream. 10000)
        stdout-buf (agent "")
        stderr-buf (agent "")
        stdout-handler (fn [s] (send-off stdout-buf (fn [b] (stdout-cb s) (str b s))))
        stderr-handler (fn [s] (send-off stderr-buf (fn [b] (stderr-cb s) (str b s))))
        stdout-observer (start-stream-observer-thread stdout-stream stdout-handler :freq 1)
        stderr-observer (start-stream-observer-thread stderr-stream stderr-handler)]
    (hash-args build-name build-uuid stdout-stream stderr-stream stdout-observer stderr-observer
               stdout-buf stderr-buf url port init-sequence current-task stdout-cb stderr-cb)))


(defn- release-build-session
  "releases build session and stops observer threads"
  [session]
  (stop-stream-observer-thread (:stdout-observer session))
  (stop-stream-observer-thread (:stderr-observer session)))


(defn- create-build-task-handler
  "returns a build handler"
  [session id cmdline timeout-in-sec]
  (let [remote-build-cmd (str (:init-sequence session) " && " cmdline)
        print-log (:stdout-cb session)
        print-err (:stderr-cb session)
        build-cmd (format "ssh -p %d %s \"%s\"" (:port session) (:url session)
                          remote-build-cmd)
        timeout (* 1000 timeout-in-sec)]
    (fn []
      (send-off (:stdout-buf session) (fn [_] ""))
      (send-off (:stderr-buf session) (fn [_] ""))
      (let [task (sh-cmd build-cmd (:stdout-stream session) (:stderr-stream session))]
        (swap! (:current-task session) #(assoc % :id id :cmdline cmdline :task task))
        (let [proc-res (wait-for-process-exit task timeout)]
          (if (= proc-res :timeout)
            (do
              (print-err "*** process timed out and got not completed error! ***\n\n")
              {:success :timeout :stdout @(:stdout-buf session)
               :stderr (let [ret "timeout"
                             stderr-buf (:stderr-buf session)]
                         (send-off stderr-buf (fn [_] ret))
                         ret)})
            (do
              (Thread/sleep *remote-termination-follow-up-time*)
              {:success (or (= 0 proc-res) proc-res)
               :stdout @(:stdout-buf session)
               :stderr (let [stderr-buf (:stderr-buf session)
                             upd (if (> (count @stderr-buf) 0)
                                   @stderr-buf
                                   (when-not (= 0 proc-res) (str proc-res)))]
                         (send-off stderr-buf (fn [_] upd))
                         upd)})))))))


(comment

  (def current-task (atom nil))

  (def build-session (create-build-session
                      "my-build-name"
                      "my-build-uuid"
                      current-task
                      "localhost" 2226
                      "export LANG=en_US.UTF-8"
                      println
                      println-err))

  (def task-handler (create-build-task-handler
                     build-session
                     :build
                     (lstr "cd /data/ol/development/ltenad9628-bl2_2-ws1/apps_proc/poky &&"
                           ". build/conf/set_bb_env.sh &&"
                           "bitbake 9607-cdp-ltenad") 3600))

  (def res (task-handler))
  (println (:stdout res))
  (println (:stderr res))

  (release-build-session build-session)

  )


(defn- create-build-handler
  [{:keys [build-machine work-spaces build-name build-uuid
           build-dir dev-build-root deploy-build-root
           task-list current-task]
    :as build-description}
   log-cb]
  (let [build-env-decl *build-env-decl*
        stdout-cb ()
        build-session (create-build-session
                       build-name build-uuid current-task
                       (:host build-machine) (:port build-machine)
                       build-env-decl log-cb log-cb)]
    (map
     (fn [[id cmdline timeout]]
       {:task-id id
        :cmdline cmdline
        :build-session build-session
        :fn (create-build-task-handler build-session id cmdline timeout)})
     task-list)))


(comment
  (def task-sequence
    (gen-task-sequence
     "build-0"
     (create-build-handler (create-build-description-mdm9607-bl2_2_0))
     nil
     println))
  )


(defn- create-term-handler
  "helper function to create termination handler which is executed on the host"
  [build-description session log-cb]
  (let [{:keys [terminate]} build-description
        [id cmdline timeout-in-sec & opts] terminate
        {:keys [term-cb]} opts
        timeout (* 1000 timeout-in-sec)]
    (fn [error stopped]
      (let [task (sh-cmd cmdline (:stdout-stream session) (:stderr-stream session))]
        (let [proc-res (wait-for-process-exit task timeout)]
          (if (= proc-res :timeout)
            (log-cb "*** terminator process timed out and got not completed error! ***\n\n"))
          (Thread/sleep *remote-termination-follow-up-time*)
          (let [stderr-buf (-> session :stderr-buf deref trim)
                stderr-buf (if stopped "processing stopped!" stderr-buf)
                [cb-error  opt-term-results] (if term-cb
                                               (term-cb session (when error stderr-buf)) 0)
                proc-res (if (= 0 proc-res) cb-error proc-res)]
            {:success (or (= 0 proc-res) proc-res)
             :stdout @(:stdout-buf session)
             :stderr (if (not-empty stderr-buf) stderr-buf cb-error)
             :opt-term-results opt-term-results}))))))


(defonce task-store (atom {}))

(defn create-task-store
  "creates and initializes from disk task-store atom"
  []
  (let [task-dir (clojure.java.io/file *task-store-dir*)
        task-files (file-seq task-dir)]
    (mkdirs *task-store-dir*)
    (when (empty? @task-store)
      (map
       (fn [task-file]
         (when-not (.isDirectory task-file)
           (comment println "reading task cache from file " (str task-file))
           (let [task (-> task-file slurp edn/read-string)
                 task (assoc task :stop (atom false) :tasks ())
                 build-uuid (:seq-id task)
                 task-sequence (agent task)
                 build-description :none]
             (swap! task-store #(assoc % build-uuid
                                       (hash-args task-sequence build-description))))))
       task-files))))


(comment
  (reset! task-store {})
  (create-task-store)
  (println @task-store)
)

(defonce task-uuid-last-updated-store (atom {:ts (get-date-str) :ts-build-hash {}}))

(defn- wrap-last-updated-stored
  [build-uuid s]
  (swap! task-uuid-last-updated-store
         (fn [{:keys [ts ts-build-hash]}]
           (let [ts (get-date-str-with-nano-secs)
                 outdated (get-date-str (. (LocalDateTime/now) (minusMinutes 5)))
                 ts-build-hash (assoc ts-build-hash ts build-uuid)
                 ts-build-hash (into {} (keep (fn [[k v]]
                                                (when (< (compare outdated k) 0)
                                                  {k v}))
                                              ts-build-hash))]
             (hash-args ts ts-build-hash)))))

(comment
  (keys @task-uuid-last-updated-store)
  (reset! task-uuid-last-updated-store {:ts nil :ts-build-hash {}})
  (wrap-last-updated-stored "build-version-1.2.3" "bla bla")
  )

;; ----- Public API -----

(defn gen-build-task-sequence
  "create a sequence of build operations

   The  operations are  described  by a  so called  build  description which  is
   created by a callback handler function provided as first argument. The second
   argument  is a  logging handler  which  creates the  actual logging  callback
   function. The logging handler is invoked with an additional argument which is
   usually a log  file name. The logging call back  function is invoked whenever
   the internal state of the build sequence changes.

   All build  sequences aka  as tasks  are stored in  an internal  database, the
   task-store as hash map  where the key is an unique  identifier (uuid) and the
   value is hash map providing the  current build state which hold currently the
   the  build description  and  a list  of not  yet  processed operations  (task
   sequence).

   The function returns this uuid for further usage."
  [build-description-handler log-handler]
  (create-task-store)
  (let [build-description (build-description-handler)
        build-uuid (:build-uuid build-description)
        build-log-filename (:build-log-filename build-description)
        log-cb (log-handler build-uuid build-log-filename)
        build-handler (create-build-handler build-description log-cb)
        session (-> build-handler first :build-session)
        term-handler (create-term-handler build-description session log-cb)
        task-evt-wrapper (fn [s] (wrap-last-updated-stored build-uuid s) (log-cb s))
        task-sequence (gen-task-sequence build-uuid build-handler term-handler task-evt-wrapper)]
    (swap! task-store #(assoc % build-uuid
                              (hash-args task-sequence build-description)))
    build-uuid))


(defn get-task-sequence
  "retrieves  the  task  sequence  which  is   the  list  of  open  or  not  yet
  successfully executed  operations from the  task-store. Refer to  the function
  'gen-build-task-sequence' for further information."
  [task-uuid]
  (when @task-store
    (when-let [{:keys [task-sequence]} (@task-store task-uuid)]
      task-sequence)))


(defn get-build-description
  "retrieves the build  description for a given task-uuid. Refer  to the funtion
  'gen-build-task-sequence' for further information."
  [task-uuid]
  (when @task-store
    (when-let [{:keys [build-description]} (@task-store task-uuid)]
      build-description)))


(defn get-all-build-uuids
  []
  "returns  a list  with all  universal unique  identifiers for  build sequences
  stored as keys in the task-store."
  (keys @task-store))


(defn start-build-task-sequence
  "start or  continue the processing  of the  build task sequence  as background
  process referred by the provided task-uuid."
  [task-uuid]
  (when-let [task-sequence (get-task-sequence task-uuid)]
    (when-let [stderr-buf (-> @task-sequence :tasks first :build-session :stderr-buf)]
      (send-off stderr-buf (fn [_] "")))
    (start-tasks task-sequence)))


(defn stop-build-task-sequence
  "stop  the processing  of the  build task  sequence referred  by the  provided
  task-uuid."
  [task-uuid]
  (when-let [task-sequence (get-task-sequence task-uuid)]
    (stop-tasks task-sequence)
    (when-let [current-sequence (-> @task-sequence :tasks first)]
      (let [current-task (-> current-sequence :build-session :current-task)]
        (swap!
         current-task
         (fn [ct]
           (when ct
             (destroy-process (:task ct))
             ct)))))))


(defn release-build-task-sequence
  "release callback  handlers which carry  out event notifications of  the build
  task sequence referred by the provided task-uuid."
  [task-uuid]
    (when-let [task-sequence (get-task-sequence task-uuid)]
      (stop-build-task-sequence task-uuid)
      (when-let [current-sequence (-> @task-sequence :tasks first)]
        (release-build-session (-> current-sequence :build-session)))
      (swap! task-store dissoc task-uuid)
      task-uuid))


(defn is-task-sequence-running?
  "returns true when the background  build process associated with the task-uuid
   is still running."
  [task-uuid]
  (when-let [task-sequence (get-task-sequence task-uuid)]
    (when-let [current-sequence (-> @task-sequence :tasks first)]
     (when-let [current-task @(-> current-sequence :build-session :current-task)]
      (is-alive (:task current-task))))))


(defn get-task-sequence-state
  "retrieve  the  current  processing  state of  the  background  build  process
   associated with the provided task-uuid."
  [task-uuid]
  (when-let [task-sequence (get-task-sequence task-uuid)]
    (when-let [current-sequence (-> @task-sequence :tasks first)]
      (when-let [current-task @(-> current-sequence :build-session :current-task)]
        (let [{:keys [cmdline id task]} current-task
              running (if (is-alive task) true false)]
          (hash-args id cmdline running))))))


(defn get-last-error
  "retrieve last error from given task-uuid"
  [task-uuid]
  (when-not (is-task-sequence-running? task-uuid)
    (when-let [task-sequence (get-task-sequence task-uuid)]
      (:error @task-sequence))))


(defn get-sw-version
  "retrieve the software version if available from given task-uuid"
  [task-uuid]
  (when-let [task-sequence (get-task-sequence task-uuid)]
    (get-in @task-sequence [:opt-term-results :sw-version])))


(defn anything-changed?
  "true if any new changes has been introduced in build task with given task-uuid"
  [task-uuid]
  (when-let [task-sequence (get-task-sequence task-uuid)]
    (get-in @task-sequence [:opt-term-results :anything-changed?])))


(defn processing-error
  "returns the processing (agent) error string for the given task-uuid"
  [task-uuid]
  (when-let [task-sequence (get-task-sequence task-uuid)]
    (when-let [aerr (agent-error task-sequence)]
      (str (.getCause aerr)))))


(defn get-changelog
  "retrieve changelog for given task-uuid"
  [task-uuid]
  (let [changelog
        (when-let [task-sequence (get-task-sequence task-uuid)]
          (get-in @task-sequence [:opt-term-results :changelog]))]
    (or changelog "not defined yet!")))


(defn all-task-states-sorted-by-date
  "returns a sorted lazy sequence of build id's and state information

   The output is sorted such that most recent build uuid's appear first.
   Each task comes as a key value pair with the following keys:
   :running, :changelog, :anything-changed :sw-version :task-uuid"
  []
  (seq
   (reduce
    (fn [res task-uuid]
      (let [task-sequence @(get-task-sequence task-uuid)
            running? (is-task-sequence-running? task-uuid)
            state (get-task-sequence-state task-uuid)
            opt-term-results (-> :opt-term-results task-sequence)]
        (assoc res task-uuid
               (-> (select-keys opt-term-results [:anything-changed? :sw-version])
                   (assoc :task-uuid task-uuid)
                   (assoc :running? running?)
                   (assoc :state state)
                   (assoc :error (if (or (:error task-sequence)
                                         (processing-error task-uuid))
                                   true false))))))
    (sorted-map-by #(compare %2 %1))
    (concat (filter anything-changed? (get-all-build-uuids))
            (filter is-task-sequence-running? (get-all-build-uuids))
            (filter processing-error (get-all-build-uuids))))))


(defn last-task-update
  "returns the time stamp about the most recent update of the task list"
  []
  (@task-uuid-last-updated-store :ts))


(defn updated-task-states-since
  "returns a sorted lazy sequence of updated build id's since given date-string

   The    function   returns    the    output   of    the    same   format    as
   all-task-states-sorted-by-date  but  gives  only  elements  which  have  been
   updated since given date string"
  [date-string]
  (let [last-updated @task-uuid-last-updated-store
        all-tasks (into {} (all-task-states-sorted-by-date))
        uuid-upd-since (into #{} (keep (fn [[k v]]
                                         (when (< (compare date-string k) 0) v))
                                       (last-updated :ts-build-hash)))
        updated-tasks (select-keys all-tasks uuid-upd-since)]
    {:builds updated-tasks :ts (last-updated :ts)}))


(defn updated-task-states-running
  "returns a sorted lazy sequence of updated build id's which are still running

   The function returns the output of the same format as
   all-task-states-sorted-by-date but gives only elements which have been
   updated since given 'task-uuid'"
  []
  (let [all-tasks (all-task-states-sorted-by-date)]
    (keep #(when (-> % val :running?) %) all-tasks)))


(defn task-states-for-keys
  "returns a sorted lazy sequence of build id's of the given set of task-uuid's

   The function returns the output of the same format as
   all-task-states-sorted-by-date but gives only elements which have been
   updated since given 'task-uuid'"
  [task-uuid-set]
  (let [all-tasks (all-task-states-sorted-by-date)]
    (keep #(when (task-uuid-set (key %)) %) all-tasks)))


(defn- primitive-log-handler
  "creates a  very primitive log handler  which writes out log  data to standard
  out and to the specified file."
  [build-id filename]
  (fn [logstr]
    (print logstr)
    (flush)
    (when filename
      (spit filename logstr :append true))))


(defn create-cron-tab
  "creates  a  crontab  for  automatic  start of  all  builds  within  the  list
  cron-build-descriptions"
  [cron-build-descriptions]
  (map
   (fn [{:keys [desc] :as args}]
     (let [handler
           (fn []
             (let [task-sequence-id (gen-build-task-sequence
                                     desc build-log-handler)]
               (start-build-task-sequence task-sequence-id)))]
       (assoc args :handler handler)))
   cron-build-descriptions))


(defn drop-current-task
  "helper function which drops the current task from given uuid
   this can be useful when a sequence step is executed manually
   and therefore needs to remove from the processing list."
  [task-uuid]
  (let [task-sequence (get-task-sequence task-uuid)]
    (send-off task-sequence #(update-in % [:tasks] rest))))


(comment

  ;; usage illustration

  (def task-sequence-id (gen-build-task-sequence create-build-description-mdm9607-bl2_2_0
                                                 primitive-log-handler))


  (def task-sequence-id (gen-build-task-sequence create-build-description-mdm9607-bl2_2_0
                                                 build-log-handler))


  (def task-sequence (get-task-sequence task-sequence-id))
  (def build-description (get-build-description task-sequence-id))
  ; (drop-current-task task-sequence-id)


  (start-build-task-sequence task-sequence-id)
  (stop-build-task-sequence task-sequence-id)

  (is-task-sequence-running? task-sequence-id)
  (get-task-sequence-state task-sequence-id)
  (get-last-error task-sequence-id)
  (get-sw-version task-sequence-id)
  (anything-changed? task-sequence-id)
  (get-changelog task-sequence-id)

  (println "----")
  (map println (get-all-build-uuids))
  (all-task-states-sorted-by-date)
  (updated-task-states-since "2019-11-14-13h10")
  (updated-task-states-running)

  (release-build-task-sequence task-sequence-id)

  ;; quectel

  (def task-sequence-id (gen-build-task-sequence create-build-description-mdm9607-bl2_2_0-quectel
                                                 build-log-handler))

  (def task-sequence-id-aerror "ltenad9607-bl2_2_0_2021-06-15-23h00s30")
  (def task-sequence-id "ltenad9607-bl2_2_0_2021-06-14-23h00s30")

  (def task-sequence-id (first (get-all-build-uuids)))

  ;; cron

  (def test-cron-build-descriptions
    [{:m 0 :h 2 :dom false :mon false :dow false
      :desc create-build-description-mdm9607-bl2_2_0}])

  (create-cron-tab test-cron-build-descriptions)
)
