(ns kevin.installed-spec
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [speclj.core :refer [describe it should= should-be-nil]]
            [kevin.installed :as installed]))

(def ^:private kevin-home
  (fs/parent (fs/parent (fs/parent (fs/absolutize (.getPath (io/resource "kevin/shell.clj")))))))

(describe "home"
  (it "is the kevin/ directory of this source when bin/kevin did not set it"
    (should= (str (fs/canonicalize kevin-home)) (str (fs/canonicalize (fs/path (installed/home) "tools" "kevin")))))

  (it "is the kevin.home property when bin/kevin set it"
    (let [before (System/getProperty "kevin.home")]
      (try (System/setProperty "kevin.home" "/opt/kevin/kevin")
           (should= "/opt/kevin/kevin" (installed/home))
           (finally (if before (System/setProperty "kevin.home" before) (System/clearProperty "kevin.home")))))))

(describe "launcher"
  (it "is bin/kevin of the runtime when bin/kevin did not set it"
    (when-not (System/getProperty "kevin.launcher")
      (should= (str (fs/path (installed/home) "bin" "kevin")) (installed/launcher)))))

(describe "version"
  (it "is the version of the runtime's Claude Code plugin manifest"
    (let [manifest (json/parse-string (slurp (str (fs/path kevin-home ".." ".." ".." ".claude-plugin" "plugin.json"))))]
      (should= (get manifest "version") (installed/version))))

  (it "is nil without a manifest"
    (with-redefs [installed/manifest (constantly "/no/such/plugin.json")]
      (should-be-nil (installed/version)))))
