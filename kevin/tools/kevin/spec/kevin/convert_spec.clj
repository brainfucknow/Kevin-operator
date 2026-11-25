(ns kevin.convert-spec
  "kevin init --convert on a committed install in a throwaway repository."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [speclj.core :refer [around describe it should should-not should= should-contain should-not-contain]]
            [kevin.adapters :as adapters]
            [kevin.agents :as agents]
            [kevin.config :as config]
            [kevin.convert :as convert]
            [kevin.repo-support :refer [*dir* captured git with-repo write!]]
            [kevin.installed :as installed]))

(def ^:dynamic *runtime* nil)

(def swarm-line
  "Follow kevin/README.md and kevin/roles/orchestrator.md when asked to swarm a feature; delegate subagent roles by name and keep all task bookkeeping in kevin/bin/kevin.")

(defn- read [path] (slurp (str (fs/path *dir* path))))

(defn- exists? [path] (fs/exists? (fs/path *dir* path)))

(defn- tracked [] (set (str/split-lines (git *dir* "ls-files"))))

(defn- committed-install!
  "The layout the removed install.sh <project> committed, with the agents
  that its templates generated."
  []
  (doseq [[path text] {"kevin/bin/kevin" "#!/usr/bin/env bb\n"
                       "kevin/bin/kevin-cs" "#!/usr/bin/env bash\n"
                       "kevin/tools/kevin/src/kevin/cli.clj" "(ns kevin.cli)\n"
                       "kevin/languages/java.md" "# Java\n"
                       "kevin/constitution.md" "# Kevin Constitution\n"
                       "kevin/constitution/articles/engineering.md" "# Engineering\n"
                       "kevin/constitution/articles/project.md" "# Mine\n"
                       "kevin/README.md" "# Kevin\n"
                       "kevin/handoff-protocol.md" "# Protocol\n"
                       "kevin/.gitignore" "!/bin/\n!/bin/**\n"
                       ".claude/skills/swarm/SKILL.md" "skill\n"
                       ".agents/skills/swarm/SKILL.md" "skill\n"
                       ".vibe/skills/swarm/SKILL.md" "skill\n"
                       ".kilo/command/swarm.md" "command\n"
                       "AGENTS.md" (str swarm-line "\n")
                       "CLAUDE.md" "@AGENTS.md\n"
                       ".github/copilot-instructions.md" "Follow AGENTS.md at the repository root.\n"}]
    (write! path text))
  (binding [config/*root* *dir*]
    (doseq [[role tier :as row] (config/rows "roles.conf")
            [path text] (adapters/agent-files role tier (agents/kind row)
                                              {:templates "legacy/"
                                               :summary (agents/summary (read (str "kevin/roles/" role ".md")))})]
      (write! path text)))
  (git *dir* "add" "-A")
  (git *dir* "commit" "-q" "-m" "Committed install"))

(defn- with-install
  "Runs it in a repository with a committed install, and a runtime apart from
  it: a copy of the repository's kevin/."
  [it]
  (with-repo
    (fn []
      (let [runtime (str (fs/create-temp-dir {:prefix "kevin-runtime-"}))]
        (try
          (fs/copy-tree (fs/path *dir* "kevin") runtime {:replace-existing true})
          (committed-install!)
          (with-redefs [installed/home (constantly runtime)
                        installed/version (constantly "0.2.0")]
            (binding [*runtime* runtime] (it)))
          (finally (fs/delete-tree runtime)))))))

(defn- convert [] (captured convert/convert))

(describe "kevin init --convert"
  (around [it] (with-install it))

  (it "keeps only the configuration and stages the change without a commit"
    (let [head (git *dir* "rev-parse" "HEAD")
          {:keys [status]} (convert)
          files (tracked)]
      (should= 0 status)
      (should= head (git *dir* "rev-parse" "HEAD"))
      (should= #{"kevin/project.md" "kevin/roles.conf" "kevin/routes.conf" "kevin/tiers.conf" "kevin/tools.conf"}
               (set (filter #(str/starts-with? % "kevin/") files)))
      (should= #{} (set (filter #(re-find #"^\.(claude|github|codex|kilo|vibe|agents)/" %) files)))
      (should-not (exists? "AGENTS.md"))
      (should-not (exists? "CLAUDE.md"))
      (should-not (exists? ".github/copilot-instructions.md"))
      (should-not (exists? "kevin/.gitignore"))
      (should= "# Mine\nMinimum Kevin version: 0.2.0\n" (read "kevin/project.md"))
      (let [staged (git *dir* "diff" "--cached" "--name-status")]
        (git *dir* "add" "-A")
        (should= staged (git *dir* "diff" "--cached" "--name-status")))))

  (it "keeps an agent that is not Kevin's, and names the ones under a role name"
    (write! ".claude/agents/coder.md" "my own coder\n")
    (write! ".claude/agents/helper.md" "my own helper\n")
    (git *dir* "add" "-A")
    (git *dir* "commit" "-q" "-m" "Own agents")
    (let [{:keys [status out]} (convert)]
      (should= 0 status)
      (should= "my own coder\n" (read ".claude/agents/coder.md"))
      (should= "my own helper\n" (read ".claude/agents/helper.md"))
      (should-contain "kevin: kept .claude/agents/coder.md; it is not Kevin's generated agent" out)
      (should-not-contain "helper" out)
      (should-not (exists? ".codex/agents/coder.toml"))))

  (it "recognizes an agent by its digest in .kevin/adapters.edn"
    (write! ".codex/agents/coder.toml" "an older generated body\n")
    (git *dir* "add" "-A")
    (git *dir* "commit" "-q" "-m" "Older agent")
    (write! ".kevin/adapters.edn" (pr-str {".codex/agents/coder.toml" (convert/digest "an older generated body\n")}))
    (convert)
    (should-not (exists? ".codex/agents/coder.toml")))

  (it "recognizes agents with other models and tools than the project's tiers give"
    (write! ".claude/agents/coder.md" (str/replace (read ".claude/agents/coder.md") "model: opus" "model: haiku"))
    (git *dir* "add" "-A")
    (git *dir* "commit" "-q" "-m" "Other model")
    (convert)
    (should-not (exists? ".claude/agents/coder.md")))

  (it "recognizes gate and spec-judge agents that carry the old use sentences"
    (doseq [roles-conf [(fs/path *dir* "kevin" "roles.conf") (fs/path *runtime* "roles.conf")]]
      (spit (str roles-conf)
            (str (slurp (str roles-conf)) "falsifier strong subagent gate\nspec-judge strong subagent spec-judge\n")))
    (doseq [[role text] {"falsifier" "# falsifier\n\nAudits a specification.\n"
                         "spec-judge" "# spec-judge\n\nRules on a spec revision.\n"}]
      (write! (str "kevin/roles/" role ".md") text)
      (spit (str (fs/path *runtime* "roles" (str role ".md"))) text))
    (let [old-uses {"falsifier" "Use at a task's specification gate, after the specifier's kevin review and before the operator approves."
                    "spec-judge" "Use at a task's specification gate, after the gates of each spec revision, for the spec revision's spec verdict."}
          paths (for [[role use] old-uses
                      templates ["" "legacy/"]
                      [path text] (binding [config/*root* *dir*]
                                    (adapters/agent-files role "strong" (if (= role "falsifier") :gate :spec-judge)
                                                          {:templates templates
                                                           :summary (agents/summary (read (str "kevin/roles/" role ".md")))
                                                           :use use}))]
                  (do (write! path text) path))]
      (doall paths)
      (git *dir* "add" "-A")
      (git *dir* "commit" "-q" "-m" "Old gate agents")
      (should= 0 (:status (convert)))
      (doseq [path paths]
        (should-not (exists? path)))))

  (it "keeps the other lines of the instruction files"
    (write! "AGENTS.md" (str "Project notes.\n" swarm-line "\n"))
    (write! "CLAUDE.md" "Claude notes.\n@AGENTS.md\n")
    (git *dir* "add" "-A")
    (git *dir* "commit" "-q" "-m" "Notes")
    (convert)
    (should= "Project notes.\n" (read "AGENTS.md"))
    (should= "Claude notes.\n@AGENTS.md\n" (read "CLAUDE.md")))

  (it "removes the old swarmforge line"
    (write! "AGENTS.md" "Follow swarmforge/README.md and swarmforge/roles/orchestrator.md when asked to swarm a feature; delegate subagent roles by name and keep all task bookkeeping in swarmforge/bin/sf.\n")
    (write! "CLAUDE.md" "Claude notes.\n@AGENTS.md\n")
    (git *dir* "add" "-A")
    (git *dir* "commit" "-q" "-m" "Old line")
    (convert)
    (should-not (exists? "AGENTS.md"))
    (should= "Claude notes.\n" (read "CLAUDE.md")))

  (it "rewrites the runtime paths in kept configuration other than tools.conf"
    (write! "kevin/verify.conf" (str "bash kevin/bin/kevin-cs test\nkevin/bin/kevin check\n"
                                     "sh  ./kevin/bin/kevin-rb crap\nbb kevin/bin/kevin help\n"
                                     "make && bash kevin/bin/kevin-cs test\n"
                                     "bash test/all.sh && kevin/bin/kevin check\n"
                                     "rebash kevin/bin/kevin-cs test\n"
                                     "true;bash kevin/bin/kevin-cs test\n"
                                     "(bash kevin/bin/kevin-cs test)\n"
                                     "foo|sh kevin/bin/kevin-rb crap\n"
                                     "make&&bb ./kevin/bin/kevin help\n"
                                     "echo `bash kevin/bin/kevin help`\n"
                                     "X=bash kevin/bin/kevin-cs test\n"
                                     "run.sh kevin/bin/kevin check\n"
                                     "/bin/bash kevin/bin/kevin-cs test\n"))
    (write! "kevin/tools.conf" (str (read "kevin/tools.conf") "# kevin/bin/kevin-cs\n"))
    (git *dir* "add" "-A")
    (git *dir* "commit" "-q" "-m" "Verify")
    (let [{:keys [out]} (convert)]
      (should= (str "kevin cs test\nkevin check\n"
                    "kevin rb crap\nkevin help\n"
                    "make && kevin cs test\n"
                    "bash test/all.sh && kevin check\n"
                    "rebash kevin cs test\n"
                    "true;kevin cs test\n"
                    "(kevin cs test)\n"
                    "foo|kevin rb crap\n"
                    "make&&kevin help\n"
                    "echo `kevin help`\n"
                    "X=bash kevin cs test\n"
                    "run.sh kevin check\n"
                    "/bin/bash kevin cs test\n")
               (read "kevin/verify.conf"))
      (should-contain "kevin: rewrote kevin/verify.conf" out)
      (should-contain "# kevin/bin/kevin-cs" (read "kevin/tools.conf"))
      (should-not-contain "tools.conf" out)
      (should= "" (git *dir* "diff" "--name-only" "--" "kevin/verify.conf"))))

  (it "refuses a tracked file with changes and changes nothing"
    (write! "kevin/routes.conf" "changed\n")
    (let [before (git *dir* "status" "--porcelain" "--untracked-files=all")
          {:keys [status err]} (convert)]
      (should= 1 status)
      (should= "commit or stash your changes before kevin init --convert" err)
      (should= before (git *dir* "status" "--porcelain" "--untracked-files=all"))
      (should (exists? "kevin/bin/kevin"))))

  (it "ends in the same state when run again"
    (convert)
    (let [before (git *dir* "status" "--porcelain" "--untracked-files=all")
          {:keys [status]} (convert)]
      (should= 0 status)
      (should= before (git *dir* "status" "--porcelain" "--untracked-files=all"))
      (should= 1 (count (filter #{"Minimum Kevin version: 0.2.0"} (str/split-lines (read "kevin/project.md"))))))))
