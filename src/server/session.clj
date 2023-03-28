;; Utility functions for controlling and  monitoring long running background system processes
;;
;; The use and distribution terms for this software are covered by
;; the GNU General Public License
;;
;; (C) 2019, Otto Linnemann

(ns server.session
  (:require [server.local-settings :as setup]
            [ring.util.codec :as codec])
  (:use [ring.middleware.session.store :only [SessionStore]]
        [server.utils]
        [server.shell-utils]
        [server.crypto :only [get-secret-key get-encrypt-pass-and-salt hash-password]]
        [crossover.macros]))


(defn- save-session-store
  "serializes and saves the given sessionstore hash table to disk"
  [filename content]
  (try
    (do
      (mkdirs setup/*cache-directory*)
      (spit filename (pr-str content)))
    (catch Exception e (println-err "caught exception: " (.getMessage e)))))


(defn- load-session-store
  "loads and deserializes the given session store hash table from disk"
  [filename]
  (try
    (-> filename slurp clojure.edn/read-string)
    (catch java.io.FileNotFoundException e
      (do (println-err "caught exception: " (.getMessage e)) {}))))


(defn- init-session-store
  "read the session log store from disk. If this is not possible
   return an emptry and freshly initialized store."
  []
  (load-session-store setup/*sesion-store-filename*))




(defonce ^{:private true
       :doc "session store which is backed up to the filesystem"}
  session-store (agent (init-session-store)))


(comment
  (ns-unmap *ns* 'session-store)
  )


(defn read-session-data
  "reads the session data for a given key"
  [key]
  (let [res (@session-store key)]
    (comment println "--- read-session-data for key: " key " -> " res)
    (or res {})))

(defn delete-session-data
  "deletes session data for a given key"
  [key]
  (comment (println "--- delete-session-data for key: " key))
  (send-off session-store
            (fn [content]
              (let [upd (dissoc content key)]
                (save-session-store setup/*sesion-store-filename* upd)
                upd))))

(defn write-session-data
  "write respectively updates session data for key"
  [key data]
  (comment (println "--- write-session-data for key: " data " -> " data))
  (send-off session-store
            (fn [content]
              (let [upd (assoc content key data)]
                (save-session-store setup/*sesion-store-filename* upd)
                upd))))


(deftype FileSessionStore []
  SessionStore
  (read-session [_ key]
    ; (println "! read-session: key->" key "data->" (read-session-data key))
    (read-session-data key))
  (write-session [_ key data]
    (let [key (or key (codec/base64-encode (get-secret-key {})))]
      ; (println "! write-session: key->" key "data->" data)
      (write-session-data key data)
      key))
  (delete-session [_ key]
    ; (println "! delete-session: key->" key)
    (delete-session-data key)
    nil))

(defn db-session-store
  "creates the persistent session store (via file system)"
  []
  (FileSessionStore.))
