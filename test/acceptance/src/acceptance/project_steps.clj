(ns acceptance.project-steps
  "Step handlers for test/features/project (see its README for the step
  meanings): a scratch Kevin runtime whose kevin is on PATH through a link,
  and a separate scratch project repository."
  (:require [acceptance.support :as support :refer [check unescape]]
            [babashka.fs :as fs]
            [clj-yaml.core :as yaml]
            [clojure.string :as str]))

(defn new-world [] {:ids {}})

(defn- scratch-dir [prefix]
  (str (fs/create-dirs (fs/path @support/scratch (str prefix "-" (random-uuid))))))

(defn- base-path [] (get (support/clean-env nil) "PATH"))

(defn- env [world]
  (cond-> {"PATH" (if (:no-kevin world) (base-path) (str (:bin world) ":" (base-path)))}
    (:unset world) (assoc :unset (:unset world))))

(defn- run
  "Runs cmd in dir with the world's PATH; a kevin command runs the link on
  that PATH. The variables the world unset are left out."
  [world dir cmd]
  (let [extra (dissoc (env world) :unset)
        cmd (if (= "kevin" (first cmd)) (cons (str (fs/path (:bin world) "kevin")) (rest cmd)) cmd)]
    (if (:unset world)
      (let [environment (apply dissoc (merge (support/clean-env nil) extra) (:unset world))]
        (babashka.process/shell {:cmd (vec cmd) :dir (str dir) :out :string :err :string :continue true
                                 :env environment}))
      (support/run dir cmd extra))))

(defn- must [world dir & cmd]
  (let [{:keys [exit out err]} (run world dir cmd)]
    (check (zero? exit) (str (str/join " " cmd) " exited " exit ": " (str/trim (str out err))))
    (str/trim-newline out)))

(defn- git [world & args] (apply must world (:repo world) "git" args))

(defn- fill-ids [world text]
  (str/replace text #"\$([A-Z])" (fn [[whole name]] (or (get-in world [:ids name]) whole))))

(defn- words [world command]
  (str/split (fill-ids world command) #" "))

(defn- worktree [world name]
  (str (fs/path (:repo world) ".worktrees" (get-in world [:ids name]))))

(defn- write! [dir path text]
  (let [file (fs/path dir path)]
    (fs/create-dirs (fs/parent file))
    (spit (str file) text)))

(defn- read-file [world path]
  (str/trim-newline (slurp (str (fs/path (:repo world) path)))))

(defn- lines [text] (str/split-lines (str text)))

(defn- exclude-file [world]
  (git world "rev-parse" "--path-format=absolute" "--git-path" "info/exclude"))

(defn- snapshot
  "The repository's files, index, HEAD, exclude file and hook."
  [world]
  (let [repo (:repo world)]
    {:files (into {} (for [file (fs/glob repo "**" {:hidden true})
                           :let [path (str (fs/relativize repo file))]
                           :when (and (fs/regular-file? file) (not (str/starts-with? path ".git/")))]
                       [path (slurp (str file))]))
     :index (git world "ls-files" "-s")
     :head (git world "rev-parse" "HEAD")
     :git (into {} (for [path ["info/exclude" "hooks/commit-msg"]
                         :let [file (fs/path repo ".git" path)]]
                     [path (when (fs/exists? file) (slurp (str file)))]))}))

(defn- run-kevin
  "Runs the kevin command in dir, times times, keeping the last result and a
  snapshot of the repository from before the first run."
  [world dir command times]
  (let [before (when (:repo world) (snapshot world))
        results (doall (repeatedly times #(run world dir (words world command))))]
    (merge world (select-keys (last results) [:exit :out :err]) {:before before})))

(defn- index-paths [world] (set (lines (git world "ls-files"))))

(defn- quoted [text] (map second (re-seq #"\"([^\"]+)\"" text)))

(defn- runtime!
  "A scratch copy of the runtime at version, with a bin directory whose kevin
  links to it."
  [world version]
  (let [runtime (support/plugin-copy (scratch-dir "runtime") version)
        bin (scratch-dir "bin")]
    (fs/create-sym-link (fs/path bin "kevin") (fs/path runtime "kevin" "bin" "kevin"))
    (assoc world :runtime runtime :bin bin)))

(defn- repository! [world]
  (let [repo (scratch-dir "project")
        gitconfig (fs/path (support/home) ".gitconfig")]
    (when-not (fs/exists? gitconfig)
      (fs/create-dirs (support/home))
      (spit (str gitconfig) (str "[user]\n\tname = tester\n\temail = tester@example.com\n" support/gitconfig)))
    (support/must repo "git" "init" "-q" ".")
    (spit (str (fs/path repo "README.md")) "project\n")
    (support/must repo "git" "add" "-A")
    (support/must repo "git" "commit" "-q" "-m" "Start")
    (assoc world :repo repo)))

(def swarm-line
  "The Kevin line that the removed install.sh <project> put in AGENTS.md."
  "Follow kevin/README.md and kevin/roles/orchestrator.md when asked to swarm a feature; delegate subagent roles by name and keep all task bookkeeping in kevin/bin/kevin.")

(def ^:private legacy-agents
  "Writes the agents that the removed install.sh generated with the base-commit
  templates, for the roles of the project's committed roles.conf. Run by bb
  with the runtime's kevin source on the classpath."
  "(require '[kevin.adapters :as a] '[kevin.agents :as g] '[kevin.config :as c] '[kevin.installed :as r] '[babashka.fs :as fs])
   (let [root (first *command-line-args*)]
     (with-redefs [r/home (constantly (str root \"/kevin\"))]
       (binding [c/*root* root]
         (doseq [[role tier :as row] (c/rows \"roles.conf\")
                 [path text] (a/agent-files role tier (g/kind row)
                                            {:templates \"legacy/\"
                                             :summary (g/summary (slurp (str root \"/kevin/roles/\" role \".md\")))})]
           (fs/create-dirs (fs/parent (fs/path root path)))
           (spit (str (fs/path root path)) text)))))")

(defn- committed-install!
  "The layout the removed install.sh <project> committed in the repository:
  the runtime under kevin/ with kevin/constitution/articles/project.md, the
  configuration, the generated agents, the swarm skills and command, the
  kevin/.gitignore lines and the instruction lines."
  [world]
  (let [repo (:repo world)
        kevin (fs/path repo "kevin")]
    (fs/copy-tree (fs/path (:runtime world) "kevin") kevin)
    (fs/move (fs/path kevin "project.md") (fs/path kevin "constitution" "articles" "project.md"))
    (fs/delete-if-exists (fs/path kevin "verify.conf"))
    (write! repo "kevin/.gitignore" "!/bin/\n!/bin/**\n")
    (doseq [path [".claude/skills/swarm/SKILL.md" ".agents/skills/swarm/SKILL.md" ".vibe/skills/swarm/SKILL.md"]]
      (write! repo path "---\nname: swarm\n---\n\nAct as the Kevin orchestrator. Read `kevin/roles/orchestrator.md`.\n"))
    (write! repo ".kilo/command/swarm.md" "Act as the Kevin orchestrator. Read `kevin/roles/orchestrator.md`.\n")
    (write! repo "AGENTS.md" (str swarm-line "\n"))
    (write! repo "CLAUDE.md" "@AGENTS.md\n")
    (write! repo ".github/copilot-instructions.md" "Follow AGENTS.md at the repository root.\n")
    (write! repo ".gitignore" ".worktrees/\n.kevin/\ntmp/\n.metrics/\n")
    (let [src (fs/path (:runtime world) "kevin" "tools" "kevin")]
      (support/must repo "bb" "-cp" (str (fs/path src "src") ":" (fs/path src "resources"))
                    "-e" legacy-agents repo))
    (support/must repo "git" "add" "-A")
    (support/must repo "git" "commit" "-q" "-m" "Committed Kevin install")
    world))

(defn- commit-file!
  "Commits path holding text, with SWARM read as the old Kevin swarm line."
  [world path text]
  (write! (:repo world) path (str (str/replace (unescape text) "SWARM" swarm-line) "\n"))
  (git world "add" "-A")
  (git world "commit" "-q" "--allow-empty" "-m" (str "Change " path))
  world)

(defn- place [world where]
  (case where
    "in the repository" (:repo world)
    "in the worktree of a new task" (let [id (must world (:repo world) "kevin" "new" "Where" "-m" "Where" "--route" "coder")]
                                      (must world (:repo world) "kevin" "start" id)
                                      (str (fs/path (:repo world) ".worktrees" id)))
    "outside any git repository" (scratch-dir "outside")))

(def ^:private agent-dirs [".claude/agents" ".github/agents" ".codex/agents"])

(defn- project-agents
  "The files under the project agent directories of the main checkout that
  hold the kevin-generated line."
  [world]
  (sort (for [dir agent-dirs
              :let [path (fs/path (:repo world) dir)]
              :when (fs/directory? path)
              file (fs/list-dir path)
              :when (str/includes? (slurp (str file)) "kevin-generated:")]
          (str dir "/" (fs/file-name file)))))

(defn- status-paths
  "The paths that git status in the repository lists, each untracked file on its own."
  [world]
  (map #(subs % 3) (lines (git world "status" "--porcelain" "--untracked-files=all"))))

(defn- recorder!
  "A claude in the world's bin directory that writes its arguments, each
  ended by a NUL, to a file, and exits 0."
  [world]
  (let [record (str (fs/path (:bin world) "claude.args"))
        script (fs/path (:bin world) "claude")]
    (spit (str script) (str "#!/bin/sh\nfor a in \"$@\"; do printf '%s\\0' \"$a\"; done > '" record "'\n"))
    (fs/set-posix-file-permissions script "rwxr-xr-x")
    (assoc world :recorded record)))

(defn- recorded-prompt [world]
  (last (str/split (slurp (:recorded world)) #"\u0000")))

(defn- agent-description
  "The description of the project agent: YAML front matter of a Markdown
  agent, or a TOML agent read by Python's tomllib."
  [world path]
  (let [file (str (fs/path (:repo world) path))]
    (if (str/ends-with? path ".toml")
      (must world (:repo world) "python3" "-I" "-c"
            "import sys, tomllib; print(tomllib.load(open(sys.argv[1], 'rb'))['description'])" file)
      (let [[_ front] (re-find #"(?s)\A---\n(.*?)\n---\n" (slurp file))]
        (:description (yaml/parse-string front))))))

(defn- has-line? [text line] (some #{line} (lines text)))

(defn- has-check [has found what]
  (check (if (= "has" has) found (not found)) what))

(def handlers
  [;; Given
   [#"a Kevin runtime at version \"([^\"]+)\" with its kevin on PATH"
    (fn [world version] (runtime! world version))]

   [#"a git repository with one commit and no kevin"
    (fn [world] (repository! world))]

   [#"a git repository with a committed Kevin install"
    (fn [world] (committed-install! (repository! world)))]

   [#"the repository has a committed Kevin install"
    (fn [world] (committed-install! world))]

   [#"I ran \"(kevin [^\"]+)\""
    (fn [world command]
      (let [out (apply must world (:repo world) (words world command))]
        (cond-> world
          (str/starts-with? command "kevin new ") (assoc-in [:ids "A"] out))))]

   [#"I ran \"git add -A\" and \"git commit -m config\" in the repository"
    (fn [world]
      (git world "add" "-A")
      (git world "commit" "-q" "-m" "config")
      world)]

   [#"the file \"([^\"]+)\" in the runtime holds \"(.*)\""
    (fn [world path text]
      (write! (:runtime world) path (str (unescape text) "\n"))
      world)]

   [#"the project routes.conf is missing"
    (fn [world]
      (fs/delete-if-exists (fs/path (:repo world) "kevin" "routes.conf"))
      world)]

   [#"the project routes.conf holds \"(.*)\""
    (fn [world text]
      (write! (:repo world) "kevin/routes.conf" (str text "\n"))
      world)]

   [#"the file \"([^\"]+)\" in the repository holds the runtime's roles.conf with the row \"(.*)\""
    (fn [world path row]
      (write! (:repo world) path (str (slurp (str (fs/path (:runtime world) "kevin" "roles.conf"))) row "\n"))
      world)]

   [#"the file \"([^\"]+)\" in the repository holds \"(.*)\""
    (fn [world path text]
      (if (contains? world :exit)
        (let [actual (read-file world path)]
          (check (= (unescape text) actual) (str path " holds " (pr-str actual))))
        (write! (:repo world) path (str (unescape text) "\n")))
      world)]

   [#"the committed file \"([^\"]+)\" holds \"(.*)\""
    (fn [world path text] (commit-file! world path text))]

   [#"the committed file \"([^\"]+)\" was changed and not committed"
    (fn [world path]
      (spit (str (fs/path (:repo world) path)) "changed\n" :append true)
      world)]

   [#"task \"(\w)\" titled \"([^\"]+)\" is at stage \"done\" with one commit"
    (fn [world name title]
      (let [id (must world (:repo world) "kevin" "new" title "-m" title "--route" "coder")
            world (assoc-in world [:ids name] id)]
        (must world (:repo world) "kevin" "start" id)
        (must world (worktree world name) "git" "commit" "-q" "--allow-empty" "-m" title)
        (let [commit (must world (worktree world name) "git" "rev-parse" "HEAD")]
          (must world (:repo world) "kevin" "handoff" id "-m" "done")
          (assoc-in world [:commits name] commit))))]

   [#"task \"(\w)\" titled \"([^\"]+)\" was started on route \"([^\"]+)\" before the conversion"
    (fn [world name title route]
      (let [id (must world (:repo world) "kevin" "new" title "-m" title "--route" route)]
        (must world (:repo world) "kevin" "start" id)
        (assoc-in world [:ids name] id)))]

   [#"task \"(\w)\" has a new commit in its worktree that does not touch kevin/"
    (fn [world name]
      (let [dir (worktree world name)]
        (write! dir "login.txt" "login\n")
        (must world dir "git" "add" "login.txt")
        (must world dir "git" "commit" "-q" "-m" "Add login")
        (assoc-in world [:commits name] (must world dir "git" "rev-parse" "HEAD"))))]

   [#"no directory on PATH holds kevin"
    (fn [world] (assoc world :no-kevin true))]

   [#"MISTRAL_API_KEY is not set"
    (fn [world] (assoc world :unset ["MISTRAL_API_KEY"]))]

   [#"the project roles.conf holds the runtime's with the row of \"([^\"]+)\" as \"(.*)\""
    (fn [world role row]
      (let [runtime (slurp (str (fs/path (:runtime world) "kevin" "roles.conf")))
            text (str/join "\n" (for [line (lines runtime)]
                                  (if (= role (first (str/split (str/trim line) #"\s+"))) row line)))]
        (write! (:repo world) "kevin/roles.conf" (str text "\n"))
        world))]

   [#"the file \"([^\"]+)\" in the repository is deleted"
    (fn [world path]
      (fs/delete (fs/path (:repo world) path))
      world)]

   [#"the runtime moves to version \"([^\"]+)\""
    (fn [world version]
      (let [manifest (str (fs/path (:runtime world) ".claude-plugin" "plugin.json"))]
        (spit manifest (str/replace (slurp manifest) #"\"version\": \"[^\"]*\"" (str "\"version\": \"" version "\"")))
        world))]

   [#"a claude on PATH that records its arguments"
    (fn [world] (recorder! world))]

   ;; When
   [#"I run \"(kevin [^\"]+)\"(?: (\d+) times)?"
    (fn [world command times]
      (run-kevin world (:repo world) command (if times (parse-long times) 1)))]

   [#"I run \"(kevin [^\"]+)\" (in the repository|in the worktree of a new task|outside any git repository)"
    (fn [world command where]
      (run-kevin world (place world where) command 1))]

   [#"I run \"(kevin [^\"]+)\" in the worktree of task \"(\w)\""
    (fn [world command name]
      (let [dir (worktree world name)]
        (when-not (fs/exists? dir)
          (git world "worktree" "add" "-q" dir (str "swarm/" (get-in world [:ids name]))))
        (run-kevin world dir command 1)))]

   [#"I commit a change in the worktree of task \"(\w)\" with the message \"([^\"]+)\""
    (fn [world name message]
      (let [dir (worktree world name)]
        (write! dir "change.txt" "change\n")
        (must world dir "git" "add" "change.txt")
        (merge world (select-keys (run world dir ["git" "commit" "-q" "-m" message]) [:exit :out :err]))))]

   [#"I commit every change in the main checkout"
    (fn [world]
      (git world "add" "-A")
      (git world "commit" "-q" "-m" "Convert")
      world)]

   [#"I run \"kevin (\w+)\" and the runtime's \"([^\"]+)\" in the repository"
    (fn [world name program]
      (let [mine (run world (:repo world) ["kevin" name])
            theirs (run world (:repo world) [(str (fs/path (:runtime world) program))])]
        (assoc world :pair [(select-keys mine [:exit :out :err]) (select-keys theirs [:exit :out :err])])))]

   ;; Then
   [#"the exit status is (\d+)"
    (fn [world status]
      (check (= (parse-long status) (or (:exit world) 0))
             (str "exit status " (:exit world) ", stderr: " (str/trim (str (:err world)))))
      world)]

   [#"the exit status of \"kevin \w+\" is (\d+)"
    (fn [world status]
      (check (= (parse-long status) (:exit (first (:pair world)))) (str "exit status " (:exit (first (:pair world)))))
      world)]

   [#"both have the same output, error output and exit status"
    (fn [world]
      (let [[mine theirs] (:pair world)]
        (check (= mine theirs) (str "kevin gave " (pr-str mine) ", the runtime command " (pr-str theirs)))
        world))]

   [#"the output is the runtime's absolute \"kevin\" directory"
    (fn [world]
      (check (= (str (fs/canonicalize (fs/path (:runtime world) "kevin"))) (str/trim (:out world)))
             (str "output was " (pr-str (:out world))))
      world)]

   [#"the output is \"(.*)\" and the absolute path of \"([^\"]+)\" in the (repository|runtime)"
    (fn [world text path place]
      (let [squeeze #(str/replace (str/trim %) #"\s+" " ")
            file (fs/path (if (= "runtime" place) (:runtime world) (:repo world)) path)]
        (check (some #(= (squeeze (str text %)) (squeeze (:out world))) [file (fs/canonicalize file)])
               (str "output was " (pr-str (:out world)))))
      world)]

   [#"the output is \"(.*)\""
    (fn [world text]
      (let [squeeze #(str/replace (str/trim %) #"\s+" " ")]
        (check (= (squeeze text) (squeeze (:out world))) (str "output was " (pr-str (:out world)))))
      world)]

   [#"the output has a line that starts with \"(.*)\""
    (fn [world prefix]
      (check (some #(str/starts-with? % prefix) (lines (:out world))) (str "output was " (pr-str (:out world))))
      world)]

   [#"the output (has|does not have) the line \"(.*)\""
    (fn [world has line]
      (has-check has (has-line? (:out world) (fill-ids world line)) (str "output was " (pr-str (:out world))))
      world)]

   [#"the error output (has|does not have) the line \"(.*)\""
    (fn [world has line]
      (has-check has (has-line? (:err world) line) (str "error output was " (pr-str (:err world))))
      world)]

   [#"the main checkout was put on the new branch \"([^\"]+)\""
    (fn [world branch]
      (git world "checkout" "-q" "-b" branch)
      world)]

   [#"the branch \"([^\"]+)\" has the commit of task \"(\w)\""
    (fn [world branch name]
      (let [commit (get-in world [:commits name])]
        (check (zero? (:exit (run world (:repo world) ["git" "merge-base" "--is-ancestor" commit branch])))
               (str commit " is not on " branch))
        world))]

   [#"git add -A in the repository stages exactly \"([^\"]+)\""
    (fn [world path]
      (git world "add" "-A")
      (let [staged (git world "diff" "--cached" "--name-only")]
        (check (= path staged) (str "staged " (pr-str staged)))
        world))]

   [#"git add -A in the repository stages nothing more"
    (fn [world]
      (let [before (git world "diff" "--cached" "--name-status")]
        (git world "add" "-A")
        (check (= before (git world "diff" "--cached" "--name-status")) "git add -A staged more")
        world))]

   [#"the file \"([^\"]+)\" in the repository has the line \"(.*)\"( once)?"
    (fn [world path line once]
      (let [n (count (filter #{line} (lines (read-file world path))))]
        (check (if once (= 1 n) (pos? n)) (str path " has the line " n " times"))
        world))]

   [#"the shared exclude file has the lines (.*)"
    (fn [world text]
      (let [found (set (lines (slurp (exclude-file world))))]
        (doseq [line (quoted text)]
          (check (found line) (str "no line " line)))
        world))]

   [#"the shared exclude file has no line twice"
    (fn [world]
      (let [twice (keep (fn [[line n]] (when (and (> n 1) (not (str/starts-with? line "#"))) line))
                        (frequencies (lines (slurp (exclude-file world)))))]
        (check (empty? twice) (str "repeats " (pr-str twice)))
        world))]

   [#"the commit-msg hook has the line \"(.*)\""
    (fn [world line]
      (let [hook (fs/path (git world "rev-parse" "--path-format=absolute" "--git-common-dir") "hooks" "commit-msg")]
        (check (has-line? (slurp (str hook)) line) "the hook lacks the line")
        world))]

   [#"the last commit message in the worktree of task \"(\w)\" has the line \"(.*)\""
    (fn [world name line]
      (check (has-line? (must world (worktree world name) "git" "log" "-1" "--format=%B") line) "no such line")
      world)]

   [#"the repository did not change"
    (fn [world]
      (check (= (:before world) (snapshot world)) "the repository changed")
      world)]

   [#"HEAD did not move"
    (fn [world]
      (check (= (get-in world [:before :head]) (git world "rev-parse" "HEAD")) "HEAD moved")
      world)]

   [#"the index has no path under (.*)"
    (fn [world text]
      (let [found (filter (fn [path] (some #(str/starts-with? path (str % "/")) (quoted text))) (index-paths world))]
        (check (empty? found) (str "index holds " (pr-str (take 5 found))))
        world))]

   [#"the index has no path (\".*)"
    (fn [world text]
      (let [found (filter (index-paths world) (quoted text))]
        (check (empty? found) (str "index holds " (pr-str found)))
        world))]

   [#"the project agents are \"([^\"]+)\""
    (fn [world text]
      (let [expected (if (= "none" text) [] (sort (str/split text #",\s*")))]
        (check (= expected (project-agents world)) (str "project agents " (pr-str (project-agents world))))
        world))]

   [#"git status in the repository shows no project agent"
    (fn [world]
      (let [shown (filter (fn [path] (some #(str/starts-with? path (str % "/")) agent-dirs)) (status-paths world))]
        (check (empty? shown) (str "git status shows " (pr-str shown)))
        world))]

   [#"git status in the repository shows the path \"([^\"]+)\""
    (fn [world path]
      (check (some #{path} (status-paths world)) (str "git status shows " (pr-str (status-paths world))))
      world)]

   [#"the file \"([^\"]+)\" in the repository has one line that holds \"(.*)\""
    (fn [world path text]
      (let [n (count (filter #(str/includes? % text) (lines (read-file world path))))]
        (check (= 1 n) (str path " has " n " lines that hold " text))
        world))]

   [#"the recorded prompt names the absolute path of \"([^\"]+)\" in the (repository|runtime)"
    (fn [world path place]
      (let [file (fs/path (if (= "runtime" place) (:runtime world) (:repo world)) path)
            prompt (recorded-prompt world)]
        (check (some #(str/includes? prompt (str %)) [file (fs/canonicalize file)])
               (str "the prompt was " (pr-str prompt)))
        world))]

   [#"the \"description\" of the project agent \"([^\"]+)\" contains \"(.*)\""
    (fn [world path text]
      (let [description (agent-description world path)]
        (check (str/includes? (str description) text) (str "the description was " (pr-str description)))
        world))]

   [#"the repository has no path \"([^\"]+)\""
    (fn [world path]
      (check (not (fs/exists? (fs/path (:repo world) path))) (str path " exists"))
      world)]

   [#"the repository has the file \"([^\"]+)\" holding \"(.*)\""
    (fn [world path text]
      (let [actual (read-file world path)]
        (check (= (unescape text) actual) (str path " holds " (pr-str actual)))
        world))]])
