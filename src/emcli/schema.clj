(ns emcli.schema
  "SchemaCodec: import and export of the eventmodeling.schema.json interchange
  format, round-tripped at the semantic level (event-model.allium:495-549).

  Works on parsed data (Clojure maps with the schema's string keys) so the
  round-trip invariants can be tested as pure data transforms; `read-json` /
  `write-json` bridge to JSON text for the CLI.

  Identity & wiring rules honoured here:
    * GroupIdIsElementIdentity  — one canonical Element per groupId; each
      placement is a distinct embedded element sharing that groupId.
    * DependenciesPerPlacement  — a Connection becomes an OUTBOUND dependency on
      every embedded copy of its `from` and an INBOUND dependency on every
      embedded copy of its `to`; import dedups by (from groupId, to groupId).
    * ExportRequiresComplete    — export is rejected unless every
      non-informational slice and every specification is complete.
  Informational slices and the documented technical/visual fields are dropped.
  So is the information-completeness provenance (Connection.derivations and
  Element.field_origins): the interchange format has no field-level derivation or
  field-origin concept, so this authoring-only metadata is not exported and not
  reconstructed on import (a documented exclusion from ModelRoundtrip)."
  (:require [cheshire.core :as json]
            [clojure.set :as set]
            [emcli.model :as m]
            [emcli.rules :as r]))

;; ---------------------------------------------------------------------------
;; Enum mappings (canonical keyword <-> schema string)
;; ---------------------------------------------------------------------------

(def ^:private status->schema {:created "Created" :in_progress "InProgress" :done "Done"})
(def ^:private schema->status (set/map-invert status->schema))

(def ^:private kind->slicetype {:state_change "STATE_CHANGE" :state_view "STATE_VIEW" :automation "AUTOMATION"})
(def ^:private slicetype->kind (set/map-invert kind->slicetype))

(def ^:private elkind->type {:command "COMMAND" :event "EVENT" :read_model "READMODEL"
                             :screen "SCREEN" :automation "AUTOMATION"})
(def ^:private type->elkind (set/map-invert elkind->type))

(def ^:private ctx->schema {:internal "INTERNAL" :external "EXTERNAL"})
(def ^:private schema->ctx (set/map-invert ctx->schema))

(def ^:private ftype->schema {:string "String" :boolean "Boolean" :double "Double" :decimal "Decimal"
                             :long "Long" :custom "Custom" :date "Date" :date_time "DateTime"
                             :uuid "UUID" :int "Int"})
(def ^:private schema->ftype (set/map-invert ftype->schema))

(def ^:private card->schema {:single "Single" :list "List"})
(def ^:private schema->card (set/map-invert card->schema))

;; element kind -> the schema slice array it is embedded in, and the SPEC_ type
(def ^:private kind->array {:command "commands" :event "events" :read_model "readmodels"
                           :screen "screens" :automation "processors"})
(def ^:private array->kind {"commands" :command "events" :event "readmodels" :read_model
                           "screens" :screen "processors" :automation})
(def ^:private elkind->spectype {:event "SPEC_EVENT" :command "SPEC_COMMAND" :read_model "SPEC_READMODEL"})
(def ^:private spectype->elkind {"SPEC_EVENT" :event "SPEC_COMMAND" :command "SPEC_READMODEL" :read_model})

;; ---------------------------------------------------------------------------
;; Field value <-> schema Field
;; ---------------------------------------------------------------------------

(defn- field->schema [f]
  (cond-> {"name"        (:name f)
           "type"        (ftype->schema (:type f) "Custom")
           "cardinality" (card->schema (:cardinality f) "Single")
           "optional"    (boolean (:optional f))}
    (seq (:subfields f)) (assoc "subfields" (mapv field->schema (:subfields f)))))

(defn- schema->field [d]
  {:name        (get d "name")
   :type        (schema->ftype (get d "type") :custom)
   :cardinality (schema->card (get d "cardinality") :single)
   :optional    (boolean (get d "optional"))
   :subfields   (mapv schema->field (get d "subfields" []))})

;; ---------------------------------------------------------------------------
;; Export
;; ---------------------------------------------------------------------------

(defn export-readiness
  "Return offending slices/specs that block export (empty map => ready)."
  [store model-id]
  (let [bad-slices (for [t (m/timelines store model-id)
                         s (m/slices store (:id t))
                         :when (and (not= :informational (:status s))
                                    (not (m/slice-complete? store s)))]
                     {:slice (:id s) :title (:title s)})
        bad-specs  (for [t (m/timelines store model-id)
                         s (m/slices store (:id t))
                         sp (m/specs store (:id s))
                         :when (not (m/spec-complete? store sp))]
                     {:specification (:id sp) :title (:title sp)})]
    (cond-> {}
      (seq bad-slices) (assoc :incomplete-slices (vec bad-slices))
      (seq bad-specs)  (assoc :incomplete-specs (vec bad-specs)))))

(defn- group-id [element-id] (str "g" element-id))
(defn- embedded-id [element-id placement-id] (str "e" element-id "-" placement-id))

(defn- deps-for-element
  "OUTBOUND/INBOUND dependencies for one element, per Connection. Identity of
  the far end is carried in the dependency id as its groupId."
  [store element-id]
  (concat
   (for [c (m/outgoing store element-id)
         :let [to (m/fetch store :element (:to c))]]
     {"id" (group-id (:to c)) "title" (:name to) "type" "OUTBOUND"
      "elementType" (elkind->type (:kind to))})
   (for [c (m/incoming store element-id)
         :let [from (m/fetch store :element (:from c))]]
     {"id" (group-id (:from c)) "title" (:name from) "type" "INBOUND"
      "elementType" (elkind->type (:kind from))})))

(defn- embedded-element [store placement]
  (let [el   (m/placement-element store placement)
        lane (when-let [lid (:swimlane el)] (m/fetch store :swimlane lid))]
    {"id"           (embedded-id (:id el) (:id placement))
     "groupId"      (group-id (:id el))
     "title"        (:name el)
     "type"         (elkind->type (:kind el))
     "context"      (ctx->schema (:context el) "INTERNAL")
     "aggregate"    (or (:name lane) "")
     "domain"       ""
     "fields"       (mapv field->schema (:fields el))
     "dependencies" (vec (deps-for-element store (:id el)))}))

(defn- step->schema [store step]
  (let [el (m/step-element store step)]
    (cond-> {"id"    (str "s" (:id step))
             "title" (if (:is_error step) (:error_name step) (:name el))
             "type"  (if (:is_error step) "SPEC_ERROR" (elkind->spectype (:kind el)))
             "index" (:index step)
             "expectEmptyList" (boolean (:expect_empty step))}
      (seq (:examples step))
      (assoc "examples" (mapv (fn [e] {"name" (:field_name e) "value" (:field_value e)})
                              (:examples step))))))

(defn- spec->schema [store spec]
  (let [steps   (m/spec-steps store (:id spec))
        clause= (fn [c] (->> steps (filter #(= c (:clause %))) (mapv #(step->schema store %))))]
    {"id"       (str "spec" (:id spec))
     "title"    (:title spec)
     "linkedId" (str (:slice spec))
     "given"    (clause= :given_step)
     "when"     (clause= :when_step)
     "then"     (clause= :then_step)}))

(defn- slice->schema [store timeline slice]
  (let [pls      (m/placements store (:id slice))
        embedded (group-by #(kind->array (:kind (m/placement-element store %))) pls)
        screens-with-img (for [p (get embedded "screens")
                               :let [el (m/placement-element store p)]
                               :when (:image_url el)]
                           {"url" (:image_url el)
                            "elementId" (embedded-id (:id el) (:id p))})]
    (cond-> {"id"        (str (:id slice))
             "title"     (:title slice)
             "index"     (:index slice)
             "status"    (status->schema (:status slice) "Created")
             "context"   (:title timeline)
             "sliceType" (kind->slicetype (:kind slice))
             "specifications" (mapv #(spec->schema store %) (m/specs store (:id slice)))}
      true (into (for [[arr ps] embedded]
                   [arr (mapv #(embedded-element store %) ps)]))
      (seq screens-with-img) (assoc "screenImages" (vec screens-with-img)))))

(defn export
  "Export an EventModel to a schema document. Throws ex-info :export-incomplete
  when the model is not export-ready (ExportRequiresComplete)."
  [store model-id]
  (let [offenders (export-readiness store model-id)]
    (when (seq offenders)
      (throw (ex-info "Model is not export-ready" (assoc offenders :error :export-incomplete)))))
  (let [model (m/fetch store :event-model model-id)]
    {"name"   (:name model)
     "slices" (vec (for [t (m/timelines store model-id)
                         s (m/slices store (:id t))
                         :when (not= :informational (:status s))]
                     (slice->schema store t s)))}))

;; ---------------------------------------------------------------------------
;; Import
;; ---------------------------------------------------------------------------

(defn- embedded-elements-of [schema-slice]
  (mapcat (fn [arr] (map (fn [e] (assoc e ::kind (array->kind arr)))
                         (get schema-slice arr [])))
          (keys array->kind)))

(defn- ensure-swimlane
  "Find-or-create a swimlane by name; returns [store lane-id]."
  [store model-id name]
  (if (or (nil? name) (= "" name))
    [store nil]
    (if-let [lane (first (m/by-field store :swimlane :name name))]
      [store (:id lane)]
      (let [{:keys [store result]} (r/create-swimlane store {:model model-id :name name})]
        [store (:id result)]))))

(defn- resolve-far
  "Resolve a dependency's far-end element to a canonical element id. Prefers the
  groupId the exporter writes into the dependency `id`; falls back to matching a
  foreign document's dependency by (elementType, title) against element names."
  [group->el name->el dep]
  (or (group->el (get dep "id"))
      (name->el [(type->elkind (get dep "elementType")) (get dep "title")])))

(defn- apply-step-extras
  "Re-attach a step's expectEmptyList and examples after creation, so both
  round-trip (SpecStep.expect_empty and SpecStep.examples are canonical)."
  [store step st]
  (let [store    (if (get st "expectEmptyList")
                   (:store (r/set-step-expect-empty store {:step (:id step) :value true}))
                   store)
        examples (mapv (fn [e] {:field_name (get e "name") :field_value (get e "value")})
                       (get st "examples" []))]
    (if (seq examples)
      (:store (r/set-step-examples store {:step (:id step) :examples examples}))
      store)))

(defn import-model
  "Import a schema document into a fresh store. Returns [store model-id]."
  [document]
  (let [model-name (get document "name" "imported")
        slices     (get document "slices" [])
        store0     (m/empty-store)
        {store :store mid :result} (r/create-model store0 {:name model-name})
        mid        (:id mid)
        ;; --- timelines (grouped by slice context, in first-seen order) ------
        contexts   (distinct (map #(get % "context" "") slices))
        [store ctx->tl]
        (reduce (fn [[s acc] ctx]
                  (let [{s2 :store tl :result} (r/create-timeline s {:model mid :title ctx})]
                    [s2 (assoc acc ctx (:id tl))]))
                [store {}] contexts)
        ;; --- collapse embedded elements by groupId into canonical Elements --
        all-embedded (mapcat embedded-elements-of slices)
        by-group     (reduce (fn [acc e]
                               (let [g (get e "groupId" (str "anon-" (get e "id")))]
                                 (cond-> acc (not (contains? acc g)) (assoc g e))))
                             {} all-embedded)
        [store group->el]
        (reduce (fn [[s acc] [g e]]
                  (let [[s lane] (ensure-swimlane s mid (get e "aggregate"))
                        {s2 :store el :result}
                        (r/create-element s {:model mid
                                             :name (get e "title")
                                             :kind (or (::kind e) (type->elkind (get e "type")))})
                        eid (:id el)
                        s2  (-> s2
                                (m/set-field :element eid :context (schema->ctx (get e "context") :internal))
                                (m/set-field :element eid :fields (mapv schema->field (get e "fields" []))))
                        s2  (if lane (m/set-field s2 :element eid :swimlane lane) s2)]
                    [s2 (assoc acc g eid)]))
                [store {}] by-group)
        ;; [kind name] -> element id, for resolving foreign dep/step references.
        name->el (into {} (for [e (m/elements store mid)] [[(:kind e) (:name e)] (:id e)]))
        ;; --- slices, placements, screenImages, specifications --------------
        [store embedid->placement slice-id-map]
        (reduce
         (fn [[s pmap smap] ss]
           (let [tl   (ctx->tl (get ss "context" ""))
                 {s2 :store sl :result}
                 (r/add-slice s {:timeline tl :title (get ss "title")
                                 :kind (slicetype->kind (get ss "sliceType") :state_change)
                                 :index (get ss "index" 0)})
                 slid (:id sl)
                 s2   (m/set-field s2 :slice slid :status (schema->status (get ss "status") :created))
                 ;; placements (one per embedded element)
                 [s3 pmap2]
                 (reduce (fn [[s pm] e]
                           (let [g   (get e "groupId" (str "anon-" (get e "id")))
                                 eid (group->el g)
                                 {s' :store p :result} (r/place-element s {:slice slid :element eid})]
                             [s' (assoc pm (get e "id") (:id p))]))
                         [s2 pmap] (embedded-elements-of ss))
                 ;; screenImages -> element.image_url
                 s4 (reduce (fn [s img]
                              (if-let [e (first (filter #(= (get % "id") (get img "elementId"))
                                                        (embedded-elements-of ss)))]
                                (m/set-field s :element (group->el (get e "groupId" (str "anon-" (get e "id"))))
                                             :image_url (get img "url"))
                                s))
                            s3 (get ss "screenImages" []))]
             [s4 pmap2 (assoc smap (get ss "id") slid)]))
         [store {} {}] slices)
        ;; --- connections: dedup deps into (from element, to element) edges --
        edges (reduce
               (fn [acc e]
                 (let [near (group->el (get e "groupId" (str "anon-" (get e "id"))))]
                   (reduce (fn [a d]
                             (let [far (resolve-far group->el name->el d)]
                               (cond
                                 (or (nil? near) (nil? far)) a
                                 (= "OUTBOUND" (get d "type")) (conj a [near far])
                                 (= "INBOUND"  (get d "type")) (conj a [far near])
                                 :else a)))
                           acc (get e "dependencies" []))))
               #{} all-embedded)
        store (reduce (fn [s [from to]] (:store (r/connect s {:from from :to to})))
                      store edges)
        ;; --- specifications + steps ----------------------------------------
        store (reduce
               (fn [s ss]
                 (let [slid (slice-id-map (get ss "id"))]
                   (reduce
                    (fn [s spec]
                      (let [{s2 :store sp :result} (r/add-specification s {:slice slid :title (get spec "title")})
                            spid (:id sp)]
                        (reduce
                         (fn [s [clause steps]]
                           (reduce
                            (fn [s st]
                              (if (= "SPEC_ERROR" (get st "type"))
                                (let [res (r/add-error-step s {:spec spid :error-name (get st "title")
                                                               :index (get st "index" 0)})]
                                  (if (r/error? res) s (apply-step-extras (:store res) (:result res) st)))
                                (let [kind (spectype->elkind (get st "type"))
                                      el   (name->el [kind (get st "title")])
                                      res  (when el (r/add-spec-step s {:spec spid :clause clause
                                                                        :element el :index (get st "index" 0)}))]
                                  (if (and res (not (r/error? res)))
                                    (apply-step-extras (:store res) (:result res) st)
                                    s))))
                            s steps))
                         s2 [[:given_step (get spec "given" [])]
                             [:when_step (get spec "when" [])]
                             [:then_step (get spec "then" [])]])))
                    s (get ss "specifications" []))))
               store slices)]
    [store mid]))

;; ---------------------------------------------------------------------------
;; JSON bridge (for the CLI)
;; ---------------------------------------------------------------------------

(defn write-json [document] (json/generate-string document {:pretty true}))
(defn read-json  [text]     (json/parse-string text))
