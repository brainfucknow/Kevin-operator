(ns sf.spec-revisions-history-spec
  (:require [babashka.fs :as fs]
            [speclj.core :refer [describe it should= around]]
            [sf.history :as history]
            [sf.spec-revisions :as spec-revisions]
            [sf.shell :as shell]))

(def ^:dynamic *dir* nil)

(defn- git [& args]
  (shell/chomp (apply shell/must {:dir *dir*} "git" "-c" "user.name=t" "-c" "user.email=t@x" args)))

(defn- commit-lines! [file n message]
  (spit (str (fs/path *dir* file)) (apply str (repeat n "x\n")))
  (git "add" file)
  (git "commit" "-q" "-m" message)
  (git "rev-parse" "HEAD"))

(def review {:record "review" :stage "specifier"})

(def ^:private spec-commits @#'sf.history/spec-commits)

(defn- commit-message!
  "Commits file with n lines under message, and returns the commit's short sha."
  [file n message]
  (git "rev-parse" "--short" (commit-lines! file n message)))

(describe "history spec-revisions"
  (around [it]
    (let [dir (str (fs/create-temp-dir {:prefix "spec-revisions-history-spec-"}))]
      (try
        (binding [*dir* dir]
          (git "init" "-q" "-b" "main")
          (git "commit" "-q" "--allow-empty" "-m" "Base")
          (with-redefs [shell/repo-root (constantly dir)] (it)))
        (finally (fs/delete-tree dir)))))

  (it "sizes each spec revision at its reviewed commit over the commits after created, and limits the newest"
    (let [base (git "rev-parse" "HEAD")
          a (commit-lines! "a.txt" 4 "Spec A")
          b (commit-lines! "b.txt" 2 "Spec B")
          records [{:record "created" :commit base}
                   (assoc review :reviewed a)
                   {:record "changes-requested" :spec-verdict "continue"}
                   (assoc review :reviewed b)]
          {found :spec-revisions limits :limits} (history/spec-revisions records)]
      (should= [5 8] (map :size found))
      (should= [5 5] (map :base found))
      (should= [(spec-revisions/growth 5 5) (spec-revisions/growth 8 5)] (map :growth found))
      (should= [(git "rev-parse" "--short" a) (git "rev-parse" "--short" b)] (map :short found))
      (should= (spec-revisions/limits 1 8 5) limits)))

  (it "resets the baseline on an operator changes-requested, so the newest revision carries the warning condition at 0% growth"
    (let [base (git "rev-parse" "HEAD")
          a (commit-lines! "a.txt" 4 "Spec A")
          b (commit-lines! "b.txt" 2 "Spec B")
          records [{:record "created" :commit base}
                   (assoc review :reviewed a)
                   {:record "changes-requested" :from "operator"}
                   (assoc review :reviewed b)]
          {found :spec-revisions limits :limits} (history/spec-revisions records)
          newest (peek found)]
      (should= [5 8] (map :size found))
      (should= [1 2] (map :baseline found))
      (should= "0%" (:growth newest))
      (should= "60%" (:total newest))
      (should= [] limits)
      (should= ["WARNING: total growth 60% over spec revision 1 (5); more than half over across operator send-backs"]
               (spec-revisions/warnings found))))

  (it "has no spec revisions and no limits before the first review"
    (should= {:spec-revisions [] :limits []} (history/spec-revisions [{:record "created" :commit (git "rev-parse" "HEAD")}])))

  (it "counts every commit up to reviewed when no created record is readable"
    (let [a (commit-lines! "a.txt" 4 "Spec A")]
      (should= [6] (map :size (:spec-revisions (history/spec-revisions [(assoc review :reviewed a)]))))))

  (it "reads commits whose messages hold 0x1f and 0x1e whole, with their numstat"
    (let [base (git "rev-parse" "--short" "HEAD")
          a (commit-message! "a.txt" 2 "Spec\u001fwith 0x1f\n\nBy specifier.\n\nTested: whole\n")
          b (commit-message! "b.txt" 1 "More\u001ewith 0x1e\n\nBy coder.\n")
          commits (spec-commits base b)]
      (should= [b a] (map :sha commits))
      (should= [[a] [base]] (map :parents commits))
      (should= ["More\u001ewith 0x1e\n\nBy coder.\n" "Spec\u001fwith 0x1f\n\nBy specifier.\n\nTested: whole\n"]
               (map :message commits))
      (should= [[] ["Tested: whole"]] (map :trailers commits))
      (should= [[["1" "0"]] [["2" "0"]]] (map :numstat commits)))))
