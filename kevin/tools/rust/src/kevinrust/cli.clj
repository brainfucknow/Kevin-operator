(ns kevinrust.cli
  (:require [kevincore.outcome :as outcome]
            [kevincore.shell :as shell]
            [kevinrust.analysis :as analysis]
            [kevinrust.mutation :as mutation]
            [kevinrust.options :as options]
            [kevinrust.project :as project]
            [kevinrust.testing :as testing]
            [kevinrust.tools :as tools]))

(def ^:private commands
  {"test" #(testing/test-run "test" %)
   "property" #(testing/test-run "property" %)
   "coverage" analysis/coverage
   "crap" analysis/crap
   "dry" analysis/dry
   "mutate" mutation/mutate})

(def ^:private help-args #{"help" "--help" "-h" nil})

(defn main [[command & args]]
  (cond
    (or (help-args command) (and (seq args) (help-args (first args))))
    (do (println options/usage) 0)

    (= "tools" command)
    (do (options/parse command args) (tools/show))

    (contains? commands command)
    (let [opts (options/parse command args)]
      (tools/require-tools command ["Cargo" "rustc"])
      ((commands command) (project/load-project opts)))

    :else (throw (outcome/usage-error (str "unknown command '" command "'\n" options/usage)))))

(defn -main [& args]
  (shell/run-main "kevin rust" main args))
