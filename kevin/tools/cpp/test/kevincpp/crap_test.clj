(ns kevincpp.crap-test
  (:require [clojure.test :refer [deftest is testing]]
            [kevincpp.crap :as crap]))

(def sign {:file "src/sign.cpp" :name "sign" :complexity 3 :line 2 :last-line 9})

(deftest coverage-is-the-share-of-coverable-lines-in-the-span-that-ran
  (let [[unit] (crap/score [sign] {"src/sign.cpp" {1 1 2 1 3 1 5 0 8 0 10 1}})]
    (is (= 0.5 (:coverage unit)))
    (is (= (+ (* 9 0.125) 3) (:crap unit)))))

(deftest a-function-without-coverable-lines-counts-as-uncovered
  (is (= [12.0 0.0] ((juxt :crap :coverage) (first (crap/score [sign] {"src/sign.cpp" {20 1}})))))
  (is (= 12.0 (:crap (first (crap/score [sign] {}))))))

(deftest fully-covered-functions-score-their-complexity
  (is (= 3.0 (:crap (first (crap/score [sign] {"src/sign.cpp" {2 4 9 1}}))))))

(deftest test-sources-join-the-scope-only-when-the-report-has-them
  (let [candidates ["src/a.cpp" "test/a_test.cpp" "tests/b.cpp" "tmp/x.cpp" "README.md"]]
    (is (= ["src/a.cpp"] (crap/scope candidates {})))
    (is (= ["src/a.cpp" "test/a_test.cpp"] (crap/scope candidates {"test/a_test.cpp" {1 1}})))))

(deftest sources-without-coverage-data-are-unmeasured-unless-exempt
  (is (= ["src/b.hpp"] (crap/unmeasured ["src/a.cpp" "src/b.hpp" "src/c.h"] {"src/a.cpp" {1 0}} #{"src/c.h"})))
  (testing "a report entry with no lines is no data"
    (is (= ["src/a.cpp"] (crap/unmeasured ["src/a.cpp"] {"src/a.cpp" {}} #{})))))
