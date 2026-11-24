(ns kevinrust.mutation
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [kevincore.outcome :as outcome]
            [kevinrust.artifacts :as artifacts]
            [kevinrust.project :as project]
            [kevinrust.snapshot :as snapshot]
            [kevinrust.testing :as testing]
            [kevinrust.tools :as tools]))

(defn- mutation-args [{:keys [manifest selected features locked list]} dir diff]
  (into ["cargo" "mutants" "--no-config" "--manifest-path" manifest
         "--jobs" "4" "--jobserver-tasks" "4" "--copy-target=false" "--in-diff" (str diff)]
        (concat (mapcat #(vector "--package" %) selected)
                features
                (when locked ["--cargo-arg=--locked"])
                (if list
                  ["--list"]
                  (concat ["--output" (str dir) "--baseline" "run"]
                          (mapcat #(vector "--test-package" %) selected)
                          ["--"]
                          (testing/ordinary-args [] []))))))

(defn- result [dir exit]
  (let [path (fs/path dir "mutants.out" "outcomes.json")
        candidates (fs/path dir "mutants.out" "mutants.json")]
    (cond
      (fs/regular-file? path)
      (let [{:strs [caught missed timeout unviable]} (artifacts/json-report "cargo mutants" path)]
        (when-not (every? nat-int? [caught missed timeout unviable])
          (throw (outcome/usage-error (str "cargo mutants report lacks outcome counts: " path))))
        (println (format "caught %d, missed %d, timed out %d, unviable %d"
                         caught missed timeout unviable))
        (println (str "mutation outcomes written to " path))
        (if (or (#{2 3 4} exit) (pos? missed) (pos? timeout)) 1 0))

      (and (zero? exit)
           (= [] (artifacts/json-report "cargo mutants" candidates)))
      (do (println (str "no eligible mutants; candidate list written to " candidates)) 0)

      (= 4 exit) (do (println (str "cargo mutants baseline failed; see " dir)) 1)

      :else (throw (outcome/usage-error (str "cargo mutants did not write " path))))))

(defn- run-mutants [{:keys [list] :as project} patch]
  (let [dir (artifacts/run-dir project "mutation")
        diff (fs/path dir "rust.diff")
        ;; Cargo-mutants skips directories named mutants.out when it copies the workspace.
        copy-root (fs/path dir "scratch" "mutants.out")
        _ (fs/create-dirs copy-root)
        _ (spit (str diff) patch)
        args (mutation-args project dir diff)
        _ (println (str "$ " (str/join " " args)))
        {:keys [exit]} (process/shell {:continue true :cmd args
                                      :extra-env {"TMPDIR" (str copy-root)
                                                  "TMP" (str copy-root)
                                                  "TEMP" (str copy-root)
                                                  "CARGO_TARGET_DIR" "target"
                                                  "CARGO_BUILD_BUILD_DIR" "target"}})]
    (cond
      (and list (zero? exit)) 0
      (and (not list) (#{0 2 3 4} exit)) (result dir exit)
      :else (throw (outcome/usage-error (str "cargo mutants failed for " diff " (exit " exit ")"))))))

(defn mutate [{:keys [workspace-root] :as project}]
  (tools/require-tools "mutate" ["cargo-mutants"])
  (let [patch (snapshot/patch project workspace-root (project/source-files project))]
    (if (str/blank? patch)
      (do (println "no eligible mutants") 0)
      (run-mutants project patch))))
