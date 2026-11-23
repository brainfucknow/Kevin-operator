(ns kevin.agents
  "The role agents of the harnesses claude, copilot and codex as plain values.
  The fields of a role's agent come from roles.conf and tiers.conf rows;
  comparing the fields built from the project's rows with those built from
  the runtime's tells which agent the orchestrator delegates to, and render
  turns fields into the text of an agent file."
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(def harnesses ["claude" "copilot" "codex"])

(def aliases
  "The models that Claude Code takes on a delegation call."
  #{"sonnet" "opus" "haiku" "fable"})

(def stamp-label "kevin-generated:")

(defn summary
  "The first non-blank line after the title of the role prompt text."
  [text]
  (first (remove str/blank? (rest (str/split-lines text)))))

(def kinds
  "The kinds of subagent, each with its adapter template and when to use it."
  {:stage {:template "adapter.md" :noun "role" :use "Use for a task whose stage is {{role}}."}
   :gate {:template "gate-adapter.md" :noun "gate"
          :use "Use at a task's specification gate, after the specifier's kevin review and before the operator approves."}
   :spec-judge {:template "spec-judge-adapter.md" :noun "spec-judge"
                :use "Use at a task's specification gate, after the gates of each spec revision, for the spec revision's spec verdict."}})

(def gate-tools
  "The tools of a gate's and the spec-judge's agent, by harness: reading,
  searching and the shell for kevin show, kevin spec-revisions and git, without the
  editing tools."
  {:claude "Read, Grep, Glob, Bash"
   :copilot "[\"read\", \"search\", \"shell\"]"
   :vibe "enabled_tools = [\"read_file\", \"grep\", \"glob\", \"bash\"]\n"})

(defn kind
  "The kind of the roles.conf row."
  [row]
  (let [flags (set (drop 3 row))]
    (cond (flags "gate") :gate
          (flags "spec-judge") :spec-judge
          :else :stage)))

(defn description [role kind summary]
  (let [{:keys [noun use]} (kinds kind)]
    (str "Kevin " role " " noun ". " summary " " (str/replace use "{{role}}" role))))

(def ^:private model-column {"claude" 1 "codex" 2 "copilot" 3})

(defn- row-of [rows key]
  (some #(when (= key (first %)) %) rows))

(defn fields
  "The agent of the role in the harness, from roles and tiers (the rows of
  roles.conf and tiers.conf), or nil when the role is not a subagent there.
  summary is the summary line of the role's prompt; prompt is the path its
  agent tells the role to read. :model is the harness's tier column: the
  reasoning effort for codex."
  [harness roles tiers role {:keys [summary prompt]}]
  (let [[_ tier runs :as row] (row-of roles role)]
    (when (= "subagent" runs)
      (let [kind (kind row)]
        {:role role
         :kind kind
         :description (description role kind summary)
         :model (get (row-of tiers tier) (model-column harness))
         :prompt prompt}))))

(defn- plugin-agent [harness role]
  (case harness "claude" (str "kevin:" role) "copilot" role "codex" "none"))

(defn project-agent
  "The name and path of the role's project agent in the harness."
  [harness role]
  (case harness
    "claude" {:agent (str "kevin-" role) :path (str ".claude/agents/kevin-" role ".md")}
    "copilot" {:agent role :path (str ".github/agents/" role ".agent.md")}
    "codex" {:agent role :path (str ".codex/agents/" role ".toml")}))

(defn resolution
  "How the orchestrator runs the role in the harness, from the fields of its
  agent built from the project's rows and from the runtime's (nil when the
  runtime has no agent for it): {:agent name}, with :model when Claude Code
  takes the project's model on the delegation call, or with :path when the
  role needs a project agent."
  [harness role project runtime]
  (cond
    (= project runtime) {:agent (plugin-agent harness role)}

    (and (= "claude" harness) runtime
         (= (dissoc project :model) (dissoc runtime :model))
         (aliases (:model project)))
    {:agent (plugin-agent harness role) :model (:model project)}

    :else (project-agent harness role)))

(defn toml-escape
  "Escapes s for use inside a TOML basic string."
  [s]
  (apply str (map (fn [c]
                    (cond
                      (= c \\) "\\\\"
                      (= c \") "\\\""
                      (or (< (int c) 0x20) (= (int c) 0x7f)) (format "\\u%04x" (int c))
                      :else (str c)))
                  s)))

(defn- toml-multiline
  "Escapes s for use inside a TOML multi-line basic string; newlines stay."
  [s]
  (str/join "\n" (map toml-escape (str/split s #"\n" -1))))

(defn body
  "The body of the agent: the template of its kind with its role and prompt."
  [templates {:keys [role kind prompt]}]
  (-> (templates kind) (str/replace "{{role}}" role) (str/replace "{{prompt}}" prompt)))

(defn render
  "The text of the agent file named name for the fields in the harness.
  templates maps each kind to its template text. stamp is the runtime version
  of a project agent, or nil for a plugin agent."
  [harness name {:keys [kind description model] :as fields} templates stamp]
  (let [read-only? (not= :stage kind)
        stamp-line (when stamp (str "# " stamp-label " " stamp))
        text (body templates fields)]
    (case harness
      "claude"
      (str "---\nname: " name "\ndescription: " (json/generate-string description) "\nmodel: " model
           (when read-only? (str "\ntools: " (:claude gate-tools)))
           (when stamp (str "\n" stamp-line)) "\n---\n\n" text)

      "copilot"
      (str "---\nname: " name "\ndescription: " (json/generate-string description) "\nmodel: " model
           "\ntools: " (if read-only? (:copilot gate-tools) "[\"*\"]") "\ninclude-custom-instructions: true"
           (when stamp (str "\n" stamp-line)) "\n---\n\n" text)

      "codex"
      (str (when stamp (str stamp-line "\n"))
           "name = \"" (toml-escape name) "\"\ndescription = \"" (toml-escape description)
           "\"\nmodel_reasoning_effort = \"" (toml-escape model) "\"\ndeveloper_instructions = \"\"\"\n"
           (toml-multiline text) "\"\"\"\n"))))

(defn stamped?
  "True when the agent text has the line that marks a project agent kevin wrote."
  [text]
  (boolean (some #(str/starts-with? % (str "# " stamp-label)) (str/split-lines text))))
