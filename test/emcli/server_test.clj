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
                                         {:timeline (:id tl) :title "Place" :kind "state_change" :index 0})))
          cmd  (:result (body-json (post "/authoring/create-element" {:name "PlaceOrder" :kind "command"})))
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
                                       {:timeline (:id tl) :title "S" :kind "state_change" :index 0})))
          c1 (:result (body-json (post "/authoring/create-element" {:name "A" :kind "command"})))
          c2 (:result (body-json (post "/authoring/create-element" {:name "B" :kind "command"})))]
      (is (= 200 (:status (post "/authoring/place-element" {:slice (:id sl) :element (:id c1)}))))
      ;; a second command in a state_change slice breaks PlacementMatchesSliceKind
      (let [resp (post "/authoring/place-element" {:slice (:id sl) :element (:id c2)})]
        (is (= 422 (:status resp)))
        (is (= "invariant-violation" (:error (body-json resp))))))))

(deftest export-requires-complete
  (testing "export of an incomplete model is 422; a complete one exports 200"
    (let [tl (:result (body-json (post "/authoring/create-timeline" {:title "Ordering"})))
          sl (:result (body-json (post "/authoring/add-slice"
                                       {:timeline (:id tl) :title "Place" :kind "state_change" :index 0})))]
      (is (= 422 (:status (get* "/export"))) "no command placed yet")
      (let [cmd (:result (body-json (post "/authoring/create-element" {:name "PlaceOrder" :kind "command"})))]
        (post "/authoring/place-element" {:slice (:id sl) :element (:id cmd)})
        (let [spec (:result (body-json (post "/authoring/add-specification" {:slice (:id sl) :title "spec"})))]
          (is (= 422 (:status (get* "/export"))) "spec has no when-command yet")
          (post "/authoring/add-spec-step" {:spec (:id spec) :clause "when_step" :element (:id cmd) :index 0})
          (let [resp (get* "/export")]
            (is (= 200 (:status resp)) "now complete")
            (is (seq (get (body-json resp) :slices)))))))))

(deftest resolve-endpoint
  (testing "POST /resolve batches name lookups without exposing the whole model"
    (let [tl (:result (body-json (post "/authoring/create-timeline" {:title "Checkout"})))
          _  (post "/authoring/add-slice" {:timeline (:id tl) :title "Baz" :kind "state_change" :index 0})
          _  (post "/authoring/create-element" {:name "Snaz" :kind "read_model"})
          resp (post "/resolve" {:queries [{:name "Baz"} {:name "Snaz"} {:name "Nope"}]})
          results (:results (body-json resp))]
      (is (= 200 (:status resp)))
      (is (= 3 (count results)))
      (is (= :exact (keyword (get-in (first results) [:candidates 0 :match_type]))))
      (is (= "Baz" (get-in (first results) [:candidates 0 :name])))
      (is (= "Checkout" (get-in (first results) [:candidates 0 :breadcrumb :timeline_title])))
      (is (every? #(= :near_miss (keyword (:match_type %))) (get-in (nth results 2) [:candidates]))
          "an unmatched name falls back to near-miss suggestions, not an empty model dump"))))

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
