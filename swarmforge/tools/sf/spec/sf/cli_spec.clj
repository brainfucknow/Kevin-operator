(ns sf.cli-spec
  (:require [clojure.string :as str]
            [speclj.core :refer [around describe it should-contain should=]]
            [sf.cli :as cli]
            [sf.repo-support :as support :refer [with-repo]]
            [sf.shell :as shell]))

(defn- sf [& args]
  (support/captured #(cli/main (vec args))))

(describe "main"
  (around [it] (with-repo it))

  (it "prints the usage without a command and for help"
    (doseq [args [[] [""] ["-h"] ["--help"] ["help"]]]
      (should-contain "usage: sf <command>" (:out (apply sf args)))))

  (it "stops at an unknown command"
    (should= {:out "" :err "unknown command 'nope' (try sf --help)" :status 1}
             (dissoc (sf "nope") :result)))

  (it "prints the roles and the routes"
    (should= "specifier\ncoder\ncleaner" (:out (sf "roles")))
    (should= (:out (sf "roles")) (:out (sf "pipeline")))
    (should= "feature    specifier,coder,cleaner\nquick      coder" (:out (sf "routes"))))

  (it "prints the gates, none by default"
    (should= "" (:out (sf "gates")))
    (spit (str support/*dir* "/swarmforge/roles/falsifier.md") "# falsifier\n\nAudits.\n")
    (support/write-conf! support/*dir* "roles.conf" "specifier frontier subagent\nfalsifier strong subagent gate\ncoder strong subagent\n")
    (should= "falsifier" (:out (sf "gates")))
    (should= "specifier\ncoder" (:out (sf "roles"))))

  (it "checks the catalogue"
    (should= "CHECK: ok (3 roles, 2 routes)" (:out (sf "check"))))

  (it "runs a task command with the arguments after it"
    (let [id (:out (sf "new" "Add login"))]
      (should= "specifier" (:out (sf "stage" id)))
      (should-contain "REVIEW:" (:out (sf "review" id "-m" "spec")))
      (should-contain (str "HANDOFF: " id " specifier -> coder") (:out (sf "approve" id)))))

  (it "hands the rest to sf tool and fails with its status"
    (should-contain "usage: sf tool" (:out (sf "tool" "--help")))
    (should= 1 (:status (sf "tool" "nope")))))

(def ^:private new-entry
  "The new entry of the global usage, as the specification quotes it."
  (str/join "\n" ["  new <title> [-m text | -F file] [--route NAME|a,b,c] [--gates <gates>]"
                  "      [--fixes <id>|'#N' [--found-by pr-review|ci|operator]]"
                  "                                  create a task on a route; prints its id. <gates> is a"
                  "                                  comma list of gate roles, or none (the default)"]))

(def ^:private usage-lines
  {"ask" "  ask <id> <text>                 record a question and stop: needs operator"
   "answer" "  answer <id> <text>              answer a role's question and clear needs operator"
   "reject" "  reject <id> <text>              send the specification back to the specifier"})

(def ^:private option-refusals
  {"new" "new needs a title that does not start with '-'"
   "ask" "ask needs a question that does not start with '-'"
   "answer" "answer needs text that does not start with '-'"
   "reject" "reject needs a reason that does not start with '-'"})

(describe "option-like free arguments"
  (around [it] (with-repo it))

  (it "prints the new usage entry for new --help and -h and creates no task"
    (doseq [title ["--help" "-h" " --help "]]
      (let [{:keys [out err status]} (sf "new" title)]
        (should= (str/trim new-entry) out)
        (should= "" err)
        (should= 0 status)))
    (should= (str new-entry "\n") (with-out-str (cli/main ["new" "--help"])))
    (should= "" (:out (sf "ls"))))

  (it "refuses every other '-'-prefixed title and creates no task"
    (doseq [args [["-x"] ["--"] ["--bogus"] ["-m" "body"] [" -x "]]]
      (let [{:keys [out err status]} (apply sf "new" args)]
        (should= "" out)
        (should= (option-refusals "new") err)
        (should= 1 status)))
    (should= "" (:out (sf "ls"))))

  (it "prints the command's own usage line for ask/answer/reject <id> --help and -h, recording nothing"
    (let [id (:out (sf "new" "Probe"))]
      (doseq [cmd ["ask" "answer" "reject"] title ["--help" "-h" " --help "]]
        (let [before (:out (sf "show" id))
              {:keys [out err status]} (sf cmd id title)]
          (should= (str/trim (usage-lines cmd)) out)
          (should= "" err)
          (should= 0 status)
          (should= before (:out (sf "show" id)))))
      (should= (str (usage-lines "ask") "\n")
               (with-out-str (cli/main ["ask" id "--help"])))))

  (it "refuses every other '-'-prefixed text of ask/answer/reject, recording nothing"
    (let [id (:out (sf "new" "Probe"))]
      (doseq [cmd ["ask" "answer" "reject"] text ["-x" "--help extra"]]
        (let [before (:out (sf "show" id))
              {:keys [out err status]} (sf cmd id text)]
          (should= "" out)
          (should= (option-refusals cmd) err)
          (should= 1 status)
          (should= before (:out (sf "show" id)))))))

  (it "resolves the id before the text check and keeps the state checks after it"
    (let [id (:out (sf "new" "Probe"))
          {:keys [err status]} (sf "ask" "--help")]
      (should= "no task '--help'" err)
      (should= 1 status)
      (let [{:keys [out status]} (sf "reject" id "--help")]
        (should= (str/trim (usage-lines "reject")) out)
        (should= 0 status)
        (should= (option-refusals "reject") (:err (sf "reject" id "-x"))))))

  (it "keeps the unchanged behavior around the new checks"
    (let [id (:out (sf "new" "Real title"))]
      (should= "specifier" (:out (sf "stage" id)))
      (should-contain "ASKED" (:out (sf "ask" id "why?")))
      (should-contain "ANSWERED" (:out (sf "answer" id "see --gates")))
      (should= "new needs a title" (:err (sf "new")))
      (should= "ask needs a question" (:err (sf "ask" id)))
      (should= "unknown role '--help' (see swarmforge/roles.conf)" (:err (sf "next" "--help")))
      (should= "unknown ls option '--help'" (:err (sf "ls" "--help")))
      (should= "no task '--help'" (:err (sf "show" "--help")))
      (should-contain "usage: sf <command>" (:out (sf "--help"))))))

(describe "main-with"
  (it "is 0 and prints nothing after a command that succeeds"
    (should= {:out "" :err "" :status 0 :result 0} (support/captured #(shell/main-with "p: " (constantly nil)))))

  (it "prints the message after the prefix and gives the exit status of the failure"
    (let [{:keys [err result]} (support/captured #(shell/main-with "p: " (fn [] (shell/fail 3 "broke"))))]
      (should= ["p: broke" 3] [err result])))

  (it "is 1 for an exception without a status and stays quiet for a blank message"
    (let [{:keys [err result]} (support/captured #(shell/main-with "p: " (fn [] (throw (Exception. "boom")))))]
      (should= ["p: boom" 1] [err result]))
    (should= {:out "" :err "" :status 0 :result 3} (support/captured #(shell/main-with "p: " (fn [] (shell/fail 3 nil)))))))
