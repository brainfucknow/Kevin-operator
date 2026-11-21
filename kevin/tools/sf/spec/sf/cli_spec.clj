(ns sf.cli-spec
  (:require [clojure.string :as str]
            [speclj.core :refer [around describe it should-contain should=]]
            [sf.cli :as cli]
            [sf.repo-support :as support :refer [with-repo]]
            [sf.shell :as shell]))

(defn- kevin [& args]
  (support/captured #(cli/main (vec args))))

(describe "main"
  (around [it] (with-repo it))

  (it "prints the usage without a command and for help"
    (doseq [args [[] [""] ["-h"] ["--help"] ["help"]]]
      (should-contain "usage: kevin <command>" (:out (apply kevin args)))))

  (it "stops at an unknown command"
    (should= {:out "" :err "unknown command 'nope' (try kevin --help)" :status 1}
             (dissoc (kevin "nope") :result)))

  (it "prints the roles and the routes"
    (should= "specifier\ncoder\ncleaner" (:out (kevin "roles")))
    (should= (:out (kevin "roles")) (:out (kevin "pipeline")))
    (should= "feature    specifier,coder,cleaner\nquick      coder" (:out (kevin "routes"))))

  (it "prints the gates, none by default"
    (should= "" (:out (kevin "gates")))
    (spit (str support/*dir* "/kevin/roles/falsifier.md") "# falsifier\n\nAudits.\n")
    (support/write-conf! support/*dir* "roles.conf" "specifier frontier subagent\nfalsifier strong subagent gate\ncoder strong subagent\n")
    (should= "falsifier" (:out (kevin "gates")))
    (should= "specifier\ncoder" (:out (kevin "roles"))))

  (it "checks the catalogue"
    (should= "CHECK: ok (3 roles, 2 routes)" (:out (kevin "check"))))

  (it "runs a task command with the arguments after it"
    (let [id (:out (kevin "new" "Add login"))]
      (should= "specifier" (:out (kevin "stage" id)))
      (should-contain "REVIEW:" (:out (kevin "review" id "-m" "spec")))
      (should-contain (str "HANDOFF: " id " specifier -> coder") (:out (kevin "approve" id)))))

  (it "hands the rest to kevin tool and fails with its status"
    (should-contain "usage: kevin tool" (:out (kevin "tool" "--help")))
    (should= 1 (:status (kevin "tool" "nope")))))

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
      (let [{:keys [out err status]} (kevin "new" title)]
        (should= (str/trim new-entry) out)
        (should= "" err)
        (should= 0 status)))
    (should= (str new-entry "\n") (with-out-str (cli/main ["new" "--help"])))
    (should= "" (:out (kevin "ls"))))

  (it "refuses every other '-'-prefixed title and creates no task"
    (doseq [args [["-x"] ["--"] ["--bogus"] ["-m" "body"] [" -x "]]]
      (let [{:keys [out err status]} (apply kevin "new" args)]
        (should= "" out)
        (should= (option-refusals "new") err)
        (should= 1 status)))
    (should= "" (:out (kevin "ls"))))

  (it "prints the command's own usage line for ask/answer/reject <id> --help and -h, recording nothing"
    (let [id (:out (kevin "new" "Probe"))]
      (doseq [cmd ["ask" "answer" "reject"] title ["--help" "-h" " --help "]]
        (let [before (:out (kevin "show" id))
              {:keys [out err status]} (kevin cmd id title)]
          (should= (str/trim (usage-lines cmd)) out)
          (should= "" err)
          (should= 0 status)
          (should= before (:out (kevin "show" id)))))
      (should= (str (usage-lines "ask") "\n")
               (with-out-str (cli/main ["ask" id "--help"])))))

  (it "refuses every other '-'-prefixed text of ask/answer/reject, recording nothing"
    (let [id (:out (kevin "new" "Probe"))]
      (doseq [cmd ["ask" "answer" "reject"] text ["-x" "--help extra"]]
        (let [before (:out (kevin "show" id))
              {:keys [out err status]} (kevin cmd id text)]
          (should= "" out)
          (should= (option-refusals cmd) err)
          (should= 1 status)
          (should= before (:out (kevin "show" id)))))))

  (it "resolves the id before the text check and keeps the state checks after it"
    (let [id (:out (kevin "new" "Probe"))
          {:keys [err status]} (kevin "ask" "--help")]
      (should= "no task '--help'" err)
      (should= 1 status)
      (let [{:keys [out status]} (kevin "reject" id "--help")]
        (should= (str/trim (usage-lines "reject")) out)
        (should= 0 status)
        (should= (option-refusals "reject") (:err (kevin "reject" id "-x"))))))

  (it "keeps the unchanged behavior around the new checks"
    (let [id (:out (kevin "new" "Real title"))]
      (should= "specifier" (:out (kevin "stage" id)))
      (should-contain "ASKED" (:out (kevin "ask" id "why?")))
      (should-contain "ANSWERED" (:out (kevin "answer" id "see --gates")))
      (should= "new needs a title" (:err (kevin "new")))
      (should= "ask needs a question" (:err (kevin "ask" id)))
      (should= "unknown role '--help' (see kevin/roles.conf)" (:err (kevin "next" "--help")))
      (should= "unknown ls option '--help'" (:err (kevin "ls" "--help")))
      (should= "no task '--help'" (:err (kevin "show" "--help")))
      (should-contain "usage: kevin <command>" (:out (kevin "--help"))))))

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
