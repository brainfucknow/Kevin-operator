(ns kevin.mojo-suite-property
  (:require [babashka.fs :as fs]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [kevin.property-support :refer [passes? word]]
            [kevincore.shell :as shell]
            [kevinmojo.cli :as cli]
            [kevinmojo.project :as project]
            [speclj.core :refer [describe it should]]))

(def ^:private modes (gen/elements ["test" "property"]))

(def ^:private runner-arg
  (gen/one-of [word (gen/elements ["" "two words" "--" "--source" "--help" "-I"])]))

(describe "Mojo suite commands"
  (it "forward import paths and runner arguments in caller order"
    (fs/with-temp-dir [directory {:dir (fs/create-dirs (fs/path "tmp"))}]
      (let [root (fs/real-path directory)
            test-dir (fs/create-dir (fs/path root "test dir"))
            imports (mapv #(str (fs/create-dir (fs/path root %))) ["one import" "two import"])]
        (doseq [mode ["test" "property"]]
          (spit (str (fs/path test-dir (str mode "_example.mojo"))) ""))
        (with-redefs [project/root (constantly root)
                      shell/available? (constantly true)]
          (should
           (passes?
            (prop/for-all [mode modes
                           includes (gen/vector (gen/elements imports) 0 5)
                           forwarded (gen/vector runner-arg 0 8)
                           status (gen/choose 0 125)]
              (let [file (str (fs/path test-dir (str mode "_example.mojo")))
                    include-args (mapcat #(vector "-I" %) includes)
                    calls (atom [])]
                (with-redefs [shell/run (fn [tool argv]
                                          (swap! calls conj [tool (vec argv)])
                                          status)]
                  (and (= status (cli/main (concat [mode] include-args [file "--"] forwarded)))
                       (= [["mojo" (vec (concat ["run" "-j" "4"] include-args [file] forwarded))]]
                          @calls))))))))))))
