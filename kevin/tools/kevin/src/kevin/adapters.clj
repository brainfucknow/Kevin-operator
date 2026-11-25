(ns kevin.adapters
  "Agent files for each subagent role. In the Kevin source checkout, kevin
  adapters writes the plugin agents: Claude Code's agents/<role>.md,
  Copilot's .github/plugin/agents/<role>.agent.md, and Mistral Vibe's
  ai.mistral.vibe/agents/<role>.toml. In any project it then
  writes the project agents (kevin.project-agents). The five shapes that an
  old committed install wrote into a project stay here so kevin init
  --convert can recognize them."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [kevin.agents :as agents]
            [kevin.config :as config]
            [kevin.installed :as installed]
            [kevin.project-agents :as project-agents]
            [kevin.shell :as shell]))

(defn code-point-count
  "Count Unicode code points in a string, not UTF-16 code units."
  [s]
  (.codePointCount s 0 (.length s)))

(def max-description
  "The most characters (Unicode code points) Vibe accepts in a plugin agent description."
  300)

(defn- runtime-prompt
  "The text of the runtime's prompt of the role."
  [role]
  (let [file (fs/path (installed/home) "roles" (str role ".md"))]
    (when-not (fs/exists? file) (shell/die "missing role prompt " file))
    (slurp (str file))))

;; The shapes of the removed committed install, for kevin init --convert.

(defn- kilo-model [tier]
  (let [model (config/tier-field tier 5)]
    (when (and model (not= "inherit" model))
      (str "model: " model "\n"))))

(defn- vibe-model [tier]
  (let [model (config/tier-field tier 6)]
    (when (and model (not= "inherit" model))
      (str "active_model = \"" (agents/toml-escape model) "\"\n"))))

(defn agent-files
  "The agent of each harness for the role as kevin wrote it into a project:
  path to text. templates is the resource directory of the templates (\"\"
  for the current ones, \"legacy/\" for those of the removed committed
  install), summary the first line of the role prompt, use the sentence saying
  when to use the agent (the kind's current one when absent)."
  [role tier kind {:keys [templates summary use] :or {use (:use (agents/kinds kind))}}]
  (let [{:keys [template]} (agents/kinds kind)
        read-only? (not= :stage kind)
        body (-> (shell/template (str templates template))
                 (str/replace "{{prompt}}" (agents/home-prompt role))
                 (str/replace "{{role}}" role))
        description (agents/description role kind summary use)
        toml-escape agents/toml-escape
        gate-tools agents/gate-tools]
    {(str ".claude/agents/" role ".md")
     (str "---\nname: " role "\ndescription: " description "\nmodel: " (config/tier-field tier 2)
          (when read-only? (str "\ntools: " (:claude gate-tools))) "\n---\n\n" body)

     (str ".github/agents/" role ".agent.md")
     (str "---\nname: " role "\ndescription: " description "\nmodel: " (config/tier-field tier 4)
          "\ntools: " (if read-only? (:copilot gate-tools) "[\"*\"]") "\ninclude-custom-instructions: true\n---\n\n" body)

     (str ".codex/agents/" role ".toml")
     (str "name = \"" role "\"\ndescription = \"" (toml-escape description) "\"\nmodel_reasoning_effort = \""
          (config/tier-field tier 3) "\"\ndeveloper_instructions = \"\"\"\n" body "\"\"\"\n")

     (str ".kilo/agent/" role ".md")
     (str "---\ndescription: " description "\nmode: subagent\n" (kilo-model tier)
          "---\n\n" body)

     (str ".vibe/agents/" role ".toml")
     (str "agent_type = \"subagent\"\n"
          "display_name = \"" (agents/display-name role) "\"\n"
          "description = \"" (toml-escape description) "\"\n"
          (vibe-model tier)
          (when read-only? (:vibe gate-tools))
          "instructions = \"\"\"\n" (str body agents/vibe-trailer-instruction) "\"\"\"\n")}))

;; Agents rendered from the model in kevin.agents.

(def plugin-dirs
  "The plugin agent directories of the source checkout, with the harness
  of each and the file suffix."
  {"agents" {:harness "claude" :suffix ".md"}
   ".github/plugin/agents" {:harness "copilot" :suffix ".agent.md"}
   "ai.mistral.vibe/agents" {:harness "vibe" :suffix ".toml"}})

(defn- plugin-agents
  "The plugin agents of the checkout's subagent roles: path to text."
  []
  (let [roles (config/rows "roles.conf")
        tiers (config/rows "tiers.conf")
        templates (project-agents/templates)]
    (doseq [role (agents/subagents roles)
            :let [text {:summary (agents/summary (runtime-prompt role)) :prompt (agents/home-prompt role)}
                  length (code-point-count (:description (agents/fields "vibe" roles tiers role text)))]
            :when (> length max-description)]
      (shell/die (str "kevin adapters: the description of " role " is " length
                      " characters; Vibe accepts at most " max-description)))
    (into {} (for [role (agents/subagents roles)
                   :let [text {:summary (agents/summary (runtime-prompt role)) :prompt (agents/home-prompt role)}]
                   [dir {:keys [harness suffix]}] plugin-dirs]
               [(str dir "/" role suffix)
                (agents/render harness role (agents/fields harness roles tiers role text) templates nil)]))))

(defn- source-checkout?
  "True when root is the Kevin source checkout: its Claude Code plugin
  manifest names the plugin kevin."
  [root]
  (let [manifest (fs/path root ".claude-plugin" "plugin.json")]
    (and (fs/regular-file? manifest)
         (= "kevin" (get (try (json/parse-string (slurp (str manifest))) (catch Exception _ nil)) "name")))))

(defn- generated
  "The files in the plugin agent directories under root."
  [root]
  (for [[dir {:keys [suffix]}] plugin-dirs
        file (fs/glob (fs/path root dir) (str "*" suffix))]
    (str dir "/" (fs/file-name file))))

(defn- write-plugin-agents!
  "Writes the plugin agents of the checkout at root and deletes the others."
  [root]
  (let [desired (plugin-agents)]
    (doseq [path (generated root) :when (not (contains? desired path))]
      (fs/delete (fs/path root path)))
    (doseq [[path text] desired]
      (fs/create-dirs (fs/parent (fs/path root path)))
      (spit (str (fs/path root path)) text))
    (doseq [[role tier] (filter #(= "subagent" (nth % 2)) (config/rows "roles.conf"))]
      (println (str role ": " tier)))))

(defn adapters [_]
  (shell/quietly config/check)
  (let [root (shell/checkout-root)]
    (when (source-checkout? root)
      (write-plugin-agents! root)))
  (project-agents/sync!))
