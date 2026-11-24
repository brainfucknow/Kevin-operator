(ns kevincpp.coverage
  "The coverage backends: which one runs on this host, and the command lines
  and settings that make each write Cobertura for the repository's sources."
  (:require [clojure.string :as str]
            [kevincore.outcome :as outcome]))

(def backends #{"dotnet-coverage" "opencppcoverage" "gcov"})

(defn backend
  "The backend `requested` names, else the host's default; stops when it
  cannot run here."
  [requested {:keys [windows? kind]}]
  (let [chosen (or requested (if windows? "dotnet-coverage" "gcov"))]
    (cond
      (not (backends chosen))
      (throw (outcome/usage-error (str "unknown --tool '" chosen "'; use dotnet-coverage, opencppcoverage or gcov")))
      (and (= "dotnet-coverage" chosen) (not windows?))
      (throw (outcome/usage-error "--tool dotnet-coverage: dotnet-coverage collects native coverage on Windows only; use --tool gcov"))
      (and (= "opencppcoverage" chosen) (not windows?))
      (throw (outcome/usage-error "--tool opencppcoverage: OpenCppCoverage runs on Windows only; use --tool gcov"))
      (and (= "gcov" chosen) (= :msbuild kind))
      (throw (outcome/usage-error "--tool gcov: gcov needs a CMake project; use dotnet-coverage or opencppcoverage"))
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
  Cobertura to `out` for sources under `root`."
  [{:keys [root out command include-tests?]}]
  (let [root (str/replace (backslashes root) #"\\+$" "")]
    (-> ["--sources" root "--excluded_sources" (str root "\\tmp")]
        (into (when-not include-tests? ["--excluded_sources" "\\test\\" "--excluded_sources" "\\tests\\"]))
        (into ["--cover_children" "--export_type" (str "cobertura:" (backslashes out)) "--"])
        (into command))))

(def ^:private separator "[\\\\/]")

(defn- escape [segment]
  (str/replace segment #"[\\*+?|{}\[\]()^$.# ]" #(str "\\" %)))

(defn- under [root & segments]
  (str "^" (str/join separator (map escape (concat (str/split root #"[\\/]+") segments))) separator ".*"))

(defn dotnet-settings
  "dotnet-coverage settings: dynamic native instrumentation of sources under
  `root`, outside tmp/ and, unless `include-tests?`, test directories."
  [root include-tests?]
  (let [source #(str "\n        <Source>" (-> % (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")) "</Source>")]
    (str "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<Configuration>\n  <CodeCoverage>"
         "\n    <EnableStaticNativeInstrumentation>False</EnableStaticNativeInstrumentation>"
         "\n    <EnableDynamicNativeInstrumentation>True</EnableDynamicNativeInstrumentation>"
         "\n    <Sources>\n      <Include>" (source (under root))
         "\n      </Include>\n      <Exclude>" (source (under root "tmp"))
         (when-not include-tests? (source (str ".*" separator "[Tt]ests?" separator ".*")))
         "\n      </Exclude>\n    </Sources>\n  </CodeCoverage>\n</Configuration>\n")))

(defn dotnet-coverage-args
  "`dotnet` arguments that collect Cobertura into `out` around `command`."
  [{:keys [settings out command]}]
  (into ["dotnet-coverage" "collect" "-s" settings "-f" "cobertura" "-o" out "--"] command))
