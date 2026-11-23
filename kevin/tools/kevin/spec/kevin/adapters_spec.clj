(ns kevin.adapters-spec
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [speclj.core :refer :all]
            [kevin.adapters :as adapters]
            [kevin.repo-support :refer [*dir* with-repo write-conf! captured write! files]]
            [kevin.shell :as shell]))

(defn- read [path] (slurp (str (fs/path *dir* path))))

(defn- source-checkout! []
  (write! ".claude-plugin/plugin.json" "{\"name\": \"kevin\", \"version\": \"0.2.0\"}\n"))

(defn- write-gate! []
  (write-conf! *dir* "roles.conf" "specifier frontier subagent\nfalsifier strong subagent gate\nspec-judge strong subagent spec-judge\ncoder strong subagent\ncleaner fast session\n")
  (write! "kevin/roles/falsifier.md" "# falsifier\n\nAudits a specification.\n")
  (write! "kevin/roles/spec-judge.md" "# spec-judge\n\nRules on a spec revision.\n"))

(describe "kevin adapters"
  (around [it] (with-repo it))

  (it "writes no plugin agents and no project agents outside the Kevin source checkout when nothing differs"
    (let [before (files)]
      (should= 0 (:status (captured #(adapters/adapters []))))
      (should= before (files))))

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

  (it "writes the Vibe plugin agents with the plugin schema version and deletes them with the role"
    (source-checkout!)
    (should= 0 (:status (captured #(adapters/adapters []))))
    (should= ["cleaner.toml" "coder.toml" "specifier.toml"]
             (sort (map fs/file-name (fs/list-dir (fs/path *dir* "ai.mistral.vibe/agents")))))
    (let [coder (read "ai.mistral.vibe/agents/coder.toml")]
      (should (str/starts-with? coder "schema_version = 1\n"))
      (should-contain "agent_type = \"subagent\"" coder)
      (should-contain (-> (shell/template "adapter.md")
                          (str/replace "{{role}}" "coder")
                          (str/replace "{{prompt}}" "<home>/roles/coder.md"))
                      coder))
    (write-conf! *dir* "roles.conf" "specifier frontier session\ncoder fast subagent\ncleaner fast subagent\n")
    (should= 0 (:status (captured #(adapters/adapters []))))
    (should-not (fs/exists? (fs/path *dir* "ai.mistral.vibe/agents/specifier.toml")))
    (should (fs/exists? (fs/path *dir* "ai.mistral.vibe/agents/coder.toml"))))

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
    (let [before (files)]
      (captured #(adapters/adapters []))
      (should= before (files))))

  (it "names no project path of the runtime in any template"
    (doseq [template ["adapter.md" "gate-adapter.md" "spec-judge-adapter.md"]
            :let [text (shell/template template)]]
      (should-not-contain "kevin/bin/kevin" text)
      (should-not-contain "kevin/roles/" text)
      (should-not-contain "kevin/constitution.md" text))))

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
