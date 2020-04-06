;;; JSON Helpers
;;; taken from: http://mmcgrana.github.com/2011/09/clojurescript-nodejs.html
;;. updated by https://stackoverflow.com/questions/10157447/how-do-i-create-a-json-in-clojurescript
;;;
;;; Eclipse Public License 1.0

(ns client.json)


(defn generate
  "Returns a newline-terminate JSON string from the given
   ClojureScript data."
  [data]
  (.stringify js/JSON (clj->js data)))


(defn parse
  "Returns ClojureScript data for the given JSON string."
  [line]
  (js->clj (.parse js/JSON line)))
