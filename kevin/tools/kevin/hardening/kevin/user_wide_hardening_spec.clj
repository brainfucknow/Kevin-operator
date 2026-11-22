(ns kevin.user-wide-hardening-spec
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [speclj.core :refer [around describe it should should-be-nil should-contain should-not-contain should=]]
            [kevin.adapters :as adapters]
            [kevin.convert :as convert]
            [kevin.repo-support :refer [*dir* with-repo write-conf!]]
            [kevin.shell :as shell]))

(defn- agent-files [role tier kind summary]
  (adapters/agent-files role tier kind {:templates "" :summary summary}))

(describe "agent tools by kind"
  (around [it] (with-repo it))

  (it "gives a gate agent the read-only tools and a stage agent no tools line"
    (let [gate (agent-files "falsifier" "strong" :gate "Audits.")
          stage (agent-files "coder" "strong" :stage "Codes.")]
      (should-contain "\ntools: Read, Grep, Glob, Bash\n" (get gate ".claude/agents/falsifier.md"))
      (should-contain "tools: [\"read\", \"search\", \"shell\"]" (get gate ".github/agents/falsifier.agent.md"))
      (should-contain "enabled_tools = [" (get gate ".vibe/agents/falsifier.toml"))
      (should-not-contain "tools:" (get stage ".claude/agents/coder.md"))
      (should-contain "tools: [\"*\"]" (get stage ".github/agents/coder.agent.md"))
      (should-not-contain "enabled_tools" (get stage ".vibe/agents/coder.toml"))))

  (it "writes no model line for a tier that inherits the harness model"
    (write-conf! *dir* "tiers.conf" "frontier inherit high gpt inherit inherit inherit\n")
    (let [files (adapters/agent-files "specifier" "frontier" :stage {:templates "legacy/" :summary "Specifies."})]
      (should-not-contain "model:" (get files ".kilo/agent/specifier.md"))
      (should-not-contain "active_model" (get files ".vibe/agents/specifier.toml"))))

  (it "escapes quotes, backslashes and control characters in a Codex description"
    (let [text (get (agent-files "coder" "strong" :stage "Say \"hi\" \\ a\tb\u007fc.") ".codex/agents/coder.toml")
          description (first (filter #(str/starts-with? % "description = ") (str/split-lines text)))]
      (should-contain "Say \\\"hi\\\" \\\\ a\\u0009b\\u007fc." description)
      (should-contain "Kevin coder role. Say" description))))

(describe "remove-lines"
  (it "reports a change only when a dropped line was present"
    (let [dir (str (fs/create-temp-dir {:prefix "convert-hardening-"}))
          remove-lines #'convert/remove-lines]
      (spit (str (fs/path dir "f")) "a\nb\n")
      (should= nil (remove-lines dir "f" #{"zzz"}))
      (should= true (remove-lines dir "f" #{"a"}))
      (should= "b\n" (slurp (str (fs/path dir "f"))))
      (should= true (remove-lines dir "f" #{"b"}))
      (should (not (fs/exists? (fs/path dir "f"))))
      (fs/delete-tree dir))))

(describe "main-root"
  (it "is nil outside a repository and the parent of the common git dir inside one"
    (with-redefs [shell/run (constantly {:exit 128 :out "" :err "fatal"})]
      (should-be-nil (shell/main-root)))
    (with-redefs [shell/run (constantly {:exit 0 :out "/a/b/.git\n" :err ""})]
      (should= "/a/b" (shell/main-root)))))
