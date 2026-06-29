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

(defn- do-authoring [command opts]
  (let [payload (-> (prepare command opts)
                    (dissoc :server :fields-json :examples-json :origins-json :derivations-json))
        resp    (request :post (str (server-url opts) "/authoring/" command) payload)
        body    (parse-body resp)]
    (if (and (= 200 (:status resp)) (:ok body))
      (emit (:result body))
      (die (str "✗ " command ": " (:message body))))))

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

;; --- help & dispatch -------------------------------------------------------

(defn- print-help []
  (println "emcli — author Event Models from the command line\n")
  (println "Usage: emcli <command> [--opt value ...] [--server URL]\n")
  (println "Process:")
  (println "  serve     [--port 8090] [--name NAME] [--file PATH]")
  (println "                                          start the model server (SSE + authoring).")
  (println "                                          --file loads/persists the model as EDN,")
  (println "                                          flushed on every write for crash recovery.\n")
  (println "Inspect:")
  (println "  show                                    print the canonical model snapshot")
  (println "  validate                                report slices/specs not yet complete")
  (println "  export    [--out FILE]                  export the eventmodeling.schema.json")
  (println "  import    --in FILE                     import an eventmodeling.schema.json\n")
  (println "Authoring commands (each maps to a ModelAuthoring operation):")
  (doseq [c cmd/commands] (println (str "  " c))))

(def ^:private meta-commands #{"serve" "show" "validate" "export" "import" "help"})

(defn -main [& argv]
  (let [argv    (vec (or (seq argv) *command-line-args*))
        command (first argv)
        opts    (cli/parse-opts (rest argv))]
    (cond
      (or (nil? command) (= "help" command)) (print-help)
      (= "serve" command)    (do-serve opts)
      (= "show" command)     (do-show opts)
      (= "validate" command) (do-validate opts)
      (= "export" command)   (do-export opts)
      (= "import" command)   (do-import opts)
      (some #{command} cmd/commands) (do-authoring command opts)
      :else (do (binding [*out* *err*] (println (str "Unknown command: " command "\n")))
                (print-help)
                (System/exit 2)))))
