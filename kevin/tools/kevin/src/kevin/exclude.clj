(ns kevin.exclude
  "The shared exclude file of the main checkout, the file that
  git rev-parse --git-path info/exclude names: the one owner of its reads
  and writes."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kevin.shell :as shell]))

(defn- file [root]
  (fs/path (shell/chomp (shell/must {:dir (str root)} "git" "rev-parse" "--path-format=absolute" "--git-path" "info/exclude"))))

(defn- text [root]
  (let [file (file root)]
    (if (fs/exists? file) (slurp (str file)) "")))

(defn lines
  "The trimmed non-blank lines of the exclude file."
  [root]
  (remove str/blank? (map str/trim (str/split-lines (text root)))))

(defn- append-line [text line]
  (str text (when-not (or (str/blank? text) (str/ends-with? text "\n")) "\n") line "\n"))

(defn add!
  "Adds each line that the exclude file lacks."
  [root wanted]
  (let [old (text root)
        present (set (map str/trim (str/split-lines old)))
        missing (distinct (remove present wanted))]
    (when (seq missing)
      (fs/create-dirs (fs/parent (file root)))
      (spit (str (file root)) (reduce append-line old missing)))))

(defn remove!
  "Removes each line of the exclude file that is one of unwanted."
  [root unwanted]
  (let [old (text root)
        unwanted (set unwanted)
        kept (remove #(unwanted (str/trim %)) (str/split-lines old))]
    (when (not= (count kept) (count (str/split-lines old)))
      (spit (str (file root)) (apply str (map #(str % "\n") kept))))))
