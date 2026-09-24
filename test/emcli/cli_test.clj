(ns emcli.cli-test
  "The CLI's entity-grouped subcommands must cover exactly the flat authoring
  commands the server exposes — every operation reachable, nothing dangling."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [emcli.app :as app]
            [emcli.cli :as cli]
            [emcli.commands :as cmd]
            [emcli.server :as server]
            [emcli.support :as s]
            [emcli.wireframe :as wf]))

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

;; `wireframe tags` is the discovery verb for the tag vocabulary: CLI-only (the
;; vocabulary is fixed, so no server round trip), offered wherever the verbs are.
(deftest wireframe-group-help-includes-tags
  (let [output (with-out-str (#'cli/print-group-help "wireframe"))]
    (is (re-find #"wireframe tags\s+\[--tag <string>\]" output))
    (is (re-find #"wireframe show\s+--element <int>" output))))

(deftest wireframe-tool-offers-tags-verb
  (let [{:keys [description schema]} (get (#'cli/build-tools) "emcli_wireframe")]
    (is (some #{"tags"} (get-in schema ["properties" "verb" "enum"])))
    (is (clojure.string/includes? description "tags ([--tag])"))
    (is (clojure.string/includes? description "show (--element)"))))

(deftest tags-output-lists-details-or-rejects
  (testing "without --tag, every tag is listed"
    (is (= {:out (wf/tag-list-text)} (#'cli/tags-output nil))))
  (testing "with --tag, that tag's detail"
    (is (= {:out (wf/tag-detail-text :button)} (#'cli/tags-output "button")))
    (is (= {:out (wf/tag-detail-text :button)} (#'cli/tags-output ":button"))
        "a leading colon, as tags are written in the docs, is accepted"))
  (testing "an unknown tag is named, and the valid ones listed"
    (let [{:keys [out error]} (#'cli/tags-output "card")]
      (is (= "unknown tag: card" error))
      (is (= (wf/tag-list-text) out)))))

(deftest set-wireframe-attr-node-note-names-current-show-command
  (let [params (#'cli/command->manifest-params "set-wireframe-attr")
        node   (first (filter #(= "node" (:flag %)) params))]
    (testing "the node-id note points operators at the current command name"
      (is (= "node id (nN) as shown by wireframe show" (:note node))))))

;; Authoring flags are parsed against the usage table, not babashka.cli's
;; auto-coercion: an unspecced parse turns "12" into a number and "007" into 7,
;; which crashed keyword coercion on the server and silently mangled names.
(deftest authoring-opts-are-parsed-by-spec
  (let [parse #'cli/parse-authoring-opts]
    (testing "string and keyword flags stay strings, whatever they look like"
      (is (= {:element "7" :field "x" :origin "12"}
             (parse "add-field-origin" ["--element" "7" "--field" "x" "--origin" "12"])))
      (is (= "007" (:name (parse "create-element" ["--name" "007" "--element-type" "screen"]))))
      (is (= "1e3" (:name (parse "remove-field" ["--element" "1" "--name" "1e3"])))))
    (testing "int flags are left as strings for the server to validate"
      (is (= "abc" (:element (parse "remove-field" ["--element" "abc" "--name" "x"])))))
    (testing "boolean flags may be bare or explicit"
      (is (true? (:optional (parse "add-field" ["--element" "1" "--name" "a" "--type" "string" "--optional"]))))
      (is (false? (:optional (parse "add-field" ["--element" "1" "--name" "a" "--type" "string" "--optional" "false"])))))
    (testing "--server is always accepted as a string"
      (is (= "http://x:1" (:server (parse "delete-slice" ["--slice" "1" "--server" "http://x:1"])))))
    (testing "a string flag without a value is a parse error naming the flag"
      (let [e (try (parse "remove-field" ["--element" "1" "--name"]) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (clojure.string/includes? (ex-message e) "--name"))))))

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

;; --- wireframe move-node / apply ---------------------------------------------

(deftest wireframe-move-node-and-apply-verbs
  (is (= "move-wireframe-node" (cli/resolve-command "wireframe" "move-node")))
  (is (= "replace-wireframe" (cli/resolve-command "wireframe" "apply")))
  (testing "their flags are published for help, the manifest and the tools"
    (is (= #{"element" "node" "before" "parent"}
           (set (map :flag (#'cli/command->manifest-params "move-wireframe-node")))))
    (is (= #{"element" "tree"}
           (set (map :flag (#'cli/command->manifest-params "replace-wireframe")))))
    (let [{:keys [schema]} (get (#'cli/build-tools) "emcli_wireframe")]
      (is (some #{"move-node"} (get-in schema ["properties" "verb" "enum"])))
      (is (some #{"apply"} (get-in schema ["properties" "verb" "enum"]))))))

;; --- what a wireframe verb prints (LayoutEditRevealsResult,
;; RejectedLayoutEditNamesRemedy) -------------------------------------------------
;; `cli/authoring-output` is the pure half of the authoring round trip: given
;; the verb, its parsed flags and the server's response ({:status :body}, body
;; parsed from JSON), it returns {:out text} for stdout or {:error text} for
;; stderr (exit 1).

(def ^:private authoring-output cli/authoring-output)

(defn- screen-app []
  (let [a (app/new-app "M")]
    [a (:id (:result (cmd/run a "create-element" {:name "Login" :element-type "screen"})))]))

(defn- run-verb
  "Run `wireframe <verb>` with CLI `opts` against `a` as the CLI does - through
  the server's authoring route, minus the network hop - and return what it
  prints. The CLI never forwards --json (or --server) to the server."
  [a verb opts]
  (let [resp (server/handler a {:request-method :post
                                :uri            (str "/authoring/" (cli/resolve-command "wireframe" verb))
                                :body           (json/generate-string (dissoc opts :json :server))})]
    (authoring-output "wireframe" verb opts
                      {:status (:status resp) :body (json/parse-string (:body resp) true)})))

(defn- tree-shape
  "The addressed tree printed below the first line, as [depth id tag] rows in
  document order (depth from the two-space indentation `wireframe show` uses)."
  [out]
  (vec (for [line  (rest (str/split-lines out))
             :let  [[_ indent id tag] (re-find #"^( *)\[(n\d+)\] (:\S+)" line)]
             :when id]
         [(quot (count indent) 2) id tag])))

(defn- tree-line [out id]
  (first (filter #(str/includes? % (str "[" id "]")) (rest (str/split-lines out)))))

(defn- form-app
  "The transcript's misordered form: n2 Password, n3 Submit, n4 Email."
  []
  (let [[a eid] (screen-app)]
    (cmd/run a "add-wireframe-node" {:element eid :tag "input" :type "password" :label "Password"})
    (cmd/run a "add-wireframe-node" {:element eid :tag "button" :label "Submit"})
    (cmd/run a "add-wireframe-node" {:element eid :tag "input" :type "email" :label "Email"})
    [a eid]))

(deftest add-node-prints-the-new-node-and-the-tree
  (let [[a eid] (screen-app)
        {:keys [out error]} (run-verb a "add-node" {:element (str eid) :tag "input"
                                                    :type "email" :label "Email"})]
    (is (nil? error))
    (is (re-find #"^added n2\b" out))
    (is (= [[0 "n1" ":canvas"] [1 "n2" ":input"]] (tree-shape out)))
    (is (str/includes? (str (tree-line out "n2")) "Email"))))

(deftest add-node-before-prints-the-new-node-and-the-tree
  (let [[a eid] (form-app)
        {:keys [out]} (run-verb a "add-node-before" {:element (str eid) :before "n2" :tag "h1"
                                                     :text "Log in"})]
    (is (re-find #"^added n5\b" out))
    (is (= [[0 "n1" ":canvas"] [1 "n5" ":h1"] [1 "n2" ":input"] [1 "n3" ":button"] [1 "n4" ":input"]]
           (tree-shape out)))
    (is (str/includes? (str (tree-line out "n5")) "Log in"))))

(deftest move-node-prints-the-moved-node-and-the-tree
  (let [[a eid] (form-app)
        {:keys [out]} (run-verb a "move-node" {:element (str eid) :node "n4" :before "n2"})]
    (is (re-find #"^moved n4\b" out))
    (is (= [[0 "n1" ":canvas"] [1 "n4" ":input"] [1 "n2" ":input"] [1 "n3" ":button"]]
           (tree-shape out)))))

(deftest set-attr-and-set-text-print-the-updated-node-and-the-tree
  (let [[a eid] (form-app)]
    (let [{:keys [out]} (run-verb a "set-attr" {:element (str eid) :node "n3"
                                                :attr "label" :value "Sign in"})]
      (is (re-find #"^updated n3\b" out))
      (is (= 4 (count (tree-shape out))))
      (is (str/includes? (str (tree-line out "n3")) "Sign in")))
    (cmd/run a "add-wireframe-node" {:element eid :tag "h1" :text "Log in"})
    (let [{:keys [out]} (run-verb a "set-text" {:element (str eid) :node "n5" :text "Welcome back"})]
      (is (re-find #"^updated n5\b" out))
      (is (str/includes? (str (tree-line out "n5")) "Welcome back")))))

(deftest delete-node-prints-the-deleted-node-and-the-tree
  (let [[a eid] (form-app)]
    (let [{:keys [out]} (run-verb a "delete-node" {:element (str eid) :node "n3"})]
      (is (re-find #"^deleted n3\b" out))
      (is (= [[0 "n1" ":canvas"] [1 "n2" ":input"] [1 "n4" ":input"]] (tree-shape out))))
    (testing "deleting the root reports that the screen has no layout"
      (let [{:keys [out]} (run-verb a "delete-node" {:element (str eid) :node "n1"})]
        (is (re-find #"^deleted n1\b" out))
        (is (re-find #"(?i)no layout" out))
        (is (empty? (tree-shape out)))))))

(deftest apply-prints-the-whole-resulting-tree
  (let [[a eid] (form-app)
        {:keys [out]} (run-verb a "apply" {:element (str eid)
                                           :tree    (str "[n1] :canvas\n"
                                                         "  :col\n"
                                                         "    [n4] :input  {:type :email, :label \"Email\"}\n"
                                                         "    [n2] :input  {:type :password, :label \"Password\"}\n"
                                                         "    [n3] :button  {:label \"Submit\"}")})]
    (is (re-find #"^applied\b" out))
    (is (= [[0 "n1" ":canvas"] [1 "n5" ":col"] [2 "n4" ":input"] [2 "n2" ":input"] [2 "n3" ":button"]]
           (tree-shape out))
        "new nodes carry the ids they were given")))

(deftest json-flag-keeps-the-element-json
  (let [[a eid] (screen-app)
        {:keys [out]} (run-verb a "add-node" {:element (str eid) :tag "divider" :json true})
        el            (json/parse-string out true)]
    (is (= eid (:id el)))
    (is (vector? (:wireframe el)))))

(deftest a-remedy-rejection-prints-no-generic-usage-line
  (let [[a eid] (form-app)]
    (doseq [[verb opts] [["add-node" {:element (str eid) :tag "button" :text "Submit"}]
                         ["add-node" {:element (str eid) :tag "button"}]
                         ["add-node-before" {:element (str eid) :before "n2" :tag "button" :text "Back"}]
                         ["set-attr" {:element (str eid) :node "n3" :attr "variant" :value "bogus"}]
                         ["apply" {:element (str eid) :tree "[n1] :canvas\n  [n3] :button  {:text \"Go\"}"}]]]
      (let [{:keys [out error]} (run-verb a verb opts)]
        (is (nil? out) (str verb " is rejected"))
        (is (str/starts-with? (str error) (str "✗ wireframe " verb ": ")))
        (is (re-find #"label \(required\)" (str error)) (str verb " names the remedy"))
        (is (not (str/includes? (str error) "Usage:"))
            (str verb " does not print the generic usage line"))))
    (testing "the corrected command reaches the operator"
      (let [{:keys [error]} (run-verb a "add-node" {:element (str eid) :tag "button" :text "Submit"})]
        (is (re-find (re-pattern (str "(?m)^\\s*try:\\s+emcli wireframe add-node --element " eid
                                      " --tag button\\b.*--label"))
                     (str error)))))))
