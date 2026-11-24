(ns kevinmojo.suite
  (:require [babashka.fs :as fs]
            [kevinmojo.project :as project]
            [kevincore.shell :as shell]))

(defn- suite-name? [kind path]
  (re-matches (case kind :property #"property_.*\.mojo" #"test_.*\.mojo")
              (fs/file-name path)))

(defn suite-file? [path]
  (or (suite-name? :test path) (suite-name? :property path)))

(defn- discover [root kind]
  (let [test-dir (fs/path root "test")]
    (when (and (fs/directory? test-dir) (project/inside? root test-dir))
      (->> (fs/glob test-dir "**.mojo" {:hidden true})
           (filter #(and (fs/regular-file? %) (suite-name? kind %) (project/inside? root %)))
           sort))))

(defn- parse-options [mode args]
  (loop [[arg value & remaining :as args] args
         opts {:files [] :includes []}]
    (cond
      (nil? arg) opts
      (= "--" arg) (assoc opts :suite-args (vec (rest args)))
      (= "-I" arg) (recur remaining (update opts :includes conj (project/option-value arg value)))
      (and (= :coverage mode) (= "--source" arg))
      (recur remaining (assoc opts :source (project/option-value arg value)))
      (.startsWith arg "-") (project/fail (str "unknown option " arg "; see kevin mojo --help"))
      :else (recur (rest args) (update opts :files conj arg)))))

(defn selection [root mode args]
  (let [{:keys [files includes suite-args source]} (parse-options mode args)
        kind (if (= :property mode) :property :test)
        matching? #(and (fs/regular-file? %) (suite-name? kind %))
        pattern (str (name kind) "_*.mojo")
        suites (if (seq files)
                 (mapv #(project/resolve-path root % matching? pattern) files)
                 (discover root kind))]
    (when (and suite-args (not= 1 (count files)))
      (project/fail "pass exactly one explicit suite before -- to forward suite arguments"))
    (when (empty? suites)
      (project/fail (str "no suites selected; add test/**/" pattern " or pass a matching path")))
    {:suites suites
     :includes (mapv #(project/resolve-path root % fs/directory? "an import directory for -I") includes)
     :suite-args suite-args
     :source source}))

(defn import-args [includes]
  (mapcat #(vector "-I" (str %)) includes))

(defn run-until-failed [run-suite suites]
  (or (some (fn [suite]
              (let [status (run-suite suite)]
                (when-not (zero? status) status)))
            suites)
      0))

(defn run-tests [{:keys [includes suites suite-args]}]
  (project/require-tool "mojo")
  (run-until-failed
   #(shell/run "mojo" (concat ["run" "-j" "4"] (import-args includes) [(str %)] suite-args))
   suites))
