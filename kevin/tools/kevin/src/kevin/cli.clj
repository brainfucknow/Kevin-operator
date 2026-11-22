(ns kevin.cli
  "kevin: Kevin task helper. Each task is a git branch, swarm/<id>. A role
  hands work to the next role: it commits on the task branch and kevin records a
  handoff commit."
  (:require [kevin.adapters :as adapters]
            [kevin.config :as config]
            [kevin.dispatch :as dispatch]
            [kevin.hook :as hook]
            [kevin.options :as options]
            [kevin.shell :as shell]
            [kevin.task :as task]
            [kevin.tool :as tool]))

(defn- print-roles [_]
  (run! println (config/pipeline)))

(def ^:private commands
  {"init" task/init
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

(defn main [[command & args]]
  (cond
    (contains? #{nil "" "-h" "--help" "help"} command) (println options/usage)
    (= "tool" command) (let [status (tool/main args)]
                         (when-not (zero? status) (shell/fail status nil)))
    (commands command) ((commands command) (vec args))
    :else (shell/die "unknown command '" command "' (try kevin --help)")))

(defn -main [args]
  (System/exit (shell/main-with "kevin: " #(main args))))
