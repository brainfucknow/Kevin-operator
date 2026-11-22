(ns kevin.architecture-spec
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [speclj.core :refer [describe it should= should-be]]))

(defn- source-path [ns-name]
  (str (.replace (.replace (name ns-name) "-" "_") "." "/") ".clj"))

(defn- required
  "The namespaces the ns form of the source file for ns-name requires."
  [ns-name]
  (let [[_ _ & clauses] (read-string (slurp (io/resource (source-path ns-name))))]
    (set (for [clause clauses
               :when (and (sequential? clause) (= :require (first clause)))
               spec (rest clause)]
           (if (sequential? spec) (first spec) spec)))))

(defn- kevin-required [ns-name]
  (set (filter #(.startsWith (name %) "kevin.") (required ns-name))))

(def ^:private io-namespaces
  '#{babashka.fs babashka.process clojure.java.io clojure.java.shell kevin.shell})

(def ^:private pure
  "The high-level namespaces: plain values in and out, no IO."
  '#{kevin.lore kevin.record kevin.flow kevin.spec-revisions kevin.harvest kevin.feature-block kevin.coverage-args kevin.catalogue kevin.missing-tools})

(def ^:private allowed
  "The kevin namespaces each kevin namespace may require. Dependencies point from the
  IO-near namespaces toward the pure ones; nothing requires kevin.cli."
  '{kevin.shell #{}
    kevin.runtime #{}
    kevin.lore #{}
    kevin.record #{}
    kevin.flow #{kevin.record}
    kevin.catalogue #{}
    kevin.spec-revisions #{kevin.lore kevin.record}
    kevin.harvest #{kevin.flow kevin.lore kevin.record}
    kevin.config #{kevin.catalogue kevin.flow kevin.runtime kevin.shell}
    kevin.entry-point #{}
    kevin.maven-tool #{kevin.shell}
    kevin.wrapper #{kevin.shell}
    kevin.clone #{kevin.shell}
    kevin.tool #{kevin.maven-tool kevin.catalogue kevin.clone kevin.config kevin.entry-point kevin.runtime kevin.shell kevin.wrapper}
    kevin.adapters #{kevin.config kevin.runtime kevin.shell}
    kevin.git #{kevin.shell}
    kevin.store #{kevin.git kevin.record kevin.shell}
    kevin.metrics #{kevin.git kevin.shell}
    kevin.verify #{kevin.shell}
    kevin.feature-block #{}
    kevin.gherkin-cache #{kevin.feature-block kevin.shell}
    kevin.coverage-args #{}
    kevin.coverage-rewrite #{kevin.coverage-args kevin.shell kevin.wrapper}
    kevin.hook #{kevin.lore kevin.record kevin.runtime kevin.shell kevin.wrapper}
    kevin.options #{kevin.record kevin.shell}
    kevin.audit #{kevin.config kevin.flow kevin.shell kevin.store}
    kevin.history #{kevin.flow kevin.lore kevin.record kevin.spec-revisions kevin.shell kevin.store}
    kevin.dispatch #{kevin.config kevin.flow kevin.record kevin.runtime kevin.shell kevin.store kevin.task}
    kevin.missing-tools #{kevin.record}
    kevin.task #{kevin.audit kevin.config kevin.flow kevin.git kevin.harvest kevin.history kevin.hook kevin.lore kevin.metrics kevin.missing-tools kevin.options kevin.record kevin.runtime kevin.spec-revisions kevin.shell kevin.store kevin.tool kevin.verify}
    kevin.convert #{kevin.adapters kevin.config kevin.runtime kevin.shell kevin.task}
    kevin.cli #{kevin.adapters kevin.config kevin.convert kevin.dispatch kevin.hook kevin.options kevin.runtime kevin.shell kevin.task kevin.tool}})

(defn- source-namespaces []
  (let [dir (fs/parent (fs/path (io/resource (source-path 'kevin.cli))))]
    (set (for [file (fs/glob dir "*.clj")]
           (symbol (str "kevin." (.replace (fs/strip-ext (fs/file-name file)) "_" "-")))))))

(defn- reachable
  "The namespaces that ns-name reaches through allowed."
  [ns-name]
  (loop [seen #{} todo (allowed ns-name)]
    (if-let [[next & more] (seq todo)]
      (if (seen next)
        (recur seen more)
        (recur (conj seen next) (concat more (allowed next))))
      seen)))

(describe "kevin namespaces"
  (it "are each pinned to the kevin namespaces they may require"
    (should= (set (keys allowed)) (source-namespaces)))

  (it "require only what the pin allows"
    (doseq [ns-name (keys allowed)]
      (should= [ns-name #{}] [ns-name (set/difference (kevin-required ns-name) (allowed ns-name))])))

  (it "form no cycle"
    (doseq [ns-name (keys allowed)]
      (should= [ns-name false] [ns-name (contains? (reachable ns-name) ns-name)]))))

(describe "the pure namespaces"
  (it "do no IO, so the commands and the hook reach them with plain values"
    (doseq [ns-name pure]
      (should= [ns-name #{}] [ns-name (set (filter io-namespaces (required ns-name)))])))

  (it "require only each other"
    (doseq [ns-name pure]
      (should-be #(set/subset? % pure) (kevin-required ns-name)))))

(describe "the IO adapters"
  (it "ask kevin.lore for the byline, the Lore-id and the LORE lines"
    (should-be #(contains? % 'kevin.lore) (required 'kevin.hook))
    (should-be #(contains? % 'kevin.lore) (required 'kevin.history)))

  (it "ask kevin.flow whether a task waits on the operator"
    (should-be #(contains? % 'kevin.flow) (required 'kevin.dispatch))
    (should-be #(contains? % 'kevin.flow) (required 'kevin.task))))
