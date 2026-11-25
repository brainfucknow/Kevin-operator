(ns kevinrust.testing
  (:require [kevincore.shell :as shell]
            [kevinrust.libtest :as libtest]
            [kevinrust.options :as options]))

(defn exclude-properties [user-args list-tests]
  (if-let [args (libtest/list-args user-args)]
    (let [{:keys [status out]} (list-tests args)]
      (if (zero? status)
        {:args (libtest/ordinary-args user-args out)}
        (do (print out)
            {:status status})))
    {:args (libtest/ordinary-args user-args)}))

(defn- list-tests [project libtest]
  (let [args (concat ["test" "--color" "never"] (options/test-args project) libtest)
        {:keys [exit out]} (shell/capture "cargo" args)]
    {:status (if (zero? exit) 0 1) :out out}))

(defn test-run [command project]
  (let [{:keys [args status]} (if (= "property" command)
                               {:args libtest/property-args}
                               (exclude-properties (:libtest project) #(list-tests project %)))]
    (or status
        (if (zero? (shell/run "cargo" (concat ["test"] (options/test-args project) args))) 0 1))))
