(ns emcli.tag-docs-test
  (:require [clojure.test :refer [deftest testing is]]
            [emcli.tag-docs :as td]))

(def ^:private doc
  (str "# Title\n\nprose before\n\n"
       td/begin-marker "\nstale tables\n" td/end-marker
       "\n\nprose after\n"))

(deftest splice-replaces-only-the-generated-section
  (is (= (str "# Title\n\nprose before\n\n"
              td/begin-marker "\nNEW\n" td/end-marker
              "\n\nprose after\n")
         (td/splice doc "NEW"))))

(deftest splice-is-idempotent
  (is (= (td/splice doc "NEW") (td/splice (td/splice doc "NEW") "NEW"))))

(deftest splice-without-markers-is-nil
  (testing "a doc that lost its markers is reported, never silently rewritten"
    (is (nil? (td/splice "# no markers here\n" "NEW")))
    (is (nil? (td/splice (str "only " td/begin-marker) "NEW")))))

(deftest the-docs-carry-the-current-tag-tables
  ;; the same drift check `bb check` runs: regenerate with `bb gen-docs`
  (is (empty? (td/violations))))
