(ns kevinrust.libtest
  (:require [clojure.string :as str]))

(def ^:private property-marker "property_")

(def property-args ["--" property-marker "--ignored"])

(defn list-args [user-args]
  ;; Libtest applies --exact to skip filters too.
  (when (some #{"--exact"} user-args)
    ["--" "--list" "--include-ignored"]))

(defn- property-names [output]
  (into (sorted-set)
        (keep (fn [line]
                (let [[_ name] (re-matches #"(.+): (?:test|benchmark)" line)]
                  (when (and name (str/includes? name property-marker)) name))))
        (str/split-lines output)))

(defn ordinary-args
  ([user-args] (ordinary-args user-args ""))
  ([user-args listing]
   (into ["--" "--skip" property-marker]
         (concat (mapcat #(vector "--skip" %) (property-names listing)) user-args))))
