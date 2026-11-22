(ns kevin.hook
  "The commit-msg hook that adds the `By <role>.` byline and a Lore-id in task
  worktrees."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kevin.lore :as lore]
            [kevin.record :as record]
            [kevin.runtime :as runtime]
            [kevin.shell :as shell]
            [kevin.wrapper :as wrapper]))

(def marker "# kevin commit-msg hook")

(def ^:private legacy-marker
  "The marker of the hook that the old sf command wrote before the rename to kevin."
  "# swarmforge commit-msg hook")

(defn- own? [text]
  (or (str/includes? text marker) (str/includes? text legacy-marker)))

(defn worktree-dir []
  (shell/kevin-env "WORKTREE_DIR" ".worktrees"))

(defn- body []
  (-> (shell/template "commit-msg")
      (str/replace "{{marker}}" marker)
      (str/replace "{{worktree-dir}}" (worktree-dir))
      (str/replace "{{kevin}}" (wrapper/sq (runtime/launcher)))))

(defn install
  "Writes the hook unless core.hooksPath is set or another hook is in place."
  [root]
  (let [hooks-path (shell/chomp (:out (shell/run {:dir root} "git" "config" "--get" "core.hooksPath")))
        hooks (fs/path (shell/chomp (shell/must {:dir root} "git" "rev-parse" "--path-format=absolute" "--git-common-dir")) "hooks")
        hook (fs/path hooks "commit-msg")]
    (cond
      (seq hooks-path)
      (println "HOOK: core.hooksPath is set; run 'kevin commit-msg <role> <file>' on each commit message")

      (and (fs/exists? hook) (not (own? (slurp (str hook)))))
      (println "HOOK: existing commit-msg hook left alone; run 'kevin commit-msg <role> <file>' on each commit message")

      :else
      (do (fs/create-dirs hooks)
          (spit (str hook) (body))
          (fs/set-posix-file-permissions hook "rwxr-xr-x")))))

(defn- comment-char
  "git's core.commentChar; # when it is not set or is auto, as git
  interpret-trailers reads it."
  []
  (let [char (shell/chomp (:out (shell/run {} "git" "config" "--get" "core.commentChar")))]
    (if (or (str/blank? char) (= "auto" char)) "#" char)))

(defn commit-msg
  "Refuses a commit message in file whose trailer block has a Kevin-
  trailer; only kevin writes those. Otherwise adds the byline for role and a
  Lore-id, as kevin.lore/sign-commit does, except at stage done. git
  interpret-trailers finds the trailer block as git log reads it: a --- line in
  the message is text, not a patch divider."
  [[role file]]
  (when (or (str/blank? role) (str/blank? file))
    (shell/die "commit-msg needs a role and a message file"))
  (let [message (slurp file)
        trailers (shell/lines (shell/must {:in message} "git" "interpret-trailers" "--parse" "--no-divider"))
        keys (keep lore/trailer-key trailers)]
    (when-let [key (record/record-trailer keys)]
      (shell/die key " is a Kevin record trailer; only kevin writes those"))
    (when (not= "done" role)
      (let [signed (lore/sign-commit message (comment-char) role keys lore/new-id)]
        (when (not= signed message)
          (spit file signed))))))
