(ns emcli.app-test
  "ChangeStream contract obligations exercised at the app level (no sockets):
  SnapshotThenDeltas, DeltaPerMutation, CanonicalShape, and the Subscribe/
  Unsubscribe lifecycle of ModelChangeStream."
  (:require [clojure.test :refer [deftest testing is]]
            [emcli.app :as app]
            [emcli.commands :as cmd]
            [emcli.model :as m]
            [emcli.rules :as r]))

(defn- recording-sub
  "Subscribe with a recorder; returns [sub-id messages-atom]."
  [a]
  (let [msgs (atom [])
        sid  (app/subscribe! a #(swap! msgs conj %))]
    [sid msgs]))

(deftest snapshot-is-first-message
  (testing "SnapshotThenDeltas: the first message a client receives is one snapshot"
    (let [a (app/new-app "Orders")
          [_ msgs] (recording-sub a)]
      (is (= 1 (count @msgs)))
      (is (= :snapshot (:op (first @msgs))))
      (is (contains? (:model (first @msgs)) :timelines)))))

(deftest snapshot-reflects-state-at-connect
  (testing "the snapshot carries the model as it is at connect time"
    (let [a (app/new-app "Orders")]
      (cmd/run a "create-timeline" {:title "Ordering"})
      (let [[_ msgs] (recording-sub a)
            snap     (first @msgs)]
        (is (= ["Ordering"] (map :title (get-in snap [:model :timelines]))))))))

(deftest snapshot-carries-entity-ids-matching-the-store
  (testing "every snapshot entity exposes its integer id, correlatable with deltas"
    (let [a   (app/new-app "Orders")
          tl  (:result (cmd/run a "create-timeline" {:title "Ordering"}))
          sw  (:result (cmd/run a "create-swimlane" {:name "Lane" :index 0}))
          sl  (:result (cmd/run a "add-slice" {:timeline (:id tl) :title "Place" :kind "state_change" :index 0}))
          cmd' (:result (cmd/run a "create-element" {:name "PlaceOrder" :kind "command"}))
          evt (:result (cmd/run a "create-element" {:name "OrderPlaced" :kind "event"}))
          pl  (:result (cmd/run a "place-element" {:slice (:id sl) :element (:id cmd')}))
          cn  (:result (cmd/run a "connect" {:from (:id cmd') :to (:id evt)}))
          [_ msgs] (recording-sub a)
          model (:model (first @msgs))
          t1    (first (:timelines model))
          s1    (first (:slices t1))
          p1    (first (:placements s1))
          c1    (first (:connections model))]
      (is (= (app/model-id a) (:id model)))
      (is (= (:id tl) (:id t1)))
      (is (= (:id sl) (:id s1)))
      (is (= (:id sw) (:id (first (:swimlanes model)))))
      (is (true? (:is_complete s1)) "state_change slice with one placed command is complete")
      (is (= (:id pl) (:id p1)))
      (is (= {:id (:id cmd') :name "PlaceOrder" :kind :command :swimlane nil :is_information_complete true :fields []}
             (:element p1)))
      (is (= (:id cn) (:id c1)))
      (is (= {:id (:id cmd') :name "PlaceOrder"} (:from c1)))
      (is (= {:id (:id evt) :name "OrderPlaced"} (:to c1))))))

(deftest snapshot-carries-placed-element-fields
  (testing "an element's fields are streamed flat under its placement (subfields dropped)"
    (let [a    (app/new-app "Orders")
          tl   (:result (cmd/run a "create-timeline" {:title "Ordering"}))
          sl   (:result (cmd/run a "add-slice" {:timeline (:id tl) :title "Place" :kind "state_change" :index 0}))
          cmd' (:result (cmd/run a "create-element" {:name "PlaceOrder" :kind "command"}))
          _    (cmd/run a "set-fields" {:element (:id cmd')
                                        :fields [{:name "id" :type :uuid :optional false
                                                  :cardinality :single
                                                  :subfields [{:name "nested" :type :string
                                                               :optional false :cardinality :single
                                                               :subfields []}]}]})
          _    (cmd/run a "place-element" {:slice (:id sl) :element (:id cmd')})
          [_ msgs] (recording-sub a)
          model (:model (first @msgs))
          p1    (first (:placements (first (:slices (first (:timelines model))))))]
      (is (= [{:name "id" :type :uuid :optional false :cardinality :single}]
             (:fields (:element p1)))))))

(deftest snapshot-carries-specifications-with-examples
  (testing "a slice's specifications, steps and their examples are streamed in the snapshot"
    (let [a    (app/new-app "Orders")
          tl   (:result (cmd/run a "create-timeline" {:title "Ordering"}))
          sl   (:result (cmd/run a "add-slice" {:timeline (:id tl) :title "Place" :kind "state_change" :index 0}))
          el   (:result (cmd/run a "create-element" {:name "PlaceOrder" :kind "command"}))
          sp   (:result (cmd/run a "add-specification" {:slice (:id sl) :title "Happy path"}))
          st   (:result (cmd/run a "add-spec-step" {:spec (:id sp) :clause :when_step :element (:id el) :index 0}))
          _    (cmd/run a "set-step-examples" {:step (:id st)
                                                :examples [{:field_name "id" :field_value "1"}]})
          [_ msgs] (recording-sub a)
          model (:model (first @msgs))
          slice (first (:slices (first (:timelines model))))
          spec1 (first (:specifications slice))
          step1 (first (:steps spec1))]
      (is (= (:id sp) (:id spec1)))
      (is (= "Happy path" (:title spec1)))
      (is (true? (:is_complete spec1)))
      (is (= (:id st) (:id step1)))
      (is (= :when_step (:clause step1)))
      (is (= {:id (:id el) :name "PlaceOrder"} (:element step1))
          "the step's element is streamed as a nested {:id :name} ref, like placements and connections")
      (is (= [{:field_name "id" :field_value "1"}] (:examples step1))))))

(deftest snapshot-error-step-has-no-element
  (testing "an error step has no element to reference"
    (let [a    (app/new-app "Orders")
          tl   (:result (cmd/run a "create-timeline" {:title "Ordering"}))
          sl   (:result (cmd/run a "add-slice" {:timeline (:id tl) :title "Place" :kind "state_change" :index 0}))
          sp   (:result (cmd/run a "add-specification" {:slice (:id sl) :title "Rejected"}))
          _    (cmd/run a "add-error-step" {:spec (:id sp) :error-name "AlreadyPlaced" :index 0})
          [_ msgs] (recording-sub a)
          model (:model (first @msgs))
          step1 (-> model :timelines first :slices first :specifications first :steps first)]
      (is (true? (:is_error step1)))
      (is (nil? (:element step1))))))

(deftest delta-per-mutation
  (testing "DeltaPerMutation: each ModelAuthoring mutation yields exactly one delta"
    (let [a (app/new-app "Orders")
          [_ msgs] (recording-sub a)] ; msgs starts with the snapshot
      (cmd/run a "create-timeline" {:title "Ordering"})
      (cmd/run a "create-swimlane" {:name "Lane" :index 0})
      (is (= 3 (count @msgs)))                       ; snapshot + 2 deltas
      (is (= [:snapshot :CreateTimeline :CreateSwimlane] (map :op @msgs))))))

(deftest cascade-is-one-delta
  (testing "a cascading delete is one surface op -> one delta"
    (let [a (app/new-app "Orders")
          tl (:result (cmd/run a "create-timeline" {:title "T"}))
          _  (cmd/run a "add-slice" {:timeline (:id tl) :title "S" :kind "state_change" :index 0})
          [_ msgs] (recording-sub a)]
      (cmd/run a "delete-timeline" {:timeline (:id tl)})
      (is (= 2 (count @msgs)))                        ; snapshot + 1 delta
      (is (= :DeleteTimeline (:op (last @msgs))))
      (is (> (count (:changes (last @msgs))) 1)))))   ; carrying multiple changes

(deftest rejected-mutation-emits-no-delta
  (testing "a rejected mutation changes nothing and broadcasts nothing"
    (let [a (app/new-app "Orders")
          tl (:result (cmd/run a "create-timeline" {:title "T"}))
          _  (cmd/run a "add-slice" {:timeline (:id tl) :title "S" :kind "state_change" :index 0})
          slice-id (:id (first (m/slices (app/store a) (:id tl))))
          [_ msgs] (recording-sub a)
          res (cmd/run a "set-slice-status" {:slice slice-id :new-status "done"})]
      (is (r/error? res))
      (is (= 1 (count @msgs))))))                     ; only the snapshot

(deftest deltas-broadcast-to-all-subscribers-in-order
  (testing "every active subscription receives every delta, in commit order"
    (let [a (app/new-app "Orders")
          [_ m1] (recording-sub a)
          [_ m2] (recording-sub a)]
      (cmd/run a "create-timeline" {:title "A"})
      (cmd/run a "create-timeline" {:title "B"})
      (is (= [:CreateTimeline :CreateTimeline] (map :op (rest @m1))))
      (is (= (map :op @m1) (map :op @m2))))))

(deftest unsubscribe-stops-delivery
  (let [a (app/new-app "Orders")
        [sid msgs] (recording-sub a)]
    (app/unsubscribe! a sid)
    (is (zero? (app/subscriber-count a)))
    (cmd/run a "create-timeline" {:title "A"})
    (is (= 1 (count @msgs)))))                         ; only the snapshot, no delta
