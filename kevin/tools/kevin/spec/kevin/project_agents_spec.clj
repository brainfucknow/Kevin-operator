(ns kevin.project-agents-spec
  (:require [babashka.fs :as fs]
            [speclj.core :refer :all]
            [kevin.adapters :as adapters]
            [kevin.installed :as installed]
            [kevin.project-agents :as project-agents]
            [kevin.repo-support :refer [*dir* with-repo write-conf! captured write! files]]))

(defn- read [path] (slurp (str (fs/path *dir* path))))

(def ^:dynamic *runtime* nil)

(defn- with-runtime
  "A repository whose kevin/ is the project's, with a separate runtime that
  holds the repository's catalogue as it was at the start, at version 0.3.0."
  [run-spec]
  (with-repo
    (fn []
      (let [runtime (str (fs/create-temp-dir {:prefix "kevin-runtime-"}))]
        (try
          (fs/copy-tree (fs/path *dir* "kevin") (fs/path runtime "kevin"))
          (fs/create-dirs (fs/path runtime ".claude-plugin"))
          (spit (str (fs/path runtime ".claude-plugin" "plugin.json")) "{\"name\": \"kevin\", \"version\": \"0.3.0\"}")
          (with-redefs [installed/home (constantly (str (fs/path runtime "kevin")))]
            (binding [*runtime* runtime] (run-spec)))
          (finally (fs/delete-tree runtime)))))))

(defn- fast! [line] (write-conf! *dir* "tiers.conf" (str "frontier inherit high gpt\nstrong opus medium gpt-5\nfast " line "\n")))

(defn- exclude [] (slurp (str (fs/path *dir* ".git" "info" "exclude"))))

(defn- agent-out [role harness] (captured #(project-agents/agent-command [role harness])))

(describe "project agents"
  (around [it] (with-runtime it))

  (it "writes a stamped project agent only for a role whose agent differs, and excludes it"
    (fast! "claude-haiku-5-5 low gpt-mini")
    (should= 0 (:status (captured #(adapters/adapters []))))
    (should= [".claude/agents/kevin-cleaner.md"] (project-agents/paths *dir*))
    (should-contain "\nmodel: claude-haiku-5-5\n# kevin-generated: 0.3.0\n---\n" (read ".claude/agents/kevin-cleaner.md"))
    (should-contain "\n/.claude/agents/kevin-cleaner.md\n" (exclude))
    (should-not (fs/exists? (fs/path *dir* "agents"))))

  (it "writes no file for a Claude Code alias"
    (fast! "haiku low gpt-mini")
    (captured #(adapters/adapters []))
    (should= [] (project-agents/paths *dir*))
    (should= {:status 0 :out "AGENT: kevin:cleaner\nMODEL: haiku"} (select-keys (agent-out "cleaner" "claude") [:status :out])))

  (it "refuses a needed path that holds a file kevin did not write, and writes nothing"
    (fast! "sonnet low gpt-z")
    (write! ".github/agents/cleaner.agent.md" "mine\n")
    (let [excluded (exclude)
          {:keys [status err]} (captured #(adapters/adapters []))]
      (should= 1 status)
      (should= ".github/agents/cleaner.agent.md exists and kevin did not write it" err)
      (should= excluded (exclude)))
    (should= "mine\n" (read ".github/agents/cleaner.agent.md"))
    (should= [".github/agents/cleaner.agent.md"] (project-agents/paths *dir*)))

  (it "refuses to write through a symbolic link"
    (fast! "sonnet low gpt-z")
    (let [elsewhere (fs/create-temp-dir)]
      (try
        (fs/create-dirs (fs/path *dir* ".github"))
        (fs/create-sym-link (fs/path *dir* ".github" "agents") elsewhere)
        (should= {:status 1 :err ".github/agents/cleaner.agent.md is under a symbolic link; kevin does not write through it"}
                 (select-keys (captured #(adapters/adapters [])) [:status :err]))
        (should= [] (map str (fs/list-dir elsewhere)))
        (finally (fs/delete-tree elsewhere)))))

  (it "removes a project agent that is no longer needed with its exclude line, and keeps a file kevin did not write"
    (fast! "sonnet low gpt-z")
    (captured #(adapters/adapters []))
    (write! ".github/agents/other.agent.md" "mine\n")
    (fast! "sonnet low gpt-mini")
    (should= 0 (:status (captured #(adapters/adapters []))))
    (should= [".github/agents/other.agent.md"] (project-agents/paths *dir*))
    (should-not-contain "cleaner.agent.md" (exclude)))

  (it "ends in the same state when run twice"
    (fast! "sonnet high gpt-z")
    (captured #(adapters/adapters []))
    (let [before (files) excluded (exclude)]
      (captured #(adapters/adapters []))
      (should= before (files))
      (should= excluded (exclude))))

  (it "names an added role's project prompt in its agents, and the runtime's prompt for a runtime role"
    (write-conf! *dir* "roles.conf" "specifier frontier subagent\ncoder strong subagent audit\ncleaner fast subagent\ndesigner strong subagent\n")
    (write! "kevin/roles/designer.md" "# designer\n\nDraws: screens # and 'flows'.\n")
    (write! "kevin/roles/coder.md" "# coder\n\nProject text.\n")
    (should= 0 (:status (captured #(adapters/adapters []))))
    (should= [".claude/agents/kevin-designer.md" ".codex/agents/designer.toml" ".github/agents/designer.agent.md"]
             (project-agents/paths *dir*))
    (should-contain (str "Read `" (fs/path *dir* "kevin" "roles" "designer.md") "`") (read ".claude/agents/kevin-designer.md"))
    (should-contain "\"Kevin designer role. Draws: screens # and 'flows'. Use for a task whose stage is designer.\"" (read ".claude/agents/kevin-designer.md")))

  (it "is how kevin agent names the agent, the model and a missing or stale project agent"
    (fast! "claude-haiku-5-5 low gpt-mini")
    (should= "AGENT: kevin-cleaner\nWARNING: .claude/agents/kevin-cleaner.md is missing; run kevin adapters"
             (:out (agent-out "cleaner" "claude")))
    (captured #(adapters/adapters []))
    (should= "AGENT: kevin-cleaner" (:out (agent-out "cleaner" "claude")))
    (write! ".claude/agents/kevin-cleaner.md" "mine\n")
    (should= "AGENT: kevin-cleaner\nWARNING: .claude/agents/kevin-cleaner.md is stale; run kevin adapters"
             (:out (agent-out "cleaner" "claude")))
    (should= "AGENT: cleaner" (:out (agent-out "cleaner" "copilot")))
    (should= "AGENT: none" (:out (agent-out "cleaner" "codex"))))

  (it "names a session role with the path of its prompt and refuses an unknown harness or role"
    (write-conf! *dir* "roles.conf" "specifier frontier session\ncoder strong subagent audit\ncleaner fast subagent\ndesigner strong session\n")
    (write! "kevin/roles/specifier.md" "# specifier\n\nProject text.\n")
    (write! "kevin/roles/designer.md" "# designer\n\nDraws the screens.\n")
    (should= (str "AGENT: session\nPROMPT: " (fs/path *runtime* "kevin" "roles" "specifier.md"))
             (:out (agent-out "specifier" "claude")))
    (should= (str "AGENT: session\nPROMPT: " (fs/path *dir* "kevin" "roles" "designer.md"))
             (:out (agent-out "designer" "vibe")))
    (should= {:status 1 :err "unknown harness 'kilo' (claude, copilot, codex, vibe)"} (select-keys (agent-out "cleaner" "kilo") [:status :err]))
    (should= {:status 1 :err "unknown role 'nobody' (see kevin/roles.conf)"} (select-keys (agent-out "nobody" "claude") [:status :err])))

  (it "names the plugin agent of a runtime role in vibe, whatever the project's rows, and no agent for an added role"
    (fast! "claude-haiku-5-5 low gpt-mini")
    (write-conf! *dir* "roles.conf" "specifier frontier subagent\ncoder strong subagent audit\ncleaner fast subagent\ndesigner strong subagent\ncritic strong subagent gate\n")
    (write! "kevin/roles/designer.md" "# designer\n\nDraws the screens.\n")
    (write! "kevin/roles/critic.md" "# critic\n\nCriticizes.\n")
    (captured #(adapters/adapters []))
    (should= {:status 0 :out "AGENT: kevin:cleaner"} (select-keys (agent-out "cleaner" "vibe") [:status :out]))
    (should= "AGENT: none" (:out (agent-out "designer" "vibe")))
    (should= "AGENT: none" (:out (agent-out "critic" "vibe")))
    (should-not (fs/exists? (fs/path *dir* ".vibe")))))
