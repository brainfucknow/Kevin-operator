(ns kevin.dispatch-spec
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [speclj.core :refer [around describe it should-contain should-not-contain should=]]
            [kevin.dispatch :as dispatch]
            [kevin.repo-support :as support :refer [*dir* git with-repo]]
            [kevin.installed :as installed]
            [kevin.shell :as shell]
            [kevin.task :as task]))

(def ^:private command-for @#'kevin.dispatch/command-for)
(def ^:private detect-backend @#'kevin.dispatch/detect-backend)
(def ^:private mistral-command @#'kevin.dispatch/mistral-command)
(def ^:private pipeline-command @#'kevin.dispatch/pipeline-command)
(def ^:private role-prompt @#'kevin.dispatch/role-prompt)

(defn- prompts!
  "Writes a role prompt for each role in the repository's runtime."
  [& roles]
  (doseq [role roles]
    (spit (str (fs/path *dir* "kevin" "roles" (str role ".md"))) (str "# " role "\n\nDoes " role " work.\n"))))

(defn- env-of [variables]
  (fn [name default] (get variables name default)))

(defn- backend-shell
  "A p/shell that hands git to the real one and every other command to handler."
  [handler]
  (let [real p/shell]
    (fn [opts] (if (= "git" (first (:cmd opts))) (real opts) (handler opts)))))

(defn- new-id [& args]
  (shell/chomp (with-out-str (task/new-task (vec args)))))

(describe "command-for"
  (around [it] (with-repo it))

  (it "runs claude on the model of the role's tier, none for inherit"
    (should= ["claude" "-p" "--permission-mode" "acceptEdits" "--model" "opus" "prompt"]
             (vec (command-for "claude" "coder" "prompt")))
    (should= ["claude" "-p" "--permission-mode" "acceptEdits" "prompt"]
             (vec (command-for "claude" "specifier" "prompt"))))

  (it "adds the extra arguments of the backend"
    (with-redefs [shell/env (env-of {"KEVIN_CLAUDE_ARGS" " --verbose  --x " "KEVIN_CODEX_ARGS" "--y"
                                     "KEVIN_COPILOT_ARGS" "--z"})]
      (should= ["claude" "-p" "--permission-mode" "acceptEdits" "--model" "opus" "--verbose" "--x" "prompt"]
               (vec (command-for "claude" "coder" "prompt")))
      (should= ["codex" "exec" "--sandbox" "workspace-write" "-c" "model_reasoning_effort=\"medium\"" "--y" "prompt"]
               (vec (command-for "codex" "coder" "prompt")))
      (should= ["copilot" "-p" "prompt" "--model" "gpt-5" "--z"]
               (vec (command-for "copilot" "coder" "prompt")))))

  (it "grants copilot no tool permissions beyond KEVIN_COPILOT_ARGS"
    (should= ["copilot" "-p" "prompt" "--model" "gpt-5"]
             (vec (command-for "copilot" "coder" "prompt")))
    (with-redefs [shell/env (env-of {"KEVIN_COPILOT_ARGS" "--allow-all-tools"})]
      (should= ["--model" "gpt-5" "--allow-all-tools"]
               (take-last 3 (command-for "copilot" "coder" "prompt"))))
    (with-redefs [shell/env (env-of {"KEVIN_COPILOT_ARGS" "--allow-tool shell(git:*)"})]
      (should= ["--model" "gpt-5" "--allow-tool" "shell(git:*)"]
               (take-last 4 (command-for "copilot" "coder" "prompt")))))

  (it "refuses an unknown backend"
    (should-contain "unknown backend 'nope'" (:err (support/captured #(command-for "nope" "coder" "p"))))))

(describe "detect-backend"
  (it "takes KEVIN_BACKEND first"
    (with-redefs [shell/env (env-of {"KEVIN_BACKEND" "codex"})]
      (should= "codex" (detect-backend))))

  (it "takes the first backend found on the path"
    (with-redefs [fs/which (fn [name] (when (= "codex" name) "/bin/codex"))]
      (should= "codex" (detect-backend))))

  (it "stops when there is none"
    (with-redefs [fs/which (constantly nil)
                  shell/env (env-of {})]
      (should-contain "no backend found" (:err (support/captured detect-backend))))))

(defn- dispatched
  "The options of each backend run that dispatching role on id with backend starts."
  [role id backend]
  (let [ran (atom [])]
    (with-redefs [p/shell (backend-shell (fn [opts] (swap! ran conj opts) {:exit 0}))]
      (dispatch/dispatch [role id backend]))
    @ran))

(defn- branch-of [dir]
  (git dir "rev-parse" "--abbrev-ref" "HEAD"))

(describe "dispatch"
  (around [it] (with-repo it))

  (it "runs the backend command"
    (let [id (new-id "Add login")
          [{:keys [cmd]}] (dispatched "coder" id "claude")]
      (should= ["claude" "-p" "--permission-mode" "acceptEdits" "--model" "opus"]
               (take 6 cmd))
      (should-contain (str "do your role's work for task " id) (last cmd))))

  (it "tells the backend which role it runs, so operator commands refuse it"
    (doseq [backend dispatch/backends]
      (let [id (new-id (str "Mark " backend))]
        (should= [{"KEVIN_DISPATCHED" "coder" "KEVIN_BACKEND" backend "KEVIN_ROLE" "coder"}]
                 (map :extra-env (dispatched "coder" id backend))))))

  (it "runs every backend in the task worktree, created on the task branch when missing"
    (doseq [backend dispatch/backends]
      (let [id (new-id (str "Add " backend))
            dir (str *dir* "/.worktrees/" id)]
        (should= [dir] (map :dir (dispatched "coder" id backend)))
        (should= (str "swarm/" id) (branch-of dir)))))

  (it "dispatches an optional reviewer with the existing strong tier on every backend"
    (support/write-conf! *dir* "roles.conf"
                         "specifier frontier subagent\narchitecture-design-reviewer strong subagent optional\ncoder strong subagent\ncleaner fast subagent\n")
    (prompts! "architecture-design-reviewer")
    (doseq [backend dispatch/backends]
      (let [id (new-id (str "Design " backend) "--route" "architecture-design-reviewer,coder")
            [{:keys [cmd dir]}] (dispatched "architecture-design-reviewer" id backend)
            prompt (case backend "copilot" (nth cmd 2) (last cmd))
            expected (if (= "mistral" backend)
                       (vec (mistral-command "architecture-design-reviewer" id))
                       (vec (command-for backend "coder" prompt)))]
        (should= (str *dir* "/.worktrees/" id) dir)
        (should= expected cmd))))

  (it "dispatches an explicitly selected optional reviewer with the existing strong tier on every backend"
    (support/write-conf! *dir* "roles.conf" "coder strong subagent\nsecurity-reviewer strong subagent optional\ncleaner fast subagent\n")
    (support/write-conf! *dir* "routes.conf" "quick coder\n")
    (prompts! "security-reviewer")
    (doseq [backend dispatch/backends]
      (let [id (new-id (str "Review " backend) "--route" "coder,security-reviewer,cleaner")
            [{:keys [cmd dir]}] (dispatched "security-reviewer" id backend)
            expected (if (= "mistral" backend)
                       (vec (mistral-command "security-reviewer" id))
                       (vec (command-for backend "coder" (if (= "copilot" backend) (nth cmd 2) (last cmd)))))]
        (should= expected cmd)
        (should= (str *dir* "/.worktrees/" id) dir))))

  (it "reuses the task worktree with uncommitted changes in it"
    (let [id (new-id "Add login")
          dir (str *dir* "/.worktrees/" id)]
      (dispatched "specifier" id "codex")
      (spit (str dir "/work-in-progress") "x")
      (should= [dir] (map :dir (dispatched "coder" id "codex")))
      (should= "x" (slurp (str dir "/work-in-progress")))))

  (it "refuses a task worktree on another branch without starting the backend"
    (let [id (new-id "Add login")
          dir (str *dir* "/.worktrees/" id)]
      (dispatched "coder" id "claude")
      (git dir "checkout" "-q" "-b" "other")
      (let [ran (atom [])
            result (with-redefs [p/shell (backend-shell (fn [opts] (swap! ran conj opts) {:exit 0}))]
                     (support/captured #(dispatch/dispatch ["coder" id "claude"])))]
        (should= 1 (:status result))
        (should= (str "worktree is on 'other', expected 'swarm/" id "'") (:err result))
        (should= [] @ran))))

  (it "fails with the exit status of the backend"
    (let [id (new-id "Add login")]
      (with-redefs [p/shell (backend-shell (constantly {:exit 7}))]
        (should= 7 (:status (support/captured #(dispatch/dispatch ["coder" id "claude"])))))))

  (it "names a backend that cannot start"
    (let [id (new-id "Add login")]
      (with-redefs [p/shell (backend-shell (fn [_] (throw (java.io.IOException. "no such file"))))]
        (should= {:out "" :err "claude: no such file" :status 1}
                (support/captured #(dispatch/dispatch ["coder" id "claude"]))))))

  (it "dispatches a gate read-only on the task's specification"
    (support/write-conf! *dir* "roles.conf" "specifier frontier subagent\nfalsifier strong subagent gate\ncoder strong subagent\n")
    (support/write-conf! *dir* "routes.conf" "feature specifier,coder\n")
    (prompts! "falsifier")
    (let [id (new-id "Add login" "--route" "specifier,coder")
          [{:keys [cmd dir]}] (dispatched "falsifier" id "claude")]
      (should= (str *dir* "/.worktrees/" id) dir)
      (should= ["claude" "-p" "--permission-mode" "default"
                "--disallowedTools=Edit" "--disallowedTools=Write" "--disallowedTools=NotebookEdit"
                "--model" "opus"]
               (take 9 cmd))
      (should-contain (str (installed/home) "/roles/falsifier.md") (last cmd))
      (should-contain (str "Start with: kevin show " id) (last cmd))
      (should-not-contain "kevin/bin/kevin" (last cmd))
      (should-not-contain "kevin start" (last cmd))))

  (it "dispatches a gate on codex and copilot without edit permissions, keeping the extra arguments"
    (support/write-conf! *dir* "roles.conf" "specifier frontier subagent\nfalsifier strong subagent gate\ncoder strong subagent\n")
    (with-redefs [shell/env (env-of {"KEVIN_CODEX_ARGS" "--y" "KEVIN_COPILOT_ARGS" "--allow-all-tools"})]
      (should= ["codex" "exec" "--sandbox" "read-only" "-c" "model_reasoning_effort=\"medium\"" "--y" "prompt"]
               (vec (command-for "codex" "falsifier" "prompt")))
      (should= ["copilot" "-p" "prompt" "--model" "gpt-5" "--deny-tool=write" "--allow-all-tools"]
               (vec (command-for "copilot" "falsifier" "prompt")))))

  (it "refuses an unsafe tiers.conf value without starting a backend"
    (let [started (atom false)
          id (new-id "Unsafe" "-m" "x")]
      (support/write-conf! *dir* "tiers.conf" "frontier inherit high gpt\nstrong --dangerous medium gpt-5\nfast sonnet low gpt-mini\n")
      (with-redefs [p/shell (backend-shell (fn [_] (reset! started true) {:exit 0}))]
        (let [{:keys [status err]} (support/captured #(dispatch/dispatch ["coder" id "claude"]))]
          (should= 1 status)
          (should-contain "CHECK: tiers.conf: 'strong' has the unsafe value '--dangerous'" err)))
      (should= false @started)))

  (it "needs a known role"
    (should-contain "dispatch needs a role" (:err (support/captured #(dispatch/dispatch []))))
    (should-contain "unknown role" (:err (support/captured #(dispatch/dispatch ["nobody" "x"])))))

  (it "refuses the spec-judge without starting a backend"
    (support/write-conf! *dir* "roles.conf" "specifier frontier subagent\nspec-judge strong subagent spec-judge\ncoder strong subagent\ncleaner fast subagent\n")
    (prompts! "spec-judge")
    (let [started (atom false)
          id (new-id "Judged" "-m" "x")]
      (with-redefs [p/shell (backend-shell (fn [_] (reset! started true) {:exit 0}))]
        (let [{:keys [status err]} (support/captured #(dispatch/dispatch ["spec-judge" id "claude"]))]
          (should= 1 status)
          (should-contain "'spec-judge' is the spec-judge: it needs the spec revision's gate reports and runs only as a subagent of the session" err)))
      (should= false @started))))

(describe "run"
  (around [it] (with-repo it))

  (it "dispatches stage after stage until the task is done"
    (let [id (new-id "Add login" "--route" "coder,cleaner")
          stages (atom [])]
      (with-redefs [dispatch/dispatch (fn [[stage id _]]
                                        (swap! stages conj stage)
                                        (task/handoff [id] {:reviewed? true}))]
        (should-contain (str "DONE: " id "; run: kevin finish " id)
                        (:out (support/captured #(dispatch/run [id "claude"])))))
      (should= ["coder" "cleaner"] @stages)))

  (it "reports completed spikes without a finish hint on either run path"
    (let [id (new-id "Spike: Try a different approach" "--route" "coder,cleaner")]
      (with-out-str (doseq [_ (range 2)] (task/handoff [id] {:reviewed? true})))
      (with-redefs [p/shell (backend-shell (constantly {:exit 0}))]
        (should= (repeat 2 {:status 0 :out (str "DONE: " id)})
                 (for [backend ["codex" "mistral"]]
                   (select-keys (support/captured #(dispatch/run [id backend])) [:status :out]))))))

  (it "stops with status 2 when the operator is needed"
    (let [id (new-id "Add login")]
      (task/ask [id "Which?"])
      (let [result (support/captured #(dispatch/run [id "claude"]))]
        (should= 2 (:status result))
        (should-contain "BLOCKED" (:out result)))))

  (it "runs the task's gates on a specification it dispatched, then waits for approval"
    (support/write-conf! *dir* "roles.conf" "specifier frontier subagent\nfalsifier strong subagent gate\ndoubter strong subagent gate\ncoder strong subagent\n")
    (let [id (new-id "Add login" "--route" "specifier,coder" "--gates" "falsifier")
          ran (atom [])]
      (with-redefs [shell/env (env-of {"KEVIN_BACKEND" "claude"})
                    dispatch/dispatch (fn [[role id _]]
                                        (swap! ran conj role)
                                        (when (= "specifier" role) (task/review [id "-m" "spec"])))]
        (let [result (support/captured #(dispatch/run [id]))]
          (should= 2 (:status result))
          (should-contain (str "BLOCKED: " id " needs:approval") (:out result))))
      (should= ["specifier" "falsifier"] @ran)))

  (it "runs no gate on a specification it dispatched when the task's gates are none"
    (support/write-conf! *dir* "roles.conf" "specifier frontier subagent\nfalsifier strong subagent gate\ncoder strong subagent\n")
    (let [id (new-id "Add login" "--route" "specifier,coder")
          ran (atom [])]
      (with-redefs [dispatch/dispatch (fn [[role id _]]
                                        (swap! ran conj role)
                                        (when (= "specifier" role) (task/review [id "-m" "spec"])))]
        (let [result (support/captured #(dispatch/run [id "claude"]))]
          (should= 2 (:status result))
          (should-contain (str "BLOCKED: " id " needs:approval") (:out result))))
      (should= ["specifier"] @ran)))

  (it "does not rerun the gates on a specification that already waited for approval"
    (support/write-conf! *dir* "roles.conf" "specifier frontier subagent\nfalsifier strong subagent gate\ncoder strong subagent\n")
    (let [id (new-id "Add login" "--route" "specifier,coder")
          ran (atom [])]
      (task/review [id "-m" "spec"])
      (with-redefs [dispatch/dispatch (fn [[role]] (swap! ran conj role))]
        (should= 2 (:status (support/captured #(dispatch/run [id "claude"])))))
      (should= [] @ran)))

  (it "stops when a gate records anything"
    (support/write-conf! *dir* "roles.conf" "specifier frontier subagent\nfalsifier strong subagent gate\ncoder strong subagent\n")
    (let [id (new-id "Add login" "--route" "specifier,coder" "--gates" "falsifier")]
      (with-redefs [dispatch/dispatch (fn [[role id _]]
                                        (if (= "specifier" role)
                                          (task/review [id "-m" "spec"])
                                          (task/ask [id "Which?"])))]
        (let [result (support/captured #(dispatch/run [id "claude"]))]
          (should= 1 (:status result))
          (should-contain (str "gate falsifier changed task " id "; a gate only reports") (:err result))))))

  (it "stops when a gate edits or commits in the task worktree"
    (support/write-conf! *dir* "roles.conf" "specifier frontier subagent\nfalsifier strong subagent gate\ncoder strong subagent\n")
    (doseq [[title change] [["Edit" #(spit (str % "/note") "x")]
                            ["Commit" #(git % "commit" "-q" "--allow-empty" "-m" "Gate commit")]]]
      (let [id (new-id title "--route" "specifier,coder" "--gates" "falsifier")]
        (with-redefs [dispatch/dispatch (fn [[role id _]]
                                          (if (= "specifier" role)
                                            (task/review [id "-m" "spec"])
                                            (change (str *dir* "/.worktrees/" id))))]
          (let [result (support/captured #(dispatch/run [id "claude"]))]
            (should= 1 (:status result))
            (should-contain (str "gate falsifier changed task " id) (:err result)))))))

  (it "stops when a stage records what only the operator records"
    (doseq [[title stage operator] [["Approve" "specifier" #(task/approve [%])]
                                    ["Answer" "coder" #(do (task/ask [% "Which?"]) (task/answer [% "This."]))]
                                    ["Route" "coder" #(task/route [% "coder,cleaner"])]
                                    ["Gates" "coder" #(task/gates [% "none"])]]]
      (let [id (new-id title "--route" "specifier,coder,cleaner")
            ran (atom [])]
        (when (= "coder" stage)
          (task/review [id "-m" "spec"])
          (task/approve [id]))
        (with-redefs [dispatch/dispatch (fn [[role id _]]
                                          (swap! ran conj role)
                                          (when (= "specifier" role) (task/review [id "-m" "spec"]))
                                          (operator id))]
          (let [result (support/captured #(dispatch/run [id "claude"]))]
            (should= 1 (:status result))
            (should-contain (str "stage:" stage " added the operator's ") (:err result))))
        (should= [stage] @ran))))

  (it "stops when a stage makes no progress"
    (let [id (new-id "Add login")]
      (with-redefs [dispatch/dispatch (fn [_])]
        (should-contain "made no progress" (:err (support/captured #(dispatch/run [id "claude"]))))))))

(describe "the mistral backend"
  (around [it] (with-repo it))

  (it "is detected when no CLI backend is on PATH and MISTRAL_API_KEY is set"
    (with-redefs [fs/which (constantly nil)
                  shell/env (env-of {"MISTRAL_API_KEY" "dummy"})]
      (should= "mistral" (detect-backend))))

  (it "is not detected without MISTRAL_API_KEY"
    (with-redefs [fs/which (constantly nil)
                  shell/env (env-of {})]
      (should-contain "no backend found (claude, codex, copilot, mistral)" (:err (support/captured detect-backend)))))

  (it "stays behind the CLI backends when both are available"
    (with-redefs [fs/which (fn [name] (when (= "codex" name) "/bin/codex"))
                  shell/env (env-of {"MISTRAL_API_KEY" "dummy"})]
      (should= "codex" (detect-backend))))

  (it "builds the kevin-mistral dispatch command for role and id"
    (let [cmd (vec (mistral-command "coder" "add-login-3f9a2c1"))]
      (should= ["dispatch" "coder" "add-login-3f9a2c1"] (drop 2 cmd))
      (should= (str (installed/home) "/bin/kevin-mistral") (nth cmd 1))))

  (it "reads the role prompt and the constitution from the runtime and runs kevin from PATH"
    (let [prompt (role-prompt "coder" "add-login-3f9a2c1")]
      (should-contain (str (installed/home) "/constitution.md") prompt)
      (should-contain (str (installed/home) "/roles/coder.md") prompt)
      (should-contain "Start with: kevin start add-login-3f9a2c1" prompt)
      (should-not-contain "kevin/bin/kevin" prompt)))

  (it "builds the kevin-mistral run command for id"
    (let [cmd (vec (pipeline-command "add-login-3f9a2c1"))]
      (should= ["run" "add-login-3f9a2c1"] (drop 2 cmd))
      (should-contain "kevin-mistral" (nth cmd 1))))

  (it "dispatches a role as one workflow execution in the task worktree"
    (let [id (new-id "Add login")
          [{:keys [cmd dir extra-env]}] (dispatched "coder" id "mistral")]
      (should= (str *dir* "/.worktrees/" id) dir)
      (should= ["dispatch" "coder" id] (drop 2 cmd))
      (should= {"KEVIN_DISPATCHED" "coder" "KEVIN_BACKEND" "mistral" "KEVIN_ROLE" "coder"} extra-env)))

  (it "runs one pipeline execution with kevin run <id> mistral"
    (let [id (new-id "Add login" "--route" "coder,cleaner")
          ran (atom [])]
      (with-redefs [p/shell (backend-shell (fn [opts] (swap! ran conj opts) {:exit 0}))]
        (should-contain (str "DONE: " id) (:out (support/captured #(dispatch/run [id "mistral"])))))
      (should= [["run" id]] (map #(drop 2 (:cmd %)) @ran))))

  (it "routes an auto-detected mistral backend to one pipeline execution"
    (with-redefs [fs/which (constantly nil)
                  shell/env (env-of {"MISTRAL_API_KEY" "dummy"})]
      (let [id (new-id "Add login" "--route" "coder,cleaner")
            ran (atom [])]
        (with-redefs [p/shell (backend-shell (fn [opts] (swap! ran conj opts) {:exit 0}))]
          (should-contain (str "DONE: " id) (:out (support/captured #(dispatch/run [id])))))
        (should= [["run" id]] (map #(drop 2 (:cmd %)) @ran)))))

  (it "reports BLOCKED with status 2 when the pipeline execution waits on the operator"
    (let [id (new-id "Add login")
          _ (task/ask [id "Which?"])]
      (with-redefs [p/shell (backend-shell (constantly {:exit 2}))]
        (let [result (support/captured #(dispatch/run [id "mistral"]))]
          (should= 2 (:status result))
          (should-contain (str "BLOCKED: " id " needs:operator") (:out result)))))))
