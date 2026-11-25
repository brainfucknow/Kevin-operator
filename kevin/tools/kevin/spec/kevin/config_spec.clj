(ns kevin.config-spec
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [speclj.core :refer [describe context it should= should should-not should-throw around]]
            [kevin.config :as config]
            [kevin.installed :as installed]
            [kevin.shell :as shell]))

(def ^:dynamic *dir* nil)
(def ^:dynamic *env* {})

(defn- conf [name text]
  (spit (str (fs/path *dir* "kevin" name)) text))

(defn- with-catalogue [run-spec]
  (let [dir (str (fs/create-temp-dir {:prefix "config-spec-"}))]
    (try
      (fs/create-dirs (fs/path dir "kevin" "roles"))
      (doseq [role ["specifier" "coder" "qa"]] (spit (str (fs/path dir "kevin" "roles" (str role ".md"))) role))
      (with-redefs [shell/repo-root (constantly dir)
                    installed/home (constantly (str (fs/path dir "kevin")))
                    shell/env (fn [name default] (get *env* name default))]
        (binding [*dir* dir]
          (conf "tiers.conf" "strong opus high gpt\nfast sonnet low mini\n")
          (conf "roles.conf" "specifier strong session\ncoder strong subagent audit\nqa fast subagent\n")
          (conf "routes.conf" "feature specifier,coder,qa\nquick coder,qa\n")
          (conf "tools.conf" (str "dry4clj bbmain github.com/x/dry4clj 0123456789abcdef0123456789abcdef01234567 dry4clj.core - -\n"
                                  "crap4clj bb github.com/x/crap4clj 0123456789abcdef0123456789abcdef01234567 crap coverage -\n"
                                  "speclj clj speclj/speclj 3.13.0 speclj.main - -\n"
                                  "kevin-cs local kevin/bin/kevin-cs - - - -\n"))
          (run-spec)))
      (finally (fs/delete-tree dir)))))

(defn- check
  "Runs kevin check; returns [status stdout stderr lines]."
  []
  (let [err (java.io.StringWriter.)
        status (atom nil)
        out (with-out-str (binding [*err* err] (reset! status (shell/main-with "kevin: " config/check))))]
    [@status out (str/split-lines (str err))]))

(defn- in-dirs
  "Calls (f project runtime) with a scratch main checkout and a scratch runtime
  kevin/ directory, with kevin's repo-root and home pointing at them."
  [f]
  (let [project (str (fs/create-temp-dir {:prefix "conf-project-"}))
        runtime (str (fs/create-temp-dir {:prefix "conf-runtime-"}))]
    (try (fs/create-dirs (fs/path project "kevin"))
         (with-redefs [shell/repo-root (constantly project)
                       installed/home (constantly runtime)]
           (f project runtime))
         (finally (fs/delete-tree project) (fs/delete-tree runtime)))))

(describe "conf-file"
  (it "is the project's kevin/<name> when the project has it"
    (in-dirs (fn [project runtime]
               (doseq [name ["roles.conf" "routes.conf" "tiers.conf" "tools.conf"]]
                 (spit (str (fs/path project "kevin" name)) "x")
                 (spit (str (fs/path runtime name)) "y")
                 (should= (str (fs/path project "kevin" name)) (config/conf-file name))))))

  (it "is the runtime's file when the project has none"
    (in-dirs (fn [_ runtime]
               (doseq [name ["roles.conf" "routes.conf" "tiers.conf" "tools.conf"]]
                 (should= (str (fs/path runtime name)) (config/conf-file name))))))

  (it "never falls back to the runtime for verify.conf"
    (in-dirs (fn [project runtime]
               (spit (str (fs/path runtime "verify.conf")) "false")
               (should= (str (fs/path project "kevin" "verify.conf")) (config/conf-file "verify.conf")))))

  (it "reads the checkout that *root* names"
    (in-dirs (fn [_ _]
               (let [other (str (fs/create-temp-dir {:prefix "conf-other-"}))]
                 (try (fs/create-dirs (fs/path other "kevin"))
                      (spit (str (fs/path other "kevin" "routes.conf")) "x")
                      (binding [config/*root* other]
                        (should= (str (fs/path other "kevin" "routes.conf")) (config/conf-file "routes.conf")))
                      (finally (fs/delete-tree other)))))))

  (it "does not read a legacy swarmforge/ directory"
    (in-dirs (fn [project runtime]
               (fs/create-dirs (fs/path project "swarmforge"))
               (spit (str (fs/path project "swarmforge" "routes.conf")) "x")
               (should= (str (fs/path runtime "routes.conf")) (config/conf-file "routes.conf"))))))

(defn- added-role!
  "A runtime with coder, and a project roles.conf that adds row with the
  project prompt text for designer (none when text is nil)."
  [project runtime row text]
  (spit (str (fs/path runtime "tiers.conf")) "strong opus high gpt\n")
  (spit (str (fs/path runtime "roles.conf")) "coder strong subagent\n")
  (spit (str (fs/path runtime "routes.conf")) "feature coder\n")
  (fs/create-dirs (fs/path runtime "roles"))
  (spit (str (fs/path runtime "roles" "coder.md")) "# coder\n\nCodes.\n")
  (spit (str (fs/path project "kevin" "roles.conf")) (str "coder strong subagent\n" row "\n"))
  (fs/create-dirs (fs/path project "kevin" "roles"))
  (when text (spit (str (fs/path project "kevin" "roles" "designer.md")) text)))

(defn- check-lines []
  (let [[status _ errors] (check)]
    [status (filter #(str/starts-with? % "CHECK:") errors)]))

(describe "check against the runtime"
  (it "accepts a role that the project adds with its own prompt"
    (in-dirs (fn [project runtime]
               (added-role! project runtime "designer strong subagent" "# designer\n\nDraws.\n")
               (should= [0 []] (check-lines)))))

  (it "refuses an added role without a prompt, without a summary line, or flagged spec-judge"
    (in-dirs (fn [project runtime]
               (added-role! project runtime "designer strong subagent" nil)
               (should= [1 ["CHECK: kevin/roles/designer.md: missing role prompt for 'designer'"]] (check-lines))
               (added-role! project runtime "designer strong subagent" "# designer\n")
               (should= [1 ["CHECK: kevin/roles/designer.md: needs a title line and a summary line"]] (check-lines))
               (added-role! project runtime "designer strong subagent" "Draws.\n\nMore.\n")
               (should= [1 ["CHECK: kevin/roles/designer.md: needs a title line and a summary line"]] (check-lines))
               (added-role! project runtime "designer strong subagent spec-judge" "# designer\n\nJudges.\n")
               (should= [1 ["CHECK: roles.conf: 'designer' is a spec-judge; only a runtime role can be"]] (check-lines)))))

  (it "names the prompt of a runtime role in the runtime and of an added role in the checkout"
    (in-dirs (fn [project runtime]
               (added-role! project runtime "designer strong subagent" "# designer\n\nDraws.\n")
               (spit (str (fs/path project "kevin" "roles" "coder.md")) "# coder\n\nMine.\n")
               (should= (str (fs/path runtime "roles" "coder.md")) (config/prompt-file "coder"))
               (should= (str (fs/path project "kevin" "roles" "designer.md")) (config/prompt-file "designer"))
               (binding [config/*root* "/elsewhere"]
                 (should= "/elsewhere/kevin/roles/designer.md" (config/prompt-file "designer"))))))

  (it "refuses a tiers.conf value that could be read as an option or shell text"
    (in-dirs (fn [project runtime]
               (added-role! project runtime "designer strong subagent" "# designer\n\nDraws.\n")
               (doseq [[value ok?] [["--x" false] ["gpt;b" false] ["$(id)" false] ["a b" true]
                                    ["opus[1m]" true] ["provider/gpt-b@1:0" true] ["claude-haiku-5.5_x" true]]]
                 (spit (str (fs/path project "kevin" "tiers.conf")) (str "strong opus high " value "\n"))
                 (should= [value (if ok? [0 []] [1 [(str "CHECK: tiers.conf: 'strong' has the unsafe value '" value "'")]])]
                          [value (check-lines)])))))

  (it "reads role prompts from the runtime, not the project"
    (in-dirs (fn [project runtime]
               (spit (str (fs/path runtime "tiers.conf")) "strong opus high gpt\n")
               (spit (str (fs/path runtime "roles.conf")) "coder strong subagent\n")
               (spit (str (fs/path runtime "routes.conf")) "feature coder\n")
               (fs/create-dirs (fs/path project "kevin" "roles"))
               (spit (str (fs/path project "kevin" "roles" "coder.md")) "# coder")
               (let [[status _ errors] (check)]
                 (should= 1 status)
                 (should= ["CHECK: roles/coder.md: missing role prompt for 'coder'"]
                          (filter #(str/starts-with? % "CHECK:") errors)))))))

(describe "catalogue"
  (around [it] (with-catalogue it))

  (it "reads roles, tiers and audit flags"
    (should= ["specifier" "coder" "qa"] (config/pipeline))
    (should= "sonnet" (config/tier-field "fast" 2))
    (should (config/role-audits? "coder"))
    (should= nil (config/require-role "qa"))
    (should-throw clojure.lang.ExceptionInfo "unknown role 'nope' (see kevin/roles.conf)" (config/require-role "nope")))

  (context "routes"
    (it "resolves a route name or a role list"
      (should= "coder,qa" (config/resolve-route "quick"))
      (should= "specifier,qa" (config/resolve-route "specifier,qa")))

    (it "refuses an empty, unknown or out-of-order route"
      (should-throw clojure.lang.ExceptionInfo "empty route" (config/resolve-route ""))
      (should-throw clojure.lang.ExceptionInfo "unknown role 'x' in route 'coder,x' (see kevin/roles.conf)" (config/resolve-route "coder,x"))
      (should-throw clojure.lang.ExceptionInfo "route 'qa,coder' is out of order; follow kevin/roles.conf: specifier,coder,qa"
                    (config/resolve-route "qa,coder"))
      (should-throw clojure.lang.ExceptionInfo "route 'coder,coder' is out of order; follow kevin/roles.conf: specifier,coder,qa"
                    (config/resolve-route "coder,coder")))

    (it "defaults to KEVIN_ROUTE, then the feature route, then the catalogue"
      (binding [*env* {"KEVIN_ROUTE" "quick"}] (should= "coder,qa" (config/default-route)))
      (should= "specifier,coder,qa" (config/default-route))
      (conf "routes.conf" "")
      (should= "specifier,coder,qa" (config/default-route))))

  (context "optional roles"
    (it "requires explicit selection while preserving named and fallback routes"
      (conf "roles.conf" "specifier strong session\ncoder strong subagent audit\narchitecture-design-reviewer strong subagent optional\nhardener fast subagent\nsecurity-reviewer strong subagent optional audit\nqa fast subagent\n")
      (should= ["specifier" "coder" "architecture-design-reviewer" "hardener" "security-reviewer" "qa"] (config/pipeline))
      (should= "specifier,coder,qa" (config/default-route))
      (conf "routes.conf" "review coder,architecture-design-reviewer,hardener,security-reviewer,qa\n")
      (should= "coder,architecture-design-reviewer,hardener,security-reviewer,qa" (config/resolve-route "review"))
      (conf "routes.conf" "")
      (should= "specifier,coder,hardener,qa" (config/default-route))
      (should (config/role-audits? "security-reviewer"))
      (should= "coder,architecture-design-reviewer,hardener,security-reviewer,qa"
               (config/resolve-route "coder,architecture-design-reviewer,hardener,security-reviewer,qa"))
      (binding [*env* {"KEVIN_ROUTE" "coder,security-reviewer,qa"}]
        (should= "coder,security-reviewer,qa" (config/default-route)))
      (should-throw clojure.lang.ExceptionInfo (config/resolve-route "security-reviewer,hardener,qa")))

    (it "does not expand a fallback that collides with a named route"
      (conf "roles.conf" "coder strong subagent\nsecurity-reviewer strong subagent optional\n")
      (conf "routes.conf" "coder coder,security-reviewer\n")
      (should= "coder" (config/default-route))
      (should= "coder,security-reviewer" (config/resolve-route "coder")))

    (it "refuses an empty fallback rather than enabling optional reviewers"
      (conf "roles.conf" "security-reviewer strong subagent optional\n")
      (conf "routes.conf" "")
      (should-throw clojure.lang.ExceptionInfo "empty route" (config/default-route)))

    (it "accepts optional and audit flags in either order and rejects duplicate or unknown flags"
      (doseq [role ["architecture-design-reviewer" "security-reviewer"]]
        (spit (str (fs/path *dir* "kevin/roles" (str role ".md"))) "fixture"))
      (doseq [flags ["optional" "optional audit" "audit optional"]]
        (conf "roles.conf" (str "coder strong subagent\nsecurity-reviewer strong subagent " flags "\nqa fast subagent\n"))
        (conf "routes.conf" "feature coder,qa\n")
        (should= 0 (first (check)))
        (should= (not= flags "optional") (config/role-audits? "security-reviewer")))
      (conf "routes.conf" "feature coder\n")
      (doseq [[flags problem] [["optional optional" "repeats flag 'optional'"]
                                ["audit audit" "repeats flag 'audit'"]
                                ["optional loud" "unknown flag 'loud'"]]]
        (conf "roles.conf" (str "coder strong subagent " flags "\n"))
        (let [[status _ errors] (check)]
          (should= 1 status)
          (should (some #(str/includes? % problem) errors))))))

  (context "send-back roles"
    (it "reads the send-back flag, alone or with audit and optional in any order"
      (spit (str (fs/path *dir* "kevin/roles/pr-reviewer.md")) "fixture")
      (conf "routes.conf" "feature coder,qa\n")
      (doseq [flags ["send-back" "optional send-back" "send-back audit optional" "audit optional send-back"]]
        (conf "roles.conf" (str "coder strong subagent\npr-reviewer strong subagent " flags "\nqa fast subagent\n"))
        (should= 0 (first (check)))
        (should (config/sends-back? "pr-reviewer"))
        (should-not (config/sends-back? "coder"))))

    (it "refuses send-back on a gate or a spec-judge, and a repeated send-back"
      (doseq [role ["falsifier" "spec-judge"]] (spit (str (fs/path *dir* "kevin/roles" (str role ".md"))) "fixture"))
      (doseq [[row problem] [["falsifier strong subagent gate send-back" "roles.conf: 'falsifier' is a gate and cannot also be send-back"]
                             ["spec-judge strong subagent spec-judge send-back" "roles.conf: 'spec-judge' is a spec-judge and cannot also be send-back"]
                             ["coder strong subagent send-back send-back" "roles.conf: 'coder' repeats flag 'send-back'"]]]
        (conf "roles.conf" (str "specifier strong session\n" row "\nqa fast subagent\n"))
        (let [[status _ errors] (check)]
          (should= 1 status)
          (should (some #(str/includes? % problem) errors))))))

  (context "gate roles"
    (it "keeps a gate off the pipeline and the routes and lists it with the gates"
      (spit (str (fs/path *dir* "kevin/roles/falsifier.md")) "fixture")
      (conf "roles.conf" "specifier strong session\nfalsifier strong subagent gate\ncoder strong subagent audit\nqa fast subagent\n")
      (should= ["specifier" "coder" "qa"] (config/pipeline))
      (should= ["falsifier"] (config/gates))
      (should (config/gate? "falsifier"))
      (should-not (config/gate? "coder"))
      (should= "specifier,coder,qa" (config/default-route))
      (should= nil (config/require-agent "falsifier"))
      (should= nil (config/require-agent "coder"))
      (should-throw clojure.lang.ExceptionInfo "unknown role 'nope' (see kevin/roles.conf)" (config/require-agent "nope"))
      (should-throw clojure.lang.ExceptionInfo "unknown role 'falsifier' (see kevin/roles.conf)" (config/require-role "falsifier"))
      (should-throw clojure.lang.ExceptionInfo "'falsifier' in route 'specifier,falsifier,coder' is a gate, not a stage; it runs at the specification gate"
                    (config/resolve-route "specifier,falsifier,coder"))
      (should= [0 "CHECK: ok (3 roles, 2 routes)\n" [""]] (check)))

    (it "has no gates when no role is flagged gate"
      (should= [] (config/gates)))

    (it "refuses a gate that audits, is optional, runs in session, or sits on a route"
      (spit (str (fs/path *dir* "kevin/roles/falsifier.md")) "fixture")
      (doseq [[row problem] [["falsifier strong subagent gate audit" "'falsifier' is a gate and cannot also be audit or optional"]
                             ["falsifier strong subagent optional gate" "'falsifier' is a gate and cannot also be audit or optional"]
                             ["falsifier strong session gate" "'falsifier' is a gate and must run as subagent"]]]
        (conf "roles.conf" (str "specifier strong session\n" row "\ncoder strong subagent\nqa fast subagent\n"))
        (let [[status _ errors] (check)]
          (should= 1 status)
          (should (some #(str/includes? % problem) errors))))
      (conf "roles.conf" "specifier strong session\nfalsifier strong subagent gate\ncoder strong subagent\nqa fast subagent\n")
      (conf "routes.conf" "feature specifier,falsifier,coder,qa\n")
      (let [[status _ errors] (check)]
        (should= 1 status)
        (should (some #(str/includes? % "routes.conf: feature: 'falsifier' in route 'specifier,falsifier,coder,qa' is a gate, not a stage") errors)))))

  (context "spec-judge role"
    (it "keeps the spec-judge off the pipeline, the gates and the routes, and names it"
      (doseq [role ["falsifier" "spec-judge"]] (spit (str (fs/path *dir* "kevin/roles" (str role ".md"))) "fixture"))
      (conf "roles.conf" "specifier strong session\nfalsifier strong subagent gate\nspec-judge strong subagent spec-judge\ncoder strong subagent\nqa fast subagent\n")
      (should= ["specifier" "coder" "qa"] (config/pipeline))
      (should= ["falsifier"] (config/gates))
      (should= "spec-judge" (config/spec-judge))
      (should (config/spec-judge? "spec-judge"))
      (should-not (config/spec-judge? "falsifier"))
      (should-not (config/gate? "spec-judge"))
      (should= nil (config/require-agent "spec-judge"))
      (should-throw clojure.lang.ExceptionInfo "'spec-judge' in route 'specifier,spec-judge,coder' is a spec-judge, not a stage; it runs at the specification gate"
                    (config/resolve-route "specifier,spec-judge,coder"))
      (should= [0 "CHECK: ok (3 roles, 2 routes)\n" [""]] (check)))

    (it "has no spec-judge when no role is flagged spec-judge"
      (should= nil (config/spec-judge)))

    (it "refuses a spec-judge that is also audit, optional or gate, runs in session, or comes twice"
      (doseq [role ["spec-judge" "referee"]] (spit (str (fs/path *dir* "kevin/roles" (str role ".md"))) "fixture"))
      (doseq [[rows problem] [["spec-judge strong subagent spec-judge audit" "'spec-judge' is a spec-judge and cannot also be audit, optional or gate"]
                              ["spec-judge strong subagent optional spec-judge" "'spec-judge' is a spec-judge and cannot also be audit, optional or gate"]
                              ["spec-judge strong subagent gate spec-judge" "'spec-judge' is a spec-judge and cannot also be audit, optional or gate"]
                              ["spec-judge strong session spec-judge" "'spec-judge' is a spec-judge and must run as subagent"]
                              ["spec-judge strong subagent spec-judge\nreferee strong subagent spec-judge" "'referee' is a second spec-judge; roles.conf has one"]]]
        (conf "roles.conf" (str "specifier strong session\n" rows "\ncoder strong subagent\nqa fast subagent\n"))
        (let [[status _ errors] (check)]
          (should= 1 status)
          (should (some #(str/includes? % problem) errors)))))

    (it "refuses the old flag judge as an unknown flag that names spec-judge"
      (spit (str (fs/path *dir* "kevin/roles/referee.md")) "fixture")
      (conf "roles.conf" "specifier strong session\nreferee strong subagent judge\ncoder strong subagent\nqa fast subagent\n")
      (let [[status _ errors] (check)]
        (should= 1 status)
        (should (some #(str/includes? % "roles.conf: 'referee' has unknown flag 'judge' (only audit, optional, gate, spec-judge, send-back)") errors))))

    (it "refuses a route that names the spec-judge"
      (spit (str (fs/path *dir* "kevin/roles/spec-judge.md")) "fixture")
      (conf "roles.conf" "specifier strong session\nspec-judge strong subagent spec-judge\ncoder strong subagent\nqa fast subagent\n")
      (conf "routes.conf" "feature specifier,spec-judge,coder,qa\n")
      (let [[status _ errors] (check)]
        (should= 1 status)
        (should (some #(str/includes? % "routes.conf: feature: 'spec-judge' in route 'specifier,spec-judge,coder,qa' is a spec-judge, not a stage") errors)))))

  (context "check"
    (it "accepts a valid catalogue, the bbmain kind and the coverage rewrite"
      (should= [0 "CHECK: ok (3 roles, 2 routes)\n" [""]] (check)))

    (it "refuses none as a reserved role name on a stage, gate or spec-judge row, even with its prompt"
      (spit (str (fs/path *dir* "kevin/roles/none.md")) "fixture")
      (doseq [row ["none strong subagent" "none strong subagent gate" "none strong subagent spec-judge"]]
        (conf "roles.conf" (str "specifier strong session\ncoder strong subagent\nqa fast subagent\n" row "\n"))
        (let [[status _ errors] (check)]
          (should= 1 status)
          (should (some #{"CHECK: roles.conf: 'none' is reserved"} errors)))))

    (it "reports missing tiers and roles files"
      (fs/delete (fs/path *dir* "kevin" "tiers.conf"))
      (fs/delete (fs/path *dir* "kevin" "roles.conf"))
      (let [[status _ lines] (check)]
        (should= 1 status)
        (should (some #{"CHECK: tiers.conf: missing"} lines))
        (should (some #{"CHECK: roles.conf: missing"} lines))))

    (it "reports every problem of a row"
      (conf "tiers.conf" "strong opus high gpt\nstrong opus\n")
      (conf "roles.conf" (str "specifier strong session\nBad strong subagent\ndone strong subagent\nspecifier strong subagent\n"
                              "coder weak robot\nqa strong subagent flag\nx\n"))
      (conf "routes.conf" "feature specifier,qa\nfeature qa,specifier\nlonely\n")
      (conf "tools.conf" "a java s - t rot13 -\na bb github.com/x/a 0123abc t - -\nshort bb\nc bb github.com/x/a aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa t - -\n")
      (should= ["CHECK: tiers.conf: 'strong' needs exactly <tier> <claude-model> <codex-effort> <copilot-model> [<kilo-model>] [<vibe-model>]"
                "CHECK: tiers.conf: tier 'strong' listed twice"
                "CHECK: roles.conf: role name 'Bad' must be lowercase letters, digits, and hyphens"
                "CHECK: roles/Bad.md: missing role prompt for 'Bad'"
                "CHECK: roles.conf: 'done' is reserved"
                "CHECK: roles/done.md: missing role prompt for 'done'"
                "CHECK: roles.conf: role 'specifier' listed twice"
                "CHECK: roles.conf: 'coder' uses unknown tier 'weak' (see tiers.conf)"
                "CHECK: roles.conf: 'coder' runs-as must be subagent or session, not 'robot'"
                "CHECK: roles.conf: 'qa' has unknown flag 'flag' (only audit, optional, gate, spec-judge, send-back)"
                "CHECK: roles.conf: 'x' needs <role> <tier> <runs-as> [audit] [optional] [send-back]"
                "CHECK: routes.conf: route 'feature' listed twice"
                "CHECK: routes.conf: feature: route 'qa,specifier' is out of order; follow kevin/roles.conf: specifier,Bad,done,specifier,coder,qa,x"
                "CHECK: routes.conf: 'lonely' needs exactly <name> <role,role,...>"
                "CHECK: tools.conf: 'a' has unknown kind 'java'"
                "CHECK: tools.conf: 'a' has unknown rewrite 'rot13'"
                "CHECK: tools.conf: tool 'a' listed twice"
                "CHECK: tools.conf: 'a' needs a 40-digit lowercase hex commit SHA as its ref, not '0123abc'"
                "CHECK: tools.conf: 'short' needs exactly <name> <kind> <source> <ref> <task> <rewrite> <needs>"
                "CHECK: tools.conf: 'c' pins github.com/x/a at a different ref than 'a'"
                (str "kevin: 20 configuration problem(s) in " *dir* "/kevin")]
               (nth (check) 2)))

    (it "reports an empty roles file"
      (conf "roles.conf" "")
      (should (some #{"CHECK: roles.conf: no roles"} (nth (check) 2))))))
