(ns emcli.cli-test
  "The CLI's entity-grouped subcommands must cover exactly the flat authoring
  commands the server exposes — every operation reachable, nothing dangling."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest testing is]]
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
  (is (= "add-derivation" (cli/resolve-command "connection" "add-derivation")))
  (is (= "remove-derivation" (cli/resolve-command "connection" "remove-derivation")))
  (is (= "add-field" (cli/resolve-command "element" "add-field")))
  (is (= "remove-field" (cli/resolve-command "element" "remove-field")))
  (is (= "add-field-origin" (cli/resolve-command "element" "add-origin")))
  (is (= "remove-field-origin" (cli/resolve-command "element" "remove-origin")))
  (is (= "add-step-example" (cli/resolve-command "step" "add-example")))
  (is (= "remove-step-example" (cli/resolve-command "step" "remove-example")))
  (is (= "add-wireframe-node" (cli/resolve-command "wireframe" "add-node")))
  (is (= "add-wireframe-node-before" (cli/resolve-command "wireframe" "add-node-before")))
  (is (= "delete-wireframe-node" (cli/resolve-command "wireframe" "delete-node")))
  (is (= "set-wireframe-attr" (cli/resolve-command "wireframe" "set-attr")))
  (is (= "set-wireframe-text" (cli/resolve-command "wireframe" "set-text")))
  (testing "unknown entity or verb resolves to nil"
    (is (nil? (cli/resolve-command "slice" "frobnicate")))
    (is (nil? (cli/resolve-command "nonsense" "add")))))

(deftest wireframe-composite-commands-have-manifest-params
  (doseq [cmd ["add-wireframe-node" "add-wireframe-node-before" "set-wireframe-attr" "set-wireframe-text"]]
    (let [params (#'cli/command->manifest-params cmd)]
      (is (seq params) (str cmd " must have non-empty manifest params")))))

(deftest wireframe-add-node-commands-declare-optional-text-flag
  (doseq [cmd ["add-wireframe-node" "add-wireframe-node-before"]]
    (testing (str cmd " declares a text flag")
      (let [params (#'cli/command->manifest-params cmd)
            text   (first (filter #(= "text" (:flag %)) params))]
        (is (some? text) (str cmd " must declare a text flag"))
        (testing "the text flag is optional"
          (is (false? (:required text))))))))

(deftest top-level-help-includes-wireframe-show
  (let [output (with-out-str (#'cli/print-help))]
    (is (clojure.string/includes? output "wireframe")
        "top-level help must list the wireframe group")))

(deftest wireframe-group-help-includes-show
  (let [output (with-out-str (#'cli/print-group-help "wireframe"))]
    (is (clojure.string/includes? output "show")
        "wireframe group help must list the CLI-only show verb")))

(deftest set-wireframe-attr-node-note-names-current-show-command
  (let [params (#'cli/command->manifest-params "set-wireframe-attr")
        node   (first (filter #(= "node" (:flag %)) params))]
    (testing "the node-id note points operators at the current command name"
      (is (= "node id (nN) as shown by wireframe show" (:note node))))))

;; --queries "name[:kind_hint],..." parsing for `emcli resolve`.
(deftest parse-resolve-queries-test
  (testing "bare names carry no kind_hint"
    (is (= [{:name "Baz"}] (#'cli/parse-resolve-queries "Baz"))))
  (testing "a trailing :kind adds a kind_hint"
    (is (= [{:name "Snaz" :kind_hint "element"}] (#'cli/parse-resolve-queries "Snaz:element"))))
  (testing "a batch mixes hinted and bare names, trimming whitespace"
    (is (= [{:name "Baz" :kind_hint "slice"} {:name "Snaz"} {:name "Foobar"}]
           (#'cli/parse-resolve-queries " Baz:slice, Snaz , Foobar "))))
  (testing "blank entries are dropped"
    (is (= [{:name "Baz"}] (#'cli/parse-resolve-queries "Baz,,")))))

;; The ECA plugin embeds a copy of the generated tool definitions, and nothing
;; else keeps the two in step: a stale description there tells a harness's model
;; about flags that no longer exist, which is how the plugin drifted from
;; tools.json once already (the `add-field` flags).
(deftest plugin-manifest-matches-the-generated-tools
  (let [tools (json/parse-string (slurp "tools.json") true)
        eca   (:customTools (json/parse-string (slurp "plugins/event-modeling/eca.json") true))]
    (is (= (set (keys tools)) (set (keys eca)))
        "the plugin advertises exactly the tools tools.json defines")
    (doseq [[tool definition] tools]
      (is (= (:description definition) (get-in eca [tool :description]))
          (str tool "'s description is in sync"))
      (is (= (:command definition) (get-in eca [tool :command]))
          (str tool "'s command is in sync"))
      (is (= (:schema definition) (get-in eca [tool :schema]))
          (str tool "'s schema is in sync")))))

;; The emcli_query tool template must pass the pipeline as the --query flag and
;; quote it: babashka.cli drops a bare positional (so `:query` stays nil and
;; do-query dies), and quoting keeps spaces and `|` from being shell-split.
(deftest emcli-query-tool-passes-pipeline-as-quoted-flag
  (let [command (get-in (#'cli/build-tools) ["emcli_query" :command])]
    (is (clojure.string/starts-with? command "emcli query --query"))
    (is (clojure.string/includes? command "'{{query}}'"))))

;; emcli_resolve takes one opaque value, and resolved names may contain spaces,
;; so the placeholder must be single-quoted too.
(deftest emcli-resolve-tool-quotes-queries-value
  (let [command (get-in (#'cli/build-tools) ["emcli_resolve" :command])]
    (is (clojure.string/starts-with? command "emcli resolve --queries"))
    (is (clojure.string/includes? command "'{{queries}}'"))))
