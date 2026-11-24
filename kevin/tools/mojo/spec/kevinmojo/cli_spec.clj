(ns kevinmojo.cli-spec
  (:require [babashka.fs :as fs]
            [kevinmojo.cli :as cli]
            [kevinmojo.project :as project]
            [kevincore.git :as git]
            [kevincore.shell :as shell]
            [speclj.core :refer [around describe it should= should-throw]]))

(def ^:dynamic *project* nil)

(defn- fixture [file contents]
  (let [path (fs/path *project* file)]
    (fs/create-dirs (fs/parent path))
    (spit (str path) contents)
    (str path)))

(describe "Mojo commands"
  (around [example]
    (fs/with-temp-dir [directory {:dir (fs/create-dirs (fs/path "tmp"))}]
      (binding [*project* (fs/real-path directory)]
        (with-redefs [project/root (constantly *project*)
                      shell/available? (constantly true)]
          (example)))))

  (it "reports the SDK status, including an absent SDK"
    (with-redefs [shell/run (constantly 17)]
      (should= 17 (cli/main ["tools"])))
    (with-redefs [shell/available? (constantly false)]
      (should= 2 (cli/main ["tools"])))
    (should-throw clojure.lang.ExceptionInfo "tools takes no options"
                  (cli/main ["tools" "extra"])))

  (it "passes include directories and runner arguments as separate values"
    (let [suite (fixture "test/test_math.mojo" "")
          includes (str (fs/create-dirs (fs/path *project* "import dir")))]
      (with-redefs [shell/run (fn [tool args]
                               (should= "mojo" tool)
                               (should= ["run" "-j" "4" "-I" includes suite "--only" "case name"] args)
                               17)]
        (should= 17 (cli/main ["test" "-I" includes suite "--" "--only" "case name"])))))

  (it "discovers property suites separately from unit suites"
    (fixture "test/test_math.mojo" "")
    (let [property (fixture "test/property_math.mojo" "")]
      (with-redefs [shell/run (fn [_ args] (should= property (last args)) 0)]
        (should= 0 (cli/main ["property"])))))

  (it "rejects invalid suite selections and options"
    (should-throw clojure.lang.ExceptionInfo #"no suites selected" (cli/main ["test"]))
    (should-throw clojure.lang.ExceptionInfo #"inside the worktree" (cli/main ["test" "absent.mojo"]))
    (should-throw clojure.lang.ExceptionInfo #"exactly one explicit suite" (cli/main ["test" "--" "--only"]))
    (should-throw clojure.lang.ExceptionInfo #"unknown option" (cli/main ["test" "--bad"]))
    (should-throw clojure.lang.ExceptionInfo #"-I requires a value" (cli/main ["test" "-I"])))

  (it "reads source coverage and preserves a test failure"
    (when (= "Linux" (System/getProperty "os.name"))
      (fixture "src/source.mojo" "")
      (let [test-file (fixture "src/test_math.mojo" "")
            args ["coverage" "--source" (str (fs/path *project* "src")) test-file]]
        (doseq [[lines status expected] [[2 0 0] [2 17 17]]]
          (with-redefs [shell/run (fn [tool options]
                                   (when (= "kcov" tool)
                                     (let [directory (first (drop-while #(re-find #"^--" %) options))
                                           report (fs/path directory "report/cobertura.xml")]
                                       (fs/create-dirs (fs/parent report))
                                       (spit (str report) (str "<coverage lines-valid=\"" lines "\"/>"))))
                                   (if (= "kcov" tool) status 0))]
            (should= expected (cli/main args)))))))

  (it "publishes a new empty DRY report when no files are eligible"
    (with-redefs [git/git (constantly "")
                  git/ref-exists? (constantly true)
                  git/collect-changed-lines (constantly {})]
      (should= 0 (cli/main ["dry"]))
      (should= 0 (cli/main ["dry" "--base" "main"]))
      (should= 0 (cli/main ["dry" "--all"]))
      (should= "{\"duplicates\":[]}" (slurp (str (fs/path *project* "tmp/jscpd/jscpd-report.json"))))
      (should-throw clojure.lang.ExceptionInfo #"dry takes" (cli/main ["dry" "--unknown"])))))
