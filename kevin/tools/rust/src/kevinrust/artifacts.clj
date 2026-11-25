(ns kevinrust.artifacts
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [kevincore.outcome :as outcome]))

(defn run-dir [{:keys [root]} name]
  (let [tmp (fs/path root "tmp")
        parent (fs/path tmp name)]
    (doseq [path [tmp parent]]
      (when (fs/sym-link? path)
        (throw (outcome/usage-error (str "report or scratch path is a symbolic link: " path))))
      (fs/create-dirs path))
    (fs/create-temp-dir {:dir parent :prefix "run-"})))

(defn read-report [tool path]
  (try
    (slurp (str path))
    (catch Exception e
      (throw (outcome/usage-error (str tool " report " path ": " (ex-message e)))))))

(defn parse-json [tool path text]
  (try
    (json/parse-string text)
    (catch Exception e
      (throw (outcome/usage-error (str tool " JSON " path ": " (ex-message e)))))))

(defn json-report [tool path]
  (parse-json tool path (read-report tool path)))
