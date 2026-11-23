(ns kevin.project-agents-hardening-spec
  (:require [babashka.fs :as fs]
            [speclj.core :refer [describe it around should= should-contain should-not-contain]]
            [kevin.adapters :as adapters]
            [kevin.installed :as installed]
            [kevin.project-agents :as project-agents]
            [kevin.repo-support :refer [*dir* with-repo write-conf! captured]]))

(defn- read [path] (slurp (str (fs/path *dir* path))))

(defn- with-runtime [run-spec]
  (with-repo
    (fn []
      (let [runtime (str (fs/create-temp-dir {:prefix "kevin-runtime-"}))]
        (try
          (fs/copy-tree (fs/path *dir* "kevin") (fs/path runtime "kevin"))
          (fs/create-dirs (fs/path runtime ".claude-plugin"))
          (spit (str (fs/path runtime ".claude-plugin" "plugin.json")) "{\"name\": \"kevin\", \"version\": \"0.3.0\"}")
          (with-redefs [installed/home (constantly (str (fs/path runtime "kevin")))]
            (run-spec))
          (finally (fs/delete-tree runtime)))))))

(defn- fast! [line] (write-conf! *dir* "tiers.conf" (str "frontier inherit high gpt\nstrong opus medium gpt-5\nfast " line "\n")))

(describe "project agents of runtime roles"
  (around [it] (with-runtime it))

  (it "names the runtime prompt of a runtime role, not the path of a checkout file"
    (fast! "claude-haiku-5-5 low gpt-mini")
    (captured #(adapters/adapters []))
    (should-contain "<home>/roles/cleaner.md" (read ".claude/agents/kevin-cleaner.md")))

  (it "rewrites a stale project agent that kevin wrote"
    (fast! "claude-haiku-5-5 low gpt-mini")
    (captured #(adapters/adapters []))
    (let [fresh (read ".claude/agents/kevin-cleaner.md")]
      (spit (str (fs/path *dir* ".claude/agents/kevin-cleaner.md")) (str fresh "stale\n"))
      (should-contain "WARNING: .claude/agents/kevin-cleaner.md is stale" (:out (captured #(project-agents/agent-command ["cleaner" "claude"]))))
      (should= 0 (:status (captured #(adapters/adapters []))))
      (should= fresh (read ".claude/agents/kevin-cleaner.md"))
      (should-not-contain "WARNING" (:out (captured #(project-agents/agent-command ["cleaner" "claude"])))))))
