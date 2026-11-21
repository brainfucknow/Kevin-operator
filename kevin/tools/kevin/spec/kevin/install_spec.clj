(ns kevin.install-spec
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [kevin.repo-support :refer [*dir* with-repo]]
            [speclj.core :refer :all]))

(def checkout (str (fs/canonicalize (fs/path (fs/parent *file*) "../../../../.."))))
(def protected ["kevin/constitution/articles/project.md" "kevin/roles.conf"
                "kevin/routes.conf" "kevin/tiers.conf" "kevin/tools.conf"])
(def ^:dynamic *source* checkout)

(defn- write-file! [root relative text]
  (let [path (fs/path root relative)]
    (fs/create-dirs (fs/parent path))
    (spit (str path) text)))

(defn- install! []
  (process/shell {:dir *dir* :out :string :err :string :continue true}
                 "bash" (str (fs/path *source* "install.sh")) *dir*))

(defn- installed-kevin [& args]
  (apply process/shell {:dir *dir* :out :string :err :string :continue true}
         (str (fs/path *dir* "kevin/bin/kevin")) args))

(defn- with-source [example]
  (let [source (fs/path *dir* "source checkout")]
    (fs/create-dirs source)
    (fs/copy (fs/path checkout "install.sh") (fs/path source "install.sh"))
    (doseq [relative ["kevin" ".agents/skills" ".claude/skills" ".kilo/command" ".vibe/skills"]
            :when (fs/exists? (fs/path checkout relative))]
      (fs/copy-tree (fs/path checkout relative) (fs/path source relative)))
    (binding [*source* (str source)] (example))))

(defn- contents [paths]
  (into {} (map (fn [path] [path (slurp (str (fs/path *dir* path)))]) paths)))

(defn- review-lines [result]
  (filterv #(str/starts-with? % "UNSHIPPED ") (str/split-lines (:out result))))

(describe "Installer updates"
  (around [example] (with-repo example))

  (it "preserves existing project configuration byte-for-byte on reinstall"
    (should= 0 (:exit (install!)))
    (doseq [path (rest protected)]
      (write-file! *dir* path (str (slurp (str (fs/path *dir* path))) "# consumer setting\n")))
    (write-file! *dir* "kevin/constitution/articles/project.md" "project language and policy chosen here\n")
    (let [before (contents protected)
          {:keys [exit]} (install!)]
      (should= 0 exit)
      (doseq [[path content] before]
        (should= content (slurp (str (fs/path *dir* path)))))))

  (it "seeds only missing defaults and never creates or overwrites verify.conf"
    (doseq [path protected] (fs/delete-if-exists (fs/path *dir* path)))
    (should= 0 (:exit (install!)))
    (doseq [path protected]
      (should= (slurp (str (fs/path checkout path))) (slurp (str (fs/path *dir* path)))))
    (should-not (fs/exists? (fs/path *dir* "kevin/verify.conf")))
    (write-file! *dir* "kevin/verify.conf" "project verification\n")
    (write-file! *dir* "kevin/constitution/articles/project.md" "project owns this\n")
    (fs/delete (fs/path *dir* "kevin/routes.conf"))
    (let [before (contents (remove #{"kevin/routes.conf"} protected))]
      (should= 0 (:exit (install!)))
      (should= before (contents (keys before)))
      (should= (slurp (str (fs/path checkout "kevin/routes.conf")))
               (slurp (str (fs/path *dir* "kevin/routes.conf")))))
    (should= "project verification\n" (slurp (str (fs/path *dir* "kevin/verify.conf")))))

  (it "regenerates adapters from the consumer catalogue and keeps repeat installs idempotent"
    (should= 0 (:exit (install!)))
    (write-file! *dir* "kevin/roles.conf"
                 "specifier frontier subagent\ncoder fast subagent\ncleaner fast subagent\n")
    (write-file! *dir* "kevin/routes.conf" "feature coder\n")
    (should= 0 (:exit (installed-kevin "adapters")))
    (fs/delete (fs/path *dir* ".kevin/adapters.edn"))
    (let [paths (concat protected [".codex/agents/coder.toml" ".github/agents/coder.agent.md"
                                  "AGENTS.md" "CLAUDE.md" ".github/copilot-instructions.md"
                                  ".gitignore" "kevin/.gitignore"])
          before (contents paths)]
      (should= 0 (:exit (install!)))
      (should= before (contents paths))
      (should= 0 (:exit (install!)))
      (should= before (contents paths))
      (should= 0 (:exit (installed-kevin "check")))
      (should-contain "coder" (:out (installed-kevin "roles")))
      (should-contain "feature" (:out (installed-kevin "routes")))))

  (it "keeps invalid consumer configuration intact and permits retry after correction"
    (should= 0 (:exit (install!)))
    (let [roles (slurp (str (fs/path *dir* "kevin/roles.conf")))
          invalid "not a valid catalogue\n"]
      (write-file! *dir* "kevin/roles.conf" invalid)
      (let [{:keys [exit err]} (install!)]
        (should-not= 0 exit)
        (should-contain "roles.conf" err)
        (should= invalid (slurp (str (fs/path *dir* "kevin/roles.conf")))))
      (write-file! *dir* "kevin/roles.conf" roles)
      (should= 0 (:exit (install!)))))

  (it "refuses symlink and nonregular configuration destinations without modifying their targets"
    (should= 0 (:exit (install!)))
    (let [before (contents protected)
          outside (fs/path *dir* "external.txt")]
      (spit (str outside) "external contents\n")
      (doseq [path protected]
        (let [destination (fs/path *dir* path)]
          (fs/delete destination)
          (fs/create-sym-link destination outside)
          (let [{:keys [exit err]} (install!)]
            (should-not= 0 exit)
            (should-contain "configuration conflict" err)
            (should (fs/sym-link? destination))
            (should= "external contents\n" (slurp (str outside))))
          (fs/delete destination)
          (fs/create-dirs destination)
          (should-not= 0 (:exit (install!)))
          (should (fs/directory? destination))
          (fs/delete-tree destination)
          (write-file! *dir* path (before path))))
      (should= 0 (:exit (install!)))))

  (it "ships .vibe/skills into the consumer project"
    (with-source
      #(do (write-file! *source* ".vibe/skills/swarm/SKILL.md" "vibe override\n")
           (should= 0 (:exit (install!)))
           (should= "vibe override\n" (slurp (str (fs/path *dir* ".vibe/skills/swarm/SKILL.md")))))))

  (it "refreshes shipped runtime and lists unshipped payload files without deleting them"
    (with-source
      #(let [retired "kevin/roles/retired.md"
             old-tool "kevin/tools/retired/src/Old.clj"
             user-guide "kevin/languages/My Guide.md"
             user-skill ".agents/skills/custom/SKILL.md"
             user-vibe-skill ".vibe/skills/custom/SKILL.md"
             runtime "kevin/handoff-protocol.md"]
         (write-file! *source* retired "retired role\n")
         (write-file! *source* old-tool "retired source\n")
         (should= 0 (:exit (install!)))
         (fs/delete (fs/path *source* retired))
         (fs/delete (fs/path *source* old-tool))
         (write-file! *dir* user-guide "user guide\n")
         (write-file! *dir* user-skill "user skill\n")
         (write-file! *dir* user-vibe-skill "user vibe skill\n")
         (write-file! *dir* "kevin/tools/kevin/.cpcache/old" "cache\n")
         (write-file! *dir* ".codex/agents/user.toml" "user agent\n")
         (write-file! *source* runtime "updated runtime payload\n")
         (let [unknown [retired old-tool user-guide user-skill user-vibe-skill]
               before (contents unknown)
               first-result (install!)
               second-result (install!)]
           (should= 0 (:exit first-result))
           (should= 0 (:exit second-result))
           (should= before (contents unknown))
           (should= "updated runtime payload\n" (slurp (str (fs/path *dir* runtime))))
           (should= (review-lines first-result) (review-lines second-result))
           (should= (mapv (fn [path] (str "UNSHIPPED (manual review; may be a user file): " path)) (sort unknown))
                    (review-lines first-result)))))))
