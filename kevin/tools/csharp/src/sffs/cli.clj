(ns sffs.cli
  (:require [sfcs.cli :as dotnet]
            [sfcore.shell :as shell]))

(defn -main [& argv]
  (shell/run-main "kevin-fs" #(dotnet/main :fsharp %) argv))
