# emcli dirge plugin
#
# Registers three LLM-visible tools for working with an Event Model via emcli:
#
#   emcli_resolve  — look up entity names -> ids (read-only, parallel)
#   emcli_validate — run model validation    (read-only, parallel)
#   emcli_author   — execute any authoring command (sequential, mutating)
#
# Also registers one slash command for human use:
#   /em-validate — run validation and print results to chat
#
# Requires `emcli serve` to be running (default: http://localhost:8090).
# The `emcli` binary must be on PATH.

# --- subprocess helper -------------------------------------------------------

(defn- run-emcli [argv]
  # Capture stdout + stderr together via a temp file passed as the :out pipe.
  # os/execute with :p searches PATH; blocks until the subprocess finishes.
  (let [outbuf @""
        pipe   (file/temp)
        code   (os/execute (array/concat ["emcli"] argv)
                           :p {:out pipe :err pipe})]
    (file/seek pipe :set 0)
    (file/read pipe :all outbuf)
    (file/close pipe)
    (if (= 0 code)
      (string/trim outbuf)
      (string "Error (exit " code "): " (string/trim outbuf)))))

# --- tool handlers -----------------------------------------------------------

# A JSON string body: any sequence of \<anything> escape or non-quote character.
# This correctly handles values with spaces ("User created") and embedded
# escaped quotes ("User \"nickname\" created").
(def- json-str-body ~(any (+ (sequence "\\" 1) (if-not "\"" 1))))

(defn emcli-resolve-handler [args-json]
  # args-json: {"queries": "Name1[:kind],Name2[:kind],..."}
  # Extract the queries string value with a PEG -- avoids needing a JSON lib.
  (def pat
    (peg/compile
      ~(any
         (+ (sequence "\"queries\"" :s* ":" :s* "\"" (capture ,json-str-body) "\"")
            1))))
  (def result (peg/match pat args-json))
  (if (and result (> (length result) 0))
    (run-emcli ["resolve" "--queries" (get result 0)])
    "Error: missing 'queries' argument"))

(defn emcli-validate-handler [_args]
  (run-emcli ["validate"]))

(defn emcli-author-handler [args-json]
  # args-json: {"group": "...", "verb": "...", "args": {"flag": "value", ...}}
  # Build argv as [group verb --flag1 val1 --flag2 val2 ...].
  # Parse group and verb with targeted PEGs, then extract the args object.
  (def str-field-pat
    (fn [field]
      (peg/compile
        ~(any
           (+ (sequence ,field :s* ":" :s* "\"" (capture ,json-str-body) "\"")
              1)))))
  (def group-pat (str-field-pat "\"group\""))
  (def verb-pat  (str-field-pat "\"verb\""))
  # Match "key": "value" or "key": number pairs inside a JSON object.
  # Key names are simple identifiers (no escapes needed); values use the full
  # json-str-body so flag values with spaces or embedded quotes are preserved.
  (def kv-pat
    (peg/compile
      ~{:str-val (sequence "\"" (capture ,json-str-body) "\"")
        :num-val (capture (some (+ :d (set "-."))))
        :val     (+ :str-val :num-val)
        :pair    (sequence :s* "\"" (capture (any (if-not "\"" 1))) "\"" :s* ":" :s* :val :s*)
        :main    (any (+ :pair 1))}))
  (def g (peg/match group-pat args-json))
  (def v (peg/match verb-pat  args-json))
  (if (or (not g) (not v) (empty? g) (empty? v))
    "Error: 'group' and 'verb' are required"
    (let [group (get g 0)
          verb  (get v 0)
          # Find the "args" object substring: scan for {"args":{...}}
          args-key-pos (string/find "\"args\"" args-json)
          argv
          (if args-key-pos
            (let [brace (string/find "{" args-json (+ args-key-pos (length "\"args\"")))]
              (if brace
                (let [obj-end
                      (do
                        (var depth 0)
                        (var pos nil)
                        (loop [i :range [brace (length args-json)]]
                          (let [c (get args-json i)]
                            (cond
                              (= c (chr "{")) (++ depth)
                              (= c (chr "}")) (do (-- depth)
                                                  (when (= depth 0)
                                                    (set pos (+ i 1))
                                                    (break))))))
                        pos)
                      obj-str (when obj-end (string/slice args-json brace obj-end))
                      pairs   (when obj-str (peg/match kv-pat obj-str))]
                  (if (and pairs (> (length pairs) 0))
                    (do
                      (def flags @[])
                      (loop [i :range [0 (length pairs) 2]]
                        (array/push flags (string "--" (get pairs i)))
                        (array/push flags (string (get pairs (+ i 1)))))
                      (array/concat [group verb] flags))
                    [group verb]))
                [group verb]))
            [group verb])]
      (run-emcli argv))))

# --- slash command handlers --------------------------------------------------

(defn em-validate-cmd [_args]
  (run-emcli ["validate"]))

# --- registrations (run at load time) ----------------------------------------

(harness/register-tool
  "emcli_resolve"
  (string
    "Resolve one or more element/timeline/slice names to their integer ids. "
    "Pass a comma-separated list of names; optionally suffix each with :kind_hint "
    "(e.g. \"OrderPlaced:event,CreateOrder:command\"). "
    "Returns candidates with their ids, kinds, and swimlane assignments. "
    "Use this to look up ids before calling emcli_author -- "
    "never pass guessed ids to emcli_author.")
  "EM Resolve"
  (string
    "{"
    "\"type\":\"object\","
    "\"properties\":{"
    "\"queries\":{"
    "\"type\":\"string\","
    "\"description\":\"Comma-separated names, each optionally suffixed with :kind_hint"
    " (e.g. \\\"OrderPlaced:event,CreateOrder:command\\\")\""
    "}"
    "},"
    "\"required\":[\"queries\"]"
    "}")
  "emcli-resolve-handler"
  :parallel)

(harness/register-tool
  "emcli_validate"
  (string
    "Run Event Model validation. Returns all warnings and errors: "
    "unsourced fields, disconnected elements, missing specifications, orphaned derivations. "
    "Call this after a sequence of emcli_author mutations to verify the model is consistent.")
  "EM Validate"
  "{\"type\":\"object\",\"properties\":{}}"
  "emcli-validate-handler"
  :parallel)

(harness/register-tool
  "emcli_author"
  (string
    "Execute an emcli authoring command to mutate the Event Model. "
    "Requires `emcli serve` to be running (default port 8090). "
    "`group` is the entity noun: timeline, swimlane, slice, element, placement, connection, spec, or step. "
    "`verb` is the operation: add, rename, delete, reorder, etc. "
    "`args` is an object of flag-name to value pairs for that command "
    "(e.g. {\"name\": \"OrderPlaced\", \"kind\": \"event\"}). "
    "Load the emcli-authoring skill to see all valid group/verb combinations and their required flags.")
  "EM Author"
  (string
    "{"
    "\"type\":\"object\","
    "\"properties\":{"
    "\"group\":{"
    "\"type\":\"string\","
    "\"description\":\"Entity noun: timeline|swimlane|slice|element|placement|connection|spec|step\""
    "},"
    "\"verb\":{"
    "\"type\":\"string\","
    "\"description\":\"Operation verb, e.g. add|rename|delete|reorder\""
    "},"
    "\"args\":{"
    "\"type\":\"object\","
    "\"description\":\"Flag name to value pairs for the command (e.g. {\\\"name\\\": \\\"OrderPlaced\\\", \\\"kind\\\": \\\"event\\\"})\","
    "\"additionalProperties\":true"
    "}"
    "},"
    "\"required\":[\"group\",\"verb\"]"
    "}")
  "emcli-author-handler"
  :sequential)

(harness/register-command "em-validate" "em-validate-cmd")
