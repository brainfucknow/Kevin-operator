(ns kevinrust.reports
  (:require [clojure.string :as str]
            [kevincore.changes :as changes]
            [kevincore.jscpd :as jscpd]
            [kevincore.outcome :as outcome]))

(defn- invalid-lcov []
  (throw (outcome/usage-error "invalid LCOV record")))

(defn- line-hits [record]
  (into {}
        (keep (fn [line]
                (when (str/starts-with? line "DA:")
                  (let [[_ number hits] (re-matches #"DA:(\d+),(\d+)(?:,.*)?" line)]
                    (if (and number (parse-long number) (parse-long hits))
                      [(parse-long number) (parse-long hits)]
                      (invalid-lcov))))))
        record))

(defn- read-record [root text]
  (let [lines (str/split-lines text)
        sources (filter #(str/starts-with? % "SF:") lines)]
    (when-not (and (= 1 (count sources)) (< 3 (count (first sources))))
      (invalid-lcov))
    {:file (changes/relative-path (subs (first sources) 3) root)
     :hits (line-hits lines)
     :text (str text "end_of_record\n")}))

(defn selected-lcov [root files text]
  (let [parts (str/split text #"(?m)^end_of_record\r?\n?" -1)]
    (when (or (not (str/blank? (last parts)))
              (and (= 1 (count parts)) (not (str/blank? text))))
      (invalid-lcov))
    (into [] (comp (map #(read-record root %)) (filter #(files (:file %)))) (butlast parts))))

(defn changed-coverage [records changed]
  (let [hits-by-file (reduce (fn [hits {:keys [file] :as record}]
                               (update hits file #(merge-with + % (:hits record))))
                             {} records)
        hits (mapcat (fn [[file lines]] (keep (get hits-by-file file {}) lines)) changed)]
    [(count (filter pos? hits)) (count hits)]))

(defn- crap-line [{:strs [function line cyclomatic coverage crap] :keys [path]}]
  (format "%s:%d  %s  complexity %.1f  coverage %.1f%%  CRAP %.2f"
          path line function (double cyclomatic) (double coverage) (double crap)))

(defn crap-review [root files rows]
  (when-not (vector? rows)
    (throw (outcome/usage-error "cargo-crap JSON is missing its entries array")))
  (let [selected (for [{:strs [file] :as row} rows
                       :let [path (changes/relative-path file root)]
                       :when (files path)]
                   (assoc row :path path))
        failures (filter #(> (get % "crap") 10) selected)]
    (outcome/report (conj (mapv crap-line selected)
                          (str (count failures) " of " (count selected) " function(s) over CRAP 10"))
                    (seq failures))))

(defn dry-review [root files changed report]
  (let [clones (jscpd/parse-clones report)
        known-paths (when changed (merge (zipmap files (repeat #{})) changed))]
    (jscpd/review clones known-paths root)))
