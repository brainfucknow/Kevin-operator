(ns kevin.config
  "The catalogue files under kevin/: roles, tiers, routes and tools."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kevin.agents :as agents]
            [kevin.catalogue :as catalogue]
            [kevin.flow :as flow]
            [kevin.installed :as installed]
            [kevin.shell :as shell]))

(def ^:dynamic *root*
  "The checkout whose kevin/ is read; nil reads the main checkout's."
  nil)

(def ^:private runtime-defaults
  "The files a project may leave out; kevin then reads the runtime's."
  #{"roles.conf" "routes.conf" "tiers.conf" "tools.conf"})

(defn project-dir
  "kevin/ of the checkout: the project's configuration."
  []
  (str (fs/path (or *root* (shell/repo-root)) "kevin")))

(defn in-checkout
  "The command f, reading the config of the checkout it runs in."
  [f]
  (fn [args] (binding [*root* (shell/checkout-root)] (f args))))

(defn conf-file
  "The project's kevin/<name>; for a catalogue file the project does not have,
  the runtime's."
  [name]
  (let [file (fs/path (project-dir) name)]
    (str (if (or (fs/exists? file) (not (runtime-defaults name)))
           file
           (fs/path (installed/home) name)))))

(defn rows [name]
  (shell/conf-rows (conf-file name)))

(defn runtime-rows
  "The rows of the runtime's kevin/<name>."
  [name]
  (shell/conf-rows (fs/path (installed/home) name)))

(defn- runtime-roles
  "The roles of the runtime's roles.conf."
  []
  (set (map first (runtime-rows "roles.conf"))))

(defn prompt-file
  "The prompt of the role: the runtime's roles/<role>.md when the runtime's
  roles.conf has the role, else the checkout's kevin/roles/<role>.md. A
  project file of a runtime role's name is never read."
  [role]
  (str (if ((runtime-roles) role)
         (fs/path (installed/home) "roles" (str role ".md"))
         (fs/path (project-dir) "roles" (str role ".md")))))

(defn- field
  "Column (1-based) of the first row whose first field is key."
  [rows key column]
  (some #(when (= key (first %)) (get % (dec column))) rows))

(defn- gate-row? [row]
  (boolean (some #{"gate"} (drop 3 row))))

(defn- spec-judge-row? [row]
  (boolean (some #{"spec-judge"} (drop 3 row))))

(defn pipeline
  "The roles a route can visit, in canonical order. Gate and spec-judge roles
  are not stages."
  []
  (map first (remove #(or (gate-row? %) (spec-judge-row? %)) (rows "roles.conf"))))

(defn gates
  "The gate roles in file order: subagents that audit a specification after
  the specifier's kevin review and before the operator approves it."
  []
  (map first (filter gate-row? (rows "roles.conf"))))

(defn spec-judge
  "The spec-judge role: the subagent that gives a spec verdict on each spec
  revision's gate findings. nil when roles.conf has none."
  []
  (first (map first (filter spec-judge-row? (rows "roles.conf")))))

(defn role-field [role column]
  (field (rows "roles.conf") role column))

(defn tier-field [tier column]
  (field (rows "tiers.conf") tier column))

(defn require-role [role]
  (when-not (some #{role} (pipeline))
    (shell/die "unknown role '" role "' (see kevin/roles.conf)")))

(defn require-agent
  "Refuses a role that is neither a stage nor a gate."
  [role]
  (when-not (some #{role} (map first (rows "roles.conf")))
    (shell/die "unknown role '" role "' (see kevin/roles.conf)")))

(defn- role-flags [role]
  (drop 3 (some #(when (= role (first %)) %) (rows "roles.conf"))))

(defn gate? [role]
  (boolean (some #{"gate"} (role-flags role))))

(defn spec-judge? [role]
  (boolean (some #{"spec-judge"} (role-flags role))))

(defn sends-back?
  "True when the role may hand a task back to an earlier role on its route."
  [role]
  (boolean (some #{"send-back"} (role-flags role))))

(defn named-routes []
  (rows "routes.conf"))

(defn catalogue-route []
  (str/join "," (pipeline)))

(defn resolve-route
  "The comma-separated role list of a route name or list. The roles must exist
  and be in catalogue order."
  [spec]
  (let [route (or (field (named-routes) spec 2) spec)
        roles (flow/route-list route)
        catalogue (vec (pipeline))]
    (when (empty? roles) (shell/die "empty route"))
    (reduce (fn [last role]
              (let [index (.indexOf catalogue role)]
                (when (and (neg? index) (some #{role} (gates)))
                  (shell/die "'" role "' in route '" spec "' is a gate, not a stage; it runs at the specification gate"))
                (when (and (neg? index) (= role (spec-judge)))
                  (shell/die "'" role "' in route '" spec "' is a spec-judge, not a stage; it runs at the specification gate"))
                (when (neg? index)
                  (shell/die "unknown role '" role "' in route '" spec "' (see kevin/roles.conf)"))
                (when (<= index last)
                  (shell/die "route '" spec "' is out of order; follow kevin/roles.conf: " (catalogue-route)))
                index))
            -1 roles)
    route))

(defn default-route []
  (let [route (shell/kevin-env "ROUTE" nil)]
    (cond route (resolve-route route)
          (field (named-routes) "feature" 2) (resolve-route "feature")
          :else (let [roles (remove #(some #{"optional"} (role-flags %)) (pipeline))]
                  (when (empty? roles) (shell/die "empty route"))
                  (str/join "," roles)))))

;; kevin check

(defn- scan
  "Problems from (check row seen) for each row; seen holds the earlier first fields."
  [rows check]
  (:problems (reduce (fn [{:keys [seen] :as acc} row]
                       (-> acc
                           (update :problems into (check row seen))
                           (update :seen conj (first row))))
                     {:seen #{} :problems []}
                     rows)))

(defn- safe-value?
  "True when a tiers.conf value cannot be read as an option or as shell text."
  [value]
  (boolean (re-matches #"[A-Za-z0-9][A-Za-z0-9._:/@\[\]-]*" value)))

(defn- check-tiers []
  (if-not (fs/exists? (conf-file "tiers.conf"))
    ["tiers.conf: missing"]
    (scan (rows "tiers.conf")
          (fn [[tier & values :as row] seen]
            (cond-> []
              (not (<= 4 (count row) 6)) (conj (str "tiers.conf: '" tier "' needs exactly <tier> <claude-model> <codex-effort> <copilot-model> [<kilo-model>] [<vibe-model>]"))
              (seen tier) (conj (str "tiers.conf: tier '" tier "' listed twice"))
              true (into (for [value values :when (not (safe-value? value))]
                           (str "tiers.conf: '" tier "' has the unsafe value '" value "'"))))))))

(defn- flag-problems [role runs flags]
  (concat
    (for [flag (distinct flags) :when (not (#{"optional" "gate" "spec-judge" "send-back"} flag))]
      (str "roles.conf: '" role "' has unknown flag '" flag "' (only optional, gate, spec-judge, send-back)"))
    (for [[flag n] (frequencies flags) :when (> n 1)]
      (str "roles.conf: '" role "' repeats flag '" flag "'"))
    (when (some #{"gate"} flags)
      (cond-> []
        (some #{"optional"} flags) (conj (str "roles.conf: '" role "' is a gate and cannot also be optional"))
        (not= "subagent" runs) (conj (str "roles.conf: '" role "' is a gate and must run as subagent"))
        (some #{"send-back"} flags) (conj (str "roles.conf: '" role "' is a gate and cannot also be send-back"))))
    (when (some #{"spec-judge"} flags)
      (cond-> []
        (some #{"optional" "gate"} flags) (conj (str "roles.conf: '" role "' is a spec-judge and cannot also be optional or gate"))
        (not= "subagent" runs) (conj (str "roles.conf: '" role "' is a spec-judge and must run as subagent"))
        (some #{"send-back"} flags) (conj (str "roles.conf: '" role "' is a spec-judge and cannot also be send-back"))))))

(defn- added-role-problem
  "Why the prompt of a role that the project adds is unusable, or nil."
  [role flags]
  (let [file (prompt-file role)
        label (str "kevin/roles/" role ".md")
        text (when (fs/exists? file) (slurp file))]
    (cond
      (some #{"spec-judge"} flags) (str "roles.conf: '" role "' is a spec-judge; only a runtime role can be")
      (nil? text) (str label ": missing role prompt for '" role "'")
      (not (and (str/starts-with? text "#") (agents/summary text))) (str label ": needs a title line and a summary line"))))

(defn- prompt-problem
  "Why the role's prompt is missing or unusable, or nil. known holds the
  runtime's roles, whose prompts come only from the runtime."
  [role flags known]
  (if (known role)
    (when-not (fs/exists? (prompt-file role)) (str "roles/" role ".md: missing role prompt for '" role "'"))
    (added-role-problem role flags)))

(defn- check-role [known [role tier runs & flags :as row] seen]
  (if-not (<= 3 (count row) 6)
    [(str "roles.conf: '" role "' needs <role> <tier> <runs-as> [optional] [send-back]")]
    (cond-> (vec (flag-problems role runs flags))
      (not (re-matches #"[a-z][a-z0-9-]*" role)) (conj (str "roles.conf: role name '" role "' must be lowercase letters, digits, and hyphens"))
      (#{"done" "operator" "none"} role) (conj (str "roles.conf: '" role "' is reserved"))
      (seen role) (conj (str "roles.conf: role '" role "' listed twice"))
      (not (tier-field tier 2)) (conj (str "roles.conf: '" role "' uses unknown tier '" tier "' (see tiers.conf)"))
      (not (#{"subagent" "session"} runs)) (conj (str "roles.conf: '" role "' runs-as must be subagent or session, not '" runs "'"))
      true (into (some-> (prompt-problem role flags known) vector)))))

(defn- second-spec-judges []
  (for [role (rest (map first (filter spec-judge-row? (rows "roles.conf"))))]
    (str "roles.conf: '" role "' is a second spec-judge; roles.conf has one")))

(defn- check-roles []
  (if-not (fs/exists? (conf-file "roles.conf"))
    ["roles.conf: missing"]
    (cond-> (into (scan (rows "roles.conf") (partial check-role (runtime-roles))) (second-spec-judges))
      (empty? (pipeline)) (conj "roles.conf: no roles"))))

(defn- route-problem [route]
  (try (resolve-route route) nil
       (catch clojure.lang.ExceptionInfo e (ex-message e))))

(defn- check-routes []
  (scan (named-routes)
        (fn [[name route :as row] seen]
          (if (not= 2 (count row))
            [(str "routes.conf: '" name "' needs exactly <name> <role,role,...>")]
            (cond-> []
              (seen name) (conj (str "routes.conf: route '" name "' listed twice"))
              (route-problem route) (conj (str "routes.conf: " name ": " (route-problem route))))))))

(defn- check-tools []
  (map #(str "tools.conf: " %) (catalogue/problems (map catalogue/tool (rows "tools.conf")))))

(defn check
  "Validates the catalogue files and prints a summary; stops on any problem."
  []
  (let [problems (concat (check-tiers) (check-roles) (check-routes) (check-tools))]
    (when (seq problems)
      (binding [*out* *err*]
        (doseq [problem problems] (println (str "CHECK: " problem))))
      (shell/die (count problems) " configuration problem(s) in " (project-dir)))
    (println (str "CHECK: ok (" (count (pipeline)) " roles, " (count (named-routes)) " routes)"))))
