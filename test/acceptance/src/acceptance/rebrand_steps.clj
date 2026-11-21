(ns acceptance.rebrand-steps
  "Step handlers for test/features/rebrand (see its README): the steps of
  acceptance.install-steps, and fixtures in the legacy SwarmForge form made
  with git and plain files, as an old sf wrote them."
  (:require [acceptance.install-steps :as install]
            [acceptance.metrics-steps :as metrics]
            [acceptance.store-steps :as store :refer [expected fill git must-in run-in when-step worktree write-file]]
            [acceptance.support :as support :refer [check unescape]]
            [babashka.fs :as fs]
            [clojure.string :as str]))

(def new-world store/new-world)

(def ^:private swarm-line
  "Follow kevin/README.md and kevin/roles/orchestrator.md when asked to swarm a feature; delegate subagent roles by name and keep all task bookkeeping in kevin/bin/kevin.")

(def ^:private old-swarm-line
  "Follow swarmforge/README.md and swarmforge/roles/orchestrator.md when asked to swarm a feature; delegate subagent roles by name and keep all task bookkeeping in swarmforge/bin/sf.")

(def ^:private old-hook
  "The commit-msg hook as an old sf wrote it."
  (str "#!/usr/bin/env bash\n"
       "# swarmforge commit-msg hook\n"
       "# Adds \"By <role>.\" and a Lore-id to a commit in a task worktree (sf commit-msg),\n"
       "# and refuses one with a SwarmForge-* trailer.\n"
       "msg=\"$1\"\n"
       "top=\"$(git rev-parse --show-toplevel)\"\n"
       "common=\"$(git rev-parse --path-format=absolute --git-common-dir)\"\n"
       "root=\"$(cd \"$common/..\" && pwd)\"\n"
       "case \"$top\" in\n"
       "  \"$root/${SWARMFORGE_WORKTREE_DIR:-.worktrees}\"/*) ;;\n"
       "  *) exit 0 ;;\n"
       "esac\n"
       "role=\"${SWARMFORGE_ROLE:-}\"\n"
       "if [ -z \"$role\" ]; then\n"
       "  role=\"$(\"$root/swarmforge/bin/sf\" stage \"$(basename \"$top\")\" 2>&1)\" || exit 1\n"
       "fi\n"
       "exec \"$root/swarmforge/bin/sf\" commit-msg \"$role\" \"$msg\"\n"))

(defn- after-when?
  "True once the step under test has run: a step that reads as a Given before
  it reads as a Then after it."
  [world]
  (contains? world :exit))

(defn- words
  "The command split on spaces, with '...' kept as one word."
  [command]
  (for [[_ quoted plain] (re-seq #"'([^']*)'|(\S+)" command)]
    (or quoted plain)))

(defn- environment
  "NAME=value pairs separated by spaces, as a map."
  [text]
  (into {} (for [pair (str/split (str/trim (or text "")) #"\s+")
                 :when (seq pair)]
             (str/split pair #"=" 2))))

(defn- hook-file [world]
  (fs/path (:repo world) ".git" "hooks" "commit-msg"))

(defn- hook-text [world]
  (slurp (str (hook-file world))))

(defn- repo-file [world path]
  (fs/path (:repo world) path))

(defn- file-lines [world path]
  (str/split-lines (slurp (str (repo-file world path)))))

;; Legacy records

(defn- legacy-message
  "The message of a record as an old sf wrote it: SwarmForge-* trailers."
  [record id text fields]
  (str "sf " record ": " id
       (when text (str "\n\n" text))
       "\n\nSwarmForge-Task: " id "\nSwarmForge-Record: " record "\n"
       (apply str (for [[key value] fields] (str "SwarmForge-" key ": " value "\n")))))

(defn- commit-on
  "A commit with message, the tree of parent and parent as its parent."
  [world parent message]
  (git world "commit-tree" (str parent "^{tree}") "-p" parent "-m" message))

(defn- record-slug [title]
  (-> (str/lower-case title) (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-|-$" "")))

(defn- legacy-task
  "Task name as an old sf left it: its branch with a created record and one
  handoff record per stage up to stage on route, and its worktree."
  [world name title route stage]
  (let [id (str (record-slug title) "-" (format "%07x" (rand-int 0x10000000)))
        stages (conj (str/split route #",") "done")
        upto (inc (.indexOf stages stage))
        head (git world "rev-parse" "HEAD")
        created (commit-on world head (legacy-message "created" id (str title "\n\n" title)
                                                      [["From" "operator"] ["Stage" (first stages)]
                                                       ["Route" route] ["Needs" "none"]]))
        tip (reduce (fn [parent [from to]]
                      (commit-on world parent (legacy-message "handoff" id (str from " work complete")
                                                              [["From" from] ["Stage" to]
                                                               ["Route" route] ["Needs" "none"]])))
                    created
                    (take (dec upto) (map vector stages (rest stages))))]
    (git world "update-ref" (str "refs/heads/swarm/" id) tip)
    (git world "worktree" "add" "-q" (str (fs/path (:repo world) ".worktrees" id)) (str "swarm/" id))
    (assoc-in world [:ids name] id)))

(defn- legacy-finished
  "Task name handed off to done and merged into the main checkout with a
  legacy finished record, its branch and worktree removed."
  [world name title route]
  (let [world (legacy-task world name title route "done")
        id (store/id-of world name)]
    (git world "worktree" "remove" "--force" (worktree world name))
    (git world "merge" "-q" "--no-ff" (str "swarm/" id) "-m"
         (legacy-message "finished" id nil [["From" "operator"] ["Stage" "done"] ["Route" route]
                                            ["Needs" "none"] ["Merged-Into" "main"]]))
    (git world "branch" "-D" (str "swarm/" id))
    world))

;; A legacy install

(defn- legacy-install
  "A scratch repository with an install as an old install.sh left it: this
  checkout's kevin/ as swarmforge/ with the old command names, the old
  AGENTS.md line, .gitignore entries and commit-msg hook, all committed."
  [world]
  (let [world (assoc world :repo (support/project false))
        repo (:repo world)
        old (fs/path repo "swarmforge")]
    (git world "mv" "kevin" "swarmforge")
    (git world "mv" "swarmforge/bin/kevin" "swarmforge/bin/sf")
    (doseq [file (fs/glob (fs/path old "bin") "kevin-*")
            :let [name (str (fs/file-name file))]]
      (git world "mv" (str "swarmforge/bin/" name) (str "swarmforge/bin/" (str/replace name #"^kevin-" "sf-"))))
    (write-file repo "AGENTS.md" (str old-swarm-line "\n"))
    (write-file repo "CLAUDE.md" "@AGENTS.md\n")
    (write-file repo ".gitignore" ".worktrees/\n.swarmforge/\ntmp/\n.metrics/\n")
    (write-file repo "swarmforge/.gitignore" "!/bin/\n!/bin/**\n")
    (write-file repo ".git/hooks/commit-msg" old-hook)
    (fs/set-posix-file-permissions (hook-file world) "rwxr-xr-x")
    (git world "add" "-A")
    (git world "commit" "-q" "-m" "Install SwarmForge")
    world))

(defn- new-commit
  "Commits a new file in the worktree of task name past the hook and names
  the commit after the task."
  [world name]
  (let [dir (worktree world name)
        file (str "work-" (random-uuid) ".txt")]
    (write-file dir file "work\n")
    (must-in world dir "git" "add" file)
    (must-in world dir "git" "commit" "-q" "--no-verify" "-m" "Work")
    (assoc-in world [:names (str "work-" name)] (must-in world dir "git" "rev-parse" "HEAD"))))

;; Fake claude

(defn- fake-claude
  "A claude on PATH that writes its environment to a file."
  [world]
  (let [dir (str (:repo world) "-bin")
        seen (str (:repo world) "-claude-env")]
    (write-file dir "claude" (str "#!/usr/bin/env bash\nenv > '" seen "'\n"))
    (fs/set-posix-file-permissions (fs/path dir "claude") "rwxr-xr-x")
    (-> world
        (assoc :claude-env seen)
        (assoc-in [:env "PATH"] (str dir ":" (get (support/clean-env nil) "PATH"))))))

(defn- environment-lines [text]
  (into {} (for [line (str/split-lines text)
                 :let [[_ k v] (re-matches #"([A-Za-z_][A-Za-z0-9_]*)=(.*)" line)]
                 :when k]
             [k v])))

(defn- claude-saw [world]
  (environment-lines (slurp (:claude-env world))))

(def ^:private rebrand-handlers
  [;; Given
   [#"a git repository with a legacy install"
    legacy-install]

   [#"a git repository with one commit and no user.name or user.email"
    (fn [world]
      (let [home (str (fs/path @support/scratch (str "home-" (random-uuid))))]
        (write-file home ".gitconfig" support/gitconfig)
        (assoc-in world [:env "HOME"] home)))]

   [#"a project with kevin installed"
    (fn [world] (assoc world :repo (support/project false)))]

   [#"the commit-msg hook is the one an old sf wrote"
    (fn [world]
      (write-file (:repo world) ".git/hooks/commit-msg" old-hook)
      (fs/set-posix-file-permissions (hook-file world) "rwxr-xr-x")
      world)]

   [#"task \"(\w)\" titled \"([^\"]*)\" on route \"([^\"]*)\" has legacy records up to stage \"([^\"]*)\""
    (fn [world name title route stage] (legacy-task world name title route stage))]

   [#"task \"(\w)\" titled \"([^\"]*)\" was started on route \"([^\"]*)\" before the migration"
    (fn [world name title route] (legacy-task world name title route (first (str/split route #","))))]

   [#"task \"(\w)\" titled \"([^\"]*)\" on route \"([^\"]*)\" was finished with legacy records"
    (fn [world name title route] (legacy-finished world name title route))]

   [#"task \"(\w)\" has a new commit in its worktree(?: that does not touch swarmforge/)?"
    (fn [world name] (new-commit world name))]

   [#"a fake claude on PATH that prints its environment"
    fake-claude]

   [#"the branch \"([^\"]+)\" holds the file \"([^\"]+)\" with \"([^\"]*)\""
    (fn [world branch path text]
      (if (after-when? world)
        (let [{:keys [exit out]} (run-in world (:repo world) ["git" "show" (str "refs/heads/" branch ":" path)])]
          (check (and (zero? exit) (= (str text "\n") out)) (str branch ":" path " holds " (pr-str out)))
          world)
        (metrics/hold-on world (str "refs/heads/" branch) path text)))]

   [#"the file \"([^\"]+)\" in the repository holds \"(.*)\""
    (fn [world path text]
      (if (after-when? world)
        (let [file (repo-file world path)
              actual (when (fs/exists? file) (slurp (str file)))]
          (check (= (str (unescape text) "\n") actual) (str path " holds " (pr-str actual)))
          world)
        (do (write-file (:repo world) path (str (unescape text) "\n"))
            world)))]

   ;; When
   [#"I (run|ran) \"([^\"]*)\"(?: in the worktree of task \"(\w)\")?(?: with the text \"(.*)\")?(?: with environment \"([^\"]*)\")?"
    (fn [world tense command name text env]
      (let [dir (if name (worktree world name) (:repo world))
            cmd (cond-> (vec (words (fill world command))) text (conj (unescape text)))
            env (environment env)]
        (if (= "ran" tense)
          (assoc world :ran-out (:out (run-in world dir cmd env)))
          (assoc (when-step world #(run-in % dir cmd env)) :ran-env env))))]

   [#"I commit every change in the main checkout"
    (fn [world]
      (git world "add" "-A")
      (git world "commit" "-q" "-m" "Migrate to Kevin")
      world)]

   ;; Then
   [#"the newest record has the author \"(.*)\""
    (fn [world author]
      (let [id (str/trim (:out world))
            actual (git world "log" "-1" "--format=%an <%ae>" (str "swarm/" id))]
        (check (= author actual) (str "author was " actual))
        world))]

   [#"the newest record of task \"(\w)\" has the trailer \"(.*)\""
    (fn [world name trailer]
      (let [trailers (str/split-lines (git world "log" "-1" "--format=%(trailers:only,unfold)" (str "swarm/" (store/id-of world name))))]
        (check (some #{trailer} trailers) (str "trailers: " (pr-str trailers)))
        world))]

   [#"the newest record of task \"(\w)\" has no trailer that starts with \"(.*)\""
    (fn [world name prefix]
      (let [trailers (str/split-lines (git world "log" "-1" "--format=%(trailers:only,unfold)" (str "swarm/" (store/id-of world name))))
            found (filter #(str/starts-with? % prefix) trailers)]
        (check (empty? found) (str "trailers: " (pr-str found)))
        world))]

   [#"\"([^\"]*)\" prints \"(.*)\""
    (fn [world command text]
      (let [result (run-in world (:repo world) (words (fill world command)))]
        (check (= (expected world text) (str/trim-newline (:out result))) (str "it printed " (pr-str (:out result))))
        world))]

   [#"the new task id is not \"(.*)\""
    (fn [world text]
      (let [id (str/trim (:out world))]
        (check (seq id) "no task id printed")
        (check (not= (fill world text) id) (str "the new task id is " id))
        world))]

   [#"the fake claude saw \"([^\"]+)\" set(?: to \"([^\"]*)\")?"
    (fn [world variable value]
      (let [actual (get (claude-saw world) variable)]
        (check (seq actual) (str variable " is not set"))
        (when value (check (= value actual) (str variable " is " (pr-str actual))))
        world))]

   [#"the fake claude saw no variable that kevin set with the prefix \"([^\"]+)\""
    (fn [world prefix]
      (let [prefixed (fn [env] (into {} (filter #(str/starts-with? (key %) prefix) env)))
            given (prefixed (merge (support/clean-env nil) (:env world) (:ran-env world)))
            seen (prefixed (claude-saw world))]
        (check (= given seen) (str "kevin started with " (pr-str given) "; claude saw " (pr-str seen)))
        world))]

   [#"the commit-msg hook has the line \"(.*)\""
    (fn [world line]
      (check (some #{line} (str/split-lines (hook-text world))) (str "the hook has no line " (pr-str line)))
      world)]

   [#"the commit-msg hook runs \"(.*)\""
    (fn [world path]
      (check (str/includes? (hook-text world) (str "$root/" path "\"")) (str "the hook does not run " path))
      world)]

   [#"the commit-msg hook does not mention \"(.*)\""
    (fn [world text]
      (check (not (str/includes? (hook-text world) text)) (str "the hook mentions " text))
      world)]

   [#"the repository has no path \"([^\"]+)\""
    (fn [world path]
      (check (not (or (fs/exists? (repo-file world path)) (fs/sym-link? (repo-file world path)))) (str path " exists"))
      world)]

   [#"the repository has the file \"([^\"]+)\""
    (fn [world path]
      (check (fs/regular-file? (repo-file world path)) (str path " is missing"))
      world)]

   [#"the repository has no file under \"([^\"]+)\" whose name starts with \"([^\"]+)\""
    (fn [world dir prefix]
      (let [found (filter #(str/starts-with? (str (fs/file-name %)) prefix) (fs/list-dir (repo-file world dir)))]
        (check (empty? found) (str "found " (pr-str (map str found))))
        world))]

   [#"the repository did not change"
    (fn [world]
      (let [before (:snapshot world)
            after (metrics/snapshot (:repo world))
            changed (sort (filter #(not= (get before %) (get after %)) (set (concat (keys before) (keys after)))))]
        (check (empty? changed) (str "changed: " (pr-str changed)))
        world))]

   [#"the file \"([^\"]+)\" in the repository has no line that mentions \"(.*)\""
    (fn [world path text]
      (let [found (filter #(str/includes? % text) (file-lines world path))]
        (check (empty? found) (str path " has " (pr-str found)))
        world))]

   [#"the file \"([^\"]+)\" in the repository has the new swarm line once"
    (fn [world path]
      (let [n (count (filter #{swarm-line} (file-lines world path)))]
        (check (= 1 n) (str path " has the new swarm line " n " times"))
        world))]

   [#"the branch \"([^\"]+)\" did not move"
    (fn [world branch]
      (let [ref (str "refs/heads/" branch)]
        (check (= (get-in world [:before ref]) (get (store/tips world) ref)) (str branch " moved"))
        world))]

   [#"the base branch has the commit of task \"(\w)\""
    (fn [world name]
      (let [commit (get-in world [:names (str "work-" name)])]
        (check (zero? (:exit (run-in world (:repo world) ["git" "merge-base" "--is-ancestor" commit "main"])))
               (str "main does not have " commit))
        world))]])

(def ^:private replaced-steps
  "Step texts of the inherited handlers that a rebrand handler takes over."
  ["I run \"x\"" "the file \"x\" in the repository holds \"y\""])

(defn- replaced? [[pattern]]
  (some #(re-matches pattern %) replaced-steps))

(def handlers
  (concat (remove replaced? install/handlers) rebrand-handlers))
