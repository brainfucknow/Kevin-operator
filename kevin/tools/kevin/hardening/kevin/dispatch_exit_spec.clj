(ns kevin.dispatch-exit-spec
  (:require [babashka.process :as p]
            [speclj.core :refer [around describe it should=]]
            [kevin.dispatch :as dispatch]
            [kevin.repo-support :refer [with-repo]]
            [kevin.shell :as shell]
            [kevin.task :as task]))

(describe "dispatch"
  (around [it] (with-repo it))

  (it "asks the backend run to return its exit status instead of throwing"
    (let [id (shell/chomp (with-out-str (task/new-task ["Add login"])))
          real p/shell
          options (atom nil)]
      (with-redefs [p/shell (fn [opts]
                              (if (= "git" (first (:cmd opts)))
                                (real opts)
                                (do (reset! options opts) {:exit 0})))]
        (dispatch/dispatch ["coder" id "claude"]))
      (should= true (:continue @options)))))
