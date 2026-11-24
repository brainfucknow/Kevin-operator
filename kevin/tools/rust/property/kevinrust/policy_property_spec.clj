(ns kevinrust.policy-property-spec
  (:require [clojure.string :as str]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [kevinrust.libtest :as libtest]
            [kevinrust.reports :as reports]
            [speclj.core :refer [describe it should=]]))

(describe "Rust wrapper properties"
  (it "uses exactly the marked names as extra exact skips and preserves user arguments"
    (let [property
          (prop/for-all [ids (gen/vector gen/nat)
                         filters (gen/vector gen/string-alphanumeric)]
            (let [names (map #(str "module::property_" %) ids)
                  listing (str/join "\n" (concat (map #(str % ": test") names)
                                                 (map #(str "module::test_" % ": test") ids)
                                                 ["property_noise: not a test"]))
                  user-args (into ["--exact" "--include-ignored"] filters)
                  expected (into ["--" "--skip" "property_"]
                                 (concat (mapcat #(vector "--skip" %) (sort (set names))) user-args))]
              (= expected (libtest/ordinary-args user-args listing))))
          result (tc/quick-check 200 property)]
      (should= {:pass? true} (select-keys result [:pass? :shrunk]))))

  (it "counts each mapped changed line once across repeated LCOV records"
    (let [property
          (prop/for-all [observed (gen/vector (gen/tuple (gen/choose 1 30) gen/boolean))
                         changed (gen/set (gen/choose 1 40))]
            (let [lcov (apply str (for [[line hit?] observed]
                                   (str "SF:/repo/src/lib.rs\nDA:" line "," (if hit? 1 0)
                                        "\nend_of_record\n")))
                  records (reports/selected-lcov "/repo" #{"src/lib.rs"} lcov)
                  measured (filter #(changed (first %)) observed)
                  expected [(count (set (map first (filter second measured))))
                            (count (set (map first measured)))]]
              (= expected (reports/changed-coverage (reverse records) {"src/lib.rs" changed}))))
          result (tc/quick-check 200 property)]
      (should= {:pass? true} (select-keys result [:pass? :shrunk])))))
