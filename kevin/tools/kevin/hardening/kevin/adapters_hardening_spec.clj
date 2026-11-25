(ns kevin.adapters-hardening-spec
  (:require [speclj.core :refer [describe it around should= should-contain should-not-contain]]
            [kevin.adapters :as adapters]
            [kevin.repo-support :refer [*dir* with-repo write-conf! write! captured]]))

(def ^:private legacy {:templates "legacy/" :summary "Audits."})

(describe "agent-files of the removed committed install"
  (around [it] (with-repo it))

  (it "gives a gate read-only tools in each harness that has them"
    (let [files (adapters/agent-files "coder" "strong" :gate legacy)]
      (should-contain "\ntools: Read, Grep, Glob, Bash\n" (get files ".claude/agents/coder.md"))
      (should-contain "\ntools: [\"read\", \"search\", \"shell\"]\n" (get files ".github/agents/coder.agent.md"))
      (should-contain "enabled_tools = [\"read_file\", \"grep\", \"glob\", \"bash\"]" (get files ".vibe/agents/coder.toml"))))

  (it "gives a stage every tool and no tool line in Claude Code and Vibe"
    (let [files (adapters/agent-files "coder" "strong" :stage legacy)]
      (should-not-contain "tools:" (get files ".claude/agents/coder.md"))
      (should-contain "\ntools: [\"*\"]\n" (get files ".github/agents/coder.agent.md"))
      (should-not-contain "enabled_tools" (get files ".vibe/agents/coder.toml"))))

  (it "names no Kilo or Vibe model for a tier that inherits"
    (write-conf! *dir* "tiers.conf" "frontier inherit high gpt inherit inherit\nstrong opus medium gpt-5 kilo/m vibe/m\nfast sonnet low gpt-mini kilo/f vibe/f\n")
    (let [files (adapters/agent-files "specifier" "frontier" :stage legacy)]
      (should-not-contain "model:" (get files ".kilo/agent/specifier.md"))
      (should-not-contain "active_model" (get files ".vibe/agents/specifier.toml"))))

  (it "names no model for a tier without a Kilo or Vibe column"
    (write-conf! *dir* "tiers.conf" "frontier inherit high gpt\nstrong opus medium gpt-5\nfast sonnet low gpt-mini\n")
    (let [files (adapters/agent-files "coder" "strong" :stage legacy)]
      (should-not-contain "model:" (get files ".kilo/agent/coder.md"))
      (should-not-contain "active_model" (get files ".vibe/agents/coder.toml")))))

(describe "kevin adapters in the source checkout"
  (around [it] (with-repo it))

  (it "reports the tier of each subagent role only"
    (write! ".claude-plugin/plugin.json" "{\"name\": \"kevin\", \"version\": \"0.3.0\"}\n")
    (write-conf! *dir* "roles.conf" "specifier frontier subagent\ncoder strong subagent\ncleaner fast session\n")
    (should= "specifier: frontier\ncoder: strong" (:out (captured #(adapters/adapters []))))))
