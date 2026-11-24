(ns kevinrust.analysis
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [kevincore.outcome :as outcome]
            [kevincore.shell :as shell]
            [kevinrust.artifacts :as artifacts]
            [kevinrust.options :as options]
            [kevinrust.project :as project]
            [kevinrust.reports :as reports]
            [kevinrust.snapshot :as snapshot]
            [kevinrust.testing :as testing]
            [kevinrust.tools :as tools]))

(defn- llvm-cov [project dir flags libtest capture?]
  (let [args (into ["cargo" "llvm-cov"]
                   (concat flags (options/test-args project) libtest))
        log (fs/path dir (if capture? "list.stderr" "coverage.stderr"))
        build (str (fs/path dir "target"))
        _ (println (str "$ " (str/join " " args)))
        {:keys [exit out]} (with-open [error-stream (java.io.FileOutputStream. (str log))]
                            (process/shell {:continue true :cmd args :err error-stream
                                            :out (if capture? :string :inherit)
                                            :extra-env {"CARGO_LLVM_COV_TARGET_DIR" build
                                                        "CARGO_LLVM_COV_BUILD_DIR" build}}))
        errors (slurp (str log))]
    (binding [*out* *err*] (print errors) (flush))
    (cond
      (zero? exit) {:status 0 :out out}
      (or (= 101 exit) (re-find #"(?m)^error: process didn't exit successfully: .*cargo(?:\.exe)?['\"]? test .+\(exit status: 101\)$" errors))
      {:status 1 :out out}
      :else (throw (outcome/usage-error (str "cargo llvm-cov failed (exit " exit "). See " log))))))

(defn- finish-coverage [root files raw report]
  (let [records (try
                  (reports/selected-lcov root files (artifacts/read-report "cargo llvm-cov" raw))
                  (catch Exception e
                    (throw (outcome/usage-error (str raw ": " (ex-message e))))))]
    (spit (str report) (apply str (map :text records)))
    (println (str "coverage written to " report))
    {:status 0 :report report :records records}))

(defn- collect-coverage [{:keys [root libtest] :as project} files]
  (let [dir (artifacts/run-dir project "coverage")
        raw (fs/path dir "raw.lcov")
        report (fs/path dir "rust.lcov")]
    (try
      (let [{:keys [args status]} (testing/exclude-properties
                                   libtest #(llvm-cov project dir ["--no-report"] % true))]
        (if status
          {:status status}
          (let [result (llvm-cov project dir ["--lcov" "--output-path" (str raw)] args false)]
            (if (zero? (:status result))
              (finish-coverage root files raw report)
              result))))
      (finally
        (fs/delete-if-exists raw)
        (fs/delete-tree (fs/path dir "target"))))))

(defn coverage [project]
  (tools/require-tools "coverage" ["cargo-llvm-cov" "llvm-tools"])
  (let [files (project/source-files project)
        changed (snapshot/changed-lines project files)
        {:keys [status records]} (collect-coverage project files)]
    (when (zero? status)
      (let [[hits total] (reports/changed-coverage records changed)]
        (println (format "changed lines covered: %d/%d%s" hits total
                         (if (zero? total) " (no coverage data for changed lines)" "")))))
    status))

(defn- crap-rows [{:keys [workspace-root selected]} report]
  (let [args (into ["crap" "--lcov" (str report) "--no-cache" "--jobs" "4"
                    "--format" "json" "--threshold" "10" "--missing" "pessimistic"]
                   (mapcat #(vector "--package" %) selected))
        {:keys [exit out]} (binding [shell/*dir* workspace-root] (shell/capture "cargo" args))]
    (when-not (zero? exit)
      (throw (outcome/usage-error (str "cargo crap failed for " report " (exit " exit ")"))))
    (get (artifacts/parse-json "cargo crap" report out) "entries")))

(defn crap [project]
  (tools/require-tools "crap" ["cargo-crap" "cargo-llvm-cov" "llvm-tools"])
  (let [files (project/source-files project)
        changed (snapshot/changed-lines project files)
        scoped (if (:all project) files (set (keys changed)))]
    (if (empty? scoped)
      (do (println "no production Rust files to score") 0)
      (let [{:keys [status report]} (collect-coverage project files)]
        (if (zero? status)
          (shell/emit (reports/crap-review (:root project) scoped (crap-rows project report)))
          status)))))

(defn- jscpd-report [{:keys [root] :as project} files]
  (let [dir (artifacts/run-dir project "jscpd")
        report (fs/path dir "jscpd-report.json")
        config (fs/path dir "jscpd.json")]
    (if (empty? files)
      (spit (str report) "{\"duplicates\":[]}\n")
      (do
        (spit (str config) "{}\n")
        (let [max-bytes (reduce max 0 (map #(fs/size (fs/path root %)) files))
              args (into (vec (rest tools/jscpd))
                         (concat ["--format" "rust" "--min-tokens" "30" "--min-lines" "5"
                                  "--max-lines" (str (inc max-bytes))
                                  "--max-size" (str (inc max-bytes) "b")
                                  "--reporters" "json" "--output" (str dir) "--config" (str config)
                                  "--absolute" "--no-gitignore" "--silent" "--noTips" "--"]
                                 (map #(str (fs/path root %)) (sort files))))
              exit (shell/run (first tools/jscpd) args)]
          (when-not (zero? exit)
            (throw (outcome/usage-error (str "jscpd failed (exit " exit ")")))))))
    (let [json (artifacts/json-report "jscpd" report)]
      (when-not (vector? (get json "duplicates"))
        (throw (outcome/usage-error (str "jscpd report is missing its duplicates array: " report))))
      (println (str "jscpd report written to " report))
      json)))

(defn dry [project]
  (tools/require-tools "dry" ["Node.js" "npm" "npx" "jscpd"])
  (let [files (project/source-files project)
        changed (snapshot/changed-lines project files)
        report (jscpd-report project files)]
    (shell/emit (reports/dry-review (:root project) files changed report))))
