(ns emcli.server
  "The HTTP process that hosts both boundaries on one in-memory model:

    * ModelChangeStream — GET /stream is a Server-Sent Events stream. On connect
      a client receives exactly one snapshot, then one delta per committed
      mutation (SnapshotThenDeltas, DeltaPerMutation). Outbound only.
    * ModelAuthoring   — POST /authoring/<command> applies an authoring rule;
      GET /model, GET /export, POST /import, GET /validate round out the surface.

  No web framework: requests are routed by method + path by hand
  (org.httpkit.server only), per the project guidelines."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [emcli.app :as app]
            [emcli.commands :as cmd]
            [emcli.rules :as r]
            [emcli.schema :as schema]
            [org.httpkit.server :as http]))

(def ^:private sse-headers
  {"Content-Type"  "text/event-stream"
   "Cache-Control" "no-cache"
   "Connection"    "keep-alive"})

(defn- sse-event [message]
  ;; one SSE event: an `op:`-tagged event line plus the JSON payload.
  (str "event: " (name (:op message :message)) "\n"
       "data: " (json/generate-string message) "\n\n"))

;; channel -> subscription id, so on-close can Unsubscribe.
(defn- stream-handler [app req]
  (let [subs (atom nil)]
    (http/as-channel
     req
     {:on-open  (fn [ch]
                  (http/send! ch {:status 200 :headers sse-headers} false)
                  ;; subscribe! sends the snapshot first, then registers for
                  ;; deltas — all under the app lock, so no mutation is lost or
                  ;; duplicated between snapshot and first delta.
                  (reset! subs (app/subscribe! app #(http/send! ch (sse-event %) false))))
      :on-close (fn [_ch _status]
                  (when-let [sid @subs] (app/unsubscribe! app sid)))})))

;; --- JSON helpers ----------------------------------------------------------

(defn- json-response [status body]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string body {:pretty true})})

(defn- read-json-body [req]
  (when-let [b (:body req)]
    (let [s (if (string? b) b (slurp b))]
      (when (seq s) (json/parse-string s true)))))

(defn- sanitize
  "Strip the heavy/internal bits from a rule result for the wire."
  [res]
  (if (r/error? res)
    {:ok false :error (name (:error res)) :message (:message res)}
    {:ok true :result (let [r (:result res)] (if (map? r) (dissoc r :type) r))}))

;; --- routing ---------------------------------------------------------------

(defn handler [app req]
  (let [{:keys [request-method uri]} req
        segments (->> (str/split uri #"/") (remove str/blank?) vec)]
    (cond
      (and (= :get request-method) (= uri "/health"))
      (json-response 200 {:ok true})

      (and (= :get request-method) (= uri "/stream"))
      (stream-handler app req)

      ;; ModelAuthoring `exposes:` — the full authoring read projection.
      (and (= :get request-method) (= uri "/model"))
      (json-response 200 (cmd/authoring-view app))

      ;; ModelChangeStream snapshot shape on demand (the same payload /stream
      ;; sends first), for clients that want a one-shot canonical snapshot.
      (and (= :get request-method) (= uri "/snapshot"))
      (json-response 200 (app/snapshot app))

      (and (= :get request-method) (= uri "/validate"))
      (json-response 200 (cmd/validate app))

      (and (= :get request-method) (= uri "/export"))
      (try
        (json-response 200 (schema/export (app/store app) (app/model-id app)))
        (catch clojure.lang.ExceptionInfo e
          (json-response 422 (assoc (ex-data e) :ok false :message (ex-message e)))))

      (and (= :post request-method) (= uri "/import"))
      (let [doc (read-json-body req)
            [store mid] (schema/import-model doc)]
        (swap! app assoc :store store :model mid)
        (doseq [send-fn (vals (:subscribers @app))] (send-fn (app/snapshot app)))
        (json-response 200 {:ok true :model mid}))

      ;; POST /authoring/<command>
      (and (= :post request-method) (= "authoring" (first segments)) (= 2 (count segments)))
      (let [command (second segments)
            opts    (or (read-json-body req) {})
            res     (cmd/run app command opts)]
        (json-response (cond (not (r/error? res)) 200
                             (= :unknown-command (:error res)) 404
                             :else 422)
                       (sanitize res)))

      :else
      (json-response 404 {:ok false :message (str "no route for " (name request-method) " " uri)}))))

(defn start!
  "Start the server on `port` holding a model named `model-name`. Returns a map
  with :app, :port and :stop (a no-arg fn that shuts the server down)."
  [{:keys [port model-name] :or {port 8090 model-name "model"}}]
  (let [app  (app/new-app model-name)
        stop (http/run-server (fn [req] (handler app req)) {:port port :legacy-return-value? false})]
    {:app app :port port :stop #(http/server-stop! stop)}))
