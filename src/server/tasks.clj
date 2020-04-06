;; Utility functions for controlling and  monitoring long running background system processes
;;
;; The use and distribution terms for this software are covered by
;; the GNU General Public License
;;
;; (C) 2019, Otto Linnemann


(ns server.tasks
  (:use [server.shell-utils]
        [server.utils]
        [crossover.macros]
        [server.local-settings]))


(defn gen-task-sequence
  "generate a task sequence structure

  seq-id: identifier for given sequence
  tasks-list: list of tasks to be executed one by one when no error occured
  evt-cb: handler to be invoked about current state"
  [seq-id task-list terminate evt-cb]
  (agent {:seq-id seq-id
          :tasks task-list
          :terminate terminate
          :stop (atom false)
          :evt-cb evt-cb}))


(defn- task-to-cache
  "store task to cache"
  [task]
  (let [last-id-cmd (when-let [current-task (first (:tasks task))]
                      (when-let [current-task (-> current-task :build-session :current-task deref)]
                        (let [id (-> current-task :id)
                              cmdline (-> current-task :cmdline)]
                          (hash-args id cmdline))))
        task-cached (-> task
                        (select-keys [:seq-id :error :opt-term-results])
                        (into last-id-cmd))
        filename (str *task-store-dir* file-sep (task :seq-id) ".clj")]
    (mkdirs *task-store-dir*)
    (spit filename (pr-str task-cached))
    task))


(defn exec-tasks
  "process task list executed within background agent

   The task list as specified as hash map with the following keys:
   :seq-id <identifier for the given sequence (all tasks)>
   :tasks  <list of call back functions to be executed>
   :res    <accumulated result string>

   The task function shall return a hash map with these keys:
   :stdout standard output text
   :stderr standard error text
   :success true when successful and next task shall be executed"
  [{:keys [seq-id tasks stop res evt-cb]
    :or {evt-cb identity}
    :as all-args}]
  (loop [res (or res "")
         tasks tasks]
    (task-to-cache (assoc all-args :tasks tasks :error false))
    (let [task (first tasks)]
      (if (and task (not @stop))
        (let [{:keys [stdout stderr success]}
              (do
                (evt-cb (format "sequence: %s -> run task %s ...\n\n"
                                seq-id (:task-id task)))
                ((:fn task)))]
          (if (and (= true success) (not @stop))
            (do
              (evt-cb (format "\n\nsequence: %s -> task %s completed successfully!\n"
                              seq-id (:task-id task)))
              (recur (str res "\n" stdout) (rest tasks)))
            (let [error (if (= success :timeout) "timeout" (if @stop "stopped" stderr))]
              (evt-cb (format "sequence: %s -> task %s failed with error: %s\n"
                              seq-id (:task-id task) error))
              (assoc all-args :tasks tasks :res res :error error))))
        (assoc all-args :tasks tasks :res res :error false)))))


(defn exec-termination
  "process termination handler executed within background agent

   The task function shall return a hash map with these keys:
   :stdout standard output text
   :stderr standard error text
   :success true when successful and next task shall be executed
   :opt-term-results hash map with additional info from term handler (sw version)"
  [{:keys [seq-id tasks terminate error stop evt-cb]
    :or {evt-cb identity}
    :as all-args}]
  (task-to-cache
     (if terminate
       (let [_ (dorun (evt-cb "execute termination sequence ...\n\n"))
             {:keys [stdout stderr success opt-term-results] :as args} (terminate error @stop)
             {:keys [stdout-observer stderr-observer]} (-> tasks first :build-session)]
         (if (= success true)
           (do
             (evt-cb "termination hander successfully executed!")
             (map stop-stream-observer-thread [stdout-observer stderr-observer]))
           (evt-cb (format "termination handler returned error: %s" stderr)))
         (-> all-args
             (assoc-in [:opt-term-results] (assoc opt-term-results :success success))
             (dissoc :res)))
       all-args)))


(defn gen-test-task-list
  [& {:keys [enable-failure]
      :or {enable-failure false}}]
  (defonce enable-failure-test (atom false))
  (reset! enable-failure-test enable-failure)
  (map
   (fn [[id sleep-time]]
     {:task-id id
      :fn (fn []
            (let [res (str (time (Thread/sleep (* sleep-time 1000)))
                           "task: " id)]
              (if (and @enable-failure-test (= id :compile))
                {:stderr "another compile error" :success false}
                {:stdout res :success true})))})
   (list [:configure 1] [:compile 3] [:install 2])))


(defn start-tasks
  "starts or continues processing of task sequence"
  [task-sequence]
  (let [{:keys [tasks stop]} @task-sequence]
    (when-not (empty? tasks)
      (reset! stop false)
      (send-off task-sequence exec-tasks)
      (send-off task-sequence exec-termination))))


(defn stop-tasks
  "stops processing of task sequence"
  [task-sequence]
  (let [stop-atom (:stop @task-sequence)]
    (reset! stop-atom true)))



(comment

  (def test-task-sequence
    (gen-task-sequence
     "build-0"
     (gen-test-task-list :enable-failure true)
     nil
     println))

  (start-tasks test-task-sequence)
  (stop-tasks test-task-sequence)
  (start-tasks test-task-sequence)

  (reset! enable-failure-test false)

  (start-tasks test-task-sequence)

  (println @test-task-sequence)

  )
