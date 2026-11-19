(ns sfcore.changes
  "Changed-line sets: reading them from a unified diff and asking whether a
  tool-reported file and line range touch one. sfcore.git collects them."
  (:require [clojure.string :as str]))

(defn- hunk-header [line]
  (re-find #"^@@ -\d+(?:,(\d+))? \+(\d+)(?:,(\d+))? @@" line))

(defn- advance-line [state]
  (-> state (update :pending-new dec) (update :cursor inc)))

(defn- note-addition [{:keys [current cursor] :as state}]
  (cond-> (-> state advance-line (assoc :deletion-anchors nil))
    current (update-in [:result current] (fnil conj (sorted-set)) cursor)))

(defn- start-file [state raw]
  (let [name (str/replace (subs raw 4) #"\t.*$" "")
        file (when-not (= name "/dev/null") (str/replace name #"^b/" ""))]
    (cond-> (assoc state :current file)
      file (update :result #(if (contains? % file) % (assoc % file (sorted-set)))))))

(defn- note-deletion [{:keys [cursor deletion-anchors] :as state}]
  (cond-> (update state :pending-old dec)
    deletion-anchors (update :deletion-anchors conj cursor)))

(defn- hunk-open? [{:keys [pending-old pending-new]}]
  (or (pos? pending-old) (pos? pending-new)))

(defn- read-hunk-line [state raw]
  (let [{:keys [current deletion-anchors] :as next-state}
        (case (first raw)
          \+ (note-addition state)
          \- (note-deletion state)
          \space (-> state advance-line (update :pending-old dec))
          state)]
    (cond-> next-state
      (and (not (hunk-open? next-state)) current (seq deletion-anchors))
      (update-in [:result current] into deletion-anchors))))

(defn- read-header-line [state raw]
  (if-let [[_ old-count new-start new-count] (hunk-header raw)]
    (let [start (parse-long new-start)
          pending-new (if new-count (parse-long new-count) 1)]
      (assoc state
             :pending-old (if old-count (parse-long old-count) 1)
             :pending-new pending-new
             :cursor (if (zero? pending-new) (inc start) start)
             :deletion-anchors #{}))
    state))

(defn- read-diff-line [state raw]
  (cond
    (hunk-open? state) (read-hunk-line state raw)
    (str/starts-with? raw "+++ ") (start-file state raw)
    :else (read-header-line state raw)))

(defn changed-lines-from-diff
  "Reads unified git diff output into a map from new-side path to the sorted set
  of line numbers that were added or rewritten, plus the line that follows
  each deletion in a hunk without additions."
  [text]
  (:result (reduce read-diff-line
                   {:current nil :pending-old 0 :pending-new 0 :result {}}
                   (str/split-lines text))))

(defn relative-path
  "Normalizes a path reported by a tool (absolute, relative, Windows) to one
  relative to `root` when it lies under it, with forward slashes."
  [reported root]
  (let [slashes #(str/replace % "\\" "/")
        prefix (when root (str (str/replace (slashes root) #"/+$" "") "/"))
        candidate (slashes reported)
        candidate (if (and prefix (str/starts-with? candidate prefix)) (subs candidate (count prefix)) candidate)]
    (str/replace candidate #"^\./" "")))

(defn to-changed-path
  "Normalizes a path reported by a tool (absolute, relative, Windows) to a
  repo-relative changed path, or nil when it is not a changed file."
  [reported changed root]
  (let [candidate (relative-path reported root)]
    (if (contains? changed candidate)
      candidate
      (some (fn [file] (when (str/ends-with? candidate (str "/" file)) file)) (keys changed)))))

(defn overlaps? [file start end changed]
  (boolean (when-let [lines (get changed file)]
             (some lines (range start (inc end))))))
