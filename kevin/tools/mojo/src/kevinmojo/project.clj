(ns kevinmojo.project
  (:require [babashka.fs :as fs]
            [kevincore.git :as git]
            [kevincore.outcome :as outcome]
            [kevincore.shell :as shell]))

(defn fail [message]
  (throw (outcome/usage-error message)))

(defn require-tool [tool]
  (when-not (shell/available? tool)
    (fail (str tool " is required; install it or activate the project environment"))))

(defn root []
  (fs/real-path (if (git/succeeds? ["rev-parse" "--show-toplevel"])
                  (git/repo-root)
                  ".")))

(defn inside? [root path]
  (and (fs/exists? path) (.startsWith (fs/real-path path) root)))

(defn resolve-path [root raw valid? description]
  (let [path (fs/absolutize raw)]
    (when-not (and (valid? path) (inside? root path))
      (fail (str "expected " description " inside the worktree: " raw)))
    (fs/real-path path)))

(defn output-dir [root folder]
  (reduce (fn [parent name]
            (let [path (fs/path parent name)]
              (when (fs/sym-link? path)
                (fail (str "use a real directory for Mojo output: " path)))
              (fs/create-dirs path)
              path))
          root ["tmp" folder]))

(defn scratch [directory prefix]
  (fs/create-temp-dir {:dir directory :prefix prefix}))

(defn option-value [option value]
  (when (or (nil? value) (.startsWith value "-"))
    (fail (str option " requires a value")))
  value)
