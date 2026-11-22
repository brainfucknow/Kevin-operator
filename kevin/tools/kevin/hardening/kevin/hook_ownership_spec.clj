(ns kevin.hook-ownership-spec
  (:require [babashka.fs :as fs]
           
            [speclj.core :refer [describe it should should-not]]
            [kevin.hook :as hook]
            [kevin.shell :as shell]))

(defn- fresh-repo []
  (let [repo (str (fs/create-temp-dir {:prefix "hook-hardening-"}))]
    (shell/must {:dir repo} "git" "init" "--quiet")
    repo))

(defn- hook-file [repo]
  (fs/path repo ".git" "hooks" "commit-msg"))

(defn- replaced-by-install? [text]
  (let [repo (fresh-repo)
        file (hook-file repo)]
    (fs/create-dirs (fs/parent file))
    (spit (str file) text)
    (hook/install repo)
    (let [now (slurp (str file))]
      (fs/delete-tree repo)
      (not= text now))))

(describe "hook ownership"
  (it "replaces a hook that carries only the kevin marker"
    (should (replaced-by-install? (str "#!/bin/sh\n" hook/marker "\n"))))

  (it "replaces a hook that carries only the legacy marker"
    (should (replaced-by-install? "#!/bin/sh\n# swarmforge commit-msg hook\n")))

  (it "leaves a hook with neither marker"
    (should-not (replaced-by-install? "#!/bin/sh\necho other\n"))))

(describe "comment character"
  (it "keeps a block that starts with a configured core.commentChar below the trailers"
    (let [repo (fresh-repo)
          file (str (fs/path repo "msg"))
          message "Fix\n\nTested: x\n\n; Please enter the commit message.\n"]
      (spit file message)
      (with-redefs [shell/run (let [run shell/run]
                                (fn [opts & cmd]
                                  (apply run (assoc opts :env {"GIT_CONFIG_COUNT" "1"
                                                               "GIT_CONFIG_KEY_0" "core.commentChar"
                                                               "GIT_CONFIG_VALUE_0" ";"})
                                         cmd)))]
        (hook/commit-msg ["qa" file]))
      (should (re-matches #"Fix\n\nBy qa\.\n\nLore-id: [0-9a-f]{8}\nTested: x\n\n; Please enter the commit message\.\n"
                          (slurp file)))
      (fs/delete-tree repo))))
