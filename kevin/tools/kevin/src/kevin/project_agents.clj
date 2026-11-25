(ns kevin.project-agents
  "The project agents of the main checkout: the agents that differ from the
  plugin's because the project changes a tier or a role, or adds a role.
  kevin adapters and kevin init write them, untracked and excluded; kevin
  agent tells the orchestrator which agent to delegate a role to."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kevin.agents :as agents]
            [kevin.config :as config]
            [kevin.exclude :as exclude]
            [kevin.installed :as installed]
            [kevin.shell :as shell]))

(def ^:private max-vibe-description
  "The most characters (Unicode code points) Vibe accepts in a plugin agent description."
  300)

(defn- code-point-count
  "Returns the number of Unicode code points in the string, not UTF-16 code units."
  [s]
  (.codePointCount s 0 (count s)))

(defn templates
  "The adapter template text of each kind of subagent."
  []
  (into {} (for [[kind {:keys [template]}] agents/kinds] [kind (shell/template template)])))

(defn- context
  "The rows that the fields of each role's agent come from: the main
  checkout's and the runtime's."
  []
  {:roles (config/rows "roles.conf")
   :tiers (config/rows "tiers.conf")
   :runtime-roles (config/runtime-rows "roles.conf")
   :runtime-tiers (config/runtime-rows "tiers.conf")
   :templates (templates)})

(defn- role-text
  "The summary of the role's prompt and the prompt path that its agent names:
  <home>/roles/<role>.md for a runtime role, the absolute path of the main
  checkout's prompt for an added role."
  [role runtime-roles]
  (let [file (config/prompt-file role)]
    (when-not (fs/exists? file) (shell/die "missing role prompt " file))
    {:summary (agents/summary (slurp file))
     :prompt (if (some #{role} (map first runtime-roles)) (agents/home-prompt role) file)}))

(defn- resolve-agent
  "How the main checkout runs the role in the harness: the resolution of
  kevin.agents, with :text, the project agent file, when it has a :path."
  [harness role {:keys [roles tiers runtime-roles runtime-tiers templates]}]
  (let [text (role-text role runtime-roles)
        project (agents/fields harness roles tiers role text)
        runtime (agents/fields harness runtime-roles runtime-tiers role text)
        {:keys [agent path] :as resolution} (agents/resolution harness role project runtime)]
    (when (and (= "vibe" harness) (> (code-point-count (:description project)) max-vibe-description))
      (shell/die (str "kevin adapters: the Vibe description of " role
                   " is " (code-point-count (:description project))
                   " code points; Vibe accepts at most " max-vibe-description)))
    (cond-> resolution
      path (assoc :text (agents/render harness agent project templates (installed/version))))))

(defn- desired
  "The project agents that the main checkout needs: path to text."
  []
  (let [context (context)]
    (into {} (for [role (agents/subagents (:roles context))
                   harness agents/harnesses
                   :let [{:keys [path text]} (resolve-agent harness role context)]
                   :when path]
               [path text]))))

(def ^:private globs
  [[".claude/agents" "kevin-*.md"] [".github/agents" "*.agent.md"] [".codex/agents" "*.toml"]])

(def ^:private exclude-line
  #"/(\.claude/agents/kevin-[^/]+\.md|\.github/agents/[^/]+\.agent\.md|\.codex/agents/[^/]+\.toml)")

(defn paths
  "The files under root where project agents live, kevin's or not, sorted."
  [root]
  (sort (for [[dir glob] globs
              :when (fs/directory? (fs/path root dir))
              file (fs/glob (fs/path root dir) glob)]
          (str dir "/" (fs/file-name file)))))

(defn- linked?
  "True when the path under root, or a directory on the way to it, is a
  symbolic link."
  [root path]
  (boolean (some #(fs/sym-link? (fs/path root %)) (reductions #(str %1 "/" %2) (str/split path #"/")))))

(defn- kevins?
  "True when root holds a project agent that kevin wrote at path."
  [root path]
  (let [file (fs/path root path)]
    (and (fs/regular-file? file) (agents/stamped? (slurp (str file))))))

(defn- refusal [root path]
  (cond
    (linked? root path) (str path " is under a symbolic link; kevin does not write through it")
    (and (fs/exists? (fs/path root path)) (not (kevins? root path))) (str path " exists and kevin did not write it")))

(defn sync!
  "Writes the project agents that the main checkout needs, removes those
  that kevin wrote and it no longer needs, and keeps one line in the shared
  exclude file per project agent. Writes nothing when a needed path holds a
  file that kevin did not write."
  []
  (binding [config/*root* nil]
    (shell/quietly config/check)
    (let [root (shell/repo-root)
          wanted (desired)]
      (when-let [problem (some #(refusal root %) (sort (keys wanted)))]
        (shell/die problem))
      (doseq [[path text] wanted
              :let [file (fs/path root path)]
              :when (not (and (fs/regular-file? file) (= text (slurp (str file)))))]
        (fs/create-dirs (fs/parent file))
        (spit (str file) text))
      (doseq [path (paths root)
              :when (and (not (wanted path)) (not (linked? root path)) (kevins? root path))]
        (fs/delete (fs/path root path)))
      (exclude/add! root (map #(str "/" %) (sort (keys wanted))))
      (exclude/remove! root (for [line (exclude/lines root)
                                  :let [[_ path] (re-matches exclude-line line)]
                                  :when (and path (not (wanted path)) (not (fs/exists? (fs/path root path))))]
                              line)))))

(def ^:private agent-harnesses
  "The harnesses that kevin agent takes: those with project agents, and vibe,
  whose plugin agents exist only for runtime roles."
  (conj agents/harnesses "vibe"))

(defn- print-subagent
  "Prints AGENT, then MODEL when the delegation call takes the model, then a
  WARNING when the project agent is missing or stale."
  [role harness]
  (let [{:keys [agent model path text]} (resolve-agent harness role (context))
        file (some->> path (fs/path (shell/repo-root)))]
    (println (str "AGENT: " agent))
    (when model (println (str "MODEL: " model)))
    (cond
      (nil? file) nil
      (not (fs/regular-file? file)) (println (str "WARNING: " path " is missing; run kevin adapters"))
      (not= text (slurp (str file))) (println (str "WARNING: " path " is stale; run kevin adapters")))))

(defn- vibe-agent
  "The Vibe plugin agent of a runtime subagent role, else none."
  [role]
  (if (some #{role} (agents/subagents (config/runtime-rows "roles.conf")))
    (str "kevin:" role)
    "none"))

(defn agent-command
  "kevin agent <role> <harness>: how the orchestrator runs the role in the
  harness. A session role prints AGENT: session and the PROMPT path to read.
  In vibe a subagent role prints its plugin agent or none. Otherwise see
  print-subagent. Writes nothing."
  [[role harness]]
  (binding [config/*root* nil]
    (when-not (some #{harness} agent-harnesses)
      (shell/die "unknown harness '" harness "' (" (str/join ", " agent-harnesses) ")"))
    (shell/quietly config/check)
    (config/require-agent role)
    (cond
      (not= "subagent" (config/role-field role 3))
      (do (println "AGENT: session")
          (println (str "PROMPT: " (fs/absolutize (config/prompt-file role)))))

      (= "vibe" harness) (println (str "AGENT: " (vibe-agent role)))

      :else (print-subagent role harness))))
