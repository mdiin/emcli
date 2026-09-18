(ns emcli.invariants-test
  "invariant obligations: PlacementMatchesSliceType, PlacementElementUnique,
  ElementNameUnique, TimelineTitleUnique, SwimlaneNameUnique, SliceTitleUnique,
  SpecificationTitleUnique, SpecificationComposition, ValidConnectionElementTypes,
  WireframeWellFormed, WireframeReferencesResolve. Rules must reject any mutation
  that would break one."
  (:require [clojure.test :refer [deftest testing is]]
            [emcli.invariants :as inv]
            [emcli.model :as m]
            [emcli.rules :as r]
            [emcli.support :as s]))

(defn- slice-of [slice-type]
  (let [[store mid] (s/with-model)
        store       (:store (s/ok store r/create-timeline {:model mid :title "T"}))
        tlid        (:id (first (m/timelines store mid)))
        store       (:store (s/ok store r/add-slice {:timeline tlid :title "S" :slice-type slice-type :index 0}))
        slid        (:id (first (m/slices store tlid)))]
    [store mid slid]))

(defn- element [store mid name element-type]
  (let [store (:store (s/ok store r/create-element {:model mid :name name :element-type element-type}))]
    [store (:id (last (m/elements store mid)))]))

;; --- PlacementMatchesSliceType --------------------------------------------

(deftest placement-must-match-slice-type
  (testing "a read_model cannot be placed in a state_change slice"
    (let [[store mid slid] (slice-of :state_change)
          [store rm]       (element store mid "OrderList" :read_model)
          res              (r/place-element store {:slice slid :element rm})]
      (is (r/error? res))
      (is (= :invariant-violation (:error res)))))
  (testing "a second command cannot be placed in a state_change slice"
    (let [[store mid slid] (slice-of :state_change)
          [store c1]       (element store mid "PlaceOrder" :command)
          [store c2]       (element store mid "CancelOrder" :command)
          store            (:store (s/ok store r/place-element {:slice slid :element c1}))
          res              (r/place-element store {:slice slid :element c2})]
      (is (r/error? res))))
  (testing "informational slices are exempt"
    (let [[store mid slid] (slice-of :state_change)
          store            (m/set-field store :slice slid :status :informational)
          [store rm]       (element store mid "OrderList" :read_model)
          res              (r/place-element store {:slice slid :element rm})]
      (is (not (r/error? res))))))

;; --- PlacementElementUnique ------------------------------------------------

(deftest same-element-cannot-be-placed-twice-in-one-slice
  (testing "a second placement of the same event in a state_change slice is rejected"
    (let [[store mid slid] (slice-of :state_change)
          [store evt]      (element store mid "OrderPlaced" :event)
          store            (:store (s/ok store r/place-element {:slice slid :element evt}))
          res              (r/place-element store {:slice slid :element evt})]
      (is (r/error? res))
      (is (= :invariant-violation (:error res)))))
  (testing "the same element may be placed in two different slices"
    (let [[store mid slid] (slice-of :state_change)
          [store evt]      (element store mid "OrderPlaced" :event)
          store            (:store (s/ok store r/place-element {:slice slid :element evt}))
          tlid             (:timeline (m/fetch store :slice slid))
          store            (:store (s/ok store r/add-slice {:timeline tlid
                                                            :title "S2"
                                                            :slice-type :state_change
                                                            :index 1}))
          slid2            (:id (second (m/slices store tlid)))
          res              (r/place-element store {:slice slid2 :element evt})]
      (is (not (r/error? res)))))
  (testing "informational slices are not exempt"
    (let [[store mid slid] (slice-of :state_change)
          store            (m/set-field store :slice slid :status :informational)
          [store evt]      (element store mid "OrderPlaced" :event)
          store            (:store (s/ok store r/place-element {:slice slid :element evt}))
          res              (r/place-element store {:slice slid :element evt})]
      (is (r/error? res)))))

;; --- repair ----------------------------------------------------------------

(deftest repair-drops-duplicate-placements
  (testing "the later duplicate is dropped, the first placement is kept"
    (let [[store mid slid] (slice-of :state_change)
          [store evt]      (element store mid "OrderPlaced" :event)
          store            (:store (s/ok store r/place-element {:slice slid :element evt}))
          first-id         (:id (first (m/placements store slid)))
          ;; inject a second placement directly: the rules now reject this
          [store dup]      (m/create store :placement {:slice slid :element evt
                                                        :index 1})
          [store' repairs] (inv/repair store)]
      (is (seq repairs))
      (is (= :PlacementElementUnique (:invariant (first repairs))))
      (is (= slid (:slice (first repairs))))
      (is (= [(:id dup)] (:dropped (first repairs))))
      (is (= 1 (count (m/placements store' slid))))
      (is (= first-id (:id (first (m/placements store' slid)))))
      (is (empty? (inv/check store')))))
  (testing "a clean store is returned unchanged with no repairs"
    (let [[store mid slid] (slice-of :state_change)
          [store evt]      (element store mid "OrderPlaced" :event)
          store            (:store (s/ok store r/place-element {:slice slid :element evt}))
          [store' repairs] (inv/repair store)]
      (is (empty? repairs))
      (is (= store store')))))

;; --- name uniqueness -------------------------------------------------------
;; An element's name, a timeline's title and a swimlane's name are unique within
;; their model, a slice's title within its timeline and a specification's title
;; within its slice. Collisions are injected through `m/create`, the way a
;; hand-edited or older file holds them: the authoring rules now reject one at the
;; guard (see rules-test), so a direct write is the only way left to reach the
;; state.

(defn- element-attrs [mid name element-type]
  {:model mid :name name :element_type element-type :context :internal :fields [] :field_origins []})

(defn- collision-invariants [store]
  (map :invariant (inv/check store)))

(deftest element-names-are-unique-within-a-model
  (testing "two elements may not share a name, whatever their element types"
    (let [[store mid] (s/with-model)
          [store _]   (m/create store :element (element-attrs mid "Order" :command))
          [store _]   (m/create store :element (element-attrs mid "order" :event))]
      (is (= [:ElementNameUnique] (collision-invariants store)))))
  (testing "the collision wedges the model: every later mutation is refused"
    (let [[store mid] (s/with-model)
          [store _]   (m/create store :element (element-attrs mid "Order" :command))
          [store _]   (m/create store :element (element-attrs mid "ORDER" :event))
          res         (r/create-timeline store {:model mid :title "T"})]
      (is (= :invariant-violation (:error res)))))
  (testing "uniqueness is per model: two models may each name an element \"Order\""
    (let [[store mid] (s/with-model)
          [store _]   (m/create store :element (element-attrs mid "Order" :command))
          [store m2]  (m/create store :event-model {:name "Other"})
          [store _]   (m/create store :element (element-attrs (:id m2) "Order" :command))]
      (is (empty? (inv/check store))))))

(deftest timeline-titles-are-unique-within-a-model
  (testing "two timelines may not share a title, case and spacing aside"
    (let [[store mid] (s/with-model)
          [store _]   (m/create store :timeline {:model mid :title "Ordering"})
          [store _]   (m/create store :timeline {:model mid :title " ordering "})]
      (is (= [:TimelineTitleUnique] (collision-invariants store))))))

(deftest swimlane-names-are-unique-within-a-model
  (testing "two swimlanes may not share a name"
    (let [[store mid] (s/with-model)
          [store _]   (m/create store :swimlane {:model mid :name "Orders" :index 0})
          [store _]   (m/create store :swimlane {:model mid :name "orders" :index 1})]
      (is (= [:SwimlaneNameUnique] (collision-invariants store))))))

(deftest slice-titles-are-unique-within-a-timeline
  (testing "two slices of one timeline may not share a title"
    (let [[store mid] (s/with-model)
          [store t]   (m/create store :timeline {:model mid :title "Ordering"})
          [store _]   (m/create store :slice {:timeline (:id t) :title "Add" :slice_type :state_change
                                              :index 0 :status :created})
          [store _]   (m/create store :slice {:timeline (:id t) :title "add" :slice_type :state_change
                                              :index 1 :status :created})]
      (is (= [:SliceTitleUnique] (collision-invariants store)))))
  (testing "the same title in another timeline is a different slice, and legal"
    (let [[store mid] (s/with-model)
          [store t1]  (m/create store :timeline {:model mid :title "Ordering"})
          [store t2]  (m/create store :timeline {:model mid :title "Viewing"})
          [store _]   (m/create store :slice {:timeline (:id t1) :title "Add" :slice_type :state_change
                                              :index 0 :status :created})
          [store _]   (m/create store :slice {:timeline (:id t2) :title "Add" :slice_type :state_change
                                              :index 0 :status :created})]
      (is (empty? (inv/check store))))))

(deftest specification-titles-are-unique-within-a-slice
  (testing "two specifications of one slice may not share a title"
    (let [[store mid] (s/with-model)
          [store t]   (m/create store :timeline {:model mid :title "Ordering"})
          [store sl]  (m/create store :slice {:timeline (:id t) :title "Add" :slice_type :state_change
                                              :index 0 :status :created})
          [store _]   (m/create store :specification {:slice (:id sl) :title "Happy path"})
          [store _]   (m/create store :specification {:slice (:id sl) :title "HAPPY PATH"})]
      (is (= [:SpecificationTitleUnique] (collision-invariants store)))))
  (testing "the same title in another slice is a different specification, and legal"
    (let [[store mid] (s/with-model)
          [store t]   (m/create store :timeline {:model mid :title "Ordering"})
          [store sl1] (m/create store :slice {:timeline (:id t) :title "Add" :slice_type :state_change
                                              :index 0 :status :created})
          [store sl2] (m/create store :slice {:timeline (:id t) :title "Cancel" :slice_type :state_change
                                              :index 1 :status :created})
          [store _]   (m/create store :specification {:slice (:id sl1) :title "Happy path"})
          [store _]   (m/create store :specification {:slice (:id sl2) :title "Happy path"})]
      (is (empty? (inv/check store))))))

(deftest name-collisions-are-not-repairable
  (testing "repair leaves a collision alone: renaming one of the two would be guessing intent"
    (let [[store mid] (s/with-model)
          [store _]   (m/create store :element (element-attrs mid "Order" :command))
          [store _]   (m/create store :element (element-attrs mid "Order" :event))
          [store' repairs] (inv/repair store)]
      (is (empty? repairs))
      (is (= store store'))
      (is (= [:ElementNameUnique] (collision-invariants store'))))))

;; --- WireframeWellFormed / WireframeReferencesResolve ----------------------

(defn- screen-with-field
  "A store holding one screen element that declares a single :searchTerm field,
  plus the model id and the screen's element id."
  []
  (let [[store mid] (s/with-model)
        [store eid] (element store mid "OrderList" :screen)
        store       (:store (s/ok store r/add-field {:element eid
                                                     :field   {:name "searchTerm"
                                                               :type :string}}))]
    [store mid eid]))

(defn- wireframe-violation
  "The sole invariant violation `store` reports, or nil when it is valid."
  [store]
  (first (inv/check store)))

(deftest a-stored-wireframe-must-be-well-formed
  (testing "a malformed stored tree makes every later mutation fail"
    (let [[store mid eid] (screen-with-field)
          ;; bypass the rules: install the tree directly, as a hand-edited or
          ;; older file would. A leaf tag carrying a child is malformed, and the
          ;; child's dangling field-name is only reported when the tree itself
          ;; is sound.
          broken (m/set-field store :element eid :wireframe
                              [:canvas {:-id "n1"}
                               [:divider {:-id "n2"}
                                [:input {:-id "n3"} {:field-name "noSuchField"}]]])
          violation (wireframe-violation broken)]
      (is (= :WireframeWellFormed (:invariant violation)))
      (is (= eid (:element violation)))
      (is (re-find #"leaf" (:message violation)))
      (is (= 1 (count (inv/check broken)))
          "one broken tree is reported once, not once per symptom")
      (let [res (r/create-timeline broken {:model mid :title "T"})]
        (is (r/error? res))
        (is (= :invariant-violation (:error res))))))
  (testing "an unknown tag in the stored tree is a well-formedness violation"
    (let [[store mid eid] (screen-with-field)
          broken          (m/set-field store :element eid :wireframe
                                       [:canvas {:-id "n1"} [:foobar {:-id "n2"}]])]
      (is (= :WireframeWellFormed (:invariant (wireframe-violation broken))))
      (is (= :invariant-violation
             (:error (r/create-timeline broken {:model mid :title "T"}))))))
  (testing "a non-screen carrying a wireframe is a well-formedness violation"
    (let [[store mid] (s/with-model)
          [store cmd] (element store mid "PlaceOrder" :command)
          broken      (m/set-field store :element cmd :wireframe [:canvas {:-id "n1"}])]
      (is (= :WireframeWellFormed (:invariant (wireframe-violation broken))))
      (is (= :invariant-violation
             (:error (r/create-timeline broken {:model mid :title "T"}))))))
  (testing "a sound stored tree naming a declared field keeps the store editable"
    (let [[store mid eid] (screen-with-field)
          sound           (m/set-field store :element eid :wireframe
                                       [:canvas {:-id "n1"}
                                        [:input {:-id "n2"} {:field-name "searchTerm"}]])]
      (is (empty? (inv/check sound)))
      (is (not (r/error? (r/create-timeline sound {:model mid :title "T"})))))))

(deftest a-stored-wireframe-must-resolve-its-field-names
  (testing "a stored tree naming a field the screen does not have fails every mutation"
    (let [[store mid eid] (screen-with-field)
          ;; the tree is structurally sound, so the reference is what is wrong.
          dangling (m/set-field store :element eid :wireframe
                                [:canvas {:-id "n1"}
                                 [:input {:-id "n2"} {:field-name "noSuchField"}]])
          violation (wireframe-violation dangling)]
      (is (= :WireframeReferencesResolve (:invariant violation)))
      (is (= eid (:element violation)))
      (is (re-find #"noSuchField" (:message violation)))
      (let [res (r/create-timeline dangling {:model mid :title "T"})]
        (is (r/error? res))
        (is (= :invariant-violation (:error res))))))
  (testing "a reference that resolves is not a violation"
    (let [[store _ eid] (screen-with-field)
          resolved      (m/set-field store :element eid :wireframe
                                     [:canvas {:-id "n1"}
                                      [:input {:-id "n2"} {:field-name "searchTerm"}]])]
      (is (not (some #(= :WireframeReferencesResolve (:invariant %))
                     (inv/check resolved)))))))

;; --- ValidConnectionElementTypes --------------------------------------------------

(deftest connections-follow-event-modeling-patterns
  (let [[store mid] (s/with-model)
        [store scr] (element store mid "OrderScreen" :screen)
        [store cmd] (element store mid "PlaceOrder" :command)
        [store evt] (element store mid "OrderPlaced" :event)]
    (testing "screen -> command is valid"
      (is (not (r/error? (r/connect store {:from scr :to cmd})))))
    (testing "command -> event is valid"
      (is (not (r/error? (r/connect store {:from cmd :to evt})))))
    (testing "event -> command is invalid"
      (is (r/error? (r/connect store {:from evt :to cmd}))))
    (testing "screen -> event is invalid"
      (is (r/error? (r/connect store {:from scr :to evt}))))))

;; --- SpecificationComposition ---------------------------------------------

(deftest spec-steps-must-match-pattern
  (let [[store mid slid] (slice-of :state_change)
        [store cmd]      (element store mid "PlaceOrder" :command)
        [store evt]      (element store mid "OrderPlaced" :event)
        store            (:store (s/ok store r/add-specification {:slice slid :title "spec"}))
        spid             (:id (first (m/specs store slid)))]
    (testing "given step referencing a command is rejected (given steps are events)"
      (is (r/error? (r/add-spec-step store {:spec spid :clause :given_step :element cmd :index 0}))))
    (testing "given step referencing an event is accepted"
      (is (not (r/error? (r/add-spec-step store {:spec spid :clause :given_step :element evt :index 0})))))
    (testing "when step referencing a command is accepted"
      (is (not (r/error? (r/add-spec-step store {:spec spid :clause :when_step :element cmd :index 0})))))
    (testing "error step is accepted in a state_change spec"
      (is (not (r/error? (r/add-error-step store {:spec spid :error-name "Rejected" :index 0})))))))

(deftest state-view-spec-has-no-when
  (let [[store mid slid] (slice-of :state_view)
        [store cmd]      (element store mid "PlaceOrder" :command)
        store            (:store (s/ok store r/add-specification {:slice slid :title "spec"}))
        spid             (:id (first (m/specs store slid)))]
    (testing "a when step is rejected for a state_view spec"
      (is (r/error? (r/add-spec-step store {:spec spid :clause :when_step :element cmd :index 0}))))))
