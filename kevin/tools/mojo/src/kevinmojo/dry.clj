(ns kevinmojo.dry
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [kevinmojo.project :as project]
            [kevincore.git :as git]
            [kevincore.jscpd :as jscpd]
            [kevincore.shell :as shell]))

(def ^:private generated-dirs
  #{".kevin" ".metrics" ".pixi" ".venv" ".worktrees" "build" "dist" "generated"
    "node_modules" "target" "tmp"})

(defn- git-files [args]
  (remove str/blank? (str/split (git/git (into ["ls-files" "-z"] args)) #"\x00")))

(defn- eligible-files [root]
  (let [ignored (set (git-files ["--cached" "--ignored" "--exclude-standard" "--" "*.mojo"]))]
    (->> (git-files ["--cached" "--others" "--exclude-standard" "--" "*.mojo"])
         (remove ignored)
         (remove #(some generated-dirs (butlast (str/split % #"/"))))
         (filter (fn [file]
                   (let [path (fs/path root file)]
                     (and (fs/regular-file? path)
                          (not (fs/sym-link? path))
                          (project/inside? root path)))))
         distinct
         sort)))

(defn- parse-options [[option value :as args]]
  (cond
    (empty? args) {}
    (= ["--all"] (vec args)) {:all true}
    (and (= "--base" option) (= 2 (count args)))
    {:base (project/option-value option value)}
    :else (project/fail "dry takes [--all | --base REF]")))

(defn- changed-lines [{:keys [all base]} files]
  (when-not all
    (let [ref (or base (git/first-existing [(System/getenv "KEVIN_BASE") "main" "master"]
                                           #(git/ref-exists? % nil)))]
      (when-not ref
        (project/fail "no base branch found; pass --base REF or set KEVIN_BASE"))
      (git/collect-changed-lines {:base ref :pathspecs ["*.mojo"]
                                  :include-untracked? (set files)}))))

(defn- copy-inputs [root snapshot files]
  (doseq [file files
          :let [dest (fs/path snapshot file)]]
    (fs/create-dirs (fs/parent dest))
    (fs/copy (fs/path root file) dest)))

(defn- scan [root work report files]
  (if (empty? files)
    (spit (str report) (json/generate-string {"duplicates" []}))
    (let [snapshot (fs/create-dir (fs/path work "input"))
          config (fs/path work "jscpd.json")]
      (copy-inputs root snapshot files)
      (spit (str config)
            (json/generate-string {:maxLines Integer/MAX_VALUE
                                   :maxSize (str (apply max (map #(fs/size (fs/path snapshot %)) files)) "b")}))
      (binding [shell/*dir* (str snapshot)]
        (let [{:keys [exit out]} (shell/capture
                                  "jscpd" ["--config" (str config) "--format" "txt"
                                           "--formats-exts" "txt:mojo" "--min-tokens" "30"
                                           "--min-lines" "5" "--silent" "--no-gitignore" "--reporters" "json"
                                           "--output" (str work) "."])]
          (when-not (zero? exit)
            (project/fail (str/trim (str "jscpd exited " exit "\n" out)))))))))

(defn- valid-side? [{:keys [file start end]}]
  (and (string? file) (int? start) (int? end) (<= 1 start end)))

(defn- read-clones [report]
  (when-not (fs/regular-file? report)
    (project/fail "jscpd did not write a JSON report"))
  (let [raw (shell/read-json (str report))
        clones (jscpd/parse-clones raw)]
    (when-not (and (vector? (get raw "duplicates"))
                   (every? valid-side? (mapcat :sides clones)))
      (project/fail "invalid jscpd JSON report"))
    clones))

(defn run-dry [root args]
  (let [opts (parse-options args)]
    (project/require-tool "jscpd")
    (binding [shell/*dir* (str root)]
      (let [files (eligible-files root)
            changed (when-let [lines (changed-lines opts files)]
                      (merge (zipmap files (repeat #{})) lines))
            output (project/output-dir root "jscpd")
            work (project/scratch output "mojo-")
            report (fs/path work "jscpd-report.json")
            destination (fs/path output "jscpd-report.json")]
        (try
          (scan root work report files)
          (let [clones (read-clones report)]
            (fs/move report destination {:replace-existing true :atomic-move true})
            (println (str "DRY report: " destination))
            (shell/emit (jscpd/review clones changed nil)))
          (finally (fs/delete-tree work)))))))
