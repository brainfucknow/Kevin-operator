(ns kevinmojo.coverage
  (:require [babashka.fs :as fs]
            [clojure.data.xml :as xml]
            [clojure.string :as str]
            [kevinmojo.project :as project]
            [kevinmojo.suite :as suite]
            [kevincore.shell :as shell]))

(defn- measured-lines [report]
  (let [document (xml/parse-str (str/replace (slurp (str report)) #"<!DOCTYPE[^>]*>" ""))
        lines (some-> document :attrs :lines-valid parse-long)]
    (when-not (and (= :coverage (:tag document)) lines (not (neg? lines)))
      (project/fail (str "invalid Cobertura report: " report)))
    lines))

(defn- kcov-options [source]
  (let [tests (->> (fs/glob source "**.mojo" {:hidden true})
                   (filter suite/suite-file?))]
    (cond-> ["--clean" (str "--include-path=" source)
             "--include-pattern=.mojo" (str "--strip-path=" source)]
      (seq tests) (conj (str "--exclude-path=" (str/join "," tests))))))

(defn- collect-report [options run-dir number binary suite-args]
  (let [directory (fs/path run-dir (str "suite-" number))
        status (shell/run "kcov" (concat options [(str directory) (str binary)] suite-args))
        reports (when (fs/directory? directory)
                  (sort (fs/glob directory "*/cobertura.xml")))]
    (doseq [report reports]
      (println (str "Coverage report: " report)))
    (cond
      (and (= 255 status) (not-any? (comp pos? measured-lines) reports))
      (project/fail "kcov exited 255; check permission to trace local test binaries")
      (empty? reports) (project/fail (str "kcov did not write a Cobertura report under " directory))
      (not (zero? status)) {:status status}
      :else {:lines (reduce + (map measured-lines reports))})))

(defn- run-suite [options run-dir {:keys [suite-args] :as selection} number file]
  (let [binary (fs/path run-dir (str "test-" number))
        cache (fs/path run-dir (str "compiler-cache-" number))
        args (concat (suite/compiler-args "build" ["-g1" "-O" "0"] selection file)
                     ["-o" (str binary)])]
    (try
      ;; Cached debug line tables can name a different worktree.
      (let [status (shell/run "mojo" args {"MODULAR_CACHE_DIR" (str cache)})]
        (if (zero? status)
          (collect-report options run-dir number binary suite-args)
          {:status status}))
      (finally
        (fs/delete-if-exists binary)
        (fs/delete-tree cache)))))

(defn run-coverage [root {:keys [source suites] :as selection}]
  (when-not (= "Linux" (System/getProperty "os.name"))
    (project/fail "coverage is supported on Linux with permission to trace child processes"))
  (let [source-dir (project/resolve-path root (or source (fs/path root "src"))
                                         fs/directory? "a source directory for --source")]
    (project/require-tool "mojo")
    (project/require-tool "kcov")
    (let [options (kcov-options source-dir)
          directory (project/scratch (project/output-dir root "coverage") "mojo-")
          {:keys [status lines]}
          (reduce (fn [total [number file]]
                    (let [{:keys [status lines] :as result}
                          (run-suite options directory selection number file)]
                      (if status (reduced result) (update total :lines + lines))))
                  {:lines 0} (map-indexed vector suites))]
      (cond
        status status
        (pos? lines) 0
        :else (project/fail (str "no measured Mojo source lines under " source-dir))))))
