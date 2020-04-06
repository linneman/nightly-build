;; Utility functions for controlling and  monitoring long running background system processes
;;
;; The use and distribution terms for this software are covered by
;; the GNU General Public License
;;
;; (C) 2019, Otto Linnemann


;; build configuration for nightly generation of LTENAD release candidates

(ns server.config
  (:use [server.utils]
        [crossover.macros])
  (:import [java.time LocalDateTime]))


(def #^{:doc "environemnt variable definition invoked before any other build command"
        :dynamic true}
  *build-env-decl*
  (lstr
    "export LANG=en_US.UTF-8 &&"
    "export GIT_SSL_NO_VERIFY=1"))


;; --- email setup ---
;;

(def ^:dynamic *email-host-name* "smtp.gmail.com")
(def ^:dynamic *email-ssl-smtp-port* "465")
(def ^:dynamic *email-smtp-port* 587)
(def ^:dynamic *email-set-ssl* true)
(def ^:dynamic *email-from-name* "<from-name>")
(def ^:dynamic *email-from-email* "<from-name@gmail.com>")
(def ^:dynamic *email-auth-name* "<from-name@gmail.com>")
(def ^:dynamic *email-auth-password* "<gmail-passwd>")

(comment
  ;; for test
  (sendmail ["fram-name@googlemail.com"] "test subject 3" "DEF")
  )


(def #^{:doc "recipients list of email addresses where build status information is sent to"
        :dynamic true}
  *email-build-status-recipients*
  ["john.doe@yoyodyne.org"
   "max.miller@yoydyne.org"])


;; --- miscellaneous ---

(def #^{:doc "sleep time in ms to ensure that last messages/error messages can be fetched"
        :dynamic true}
  *remote-termination-follow-up-time* 3000)


;; --- build configurations ---


;; we cannot include sendmail due to cyclic dependencies. Instead
;; we refer to sendmail at runtime
;; https://stackoverflow.com/questions/3084205/resolving-clojure-circular-dependencies
(defn- sendmail [& args]
  (apply (resolve 'server.email/sendmail) args))


(defn create-build-steps-ltenad9607
  "build steps for ltenad projects based on MDM9607"
  [{:keys [build-name build-uuid build-machine work-spaces build-dir
           current-task dev-build-root deploy-build-root task-list
           manifest-url meta-proprietary-repo-url nand-images-repo-url
           dev-manifest deploy-manifest version-regex
           dev-branch deploy-branch manifest-repo-branch
           image-dir build-log-filename change-log-filename version-filename
           required-space-gb]}]
  (let [cleanup-script (-> "cleanup.py" get-resource quote-string-for-echoing)

        task-list
        (filter
         identity

         (list

          [:cleanup-first
           (format
            (lstr
             "SCRIPT=\\$(mktemp /tmp/cleanup.py.XXXXXX) &&"
             "echo '%s' > \\${SCRIPT} &&"
             "python \\${SCRIPT} %s %s &&"
             "rm \\${SCRIPT}")
            cleanup-script (str work-spaces "/" build-name) required-space-gb) 2700]
          [:init
           (format
            "mkdir -p %s %s"
            dev-build-root deploy-build-root) 10]
          [:fetch-dev
           (format
            "cd %s && repo init -u %s -b %s -m %s && repo sync -q -j 4 2>&1"
            dev-build-root manifest-url manifest-repo-branch dev-manifest) 7200]
          [:gen-changeset
           (format
            "cd %s && python yoyotools/scripts/gen-changesets.py %s"
            dev-build-root version-regex) 60]
          [:anything-changed?
           (format
            (lstr
             "cd %s && if [ -f no_changes ]; then"
             "  rm -rf apps_proc modem_proc boot_images trustzone_images common .repo;"
             "  rm -rf rpm_proc nv_configuration btfm_proc cnss_proc images *.xml *.sh *.bat;"
             "  echo 'no new changes' > nightly.log;"
             "  exit 1;"
             "fi")
            dev-build-root) 10]
          [:update-version
           (format
            "cd %s && python yoyotools/scripts/inc-version.py"
            dev-build-root) 10]
          [:build-apps-proc
           (format
            "cd  %s && . build/conf/set_bb_env.sh && bitbake 9607-cdp-ltenad"
            (str dev-build-root "/apps_proc/poky")) 10800]
          [:build-modem-proc
           (format
            "cd  %s && ./BUILD.sh"
            (str dev-build-root "/modem_proc")) 3600]
          [:build-boot-images
           (format
            "cd  %s && ./BUILD.sh ; ./BUILD.sh"
            (str dev-build-root "/boot_images")) 1800]
          [:build-trustzone-images
           (format
            "cd  %s && ./BUILD.sh ; ./BUILD.sh"
            (str dev-build-root "/trustzone_images")) 1800]
          [:build-rpm-proc
           (format
            "cd  %s && ./BUILD.sh"
            (str dev-build-root "/rpm_proc")) 1800]
          [:build-common
           (format
            "cd  %s && . build/conf/set_bb_env.sh && cd %s && python build.py --imf"
            (str dev-build-root "/apps_proc/poky")
            (str dev-build-root "/common/build")) 1800]
          [:collect_image_data
           (format
            "cd  %s && . build/conf/set_bb_env.sh && cd %s && ./Get_NAND.sh"
            (str dev-build-root "/apps_proc/poky")
            dev-build-root) 120]
          [:checkin-version
           (format
            "cd %s && git add . && git commit -s -m 'update version' && git push origin HEAD:refs/heads/%s"
            (str dev-build-root "/apps_proc/poky/build/conf")
            dev-branch) 120]
          [:checkin-proprietary-packages
           (format
            (lstr
             "cd %s && git add . && git commit -s -m 'update binaries' &&"
             "git push origin-lfs HEAD:refs/heads/%s")
            (str dev-build-root "/apps_proc/proprietary")
            dev-branch) 600]
          [:checkin-meta-proprietary
           (format
            (lstr
             "echo 'clone repository for proprietary installation recipes ...' &&"
             "cd %s && git clone %s meta-proprietary-deploy -b %s &&"
             "echo 'copy proprietary installation recipes to new repository ...' &&"
             "cp -R meta-proprietary/recipes/* meta-proprietary-deploy/recipes/ &&"
             "echo 'commit and push proprietary installation recipes ...' &&"
             "cd  meta-proprietary-deploy && git add . && "
             "git commit -s -m 'update binary installation packets' &&"
             "git push origin HEAD:refs/heads/%s")
            (str dev-build-root "/apps_proc/poky")
            meta-proprietary-repo-url deploy-branch deploy-branch) 60]
          [:clone-and-update-binary-images
           (format
            (lstr
             "echo 'clone large file system repository for modem images ...' &&"
             "cd %s && git clone %s -b %s images_deploy && cd %s &&"
             "if [ -f ../modem_proc_changed ]; then"
             "  echo 'update modem_proc images, update files ...' &&"
             "  cp orig_MODEM_PROC_IMG* ../images_deploy/ &&"
             "  cp unsigned_binaries.tar ../images_deploy/ &&"
             "  cp partition.mbn ../images_deploy/ &&"
             "  cp *.elf ../images_deploy/ &&"
             "  cp NON-HLOS.ubi ../images_deploy/ &&"
             "  cp NON-HLOS.ubifs ../images_deploy/ ;"
             "fi &&"
             "if [ -f ../boot_images_changed ]; then"
             "  echo 'update boot images, update files ...' &&"
             "  cp ENPRG9x07.mbn ../images_deploy/ &&"
             "  cp NPRG9x07.mbn ../images_deploy/ &&"
             "  cp sbl1.mbn ../images_deploy/ ;"
             "fi &&"
             "if [ -f ../trustzone_changed ]; then"
             "  echo 'trustzone changed, update files ...' &&"
             "  cp tz.mbn ../images_deploy/ &&"
             "  cp devcfg.mbn ../images_deploy/ || : ;"
             "fi &&"
             "if [ -f ../rpm_proc_changed ]; then"
             "  echo 'rpm proc changed, update files ...' &&"
             "  cp rpm.mbn ../images_deploy/ ;"
             "fi &&"
             "if [ -f ../apps_proc_changed ]; then"
             "  echo 'apps proc changed, update files ...' &&"
             "  cp appsboot.mbn ../images_deploy/ &&"
             "  cp mdm9607-boot.img ../images_deploy/ &&"
             "  cp mdm9607-rootfs.ubifs ../images_deploy/ &&"
             "  cp mdm9607-usrfs.ubifs ../images_deploy/ &&"
             "  cp mdm9607-otafs.ubifs ../images_deploy/ &&"
             "  cp mdm9607-persistfs.ubifs ../images_deploy/ &&"
             "  cp mdm9607-appfs.ubifs ../images_deploy/ &&"
             "  cp vmlinux ../images_deploy/ || : ;"
             "fi &&"
             "echo 'update system partition files ...' &&"
             "cp mdm9607-sysfs.ubi ../images_deploy/ &&"
             "cp ubinize.cfg ../images_deploy/")
            dev-build-root nand-images-repo-url dev-branch image-dir) 1200]
          [:push-images
           (format
            (lstr "echo 'push large file system repository for modem images ...' &&"
                  "cd %s/images_deploy && git add . && git commit -s -m 'update binaries' &&"
                  "git push origin HEAD:refs/heads/%s")
            dev-build-root dev-branch) 1200]
          [:create-and-push-dev-manifest
           (format
            (lstr
             "VERSION=\\$(python %s/yoyotools/scripts/get-version.py) &&"
             "cd %s/.repo/manifests/releases && repo manifest -r -o \\${VERSION}.xml &&"
             "git add . && git commit -s -m 'update manifests' &&"
             "git push origin HEAD:refs/heads/%s")
            dev-build-root dev-build-root manifest-repo-branch
            ) 600]
          [:sync-downloads-dir
           (format
            (lstr
             "cd %s/apps_proc/poky/build &&"
             "rsync -vtrOpog --chown=ol:integrator --chmod=g+w "
             "      downloads/ space:/srv/nfs/ltenad/sources-9607")
            dev-build-root)
           240]
          [:fetch-deploy
           (format
            (lstr
             "cd %s && repo init -u %s -b %s -m %s && repo sync -q -j 4 2>&1 &&"
             "cd %s && git lfs fetch && git lfs checkout &&"
             "cd %s && git lfs fetch && git lfs checkout")
            deploy-build-root manifest-url manifest-repo-branch deploy-manifest
            (str deploy-build-root "/" image-dir)
            (str deploy-build-root "/apps_proc/proprietary" )) 7200]
          [:build-apps-proc-deploy-for-check
           (format
            "cd  %s && . build/conf/set_bb_env.sh && bitbake 9607-cdp-ltenad"
            (str deploy-build-root "/apps_proc/poky")) 10800]
          [:create-and-push-deploy-manifest
           (format
            (lstr
             "VERSION=\\$(python %s/yoyotools/scripts/get-version.py) &&"
             "cd %s/.repo/manifests/releases && repo manifest -r -o \\${VERSION}_deploy.xml &&"
             "git add . && git commit -s -m 'update manifests' &&"
             "git push origin HEAD:refs/heads/%s")
            dev-build-root deploy-build-root manifest-repo-branch
            ) 600]


          [:success
           "echo '+++ all build steps have been successfully executed! +++'" 5]
          ))

        terminate
        ;; copy the local build log file back to buid host
        ;; copy from the build host the changelog and version files
        [:copy-log-to-build-machine
         (format (lstr
                  "scp -P %s %s %s:%s ;"
                  "scp -P %s %s:%s %s ;"
                  "scp -P %s %s:%s %s")
                 (:port build-machine) build-log-filename (:host build-machine) build-dir
                 (:port build-machine) (:host build-machine) (str dev-build-root "/changelog.txt")
                 change-log-filename
                 (:port build-machine) (:host build-machine)
                 (str dev-build-root "/apps_proc/poky/build/conf/local.conf") version-filename)
         120
         :term-cb ;; clojure handler for sending status email
         (fn [session error]
           (let [{:keys [build-uuid build-name]} session
                 current-task (-> session :current-task deref)
                 sw-version (if (file-exists? version-filename)
                              (->> version-filename slurp
                                    (re-find #"(BUILDNAME[ ]?=[ ]?[\"])([a-zA-Z0-9-_.]+)([ \"])+")
                                    reverse second)
                              "sw-version not avaiable")
                 anything-changed? (not= :anything-changed? (:id current-task))
                 changelog (if (file-exists? change-log-filename)
                             (slurp change-log-filename)
                             "change log not available")
                 [subject mail-txt]
                 (if anything-changed?
                   (if (empty? error)
                     [(format "nightly build %s, sw version %s successful!" build-uuid sw-version)
                      (format (lstr
                               "Dear Colleagues,\n\n"
                               "This message is to inform you that the nightly build %s, sw version %s\n"
                               "has been successfully compiled:\n\n"
                               "The following changes have been introduced:\n\n"
                               "%s")
                              build-uuid sw-version changelog)]
                     [(format "nightly build %s, sw version %s failed!" build-uuid sw-version)
                      (format (lstr
                               "Dear Colleagues,\n\n"
                               "This message is to inform you that the nightly build %s, sw version %s\n"
                               "failed within the build task %s with the following error:\n\n"
                               "Error: %s\n\n"
                               "The following changes have been introduced:\n\n"
                               "%s")
                              build-uuid sw-version (:id current-task) error changelog)])
                   [(format "no changes detected in nightly build %s" build-uuid)
                    (format (lstr
                             "Dear Colleagues,\n\n"
                             "There have been no new changes discovered within the nightly build %s.\n")
                            build-uuid)])]
             (println "___ send email: " mail-txt)
             [(if (= error "processing stopped!")
                0
                (sendmail *email-build-status-recipients* subject mail-txt))
              (hash-args sw-version changelog anything-changed?)]))]]
    (hash-args task-list terminate)))


(defn create-build-description-mdm9607-bl2_2_0
  []
  (let [build-machine {:host "ava-build" :port 2226}
        work-spaces "/mnt/ssd1/ol/nightly-builds"
        build-name "ltenad9607-bl2_2_0"
        version-regex "MDM9628.LE.2.2-00103.1.w01.[0-9]?"
        current-task (atom nil)
        date-str (get-date-str)
        ;; date-str "2019-10-24-15h04" ;; --> for test purposes XXX
        build-uuid (str build-name "_" date-str)
        build-dir (str work-spaces "/" build-name "/" date-str)
        log-dir (str (System/getProperty "user.home") "/nightly-build-logs")
        _ (mkdirs log-dir)
        build-log-filename (str log-dir "/nightly_" date-str ".log")
        change-log-filename (str log-dir "/changelog_" date-str ".log")
        version-filename (str log-dir "/version_" date-str ".log")
        dev-build-root (str build-dir "/" build-name "-dev")
        deploy-build-root (str build-dir "/" build-name "-deploy")
        image-dir "9607_nand_images"
        repo-url "ssh://scm.yoyodyne.org:29418/"
        manifest-url (str repo-url "proj1737_manifests.git")
        meta-proprietary-repo-url (str repo-url "proj1737_apps_proc-oe-core-meta-proprietary")
        nand-images-repo-url (str repo-url "proj1737_images")
        manifest-repo-branch "corealm"
        dev-manifest "yoyo-mdm9607-bl2_2_0-dev.xml"
        deploy-manifest "yoyo-mdm9607-bl2_2_0-deploy.xml"
        dev-branch "yoyo-mdm9607-bl2_2_0-dev"
        deploy-branch "yoyo-mdm9607-bl2_2_0-deploy"
        required-space-gb 235

        env (hash-args build-name build-uuid build-machine work-spaces build-dir
                       manifest-url version-regex image-dir dev-manifest deploy-manifest
                       current-task dev-build-root deploy-build-root
                       meta-proprietary-repo-url nand-images-repo-url
                       dev-branch deploy-branch build-log-filename
                       change-log-filename version-filename
                       manifest-repo-branch required-space-gb)
        {:keys [task-list terminate]} (create-build-steps-ltenad9607 env)]
    (merge env (hash-args build-log-filename task-list terminate))))


(defn create-build-description-mdm9607-bl2_2_0-quectel
  []
  (let [build-machine {:host "ava-build" :port 2226}
        work-spaces "/mnt/ssd2/ol/nightly-builds"
        build-name "ltenad9607-bl2_2_0-quectel"
        version-regex "MDM9628.LE.2.2-quec85.1.000.[0-9]?"
        current-task (atom nil)
        date-str (get-date-str)
        ;; date-str "2019-06-17-11h41" ;; --> for test purposes
        build-uuid (str build-name "_" date-str)
        build-dir (str work-spaces "/" build-name "/" date-str)
        log-dir (str (System/getProperty "user.home") "/nightly-build-logs")
        _ (mkdirs log-dir)
        build-log-filename (str log-dir "/nightly_" date-str ".log")
        change-log-filename (str log-dir "/changelog_" date-str ".log")
        version-filename (str log-dir "/version_" date-str ".log")
        dev-build-root (str build-dir "/" build-name "-dev")
        deploy-build-root (str build-dir "/" build-name "-deploy")
        image-dir "9607_nand_images"
        repo-url "ssh://scm.yoyodyne.org:29418/"
        manifest-url (str repo-url "proj1737_manifests.git")
        meta-proprietary-repo-url (str repo-url "proj1737_apps_proc-oe-core-meta-proprietary")
        nand-images-repo-url (str repo-url "proj1737_images")
        manifest-repo-branch "corealm"
        dev-manifest "yoyo-mdm9607-bl2_2_0-quectel.xml"
        deploy-manifest "yoyo-mdm9607-bl2_2_0-quectel-deploy.xml"
        dev-branch "yoyo-mdm9607-bl2_2_0-quectel"
        deploy-branch "yoyo-mdm9607-bl2_2_0-quectel-deploy"
        required-space-gb 235

        env (hash-args build-name build-uuid build-machine work-spaces build-dir
                       manifest-url version-regex image-dir dev-manifest deploy-manifest
                       current-task dev-build-root deploy-build-root
                       meta-proprietary-repo-url nand-images-repo-url
                       dev-branch deploy-branch build-log-filename
                       change-log-filename version-filename
                       manifest-repo-branch required-space-gb)
        {:keys [task-list terminate]} (create-build-steps-ltenad9607 env)]
    (merge env (hash-args build-log-filename task-list terminate))))


(defn create-build-description-mdm9607-bl2_0_1
  []
  (let [build-machine {:host "gamma-build" :port 2226}
        work-spaces "/home/ol/workspace/nightly-builds"
        build-name "ltenad9607-bl2_0_1"
        version-regex "MDM9628.LE.2.0.1-00154.1-VE00.03.[0-9][0-9][0-9]"
        current-task (atom nil)
        date-str (get-date-str)
        ;; date-str "2019-10-24-15h04" ;; --> for test purposes XXX
        build-uuid (str build-name "_" date-str)
        build-dir (str work-spaces "/" build-name "/" date-str)
        log-dir (str (System/getProperty "user.home") "/nightly-build-logs")
        _ (mkdirs log-dir)
        build-log-filename (str log-dir "/nightly_" date-str ".log")
        change-log-filename (str log-dir "/changelog_" date-str ".log")
        version-filename (str log-dir "/version_" date-str ".log")
        dev-build-root (str build-dir "/" build-name "-dev")
        deploy-build-root (str build-dir "/" build-name "-deploy")
        image-dir "9607_nand_images"
        repo-url "ssh://scm.yoyodyne.org:29418/"
        manifest-url (str repo-url "proj1737_manifests.git")
        meta-proprietary-repo-url (str repo-url "proj1737_apps_proc-oe-core-meta-proprietary")
        nand-images-repo-url (str repo-url "proj1737_images")
        manifest-repo-branch "corealm"
        dev-manifest "yoyo-mdm9607-bl2_0_1-dev.xml"
        deploy-manifest "yoyo-mdm9607-bl2_0_1-deploy.xml"
        dev-branch "yoyo-mdm9607-bl2_0_1-dev"
        deploy-branch "yoyo-mdm9607-bl2_0_1-deploy"
        required-space-gb 235

        env (hash-args build-name build-uuid build-machine work-spaces build-dir
                       manifest-url version-regex image-dir dev-manifest deploy-manifest
                       current-task dev-build-root deploy-build-root
                       meta-proprietary-repo-url nand-images-repo-url
                       dev-branch deploy-branch build-log-filename
                       change-log-filename version-filename
                       manifest-repo-branch required-space-gb)
        {:keys [task-list terminate]} (create-build-steps-ltenad9607 env)]
    (merge env (hash-args build-log-filename task-list terminate))))


(comment
  (println (:task-list (create-build-description-mdm9607-bl2_2_0)))
  )


;; --- crontab ---

(def cron-build-descriptions
  [{:m 0 :h 00 :dom false :mon false :dow false
    :desc create-build-description-mdm9607-bl2_2_0}
   {:m 0 :h 05 :dom false :mon false :dow false
    :desc create-build-description-mdm9607-bl2_2_0-quectel}
   {:m 0 :h 20 :dom false :mon false :dow false :enabled true
    :desc create-build-description-mdm9607-bl2_0_1}])
