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

(defn create
  "Allocate an id and insert a new entity of `type` with `attrs`.
  Returns [store' entity]."
  [store type attrs]
  (let [[store id] (next-id store)
        entity     (merge attrs {:id id :type type})]
    [(insert store entity) entity]))

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
(defn swimlanes     [store model-id] (by-field store :swimlane :model model-id))
(defn elements      [store model-id] (by-field store :element :model model-id))
(defn connections   [store model-id] (by-field store :connection :model model-id))
(defn subscriptions [store model-id] (by-field store :subscription :model model-id))

(defn slices
  "Slices of a timeline, ordered by their :index then id."
  [store timeline-id]
  (->> (by-field store :slice :timeline timeline-id)
       (sort-by (juxt :index :id))))

(defn placements   [store slice-id] (by-field store :placement :slice slice-id))
(defn specs        [store slice-id] (by-field store :specification :slice slice-id))
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
