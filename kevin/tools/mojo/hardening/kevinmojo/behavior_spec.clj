(ns kevinmojo.behavior-spec
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [kevinmojo.cli :as cli]
            [kevinmojo.project :as project]
            [kevincore.git :as git]
            [kevincore.shell :as shell]
            [speclj.core :refer [around describe it should= should-contain should-throw]]))

(def ^:dynamic *root* nil)

(defn- touch [file]
  (let [path (fs/path *root* file)]
    (fs/create-dirs (fs/parent path))
    (str (fs/create-file path))))

(defn- coverage-runner [report status excluded]
  (fn [tool args & _]
    (if (= "kcov" tool)
      (let [[options [directory]] (split-with #(.startsWith % "--") args)]
        (should= [excluded] (filter #(.startsWith % "--exclude-path=") options))
        (when report
          (let [file (fs/path directory "suite/cobertura.xml")]
            (fs/create-dirs (fs/parent file))
            (spit (str file) report)))
        status)
      0)))

(defn- clone-report [start]
  (let [side (fn [file] {:name file :start start :end 1})]
    {:duplicates [{:tokens 30 :firstFile (side "a.mojo") :secondFile (side "b.mojo")}]}))

(def ^:private actual-root project/root)

(describe "Mojo hardening"
  (around [example]
    (fs/with-temp-dir [directory {:dir (fs/create-dirs (fs/path "tmp"))}]
      (binding [*root* (fs/real-path directory)]
        (with-redefs [project/root (constantly *root*)
                      shell/available? (constantly true)]
          (example)))))

  (it "prints help and reports installed tools"
    (should-contain "jscpd: available"
                    (with-out-str (with-redefs [shell/run (constantly 0)]
                                    (cli/main ["tools"]))))
    (doseq [args [[] ["test" "--help"] ["--help"]]]
      (should-contain "usage: kevin mojo"
                      (with-out-str (should= 0 (cli/main args))))))

  (it "discovers hidden suites and rejects files that are not suites"
    (let [file (touch "test/.hidden/test_math.mojo")
          other (touch "test/other.mojo")]
      (with-redefs [shell/run (fn [_ args] (should= file (last args)) 0)]
        (should= 0 (cli/main ["test"])))
      (should-throw clojure.lang.ExceptionInfo #"inside the worktree"
                    (cli/main ["test" other]))))

  (it "rejects a non-directory test discovery root"
    (touch "test")
    (should-throw clojure.lang.ExceptionInfo #"no suites selected" (cli/main ["test"])))

  (it "uses the Git root when available and the working directory otherwise"
    (with-redefs [git/succeeds? (constantly true)
                  git/repo-root (constantly (str *root*))]
      (should= *root* (actual-root)))
    (with-redefs [git/succeeds? (constantly false)]
      (should= (fs/real-path ".") (actual-root))))

  (it "rejects explicit suites and discovery directories outside the project"
    (let [outside (touch "outside/test_math.mojo")
          nested (fs/create-dir (fs/path *root* "nested"))]
      (fs/create-sym-link (fs/path nested "test") (fs/parent outside))
      (with-redefs [project/root (constantly nested)]
        (should-throw clojure.lang.ExceptionInfo #"inside the worktree"
                      (cli/main ["test" outside]))
        (should-throw clojure.lang.ExceptionInfo #"no suites selected"
                      (cli/main ["test"])))))

  (it "reports a missing SDK before running a valid suite"
    (let [file (touch "test/test_math.mojo")]
      (with-redefs [shell/available? (constantly false)]
        (should-throw clojure.lang.ExceptionInfo #"mojo is required"
                      (cli/main ["test" file])))))

  (it "validates coverage reports and distinguishes trace and suite failures"
    (when (= "Linux" (System/getProperty "os.name"))
      (let [suite (touch "test/test_math.mojo")
            excluded (touch "src/.hidden/property_math.mojo")
            args ["coverage" suite]]
        (touch "src/math.mojo")
        (doseq [[report status error] [["<coverage lines-valid=\"0\"/>" 0 #"no measured"]
                                       ["<coverage lines-valid=\"-1\"/>" 0 #"invalid Cobertura"]
                                       ["<coverage lines-valid=\"0\"/>" 255 #"permission to trace"]
                                       [nil 0 #"did not write"]]]
          (with-redefs [shell/run (coverage-runner report status (str "--exclude-path=" excluded))]
            (should-throw clojure.lang.ExceptionInfo error (cli/main args))))
        (with-redefs [shell/run (coverage-runner "<coverage lines-valid=\"2\"/>" 255
                                                (str "--exclude-path=" excluded))]
          (should= 255 (cli/main args))))))

  (it "scans eligible files and publishes only successful DRY reports"
    (doseq [file ["a.mojo" "b.mojo" "ignored.mojo" "nested/build/skip.mojo"]]
      (touch file))
    (fs/create-sym-link (fs/path *root* "alias.mojo") (fs/path *root* "a.mojo"))
    (let [report (atom (clone-report 1))
          result (atom {:exit 0 :out ""})
          published (fs/path *root* "tmp/jscpd/jscpd-report.json")]
      (with-redefs [git/git (fn [args]
                            (if (some #{"--ignored"} args)
                              "ignored.mojo\u0000"
                              "a.mojo\u0000b.mojo\u0000ignored.mojo\u0000nested/build/skip.mojo\u0000alias.mojo\u0000deleted.mojo\u0000"))
                    git/ref-exists? (constantly true)
                    git/collect-changed-lines (constantly {})
                    shell/capture (fn [_ args]
                                    (should= ["a.mojo" "b.mojo"]
                                             (sort (map fs/file-name (fs/list-dir shell/*dir*))))
                                    (let [directory (nth args (inc (.indexOf args "--output")))]
                                      (spit (str (fs/path directory "jscpd-report.json"))
                                            (json/generate-string @report))
                                    @result))]
        (should= 0 (cli/main ["dry"]))
        (swap! report assoc :scan "all")
        (should= 1 (cli/main ["dry" "--all"]))
        (should-contain "\"all\"" (slurp (str published)))
        (should-throw clojure.lang.ExceptionInfo #"dry takes" (cli/main ["dry" "--base"]))
        (let [previous (slurp (str published))]
          (doseq [invalid [(clone-report 0) {}]]
            (reset! report invalid)
            (should-throw clojure.lang.ExceptionInfo #"invalid jscpd JSON"
                          (cli/main ["dry" "--all"])))
          (reset! result {:exit 9 :out "scanner failed"})
          (should-throw clojure.lang.ExceptionInfo #"jscpd exited 9" (cli/main ["dry" "--all"]))
          (should= previous (slurp (str published))))))))
