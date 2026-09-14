(ns emcli.invariants
  "The always-on invariants from event-model.allium. `check` returns a (possibly
  empty) vector of violations for a store; the authoring rules consult it to
  reject any mutation that would break an invariant, so the invariants hold at
  every observable state."
  (:require [clojure.string :as str]
            [emcli.model :as m]))

(defn- step-kind [store step]
  (:kind (m/step-element store step)))

;; PlacementMatchesSliceKind ------------------------------------------------
;; A non-informational slice may never hold an element kind that is a category
;; error for it, nor more than one of a singleton kind.
(defn- placement-violations [store]
  (for [s     (m/all store :slice)
        :when (not= :informational (:status s))
        :let  [id (:id s)
               c  (count (m/slice-commands store id))
               r  (count (m/slice-read-models store id))
               a  (count (m/slice-automations store id))
               ok (case (:kind s)
                    :state_change (and (<= c 1) (zero? r) (zero? a))
                    :state_view   (and (<= r 1) (zero? c) (zero? a))
                    :automation   (and (<= c 1) (<= a 1) (zero? r))
                    true)]
        :when (not ok)]
    {:invariant :PlacementMatchesSliceKind
     :slice     id
     :message   (str "Slice " id " (" (name (:kind s)) ") has an illegal "
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
               slice-kind (:kind slice)
               givens     (m/spec-given-steps store id)
               whens      (m/spec-when-steps store id)
               thens      (m/spec-then-steps store id)
               ok (and
                   (every? #(= :event (step-kind store %)) givens)
                   (every? #(= :command (step-kind store %)) whens)
                   (if (#{:state_change :automation} slice-kind)
                     (and (<= (count (m/spec-when-commands store id)) 1)
                          (every? #(or (:is_error %) (= :event (step-kind store %)))
                                  thens))
                     true)
                   (if (= :state_view slice-kind)
                     (and (zero? (count whens))
                          (<= (count (m/spec-then-read-models store id)) 1)
                          (every? #(= :read_model (step-kind store %)) thens))
                     true))]
        :when (not ok)]
    {:invariant     :SpecificationComposition
     :specification id
     :message       (str "Specification " id " violates the composition rules "
                         "for its " (name (or slice-kind :nil)) " slice")}))

;; ValidConnectionKinds ------------------------------------------------------
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
        :when (not (valid-connection-pairs [(:kind from) (:kind to)]))]
    {:invariant  :ValidConnectionKinds
     :connection (:id c)
     :message    (str "Connection " (:id c) " is not a valid Event Modeling "
                      "pattern: " (some-> from :kind name) " -> "
                      (some-> to :kind name))}))

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

(defn check
  "Return every invariant violation in `store` (empty when the store is valid)."
  [store]
  (vec (concat (placement-violations store)
               (duplicate-placement-violations store)
               (spec-violations store)
               (connection-violations store)
               (example-violations store))))

(defn valid? [store]
  (empty? (check store)))
