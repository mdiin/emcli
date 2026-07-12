(ns emcli.rules
  "The authoring operations of the ModelAuthoring surface. Each rule is a pure
  function (store, args) -> result.

  A successful result is {:store store', :delta delta, :result entity-or-id};
  a rejected one is {:error keyword, ...}. Every mutating rule produces exactly
  one delta carrying the canonical changes it made (DeltaPerMutation), and no
  rule commits a store that violates an invariant (the invariants always hold)."
  (:require [clojure.string :as str]
            [emcli.model :as m]
            [emcli.invariants :as inv]))

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
(defn- updated [store type id] {:action :updated :type type :id id :entity (m/fetch store type id)})
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

(def ^:private element-kinds #{:command :event :read_model :screen :automation})

(defn- require-valid-kind [kind]
  (when-not (contains? element-kinds kind)
    {:error :invalid-kind :kind kind
     :message (str "invalid element kind " kind "; must be one of "
                   (str/join ", " (map name element-kinds)))}))

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
  (let [[store model] (m/create store :event-model {:name name})]
    {:store store
     :delta {:op :CreateModel :changes [(created :event-model model)]}
     :result model}))

;; ---------------------------------------------------------------------------
;; Timelines
;; ---------------------------------------------------------------------------

(defn create-timeline [store {:keys [model title id]}]
  (or (require-entity store :event-model model)
      (require-id-available store id)
      (let [[store tl] (m/create store :timeline (with-id {:model model :title title} id))]
        (commit store :CreateTimeline [(created :timeline tl)] tl))))

(defn rename-timeline [store {:keys [timeline new-title]}]
  (or (require-entity store :timeline timeline)
      (let [store (m/set-field store :timeline timeline :title new-title)]
        (commit store :RenameTimeline [(updated store :timeline timeline)]
                (m/fetch store :timeline timeline)))))

;; ---------------------------------------------------------------------------
;; Swimlanes
;; ---------------------------------------------------------------------------

(defn create-swimlane [store {:keys [model name index id]}]
  (or (require-entity store :event-model model)
      (require-id-available store id)
      (let [[store lane] (m/create store :swimlane (with-id {:model model :name name :index index} id))]
        (commit store :CreateSwimlane [(created :swimlane lane)] lane))))

(defn rename-swimlane [store {:keys [lane new-name]}]
  (or (require-entity store :swimlane lane)
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
      (let [store (m/set-field store :slice slice :status new-status)]
        (commit store :SetSliceStatus [(updated store :slice slice)]
                (m/fetch store :slice slice)))))

(defn set-slice-kind [store {:keys [slice new-kind]}]
  (or (require-entity store :slice slice)
      (let [store (m/set-field store :slice slice :kind new-kind)]
        (commit store :SetSliceKind [(updated store :slice slice)]
                (m/fetch store :slice slice)))))

;; ---------------------------------------------------------------------------
;; Elements
;; ---------------------------------------------------------------------------

(defn create-element [store {:keys [model name kind id]}]
  (or (require-entity store :event-model model)
      (require-id-available store id)
      (require-valid-kind kind)
      (let [[store el] (m/create store :element (with-id {:model model :name name :kind kind
                                                           :context :internal :fields []
                                                           :field_origins []} id))]
        (commit store :CreateElement [(created :element el)] el))))

(defn set-fields [store {:keys [element fields]}]
  (or (require-entity store :element element)
      (let [store (m/set-field store :element element :fields (vec fields))]
        (commit store :SetFields [(updated store :element element)]
                (m/fetch store :element element)))))

(defn set-element-context [store {:keys [element new-context]}]
  (or (require-entity store :element element)
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
      (let [store (m/set-field store :element element :field_origins (vec origins))]
        (commit store :SetFieldOrigins [(updated store :element element)]
                (m/fetch store :element element)))))

;; Convenience composite (a CLI affordance, not a domain operation): append a
;; single field-origin override — replacing any existing override for the same
;; field — preserving the element's others. Decomposes into SetFieldOrigins, so
;; it emits exactly one SetFieldOrigins delta.
(defn add-field-origin [store {:keys [element field origin]}]
  (or (require-entity store :element element)
      (let [current (:field_origins (m/fetch store :element element))
            origins (conj (vec (remove #(= field (:field %)) current))
                          {:field field :origin origin})]
        (set-field-origins store {:element element :origins origins}))))

(defn rename-element [store {:keys [element new-name]}]
  (or (require-entity store :element element)
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
        (commit store :Connect [(created :connection c)] c))))

(defn disconnect [store {:keys [connection]}]
  (or (require-entity store :connection connection)
      (let [store (m/delete store :connection connection)]
        (commit store :Disconnect [(deleted :connection connection)] connection))))

(defn set-connection-derivations [store {:keys [connection derivations]}]
  (or (require-entity store :connection connection)
      (let [store (m/set-field store :connection connection :derivations (vec derivations))]
        (commit store :SetConnectionDerivations [(updated store :connection connection)]
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

;; ---------------------------------------------------------------------------
;; Specifications (Given / When / Then)
;; ---------------------------------------------------------------------------

(defn add-specification [store {:keys [slice title id]}]
  (or (require-entity store :slice slice)
      (require-id-available store id)
      (let [[store spec] (m/create store :specification (with-id {:slice slice :title title} id))]
        (commit store :AddSpecification [(created :specification spec)] spec))))

(defn add-spec-step [store {:keys [spec clause element index id]}]
  (or (require-entity store :specification spec)
      (require-entity store :element element)
      (require-id-available store id)
      (let [[store st] (m/create store :spec-step
                                 (with-id {:spec spec :clause clause :element element :index index
                                          :is_error false :expect_empty false :examples []} id))]
        (commit store :AddSpecStep [(created :spec-step st)] st))))

(defn add-error-step [store {:keys [spec error-name index id]}]
  (or (require-entity store :specification spec)
      (require-id-available store id)
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
