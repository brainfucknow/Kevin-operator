(ns kevincpp.cli-test
  "kevin-cpp commands run in-process against a scratch git project, with
  CMake, ctest, MSBuild, vswhere, the coverage backends, lizard and Mull
  replaced by scripted fakes that record their command lines."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kevincore.shell :as shell]
            [kevincpp.cli :as cli]))

(def ^:dynamic *project* nil)
(def ^:dynamic *calls* nil)

(defn- git [& args]
  (apply p/shell {:dir *project* :out :string :err :string} "git" args))

(defn- project-file [path]
  (str (fs/path *project* path)))

(defn- write! [path content]
  (fs/create-dirs (fs/parent (project-file path)))
  (spit (project-file path) content))

(defn- commit! [message]
  (git "add" ".")
  (git "commit" "--quiet" "-m" message))

(def sign-source "#include \"sign.hpp\"\n\nint sign(int value) {\n  if (value > 0) {\n    return 1;\n  } else if (value < 0) {\n    return -1;\n  }\n  return 0;\n}\n")

(defn- scratch-project [f]
  (let [dir (str (fs/canonicalize (fs/create-temp-dir {:prefix "kevincpp-cli"})))]
    (try
      (binding [*project* dir *calls* (atom [])]
        (git "init" "--quiet" "--initial-branch=main")
        (git "config" "user.email" "test@example.com")
        (git "config" "user.name" "Test")
        (git "config" "commit.gpgsign" "false")
        (write! ".gitignore" "tmp/\n")
        (write! "CMakeLists.txt" "project(sign CXX)\n")
        (write! "src/sign.cpp" sign-source)
        (write! "test/sign_test.cpp" "int main() { return 0; }\n")
        (commit! "base")
        (git "switch" "--quiet" "-c" "swarm/task")
        (f))
      (finally (fs/delete-tree dir)))))

(use-fixtures :each scratch-project)

(def ctest-list
  (json/generate-string {"kind" "ctestInfo" "tests" [{"name" "sign positive" "command" ["/b/sign_test" "positive"]}]}))

(defn- cmake-tool
  "Answers like a working CMake project with one test."
  [line]
  (cond
    (str/starts-with? line "cmake --version") {:exit 0 :out "cmake version 3.28.1\n"}
    (str/includes? line "--show-only=json-v1") {:exit 0 :out ctest-list}
    :else 0))

(defn- run-kevin-cpp
  "Runs `argv` in the scratch project; `tool` answers each command line.
  `host` overrides Windows detection and which programs are on PATH."
  [{:keys [tool windows? which] :or {which {}}} & argv]
  (reset! *calls* [])
  (let [answer (fn [command args env]
                 (let [line (str/join " " (concat (map (fn [[k v]] (str k "=" v)) env) (cons command args)))
                       answered (tool line)]
                   (swap! *calls* conj line)
                   (if (map? answered) answered {:exit answered :out ""})))]
    (binding [shell/*dir* *project*]
      (with-redefs [shell/run (fn [command args & [env]] (:exit (answer command args env)))
                    shell/capture (fn [command args] (answer command args nil))
                    shell/available? (fn [command] (contains? which command))
                    fs/which (fn [command & _] (get which command))
                    cli/windows? (constantly (boolean windows?))]
        (let [out (java.io.StringWriter.)
              status (binding [*out* out] (cli/main (vec argv)))]
          {:status status :out (str out) :calls @*calls*})))))

(defn- refusal [host & argv]
  (try (apply run-kevin-cpp host argv)
       nil
       (catch Exception e (ex-message e))))

(deftest help-and-unknown-commands
  (is (str/starts-with? (:out (run-kevin-cpp {:tool cmake-tool})) "usage: kevin-cpp"))
  (is (str/starts-with? (:out (run-kevin-cpp {:tool cmake-tool} "crap" "--help")) "usage: kevin-cpp"))
  (is (str/includes? (refusal {:tool cmake-tool} "frobnicate") "unknown command 'frobnicate'")))

;; test

(deftest test-configures-builds-lists-and-runs-ctest-under-tmp
  (let [{:keys [status calls]} (run-kevin-cpp {:tool cmake-tool} "test" "--" "-R" "sign")]
    (is (= 0 status))
    (is (= ["cmake --version"
            "cmake -S . -B tmp/build/test"
            "cmake --build tmp/build/test --config Debug"
            "ctest --test-dir tmp/build/test -C Debug --show-only=json-v1"
            "ctest --test-dir tmp/build/test -C Debug --output-on-failure -R sign"]
           calls))))

(deftest test-configures-from-a-preset-into-tmp
  (is (= "cmake --preset dev -B tmp/build/test"
         (second (:calls (run-kevin-cpp {:tool cmake-tool} "test" "--preset" "dev"))))))

(deftest build-and-test-failures-are-findings
  (testing "configure"
    (is (= 1 (:status (run-kevin-cpp {:tool #(if (str/starts-with? % "cmake -S") 1 (cmake-tool %))} "test")))))
  (testing "a compile error"
    (let [{:keys [status calls]} (run-kevin-cpp {:tool #(if (str/includes? % "--build") 2 (cmake-tool %))} "test")]
      (is (= 1 status))
      (is (not-any? #(str/starts-with? % "ctest") calls))))
  (testing "a failing test"
    (is (= 1 (:status (run-kevin-cpp {:tool #(if (str/includes? % "--output-on-failure") 8 (cmake-tool %))} "test"))))))

(deftest test-refusals
  (is (str/includes? (refusal {:tool #(if (str/includes? % "show-only") {:exit 0 :out "{\"tests\":[]}"} (cmake-tool %))} "test")
                     "no test found"))
  (is (str/includes? (refusal {:tool #(if (str/starts-with? % "cmake --version") {:exit 0 :out "cmake version 3.16.3"} 0)} "test")
                     "cmake 3.16.3 is older than 3.21"))
  (fs/delete (project-file "CMakeLists.txt"))
  (is (str/includes? (refusal {:tool cmake-tool} "test") "no CMakeLists.txt, .sln, .slnx or .vcxproj"))
  (write! "App.sln" "")
  (is (str/includes? (refusal {:tool cmake-tool} "test") "MSBuild is Windows only")))

(deftest property-is-unsupported
  (is (str/includes? (refusal {:tool cmake-tool} "property") "property tests are not supported yet")))

;; MSBuild on Windows

(def vs-json "[{\"installationVersion\":\"17.10.35004.147\",\"installationPath\":\"C:\\\\VS\"}]")

(def solution-contents
  (str "<SolutionConfiguration>"
       "<ProjectConfiguration Project=\"{1}\" AbsolutePath=\"C:\\s\\Lib.vcxproj\" BuildProjectInSolution=\"True\">Debug|x64</ProjectConfiguration>"
       "<ProjectConfiguration Project=\"{2}\" AbsolutePath=\"C:\\s\\Tests.vcxproj\" BuildProjectInSolution=\"True\">Debug|x64</ProjectConfiguration>"
       "</SolutionConfiguration>"))


(def test-evaluation
  "{\"Properties\":{\"ProjectSubType\":\"NativeUnitTestProject\",\"TargetPath\":\"C:\\\\s\\\\x64\\\\Debug\\\\Tests.dll\"}}")

(defn- msbuild-tool [line]
  (cond
    (str/includes? line "-format json") {:exit 0 :out vs-json}
    (str/includes? line "-find MSBuild") {:exit 0 :out "C:\\VS\\MSBuild\\Current\\Bin\\MSBuild.exe\r\n"}
    (str/includes? line "-find **\\TestPlatform") {:exit 0 :out "C:\\VS\\vstest.console.exe\n"}
    (str/includes? line "-getProperty:KevinProjects") {:exit 0 :out solution-contents}
    (str/includes? line "Tests.vcxproj -nologo") {:exit 0 :out test-evaluation}
    (str/includes? line "Lib.vcxproj -nologo") {:exit 0 :out "{\"Properties\":{\"ProjectSubType\":\"\",\"TargetPath\":\"C:\\\\s\\\\Lib.lib\"}}"}
    :else 0))

(defn- solution-project! []
  (fs/delete (project-file "CMakeLists.txt"))
  (write! "App.sln" ""))

(def windows {:windows? true :which {"vswhere" "C:\\vswhere.exe"}})

(deftest msbuild-runs-vstest-on-the-native-test-outputs
  (solution-project!)
  (let [{:keys [status calls]} (run-kevin-cpp (assoc windows :tool msbuild-tool) "test" "--" "/Tests:Sign")
        msbuild "C:\\VS\\MSBuild\\Current\\Bin\\MSBuild.exe"]
    (is (= 0 status))
    (is (some #{(str msbuild " App.sln -nologo -restore -p:Configuration=Debug -t:Build")} calls))
    (is (some #{(str msbuild " tmp/msbuild/build.proj -nologo -p:Configuration=Debug -t:Projects -getProperty:KevinProjects")} calls))
    (is (str/includes? (slurp (project-file "tmp/msbuild/build.proj"))
                       (str "<MSBuild Projects=\"" *project* "/App.sln\" Targets=\"GetSolutionConfigurationContents\">")))
    (is (some #{(str msbuild " C:\\s\\Tests.vcxproj -nologo -p:Configuration=Debug -p:Platform=x64 -p:SolutionDir=" *project* "/ "
                     "-getProperty:ProjectSubType -getProperty:TargetPath")} calls))
    (is (= "C:\\VS\\vstest.console.exe C:\\s\\x64\\Debug\\Tests.dll /Tests:Sign" (last calls)))))

(deftest msbuild-evaluates-a-lone-project-as-built
  (fs/delete (project-file "CMakeLists.txt"))
  (write! "Tests.vcxproj" "")
  (let [{:keys [status calls]} (run-kevin-cpp (assoc windows :tool msbuild-tool) "test")]
    (is (= 0 status))
    (is (not-any? #(str/includes? % "KevinProjects") calls))
    (is (some #{"C:\\VS\\MSBuild\\Current\\Bin\\MSBuild.exe Tests.vcxproj -nologo -p:Configuration=Debug -getProperty:ProjectSubType -getProperty:TargetPath"} calls))))

(deftest msbuild-refusals
  (solution-project!)
  (is (str/includes? (refusal (assoc windows :tool #(if (str/includes? % "Tests.vcxproj -nologo") {:exit 0 :out "{}"} (msbuild-tool %))) "test")
                     "no test found: App.sln builds no NativeUnitTestProject (CppUnitTestFramework)"))
  (is (str/includes? (refusal (assoc windows :tool #(if (str/includes? % "-getProperty:KevinProjects") {:exit 1 :out "error MSB4057: no target\n"} (msbuild-tool %))) "test")
                     "MSBuild listed no project in App.sln: error MSB4057: no target"))
  (is (str/includes? (refusal (assoc windows :tool #(if (str/includes? % "-format json") {:exit 0 :out "[{\"installationVersion\":\"16.11.1\"}]"} (msbuild-tool %))) "test")
                     "Visual Studio 16.11.1 is older than 2022"))
  (let [{:keys [status calls]} (run-kevin-cpp (assoc windows :tool #(if (str/includes? % "-t:Build") 1 (msbuild-tool %))) "test")]
    (is (= 1 status))
    (is (not-any? #(str/includes? % "vstest") (rest (drop-while #(not (str/includes? % "-t:Build")) calls))))))

(deftest cmake-on-windows-needs-visual-studio-2022
  (is (str/includes? (refusal {:windows? true :which {} :tool cmake-tool} "test") "vswhere"))
  (is (= 0 (:status (run-kevin-cpp (assoc windows :tool #(if (str/includes? % "-format json") (msbuild-tool %) (cmake-tool %))) "test")))))

;; coverage

(def cobertura
  (str "<coverage><sources><source>" "SOURCE" "</source></sources><packages><package name=\"src\"><classes>"
       "<class name=\"sign_cpp\" filename=\"src/sign.cpp\"><lines>"
       "<line number=\"3\" hits=\"1\"/><line number=\"4\" hits=\"1\"/><line number=\"5\" hits=\"1\"/>"
       "<line number=\"6\" hits=\"0\"/><line number=\"7\" hits=\"0\"/><line number=\"9\" hits=\"0\"/>"
       "</lines></class></classes></package></packages></coverage>"))

(defn- writes-report [line]
  (when (or (str/includes? line "gcovr") (str/includes? line "coverage.cobertura.xml"))
    (write! "tmp/coverage/coverage.cobertura.xml" (str/replace cobertura "SOURCE" *project*)))
  (cmake-tool line))

(deftest gcov-coverage-builds-instrumented-runs-ctest-then-gcovr
  (write! "tmp/coverage/stale.xml" "")
  (let [{:keys [status out calls]} (run-kevin-cpp {:tool writes-report} "coverage")]
    (is (= 0 status))
    (is (some #{(str "cmake -S . -B tmp/build/coverage -DCMAKE_BUILD_TYPE=Debug -DCMAKE_C_FLAGS=--coverage -DCMAKE_CXX_FLAGS=--coverage "
                     "-DCMAKE_EXE_LINKER_FLAGS=--coverage -DCMAKE_SHARED_LINKER_FLAGS=--coverage")} calls))
    (is (some #{"ctest --test-dir tmp/build/coverage -C Debug --output-on-failure"} calls))
    (is (some #{(str "uvx gcovr@8.4 --root " *project* " --exclude tmp/ --exclude (.+/)?tests?/ --cobertura "
                     *project* "/tmp/coverage/coverage.cobertura.xml " *project* "/tmp/build/coverage")} calls))
    (is (str/includes? out "coverage written to tmp/coverage/coverage.cobertura.xml"))
    (is (not (fs/exists? (project-file "tmp/coverage/stale.xml"))))))

(deftest gcov-reads-clang-data-with-llvm-cov
  (let [tool #(cond (str/starts-with? % "cmake -N -LA") {:exit 0 :out "CMAKE_CXX_COMPILER:FILEPATH=/usr/bin/clang++\n"}
                    (str/starts-with? % "/usr/bin/clang++ --version") {:exit 0 :out "clang version 18.1.3\n"}
                    :else (writes-report %))
        {:keys [calls]} (run-kevin-cpp {:tool tool :which {"llvm-cov-18" "/usr/bin/llvm-cov-18"}} "coverage" "--include-tests")
        gcovr (first (filter #(str/includes? % "gcovr") calls))]
    (is (str/includes? gcovr "--gcov-executable llvm-cov-18 gcov"))
    (is (not (str/includes? gcovr "tests?")))))

(deftest a-failing-coverage-run-keeps-the-report-and-reports-the-failure
  (is (= 1 (:status (run-kevin-cpp {:tool #(if (str/includes? % "--output-on-failure") 8 (writes-report %))} "coverage"))))
  (is (fs/exists? (project-file "tmp/coverage/coverage.cobertura.xml")))
  (testing "a compile error is a finding even though gcovr finds no data"
    (let [tool #(cond (str/includes? % "--build") 2 (str/includes? % "gcovr") 1 :else (cmake-tool %))]
      (is (= 1 (:status (run-kevin-cpp {:tool tool} "coverage")))))))

(deftest opencppcoverage-wraps-vstest-for-a-solution
  (solution-project!)
  (let [tool #(if (str/starts-with? % "OpenCppCoverage") (writes-report %) (msbuild-tool %))
        {:keys [status calls]} (run-kevin-cpp (-> windows (assoc :tool tool) (assoc-in [:which "OpenCppCoverage"] "C:\\occ.exe"))
                                              "coverage" "--tool" "opencppcoverage")]
    (is (= 0 status))
    (is (str/starts-with? (last calls) "OpenCppCoverage --modules "))
    (is (str/includes? (last calls) "--cover_children --export_type cobertura:"))
    (is (str/ends-with? (last calls) "-- C:\\VS\\vstest.console.exe C:\\s\\x64\\Debug\\Tests.dll")))
  (is (str/includes? (refusal (assoc windows :tool msbuild-tool) "coverage" "--tool" "opencppcoverage")
                     "OpenCppCoverage is not on PATH"))
  (is (str/includes? (refusal (assoc windows :tool msbuild-tool) "coverage" "--tool" "gcov") "gcov needs a CMake project")))

(deftest opencppcoverage-is-the-windows-default
  (let [tool #(cond (str/includes? % "-format json") (msbuild-tool %)
                    (str/starts-with? % "OpenCppCoverage") (writes-report %)
                    :else (cmake-tool %))
        {:keys [status calls]} (run-kevin-cpp (-> windows (assoc :tool tool) (assoc-in [:which "OpenCppCoverage"] "C:\\occ.exe"))
                                              "coverage")]
    (is (= 0 status))
    (is (str/ends-with? (last calls) "-- ctest --test-dir tmp/build/coverage -C Debug --output-on-failure"))))

(deftest coverage-backends-off-windows-are-refused-before-any-run
  (is (str/includes? (refusal {:tool cmake-tool} "coverage" "--tool" "opencppcoverage") "Windows only"))
  (is (str/includes? (refusal {:tool cmake-tool} "coverage" "--tool" "dotnet-coverage") "unknown --tool 'dotnet-coverage'"))
  (is (= [] @*calls*)))

;; crap

(def lizard-csv "8,3,34,1,8,\"sign@3-10@src/sign.cpp\",\"src/sign.cpp\",\"sign\",\"sign( int value)\",3,10\n")

(defn- lizard [line]
  (if (str/includes? line "lizard") {:exit 0 :out lizard-csv} 0))

(defn- with-report! []
  (write! "tmp/coverage/coverage.cobertura.xml" (str/replace cobertura "SOURCE" *project*)))

(defn- change-sign! []
  (write! "src/sign.cpp" (str/replace sign-source "return 0;" "return 0;  // zero"))
  (commit! "change sign"))

(deftest crap-scores-changed-functions-from-lizard-and-the-report
  (with-report!)
  (change-sign!)
  (let [{:keys [status out calls]} (run-kevin-cpp {:tool lizard} "crap")]
    (is (= 0 status))
    (is (= ["uvx lizard@1.17.31 --csv src/sign.cpp"] calls))
    (is (= ["ok   crap    4.1  cc   3  cov  50%  src/sign.cpp:3 sign" "1 changed function(s), 0 over CRAP 10"]
           (str/split-lines out)))))

(deftest crap-flags-functions-over-the-limit
  (with-report!)
  (let [{:keys [status out]} (run-kevin-cpp {:tool #(if (str/includes? % "lizard") {:exit 0 :out (str/replace lizard-csv "8,3," "8,9,")} 0)}
                                            "crap" "--all")]
    (is (= 1 status))
    (is (str/starts-with? out "OVER crap   19.1  cc   9"))))

(deftest crap-reports-unmeasured-sources-unless-declared
  (with-report!)
  (write! "src/sign.hpp" "int sign(int value);\n")
  (commit! "header")
  (let [{:keys [status out]} (run-kevin-cpp {:tool lizard} "crap" "--all")]
    (is (= 2 status))
    (is (str/includes? out "UNMEASURED source: src/sign.hpp")))
  (let [{:keys [status out]} (run-kevin-cpp {:tool lizard} "crap" "--all" "--no-executable-code" "src/sign.hpp")]
    (is (= 0 status))
    (is (str/includes? out "EXEMPT no executable code: src/sign.hpp")))
  (is (str/includes? (refusal {:tool lizard} "crap" "--all" "--no-executable-code" "test/sign_test.cpp")
                     "expected an existing in-scope source file")))

(deftest crap-discovers-mixed-case-sources-and-scopes-them-to-the-diff
  (with-report!)
  (write! "src/Foo.CPP" "int foo;\n")
  (write! "src/Stable.Hh" "int stable;\n")
  (commit! "mixed-case sources")
  (git "branch" "mixed-base")
  (write! "src/Foo.CPP" "int foo = 1;\n")
  (write! "src/Header.HpP" "int bar;\n")
  (write! "src/Other.Cs" "class Other {}\n")
  (let [all (:out (run-kevin-cpp {:tool lizard} "crap" "--all"))
        changed (:out (run-kevin-cpp {:tool lizard} "crap" "--base" "mixed-base"))]
    (doseq [path ["src/Foo.CPP" "src/Header.HpP"]]
      (is (str/includes? all (str "UNMEASURED source: " path)))
      (is (str/includes? changed (str "UNMEASURED source: " path))))
    (is (str/includes? all "UNMEASURED source: src/Stable.Hh"))
    (is (not (str/includes? changed "src/Stable.Hh")))
    (is (not (str/includes? all "src/Other.Cs")))))

(deftest crap-needs-a-report-and-a-working-lizard
  (is (str/includes? (refusal {:tool lizard} "crap") "run kevin-cpp coverage first"))
  (with-report!)
  (is (str/includes? (refusal {:tool (constantly {:exit 1 :out ""})} "crap" "--all") "lizard exited 1")))

;; dry

(def jscpd-report
  (json/generate-string {"duplicates" [{"tokens" 80 "lines" 6
                                        "firstFile" {"name" "src/sign.cpp" "start" 3 "end" 9}
                                        "secondFile" {"name" "src/copy.hpp" "start" 1 "end" 7}}]}))

(defn- jscpd [line]
  (when (str/includes? line "jscpd")
    (write! "tmp/jscpd/jscpd-report.json" jscpd-report))
  0)

(deftest dry-runs-jscpd-on-every-c-and-cpp-extension
  (let [{:keys [status out calls]} (run-kevin-cpp {:tool jscpd} "dry" "--all")]
    (is (= 1 status))
    (is (= [(str "npx --yes jscpd@4 --format cpp --formats-exts cpp:c,cc,cpp,cxx,c++,h,hh,hpp,hxx,ixx,inl --min-tokens 50 "
                 "--reporters json --output tmp/jscpd --gitignore --ignore **/tmp/**,**/node_modules/**,**/.git/** .")]
           calls))
    (is (str/includes? out "80 tokens  src/sign.cpp:3-9  ==  src/copy.hpp:1-7"))))

(deftest dry-lists-only-clones-touching-changed-lines
  (is (= 0 (:status (run-kevin-cpp {:tool jscpd} "dry"))))
  (change-sign!)
  (is (= 1 (:status (run-kevin-cpp {:tool jscpd} "dry"))))
  (is (str/includes? (refusal {:tool (constantly 2)} "dry") "jscpd exited 2 without tmp/jscpd/jscpd-report.json")))

;; mutate

(defn- mull-host []
  (let [prefix (str (fs/path *project* "mull"))]
    (fs/create-dirs (fs/path prefix "lib"))
    (spit (str (fs/path prefix "lib" "mull-ir-frontend-18")) "")
    {:which {"clang" "/usr/bin/clang" "clang++" "/usr/bin/clang++" "ctest" "/usr/bin/ctest"
             "mull-runner-18" (str prefix "/bin/mull-runner-18")}
     :prefix prefix}))

(deftest mutation-runs-only-enabled-tests-in-ctest
  (let [{:keys [which]} (mull-host)
        tests [{:name "disabled" :command ["/b/disabled"] :properties [{:name "DISABLED" :value true}]}
               {:name "sign[positive]" :command ["/b/sign_test" "positive"]}]
        tool #(cond
                (str/starts-with? % "clang --version") {:exit 0 :out "clang version 18.1.3"}
                (str/includes? % "--show-only=json-v1") {:exit 0 :out (json/generate-string {:tests tests})}
                :else (cmake-tool %))
        {:keys [calls]} (run-kevin-cpp {:tool tool :which which} "mutate" "--all")
        runs (filter #(str/includes? % "/bin/mull-runner-18 ") calls)]
    (is (= 1 (count runs)))
    (is (some #(str/includes? % "--test-program /usr/bin/ctest /b/sign_test -- --test-dir tmp/build/mutate -C Debug --output-on-failure --no-tests=error -I 2,2") runs))))

(def elements
  {"schemaVersion" "1"
   "files" {"/r/src/sign.cpp" {"mutants" [{"mutatorName" "cxx_gt_to_ge" "status" "Survived" "location" {"start" {"line" 4}}}
                                          {"mutatorName" "cxx_lt_to_le" "status" "Killed" "location" {"start" {"line" 6}}}]}}})

(defn- mull-tool [survived?]
  (fn [line]
    (cond
      (str/starts-with? line "clang --version") {:exit 0 :out "Ubuntu clang version 18.1.3\n"}
      (str/includes? line "mull-runner-18")
      (do (write! "tmp/mull/sign_positive.json"
                  (json/generate-string (cond-> elements (not survived?) (assoc-in ["files" "/r/src/sign.cpp" "mutants" 0 "status"] "Killed"))))
          1)
      :else (cmake-tool line))))

(deftest mutate-builds-with-the-mull-plugin-and-runs-each-test
  (let [{:keys [which prefix]} (mull-host)
        {:keys [status out calls]} (run-kevin-cpp {:tool (mull-tool true) :which which} "mutate")
        config (str *project* "/tmp/mull/mull.yml")]
    (is (= 1 status))
    (is (some #(and (str/starts-with? % (str "MULL_CONFIG=" config " cmake -S . -B tmp/build/mutate"))
                    (str/includes? % "-DCMAKE_CXX_COMPILER=/usr/bin/clang++")
                    (str/includes? % (str "-DCMAKE_CXX_FLAGS=-fpass-plugin=" prefix "/lib/mull-ir-frontend-18 -g -grecord-command-line")))
              calls))
    (is (some #{(str "MULL_CONFIG=" config " " prefix "/bin/mull-runner-18 --reporters IDE --reporters Elements "
                     "--report-dir tmp/mull --report-name sign_positive --test-program /usr/bin/ctest /b/sign_test "
                     "-- --test-dir tmp/build/mutate -C Debug --output-on-failure --no-tests=error -I 1,1")} calls))
    (is (str/includes? (slurp config) "gitDiffRef: "))
    (is (str/includes? out "Survived   /r/src/sign.cpp:4  cxx_gt_to_ge"))
    (is (str/includes? out "mutation score 50.0%")))
  (let [{:keys [which]} (mull-host)]
    (is (= 0 (:status (run-kevin-cpp {:tool (mull-tool false) :which which} "mutate" "--all"))))
    (is (not (str/includes? (slurp (project-file "tmp/mull/mull.yml")) "gitDiffRef")))))

(deftest mutate-without-a-mutant-in-the-diff-scores-none
  (let [{:keys [which]} (mull-host)
        tool #(cond (str/starts-with? % "clang --version") {:exit 0 :out "clang version 18.1.3\n"}
                    (str/includes? % "mull-runner-18") 0
                    :else (cmake-tool %))
        {:keys [status out]} (run-kevin-cpp {:tool tool :which which} "mutate")]
    (is (= 0 status))
    (is (str/includes? out "no mutants")))
  (let [{:keys [which]} (mull-host)
        tool #(cond (str/starts-with? % "clang --version") {:exit 0 :out "clang version 18.1.3\n"}
                    (str/includes? % "mull-runner-18") 3
                    :else (cmake-tool %))]
    (is (str/includes? (refusal {:tool tool :which which} "mutate") "mull-runner exited 3 and wrote no tmp/mull/sign_positive.json"))))

(deftest mutate-is-unsupported-without-clang-and-mull
  (is (str/includes? (refusal {:tool cmake-tool} "mutate") "mutation unsupported in this setup: no clang on PATH"))
  (is (str/includes? (refusal {:tool (mull-tool true) :which {"clang" "/usr/bin/clang"}} "mutate") "no mull-runner-18 on PATH"))
  (is (str/includes? (refusal (assoc windows :tool msbuild-tool) "mutate") "mutation unsupported in this setup: MSVC"))
  (is (not (fs/exists? (project-file "tmp/mull")))))

;; tools

(deftest tools-reports-each-tool-and-fails-only-without-a-build-system
  (let [{:keys [status out]} (run-kevin-cpp {:tool #(if (str/includes? % "jscpd") {:exit 1 :out ""} (cmake-tool %))} "tools")]
    (is (= 0 status))
    (is (str/includes? out "build system: cmake"))
    (is (str/includes? out "cmake: cmake version 3.28.1"))
    (is (str/includes? out "jscpd: not available (exit 1)"))
    (is (str/includes? out "mull: unsupported: no clang on PATH")))
  (fs/delete (project-file "CMakeLists.txt"))
  (let [{:keys [status out]} (run-kevin-cpp {:tool cmake-tool} "tools")]
    (is (= 2 status))
    (is (str/includes? out "build system: none")))
  (write! "App.sln" "")
  (let [{:keys [status out]} (run-kevin-cpp (assoc windows :tool msbuild-tool) "tools")]
    (is (= 0 status))
    (is (str/includes? out "build system: msbuild App.sln"))
    (is (str/includes? out "visual studio: "))
    (is (str/includes? out "OpenCppCoverage: not available (not on PATH)"))
    (is (not (str/includes? out "dotnet")))))
