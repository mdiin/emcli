(ns emcli.cli
  "The ModelAuthoring surface as a command-line tool (the CLI boundary). A local
  single operator drives one model. Authoring subcommands are thin clients that
  POST to a running `emcli serve` process, so every mutation flows through the
  same in-memory model that feeds the SSE change stream — that is what makes a
  frontend see edits live (DeltaPerMutation)."
  (:require [babashka.cli :as cli]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [emcli.commands :as cmd]
            [emcli.query :as q]
            [emcli.server :as server]
            [emcli.wireframe :as wf]))

(def ^:private default-server "http://localhost:8090")

(declare format-usage-line)

(defn- server-url [opts] (or (:server opts) default-server))

(defn- emit [x] (println (json/generate-string x {:pretty true})))
(defn- die [msg] (binding [*out* *err*] (println msg)) (System/exit 1))

(defn- startup-failure
  "The report for a `serve` that could not start. A store the app refuses to load
  is named with its file and the reasons it cannot be used; any other failure is
  still named with the port and its cause."
  [port e]
  (let [{:keys [error file violations]} (ex-data e)]
    (if (= :invalid-store error)
      (let [reasons (->> (if (seq violations) (map :message violations) [(ex-message e)])
                         (remove str/blank?))]
        (str "✗ " file " cannot be used as a model"
             (when (seq reasons) (str "\n  " (str/join "\n  " reasons)))))
      (str "✗ could not start the model server on port " port ": "
           (or (ex-message e) (str (class e)))))))

;; --- HTTP helpers ----------------------------------------------------------

(defn- request [method url & [body]]
  (try
    (http/request (cond-> {:method method :uri url :throw false
                           :headers {"Content-Type" "application/json"}}
                    body (assoc :body (json/generate-string body))))
    (catch Exception e
      (die (str "Could not reach emcli server at " url
                "\n  Is `emcli serve` running? (" (ex-message e) ")")))))

(defn- parse-body [resp] (some-> (:body resp) (json/parse-string true)))



;; --- subcommands -----------------------------------------------------------

;; --- entity-grouped subcommands -------------------------------------------
;; Commands are grouped under their entity noun for a nicer authoring UX
;; (`slice add`, `timeline delete`, ...). This is a CLI affordance only: each
;; (group, verb) resolves to the flat command the server's /authoring/<command>
;; endpoint expects (and which maps to a ModelAuthoring operation).
(def command-groups
  {"timeline"   {"add" "create-timeline" "rename" "rename-timeline" "delete" "delete-timeline"}
   "swimlane"   {"add" "create-swimlane" "rename" "rename-swimlane"
                 "reorder" "reorder-swimlane" "delete" "delete-swimlane"}
   "slice"      {"add" "add-slice" "rename" "rename-slice" "reorder" "reorder-slice" "status" "set-slice-status"
                 "type" "set-slice-type" "delete" "delete-slice"}
   "element"    {"add" "create-element" "add-field" "add-field" "remove-field" "remove-field"
                 "rename-field" "rename-field"
                 "context" "set-element-context"
                 "swimlane" "assign-swimlane" "image" "set-image-url"
                 "add-origin" "add-field-origin" "remove-origin" "remove-field-origin"
                 "rename" "rename-element" "delete" "delete-element"}
   "wireframe"  {"add-node"        "add-wireframe-node"
                 "add-node-before" "add-wireframe-node-before"
                 "delete-node"     "delete-wireframe-node"
                 "set-attr"        "set-wireframe-attr"
                 "set-text"        "set-wireframe-text"
                 "move-node"       "move-wireframe-node"
                 "apply"           "replace-wireframe"}
   "placement"  {"add" "place-element" "reorder" "reorder-placement" "remove" "remove-placement"}
   "connection" {"add" "connect" "remove" "disconnect"
                 "add-derivation" "add-derivation" "remove-derivation" "remove-derivation"}
   "spec"       {"add" "add-specification" "rename" "rename-specification" "delete" "delete-specification"}
   "step"       {"add" "add-spec-step" "error" "add-error-step" "remove" "remove-spec-step"
                 "rename-error" "rename-error-step"
                 "add-example" "add-step-example" "remove-example" "remove-step-example"
                 "expect-empty" "set-step-expect-empty"}})

;; Verbs a group offers that the CLI answers itself, with no authoring command
;; behind them: reads, not operations. Listed with their params wherever the
;; group's verbs are (help, tools), alongside the authoring verbs.
(def ^:private cli-only-verbs
  {"wireframe" {"show" [{:flag "element" :type "int" :required true}]
                "tags" [{:flag "tag" :type "string" :required false
                         :note "a tag's attributes and an example; omit to list every tag"}]}})

(defn- group-verbs
  "Every verb of `group`, authoring and CLI-only alike, sorted."
  [group]
  (sort (concat (keys (command-groups group)) (keys (cli-only-verbs group)))))

(defn resolve-command
  "The flat authoring command for an (entity, verb) pair, or nil."
  [group verb]
  (get-in command-groups [group verb]))

(defn- do-serve [opts]
  (let [port  (parse-long (str (or (:port opts) "8090")))
        name  (or (:name opts) "model")
        file  (:file opts)
        ;; a store the app cannot load is a user error, not a crash: report it
        ;; like every other one (stderr + exit 1).
        {:keys [stop]} (try
                         (server/start! {:port port :model-name name :file file})
                         (catch Exception e
                           (die (startup-failure port e))))]
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable stop))
    (println (str "emcli serving model \"" name "\" on http://localhost:" port))
    (when file (println (str "  persisting to:        " file)))
    (println "  change stream (SSE):  GET  /stream")
    (println "  authoring:            POST /authoring/<command>")
    (println "  snapshot:             GET  /snapshot")
    (println "  authoring view:       GET  /model")
    (println "  export / validate:    GET  /export | /validate")
    (println "  import:               POST /import")
    (println "  resolve:              POST /resolve")
    @(promise)))

(defn- do-show [opts]
  (emit (parse-body (request :get (str (server-url opts) "/model")))))

(defn- json->wireframe
  "Reconstruct a wireframe vector from its JSON-deserialized form.
  String tags become keywords; :-id keys are already restored by parse-string."
  [v]
  (when (vector? v)
    (let [tag  (keyword (first v))
          rest- (map #(cond (vector? %) (json->wireframe %)
                            (map? %)    %
                            :else       %)
                     (rest v))]
      (into [tag] rest-))))

(defn- do-show-wireframe [opts]
  (let [eid  (or (:element opts) (die "show-wireframe requires --element <id>"))
        body (parse-body (request :get (str (server-url opts) "/model")))
        el   (first (filter #(= (parse-long (str eid)) (:id %)) (:elements body)))]
    (cond
      (nil? el)   (die (str "✗ element " eid " does not exist"))
      (nil? (:wireframe el)) (die (str "✗ element " eid " has no wireframe"))
      :else       (println (wf/format-tree (json->wireframe (:wireframe el)))))))

;; --- authoring round trip --------------------------------------------------

(def ^:private wireframe-actions
  {"add-node" "added" "add-node-before" "added" "move-node" "moved"
   "set-attr" "updated" "set-text" "updated" "delete-node" "deleted"})

(defn- wireframe-outcome
  "What a successful layout edit prints (LayoutEditRevealsResult): the node it
  created, moved or changed, then the screen's resulting layout as `wireframe
  show` prints it - so the outcome, order and nesting included, can be checked
  without another call."
  [verb {:keys [result node]}]
  (let [tree (some-> (:wireframe result) json->wireframe)
        tag  (when (and tree node (not= "delete-node" verb)) (first (wf/find-node tree node)))]
    (str/join "\n"
              [(if (= "apply" verb)
                 "applied"
                 (str/join " " (remove nil? [(wireframe-actions verb) node (some-> tag str)])))
               (if tree
                 (wf/format-tree tree)
                 (str "element " (:id result) " has no layout now"))])))

(defn authoring-output
  "What an authoring verb prints, given its parsed flags and the server's
  response ({:status :body}, body parsed from JSON): {:out text} for stdout, or
  {:error text} for stderr. A wireframe edit prints its outcome as a tree unless
  --json asks for the element; a rejection whose message already names the
  remedy is not followed by the generic usage line."
  [group verb opts {:keys [status body]}]
  (cond
    (not (and (= 200 status) (:ok body)))
    {:error (str "✗ " group " " verb ": " (:message body)
                 (when-not (:remedy body) (str "\n\nUsage: " (format-usage-line group verb))))}

    (or (:json opts) (not= "wireframe" group))
    {:out (json/generate-string (:result body) {:pretty true})}

    :else
    {:out (wireframe-outcome verb body)}))

(defn- do-authoring [group verb opts]
  (let [command (resolve-command group verb)
        payload (dissoc opts :server :json)
        resp    (request :post (str (server-url opts) "/authoring/" command) payload)
        {:keys [out error]} (authoring-output group verb opts
                                              {:status (:status resp) :body (parse-body resp)})]
    (if error (die error) (println out))))

(defn- tags-output
  "What `wireframe tags [--tag X]` prints: every tag, one tag's detail, or - for
  a name that is no tag - an error plus the list, so the next call can succeed."
  [tag]
  (let [kw (some-> tag str (str/replace #"^:" "") keyword)]
    (cond
      (nil? kw)            {:out (wf/tag-list-text)}
      (wf/tag-detail-text kw) {:out (wf/tag-detail-text kw)}
      :else                {:out (wf/tag-list-text) :error (str "unknown tag: " (name kw))})))

(defn- do-wireframe-tags [opts]
  (let [{:keys [out error]} (tags-output (:tag opts))]
    (println out)
    (when error (die (str "✗ " error)))))

(defn- do-validate [opts]
  (emit (parse-body (request :get (str (server-url opts) "/validate")))))

;; --queries "name[:kind_hint],..." — a batch, so a whole turn's worth of
;; mentioned names resolves in one request (NameResolution.resolve).
(defn- parse-resolve-queries [s]
  (->> (str/split (str s) #",")
       (remove str/blank?)
       (mapv (fn [tok]
               (let [[n k] (str/split (str/trim tok) #":" 2)]
                 (cond-> {:name (str/trim n)}
                   (not (str/blank? (or k ""))) (assoc :kind_hint (str/trim k))))))))

(defn- do-resolve [opts]
  (let [queries (or (:queries opts) (die "resolve requires --queries \"name[:kind_hint][,name[:kind_hint]...]\""))
        resp    (request :post (str (server-url opts) "/resolve") {:queries (parse-resolve-queries queries)})
        body    (parse-body resp)]
    (if (= 200 (:status resp))
      (emit (:results body))
      (die (str "✗ resolve: " (:message body))))))

;; --query "<pipeline>" — a read-only structural query (ModelQuery.query).
;; --relations prints the vocabulary locally, with no server round trip, so it
;; works with the server down (like --manifest/--export-tools).
(defn- do-query [opts]
  (if (:relations opts)
    (emit (q/relations-doc))
    (let [query (or (:query opts)
                    (die "query requires --query \"<pipeline>\" (or --relations for the vocabulary)"))
          resp  (request :post (str (server-url opts) "/query") {:query query})
          body  (parse-body resp)]
      (if (= 200 (:status resp))
        (emit (:results body))
        (die (str "✗ query: " (:message body)))))))

(defn- do-export [opts]
  (let [resp (request :get (str (server-url opts) "/export"))
        body (:body resp)]
    (if (= 200 (:status resp))
      (if-let [out (:out opts)]
        (do (spit out body) (println (str "Exported to " out)))
        (println body))
      (die (str "✗ export: model is not export-ready\n" body)))))

(defn- do-import [opts]
  (let [in (or (:in opts) (die "import requires --in <file>"))
        resp (request :post (str (server-url opts) "/import") (json/parse-string (slurp in) true))]
    (if (= 200 (:status resp))
      (println (str "Imported model from " in))
      (die (str "✗ import: " (:body resp))))))

;; --- manifest (--manifest) -------------------------------------------------

;; One-sentence descriptions for each command group, used in tool manifests.
(def ^:private group-descriptions
  {"timeline"   "Manage timelines (vertical swim-lane columns) in the event model."
   "swimlane"   "Manage swimlanes (horizontal rows) in the event model."
   "slice"      "Manage slices (vertical time segments) within a timeline."
   "element"    "Manage elements (commands, events, read models, screens, automations) and their fields."
   "wireframe"  "Manage wireframe node trees on screen elements and display them."
   "placement"  "Manage element placements within timeline slices."
   "connection" "Manage connections and field derivations between elements."
   "spec"       "Manage specifications attached to slices."
   "step"       "Manage steps and examples within a specification."})

;; Semantic refs for integer flags: the JSON path in the `emcli show` output
;; where the id can be found.
(def ^:private param-refs
  {"timeline"   "timelines[].id"
   "slice"      "timelines[].slices[].id"
   "element"    "elements[].id"
   "lane"       "swimlanes[].id"
   "connection" "connections[].id"
   "spec"       "timelines[].slices[].specifications[].id"
   "step"       "timelines[].slices[].specifications[].steps[].id"
   "from"       "elements[].id"
   "to"         "elements[].id"
   ;; Reorder-placement's --before/--after name an element id. Both flag names
   ;; are used only by reorder-placement among registry commands (the wireframe
   ;; add-node-before command's --before is a node id passed through the literal
   ;; structured params, which never consult this map), so a global entry is
   ;; enough and leaks nowhere.
   "before"     "elements[].id"
   "after"      "elements[].id"})

;; Valid keyword values per (command, flag) — only listed when the rule enforces
;; a bounded set; free-form keyword flags are left without :values.
(def ^:private param-enums
  {"add-slice"        {"slice-type"       ["state_change" "state_view" "automation"]}
   "set-slice-status" {"new-status" ["created" "in_progress" "done" "informational"]}
   "set-slice-type"   {"new-slice-type"   ["state_change" "state_view" "automation"]}
   "create-element"   {"element-type"       ["command" "event" "read_model" "screen" "automation"]}
   "add-spec-step"    {"clause"     ["given_step" "when_step" "then_step"]}
   "reorder-placement" {"position"  ["front" "back"]}
   "reorder-slice"    {"position"   ["front" "back"]}})

;; Free-text notes per (registry) flag name — shown in `--manifest` and
;; `<entity> <verb> help`. "id" is the one optional flag every entity-creating
;; command shares; "before"/"after" take an element id and are the alternative
;; movers to "position" on reorder-placement (exactly one is required).
(def ^:private param-notes
  {"id" "pre-assign this entity's id instead of auto-generating one; must not already be in use"
   "position" "move to the front or back; exactly one of position/before/after is required"
   "before" "id to move/insert immediately before; exactly one of position/before/after is required"
   "after" "id to move/insert immediately after; exactly one of position/before/after is required"})

;; Full param specs for composite commands that are not in the registry.
(def ^:private structured-manifest-params
  {"add-field"
   [{:flag "element" :type "int" :required true :ref "elements[].id"}
    {:flag "name" :type "string" :required true :note "field name; replaces any existing field of the same name"}
    {:flag "type" :type "keyword" :required true
     :values ["string" "boolean" "double" "decimal" "long" "custom" "date" "date_time" "uuid" "int"]}
    {:flag "cardinality" :type "keyword" :required false :values ["single" "list"]}
    {:flag "optional" :type "boolean" :required false
     :note "whether the field may be absent (Field.optional)"}
    {:flag "subfield-of" :type "string" :required false
     :note "name of a field of the same element to nest this field inside (a Field list is recursive, so one level is added per call, and the parent must be a field of the element itself)"}]
   "remove-field"
   [{:flag "element" :type "int" :required true :ref "elements[].id"}
    {:flag "name" :type "string" :required true :note "field name to remove"}]
   "add-field-origin"
   [{:flag "element" :type "int" :required true :ref "elements[].id"}
    {:flag "field" :type "string" :required true :note "field name on the element"}
    {:flag "origin" :type "keyword" :required true
     :note "how the field is introduced: user_input, generated, external"}]
   "remove-field-origin"
   [{:flag "element" :type "int" :required true :ref "elements[].id"}
    {:flag "field" :type "string" :required true :note "field name whose origin override should be removed"}]
   "add-derivation"
   [{:flag "connection" :type "int" :required true :ref "connections[].id"}
    {:flag "target" :type "string" :required true :note "target field name on the to-element"}
    {:flag "from" :type "string" :required true
     :note "comma-separated source field names from the from-element"}]
   "remove-derivation"
   [{:flag "connection" :type "int" :required true :ref "connections[].id"}
    {:flag "target" :type "string" :required true :note "target field name to remove the derivation for"}]
   "add-step-example"
   [{:flag "step" :type "int" :required true :ref "timelines[].slices[].specifications[].steps[].id"}
    {:flag "field-name" :type "string" :required true
     :note "example field name; replaces any existing example for the same field"}
    {:flag "field-value" :type "string" :required true}]
   "remove-step-example"
   [{:flag "step" :type "int" :required true :ref "timelines[].slices[].specifications[].steps[].id"}
    {:flag "field-name" :type "string" :required true :note "example field name to remove"}]
   "add-wireframe-node"
   [{:flag "element" :type "int" :required true :ref "elements[].id"
     :note "must be a screen element"}
     {:flag "tag" :type "string" :required true
      :note "wireframe tag; `emcli wireframe tags` lists them, `--tag <name>` gives one's attributes"}
     {:flag "text" :type "string" :required false
      :note "new node's text content on a text-children tag (h1, h2, h3, text, span), or the required text attribute on :alert; rejected for any other tag"}
     {:flag "parent" :type "string" :required false
      :note "node id (nN) to append under; omit to append at the root"}]
   "add-wireframe-node-before"
   [{:flag "element" :type "int" :required true :ref "elements[].id"
     :note "must be a screen element"}
    {:flag "before" :type "string" :required true
     :note "node id (nN) of the existing sibling node to insert before"}
    {:flag "tag" :type "string" :required true
     :note "wireframe tag; `emcli wireframe tags` lists them, `--tag <name>` gives one's attributes"}
    {:flag "text" :type "string" :required false
     :note "new node's text content on a text-children tag (h1, h2, h3, text, span), or the required text attribute on :alert; rejected for any other tag"}]
   "set-wireframe-text"
   [{:flag "element" :type "int" :required true :ref "elements[].id"
     :note "must be a screen element"}
    {:flag "node" :type "string" :required true
     :note "node id (nN) of a text-children node: h1, h2, h3, text, span"}
    {:flag "text" :type "string" :required true
     :note "text content to set on the node"}]
   "set-wireframe-attr"
   [{:flag "element" :type "int" :required true :ref "elements[].id"
     :note "must be a screen element"}
    {:flag "node" :type "string" :required true
     :note "node id (nN) as shown by wireframe show"}
    {:flag "attr" :type "string" :required true
     :note "attribute name, e.g. label, placeholder, field-name"}
    {:flag "value" :type "string" :required true
     :note "new value for the attribute"}]
   "move-wireframe-node"
   [{:flag "element" :type "int" :required true :ref "elements[].id"
     :note "must be a screen element"}
    {:flag "node" :type "string" :required true
     :note "node id (nN) to move, with its whole subtree; every node keeps its id"}
    {:flag "before" :type "string" :required false
     :note "node id (nN) to place the node immediately before; exactly one of before/parent is required"}
    {:flag "parent" :type "string" :required false
     :note "node id (nN) of a container (canvas, row, col) to append the node to as its last child; exactly one of before/parent is required"}]
   "replace-wireframe"
   [{:flag "element" :type "int" :required true :ref "elements[].id"
     :note "must be a screen element"}
    {:flag "tree" :type "string" :required true
     :note "the whole target layout in the format `wireframe show` prints: one `[nX] :tag {attributes} \"text\"` line per node, two spaces of indentation per level; keep [nX] on existing nodes, omit it on new ones; nodes left out are deleted"}]
   "resolve"
   [{:flag "queries" :type "string" :required true
     :note "comma-separated name[:kind_hint] entries, e.g. \"Baz:slice,Snaz\"; kind_hint is one of timeline|swimlane|slice|element|specification and only ranks candidates, never filters them"}]})

(def ^:private type-names {:str "string" :int "int" :kw "keyword" :bool "boolean"})

(defn- registry-param->manifest [command [opt-key _ type required?]]
  (let [flag (name opt-key)
        base {:flag flag :type (type-names type) :required required?}
        ref  (param-refs flag)
        vals (get-in param-enums [command flag])
        note (param-notes flag)]
    (cond-> base
      ref  (assoc :ref ref)
      vals (assoc :values vals)
      note (assoc :note note))))

(defn- command->manifest-params [command]
  (if-let [reg-params (:params (cmd/registry command))]
    (mapv #(registry-param->manifest command %) reg-params)
    (or (structured-manifest-params command) [])))

(defn- build-manifest []
  {:_instructions (str "Run `emcli show` to read entity ids required by authoring commands, or "
                       "`emcli resolve` to look up ids by name (e.g. from an LLM's conversational "
                       "context) without pulling the whole model. Pass flags as --flag value.")
   :commands
   {"resolve" {:params (command->manifest-params "resolve")}}
   :groups
   (into (sorted-map)
         (for [[group verbs] (sort command-groups)]
           [group (into (sorted-map)
                        (for [[verb command] (sort verbs)]
                          [verb {:params (command->manifest-params command)}]))]))})

;; --- tools (--export-tools) ------------------------------------------------

(defn- verb-params
  "A verb's params: its authoring command's, or a CLI-only verb's own."
  [group verb]
  (or (get-in cli-only-verbs [group verb])
      (command->manifest-params (resolve-command group verb))))

(defn- verb-flag-summary [group]
  (let [parts       (for [verb (group-verbs group)
                          :let [params (verb-params group verb)]
                          :let [flags (map (fn [{:keys [flag required]}]
                                            (if required
                                              (str "--" flag)
                                              (str "[--" flag "]")))
                                          params)]]
                      (str verb " (" (str/join " " flags) ")"))]
    (str "Verbs: " (str/join ", " parts))))

(defn- build-tools []
  (into (sorted-map)
        (concat
         (for [group (sort (keys command-groups))]
           (let [tool-name  (str/replace (str "emcli_" group) "-" "_")
                 verb-enum  (group-verbs group)
                 desc       (str (group-descriptions group)
                                 "\n"
                                 (verb-flag-summary group))]
             [tool-name
              {:description desc
               :command     (str "emcli " group " {{verb}} {{args}}")
               :schema
               {"properties"
                {"verb" {"type" "string"
                         "enum" (vec verb-enum)}
                 "args" {"type"        "string"
                         "description" "Space-separated --flag value pairs for the chosen verb. Optional flags may be omitted."}}
                "required" ["verb"]}}]))
         [["emcli_resolve"
           {:description "Resolve one or more element/timeline/slice names to their integer ids. Use before authoring commands when you have names but not ids."
            ;; single-quote the value so names containing spaces stay one argument
            :command     "emcli resolve --queries '{{queries}}'"
            :schema
            {"properties"
             {"queries" {"type"        "string"
                         "description" (-> structured-manifest-params (get "resolve") first :note)}}
             "required" ["queries"]}}]
          ["emcli_query"
           {:description (q/tool-description)
            ;; `query` takes the pipeline as the --query flag (a bare positional is
            ;; discarded by babashka.cli); quote it so spaces and `|` survive the shell.
            :command     "emcli query --query '{{query}}'"
            :schema
            {"properties"
             {"query" {"type"        "string"
                       "description" "A pipeline, e.g. `element:42 | slice` or `slice:7 | elements {index}`. Root first, then `|`-separated stages; see the tool description for the grammar and relation vocabulary."}}
             "required" ["query"]}}]
          ["emcli_validate"
           {:description "Run Event Model validation and return all warnings and errors."
            :command     "emcli validate"
            :schema
            {"properties" {}
             "required"   []}}]])))

(defn- export-tools []
  (emit (build-tools)))

;; --- human-readable usage formatting ---------------------------------------

(defn- format-param-signature [{:keys [flag type required values]}]
  (let [type-str (if values (str/join "|" values) type)
        inner    (str "--" flag " <" type-str ">")]
    (if required inner (str "[" inner "]"))))

(defn- format-usage-suffix [group verb]
  (let [params (verb-params group verb)]
    (str/join " " (map format-param-signature params))))

(defn- format-usage-line [group verb]
  (let [suffix (format-usage-suffix group verb)]
    (str "emcli " group " " verb (when (seq suffix) (str " " suffix)))))

(defn- print-verb-help [group verb]
  (println (str "Usage: " (format-usage-line group verb)))
  (let [params (verb-params group verb)]
    (when (seq params)
      (println "\nOptions:")
      (let [max-flag (apply max (map #(count (:flag %)) params))]
        (doseq [{:keys [flag type required values ref note]} params]
          (let [flag-pad (str/join (repeat (- max-flag (count flag)) " "))
                type-pad (str/join (repeat (max 0 (- 7 (count type))) " "))
                detail   (str/join "  "
                                   (remove nil? [(when values (str/join " | " values))
                                                 (when ref (str "from " ref " in `emcli show`"))
                                                 note]))]
            (println (str "  --" flag flag-pad
                          "  " type type-pad
                          "  " (if required "required" "optional")
                          (when (seq detail) (str "  " detail))))))))))

;; --- authoring option parsing ----------------------------------------------

;; babashka.cli coercion per usage-table type. Everything but booleans stays a
;; string: an unspecced parse auto-coerces "12" to a number and "007" to 7,
;; mangling names and crashing keyword coercion on the server. Int flags stay
;; strings too, so the server's integer check remains the single validator.
(defn- authoring-coerce-spec [command]
  (into {:server :string}
        (map (fn [{:keys [flag type]}]
               [(keyword flag) (if (= "boolean" type) :boolean :string)]))
        (command->manifest-params command)))

(defn- parse-authoring-opts
  "Parse an authoring command's flags against its usage table. Flags outside the
  table (the open wireframe attribute flags) keep babashka.cli's defaults.
  Throws babashka.cli's ex-info on a malformed flag, e.g. a missing value."
  [command args]
  (cli/parse-opts args {:coerce (authoring-coerce-spec command)}))

(defn- cli-parse-error? [e]
  (= :org.babashka/cli (:type (ex-data e))))

;; --- help & dispatch -------------------------------------------------------

(defn- print-group [group]
  (let [verbs (group-verbs group)]
    (println (str "  " group))
    (doseq [v verbs]
      (println (str "    " group " " v)))))

(defn- print-help []
  (println "emcli — author Event Models from the command line\n")
  (println "Usage: emcli <entity> <verb> [--opt value ...] [--server URL]")
  (println "       emcli <command> [...]            (process / inspect commands)\n")
  (println "Discovery:")
  (println "  --manifest                              machine-readable JSON schema of all commands")
  (println "  --export-tools                          generate tools.json: one LLM tool definition per command group")
  (println "  <entity>                                list verbs + signatures for that entity")
  (println "  <entity> <verb> help                   show options for a specific verb\n")
  (println "Process:")
  (println "  serve     [--port 8090] [--name NAME] [--file PATH]")
  (println "                                          start the model server (SSE + authoring).")
  (println "                                          --file loads/persists the model as EDN,")
  (println "                                          flushed on every write for crash recovery.\n")
  (println "Inspect:")
  (println "  show                                    print the authoring view (GET /model; includes entity ids)")
  (println "  validate                                report slices/specs/elements not yet complete")
  (println "  resolve   --queries \"name[:kind],...\"    resolve human-readable names to candidate entity ids")
  (println "                                          kind is one of timeline|swimlane|slice|element|specification")
  (println "  query     --query \"<pipeline>\"            follow relations outward from a root, returning matching entities")
  (println "                                          stages: where, order, select, count, limit, distinct")
  (println "            --relations                   print the query vocabulary (roots, relations, stages)")
  (println "  export    [--out FILE]                  export the eventmodeling.schema.json")
  (println "  import    --in FILE                     import an eventmodeling.schema.json\n")
  (println "Authoring (grouped by entity — `emcli <entity>` lists an entity's verbs):")
  (doseq [group (keys command-groups)] (print-group group)))

(defn- print-group-help [group]
  (println (str "emcli " group " <verb> [--server URL]\n"))
  (let [verbs    (group-verbs group)
        max-verb (apply max (map count verbs))]
    (doseq [v verbs]
      (let [pad    (str/join (repeat (- max-verb (count v)) " "))
            suffix (format-usage-suffix group v)]
        (println (str "  " group " " v pad
                      (when (seq suffix) (str "  " suffix))))))))

(def ^:private meta-commands #{"serve" "show" "validate" "resolve" "query" "export" "import" "help"})

(defn -main [& argv]
  (let [argv (vec (or (seq argv) *command-line-args*))
        head (first argv)]
    (cond
      (or (nil? head) (= "help" head)) (print-help)
      (= "--manifest" head)      (emit (build-manifest))
      (= "--export-tools" head) (export-tools)
      (= "serve" head)    (do-serve (cli/parse-opts (rest argv)))
      (= "show" head)     (do-show (cli/parse-opts (rest argv)))
      (= "validate" head) (do-validate (cli/parse-opts (rest argv)))
      (= "resolve" head)  (do-resolve (cli/parse-opts (rest argv)))
      (= "query" head)    (do-query (cli/parse-opts (rest argv)))
      (= "export" head)   (do-export (cli/parse-opts (rest argv)))
      (= "import" head)   (do-import (cli/parse-opts (rest argv)))

      (command-groups head)
      (let [verb     (second argv)
            third    (nth argv 2 nil)]
        (cond
          (or (nil? verb) (= "help" verb)) (print-group-help head)
          ;; show and tags are CLI-only (not server commands)
          (and (= head "wireframe") (= verb "show"))
          (do-show-wireframe (cli/parse-opts (drop 2 argv)))
          (and (= head "wireframe") (= verb "tags"))
          (if (= "help" third)
            (print-verb-help head verb)
            (do-wireframe-tags (cli/parse-opts (drop 2 argv) {:coerce {:tag :string}})))
          (resolve-command head verb)
          (if (= "help" third)
            (print-verb-help head verb)
            (let [opts (try
                         (parse-authoring-opts (resolve-command head verb) (drop 2 argv))
                         (catch clojure.lang.ExceptionInfo e
                           (if (cli-parse-error? e)
                             (die (str "✗ " head " " verb ": " (ex-message e)
                                       "\n\nUsage: " (format-usage-line head verb)))
                             (throw e))))]
              (if (:help opts)
                (print-verb-help head verb)
                (do-authoring head verb opts))))
          :else (do (binding [*out* *err*]
                      (println (str "Unknown verb: " head " " verb "\n")))
                    (print-group-help head)
                    (System/exit 2))))

      :else (do (binding [*out* *err*] (println (str "Unknown command: " head "\n")))
                (print-help)
                (System/exit 2)))))
