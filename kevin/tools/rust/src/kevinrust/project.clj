(ns kevinrust.project
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [kevincore.git :as git]
            [kevincore.outcome :as outcome]))

(defn- cargo-output [& args]
  (let [{:keys [exit out err]} (apply process/shell
                                     {:out :string :err :string :continue true}
                                     "cargo" args)]
    (if (zero? exit)
      (str/trim out)
      (throw (outcome/usage-error (str "cargo " (str/join " " args) ": " (str/trim err)))))))

(defn- workspace-packages [metadata]
  (let [members (set (get metadata "workspace_members"))]
    (filterv #(members (get % "id")) (get metadata "packages"))))

(defn select-packages [metadata {:keys [workspace packages excludes]}]
  (let [members (workspace-packages metadata)
        by-name (into {} (map (juxt #(get % "name") identity)) members)
        defaults (set (get metadata "workspace_default_members"))
        excluded (set excludes)]
    (doseq [name (concat packages excludes)]
      (when-not (contains? by-name name)
        (throw (outcome/usage-error (str "unknown Cargo package: " name)))))
    (let [selected (cond
                     workspace (remove #(excluded (get % "name")) members)
                     (seq packages) (map by-name (distinct packages))
                     :else (filter #(defaults (get % "id")) members))]
      (when (empty? selected)
        (throw (outcome/usage-error "Cargo selection has no packages")))
      (vec selected))))

(defn- canonical [path]
  (str (fs/canonicalize path)))

(defn- inside? [path root]
  (fs/starts-with? path root))

(defn- manifest-path [root requested]
  (let [path (or requested (cargo-output "locate-project" "--message-format" "plain"))]
    (when-not (and (fs/regular-file? path) (inside? (canonical path) root))
      (throw (outcome/usage-error (str "Cargo manifest must be a file in the Git worktree: " path))))
    (canonical path)))

(defn load-project [opts]
  (let [root (canonical (git/repo-root))
        manifest (manifest-path root (:manifest opts))
        metadata-text (cargo-output "metadata" "--no-deps" "--format-version" "1"
                                    "--manifest-path" manifest)
        metadata (try (json/parse-string metadata-text)
                      (catch Exception _
                        (throw (outcome/usage-error "cargo metadata returned invalid JSON"))))
        workspace (get metadata "workspace_root")
        packages (select-packages metadata opts)]
    (when-not (and workspace (inside? (canonical workspace) root))
      (throw (outcome/usage-error (str "Cargo workspace must be in the Git worktree: " workspace))))
    (doseq [package packages
            :let [path (get package "manifest_path")]]
      (when-not (inside? (canonical path) root)
        (throw (outcome/usage-error (str "Cargo member must be in the Git worktree: " path)))))
    (assoc opts :root root :manifest manifest :metadata metadata :workspace-root workspace
           :selected (mapv #(get % "name") packages))))

(def ^:private separate-kinds #{"test" "bench" "example" "custom-build"})
(def ^:private excluded-dirs #{"tests" "benches" "examples" "target" "tmp"})

(defn- package-scope [package]
  {:name (get package "name")
   :dir (str (fs/parent (get package "manifest_path")))
   :separate (into #{} (comp (filter #(some separate-kinds (get % "kind")))
                             (map #(get % "src_path")))
                   (get package "targets"))})

(defn- production-source? [path {:keys [dir separate]}]
  (let [relative (fs/relativize dir path)]
    (and (not (excluded-dirs (str (first (iterator-seq (.iterator relative))))))
         (not (separate path)))))

(defn production-files [root metadata selected candidates]
  (let [scopes (sort-by (comp - count :dir) (map package-scope (workspace-packages metadata)))
        selected (set selected)
        generated (keep metadata ["target_directory" "build_directory"])]
    (into #{}
          (filter (fn [file]
                    (let [path (str (fs/path root file))
                          owner (some #(when (inside? path (:dir %)) %) scopes)]
                      (and (selected (:name owner))
                           (str/ends-with? file ".rs")
                           (not-any? #(inside? path %) generated)
                           (production-source? path owner)))))
          candidates)))

(defn untracked [root]
  (set (remove empty? (str/split (git/git ["ls-files" "-z" "--others" "--exclude-standard" "--" "*.rs"]
                                         :dir root)
                                #"\u0000"))))

(defn source-files [{:keys [root metadata selected]}]
  (let [tracked (str/split (git/git ["ls-files" "-z" "--cached" "--" "*.rs"] :dir root) #"\u0000")
        files (filter (fn [file]
                        (let [path (fs/path root file)]
                          (and (fs/regular-file? path {:nofollow-links true})
                               (= (str path) (canonical path)))))
                      (concat tracked (untracked root)))]
    (production-files root metadata selected files)))

(defn merge-base [{:keys [root base]}]
  (str/trim (git/git ["merge-base" (or base (git/default-base root)) "HEAD"] :dir root)))
