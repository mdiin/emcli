(ns emcli.commands-test
  "Command-layer argument validation: a spec-declared Integer argument that is
  present but not a valid integer is rejected (:bad-argument) rather than
  silently coerced to nil."
  (:require [clojure.test :refer [deftest testing is]]
            [emcli.app :as app]
            [emcli.commands :as cmd]
            [emcli.model :as m]
            [emcli.rules :as r]))

(deftest non-integer-int-arg-is-rejected
  (let [a (app/new-app "M")]
    (testing "registry command: a non-numeric --index is a bad-argument error"
      (let [res (cmd/run a "create-swimlane" {:name "X" :index "abc"})]
        (is (= :bad-argument (:error res)))
        (is (= ["index"] (:args res)))
        (is (zero? (count (m/swimlanes (app/store a) (app/model-id a))))
            "nothing was committed")))
    (testing "an id argument is validated too"
      (is (= :bad-argument (:error (cmd/run a "add-slice"
                                            {:timeline "nope" :title "t" :kind "state_change" :index "0"})))))
    (testing "structured/composite command: bad :connection is rejected"
      (is (= :bad-argument (:error (cmd/run a "add-derivation"
                                            {:connection "xyz" :target "t" :from "a"})))))
    (testing "a fractional value is not an integer"
      (is (= :bad-argument (:error (cmd/run a "create-swimlane" {:name "X" :index "1.5"})))))))

(deftest valid-integers-still-pass
  (let [a (app/new-app "M")]
    (testing "integer-as-string parses"
      (is (not (r/error? (cmd/run a "create-swimlane" {:name "A" :index "0"})))))
    (testing "negative integers parse (used by reorder)"
      (is (not (r/error? (cmd/run a "create-swimlane" {:name "B" :index "-3"})))))
    (testing "native integers pass through"
      (is (not (r/error? (cmd/run a "create-swimlane" {:name "C" :index 2})))))
    (is (= ["B" "A" "C"] (map :name (m/swimlanes (app/store a) (app/model-id a)))))))

(deftest absent-required-int-is-still-missing-not-bad
  (testing "a missing (not malformed) required int is reported as missing-args"
    (let [a (app/new-app "M")
          res (cmd/run a "create-swimlane" {:name "X"})]
      (is (= :missing-args (:error res)))
      (is (= [:index] (:missing res))))))
