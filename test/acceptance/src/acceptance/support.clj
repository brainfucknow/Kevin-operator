(ns acceptance.support
  "Scratch repositories for the step handlers. Each execution works in a fresh
  copy of a scratch git repository that holds this checkout's kevin/,
  without its verify.conf, its .claude-plugin/ (the runtime version), and a
  .gitignore. Its kevin/bin/kevin is the runtime and the project at once. The real kevin and git run there,
  with git-bug off PATH. Scratch files go under ./tmp/acceptance/ and are
  removed when the process exits."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def checkout
  (str (fs/canonicalize (fs/path (fs/parent *file*) ".." ".." ".." ".."))))

(def scratch
  (delay (let [base (fs/path checkout "tmp" "acceptance")
               _ (fs/create-dirs base)
               dir (str (fs/canonicalize (fs/create-temp-dir {:dir base :prefix "work-"})))]
           (.addShutdownHook (Runtime/getRuntime) (Thread. #(fs/delete-tree dir)))
           dir)))

(defn- path-without-git-bug
  "PATH with each directory that holds git-bug replaced by a scratch directory
  of links to its other programs."
  []
  (str/join ":" (for [[index dir] (map-indexed vector (str/split (or (System/getenv "PATH") "") #":"))]
                  (if-not (fs/exists? (fs/path dir "git-bug"))
                    dir
                    (let [links (fs/path @scratch "path" (str index))]
                      (when-not (fs/exists? links)
                        (fs/create-dirs links)
                        (doseq [program (fs/list-dir dir)
                                :when (not= "git-bug" (str (fs/file-name program)))]
                          (fs/create-sym-link (fs/path links (fs/file-name program)) program)))
                      (str links))))))

(def ^:private path (delay (path-without-git-bug)))

(defn home [] (str (fs/path @scratch "home")))

(defn clean-env
  "The fixture environment, using only the selected scratch home's Git config,
  without the session's KEVIN_, legacy SWARMFORGE_, GIT_, CLAUDECODE and
  CODEX_THREAD_ID variables. A scenario sets those through extra-env."
  [home-dir]
  (-> (into {} (remove (fn [[k _]] (re-find #"^(KEVIN_|SWARMFORGE_|GIT_|CLAUDECODE$|CODEX_THREAD_ID$)" k)) (System/getenv)))
      (assoc "HOME" (or home-dir (home)) "PATH" @path
             "GIT_CONFIG_NOSYSTEM" "1"
             "GIT_CONFIG_GLOBAL" (str (fs/path (or home-dir (home)) ".gitconfig")))))

(defn run
  "Runs cmd in dir and returns {:exit :out :err}. extra-env is added to the
  clean environment; its \"HOME\" replaces the scratch home."
  ([dir cmd] (run dir cmd {}))
  ([dir cmd extra-env]
   (p/shell {:cmd (vec cmd) :dir (str dir) :out :string :err :string :continue true
             :env (merge (clean-env (get extra-env "HOME")) extra-env)})))

(defn must [dir & cmd]
  (let [{:keys [exit out err]} (run dir cmd)]
    (when-not (zero? exit)
      (throw (ex-info (str (str/join " " cmd) " exited " exit ": " (str/trim (str out err))) {})))
    (str/trim-newline out)))

(defn kevin-path [repo] (str (fs/path repo "kevin" "bin" "kevin")))

(declare plugin-copy)

(defn- runtime-version
  "The version of this checkout's plugin manifest."
  []
  (second (re-find #"\"version\": \"([^\"]*)\"" (slurp (str (fs/path checkout ".claude-plugin" "plugin.json"))))))

(def gitconfig "[init]\n\tdefaultBranch = main\n")

(def ^:private seed
  (delay (let [repo (str (fs/path @scratch "seed"))]
           (fs/create-dirs (home))
           (fs/create-dirs repo)
           (spit (str (fs/path (home) ".gitconfig"))
                 (str "[user]\n\tname = tester\n\temail = tester@example.com\n" gitconfig))
           (must repo "git" "init" "-q" ".")
           (plugin-copy repo (runtime-version))
           (fs/delete-if-exists (fs/path repo "install.sh"))
           (fs/delete-if-exists (fs/path repo "kevin" "verify.conf"))
           (spit (str (fs/path repo ".gitignore")) ".worktrees/\n.kevin/\ntmp/\n")
           (must repo "git" "add" "-A")
           (must repo "git" "commit" "-q" "-m" "seed")
           repo)))

(def ^:private initialized
  (delay (let [repo (str (fs/path @scratch "initialized"))]
           (must @scratch "cp" "-a" @seed repo)
           (must repo (kevin-path repo) "init")
           repo)))

(def ^:private copies (atom 0))

(defn project
  "A fresh copy of the scratch repository, after kevin init when initialized?."
  [initialized?]
  (let [repo (str (fs/path @scratch (str "run-" (swap! copies inc))))]
    (must @scratch "cp" "-a" (if initialized? @initialized @seed) repo)
    repo))

(defn unescape [text]
  (-> text (str/replace "\\n" "\n") (str/replace "\\t" "\t")))

(defn check [ok? message]
  (when-not ok? (throw (ex-info message {}))))

(defn require-ok [{:keys [exit err]} what]
  (when-not (zero? exit)
    (throw (ex-info (str what " exited " exit ": " (str/trim err)) {}))))

(defn plugin-copy
  "A scratch copy of this checkout's Kevin plugin at dir: install.sh, kevin/
  and .claude-plugin/, the files git tracks or does not ignore, with version
  in its plugin manifest. Returns dir."
  [dir version]
  (doseq [path (str/split-lines (must checkout "git" "ls-files" "--cached" "--others" "--exclude-standard"
                                      "--" "install.sh" "kevin" ".claude-plugin"))
          :let [from (fs/path checkout path)]
          :when (fs/exists? from)]
    (fs/create-dirs (fs/parent (fs/path dir path)))
    (fs/copy from (fs/path dir path) {:replace-existing true :copy-attributes true}))
  (let [manifest (str (fs/path dir ".claude-plugin" "plugin.json"))]
    (spit manifest (str/replace (slurp manifest) #"\"version\": \"[^\"]*\"" (str "\"version\": \"" version "\""))))
  (str dir))
