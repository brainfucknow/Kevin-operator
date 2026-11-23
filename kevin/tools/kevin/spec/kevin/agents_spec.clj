(ns kevin.agents-spec
  (:require [clojure.string :as str]
            [speclj.core :refer [describe context it should= should-be-nil should-contain should-not-contain]]
            [kevin.agents :as agents]))

(def ^:private roles
  [["specifier" "frontier" "session"]
   ["falsifier" "strong" "subagent" "gate"]
   ["coder" "strong" "subagent" "audit"]
   ["cleaner" "fast" "subagent"]])

(def ^:private tiers
  [["frontier" "inherit" "high" "gpt-a"]
   ["strong" "opus" "medium" "gpt-a"]
   ["fast" "sonnet" "low" "gpt-b"]])

(def ^:private vibe-tiers
  [["strong" "opus" "medium" "gpt-a" "inherit" "inherit"]
   ["fast" "sonnet" "low" "gpt-b" "inherit" "mistral-small-latest"]])

(def ^:private text
  {:summary "Cleans up." :prompt "<home>/roles/cleaner.md"})

(defn- fields [harness roles tiers role]
  (agents/fields harness roles tiers role text))

(def ^:private templates
  {:stage "Role {{role}} reads {{prompt}}.\n" :gate "Gate {{role}} reads {{prompt}}.\n" :spec-judge "Judge {{role}}.\n"})

(describe "summary"
  (it "is the first non-blank line after the title"
    (should= "Draws." (agents/summary "# designer\n\n  \nDraws.\nMore."))
    (should-be-nil (agents/summary "# designer\n"))))

(describe "fields"
  (it "takes the model of the harness's column of the role's tier"
    (should= "sonnet" (:model (fields "claude" roles tiers "cleaner")))
    (should= "low" (:model (fields "codex" roles tiers "cleaner")))
    (should= "gpt-b" (:model (fields "copilot" roles tiers "cleaner"))))

  (it "is nil for a session role and a role that roles.conf lacks"
    (should-be-nil (fields "claude" roles tiers "specifier"))
    (should-be-nil (fields "claude" roles tiers "designer")))

  (it "names the kind, and the description holds the summary and when to use the agent"
    (should= :gate (:kind (fields "claude" roles tiers "falsifier")))
    (should= :stage (:kind (fields "claude" roles tiers "coder")))
    (should= "Kevin cleaner role. Cleans up. Use for a task whose stage is cleaner."
             (:description (fields "claude" roles tiers "cleaner")))))

(defn- resolution [harness project-tiers]
  (agents/resolution harness "cleaner"
                     (fields harness roles project-tiers "cleaner")
                     (fields harness roles tiers "cleaner")))

(defn- fast [& columns] (conj (pop tiers) (into ["fast"] columns)))

(describe "resolution"
  (context "claude"
    (it "names the plugin agent when nothing differs"
      (should= {:agent "kevin:cleaner"} (resolution "claude" tiers)))

    (it "passes an alias when only the model differs"
      (should= {:agent "kevin:cleaner" :model "haiku"} (resolution "claude" (fast "haiku" "low" "gpt-b"))))

    (it "names the project agent for a full id or inherit"
      (should= {:agent "kevin-cleaner" :path ".claude/agents/kevin-cleaner.md"}
               (resolution "claude" (fast "claude-haiku-5-5" "low" "gpt-b")))
      (should= "kevin-cleaner" (:agent (resolution "claude" (fast "inherit" "low" "gpt-b")))))

    (it "ignores the columns of the other harnesses"
      (should= {:agent "kevin:cleaner"} (resolution "claude" (fast "sonnet" "high" "gpt-z"))))

    (it "names the project agent when more than the model differs"
      (should= "kevin-cleaner"
               (:agent (agents/resolution "claude" "cleaner"
                                          (assoc (fields "claude" roles tiers "cleaner") :model "haiku" :kind :gate)
                                          (fields "claude" roles tiers "cleaner")))))

    (it "names the project agent of a role the runtime has no agent for"
      (should= {:agent "kevin-cleaner" :path ".claude/agents/kevin-cleaner.md"}
               (agents/resolution "claude" "cleaner" (fields "claude" roles (fast "haiku" "low" "x") "cleaner") nil))))

  (context "copilot"
    (it "names the role, with a project path only when the agent differs"
      (should= {:agent "cleaner"} (resolution "copilot" tiers))
      (should= {:agent "cleaner" :path ".github/agents/cleaner.agent.md"} (resolution "copilot" (fast "sonnet" "low" "gpt-z")))))

  (context "codex"
    (it "names no agent, or the project agent when the effort differs"
      (should= {:agent "none"} (resolution "codex" tiers))
      (should= {:agent "cleaner" :path ".codex/agents/cleaner.toml"} (resolution "codex" (fast "sonnet" "high" "gpt-b"))))))

(defn- render [harness role-fields & [stamp]]
  (agents/render harness (if (= "claude" harness) "kevin-cleaner" "cleaner") role-fields templates stamp))

(describe "render"
  (it "writes a Claude Code agent with a quoted description and the body of its kind"
    (let [out (render "claude" (fields "claude" roles tiers "cleaner"))]
      (should= (str "---\nname: kevin-cleaner\n"
                    "description: \"Kevin cleaner role. Cleans up. Use for a task whose stage is cleaner.\"\n"
                    "model: sonnet\n---\n\nRole cleaner reads <home>/roles/cleaner.md.\n")
               out)))

  (it "gives a gate the read-only tools"
    (should-contain "\ntools: Read, Grep, Glob, Bash\n" (render "claude" (fields "claude" roles tiers "falsifier")))
    (should-contain "\ntools: [\"read\", \"search\", \"shell\"]\n" (render "copilot" (fields "copilot" roles tiers "falsifier")))
    (should-contain "\ntools: [\"*\"]\n" (render "copilot" (fields "copilot" roles tiers "cleaner"))))

  (it "puts the stamp in the front matter of a Markdown agent and first in a TOML agent"
    (let [claude (render "claude" (fields "claude" roles tiers "cleaner") "0.3.0")
          codex (render "codex" (fields "codex" roles tiers "cleaner") "0.3.0")]
      (should-contain "\nmodel: sonnet\n# kevin-generated: 0.3.0\n---\n" claude)
      (should= "# kevin-generated: 0.3.0" (first (str/split-lines codex)))
      (should-contain "\nname = \"cleaner\"\n" codex)
      (should-contain "\nmodel_reasoning_effort = \"low\"\n" codex)))

  (it "escapes the description and the body of a TOML agent"
    (let [codex (render "codex" (assoc (fields "codex" roles tiers "cleaner")
                                       :description "a \\ \"b\""
                                       :prompt "C:\\x\"\"\"y"))]
      (should-contain "description = \"a \\\\ \\\"b\\\"\"" codex)
      (should-contain "reads C:\\\\x\\\"\\\"\\\"y." codex)
      (should-not-contain "\"\"\"y" codex)))

  (context "vibe"
    (it "writes a Vibe plugin agent with the schema version, display name, model and commit trailer"
      (should= (str "schema_version = 1\nagent_type = \"subagent\"\ndisplay_name = \"Cleaner\"\n"
                    "description = \"Kevin cleaner role. Cleans up. Use for a task whose stage is cleaner.\"\n"
                    "active_model = \"mistral-small-latest\"\n"
                    "instructions = \"\"\"\nRole cleaner reads <home>/roles/cleaner.md.\n"
                    "\n4. End every commit message you write with these two lines:\n\n"
                    "Generated by Mistral Vibe.\nCo-Authored-By: Mistral Vibe <vibe@mistral.ai>\n\"\"\"\n")
               (render "vibe" (fields "vibe" roles vibe-tiers "cleaner"))))

    (it "leaves out the model when the tier inherits it or names none, and gives a gate the read-only tools"
      (let [gate (render "vibe" (fields "vibe" roles vibe-tiers "falsifier"))]
        (should-not-contain "active_model" gate)
        (should-contain "\nenabled_tools = [\"read_file\", \"grep\", \"glob\", \"bash\"]\ninstructions" gate))
      (should-not-contain "active_model" (render "vibe" (fields "vibe" roles tiers "cleaner"))))

    (it "capitalizes each word of the display name"
      (should-contain "\ndisplay_name = \"Spec Judge\"\n"
                      (agents/render "vibe" "spec-judge" (fields "vibe" roles vibe-tiers "cleaner") templates nil)))))
