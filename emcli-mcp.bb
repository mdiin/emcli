#!/usr/bin/env bb
;; MCP stdio shim for emcli — serves tools.json over the Model Context Protocol.
;; Claude Code spawns this as a subprocess and speaks JSON-RPC 2.0 over stdin/stdout.
;; All debug output goes to stderr; all protocol output goes to stdout.

(require '[cheshire.core :as json]
         '[babashka.process :as p])

;; ---------------------------------------------------------------------------
;; Tool manifest loading
;; ---------------------------------------------------------------------------

(defn- load-tools-json
  "Read tools.json relative to the script's working directory."
  []
  (let [path "tools.json"]
    (json/parse-string (slurp path) true)))

(defn- eca-tool->mcp-tool
  "Translate one ECA.dev tool entry to an MCP tool descriptor."
  [[tool-name {:keys [description schema]}]]
  {:name        (name tool-name)
   :description description
   :inputSchema (assoc schema :type "object")})

(defn- build-mcp-tools [tools-json]
  (mapv eca-tool->mcp-tool tools-json))

;; ---------------------------------------------------------------------------
;; JSON-RPC helpers
;; ---------------------------------------------------------------------------

(defn- write-response!
  "Serialise `payload` as a single JSON line to stdout and flush."
  [payload]
  (println (json/generate-string payload))
  (flush))

(defn- ok-response [id result]
  {:jsonrpc "2.0" :id id :result result})

(defn- error-response [id code message]
  {:jsonrpc "2.0" :id id :error {:code code :message message}})

;; ---------------------------------------------------------------------------
;; Method handlers
;; ---------------------------------------------------------------------------

(defn- handle-initialize [id _params]
  (ok-response id {:protocolVersion "2024-11-05"
                   :capabilities    {:tools {}}
                   :serverInfo      {:name "emcli" :version "1.0.0"}}))

(defn- handle-tools-list [id _params mcp-tools]
  (ok-response id {:tools mcp-tools}))

(defn- handle-tools-call [id {:keys [name arguments]} ]
  (let [tool-name  name
        group      (-> tool-name
                       (clojure.string/replace-first #"^emcli_" "")
                       (clojure.string/replace #"_" "-"))
        verb       (:verb arguments)
        args       (or (:args arguments) "")
        cmd        (str "emcli " group " " verb (when (seq args) (str " " args)))
        _          (binding [*out* *err*] (println "[emcli-mcp] exec:" cmd))
        result     (p/sh cmd :out :string :err :string)]
    (if (zero? (:exit result))
      (ok-response id {:content [{:type "text" :text (:out result)}]})
      (ok-response id {:content [{:type "text" :text (str (:err result) (:out result))}]
                       :isError true}))))

;; ---------------------------------------------------------------------------
;; Main dispatch loop
;; ---------------------------------------------------------------------------

(defn- dispatch [request mcp-tools]
  (let [{:keys [id method params]} request]
    (binding [*out* *err*]
      (println "[emcli-mcp] <-" method (when id (str "id=" id))))
    (cond
      (= method "initialize")
      (write-response! (handle-initialize id params))

      (= method "notifications/initialized")
      nil ;; notification — no response

      (= method "tools/list")
      (write-response! (handle-tools-list id params mcp-tools))

      (= method "tools/call")
      (write-response! (handle-tools-call id params))

      :else
      (when id
        (write-response! (error-response id -32601 "Method not found"))))))

(defn -main []
  (let [tools-json (load-tools-json)
        mcp-tools  (build-mcp-tools tools-json)]
    (binding [*out* *err*]
      (println "[emcli-mcp] started;" (count mcp-tools) "tools loaded"))
    (loop []
      (let [line (read-line)]
        (when (some? line)          ; nil = EOF → exit cleanly
          (when (seq (clojure.string/trim line))
            (try
              (let [request (json/parse-string line true)]
                (dispatch request mcp-tools))
              (catch Exception e
                (binding [*out* *err*]
                  (println "[emcli-mcp] parse error:" (ex-message e))))))
          (recur))))))

(-main)
