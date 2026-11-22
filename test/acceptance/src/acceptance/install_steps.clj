(ns acceptance.install-steps
  "Step handlers for test/features/install (see its README for the step
  meanings): install.sh --user run from a scratch copy of the plugin with a
  scratch HOME. $HOME in a step is that home."
  (:require [acceptance.support :as support :refer [check]]
            [babashka.fs :as fs]
            [clojure.string :as str]))

(defn new-world [] {})

(defn- path-without-bb
  "The PATH of the clean environment with each directory that holds bb replaced
  by a scratch directory of links to its other programs."
  []
  (let [path (get (support/clean-env nil) "PATH")]
    (str/join ":" (for [[index dir] (map-indexed vector (str/split path #":"))]
                    (if-not (fs/exists? (fs/path dir "bb"))
                      dir
                      (let [links (fs/path @support/scratch "path-without-bb" (str index))]
                        (when-not (fs/exists? links)
                          (fs/create-dirs links)
                          (doseq [program (fs/list-dir dir)
                                  :when (not= "bb" (str (fs/file-name program)))]
                            (fs/create-sym-link (fs/path links (fs/file-name program)) program)))
                        (str links)))))))

(defn- scratch-dir [prefix]
  (str (fs/create-dirs (fs/path @support/scratch (str prefix "-" (random-uuid))))))

(defn- home-path [world text]
  (str/replace text "$HOME" (:home world)))

(defn- tree
  "Each file and link under dir, by relative path, with its contents or link
  target."
  [dir]
  (into {} (for [file (fs/glob dir "**" {:hidden true :follow-links false})
                 :when (or (fs/sym-link? file) (fs/regular-file? file))]
             [(str (fs/relativize dir file))
              (if (fs/sym-link? file) (str "-> " (fs/read-link file)) (slurp (str file)))])))

(defn- lines [text] (str/split-lines (str text)))

(defn- env [world]
  {"HOME" (:home world)
   "PATH" (or (:path world) (get (support/clean-env (:home world)) "PATH"))})

(def handlers
  [;; Given
   [#"a Kevin plugin directory at version \"([^\"]+)\""
    (fn [world version]
      (let [plugin (support/plugin-copy (scratch-dir "plugin") version)]
        (assoc world :plugin plugin :plugin-before (tree plugin))))]

   [#"a home directory with no \".local/bin/kevin\""
    (fn [world]
      (let [home (scratch-dir "home")]
        (assoc world :home home)))]

   [#"\"\$HOME/.local/bin/kevin\" is a symbolic link to \"([^\"]+)\""
    (fn [world target]
      (fs/create-dirs (fs/path (:home world) ".local" "bin"))
      (fs/create-sym-link (fs/path (:home world) ".local" "bin" "kevin") target)
      world)]

   [#"the file \"(\$HOME/[^\"]+)\" holds \"(.*)\""
    (fn [world path text]
      (let [file (fs/path (home-path world path))]
        (fs/create-dirs (fs/parent file))
        (spit (str file) (str text "\n"))
        world))]

   [#"no directory on PATH holds bb"
    (fn [world] (assoc world :path (path-without-bb)))]

   [#"a working directory outside the plugin directory holds a bb.edn that requires Babashka \"([^\"]+)\""
    (fn [world version]
      (let [dir (scratch-dir "workdir")]
        (spit (str (fs/path dir "bb.edn")) (pr-str {:min-bb-version version}))
        (assoc world :workdir dir)))]

   [#"\"\$HOME/.local/bin\" (is|is not) on PATH"
    (fn [world state]
      (let [path (get (support/clean-env (:home world)) "PATH")]
        (assoc world :path (if (= "is" state) (str (:home world) "/.local/bin:" path) path))))]

   ;; When
   [#"I run install.sh with \"([^\"]*)\" (\d+) times( from that working directory)?"
    (fn [world arguments times from-workdir]
      (let [args (remove str/blank? (str/split arguments #" "))
            dir (if from-workdir (:workdir world) (:home world))
            before (tree (:home world))
            results (doall (repeatedly (parse-long times)
                                       #(support/run dir (concat ["bash" (str (fs/path (:plugin world) "install.sh"))] args)
                                                     (env world))))]
        (merge world (select-keys (last results) [:exit :out :err]) {:home-before before})))]

   ;; Then
   [#"the exit status is (\d+)"
    (fn [world status]
      (check (= (parse-long status) (:exit world))
             (str "exit status " (:exit world) ", stderr: " (str/trim (str (:err world)))))
      world)]

   [#"\"\$HOME/.local/bin/kevin\" is a symbolic link to the plugin's \"([^\"]+)\""
    (fn [world target]
      (let [link (fs/path (:home world) ".local" "bin" "kevin")]
        (check (fs/sym-link? link) "not a symbolic link")
        (check (= (str (fs/canonicalize (fs/path (:plugin world) target))) (str (fs/read-link link)))
               (str "links to " (fs/read-link link)))
        world))]

   [#"running \"\$HOME/.local/bin/kevin version\" prints \"([^\"]+)\""
    (fn [world version]
      (let [{:keys [exit out err]} (support/run (:home world) [(str (:home world) "/.local/bin/kevin") "version"] (env world))]
        (check (and (zero? exit) (= version (str/trim out))) (str "printed " (pr-str out) " " err))
        world))]

   [#"the home directory has no new path outside \".local/bin\""
    (fn [world]
      (let [added (remove #(or (contains? (:home-before world) %) (str/starts-with? % ".local/bin/"))
                          (keys (tree (:home world))))]
        (check (empty? added) (str "new paths " (pr-str added)))
        world))]

   [#"the plugin directory has the same files with the same contents as before"
    (fn [world]
      (check (= (:plugin-before world) (tree (:plugin world))) "the plugin directory changed")
      world)]

   [#"the error output has the line \"(.*)\""
    (fn [world line]
      (check (some #{(home-path world line)} (lines (:err world))) (str "error output was " (pr-str (:err world))))
      world)]

   [#"the file \"(\$HOME/[^\"]+)\" still holds \"(.*)\""
    (fn [world path text]
      (check (= (str text "\n") (slurp (home-path world path))) "the file changed")
      world)]

   [#"the home directory has no path \"([^\"]+)\""
    (fn [world path]
      (check (not (or (fs/exists? (fs/path (:home world) path)) (fs/sym-link? (fs/path (:home world) path))))
             (str path " exists"))
      world)]

   [#"the error output has a line containing \"([^\"]+)\" and \"([^\"]+)\""
    (fn [world one other]
      (check (some #(and (str/includes? % one) (str/includes? % other)) (lines (:err world)))
             (str "error output was " (pr-str (:err world))))
      world)]

   [#"the error output has no line containing \"([^\"]+)\""
    (fn [world text]
      (let [found (filter #(str/includes? % text) (lines (:err world)))]
        (check (empty? found) (str "error output has " (pr-str found)))
        world))]

   [#"the output (has|does not have) the line \"(.*)\""
    (fn [world has line]
      (let [found (some #{(home-path world line)} (lines (:out world)))]
        (check (if (= "has" has) found (not found)) (str "output was " (pr-str (:out world))))
        world))]])
