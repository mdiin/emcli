(ns emcli.rules-test
  "rule_success, rule_entity_creation, transition_edge, transition_rejected,
  name uniqueness (the same_name guards) and cascade obligations from
  event-model.allium."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [emcli.model :as m]
            [emcli.rules :as r]
            [emcli.support :as s]
            [emcli.wireframe :as wf]))

;; --- rule_entity_creation --------------------------------------------------

(deftest create-rules-produce-entities-and-deltas
  (let [[store mid] (s/with-model)]
    (doseq [[rule args type] [[r/create-timeline {:model mid :title "T"} :timeline]
                              [r/create-swimlane {:model mid :name "L" :index 0} :swimlane]
                              [r/create-element  {:model mid :name "E" :element-type :command} :element]]]
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
      (let [store (:store (s/ok store r/create-element {:model mid :name "Order" :element-type :screen}))
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
          store       (:store (s/ok store r/create-element {:model mid :name "Order" :element-type :command}))
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
        store       (:store (s/ok store r/add-slice {:timeline tlid :title "S" :slice-type :state_change :index 0}))
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
        store       (:store (s/ok store r/add-slice {:timeline tlid :title "S" :slice-type :state_change :index 0}))
        slid        (:id (first (m/slices store tlid)))
        store       (:store (s/ok store r/create-element {:model mid :name "PlaceOrder" :element-type :command}))
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
        store       (:store (s/ok store r/add-slice {:timeline tlid :title "S" :slice-type :state_change :index 0}))
        slid        (:id (first (m/slices store tlid)))
        store       (:store (s/ok store r/create-element {:model mid :name "PlaceOrder" :element-type :command}))
        cmd         (:id (first (m/elements store mid)))
        store       (:store (s/ok store r/create-element {:model mid :name "OrderPlaced" :element-type :event}))
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
;; Placements: relative reorder and removal, keyed on (slice, element)
;; ---------------------------------------------------------------------------

(defn- slice-with-elements
  "A store with one slice holding one placement per named element, in the given
  order. Returns {:store :mid :slid :elements} with :elements a name -> id map."
  [names]
  (let [[store mid]    (s/with-model)
        store          (:store (s/ok store r/create-timeline {:model mid :title "T"}))
        tlid           (:id (first (m/timelines store mid)))
        store          (:store (s/ok store r/add-slice {:timeline tlid :title "S"
                                                        :slice-type :state_change :index 0}))
        slid           (:id (first (m/slices store tlid)))
        [store elements] (reduce (fn [[st acc] nm]
                                   (let [res (s/ok st r/create-element {:model mid :name nm :element-type :event})]
                                     [(:store res) (assoc acc nm (:id (:result res)))]))
                                 [store {}] names)
        store          (reduce (fn [st nm]
                                 (:store (s/ok st r/place-element
                                               {:slice slid :element (get elements nm)})))
                               store names)]
    {:store store :mid mid :slid slid :elements elements}))

(defn- slice-order
  "The element names of a slice's placements, in display order."
  [store slid]
  (mapv #(:name (m/placement-element store %)) (m/placements store slid)))

(deftest reorder-placement-front-and-back
  (let [{:keys [store slid elements]} (slice-with-elements ["A" "B" "C"])]
    (testing ":front moves the target to the head and renumbers 0..n-1"
      (let [res    (s/ok store r/reorder-placement {:slice slid :element (elements "C") :position :front})
            store' (:store res)]
        (is (= ["C" "A" "B"] (slice-order store' slid)))
        (is (= [0 1 2] (map :index (m/placements store' slid))))
        (is (= (elements "C") (:element (:result res))))
        (is (= :ReorderPlacement (:op (:delta res))))
        (is (= 3 (count (:changes (:delta res))))
            "every placement is restated, not just the moved one")))
    (testing ":back moves the target to the tail"
      (let [store' (:store (s/ok store r/reorder-placement {:slice slid :element (elements "A") :position :back}))]
        (is (= ["B" "C" "A"] (slice-order store' slid)))
        (is (= [0 1 2] (map :index (m/placements store' slid))))))))

(deftest reorder-placement-before-and-after
  (let [{:keys [store slid elements]} (slice-with-elements ["A" "B" "C"])]
    (testing ":before inserts immediately before the anchor's placement"
      (let [store' (:store (s/ok store r/reorder-placement {:slice slid :element (elements "C") :before (elements "A")}))]
        (is (= ["C" "A" "B"] (slice-order store' slid)))
        (is (= [0 1 2] (map :index (m/placements store' slid))))))
    (testing ":after inserts immediately after the anchor's placement"
      (let [store' (:store (s/ok store r/reorder-placement {:slice slid :element (elements "A") :after (elements "C")}))]
        (is (= ["B" "C" "A"] (slice-order store' slid)))
        (is (= [0 1 2] (map :index (m/placements store' slid))))))
    (testing "every other placement keeps its relative order"
      (let [store' (:store (s/ok store r/reorder-placement {:slice slid :element (elements "A") :after (elements "B")}))]
        (is (= ["B" "A" "C"] (slice-order store' slid)))))
    (testing "a move that changes nothing still succeeds, with no changes restated"
      (let [res (s/ok store r/reorder-placement {:slice slid :element (elements "B") :before (elements "C")})]
        (is (= ["A" "B" "C"] (slice-order (:store res) slid)))
        (is (empty? (:changes (:delta res))))))))

(deftest reorder-placement-renormalizes-indices
  (let [{:keys [store slid elements]} (slice-with-elements ["A" "B" "C"])
        store  (:store (s/ok store r/remove-placement {:slice slid :element (elements "B")}))
        store' (:store (s/ok store r/reorder-placement {:slice slid :element (elements "C") :position :front}))]
    (testing "removal leaves the survivors' indices non-contiguous"
      (is (= [0 2] (map :index (m/placements store slid)))))
    (testing "a reorder renormalizes the whole slice to 0..n-1"
      (is (= ["C" "A"] (slice-order store' slid)))
      (is (= [0 1] (map :index (m/placements store' slid)))))))

(deftest remove-placement-by-slice-and-element
  (let [{:keys [store slid elements]} (slice-with-elements ["A" "B"])
        res    (s/ok store r/remove-placement {:slice slid :element (elements "A")})
        store' (:store res)]
    (is (= ["B"] (slice-order store' slid)))
    (is (= (elements "A") (:element (:result res))) "the removed placement is returned")
    (is (= :RemovePlacement (:op (:delta res))))
    (is (= [{:action :deleted :type :placement :id (:id (:result res))}]
           (:changes (:delta res))))))

(deftest placement-rules-reject-an-element-not-placed
  (let [{:keys [store mid slid elements]} (slice-with-elements ["A"])
        store (:store (s/ok store r/create-element {:model mid :name "Z" :element-type :event}))
        zid   (:id (first (filter #(= "Z" (:name %)) (m/elements store mid))))]
    (testing "remove"
      (let [err (s/err store r/remove-placement {:slice slid :element zid})]
        (is (= :not-found (:error err)))
        (is (= :placement (:type err)))
        (is (= zid (:element err)))
        (is (re-find #"no placement of element" (:message err)))))
    (testing "reorder of an unplaced target"
      (let [err (s/err store r/reorder-placement {:slice slid :element zid :position :front})]
        (is (= :not-found (:error err)))
        (is (re-find #"no placement of element" (:message err)))))
    (testing "reorder against an unplaced before/after anchor"
      (let [err (s/err store r/reorder-placement {:slice slid :element (elements "A") :before zid})]
        (is (= :not-found (:error err)))
        (is (= zid (:element err)))
        (is (re-find #"no placement of element" (:message err)))))
    (testing "a genuinely unknown element is the usual not-found"
      (let [err (s/err store r/remove-placement {:slice slid :element 99999})]
        (is (= :not-found (:error err)))
        (is (= :element (:type err)))))))

(deftest reorder-placement-requires-exactly-one-move-selector
  (let [{:keys [store slid elements]} (slice-with-elements ["A" "B"])]
    (testing "no selector"
      (let [err (s/err store r/reorder-placement {:slice slid :element (elements "A")})]
        (is (= :invalid-value (:error err)))
        (is (re-find #"exactly one" (:message err)))))
    (testing "more than one selector"
      (let [err (s/err store r/reorder-placement {:slice slid :element (elements "A")
                                                  :position :front :before (elements "B")})]
        (is (= :invalid-value (:error err)))
        (is (re-find #"exactly one" (:message err)))))
    (testing "a position outside front/back"
      (let [err (s/err store r/reorder-placement {:slice slid :element (elements "A") :position :middle})]
        (is (= :invalid-value (:error err)))
        (is (= :middle (:value err)))))))

(deftest reorder-placement-rejects-anchor-equal-to-moved-element
  ;; The anchor is lifted out of the slice before the insert, so an anchor equal
  ;; to the moved element has nothing left to insert next to: the before-branch
  ;; would silently tail it and the after-branch would splice a nil placement
  ;; (and renumber would commit a dangling id). Reject it up front instead.
  (let [{:keys [store slid elements]} (slice-with-elements ["A" "B" "C"])]
    (testing "--before the element being moved is rejected"
      (let [err (s/err store r/reorder-placement {:slice slid :element (elements "B")
                                                  :before (elements "B")})]
        (is (= :invalid-value (:error err)))
        (is (re-find #"different element" (:message err)))))
    (testing "--after the element being moved is rejected"
      (let [err (s/err store r/reorder-placement {:slice slid :element (elements "A")
                                                  :after (elements "A")})]
        (is (= :invalid-value (:error err)))
        (is (re-find #"different element" (:message err)))))
    (testing "the store is untouched: same order, same indices"
      (is (= ["A" "B" "C"] (slice-order store slid)))
      (is (= [0 1 2] (map :index (m/placements store slid)))))))

;; ---------------------------------------------------------------------------
;; Wireframe rules
;; ---------------------------------------------------------------------------

(defn- screen-with-field
  "A store with one screen element that has a :searchTerm field."
  []
  (let [[store mid] (s/with-model)
        res         (s/ok store r/create-element {:model mid :name "OrderList" :element-type :screen})
        store       (:store res)
        eid         (:id (:result res))
        store       (:store (s/ok store r/add-field {:element eid
                                                      :field {:name "searchTerm" :type :string}}))]
    [store eid]))

(deftest add-wireframe-node-seeds-screen-on-first-call
  (let [[store eid] (screen-with-field)
        res         (s/ok store r/add-wireframe-node {:element eid :tag :col :parent "n1"})
        el          (:result res)]
    (is (= :canvas (first (:wireframe el))))
    (is (some? (wf/find-node (:wireframe el) "n1")))
    (is (some? (wf/find-node (:wireframe el) "n2")))))

(deftest add-wireframe-node-appends-nested-node
  (let [[store eid] (screen-with-field)
        store       (:store (s/ok store r/add-wireframe-node {:element eid :tag :col :parent "n1"}))
        res         (s/ok store r/add-wireframe-node {:element eid :tag :h1 :text "Hello" :parent "n2"})
        el          (:result res)
        node        (wf/find-node (:wireframe el) "n3")]
    (is (some? node))
    (is (some #(= "Hello" %) node)
        ":text is the rule's own input and becomes the node's string child")))

(deftest add-wireframe-node-rejects-non-screen-element
  (let [[store mid] (s/with-model)
        res         (s/ok store r/create-element {:model mid :name "PlaceOrder" :element-type :command})
        store       (:store res)
        eid         (:id (:result res))
        err         (s/err store r/add-wireframe-node {:element eid :tag :col :parent "n1"})]
    (is (= :invalid-value (:error err)))))

(deftest add-wireframe-node-rejects-unknown-field-name
  (let [[store eid] (screen-with-field)
        err         (s/err store r/add-wireframe-node {:element eid :tag :input
                                                        :attrs {:field-name "nonexistent"} :parent "n1"})]
    (is (= :invalid-wireframe (:error err)))))

;; -- parent addressing: a named parent must exist, no parent means the root --

(defn- child-ids
  "Ids of the immediate child nodes of the node with `node-id` in `el`."
  [el node-id]
  (map #(some :-id %) (filter vector? (rest (wf/find-node (:wireframe el) node-id)))))

(deftest add-wireframe-node-rejects-unknown-parent
  (testing "on a fresh screen (no wireframe yet)"
    (let [[store eid] (screen-with-field)
          err         (s/err store r/add-wireframe-node {:element eid :tag :button
                                                         :attrs {:label "Save"} :parent "nX"})]
      (is (= :not-found (:error err)))
      (is (= :wireframe-node (:type err)))
      (is (= "nX" (:id err)))
      (is (= "node nX does not exist" (:message err)))))
  (testing "on a screen that already has a layout"
    (let [[store eid] (screen-with-field)
          store       (:store (s/ok store r/add-wireframe-node {:element eid :tag :col :parent "n1"}))
          err         (s/err store r/add-wireframe-node {:element eid :tag :button
                                                         :attrs {:label "Save"} :parent "n99"})]
      (is (= :not-found (:error err)))
      (is (= :wireframe-node (:type err))))))

(deftest add-wireframe-node-explicit-root-parent-appends-at-root
  (let [[store eid] (screen-with-field)
        el          (:result (s/ok store r/add-wireframe-node {:element eid :tag :col :parent "n1"}))]
    (is (= ["n2"] (child-ids el "n1")))))

(deftest add-wireframe-node-without-parent-appends-at-root
  (let [[store eid] (screen-with-field)
        el          (:result (s/ok store r/add-wireframe-node {:element eid :tag :col}))]
    (is (= ["n2"] (child-ids el "n1")))))

(deftest add-wireframe-node-existing-nested-parent-still-works
  (let [[store eid] (screen-with-field)
        store       (:store (s/ok store r/add-wireframe-node {:element eid :tag :col :parent "n1"}))
        el          (:result (s/ok store r/add-wireframe-node {:element eid :tag :h1
                                                               :text "Hello" :parent "n2"}))
        node        (wf/find-node (:wireframe el) "n3")]
    (is (= ["n3"] (child-ids el "n2")))
    (is (some #(= "Hello" %) node))))

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

(deftest add-wireframe-node-before-inserts-before-sibling
  (let [[store eid] (screen-with-field)
        store       (:store (s/ok store r/add-wireframe-node {:element eid :tag :col :parent "n1"}))
        store       (:store (s/ok store r/add-wireframe-node {:element eid :tag :h1 :parent "n2"}))
        store       (:store (s/ok store r/add-wireframe-node {:element eid :tag :button :attrs {:label "B"} :parent "n2"}))
        ;; wireframe: n1[:canvas] > n2[:col] > n3[:h1], n4[:button]
        ;; insert divider before n4
        res         (s/ok store r/add-wireframe-node-before {:element eid :before "n4" :tag :divider :attrs {}})
        wf          (:wireframe (:result res))
        col         (wf/find-node wf "n2")
        kids        (filter vector? (drop 1 col))]
    (is (= 3 (count kids)))
    (is (= :h1 (first (first kids))))
    (is (= :divider (first (second kids))))
    (is (= "n4" (get (second (nth kids 2)) :-id)))))

(deftest add-wireframe-node-before-rejects-root
  (let [[store eid] (screen-with-field)
        _           (s/ok store r/add-wireframe-node {:element eid :tag :col :parent "n1"})
        store       (:store (s/ok store r/add-wireframe-node {:element eid :tag :col :parent "n1"}))
        err         (s/err store r/add-wireframe-node-before {:element eid :before "n1" :tag :divider :attrs {}})]
    (is (= :invalid-value (:error err)))))

(deftest add-wireframe-node-before-rejects-missing-wireframe
  (let [[store eid] (screen-with-field)
        err         (s/err store r/add-wireframe-node-before {:element eid :before "n2" :tag :divider :attrs {}})]
    (is (= :not-found (:error err)))))

(deftest add-wireframe-node-before-rejects-unknown-node
  (let [[store eid] (screen-with-field)
        store       (:store (s/ok store r/add-wireframe-node {:element eid :tag :col :parent "n1"}))
        err         (s/err store r/add-wireframe-node-before {:element eid :before "n99" :tag :divider :attrs {}})]
    (is (= :not-found (:error err)))))

;; --- field removal vs a stored layout reference (BUGS.md item 5) -----------
;; RemoveField guards the removal itself: an edit that drops a field name the
;; screen's stored layout still names is refused, so a field edit cannot strand
;; the reference. A store that already holds one stays editable.

(defn- screen-with-layout-naming
  "A store with one screen declaring :searchTerm and an :input node naming it.
  Returns [store element-id]."
  []
  (let [[store eid] (screen-with-field)]
    [(:store (s/ok store r/add-wireframe-node {:element eid :tag :input
                                               :attrs {:field-name "searchTerm"}
                                               :parent "n1"}))
     eid]))

(deftest remove-field-rejects-a-field-a-layout-still-names
  (let [[store eid] (screen-with-layout-naming)
        err         (s/err store r/remove-field {:element eid :name "searchTerm"})]
    (testing "the removal is refused, naming the field and the node referring to it"
      (is (= :field-referenced (:error err)))
      (is (= "searchTerm" (:field err)))
      (is (= ["n2"] (:nodes err)))
      (is (re-find #"searchTerm" (:message err)))
      (is (re-find #"n2" (:message err))))
    (testing "nothing was committed: the field is still declared"
      (is (= ["searchTerm"] (map :name (:fields (m/fetch store :element eid))))))))

(deftest set-fields-rejects-dropping-a-referenced-field
  (let [[store eid] (screen-with-layout-naming)
        store       (:store (s/ok store r/add-field {:element eid :field {:name "page" :type :int}}))
        err         (s/err store r/set-fields {:element eid :fields [{:name "page" :type :int}]})]
    (testing "a whole-list replace that drops the referenced name is refused too"
      (is (= :field-referenced (:error err)))
      (is (= "searchTerm" (:field err)))
      (is (= ["n2"] (:nodes err))))))

(deftest field-edits-not-removing-a-referenced-name-still-succeed
  (testing "a field no node names can be removed while the layout stays"
    (let [[store eid] (screen-with-layout-naming)
          store       (:store (s/ok store r/add-field {:element eid :field {:name "page" :type :int}}))
          res         (s/ok store r/remove-field {:element eid :name "page"})]
      (is (= ["searchTerm"] (map :name (:fields (:result res)))))))
  (testing "adding a field is unaffected"
    (let [[store eid] (screen-with-layout-naming)
          res         (s/ok store r/add-field {:element eid :field {:name "page" :type :int}})]
      (is (= ["searchTerm" "page"] (map :name (:fields (:result res)))))))
  (testing "a whole-list replace that keeps the referenced name is accepted"
    (let [[store eid] (screen-with-layout-naming)
          res         (s/ok store r/set-fields {:element eid
                                                :fields [{:name "searchTerm" :type :string}]})]
      (is (= ["searchTerm"] (map :name (:fields (:result res)))))))
  (testing "a screen with no layout is unaffected"
    (let [[store eid] (screen-with-field)
          res         (s/ok store r/remove-field {:element eid :name "searchTerm"})]
      (is (empty? (:fields (:result res)))))))

(deftest a-store-holding-a-stranded-reference-is-refused
  (let [[store eid] (screen-with-layout-naming)
        store       (:store (s/ok store r/add-field {:element eid :field {:name "page" :type :int}}))
        ;; The reference is stranded directly: with the wireframe invariants
        ;; enforced at commit this state is unreachable through the rules, and a
        ;; store holding it refuses to load, so a direct write is the only way
        ;; left to reach it.
        store       (m/set-field store :element eid :fields [{:name "page" :type :int}])]
    (is (= ["n2"] (map :node-id (wf/field-references (:wireframe (m/fetch store :element eid))))))
    (testing "every mutation is refused — the invariant holds at every observable state"
      (let [res (r/remove-field store {:element eid :name "page"})]
        (is (r/error? res))
        (is (= :invariant-violation (:error res)))))))

;; --- name uniqueness (the guards) -------------------------------------------
;; Every name a caller resolves must identify exactly one entity of its container,
;; so the operations that create or rename one reject a collision - and name the
;; entity already holding the name, so a caller reuses what exists rather than
;; inventing a near-duplicate. The invariants behind the guards are covered in
;; invariants-test; here the guard's own shape is.

(defn- unique-name-fixture
  "A store holding one timeline, element, swimlane, slice and specification, with
  each entity and the model id, for the collision guards to be exercised against."
  []
  (let [[store mid]              (s/with-model)
        {s1 :store tl :result}   (s/ok store r/create-timeline {:model mid :title "Ordering"})
        {s2 :store el :result}   (s/ok s1 r/create-element {:model mid :name "PlaceOrder" :element-type :command})
        {s3 :store ln :result}   (s/ok s2 r/create-swimlane {:model mid :name "Orders" :index 0})
        {s4 :store sl :result}   (s/ok s3 r/add-slice {:timeline (:id tl) :title "Place order"
                                                       :slice-type :state_change :index 0})
        {s5 :store sp :result}   (s/ok s4 r/add-specification {:slice (:id sl) :title "Places order"})]
    {:store s5 :model mid :timeline tl :element el :lane ln :slice sl :spec sp}))

(deftest a-colliding-name-is-rejected-and-names-the-incumbent
  (let [{:keys [store model timeline element lane slice spec]} (unique-name-fixture)]
    (testing "element: a name is not reused, whatever the element type of either"
      (let [err (s/err store r/create-element {:model model :name "placeorder" :element-type :event})]
        (is (= :name-conflict (:error err)))
        (is (= (:id element) (:id err)) "the rejection names the element that holds it")
        (is (= :element (:type err)))
        (is (str/includes? (:message err) (str (:id element))))
        (is (str/includes? (:message err) "command") "its element type is named too, for reuse")))
    (testing "element: renaming onto another element's name is rejected"
      (let [{s :store other :result} (s/ok store r/create-element {:model model
                                                                  :name "CancelOrder"
                                                                  :element-type :command})
            err                      (s/err s r/rename-element {:element (:id other)
                                                                :new-name "PlaceOrder"})]
        (is (= :name-conflict (:error err)))
        (is (= (:id element) (:id err)))))
    (testing "timeline title"
      (let [err (s/err store r/create-timeline {:model model :title " ordering "})]
        (is (= :name-conflict (:error err)))
        (is (= (:id timeline) (:id err)))))
    (testing "timeline: renaming onto another timeline's title is rejected"
      (let [{s :store t2 :result} (s/ok store r/create-timeline {:model model :title "Viewing"})
            err                   (s/err s r/rename-timeline {:timeline (:id t2)
                                                              :new-title "ORDERING"})]
        (is (= :name-conflict (:error err)))))
    (testing "swimlane name"
      (let [err (s/err store r/create-swimlane {:model model :name "ORDERS" :index 1})]
        (is (= :name-conflict (:error err)))
        (is (= (:id lane) (:id err)))))
    (testing "slice title, within its timeline"
      (let [err (s/err store r/add-slice {:timeline (:id timeline) :title "place order"
                                          :slice-type :state_change :index 1})]
        (is (= :name-conflict (:error err)))
        (is (= (:id slice) (:id err)))))
    (testing "specification title, within its slice"
      (let [err (s/err store r/add-specification {:slice (:id slice) :title "PLACES ORDER"})]
        (is (= :name-conflict (:error err)))
        (is (= (:id spec) (:id err)))))))

(deftest names-that-are-not-collisions-are-accepted
  (let [{:keys [store model timeline element lane]} (unique-name-fixture)]
    (testing "renaming an entity to the name it already holds"
      (is (not (r/error? (r/rename-element store {:element (:id element)
                                                  :new-name "PlaceOrder"}))))
      (is (not (r/error? (r/rename-timeline store {:timeline (:id timeline)
                                                   :new-title "Ordering"}))))
      (is (not (r/error? (r/rename-swimlane store {:lane (:id lane) :new-name "Orders"})))))
    (testing "the same name in a different container"
      (let [{s :store t2 :result} (s/ok store r/create-timeline {:model model :title "Viewing"})]
        (is (not (r/error? (r/add-slice s {:timeline (:id t2) :title "Place order"
                                           :slice-type :state_change :index 0}))))
        (is (not (r/error? (r/create-swimlane s {:model model :name "Customers" :index 1})))))
      (let [{s :store m2 :result} (s/ok store r/create-model {:name "Other"})]
        (is (not (r/error? (r/create-element s {:model (:id m2) :name "PlaceOrder"
                                                :element-type :command})))
            "a name is unique within its model, not globally")))))

;; --- upsert_by: an entry is replaced in place --------------------------------
;; List position is observable (it is the order a frontend renders), so a
;; re-added entry keeps its slot; only a new entry is appended.

(deftest re-adding-an-entry-replaces-it-in-place
  (let [[store mid]       (s/with-model)
        {s1 :store el :result} (s/ok store r/create-element {:model mid :name "E" :element-type :command})
        eid               (:id el)
        {s2 :store}       (s/ok s1 r/add-field {:element eid :field {:name "a" :type :string}})
        {s3 :store}       (s/ok s2 r/add-field {:element eid :field {:name "b" :type :string}})
        {s4 :store}       (s/ok s3 r/add-field {:element eid :field {:name "c" :type :string}})
        {s5 :store}       (s/ok s4 r/add-field {:element eid :field {:name "a" :type :int}})
        fields            (:fields (m/fetch s5 :element eid))]
    (is (= ["a" "b" "c"] (map :name fields))
        "the replaced field keeps its position and the others are untouched")
    (is (= :int (:type (first fields)))))
  (testing "and for the other keyed lists too"
    (let [[store mid]         (s/with-model)
          {s1 :store el :result} (s/ok store r/create-element {:model mid :name "E" :element-type :command})
          eid                 (:id el)
          {s2 :store}         (s/ok s1 r/add-field-origin {:element eid :field "a" :origin :user_input})
          {s3 :store}         (s/ok s2 r/add-field-origin {:element eid :field "b" :origin :generated})
          {s4 :store}         (s/ok s3 r/add-field-origin {:element eid :field "a" :origin :external})
          origins             (:field_origins (m/fetch s4 :element eid))]
      (is (= [["a" :external] ["b" :generated]]
             (mapv (juxt :field :origin) origins))))))

;; --- a canvas is the root of a layout, never a node -------------------------

(deftest canvas-cannot-be-added-as-a-node
  (let [[store mid]           (s/with-model)
        {s1 :store scr :result} (s/ok store r/create-element {:model mid :name "S" :element-type :screen})
        eid                   (:id scr)]
    (testing "adding one is rejected"
      (is (= :invalid-value
             (:error (s/err s1 r/add-wireframe-node {:element eid :tag :canvas})))))
    (testing "and so is inserting one before a sibling"
      (let [store (:store (s/ok s1 r/add-wireframe-node {:element eid :tag :row}))]
        ;; the row is n2, since the canvas root is always n1
        (is (= :invalid-value
               (:error (s/err store r/add-wireframe-node-before {:element eid :before "n2"
                                                                 :tag :canvas}))))))
    (testing "the root canvas comes with the layout, so a screen still gets one"
      (is (= :canvas (first (:wireframe (:result (s/ok s1 r/add-wireframe-node
                                                       {:element eid :tag :row})))))))))

;; --- a field list is keyed by name ------------------------------------------

(deftest a-field-list-cannot-hold-two-of-one-name
  (let [[store mid]             (s/with-model)
        {s1 :store el :result}  (s/ok store r/create-element {:model mid :name "E" :element-type :command})]
    (testing "a duplicated name at the top level is rejected"
      (let [err (s/err s1 r/set-fields {:element (:id el)
                                        :fields [{:name "a" :type :string}
                                                 {:name "a" :type :int}]})]
        (is (= :invalid-value (:error err)))
        (is (str/includes? (:message err) "appears twice"))))
    (testing "and at any depth"
      (is (= :invalid-value
             (:error (s/err s1 r/set-fields {:element (:id el)
                                             :fields [{:name "a" :type :string
                                                       :subfields [{:name "b" :type :string}
                                                                   {:name "b" :type :int}]}]}))))))
  (testing "the canonical Field shape is materialised whoever supplied it"
    (let [[store mid]            (s/with-model)
          {s1 :store el :result} (s/ok store r/create-element {:model mid :name "E" :element-type :command})
          res                    (s/ok s1 r/add-field {:element (:id el)
                                                       :field {:name "a" :type :string}})]
      (is (= {:name "a" :type :string :optional false :cardinality :single :subfields []}
             (first (:fields (:result res))))))))

;; --- field names compare by the model's name equality (FieldNameUnique) ------

(deftest field-names-are-the-same-name-when-they-differ-only-in-case
  (let [[store mid]            (s/with-model)
        {s1 :store el :result} (s/ok store r/create-element {:model mid :name "E" :element-type :command})
        eid                    (:id el)]
    (testing "a field list cannot hold two fields under one name, however spelled"
      (is (= :invalid-value
             (:error (s/err s1 r/set-fields {:element eid
                                             :fields [{:name "id" :type :string}
                                                      {:name "ID" :type :string}]})))))
    (testing "AddField updates a name it already finds rather than adding a second"
      (let [{s2 :store} (s/ok s1 r/add-field {:element eid :field {:name "id" :type :string}})
            {s3 :store} (s/ok s2 r/add-field {:element eid :field {:name "ID" :type :string}})]
        (is (= ["ID"] (mapv :name (:fields (m/fetch s3 :element eid)))))))
    (testing "RemoveField matches by name equality, so either spelling removes it"
      (let [{s2 :store} (s/ok s1 r/add-field {:element eid :field {:name "id" :type :string}})
            {s3 :store} (s/ok s2 r/remove-field {:element eid :name "ID"})]
        (is (= [] (:fields (m/fetch s3 :element eid))))))
    (testing "field-origin overrides are keyed the same way"
      (let [{s2 :store} (s/ok s1 r/add-field {:element eid :field {:name "amount" :type :string}})
            {s3 :store} (s/ok s2 r/add-field-origin {:element eid :field "amount" :origin :generated})
            {s4 :store} (s/ok s3 r/add-field-origin {:element eid :field "AMOUNT" :origin :user_input})]
        (is (= ["AMOUNT"] (mapv :field (:field_origins (m/fetch s4 :element eid)))))))))

(deftest provenance-and-layouts-resolve-field-names-by-that-equality
  (testing "a field is CARRIED across a connection whose far side spells it differently"
    (let [[store mid]              (s/with-model)
          {s1 :store src :result}  (s/ok store r/create-element {:model mid :name "Src" :element-type :event})
          {s2 :store dst :result}  (s/ok s1 r/create-element {:model mid :name "Dst" :element-type :read_model})
          {s3 :store}              (s/ok s2 r/add-field {:element (:id src) :field {:name "Amount" :type :string}})
          {s4 :store}              (s/ok s3 r/add-field {:element (:id dst) :field {:name "amount" :type :string}})
          {s5 :store}              (s/ok s4 r/connect {:from (:id src) :to (:id dst)})]
      (is (m/information-complete? s5 (m/fetch s5 :element (:id dst))))))
  (testing "a derivation's target and source fields are keyed the same way"
    (let [[store mid]              (s/with-model)
          {s1 :store cmd :result}  (s/ok store r/create-element {:model mid :name "Cmd" :element-type :command})
          {s2 :store evt :result}  (s/ok s1 r/create-element {:model mid :name "Evt" :element-type :event})
          {s3 :store}              (s/ok s2 r/add-field {:element (:id cmd) :field {:name "orderId" :type :string}})
          {s4 :store}              (s/ok s3 r/add-field {:element (:id evt) :field {:name "total" :type :decimal}})
          {s5 :store cn :result}   (s/ok s4 r/connect {:from (:id cmd) :to (:id evt)})
          {s6 :store}              (s/ok s5 r/add-derivation {:connection (:id cn) :target "total" :from ["orderId"]})
          {s7 :store}              (s/ok s6 r/add-derivation {:connection (:id cn) :target "TOTAL" :from ["orderId"]})]
      (is (= ["TOTAL"] (mapv :target_field (:derivations (m/fetch s7 :connection (:id cn))))))
      (let [{s8 :store} (s/ok s7 r/remove-derivation {:connection (:id cn) :target "total"})]
        (is (= [] (:derivations (m/fetch s8 :connection (:id cn))))
            "removing by the other spelling removes the same name"))))
  (testing "a layout node resolves a field the screen declares under another spelling"
    (let [[store mid]             (s/with-model)
          {s1 :store scr :result} (s/ok store r/create-element {:model mid :name "S" :element-type :screen})
          {s2 :store}             (s/ok s1 r/set-fields {:element (:id scr)
                                                         :fields [{:name "searchTerm" :type :string}]})]
      (is (= {:valid? true}
             (wf/validate-semantics [:canvas {:-id "n1"} {}
                                     [:input {:-id "n2"} {:field-name "SearchTerm"}]]
                                    (m/fetch s2 :element (:id scr))))))))

;; --- the renames (RenameSlice / RenameSpecification / RenameErrorStep / RenameField) --

(deftest slice-and-specification-titles-rename-under-their-uniqueness-guards
  (let [[store mid]              (s/with-model)
        {s1 :store tl :result}   (s/ok store r/create-timeline {:model mid :title "T"})
        {s2 :store sl :result}   (s/ok s1 r/add-slice {:timeline (:id tl) :title "Place"
                                                       :slice-type :state_change :index 0})
        {s3 :store other :result}(s/ok s2 r/add-slice {:timeline (:id tl) :title "Confirm"
                                                       :slice-type :state_change :index 1})]
    (testing "a slice's title can be changed, within its timeline"
      (let [{s4 :store} (s/ok s3 r/rename-slice {:slice (:id sl) :new-title "Confirmed"})]
        (is (= "Confirmed" (:title (m/fetch s4 :slice (:id sl)))))))
    (testing "but not onto one its timeline already holds, nor to a blank"
      (is (= :name-conflict (:error (s/err s3 r/rename-slice {:slice (:id sl) :new-title "Confirm"}))))
      (is (= :invalid-value (:error (s/err s3 r/rename-slice {:slice (:id sl) :new-title "  "})))))
    (testing "a specification's title renames within its slice"
      (let [{s4 :store sp :result}  (s/ok s3 r/add-specification {:slice (:id sl) :title "Happy"})
            {s5 :store sp2 :result} (s/ok s4 r/add-specification {:slice (:id sl) :title "Sad"})
            {s6 :store}             (s/ok s5 r/rename-specification {:spec (:id sp) :new-title "Glad"})]
        (is (= "Glad" (:title (m/fetch s6 :specification (:id sp)))))
        (is (= :name-conflict (:error (s/err s6 r/rename-specification {:spec (:id sp) :new-title "Sad"}))))))))

(deftest an-error-step-renames-its-outcome-without-a-uniqueness-rule
  (let [[store mid]              (s/with-model)
        {s1 :store cmd :result}  (s/ok store r/create-element {:model mid :name "Cmd" :element-type :command})
        {s2 :store tl :result}   (s/ok s1 r/create-timeline {:model mid :title "T"})
        {s3 :store sl :result}   (s/ok s2 r/add-slice {:timeline (:id tl) :title "S"
                                                       :slice-type :state_change :index 0})
        {s4 :store sp :result}   (s/ok s3 r/add-specification {:slice (:id sl) :title "spec"})
        {s5 :store est :result}  (s/ok s4 r/add-error-step {:spec (:id sp) :error-name "Declined" :index 0})]
    (testing "the outcome name is content, so two error steps may carry the same one"
      (let [{s6 :store} (s/ok s5 r/rename-error-step {:step (:id est) :new-error-name "PaymentDeclined"})]
        (is (= "PaymentDeclined" (:error_name (m/fetch s6 :spec-step (:id est)))))))
    (testing "only an error step is renamed this way"
      (let [{s6 :store st :result} (s/ok s5 r/add-spec-step {:spec (:id sp) :clause :when_step
                                                             :element (:id cmd) :index 1})]
        (is (= :invalid-value (:error (s/err s6 r/rename-error-step {:step (:id st) :new-error-name "X"}))))))))

(deftest renaming-a-field-carries-every-reference-with-it
  (let [[store mid]             (s/with-model)
        {s1 :store cmd :result} (s/ok store r/create-element {:model mid :name "Cmd" :element-type :command})
        {s2 :store evt :result} (s/ok s1 r/create-element {:model mid :name "Evt" :element-type :event})
        {s3 :store}             (s/ok s2 r/add-field {:element (:id cmd) :field {:name "orderId" :type :uuid}})
        {s4 :store}             (s/ok s3 r/add-field {:element (:id evt) :field {:name "total" :type :decimal}})
        {s5 :store}             (s/ok s4 r/add-field-origin {:element (:id evt) :field "total" :origin :generated})
        {s6 :store cn :result}  (s/ok s5 r/connect {:from (:id cmd) :to (:id evt)})
        {s7 :store}             (s/ok s6 r/add-derivation {:connection (:id cn) :target "total" :from ["orderId"]})
        {s8 :store tl :result}  (s/ok s7 r/create-timeline {:model mid :title "T"})
        {s9 :store sl :result}  (s/ok s8 r/add-slice {:timeline (:id tl) :title "S"
                                                      :slice-type :state_change :index 0})
        {s10 :store sp :result} (s/ok s9 r/add-specification {:slice (:id sl) :title "spec"})
        {s11 :store st :result} (s/ok s10 r/add-spec-step {:spec (:id sp) :clause :then_step
                                                           :element (:id evt) :index 0})
        {s12 :store}            (s/ok s11 r/add-step-example {:step (:id st) :field-name "total" :field-value "1"})
        {s13 :store}            (s/ok s12 r/rename-field {:element (:id evt) :name "total" :new-name "amount"})]
    (testing "the declaration moves, keeping the field's shape"
      (is (= ["amount"] (mapv :name (:fields (m/fetch s13 :element (:id evt))))))
      (is (= :decimal (:type (first (:fields (m/fetch s13 :element (:id evt))))))))
    (testing "the element's own field-origin override moves"
      (is (= ["amount"] (mapv :field (:field_origins (m/fetch s13 :element (:id evt)))))))
    (testing "an INCOMING connection's target moves; its source side is untouched"
      (let [d (first (:derivations (m/fetch s13 :connection (:id cn))))]
        (is (= "amount" (:target_field d)))
        (is (= ["orderId"] (:source_fields d)) "the far side names a field of the other element")))
    (testing "a step example about the element moves"
      (is (= ["amount"] (mapv :field_name (:examples (m/fetch s13 :spec-step (:id st)))))))
    (testing "one delta carries the element, the connection and the step"
      (let [{:keys [delta]} (s/ok s12 r/rename-field {:element (:id evt) :name "total" :new-name "amount"})]
        (is (= :RenameField (:op delta)))
        (is (= #{(:id evt) (:id cn) (:id st)}
               (set (map :id (:changes delta)))))
        (is (= #{:element :connection :spec-step} (set (map :type (:changes delta)))))))))

(deftest renaming-a-field-on-the-outgoing-side-and-in-a-layout
  (testing "an OUTGOING connection's source_fields move; its target side is untouched"
    (let [[store mid]             (s/with-model)
          {s1 :store cmd :result} (s/ok store r/create-element {:model mid :name "Cmd" :element-type :command})
          {s2 :store evt :result} (s/ok s1 r/create-element {:model mid :name "Evt" :element-type :event})
          {s3 :store}             (s/ok s2 r/add-field {:element (:id cmd) :field {:name "orderId" :type :uuid}})
          {s4 :store}             (s/ok s3 r/add-field {:element (:id evt) :field {:name "total" :type :decimal}})
          {s5 :store cn :result}  (s/ok s4 r/connect {:from (:id cmd) :to (:id evt)})
          {s6 :store}             (s/ok s5 r/add-derivation {:connection (:id cn) :target "total" :from ["orderId"]})
          {s7 :store}             (s/ok s6 r/rename-field {:element (:id cmd) :name "orderId" :new-name "orderRef"})
          d                       (first (:derivations (m/fetch s7 :connection (:id cn))))]
      (is (= ["orderRef"] (:source_fields d)))
      (is (= "total" (:target_field d)) "the far side names a field of the other element")))
  (testing "a screen's layout moves with the field it names"
    (let [[store mid]             (s/with-model)
          {s1 :store scr :result} (s/ok store r/create-element {:model mid :name "S" :element-type :screen})
          {s2 :store}             (s/ok s1 r/set-fields {:element (:id scr)
                                                         :fields [{:name "searchTerm" :type :string}]})
          {s3 :store}             (s/ok s2 r/add-wireframe-node {:element (:id scr) :tag :input
                                                                 :attrs {:field-name "searchTerm"}})
          {s4 :store}             (s/ok s3 r/rename-field {:element (:id scr) :name "searchTerm"
                                                           :new-name "query"})
          refs                    (wf/field-references (:wireframe (m/fetch s4 :element (:id scr))))]
      (is (= ["query"] (mapv :field-name refs)))
      (is (= {:valid? true} (wf/validate-semantics (:wireframe (m/fetch s4 :element (:id scr)))
                                                  (m/fetch s4 :element (:id scr))))))))

(deftest renaming-a-field-refuses-what-it-cannot-move
  (let [[store mid]             (s/with-model)
        {s1 :store el :result}  (s/ok store r/create-element {:model mid :name "E" :element-type :event})
        {s2 :store}             (s/ok s1 r/add-field {:element (:id el) :field {:name "amount" :type :decimal}})
        {s3 :store}             (s/ok s2 r/add-field {:element (:id el) :field {:name "total" :type :decimal}})]
    (testing "a name the element does not carry is rejected, not a silent no-op"
      (is (= :invalid-value (:error (s/err s3 r/rename-field {:element (:id el) :name "nope" :new-name "x"})))))
    (testing "and so is a name another field of the list already holds"
      (is (= :name-conflict (:error (s/err s3 r/rename-field {:element (:id el)
                                                              :name "amount" :new-name "total"})))))
    (testing "while a case-only respelling is a rename, not a collision"
      (let [{s4 :store} (s/ok s3 r/rename-field {:element (:id el) :name "amount" :new-name "AMOUNT"})]
        (is (= ["AMOUNT" "total"] (mapv :name (:fields (m/fetch s4 :element (:id el))))))))))

(deftest renaming-an-unreferenced-field-emits-the-element-alone
  (let [[store mid]             (s/with-model)
        {s1 :store cmd :result} (s/ok store r/create-element {:model mid :name "Cmd" :element-type :command})
        {s2 :store evt :result} (s/ok s1 r/create-element {:model mid :name "Evt" :element-type :event})
        {s3 :store}             (s/ok s2 r/add-field {:element (:id evt) :field {:name "note" :type :string}})
        {s4 :store}             (s/ok s3 r/connect {:from (:id cmd) :to (:id evt)})
        {:keys [delta]}         (s/ok s4 r/rename-field {:element (:id evt) :name "note" :new-name "memo"})]
    (is (= [(:id evt)] (mapv :id (:changes delta)))
        "the connection derives no field, so the respelling never reached it and it is not restated")))

(deftest deleting-an-element-removes-the-steps-that-assert-about-it
  (let [[store mid]             (s/with-model)
        {s1 :store cmd :result} (s/ok store r/create-element {:model mid :name "Cmd" :element-type :command})
        {s2 :store tl :result}  (s/ok s1 r/create-timeline {:model mid :title "T"})
        {s3 :store sl :result}  (s/ok s2 r/add-slice {:timeline (:id tl) :title "S"
                                                      :slice-type :state_change :index 0})
        {s4 :store sp :result}  (s/ok s3 r/add-specification {:slice (:id sl) :title "spec"})
        {s5 :store st :result}  (s/ok s4 r/add-spec-step {:spec (:id sp) :clause :when_step
                                                          :element (:id cmd) :index 0})
        {s6 :store}             (s/ok s5 r/delete-element {:element (:id cmd)})]
    (is (nil? (m/fetch s6 :spec-step (:id st))) "the step that asserted about it goes with it")
    (is (some? (m/fetch s6 :specification (:id sp))) "while the specification it belonged to stays")))

(deftest a-field-edit-restates-the-far-end-whose-completeness-it-moved
  (let [[store mid]             (s/with-model)
        {s1 :store cmd :result} (s/ok store r/create-element {:model mid :name "Cmd" :element-type :command})
        {s2 :store evt :result} (s/ok s1 r/create-element {:model mid :name "Evt" :element-type :event})
        {s3 :store}             (s/ok s2 r/connect {:from (:id cmd) :to (:id evt)})
        {s4 :store}             (s/ok s3 r/add-field {:element (:id evt) :field {:name "total" :type :decimal}})]
    (is (false? (m/information-complete? s4 (m/fetch s4 :element (:id evt))))
        "the event's field is sourced by nothing yet")
    (testing "sourcing it from the command restates the event in the same delta"
      (let [{:keys [delta store]} (s/ok s4 r/add-field {:element (:id cmd)
                                                        :field {:name "total" :type :decimal}})]
        (is (true? (m/information-complete? store (m/fetch store :element (:id evt)))))
        (is (= [(:id cmd) (:id evt)] (mapv :id (:changes delta)))
            "the command, then the element whose verdict moved")))))

(deftest a-verdict-move-restates-the-slice-or-specification-it-belonged-to
  (testing "a placement restates the slice whose is_complete it moved"
    (let [[store mid]             (s/with-model)
          {s1 :store cmd :result} (s/ok store r/create-element {:model mid :name "Cmd" :element-type :command})
          {s2 :store tl :result}  (s/ok s1 r/create-timeline {:model mid :title "T"})
          {s3 :store sl :result}  (s/ok s2 r/add-slice {:timeline (:id tl) :title "S"
                                                        :slice-type :state_change :index 0})]
      (is (false? (:is_complete (m/canonical-entity s3 :slice (:id sl))))
          "an empty slice is not complete")
      (let [{:keys [delta store]} (s/ok s3 r/place-element {:slice (:id sl) :element (:id cmd)})]
        (is (true? (:is_complete (m/canonical-entity store :slice (:id sl)))))
        (is (= [:placement :slice] (mapv :type (:changes delta))))
        (is (true? (get-in (second (:changes delta)) [:entity :is_complete]))
            "the slice change carries its new verdict"))))
  (testing "a step restates the specification whose is_complete it moved"
    (let [[store mid]             (s/with-model)
          {s1 :store cmd :result} (s/ok store r/create-element {:model mid :name "Cmd" :element-type :command})
          {s2 :store tl :result}  (s/ok s1 r/create-timeline {:model mid :title "T"})
          {s3 :store sl :result}  (s/ok s2 r/add-slice {:timeline (:id tl) :title "S"
                                                        :slice-type :state_change :index 0})
          {s4 :store sp :result}  (s/ok s3 r/add-specification {:slice (:id sl) :title "spec"})]
      (is (false? (:is_complete (m/canonical-entity s4 :specification (:id sp)))))
      (let [{:keys [delta store]} (s/ok s4 r/add-spec-step {:spec (:id sp) :clause :when_step
                                                            :element (:id cmd) :index 0})]
        (is (true? (:is_complete (m/canonical-entity store :specification (:id sp)))))
        (is (= [:spec-step :specification] (mapv :type (:changes delta))))))))
