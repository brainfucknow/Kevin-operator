(ns kevinrust.rust-spec
  (:require [kevinrust.options :as options]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [kevincore.git :as git]
            [kevinrust.project :as project]
            [kevinrust.reports :as reports]
            [kevinrust.snapshot :as snapshot]
            [kevinrust.testing]
            [speclj.core :refer [describe it should= should-throw]]))

(describe "Rust command options"
  (it "keeps repeatable selectors and libtest filters separate"
    (let [opts (options/parse "test" ["-p" "alpha" "--package=beta" "--locked"
                                      "--features" "beta/extra" "--" "--ignored" "matching"])]
      (should= ["alpha" "beta"] (:packages opts))
      (should= ["--features" "beta/extra"] (:features opts))
      (should= ["--ignored" "matching"] (:libtest opts))
      (should= true (:locked opts))))
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
             (kevinrust.testing/ordinary-args ["--ignored" "--" "name"] [])))
  (it "adds full property names when libtest uses exact matching"
    (should= ["--" "--skip" "property_" "--skip" "tests::property_x" "--exact" "--ignored"]
             (kevinrust.testing/ordinary-args ["--exact" "--ignored"] ["tests::property_x"]))))

(describe "Commands without libtest arguments"
  (it "accepts an empty tools invocation"
    (should= [] (:libtest (options/parse "tools" [])))))

(describe "Changed Rust source"
  (it "reads current file contents after the index stages a removal"
    (fs/create-dirs "tmp")
    (let [dir (fs/canonicalize (fs/create-temp-dir {:dir "tmp" :prefix "kevin-rust-spec-"}))
          file "space name.rs"
          source (fs/path dir file)
          project {:root (str dir) :base "HEAD"}]
      (try
        (git/git ["init" "-q"] :dir dir)
        (spit (str source) "fn a() {}\nfn b() {}\nfn c() {}\n")
        (git/git ["add" "--" file] :dir dir)
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
                       "git" "commit" "-qm" "Seed changed source fixture")
        (should= "Codex <codex@openai.com> / Codex <codex@openai.com>\n"
                 (git/git ["show" "-s" "--format=%an <%ae> / %cn <%ce>"] :dir dir))
        (git/git ["rm" "--cached" "--" file] :dir dir)
        (spit (str source) "fn a() {}\nfn b() { let _n = 1; }\nfn c() {}\n")
        (should= {file #{2}} (snapshot/changed-lines project #{file}))
        (finally (fs/delete-tree dir))))))

(describe "Rust clone scope"
  (it "matches the complete path when two files share a suffix"
    (let [report {"duplicates" [{"tokens" 50
                                 "firstFile" {"name" "/repo/other/foo.rs" "start" 1 "end" 5}
                                 "secondFile" {"name" "/repo/bar.rs" "start" 1 "end" 5}}]}]
      (should= 0 (:status (reports/dry-review "/repo" #{"foo.rs" "other/foo.rs" "bar.rs"}
                                            {"foo.rs" #{2}} report)))
      (should= 1 (:status (reports/dry-review "/repo" #{"foo.rs" "other/foo.rs" "bar.rs"}
                                            {"bar.rs" #{2}} report))))))
