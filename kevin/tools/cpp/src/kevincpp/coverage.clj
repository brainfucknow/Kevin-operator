(ns kevincpp.coverage
  "The coverage backends: which one runs on this host, and the command lines
  and settings that make each write Cobertura for the repository's sources."
  (:require [clojure.string :as str]
            [kevincore.outcome :as outcome]))

(def backends #{"opencppcoverage" "gcov"})

(defn backend
  "The backend `requested` names, else the host's default; stops when it
  cannot run here."
  [requested {:keys [windows? kind]}]
  (let [chosen (or requested (if windows? "opencppcoverage" "gcov"))]
    (cond
      (not (backends chosen))
      (throw (outcome/usage-error (str "unknown --tool '" chosen "'; use opencppcoverage or gcov")))
      (and (= "opencppcoverage" chosen) (not windows?))
      (throw (outcome/usage-error "--tool opencppcoverage: OpenCppCoverage runs on Windows only; use --tool gcov"))
      (and (= "gcov" chosen) (= :msbuild kind))
      (throw (outcome/usage-error "--tool gcov: gcov needs a CMake project; use opencppcoverage"))
      :else chosen)))

(def gcov-defines
  "CMake cache entries for a gcov build."
  {"CMAKE_BUILD_TYPE" "Debug"
   "CMAKE_C_FLAGS" "--coverage" "CMAKE_CXX_FLAGS" "--coverage"
   "CMAKE_EXE_LINKER_FLAGS" "--coverage" "CMAKE_SHARED_LINKER_FLAGS" "--coverage"})

(def gcovr "gcovr@8.4")

(def ^:private test-dirs-regex "(.+/)?tests?/")

(defn gcovr-args
  "`uvx` arguments: gcovr reads `build-dir` and writes Cobertura to `out`,
  keeping sources under `root` outside tmp/ and, unless `include-tests?`,
  outside test directories. Run from `root`: the excludes are relative."
  [{:keys [root out build-dir include-tests? gcov]}]
  (-> [gcovr "--root" root "--exclude" "tmp/"]
      (into (when-not include-tests? ["--exclude" test-dirs-regex]))
      (into (when gcov ["--gcov-executable" gcov]))
      (into ["--cobertura" out build-dir])))

(defn- backslashes [path]
  (str/replace path "/" "\\"))

(defn opencppcoverage-args
  "OpenCppCoverage arguments that run `command` and its children and write
  Cobertura to `out` for modules and sources under `root`. Limiting the
  modules keeps OpenCppCoverage off the test runner's own DLLs, such as an
  ARM DiaSymReader that vstest.console loads and OpenCppCoverage cannot read."
  [{:keys [root out command include-tests?]}]
  (let [root (str/replace (backslashes root) #"\\+$" "")]
    (-> ["--modules" root "--sources" root "--excluded_sources" (str root "\\tmp")]
        (into (when-not include-tests? ["--excluded_sources" "\\test\\" "--excluded_sources" "\\tests\\"]))
        (into ["--cover_children" "--export_type" (str "cobertura:" (backslashes out)) "--"])
        (into command))))
