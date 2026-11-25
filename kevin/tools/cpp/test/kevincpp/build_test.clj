(ns kevincpp.build-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kevincpp.build :as build]))

(defn- refusal [f]
  (try (f) nil (catch Exception e (ex-message e))))

(deftest cmakelists-selects-cmake-and-wins-over-a-solution
  (is (= {:kind :cmake} (build/select-system {:files ["CMakeLists.txt" "a.sln"] :windows? true})))
  (is (= {:kind :cmake} (build/select-system {:files ["CMakeLists.txt"] :windows? false}))))

(deftest one-solution-or-project-selects-msbuild-on-windows
  (is (= {:kind :msbuild :project "App.sln"} (build/select-system {:files ["App.sln" "x.vcxproj"] :windows? true})))
  (is (= {:kind :msbuild :project "App.slnx"} (build/select-system {:files ["App.slnx"] :windows? true})))
  (is (= {:kind :msbuild :project "x.vcxproj"} (build/select-system {:files ["x.vcxproj" "x.vcxproj.filters"] :windows? true})))
  (is (= {:kind :msbuild :project "a.sln"}
         (build/select-system {:files ["CMakeLists.txt" "a.sln"] :windows? true :requested "msbuild"}))))

(deftest build-system-refusals
  (testing "nothing to build"
    (is (re-find #"no CMakeLists.txt, \.sln, \.slnx or \.vcxproj" (refusal #(build/select-system {:files ["README.md"] :windows? false})))))
  (testing "several solutions"
    (is (re-find #"several solutions: a.sln, b.slnx" (refusal #(build/select-system {:files ["b.slnx" "a.sln"] :windows? true})))))
  (testing "several projects and no solution"
    (is (re-find #"several projects" (refusal #(build/select-system {:files ["a.vcxproj" "b.vcxproj"] :windows? true})))))
  (testing "MSBuild off Windows"
    (is (re-find #"MSBuild is Windows only" (refusal #(build/select-system {:files ["a.sln"] :windows? false}))))
    (is (re-find #"MSBuild is Windows only"
                 (refusal #(build/select-system {:files ["CMakeLists.txt" "a.sln"] :windows? false :requested "msbuild"})))))
  (testing "asking for MSBuild where the directory has none"
    (is (re-find #"--build-system msbuild: no \.sln" (refusal #(build/select-system {:files ["CMakeLists.txt"] :windows? true :requested "msbuild"}))))
    (is (re-find #"--build-system msbuild: MSBuild is Windows only" (refusal #(build/select-system {:files ["CMakeLists.txt"] :windows? false :requested "msbuild"})))))
  (testing "asking for a system the directory lacks or that does not exist"
    (is (re-find #"no CMakeLists.txt" (refusal #(build/select-system {:files ["a.sln"] :windows? true :requested "cmake"}))))
    (is (re-find #"unknown --build-system 'make'" (refusal #(build/select-system {:files ["CMakeLists.txt"] :requested "make"}))))))

(deftest cmake-needs-3-21
  (is (nil? (build/cmake-refusal {:exit 0 :out "cmake version 3.21.0\n\nCMake suite maintained"})))
  (is (nil? (build/cmake-refusal {:exit 0 :out "cmake version 4.1.2\n"})))
  (is (re-find #"cmake 3.20.6 is older than 3.21" (build/cmake-refusal {:exit 0 :out "cmake version 3.20.6\n"})))
  (is (re-find #"cmake --version exited 127" (build/cmake-refusal {:exit 127 :out ""})))
  (is (re-find #"no version" (build/cmake-refusal {:exit 0 :out "what\n"}))))

(deftest visual-studio-needs-version-17
  (is (= {:version "17.8.34330.188" :path "C:\\VS"}
         (build/visual-studio "[{\"installationVersion\":\"17.8.34330.188\",\"installationPath\":\"C:\\\\VS\"}]")))
  (is (re-find #"Visual Studio 16.11.5 is older than 2022 \(17\)"
               (refusal #(build/visual-studio "[{\"installationVersion\":\"16.11.5\",\"installationPath\":\"C:\\\\VS\"}]"))))
  (is (re-find #"no Visual Studio with MSBuild" (refusal #(build/visual-studio "[]"))))
  (is (re-find #"no Visual Studio with MSBuild" (refusal #(build/visual-studio "not json")))))

(deftest cmake-command-lines
  (is (= ["-S" "." "-B" "tmp/build/test"] (build/configure-args {:build-dir "tmp/build/test"})))
  (is (= ["--preset" "dev" "-B" "tmp/build/test" "-DCMAKE_BUILD_TYPE=Debug"]
         (build/configure-args {:preset "dev" :build-dir "tmp/build/test" :defines {"CMAKE_BUILD_TYPE" "Debug"}})))
  (is (= ["--build" "tmp/build/test" "--config" "Debug"] (build/build-args "tmp/build/test")))
  (is (= ["--test-dir" "tmp/build/test" "-C" "Debug" "--output-on-failure" "-R" "x"]
         (build/ctest-args "tmp/build/test" ["-R" "x"])))
  (is (= ["--test-dir" "d" "-C" "Debug" "--show-only=json-v1"] (build/list-tests-args "d"))))

(deftest ctest-lists-tests-with-their-executables
  (is (= [{:index 1 :name "sign_positive" :executable "/b/sign_test" :disabled? false}]
         (build/ctest-tests "{\"kind\":\"ctestInfo\",\"tests\":[{\"name\":\"sign_positive\",\"command\":[\"/b/sign_test\",\"pos\"]}]}")))
  (is (= [] (build/ctest-tests "{\"kind\":\"ctestInfo\",\"tests\":[]}")))
  (is (re-find #"ctest listed no tests" (refusal #(build/ctest-tests "nope")))))

(deftest the-compiler-comes-from-the-cmake-cache
  (is (= "/usr/bin/clang++" (build/cached-compiler "CMAKE_AR:FILEPATH=/usr/bin/ar\nCMAKE_CXX_COMPILER:FILEPATH=/usr/bin/clang++\n")))
  (is (= "/usr/bin/cc" (build/cached-compiler "CMAKE_C_COMPILER:FILEPATH=/usr/bin/cc\n")))
  (is (nil? (build/cached-compiler "nothing"))))

(deftest clang-is-told-from-its-version-banner
  (is (= 18 (build/clang-major "Ubuntu clang version 18.1.3 (1ubuntu1)\nTarget: x86_64")))
  (is (= 17 (build/clang-major "Apple clang version 17.0.0 (clang-1700.0.13.3)")))
  (is (nil? (build/clang-major "g++ (Ubuntu 11.4.0-1ubuntu1~22.04.3) 11.4.0"))))

(deftest msbuild-lists-a-solutions-projects-and-their-test-outputs
  (let [contents (str "<SolutionConfiguration>"
                      "<ProjectConfiguration Project=\"{1}\" AbsolutePath=\"C:\\s\\Lib\\Lib.vcxproj\" BuildProjectInSolution=\"True\">Debug|x64</ProjectConfiguration>"
                      "<ProjectConfiguration Project=\"{2}\" AbsolutePath=\"C:\\s\\Tests.vcxproj\" BuildProjectInSolution=\"True\">Debug|Win32</ProjectConfiguration>"
                      "<ProjectConfiguration Project=\"{3}\" AbsolutePath=\"C:\\s\\Off.vcxproj\" BuildProjectInSolution=\"False\">Debug|x64</ProjectConfiguration>"
                      "</SolutionConfiguration>")
        answer (str contents "\r\n")]
    (is (= [{:path "C:\\s\\Lib\\Lib.vcxproj" :configuration "Debug" :platform "x64"}
            {:path "C:\\s\\Tests.vcxproj" :configuration "Debug" :platform "Win32"}]
           (build/solution-projects answer))))
  (is (= [] (build/solution-projects "garbage")))
  (is (= [] (build/solution-projects "")))
  (is (= ["C:/s/T.vcxproj" "-nologo" "-p:Configuration=Debug" "-p:Platform=x64" "-p:SolutionDir=C:/s/"
          "-getProperty:ProjectSubType" "-getProperty:TargetPath"]
         (build/evaluate-args {:path "C:/s/T.vcxproj" :configuration "Debug" :platform "x64" :solution-dir "C:/s/"})))
  (is (= ["T.vcxproj" "-nologo" "-p:Configuration=Debug" "-getProperty:ProjectSubType" "-getProperty:TargetPath"]
         (build/evaluate-args {:path "T.vcxproj"})))
  (is (= "C:\\o\\T.dll" (build/test-target "{\"Properties\":{\"ProjectSubType\":\"NativeUnitTestProject\",\"TargetPath\":\"C:\\\\o\\\\T.dll\"}}")))
  (is (nil? (build/test-target "{\"Properties\":{\"ProjectSubType\":\"\",\"TargetPath\":\"x\"}}")))
  (is (nil? (build/test-target "oops")))
  (is (build/solution? "App.SLN"))
  (is (build/solution? "App.slnx"))
  (is (not (build/solution? "App.vcxproj"))))

(deftest msbuild-builds-a-solution-and-asks-it-for-its-projects
  (let [entry (build/entry-project "C:/My Repo/App.sln")]
    (is (str/includes? entry "<Target Name=\"Projects\">"))
    (is (str/includes? entry "<Output TaskParameter=\"TargetOutputs\" PropertyName=\"KevinProjects\" />"))
    (is (str/includes? entry "<MSBuild Projects=\"C:/My Repo/App.sln\" Targets=\"GetSolutionConfigurationContents\">")))
  (testing "MSBuild and XML special characters stay literal"
    (is (str/includes? (build/entry-project "C:/a&b;c$d@e%f'g/App.sln") "Projects=\"C:/a&amp;b%3Bc%24d%40e%25f%27g/App.sln\"")))
  (is (= ["App.sln" "-nologo" "-restore" "-p:Configuration=Debug" "-t:Build"] (build/msbuild-args "App.sln")))
  (is (= ["tmp/msbuild/build.proj" "-nologo" "-p:Configuration=Debug" "-t:Projects" "-getProperty:KevinProjects"]
         (build/projects-args "tmp/msbuild/build.proj"))))

(deftest any-failing-status-is-a-finding
  (is (= 0 (build/status 0)))
  (is (= 1 (build/status 8)))
  (is (= 1 (build/status 1))))
