(ns kevincpp.source
  "Which files are C and C++ sources, and which of them kevin-cpp measures."
  (:require [clojure.string :as str]))

(def extensions
  "File extensions of C and C++ sources and headers."
  ["c" "cc" "cpp" "cxx" "c++" "h" "hh" "hpp" "hxx" "ixx" "inl"])

(def pathspecs
  "Case-insensitive Git pathspecs naming every C or C++ source."
  (mapv #(str ":(icase)*." %) extensions))

(defn source? [path]
  (boolean (some #(str/ends-with? (str/lower-case path) (str "." %)) extensions)))

(defn- directories [path]
  (butlast (str/split (str/replace path "\\" "/") #"/")))

(defn test-path?
  "Whether `path` lies under a directory named test or tests."
  [path]
  (boolean (some #{"test" "tests"} (map str/lower-case (directories path)))))

(defn scratch-path?
  "Whether repo-relative `path` lies under the repository's tmp/."
  [path]
  (= "tmp" (first (directories path))))

(defn measured?
  "Whether repo-relative `path` is a source kevin-cpp measures; test
  directories count only with `include-tests?`."
  [path include-tests?]
  (and (source? path)
       (not (scratch-path? path))
       (or include-tests? (not (test-path? path)))))
