(ns kevincpp.mull
  "When Mull can mutate, how kevin-cpp builds for it, and how mull-runner
  reports. Mull needs clang and the mull-runner of the same LLVM major."
  (:require [clojure.string :as str]))

(defn unsupported
  "Why this setup cannot run Mull, or nil."
  [{:keys [kind windows? clang runner plugin?]}]
  (cond
    (= :msbuild kind) "MSBuild projects build with MSVC, which Mull cannot instrument"
    windows? "MSVC builds cannot be instrumented by Mull, which needs clang on Linux or macOS"
    (nil? clang) "no clang on PATH; Mull instruments clang builds only"
    (nil? runner) (str "no mull-runner-" clang " on PATH; install the Mull build for clang " clang)
    (not plugin?) (str "no mull-ir-frontend-" clang " beside mull-runner-" clang)))

(defn plugin-path
  "Mull's IR frontend, installed in lib/ beside the runner's bin/."
  [runner major]
  (let [prefix (-> runner (str/replace #"[\\/][^\\/]+$" "") (str/replace #"[\\/][^\\/]+$" ""))]
    (str prefix "/lib/mull-ir-frontend-" major)))

(defn defines
  "CMake cache entries for a clang build that Mull's plugin instruments."
  [{:keys [cc cxx plugin]}]
  (let [flags (str "-fpass-plugin=" plugin " -g -grecord-command-line")]
    {"CMAKE_BUILD_TYPE" "Debug" "CMAKE_C_COMPILER" cc "CMAKE_CXX_COMPILER" cxx
     "CMAKE_C_FLAGS" flags "CMAKE_CXX_FLAGS" flags}))

(defn- regex-literal [path]
  (str/replace path #"[\\*+?|{}\[\]()^$.]" #(str "\\" %)))

(defn config
  "mull.yml for MULL_CONFIG: mutants in the repository's sources outside tmp/
  and test directories, limited to the diff against `diff-ref` when given."
  [{:keys [root diff-ref]}]
  (let [root-regex (regex-literal root)]
    (str (when diff-ref (str "gitDiffRef: " diff-ref "\ngitProjectRoot: " root "\n"))
         "includePaths:\n  - '^" root-regex "/.*'\n"
         "excludePaths:\n  - '^" root-regex "/tmp/.*'\n  - '.*/tests?/.*'\n")))

(defn runner-args
  "Inspect the instrumented executable and let CTest run its test."
  [{:keys [report-dir name ctest executable test-args extra]}]
  (-> ["--reporters" "IDE" "--reporters" "Elements" "--report-dir" report-dir "--report-name" name]
      (into extra)
      (into ["--test-program" ctest executable "--"])
      (into test-args)))

(defn report-path [report-dir name]
  (str report-dir "/" name ".json"))

(defn- mutant-key [mutant]
  ((juxt :file :line :mutator :description :replacement) mutant))

(defn merge-mutants
  "One entry per mutant across the reports of several test runs: a mutant
  any run killed counts as killed."
  [reports]
  (->> (apply concat reports)
       (group-by mutant-key)
       vals
       (map (fn [runs] (or (first (remove #(#{"Survived" "NoCoverage"} (:status %)) runs)) (first runs))))
       (sort-by (juxt :file :line))
       vec))
