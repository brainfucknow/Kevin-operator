(ns kevin.convert
  "kevin init --convert: turns a committed Kevin install, as the removed
  install.sh <project> left it, into configuration. It removes the committed
  runtime, Kevin's generated agents, the swarm skills and the Kevin lines of
  the instruction files, stages the change, and then sets the project up as
  kevin init does. HEAD does not move."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [kevin.adapters :as adapters]
            [kevin.agents :as agents]
            [kevin.config :as config]
            [kevin.installed :as installed]
            [kevin.shell :as shell]
            [kevin.task :as task]))

(def runtime-paths
  "The committed runtime, besides the files kevin/*.md other than project.md."
  ["kevin/bin" "kevin/tools" "kevin/roles" "kevin/languages" "kevin/constitution"])

(def old-article "kevin/constitution/articles/project.md")

(def article "kevin/project.md")

(def agent-dirs [".claude/agents" ".github/agents" ".codex/agents" ".kilo/agent" ".vibe/agents"])

(def skill-paths [".claude/skills/swarm" ".agents/skills/swarm" ".vibe/skills/swarm" ".kilo/command/swarm.md"])

(def gitignore-lines
  "The lines of kevin/.gitignore that kept the committed kevin/bin tracked."
  #{"!/bin/" "!/bin/**"})

(def swarm-lines
  "The Kevin line of AGENTS.md, and its form from before the rename."
  #{"Follow kevin/README.md and kevin/roles/orchestrator.md when asked to swarm a feature; delegate subagent roles by name and keep all task bookkeeping in kevin/bin/kevin."
    "Follow swarmforge/README.md and swarmforge/roles/orchestrator.md when asked to swarm a feature; delegate subagent roles by name and keep all task bookkeeping in swarmforge/bin/sf."})

(def import-lines
  "The line of each file that loaded AGENTS.md; it goes when AGENTS.md does."
  {"CLAUDE.md" "@AGENTS.md"
   ".github/copilot-instructions.md" "Follow AGENTS.md at the repository root."})

(defn- git [root & args]
  (shell/chomp (apply shell/must {:dir root} "git" args)))

(defn- tracked [root & paths]
  (shell/lines (apply git root "ls-files" "--" paths)))

(defn digest [text]
  (let [bytes (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes text "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

(defn- normalized
  "The agent text without the lines that a tier or a kind changes: models,
  efforts and tools."
  [path text]
  (str/replace text
               (cond
                 (str/starts-with? path ".vibe/")
                 #"(?m)^(?:active_model = \"[^\"\r\n]*\"|enabled_tools = \[[^\r\n]*\])\n"

                 (str/ends-with? path ".toml")
                 #"(?m)^model_reasoning_effort = \"[^\r\n]*\"\n"

                 :else
                 #"(?m)^(?:model|tools): [^\r\n]*\n")
               ""))

(defn- summaries
  "The summaries the role's agents may carry: from the project's committed
  role prompt and from the runtime's."
  [root role]
  (distinct (for [file [(fs/path root "kevin" "roles" (str role ".md"))
                        (fs/path (installed/home) "roles" (str role ".md"))]
                  :when (fs/exists? file)]
              (agents/summary (slurp (str file))))))

(defn- generated
  "Path to the set of normalized texts that kevin generated for the roles of
  the project's roles.conf, from the current and the legacy templates, with the
  current and the earlier use sentences."
  [root rows]
  (reduce (fn [acc [path text]] (update acc path (fnil conj #{}) (normalized path text)))
          {}
          (for [[role tier] rows
                kind (keys agents/kinds)
                use (agents/uses kind)
                templates ["" "legacy/"]
                summary (summaries root role)
                file (adapters/agent-files role tier kind {:templates templates :summary summary :use use})]
            file)))

(defn- recorded-digests
  "The digests of the agents that kevin adapters recorded in the project."
  [root]
  (set (for [file (shell/state-paths root "adapters.edn")
             :when (fs/regular-file? file)
             :let [owned (try (edn/read-string (slurp (str file))) (catch Exception _ nil))]
             :when (map? owned)
             hash (vals owned)]
         hash)))

(defn- role-of [path]
  (str/replace (fs/file-name path) #"(\.agent)?\.(md|toml)$" ""))

(defn- sorted-agents
  "The committed agents as [kevin's others], with the roles of roles.conf."
  [root]
  (let [rows (config/rows "roles.conf")
        expected (generated root rows)
        digests (recorded-digests root)
        kevin? (fn [path]
                 (let [text (slurp (str (fs/path root path)))]
                   (or (contains? (get expected path) (normalized path text))
                       (contains? digests (digest text)))))
        {kevin true others false} (group-by kevin? (apply tracked root agent-dirs))]
    [kevin others (set (map first rows))]))

(defn- remove-lines
  "Writes file without the lines in drop. Deletes it when nothing else is
  left. Returns true when it changed the file."
  [root path drop]
  (let [file (fs/path root path)]
    (when (fs/regular-file? file)
      (let [text (slurp (str file))
            kept (remove #(contains? drop (str/trim %)) (str/split-lines text))]
        (cond
          (every? str/blank? kept) (do (fs/delete file) true)
          (not= (count kept) (count (str/split-lines text)))
          (do (spit (str file) (str (str/join "\n" kept) "\n")) true))))))

(def ^:private runtime-command
  "A kevin/bin path, with a ./ before it and a bare bash, sh or bb launcher
  before that. The launcher starts a line or follows whitespace or one of
  the shell separators ; | & ( and backtick. A converted project has no
  kevin/bin, so the launcher would name the kevin directory."
  #"(?:(?<![^\s;|&(`])(?:bash|sh|bb) +)?(?:\./)?kevin/bin/kevin(?:-([a-z]+))?\b")

(defn- rewrite-config!
  "Replaces kevin/bin/kevin-<name> with kevin <name> and kevin/bin/kevin with
  kevin, launcher included, in kept configuration other than tools.conf.
  Returns the rewritten paths."
  [root]
  (vec (for [path (conj (vec (filter #(re-matches #"kevin/[^/]+\.conf" %) (tracked root "kevin")))
                        article)
             :when (not= "kevin/tools.conf" path)
             :let [file (fs/path root path)]
             :when (fs/regular-file? file)
             :let [text (slurp (str file))
                   rewritten (str/replace text runtime-command
                                          (fn [[_ name]] (if name (str "kevin " name) "kevin")))]
             :when (not= text rewritten)]
         (do (spit (str file) rewritten)
             (println (str "kevin: rewrote " path))
             path))))

(defn- remove-agents!
  "Removes Kevin's committed agents and names each other agent that sits
  under a role name."
  [root]
  (let [[kevin others roles] (sorted-agents root)]
    (when (seq kevin) (apply git root "rm" "-q" "--" kevin))
    (doseq [path others :when (roles (role-of path))]
      (println (str "kevin: kept " path "; it is not Kevin's generated agent")))))

(defn- remove-runtime!
  "Moves the project article to kevin/project.md, then removes the committed
  runtime, the swarm skills and the kevin/bin lines of kevin/.gitignore."
  [root]
  (when-not (fs/exists? (fs/path root article))
    (git root "mv" old-article article))
  (let [docs (remove #{article} (filter #(re-matches #"kevin/[^/]+\.md" %) (tracked root "kevin")))]
    (apply git root "rm" "-r" "-q" "--ignore-unmatch" "--" (concat runtime-paths docs skill-paths)))
  (when (remove-lines root "kevin/.gitignore" gitignore-lines)
    (git root "add" "-A" "--" "kevin/.gitignore")))

(defn- clean-instructions!
  "Removes the Kevin swarm line from AGENTS.md, and, when that empties it,
  the lines that loaded it."
  [root]
  (when (remove-lines root "AGENTS.md" swarm-lines)
    (git root "add" "-A" "--" "AGENTS.md")
    (when-not (fs/exists? (fs/path root "AGENTS.md"))
      (doseq [[path line] import-lines
              :when (remove-lines root path #{line})]
        (git root "add" "-A" "--" path)))))

(defn- refuse-changes! [root]
  (when (seq (git root "status" "--porcelain" "--untracked-files=no"))
    (shell/die "commit or stash your changes before kevin init --convert")))

(defn convert
  "Converts the main checkout's committed install, when it has one, then sets
  the project up as kevin init does, and stages every change."
  []
  (let [root (shell/repo-root)]
    (when (task/committed-install? root)
      (refuse-changes! root)
      (binding [config/*root* root]
        (remove-agents! root))
      (remove-runtime! root)
      (clean-instructions! root)
      (when-let [paths (seq (rewrite-config! root))]
        (apply git root "add" "--" paths)))
    (task/setup!)
    (when (fs/exists? (fs/path root article))
      (git root "add" "--" article))))
