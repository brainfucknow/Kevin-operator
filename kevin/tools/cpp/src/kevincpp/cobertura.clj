(ns kevincpp.cobertura
  "Line hits per source file from a Cobertura report, whichever backend wrote
  it: gcovr, dotnet-coverage or OpenCppCoverage."
  (:require [clojure.data.xml :as xml]
            [clojure.string :as str]
            [kevincore.outcome :as outcome]))

(defn- elements [tag node]
  (filter #(and (map? %) (= tag (:tag %))) (tree-seq map? :content node)))

(defn- text [node]
  (str/trim (apply str (filter string? (:content node)))))

(defn- class-entry [class]
  {:filename (get-in class [:attrs :filename] "")
   :lines (into {} (keep (fn [line]
                           (let [{:keys [number hits]} (:attrs line)]
                             (when (and number hits (re-matches #"\d+" number) (re-matches #"\d+" hits))
                               [(parse-long number) (parse-long hits)]))))
                (elements :line class))})

(defn parse
  "The <source> entries, whether the report has any <package>, and each
  class's filename and line hits."
  [xml-text]
  (let [doc (try (xml/parse-str (-> xml-text (str/replace #"^\uFEFF" "") (str/replace #"<!DOCTYPE[^>]*>" "")))
                 (catch Exception e
                   (throw (outcome/usage-error (str "coverage report not readable: " (ex-message e))))))]
    (when-not (= :coverage (:tag doc))
      (throw (outcome/usage-error "expected a Cobertura coverage document")))
    {:sources (map text (elements :source doc))
     :packages (count (elements :package doc))
     :classes (mapv class-entry (elements :class doc))}))

(defn packages?
  "Whether the report holds any package; dotnet-coverage writes none when it
  collected no native data."
  [report]
  (pos? (:packages report)))

(defn- slashes [path]
  (str/replace path "\\" "/"))

(defn- absolute? [path]
  (boolean (re-find #"^(/|[A-Za-z]:/)" (slashes path))))

(defn- relative-to
  "`path` relative to `root` when under it, compared without case; else `path`."
  [path root]
  (let [path (slashes path)
        prefix (str (str/replace (slashes root) #"/+$" "") "/")]
    (if (str/starts-with? (str/lower-case path) (str/lower-case prefix))
      (subs path (count prefix))
      (str/replace path #"^\./" ""))))

(defn- candidates [filename sources]
  (if (absolute? filename)
    [filename]
    (concat (map #(str (str/replace (slashes %) #"/+$" "") "/" (slashes filename)) sources) [filename])))

(defn- known-path
  "The path in `known` that `relative` names, ignoring case, or nil."
  [known relative]
  (or (known relative)
      (some #(when (.equalsIgnoreCase ^String % relative) %) known)))

(defn- resolve-file [filename sources root known]
  (let [relatives (map #(relative-to % root) (candidates filename sources))]
    (or (some (partial known-path known) relatives) (first relatives))))

(defn file-lines
  "Repository-relative path to {line hits}, merged over classes by the
  highest hit count. `known` holds the repository's sources; a filename is
  resolved to one of them when it can be."
  [{:keys [sources classes]} root known]
  (reduce (fn [result {:keys [filename lines]}]
            (update result (resolve-file filename sources root known) #(merge-with max % lines)))
          {}
          (remove #(str/blank? (:filename %)) classes)))
