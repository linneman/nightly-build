;;; repl for interactive development
;;; only invoked when no optimizations used
;;;
;;; The use and distribution terms for this software are covered by
;;; the GNU General Public License

(ns client.repl
  (:require [clojure.browser.repl :as repl]))


(defn ^:export connect
  "Exported to connect to repl server. Start a shell e.g. in emacs
   by <M-x shell> and start the repl server there with the following
   command: $ lein trampoline cljsbuild repl-listen
   Afterwords you have to load the debug script by loading the
   repl.html in your browser. You should then be able to evaluate
   Clojurescript expressions in the context of the browser"
  []
  (repl/connect "http://localhost:9000/repl"))
