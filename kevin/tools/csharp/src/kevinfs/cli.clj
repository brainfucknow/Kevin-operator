(ns kevinfs.cli
  (:require [kevincs.cli :as dotnet]
            [kevincore.shell :as shell]))

(defn -main [& argv]
  (shell/run-main "kevin-fs" #(dotnet/main :fsharp %) argv))
