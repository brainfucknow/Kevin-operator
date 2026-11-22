(ns kevin.runtime
  "The Kevin runtime's version policy: the Babashka versions kevin runs on and
  the minimum Kevin version a project asks for. Plain values in and out; see
  kevin.installed for where the runtime is. bin/kevin loads this namespace
  before any other, so an older Babashka gets a clear refusal instead of a load
  error. It depends on nothing else in kevin."
  (:require [clojure.string :as str]))

(def minimum
  "The oldest Babashka kevin runs on; the kevin-min-bb job of
  .github/workflows/test.yml runs test/kevin_test.sh and the crap4clj CRAP gate
  on it. 1.12.216 is the first Babashka whose sci.lang.Var exposes the ns and
  sym fields that Cloverage's instrumentation reads ((.-ns var), (.-sym var))."
  "1.12.216")

(defn- parts
  "The major, minor and patch numbers of version, 0 when missing."
  [version]
  (vec (take 3 (concat (map parse-long (re-seq #"\d+" (str version))) (repeat 0)))))

(defn older?
  "True when version is older than other, comparing major, minor and patch by
  number."
  [version other]
  (neg? (compare (parts version) (parts other))))

(defn too-old?
  "True when version, a Babashka version such as 1.12.214, is older than minimum."
  [version]
  (older? version minimum))

(defn refusal
  "The message that refuses version, or nil when kevin runs on it."
  [version]
  (when (too-old? version)
    (str "kevin: Babashka " version " is too old; kevin needs " minimum " or newer: https://babashka.org")))

;; The minimum version a project needs

(def minimum-label "Minimum Kevin version:")

(defn minimum-value
  "The value of the first \"Minimum Kevin version:\" line of the text of a
  project's kevin/project.md, or nil when it has none."
  [text]
  (some #(let [line (str/trim %)]
           (when (str/starts-with? line minimum-label)
             (str/trim (subs line (count minimum-label)))))
        (str/split-lines (or text ""))))

(defn version-problem
  "Why value is not a <major>.<minor>.<patch> version, or nil."
  [value]
  (when-not (re-matches #"\d+\.\d+\.\d+" value)
    (str "kevin/project.md: Minimum Kevin version must be <major>.<minor>.<patch>, not '" value "'")))

(defn project-refusal
  "Why the runtime at version cannot work on a project whose kevin/project.md
  holds text, or nil when it can."
  [text version]
  (when-let [value (minimum-value text)]
    (or (version-problem value)
        (when (or (nil? version) (older? version value))
          (str "this project needs Kevin " value " or newer; this runtime is " (or version "of unknown version"))))))
