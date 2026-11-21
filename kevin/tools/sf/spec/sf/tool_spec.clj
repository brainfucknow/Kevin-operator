(ns sf.tool-spec
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [speclj.core :refer [describe context it should= should should-not around]]
            [sf.clone :as clone]
            [sf.shell :as shell]
            [sf.tool :as tool]))

(def ^:dynamic *dir* nil)
(def ^:dynamic *env* {})
(def ^:dynamic *clone* nil)

(def ^:private sha "0123456789abcdef0123456789abcdef01234567")

(def ^:private catalogue
  (str/replace "gherkin-parser bb     github.com/x/aps     SHA gherkin-parser -        -
gherkin-mutator bb    github.com/x/aps     SHA gherkin-mutator gherkin -
crap4clj       bb     github.com/x/crap4clj SHA crap           coverage cloverage
dry4clj        bbmain github.com/x/dry4clj  SHA dry4clj.core   -        -
cloverage      clj    cloverage/cloverage   9.8.7 cloverage.coverage -    -
speclj         clj    speclj/speclj         6.5.4 speclj.main    -        -
clj-mutate     clj    clj-mutate/clj-mutate 1.0 clj-mutate.core mutate  -
probe          local  bin/probe             - -              -        -
weird          java   x                     - -              -        -
odd            local  bin/odd               - -              rot13    probe
outer          bb     github.com/x/outer    SHA crap           coverage weird
short          bb     github.com/x/short    SHA crap
moved          bb     github.com/x/aps      aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa gherkin-parser - -
" "SHA" sha))

(defn- with-checkout [run-spec]
  (let [dir (str (fs/create-temp-dir {:prefix "tool-spec-"}))]
    (try
      (fs/create-dirs (fs/path dir "kevin"))
      (spit (str (fs/path dir "kevin" "tools.conf")) catalogue)
      (with-redefs [shell/repo-root (constantly dir)
                    shell/checkout-root (constantly dir)
                    shell/env (fn [name default] (get *env* name default))
                    clone/ensure-at (fn [& args] (*clone* args))]
        (binding [*dir* dir
                  *clone* (fn [_] (shell/die "no clone expected"))]
          (run-spec)))
      (finally (fs/delete-tree dir)))))

(defn- kevin-tool
  "Runs kevin tool with args; returns [status stdout stderr]."
  [& args]
  (let [err (java.io.StringWriter.)
        status (atom nil)
        out (with-out-str (binding [*err* err] (reset! status (tool/main (vec args)))))]
    [@status out (str err)]))

(defn- source [name & {:keys [tasks]}]
  (let [dir (fs/create-dirs (fs/path *dir* ".kevin" "tools" name))]
    (when tasks (spit (str (fs/path dir "bb.edn")) (str "{:tasks {" (str/join " " (map #(str % " {}") tasks)) "}}")))
    (str dir)))

(defn- wrapper [name]
  (str (fs/path *dir* ".kevin" "bin" name)))

(describe "kevin tool"
  (around [it] (with-checkout it))

  (it "prints usage, the bin directory and the catalogue with its refs"
    (should (str/includes? (second (kevin-tool "help")) "usage: kevin tool"))
    (should= [0 (str (fs/path *dir* ".kevin" "bin") "\n") ""] (kevin-tool "bin"))
    (let [[status out] (kevin-tool "list")]
      (should= 0 status)
      (should (some #{(str "dry4clj                bbmain github.com/x/dry4clj  " sha)} (str/split-lines out)))
      (should (some #{"cloverage              clj    cloverage/cloverage   9.8.7"} (str/split-lines out)))))

  (it "lists rows that have no source without failing on the column width"
    (spit (str (fs/path *dir* "kevin" "tools.conf")) "lonely\n")
    (let [[status out err] (kevin-tool "list")]
      (should= [0 ""] [status err])
      (should (str/starts-with? out "lonely "))))

  (it "rejects an unknown command, a missing tool name and an unknown tool"
    (should= [1 "" "kevin tool: unknown command 'frob'\n"] (kevin-tool "frob"))
    (should= [1 "" "kevin tool: ensure needs a tool\n"] (kevin-tool "ensure"))
    (should= [1 "" "kevin tool: require needs a tool\n"] (kevin-tool "require"))
    (should= [1 "" "kevin tool: unknown tool 'nope' (see kevin/tools.conf)\n"] (kevin-tool "ensure" "nope")))

  (context "ensure"
    (it "puts the clone at the pinned commit, wraps its bb task and prints the commit"
      (let [dir (source "aps" :tasks ["gherkin-parser"])
            calls (atom [])]
        (binding [*clone* (fn [args] (swap! calls conj args))]
          (should= [0 (str "INSTALLED: gherkin-parser " (wrapper "gherkin-parser") " @" sha "\n") ""] (kevin-tool "ensure" "gherkin-parser")))
        (should= [["gherkin-parser" dir "https://github.com/x/aps.git" sha]] @calls)
        (should (str/includes? (slurp (wrapper "gherkin-parser")) (str "exec bb --config '" dir "/bb.edn' 'gherkin-parser'")))))

    (it "keeps the gherkin-mutator block in .metrics/gherkin/ with the main checkout's kevin"
      (let [dir (source "aps" :tasks ["gherkin-mutator"])
            text (binding [*clone* (constantly nil)] (kevin-tool "ensure" "gherkin-mutator") (slurp (wrapper "gherkin-mutator")))]
        (should (str/includes? text "--level hard"))
        (should (str/includes? text (str "bb -cp '" *dir* "/kevin/tools/sf/src' -m sf.gherkin-cache")))
        (should (str/includes? text (str "\nbb --config '" dir "/bb.edn' 'gherkin-mutator' \"$@\"\n")))))

    (it "uses KEVIN_TOOL_SRC for every GitHub source as it is, and prints no commit"
      (let [dir (str (fs/create-dirs (fs/path *dir* "override")))]
        (spit (str (fs/path dir "bb.edn")) "{:tasks {gherkin-parser {}}}")
        (binding [*env* {"KEVIN_TOOL_SRC" dir}]
          (should= [0 (str "INSTALLED: gherkin-parser " (wrapper "gherkin-parser") "\n") ""] (kevin-tool "ensure" "gherkin-parser")))
        (should (str/includes? (slurp (wrapper "gherkin-parser")) (str "'" dir "/bb.edn'")))))

    (it "stops and keeps the earlier wrapper when the clone cannot be put at its commit"
      (fs/create-dirs (fs/path *dir* ".kevin" "bin"))
      (spit (wrapper "gherkin-parser") "stale")
      (binding [*clone* (fn [_] (shell/die "gherkin-parser: cannot fetch"))]
        (should= [1 "" "kevin tool: gherkin-parser: cannot fetch\n"] (kevin-tool "ensure" "gherkin-parser")))
      (should= "stale" (slurp (wrapper "gherkin-parser"))))

    (it "installs a tool's needs first and writes the crap4clj coverage config"
      (source "crap4clj" :tasks ["crap"])
      (let [[status out] (binding [*clone* (constantly nil)] (kevin-tool "ensure" "crap4clj"))]
        (should= 0 status)
        (should= [(str "INSTALLED: cloverage " (wrapper "cloverage") " @9.8.7") (str "INSTALLED: crap4clj " (wrapper "crap4clj") " @" sha)]
                 (str/split-lines out)))
      (should (fs/exists? (fs/path *dir* ".kevin" "tools" "crap4clj-coverage" "bb.edn")))
      (should (str/includes? (slurp (wrapper "crap4clj"))
                             (str "-cp '" *dir* "/kevin/tools/sf/src' -m sf.coverage-rewrite '"
                                  *dir* "/.kevin/tools/crap4clj-coverage/bb.edn' \"$@\"")))
      (should (str/includes? (slurp (wrapper "cloverage")) "exec clojure -Sdeps")))

    (it "pins a clj wrapper at its row's version, and cloverage's speclj at the speclj row's"
      (should= [0 (str "INSTALLED: cloverage " (wrapper "cloverage") " @9.8.7\n") ""] (kevin-tool "ensure" "cloverage"))
      (should= (str "#!/usr/bin/env bash\nexec clojure -Sdeps '{:paths [\"src\" \"spec\" \"test\"] :deps {cloverage/cloverage {:mvn/version \"9.8.7\"} "
                    "speclj/speclj {:mvn/version \"6.5.4\"}}}' -M -m 'cloverage.coverage' -p src -s spec -s test -r speclj \"$@\"\n")
               (slurp (wrapper "cloverage")))
      (should= [0 (str "INSTALLED: clj-mutate " (wrapper "clj-mutate") " @1.0\n") ""] (kevin-tool "ensure" "clj-mutate"))
      (should (str/includes? (slurp (wrapper "clj-mutate")) "-Sdeps '{:deps {clj-mutate/clj-mutate {:mvn/version \"1.0\"}}}' -M -m 'clj-mutate.core'")))

    (it "wraps a bbmain namespace, a clj main and a local script"
      (let [dir (source "dry4clj")]
        (fs/create-dirs (fs/path dir "src" "dry4clj"))
        (spit (str (fs/path dir "src" "dry4clj" "core.clj")) "(ns dry4clj.core)")
        (should= 0 (first (binding [*clone* (constantly nil)] (kevin-tool "ensure" "dry4clj"))))
        (should (str/includes? (slurp (wrapper "dry4clj")) "-m 'dry4clj.core'")))
      (should= 0 (first (kevin-tool "ensure" "speclj")))
      (should= 0 (first (kevin-tool "ensure" "clj-mutate")))
      (should (str/includes? (slurp (wrapper "clj-mutate")) "--mutate-all"))
      (should= [0 (str "INSTALLED: probe " (wrapper "probe") "\n") ""] (kevin-tool "ensure" "probe"))
      (should (str/includes? (slurp (wrapper "probe")) (str "'" *dir* "/bin/probe'"))))

    (it "keeps an earlier wrapper and stops when the entry point is missing"
      (source "aps" :tasks ["other"])
      (fs/create-dirs (fs/path *dir* ".kevin" "bin"))
      (spit (wrapper "gherkin-parser") "stale")
      (let [[status _ err] (binding [*clone* (constantly nil)] (kevin-tool "ensure" "gherkin-parser"))]
        (should= 1 status)
        (should (str/includes? err "gherkin-parser: ")))
      (should= "stale" (slurp (wrapper "gherkin-parser"))))

    (it "puts the clone back at its earlier commit when the pinned commit lacks the entry point"
      (let [dir (source "aps" :tasks ["other"])
            earlier "fedcba9876543210fedcba9876543210fedcba98"
            calls (atom [])]
        (fs/create-dirs (fs/path *dir* ".kevin" "bin"))
        (spit (wrapper "gherkin-parser") "stale")
        (with-redefs [clone/head (constantly earlier)]
          (binding [*clone* (fn [args] (swap! calls conj args))]
            (should= 1 (first (kevin-tool "ensure" "gherkin-parser")))))
        (should= [["gherkin-parser" dir "https://github.com/x/aps.git" sha]
                  ["gherkin-parser" dir "https://github.com/x/aps.git" earlier]]
                 @calls)
        (should= "stale" (slurp (wrapper "gherkin-parser")))))

    (it "stops on an unknown kind or rewrite in the needs chain before it writes or clones anything"
      (let [cloned (atom [])]
        (binding [*clone* (fn [cmd] (swap! cloned conj cmd) {:exit 1})]
          (should= [1 "" "kevin tool: unknown kind 'java' for weird\n"] (kevin-tool "ensure" "weird"))
          (should= [1 "" "kevin tool: unknown rewrite 'rot13' in kevin/tools.conf\n"] (kevin-tool "ensure" "odd"))
          (should= [1 "" "kevin tool: unknown kind 'java' for weird\n"] (kevin-tool "ensure" "outer"))
          (should= [1 "" "kevin tool: tools.conf: 'short' needs exactly <name> <kind> <source> <ref> <task> <rewrite> <needs>\n"]
                   (kevin-tool "ensure" "short"))
          (should= [1 "" "kevin tool: tools.conf: 'moved' pins github.com/x/aps at a different ref than 'gherkin-parser'\n"]
                   (kevin-tool "ensure" "moved"))
          (binding [*env* {"KEVIN_TOOL_SRC" *dir*}]
            (should= [1 "" "kevin tool: tools.conf: 'moved' pins github.com/x/aps at a different ref than 'gherkin-parser'\n"]
                     (kevin-tool "ensure" "moved"))))
        (should= [] @cloned))
      (should-not (fs/exists? (fs/path *dir* ".kevin")))))

  (context "require"
    (it "names a missing need, then reports a ready tool"
      (should= [1 (str "MISSING: cloverage\nRun: kevin tool ensure cloverage\n") ""] (kevin-tool "require" "crap4clj"))
      (kevin-tool "ensure" "probe")
      (should= [0 (str "OK: probe " (wrapper "probe") "\n") ""] (kevin-tool "require" "probe")))))
