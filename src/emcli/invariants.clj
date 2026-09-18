(ns emcli.invariants
  "The always-on invariants from event-model.allium. `check` returns a (possibly
  empty) vector of violations for a store; the authoring rules consult it to
  reject any mutation that would break an invariant, so the invariants hold at
  every observable state."
  (:require [clojure.string :as str]
            [emcli.model :as m]
            [emcli.wireframe :as wf]))

(defn- step-element-type [store step]
  (:element_type (m/step-element store step)))

;; PlacementMatchesSliceType ------------------------------------------------
;; A non-informational slice may never hold an element type that is a category
;; error for it, nor more than one of a singleton type.
(defn- placement-violations [store]
  (for [s     (m/all store :slice)
        :when (not= :informational (:status s))
        :let  [id (:id s)
               c  (count (m/slice-commands store id))
               r  (count (m/slice-read-models store id))
               a  (count (m/slice-automations store id))
               ok (case (:slice_type s)
                    :state_change (and (<= c 1) (zero? r) (zero? a))
                    :state_view   (and (<= r 1) (zero? c) (zero? a))
                    :automation   (and (<= c 1) (<= a 1) (zero? r))
                    true)]
        :when (not ok)]
    {:invariant :PlacementMatchesSliceType
     :slice     id
     :message   (str "Slice " id " (" (name (:slice_type s)) ") has an illegal "
                     "placement composition: commands=" c
                     " read_models=" r " automations=" a)}))

;; PlacementElementUnique ----------------------------------------------------
;; No slice may hold two placements of the same element. Informational slices
;; are not exempt; the same element may still be placed in different slices.
(defn- duplicate-placement-violations [store]
  (for [s     (m/all store :slice)
        :let  [dups (->> (m/placements store (:id s))
                         (map :element)
                         frequencies
                         (keep (fn [[el n]] (when (< 1 n) el)))
                         sort
                         vec)]
        :when (seq dups)]
    {:invariant :PlacementElementUnique
     :slice     (:id s)
     :message   (str "Slice " (:id s) " places element(s) "
                     (str/join ", " dups) " more than once")}))

;; Name uniqueness ------------------------------------------------------------
;; Within one container no two entities may share a name: an element's name, a
;; timeline's title and a swimlane's name are unique within their model, a
;; slice's title within its timeline and a specification's title within its slice
;; (see the uniqueness invariants in event-model.allium). All five are the same
;; property over the same comparison (m/same-name?), so they share one
;; implementation and differ only in the container they are checked within.
;; A collision is deliberately NOT repaired (unlike a duplicate placement):
;; choosing which of two same-named entities to rename, and to what, would be
;; guessing the author's intent, so it is reported for a human to resolve.
(defn- name-collisions
  "The groups of `entities` that share a name under m/same-name?, as non-empty
  seqs of the colliding entities. Each group is reported once, whether two
  entities share a name or ten."
  [entities name-field]
  (->> entities
       (map (fn [e] [(m/same-name-key (get e name-field)) e]))
       (filter (fn [[k _]] k))
       (group-by first)
       (sort-by key)
       (keep (fn [[_ pairs]] (when (< 1 (count pairs)) (map second pairs))))))

(defn- collision-violations
  "The violations of `invariant` for every name shared by two or more of
  `entities` (one container's worth, compared on `name-field`). Each message
  names the entities that collide, since that is what a human has to rename."
  [invariant type name-field entities]
  (for [es (name-collisions entities name-field)]
    {:invariant invariant
     :type      type
     :name      (get (first es) name-field)
     :entities  (mapv #(select-keys % [:id :element_type :slice_type]) es)
     :message   (str (str/join ", " (map #(str (name type) " " (:id %)) es))
                     " share the name " (pr-str (get (first es) name-field)))}))

(defn- uniqueness-violations
  "Every violation of the five name-uniqueness invariants, each checked within
  its own container."
  [store]
  (let [across (fn [invariant type name-field containers]
                 (mapcat #(collision-violations invariant type name-field %)
                         containers))]
    (vec (concat
          (across :ElementNameUnique :element :name
                  (for [m (m/all store :event-model)] (m/elements store (:id m))))
          (across :TimelineTitleUnique :timeline :title
                  (for [m (m/all store :event-model)] (m/timelines store (:id m))))
          (across :SwimlaneNameUnique :swimlane :name
                  (for [m (m/all store :event-model)] (m/swimlanes store (:id m))))
          (across :SliceTitleUnique :slice :title
                  (for [t (m/all store :timeline)] (m/slices store (:id t))))
          (across :SpecificationTitleUnique :specification :title
                  (for [s (m/all store :slice)] (m/specs store (:id s))))))))

;; repair ---------------------------------------------------------------------
;; A store loaded from disk must never leave the model wedged: rules/commit
;; re-checks the WHOLE store on every mutation, so one bad entity from a
;; hand-edited or older file would make every later mutation fail with an
;; invisible :invariant-violation. `repair` fixes what can be fixed without
;; guessing the author's intent, and reports what it did.
(defn- duplicate-placement-repair
  "For every slice holding the same element more than once, the placements to
  drop: each placement after the first in `m/placements` order (lowest :index,
  then lowest :id). Keeping the first is principled — it is already the
  placement the author's own ordering puts first."
  [store]
  (for [s     (m/all store :slice)
        :let  [[_ dropped] (reduce (fn [[seen acc] p]
                                     (if (contains? seen (:element p))
                                       [seen (conj acc (:id p))]
                                       [(conj seen (:element p)) acc]))
                                   [#{} []]
                                   (m/placements store (:id s)))]
        :when (seq dropped)]
    {:invariant :PlacementElementUnique
     :slice     (:id s)
     :dropped   dropped}))

(defn repair
  "Repair `store`, returning `[store' repairs]`. `repairs` is a vector of
  `{:invariant :PlacementElementUnique :slice <slice-id> :dropped [placement-ids]}`
  describing every change `store'` makes to `store`.

  Only invariants that have a principled, non-arbitrary repair are repaired —
  currently PlacementElementUnique alone: the first placement of an element in a
  slice (in placement order) is kept and every later duplicate is dropped.
  Anything else, such as a category error like a read_model placed in a
  state_change slice, is left alone, because repairing it would mean guessing
  what the author meant.

  A name collision (the uniqueness invariants) is left alone for the same reason:
  choosing which of two same-named entities to rename, and what to rename it to,
  is the author's decision, so a store holding one is reported - and therefore
  refuses to load (see `load-app` in emcli.app) - rather than silently renamed.
  So is a malformed layout (the wireframe invariants): dropping the offending node
  would delete a subtree the author wrote, and a tree the layout rules would never
  have installed can only have reached the store by a hand edit or an older build.
  The refusal names the element and the node, so the operator can fix that one
  node by hand.

  Repairing is a best-effort normalisation, NOT validation: callers MUST
  re-check the returned store with `check` and decide what to do with whatever
  remains. `repair` is idempotent — repairing a repaired store returns it
  unchanged with no repairs."
  [store]
  (let [repairs (vec (duplicate-placement-repair store))
        store'  (reduce (fn [s {:keys [dropped]}]
                          (reduce #(m/delete %1 :placement %2) s dropped))
                        store repairs)]
    [store' repairs]))

;; SpecificationComposition --------------------------------------------------
;; Given steps are events; When steps are commands; the Then shape follows the
;; slice's pattern. The "exactly one" singleton requirement is deferred to
;; export (Specification.is_complete), so only the loosened form is enforced.
(defn- spec-violations [store]
  (for [spec  (m/all store :specification)
        :let  [id         (:id spec)
               slice      (m/fetch store :slice (:slice spec))
               slice-type (:slice_type slice)
               givens     (m/spec-given-steps store id)
               whens      (m/spec-when-steps store id)
               thens      (m/spec-then-steps store id)
               ok (and
                   (every? #(= :event (step-element-type store %)) givens)
                   (every? #(= :command (step-element-type store %)) whens)
                   (if (#{:state_change :automation} slice-type)
                     (and (<= (count (m/spec-when-commands store id)) 1)
                          (every? #(or (:is_error %) (= :event (step-element-type store %)))
                                  thens))
                     true)
                   (if (= :state_view slice-type)
                     (and (zero? (count whens))
                          (<= (count (m/spec-then-read-models store id)) 1)
                          (every? #(= :read_model (step-element-type store %)) thens))
                     true))]
        :when (not ok)]
    {:invariant     :SpecificationComposition
     :specification id
     :message       (str "Specification " id " violates the composition rules "
                         "for its " (name (or slice-type :nil)) " slice")}))

;; ValidConnectionElementTypes ------------------------------------------------------
(def ^:private valid-connection-pairs
  #{[:screen :command]
    [:command :event]
    [:event :read_model]
    [:read_model :screen]
    [:read_model :automation]
    [:automation :command]})

(defn- connection-violations [store]
  (for [c     (m/all store :connection)
        :let  [from (m/fetch store :element (:from c))
               to   (m/fetch store :element (:to c))]
        :when (not (valid-connection-pairs [(:element_type from) (:element_type to)]))]
    {:invariant  :ValidConnectionElementTypes
     :connection (:id c)
     :message    (str "Connection " (:id c) " is not a valid Event Modeling "
                      "pattern: " (some-> from :element_type name) " -> "
                      (some-> to :element_type name))}))

;; ExamplesWellFormed ---------------------------------------------------------
;; Every recorded example on a spec step must carry a non-empty field_name and
;; field_value. Catches malformed payloads (e.g. wrong JSON keys) that
;; set-step-examples stores verbatim without validation.
(defn- example-violations [store]
  (for [st          (m/all store :spec-step)
        [idx e]     (map-indexed vector (:examples st))
        :when       (or (str/blank? (:field_name e)) (str/blank? (:field_value e)))]
    {:invariant :ExamplesWellFormed
     :step      (:id st)
     :message   (str "SpecStep " (:id st) " example[" idx "] must have a "
                     "non-empty field_name and field_value")}))

;; Wireframes ----------------------------------------------------------------
;; A layout carried by an element must be structurally well-formed and must name
;; only fields its screen declares. Both used to be claims only the wireframe
;; rules upheld (every edit validates the tree it is about to install), so a
;; store loaded from disk could hold a malformed tree or a dangling field
;; reference: `load-app` accepted it, and every later wireframe edit on that
;; screen was refused by a cause nothing could see. Enforced at commit, such a
;; store refuses to load instead. Structural well-formedness stays a separate
;; invariant from reference resolution because it is deliberately structural:
;; it says nothing about whether the layout's field names resolve.
(defn- structural-wireframe-violation
  "The WireframeWellFormed violation for element `e` carrying wireframe `w`, or
  nil. A wireframe on a non-screen element is itself the violation — the
  invariant's own statement is `e.element_type = screen and wireframe_well_formed(...)`."
  [e w]
  (if (not= :screen (:element_type e))
    {:invariant :WireframeWellFormed
     :element   (:id e)
     :message   (str "Element " (:id e) " carries a wireframe but its element_type is "
                     (pr-str (:element_type e)) ", not :screen")}
    (let [{:keys [valid? errors]} (wf/validate w)]
      (when-not valid?
        {:invariant :WireframeWellFormed
         :element   (:id e)
         :message   (str "Element " (:id e) " carries a malformed wireframe: "
                         (str/join "; " (map :message errors)))}))))

(defn- wireframe-element-violations
  "Every violation for one wireframe-carrying element `e`: the structural check
  first and, only if it passes, the field-reference check. A structurally broken
  tree has no trustworthy field references — its own walk may already be reading
  a node shape that is not a node — so one broken tree is reported once instead
  of spraying cascading noise."
  [e]
  (let [w (:wireframe e)]
    (if-let [structural (structural-wireframe-violation e w)]
      [structural]
      (let [{:keys [valid? errors]} (wf/validate-semantics w e)]
        (if valid?
          []
          [{:invariant :WireframeReferencesResolve
            :element   (:id e)
            :message   (str "Element " (:id e) " wireframe names field(s) the "
                            "screen does not declare: "
                            (str/join "; " (map :message errors)))}])))))

(defn- wireframe-violations [store]
  (into []
        (comp (filter #(some? (:wireframe %)))
              (mapcat wireframe-element-violations))
        (m/all store :element)))

(defn check
  "Return every invariant violation in `store` (empty when the store is valid)."
  [store]
  (vec (concat (placement-violations store)
               (duplicate-placement-violations store)
               (uniqueness-violations store)
               (spec-violations store)
               (connection-violations store)
               (example-violations store)
               (wireframe-violations store))))

(defn valid? [store]
  (empty? (check store)))
