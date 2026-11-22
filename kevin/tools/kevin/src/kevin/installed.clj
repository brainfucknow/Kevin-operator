(ns kevin.installed
  "Where the installed Kevin runtime is and its version. bin/kevin sets the
  kevin.home and kevin.launcher properties; a kevin loaded from source without
  bin/kevin finds its runtime from its own resources. It depends on nothing
  else in kevin."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.io :as io]))

(defn- resource-home
  "The kevin/ directory that holds the kevin/commit-msg resource, for a kevin
  loaded from source without bin/kevin: <home>/tools/kevin/resources/kevin/commit-msg."
  []
  (when-let [hook (io/resource "kevin/commit-msg")]
    (str (nth (iterate fs/parent (fs/absolutize (.getPath hook))) 5))))

(defn home
  "The absolute kevin/ directory of the runtime. bin/kevin sets it from its own
  canonical path."
  []
  (or (System/getProperty "kevin.home") (resource-home)))

(defn launcher
  "The absolute path that bin/kevin was run by, links kept, so a hook or
  wrapper that runs it follows a link that a plugin update moves."
  []
  (or (System/getProperty "kevin.launcher") (str (fs/path (home) "bin" "kevin"))))

(defn manifest
  "The runtime's Claude Code plugin manifest, which holds its version."
  []
  (str (fs/path (home) ".." ".claude-plugin" "plugin.json")))

(defn version
  "The \"version\" of the runtime's plugin manifest, or nil when it has none."
  []
  (let [file (manifest)]
    (when (fs/exists? file)
      (get (json/parse-string (slurp file)) "version"))))
