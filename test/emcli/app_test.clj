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
          sl  (:result (cmd/run a "add-slice" {:timeline (:id tl) :title "Place" :slice-type "state_change"}))
          cmd' (:result (cmd/run a "create-element" {:name "PlaceOrder" :element-type "command"}))
          evt (:result (cmd/run a "create-element" {:name "OrderPlaced" :element-type "event"}))
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
      (is (= {:id (:id cmd') :name "PlaceOrder" :element_type :command :swimlane nil :is_information_complete true
              :image_url nil :wireframe nil :fields []}
             (:element p1)))
      (is (= (:id cn) (:id c1)))
      (is (= {:id (:id cmd') :name "PlaceOrder"} (:from c1)))
      (is (= {:id (:id evt) :name "OrderPlaced"} (:to c1))))))

(deftest snapshot-carries-image-url-and-wireframe
  (testing "image_url and wireframe are present on element in snapshot"
    (let [a    (app/new-app "Orders")
          tl   (:result (cmd/run a "create-timeline" {:title "Ordering"}))
          sl   (:result (cmd/run a "add-slice" {:timeline (:id tl) :title "Place" :slice-type "state_change"}))
          scr  (:result (cmd/run a "create-element" {:name "OrderScreen" :element-type "screen"}))
          _    (cmd/run a "set-image-url" {:element (:id scr) :url "https://example.com/img.png"})
          _    (cmd/run a "add-wireframe-node" {:element (:id scr) :tag "row"})
          _    (cmd/run a "place-element" {:slice (:id sl) :element (:id scr)})
          [_ msgs] (recording-sub a)
          model (:model (first @msgs))
          p1    (first (:placements (first (:slices (first (:timelines model))))))]
      (is (= "https://example.com/img.png" (:image_url (:element p1))))
      (is (some? (:wireframe (:element p1)))))))

(deftest snapshot-carries-placed-element-fields
  (testing "an element's fields are streamed flat under its placement (subfields dropped)"
    (let [a    (app/new-app "Orders")
          tl   (:result (cmd/run a "create-timeline" {:title "Ordering"}))
          sl   (:result (cmd/run a "add-slice" {:timeline (:id tl) :title "Place" :slice-type "state_change"}))
          cmd' (:result (cmd/run a "create-element" {:name "PlaceOrder" :element-type "command"}))
          _    (cmd/run a "add-field" {:element (:id cmd') :name "id" :type "uuid" :cardinality "single"})
          _    (cmd/run a "place-element" {:slice (:id sl) :element (:id cmd')})
          [_ msgs] (recording-sub a)
          model (:model (first @msgs))
          p1    (first (:placements (first (:slices (first (:timelines model))))))]
      (is (= [{:name "id" :type :uuid :optional false :cardinality :single}]
             (:fields (:element p1)))))))

(deftest snapshot-carries-the-whole-element-registry
  (testing "the snapshot carries every element of the model, each in the canonical
            record shape a delta uses -- including an element no placement or
            connection reaches, which is the one a later PlaceElement delta names
            by id alone"
    (let [a        (app/new-app "Orders")
          tl       (:result (cmd/run a "create-timeline" {:title "Ordering"}))
          sl       (:result (cmd/run a "add-slice" {:timeline (:id tl) :title "Place"
                                                    :slice-type "state_change" :index 0}))
          placed   (:result (cmd/run a "create-element" {:name "PlaceOrder" :element-type "command"}))
          _        (cmd/run a "add-field" {:element (:id placed) :name "id" :type "uuid"})
          unplaced (:result (cmd/run a "create-element" {:name "OrderCancelled" :element-type "event"}))
          _        (cmd/run a "place-element" {:slice (:id sl) :element (:id placed)})
          [_ msgs] (recording-sub a)
          model    (:model (first @msgs))
          elements (into {} (map (juxt :id identity)) (:elements model))
          nested   (-> model :timelines first :slices first :placements first :element)]
      (is (= #{(:id placed) (:id unplaced)} (set (keys elements)))
          "placed and unplaced alike: the registry is the model's, not the projection's")
      (is (= {:id (:id unplaced) :type :element :model (app/model-id a)
              :name "OrderCancelled" :element_type :event :context :internal
              :fields [] :field_origins [] :is_information_complete true}
             (elements (:id unplaced)))
          "a fresh element is complete, and that derived verdict is on the snapshot record")
      (is (= #{:id :type :model :name :element_type :context :fields :field_origins :is_information_complete}
             (set (keys (elements (:id placed)))))
          "the full canonical element shape, not the reduced display projection")
      (is (false? (:is_information_complete (elements (:id placed))))
          "its `id` field is introduced nowhere and carried by no incoming connection")
      (is (nil? (:context nested))
          "the placement's nested element stays the reduced display projection it was"))))

(deftest snapshot-carries-specifications-with-examples
  (testing "a slice's specifications, steps and their examples are streamed in the snapshot"
    (let [a    (app/new-app "Orders")
          tl   (:result (cmd/run a "create-timeline" {:title "Ordering"}))
          sl   (:result (cmd/run a "add-slice" {:timeline (:id tl) :title "Place" :slice-type "state_change"}))
          el   (:result (cmd/run a "create-element" {:name "PlaceOrder" :element-type "command"}))
          sp   (:result (cmd/run a "add-specification" {:slice (:id sl) :title "Happy path"}))
          st   (:result (cmd/run a "add-spec-step" {:spec (:id sp) :clause :when_step :element (:id el) :index 0}))
          _    (cmd/run a "add-step-example" {:step (:id st) :field-name "id" :field-value "1"})
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
          sl   (:result (cmd/run a "add-slice" {:timeline (:id tl) :title "Place" :slice-type "state_change"}))
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
          _  (cmd/run a "add-slice" {:timeline (:id tl) :title "S" :slice-type "state_change"})
          [_ msgs] (recording-sub a)]
      (cmd/run a "delete-timeline" {:timeline (:id tl)})
      (is (= 2 (count @msgs)))                        ; snapshot + 1 delta
      (is (= :DeleteTimeline (:op (last @msgs))))
      (is (> (count (:changes (last @msgs))) 1)))))   ; carrying multiple changes

(deftest rejected-mutation-emits-no-delta
  (testing "a rejected mutation changes nothing and broadcasts nothing"
    (let [a (app/new-app "Orders")
          [_ msgs] (recording-sub a)
          res (cmd/run a "set-slice-status" {:slice 999999 :new-status "done"})]
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

(deftest element-delta-includes-is-information-complete
  (testing "SetFields delta carries is_information_complete on the element entity"
    (let [a   (app/new-app "Orders")
          el  (:result (cmd/run a "create-element" {:name "PlaceOrder" :element-type "command"}))
          [_ msgs] (recording-sub a)]
      (cmd/run a "add-field" {:element (:id el) :name "id" :type "uuid"})
      (let [delta  (last @msgs)
            change (first (:changes delta))
            entity (:entity change)]
        (is (= :SetFields (:op delta)))
        (is (= :element (:type change)))
        (is (contains? entity :is_information_complete))
        (is (false? (:is_information_complete entity))))))
  (testing "SetFieldOrigins delta carries updated is_information_complete = true"
    (let [a   (app/new-app "Orders")
          el  (:result (cmd/run a "create-element" {:name "PlaceOrder" :element-type "command"}))
          _   (cmd/run a "add-field" {:element (:id el) :name "id" :type "uuid"})
          [_ msgs] (recording-sub a)]
      (cmd/run a "add-field-origin" {:element (:id el) :field "id" :origin "user_input"})
      (let [delta  (last @msgs)
            entity (:entity (first (:changes delta)))]
        (is (= :SetFieldOrigins (:op delta)))
        (is (contains? entity :is_information_complete))
        (is (true? (:is_information_complete entity)))))))

(deftest create-element-delta-includes-is-information-complete
  (testing "CreateElement delta carries is_information_complete on the element entity"
    (let [a   (app/new-app "Orders")
          [_ msgs] (recording-sub a)]
      (cmd/run a "create-element" {:name "PlaceOrder" :element-type "command"})
      (let [delta  (last @msgs)
            change (first (:changes delta))
            entity (:entity change)]
        (is (= :CreateElement (:op delta)))
        (is (= :element (:type change)))
        (is (contains? entity :is_information_complete))
        (is (true? (:is_information_complete entity))
            "a fresh element declares no fields, so it is complete")))))

(deftest connection-delta-includes-target-element-change
  (testing "SetConnectionDerivations delta includes an updated change for the target element"
    (let [a    (app/new-app "Orders")
          from (:result (cmd/run a "create-element" {:name "OrderPlaced" :element-type "event"}))
          to   (:result (cmd/run a "create-element" {:name "Summary"     :element-type "read_model"}))
          _    (cmd/run a "add-field" {:element (:id from) :name "amount" :type "decimal"})
          _    (cmd/run a "add-field" {:element (:id to)   :name "total"  :type "decimal"})
          _    (cmd/run a "connect"   {:from (:id from) :to (:id to)})
          cid  (:id (first (m/connections (app/store a) (app/model-id a))))
          [_ msgs] (recording-sub a)]
      (cmd/run a "add-derivation" {:connection cid :target "total" :from "amount"})
      (let [delta   (last @msgs)
            changes (:changes delta)
            el-chg  (first (filter #(= :element (:type %)) changes))]
        (is (= :SetConnectionDerivations (:op delta)))
        (is (some? el-chg) "delta must include an element change for the target element")
        (is (= (:id to) (:id el-chg)))
        (is (true? (:is_information_complete (:entity el-chg)))))))
  (testing "connect delta includes updated change for the target element"
    (let [a    (app/new-app "Orders")
          from (:result (cmd/run a "create-element" {:name "PlaceOrder" :element-type "command"}))
          to   (:result (cmd/run a "create-element" {:name "OrderPlaced" :element-type "event"}))
          _    (cmd/run a "add-field" {:element (:id from) :name "id" :type "uuid"})
          _    (cmd/run a "add-field" {:element (:id to)   :name "id" :type "uuid"})
          [_ msgs] (recording-sub a)]
      (cmd/run a "connect" {:from (:id from) :to (:id to)})
      (let [delta   (last @msgs)
            changes (:changes delta)
            el-chg  (first (filter #(= :element (:type %)) changes))]
        (is (= :Connect (:op delta)))
        (is (some? el-chg) "delta must include an element change for the target element")
        (is (= (:id to) (:id el-chg)))
        (is (true? (:is_information_complete (:entity el-chg)))))))
  (testing "disconnect delta includes updated change for the target element"
    (let [a    (app/new-app "Orders")
          from (:result (cmd/run a "create-element" {:name "PlaceOrder" :element-type "command"}))
          to   (:result (cmd/run a "create-element" {:name "OrderPlaced" :element-type "event"}))
          _    (cmd/run a "add-field" {:element (:id from) :name "id" :type "uuid"})
          _    (cmd/run a "add-field" {:element (:id to)   :name "id" :type "uuid"})
          _    (cmd/run a "connect" {:from (:id from) :to (:id to)})
          cid  (:id (first (m/connections (app/store a) (app/model-id a))))
          [_ msgs] (recording-sub a)]
      (cmd/run a "disconnect" {:connection cid})
      (let [delta   (last @msgs)
            changes (:changes delta)
            el-chg  (first (filter #(= :element (:type %)) changes))]
        (is (= :Disconnect (:op delta)))
        (is (some? el-chg) "delta must include element change for the target element")
        (is (= (:id to) (:id el-chg)))
        (is (false? (:is_information_complete (:entity el-chg))))))))

(deftest delete-element-cascade-restates-surviving-target
  (testing "DeleteElement restates each surviving element whose completeness moved"
    (let [a    (app/new-app "Orders")
          from (:result (cmd/run a "create-element" {:name "PlaceOrder" :element-type "command"}))
          to   (:result (cmd/run a "create-element" {:name "OrderPlaced" :element-type "event"}))
          _    (cmd/run a "add-field" {:element (:id from) :name "id" :type "uuid"})
          _    (cmd/run a "add-field" {:element (:id to)   :name "id" :type "uuid"})
          _    (cmd/run a "connect"   {:from (:id from) :to (:id to)})
          [_ msgs] (recording-sub a)]
      (is (true? (m/information-complete? (app/store a)
                                          (m/fetch (app/store a) :element (:id to))))
          "the event is complete while fed by the command")
      (cmd/run a "delete-element" {:element (:id from)})
      (let [delta   (last @msgs)
            changes (:changes delta)
            el-chg  (first (filter #(and (= :element (:type %))
                                         (= :updated (:action %))) changes))]
        (is (= :DeleteElement (:op delta)))
        (is (some #(and (= :deleted (:action %)) (= :connection (:type %))) changes)
            "delta must include the removed connection")
        (is (some #(and (= :deleted (:action %)) (= :element (:type %))) changes)
            "delta must include the removed element")
        (is (some? el-chg) "delta must restate the surviving target element")
        (is (= (:id to) (:id el-chg)))
        (is (false? (:is_information_complete (:entity el-chg)))
            "removing the feeding connection moved the event's completeness")
        (is (false? (m/information-complete? (app/store a)
                                             (m/fetch (app/store a) :element (:id to))))
            "the restated value reflects the post-removal store")))))
