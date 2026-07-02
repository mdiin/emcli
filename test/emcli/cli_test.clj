(ns emcli.cli-test
  "The CLI's entity-grouped subcommands must cover exactly the flat authoring
  commands the server exposes — every operation reachable, nothing dangling."
  (:require [clojure.test :refer [deftest testing is]]
            [emcli.cli :as cli]
            [emcli.commands :as cmd]))

(defn- all-grouped-commands []
  (mapcat vals (vals cli/command-groups)))

(deftest grouping-covers-every-authoring-command-exactly-once
  (let [grouped (all-grouped-commands)]
    (testing "no duplicate flat command across groups"
      (is (= (count grouped) (count (distinct grouped)))))
    (testing "the grouped commands are exactly the server's authoring commands"
      (is (= (set cmd/commands) (set grouped))
          (str "missing from CLI: " (remove (set grouped) cmd/commands)
               " | not a real command: " (remove (set cmd/commands) grouped))))))

(deftest resolve-command-maps-entity-verb-to-flat
  (is (= "add-slice" (cli/resolve-command "slice" "add")))
  (is (= "delete-slice" (cli/resolve-command "slice" "delete")))
  (is (= "create-timeline" (cli/resolve-command "timeline" "add")))
  (is (= "set-connection-derivations" (cli/resolve-command "connection" "derivations")))
  (is (= "set-field-origins" (cli/resolve-command "element" "origins")))
  (testing "unknown entity or verb resolves to nil"
    (is (nil? (cli/resolve-command "slice" "frobnicate")))
    (is (nil? (cli/resolve-command "nonsense" "add")))))

;; Adapter-side guard for `--examples-json`, per the @guidance on rule
;; SetStepExamples: the CLI must map/validate incoming payloads against the
;; Example shape (field_name/field_value) before invoking SetStepExamples,
;; rather than relying solely on the ExamplesWellFormed invariant to catch
;; degradation after the fact.
(deftest example-shape-error-validates-against-the-example-value-type
  (testing "well-formed examples pass"
    (is (nil? (#'cli/example-shape-error [{:field_name "orderId" :field_value "123"}]))))
  (testing "an empty examples array is valid"
    (is (nil? (#'cli/example-shape-error []))))
  (testing "the historical bug: wrong keys (field/value) are rejected"
    (is (some? (#'cli/example-shape-error [{:field "orderId" :value "123"}]))))
  (testing "blank field_value is rejected"
    (is (some? (#'cli/example-shape-error [{:field_name "orderId" :field_value ""}]))))
  (testing "blank field_name is rejected"
    (is (some? (#'cli/example-shape-error [{:field_name "" :field_value "123"}]))))
  (testing "missing field_value key entirely is rejected"
    (is (some? (#'cli/example-shape-error [{:field_name "orderId"}]))))
  (testing "a numeric field_value (unquoted JSON literal) is rejected, not thrown"
    (is (some? (#'cli/example-shape-error [{:field_name "orderId" :field_value 123}]))))
  (testing "non-array input is rejected"
    (is (some? (#'cli/example-shape-error {:field_name "orderId" :field_value "123"}))))
  (testing "a non-object element in the array is rejected"
    (is (some? (#'cli/example-shape-error ["not-a-map"])))))
