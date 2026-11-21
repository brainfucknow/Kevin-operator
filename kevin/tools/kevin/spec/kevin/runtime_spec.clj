(ns kevin.runtime-spec
  (:require [babashka.fs :as fs]
            [clj-yaml.core :as yaml]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [speclj.core :refer [describe it should should-not should= should-be-nil]]
            [kevin.runtime :as runtime]))

(def ^:private kevin-home
  (fs/parent (fs/parent (fs/parent (fs/absolutize (.getPath (io/resource "kevin/shell.clj")))))))

(defn- ci-bb-version
  "The BB_VERSION the CI job named job installs."
  [job]
  (let [workflow (yaml/parse-string (slurp (str (fs/path kevin-home ".." ".." ".." ".github" "workflows" "test.yml"))))]
    (some #(get-in % [:env :BB_VERSION]) (get-in workflow [:jobs job :steps]))))

(describe "minimum"
  (it "is the Babashka the kevin-min-bb CI job tests on"
    (should= "1.12.216" runtime/minimum)
    (should= runtime/minimum (str (ci-bb-version :kevin-min-bb))))

  (it "is the :min-bb-version of the kevin bb.edn"
    (should= runtime/minimum (:min-bb-version (edn/read-string (slurp (str (fs/path kevin-home "bb.edn")))))))

  (it "is no newer than the Babashka of the main CI job"
    (should-not (runtime/too-old? (str (ci-bb-version :kevin))))))

(describe "too-old?"
  (it "refuses a Babashka older than the minimum"
    (should (runtime/too-old? "1.12.215"))
    (should (runtime/too-old? "1.12.215.9"))
    (should (runtime/too-old? "1.12.214"))
    (should (runtime/too-old? "1.11.300"))
    (should (runtime/too-old? "1.3.191")))

  (it "accepts the minimum and every later version"
    (should-not (runtime/too-old? "1.12.216"))
    (should-not (runtime/too-old? "1.12.216-SNAPSHOT"))
    (should-not (runtime/too-old? "1.13.229"))
    (should-not (runtime/too-old? "2.0.0")))

  (it "compares the numbers, not the text"
    (should-not (runtime/too-old? "1.100.0"))
    (should (runtime/too-old? "0.999.999")))

  (it "reads a missing part as 0"
    (should (runtime/too-old? "1.12"))
    (should-not (runtime/too-old? "1.13"))))

(describe "refusal"
  (it "names the version and the minimum"
    (should= "kevin: Babashka 1.12.215 is too old; kevin needs 1.12.216 or newer: https://babashka.org"
             (runtime/refusal "1.12.215")))

  (it "is nil for a version kevin runs on"
    (should-be-nil (runtime/refusal runtime/minimum))))
