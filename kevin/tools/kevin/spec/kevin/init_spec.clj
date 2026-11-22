(ns kevin.init-spec
  "kevin init in a throwaway repository."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [speclj.core :refer [around describe it should should-not should= should-contain]]
            [kevin.repo-support :refer [*dir* captured git with-repo]]
            [kevin.runtime :as runtime]
            [kevin.task :as task]))

(defn- init [] (captured #(task/init [])))

(defn- project-md [] (slurp (str (fs/path *dir* "kevin" "project.md"))))

(defn- exclude-lines []
  (str/split-lines (slurp (str (fs/path *dir* ".git" "info" "exclude")))))

(defn- lines [text] (str/split-lines text))

(describe "kevin init"
  (around [it] (with-repo #(with-redefs [runtime/version (constantly "0.2.0")] (it))))

  (it "writes a starter kevin/project.md with the runtime version"
    (let [{:keys [status out]} (init)
          text (project-md)]
      (should= 0 status)
      (should-contain "roles: specifier coder cleaner" out)
      (should= 1 (count (filter #{"Minimum Kevin version: 0.2.0"} (lines text))))
      (should (some #{"Existing code: refactor freely"} (lines text)))
      (should-not (str/includes? text "Kevin-operator"))))

  (it "adds the ignore lines to the shared exclude file once, and writes no other tracked file"
    (init)
    (init)
    (let [found (exclude-lines)]
      (doseq [line [".worktrees/" ".kevin/" "tmp/" ".metrics/"]]
        (should= 1 (count (filter #{line} found)))))
    (should= "?? kevin/project.md" (git *dir* "status" "--porcelain" "--untracked-files=all"))
    (should (fs/exists? (fs/path *dir* ".git" "hooks" "commit-msg"))))

  (it "keeps an existing project.md that names a minimum, whatever it is"
    (spit (str (fs/path *dir* "kevin" "project.md")) "# Mine\nMinimum Kevin version: 0.1.0\n")
    (init)
    (should= "# Mine\nMinimum Kevin version: 0.1.0\n" (project-md)))

  (it "appends the minimum to an existing project.md without one and changes nothing else"
    (spit (str (fs/path *dir* "kevin" "project.md")) "# Mine\nExisting code: ask before refactoring")
    (init)
    (should= "# Mine\nExisting code: ask before refactoring\nMinimum Kevin version: 0.2.0\n" (project-md))
    (init)
    (should= "# Mine\nExisting code: ask before refactoring\nMinimum Kevin version: 0.2.0\n" (project-md)))

  (it "refuses a project with a committed install and changes nothing"
    (doseq [path ["kevin/bin/kevin" "kevin/constitution/articles/project.md"]]
      (fs/create-dirs (fs/parent (fs/path *dir* path)))
      (spit (str (fs/path *dir* path)) "x\n"))
    (git *dir* "add" "-A")
    (git *dir* "commit" "-q" "-m" "Committed install")
    (let [{:keys [status err]} (init)]
      (should= 1 status)
      (should= "this project has a committed Kevin install; run kevin init --convert" err))
    (should-not (fs/exists? (fs/path *dir* "kevin" "project.md")))
    (should= "" (git *dir* "status" "--porcelain" "--untracked-files=all"))))
