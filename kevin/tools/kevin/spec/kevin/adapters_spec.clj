(ns kevin.adapters-spec
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [speclj.core :refer :all]
            [kevin.adapters :as adapters]
            [kevin.installed :as installed]
            [kevin.repo-support :refer [*dir* with-repo write-conf! captured]]
            [kevin.shell :as shell]))

(defn- write! [path text]
  (let [file (fs/path *dir* path)]
    (fs/create-dirs (fs/parent file))
    (spit (str file) text)))

(defn- read [path] (slurp (str (fs/path *dir* path))))

(defn- source-checkout! []
  (write! ".claude-plugin/plugin.json" "{\"name\": \"kevin\", \"version\": \"0.2.0\"}\n"))

(defn- snapshot []
  (into {} (for [file (fs/glob *dir* "**" {:hidden true})
                 :when (and (fs/regular-file? file) (not (str/includes? (str file) "/.git/")))]
             [(str file) (slurp (str file))])))

(defn- write-gate! []
  (write-conf! *dir* "roles.conf" "specifier frontier subagent\nfalsifier strong subagent gate\nspec-judge strong subagent spec-judge\ncoder strong subagent\ncleaner fast session\n")
  (write! "kevin/roles/falsifier.md" "# falsifier\n\nAudits a specification.\n")
  (write! "kevin/roles/spec-judge.md" "# spec-judge\n\nRules on a spec revision.\n"))

(describe "kevin adapters"
  (around [it] (with-repo it))

  (it "writes no plugin agents and no project agents outside the Kevin source checkout when nothing differs"
    (let [before (snapshot)]
      (should= 0 (:status (captured #(adapters/adapters []))))
      (should= before (snapshot))))

  (it "writes the Claude Code and Copilot plugin agents of each subagent role"
    (source-checkout!)
    (should= 0 (:status (captured #(adapters/adapters []))))
    (should= ["cleaner.md" "coder.md" "specifier.md"] (sort (map fs/file-name (fs/list-dir (fs/path *dir* "agents")))))
    (should= ["cleaner.agent.md" "coder.agent.md" "specifier.agent.md"]
             (sort (map fs/file-name (fs/list-dir (fs/path *dir* ".github/plugin/agents")))))
    (should-not (fs/exists? (fs/path *dir* ".claude/agents")))
    (should-not (fs/exists? (fs/path *dir* ".github/agents")))
    (let [coder (read "agents/coder.md")]
      (should-contain "\nname: coder\n" coder)
      (should-contain "\nmodel: opus\n" coder)
      (should-contain "Does coder work." coder)
      (should-contain "\ndescription: \"Kevin coder role. Does coder work. Use for a task whose stage is coder.\"\n" coder)
      (should-contain "Read `<home>/roles/coder.md`." coder)
      (should-not-contain "{{" coder)
      (should-not-contain "kevin-generated" coder))
    (should-contain "\nmodel: gpt-5\n" (read ".github/plugin/agents/coder.agent.md")))

  (it "gives gates and the spec-judge their own templates and read-only tools"
    (source-checkout!)
    (write-gate!)
    (should= 0 (:status (captured #(adapters/adapters []))))
    (should-contain "Read `<home>/roles/falsifier.md`" (read "agents/falsifier.md"))
    (should-contain "\ntools: Read, Grep, Glob, Bash\n" (read "agents/falsifier.md"))
    (should-contain "\ntools: [\"read\", \"search\", \"shell\"]\n" (read ".github/plugin/agents/spec-judge.agent.md"))
    (should-contain "Read `<home>/roles/spec-judge.md`" (read "agents/spec-judge.md"))
    (should-not-contain "tools:" (read "agents/coder.md")))

  (it "deletes the agents of a role that is no longer a subagent, and is idempotent"
    (source-checkout!)
    (adapters/adapters [])
    (write-conf! *dir* "roles.conf" "specifier frontier session\ncoder fast subagent\ncleaner fast subagent\n")
    (captured #(adapters/adapters []))
    (should-not (fs/exists? (fs/path *dir* "agents/specifier.md")))
    (should-not (fs/exists? (fs/path *dir* ".github/plugin/agents/specifier.agent.md")))
    (should-contain "\nmodel: sonnet\n" (read "agents/coder.md"))
    (let [before (snapshot)]
      (captured #(adapters/adapters []))
      (should= before (snapshot))))

  (it "names no project path of the runtime in any template"
    (doseq [template ["adapter.md" "gate-adapter.md" "spec-judge-adapter.md"]
            :let [text (shell/template template)]]
      (should-not-contain "kevin/bin/kevin" text)
      (should-not-contain "kevin/roles/" text)
      (should-not-contain "kevin/constitution.md" text))))

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

(defn- agent-out [role harness] (captured #(adapters/agent-command [role harness])))

(describe "project agents"
  (around [it] (with-runtime it))

  (it "writes a stamped project agent only for a role whose agent differs, and excludes it"
    (fast! "claude-haiku-5-5 low gpt-mini")
    (should= 0 (:status (captured #(adapters/adapters []))))
    (should= [".claude/agents/kevin-cleaner.md"] (adapters/project-paths *dir*))
    (should-contain "\nmodel: claude-haiku-5-5\n# kevin-generated: 0.3.0\n---\n" (read ".claude/agents/kevin-cleaner.md"))
    (should-contain "\n/.claude/agents/kevin-cleaner.md\n" (exclude))
    (should-not (fs/exists? (fs/path *dir* "agents"))))

  (it "writes no file for a Claude Code alias"
    (fast! "haiku low gpt-mini")
    (captured #(adapters/adapters []))
    (should= [] (adapters/project-paths *dir*))
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
    (should= [".github/agents/cleaner.agent.md"] (adapters/project-paths *dir*)))

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
    (should= [".github/agents/other.agent.md"] (adapters/project-paths *dir*))
    (should-not-contain "cleaner.agent.md" (exclude)))

  (it "ends in the same state when run twice"
    (fast! "sonnet high gpt-z")
    (captured #(adapters/adapters []))
    (let [before (snapshot) excluded (exclude)]
      (captured #(adapters/adapters []))
      (should= before (snapshot))
      (should= excluded (exclude))))

  (it "names an added role's project prompt in its agents, and the runtime's prompt for a runtime role"
    (write-conf! *dir* "roles.conf" "specifier frontier subagent\ncoder strong subagent audit\ncleaner fast subagent\ndesigner strong subagent\n")
    (write! "kevin/roles/designer.md" "# designer\n\nDraws: screens # and 'flows'.\n")
    (write! "kevin/roles/coder.md" "# coder\n\nProject text.\n")
    (should= 0 (:status (captured #(adapters/adapters []))))
    (should= [".claude/agents/kevin-designer.md" ".codex/agents/designer.toml" ".github/agents/designer.agent.md"]
             (adapters/project-paths *dir*))
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

  (it "names a session role and refuses an unknown harness or role"
    (write-conf! *dir* "roles.conf" "specifier frontier session\ncoder strong subagent audit\ncleaner fast subagent\n")
    (should= "AGENT: session" (:out (agent-out "specifier" "claude")))
    (should= {:status 1 :err "unknown harness 'vibe' (claude, copilot, codex)"} (select-keys (agent-out "cleaner" "vibe") [:status :err]))
    (should= {:status 1 :err "unknown role 'nobody' (see kevin/roles.conf)"} (select-keys (agent-out "nobody" "claude") [:status :err]))))

(describe "agent-files"
  (around [it] (with-repo it))

  (it "keeps the five shapes of the removed committed install, from the legacy templates"
    (let [files (adapters/agent-files "coder" "strong" :stage {:templates "legacy/" :summary "Does coder work."})]
      (should= #{".claude/agents/coder.md" ".github/agents/coder.agent.md" ".codex/agents/coder.toml"
                 ".kilo/agent/coder.md" ".vibe/agents/coder.toml"}
               (set (keys files)))
      (doseq [[_ text] files]
        (should-contain "kevin/bin/kevin" text))
      (should-contain "Generated by Mistral Vibe." (get files ".vibe/agents/coder.toml"))
      (should-contain "active_model = \"vibe/strong-model\"" (get files ".vibe/agents/coder.toml"))
      (should-contain "model: kilo/strong-model" (get files ".kilo/agent/coder.md")))))
