(ns kevin.spec-revisions-numstat-spec
  (:require [speclj.core :refer [describe it should=]]
            [kevin.spec-revisions :as spec-revisions]))

(describe "read-log"
  (it "reads each numstat row whole: a normal row, a rename's two paths, a binary row"
    (let [output (str "abc\u0000p\u0000msg\u0000\u0000"
                      "\n3\t1\ta.clj\u0000"
                      "0\t0\t\u0000old.txt\u0000new.txt\u0000"
                      "-\t-\tbin.png\u0000")]
      (should= [{:sha "abc" :parents ["p"] :message "msg" :trailers [] :numstat [["3" "1"] ["0" "0"] ["-" "-"]]}]
               (spec-revisions/read-log output)))))
