(ns emcli.invariants-test
  "invariant obligations: PlacementMatchesSliceKind, PlacementElementUnique,
  SpecificationComposition, ValidConnectionKinds. Rules must reject any
  mutation that would break one."
  (:require [clojure.test :refer [deftest testing is]]
            [emcli.invariants :as inv]
            [emcli.model :as m]
            [emcli.rules :as r]
            [emcli.support :as s]))

(defn- slice-of [kind]
  (let [[store mid] (s/with-model)
        store       (:store (s/ok store r/create-timeline {:model mid :title "T"}))
        tlid        (:id (first (m/timelines store mid)))
        store       (:store (s/ok store r/add-slice {:timeline tlid :title "S" :kind kind :index 0}))
        slid        (:id (first (m/slices store tlid)))]
    [store mid slid]))

(defn- element [store mid name kind]
  (let [store (:store (s/ok store r/create-element {:model mid :name name :kind kind}))]
    [store (:id (last (m/elements store mid)))]))

;; --- PlacementMatchesSliceKind --------------------------------------------

(deftest placement-must-match-slice-kind
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
                                                            :kind :state_change
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

;; --- ValidConnectionKinds --------------------------------------------------

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
