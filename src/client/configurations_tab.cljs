;; Utility functions for controlling and  monitoring long running background system processes
;;
;; The use and distribution terms for this software are covered by
;; the GNU General Public License
;;
;; (C) 2019, Otto Linnemann
;;
;; build configuration setup

(ns client.configurations-tab
  (:require
   [client.repl :as repl]
   [client.dispatch :as dispatch]
   [clojure.browser.dom :as dom]
   [clojure.string :as str]
   [goog.dom :as gdom]
   [goog.events :as events]
   [goog.positioning.Corner :as poscorner]
   [goog.ui.Button :as Button]
   [goog.ui.ButtonRenderer :as ButtonRenderer]
   [goog.ui.FlatButtonRenderer :as FlatButtonRenderer]
   [goog.ui.Tab :as tab]
   [goog.ui.TabBar :as tabbar]
   [goog.ui.Menu :as menu]
   [goog.ui.SubMenu :as submenu]
   [goog.ui.PopupMenu :as popupmenu]
   [goog.ui.MenuBarRenderer :as mb-renderer]
   [goog.ui.MenuButton :as menu-button]
   [goog.ui.MenuItem :as menu-item]
   [goog.ui.Separator :as separator]
   [goog.ui.decorate :as decorate]
   [goog.ui.menuBar :as menu-bar]
   [goog.ui.menuBarDecorator :as menu-bar-decorator]
   [goog.style :as style]
   [goog.Timer :as timer]
   [client.json :as json]
   [client.clj-reader :as clj-reader])
  (:use
   [client.ajax :only [send-request]]
   [client.utils :only [get-element get-modal-dialog open-modal-dialog load-url-in-new-tab]]
   [client.logging :only [loginfo]]
   [client.auth :only [admin-access? logged-in?]])
  (:use-macros [crossover.macros :only [hash-args]]))


(def tab-content (dom/get-element "nightly-build-tab-content"))
(def config-tab (get-element "tab-build-config" tab-content))

(def editor-id "editMe")
(def editor (dom/get-element editor-id))


(def #^{:doc "number of space per tab"
        :dynamic true}
  *tab-size* 2)

(def orange "#cb4b16")
(def yellow "#b58900")
(def red "#dc322f")
(def blue "#268bd2")
(def cyan "#2aa198")
(def green "#859900")
(def magenta "#d33682")
(def white "#839496")
(def light-white "#586e75")


(defn- color-for-level
  [level]
  (let [colors [orange yellow red blue cyan green magenta]]
    (nth colors (mod level (count colors)))))


(defn- map-line-to-html
  [line & {:keys [row level is-string] :or {row 1 level 0 :is-string false}}]
  (reduce
   (fn [state char]
     (let [{:keys [col level is-token is-comment is-string is-char-quote
                   is-definition is-definition-arg line html]} state
           delta-level (cond
                         (or (= char "(") (= char "[")) 1
                         (or (= char ")") (= char "]")) -1
                         :else 0)
           next-level (+ level delta-level)
           is-comment (or is-comment (and (= char ";") (not is-string)))
           is-string (if (and (= char "\"") (not is-char-quote)) (not is-string) is-string)
           is-token (and (= char "("))
           upd-definition (if is-token
                            (#{"defn" "def" "fn"}
                             (first (str/split (subs line (inc col)) #"[ ]+")))
                            (if (= char " ") false is-definition))
           is-definition-arg (if is-definition-arg
                               (if (= char " ") false is-definition-arg)
                               (and is-definition (not upd-definition)))
           is-definition upd-definition
           color (if is-comment light-white
                     (if (or is-string (= char "\"")) cyan
                         (case delta-level
                           1 (color-for-level level)
                           -1 (color-for-level next-level)
                           0 (cond
                               is-definition yellow
                               is-definition-arg blue
                               :else white))))
           is-char-quote (= char "\\")
           id (str "R" row "C" col)
           class (cond is-comment "comment"
                       (or is-string (= char "\"")) "string"
                       is-token "token"
                       :else "code")
           char (if (= char \space) "&nbsp;" char)
           outchar (str "<span id=\"" id "\" class=\"" class
                        "\" style=\"color:" color ";\">" char "</span>")
           html (str html outchar)
           level next-level
           col (inc col)]
       (comment println "char: " char ", outchar: " outchar ", is-comment: " is-comment ", is-token: " is-token ", is-definition: " is-definition ", upd" upd-definition ", arg: " is-definition-arg)
       (hash-args col level html is-token is-comment is-string
                  is-definition is-definition-arg is-char-quote line)))
   {:col 0 :level level :html "" :line line :is-string is-string}
   line))


(defn- split-lines
  [file-content]
  (vec (map str/trim (str/split file-content "\n"))))


(defn- clojure-code-to-html
  [lines]
  (reduce
   (fn [state line]
     (let [{:keys [html row level all-levels raw-lines html-lines is-string]} state
           indent-sequence (apply str (repeat *tab-size* "&nbsp;"))
           tab-sequence (apply str
                               (map (fn [level]
                                      (str "<span id=\"R" row "I" level "\" class=\"ident\">"
                                           indent-sequence "</span>"))
                                    (range level)))
           all-levels (conj all-levels level)
           html-line (map-line-to-html line :row row :level level :is-string is-string)
           html-line (assoc html-line :indent level)
           is-string (:is-string html-line)
           content (str tab-sequence (:html html-line))
           content (if (empty? content) "<br>" content)
           content (str "<div id=\"R" row "\">" content "</div>")
           level (:level html-line)
           row (inc row)
           html (str html content)
           raw-lines (conj raw-lines line)
           html-lines (conj html-lines html-line)]
       (hash-args row level is-string html raw-lines html-lines all-levels)))
   {:level 0 :all-levels [] :row 1 :is-string false :html "" :raw-lines [] :html-lines []}
   lines))


(def clojure-code-hash (atom {:raw-lines [] :html-lines []}))

(defn- get-html-line [row] (-> clojure-code-hash deref :html-lines (nth (dec row))))
(defn- get-line [row] (-> clojure-code-hash deref :raw-lines (nth (dec row))))
(defn- get-line-len [row] (-> clojure-code-hash deref :raw-lines (nth (dec row)) count))
(defn- get-line-indent [row] (-> clojure-code-hash deref :html-lines (nth (dec row)) :indent))
(defn- get-raw-lines [] (-> clojure-code-hash deref :raw-lines))
(defn- get-nr-lines [] (count (get-raw-lines)))


(defn- parse-clojure-file
  [txt]
  (reset! clojure-code-hash (-> txt split-lines clojure-code-to-html)))


(defn- get-clojure-code-as-ascii
  []
  (let [{:keys [raw-lines all-levels]} @clojure-code-hash
        lines (map
               (fn [indent-level line]
                 (let [tabs (apply str (repeat (* indent-level *tab-size*) " "))]
                   (str tabs line "\n")))
               all-levels
               raw-lines)]
    (apply str lines)))


(defn- render-editor-field
  [html]
  (set! (. editor -innerHTML) html))


(defn- get-current-config
  []
  (let [filename-element (get-element "config-file" tab-content)]
    (. filename-element -textContent)))


(defn- request-current-config
  []
  (send-request "/get-current-config"
                {}
                (fn [ajax-evt]
                  (let [resp (. (. ajax-evt -target) (getResponseText))
                        {:strs [filename data]} (json/parse resp)
                        filename-element (get-element "config-file" tab-content)]
                    (-> data parse-clojure-file :html render-editor-field)
                    (set! (. filename-element -textContent) filename)))
                "GET"))


(defn- request-config
  [filename]
  (send-request (str "/get-config/" filename)
                {}
                (fn [ajax-evt]
                  (let [resp (. (. ajax-evt -target) (getResponseText))
                        _ (def _resp resp)
                        {:strs [filename data]} (json/parse resp)
                        filename-element (get-element "config-file" tab-content)]
                    (-> data parse-clojure-file :html render-editor-field)
                    (set! (. filename-element -textContent) filename)))
                "GET"))

(comment
  (request-current-config)
  (-> "" parse-clojure-file :html render-editor-field)

  (keys (json/parse _resp))
  (request-config "ltenad_config.clj")
  )


(defn- position-of-editing-node
  [t & [anchor-offset]]
  (if (= (type t) js/HTMLDivElement)
      (if (=  (. t -id) editor-id)
        [1 0]
        [(js/parseInt (last (re-find #"(?:[R])([0-9]+)" (. t -id)))) 0])
      (let [node (if (= (type t) js/HTMLSpanElement) t (. t -parentNode))
            id (. node -id)
            row (js/parseInt (last (re-find #"(?:[R])([0-9]+)" id)))]
        (def _node node)
        (if (= (. node -className) "ident")
          [row 0]
          (let [col (js/parseInt (last (re-find #"(?:[C])([0-9]+)" id)))
                anchor-offset (or anchor-offset 0)
                col (+ col anchor-offset)]
            [row col (. node -className)])))))


(defn- get-cursor-position
  []
  (let [s (. (js/eval "window") (getSelection))
        t (. s -anchorNode)]
    (when t
      (position-of-editing-node t (. s -anchorOffset)))))


(defn- get-selection-range
  []
  (let [s (. (js/eval "window") (getSelection))]
    (when-not (. s -isCollapsed)
      (when-let [r (. s (getRangeAt 0))]
        (let [start (. r -startContainer)
              end (. r -endContainer)
              [start-row start-col] (position-of-editing-node start)
              [end-row end-col] (position-of-editing-node end)]
          [[start-row start-col] [end-row end-col]])))))


(defn- get-char-at
  [[row col]]
  (let [row (dec row)
        col (dec col)
        {:keys [raw-lines]} @clojure-code-hash]
    (when (and (>= row 0) (>= col 0))
      (when (> (count raw-lines) row)
        (let [line (nth raw-lines row)]
          (when (> (count line) col)
            (nth line col)))))))


(defn- next-char-index
  [[row col]]
  (if (< col (get-line-len row))
    [row (inc col)]
    [(inc row) 0]))


(defn- get-char-at-cursor
  []
  (get-char-at (get-cursor-position)))


(defn- insert-string-at
  [ins-str [row col]]
  (when (and (>= row 0) (< row 10000) (>= col 0) (< col 200))
    (let [code (swap! clojure-code-hash
                      (fn [code]
                        (let [lines (:raw-lines code)
                              row (dec row)
                              line (nth lines row)
                              line (str (subs line 0 col) ins-str (subs line col))
                              upd-lines (assoc-in lines [row] line)]
                          (clojure-code-to-html upd-lines))))]
      (render-editor-field (:html code)))))


(defn- insert-new-line-at
  [[row col]]
  (when (and (>= row 0) (< row 10000) (>= col 0) (< col 200))
    (let [code (swap! clojure-code-hash
                      (fn [code]
                        (let [lines (:raw-lines code)
                              row (dec row)
                              line (nth lines row)
                              split-line (str (subs line 0 col))
                              new-line (subs line col)
                              upd-lines (apply conj
                                               (subvec lines 0 row)
                                               split-line new-line
                                               (subvec lines (inc row)))]
                          (clojure-code-to-html upd-lines))))]
      (render-editor-field (:html code)))))


(defn replace-range-with
  [[start-row start-col] [end-row end-col] text]
  (let [code (swap! clojure-code-hash
                    (fn [code]
                      (let [lines (:raw-lines code)
                            top (subvec lines 0 (dec start-row))
                            remain-first (subs (nth lines (dec start-row)) 0 start-col)
                            remain-last (subs (nth lines (dec end-row)) (inc end-col))
                            bottom (subvec lines end-row)
                            upd-lines (concat top
                                              [(str remain-first text remain-last)]
                                              bottom)]
                        (clojure-code-to-html upd-lines))))]
    (render-editor-field (:html code))))


(defn- shift-line-up
  [row]
  (when (and (> row 0) (<= row (-> clojure-code-hash deref :raw-lines count)))
    (let [code (swap! clojure-code-hash
                      (fn [code]
                        (let [lines (:raw-lines code)
                              row (dec row)
                              merged-line (nth lines (dec row))
                              appended (nth lines row)
                              upd-lines (apply conj
                                               (subvec lines 0 (dec row))
                                               (str merged-line appended)
                                               (subvec lines (inc row)))]
                          (clojure-code-to-html upd-lines))))]
      (render-editor-field (:html code)))))


(defn- remove-char-at
  [[row col]]
  (when (and (>= row 0) (< row 10000) (> col 0) (< col 200))
    (let [code (swap! clojure-code-hash
                      (fn [code]
                        (let [lines (:raw-lines code)
                              row (dec row)
                              line (nth lines row)
                              line (str (subs line 0 (dec col)) (subs line col))
                              upd-lines (assoc-in lines [row] line)]
                          (clojure-code-to-html upd-lines))))]
      (render-editor-field (:html code)))))


(defn set-cursor
  "set the cursor to specified column

   It retrieves  the associated  span element  'RmCn'. If it  does not  exist it
   tries to fetch the  element 'RmC(n-1) and sets the anchor  one element to the
   right. We do not need to create another  span element to append at the end of
   the line by this trick."
  [[row col]]
  (let [pos-elem (dom/get-element (str "R" row "C" col))
        [pos-elem anchor] (if pos-elem
                            [pos-elem 0]
                            [(dom/get-element (str "R" row "C" (dec col))) 1])
        [pos-elem anchor] (if pos-elem
                            [pos-elem anchor]
                            [(dom/get-element (str "R" row)) 0])]
    (when pos-elem
      (let [s (. (js/eval "window") (getSelection))
            r (js/eval "document.createRange();")]
        (. r (setStart pos-elem anchor))
        (. r (setEnd pos-elem anchor))
        (. r (collapse))
        (. s (removeAllRanges))
        (. s (addRange r))
        (. editor (focus))
        true))))


(defn- get-text-range
  [[[start-row start-col] [end-row end-col]]]
  (let [lines (get-raw-lines)
        first-line (subs (get-line start-row) start-col)
        center-lines (loop [res []
                            [line & rem-lines] (drop start-row lines)
                            lines-to-copy (- end-row start-row 1)]
                       (if (> lines-to-copy 0)
                         (recur (conj res line) rem-lines (dec lines-to-copy))
                         res))
        last-line (subs (get-line end-row) 0 (inc end-col))]
    (if (= (- end-row start-row) 0)
          [(subs last-line start-col)]
          (if (= (- end-row start-row) 1)
            (vec (concat [first-line] [last-line]))
            (vec (concat [first-line] center-lines [last-line]))))))


(comment
  (def x (get-text-range (get-selection-range)))
  (map #(println (str "--" % "--")) x)
  )


(defn- move-cursor-right [[row col]] [row (inc col)])
(defn- move-cursor-right-times [[row col] n] [row (+ col n)])
(defn- move-cursor-left [[row col]] (if (> col 0) [row (dec col)] [(dec row) 0]))

(defn- move-cursor-down
  [[row col]]
  (let [next-row (if (< row (get-nr-lines)) (inc row))
        next-indent (get-line-indent next-row)
        delta (* *tab-size* (- (get-line-indent row) next-indent))
        next-col (+ col delta)
        next-col-max (get-line-len next-row)

        next-col (min next-col next-col-max)
        next-col (max next-col 0)]
    [next-row next-col]))

(defn- move-cursor-up
  [[row col]]
  (let [prev-row (if (> row 1) (dec row) row)
        prev-indent (get-line-indent prev-row)
        delta (* *tab-size* (- (get-line-indent row) prev-indent))
        prev-col (+ col delta)
        prev-col-max (get-line-len prev-row)
        prev-col (min prev-col prev-col-max)
        prev-col (max prev-col 0)]
    [prev-row prev-col]))

(defn- move-cursor-to-eol [[row col]] [row (get-line-len row)])


(defn- insert-into-str-at
  [s ins pos]
  (str (subs s 0 pos) ins (subs s pos)))


(defn- index-of-pred
  [s p idx]
  (loop [s (subs s idx (count s))
         offset 0]
    (let [c (first s)]
      (if (p c)
        [c (+ idx offset)]
        (let [r (rest s)]
          (when-not (empty? r)
            (recur r (inc offset))))))))


(defn- line-index-of-pred
  [[row col] p]
  (loop [row row
         col col
         [line & remain] (drop (dec row) (get-raw-lines))]
    (when (not-empty line)
      (if-let [[_ idx] (index-of-pred line p col)]
        [row idx]
        (recur (inc row) 0 remain)))))


(defn- paredit-forward-slurp-sexp
  [[row col]]
  (let [code
        (swap!
         clojure-code-hash
         (fn [code]
           (if-let [[row1 col1] (line-index-of-pred [row col] #{")" "]" "}"})]
             (let [lines (:raw-lines code)
                   closing-brace-char (get-char-at [row1 (inc col1)])
                   closing-brace-line (nth lines (dec row1))
                   rest-closing-brace-line (subs closing-brace-line (inc col1))
                   closing-brace-line (str (subs closing-brace-line 0 col1) rest-closing-brace-line)
                   lines (vec (concat (subvec lines 0 (dec row1)) [closing-brace-line] (subvec lines row1)))
                   remain-lines (vec (concat [rest-closing-brace-line] (subvec lines row1)))
                   remain-str (apply str (map #(str % "\n") remain-lines))]
               (if-let [{:keys [parsed]} (clj-reader/read-clj-expr remain-str)]
                 (let [parsed-lines (str/split parsed "\n")
                       row-offset (dec (count parsed-lines))
                       col-offset (count (last parsed-lines))
                       row2 (+ row1 row-offset)
                       col2 (+ (if (= row-offset 0) col1 0) col-offset)
                       brace-line2 (nth lines (dec row2))
                       brace-line2 (str (subs brace-line2 0 col2) closing-brace-char
                                        (subs brace-line2 col2))
                       upd-lines (vec (concat (subvec lines 0 (dec row2))
                                              [brace-line2]
                                              (subvec lines row2)))]
                   (clojure-code-to-html upd-lines))
                 code))
             code)))]
    (render-editor-field (:html code))))


(defn- find-next-char [[row col] c]
  (let [nr-lines (get-nr-lines)]
    (loop [row row col col]
      (let [line (get-line row)]
        (if-let [idx (str/index-of line c col)]
          [row idx]
          (when (< row nr-lines)
            (recur (inc row) 0)))))))


(defn- reverse-index-of
  [s c idx]
  (let [r (apply str (reverse s))
        ridx (- (count r) idx)]
    (when-let [found-idx (str/index-of r c ridx)]
      (- (count r) found-idx))))


(defn- find-prev-char [[row col] c]
  (loop [row row col col]
    (let [line (get-line row)]
      (if-let [idx (reverse-index-of line c col)]
        [row idx]
        (when (> row 1)
          (recur (dec row) 0))))))


(defn- paredit-split-sexp
  [[row col]]
  (let [lines (:raw-lines @clojure-code-hash)
        line (nth lines (dec row))]
    (when-let [[closing-delim idx] (index-of-pred line #{")" "]" "}"} col)]
      (let [opening-delim (case closing-delim
                            ")" "("
                            "}" "{"
                            "]" "["
                            "")]
        (when-let [[crow ccol] (find-prev-char [row col] opening-delim)]
          (remove-char-at [row (inc idx)])
          (remove-char-at [crow ccol])
          )))))


(declare remote-evaluate-before-point)

(defn- handle-key-stroke
  [{:keys [key shift ctrl alt meta]}]
  (loginfo (str "pressed key:" key, " ,shift: " shift " ,crtl: " ctrl " ,alt: " alt " ,meta: " meta))
  (let [[row col class] (get-cursor-position)
        cursor-pos [row col]
        char (get-char-at-cursor)
        rchar (-> (get-cursor-position) move-cursor-right get-char-at)
        lchar (-> (get-cursor-position) move-cursor-left get-char-at)
        key (if (or (and (= key "0") shift ctrl)
                    (and (= key ")") shift alt)) "paredit-forward-slurp-sexp" key)
        key (if (or (and (= key "s") ctrl)
                    (and (= key "s") alt)) "paredit-split-sexp" key)
        key (if (or (and (= key "e") ctrl)
                    (and (= key "e") alt)) "remote-evaluate-before-point" key)]
    (case key
      "Enter" (do (insert-new-line-at [row col]) (set-cursor [(inc row) 0]))
      "Backspace" (do (if (> col 0)
                        (if (or (and (= char "(") (= rchar ")"))
                                (and (= char "{") (= rchar "}"))
                                (and (= char "[") (= rchar "]"))
                                (and (not= lchar "\\") (= char "\"") (= rchar "\""))
                                (and (= char "\\") (= rchar "\"")))
                          (dorun (map remove-char-at [[row col] [row col]]))
                          (when-not (#{"(" "{" "[" ")" "}" "]" "\"" "\\"} char)
                            (remove-char-at [row col]))))
                      (if (> col 0)
                        (set-cursor (move-cursor-left [row col]))
                        (do (shift-line-up row) (set-cursor (move-cursor-to-eol [(dec row) 1])))))
      "ArrowRight" (set-cursor (move-cursor-right [row col]))
      "ArrowLeft"  (set-cursor (move-cursor-left [row col]))
      "ArrowUp"    (set-cursor (move-cursor-up [row col]))
      "ArrowDown"  (set-cursor (move-cursor-down [row col]))
      "(" (do (insert-string-at "()" cursor-pos) (set-cursor (move-cursor-right cursor-pos)))
      "[" (do (insert-string-at "[]" cursor-pos) (set-cursor (move-cursor-right cursor-pos)))
      "{" (do (insert-string-at "{}" cursor-pos) (set-cursor (move-cursor-right cursor-pos)))
      ")" (set-cursor (move-cursor-right (find-next-char (get-cursor-position) ")")))
      "]" (set-cursor (move-cursor-right (find-next-char (get-cursor-position) "]")))
      "}" (set-cursor (move-cursor-right (find-next-char (get-cursor-position) "}")))
      "\"" (if (= class "string")
             (do (insert-string-at "\\\"" cursor-pos)
                 (set-cursor (move-cursor-right-times cursor-pos 2)))
             (do (insert-string-at "\"\"" cursor-pos)
                 (set-cursor (move-cursor-right cursor-pos))))
      "paredit-forward-slurp-sexp" (do (paredit-forward-slurp-sexp cursor-pos)
                                       (set-cursor cursor-pos))
      "paredit-split-sexp" (do (paredit-split-sexp (get-cursor-position))
                               (set-cursor cursor-pos))
      "remote-evaluate-before-point" (remote-evaluate-before-point)
      (when (= (count key) 1)
        (insert-string-at key cursor-pos)
        (set-cursor (move-cursor-right cursor-pos))))))


(comment

  (do (insert-string-at "X" [11 36]) nil)
  (do (insert-string-at "X" [21 0]) nil)
  (set-cursor (cursor-right [11 36]))
  (set-cursor [11 36])
  (set-cursor [21 0])

  (cursor-right [11 35])
  (get-cursor-position)
  (set-cursor [24 3])
  (set-cursor [1 0])
  (set-cursor [11 35])
  )


(set! (. editor -onkeydown)
      (fn [e]
        (let [key (.-key e)
              shift (.-shiftKey e)
              ctrl (.-ctrlKey e)
              alt (.-altKey e)
              meta (.-metaKey e)]
          (if (and (#{"c" "x" "v"} key) (or ctrl meta))
            true
            (do
              (handle-key-stroke (hash-args key shift ctrl alt meta))
              false)))))


(defn- insert-expr-at-cursor
  [expr]
  (let [[row col] (get-cursor-position)
        code
        (swap!
         clojure-code-hash
         (fn [code]
           (let [lines (:raw-lines code)
                 before (subvec lines 0 (dec row))
                 after (subvec lines row)
                 [target-line] (subvec lines (dec row) row)
                 chars-before (subs target-line 0 col)
                 chars-after (subs target-line col)
                 lines-to-insert (vec (map str/trim (str/split expr "\n")))
                 first-line-to-insert (first lines-to-insert)
                 next-lines-to-insert (vec (butlast (rest lines-to-insert)))
                 last-line-to-insert (when (> (count lines-to-insert) 1)
                                       (last lines-to-insert))
                 upd-lines (vec (concat before
                                        [(str chars-before first-line-to-insert)]
                                        next-lines-to-insert
                                        [(str last-line-to-insert chars-after)]
                                        after))]
             (clojure-code-to-html upd-lines))))]
    (render-editor-field (:html code))
    (set-cursor [row col])))


(set! (. editor -onpaste)
      (fn [e]
        (let [clipboard-data (. e -clipboardData)
              text (. clipboard-data (getData "Text"))
              {:keys [parsed]} (clj-reader/read-clj-expr text)]
          (insert-expr-at-cursor parsed)
          false)))


(set! (. editor -oncut)
      (fn [e]
        (let [s (. (js/eval "window") (getSelection))]
          (when-not (. s -isCollapsed)
            (when-let [r (. s (getRangeAt 0))]
              (let [clipboard-data (. e -clipboardData)
                    start (. r -startContainer)
                    end (. r -endContainer)
                    [start-row start-col] (position-of-editing-node start)
                    [end-row end-col] (position-of-editing-node end)
                    selected-text (get-text-range [[start-row start-col] [end-row end-col]])
                    selected-text (apply str (map #(str % "\n") selected-text))
                    {:keys [parsed remain]} (clj-reader/read-clj-expr selected-text)]
                (. clipboard-data (setData "text/plain" parsed))
                (replace-range-with [start-row start-col] [end-row end-col] remain)
                (. e (preventDefault))
                false))))))


(comment
  (request-current-config)
  (-> "" parse-clojure-file :html render-editor-field)
  (do (insert-string-at "X" (get-cursor-position)) nil)
  (do (insert-string-at "(" (get-cursor-position)) nil)

  (def dt (js/eval "new DataTransfer()"))
  (. (. dt -items) (add "test" "text/plain"))
  (def promise (. (. (js/eval "navigator") -clipboard) (write dt)))
  (. promise -then)
  (. promise (then (fn [] (loginfo "success")) (fn [] (loginfo "failure"))))


  (set-cursor [1 10])

  (set! (. editor -onkeydown) (fn [e] (def _e e) (loginfo "Keyboard Event") false))
  (def line (nth @config-file-lines 6))
  (subs line 35)

  (println (apply str (take 400 x)))
  (def lines (split-lines x))
  (def render (clojure-code-to-html lines))
  (subs (:res render) 0 300)

  (set! (. editor -innerHTML) (:res (map-line-to-html "(defn function [a b c] (* a b c))" :row 5)))
  (set! (. editor -innerHTML) (:res (map-line-to-html "'(defn function [a b c] (* a b c))" :row 5)))
  (set! (. editor -innerHTML) (:res (map-line-to-html ";; (defn function [a b c] (* a b c))")))

  (dorun (set! (. editor -innerHTML) (:res render)) nil)

  (def s (. (js/eval "window") (getSelection)))
  (. _node -id)
  (. _node -innerText)

  (def r (js/eval "document.createRange();"))
  ;(. r (selectNode _node))
  (. editor (select))
  (. r (setStart editor 3))
  (. r (setEnd editor 3))
  (. r (collapse))
  (. s (removeAllRanges))
  (. s (addRange r))

  (js-keys my-node)
  (. editor (focus))
  (. editor (scrollIntoView))

  (. _node (focus))
  (. _node (scrollIntoView))
  )


(defn- all-lines-to-pos
  "read and concatenate all lines up to given position"
  [[row col]]
  (let [lines (get-raw-lines)
        cutoff (- (get-line-len row) col)
        all-text (subvec lines 0 row)
        sub-text (->> (subvec lines 0 row) (interpose "\n") (apply str))]
    (subs sub-text 0 (- (count sub-text) cutoff))))


(defn- tokenize-last-clojure-s-exp
  "take  a  string  s, watch  out  for  each  the  next special  character  like
  paranthesis, curly  and rectangular braces  and blanks  and try to  parse this
  string up to this character. Return the in this way accumulated string."
  [s]
  (let [rev-s (reverse s)]
    (loop [acc ""
           to-parse rev-s]
      (when-let [[next-char & to-parse] to-parse]
        (let [acc (str acc next-char)]
          (if (#{\( \) \[ \] \{ \} \space} next-char)
            (let [test-s-exp (-> acc reverse str/join)
                  {:keys [evaluated remain]} (clj-reader/read-clj-expr test-s-exp)]
              (if (and evaluated (empty? remain))
                (do
                  test-s-exp)
                (recur acc to-parse)))
            (recur acc to-parse)))))))


(defn- remote-evaluate
  "evaluate string s in namespace ns on server"
  [ns s]
  (if (and (logged-in?) (admin-access?))
    (send-request "/load-string"
                  (json/generate {:ns ns :s s})
                  (fn [ajax-evt]
                    (let [resp (. (. ajax-evt -target) (getResponseText))
                          {:strs [result error]} (json/parse resp)]
                      (def _result result)
                      (if error
                        (js/alert (str "Evalution failed with error: " error))
                        (js/alert (str "-> " result)))))
                  "POST")
    (js/alert "You need admin permissions for remote evaluation!")))


(defn- remote-evaluate-before-point
  "evaluate s-exp before cursor position on remote server in default ns"
  []
  (let [all-lines (all-lines-to-pos (get-cursor-position))
        last-s-exp (tokenize-last-clojure-s-exp all-lines)]
    (remote-evaluate "server.config" last-s-exp)))


(defn- evaluate-all
  "evaluates all content of editor window"
  []
  (let [all-lines (str/join "\n" (get-raw-lines))]
    (remote-evaluate nil all-lines)))


(def load-file-reactor
  (dispatch/react-to
   #{:file-select-action}
   (fn [evt data]
     (loginfo (str "select file evt target: " data ", type: " evt))
     (request-config data))))

; (dispatch/delete-reaction load-file-reactor)


(defn- create-file-select-popup
  [files]
  (let [anchor (dom/get-element "load-config")
        file-select-menu (goog.ui.PopupMenu.)]
    (doseq [file files]
      (let [item (goog.ui.MenuItem. file)]
        (. item (setId file))
        (. file-select-menu (addItem item))))
    (. file-select-menu (attach anchor goog.positioning.Corner.TOP_LEFT))
    (. goog.events (listen
                    file-select-menu
                    (. goog.object (getValues goog.ui.Component.EventType))
                    (fn [e]
                      (def _e e)
                      (let [target (. e -target)
                            type (. e -type)]
                        (when (= type "action")
                          (dispatch/fire :file-select-action (. target (getId))))))))
    file-select-menu))


(def file-select-popup (atom nil))
(defn- render-file-selector
  [files]
  (swap! file-select-popup
         (fn [popup]
           (when popup
             (. popup (dispose)))
           (let [upd (create-file-select-popup files)]
             (. upd (render))
             upd))))

(defn request-file-list
  []
  (send-request "/get-config-file-list"
                {}
                (fn [ajax-evt]
                  (let [resp (. (. ajax-evt -target) (getResponseText))
                        {:strs [file-list]} (json/parse resp)]
                    (def _file-list file-list)
                    (render-file-selector file-list)))
                "GET"))


;; define enter save as filename
(let [[dialog ok-button cancel-button]
      (get-modal-dialog :panel-id "save-as-dialog"
                        :title-string "Save As ..."
                        :ok-button-id "confirm-save-as"
                        :cancel-button-id "cancel-save-as"
                        :dispatched-event :entered-save-as-filename
                        false)]
  (def save-as-dialog dialog))

(comment
  (open-modal-dialog save-as-dialog)
  (. (get-element "save-as-filename") -value)
  )


(defn new-config
  [filename]
  (let [filename-element (get-element "config-file" tab-content)]
    (set! (. filename-element -textContent) filename)
    (-> "" parse-clojure-file :html render-editor-field)))


(defn activate-current-config
  []
  (let [config-file (get-current-config)]
    (send-request "/activate-config"
                  (json/generate {:filename config-file})
                  (fn [ajax-evt]
                    (let [resp (. (. ajax-evt -target) (getResponseText))
                          {:strs [error]} (json/parse resp)]
                      (loginfo resp)
                      (if error
                        (js/alert error)
                        (do
                          (request-file-list)
                          (request-config config-file)
                          (js/alert (str "build configuration \"" config-file "\" enabled!"))))))
                  "POST")))

(defn save-as
  [filename]
  (send-request "/save-as"
                (json/generate {:filename filename :content (get-clojure-code-as-ascii)})
                (fn [ajax-evt]
                  (let [resp (. (. ajax-evt -target) (getResponseText))]
                    (request-file-list)
                    (request-config filename)
                    (loginfo resp)))
                "POST"))

(defn save
  []
  (if (and (logged-in?) (admin-access?))
    (save-as (get-current-config))
    (js/alert "You need admin permissions to upload the files!")))


(dispatch/react-to
 #{:entered-save-as-filename}
 (fn [evt data]
   (let [filename (. (get-element "save-as-filename") -value)
         [_ filename-body] (re-find #"([a-zA-Z0-9_-]+)(?:[.].*)?" filename)
         filename (str filename-body ".clj")]
     (loginfo (str "save as: " filename))
     (if (and (logged-in?) (admin-access?))
       (save-as filename)
       (js/alert "You need admin permissions to upload the files!")))))


(def menu-bar-reactor
  (dispatch/react-to
   #{:menu-select-action}
   (fn [evt data]
     (loginfo (str "menubar evt target: " data ", type: " evt))
     (let [cursor-pos (get-cursor-position)]
       (case data
         "new-config" (new-config "untitled.clj")
         "load-config" (loginfo "activate file list selection")
         "save-as" (open-modal-dialog save-as-dialog)
         "save" (if (= (get-current-config) "untitled.clj")
                  (open-modal-dialog save-as-dialog)
                  (save))
         "revert-config" (request-current-config)
         "forward-slurp-sexp" (when cursor-pos (paredit-forward-slurp-sexp cursor-pos)
                                    (set-cursor cursor-pos))
         "split-sexp" (when cursor-pos (paredit-split-sexp cursor-pos)
                            (set-cursor cursor-pos))
         "activate-config" (activate-current-config)
         "evaluate-before-point" (remote-evaluate-before-point)
         "evaluate-all" (evaluate-all)
         "help-clojure-intro" (load-url-in-new-tab "clojure-intro.html")
         "help-repl" (load-url-in-new-tab "online-repl.html")
         "help-build-descriptions" (load-url-in-new-tab "creating-build-descriptions.html")
         "help-ext-repl" (load-url-in-new-tab "connecting-an-external-repl.html")
         "help-api" (load-url-in-new-tab "doc/api/index.html"))))))

; (dispatch/delete-reaction menu-bar-reactor)


(def menubar (goog.ui.decorate (dom/get-element "menubar")))
(. goog.events (listen
                menubar
                (. goog.object (getValues goog.ui.Component.EventType))
                (fn [e]
                  (let [target (. e -target)
                        type (. e -type)]
                    (when (= type "action")
                      (dispatch/fire :menu-select-action (. target (getId))))))))



(defn- enable-config-pane
  "shows the config-pane"
  []
  (style/setOpacity config-tab 1) ;; important for first load only
  (style/showElement config-tab true)
  (request-file-list)
  (request-current-config)
  (. menubar (setVisible true))
  (loginfo "config pane enabled"))


(defn- disable-config-pane
  "hides the config-pane"
  []
  (style/showElement config-tab false)
  (. menubar (setVisible false))
  (loginfo "config pane disabled"))


(def pane-enabled-reactor (dispatch/react-to
                           #{:tab-selected}
                           (fn [evt data]
                             (loginfo (str "react-to event: " evt " data: " data))
                             (if (= data :tab-build-config-selected)
                               (enable-config-pane)
                               (disable-config-pane)))))


(defn- init
  "evaluated only once after page load"
  [e])


(events/listen
 (js/eval "window")
 goog.events.EventType.LOAD
 init)
