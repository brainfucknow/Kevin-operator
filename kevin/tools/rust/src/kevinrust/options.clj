(ns kevinrust.options
  (:require [clojure.string :as str]
            [kevincore.outcome :as outcome]))

(def usage "usage: kevin rust <tools|test|property|coverage|crap|dry|mutate> [options]

Package options: --manifest-path PATH, -p/--package NAME (repeatable),
                 --workspace, --exclude NAME (repeatable with --workspace)
Test options:    --locked, --features FEATURES (repeatable),
                 --all-features, --no-default-features
                 test and coverage also accept -- LIBTEST_ARGS
Change scope:    --base REF (coverage, crap, dry, mutate)
                 --all (crap, dry), --list (mutate)")

(def ^:private values
  {"--manifest-path" :manifest "-p" :packages "--package" :packages
   "--exclude" :excludes "--features" :features "--base" :base})

(def ^:private flags
  {"--workspace" :workspace "--locked" :locked "--all-features" :features
   "--no-default-features" :features "--all" :all "--list" :list})

(def ^:private selectors #{:manifest :packages :workspace :excludes})
(def ^:private test-options (into selectors [:locked :features]))

(def ^:private allowed
  {"tools" #{} "test" test-options "property" test-options
   "coverage" (conj test-options :base) "crap" (conj test-options :base :all)
   "dry" (into selectors [:base :all]) "mutate" (conj test-options :base :list)})

(defn- add-option [opts key flag value]
  (case key
    (:packages :excludes) (update opts key (fnil conj []) value)
    :features (update opts key (fnil into []) (cond-> [flag] value (conj value)))
    (assoc opts key (or value true))))

(defn- parse-options [command args]
  (loop [opts {}, [arg & remaining] args]
    (if-not arg
      opts
      (let [[flag inline] (str/split arg #"=" 2)
            key (or (values flag) (flags flag))]
        (when-not (contains? (allowed command) key)
          (throw (outcome/usage-error (str command " does not accept " arg))))
        (if (values flag)
          (let [value (or inline (first remaining))]
            (when (or (str/blank? value) (str/starts-with? value "-"))
              (throw (outcome/usage-error (str flag " requires a value"))))
            (recur (add-option opts key flag value) (if inline remaining (rest remaining))))
          (do
            (when inline (throw (outcome/usage-error (str flag " takes no value"))))
            (recur (add-option opts key flag nil) remaining)))))))

(defn parse [command args]
  (let [[before separator] (split-with #(not= "--" %) args)
        opts (parse-options command before)]
    (when (and (seq separator) (not (#{"test" "coverage"} command)))
      (throw (outcome/usage-error (str command " does not accept libtest arguments"))))
    (when (and (seq (:excludes opts)) (not (:workspace opts)))
      (throw (outcome/usage-error "--exclude requires --workspace")))
    (assoc opts :libtest (vec (rest separator)))))

(defn test-args [{:keys [manifest packages workspace excludes locked features]}]
  (into ["--manifest-path" manifest "--jobs" "4"]
        (concat (when workspace ["--workspace"])
                (mapcat #(vector "--package" %) packages)
                (mapcat #(vector "--exclude" %) excludes)
                (when locked ["--locked"])
                features)))
