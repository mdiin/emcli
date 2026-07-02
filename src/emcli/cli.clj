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
            [emcli.server :as server]))

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

;; --- structured argument prep (set-fields / set-step-examples) -------------

;; Per the @guidance on rule SetStepExamples in the spec: the CLI must
;; map/validate --examples-json against the Example shape (field_name/
;; field_value, both non-empty) before invoking set-step-examples, rather
;; than relying solely on the ExamplesWellFormed invariant to reject bad
;; data after it's already been sent. A historical bug accepted objects
;; keyed field/value and forwarded them verbatim, degrading to empty strings.
(defn- example-shape-error
  "nil if `examples` is a well-formed array of Example maps, otherwise a
  human-readable description of the problem."
  [examples]
  (cond
    (not (sequential? examples))
    "--examples-json must be a JSON array"

    (not (every? map? examples))
    "--examples-json must be an array of objects"

    (not (every? #(and (contains? % :field_name) (contains? % :field_value)) examples))
    "each example must have \"field_name\" and \"field_value\" keys"

    (not (every? #(and (string? (:field_name %)) (string? (:field_value %))) examples))
    "each example's field_name and field_value must be strings"

    (not (every? #(and (not (str/blank? (:field_name %)))
                        (not (str/blank? (:field_value %))))
                 examples))
    "each example's field_name and field_value must be non-empty"

    :else nil))

(defn- prepare [command opts]
  (cond-> opts
    (and (= command "set-fields") (:fields-json opts))
    (assoc :fields (json/parse-string (:fields-json opts) true))

    (and (= command "set-step-examples") (:examples-json opts))
    (assoc :examples (json/parse-string (:examples-json opts) true))

    (and (= command "set-field-origins") (:origins-json opts))
    (assoc :origins (json/parse-string (:origins-json opts) true))

    (and (= command "set-connection-derivations") (:derivations-json opts))
    (assoc :derivations (json/parse-string (:derivations-json opts) true))))

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
   "element"    {"add" "create-element" "fields" "set-fields" "context" "set-element-context"
                 "swimlane" "assign-swimlane" "image" "set-image-url"
                 "origins" "set-field-origins" "origin" "add-field-origin"
                 "rename" "rename-element" "delete" "delete-element"}
   "placement"  {"add" "place-element" "reorder" "reorder-placement" "remove" "remove-placement"}
   "connection" {"add" "connect" "remove" "disconnect"
                 "derivations" "set-connection-derivations" "derive" "add-derivation"}
   "spec"       {"add" "add-specification" "delete" "delete-specification"}
   "step"       {"add" "add-spec-step" "error" "add-error-step" "remove" "remove-spec-step"
                 "examples" "set-step-examples" "expect-empty" "set-step-expect-empty"}})

(defn resolve-command
  "The flat authoring command for an (entity, verb) pair, or nil."
  [group verb]
  (get-in command-groups [group verb]))

(defn- do-authoring [group verb opts]
  (let [command (resolve-command group verb)
        payload (-> (prepare command opts)
                    (dissoc :server :fields-json :examples-json :origins-json :derivations-json))]
    (when (= command "set-step-examples")
      (when-let [err (example-shape-error (:examples payload))]
        (die (str "✗ " group " " verb ": " err
                  "\n\nExpected shape: [{\"field_name\": \"...\", \"field_value\": \"...\"}, ...]"))))
    (let [resp (request :post (str (server-url opts) "/authoring/" command) payload)
          body (parse-body resp)]
      (if (and (= 200 (:status resp)) (:ok body))
        (emit (:result body))
        (die (str "✗ " group " " verb ": " (:message body)
                  "\n\nUsage: " (format-usage-line group verb)))))))

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
    @(promise)))

(defn- do-show [opts]
  (emit (parse-body (request :get (str (server-url opts) "/model")))))

(defn- do-validate [opts]
  (emit (parse-body (request :get (str (server-url opts) "/validate")))))

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

;; Full param specs for structured (JSON-valued) commands that are not in the
;; registry — their args are coerced by `prepare` in this ns.
(def ^:private structured-manifest-params
  {"set-fields"
   [{:flag "element" :type "int" :required true :ref "elements[].id"}
    {:flag "fields-json" :type "json" :required true
     :note "JSON array of field objects, e.g. [{\"name\":\"orderId\",\"type\":\"string\"}]"}]
   "set-step-examples"
   [{:flag "step" :type "int" :required true :ref "timelines[].slices[].specifications[].steps[].id"}
    {:flag "examples-json" :type "json" :required true
     :note "JSON array of example objects"}]
   "set-field-origins"
   [{:flag "element" :type "int" :required true :ref "elements[].id"}
    {:flag "origins-json" :type "json" :required true
     :note "JSON array of field-origin objects, e.g. [{\"field\":\"orderId\",\"origin\":\"user_input\"}]"}]
   "set-connection-derivations"
   [{:flag "connection" :type "int" :required true :ref "connections[].id"}
    {:flag "derivations-json" :type "json" :required true
     :note "JSON array of derivation objects, e.g. [{\"target_field\":\"orderId\",\"source_fields\":[\"id\"]}]"}]
   "add-field-origin"
   [{:flag "element" :type "int" :required true :ref "elements[].id"}
    {:flag "field" :type "string" :required true :note "field name on the element"}
    {:flag "origin" :type "keyword" :required true
     :note "how the field is introduced: user_input, generated, external"}]
   "add-derivation"
   [{:flag "connection" :type "int" :required true :ref "connections[].id"}
    {:flag "target" :type "string" :required true :note "target field name on the to-element"}
    {:flag "from" :type "string" :required true
     :note "comma-separated source field names from the from-element"}]})

(def ^:private type-names {:str "string" :int "int" :kw "keyword" :bool "boolean"})

(defn- registry-param->manifest [command [opt-key _ type required?]]
  (let [flag (name opt-key)
        base {:flag flag :type (type-names type) :required required?}
        ref  (param-refs flag)
        vals (get-in param-enums [command flag])]
    (cond-> base
      ref  (assoc :ref ref)
      vals (assoc :values vals))))

(defn- command->manifest-params [command]
  (if-let [reg-params (:params (cmd/registry command))]
    (mapv #(registry-param->manifest command %) reg-params)
    (or (structured-manifest-params command) [])))

(defn- build-manifest []
  {:_instructions "Run `emcli show` to read entity ids required by authoring commands. Pass flags as --flag value."
   :groups
   (into (sorted-map)
         (for [[group verbs] (sort command-groups)]
           [group (into (sorted-map)
                        (for [[verb command] (sort verbs)]
                          [verb {:params (command->manifest-params command)}]))]))})

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
  (let [verbs (command-groups group)]
    (println (str "  " group))
    (doseq [v (sort (keys verbs))]
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
  (println "  export    [--out FILE]                  export the eventmodeling.schema.json")
  (println "  import    --in FILE                     import an eventmodeling.schema.json\n")
  (println "Authoring (grouped by entity — `emcli <entity>` lists an entity's verbs):")
  (doseq [group (keys command-groups)] (print-group group)))

(defn- print-group-help [group]
  (println (str "emcli " group " <verb> [--server URL]\n"))
  (let [verbs    (sort (keys (command-groups group)))
        max-verb (apply max (map count verbs))]
    (doseq [v verbs]
      (let [pad    (str/join (repeat (- max-verb (count v)) " "))
            suffix (format-usage-suffix group v)]
        (println (str "  " group " " v pad
                      (when (seq suffix) (str "  " suffix))))))))

(def ^:private meta-commands #{"serve" "show" "validate" "export" "import" "help"})

(defn -main [& argv]
  (let [argv (vec (or (seq argv) *command-line-args*))
        head (first argv)]
    (cond
      (or (nil? head) (= "help" head)) (print-help)
      (= "--manifest" head) (emit (build-manifest))
      (= "serve" head)    (do-serve (cli/parse-opts (rest argv)))
      (= "show" head)     (do-show (cli/parse-opts (rest argv)))
      (= "validate" head) (do-validate (cli/parse-opts (rest argv)))
      (= "export" head)   (do-export (cli/parse-opts (rest argv)))
      (= "import" head)   (do-import (cli/parse-opts (rest argv)))

      (command-groups head)
      (let [verb     (second argv)
            third    (nth argv 2 nil)]
        (cond
          (or (nil? verb) (= "help" verb)) (print-group-help head)
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
