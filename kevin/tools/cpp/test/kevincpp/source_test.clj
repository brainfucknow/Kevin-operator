(ns kevincpp.source-test
  (:require [clojure.test :refer [deftest is]]
            [kevincpp.lizard :as lizard]
            [kevincpp.source :as source]))

(deftest sources-are-c-and-cpp-files-and-headers
  (is (every? source/source? ["a.c" "b.cc" "c.cpp" "d.cxx" "e.c++" "f.h" "g.hh" "h.hpp" "i.hxx" "j.ixx" "k.inl" "L.CPP"]))
  (is (not-any? source/source? ["a.cs" "b.txt" "CMakeLists.txt" "c.cppx"])))

(deftest test-directories-and-tmp-are-left-out-unless-tests-are-included
  (is (source/measured? "src/sign.cpp" false))
  (is (not (source/measured? "test/sign_test.cpp" false)))
  (is (not (source/measured? "lib/Tests/x.cpp" false)))
  (is (source/measured? "test/sign_test.cpp" true))
  (is (not (source/measured? "tmp/build/x.cpp" true)))
  (is (source/measured? "src/tmp/x.cpp" false))
  (is (source/measured? "src/testing.cpp" false))
  (is (source/test-path? "a\\tests\\b.cpp")))

(deftest lizard-csv-rows-become-functions
  (is (= [{:file "src/sign.cpp" :name "sign" :complexity 3 :line 2 :last-line 9}
          {:file "src/x.hpp" :name "a::S::f" :complexity 2 :line 2 :last-line 4}]
         (lizard/parse-csv (str "8,3,34,1,8,\"sign@2-9@src/sign.cpp\",\"src/sign.cpp\",\"sign\",\"sign( int x)\",2,9\n"
                                "\n"
                                "1,2,15,2,1,\"a::S::f@2-4@src/x.hpp\",\"src/x.hpp\",\"a::S::f\",\"a::S::f( int x , int \"\"y\"\")\",2,4\n"
                                "garbage\n")))))
