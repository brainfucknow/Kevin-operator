(ns kevin.exclude-spec
  (:require [babashka.fs :as fs]
            [speclj.core :refer [describe it should= around]]
            [kevin.exclude :as exclude]
            [kevin.repo-support :refer [*dir* with-repo]]))

(defn- text [] (slurp (str (fs/path *dir* ".git" "info" "exclude"))))

(describe "the shared exclude file"
  (around [it] (with-repo it))

  (it "gains each missing line once, after the text it had"
    (spit (str (fs/path *dir* ".git" "info" "exclude")) "# git's comment\n/a")
    (exclude/add! *dir* ["/a" "/b" "/b"])
    (exclude/add! *dir* ["/b"])
    (should= "# git's comment\n/a\n/b\n" (text)))

  (it "is created when it is missing"
    (fs/delete-if-exists (fs/path *dir* ".git" "info" "exclude"))
    (exclude/add! *dir* ["/a"])
    (should= "/a\n" (text)))

  (it "loses only the named lines"
    (spit (str (fs/path *dir* ".git" "info" "exclude")) "/a\n  /b\n/c\n")
    (exclude/remove! *dir* ["/b" "/d"])
    (should= "/a\n/c\n" (text))
    (should= ["/a" "/c"] (exclude/lines *dir*))))
