(ns kevinrb.commands-test
  (:require [clojure.test :refer [deftest is]]
            [kevinrb.commands :as commands]))

(deftest specs-are-files-under-spec-or-named-like-specs
  (is (commands/spec-file? "spec/ledger_spec.rb"))
  (is (commands/spec-file? "spec/support/helpers.rb"))
  (is (commands/spec-file? "engines/a/spec/helper.rb"))
  (is (commands/spec-file? "lib/ledger_spec.rb"))
  (is (not (commands/spec-file? "lib/spec.rb")))
  (is (not (commands/spec-file? "lib/inspector/specter.rb"))))

(deftest production-sources-are-ruby-files-outside-specs-and-ignored-directories
  (is (= ["app/b.rb" "lib/a.rb"]
         (commands/production-sources ["lib/a.rb" "app/b.rb" "spec/a_spec.rb" "README.md" "vendor/bundle/x.rb"
                                       "tmp/y.rb" "node_modules/z.rb" "lib/a_property_spec.rb"]))))

(deftest ruby-sources-keep-specs
  (is (= ["lib/a.rb" "spec/a_spec.rb"] (commands/ruby-sources ["spec/a_spec.rb" "lib/a.rb" "vendor/v.rb"]))))

(deftest rspec-leaves-out-property-specs
  (is (= ["exec" "rspec" "--exclude-pattern" "**/*_property_spec.rb" "--seed" "1"]
         (commands/rspec-args ["--seed" "1"]))))

(deftest coverage-env-adds-the-setup-to-rubyopt
  (is (= {"RUBYOPT" "-W0 -r/kevin/simplecov.rb" "BUNDLE_DISABLE_EXEC_LOAD" "true"}
         (commands/coverage-env "-W0" "/kevin/simplecov.rb")))
  (is (= "-r/kevin/simplecov.rb" (get (commands/coverage-env nil "/kevin/simplecov.rb") "RUBYOPT"))))

(deftest jscpd-reads-ruby-and-skips-vendored-and-generated-directories
  (is (= ["npx" "--yes" "jscpd@4" "--format" "ruby" "--min-tokens" "40" "--reporters" "json" "--output" "tmp/jscpd"
          "--ignore" "**/node_modules/**,**/dist/**,**/build/**,**/coverage/**,**/tmp/**,**/vendor/**" "."]
         (commands/jscpd-args 40))))

(deftest flay-runs-the-reporter-over-the-files
  (is (= ["exec" "ruby" "/kevin/flay_report.rb" "16" "tmp/flay/flay-report.json" "lib/a.rb"]
         (commands/flay-args "/kevin/flay_report.rb" 16 ["lib/a.rb"]))))
