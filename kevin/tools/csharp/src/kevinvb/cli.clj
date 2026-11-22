(ns kevinvb.cli
  (:require [kevincs.cli :as dotnet]
            [kevincore.shell :as shell]))

(defn -main [& argv]
  (shell/run-main "kevin-vb" #(dotnet/main :vbnet %) argv))
