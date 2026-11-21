(ns sf.runtime
  "The Babashka versions kevin runs on. bin/kevin loads this namespace before any
  other, so an older Babashka gets a clear refusal instead of a load error.")

(def minimum
  "The oldest Babashka kevin runs on; the kevin-min-bb job of
  .github/workflows/test.yml runs test/sf_test.sh and the crap4clj CRAP gate
  on it. 1.12.216 is the first Babashka whose sci.lang.Var exposes the ns and
  sym fields that Cloverage's instrumentation reads ((.-ns var), (.-sym var))."
  "1.12.216")

(defn- parts
  "The major, minor and patch numbers of version, 0 when missing."
  [version]
  (vec (take 3 (concat (map parse-long (re-seq #"\d+" (str version))) (repeat 0)))))

(defn too-old?
  "True when version, a Babashka version such as 1.12.214, is older than minimum."
  [version]
  (neg? (compare (parts version) (parts minimum))))

(defn refusal
  "The message that refuses version, or nil when kevin runs on it."
  [version]
  (when (too-old? version)
    (str "kevin: Babashka " version " is too old; kevin needs " minimum " or newer: https://babashka.org")))
