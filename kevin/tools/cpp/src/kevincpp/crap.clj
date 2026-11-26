(ns kevincpp.crap
  "CRAP per function: lizard's complexity and line span joined with the
  Cobertura line hits of any coverage backend."
  (:require [kevincore.crap :as crap]
            [kevincpp.source :as source]))

(defn- coverage-of
  "The share of the coverable lines between `line` and `last-line` that ran;
  0 when the report has none there."
  [{:keys [line last-line]} hits]
  (let [coverable (filter (fn [[n _]] (<= line n last-line)) hits)]
    (if (empty? coverable)
      0.0
      (double (/ (count (filter (comp pos? val) coverable)) (count coverable))))))

(defn score
  "Each lizard function with its :coverage and :crap, from `file-lines`
  (repository path to {line hits})."
  [functions file-lines]
  (mapv (fn [function]
          (let [coverage (coverage-of function (get file-lines (:file function)))]
            (assoc function :coverage coverage :crap (crap/crap-score (:complexity function) coverage))))
        functions))

(defn scope
  "The measured sources among `candidates`; a test source joins only when
  the report covers it, which `coverage --include-tests` does."
  [candidates file-lines]
  (vec (filter #(or (source/measured? % false)
                    (and (source/measured? % true) (contains? file-lines %)))
               candidates)))

(defn unmeasured
  "Sources in `scope` with no line data in the report, other than `exempt`."
  [scope file-lines exempt]
  (vec (remove #(or (seq (get file-lines %)) (contains? exempt %)) scope)))
