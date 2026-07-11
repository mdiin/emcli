(ns emcli.model
  "The canonical Event Modeling domain model: a normalised, purely-functional
  in-memory store and the navigation / projection / derived-predicate helpers
  the authoring rules and surfaces are built on.

  The store is an ordinary map (a value, never mutated in place). Every entity
  lives in a collection keyed by its integer id; relationships are stored as
  foreign-key ids and reverse relationships are computed by filtering. This
  mirrors the canonical model in event-model.allium one-to-one.")

;; ---------------------------------------------------------------------------
;; Store primitives
;; ---------------------------------------------------------------------------

(def collections
  "Entity type -> store collection key."
  {:event-model   :models
   :subscription  :subscriptions
   :swimlane      :swimlanes
   :timeline      :timelines
   :slice         :slices
   :placement     :placements
   :element       :elements
   :connection    :connections
   :specification :specifications
   :spec-step     :spec-steps})

(defn empty-store []
  (into {:seq 0}
        (map (fn [k] [k {}]))
        (vals collections)))

(defn- coll-key [type]
  (or (collections type)
      (throw (ex-info (str "Unknown entity type: " type) {:type type}))))

(defn next-id
  "Allocate the next id. Returns [store' id]. Ids are monotonic integers, so
  creation order is recoverable by sorting on id."
  [store]
  (let [id (inc (:seq store))]
    [(assoc store :seq id) id]))

(defn insert
  "Insert `entity` (carrying :id and :type) into its collection."
  [store entity]
  (assoc-in store [(coll-key (:type entity)) (:id entity)] entity))

(defn id-taken?
  "True if `id` is already used by any entity, of any type. Ids are a single
  sequence shared across all entity types (`next-id`/:seq), so uniqueness must
  be checked globally rather than per-collection."
  [store id]
  (boolean (some #(contains? (get store %) id) (vals collections))))

(defn create
  "Allocate an id and insert a new entity of `type` with `attrs`. If `attrs`
  carries an :id, that id is used verbatim instead of auto-allocating (the
  caller must check id-taken? first) and :seq is advanced past it so future
  auto-allocated ids never collide with it.
  Returns [store' entity]."
  [store type attrs]
  (if-let [id (:id attrs)]
    (let [entity (assoc (dissoc attrs :id) :id id :type type)
          store  (update store :seq max id)]
      [(insert store entity) entity])
    (let [[store id] (next-id store)
          entity     (merge attrs {:id id :type type})]
      [(insert store entity) entity])))

(defn fetch
  "Look up an entity by type and id (nil if absent)."
  [store type id]
  (get-in store [(coll-key type) id]))

(defn exists?
  [store type id]
  (some? (fetch store type id)))

(defn update-entity
  "Apply `f` to the entity (type,id) in place."
  [store type id f & args]
  (apply update-in store [(coll-key type) id] f args))

(defn set-field
  [store type id k v]
  (assoc-in store [(coll-key type) id k] v))

(defn delete
  "Remove the entity (type,id) from the store."
  [store type id]
  (update store (coll-key type) dissoc id))

(defn all
  "All entities of `type`, in creation order."
  [store type]
  (->> (get store (coll-key type)) vals (sort-by :id)))

(defn where
  "All entities of `type` matching predicate `pred`, in creation order."
  [store type pred]
  (filter pred (all store type)))

(defn by-field
  "All entities of `type` whose field `k` equals `v`."
  [store type k v]
  (where store type #(= v (get % k))))

;; ---------------------------------------------------------------------------
;; Relationship navigation (matches the `with` reverse references in the spec)
;; ---------------------------------------------------------------------------

(defn timelines     [store model-id] (by-field store :timeline :model model-id))
(defn swimlanes
  "Swimlanes of a model, in author-controlled order: by :index then id (ties
  broken by creation order), exactly like `slices`. Swimlane.index is a required
  Integer set on every creation path, so no nil guard is needed."
  [store model-id]
  (->> (by-field store :swimlane :model model-id)
       (sort-by (juxt :index :id))))
(defn elements      [store model-id] (by-field store :element :model model-id))
(defn connections   [store model-id] (by-field store :connection :model model-id))
(defn subscriptions [store model-id] (by-field store :subscription :model model-id))

(defn slices
  "Slices of a timeline, ordered by their :index then id."
  [store timeline-id]
  (->> (by-field store :slice :timeline timeline-id)
       (sort-by (juxt :index :id))))

(defn placements
  "Placements of a slice, ordered by :index then id."
  [store slice-id]
  (->> (by-field store :placement :slice slice-id)
       (sort-by (juxt :index :id))))
(defn specs        [store slice-id] (by-field store :specification :slice slice-id))

(defn model-slices
  "All slices across every timeline of a model."
  [store model-id]
  (mapcat #(slices store (:id %)) (timelines store model-id)))

(defn model-specs
  "All specifications across every slice of a model."
  [store model-id]
  (mapcat #(specs store (:id %)) (model-slices store model-id)))
(defn spec-steps
  "Steps of a specification, ordered by :index then id."
  [store spec-id]
  (->> (by-field store :spec-step :spec spec-id)
       (sort-by (juxt :index :id))))

(defn element-placements [store element-id] (by-field store :placement :element element-id))
(defn outgoing           [store element-id] (by-field store :connection :from element-id))
(defn incoming           [store element-id] (by-field store :connection :to element-id))

(defn placement-element
  "The Element a placement references."
  [store placement]
  (fetch store :element (:element placement)))

(defn step-element
  "The Element a spec-step references (nil for an error step)."
  [store step]
  (when-let [eid (:element step)]
    (fetch store :element eid)))

;; ---------------------------------------------------------------------------
;; Slice projections: placements grouped by referenced element kind
;; ---------------------------------------------------------------------------

(defn- placements-of-kind [store slice-id kind]
  (filter #(= kind (:kind (placement-element store %)))
          (placements store slice-id)))

(defn slice-commands    [store slice-id] (placements-of-kind store slice-id :command))
(defn slice-events      [store slice-id] (placements-of-kind store slice-id :event))
(defn slice-read-models [store slice-id] (placements-of-kind store slice-id :read_model))
(defn slice-screens     [store slice-id] (placements-of-kind store slice-id :screen))
(defn slice-automations [store slice-id] (placements-of-kind store slice-id :automation))

(defn slice-complete?
  "Slice.is_complete — the strict composition required before export."
  [store slice]
  (let [id (:id slice)]
    (case (:kind slice)
      :state_change (= 1 (count (slice-commands store id)))
      :state_view   (= 1 (count (slice-read-models store id)))
      :automation   (and (= 1 (count (slice-commands store id)))
                         (= 1 (count (slice-automations store id))))
      false)))

;; ---------------------------------------------------------------------------
;; Specification projections and derived predicate
;; ---------------------------------------------------------------------------

(defn spec-given-steps [store spec-id]
  (filter #(= :given_step (:clause %)) (spec-steps store spec-id)))

(defn spec-when-steps [store spec-id]
  (filter #(= :when_step (:clause %)) (spec-steps store spec-id)))

(defn spec-then-steps [store spec-id]
  (filter #(= :then_step (:clause %)) (spec-steps store spec-id)))

(defn spec-when-commands [store spec-id]
  (filter #(and (= :when_step (:clause %))
                (= :command (:kind (step-element store %))))
          (spec-steps store spec-id)))

(defn spec-then-read-models [store spec-id]
  (filter #(and (= :then_step (:clause %))
                (= :read_model (:kind (step-element store %))))
          (spec-steps store spec-id)))

(defn spec-complete?
  "Specification.is_complete — the singleton each shape requires at export."
  [store spec]
  (let [id        (:id spec)
        slice     (fetch store :slice (:slice spec))
        slice-kind (:kind slice)]
    (or (and (#{:state_change :automation} slice-kind)
             (= 1 (count (spec-when-commands store id))))
        (and (= :state_view slice-kind)
             (= 1 (count (spec-then-read-models store id)))))))

;; ---------------------------------------------------------------------------
;; Information completeness (Element.is_information_complete)
;; ---------------------------------------------------------------------------

(defn- field-carried?
  "Some incoming connection's `from` element has a field of this name."
  [store element-id field-name]
  (boolean (some (fn [c]
                   (let [from (fetch store :element (:from c))]
                     (some #(= field-name (:name %)) (:fields from))))
                 (incoming store element-id))))

(defn- field-derived?
  "Some incoming connection carries a derivation naming this field, whose every
  source field exists on that connection's `from` element."
  [store element-id field-name]
  (boolean (some (fn [c]
                   (let [from-names (set (map :name (:fields (fetch store :element (:from c)))))]
                     (some (fn [d]
                             (and (= field-name (:target_field d))
                                  (every? from-names (:source_fields d))))
                           (:derivations c))))
                 (incoming store element-id))))

(defn- field-introduced?
  "The element carries a field-origin override for this field."
  [element field-name]
  (boolean (some #(= field-name (:field %)) (:field_origins element))))

(defn field-sourced?
  "Whether a field of `element` is sourced — carried, derived, or introduced."
  [store element field-name]
  (or (field-carried? store (:id element) field-name)
      (field-derived? store (:id element) field-name)
      (field-introduced? element field-name)))

(defn unsourced-fields
  "Names of the element's fields that are not sourced (the completeness gap)."
  [store element]
  (->> (:fields element)
       (remove #(field-sourced? store element (:name %)))
       (mapv :name)))

(defn information-complete?
  "Element.is_information_complete — STRICT: every declared field is sourced."
  [store element]
  (empty? (unsourced-fields store element)))
