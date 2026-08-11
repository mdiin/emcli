(ns emcli.rules-test
  "rule_success, rule_entity_creation, transition_edge, transition_rejected and
  cascade obligations from event-model.allium."
  (:require [clojure.test :refer [deftest testing is]]
            [emcli.model :as m]
            [emcli.rules :as r]
            [emcli.support :as s]
            [emcli.wireframe :as wf]))

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

;; --- explicit --id (a scripting affordance: pin an id instead of reading it
;; back out of the create response) --------------------------------------

(deftest explicit-id-is-honored-and-blocks-future-collisions
  (let [[store mid]        (s/with-model)
        {tl :result store :store} (s/ok store r/create-timeline {:model mid :title "T" :id 100})]
    (is (= 100 (:id tl)))
    (testing "the next auto-allocated id is past the explicit one"
      (let [store' (:store (s/ok store r/create-timeline {:model mid :title "T2"}))
            tl2    (first (filter #(= "T2" (:title %)) (m/timelines store' mid)))]
        (is (> (:id tl2) 100))))))

(deftest explicit-id-conflict-is-rejected
  (let [[store mid] (s/with-model)
        store       (:store (s/ok store r/create-timeline {:model mid :title "T" :id 5}))]
    (testing "same id, same type"
      (let [err (s/err store r/create-timeline {:model mid :title "Dup" :id 5})]
        (is (= :id-conflict (:error err)))
        (is (= 5 (:id err)))))
    (testing "same id, different type -- ids are one sequence shared across all types"
      (let [err (s/err store r/create-swimlane {:model mid :name "L" :index 0 :id 5})]
        (is (= :id-conflict (:error err)))))))

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
    (testing "any status transition is accepted, regardless of the prior status"
      (doseq [[from to] [[:created :in_progress]
                         [:in_progress :done]
                         [:done :in_progress]
                         [:in_progress :created]
                         [:created :informational]
                         [:informational :created]
                         [:created :done]]]
        (let [store (m/set-field store :slice slid :status from)
              res   (r/set-slice-status store {:slice slid :new-status to})]
          (is (not (r/error? res)) (str from " -> " to " should be allowed"))
          (is (= to (:status (m/fetch (:store res) :slice slid)))))))))

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

;; ---------------------------------------------------------------------------
;; Wireframe rules
;; ---------------------------------------------------------------------------

(defn- screen-with-field
  "A store with one screen element that has a :searchTerm field."
  []
  (let [[store mid] (s/with-model)
        res         (s/ok store r/create-element {:model mid :name "OrderList" :kind :screen})
        store       (:store res)
        eid         (:id (:result res))
        store       (:store (s/ok store r/add-field {:element eid
                                                      :field {:name "searchTerm" :type :string}}))]
    [store eid]))

(deftest add-wireframe-node-seeds-screen-on-first-call
  (let [[store eid] (screen-with-field)
        res         (s/ok store r/add-wireframe-node {:element eid :tag :col :parent "n1"})
        el          (:result res)]
    (is (= :screen (first (:wireframe el))))
    (is (some? (wf/find-node (:wireframe el) "n1")))
    (is (some? (wf/find-node (:wireframe el) "n2")))))

(deftest add-wireframe-node-appends-nested-node
  (let [[store eid] (screen-with-field)
        store       (:store (s/ok store r/add-wireframe-node {:element eid :tag :col :parent "n1"}))
        res         (s/ok store r/add-wireframe-node {:element eid :tag :h1 :attrs {:text "Hello"} :parent "n2"})
        el          (:result res)]
    (is (some? (wf/find-node (:wireframe el) "n3")))))

(deftest add-wireframe-node-rejects-non-screen-element
  (let [[store mid] (s/with-model)
        res         (s/ok store r/create-element {:model mid :name "PlaceOrder" :kind :command})
        store       (:store res)
        eid         (:id (:result res))
        err         (s/err store r/add-wireframe-node {:element eid :tag :col :parent "n1"})]
    (is (= :invalid-value (:error err)))))

(deftest add-wireframe-node-rejects-unknown-field-name
  (let [[store eid] (screen-with-field)
        err         (s/err store r/add-wireframe-node {:element eid :tag :input
                                                        :attrs {:field-name "nonexistent"} :parent "n1"})]
    (is (= :invalid-wireframe (:error err)))))

(deftest set-wireframe-attr-updates-node
  (let [[store eid] (screen-with-field)
        store       (:store (s/ok store r/add-wireframe-node {:element eid :tag :button
                                                               :attrs {:label "Old"} :parent "n1"}))
        res         (s/ok store r/set-wireframe-attr {:element eid :node "n2" :attr :label :value "New"})
        wf          (:wireframe (:result res))
        node        (wf/find-node wf "n2")
        attrs       (some #(when (and (map? %) (not (contains? % :-id))) %) (rest node))]
    (is (= "New" (:label attrs)))))

(deftest set-wireframe-attr-rejects-missing-wireframe
  (let [[store eid] (screen-with-field)
        err         (s/err store r/set-wireframe-attr {:element eid :node "n2" :attr :label :value "X"})]
    (is (= :not-found (:error err)))))

(deftest set-wireframe-attr-rejects-unknown-node
  (let [[store eid] (screen-with-field)
        store       (:store (s/ok store r/add-wireframe-node {:element eid :tag :col :parent "n1"}))
        err         (s/err store r/set-wireframe-attr {:element eid :node "n99" :attr :label :value "X"})]
    (is (= :not-found (:error err)))))

(deftest set-wireframe-text-sets-string-child
  (let [[store eid] (screen-with-field)
        store       (:store (s/ok store r/add-wireframe-node {:element eid :tag :h1 :parent "n1"}))
        res         (s/ok store r/set-wireframe-text {:element eid :node "n2" :text "Hello"})
        node        (wf/find-node (:wireframe (:result res)) "n2")]
    (is (some #(= "Hello" %) node))))

(deftest set-wireframe-text-replaces-existing-text
  (let [[store eid] (screen-with-field)
        store       (:store (s/ok store r/add-wireframe-node {:element eid :tag :text :parent "n1"}))
        store       (:store (s/ok store r/set-wireframe-text {:element eid :node "n2" :text "First"}))
        res         (s/ok store r/set-wireframe-text {:element eid :node "n2" :text "Second"})
        node        (wf/find-node (:wireframe (:result res)) "n2")]
    (is (some #(= "Second" %) node))
    (is (not (some #(= "First" %) node)))))

(deftest set-wireframe-text-rejects-non-text-tag
  (let [[store eid] (screen-with-field)
        store       (:store (s/ok store r/add-wireframe-node {:element eid :tag :col :parent "n1"}))
        err         (s/err store r/set-wireframe-text {:element eid :node "n2" :text "Hi"})]
    (is (= :invalid-value (:error err)))))

(deftest set-wireframe-text-rejects-missing-wireframe
  (let [[store eid] (screen-with-field)
        err         (s/err store r/set-wireframe-text {:element eid :node "n2" :text "Hi"})]
    (is (= :not-found (:error err)))))

(deftest set-wireframe-text-rejects-unknown-node
  (let [[store eid] (screen-with-field)
        store       (:store (s/ok store r/add-wireframe-node {:element eid :tag :h1 :parent "n1"}))
        err         (s/err store r/set-wireframe-text {:element eid :node "n99" :text "Hi"})]
    (is (= :not-found (:error err)))))

(deftest delete-wireframe-node-removes-leaf
  (let [[store eid] (screen-with-field)
        store       (:store (s/ok store r/add-wireframe-node {:element eid :tag :col :parent "n1"}))
        store       (:store (s/ok store r/add-wireframe-node {:element eid :tag :h1 :parent "n2"}))
        res         (s/ok store r/delete-wireframe-node {:element eid :node "n3"})
        wf          (:wireframe (:result res))]
    (is (nil? (wf/find-node wf "n3")))
    (is (some? (wf/find-node wf "n2")))))

(deftest delete-wireframe-node-n1-clears-wireframe
  (let [[store eid] (screen-with-field)
        store       (:store (s/ok store r/add-wireframe-node {:element eid :tag :col :parent "n1"}))
        res         (s/ok store r/delete-wireframe-node {:element eid :node "n1"})
        el          (:result res)]
    (is (nil? (:wireframe el)))))

(deftest delete-wireframe-node-rejects-missing-wireframe
  (let [[store eid] (screen-with-field)
        err         (s/err store r/delete-wireframe-node {:element eid :node "n1"})]
    (is (= :not-found (:error err)))))
