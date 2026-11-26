(ns kevincpp.lizard
  "lizard's CSV report: one row per function with its cyclomatic complexity
  and line span."
  (:require [clojure.string :as str]))

(def command
  "The pinned lizard, run through uvx."
  ["uvx" "lizard@1.17.31"])

(defn args [files]
  (into (subvec command 1) (into ["--csv"] files)))

(defn- csv-fields
  "The fields of one CSV line; quoted fields may hold commas and doubled quotes."
  [line]
  (loop [chars (seq line) field (StringBuilder.) fields [] quoted? false]
    (let [[c & more] chars]
      (cond
        (nil? c) (conj fields (str field))
        (and quoted? (= c \") (= (first more) \")) (recur (rest more) (.append field \") fields true)
        (= c \") (recur more field fields (not quoted?))
        (and (not quoted?) (= c \,)) (recur more (StringBuilder.) (conj fields (str field)) false)
        :else (recur more (.append field c) fields quoted?)))))

(defn- function-row [fields]
  (let [[_nloc ccn _tokens _params _length _location file name _long-name start end] fields]
    (when (and end (re-matches #"\d+" ccn) (re-matches #"\d+" start) (re-matches #"\d+" end))
      {:file file :name name :complexity (parse-long ccn)
       :line (parse-long start) :last-line (parse-long end)})))

(defn parse-csv
  "Every function lizard reports in `text`; other lines are skipped."
  [text]
  (vec (keep (comp function-row csv-fields) (remove str/blank? (str/split-lines text)))))
