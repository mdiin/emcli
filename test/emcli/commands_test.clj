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

;; --- explicit --id (CLI-level: string coercion + conflict surfacing) ------

(deftest explicit-id-flows-through-cmd-run
  (let [a (app/new-app "M")]
    (testing "string --id coerces to int and is honored"
      (let [res (cmd/run a "create-timeline" {:title "T" :id "9"})]
        (is (not (r/error? res)))
        (is (= 9 (:id (:result res))))))
    (testing "conflicting id is rejected, same as any other rule error"
      (let [res (cmd/run a "create-swimlane" {:name "L" :index 0 :id "9"})]
        (is (= :id-conflict (:error res)))))
    (testing "non-integer --id is a bad-argument, same as any other int param"
      (is (= :bad-argument (:error (cmd/run a "create-element" {:name "E" :kind "command" :id "nope"})))))))

;; NameResolution.resolve (event-model.allium): batched name -> candidate
;; lookup, so an LLM never has to pull the whole model to resolve a name.
(deftest resolve-names-test
  (let [a  (app/new-app "M")
        tl (:id (:result (cmd/run a "create-timeline" {:title "Checkout"})))]
    (cmd/run a "add-slice" {:timeline tl :title "Baz" :kind "state_change" :index 0})
    (cmd/run a "create-element" {:name "Snaz" :kind "read_model"})
    (cmd/run a "create-element" {:name "Snazzz" :kind "read_model"})

    (testing "exact match wins outright, carries its breadcrumb"
      (let [[res] (cmd/resolve-names a [{:name "Baz"}])]
        (is (= [:exact] (map :match_type (:candidates res))))
        (is (= "Checkout" (get-in res [:candidates 0 :breadcrumb :timeline_title])))
        (is (= 1 (:total_matches res)))
        (is (false? (:truncated res)))))

    (testing "substring is only tried once exact yields nothing"
      (let [[res] (cmd/resolve-names a [{:name "naz"}])]
        (is (= #{:substring} (set (map :match_type (:candidates res)))))
        (is (= #{"Snaz" "Snazzz"} (set (map :name (:candidates res)))))))

    (testing "near-miss only fires when neither exact nor substring matched anything"
      (let [[res] (cmd/resolve-names a [{:name "Foobar"}])]
        (is (seq (:candidates res)))
        (is (every? #(= :near_miss (:match_type %)) (:candidates res)))
        (is (apply <= (map :distance (:candidates res)))
            "near-miss candidates are ordered nearest-first")))

    (testing "kind_hint ranks matches of the hinted kind first, never filters others out"
      (cmd/run a "create-swimlane" {:name "Snaz" :index 0})
      (let [[res] (cmd/resolve-names a [{:name "Snaz" :kind_hint "swimlane"}])]
        (is (= :swimlane (get-in res [:candidates 0 :kind])))
        (is (= #{:swimlane :element} (set (map :kind (:candidates res))))
            "the element match is still present, just ranked after the hint")))

    (testing "zero queries is a no-op, not an error"
      (is (= [] (cmd/resolve-names a []))))))
