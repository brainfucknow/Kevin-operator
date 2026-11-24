(ns kevincpp.backends-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kevincpp.coverage :as coverage]
            [kevincpp.mull :as mull]))

(defn- refusal [f]
  (try (f) nil (catch Exception e (ex-message e))))

(deftest the-default-backend-depends-on-the-host
  (is (= "opencppcoverage" (coverage/backend nil {:windows? true :kind :cmake})))
  (is (= "gcov" (coverage/backend nil {:windows? false :kind :cmake})))
  (is (= "opencppcoverage" (coverage/backend "opencppcoverage" {:windows? true :kind :msbuild})))
  (is (re-find #"unknown --tool 'dotnet-coverage'; use opencppcoverage or gcov"
               (refusal #(coverage/backend "dotnet-coverage" {:windows? true :kind :cmake}))))
  (is (re-find #"OpenCppCoverage runs on Windows only"
               (refusal #(coverage/backend "opencppcoverage" {:windows? false :kind :cmake}))))
  (is (re-find #"gcov needs a CMake project" (refusal #(coverage/backend "gcov" {:windows? true :kind :msbuild}))))
  (is (re-find #"unknown --tool 'bullseye'" (refusal #(coverage/backend "bullseye" {:windows? false :kind :cmake})))))

(deftest gcov-builds-with-coverage-flags-in-debug
  (is (= {"CMAKE_BUILD_TYPE" "Debug" "CMAKE_C_FLAGS" "--coverage" "CMAKE_CXX_FLAGS" "--coverage"
          "CMAKE_EXE_LINKER_FLAGS" "--coverage" "CMAKE_SHARED_LINKER_FLAGS" "--coverage"}
         coverage/gcov-defines)))

(deftest gcovr-writes-cobertura-for-the-repository-without-tests-or-tmp
  (is (= ["gcovr@8.4" "--root" "/repo" "--exclude" "tmp/" "--exclude" "(.+/)?tests?/"
          "--cobertura" "/repo/tmp/coverage/coverage.cobertura.xml" "/repo/tmp/build/coverage"]
         (coverage/gcovr-args {:root "/repo" :out "/repo/tmp/coverage/coverage.cobertura.xml"
                               :build-dir "/repo/tmp/build/coverage"})))
  (testing "--include-tests keeps test directories; clang reads its data with llvm-cov gcov"
    (let [args (coverage/gcovr-args {:root "/r" :out "o" :build-dir "b" :include-tests? true :gcov "llvm-cov-18 gcov"})]
      (is (not (some #{"(.+/)?tests?/"} args)))
      (is (= ["--gcov-executable" "llvm-cov-18 gcov"] (take 2 (drop-while #(not= "--gcov-executable" %) args)))))))

(deftest opencppcoverage-covers-children-and-the-repository-only
  (is (= ["--modules" "C:\\repo" "--sources" "C:\\repo" "--excluded_sources" "C:\\repo\\tmp" "--excluded_sources" "\\test\\"
          "--excluded_sources" "\\tests\\" "--cover_children" "--export_type" "cobertura:C:\\repo\\tmp\\coverage\\coverage.cobertura.xml"
          "--" "ctest" "-C" "Debug"]
         (coverage/opencppcoverage-args {:root "C:/repo" :out "C:/repo/tmp/coverage/coverage.cobertura.xml"
                                         :command ["ctest" "-C" "Debug"]})))
  (is (not (some #{"\\test\\"} (coverage/opencppcoverage-args {:root "C:/r" :out "o" :command ["x"] :include-tests? true})))))

(deftest mull-runs-only-with-clang-a-matching-runner-and-cmake
  (is (nil? (mull/unsupported {:kind :cmake :windows? false :clang 18 :runner "/usr/bin/mull-runner-18" :plugin? true})))
  (is (re-find #"MSBuild" (mull/unsupported {:kind :msbuild :windows? true})))
  (is (re-find #"MSVC" (mull/unsupported {:kind :cmake :windows? true})))
  (is (re-find #"no clang on PATH" (mull/unsupported {:kind :cmake :windows? false})))
  (is (re-find #"no mull-runner-18 on PATH" (mull/unsupported {:kind :cmake :windows? false :clang 18})))
  (is (re-find #"no mull-ir-frontend-18" (mull/unsupported {:kind :cmake :windows? false :clang 18 :runner "/x/bin/mull-runner-18"}))))

(deftest mull-builds-with-its-frontend-plugin
  (is (= "/usr/lib/mull-ir-frontend-18" (mull/plugin-path "/usr/bin/mull-runner-18" 18)))
  (let [flags "-fpass-plugin=/usr/lib/mull-ir-frontend-18 -g -grecord-command-line"]
    (is (= {"CMAKE_BUILD_TYPE" "Debug" "CMAKE_C_COMPILER" "/usr/bin/clang-18" "CMAKE_CXX_COMPILER" "/usr/bin/clang++-18"
            "CMAKE_C_FLAGS" flags "CMAKE_CXX_FLAGS" flags}
           (mull/defines {:cc "/usr/bin/clang-18" :cxx "/usr/bin/clang++-18" :plugin "/usr/lib/mull-ir-frontend-18"})))))

(deftest mull-config-limits-mutants-to-the-diff-and-the-repository
  (let [config (mull/config {:root "/repo" :diff-ref "abc123"})]
    (is (str/includes? config "gitDiffRef: abc123\n"))
    (is (str/includes? config "gitProjectRoot: /repo\n"))
    (is (str/includes? config "  - '^/repo/.*'\n"))
    (is (str/includes? config "  - '^/repo/tmp/.*'\n"))
    (is (str/includes? config "  - '.*/tests?/.*'\n")))
  (is (not (str/includes? (mull/config {:root "/repo"}) "gitDiffRef"))))

(deftest mull-runner-reports-in-ide-and-elements-formats
  (is (= ["--reporters" "IDE" "--reporters" "Elements" "--report-dir" "tmp/mull" "--report-name" "sign_test" "/b/sign_test" "pos"]
         (mull/runner-args {:report-dir "tmp/mull" :name "sign_test" :command ["/b/sign_test" "pos"] :extra []})))
  (is (= "tmp/mull/sign_test.json" (mull/report-path "tmp/mull" "sign_test"))))

(deftest a-mutant-any-test-kills-is-killed
  (let [survived {:file "/r/a.cpp" :line 3 :mutator "cxx_add_to_sub" :description "+ to -" :replacement "" :status "Survived"}
        other (assoc survived :line 9)]
    (is (= [(assoc survived :status "Killed") other]
           (mull/merge-mutants [[survived other] [(assoc survived :status "Killed") other]])))))
