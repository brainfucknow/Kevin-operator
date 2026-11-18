(ns sf.lore
  "Lore trailers (github.com/Ian-stetsenko/lore-protocol) on task commits: where
  the commit-msg hook puts the byline and the Lore-id, and the LORE: lines that
  sf start and sf show print."
  (:require [clojure.string :as str])
  (:import (java.security SecureRandom)))

(def ^:private lore-keys
  #{"Constraint" "Rejected" "Confidence" "Scope-risk" "Reversibility" "Directive"
    "Tested" "Not-tested" "Supersedes" "Depends-on" "Related" "Lore-id"})

(defn lore-key? [key]
  (contains? lore-keys key))

(defn trailer-key
  "The key of a `Key: value` trailer line, or nil."
  [line]
  (second (re-matches #"([^:]+):.*" line)))

(defn- byline [role]
  (str "By " role "."))

(defn- byline-role
  "The role in a `By <role>.` line, or nil."
  [line]
  (second (re-matches #"By (\S+)\." line)))

(def ^:private random (SecureRandom.))

(defn new-id
  "A random Lore-id: 8 lowercase hexadecimal digits."
  []
  (format "%08x" (bit-and (.nextLong random) 0xffffffff)))

(defn- head-and-block
  "The text before the last paragraph, and the last paragraph."
  [text]
  (let [lines (vec (str/split-lines text))
        gap (last (keep-indexed #(when (str/blank? %2) %1) lines))]
    [(str/join "\n" (subvec lines 0 gap))
     (str/join "\n" (subvec lines (inc gap)))]))

(defn sign
  "The commit message with the byline `By <role>.` and a Lore-id added.
  trailer-keys are the keys of the trailers that git reads from the message's
  last paragraph, empty when it has no trailer block. The byline goes in its own
  paragraph before that block, or at the end when there is none. A block with a
  Lore trailer and no Lore-id gets `Lore-id: <(make-id)>` as its first line. A
  message that needs neither is returned as it is."
  [message role trailer-keys make-id]
  (let [text (str/replace message #"\s+\z" "")
        signature (byline role)
        add-byline? (not-any? #{signature} (str/split-lines text))
        add-id? (and (some lore-key? trailer-keys) (not-any? #{"Lore-id"} trailer-keys))
        [head block] (if (seq trailer-keys) (head-and-block text) [text nil])]
    (if (or add-byline? add-id?)
      (str (str/join "\n\n" (remove nil? [head
                                          (when add-byline? signature)
                                          (if add-id? (str "Lore-id: " (make-id) "\n" block) block)]))
           "\n")
      message)))

(defn- comment-start
  "The index of the first line of git's comment block at the end of lines: the
  comment and blank lines there, and every line from the scissors line on."
  [lines comment-char]
  (let [scissors (str comment-char " ------------------------ >8 ------------------------")
        cut (or (first (keep-indexed #(when (= scissors %2) %1) lines)) (count lines))
        text? #(not (or (str/blank? %) (str/starts-with? % comment-char)))]
    (or (some-> (last (keep-indexed #(when (text? %2) %1) (subvec lines 0 cut))) inc) 0)))

(defn sign-commit
  "sign for the message file of git commit. When git opens an editor, the file
  ends in a comment block (lines that start with comment-char) that git strips
  after the commit-msg hook. The block stays at the end, so the trailer block
  above it stays last. A message with no text above the block is returned as it
  is, so git can still abort the commit for an empty message."
  [message comment-char role trailer-keys make-id]
  (let [lines (vec (str/split message #"\n" -1))
        start (comment-start lines comment-char)
        text (str/join "\n" (subvec lines 0 start))
        signed (sign text role trailer-keys make-id)]
    (if (or (str/blank? text) (= signed text))
      message
      (str signed (str/join "\n" (subvec lines start))))))

(defn- role-of
  "The role of the last `By <role>.` line, or -."
  [message]
  (or (last (keep byline-role (str/split-lines message))) "-"))

(defn- commit-trailers
  "The Lore trailers of commit as maps of :sha, :role, :key and :value."
  [{:keys [sha message trailers]}]
  (for [trailer trailers
        :let [key (trailer-key trailer)]
        :when (lore-key? key)]
    {:sha sha :role (role-of message) :key key :value (str/triml (subs trailer (inc (count key))))}))

(defn- line [{:keys [sha role key value]}]
  (str "LORE: " sha " " role " " key ": " value))

(defn lines
  "One `LORE: <sha> <role> <Key>: <value>` line per Lore trailer. commits are
  maps of :sha, :message, and :trailers (the unfolded trailer lines git reads)."
  [commits]
  (map line (mapcat commit-trailers commits)))

(def ^:private evidence-keys
  #{"Tested" "Not-tested" "Confidence" "Lore-id"})

(defn- values-of [key trailers]
  (for [trailer trailers :when (= key (:key trailer))] (str/trim (:value trailer))))

(defn entries
  "The Lore trailers of commits, in the order of their LORE: lines, as maps of
  :sha, :role, :key, :value and :lore-id, the Lore-id of their commit or nil.
  The Lore-id trailers themselves are left out."
  [commits]
  (for [trailers (map commit-trailers commits)
        :let [lore-id (first (values-of "Lore-id" trailers))]
        trailer trailers
        :when (not= "Lore-id" (:key trailer))]
    (assoc trailer :lore-id lore-id)))

(defn current
  "The commits, in order, whose Lore-id no Supersedes trailer of commits names."
  [commits]
  (let [superseded (set (mapcat #(values-of "Supersedes" (commit-trailers %)) commits))]
    (remove #(some superseded (values-of "Lore-id" (commit-trailers %))) commits)))

(defn brief
  "The LORE: lines of commits that sf start prints, as :lines, and how many of
  the lines that sf show prints it leaves out, as :omitted. It leaves out the
  evidence trailers (Tested, Not-tested, Confidence, Lore-id) and every trailer
  of a commit that is not current."
  [commits]
  (let [kept (for [commit (current commits)
                   trailer (commit-trailers commit)
                   :when (not (evidence-keys (:key trailer)))]
               trailer)]
    {:lines (map line kept)
     :omitted (- (count (mapcat commit-trailers commits)) (count kept))}))

(defn read-frames
  "The commits in NUL-framed git log -z output, each as [header-fields
  tail-fields]: a commit's header is the next n fields of the \\x00-split
  output, and take-tail reads the fields after the header, returning
  [tail-fields remaining-fields]. The final NUL leaves one empty trailing
  field; no commit header starts with a blank field, so the log ends there."
  [output n take-tail]
  (loop [fields (str/split output #"\x00" -1) frames []]
    (if (or (empty? fields) (str/blank? (first fields)) (< (count fields) n))
      frames
      (let [[header more] (split-at n fields)
            [tail fields] (take-tail more)]
        (recur fields (conj frames [header tail]))))))

(defn no-tail
  "A take-tail for output without a per-commit tail: the header ends the commit."
  [fields]
  [[] fields])

(def log-format
  "The git log -z --format that read-log reads: three NUL-terminated fields per
  commit. git keeps NUL out of a commit message, so no field can end early."
  "%h%x00%B%x00%(trailers:only,unfold)")

(defn read-log
  "The commits in git log -z output made with log-format: sha, message and
  trailers per commit."
  [output]
  (vec (for [[[sha message trailers] _tail] (read-frames output 3 no-tail)]
         {:sha sha :message message :trailers (vec (remove str/blank? (str/split-lines trailers)))})))
