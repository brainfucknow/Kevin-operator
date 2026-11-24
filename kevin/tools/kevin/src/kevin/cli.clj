(ns kevin.cli
  "kevin: Kevin task helper. Each task is a git branch, swarm/<id>. A role
  hands work to the next role: it commits on the task branch and kevin records a
  handoff commit."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [kevin.adapters :as adapters]
            [kevin.config :as config]
            [kevin.convert :as convert]
            [kevin.dispatch :as dispatch]
            [kevin.hook :as hook]
            [kevin.options :as options]
            [kevin.runtime :as runtime]
            [kevin.installed :as installed]
            [kevin.shell :as shell]
            [kevin.task :as task]
            [kevin.tool :as tool]))

(defn- print-roles [_]
  (run! println (config/pipeline)))

(def ^:private subcommands
  "The runtime's other commands, run as kevin <name>: kevin/bin/kevin-<name>."
  #{"cs" "fs" "vb" "js" "rb" "java" "mojo" "mistral"})

(defn- run-subcommand
  "Runs the runtime's kevin-<name> from the current directory with the
  terminal's input and output, and stops with its exit status."
  [name args]
  (let [program (str (fs/path (installed/home) "bin" (str "kevin-" name)))
        {:keys [exit]} (try (p/shell {:continue true :cmd (into [program] args)})
                            (catch java.io.IOException e (shell/die name ": " (ex-message e))))]
    (when-not (zero? exit) (shell/fail exit nil))))

(defn- init [args]
  (case (vec args)
    [] (task/init args)
    ["--convert"] (convert/convert)
    (shell/die "init takes [--convert]")))

(def ^:private commands
  {"init" init
   "version" (fn [_] (println (installed/version)))
   "home" (fn [_] (println (installed/home)))
   "new" task/new-task
   "route" task/route
   "ls" task/ls
   "show" task/show
   "next" task/next-task
   "stage" task/stage
   "harvest" task/harvest
   "start" task/start
   "handoff" #(task/handoff % {})
   "ask" task/ask
   "answer" task/answer
   "review" task/review
   "approve" task/approve
   "reject" task/reject
   "spec-verdict" task/spec-verdict
   "spec-revisions" task/spec-revisions
   "finish" task/finish
   "roles" (config/in-checkout print-roles)
   "pipeline" (config/in-checkout print-roles)
   "gates" (fn [args] (if (empty? args)
                        ((config/in-checkout (fn [_] (run! println (config/gates)))) args)
                        (task/gates args)))
   "routes" (config/in-checkout (fn [_] (doseq [[name route] (config/named-routes)]
                                          (println (format "%-10s %s" name (str route))))))
   "dispatch" dispatch/dispatch
   "run" dispatch/run
   "adapters" (config/in-checkout adapters/adapters)
   "check" (config/in-checkout (fn [_] (config/check)))
   "commit-msg" hook/commit-msg})

(defn- help? [command] (contains? #{nil "" "-h" "--help" "help"} command))

(defn- check-runtime!
  "Stops when the main checkout's kevin/project.md needs a newer runtime.
  Outside a git repository there is no project to check."
  []
  (when-let [root (shell/main-root)]
    (let [file (fs/path root "kevin" "project.md")]
      (when (fs/exists? file)
        (when-let [refusal (runtime/project-refusal (slurp (str file)) (installed/version))]
          (shell/die refusal))))))

(defn main [[command & args]]
  (when-not (or (help? command) (#{"version" "home"} command))
    (check-runtime!))
  (cond
    (help? command) (println options/usage)
    (subcommands command) (run-subcommand command args)
    (= "tool" command) (let [status (tool/main args)]
                         (when-not (zero? status) (shell/fail status nil)))
    (commands command) ((commands command) (vec args))
    :else (shell/die "unknown command '" command "' (try kevin --help)")))

(defn -main [args]
  (System/exit (shell/main-with "kevin: " #(main args))))
