(ns emcli.rules-test
  "rule_success, rule_entity_creation, transition_edge, transition_rejected and
  cascade obligations from event-model.allium."
  (:require [clojure.test :refer [deftest testing is]]
            [emcli.model :as m]
            [emcli.rules :as r]
            [emcli.support :as s]))

;; --- rule_entity_creation --------------------------------------------------

(deftest create-rules-produce-entities-and-deltas
  (let [[store mid] (s/with-model)]
    (doseq [[rule args type] [[r/create-timeline {:model mid :title "T"} :timeline]
                              [r/create-swimlane {:model mid :name "L" :index 0} :swimlane]
                              [r/create-element  {:model mid :name "E" :kind :command} :element]]]
      (let [{:keys [store delta result]} (s/ok store rule args)]
        (is (= type (:type result)))
        (is (m/exists? store type (:id result)))
        (is (= 1 (count (:changes delta))))
        (is (= :created (:action (first (:changes delta)))))))))

(deftest subscribe-creates-subscription
  (let [[store mid] (s/with-model)
        sub         (:result (s/ok store r/subscribe {:model mid}))]
    (is (= :subscription (:type sub)))
    (is (= mid (:model sub)))))

;; --- rule_success: updates -------------------------------------------------

(deftest rename-and-set-rules
  (let [[store mid] (s/with-model)
        tl          (:result (s/ok store r/create-timeline {:model mid :title "Old"}))
        store       (:store (s/ok store r/create-timeline {:model mid :title "Old"}))
        tlid        (:id (first (m/timelines store mid)))]
    (testing "RenameTimeline"
      (let [store (:store (s/ok store r/rename-timeline {:timeline tlid :new-title "New"}))]
        (is (= "New" (:title (m/fetch store :timeline tlid))))))
    (testing "SetElementContext / SetImageUrl / RenameElement / SetFields"
      (let [store (:store (s/ok store r/create-element {:model mid :name "Order" :kind :screen}))
            eid   (:id (first (m/elements store mid)))
            store (:store (s/ok store r/set-element-context {:element eid :new-context :external}))
            store (:store (s/ok store r/set-image-url {:element eid :url "http://x/y.png"}))
            store (:store (s/ok store r/rename-element {:element eid :new-name "OrderScreen"}))
            store (:store (s/ok store r/set-fields {:element eid
                                                    :fields [{:name "id" :type :uuid
                                                              :optional false :cardinality :single
                                                              :subfields []}]}))
            el    (m/fetch store :element eid)]
        (is (= :external (:context el)))
        (is (= "http://x/y.png" (:image_url el)))
        (is (= "OrderScreen" (:name el)))
        (is (= 1 (count (:fields el))))))))

(deftest swimlane-ordering
  (testing "swimlanes list by (index, id); ReorderSwimlane changes the order"
    (let [[store mid] (s/with-model)
          store (:store (s/ok store r/create-swimlane {:model mid :name "A" :index 0}))
          store (:store (s/ok store r/create-swimlane {:model mid :name "B" :index 1}))
          store (:store (s/ok store r/create-swimlane {:model mid :name "C" :index 2}))]
      (is (= ["A" "B" "C"] (map :name (m/swimlanes store mid))))
      (testing "create stores the explicit index"
        (is (= [0 1 2] (map :index (m/swimlanes store mid)))))
      (testing "reorder moves C to the front"
        (let [cid   (:id (first (filter #(= "C" (:name %)) (m/swimlanes store mid))))
              store (:store (s/ok store r/reorder-swimlane {:lane cid :new-index -1}))]
          (is (= ["C" "A" "B"] (map :name (m/swimlanes store mid))))))
      (testing "ties broken by creation order (id)"
        (let [store (:store (s/ok store r/create-swimlane {:model mid :name "D" :index 0}))]
          ;; A and D both index 0 -> A first (created earlier)
          (is (= ["A" "D"] (->> (m/swimlanes store mid)
                                (filter #(zero? (:index %))) (map :name)))))))))

(deftest assign-and-delete-swimlane-cascades
  (testing "DeleteSwimlane unassigns it from elements, then removes it"
    (let [[store mid] (s/with-model)
          store       (:store (s/ok store r/create-swimlane {:model mid :name "Orders" :index 0}))
          lane        (:id (first (m/swimlanes store mid)))
          store       (:store (s/ok store r/create-element {:model mid :name "Order" :kind :command}))
          eid         (:id (first (m/elements store mid)))
          store       (:store (s/ok store r/assign-swimlane {:element eid :lane lane}))]
      (is (= lane (:swimlane (m/fetch store :element eid))))
      (let [store (:store (s/ok store r/delete-swimlane {:lane lane}))]
        (is (not (m/exists? store :swimlane lane)))
        (is (nil? (:swimlane (m/fetch store :element eid))))))))

;; --- transition_edge / transition_rejected --------------------------------

(deftest slice-status-transitions
  (let [[store mid] (s/with-model)
        store       (:store (s/ok store r/create-timeline {:model mid :title "T"}))
        tlid        (:id (first (m/timelines store mid)))
        store       (:store (s/ok store r/add-slice {:timeline tlid :title "S" :kind :state_change :index 0}))
        slid        (:id (first (m/slices store tlid)))]
    (testing "every declared edge is accepted"
      (doseq [[from to] r/slice-transitions]
        (let [store (m/set-field store :slice slid :status from)
              res   (r/set-slice-status store {:slice slid :new-status to})]
          (is (not (r/error? res)) (str from " -> " to " should be allowed"))
          (is (= to (:status (m/fetch (:store res) :slice slid)))))))
    (testing "an undeclared edge is rejected"
      (let [store (m/set-field store :slice slid :status :created)
            res   (r/set-slice-status store {:slice slid :new-status :done})]
        (is (r/error? res))
        (is (= :illegal-transition (:error res)))))))

;; --- cascades --------------------------------------------------------------

(defn- full-timeline []
  (let [[store mid] (s/with-model)
        store       (:store (s/ok store r/create-timeline {:model mid :title "T"}))
        tlid        (:id (first (m/timelines store mid)))
        store       (:store (s/ok store r/add-slice {:timeline tlid :title "S" :kind :state_change :index 0}))
        slid        (:id (first (m/slices store tlid)))
        store       (:store (s/ok store r/create-element {:model mid :name "PlaceOrder" :kind :command}))
        eid         (:id (first (m/elements store mid)))
        store       (:store (s/ok store r/place-element {:slice slid :element eid}))
        pid         (:id (first (m/placements store slid)))
        store       (:store (s/ok store r/add-specification {:slice slid :title "spec"}))
        spid        (:id (first (m/specs store slid)))
        store       (:store (s/ok store r/add-spec-step {:spec spid :clause :when_step :element eid :index 0}))
        stid        (:id (first (m/spec-steps store spid)))]
    {:store store :mid mid :tlid tlid :slid slid :eid eid :pid pid :spid spid :stid stid}))

(deftest set-step-examples-enforces-well-formed-examples
  (let [{:keys [store stid]} (full-timeline)]
    (testing "well-formed examples are accepted"
      (let [res (r/set-step-examples store {:step stid :examples [{:field_name "id" :field_value "42"}]})]
        (is (not (r/error? res)))
        (is (= [{:field_name "id" :field_value "42"}] (:examples (m/fetch (:store res) :spec-step stid))))))
    (testing "wrong keys (e.g. field/value) are rejected, not silently stored empty"
      (let [res (r/set-step-examples store {:step stid :examples [{:field "id" :value "42"}]})]
        (is (r/error? res))
        (is (= :invariant-violation (:error res)))))
    (testing "blank field_name/field_value are rejected"
      (let [res (r/set-step-examples store {:step stid :examples [{:field_name "" :field_value "42"}]})]
        (is (r/error? res))
        (is (= :invariant-violation (:error res)))))))

(deftest delete-timeline-cascades-to-slices-specs-placements
  (let [{:keys [store tlid slid pid spid stid eid]} (full-timeline)
        store (:store (s/ok store r/delete-timeline {:timeline tlid}))]
    (is (not (m/exists? store :timeline tlid)))
    (is (not (m/exists? store :slice slid)))
    (is (not (m/exists? store :placement pid)))
    (is (not (m/exists? store :specification spid)))
    (is (not (m/exists? store :spec-step stid)))
    (testing "the single-source element survives"
      (is (m/exists? store :element eid)))))

(deftest delete-element-cascades-to-placements-and-connections
  (let [[store mid] (s/with-model)
        store       (:store (s/ok store r/create-timeline {:model mid :title "T"}))
        tlid        (:id (first (m/timelines store mid)))
        store       (:store (s/ok store r/add-slice {:timeline tlid :title "S" :kind :state_change :index 0}))
        slid        (:id (first (m/slices store tlid)))
        store       (:store (s/ok store r/create-element {:model mid :name "PlaceOrder" :kind :command}))
        cmd         (:id (first (m/elements store mid)))
        store       (:store (s/ok store r/create-element {:model mid :name "OrderPlaced" :kind :event}))
        evt         (:id (second (m/elements store mid)))
        store       (:store (s/ok store r/place-element {:slice slid :element cmd}))
        pid         (:id (first (m/placements store slid)))
        store       (:store (s/ok store r/connect {:from cmd :to evt}))
        cid         (:id (first (m/connections store mid)))
        store       (:store (s/ok store r/delete-element {:element cmd}))]
    (is (not (m/exists? store :element cmd)))
    (is (not (m/exists? store :placement pid)))
    (is (not (m/exists? store :connection cid)))
    (is (m/exists? store :element evt))))

(deftest cascade-emits-single-delta-with-all-changes
  (testing "DeleteTimeline is one surface op -> exactly one delta carrying every change"
    (let [{:keys [store tlid]} (full-timeline)
          {:keys [delta]}      (s/ok store r/delete-timeline {:timeline tlid})]
      (is (= :DeleteTimeline (:op delta)))
      (is (every? #(= :deleted (:action %)) (:changes delta)))
      (is (>= (count (:changes delta)) 4)))))
