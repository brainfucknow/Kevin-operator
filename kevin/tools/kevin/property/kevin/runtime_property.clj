(ns kevin.runtime-property
  (:require [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [speclj.core :refer [describe it should]]
            [kevin.runtime :as runtime]
            [kevin.property-support :refer [passes? sentence]]))

(def ^:private numbers (gen/vector (gen/choose 0 120) 3))

(defn- dotted [parts] (str/join "." parts))

(def ^:private version (gen/fmap dotted numbers))

(describe "older?"
  (it "orders versions as their major, minor and patch numbers"
    (should (passes? (prop/for-all [a numbers b numbers]
                       (= (neg? (compare a b)) (runtime/older? (dotted a) (dotted b)))))))

  (it "is a strict order: never both ways, never a version against itself"
    (should (passes? (prop/for-all [a version b version]
                       (and (not (runtime/older? a a))
                            (not (and (runtime/older? a b) (runtime/older? b a)))))))))

(describe "project-refusal"
  (it "refuses exactly the runtimes older than the minimum line, wherever the line is"
    (should (passes? (prop/for-all [minimum version running version before sentence after sentence]
                       (let [text (str before "\nMinimum Kevin version: " minimum "\n" after)]
                         (= (runtime/older? running minimum)
                            (some? (runtime/project-refusal text running))))))))

  (it "reads the minimum back from the line it is written in"
    (should (passes? (prop/for-all [minimum version]
                       (= minimum (runtime/minimum-value (str runtime/minimum-label " " minimum))))))))
