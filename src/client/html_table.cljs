;; Utility functions for controlling and  monitoring long running background system processes
;;
;; The use and distribution terms for this software are covered by
;; the GNU General Public License
;;
;; (C) 2019, Otto Linnemann
;;
;; overview about all builds

(ns client.html-table
  (:require
   [clojure.browser.dom :as dom]
   [clojure.string :as str]
   [goog.dom :as gdom]
   [goog.style :as style])
  (:use
   [client.utils :only [get-element]]
   [client.logging :only [loginfo]]))


(defn htmlcoll2array
  "Transforms a HTMLCollection into a clojure array"
  [htmlcol]
  (loop [index 0 row-array []]
    (if-let [row (. htmlcol (item index))]
      (recur (inc index) (conj row-array row))
      row-array)))


(defn clear-table
  "Removes all rows except the prototype-row and header
   from an html table with a given dom id string."
  [table-id-str]
  (let [table (dom/get-element table-id-str)
        table-body (gdom/getFirstElementChild table)
        rows (htmlcoll2array (gdom/getChildren table-body))]
    (doseq [row rows]
      (let [cells (htmlcoll2array (gdom/getChildren row))
            first-cell (first cells)
            tag-name (. first-cell -tagName)]
        (when-not (or (= "prototype-row" (. row -id))
                      (= tag-name "TH"))
          (dorun (map #(when-let [obj (. % -rendered)] (. obj (dispose))) cells))
          (gdom/removeNode row))))))


(defn delete-table-row
  "Removes the row with the given index from an html table with a given dom id string.

   The index counts content rows exclusively. Title and prototype rows are excluded."
  [table-id-str idx-to-remove]
  (let [table (dom/get-element table-id-str)
        table-body (gdom/getFirstElementChild table)
        rows (htmlcoll2array (gdom/getChildren table-body))
        idx-to-remove (+ idx-to-remove 2)]
    (doseq [[idx row] (map-indexed vector rows)]
      (let [cells (htmlcoll2array (gdom/getChildren row))
            first-cell (first cells)
            tag-name (. first-cell -tagName)]
        (when (= idx idx-to-remove)
          (dorun (map #(when-let [obj (. % -rendered)] (. obj (dispose))) cells))
          (gdom/removeNode row))))))


(defn copy-table-row
  "copies the row with the given index from an html table with a given dom id string.

   The index counts content rows exclusively. Title and prototype rows are excluded."
  [table-id-str idx-to-remove]
  (let [table (dom/get-element table-id-str)
        table-body (gdom/getFirstElementChild table)
        rows (htmlcoll2array (gdom/getChildren table-body))
        idx-to-remove (+ idx-to-remove 2)]
    (doseq [[idx row] (map-indexed vector rows)]
      (let [cells (htmlcoll2array (gdom/getChildren row))
            first-cell (first cells)
            tag-name (. first-cell -tagName)]
        (when (= idx idx-to-remove)
          (let [new-row (. row (cloneNode true))]
            (gdom/insertChildAt table-body new-row (+ idx 2))))))))


(comment
  (copy-table-row "list-of-all-cron-jobs" 1)
  )

(defn html-tab-get-row
  "retreives the html row object from html and row of the given ids"
  [html-table-id row-id]
  (let [table (dom/get-element html-table-id)
        table-body (gdom/getFirstElementChild table)
        rows (htmlcoll2array (gdom/getChildren table-body))
        [row] (filter #(= ( . % -id) row-id) rows)]
    row))


(defn html-tab-insert-cells
  "inserts cell values of the given cells-hash object
   into associated places of provided html row object."
  [row cells-hash]
  (let [cells (htmlcoll2array (gdom/getChildren row))]
    (dorun
     (map
      (fn [cell]
        (let [this-cell-id (. cell -id)
              data (cells-hash this-cell-id)]
          (when data
            (if (string? data)
              (set! (. cell -innerHTML) data)
              (do
                (gdom/removeChildren cell)
                (gdom/insertChildAt cell data 0))))))
      cells))))


(comment
  (def table (dom/get-element "list-of-all-build-processes"))
  (def table-body (gdom/getFirstElementChild table))
  (def rows (htmlcoll2array (gdom/getChildren table-body)))

  (def prototype (html-tab-get-row "list-of-all-build-processes" "prototype-row"))
  (def new-row (. prototype (cloneNode true)))
  (set! (. new-row -id) "build-id-xyz")
  (html-tab-insert-cells new-row {"id" "my-id" "version" "my-version" "details" "my-details"})
  (gdom/insertChildAt table-body new-row 3)
  (clear-table "list-of-all-build-processes")
  )
