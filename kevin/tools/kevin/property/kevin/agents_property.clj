(ns kevin.agents-property
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [speclj.core :refer [describe it should]]
            [kevin.agents :as agents]
            [kevin.property-support :refer [passes? word]]))

(def ^:private hostile
  "Text that mixes any byte with the characters that break YAML, JSON and
  TOML strings."
  (gen/fmap #(apply str %)
            (gen/vector (gen/one-of [gen/char (gen/elements (seq "\"\\\n\r\t:#'[{}]`\u007f"))
                                     (gen/return "\"\"\"")])
                        0 24)))

(def ^:private line
  "One line of hostile text: what agents/summary can return."
  (gen/fmap #(str/replace % #"[\n\r]" " ") hostile))

(def ^:private model
  "A tiers.conf value that kevin check accepts."
  (gen/fmap #(apply str "m" %) (gen/vector (gen/elements (seq "az09._:/@[]-")) 0 10)))

(def ^:private templates
  {:stage "Run as {{role}}. Read `{{prompt}}`.\n"
   :gate "Audit as {{role}}. Read `{{prompt}}`.\n"
   :spec-judge "Judge as {{role}}. Read `{{prompt}}`.\n"})

(defn- toml-unescape
  "Reads back the text of a TOML basic string body."
  [s]
  (str/replace s #"\\(?:u([0-9a-fA-F]{4})|(.))"
               (fn [[_ hex c]] (if hex (str (char (Integer/parseInt hex 16))) c))))

(defn- between [text start end]
  (let [from (+ (str/index-of text start) (count start))]
    (subs text from (str/index-of text end from))))

(def ^:private agent-fields
  (gen/let [role word
            kind (gen/elements [:stage :gate :spec-judge])
            summary line
            model model
            prompt hostile]
    {:role role :kind kind :description (agents/description role kind summary) :model model :prompt prompt}))

(describe "render"
  (it "keeps the description and the model of a Markdown agent through YAML front matter, stamped only with a stamp"
    (should (passes? (prop/for-all [harness (gen/elements ["claude" "copilot"])
                                    fields agent-fields
                                    stamp (gen/one-of [(gen/return nil) (gen/return "0.3.0")])]
                       (let [text (agents/render harness (:role fields) fields templates stamp)
                             front (str/split-lines (between text "---\n" "\n---\n"))
                             value (fn [key] (some #(when (str/starts-with? % key) (subs % (count key))) front))]
                         (and (= (:description fields) (json/parse-string (value "description: ")))
                              (= (:model fields) (value "model: "))
                              (= (boolean stamp) (agents/stamped? text))
                              (every? #(re-matches #"(?s)[a-z-]+: .*|# kevin-generated: .*" %) front)))))))

  (it "keeps the description and the body of a Codex agent through TOML strings, prompt paths with \\ and \"\"\" included"
    (should (passes? (prop/for-all [fields agent-fields
                                    stamp (gen/one-of [(gen/return nil) (gen/return "0.3.0")])]
                       (let [text (agents/render "codex" (:role fields) fields templates stamp)
                             instructions (between text "developer_instructions = \"\"\"\n" "\"\"\"\n")]
                         (and (= (:description fields) (toml-unescape (between text "\ndescription = \"" "\"\n")))
                              (= (agents/body templates fields) (toml-unescape instructions))
                              (not (str/includes? (str/replace instructions "\\\"" "") "\""))
                              (= (boolean stamp) (agents/stamped? text)))))))))

(describe "resolution"
  (it "is the plugin agent with no model and no file when the project's agent equals the runtime's"
    (should (passes? (prop/for-all [harness (gen/elements agents/harnesses) fields agent-fields]
                       (let [resolution (agents/resolution harness (:role fields) fields fields)]
                         (and (:agent resolution) (not (:path resolution)) (not (:model resolution))))))))

  (it "passes a Claude Code alias on the call and needs a project agent for any other difference"
    (should (passes? (prop/for-all [harness (gen/elements agents/harnesses)
                                    fields agent-fields
                                    other model
                                    alias (gen/elements (vec agents/aliases))
                                    summary line]
                       (let [role (:role fields)
                             by-alias (agents/resolution harness role (assoc fields :model alias) (assoc fields :model other))
                             by-model (agents/resolution harness role (assoc fields :model (str other "x")) (assoc fields :model other))
                             described (agents/description role (:kind fields) (str summary "x"))
                             by-text (agents/resolution harness role (assoc fields :model alias :description described) fields)
                             added (agents/resolution harness role fields nil)]
                         (and (if (= "claude" harness)
                                (and (= alias (:model by-alias)) (not (:path by-alias)))
                                (:path by-alias))
                              (:path by-model)
                              (if (= described (:description fields)) true (:path by-text))
                              (:path added)
                              (= (:path added) (:path (agents/project-agent harness role))))))))))
