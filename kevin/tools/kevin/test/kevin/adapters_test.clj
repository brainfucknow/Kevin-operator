(ns kevin.adapters-test
  "Tests for kevin.adapters."
  (:require [clojure.test :refer [deftest is testing]]
            [kevin.adapters :as adapters]))

(deftest code-point-count-test
  (testing "code-point-count counts Unicode code points, not UTF-16 code units"
    (is (= 1 (adapters/code-point-count "😀")) "emoji is 1 code point")
    (is (= 1 (adapters/code-point-count "a")) "ASCII char is 1 code point")
    (is (= 3 (adapters/code-point-count "a😀b")) "mixed string has correct count")
    (is (= 2 (adapters/code-point-count "👨👩")) "family emoji is 2 code points")))
