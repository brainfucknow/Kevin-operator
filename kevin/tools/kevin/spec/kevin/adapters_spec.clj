(ns kevin.adapters-spec
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [speclj.core :refer :all]
            [kevin.adapters :as adapters]
            [kevin.config :as config]
            [cheshire.core :as json]
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

(def ^:private runtime-home
  "The kevin/ directory of this checkout, whose roles the length check covers."
  (installed/home))

(defn- vibe-description
  "The description of a Vibe agent file, unescaped: TOML basic string escapes
  are JSON's."
  [text]
  (json/parse-string (str "\"" (second (re-find #"(?m)^description = \"(.*)\"$" text)) "\"")))

(defn- write-gate! []
  (write-conf! *dir* "roles.conf" "specifier frontier subagent\nfalsifier strong subagent gate\nspec-judge strong subagent spec-judge\ncoder strong subagent\ncleaner fast session\n")
  (write! "kevin/roles/falsifier.md" "# falsifier\n\nAudits a specification.\n")
  (write! "kevin/roles/spec-judge.md" "# spec-judge\n\nRules on a spec revision.\n"))

(describe "kevin adapters"
  (around [it] (with-repo it))

  (it "refuses outside the Kevin source checkout and writes nothing"
    (let [before (snapshot)]
      (should= {:status 1 :err "kevin adapters runs only in the Kevin source checkout"}
               (select-keys (captured #(adapters/adapters [])) [:status :err]))
      (write! ".claude-plugin/plugin.json" "{\"name\": \"other\"}")
      (should= 1 (:status (captured #(adapters/adapters []))))
      (should= (assoc before (str (fs/path *dir* ".claude-plugin/plugin.json")) "{\"name\": \"other\"}") (snapshot))))

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
      (should-contain (str/replace (shell/template "adapter.md") "{{role}}" "coder") coder))
    (should-contain "\nmodel: gpt-5\n" (read ".github/plugin/agents/coder.agent.md")))

  (it "writes the Vibe plugin agents with the plugin schema version and deletes them with the role"
    (source-checkout!)
    (should= 0 (:status (captured #(adapters/adapters []))))
    (should= ["cleaner.toml" "coder.toml" "specifier.toml"]
             (sort (map fs/file-name (fs/list-dir (fs/path *dir* "ai.mistral.vibe/agents")))))
    (let [coder (read "ai.mistral.vibe/agents/coder.toml")]
      (should (str/starts-with? coder "schema_version = 1\n"))
      (should-contain "agent_type = \"subagent\"" coder)
      (should-contain (str/replace (shell/template "adapter.md") "{{role}}" "coder") coder))
    (write-conf! *dir* "roles.conf" "specifier frontier session\ncoder fast subagent\ncleaner fast subagent\n")
    (should= 0 (:status (captured #(adapters/adapters []))))
    (should-not (fs/exists? (fs/path *dir* "ai.mistral.vibe/agents/specifier.toml")))
    (should (fs/exists? (fs/path *dir* "ai.mistral.vibe/agents/coder.toml"))))

  (it "gives gates and the spec-judge their own templates and read-only tools"
    (source-checkout!)
    (write-gate!)
    (should= 0 (:status (captured #(adapters/adapters []))))
    (should-contain (str/replace (shell/template "gate-adapter.md") "{{role}}" "falsifier") (read "agents/falsifier.md"))
    (should-contain "\ntools: Read, Grep, Glob, Bash\n" (read "agents/falsifier.md"))
    (should-contain "\ntools: [\"read\", \"search\", \"shell\"]\n" (read ".github/plugin/agents/spec-judge.agent.md"))
    (should-contain (str/replace (shell/template "spec-judge-adapter.md") "{{role}}" "spec-judge") (read "agents/spec-judge.md"))
    (should-not-contain "tools:" (read "agents/coder.md")))

  (it "keeps every Vibe agent description of the runtime's roles within 300 characters"
    (source-checkout!)
    (doseq [name ["roles.conf" "tiers.conf" "routes.conf"]]
      (write-conf! *dir* name (slurp (str (fs/path runtime-home name)))))
    (doseq [role (map first (config/rows "roles.conf"))]
      (write! (str "kevin/roles/" role ".md") (slurp (str (fs/path runtime-home "roles" (str role ".md"))))))
    (should= 0 (:status (captured #(adapters/adapters []))))
    (doseq [file (fs/glob (fs/path *dir* "ai.mistral.vibe/agents") "*.toml")]
      (should (<= (count (vibe-description (slurp (str file)))) 300))))

  (it "refuses a description over 300 characters, names the role and length, and writes nothing"
    (source-checkout!)
    (captured #(adapters/adapters []))
    (write-gate!)
    (let [before (snapshot)
          summary (apply str (repeat 290 "x"))]
      (write! "kevin/roles/falsifier.md" (str "# falsifier\n\n" summary "\n"))
      (let [{:keys [status err]} (captured #(adapters/adapters []))
            length (count (adapters/description "falsifier" :gate summary))]
        (should= 1 status)
        (should-contain "falsifier" err)
        (should-contain (str length) err))
      (should= (dissoc before (str (fs/path *dir* "kevin/roles/falsifier.md")))
               (dissoc (snapshot) (str (fs/path *dir* "kevin/roles/falsifier.md"))))))

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
