(ns kevin.options-handoff-spec
  (:require [speclj.core :refer [describe it should=]]
            [kevin.options :as options]))

(describe "allow-missing-tool"
  (it "finds the flag among other arguments and removes it"
    (should= [true ["x" "y"]] (mapv #(if (seq? %) (vec %) %) (options/allow-missing-tool ["x" "--allow-missing-tool" "y"])))
    (should= [false ["x"]] (mapv #(if (seq? %) (vec %) %) (options/allow-missing-tool ["x"])))))

(describe "handoff"
  (it "takes --to and its role from anywhere in the arguments and keeps the rest as the summary"
    (should= {:to "qa" :summary "before after" :allow? false}
             (options/handoff ["before" "--to" "qa" "after"]))
    (should= {:to "qa" :summary "done" :allow? true}
             (options/handoff ["done" "--allow-missing-tool" "--to" "qa"]))
    (should= {:to "coder" :summary nil :allow? false}
             (options/handoff ["--to" "coder"]))))
