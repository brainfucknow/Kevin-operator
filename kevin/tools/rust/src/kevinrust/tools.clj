(ns kevinrust.tools
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [kevincore.outcome :as outcome]))

(def jscpd ["npx" "--yes" "jscpd@4.3.0"])

(def ^:private probes
  [["Cargo" ["cargo" "--version"]]
   ["rustc" ["rustc" "--version"]]
   ["cargo-llvm-cov" ["cargo" "llvm-cov" "--version"]]
   ["cargo-crap" ["cargo" "crap" "--version"]]
   ["cargo-mutants" ["cargo" "mutants" "--version"]]
   ["Node.js" ["node" "--version"]]
   ["npm" ["npm" "--version"]]
   ["npx" ["npx" "--version"]]
   ["jscpd" (conj jscpd "--version")]
   ["Semgrep launcher (uvx)" ["uvx" "--version"]]
   ["cargo-audit" ["cargo" "audit" "--version"]]
   ["cargo-deny" ["cargo" "deny" "--version"]]])

(defn- probe [[program & args]]
  (when (fs/which program)
    (let [{:keys [out exit]} (apply process/shell
                                  {:out :string :err :string :continue true}
                                  program args)]
      (when (zero? exit)
        (str/trim out)))))

(defn- llvm-executable? [bin [env name]]
  (if-let [override (System/getenv env)]
    (or (fs/executable? override) (fs/which override))
    (or (fs/executable? (fs/path bin name))
        (fs/executable? (fs/path bin (str name ".exe"))))))

(defn- llvm-tools []
  (when-let [target-libdir (probe ["rustc" "--print" "target-libdir"])]
    (let [bin (fs/path (fs/parent target-libdir) "bin")]
      (when (every? #(llvm-executable? bin %)
                    [["LLVM_COV" "llvm-cov"] ["LLVM_PROFDATA" "llvm-profdata"]])
        "llvm-cov and llvm-profdata"))))

(defn- available [label]
  (if (= label "llvm-tools")
    (llvm-tools)
    (probe (get (into {} probes) label))))

(defn require-tools [command labels]
  (doseq [label labels]
    (when-not (available label)
      (throw (outcome/usage-error (str command " requires " label
                                       ". Run kevin rust tools"))))))

(defn show []
  (let [installed (mapv (fn [[label]] [label (available label)]) probes)
        labels (conj installed ["llvm-tools" (llvm-tools)])]
    (doseq [[label version] labels]
      (println (str label ": " (or version "unavailable"))))
    (if (every? second (take 2 installed)) 0 2)))
