(ns emcli.server-test
  "Integration tests over a real HTTP server: the ModelAuthoring HTTP boundary
  (POST /authoring/<command>, /model, /export, /validate, /import) and the
  ModelChangeStream SSE endpoint (GET /stream) delivering snapshot-then-deltas."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [emcli.server :as server]))

(def ^:dynamic *base* nil)
(def ^:dynamic *srv* nil)

(use-fixtures :each
  (fn [t]
    (let [port (+ 8100 (rand-int 800))
          srv  (server/start! {:port port :model-name "Orders"})]
      (try
        (binding [*base* (str "http://localhost:" port) *srv* srv]
          (t))
        (finally ((:stop srv)))))))

(defn- post [path body]
  (http/post (str *base* path)
             {:headers {"Content-Type" "application/json"} :throw false
              :body (json/generate-string body)}))

(defn- get* [path] (http/get (str *base* path) {:throw false}))
(defn- body-json [resp] (json/parse-string (:body resp) true))

(deftest health-snapshot-and-authoring-view
  (is (= 200 (:status (get* "/health"))))
  (testing "GET /snapshot is the ModelChangeStream canonical shape"
    (let [snap (body-json (get* "/snapshot"))]
      (is (= "snapshot" (:op snap)))
      (is (= "Orders" (get-in snap [:model :name])))))
  (testing "GET /model is the richer ModelAuthoring exposes projection"
    (let [tl   (:result (body-json (post "/authoring/create-timeline" {:title "Ordering"})))
          sl   (:result (body-json (post "/authoring/add-slice"
                                         {:timeline (:id tl) :title "Place" :slice-type "state_change"})))
          cmd  (:result (body-json (post "/authoring/create-element" {:name "PlaceOrder" :element-type "command"})))
          _    (post "/authoring/place-element" {:slice (:id sl) :element (:id cmd)})
          spec (:result (body-json (post "/authoring/add-specification" {:slice (:id sl) :title "spec"})))
          st   (:result (body-json (post "/authoring/add-spec-step"
                                         {:spec (:id spec) :clause "when_step"
                                          :element (:id cmd) :index 0})))
          _    (post "/authoring/add-step-example"
                     {:step (:id st) :field-name "id" :field-value "1"})
          view (body-json (get* "/model"))
          slice (-> view :timelines first :slices first)
          step1 (-> slice :specifications first :steps first)]
      (is (= "Orders" (:name view)))
      (is (= ["PlaceOrder"] (map :name (:elements view))))
      (is (some? (:id (first (:elements view)))) "elements carry id")
      (is (true? (:is_complete slice)) "slice with one command is complete")
      (is (= "Ordering" (:timeline_title slice)))
      (is (= ["PlaceOrder"] (map :element_name (:placements slice))))
      (is (some? (:id (first (:placements slice)))) "placements carry id")
      (is (= 1 (count (:specifications slice))))
      (is (true? (-> slice :specifications first :is_complete)) "spec has a when-command now")
      (is (= [{:field_name "id" :field_value "1"}] (:examples step1))
          "GET /model exposes each step's examples"))))

(deftest authoring-create-and-reject
  (testing "a successful authoring command returns the created entity"
    (let [resp (post "/authoring/create-timeline" {:title "Ordering"})
          body (body-json resp)]
      (is (= 200 (:status resp)))
      (is (:ok body))
      (is (= "Ordering" (get-in body [:result :title])))))
  (testing "an unknown command is 404"
    (is (= 404 (:status (post "/authoring/frobnicate" {})))))
  (testing "an invariant-violating command is 422"
    (let [tl (:result (body-json (post "/authoring/create-timeline" {:title "T"})))
          sl (:result (body-json (post "/authoring/add-slice"
                                       {:timeline (:id tl) :title "S" :slice-type "state_change"})))
          c1 (:result (body-json (post "/authoring/create-element" {:name "A" :element-type "command"})))
          c2 (:result (body-json (post "/authoring/create-element" {:name "B" :element-type "command"})))]
      (is (= 200 (:status (post "/authoring/place-element" {:slice (:id sl) :element (:id c1)}))))
      ;; a second command in a state_change slice breaks PlacementMatchesSliceType
      (let [resp (post "/authoring/place-element" {:slice (:id sl) :element (:id c2)})]
        (is (= 422 (:status resp)))
        (is (= "invariant-violation" (:error (body-json resp))))))))

(deftest authoring-internal-error-is-json-and-logged
  (testing "an exception inside a command is a 500 JSON `internal` error, logged with its request"
    (let [logged (atom [])
          el     (:result (body-json (post "/authoring/create-element"
                                           {:name "Notify" :element-type "automation"})))]
      (with-redefs [server/log-internal-error! (fn [req e] (swap! logged conj [(:uri req) e]))]
        ;; a JSON number where a keyword is expected throws inside the command
        (let [resp (post "/authoring/add-field-origin" {:element (:id el) :field "x" :origin 12})
              body (body-json resp)]
          (is (= 500 (:status resp)))
          (is (str/starts-with? (get-in resp [:headers "content-type"]) "application/json"))
          (is (false? (:ok body)))
          (is (= "internal" (:error body)))
          (is (string? (:message body)))
          (is (= ["/authoring/add-field-origin"] (map first @logged)))
          (is (instance? Throwable (second (first @logged))))))))
  (testing "the server keeps serving after an internal error"
    (is (= 200 (:status (post "/authoring/create-timeline" {:title "After"}))))))

(deftest export-requires-complete
  (testing "export of an incomplete model is 422; a complete one exports 200"
    (let [tl (:result (body-json (post "/authoring/create-timeline" {:title "Ordering"})))
          sl (:result (body-json (post "/authoring/add-slice"
                                       {:timeline (:id tl) :title "Place" :slice-type "state_change"})))]
      (is (= 422 (:status (get* "/export"))) "no command placed yet")
      (let [cmd (:result (body-json (post "/authoring/create-element" {:name "PlaceOrder" :element-type "command"})))]
        (post "/authoring/place-element" {:slice (:id sl) :element (:id cmd)})
        (let [spec (:result (body-json (post "/authoring/add-specification" {:slice (:id sl) :title "spec"})))]
          (is (= 422 (:status (get* "/export"))) "spec has no when-command yet")
          (post "/authoring/add-spec-step" {:spec (:id spec) :clause "when_step" :element (:id cmd) :index 0})
          (let [resp (get* "/export")]
            (is (= 200 (:status resp)) "now complete")
            (is (seq (get (body-json resp) :slices)))))))))

(defn- import-doc
  "A document in the eventmodeling.schema.json interchange shape whose
  STATE_CHANGE slice carries `n` commands. n = 1 is valid and imports; n = 2 is
  still valid against the schema (a slice's arrays hold as many commands as they
  like) but the always-on authoring invariant PlacementMatchesSliceType forbids
  it, so the document must be refused."
  [n]
  {"name" "Imported"
   "slices"
   [{"id" "sl-1" "title" "Place order" "index" 0 "status" "Created"
     "context" "Ordering" "sliceType" "STATE_CHANGE"
     "commands" (mapv (fn [i] {"id" (str "emb-cmd-" i) "groupId" (str "grp-cmd-" i)
                               "title" (nth ["PlaceOrder" "CancelOrder"] i)
                               "type" "COMMAND" "context" "INTERNAL" "aggregate" "Orders"
                               "fields" [] "dependencies" []})
                      (range n))
     "events" [{"id" "emb-evt" "groupId" "grp-evt" "title" "OrderPlaced" "type" "EVENT"
                "context" "INTERNAL" "fields" [] "dependencies" []}]
     "specifications" []}]})

(defn- snapshot-placement-names
  "The element names of the first slice's placements in a /snapshot body."
  [snap]
  (map #(get-in % [:element :name])
       (-> snap :model :timelines first :slices first :placements)))

(deftest import-rejects-a-document-the-invariants-forbid
  (testing "POST /import of a valid-but-forbidden document is refused and installs nothing"
    (let [tl     (:result (body-json (post "/authoring/create-timeline" {:title "Ordering"})))
          sl     (:result (body-json (post "/authoring/add-slice" {:timeline (:id tl) :title "Place"
                                                                   :slice-type "state_change"})))
          cmd    (:result (body-json (post "/authoring/create-element" {:name "PlaceOrder"
                                                                        :element-type "command"})))
          _      (post "/authoring/place-element" {:slice (:id sl) :element (:id cmd)})
          before (body-json (get* "/snapshot"))
          resp   (post "/import" (import-doc 2))
          body   (body-json resp)
          after  (body-json (get* "/snapshot"))]
      (is (= 422 (:status resp)) "a rejected import is a 422, never a 200")
      (is (false? (:ok body)))
      (is (= "import-rejected" (:error body)))
      (is (= ["sl-1"] (:slices body)) "the refusal names the offending slice")
      (is (str/includes? (:message body) "sl-1"))
      (testing "the running model is left untouched (nothing was installed)"
        (is (= before after))
        (is (= 1 (count (get-in after [:model :timelines]))) "the app's timeline survived")
        (is (= ["PlaceOrder"] (snapshot-placement-names after))))))
  (testing "a document the invariants admit is still imported"
    (let [resp  (post "/import" (import-doc 1))
          after (body-json (get* "/snapshot"))]
      (is (= 200 (:status resp)))
      (is (= ["PlaceOrder" "OrderPlaced"] (snapshot-placement-names after))))))

(deftest resolve-endpoint
  (testing "POST /resolve batches name lookups without exposing the whole model"
    (let [tl (:result (body-json (post "/authoring/create-timeline" {:title "Checkout"})))
          _  (post "/authoring/add-slice" {:timeline (:id tl) :title "Baz" :slice-type "state_change"})
          _  (post "/authoring/create-element" {:name "Snaz" :element-type "read_model"})
          resp (post "/resolve" {:queries [{:name "Baz"} {:name "Snaz"} {:name "Nope"}]})
          results (:results (body-json resp))]
      (is (= 200 (:status resp)))
      (is (= 3 (count results)))
      (is (= :exact (keyword (get-in (first results) [:candidates 0 :match_type]))))
      (is (= "Baz" (get-in (first results) [:candidates 0 :name])))
      (is (= "Checkout" (get-in (first results) [:candidates 0 :breadcrumb :timeline_title])))
      (is (every? #(= :near_miss (keyword (:match_type %))) (get-in (nth results 2) [:candidates]))
          "an unmatched name falls back to near-miss suggestions, not an empty model dump"))))

(deftest query-endpoint
  (testing "POST /query follows relations from a root without dumping the model"
    (let [tl  (:result (body-json (post "/authoring/create-timeline" {:title "Checkout"})))
          sl  (:result (body-json (post "/authoring/add-slice"
                                        {:timeline (:id tl) :title "Ordering" :slice-type "state_change"})))
          e1  (:result (body-json (post "/authoring/create-element" {:name "PlaceOrder" :element-type "command"})))
          e2  (:result (body-json (post "/authoring/create-element" {:name "OrderPlaced" :element-type "event"})))
          _   (post "/authoring/place-element" {:slice (:id sl) :element (:id e1)})
          _   (post "/authoring/place-element" {:slice (:id sl) :element (:id e2)})
          resp (post "/query" {:query (str "slice:" (:id sl) " | elements {index}")})
          rows (:results (body-json resp))]
      (is (= 200 (:status resp)))
      (is (= #{"PlaceOrder" "OrderPlaced"} (set (map :name rows))))
      (is (every? #(contains? (:placement %) :index) rows) "the projection attaches the placement edge")))
  (testing "an invalid query is a 422 naming the valid alternatives"
    (let [resp (post "/query" {:query "element:1 | element"})
          body (body-json resp)]
      (is (= 422 (:status resp)))
      (is (false? (:ok body)))
      (is (str/includes? (:message body) "outgoing")))))

(deftest sse-stream-delivers-snapshot-then-delta
  (testing "GET /stream sends a snapshot, then one delta per mutation"
    (let [resp   (http/get (str *base* "/stream") {:as :stream :throw false
                                                   :headers {"Accept" "text/event-stream"}})
          stream (:body resp)
          events (atom [])
          reader (future
                   (let [rdr (clojure.java.io/reader stream)]
                     (loop []
                       (when-let [line (.readLine rdr)]
                         (when (str/starts-with? line "data: ")
                           (swap! events conj (json/parse-string (subs line 6) true)))
                         (recur)))))]
      (is (= "text/event-stream" (str/trim (str/replace (get-in resp [:headers "content-type"] "") #";.*" ""))))
      ;; wait for the snapshot to arrive
      (Thread/sleep 200)
      (is (= :snapshot (-> @events first :op keyword)) "first event is the snapshot")
      ;; cause a mutation; the subscriber should receive exactly one delta
      (post "/authoring/create-timeline" {:title "Ordering"})
      (Thread/sleep 200)
      (is (= 2 (count @events)))
      (is (= :CreateTimeline (-> @events second :op keyword)))
      (future-cancel reader))))

;; --- wireframe edits over HTTP (MoveWireframeNode, ReplaceWireframe,
;; LayoutEditRevealsResult) ------------------------------------------------------

(defn- json-child-ids
  "The ids of the immediate children of a JSON-decoded layout node."
  [node]
  (map #(get-in % [1 :-id]) (filter vector? (drop 2 node))))

(defn- form-screen
  "A screen with n2 Password, n3 Submit, n4 Email at the root; returns its id."
  []
  (let [eid (get-in (body-json (post "/authoring/create-element" {:name "Login" :element-type "screen"}))
                    [:result :id])]
    (post "/authoring/add-wireframe-node" {:element eid :tag "input" :type "password" :label "Password"})
    (post "/authoring/add-wireframe-node" {:element eid :tag "button" :label "Submit"})
    (post "/authoring/add-wireframe-node" {:element eid :tag "input" :type "email" :label "Email"})
    eid))

(deftest authoring-a-node-edit-names-the-touched-node
  (let [eid  (get-in (body-json (post "/authoring/create-element" {:name "Login" :element-type "screen"}))
                     [:result :id])
        body (body-json (post "/authoring/add-wireframe-node" {:element eid :tag "divider"}))]
    (is (:ok body))
    (is (= "n2" (:node body)))))

(deftest authoring-moves-a-wireframe-node
  (let [eid  (form-screen)
        resp (post "/authoring/move-wireframe-node" {:element eid :node "n4" :before "n2"})
        body (body-json resp)]
    (is (= 200 (:status resp)))
    (is (= "n4" (:node body)))
    (is (= ["n4" "n2" "n3"] (json-child-ids (get-in body [:result :wireframe]))))
    ;; the same screen: a second form-screen would collide on its name
    (testing "a rejected move is a 422"
      (let [resp (post "/authoring/move-wireframe-node" {:element eid :node "n1" :before "n2"})]
        (is (= 422 (:status resp)))
        (is (re-find #"(?i)root" (:message (body-json resp))))))))

(deftest authoring-replaces-a-wireframe
  (let [eid  (form-screen)
        resp (post "/authoring/replace-wireframe"
                   {:element eid
                    :tree    "[n1] :canvas\n  [n4] :input  {:type :email, :label \"Email\"}\n  :divider"})
        body (body-json resp)]
    (is (= 200 (:status resp)) (pr-str body))
    (is (= ["n4" "n5"] (json-child-ids (get-in body [:result :wireframe]))))
    ;; the same screen: a second form-screen would collide on its name
    (testing "a rejected replacement is a 422 reporting every problem"
      (let [resp (post "/authoring/replace-wireframe"
                       {:element eid :tree "[n1] :canvas\n  [n99] :divider\n  :button  {:variant :primary}"})
            body (body-json resp)]
        (is (= 422 (:status resp)))
        (is (str/includes? (:message body) "n99"))
        (is (str/includes? (:message body) "label is required"))))))
