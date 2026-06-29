(ns emcli.schema-test
  "SchemaCodec contract obligations: contract signatures, ExportRequiresComplete,
  ModelRoundtrip, SchemaRoundtrip, GroupIdIsElementIdentity, DependenciesPerPlacement."
  (:require [clojure.test :refer [deftest testing is]]
            [emcli.model :as m]
            [emcli.rules :as r]
            [emcli.schema :as sc]
            [emcli.support :as s]))

;; A small but representative export-ready model: two timelines, a swimlane, a
;; command/event/read_model/screen, connections forming a valid EM chain, a
;; placement of one element in two slices (to exercise groupId sharing), and a
;; complete specification.
(defn- build-model []
  (let [[store mid] (s/with-model "Orders")
        store       (:store (s/ok store r/create-swimlane {:model mid :name "OrderLane"}))
        lane        (:id (first (m/swimlanes store mid)))
        ;; elements
        mk          (fn [s name kind] (let [s (:store (s/ok s r/create-element {:model mid :name name :kind kind}))]
                                        [s (:id (last (m/elements s mid)))]))
        [store scr] (mk store "OrderScreen" :screen)
        [store cmd] (mk store "PlaceOrder" :command)
        [store evt] (mk store "OrderPlaced" :event)
        [store rm]  (mk store "OrderList" :read_model)
        store       (:store (s/ok store r/assign-swimlane {:element cmd :lane lane}))
        store       (:store (s/ok store r/set-image-url {:element scr :url "http://x/screen.png"}))
        store       (:store (s/ok store r/set-fields {:element evt :fields [{:name "id" :type :uuid
                                                                            :optional false :cardinality :single
                                                                            :subfields []}]}))
        ;; connections: screen->command->event->read_model->screen
        store       (:store (s/ok store r/connect {:from scr :to cmd}))
        store       (:store (s/ok store r/connect {:from cmd :to evt}))
        store       (:store (s/ok store r/connect {:from evt :to rm}))
        store       (:store (s/ok store r/connect {:from rm :to scr}))
        ;; timeline 1: a complete state_change slice
        store       (:store (s/ok store r/create-timeline {:model mid :title "Ordering"}))
        tl1         (:id (first (m/timelines store mid)))
        store       (:store (s/ok store r/add-slice {:timeline tl1 :title "Place an order"
                                                     :kind :state_change :index 0}))
        sc1         (:id (first (m/slices store tl1)))
        store       (:store (s/ok store r/place-element {:slice sc1 :element cmd}))
        store       (:store (s/ok store r/place-element {:slice sc1 :element evt}))
        ;; a complete spec on the state_change slice
        store       (:store (s/ok store r/add-specification {:slice sc1 :title "places an order"}))
        sp1         (:id (first (m/specs store sc1)))
        store       (:store (s/ok store r/add-spec-step {:spec sp1 :clause :when_step :element cmd :index 0}))
        wstep       (:id (first (m/spec-steps store sp1)))
        store       (:store (s/ok store r/set-step-examples {:step wstep
                                                             :examples [{:field_name "id" :field_value "abc-123"}]}))
        store       (:store (s/ok store r/add-spec-step {:spec sp1 :clause :then_step :element evt :index 1}))
        ;; timeline 2: a complete state_view slice; reuse the read_model + screen
        store       (:store (s/ok store r/create-timeline {:model mid :title "Viewing"}))
        tl2         (:id (second (m/timelines store mid)))
        store       (:store (s/ok store r/add-slice {:timeline tl2 :title "View orders"
                                                     :kind :state_view :index 0}))
        sc2         (:id (first (m/slices store tl2)))
        store       (:store (s/ok store r/place-element {:slice sc2 :element rm}))
        store       (:store (s/ok store r/place-element {:slice sc2 :element scr}))
        store       (:store (s/ok store r/add-specification {:slice sc2 :title "shows orders"}))
        sp2         (:id (first (m/specs store sc2)))
        store       (:store (s/ok store r/add-spec-step {:spec sp2 :clause :then_step :element rm :index 0}))]
    [store mid]))

;; --- A normalised, id-independent view of a model for round-trip comparison.
(defn- normalise [store mid]
  {:name (:name (m/fetch store :event-model mid))
   :swimlanes (sort (map :name (m/swimlanes store mid)))
   :elements (sort-by :name
                      (for [e (m/elements store mid)]
                        {:name (:name e) :kind (:kind e) :context (:context e)
                         :swimlane (some->> (:swimlane e) (m/fetch store :swimlane) :name)
                         :image_url (:image_url e)
                         :fields (mapv #(select-keys % [:name :type :optional :cardinality]) (:fields e))}))
   :connections (sort (for [c (m/connections store mid)]
                        [(:name (m/fetch store :element (:from c)))
                         (:name (m/fetch store :element (:to c)))]))
   :timelines (for [t (m/timelines store mid)]
                {:title (:title t)
                 :slices (for [sl (m/slices store (:id t))]
                           {:title (:title sl) :kind (:kind sl) :status (:status sl) :index (:index sl)
                            :placements (sort (map #(:name (m/placement-element store %))
                                                   (m/placements store (:id sl))))
                            :specs (for [sp (m/specs store (:id sl))]
                                     {:title (:title sp)
                                      :steps (for [st (m/spec-steps store (:id sp))]
                                               {:clause (:clause st)
                                                :element (some->> (:element st) (m/fetch store :element) :name)
                                                :is_error (:is_error st)
                                                :expect_empty (:expect_empty st)
                                                :examples (mapv #(select-keys % [:field_name :field_value])
                                                                (:examples st))})})})})})

;; --- contract signatures ---------------------------------------------------

(deftest export-and-import-signatures
  (let [[store mid] (build-model)
        doc         (sc/export store mid)]
    (is (map? doc))
    (is (contains? doc "slices"))
    (let [[store2 mid2] (sc/import-model doc)]
      (is (some? mid2))
      (is (map? store2)))))

;; --- ExportRequiresComplete ------------------------------------------------

(deftest export-rejects-incomplete-model
  (testing "a slice with no command (incomplete state_change) blocks export"
    (let [[store mid] (s/with-model)
          store       (:store (s/ok store r/create-timeline {:model mid :title "T"}))
          tlid        (:id (first (m/timelines store mid)))
          store       (:store (s/ok store r/add-slice {:timeline tlid :title "incomplete"
                                                       :kind :state_change :index 0}))]
      (is (seq (sc/export-readiness store mid)))
      (is (thrown? clojure.lang.ExceptionInfo (sc/export store mid))))))

(deftest informational-slices-excluded-not-blocking
  (testing "an informational slice is dropped on export and never blocks it"
    (let [[store mid] (build-model)
          store       (:store (s/ok store r/create-timeline {:model mid :title "Notes"}))
          tlid        (:id (last (m/timelines store mid)))
          store       (:store (s/ok store r/add-slice {:timeline tlid :title "just a note"
                                                       :kind :state_change :index 0}))
          slid        (:id (first (m/slices store tlid)))
          store       (:store (s/ok store r/set-slice-status {:slice slid :new-status :informational}))
          doc         (sc/export store mid)]
      (is (not (some #(= "just a note" (get % "title")) (get doc "slices")))))))

;; --- ModelRoundtrip --------------------------------------------------------

(deftest model-roundtrip-reproduces-model
  (testing "import(export(model)) reproduces the canonical model (ids normalised)"
    (let [[store mid]   (build-model)
          doc           (sc/export store mid)
          [store2 mid2] (sc/import-model doc)]
      (is (= (normalise store mid) (normalise store2 mid2))))))

(deftest model-roundtrip-survives-json
  (testing "round-trip through JSON text is lossless at the semantic level"
    (let [[store mid]   (build-model)
          doc           (-> (sc/export store mid) sc/write-json sc/read-json)
          [store2 mid2] (sc/import-model doc)]
      (is (= (normalise store mid) (normalise store2 mid2))))))

;; --- SchemaRoundtrip (foreign document) ------------------------------------

(def foreign-doc
  "A document in the eventmodeling.schema.json shape, NOT produced by our own
  exporter: dependency far-ends are referenced by title (not by our groupId
  scheme), and steps carry examples. Exercises the foreign-import path."
  {"name" "Imported"
   "slices"
   [{"id" "sl-1" "title" "Place order" "index" 0 "status" "Created"
     "context" "Ordering" "sliceType" "STATE_CHANGE"
     "commands" [{"id" "emb-cmd" "groupId" "grp-cmd" "title" "PlaceOrder" "type" "COMMAND"
                  "context" "INTERNAL" "aggregate" "Orders"
                  "fields" [{"name" "id" "type" "UUID" "cardinality" "Single" "optional" false}]
                  "dependencies" [{"id" "ignored-foreign-id" "title" "OrderPlaced"
                                   "type" "OUTBOUND" "elementType" "EVENT"}]}]
     "events" [{"id" "emb-evt" "groupId" "grp-evt" "title" "OrderPlaced" "type" "EVENT"
                "context" "INTERNAL" "fields" [] "dependencies" []}]
     "specifications" [{"id" "spec-1" "title" "places order" "linkedId" "sl-1"
                        "given" []
                        "when" [{"id" "w1" "title" "PlaceOrder" "type" "SPEC_COMMAND" "index" 0
                                 "examples" [{"name" "id" "value" "abc-123"}]}]
                        "then" [{"id" "t1" "title" "OrderPlaced" "type" "SPEC_EVENT" "index" 1}]}]}
    {"id" "sl-2" "title" "View orders" "index" 0 "status" "Created"
     "context" "Viewing" "sliceType" "STATE_VIEW"
     "readmodels" [{"id" "emb-rm" "groupId" "grp-rm" "title" "OrderList" "type" "READMODEL"
                    "context" "INTERNAL" "fields" [] "dependencies" []}]
     "screens" [{"id" "emb-scr" "groupId" "grp-scr" "title" "OrderScreen" "type" "SCREEN"
                 "context" "INTERNAL" "fields" [] "dependencies" []}]
     "screenImages" [{"url" "http://x/s.png" "elementId" "emb-scr"}]
     "specifications" [{"id" "spec-2" "title" "shows orders" "linkedId" "sl-2"
                        "given" [] "when" []
                        "then" [{"id" "t2" "title" "OrderList" "type" "SPEC_READMODEL" "index" 0}]}]}]})

(deftest schema-roundtrip-preserves-canonical-subset
  (testing "export(import(document)) preserves the canonical subset of a foreign document"
    (let [[store1 mid1] (sc/import-model foreign-doc)
          doc2          (sc/export store1 mid1)
          [store2 mid2] (sc/import-model doc2)]
      (is (= (normalise store1 mid1) (normalise store2 mid2)))
      (testing "canonical content survived the foreign import"
        (is (= #{"PlaceOrder" "OrderPlaced" "OrderList" "OrderScreen"}
               (set (map :name (m/elements store1 mid1)))))
        (testing "dependency referenced by title became a Connection"
          (is (contains? (set (for [c (m/connections store1 mid1)]
                                [(:name (m/fetch store1 :element (:from c)))
                                 (:name (m/fetch store1 :element (:to c)))]))
                         ["PlaceOrder" "OrderPlaced"])))
        (testing "step examples survived import"
          (let [steps (mapcat #(m/spec-steps store1 (:id %))
                              (mapcat #(m/specs store1 (:id %))
                                      (mapcat #(m/slices store1 (:id %)) (m/timelines store1 mid1))))]
            (is (some #(seq (:examples %)) steps))))
        (testing "screenImages.url became Element.image_url"
          (is (= "http://x/s.png"
                 (:image_url (first (filter #(= "OrderScreen" (:name %)) (m/elements store1 mid1)))))))))))

;; --- GroupIdIsElementIdentity ----------------------------------------------

(deftest groupid-is-element-identity
  (testing "an element placed in two slices exports as two embedded elements sharing one groupId"
    (let [[store mid] (build-model)
          doc         (sc/export store mid)
          embedded    (mapcat (fn [s] (mapcat #(get s % []) ["commands" "events" "readmodels" "screens" "processors"]))
                              (get doc "slices"))
          screens     (filter #(= "SCREEN" (get % "type")) embedded)]
      ;; OrderScreen is placed in the state_view slice and connected; appears as
      ;; its own group. Each embedded element has a groupId; ids are unique.
      (is (apply distinct? (map #(get % "id") embedded)))
      (is (every? #(get % "groupId") embedded))
      ;; OrderList read_model collapses back to a single element on import
      (let [[store2 mid2] (sc/import-model doc)]
        (is (= 1 (count (filter #(= "OrderList" (:name %)) (m/elements store2 mid2)))))))))

;; --- DependenciesPerPlacement ----------------------------------------------

(deftest dependencies-dedup-into-connections
  (testing "deps on embedded copies dedup by (from,to) back into the original connections"
    (let [[store mid]   (build-model)
          before        (set (for [c (m/connections store mid)]
                               [(:name (m/fetch store :element (:from c)))
                                (:name (m/fetch store :element (:to c)))]))
          [store2 mid2] (sc/import-model (sc/export store mid))
          after         (set (for [c (m/connections store2 mid2)]
                               [(:name (m/fetch store2 :element (:from c)))
                                (:name (m/fetch store2 :element (:to c)))]))]
      (is (= before after)))))
