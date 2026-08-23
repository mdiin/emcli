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
            [emcli.server :as server]
            [emcli.wireframe :as wf]))

(def ^:private default-server "http://localhost:8090")

(declare format-usage-line)

(defn- server-url [opts] (or (:server opts) default-server))

(defn- emit [x] (println (json/generate-string x {:pretty true})))
(defn- die [msg] (binding [*out* *err*] (println msg)) (System/exit 1))

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
   "slice"      {"add" "add-slice" "reorder" "reorder-slice" "status" "set-slice-status"
                 "kind" "set-slice-kind" "delete" "delete-slice"}
   "element"    {"add" "create-element" "add-field" "add-field" "remove-field" "remove-field"
                 "context" "set-element-context"
                 "swimlane" "assign-swimlane" "image" "set-image-url"
                 "add-origin" "add-field-origin" "remove-origin" "remove-field-origin"
                 "rename" "rename-element" "delete" "delete-element"}
   "wireframe"  {"add-node"        "add-wireframe-node"
                 "add-node-before" "add-wireframe-node-before"
                 "delete-node"     "delete-wireframe-node"
                 "set-attr"        "set-wireframe-attr"
                 "set-text"        "set-wireframe-text"}
   "placement"  {"add" "place-element" "reorder" "reorder-placement" "remove" "remove-placement"}
   "connection" {"add" "connect" "remove" "disconnect"
                 "add-derivation" "add-derivation" "remove-derivation" "remove-derivation"}
   "spec"       {"add" "add-specification" "delete" "delete-specification"}
   "step"       {"add" "add-spec-step" "error" "add-error-step" "remove" "remove-spec-step"
                 "add-example" "add-step-example" "remove-example" "remove-step-example"
                 "expect-empty" "set-step-expect-empty"}})

(defn resolve-command
  "The flat authoring command for an (entity, verb) pair, or nil."
  [group verb]
  (get-in command-groups [group verb]))

(defn- do-authoring [group verb opts]
  (let [command (resolve-command group verb)
        payload (dissoc opts :server)
        resp    (request :post (str (server-url opts) "/authoring/" command) payload)
        body    (parse-body resp)]
    (if (and (= 200 (:status resp)) (:ok body))
      (emit (:result body))
      (die (str "✗ " group " " verb ": " (:message body)
                "\n\nUsage: " (format-usage-line group verb))))))

(defn- do-serve [opts]
  (let [port  (parse-long (str (or (:port opts) "8090")))
        name  (or (:name opts) "model")
        file  (:file opts)
        {:keys [stop]} (server/start! {:port port :model-name name :file file})]
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable stop))
    (println (str "emcli serving model \"" name "\" on http://localhost:" port))
    (when file (println (str "  persisting to:        " file)))
    (println "  change stream (SSE):  GET  /stream")
    (println "  authoring:            POST /authoring/<command>")
    (println "  snapshot:             GET  /model")
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
   "placement"  "timelines[].slices[].placements[].id"
   "connection" "connections[].id"
   "spec"       "timelines[].slices[].specifications[].id"
   "step"       "timelines[].slices[].specifications[].steps[].id"
   "from"       "elements[].id"
   "to"         "elements[].id"})

;; Valid keyword values per (command, flag) — only listed when the rule enforces
;; a bounded set; free-form keyword flags are left without :values.
(def ^:private param-enums
  {"add-slice"        {"kind"       ["state_change" "state_view" "automation"]}
   "set-slice-status" {"new-status" ["created" "in_progress" "done" "informational"]}
   "set-slice-kind"   {"new-kind"   ["state_change" "state_view" "automation"]}
   "create-element"   {"kind"       ["command" "event" "read_model" "screen" "automation"]}
   "add-spec-step"    {"clause"     ["given_step" "when_step" "then_step"]}})

;; Free-text notes per (registry) flag name — shown in `--manifest` and
;; `<entity> <verb> help`. Currently only "id" needs one: it's the one
;; optional flag every entity-creating command shares.
(def ^:private param-notes
  {"id" "pre-assign this entity's id instead of auto-generating one; must not already be in use"})

;; Full param specs for composite commands that are not in the registry.
(def ^:private structured-manifest-params
  {"add-field"
   [{:flag "element" :type "int" :required true :ref "elements[].id"}
    {:flag "name" :type "string" :required true :note "field name; replaces any existing field of the same name"}
    {:flag "type" :type "keyword" :required true
     :values ["string" "boolean" "double" "decimal" "long" "custom" "date" "date_time" "uuid" "int"]}
    {:flag "cardinality" :type "keyword" :required false :values ["single" "list"]}]
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
      :note "wireframe tag, e.g. button, input, row, col, text"}
     {:flag "parent" :type "string" :required false
      :note "node id (nN) to append under; omit to append at the root"}]
   "add-wireframe-node-before"
   [{:flag "element" :type "int" :required true :ref "elements[].id"
     :note "must be a screen element"}
    {:flag "before" :type "string" :required true
     :note "node id (nN) of the existing sibling node to insert before"}
    {:flag "tag" :type "string" :required true
     :note "wireframe tag, e.g. button, input, row, col, text"}]
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
     :note "node id (nN) as shown by element show-wireframe"}
    {:flag "attr" :type "string" :required true
     :note "attribute name, e.g. label, placeholder, field-name"}
    {:flag "value" :type "string" :required true
     :note "new value for the attribute"}]
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

(def ^:private wireframe-show-params
  [{:flag "element" :type "int" :required true :ref "elements[].id"
    :note "must be a screen element"}])

(defn- type->json-schema-type [t]
  (case t
    "int"     "integer"
    "keyword" "string"
    "boolean" "boolean"
    "string"))

(defn- param->json-schema-property [{:keys [flag type note ref values]}]
  (let [desc (str/join ". "
               (remove nil?
                 [(when (seq (str note)) note)
                  (when (seq (str ref)) (str "Ref: " ref))]))
        desc (if (seq desc) desc (str flag " value"))
        prop (cond-> {"type" (type->json-schema-type type)
                      "description" desc}
               (seq values) (assoc "enum" values))]
    [flag prop]))

(defn- build-tools []
  (vec
   (for [[group verbs] (sort command-groups)]
     (let [tool-name   (str/replace (str "emcli_" group) "-" "_")
           all-verbs   (sort (keys verbs))
           ;; For wireframe, inject "show" into the verb enum
           verb-enum   (if (= group "wireframe")
                         (sort (conj (set all-verbs) "show"))
                         all-verbs)
           ;; Collect all params across all verbs (union by flag name)
           all-params  (reduce
                        (fn [acc verb]
                          (let [cmd    (resolve-command group verb)
                                params (command->manifest-params cmd)]
                            (into acc (map (fn [p] [(:flag p) p]) params))))
                        {}
                        all-verbs)
           ;; For wireframe, also union in the show params
           all-params  (if (= group "wireframe")
                         (into all-params (map (fn [p] [(:flag p) p]) wireframe-show-params))
                         all-params)
           properties  (into {} (map param->json-schema-property (vals all-params)))]
       {:name        tool-name
        :description (group-descriptions group)
        :input_schema
        {"type"       "object"
         "properties"
         {"verb" {"type"        "string"
                  "enum"        (vec verb-enum)}
          "args" {"type"        "object"
                  "description" "Flags for the chosen verb. See each property for details."
                  "properties"  properties
                  "additionalProperties" true}}
         "required" ["verb"]}}))))

(defn- export-tools []
  (emit (build-tools)))

;; --- human-readable usage formatting ---------------------------------------

(defn- format-param-signature [{:keys [flag type required values]}]
  (let [type-str (if values (str/join "|" values) type)
        inner    (str "--" flag " <" type-str ">")]
    (if required inner (str "[" inner "]"))))

(defn- format-usage-suffix [group verb]
  (let [params (command->manifest-params (resolve-command group verb))]
    (str/join " " (map format-param-signature params))))

(defn- format-usage-line [group verb]
  (let [suffix (format-usage-suffix group verb)]
    (str "emcli " group " " verb (when (seq suffix) (str " " suffix)))))

(defn- print-verb-help [group verb]
  (println (str "Usage: " (format-usage-line group verb)))
  (let [params (command->manifest-params (resolve-command group verb))]
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

;; --- help & dispatch -------------------------------------------------------

(defn- print-group [group]
  (let [verbs (cond-> (sort (keys (command-groups group)))
                (= group "wireframe") (concat ["show"]))]
    (println (str "  " group))
    (doseq [v verbs]
      (println (str "    " group " " v)))))

(defn- print-help []
  (println "emcli — author Event Models from the command line\n")
  (println "Usage: emcli <entity> <verb> [--opt value ...] [--server URL]")
  (println "       emcli <command> [...]            (process / inspect commands)\n")
  (println "Discovery:")
  (println "  --manifest                              machine-readable JSON schema of all commands")
  (println "  <entity>                                list verbs + signatures for that entity")
  (println "  <entity> <verb> help                   show options for a specific verb\n")
  (println "Process:")
  (println "  serve     [--port 8090] [--name NAME] [--file PATH]")
  (println "                                          start the model server (SSE + authoring).")
  (println "                                          --file loads/persists the model as EDN,")
  (println "                                          flushed on every write for crash recovery.\n")
  (println "Inspect:")
  (println "  show                                    print the canonical model snapshot (includes entity ids)")
  (println "  validate                                report slices/specs/elements not yet complete")
  (println "  resolve   --queries \"name[:kind],...\"    resolve human-readable names to candidate entity ids")
  (println "                                          kind is one of timeline|swimlane|slice|element|specification")
  (println "  export    [--out FILE]                  export the eventmodeling.schema.json")
  (println "  import    --in FILE                     import an eventmodeling.schema.json\n")
  (println "Authoring (grouped by entity — `emcli <entity>` lists an entity's verbs):")
  (doseq [group (keys command-groups)] (print-group group)))

(defn- print-group-help [group]
  (println (str "emcli " group " <verb> [--server URL]\n"))
  (let [verbs    (cond-> (sort (keys (command-groups group)))
                   (= group "wireframe") (concat ["show"]))
        max-verb (apply max (map count verbs))]
    (doseq [v verbs]
      (let [pad    (str/join (repeat (- max-verb (count v)) " "))
            suffix (when-not (= v "show")
                     (format-usage-suffix group v))]
        (println (str "  " group " " v pad
                      (when (seq suffix) (str "  " suffix))
                      (when (= v "show") "  --element <int>")))))))

(def ^:private meta-commands #{"serve" "show" "validate" "resolve" "export" "import" "help"})

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
      (= "export" head)   (do-export (cli/parse-opts (rest argv)))
      (= "import" head)   (do-import (cli/parse-opts (rest argv)))

      (command-groups head)
      (let [verb     (second argv)
            third    (nth argv 2 nil)]
        (cond
          (or (nil? verb) (= "help" verb)) (print-group-help head)
          ;; show is CLI-only (not a server command)
          (and (= head "wireframe") (= verb "show"))
          (do-show-wireframe (cli/parse-opts (drop 2 argv)))
          (resolve-command head verb)
          (if (= "help" third)
            (print-verb-help head verb)
            (let [opts (cli/parse-opts (drop 2 argv))]
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
