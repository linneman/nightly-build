;; Starts the browser repl for Clojurescript development
;; This is required since lein-cljsbuild is broken with recent Clojurescript versions
;;
;; 2019-07-28 OL


(ns browser-repl
  (:require
   [cljs.repl :as repl]
   [cljs.repl.browser :as browser]))


(repl/repl* (browser/repl-env
             :port 9000
             :launch-browser false
             :work-dir "resources/public"
             :static-dir "out"
             :src "src/client")
            {:output-dir "out"
             :optimizations :none
             :cache-analysis true
             :source-map true})
