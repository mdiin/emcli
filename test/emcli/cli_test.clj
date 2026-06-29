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
