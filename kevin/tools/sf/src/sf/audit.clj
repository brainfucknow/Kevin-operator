(ns sf.audit
  "The audit gate of the roles that roles.conf flags `audit`: the first attempt
  to record a commit is refused with a self-review; the next attempt at the
  same commit goes through."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [sf.config :as config]
            [sf.flow :as flow]
            [sf.shell :as shell]
            [sf.store :as store]))

(defn- refusal [id head]
  (str "AUDIT_REQUIRED: " id " at " head "; nothing was recorded
Before running this command again:
- Re-read the task as kevin start prints it: the request, every answer, and the record you received.
- Check every requirement and constraint against the committed work, including boundaries, failure cases, and what must not happen.
- For each requirement, name the test or check that covers it, or call it a gap. Passing tools alone do not make the task complete.
- Read the whole diff since the previous record (git diff " (flow/last-milestone-commit (store/records-at (store/ref-of id) id)) "..HEAD), the tests, and anything generated or left in the working tree.
- Fix every finding, commit, rerun the checks, then run this command again. It records the handoff when HEAD is still " head "; a new commit starts a new audit."))

(defn- task-paths
  "The task's pending audit directory, one file per audited role, and its
  legacy place."
  [id]
  (shell/state-paths (shell/repo-root) "audit" id))

(defn- role-paths [id role]
  (map #(fs/path % role) (task-paths id)))

(defn- drop-roleless!
  "Deletes a pending file an earlier kevin wrote for the whole task. It names no
  role, so it counts for none."
  [id]
  (doseq [path (task-paths id) :when (fs/regular-file? path)]
    (fs/delete path)))

(defn- pending
  "The :head the gate refused last and the number of :refusals since the
  role's last audited record, from the first of the role's pending audit files
  that exists. A file that holds only the head stands for one refusal."
  [paths]
  (if-let [path (first (filter fs/exists? paths))]
    (let [[head refusals] (str/split-lines (slurp (str path)))]
      {:head (str/trim (or head "")) :refusals (or (some-> refusals str/trim parse-long) 1)})
    {:head nil :refusals 0}))

(defn gate
  "nil when role does not audit. Otherwise stops with exit 3 when it refuses
  role's attempt to record head, or returns the number of role's refusals the
  record carries. The count stays pending until clear! runs for role after
  the record is written."
  [id role head]
  (when (config/role-audits? role)
    (drop-roleless! id)
    (let [paths (role-paths id role)
          path (first paths)
          {:keys [refusals] refused :head} (pending paths)]
      (if (= head refused)
        refusals
        (do (fs/create-dirs (fs/parent path))
            (spit (str path) (str head "\n" (inc refusals) "\n"))
            (println (refusal id head))
            (shell/fail 3 nil))))))

(defn clear!
  "Removes role's pending audit state once its record is written, and the
  task's audit directory once no role is pending."
  [id role]
  (run! fs/delete-if-exists (role-paths id role))
  (doseq [dir (task-paths id)
          :when (and (fs/directory? dir) (empty? (fs/list-dir dir)))]
    (fs/delete dir)))
