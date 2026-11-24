(ns kevincpp.build
  "Which build system a directory uses, what kevin-cpp asks CMake, ctest,
  MSBuild and vswhere, and how it reads their answers."
  (:require [cheshire.core :as json]
            [clojure.data.xml :as xml]
            [clojure.string :as str]
            [kevincore.outcome :as outcome]))

(def config
  "The configuration every build and test run uses."
  "Debug")

(def minimum-cmake [3 21])
(def minimum-visual-studio 17)

(defn- extension? [ext file]
  (str/ends-with? (str/lower-case file) ext))

(defn- solutions [files]
  (sort (filter #(or (extension? ".sln" %) (extension? ".slnx" %)) files)))

(defn- msbuild-project
  "The one solution, else the one .vcxproj, among `files`."
  [files]
  (let [found (solutions files)
        projects (sort (filter (partial extension? ".vcxproj") files))]
    (cond
      (< 1 (count found)) (throw (outcome/usage-error (str "several solutions: " (str/join ", " found) "; keep one in this directory")))
      (seq found) (first found)
      (< 1 (count projects)) (throw (outcome/usage-error (str "several projects and no solution: " (str/join ", " projects))))
      :else (first projects))))

(defn- nothing-to-build []
  (outcome/usage-error "no CMakeLists.txt, .sln, .slnx or .vcxproj in this directory; run kevin-cpp from the project root"))

(defn- msbuild [files windows?]
  (let [project (msbuild-project files)]
    (cond
      (nil? project) (throw (nothing-to-build))
      (not windows?) (throw (outcome/usage-error (str "MSBuild is Windows only; " project " cannot build on this host")))
      :else {:kind :msbuild :project project})))

(defn select-system
  "The build system of a directory holding `files`: CMake when it has a
  CMakeLists.txt, unless `requested` is \"msbuild\"; else MSBuild on its one
  solution or project."
  [{:keys [files windows? requested]}]
  (let [cmake? (some #{"CMakeLists.txt"} files)]
    (case requested
      nil (if cmake? {:kind :cmake} (msbuild files windows?))
      "cmake" (if cmake? {:kind :cmake} (throw (outcome/usage-error "--build-system cmake: no CMakeLists.txt in this directory")))
      "msbuild" (if windows?
                  (or (some->> (msbuild-project files) (hash-map :kind :msbuild :project))
                      (throw (outcome/usage-error "--build-system msbuild: no .sln, .slnx or .vcxproj in this directory")))
                  (throw (outcome/usage-error "--build-system msbuild: MSBuild is Windows only")))
      (throw (outcome/usage-error (str "unknown --build-system '" requested "'; use cmake or msbuild"))))))

(defn- version-numbers [text]
  (mapv parse-long (str/split text #"\.")))

(defn- older? [version minimum]
  (neg? (compare (vec (take (count minimum) (concat version (repeat 0)))) minimum)))

(defn cmake-refusal
  "Why the captured `cmake --version` cannot serve, or nil."
  [{:keys [exit out]}]
  (let [version (second (re-find #"cmake version (\d+(?:\.\d+)+)" (or out "")))]
    (cond
      (not (zero? exit)) (str "cmake --version exited " exit "; put cmake " (str/join "." minimum-cmake) " or later on PATH")
      (nil? version) "cmake --version printed no version"
      (older? (version-numbers version) minimum-cmake)
      (str "cmake " version " is older than " (str/join "." minimum-cmake) "; put a newer cmake on PATH"))))

(defn visual-studio
  "{:version :path} of the Visual Studio that `vswhere -format json` reports;
  stops when there is none or it is older than 2022."
  [text]
  (let [parsed (try (json/parse-string text) (catch Exception _ nil))
        entry (when (sequential? parsed) (first parsed))
        version (get entry "installationVersion")]
    (when-not (string? version)
      (throw (outcome/usage-error "vswhere found no Visual Studio with MSBuild; install Visual Studio 2022 or later with the C++ workload")))
    (when (< (first (version-numbers (re-find #"^\d+" version))) minimum-visual-studio)
      (throw (outcome/usage-error (str "Visual Studio " version " is older than 2022 (" minimum-visual-studio ")"))))
    {:version version :path (get entry "installationPath")}))

(def vswhere-args
  ["-latest" "-products" "*" "-requires" "Microsoft.Component.MSBuild" "-format" "json"])

(defn vswhere-find-args [pattern]
  ["-latest" "-products" "*" "-requires" "Microsoft.Component.MSBuild" "-find" pattern])

(defn configure-args
  "`cmake` arguments that configure into `build-dir`, from `preset` when given."
  [{:keys [preset build-dir defines]}]
  (-> (if preset ["--preset" preset] ["-S" "."])
      (into ["-B" build-dir])
      (into (map (fn [[k v]] (str "-D" k "=" v)) defines))))

(defn build-args [build-dir]
  ["--build" build-dir "--config" config])

(defn list-tests-args [build-dir]
  ["--test-dir" build-dir "-C" config "--show-only=json-v1"])

(defn ctest-args [build-dir extra]
  (into ["--test-dir" build-dir "-C" config "--output-on-failure"] extra))

(defn ctest-tests
  "The tests `ctest --show-only=json-v1` lists, each {:name :command}."
  [text]
  (let [parsed (try (json/parse-string text) (catch Exception _ nil))]
    (when-not (map? parsed)
      (throw (outcome/usage-error "ctest listed no tests: its --show-only=json-v1 output is not JSON")))
    (mapv (fn [test] {:name (get test "name") :command (vec (get test "command"))})
          (get parsed "tests"))))

(defn cached-compiler
  "The C++ compiler, else the C compiler, in `cmake -N -LA` output."
  [text]
  (let [value #(second (re-find (re-pattern (str "(?m)^" % ":[A-Z]+=(.+)$")) text))]
    (some-> (or (value "CMAKE_CXX_COMPILER") (value "CMAKE_C_COMPILER")) str/trim not-empty)))

(defn clang-major
  "The major version of a compiler whose `--version` banner says clang, or nil."
  [banner]
  (some-> (re-find #"clang version (\d+)" banner) second parse-long))

(defn- msbuild-literal
  "`path` as a literal in an MSBuild project file: MSBuild's special
  characters %-escaped, then XML-escaped."
  [path]
  (-> path
      (str/replace #"[%$@';?*]" #(format "%%%02X" (int (first %))))
      (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;") (str/replace "\"" "&quot;")))

(defn solution?
  "Whether `project` is a solution rather than a project file."
  [project]
  (boolean (seq (solutions [project]))))

(defn entry-project
  "An MSBuild project whose Projects target asks the solution at `path` for
  its configuration contents. MSBuild refuses -getTargetResult on a
  solution, but not on this project."
  [path]
  (str "<Project>\n"
       "  <Target Name=\"Projects\" Returns=\"@(KevinProjects)\">\n"
       "    <MSBuild Projects=\"" (msbuild-literal path) "\" Targets=\"GetSolutionConfigurationContents\">\n"
       "      <Output TaskParameter=\"TargetOutputs\" ItemName=\"KevinProjects\" />\n"
       "    </MSBuild>\n"
       "  </Target>\n"
       "</Project>\n"))

(defn msbuild-args
  "MSBuild arguments that restore and build `project`."
  [project]
  [project "-nologo" "-restore" (str "-p:Configuration=" config) "-t:Build"])

(defn projects-args
  "MSBuild arguments that run `entry`, an `entry-project`."
  [entry]
  [entry "-nologo" (str "-p:Configuration=" config) "-getTargetResult:Projects"])

(defn solution-projects
  "The projects a solution builds, each {:path :configuration :platform},
  from MSBuild's -getTargetResult:Projects answer: its items carry the
  solution's SolutionConfigurationContents."
  [text]
  (let [parsed (try (json/parse-string text) (catch Exception _ nil))
        contents (str/join ";" (map #(get % "Identity") (get-in parsed ["TargetResults" "Projects" "Items"])))
        doc (try (xml/parse-str contents) (catch Exception _ nil))]
    (vec (for [node (tree-seq map? :content doc)
               :when (and (map? node) (= :ProjectConfiguration (:tag node))
                          (not= "false" (str/lower-case (get-in node [:attrs :BuildProjectInSolution] "true"))))
               :let [[configuration platform] (str/split (str/join (filter string? (:content node))) #"\|" 2)]]
           {:path (get-in node [:attrs :AbsolutePath]) :configuration configuration :platform platform}))))

(defn evaluate-args
  "MSBuild arguments that print `path`'s ProjectSubType and TargetPath as the
  solution in `solution-dir` builds it."
  [{:keys [path configuration platform solution-dir]}]
  (cond-> [path "-nologo" (str "-p:Configuration=" (or configuration config))]
    platform (conj (str "-p:Platform=" platform))
    solution-dir (conj (str "-p:SolutionDir=" solution-dir))
    :always (into ["-getProperty:ProjectSubType" "-getProperty:TargetPath"])))

(defn test-target
  "The TargetPath of an evaluation whose ProjectSubType is
  NativeUnitTestProject (a CppUnitTestFramework project), else nil."
  [text]
  (let [properties (get (try (json/parse-string text) (catch Exception _ nil)) "Properties")]
    (when (= "NativeUnitTestProject" (some-> (get properties "ProjectSubType") str str/trim))
      (some-> (get properties "TargetPath") str str/trim not-empty))))

(defn status
  "A build or test status as kevin-cpp reports it: any failure is 1."
  [exit]
  (if (zero? exit) outcome/exit-ok outcome/exit-findings))
