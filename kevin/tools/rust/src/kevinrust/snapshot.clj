(ns kevinrust.snapshot
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.set :as set]
            [clojure.string :as str]
            [kevincore.changes :as changes]
            [kevincore.git :as git]
            [kevincore.outcome :as outcome]
            [kevinrust.artifacts :as artifacts]
            [kevinrust.project :as project]))

(def ^:private diff-options
  ["--no-color" "--no-ext-diff" "--no-textconv" "--no-renames"
   "--src-prefix=a/" "--dst-prefix=b/" "--inter-hunk-context=0" "-U0"])

(defn- tracked-patches [dir env files]
  (map (fn [batch]
         (git/git (into ["--literal-pathspecs" "diff" "--relative" "--diff-filter=AMT"]
                        (concat diff-options ["--"] batch))
                  :dir dir :env env))
       (partition-all 256 (sort files))))

(defn- untracked-patch [dir file]
  (let [{:keys [exit out err]} (apply process/shell
                                     {:dir dir :out :string :err :string :continue true}
                                     "git" "-c" "core.quotePath=false" "diff" "--no-index"
                                     (concat diff-options ["--" "/dev/null" file]))]
    (if (#{0 1} exit)
      out
      (throw (outcome/usage-error (str "git diff --no-index " file ": " (str/trim err)))))))

(defn- indexed-files [root env]
  (set (str/split (git/git ["ls-files" "-z" "--cached" "--" "*.rs"] :dir root :env env)
                  #"\u0000")))

(defn patch [{:keys [root] :as project} dir files]
  (let [base (project/merge-base project)]
    (if (empty? files)
      ""
      (let [scratch (artifacts/run-dir project "rust-diff")
            env {"GIT_INDEX_FILE" (str (fs/path scratch "index"))}
            relative #(str (fs/relativize dir (fs/path root %)))]
        (try
          ;; Compare the merge base directly with the current files, even after a staged removal.
          (git/git ["-c" "core.splitIndex=false" "read-tree" base] :dir root :env env)
          (let [indexed (indexed-files root env)]
            (apply str
                   (concat (tracked-patches dir env (map relative (set/intersection files indexed)))
                           (map #(untracked-patch dir (relative %))
                                (sort (set/difference files indexed))))))
          (finally (fs/delete-tree scratch)))))))

(defn changed-lines [{:keys [root all] :as project} files]
  (when-not all
    (changes/changed-lines-from-diff (patch project root files))))
