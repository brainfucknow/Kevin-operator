(ns kevin.tool
  "kevin tool: installs the constitution tools in the project and wraps them with
  the argument guardrails of the constitution. Catalogue: kevin/tools.conf
  of the checkout kevin tool runs in."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kevin.catalogue :as catalogue]
            [kevin.clone :as clone]
            [kevin.config :as config]
            [kevin.entry-point :as entry-point]
            [kevin.maven-tool :as maven-tool]
            [kevin.runtime :as runtime]
            [kevin.shell :as shell]
            [kevin.wrapper :as wrapper]))

(def usage "usage: kevin tool <command> [tool]

  require <tool>   print OK and the wrapper path, or MISSING (exit 1)
  ensure <tool>    install the tool and what it needs; write its wrapper
  list             print the catalogue
  bin              print the wrapper directory; put it on PATH

The catalogue is kevin/tools.conf of the checkout you run in, else the
runtime's tools.conf. ensure puts
a GitHub source's clone at its pinned commit, discarding local changes, and
prints @<ref> after the wrapper path (a clj tool's Maven version too).
Wrappers live in .kevin/bin/ and clones in .kevin/tools/, both under
the main checkout and ignored by git. KEVIN_TOOL_SRC points every GitHub
source at one local checkout, used as it is: unpinned, never fetched or checked
out, and printed without @.")

(defn- tools
  "The tools of kevin/tools.conf of the checkout kevin tool runs in, else of
  the runtime's tools.conf."
  []
  (map catalogue/tool (binding [config/*root* (shell/checkout-root)] (config/rows "tools.conf"))))

(defn- spec-of [tool]
  (or (some #(when (= tool (:name %)) %) (tools))
      (shell/die "unknown tool '" tool "' (see kevin/tools.conf)")))

(defn- bin-dir []
  (str (fs/path (shell/repo-root) ".kevin" "bin")))

(defn- wrapper-path [tool]
  (str (fs/path (bin-dir) tool)))

(defn- missing-wrapper
  "The first of the tools the tool needs, then the tool, whose wrapper is not
  executable; nil when none."
  [tool]
  (first (remove #(fs/executable? (wrapper-path %)) (conj (:needs (spec-of tool)) tool))))

(defn missing?
  "True when kevin tool require would exit 1 for the tool."
  [tool]
  (some? (missing-wrapper tool)))

(defn wrapped-names
  "The names of the catalogue tools that kevin tool ensure wraps: all but the
  local rows, which are tracked scripts."
  []
  (map :name (remove #(= "local" (:kind %)) (tools))))

(defn- require-tool [tool]
  (when-let [dep (missing-wrapper tool)]
    (println (str "MISSING: " dep))
    (println (str "Run: kevin tool ensure " dep))
    (shell/fail 1 nil))
  (println (str "OK: " tool " " (wrapper-path tool))))

(defn- pinned-source
  "The tool's source directory, its commit, and a function that puts the clone
  back where it was. KEVIN_TOOL_SRC is used as it is, with no commit."
  [tool {:keys [source ref]}]
  (if-let [override (shell/kevin-env "TOOL_SRC" nil)]
    [override nil (constantly nil)]
    (let [dir (str (fs/path (shell/repo-root) ".kevin" "tools" (catalogue/github-repo source)))
          url (str "https://" source ".git")
          earlier (clone/head dir)]
      (clone/ensure-at tool dir url ref)
      [dir ref #(when (and earlier (not= earlier ref)) (clone/ensure-at tool dir url earlier))])))

(defn- checked-source
  "The tool's source directory and its commit. A source without the entry
  point stops the install, puts the clone back at its earlier commit, and
  leaves any earlier wrapper as it is."
  [tool {:keys [kind task] :as spec}]
  (let [[dir pin restore] (pinned-source tool spec)]
    (when-let [problem (entry-point/problem kind dir task)]
      (restore)
      (shell/die tool ": " problem))
    [dir pin]))

(defn- coverage-config-path [tool]
  (str (fs/path (shell/repo-root) ".kevin" "tools" (str tool "-coverage") "bb.edn")))

(defn- kevin-src []
  (str (fs/path (runtime/home) "tools" "kevin" "src")))

(defn- rewrite-for [rewrite tool]
  (case rewrite
    "mutate" (shell/template "rewrite-mutate.sh")
    "gherkin" (shell/template "rewrite-gherkin.sh")
    "coverage" (wrapper/coverage-rewrite (str (fs/which "bb")) (kevin-src) (coverage-config-path tool))
    "-" ""))

(defn- write-coverage-config [tool dir]
  (let [path (coverage-config-path tool)]
    (fs/create-dirs (fs/parent path))
    (spit path (wrapper/coverage-config dir))))

(defn- clj-deps
  "The Maven dependencies of a clj tool's wrapper as [coordinate version]
  pairs: its own, then those of the rows it takes a pin from."
  [tool {:keys [source ref]}]
  (into [[source ref]] (map (comp (juxt :source :ref) spec-of)) (catalogue/pin-sources tool)))

(defn- command-for
  "The wrapper command for the tool and the ref it is pinned at, if any."
  [tool {:keys [kind source ref task rewrite] :as spec}]
  (case kind
    "bb" (let [[dir pin] (checked-source tool spec)]
           (when (= "coverage" rewrite) (write-coverage-config tool dir))
           [(wrapper/bb-command dir task) pin])
    "bbmain" (let [[dir pin] (checked-source tool spec)]
               [(wrapper/bbmain-command dir task) pin])
    "maven" (let [[dir pin] (checked-source tool spec)
                  patches (str (fs/path (runtime/home) "tools/kevin/resources/kevin/java-patches"))
                  cache (str (fs/path (shell/repo-root) ".kevin/m2"))
                  jar (maven-tool/build dir tool patches cache (some? pin))]
              [(str "exec java -jar " (wrapper/sq jar) " \"$@\"") pin])
    "clj" [(wrapper/clj-command tool (clj-deps tool spec) task) ref]
    "local" [(if (fs/exists? (fs/path (shell/repo-root) source))
               (wrapper/local-command (shell/repo-root) source)
               (wrapper/runtime-command (runtime/launcher) source))
             nil]))

(defn- install-one [tool]
  (let [spec (spec-of tool)
        [command pin] (command-for tool spec)
        command (cond->> command
                  (= "gherkin" (:rewrite spec))
                  (wrapper/keeping-feature-block (kevin-src)))
        path (wrapper-path tool)]
    (fs/create-dirs (bin-dir))
    (spit path (wrapper/script (rewrite-for (:rewrite spec) tool) command))
    (fs/set-posix-file-permissions path "rwxr-xr-x")
    (println (str "INSTALLED: " tool " " path (when pin (str " @" pin))))))

(defn- check-known
  "Stops when the tool, a tool it needs, or a row it takes a pin from has an
  unknown kind or rewrite, or breaks a ref or source rule."
  [tool]
  (let [{:keys [kind rewrite needs] :as spec} (spec-of tool)]
    (run! check-known needs)
    (when-let [problem (first (catalogue/ref-problems spec (tools)))]
      (shell/die "tools.conf: " problem))
    (when-not (catalogue/kinds kind) (shell/die "unknown kind '" kind "' for " tool))
    (when-not (catalogue/rewrites rewrite) (shell/die "unknown rewrite '" rewrite "' in kevin/tools.conf"))
    (run! check-known (catalogue/pin-sources tool))))

(defn- install-with-needs [tool]
  (run! install-with-needs (:needs (spec-of tool)))
  (install-one tool))

(defn- ensure-tool [tool]
  (check-known tool)
  (install-with-needs tool))

(defn- list-tools []
  (let [rows (tools)
        width (apply max 0 (map (comp count :source) rows))]
    (doseq [{:keys [name kind source ref]} rows]
      (println (format (str "%-22s %-6s %-" (max 1 width) "s %s") name kind source ref)))))

(defn- named
  "The tool the command was given; stops when there is none."
  [command tool]
  (when (str/blank? tool) (shell/die command " needs a tool"))
  tool)

(defn main
  "Runs one kevin tool command and returns its exit status."
  [[command tool]]
  (shell/main-with
   "kevin tool: "
   #(case command
      "require" (require-tool (named command tool))
      "ensure" (ensure-tool (named command tool))
      "list" (list-tools)
      "bin" (println (bin-dir))
      (nil "" "-h" "--help" "help") (println usage)
      (shell/die "unknown command '" command "'"))))
