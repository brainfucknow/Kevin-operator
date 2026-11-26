(ns kevincpp.kevincpp-property
  "Properties of kevin-cpp's report readers (lizard CSV, Cobertura from any
  backend, Mull's per-test reports), its CRAP join and its source selection."
  (:require [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [kevincore.crap :as crap]
            [kevincpp.cobertura :as cob]
            [kevincpp.crap :as cppcrap]
            [kevincpp.lizard :as lizard]
            [kevincpp.mull :as mull]
            [kevincpp.source :as source]
            [property-support :refer [defspec]]))

(def trials 200)

(def cpp-file (gen/elements ["src/sign.cpp" "src/a/b.hpp" "lib/Util.cc" "include/x.h"]))

;; lizard

(def awkward-text
  "Names and signatures as C++ prints them: commas, quotes, spaces, colons."
  (gen/fmap str/join (gen/vector (gen/elements ["a" "S" "::" "," "\"" " " "<" ">" "(" ")" "&" "~" "operator"]) 1 8)))

(def lizard-function
  (gen/fmap (fn [[file name complexity line length]]
              {:file file :name name :complexity complexity :line line :last-line (+ line length)})
            (gen/tuple cpp-file awkward-text (gen/choose 1 40) (gen/choose 1 500) (gen/choose 0 60))))

(defn- quoted [text]
  (str "\"" (str/replace text "\"" "\"\"") "\""))

(defn- csv-row [{:keys [file name complexity line last-line]}]
  (str/join "," ["7" complexity "30" "1" (inc (- last-line line))
                 (quoted (str name "@" line "-" last-line "@" file)) (quoted file) (quoted name)
                 (quoted (str name "( int x )")) line last-line]))

(defspec lizard-rows-read-back-whatever-the-name-holds trials
  (prop/for-all [functions (gen/vector lizard-function 0 6)
                 noise (gen/vector (gen/elements ["" "garbage" "NLOC,CCN"]) 0 3)]
    (= functions (lizard/parse-csv (str/join "\n" (concat noise (map csv-row functions)))))))

;; Cobertura

(def root "C:/Work/Repo")

(def reported-path
  "A known file as a backend names it, with the path the resolver must find."
  (gen/bind cpp-file
            (fn [file]
              (let [windows (str/replace file "/" "\\")]
                (gen/elements [[file "C:/Work/Repo" file]
                               [(str "C:\\Work\\Repo\\" windows) "D:\\elsewhere" file]
                               [(str/upper-case windows) "c:\\work\\repo" file]
                               [(str "./" file) "C:/Work/Repo/" file]])))))

(def hits (gen/map (gen/choose 1 60) (gen/choose 0 5) {:max-elements 6}))

(defn- report
  "A one-class Cobertura report, as each backend run writes one source."
  [{:keys [source filename lines]}]
  (str "<coverage><sources><source>" source "</source></sources><packages><package><classes>"
       "<class filename=\"" filename "\"><lines>"
       (str/join (map (fn [[n h]] (str "<line number=\"" n "\" hits=\"" h "\"/>")) lines))
       "</lines></class></classes></package></packages></coverage>"))

(defspec cobertura-lines-land-on-the-known-file-merged-by-the-highest-hit trials
  (prop/for-all [classes (gen/vector (gen/fmap (fn [[[filename source file] lines]]
                                                 {:filename filename :source source :file file :lines lines})
                                               (gen/tuple reported-path hits))
                                     0 6)]
    (let [expected (reduce (fn [result {:keys [file lines]}] (update result file #(merge-with max % lines))) {} classes)
          known (set (map :file classes))
          read-each (map #(cob/file-lines (cob/parse (report %)) root known) classes)]
      (= expected (apply merge-with (partial merge-with max) {} read-each)))))

;; Mull

(def mutant
  (gen/fmap (fn [[file line mutator status]]
              {:file file :line line :mutator mutator :description "" :replacement "" :status status})
            (gen/tuple cpp-file (gen/choose 1 20) (gen/elements ["cxx_add_to_sub" "cxx_eq_to_ne"])
                       (gen/elements ["Killed" "Survived" "NoCoverage" "Timeout"]))))

(def mutant-key (juxt :file :line :mutator :description :replacement))

(defspec a-mutant-survives-the-merge-only-when-every-run-let-it-live trials
  (prop/for-all [reports (gen/vector (gen/vector mutant 0 8) 0 4)]
    (let [merged (mull/merge-mutants reports)
          runs (group-by mutant-key (apply concat reports))
          alive? #(contains? #{"Survived" "NoCoverage"} (:status %))]
      (and (= (count runs) (count merged))
           (every? (fn [m] (= (alive? m) (every? alive? (runs (mutant-key m))))) merged)
           (= (set (map (juxt mutant-key alive?) merged))
              (set (map (juxt mutant-key alive?) (mull/merge-mutants (reverse reports)))))
           (= merged (mull/merge-mutants [merged]))))))

;; CRAP

(defspec crap-is-complexity-when-covered-and-grows-as-coverage-falls trials
  (prop/for-all [function lizard-function
                 lines (gen/vector (gen/tuple (gen/choose 1 600) (gen/choose 0 3)) 0 20)]
    (let [file-lines {(:file function) (into {} lines)}
          [{:keys [coverage] :as scored}] (cppcrap/score [function] file-lines)
          complexity (:complexity function)
          covered (into {} (map (fn [[n _]] [n 1])) lines)
          [full] (cppcrap/score [function] {(:file function) covered})]
      (and (<= 0.0 coverage 1.0)
           (<= complexity (:crap scored) (crap/crap-score complexity 0.0))
           (if (some (fn [[n _]] (<= (:line function) n (:last-line function))) lines)
             (== complexity (:crap full))
             (== (crap/crap-score complexity 0.0) (:crap full)))))))

;; source selection

(def segment (gen/elements ["src" "test" "Tests" "tmp" "lib" "a"]))

(def path
  (gen/fmap (fn [[dirs file]] (str/join "/" (conj dirs file)))
            (gen/tuple (gen/vector segment 0 3) (gen/elements ["x.cpp" "y.HPP" "z.c" "w.txt" "v.cs"]))))

(defspec measuring-tests-only-ever-adds-sources trials
  (prop/for-all [path path]
    (let [without (source/measured? path false)
          with (source/measured? path true)]
      (and (or (not without) with)
           (= with (and (source/source? path) (not (source/scratch-path? path))))
           (not (and (source/scratch-path? path) with))))))

(defspec crap-scope-keeps-only-measured-candidates-in-order trials
  (prop/for-all [candidates (gen/vector path 0 8)
                 reported (gen/vector path 0 4)]
    (let [file-lines (into {} (map #(vector % {1 1})) reported)
          scope (cppcrap/scope candidates file-lines)]
      (and (= scope (filter (set scope) candidates))
           (every? #(source/measured? % true) scope)
           (every? #(or (source/measured? % false) (contains? file-lines %)) scope)))))
