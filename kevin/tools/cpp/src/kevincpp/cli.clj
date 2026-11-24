(ns kevincpp.cli
  "kevin-cpp: C and C++ tool wrapper for Kevin roles. Builds with CMake or
  MSBuild, tests with ctest or vstest.console, and measures coverage, CRAP,
  duplication and mutation for the task's changes."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kevincore.command :as command]
            [kevincore.crap :as crap]
            [kevincore.git :as git]
            [kevincore.jscpd :as jscpd]
            [kevincore.outcome :as outcome]
            [kevincore.shell :as shell]
            [kevincore.stryker :as stryker]
            [kevincpp.build :as build]
            [kevincpp.cobertura :as cob]
            [kevincpp.coverage :as coverage]
            [kevincpp.crap :as cppcrap]
            [kevincpp.lizard :as lizard]
            [kevincpp.mull :as mull]
            [kevincpp.source :as source]))

(def wrapper "kevin-cpp")
(def coverage-dir "tmp/coverage")
(def coverage-report (str coverage-dir "/coverage.cobertura.xml"))
(def msbuild-entry "tmp/msbuild/build.proj")
(def jscpd-dir "tmp/jscpd")
(def mull-dir "tmp/mull")
(def build-dirs {:test "tmp/build/test" :coverage "tmp/build/coverage" :mutate "tmp/build/mutate"})

(def usage
  (str "usage: kevin-cpp <command> [options]

  tools                       report the build system and every tool kevin-cpp can use
  test [--preset NAME] [-- args]
                              build, then ctest (CMake) or vstest.console (MSBuild)
  property                    unsupported; exit 2
  coverage [--tool opencppcoverage|gcov] [--include-tests] [--preset NAME] [-- args]
                              tests under the backend; Cobertura in " coverage-report "
                              (default opencppcoverage on Windows, gcov elsewhere)
  crap [--all] [--base REF] [--no-executable-code PATH ...]
                              CRAP per changed function: lizard complexity and the
                              coverage report; exit 1 over " crap/crap-limit ", exit 2 on unmeasured sources
  dry [--all] [--base REF] [--min-tokens N] [--report-only]
                              jscpd clones touching changed lines; exit 1 if any
  mutate [--base REF] [--all] [-- mull args]
                              Mull on the diff since the merge base (clang, CMake);
                              exit 1 on survivors, exit 2 in any other setup

Every command takes --build-system cmake|msbuild and runs from the project
directory. --base defaults to KEVIN_BASE, main, or master. Reports and build
trees go under tmp/."))

(def option-spec
  {:coerce {:all :boolean :report-only :boolean :help :boolean :include-tests :boolean
            :min-tokens :long :base :string :preset :string :tool :string :build-system :string
            :no-executable-code [:string]}
   :alias {:h :help}})

(defn windows? []
  (str/starts-with? (System/getProperty "os.name") "Windows"))

(defn- directory-files []
  (map #(str (fs/file-name %)) (fs/list-dir (shell/in-dir "."))))

(defn- try-capture
  "Captures `command`; a command that cannot start answers exit 127."
  [command args]
  (try (shell/capture command args)
       (catch Exception _ {:exit 127 :out ""})))

(defn- first-line [text]
  (first (remove str/blank? (str/split-lines (or text "")))))

(defn- repo-root []
  (str/replace (git/repo-root) "\\" "/"))

(defn- absolute [path]
  (str/replace (str (fs/absolutize (shell/in-dir path))) "\\" "/"))

;; Visual Studio

(defn- vswhere []
  (or (some-> (fs/which "vswhere") str)
      (let [installed (str (fs/path (or (System/getenv "ProgramFiles(x86)") "C:/Program Files (x86)")
                                    "Microsoft Visual Studio" "Installer" "vswhere.exe"))]
        (when (fs/exists? installed) installed))
      (throw (outcome/usage-error "vswhere not found; install Visual Studio 2022 or later"))))

(defn- visual-studio []
  (let [{:keys [exit out]} (try-capture (vswhere) build/vswhere-args)]
    (when-not (zero? exit)
      (throw (outcome/usage-error (str "vswhere exited " exit))))
    (build/visual-studio out)))

(defn- vs-find [pattern what]
  (or (first-line (:out (try-capture (vswhere) (build/vswhere-find-args pattern))))
      (throw (outcome/usage-error (str "vswhere found no " what)))))

;; build system

(defn- select-system [opts]
  (let [system (build/select-system {:files (directory-files) :windows? (windows?) :requested (:build-system opts)})]
    (when (windows?) (visual-studio))
    system))

(defn- require-cmake []
  (when-let [refusal (build/cmake-refusal (try-capture "cmake" ["--version"]))]
    (throw (outcome/usage-error refusal))))

(defn- cmake-build
  "Configures and builds into `build-dir`; returns the failing status, or nil."
  [{:keys [preset build-dir defines env]}]
  (let [configure (shell/run "cmake" (build/configure-args {:preset preset :build-dir build-dir :defines defines}) env)]
    (if (zero? configure)
      (let [built (shell/run "cmake" (build/build-args build-dir) env)]
        (when-not (zero? built) (build/status built)))
      (build/status configure))))

(defn- ctest-tests [build-dir]
  (let [{:keys [exit out]} (shell/capture "ctest" (build/list-tests-args build-dir))
        tests (when (zero? exit) (build/ctest-tests out))]
    (when (empty? tests)
      (throw (outcome/usage-error (str "no test found: ctest lists no test in " build-dir "; add tests with add_test"))))
    tests))

(defn- run-command
  "Runs [command & args] through `wrap`, which may put a coverage backend around it."
  [wrap command]
  (let [[program & args] (wrap command)]
    (build/status (shell/run program (vec args)))))

(defn- cmake-tests
  "Builds into `build-dir` and runs ctest; returns the status."
  [{:keys [build-dir extra wrap] :as run}]
  (require-cmake)
  (or (cmake-build run)
      (do (ctest-tests build-dir)
          (run-command wrap (into ["ctest"] (build/ctest-args build-dir extra))))))

(defn- msbuild-test-targets
  "The CppUnitTestFramework outputs MSBuild built for `project`."
  [msbuild project outputs]
  (vec (for [[built targets] (group-by :project outputs)
             :when (build/native-test-project? (:out (shell/capture msbuild (build/evaluate-args built))))
             target targets]
         (:target target))))

(defn- msbuild-tests
  "Builds the solution or project with MSBuild and runs vstest.console on
  its native test outputs; returns the status."
  [{:keys [project extra wrap]}]
  (let [msbuild (vs-find "MSBuild\\**\\Bin\\MSBuild.exe" "MSBuild.exe")
        vstest (vs-find "**\\TestPlatform\\vstest.console.exe" "vstest.console.exe")
        {:keys [exit out]} (do (shell/write-file msbuild-entry (build/entry-project (absolute project)))
                               (shell/capture msbuild (build/msbuild-args msbuild-entry)))]
    (if-not (zero? exit)
      (do (println out) (build/status exit))
      (let [targets (msbuild-test-targets msbuild project (build/build-outputs out project))]
        (when (empty? targets)
          (throw (outcome/usage-error (str "no test found: " project " builds no NativeUnitTestProject (CppUnitTestFramework)"))))
        (run-command wrap (-> [vstest] (into targets) (into extra)))))))

(defn- run-tests [system run]
  (if (= :msbuild (:kind system))
    (msbuild-tests (assoc run :project (:project system)))
    (cmake-tests run)))

(defn- cmd-test [{:keys [opts extra]}]
  (run-tests (select-system opts) {:preset (:preset opts) :build-dir (:test build-dirs) :extra extra :wrap identity}))

(defn- cmd-property [_]
  (throw (outcome/usage-error "property tests are not supported yet for C and C++")))

;; coverage

(defn- opencppcoverage-run [{:keys [root include-tests?]}]
  (when-not (shell/available? "OpenCppCoverage")
    (throw (outcome/usage-error "--tool opencppcoverage: OpenCppCoverage is not on PATH")))
  {:wrap #(into ["OpenCppCoverage"] (coverage/opencppcoverage-args {:root root :out (absolute coverage-report)
                                                                    :command % :include-tests? include-tests?}))})

(defn- gcov-executable
  "llvm-cov gcov when CMake chose clang for `build-dir`, else nil for gcov."
  [build-dir]
  (let [compiler (build/cached-compiler (:out (try-capture "cmake" ["-N" "-LA" build-dir])))
        major (some-> compiler (try-capture ["--version"]) :out build/clang-major)]
    (when major
      (if (shell/available? (str "llvm-cov-" major)) (str "llvm-cov-" major " gcov") "llvm-cov gcov"))))

(defn- run-gcovr [{:keys [root include-tests?]}]
  (let [build-dir (absolute (:coverage build-dirs))
        status (binding [shell/*dir* root]
                 (shell/run "uvx" (coverage/gcovr-args {:root root :out (absolute coverage-report) :build-dir build-dir
                                                        :include-tests? include-tests? :gcov (gcov-executable build-dir)})))]
    (when-not (zero? status)
      (throw (outcome/usage-error (str "gcovr exited " status " without a report"))))))

(defn- require-report [backend]
  (when-not (shell/exists? coverage-report)
    (throw (outcome/usage-error (str backend " wrote no " coverage-report))))
  (when-not (cob/packages? (cob/parse (slurp (shell/in-dir coverage-report))))
    (throw (outcome/usage-error (str backend " collected no coverage data: " coverage-report " has no package"))))
  (println "coverage written to" coverage-report))

(defn- cmd-coverage [{:keys [opts extra]}]
  (let [system (select-system opts)
        backend (coverage/backend (:tool opts) {:windows? (windows?) :kind (:kind system)})
        context {:root (repo-root) :include-tests? (boolean (:include-tests opts))}
        base {:preset (:preset opts) :build-dir (:coverage build-dirs) :extra extra :wrap identity}]
    (fs/delete-tree (shell/in-dir coverage-dir))
    (fs/create-dirs (shell/in-dir coverage-dir))
    (let [status (case backend
                   "gcov" (cmake-tests (assoc base :defines coverage/gcov-defines))
                   "opencppcoverage" (run-tests system (merge base (opencppcoverage-run context))))]
      (try
        (when (= "gcov" backend) (run-gcovr context))
        (require-report backend)
        (catch Exception e
          (if (zero? status) (throw e) (shell/warn wrapper (ex-message e)))))
      status)))

;; crap

(defn- known-sources
  "Every C or C++ source git tracks or would add, repo-relative."
  [root]
  (filter #(fs/regular-file? (fs/path root %))
          (remove str/blank? (str/split (git/git (into ["ls-files" "-z" "--cached" "--others" "--exclude-standard"
                                                         "--full-name" "--"] source/pathspecs) :dir root)
                                        #"\u0000"))))

(defn- exemption [root scope declared]
  (let [file (fs/normalize (fs/absolutize (shell/in-dir declared)))
        relative (str/replace (str (fs/relativize (fs/real-path root) (fs/real-path file))) "\\" "/")]
    (when-not (and (fs/regular-file? file) (contains? (set scope) relative))
      (throw (outcome/usage-error (str "--no-executable-code " declared ": expected an existing in-scope source file"))))
    relative))

(defn- lizard-functions [root files]
  (if (empty? files)
    []
    (let [{:keys [exit out]} (binding [shell/*dir* root] (shell/capture "uvx" (lizard/args files)))]
      (when-not (zero? exit)
        (throw (outcome/usage-error (str "lizard exited " exit))))
      (lizard/parse-csv out))))

(defn- cmd-crap [{:keys [opts]}]
  (when-not (shell/exists? coverage-report)
    (throw (outcome/usage-error (str "no " coverage-report "; run kevin-cpp coverage first"))))
  (let [root (repo-root)
        known (vec (known-sources root))
        changed (git/changed-since-base opts source/pathspecs)
        file-lines (cob/file-lines (cob/parse (slurp (shell/in-dir coverage-report))) root (set known))
        scope (cppcrap/scope (if changed (filter (set known) (keys changed)) known) file-lines)
        exempt (into (sorted-set) (map (partial exemption root scope)) (:no-executable-code opts))
        missing (cppcrap/unmeasured scope file-lines exempt)
        scored (cppcrap/score (lizard-functions root (remove exempt scope)) file-lines)]
    (doseq [file exempt] (println "EXEMPT no executable code:" file))
    (doseq [file missing] (println "UNMEASURED source:" file))
    (if (seq missing)
      outcome/exit-usage
      (shell/emit (crap/review crap/crap-limit (crap/select scored changed) crap/format-row "function" changed)))))

;; dry

(defn- jscpd-args [min-tokens]
  ["--yes" "jscpd@4" "--format" "cpp" "--formats-exts" (str "cpp:" (str/join "," source/extensions))
   "--min-tokens" (str min-tokens) "--reporters" "json" "--output" jscpd-dir
   "--gitignore" "--ignore" "**/tmp/**,**/node_modules/**,**/.git/**" "."])

(defn- cmd-dry [{:keys [opts]}]
  (let [report (str jscpd-dir "/jscpd-report.json")]
    (when-not (:report-only opts)
      (fs/create-dirs (shell/in-dir jscpd-dir))
      (fs/delete-if-exists (shell/in-dir report))
      (let [status (shell/run "npx" (jscpd-args (or (:min-tokens opts) 50)))]
        (when-not (shell/exists? report)
          (throw (outcome/usage-error (str "jscpd exited " status " without " report))))))
    (when-not (shell/exists? report)
      (throw (outcome/usage-error (str report " not found"))))
    (let [changed (git/changed-since-base opts source/pathspecs)]
      (shell/emit (jscpd/review (jscpd/parse-clones (shell/read-json report)) changed (when changed (repo-root)))))))

;; mutate

(defn- mull-setup [system]
  (let [clang (when (shell/available? "clang") (build/clang-major (:out (try-capture "clang" ["--version"]))))
        runner (when clang (some-> (fs/which (str "mull-runner-" clang)) str))
        plugin (when runner (mull/plugin-path runner clang))]
    {:kind (:kind system) :windows? (windows?) :clang clang :runner runner
     :plugin plugin :plugin? (boolean (and plugin (fs/exists? plugin)))}))

(defn- test-name [test]
  (str/replace (:name test) #"[^A-Za-z0-9_.-]" "_"))

(defn- run-mull [{:keys [runner]} env extra test]
  (let [name (test-name test)
        report (mull/report-path mull-dir name)]
    (shell/run runner (mull/runner-args {:report-dir mull-dir :name name :command (:command test) :extra extra}) env)
    (when-not (shell/exists? report)
      (throw (outcome/usage-error (str "mull-runner wrote no " report))))
    (stryker/parse-mutants (shell/read-json report))))

(defn- cmd-mutate [{:keys [opts extra]}]
  (let [setup (mull-setup (select-system opts))]
    (when-let [reason (mull/unsupported setup)]
      (throw (outcome/usage-error (str "mutation unsupported in this setup: " reason))))
    (require-cmake)
    (let [root (repo-root)
          diff-ref (when-not (:all opts) (str/trim (git/git ["merge-base" (git/base-ref opts) "HEAD"])))
          config (str mull-dir "/mull.yml")
          env {"MULL_CONFIG" (absolute config)}
          build-dir (:mutate build-dirs)]
      (fs/delete-tree (shell/in-dir mull-dir))
      (shell/write-file config (mull/config {:root root :diff-ref diff-ref}))
      (or (cmake-build {:build-dir build-dir :env env
                        :defines (mull/defines {:cc (str (fs/which "clang")) :cxx (str (fs/which "clang++"))
                                                :plugin (:plugin setup)})})
          (let [mutants (mull/merge-mutants (mapv (partial run-mull setup env extra) (ctest-tests build-dir)))]
            (shell/emit (stryker/review mutants)))))))

;; tools

(defn- report-tool [label command args]
  (let [{:keys [exit out]} (try-capture command args)]
    (println (str label ": " (if (zero? exit) (or (first-line out) "available") (str "not available (exit " exit ")"))))))

(defn- report-system [opts]
  (try
    (let [system (select-system opts)]
      (println "build system:" (if (= :msbuild (:kind system)) (str "msbuild " (:project system)) "cmake"))
      system)
    (catch Exception e
      (println "build system: none (" (ex-message e) ")")
      nil)))

(defn- report-windows-tools []
  (report-tool "visual studio" (or (some-> (fs/which "vswhere") str) "vswhere") (into build/vswhere-args ["-property" "installationVersion"]))
  (println "OpenCppCoverage:" (if (shell/available? "OpenCppCoverage") "available" "not available (not on PATH)")))

(defn- report-unix-tools []
  (report-tool "compiler" "c++" ["--version"])
  (report-tool "gcov" "gcov" ["--version"])
  (report-tool "gcovr" "uvx" [coverage/gcovr "--version"]))

(defn- cmd-tools [{:keys [opts]}]
  (let [system (report-system opts)]
    (when (= :cmake (:kind system)) (report-tool "cmake" "cmake" ["--version"]))
    (if (windows?) (report-windows-tools) (report-unix-tools))
    (report-tool "uv" "uv" ["--version"])
    (report-tool "npx" "npx" ["--version"])
    (report-tool "lizard" "uvx" (into (subvec lizard/command 1) ["--version"]))
    (report-tool "jscpd" "npx" ["--yes" "jscpd@4" "--version"])
    (println "mull:" (if system (or (some->> (mull/unsupported (mull-setup system)) (str "unsupported: ")) "available") "unsupported"))
    (cond
      (nil? system) outcome/exit-usage
      (and (= :cmake (:kind system)) (build/cmake-refusal (try-capture "cmake" ["--version"]))) outcome/exit-usage
      :else outcome/exit-ok)))

(def ^:private commands
  {"tools" cmd-tools "test" cmd-test "property" cmd-property "coverage" cmd-coverage
   "crap" cmd-crap "dry" cmd-dry "mutate" cmd-mutate})

(defn main [argv]
  (command/main {:usage usage :commands commands :option-spec option-spec :help-option? true} argv))

(defn -main [& argv]
  (shell/run-main wrapper main argv))
