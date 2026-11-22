(ns kevin.runtime-spec
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
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

(describe "older?"
  (it "compares major, minor and patch by number"
    (should (runtime/older? "0.2.0" "0.3.0"))
    (should (runtime/older? "0.2.0" "0.2.1"))
    (should-not (runtime/older? "0.2.0" "0.2.0"))
    (should-not (runtime/older? "0.10.0" "0.9.1"))))

(describe "home"
  (it "is the kevin/ directory of this source when bin/kevin did not set it"
    (should= (str (fs/canonicalize kevin-home)) (str (fs/canonicalize (fs/path (runtime/home) "tools" "kevin")))))

  (it "is the kevin.home property when bin/kevin set it"
    (let [before (System/getProperty "kevin.home")]
      (try (System/setProperty "kevin.home" "/opt/kevin/kevin")
           (should= "/opt/kevin/kevin" (runtime/home))
           (finally (if before (System/setProperty "kevin.home" before) (System/clearProperty "kevin.home")))))))

(describe "launcher"
  (it "is bin/kevin of the runtime when bin/kevin did not set it"
    (when-not (System/getProperty "kevin.launcher")
      (should= (str (fs/path (runtime/home) "bin" "kevin")) (runtime/launcher)))))

(describe "version"
  (it "is the version of the runtime's Claude Code plugin manifest"
    (let [manifest (json/parse-string (slurp (str (fs/path kevin-home ".." ".." ".." ".claude-plugin" "plugin.json"))))]
      (should= (get manifest "version") (runtime/version))))

  (it "is nil without a manifest"
    (with-redefs [runtime/manifest (constantly "/no/such/plugin.json")]
      (should-be-nil (runtime/version)))))

(describe "project-refusal"
  (it "refuses a runtime older than the project's minimum"
    (should= "this project needs Kevin 0.3.0 or newer; this runtime is 0.2.0"
             (runtime/project-refusal "# Mine\nMinimum Kevin version: 0.3.0\n" "0.2.0")))

  (it "accepts a runtime at or above the minimum"
    (should-be-nil (runtime/project-refusal "Minimum Kevin version: 0.2.0" "0.2.0"))
    (should-be-nil (runtime/project-refusal "Minimum Kevin version: 0.9.1" "0.10.0")))

  (it "sets no minimum without the line or the file"
    (should-be-nil (runtime/project-refusal "# Mine\n" "0.1.0"))
    (should-be-nil (runtime/project-refusal nil "0.1.0")))

  (it "refuses a minimum that is not three numbers"
    (should= "kevin/project.md: Minimum Kevin version must be <major>.<minor>.<patch>, not 'soon'"
             (runtime/project-refusal "Minimum Kevin version: soon" "0.2.0"))
    (should= "kevin/project.md: Minimum Kevin version must be <major>.<minor>.<patch>, not '1.0'"
             (runtime/project-refusal "Minimum Kevin version: 1.0" "0.2.0")))

  (it "refuses a runtime with no version when the project sets a minimum"
    (should= "this project needs Kevin 0.1.0 or newer; this runtime is of unknown version"
             (runtime/project-refusal "Minimum Kevin version: 0.1.0" nil))))
