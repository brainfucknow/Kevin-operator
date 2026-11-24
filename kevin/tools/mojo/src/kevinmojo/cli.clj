(ns kevinmojo.cli
  (:require [kevinmojo.coverage :as coverage]
            [kevinmojo.dry :as dry]
            [kevinmojo.project :as project]
            [kevinmojo.suite :as suite]
            [kevincore.shell :as shell]))

(def usage
  "usage: kevin mojo <command> [options]

  tools                                      Show Mojo, jscpd, and kcov availability.
  test [-I DIR]... [SUITE...] [-- ARGS...]     Run test_*.mojo suites.
  property [-I DIR]... [SUITE...] [-- ARGS...] Run property_*.mojo suites.
  coverage [--source DIR] [test options]     Measure unit suite line coverage with kcov.
  dry [--all | --base REF]                   Check Mojo files for duplicate text blocks.
  crap | mutate                              Unsupported.

Default suite discovery starts at test/ in the current project.
Use one explicit suite to forward arguments after --.")

(defn- tools [args]
  (when (seq args)
    (project/fail "tools takes no options"))
  (let [status (if (shell/available? "mojo")
                 (shell/run "mojo" ["--version"])
                 (do (println "mojo: missing (activate the project SDK)") 2))]
    (doseq [tool ["jscpd" "kcov"]]
      (println (str tool ": " (if (shell/available? tool) "available" "missing"))))
    status))

(defn- run-tests [{:keys [suites suite-args] :as selection}]
  (project/require-tool "mojo")
  (or (some (fn [file]
              (let [args (concat (suite/compiler-args "run" [] selection file) suite-args)
                    status (shell/run "mojo" args)]
                (when-not (zero? status) status)))
            suites)
      0))

(defn- run-command [command args]
  (case command
    "tools" (tools args)
    "test" (run-tests (suite/selection (project/root) :test args))
    "property" (run-tests (suite/selection (project/root) :property args))
    "coverage" (let [root (project/root)]
                 (coverage/run-coverage root (suite/selection root :coverage args)))
    "dry" (dry/run-dry (project/root) args)
    "crap" (project/fail "Mojo CRAP is unsupported")
    "mutate" (project/fail "Mojo mutation testing is unsupported")
    (project/fail (str "unknown command " command "; see kevin mojo --help"))))

(defn main [[command & args]]
  (if (or (contains? #{nil "-h" "--help" "help"} command)
          (contains? #{["-h"] ["--help"]} (vec args)))
    (do (println usage) 0)
    (run-command command args)))

(defn -main [& args]
  (shell/run-main "kevin mojo" main args))
