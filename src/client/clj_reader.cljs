;; Utility functions for controlling and  monitoring long running background system processes
;;
;; The use and distribution terms for this software are covered by
;; the GNU General Public License
;;
;; (C) 2019, Otto Linnemann
;;
;; simple parsing function for clojure s-expression

(ns client.clj-reader
  (:require
   [clojure.tools.reader :as r]
   [clojure.tools.reader.reader-types :as rt]))


(defn read-clj-expr
  "reads clojure expression given as string

   returns  hash-map with  evaluated  expression in  key  :eval, the  associated
   characters  which  have been  successfully  parsed  in  key :parsed  and  the
   remaining characters in key :rest."
  [expr]
  (let [pbrd (rt/string-push-back-reader expr)
        evaluated (try (r/read pbrd) (catch js/Error e nil))]
    (when evaluated
      (let [remain (loop [remain ""]
                     (if-let [c (rt/read-char pbrd)]
                       (recur (str remain c))
                       remain))
            parsed (subs expr 0 (- (count expr) (count remain)))]
        {:evaluated evaluated :parsed parsed :remain remain}))))


(comment
  (read-clj-expr "[1 2 3]")
  (read-clj-expr "[11 22 33]")
  (read-clj-expr "[1 [2 3]]")
  (read-clj-expr "[1 [2 3] 4 5]")
  (read-clj-expr "(defn otto [a b] (* a b))")

  (read-clj-expr "[1 2 \"[3 4]\" 5 6]")

  (read-clj-expr "(defn otto [a b] (* a b)) )")
  (read-clj-expr "[1 \"[1 2 3]\" 3]")
  (read-clj-expr "[1 \"[1 2\n 3]\" \n3]")

  (read-clj-expr "(defn otto [a b]\n\t(* a b))")
  (read-clj-expr "(defn otto [a b] \n\t(* a b)) )")

  (read-clj-expr "1 2 '(1 2 3)")
  (read-clj-expr "(1 2 3) 1 2 3")


  (read-clj-expr "2 3) 4 5")
  (read-clj-expr "3) 4 5")
  (read-clj-expr ") 4 5")

  (read-clj-expr " 4 5")

  )
