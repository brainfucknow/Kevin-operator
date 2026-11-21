(ns kevin.shell-spec
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [speclj.core :refer [describe it should=]]
            [kevin.shell :as shell]))

(defn- kevin-env-with
  "shell/kevin-env with the environment variables in vars."
  [vars name default]
  (with-redefs [shell/env (fn [variable default]
                            (let [value (get vars variable)]
                              (if (str/blank? value) default value)))]
    (shell/kevin-env name default)))

(describe "kevin-env"
  (it "is KEVIN_<name> when it is set, whatever SWARMFORGE_<name> holds"
    (should= "new" (kevin-env-with {"KEVIN_ROUTE" "new" "SWARMFORGE_ROUTE" "old"} "ROUTE" "d")))

  (it "is the legacy SWARMFORGE_<name> when KEVIN_<name> is unset or blank"
    (should= "old" (kevin-env-with {"SWARMFORGE_ROUTE" "old"} "ROUTE" "d"))
    (should= "old" (kevin-env-with {"KEVIN_ROUTE" " " "SWARMFORGE_ROUTE" "old"} "ROUTE" "d")))

  (it "is the default when neither is set"
    (should= "d" (kevin-env-with {} "ROUTE" "d"))))

(describe "state-paths"
  (it "is the path under .kevin/, then the legacy one under .swarmforge/"
    (should= [(str (fs/path "/r" ".kevin" "audit" "t1")) (str (fs/path "/r" ".swarmforge" "audit" "t1"))]
             (map str (shell/state-paths "/r" "audit" "t1")))))
