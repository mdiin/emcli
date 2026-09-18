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
  {:action :updated :type type :id id :entity (m/canonical-entity store type id)})
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

;; A name that identifies an entity to a caller must identify exactly one entity
;; of its container (see the uniqueness invariants in event-model.allium: element
;; names, timeline titles and swimlane names are unique within their model, a
;; slice's title within its timeline, a specification's title within its slice).
;; Unlike the invariant, which reports the collision, these guards reject the edit
;; before it is applied - and the rejection names the entity that already holds
;; the name, so a caller reuses or places what exists instead of inventing a
;; near-duplicate name.
(defn- name-conflict [type e entity-name]
  ;; The colliding entity's own type, whichever it has: an element carries
  ;; :element_type, a slice :slice_type, and a timeline or swimlane neither.
  (let [id     (:id e)
        subtype (or (:element_type e) (:slice_type e))]
    {:error :name-conflict :type type :id id :name entity-name
     :message (str "the name " (pr-str entity-name) " is already used by " (name type)
                   " " id (when subtype (str " (" (name subtype) ")"))
                   "; reuse or place it instead")}))

(defn- require-unique-name
  "Reject `entity-name` when one of `entities` - one container's worth, compared
  on `name-key` - already holds it. `exclude` is the entity's own id on a rename,
  so renaming an entity to the name it already holds is no collision."
  [type entity-name entities name-key exclude]
  (when-let [e (m/name-collision entity-name entities name-key exclude)]
    (name-conflict type e entity-name)))

(def ^:private element-types    #{:command :event :read_model :screen :automation})
(def ^:private slice-types      #{:state_change :state_view :automation})
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
  (or (when-let [msg (some invalid-field fields)]
        {:error :invalid-value :message msg})
      (when-let [dup (m/duplicate-field-name fields)]
        {:error :invalid-value
         :message (str "field " (pr-str dup) " appears twice in one field list")})))

(defn- canonical-fields
  "Every Field member the model holds, at every level: a missing :optional is
  false, a missing :cardinality is :single, a missing :subfields is empty. `value
  Field` declares all three, so a field authored through a flat flag set must be
  stored in the shape any other route (a pre-built argument map, the interchange
  format) already produces - the canonical record should not depend on how the
  field was authored."
  [fields]
  (mapv (fn [f]
          (assoc f
                 :optional (boolean (:optional f))
                 :cardinality (or (:cardinality f) :single)
                 :subfields (canonical-fields (:subfields f))))
        fields))

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
;; modelled identity (the scoping note on `surface ModelAuthoring` in
;; event-model.allium), so the model pre-exists any authoring session. There is
;; intentionally no RenameModel — the spec exposes `model.name` for reading but
;; provides no operation to change it.
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
      (require-unique-name :timeline title (m/timelines store model) :title nil)
      (let [[store tl] (m/create store :timeline (with-id {:model model :title title} id))]
        (commit store :CreateTimeline [(created :timeline tl)] tl))))

(defn rename-timeline [store {:keys [timeline new-title]}]
  (let [tl (m/fetch store :timeline timeline)]
    (or (require-entity store :timeline timeline)
        (require-non-blank :new-title new-title)
        (require-unique-name :timeline new-title
                             (m/timelines store (:model tl)) :title timeline)
        (let [store (m/set-field store :timeline timeline :title new-title)]
          (commit store :RenameTimeline [(updated store :timeline timeline)]
                  (m/fetch store :timeline timeline))))))

;; ---------------------------------------------------------------------------
;; Swimlanes
;; ---------------------------------------------------------------------------

(defn create-swimlane [store {:keys [model name index id]}]
  (or (require-entity store :event-model model)
      (require-id-available store id)
      (require-non-blank :name name)
      (require-unique-name :swimlane name (m/swimlanes store model) :name nil)
      (let [[store lane] (m/create store :swimlane (with-id {:model model :name name :index index} id))]
        (commit store :CreateSwimlane [(created :swimlane lane)] lane))))

(defn rename-swimlane [store {:keys [lane new-name]}]
  (let [current (m/fetch store :swimlane lane)]
    (or (require-entity store :swimlane lane)
        (require-non-blank :new-name new-name)
        (require-unique-name :swimlane new-name
                             (m/swimlanes store (:model current)) :name lane)
        (let [store (m/set-field store :swimlane lane :name new-name)]
          (commit store :RenameSwimlane [(updated store :swimlane lane)]
                  (m/fetch store :swimlane lane))))))

(defn reorder-swimlane [store {:keys [lane new-index]}]
  (or (require-entity store :swimlane lane)
      (let [store (m/set-field store :swimlane lane :index new-index)]
        (commit store :ReorderSwimlane [(updated store :swimlane lane)]
                (m/fetch store :swimlane lane)))))

;; ---------------------------------------------------------------------------
;; Slices
;; ---------------------------------------------------------------------------

(defn add-slice [store {:keys [timeline title slice-type index id]}]
  (or (require-entity store :timeline timeline)
      (require-id-available store id)
      (require-non-blank :title title)
      (require-valid-value slice-types slice-type)
      (require-unique-name :slice title (m/slices store timeline) :title nil)
      (let [[store sl] (m/create store :slice (with-id {:timeline timeline :title title
                                                        :slice_type slice-type :index index
                                                        :status :created} id))]
        (commit store :AddSlice [(created :slice sl)] sl))))

;; Rename a slice's title, unique within its timeline (invariant SliceTitleUnique).
;; Nothing references a slice by title - a slice is addressed by identity, and the
;; title only travels as a resolution breadcrumb - so there is nothing to cascade.
(defn rename-slice [store {:keys [slice new-title]}]
  (let [sl (m/fetch store :slice slice)]
    (or (require-entity store :slice slice)
        (require-non-blank :new-title new-title)
        (require-unique-name :slice new-title (m/slices store (:timeline sl)) :title slice)
        (let [store (m/set-field store :slice slice :title new-title)]
          (commit store :RenameSlice [(updated store :slice slice)]
                  (m/fetch store :slice slice))))))

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

(defn set-slice-type [store {:keys [slice new-slice-type]}]
  (or (require-entity store :slice slice)
      (require-valid-value slice-types new-slice-type)
      (let [store (m/set-field store :slice slice :slice_type new-slice-type)]
        (commit store :SetSliceType [(updated store :slice slice)]
                (m/fetch store :slice slice)))))

;; ---------------------------------------------------------------------------
;; Elements
;; ---------------------------------------------------------------------------

(defn create-element [store {:keys [model name element-type id]}]
  (or (require-entity store :event-model model)
      (require-id-available store id)
      (require-non-blank :name name)
      (require-valid-value element-types element-type)
      (require-unique-name :element name (m/elements store model) :name nil)
      (let [[store el] (m/create store :element (with-id {:model model :name name :element_type element-type
                                                           :context :internal :fields []
                                                           :field_origins []} id))
            ;; DeltaPerMutation: every entity carries its full new state, so the
            ;; `created` element delta carries the derived verdict too — a fresh
            ;; element declares no fields, so it is complete. Built from the
            ;; post-insert store, exactly as `updated` builds from the current.
            change     (created :element (m/canonical-entity store :element (:id el)))]
        (commit store :CreateElement [change] el))))

(defn- stranding-removal
  "A name this edit drops (present in the element's current fields, absent from
  `fields`) that the element's stored layout still names. Returns
  {:field name :nodes [ids]} for the first such name, else nil. Only the removal
  itself is checked, never the proposed state as a whole: a store that already
  holds a stranded reference stays editable, since its guard bites only when
  this edit would create one."
  [el fields]
  (let [proposed (map :name fields)
        refs     (wf/field-references (:wireframe el))]
    (some (fn [name]
            (let [nodes (->> refs
                             (filter #(m/same-name? name (:field-name %)))
                             (map :node-id)
                             distinct
                             vec)]
              (when (seq nodes) {:field name :nodes nodes})))
          (remove #(m/same-name-in? proposed %) (map :name (:fields el))))))

(defn- referenced-field-error [{:keys [field nodes]}]
  {:error :field-referenced :field field :nodes nodes
   :message (str "field " field " is referenced by layout node(s) "
                 (str/join ", " nodes) " and cannot be removed")})

(defn set-fields [store {:keys [element fields]}]
  (or (require-entity store :element element)
      (require-valid-fields fields)
      (or (when-let [stranded (stranding-removal (m/fetch store :element element) fields)]
            (referenced-field-error stranded))
          (let [store (m/set-field store :element element :fields (canonical-fields fields))]
            (commit store :SetFields [(updated store :element element)]
                    (m/fetch store :element element))))))

;; Convenience composite (a CLI affordance, not a domain operation): append (or
;; replace by name) a single field, preserving the element's others. Decomposes
;; into SetFields, so it emits exactly one SetFields delta.
(defn add-field [store {:keys [element field]}]
  (or (require-entity store :element element)
      (let [current (:fields (m/fetch store :element element))
            fields  (m/upsert-by current field :name)]
        (set-fields store {:element element :fields fields}))))

(defn remove-field [store {:keys [element name]}]
  (or (require-entity store :element element)
      (let [current (:fields (m/fetch store :element element))
            fields  (vec (remove #(m/same-name? name (:name %)) current))]
        (set-fields store {:element element :fields fields}))))

;; RenameField's cascade (see RenameField in event-model.allium). A field name is
;; the model's one TEXTUAL reference, so a rename respells every entry holding it
;; rather than being blocked by one. Two helpers express the keyed rewrites; the
;; layout's attributes are rewritten by the wireframe module.
(defn- rename-keyed
  "`entries` with the value at `key` set to `new` on every entry where it is the
  same name as `old`; every other entry, and every other part of a matched entry,
  is preserved."
  [entries key old new]
  (mapv (fn [e] (if (m/same-name? (get e key) old) (assoc e key new) e)) entries))

(defn- rename-source-fields
  "`derivations` with every source_fields entry that is the same name as `old`
  respelled to `new`; the derivations and their targets are otherwise preserved."
  [derivations old new]
  (mapv (fn [d] (update d :source_fields
                        (fn [fs] (mapv #(if (m/same-name? % old) new %) fs))))
        derivations))

;; Rename the field `name` on `element` to `new-name`, carrying every reference
;; with it: the declaration, the element's own field-origin override, the
;; derivations of every connection that touches the element, the examples of every
;; step that asserts about it, and the element's own layout. The two connection
;; sides are distinct - a derivation's target_field names a field of the
;; connection's `to` and its source_fields fields of its `from` - so an incoming
;; connection is rewritten on the target side and an outgoing one on the source
;; side, never the far one.
;; A name the element's fields do not carry is rejected rather than a silent
;; no-op, and the new name must not be one another field of the same list already
;; holds: like RemoveField this moves an existing field, so a collision would
;; destroy the sibling holding it. That is the deliberate contrast with AddField,
;; which upserts because it is how a field is edited (invariant FieldNameUnique).
(defn rename-field [store {:keys [element name new-name]}]
  (or (require-entity store :element element)
      (require-non-blank :new-name new-name)
      (let [pre      store
            el       (m/fetch store :element element)
            fields   (:fields el)
            incoming (m/incoming store element)
            outgoing (m/outgoing store element)
            steps    (m/by-field store :spec-step :element element)]
        (or (when-not (some #(m/same-name? (:name %) name) fields)
              {:error :invalid-value
               :message (str "element " element " has no field named " (pr-str name))})
            (when-let [clash (some #(when (and (not (m/same-name? (:name %) name))
                                               (m/same-name? (:name %) new-name))
                                        (:name %))
                                   fields)]
              {:error :name-conflict :type :field :id element :name clash
               :message (str "the name " (pr-str new-name) " is already used by field "
                             (pr-str clash) " of element " element
                             "; reuse or rename it instead")})
            (let [store (-> store
                            (m/set-field :element element :fields
                                         (rename-keyed fields :name name new-name))
                            (m/set-field :element element :field_origins
                                         (rename-keyed (:field_origins el) :field name new-name)))
                  ;; a screen's layout names its own fields, so it moves with them;
                  ;; an element with no layout keeps the key absent
                  store (if (some? (:wireframe el))
                          (m/set-field store :element element :wireframe
                                       (wf/rename-field-references (:wireframe el) name new-name))
                          store)
                  store (reduce (fn [s c]
                                  (m/set-field s :connection (:id c) :derivations
                                               (rename-keyed (:derivations c) :target_field name new-name)))
                                store incoming)
                  store (reduce (fn [s c]
                                  (m/set-field s :connection (:id c) :derivations
                                               (rename-source-fields (:derivations c) name new-name)))
                                store outgoing)
                  store (reduce (fn [s st]
                                  (m/set-field s :spec-step (:id st) :examples
                                               (rename-keyed (:examples st) :field_name name new-name)))
                                store steps)
                  ;; DeltaPerMutation carries every entity whose observable state
                  ;; CHANGED, not every entity the operation touched: a connection
                  ;; is restated only when a derivation naming the field moved, a
                  ;; step only when an example naming it did, and an element at the
                  ;; far end of an outgoing connection only when its completeness
                  ;; followed the source field's respelling. Renaming a field
                  ;; nothing references therefore emits the element alone.
                  moved-conns (filter (fn [c]
                                        (not= (:derivations c)
                                              (:derivations (m/fetch store :connection (:id c)))))
                                      (concat incoming outgoing))
                  moved-steps (filter (fn [st]
                                        (not= (:examples st)
                                              (:examples (m/fetch store :spec-step (:id st)))))
                                      steps)
                  moved-far   (filter (fn [id]
                                        (not= (m/information-complete? pre (m/fetch pre :element id))
                                              (m/information-complete? store (m/fetch store :element id))))
                                      (distinct (map :to outgoing)))
                  element-ids (distinct (cons element moved-far))
                  changes     (concat (map #(updated store :element %) element-ids)
                                      (map #(updated store :connection (:id %)) moved-conns)
                                      (map #(updated store :spec-step (:id %)) moved-steps))]
              (commit store :RenameField (vec changes) (m/fetch store :element element)))))))

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
            origins (m/upsert-by current {:field field :origin origin} :field)]
        (set-field-origins store {:element element :origins origins}))))

(defn remove-field-origin [store {:keys [element field]}]
  (or (require-entity store :element element)
      (let [current (:field_origins (m/fetch store :element element))
            origins (vec (remove #(m/same-name? field (:field %)) current))]
        (set-field-origins store {:element element :origins origins}))))

(defn rename-element [store {:keys [element new-name]}]
  (let [current (m/fetch store :element element)]
    (or (require-entity store :element element)
        (require-non-blank :new-name new-name)
        (require-unique-name :element new-name
                             (m/elements store (:model current)) :name element)
        (let [store (m/set-field store :element element :name new-name)]
          (commit store :RenameElement [(updated store :element element)]
                  (m/fetch store :element element))))))

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

;; A reorder moves a placement by one relative selector: --position front|back
;; takes it to an end, --before <element> / --after <element> take it next to a
;; sibling. Exactly one selector is required, and :position admits only the two
;; bounded values.
(def ^:private placement-positions #{:front :back})

(defn- require-valid-move
  "Exactly one of position/before/after selects the move; position is :front or
  :back, and a before/after anchor must name a different element from the one
  being moved (an anchor equal to the moved element has no placement left to
  insert next to once the moved placement is lifted out). Same :invalid-value
  shape as require-valid-value."
  [{:keys [element position before after]}]
  (let [selectors (cond-> []
                    (some? position) (conj :position)
                    (some? before)   (conj :before)
                    (some? after)    (conj :after))
        anchor    (if (some? before) before after)]
    (cond
      (empty? selectors)
      {:error :invalid-value
       :message "reorder requires exactly one of --position, --before or --after"}
      (next selectors)
      {:error :invalid-value
       :message (str "reorder accepts exactly one of --position, --before or --after, got "
                     (str/join " and " (map #(str "--" (name %)) selectors)))}
      (some? position) (require-valid-value placement-positions position)
      (= anchor element)
      {:error :invalid-value
       :message (str "reorder anchor " anchor " is the element being moved; "
                     "--before/--after must name a different element")})))

(defn- no-placement [slice element]
  {:error :not-found :type :placement :slice slice :element element
   :message (str "no placement of element " element " in slice " slice)})

(defn- normalize-move
  "The move selectors as a single {:position kw :anchor element-or-nil}."
  [{:keys [position before after]}]
  (cond
    position {:position position}
    before   {:position :before :anchor before}
    :else    {:position :after :anchor after}))

(defn- insert-next-to
  "`others` (a slice's placements minus the one being moved) with `target`
  inserted immediately before the placement of element `anchor` — or immediately
  after it when `after?`. `anchor` is known to be placed in the slice and to be a
  different element from `target`, so it is always present in `others`; the
  empty-`tail` branch merely keeps the helper total, degrading to an append
  rather than splicing a nil placement in."
  [others target anchor after?]
  (let [[head tail] (split-with #(not= anchor (:element %)) others)]
    (if (and after? (seq tail))
      (vec (concat head [(first tail) target] (rest tail)))
      (vec (concat head [target] tail)))))

(defn- reposition
  "The slice's placements `ps` reordered by `move`, preserving the relative order
  of every other placement. `move` is {:position :front}, {:position :back},
  {:position :before :anchor element} or {:position :after :anchor element}."
  [ps target {:keys [position anchor]}]
  (let [others (into [] (remove #(= (:id target) (:id %))) ps)]
    (case position
      :front  (into [target] others)
      :back   (conj others target)
      :before (insert-next-to others target anchor false)
      :after  (insert-next-to others target anchor true))))

(defn- renumber
  "Assign index = 0-based rank to every placement in `order` (a slice's placements
  after a move), returning [store' changes] where changes restates each placement
  whose index actually moved. Renormalizing the whole slice is intentional:
  indices are a sort key only, and ties break by creation order (now merely a
  defensive tiebreak — PlaceElement and ReorderPlacement both yield distinct
  indices), so a relative before/after move cannot be expressed by nudging a
  single integer."
  [store order]
  (reduce (fn [[s changes] [i p]]
            (if (= i (:index p))
              [s changes]
              (let [s (m/set-field s :placement (:id p) :index i)]
                [s (conj changes (updated s :placement (:id p)))])))
          [store []]
          (map-indexed vector order)))

(defn reorder-placement [store {:keys [slice element before after] :as args}]
  (or (require-valid-move args)
      (require-entity store :slice slice)
      (require-entity store :element element)
      (let [target (m/placement-of store slice element)
            move   (normalize-move args)
            anchor (m/placement-of store slice (:anchor move))]
        (or (when-not target (no-placement slice element))
            (when (and (:anchor move) (nil? anchor)) (no-placement slice (:anchor move)))
            (let [[store changes] (renumber store (reposition (m/placements store slice) target move))]
              (commit store :ReorderPlacement changes
                      (m/fetch store :placement (:id target))))))))

(defn remove-placement [store {:keys [slice element]}]
  (or (require-entity store :slice slice)
      (require-entity store :element element)
      (or (when-let [target (m/placement-of store slice element)]
            (let [store (m/delete store :placement (:id target))]
              (commit store :RemovePlacement [(deleted :placement (:id target))] target)))
          (no-placement slice element))))

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
            derivations (m/upsert-by current
                                     {:target_field target :source_fields (vec from)}
                                     :target_field)]
        (set-connection-derivations store {:connection connection :derivations derivations}))))

(defn remove-derivation [store {:keys [connection target]}]
  (or (require-entity store :connection connection)
      (let [current     (:derivations (m/fetch store :connection connection))
            derivations (vec (remove #(m/same-name? target (:target_field %)) current))]
        (set-connection-derivations store {:connection connection :derivations derivations}))))

;; ---------------------------------------------------------------------------
;; Specifications (Given / When / Then)
;; ---------------------------------------------------------------------------

(defn add-specification [store {:keys [slice title id]}]
  (or (require-entity store :slice slice)
      (require-id-available store id)
      (require-non-blank :title title)
      (require-unique-name :specification title (m/specs store slice) :title nil)
      (let [[store spec] (m/create store :specification (with-id {:slice slice :title title} id))]
        (commit store :AddSpecification [(created :specification spec)] spec))))

;; Rename a specification's title, unique within its slice (invariant
;; SpecificationTitleUnique) and likewise referenced by nothing.
(defn rename-specification [store {:keys [spec new-title]}]
  (let [sp (m/fetch store :specification spec)]
    (or (require-entity store :specification spec)
        (require-non-blank :new-title new-title)
        (require-unique-name :specification new-title (m/specs store (:slice sp)) :title spec)
        (let [store (m/set-field store :specification spec :title new-title)]
          (commit store :RenameSpecification [(updated store :specification spec)]
                  (m/fetch store :specification spec))))))

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

;; Rename an error outcome. error_name is CONTENT, not an identifier - no boundary
;; resolves a step by name - so no uniqueness is required of it. The guard is
;; therefore the SHAPE of the target: only an error step is renamed this way.
(defn rename-error-step [store {:keys [step new-error-name]}]
  (let [st (m/fetch store :spec-step step)]
    (or (require-entity store :spec-step step)
        (when-not (:is_error st)
          {:error :invalid-value
           :message (str "spec-step " step " is not an error step; only an error step "
                         "carries an error_name")})
        (require-non-blank :new-error-name new-error-name)
        (let [store (m/set-field store :spec-step step :error_name new-error-name)]
          (commit store :RenameErrorStep [(updated store :spec-step step)]
                  (m/fetch store :spec-step step))))))

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
            examples (m/upsert-by current
                                  {:field_name field-name :field_value field-value}
                                  :field_name)]
        (set-step-examples store {:step step :examples examples}))))

(defn remove-step-example [store {:keys [step field-name]}]
  (or (require-entity store :spec-step step)
      (let [current  (:examples (m/fetch store :spec-step step))
            examples (vec (remove #(m/same-name? field-name (:field_name %)) current))]
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
        pre       store
        conns     (vals (into {} (map (juxt :id identity))
                              (concat (m/outgoing store element-id)
                                      (m/incoming store element-id))))
        ;; Removing a connection can move the derived is_information_complete of
        ;; its surviving :to endpoint — exactly why Disconnect restates its
        ;; target element. The element being deleted is not a survivor, and an
        ;; element fed by several removed connections is restated once.
        survivors (->> conns
                       (map :to)
                       (remove #{element-id})
                       distinct)
        acc       (reduce (fn [a p] (del a :placement (:id p)))
                          acc (m/element-placements store element-id))
        acc       (reduce (fn [a c] (del a :connection (:id c))) acc conns)
        [store changes] (del acc :element element-id)
        ;; DeltaPerMutation restates entities whose observable state CHANGED. A
        ;; survivor that was complete by some other route before the removal is
        ;; still complete after it, and is not restated.
        moved     (filter (fn [id]
                            (not= (m/information-complete? pre (m/fetch pre :element id))
                                  (m/information-complete? store (m/fetch store :element id))))
                          survivors)]
    [store (into changes (map #(updated store :element %)) moved)]))

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

(defn add-wireframe-node [store {:keys [element tag parent attrs text]}]
  (or (require-entity store :element element)
      (let [el (m/fetch store :element element)]
        (or (when (not= :screen (:element_type el))
              {:error :invalid-value
               :message (str "element " element " is not a screen")})
            (when (= :canvas tag)
              {:error :invalid-value
               :message (str "tag :canvas is the layout's root (always n1) and "
                             "cannot be added as a node")})
            (let [seed     [:canvas {:-id "n1"}]
                  wf       (or (:wireframe el) seed)
                  schema   (wf/tag-schema tag)
                  ;; For text-children tags, the rule's own :text input becomes a
                  ;; string child; a stray :text in attrs is not a node attribute
                  text-child (when (:text-children? schema) text)
                  clean-attrs (if (:text-children? schema) (dissoc attrs :text) attrs)
                  child    (cond-> [tag]
                             (seq clean-attrs) (conj clean-attrs)
                             text-child        (conj text-child))
                  wf'      (wf/append-child-at wf (or parent "n1") child)
                  sv       (wf/validate wf')
                  ss       (wf/validate-semantics wf' el)]
              (or (when (and parent (not (wf/find-node wf parent)))
                    {:error :not-found :type :wireframe-node :id parent
                     :message (str "node " parent " does not exist")})
                  (when-not (:valid? sv) (wireframe-invalid sv))
                  (when-not (:valid? ss) (wireframe-invalid ss))
                   (let [store (m/set-field store :element element :wireframe wf')]
                     (commit store :AddWireframeNode [(updated store :element element)]
                             (m/fetch store :element element)))))))))

(defn add-wireframe-node-before [store {:keys [element before tag attrs text]}]
  (or (require-entity store :element element)
      (let [el (m/fetch store :element element)]
        (or (when (not= :screen (:element_type el))
              {:error :invalid-value
               :message (str "element " element " is not a screen")})
            (when (= :canvas tag)
              {:error :invalid-value
               :message (str "tag :canvas is the layout's root (always n1) and "
                             "cannot be added as a node")})
            (when-not (:wireframe el)
              {:error :not-found :type :wireframe
               :message (str "element " element " has no wireframe")})
            (when-not (wf/find-node (:wireframe el) before)
              {:error :not-found :type :wireframe-node :id before
               :message (str "node " before " does not exist")})
            (let [schema      (wf/tag-schema tag)
                  ;; Same split as add-wireframe-node: :text is the rule's own
                  ;; input for text-children tags, never a node attribute
                  text-child  (when (:text-children? schema) text)
                  clean-attrs (if (:text-children? schema) (dissoc attrs :text) attrs)
                  child       (cond-> [tag]
                                (seq clean-attrs) (conj clean-attrs)
                                text-child        (conj text-child))
                  wf'         (wf/insert-before-at (:wireframe el) before child)]
              (or (when-not wf'
                    {:error :invalid-value
                     :message (str "cannot insert before root node " before)})
                  (let [sv (wf/validate wf')
                        ss (wf/validate-semantics wf' el)]
                    (or (when-not (:valid? sv) (wireframe-invalid sv))
                        (when-not (:valid? ss) (wireframe-invalid ss))
                        (let [store (m/set-field store :element element :wireframe wf')]
                          (commit store :AddWireframeNodeBefore
                                  [(updated store :element element)]
                                  (m/fetch store :element element)))))))))))

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

(defn set-wireframe-text [store {:keys [element node text]}]
  (or (require-entity store :element element)
      (let [el (m/fetch store :element element)]
        (or (when-not (:wireframe el)
              {:error :not-found :type :wireframe
               :message (str "element " element " has no wireframe")})
            (when-not (wf/find-node (:wireframe el) node)
              {:error :not-found :type :wireframe-node :id node
               :message (str "node " node " does not exist")})
            (let [found-node (wf/find-node (:wireframe el) node)
                  tag        (first found-node)
                  schema     (wf/tag-schema tag)]
              (or (when-not (:text-children? schema)
                    {:error :invalid-value
                     :message (str "node " node " (:" (name tag) ") does not accept text content")})
                  (let [wf'  (wf/set-text-child-at (:wireframe el) node text)
                        sv   (wf/validate wf')
                        ss   (wf/validate-semantics wf' el)]
                    (or (when-not (:valid? sv) (wireframe-invalid sv))
                        (when-not (:valid? ss) (wireframe-invalid ss))
                        (let [store (m/set-field store :element element :wireframe wf')]
                          (commit store :SetWireframeText
                                  [(updated store :element element)]
                                  (m/fetch store :element element)))))))))))

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
