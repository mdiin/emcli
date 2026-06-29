(ns emcli.model-test
  "Entity-field, relationship, projection and derived-predicate obligations
  from event-model.allium (entity_fields / entity_relationship / projection /
  derived / entity_optional categories)."
  (:require [clojure.test :refer [deftest testing is]]
            [emcli.model :as m]
            [emcli.rules :as r]
            [emcli.support :as s]))

;; --- entity_fields & rule defaults ----------------------------------------

(deftest event-model-fields
  (let [[store mid] (s/with-model "Orders")
        model       (m/fetch store :event-model mid)]
    (is (= "Orders" (:name model)))
    (is (= :event-model (:type model)))))

(deftest slice-default-fields
  (testing "AddSlice creates a slice with status :created (rule default)"
    (let [[store mid] (s/with-model)
          store       (:store (s/ok store r/create-timeline {:model mid :title "T"}))
          tlid        (:id (first (m/timelines store mid)))
          slice       (:result (s/ok store r/add-slice {:timeline tlid :title "S"
                                                        :kind :state_change :index 0}))]
      (is (= :state_change (:kind slice)))
      (is (= :created (:status slice)))
      (is (= 0 (:index slice)))
      (is (= tlid (:timeline slice))))))

(deftest element-default-context
  (testing "CreateElement defaults context to :internal and fields to []"
    (let [[store mid] (s/with-model)
          el          (:result (s/ok store r/create-element {:model mid :name "Order" :kind :command}))]
      (is (= :internal (:context el)))
      (is (= [] (:fields el)))
      (is (= :command (:kind el))))))

;; --- entity_optional -------------------------------------------------------

(deftest element-optionals-absent-by-default
  (let [[store mid] (s/with-model)
        el          (:result (s/ok store r/create-element {:model mid :name "Order" :kind :screen}))]
    (is (nil? (:swimlane el)))
    (is (nil? (:image_url el)))))

(deftest spec-step-optionals
  (testing "error step has no element; normal step has no error_name"
    (let [[store mid] (s/with-model)
          store       (:store (s/ok store r/create-timeline {:model mid :title "T"}))
          tlid        (:id (first (m/timelines store mid)))
          store       (:store (s/ok store r/add-slice {:timeline tlid :title "S" :kind :state_change :index 0}))
          slid        (:id (first (m/slices store tlid)))
          store       (:store (s/ok store r/add-specification {:slice slid :title "spec"}))
          spid        (:id (first (m/specs store slid)))
          estep       (:result (s/ok store r/add-error-step {:spec spid :error-name "Boom" :index 0}))]
      (is (true? (:is_error estep)))
      (is (nil? (:element estep)))
      (is (= "Boom" (:error_name estep))))))

;; --- relationships ---------------------------------------------------------

(deftest reverse-relationships
  (let [[store mid] (s/with-model)
        store       (:store (s/ok store r/create-timeline {:model mid :title "A"}))
        store       (:store (s/ok store r/create-timeline {:model mid :title "B"}))
        store       (:store (s/ok store r/create-swimlane {:model mid :name "Lane"}))]
    (is (= 2 (count (m/timelines store mid))))
    (is (= 1 (count (m/swimlanes store mid))))
    (is (= ["A" "B"] (map :title (m/timelines store mid))))))

;; --- projections (Slice.commands etc.) -------------------------------------

(defn- seed-slice [kind]
  (let [[store mid] (s/with-model)
        store       (:store (s/ok store r/create-timeline {:model mid :title "T"}))
        tlid        (:id (first (m/timelines store mid)))
        store       (:store (s/ok store r/add-slice {:timeline tlid :title "S" :kind kind :index 0}))
        slid        (:id (first (m/slices store tlid)))]
    [store mid slid]))

(defn- place [store mid slid name kind]
  (let [store (:store (s/ok store r/create-element {:model mid :name name :kind kind}))
        eid   (:id (last (m/elements store mid)))]
    [(:store (s/ok store r/place-element {:slice slid :element eid})) eid]))

(deftest slice-projections-group-by-element-kind
  (let [[store mid slid] (seed-slice :state_change)
        [store _]        (place store mid slid "PlaceOrder" :command)
        [store _]        (place store mid slid "OrderPlaced" :event)]
    (is (= 1 (count (m/slice-commands store slid))))
    (is (= 1 (count (m/slice-events store slid))))
    (is (= 0 (count (m/slice-read-models store slid))))))

;; --- derived: Slice.is_complete -------------------------------------------

(deftest slice-is-complete
  (testing "state_change slice complete iff exactly one command"
    (let [[store mid slid] (seed-slice :state_change)]
      (is (false? (m/slice-complete? store (m/fetch store :slice slid))))
      (let [[store _] (place store mid slid "PlaceOrder" :command)]
        (is (true? (m/slice-complete? store (m/fetch store :slice slid)))))))
  (testing "state_view slice complete iff exactly one read_model"
    (let [[store mid slid] (seed-slice :state_view)
          [store _]        (place store mid slid "OrderList" :read_model)]
      (is (true? (m/slice-complete? store (m/fetch store :slice slid)))))))

;; --- derived: Specification.is_complete ------------------------------------

(deftest spec-is-complete
  (let [[store mid slid] (seed-slice :state_change)
        store            (:store (s/ok store r/create-element {:model mid :name "PlaceOrder" :kind :command}))
        cmd              (:id (last (m/elements store mid)))
        store            (:store (s/ok store r/add-specification {:slice slid :title "spec"}))
        spid             (:id (first (m/specs store slid)))]
    (is (false? (m/spec-complete? store (m/fetch store :specification spid))))
    (let [store (:store (s/ok store r/add-spec-step {:spec spid :clause :when_step :element cmd :index 0}))]
      (is (true? (m/spec-complete? store (m/fetch store :specification spid)))))))
