(ns kevin.agents-hardening-spec
  (:require [speclj.core :refer [describe it should=]]
            [kevin.agents :as agents]))

(describe "toml-escape"
  (it "writes a control character and DEL as a unicode escape"
    (should= "a\\u0001b\\u007fc\\u0009d" (agents/toml-escape "a\u0001b\u007fc\td")))
  (it "leaves a space and a printable character as they are"
    (should= " ~" (agents/toml-escape " ~"))))
