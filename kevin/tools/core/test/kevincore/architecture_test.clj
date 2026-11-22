(ns kevincore.architecture-test
  "Dependency rules for the kevin-* wrappers, read from the ns forms under
  kevin/tools: policy namespaces stay free of IO, shared core never
  reaches into a language, languages stay apart, and only the F# and VB.NET
  entrypoints delegate to the .NET CLI."
  (:require [babashka.fs :as fs]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def tools-dir (str (nth (iterate fs/parent (fs/canonicalize *file*)) 4)))

(def source-roots
  ["core/src" "javascript/src" "javascript/acceptance/src" "csharp/src" "ruby/src"])

(defn- ns-form [file]
  (with-open [reader (java.io.PushbackReader. (java.io.FileReader. (str file)))]
    (read reader)))

(defn- required [form]
  (set (for [clause (drop 2 form)
             :when (and (seq? clause) (= :require (first clause)))
             spec (rest clause)]
         (if (sequential? spec) (first spec) spec))))

(def graph
  "Each namespace under the source roots and the namespaces it requires."
  (into (sorted-map)
        (for [root source-roots
              file (fs/glob (fs/path tools-dir root) "**.clj")
              :let [form (ns-form file)]]
          [(second form) (required form)])))

(def io-libraries '#{babashka.fs babashka.process clojure.java.io clojure.java.shell})

(def adapters
  "The namespaces that may touch processes, files, git, or the console."
  '#{kevincore.shell kevincore.git kevinjs.cli kevincs.cli kevincs.cobertura kevinfs.cli kevinvb.cli kevinrb.cli})

(def dotnet-entrypoints '#{kevinfs.cli kevinvb.cli})

(defn- in? [prefix sym]
  (str/starts-with? (str sym) prefix))

(defn- dotnet? [sym]
  (some #(in? % sym) ["kevincs." "kevinfs." "kevinvb."]))

(defn- language [sym]
  (cond
    (in? "kevinjs." sym) :javascript
    (dotnet? sym) :dotnet
    (in? "kevinrb." sym) :ruby))

(defn- violations [pred]
  (into (sorted-map) (keep (fn [[from deps]] (when-let [bad (seq (filter #(pred from %) deps))] [from (vec (sort bad))])))
        graph))

(deftest the-graph-is-read
  (is (contains? graph 'kevinjs.cli))
  (is (contains? graph 'kevinrb.cli))
  (is (contains? (graph 'kevinjs.cli) 'kevincore.git)))

(deftest policy-namespaces-stay-free-of-io
  (is (= {} (violations (fn [from dep]
                          (and (not (adapters from))
                               (not (in? "kevinjs.acceptance." from))
                               (or (io-libraries dep) (adapters dep))))))))

(deftest core-never-reaches-into-a-language
  (is (= {} (violations (fn [from dep] (and (in? "kevincore." from) (not (in? "kevincore." dep)) (in? "kevin" dep)))))))

(deftest languages-stay-apart
  (is (= {} (violations (fn [from dep]
                          (let [from-language (language from) dep-language (language dep)]
                            (and from-language dep-language (not= from-language dep-language))))))))

(deftest command-line-dependencies-are-limited-to-the-dotnet-entrypoint
  (is (= {} (violations (fn [from dep]
                          (or (and (or (#{'kevinjs.cli 'kevincs.cli 'kevinrb.cli} dep) (dotnet-entrypoints dep))
                                   (not (and (dotnet-entrypoints from) (= 'kevincs.cli dep))))
                              (and (not (in? "kevinjs.acceptance." from)) (in? "kevinjs.acceptance." dep))))))))

(def complexity-sources
  "The linters kevin-js crap takes complexity from; each reads its own report
  into the functions kevinjs.crap scores."
  '#{kevinjs.eslint kevinjs.oxlint})

(deftest crap-scoring-knows-no-linter-and-the-linters-stay-apart
  (is (= {} (violations (fn [from dep]
                          (and (complexity-sources dep)
                               (or (= 'kevinjs.crap from) (complexity-sources from))))))))

(def ruby-analysers
  "The tools kevin-rb takes complexity or clones from; each reads its own report."
  '#{kevinrb.rubocop kevinrb.flog kevinrb.flay})

(deftest ruby-crap-scoring-knows-no-analyser-and-the-analysers-stay-apart
  (is (= {} (violations (fn [from dep]
                          (and (ruby-analysers dep)
                               (or (= 'kevinrb.crap from) (ruby-analysers from))))))))

(defn- reachable [start]
  (loop [seen #{} todo (vec (graph start))]
    (if-let [[next & more] (seq todo)]
      (if (seen next)
        (recur seen (vec more))
        (recur (conj seen next) (into (vec more) (graph next))))
      seen)))

(deftest no-import-cycles
  (is (= [] (filter #(contains? (reachable %) %) (keys graph)))))

(deftest adapters-exist
  (is (= #{} (set/difference adapters (set (keys graph))))))
