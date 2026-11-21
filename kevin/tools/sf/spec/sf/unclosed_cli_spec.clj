(ns sf.unclosed-cli-spec
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [sf.cli :as cli]
            [sf.repo-support :as support :refer [*dir* git with-repo]]
            [speclj.core :refer :all]))

(defn- kevin [& args]
  (support/captured #(cli/main (vec args))))

(defn- recover! []
  (let [id (:out (kevin "new" "Hosted work" "--route" "coder"))]
    (should= 0 (:status (kevin "handoff" id)))
    (git *dir* "merge" "-q" "--no-ff" "-m" "Host merge" (str "swarm/" id))
    (git *dir* "branch" "-D" (str "swarm/" id))
    id))

(describe "Public CLI for unclosed histories"
  (around [example]
    (with-repo {"roles.conf" "coder strong subagent\n" "routes.conf" "feature coder\n"} example))

  (it "does not discover task-only or unknown-type trailer commits as tasks"
    (doseq [[id type] [["phantom-1111111" nil] ["phantom-2222222" "unknown"] ["phantom-3333333" ""]]]
      (git *dir* "commit" "-q" "--allow-empty" "-m"
           (str "Incidental\n\nKevin-Task: " id "\n"
                (when type (str "Kevin-Record: " type "\n")))))
    (should= "" (:out (kevin "harvest")))
    (should= 1 (:status (kevin "show" "phantom-1111111"))))

  (it "ignores later invalid trailers without losing a real anchor or creating prefix ambiguity"
    (let [id (recover!)]
      (doseq [[task type] [[id nil] [id "unknown"] [(str id "-fake") nil]]]
        (git *dir* "commit" "-q" "--allow-empty" "-m"
             (str "Incidental\n\nKevin-Task: " task "\n"
                  (when type (str "Kevin-Record: " type "\n")))))
      (let [result (kevin "show" "hosted-work")
            [exported] (map #(json/parse-string % true) (str/split-lines (:out (kevin "harvest"))))]
        (should= 0 (:status result))
        (should-contain "TITLE: Hosted work" (:out result))
        (should= id (:id exported))
        (should= "done" (:stage exported))
        (should= ["created" "handoff"] (mapv :type (:records exported))))))

  (it "keeps legacy record names and truncated read-only history available"
    (let [id "legacy-7777777"]
      (git *dir* "commit" "-q" "--allow-empty" "-m"
           (str "Historical verdict\n\nKevin-Task: " id
                "\nKevin-Record: verdict\nSwarmForge-Verdict: converged"
                "\nKevin-Stage: specifier\nKevin-Route: specifier,coder\nKevin-Needs: approval\n"))
      (should= "specifier" (:out (kevin "stage" id)))
      (should= "specifier,coder" (:out (kevin "route" id)))
      (let [exported (json/parse-string (:out (kevin "harvest")) true)]
        (should= true (:truncated exported))
        (should= ["spec-verdict"] (mapv :type (:records exported))))
      (should= 0 (:status (kevin "show" id)))
      (should= 0 (:status (kevin "spec-revisions" id)))))

  (it "omits invalid records inside a recognized recovered first-parent history"
    (let [id "older-9999999"]
      (doseq [type ["question" "unknown" "answer"]]
        (git *dir* "commit" "-q" "--allow-empty" "-m"
             (str "Historical " type "\n\nKevin-Task: " id "\nKevin-Record: " type
                  "\nKevin-Stage: coder\nKevin-Route: coder\nKevin-Needs: none\n")))
      (let [exported (json/parse-string (:out (kevin "harvest")) true)]
        (should= ["question" "answer"] (mapv :type (:records exported)))
        (should= "coder" (:stage exported)))
      (should-not-contain "type: unknown" (:out (kevin "show" id)))))

  (it "reports absent recorded scalar values instead of printing nil or succeeding silently"
    (let [id "partial-8888888"]
      (git *dir* "commit" "-q" "--allow-empty" "-m"
           (str "Historical question\n\nKevin-Task: " id "\nKevin-Record: question\n"))
      (doseq [command ["stage" "route"]]
        (let [{:keys [status err]} (kevin command id)]
          (should= 1 status)
          (should-contain id err)
          (should-contain (str "recorded " command) err)))
      (should= 0 (:status (kevin "show" id)))))

  (it "prints the recovered last recorded stage and route"
    (let [id (recover!)]
      (should= {:out "done" :err "" :status 0} (dissoc (kevin "stage" id) :result))
      (should= {:out "coder" :err "" :status 0} (dissoc (kevin "route" id) :result))))

  (it "refuses run even when recovered history last reached done"
    (let [id (recover!)
          {:keys [status err]} (kevin "run" id "claude")]
      (should= 1 status)
      (should-contain id err)
      (should-contain "unclosed" err)))

  (it "refuses finish before verification or merge changes"
    (let [id (recover!)
          head (git *dir* "rev-parse" "HEAD")
          {:keys [status err]} (kevin "finish" id)]
      (should= 1 status)
      (should-contain id err)
      (should-contain "unclosed" err)
      (should= head (git *dir* "rev-parse" "HEAD"))
      (should= "" (git *dir* "branch" "--list" (str "swarm/" id)))))

  (it "refuses dispatch before attempting to create a worktree"
    (let [id (recover!)
          {:keys [status err]} (kevin "dispatch" "coder" id "claude")]
      (should= 1 status)
      (should-contain id err)
      (should-contain "unclosed" err))))
