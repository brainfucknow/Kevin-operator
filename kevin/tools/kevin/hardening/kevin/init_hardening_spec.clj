(ns kevin.init-hardening-spec
  (:require [babashka.fs :as fs]
            [speclj.core :refer [around describe it should-not should= should-contain]]
            [kevin.installed :as installed]
            [kevin.repo-support :refer [*dir* captured git with-repo]]
            [kevin.task :as task]))

(defn- init [] (captured #(task/init [])))

(defn- exclude-file [] (str (fs/path *dir* ".git" "info" "exclude")))

(describe "kevin init hardening"
  (around [it] (with-repo #(with-redefs [installed/version (constantly "0.2.0")] (it))))

  (it "keeps the lines of an existing exclude file, with or without a final newline"
    (fs/create-dirs (fs/parent (exclude-file)))
    (spit (exclude-file) "mine\nyours")
    (init)
    (let [lines (clojure.string/split-lines (slurp (exclude-file)))]
      (should= ["mine" "yours"] (take 2 lines))
      (should= 6 (count lines))))

  (it "adds no blank line after an exclude file that ends in a newline"
    (fs/create-dirs (fs/parent (exclude-file)))
    (spit (exclude-file) "mine\n")
    (init)
    (should= "mine\n" (subs (slurp (exclude-file)) 0 5))
    (should-not (clojure.string/includes? (slurp (exclude-file)) "\n\n")))

  (it "initializes a project where only one of the two install files is tracked"
    (fs/create-dirs (fs/path *dir* "kevin" "bin"))
    (spit (str (fs/path *dir* "kevin" "bin" "kevin")) "x\n")
    (git *dir* "add" "-A")
    (git *dir* "commit" "-q" "-m" "Only the launcher")
    (let [{:keys [status out]} (init)]
      (should= 0 status)
      (should-contain "roles:" out))))
