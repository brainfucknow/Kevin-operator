(ns kevinrust.rust-spec
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [kevincore.git :as git]
            [kevincore.shell :as shell]
            [kevinrust.cli :as cli]
            [kevinrust.options :as options]
            [kevinrust.project :as project]
            [kevinrust.reports :as reports]
            [kevinrust.snapshot :as snapshot]
            [kevinrust.testing :as testing]
            [speclj.core :refer [describe it should-contain should= should-throw]]))

(describe "Rust command options"
  (it "keeps repeatable selectors and libtest filters separate"
    (let [opts (options/parse "test" ["-p" "alpha" "--package=beta" "--locked"
                                      "--features" "beta/extra" "--" "--ignored" "matching"])]
      (should= ["alpha" "beta"] (:packages opts))
      (should= ["--features" "beta/extra"] (:features opts))
      (should= ["--ignored" "matching"] (:libtest opts))
      (should= true (:locked opts))))
  (it "reports an empty inline selector instead of consuming the next argument"
    (should-throw clojure.lang.ExceptionInfo "--package requires a value"
                  (options/parse "test" ["--package=" "alpha"])))
  (it "rejects excludes without workspace selection"
    (should-throw clojure.lang.ExceptionInfo "--exclude requires --workspace"
                  (options/parse "coverage" ["--exclude" "alpha"])))
  (it "rejects options that have no meaning for the command"
    (should-throw clojure.lang.ExceptionInfo "crap does not accept libtest arguments"
                  (options/parse "crap" ["--" "--list"]))))

(def metadata
  {"workspace_members" ["alpha-id" "beta-id"]
   "workspace_default_members" ["beta-id"]
   "packages" [{"id" "alpha-id" "name" "alpha"
                "manifest_path" "/repo/alpha/Cargo.toml"}
               {"id" "beta-id" "name" "beta"
                "manifest_path" "/repo/alpha/beta/Cargo.toml"
                "targets" [{"kind" ["test"] "src_path" "/repo/alpha/beta/src/check.rs"}]}]})

(describe "Cargo selection"
  (it "uses the default member Cargo reports for that manifest"
    (should= ["beta"] (mapv #(get % "name") (project/select-packages metadata {}))))
  (it "subtracts workspace excludes and rejects unknown package names"
    (should= ["alpha"] (mapv #(get % "name")
                             (project/select-packages metadata {:workspace true :excludes ["beta"]})))
    (should-throw clojure.lang.ExceptionInfo "unknown Cargo package: other"
                  (project/select-packages metadata {:packages ["other"]})))
  (it "assigns a nested member's source to that member only"
    (let [files ["alpha/src/lib.rs" "alpha/beta/src/lib.rs" "alpha/beta/src/check.rs"
                 "alpha/beta/tests/helpers.rs" "alpha/beta/target/debug/generated.rs"
                 "alpha/beta/src/space name.rs"]]
      (should= #{"alpha/beta/src/lib.rs" "alpha/beta/src/space name.rs"}
               (project/production-files "/repo" metadata ["beta"] files)))))

(describe "Rust tool reports"
  (it "counts only LCOV lines in selected source which also changed"
    (let [lcov (str "TN:\nSF:/repo/alpha/src/lib.rs\nDA:7,1\nDA:10,0\nend_of_record\n"
                    "SF:/repo/beta/src/lib.rs\nDA:7,2\nend_of_record\n")
          records (reports/selected-lcov "/repo" #{"alpha/src/lib.rs"} lcov)]
      (should= [1 2] (reports/changed-coverage records {"alpha/src/lib.rs" #{7 10 11}
                                                       "beta/src/lib.rs" #{7}}))
      (should= [0 0] (reports/changed-coverage records {"alpha/src/lib.rs" #{11}}))))
  (it "rejects incomplete coverage records"
    (should-throw clojure.lang.ExceptionInfo "invalid LCOV record"
                  (reports/selected-lcov "/repo" #{} "SF:/repo/f.rs\nDA:1,1\n")))
  (it "gates CRAP at above ten only for selected changed files"
    (let [rows [{"file" "/repo/a.rs" "function" "a" "line" 1 "cyclomatic" 4 "coverage" 100 "crap" 10}
                {"file" "/repo/b.rs" "function" "b" "line" 2 "cyclomatic" 4 "coverage" 0 "crap" 20}]]
      (should= 0 (:status (reports/crap-review "/repo" #{"a.rs"} rows)))
      (should= 1 (:status (reports/crap-review "/repo" #{"b.rs"} rows))))))

(describe "Libtest argument policy"
  (it "puts the property skip before any user separator"
    (should= ["--" "--skip" "property_" "--ignored" "--" "name"]
             (testing/ordinary-args ["--ignored" "--" "name"] [])))
  (it "adds full property names when libtest uses exact matching"
    (should= {:args ["--" "--skip" "property_" "--skip" "tests::property_x" "--exact" "--ignored"]}
             (testing/exclude-properties
              ["--exact" "--ignored"]
              (fn [args]
                (should= ["--" "--list" "--include-ignored"] args)
                {:status 0 :out "tests::ordinary: test\ntests::property_x: test\n"})))))

(describe "Commands without libtest arguments"
  (it "accepts an empty tools invocation"
    (should= [] (:libtest (options/parse "tools" [])))))

(defn- with-repo [run]
  (fs/create-dirs "tmp")
  (let [dir (fs/canonicalize (fs/create-temp-dir {:dir "tmp" :prefix "kevin-rust-spec-"}))]
    (try
      (git/git ["init" "-q" "-b" "main"] :dir dir)
      (run dir)
      (finally (fs/delete-tree dir)))))

(defn- commit-fixture [dir]
  (process/shell {:dir dir
                 :extra-env {"GIT_CONFIG_COUNT" "2"
                             "GIT_CONFIG_KEY_0" "user.name"
                             "GIT_CONFIG_VALUE_0" "Codex"
                             "GIT_CONFIG_KEY_1" "user.email"
                             "GIT_CONFIG_VALUE_1" "codex@openai.com"
                             "GIT_AUTHOR_NAME" "Codex"
                             "GIT_AUTHOR_EMAIL" "codex@openai.com"
                             "GIT_COMMITTER_NAME" "Codex"
                             "GIT_COMMITTER_EMAIL" "codex@openai.com"}}
                 "git" "commit" "-qm" "Seed Rust fixture"))

(describe "Changed Rust source"
  (it "reads current file contents after the index stages a removal"
    (with-repo
      (fn [dir]
        (let [file "space name.rs"
              source (fs/path dir file)
              project {:root (str dir) :base "HEAD"}]
          (spit (str source) "fn a() {}\nfn b() {}\nfn c() {}\n")
          (git/git ["add" "--" file] :dir dir)
          (commit-fixture dir)
          (should= "Codex <codex@openai.com> / Codex <codex@openai.com>\n"
                   (git/git ["show" "-s" "--format=%an <%ae> / %cn <%ce>"] :dir dir))
          (git/git ["rm" "--cached" "--" file] :dir dir)
          (spit (str source) "fn a() {}\nfn b() { let _n = 1; }\nfn c() {}\n")
          (should= {file #{2}} (snapshot/changed-lines project #{file})))))))

(describe "Rust clone scope"
  (it "matches the complete path when two files share a suffix"
    (let [report {"duplicates" [{"tokens" 50
                                 "firstFile" {"name" "/repo/other/foo.rs" "start" 1 "end" 5}
                                 "secondFile" {"name" "/repo/bar.rs" "start" 1 "end" 5}}]}]
      (should= 0 (:status (reports/dry-review "/repo" #{"foo.rs" "other/foo.rs" "bar.rs"}
                                            {"foo.rs" #{2}} report)))
      (should= 1 (:status (reports/dry-review "/repo" #{"foo.rs" "other/foo.rs" "bar.rs"}
                                            {"bar.rs" #{2}} report))))))

(defn- with-cargo-project [respond run]
  (with-repo
    (fn [dir]
      (let [manifest (str (fs/path dir "Cargo.toml"))
            source (str (fs/path dir "lib.rs"))
            metadata (assoc metadata
                            "workspace_root" (str dir)
                            "workspace_default_members" ["alpha-id"]
                            "packages" [{"id" "alpha-id" "name" "alpha"
                                         "manifest_path" manifest}])
            shell-process process/shell
            which fs/which]
        (spit manifest "[package]\nname = \"alpha\"\nversion = \"0.1.0\"\n")
        (git/git ["add" "--" "Cargo.toml"] :dir dir)
        (commit-fixture dir)
        (spit source "pub fn a() {}\n")
        (with-redefs [fs/which #(if (#{"cargo" "rustc" "node" "npm" "npx"} %) % (which %))
                      fs/executable? (constantly true)
                      process/shell (fn [opts & args]
                                      (let [[program operation & flags] (or (:cmd opts) args)]
                                        (cond
                                          (= "git" program) (apply shell-process opts args)
                                          (or (= "--version" operation)
                                              (and (= "cargo" program) (= ["--version"] flags)))
                                          {:exit 0 :out "tool 1.0\n"}
                                          (= "rustc" program) {:exit 0 :out (str dir "/lib\n")}
                                          (= "locate-project" operation) {:exit 0 :out manifest}
                                          (= "metadata" operation) {:exit 0 :out (json/generate-string metadata)}
                                          :else (respond dir source operation flags))))]
          (binding [shell/*dir* dir]
            (run dir)))))))

(defn- flag-value [flag args]
  (second (drop-while #(not= flag %) args)))

(describe "Rust command adapters"
  (it "writes selected LCOV and reports coverage of changed lines"
    (with-cargo-project
      (fn [_ source operation flags]
        (should= "llvm-cov" operation)
        (if (some #{"--list"} flags)
          {:exit 0 :out "tests::property_guard: test\n"}
          (do
            (should= ["--" "--skip" "property_" "--skip" "tests::property_guard" "--exact"]
                     (vec (drop-while #(not= "--" %) flags)))
            (spit (flag-value "--output-path" flags)
                  (str "SF:" source "\nDA:1,1\nend_of_record\n"
                       "SF:/other.rs\nDA:1,0\nend_of_record\n"))
            {:exit 0})))
      (fn [dir]
        (let [output (with-out-str (should= 0 (cli/main ["coverage" "--" "--exact"])))
              [report] (fs/glob dir "tmp/coverage/*/rust.lcov")]
          (should-contain "changed lines covered: 1/1" output)
          (should= (str "SF:" (fs/path dir "lib.rs") "\nDA:1,1\nend_of_record\n")
                   (slurp (str report)))))))
  (it "passes through CRAP findings in changed files"
    (with-cargo-project
      (fn [_ source operation flags]
        (case operation
          "llvm-cov" (do (spit (flag-value "--output-path" flags)
                               (str "SF:" source "\nDA:1,0\nend_of_record\n"))
                         {:exit 0})
          "crap" {:exit 0 :out (json/generate-string
                                {"entries" [{"file" source "function" "a" "line" 1
                                             "cyclomatic" 4 "coverage" 0 "crap" 20}]})}))
      (fn [_]
        (should-contain "lib.rs:1" (with-out-str (should= 1 (cli/main ["crap"])))))))
  (it "reports test failures without writing a coverage report"
    (with-cargo-project
      (fn [_ _ operation _]
        (should= "llvm-cov" operation)
        {:exit 101})
      (fn [dir]
        (with-out-str (should= 1 (cli/main ["coverage"])))
        (should= [] (fs/glob dir "tmp/coverage/*/rust.lcov")))))
  (it "keeps property invocations separate from ordinary tests"
    (with-cargo-project
      (fn [_ _ operation flags]
        (should= "test" operation)
        (let [libtest (vec (drop-while #(not= "--" %) flags))]
          (if (= ["--" "property_" "--ignored"] libtest)
            {:exit 0}
            (do (should= ["--" "--skip" "property_"] libtest) {:exit 101}))))
      (fn [_]
        (with-out-str
          (should= 0 (cli/main ["property"]))
          (should= 1 (cli/main ["test"]))))))
  (it "reports missed mutants from the tool's outcome report"
    (with-cargo-project
      (fn [_ _ operation flags]
        (should= "mutants" operation)
        (should-contain "+pub fn a() {}" (slurp (flag-value "--in-diff" flags)))
        (let [report-dir (fs/path (flag-value "--output" flags) "mutants.out")]
          (fs/create-dirs report-dir)
          (spit (str (fs/path report-dir "outcomes.json"))
                (json/generate-string {"caught" 3 "missed" 1 "timeout" 0 "unviable" 0})))
        {:exit 2})
      (fn [_]
        (should-contain "caught 3, missed 1, timed out 0, unviable 0"
                        (with-out-str (should= 1 (cli/main ["mutate"])))))))
  (it "reports an empty mutation candidate list as success"
    (with-cargo-project
      (fn [_ _ operation flags]
        (should= "mutants" operation)
        (let [report-dir (fs/path (flag-value "--output" flags) "mutants.out")]
          (fs/create-dirs report-dir)
          (spit (str (fs/path report-dir "mutants.json")) "[]"))
        {:exit 0})
      (fn [_]
        (should-contain "no eligible mutants"
                        (with-out-str (should= 0 (cli/main ["mutate"])))))))
  (it "reports a failed mutation baseline when the tool writes no report"
    (with-cargo-project
      (fn [_ _ operation _]
        (should= "mutants" operation)
        {:exit 4})
      (fn [_]
        (should-contain "cargo mutants baseline failed"
                        (with-out-str (should= 1 (cli/main ["mutate"])))))))
  (it "reports unavailable optional tools while Cargo and rustc are installed"
    (with-cargo-project
      (fn [_ _ operation _]
        (should= "--yes" operation)
        {:exit 1})
      (fn [_]
        (should-contain "jscpd: unavailable"
                        (with-out-str (should= 0 (cli/main ["tools"])))))))
  (it "passes selected production files to the duplication scanner"
    (with-cargo-project
      (fn [_ source operation flags]
        (should= "--yes" operation)
        (if (some #{"--version"} flags)
          {:exit 0 :out "4.3.0"}
          (do
            (should= source (last flags))
            (spit (str (fs/path (flag-value "--output" flags) "jscpd-report.json"))
                  "{\"duplicates\":[]}\n")
            {:exit 0})))
      (fn [_]
        (should-contain "jscpd report written"
                        (with-out-str (should= 0 (cli/main ["dry"]))))))))
