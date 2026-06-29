(ns emcli.invariants
  "The always-on invariants from event-model.allium. `check` returns a (possibly
  empty) vector of violations for a store; the authoring rules consult it to
  reject any mutation that would break an invariant, so the invariants hold at
  every observable state."
  (:require [emcli.model :as m]))

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

(defn check
  "Return every invariant violation in `store` (empty when the store is valid)."
  [store]
  (vec (concat (placement-violations store)
               (spec-violations store)
               (connection-violations store))))

(defn valid? [store]
  (empty? (check store)))
