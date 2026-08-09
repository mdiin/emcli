(ns emcli.rules
  "The authoring operations of the ModelAuthoring surface. Each rule is a pure
  function (store, args) -> result.

  A successful result is {:store store', :delta delta, :result entity-or-id};
  a rejected one is {:error keyword, ...}. Every mutating rule produces exactly
  one delta carrying the canonical changes it made (DeltaPerMutation), and no
  rule commits a store that violates an invariant (the invariants always hold)."
  (:require [clojure.string :as str]
            [emcli.model :as m]
            [emcli.invariants :as inv]
            [emcli.wireframe :as wf]))

;; ---------------------------------------------------------------------------
;; Result helpers
;; ---------------------------------------------------------------------------

(defn error? [r] (contains? r :error))

(defn- missing [type id]
  {:error :not-found :type type :id id
   :message (str (name type) " " id " does not exist")})

(defn- require-entity [store type id]
  (when-not (m/exists? store type id)
    (missing type id)))

(defn- created [type entity] {:action :created :type type :id (:id entity) :entity entity})
(defn- updated [store type id]
  (let [entity (m/fetch store type id)]
    (cond-> {:action :updated :type type :id id :entity entity}
      (= :element type) (assoc-in [:entity :is_information_complete]
                                   (m/information-complete? store entity)))))
(defn- deleted [type id]      {:action :deleted :type type :id id})

;; --id (a CLI/scripting affordance): every create-* rule accepts an optional
;; pre-assigned id instead of letting the store auto-allocate one, so a script
;; can reference an entity's id before creating it. Rejected up front if the
;; id is already in use by any entity, of any type (ids share one sequence).
(defn- id-conflict [id]
  {:error :id-conflict :id id :message (str "id " id " is already in use")})

(defn- require-id-available [store id]
  (when (and id (m/id-taken? store id))
    (id-conflict id)))

(defn- with-id [attrs id]
  (cond-> attrs id (assoc :id id)))

(defn- require-non-blank [field value]
  (when (str/blank? value)
    {:error :invalid-value :message (str (name field) " must not be blank")}))

(def ^:private element-kinds    #{:command :event :read_model :screen :automation})
(def ^:private slice-kinds      #{:state_change :state_view :automation})
(def ^:private slice-statuses   #{:created :in_progress :done :informational})
(def ^:private spec-step-clauses #{:given_step :when_step :then_step})
(def ^:private element-contexts #{:internal :external})
(def ^:private field-origins    #{:user_input :generated :external})

(defn- require-valid-value [allowed value]
  (when-not (contains? allowed value)
    {:error :invalid-value :value value
     :message (str "invalid value " value "; must be one of "
                   (str/join ", " (map name allowed)))}))

(defn- invalid-origin [{:keys [field origin]}]
  (cond
    (str/blank? field) (str "field-origin missing field: " {:field field :origin origin})
    (not (contains? field-origins origin))
    (str "invalid origin " origin " for field " field "; must be one of "
         (str/join ", " (map name field-origins)))))

(defn- require-valid-origins [origins]
  (when-let [msg (some invalid-origin origins)]
    {:error :invalid-value :message msg}))

(defn- invalid-derivation [{:keys [target_field source_fields]}]
  (cond
    (str/blank? target_field) (str "derivation missing target_field: " {:target_field target_field})
    (or (empty? source_fields) (some str/blank? source_fields))
    (str "derivation for " target_field " must have non-empty source_fields")))

(defn- require-valid-derivations [derivations]
  (when-let [msg (some invalid-derivation derivations)]
    {:error :invalid-value :message msg}))

(def ^:private field-types        #{:string :boolean :double :decimal :long :custom
                                     :date :date_time :uuid :int})
(def ^:private field-cardinalities #{:single :list})

(defn- invalid-field [f]
  (cond
    (str/blank? (:name f)) (str "field missing name: " f)
    (not (contains? field-types (:type f)))
    (str "invalid field type " (:type f) " for field " (:name f) "; must be one of "
         (str/join ", " (map name field-types)))
    (and (:cardinality f) (not (contains? field-cardinalities (:cardinality f))))
    (str "invalid cardinality " (:cardinality f) " for field " (:name f) "; must be one of "
         (str/join ", " (map name field-cardinalities)))
    (seq (:subfields f)) (some invalid-field (:subfields f))))

(defn- require-valid-fields [fields]
  (when-let [msg (some invalid-field fields)]
    {:error :invalid-value :message msg}))

(defn- commit
  "Validate invariants and package a successful mutation. If the candidate
  store breaks any invariant the mutation is rejected and nothing is applied."
  [store op changes result]
  (let [violations (inv/check store)]
    (if (seq violations)
      {:error :invariant-violation :op op :violations violations
       :message (str op " rejected: " (str/join "; " (map :message violations)))}
      {:store store :delta {:op op :changes changes} :result result})))

;; ---------------------------------------------------------------------------
;; Models (bootstrap helper — the surface is scoped to one model at a time)
;; ---------------------------------------------------------------------------

;; `create-model` is a bootstrap helper, deliberately OUTSIDE the ModelAuthoring
;; surface: the spec scopes the surface to one already-existing model with no
;; modelled identity (event-model.allium:588-590), so the model pre-exists any
;; authoring session. There is intentionally no RenameModel — the spec exposes
;; `model.name` for reading but provides no operation to change it.
(defn create-model [store {:keys [name]}]
  (or (require-non-blank :name name)
      (let [[store model] (m/create store :event-model {:name name})]
        {:store store
         :delta {:op :CreateModel :changes [(created :event-model model)]}
         :result model})))

;; ---------------------------------------------------------------------------
;; Timelines
;; ---------------------------------------------------------------------------

(defn create-timeline [store {:keys [model title id]}]
  (or (require-entity store :event-model model)
      (require-id-available store id)
      (require-non-blank :title title)
      (let [[store tl] (m/create store :timeline (with-id {:model model :title title} id))]
        (commit store :CreateTimeline [(created :timeline tl)] tl))))

(defn rename-timeline [store {:keys [timeline new-title]}]
  (or (require-entity store :timeline timeline)
      (require-non-blank :new-title new-title)
      (let [store (m/set-field store :timeline timeline :title new-title)]
        (commit store :RenameTimeline [(updated store :timeline timeline)]
                (m/fetch store :timeline timeline)))))

;; ---------------------------------------------------------------------------
;; Swimlanes
;; ---------------------------------------------------------------------------

(defn create-swimlane [store {:keys [model name index id]}]
  (or (require-entity store :event-model model)
      (require-id-available store id)
      (require-non-blank :name name)
      (let [[store lane] (m/create store :swimlane (with-id {:model model :name name :index index} id))]
        (commit store :CreateSwimlane [(created :swimlane lane)] lane))))

(defn rename-swimlane [store {:keys [lane new-name]}]
  (or (require-entity store :swimlane lane)
      (require-non-blank :new-name new-name)
      (let [store (m/set-field store :swimlane lane :name new-name)]
        (commit store :RenameSwimlane [(updated store :swimlane lane)]
                (m/fetch store :swimlane lane)))))

(defn reorder-swimlane [store {:keys [lane new-index]}]
  (or (require-entity store :swimlane lane)
      (let [store (m/set-field store :swimlane lane :index new-index)]
        (commit store :ReorderSwimlane [(updated store :swimlane lane)]
                (m/fetch store :swimlane lane)))))

;; ---------------------------------------------------------------------------
;; Slices
;; ---------------------------------------------------------------------------

(defn add-slice [store {:keys [timeline title kind index id]}]
  (or (require-entity store :timeline timeline)
      (require-id-available store id)
      (require-non-blank :title title)
      (require-valid-value slice-kinds kind)
      (let [[store sl] (m/create store :slice (with-id {:timeline timeline :title title
                                                        :kind kind :index index
                                                        :status :created} id))]
        (commit store :AddSlice [(created :slice sl)] sl))))

(defn reorder-slice [store {:keys [slice new-index]}]
  (or (require-entity store :slice slice)
      (let [store (m/set-field store :slice slice :index new-index)]
        (commit store :ReorderSlice [(updated store :slice slice)]
                (m/fetch store :slice slice)))))

(defn set-slice-status [store {:keys [slice new-status]}]
  (or (require-entity store :slice slice)
      (require-valid-value slice-statuses new-status)
      (let [store (m/set-field store :slice slice :status new-status)]
        (commit store :SetSliceStatus [(updated store :slice slice)]
                (m/fetch store :slice slice)))))

(defn set-slice-kind [store {:keys [slice new-kind]}]
  (or (require-entity store :slice slice)
      (require-valid-value slice-kinds new-kind)
      (let [store (m/set-field store :slice slice :kind new-kind)]
        (commit store :SetSliceKind [(updated store :slice slice)]
                (m/fetch store :slice slice)))))

;; ---------------------------------------------------------------------------
;; Elements
;; ---------------------------------------------------------------------------

(defn create-element [store {:keys [model name kind id]}]
  (or (require-entity store :event-model model)
      (require-id-available store id)
      (require-non-blank :name name)
      (require-valid-value element-kinds kind)
      (let [[store el] (m/create store :element (with-id {:model model :name name :kind kind
                                                           :context :internal :fields []
                                                           :field_origins []} id))]
        (commit store :CreateElement [(created :element el)] el))))

(defn set-fields [store {:keys [element fields]}]
  (or (require-entity store :element element)
      (require-valid-fields fields)
      (let [store (m/set-field store :element element :fields (vec fields))]
        (commit store :SetFields [(updated store :element element)]
                (m/fetch store :element element)))))

;; Convenience composite (a CLI affordance, not a domain operation): append (or
;; replace by name) a single field, preserving the element's others. Decomposes
;; into SetFields, so it emits exactly one SetFields delta.
(defn add-field [store {:keys [element field]}]
  (or (require-entity store :element element)
      (let [current (:fields (m/fetch store :element element))
            fields  (conj (vec (remove #(= (:name field) (:name %)) current)) field)]
        (set-fields store {:element element :fields fields}))))

(defn remove-field [store {:keys [element name]}]
  (or (require-entity store :element element)
      (let [current (:fields (m/fetch store :element element))
            fields  (vec (remove #(= name (:name %)) current))]
        (set-fields store {:element element :fields fields}))))

(defn set-element-context [store {:keys [element new-context]}]
  (or (require-entity store :element element)
      (require-valid-value element-contexts new-context)
      (let [store (m/set-field store :element element :context new-context)]
        (commit store :SetElementContext [(updated store :element element)]
                (m/fetch store :element element)))))

(defn assign-swimlane [store {:keys [element lane]}]
  (or (require-entity store :element element)
      (require-entity store :swimlane lane)
      (let [store (m/set-field store :element element :swimlane lane)]
        (commit store :AssignSwimlane [(updated store :element element)]
                (m/fetch store :element element)))))

(defn set-image-url [store {:keys [element url]}]
  (or (require-entity store :element element)
      (let [store (m/set-field store :element element :image_url url)]
        (commit store :SetImageUrl [(updated store :element element)]
                (m/fetch store :element element)))))

(defn set-field-origins [store {:keys [element origins]}]
  (or (require-entity store :element element)
      (require-valid-origins origins)
      (let [store (m/set-field store :element element :field_origins (vec origins))]
        (commit store :SetFieldOrigins [(updated store :element element)]
                (m/fetch store :element element)))))

;; Convenience composite (a CLI affordance, not a domain operation): append a
;; single field-origin override — replacing any existing override for the same
;; field — preserving the element's others. Decomposes into SetFieldOrigins, so
;; it emits exactly one SetFieldOrigins delta.
(defn add-field-origin [store {:keys [element field origin]}]
  (or (require-entity store :element element)
      (require-valid-value field-origins origin)
      (let [current (:field_origins (m/fetch store :element element))
            origins (conj (vec (remove #(= field (:field %)) current))
                          {:field field :origin origin})]
        (set-field-origins store {:element element :origins origins}))))

(defn remove-field-origin [store {:keys [element field]}]
  (or (require-entity store :element element)
      (let [current (:field_origins (m/fetch store :element element))
            origins (vec (remove #(= field (:field %)) current))]
        (set-field-origins store {:element element :origins origins}))))

(defn rename-element [store {:keys [element new-name]}]
  (or (require-entity store :element element)
      (require-non-blank :new-name new-name)
      (let [store (m/set-field store :element element :name new-name)]
        (commit store :RenameElement [(updated store :element element)]
                (m/fetch store :element element)))))

;; ---------------------------------------------------------------------------
;; Placements
;; ---------------------------------------------------------------------------

(defn place-element [store {:keys [slice element id]}]
  (or (require-entity store :slice slice)
      (require-entity store :element element)
      (require-id-available store id)
      (let [existing  (m/placements store slice)
            next-idx  (if (seq existing)
                        (inc (apply max (map #(or (:index %) 0) existing)))
                        0)
            [store p] (m/create store :placement (with-id {:slice slice :element element :index next-idx} id))]
        (commit store :PlaceElement [(created :placement p)] p))))

(defn reorder-placement [store {:keys [placement new-index]}]
  (or (require-entity store :placement placement)
      (let [store (m/set-field store :placement placement :index new-index)]
        (commit store :ReorderPlacement [(updated store :placement placement)]
                (m/fetch store :placement placement)))))

(defn remove-placement [store {:keys [placement]}]
  (or (require-entity store :placement placement)
      (let [store (m/delete store :placement placement)]
        (commit store :RemovePlacement [(deleted :placement placement)] placement))))

;; ---------------------------------------------------------------------------
;; Connections
;; ---------------------------------------------------------------------------

(defn connect [store {:keys [from to id]}]
  (or (require-entity store :element from)
      (require-entity store :element to)
      (require-id-available store id)
      (let [model      (:model (m/fetch store :element from))
            [store c]  (m/create store :connection (with-id {:model model :from from :to to
                                                             :derivations []} id))]
        (commit store :Connect [(created :connection c) (updated store :element to)] c))))

(defn disconnect [store {:keys [connection]}]
  (or (require-entity store :connection connection)
      (let [to    (:to (m/fetch store :connection connection))
            store (m/delete store :connection connection)]
        (commit store :Disconnect [(deleted :connection connection) (updated store :element to)]
                connection))))

(defn set-connection-derivations [store {:keys [connection derivations]}]
  (or (require-entity store :connection connection)
      (require-valid-derivations derivations)
      (let [to    (:to (m/fetch store :connection connection))
            store (m/set-field store :connection connection :derivations (vec derivations))]
        (commit store :SetConnectionDerivations
                [(updated store :connection connection) (updated store :element to)]
                (m/fetch store :connection connection)))))

;; Convenience composite (a CLI affordance, not a domain operation): append a
;; single derivation — replacing any existing derivation for the same target
;; field — preserving the connection's others. Decomposes into
;; SetConnectionDerivations, so it emits exactly one SetConnectionDerivations delta.
(defn add-derivation [store {:keys [connection target from]}]
  (or (require-entity store :connection connection)
      (let [current     (:derivations (m/fetch store :connection connection))
            derivations (conj (vec (remove #(= target (:target_field %)) current))
                              {:target_field target :source_fields (vec from)})]
        (set-connection-derivations store {:connection connection :derivations derivations}))))

(defn remove-derivation [store {:keys [connection target]}]
  (or (require-entity store :connection connection)
      (let [current     (:derivations (m/fetch store :connection connection))
            derivations (vec (remove #(= target (:target_field %)) current))]
        (set-connection-derivations store {:connection connection :derivations derivations}))))

;; ---------------------------------------------------------------------------
;; Specifications (Given / When / Then)
;; ---------------------------------------------------------------------------

(defn add-specification [store {:keys [slice title id]}]
  (or (require-entity store :slice slice)
      (require-id-available store id)
      (require-non-blank :title title)
      (let [[store spec] (m/create store :specification (with-id {:slice slice :title title} id))]
        (commit store :AddSpecification [(created :specification spec)] spec))))

(defn add-spec-step [store {:keys [spec clause element index id]}]
  (or (require-entity store :specification spec)
      (require-entity store :element element)
      (require-id-available store id)
      (require-valid-value spec-step-clauses clause)
      (let [[store st] (m/create store :spec-step
                                 (with-id {:spec spec :clause clause :element element :index index
                                          :is_error false :expect_empty false :examples []} id))]
        (commit store :AddSpecStep [(created :spec-step st)] st))))

(defn add-error-step [store {:keys [spec error-name index id]}]
  (or (require-entity store :specification spec)
      (require-id-available store id)
      (require-non-blank :error-name error-name)
      (let [[store st] (m/create store :spec-step
                                 (with-id {:spec spec :clause :then_step :error_name error-name
                                          :index index :is_error true :expect_empty false :examples []} id))]
        (commit store :AddErrorStep [(created :spec-step st)] st))))

(defn remove-spec-step [store {:keys [step]}]
  (or (require-entity store :spec-step step)
      (let [store (m/delete store :spec-step step)]
        (commit store :RemoveSpecStep [(deleted :spec-step step)] step))))

(defn set-step-examples [store {:keys [step examples]}]
  (or (require-entity store :spec-step step)
      (let [store (m/set-field store :spec-step step :examples (vec examples))]
        (commit store :SetStepExamples [(updated store :spec-step step)]
                (m/fetch store :spec-step step)))))

;; Convenience composite (a CLI affordance, not a domain operation): append (or
;; replace by field_name) a single example, preserving the step's others.
;; Decomposes into SetStepExamples, so it emits exactly one SetStepExamples
;; delta. Validates field-name/field-value non-blank here (the CLI affordance
;; this replaces used to check --examples-json against the Example shape
;; before invoking SetStepExamples; see rule SetStepExamples @guidance).
(defn add-step-example [store {:keys [step field-name field-value]}]
  (or (require-entity store :spec-step step)
      (require-non-blank :field-name field-name)
      (require-non-blank :field-value field-value)
      (let [current  (:examples (m/fetch store :spec-step step))
            examples (conj (vec (remove #(= field-name (:field_name %)) current))
                           {:field_name field-name :field_value field-value})]
        (set-step-examples store {:step step :examples examples}))))

(defn remove-step-example [store {:keys [step field-name]}]
  (or (require-entity store :spec-step step)
      (let [current  (:examples (m/fetch store :spec-step step))
            examples (vec (remove #(= field-name (:field_name %)) current))]
        (set-step-examples store {:step step :examples examples}))))

(defn set-step-expect-empty [store {:keys [step value]}]
  (or (require-entity store :spec-step step)
      (let [store (m/set-field store :spec-step step :expect_empty value)]
        (commit store :SetStepExpectEmpty [(updated store :spec-step step)]
                (m/fetch store :spec-step step)))))

;; ---------------------------------------------------------------------------
;; Change-stream subscriptions
;; ---------------------------------------------------------------------------

(defn subscribe [store {:keys [model]}]
  (or (require-entity store :event-model model)
      (let [[store sub] (m/create store :subscription {:model model})]
        {:store store
         :delta {:op :Subscribe :changes [(created :subscription sub)]}
         :result sub})))

(defn unsubscribe [store {:keys [subscription]}]
  (or (require-entity store :subscription subscription)
      {:store (m/delete store :subscription subscription)
       :delta {:op :Unsubscribe :changes [(deleted :subscription subscription)]}
       :result subscription}))

;; ---------------------------------------------------------------------------
;; Deletion cascades
;; ---------------------------------------------------------------------------

(defn- del [[store changes] type id]
  [(m/delete store type id) (conj changes (deleted type id))])

(defn- cascade-spec [acc spec-id]
  (let [[store _] acc
        acc       (reduce (fn [a st] (del a :spec-step (:id st)))
                          acc (m/spec-steps store spec-id))]
    (del acc :specification spec-id)))

(defn- cascade-slice [acc slice-id]
  (let [[store _] acc
        acc       (reduce (fn [a p] (del a :placement (:id p)))
                          acc (m/placements store slice-id))
        acc       (reduce (fn [a sp] (cascade-spec a (:id sp)))
                          acc (m/specs store slice-id))]
    (del acc :slice slice-id)))

(defn- cascade-timeline [acc timeline-id]
  (let [[store _] acc
        acc       (reduce (fn [a sl] (cascade-slice a (:id sl)))
                          acc (m/slices store timeline-id))]
    (del acc :timeline timeline-id)))

(defn- cascade-element [acc element-id]
  (let [[store _] acc
        conns     (vals (into {} (map (juxt :id identity))
                              (concat (m/outgoing store element-id)
                                      (m/incoming store element-id))))
        acc       (reduce (fn [a p] (del a :placement (:id p)))
                          acc (m/element-placements store element-id))
        acc       (reduce (fn [a c] (del a :connection (:id c))) acc conns)]
    (del acc :element element-id)))

(defn delete-specification [store {:keys [spec]}]
  (or (require-entity store :specification spec)
      (let [[store changes] (cascade-spec [store []] spec)]
        (commit store :DeleteSpecification changes spec))))

(defn delete-slice [store {:keys [slice]}]
  (or (require-entity store :slice slice)
      (let [[store changes] (cascade-slice [store []] slice)]
        (commit store :DeleteSlice changes slice))))

(defn delete-timeline [store {:keys [timeline]}]
  (or (require-entity store :timeline timeline)
      (let [[store changes] (cascade-timeline [store []] timeline)]
        (commit store :DeleteTimeline changes timeline))))

(defn delete-element [store {:keys [element]}]
  (or (require-entity store :element element)
      (let [[store changes] (cascade-element [store []] element)]
        (commit store :DeleteElement changes element))))

(defn delete-swimlane [store {:keys [lane]}]
  (or (require-entity store :swimlane lane)
      (let [elems    (filter #(= lane (:swimlane %)) (m/all store :element))
            store    (reduce (fn [s e] (m/set-field s :element (:id e) :swimlane nil))
                             store elems)
            changes  (mapv #(updated store :element (:id %)) elems)
            store    (m/delete store :swimlane lane)]
        (commit store :DeleteSwimlane (conj changes (deleted :swimlane lane)) lane))))

;; ---------------------------------------------------------------------------
;; Wireframe rules
;; ---------------------------------------------------------------------------

(defn- wireframe-invalid [validation]
  {:error :invalid-wireframe :errors (:errors validation)
   :message (str "wireframe validation failed: "
                 (str/join "; " (map :message (:errors validation))))})

(defn add-wireframe-node [store {:keys [element tag parent attrs]}]
  (or (require-entity store :element element)
      (let [el (m/fetch store :element element)]
        (or (when (not= :screen (:kind el))
              {:error :invalid-value
               :message (str "element " element " is not a screen")})
            (let [seed     [:screen {:-id "n1"}]
                  wf       (or (:wireframe el) seed)
                  schema   (wf/tag-schema tag)
                  ;; For text-children tags, :text in attrs becomes a string child
                  text-child (when (:text-children? schema) (:text attrs))
                  clean-attrs (if text-child (dissoc attrs :text) attrs)
                  child    (cond-> [tag]
                             (seq clean-attrs) (conj clean-attrs)
                             text-child        (conj text-child))
                  wf'      (wf/append-child-at wf (or parent "n1") child)
                  sv       (wf/validate wf')
                  ss       (wf/validate-semantics wf' el)]
              (or (when-not (:valid? sv) (wireframe-invalid sv))
                  (when-not (:valid? ss) (wireframe-invalid ss))
                  (let [store (m/set-field store :element element :wireframe wf')]
                    (commit store :AddWireframeNode [(updated store :element element)]
                            (m/fetch store :element element)))))))))

(defn set-wireframe-attr [store {:keys [element node attr value]}]
  (or (require-entity store :element element)
      (let [el (m/fetch store :element element)]
        (or (when-not (:wireframe el)
              {:error :not-found :type :wireframe
               :message (str "element " element " has no wireframe")})
            (when-not (wf/find-node (:wireframe el) node)
              {:error :not-found :type :wireframe-node :id node
               :message (str "node " node " does not exist")})
            (let [wf'  (wf/assoc-attr-at (:wireframe el) node attr value)
                  sv   (wf/validate wf')
                  ss   (wf/validate-semantics wf' el)]
              (or (when-not (:valid? sv) (wireframe-invalid sv))
                  (when-not (:valid? ss) (wireframe-invalid ss))
                  (let [store (m/set-field store :element element :wireframe wf')]
                    (commit store :SetWireframeAttr [(updated store :element element)]
                            (m/fetch store :element element)))))))))

(defn delete-wireframe-node [store {:keys [element node]}]
  (or (require-entity store :element element)
      (let [el (m/fetch store :element element)]
        (or (when-not (:wireframe el)
              {:error :not-found :type :wireframe
               :message (str "element " element " has no wireframe")})
            (when-not (wf/find-node (:wireframe el) node)
              {:error :not-found :type :wireframe-node :id node
               :message (str "node " node " does not exist")})
            (let [wf'   (wf/delete-node-at (:wireframe el) node)
                  store (if wf'
                          (m/set-field store :element element :wireframe wf')
                          (m/set-field store :element element :wireframe nil))]
              (commit store :DeleteWireframeNode [(updated store :element element)]
                      (m/fetch store :element element)))))))
