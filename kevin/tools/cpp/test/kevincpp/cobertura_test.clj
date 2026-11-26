(ns kevincpp.cobertura-test
  (:require [clojure.test :refer [deftest is testing]]
            [kevincpp.cobertura :as cob]))

(def gcovr
  (str "<?xml version='1.0'?>\n<!DOCTYPE coverage SYSTEM 'http://cobertura.sourceforge.net/xml/coverage-04.dtd'>\n"
       "<coverage><sources><source>/repo</source></sources><packages><package name=\"src\"><classes>"
       "<class name=\"sign_cpp\" filename=\"src/sign.cpp\"><methods/><lines>"
       "<line number=\"2\" hits=\"1\"/><line number=\"5\" hits=\"0\"/></lines></class>"
       "<class name=\"sign_cpp2\" filename=\"src/sign.cpp\"><lines>"
       "<line number=\"5\" hits=\"3\"/><line number=\"8\" hits=\"0\"/></lines></class>"
       "</classes></package></packages></coverage>"))

(def opencppcoverage
  (str "<coverage><sources><source>C:</source></sources><packages><package name=\"x\"><classes>"
       "<class name=\"a\" filename=\"Users\\me\\Repo\\src\\A.cpp\"><lines><line number=\"3\" hits=\"2\"/></lines></class>"
       "</classes></package></packages></coverage>"))

(deftest line-hits-merge-per-repository-file
  (is (= {"src/sign.cpp" {2 1 5 3 8 0}}
         (cob/file-lines (cob/parse gcovr) "/repo" #{"src/sign.cpp"}))))

(deftest windows-paths-resolve-against-the-source-ignoring-case
  (is (= {"src/A.cpp" {3 2}}
         (cob/file-lines (cob/parse opencppcoverage) "c:/users/me/repo" #{"src/A.cpp"})))
  (testing "an absolute filename ignores the sources"
    (is (= {"src/b.cpp" {1 0}}
           (cob/file-lines (cob/parse (str "<coverage><sources><source>D:\\elsewhere</source></sources><packages><package><classes>"
                                           "<class filename=\"C:\\repo\\src\\b.cpp\"><lines><line number=\"1\" hits=\"0\"/></lines></class>"
                                           "</classes></package></packages></coverage>"))
                           "C:/repo" #{"src/b.cpp"})))))

(deftest a-file-outside-the-known-set-keeps-its-relative-path
  (is (= {"lib/other.cpp" {1 1}}
         (cob/file-lines (cob/parse (str "<coverage><sources><source>/repo</source></sources><packages><package><classes>"
                                         "<class filename=\"lib/other.cpp\"><lines><line number=\"1\" hits=\"1\"/></lines></class>"
                                         "</classes></package></packages></coverage>"))
                         "/repo" #{}))))

(deftest packages-tell-whether-the-backend-collected-anything
  (is (cob/packages? (cob/parse gcovr)))
  (is (not (cob/packages? (cob/parse "<coverage><sources/><packages/></coverage>"))))
  (is (thrown-with-msg? Exception #"expected a Cobertura" (cob/parse "<html/>")))
  (is (thrown-with-msg? Exception #"not readable" (cob/parse "not xml"))))
