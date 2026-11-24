(ns kevinrust.testing
  (:require [clojure.string :as str]
            [kevincore.shell :as shell]
            [kevinrust.options :as options]))

(def property-marker "property_")

(defn ordinary-args [user-args exact-skips]
  (into ["--" "--skip" property-marker]
        (concat (mapcat #(vector "--skip" %) exact-skips) user-args)))

(defn- property-names [output]
  (into (sorted-set)
        (keep (fn [line]
                (let [[_ name] (re-matches #"(.+): (?:test|benchmark)" line)]
                  (when (and name (str/includes? name property-marker)) name))))
        (str/split-lines output)))

(defn exclude-properties [libtest list-tests]
  ;; Libtest applies --exact to skip filters too.
  (if (some #{"--exact"} libtest)
    (let [{:keys [status out]} (list-tests ["--" "--list" "--include-ignored"])]
      (if (zero? status)
        {:args (ordinary-args libtest (property-names out))}
        (do (print out)
            {:status status})))
    {:args (ordinary-args libtest [])}))

(defn- list-tests [project libtest]
  (let [args (concat ["test" "--color" "never"] (options/test-args project) libtest)
        {:keys [exit out]} (shell/capture "cargo" args)]
    {:status (if (zero? exit) 0 1) :out out}))

(defn test-run [command project]
  (let [{:keys [args status]} (if (= "property" command)
                               {:args ["--" property-marker "--ignored"]}
                               (exclude-properties (:libtest project) #(list-tests project %)))]
    (or status
        (if (zero? (shell/run "cargo" (concat ["test"] (options/test-args project) args))) 0 1))))
