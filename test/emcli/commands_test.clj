(ns emcli.commands-test
  "Command-layer argument validation: a spec-declared Integer argument that is
  present but not a valid integer is rejected (:bad-argument) rather than
  silently coerced to nil."
  (:require [clojure.test :refer [deftest testing is]]
            [emcli.app :as app]
            [emcli.commands :as cmd]
            [emcli.model :as m]
            [emcli.rules :as r]
            [emcli.wireframe :as wf]))

(deftest non-integer-int-arg-is-rejected
  (let [a (app/new-app "M")]
    (testing "registry command: a non-numeric --index is a bad-argument error"
      (let [res (cmd/run a "create-swimlane" {:name "X" :index "abc"})]
        (is (= :bad-argument (:error res)))
        (is (= ["index"] (:args res)))
        (is (zero? (count (m/swimlanes (app/store a) (app/model-id a))))
            "nothing was committed")))
    (testing "an id argument is validated too"
      (is (= :bad-argument (:error (cmd/run a "add-slice"
                                            {:timeline "nope" :title "t" :kind "state_change" :index "0"})))))
    (testing "structured/composite command: bad :connection is rejected"
      (is (= :bad-argument (:error (cmd/run a "add-derivation"
                                            {:connection "xyz" :target "t" :from "a"})))))
    (testing "wireframe composite: bad :element is rejected, not leaked as a sentinel"
      (let [res (cmd/run a "add-wireframe-node-before"
                         {:element "abc" :before "a" :tag "event"})]
        (is (= :bad-argument (:error res)))
        (is (= ["element"] (:args res)))
        (is (= "expected an integer for: element" (:message res)))))
    (testing "a fractional value is not an integer"
      (is (= :bad-argument (:error (cmd/run a "create-swimlane" {:name "X" :index "1.5"})))))))

(deftest valid-integers-still-pass
  (let [a (app/new-app "M")]
    (testing "integer-as-string parses"
      (is (not (r/error? (cmd/run a "create-swimlane" {:name "A" :index "0"})))))
    (testing "negative integers parse (used by reorder)"
      (is (not (r/error? (cmd/run a "create-swimlane" {:name "B" :index "-3"})))))
    (testing "native integers pass through"
      (is (not (r/error? (cmd/run a "create-swimlane" {:name "C" :index 2})))))
    (is (= ["B" "A" "C"] (map :name (m/swimlanes (app/store a) (app/model-id a)))))))

(deftest absent-required-int-is-still-missing-not-bad
  (testing "a missing (not malformed) required int is reported as missing-args"
    (let [a (app/new-app "M")
          res (cmd/run a "create-swimlane" {:name "X"})]
      (is (= :missing-args (:error res)))
      (is (= [:index] (:missing res))))))

;; --- explicit --id (CLI-level: string coercion + conflict surfacing) ------

(deftest explicit-id-flows-through-cmd-run
  (let [a (app/new-app "M")]
    (testing "string --id coerces to int and is honored"
      (let [res (cmd/run a "create-timeline" {:title "T" :id "9"})]
        (is (not (r/error? res)))
        (is (= 9 (:id (:result res))))))
    (testing "conflicting id is rejected, same as any other rule error"
      (let [res (cmd/run a "create-swimlane" {:name "L" :index 0 :id "9"})]
        (is (= :id-conflict (:error res)))))
    (testing "non-integer --id is a bad-argument, same as any other int param"
      (is (= :bad-argument (:error (cmd/run a "create-element" {:name "E" :kind "command" :id "nope"})))))))

;; --- add-field flat-flag API ------------------------------------------------

(deftest add-field-accepts-flat-flags
  (let [a   (app/new-app "M")
        eid (:id (:result (cmd/run a "create-element" {:name "Order" :kind "event"})))]
    (testing "adds a field from flat --name --type flags"
      (let [res (cmd/run a "add-field" {:element eid :name "orderId" :type "uuid"})]
        (is (not (r/error? res)))
        (is (= [{:name "orderId" :type :uuid}]
               (map #(select-keys % [:name :type])
                    (:fields (:result res)))))))
    (testing "optional --cardinality is passed through"
      (let [res (cmd/run a "add-field" {:element eid :name "items" :type "string" :cardinality "list"})]
        (is (not (r/error? res)))
        (is (= :list (:cardinality (first (filter #(= "items" (:name %))
                                                  (:fields (:result res)))))))))
    (testing "missing --name is a missing-args error"
      (is (= :missing-args (:error (cmd/run a "add-field" {:element eid :type "uuid"})))))
    (testing "missing --type is a missing-args error"
      (is (= :missing-args (:error (cmd/run a "add-field" {:element eid :name "x"})))))
    (testing "missing --element is a missing-args error"
      (is (= :missing-args (:error (cmd/run a "add-field" {:name "x" :type "uuid"})))))))

;; --- wireframe add-node --text (BUGS.md item 6) -----------------------------
;; AddWireframeNode declares text as its own input, separate from attributes, so
;; the CLI must deliver --text to the rule rather than smuggle it into the
;; attribute map, where parse-node-attrs correctly rejects it for text tags.

(defn- screen-app
  "An app with one screen element; returns [app screen-element-id]."
  []
  (let [a   (app/new-app "M")
        eid (:id (:result (cmd/run a "create-element" {:name "OrderList" :kind "screen"})))]
    [a eid]))

(defn- node-attrs
  "The attribute map of a wireframe node vector (its map child that is not the
  id map), or nil."
  [node]
  (some #(when (and (map? %) (not (contains? % :-id))) %) (rest node)))

(deftest add-wireframe-node-accepts-text-for-text-tags
  (let [[a eid] (screen-app)]
    (testing "a text-children tag takes --text as content, not as an attribute"
      (let [res  (cmd/run a "add-wireframe-node" {:element eid :tag "h1" :text "Your orders"})
            node (wf/find-node (:wireframe (:result res)) "n2")]
        (is (not (r/error? res)))
        (is (= :h1 (first node)))
        (is (some #(= "Your orders" %) node)
            "the text lands as the node's string child")
        (is (nil? (node-attrs node))
            "text must not become a node attribute")))))

(deftest add-wireframe-node-before-accepts-text-for-text-tags
  (let [[a eid] (screen-app)]
    (cmd/run a "add-wireframe-node" {:element eid :tag "col"})
    (testing "the insert-before variant delivers --text the same way"
      (let [res  (cmd/run a "add-wireframe-node-before"
                          {:element eid :before "n2" :tag "span" :text "Hi"})
            node (wf/find-node (:wireframe (:result res)) "n3")]
        (is (not (r/error? res)))
        (is (= :span (first node)))
        (is (some #(= "Hi" %) node))
        (is (nil? (node-attrs node)))))))

(deftest add-wireframe-node-still-rejects-text-for-other-tags
  (let [[a eid] (screen-app)]
    (testing "--text on a tag that takes neither text children nor a text attribute"
      (let [res (cmd/run a "add-wireframe-node" {:element eid :tag "col" :text "nope"})]
        (is (= :invalid-value (:error res)))
        (is (= "unknown attribute :text for :col" (:message res)))))))

(deftest add-wireframe-node-keeps-text-as-attribute-for-alert
  (let [[a eid] (screen-app)]
    (testing ":alert declares text in its attrs, so --text stays an attribute"
      (let [res  (cmd/run a "add-wireframe-node" {:element eid :tag "alert"
                                                  :text "Heads up" :type "warning"})
            node (wf/find-node (:wireframe (:result res)) "n2")]
        (is (not (r/error? res)))
        (is (= "Heads up" (:text (node-attrs node))))
        (is (= :warning (:type (node-attrs node))))))))

;; --- wireframe add-node --parent addressing (BUGS.md item 3) ----------------
;; A named --parent must exist; only omitting it (or naming the root) appends
;; at the root. See AddWireframeNode's `requires` in event-model.allium.

(defn- canvas-child-ids
  "Ids of the immediate child nodes of `node-id` in the screen `el`."
  [el node-id]
  (map #(some :-id %) (filter vector? (rest (wf/find-node (:wireframe el) node-id)))))

(deftest add-wireframe-node-rejects-unknown-parent
  (let [[a eid] (screen-app)]
    (testing "a non-existent --parent is rejected like an unknown sibling"
      (let [res (cmd/run a "add-wireframe-node" {:element eid :tag "button"
                                                 :parent "nX" :label "Save"})]
        (is (= :not-found (:error res)))
        (is (= :wireframe-node (:type res)))
        (is (= "nX" (:id res)))
        (is (= "node nX does not exist" (:message res)))))))

(deftest add-wireframe-node-parent-addressing-still-works
  (let [[a eid] (screen-app)]
    (testing "an explicit root id appends at the root"
      (let [res (cmd/run a "add-wireframe-node" {:element eid :tag "col" :parent "n1"})]
        (is (not (r/error? res)))
        (is (= ["n2"] (canvas-child-ids (:result res) "n1")))))
    (testing "omitting --parent appends at the root"
      (let [res (cmd/run a "add-wireframe-node" {:element eid :tag "col"})]
        (is (not (r/error? res)))
        (is (= ["n2" "n3"] (canvas-child-ids (:result res) "n1")))))
    (testing "an existing nested parent still works"
      (let [res  (cmd/run a "add-wireframe-node" {:element eid :tag "h1"
                                                  :parent "n2" :text "Hello"})
            node (wf/find-node (:wireframe (:result res)) "n4")]
        (is (not (r/error? res)))
        (is (= ["n4"] (canvas-child-ids (:result res) "n2")))
        (is (= :h1 (first node)))
        (is (some #(= "Hello" %) node))))))

;; --- wireframe set-attr coercion (BUGS.md item 2) ---------------------------
;; SetWireframeAttr takes its value as operator text, but the rule stores that
;; value verbatim and re-validates the whole tree; the adapter must therefore
;; hand it a value that already carries the type the node's tag declares, the
;; same treatment AddWireframeNode gives the same attributes.

(defn- set-attr-app
  "An app with a screen whose canvas holds a :button (n2), an :input (n3) and a
  :dropdown (n4); returns [app screen-element-id]."
  []
  (let [[a eid] (screen-app)]
    (cmd/run a "add-wireframe-node" {:element eid :tag "button" :label "Save"})
    (cmd/run a "add-wireframe-node" {:element eid :tag "input" :label "Name"})
    (cmd/run a "add-wireframe-node" {:element eid :tag "dropdown" :options "draft"})
    [a eid]))

(defn- stored-node-attrs
  "The attribute map the store currently holds for node `node-id` of element
  `eid` (nil when the node carries no attributes)."
  [a eid node-id]
  (let [el (m/fetch (app/store a) :element eid)]
    (node-attrs (wf/find-node (:wireframe el) node-id))))

(deftest set-wireframe-attr-coerces-keyword-values
  (let [[a eid] (set-attr-app)]
    (testing "a keyword-typed attribute can be set from operator text"
      (let [res (cmd/run a "set-wireframe-attr"
                         {:element eid :node "n2" :attr "variant" :value "primary"})]
        (is (not (r/error? res)))
        (is (= :primary (:variant (stored-node-attrs a eid "n2")))
            "the stored node carries the keyword, not the raw string")))
    (testing "a value outside the attribute's allowed set is rejected by coercion"
      (let [res (cmd/run a "set-wireframe-attr"
                         {:element eid :node "n2" :attr "variant" :value "bogus"})]
        (is (= :invalid-value (:error res)))
        (is (re-find #"^variant value 'bogus' not in allowed set " (:message res)))
        (is (= :primary (:variant (stored-node-attrs a eid "n2")))
            "the store is left untouched")))))

(deftest set-wireframe-attr-coerces-boolean-values
  (let [[a eid] (set-attr-app)]
    (testing "true coerces to the boolean true"
      (is (not (r/error? (cmd/run a "set-wireframe-attr"
                                  {:element eid :node "n3" :attr "required" :value "true"}))))
      (is (true? (:required (stored-node-attrs a eid "n3")))))
    (testing "false coerces to the boolean false"
      (is (not (r/error? (cmd/run a "set-wireframe-attr"
                                  {:element eid :node "n2" :attr "disabled" :value "false"}))))
      (is (false? (:disabled (stored-node-attrs a eid "n2")))))
    (testing "text that is not a boolean is rejected"
      (let [res (cmd/run a "set-wireframe-attr"
                         {:element eid :node "n3" :attr "required" :value "maybe"})]
        (is (= :invalid-value (:error res)))
        (is (= "required must be a boolean (true/false)" (:message res)))
        (is (true? (:required (stored-node-attrs a eid "n3")))
            "the store is left untouched")))))

(deftest set-wireframe-attr-coerces-list-values
  (let [[a eid] (set-attr-app)]
    (testing "comma-separated text becomes a vector of strings"
      (let [res (cmd/run a "set-wireframe-attr"
                         {:element eid :node "n4" :attr "options" :value "a,b"})]
        (is (not (r/error? res)))
        (is (= ["a" "b"] (:options (stored-node-attrs a eid "n4"))))))))

(deftest set-wireframe-attr-still-sets-string-values
  (let [[a eid] (set-attr-app)]
    (testing "a string-typed attribute still takes the text as-is"
      (let [res (cmd/run a "set-wireframe-attr"
                         {:element eid :node "n2" :attr "label" :value "Save it"})]
        (is (not (r/error? res)))
        (is (= "Save it" (:label (stored-node-attrs a eid "n2"))))))))

(deftest set-wireframe-attr-missing-node-keeps-the-rule-rejection
  (let [[a eid] (set-attr-app)
        res     (cmd/run a "set-wireframe-attr"
                         {:element eid :node "n99" :attr "label" :value "X"})]
    (testing "an unknown node is not pre-empted by the adapter"
      (is (= :not-found (:error res)))
      (is (= :wireframe-node (:type res)))
      (is (= "node n99 does not exist" (:message res))))))

(deftest set-wireframe-attr-leaves-unadmitted-attributes-to-the-rule
  (let [[a eid] (set-attr-app)
        ;; :align is not one of :button's attributes, so there is no schema
        ;; entry to coerce against: the raw text goes through unchanged and the
        ;; rule's own tree validation reports it — exactly as before.
        res     (cmd/run a "set-wireframe-attr"
                         {:element eid :node "n2" :attr "align" :value "center"})]
    (testing "an attribute the node's tag does not admit is still the rule's call"
      (is (= :invalid-wireframe (:error res)))
      (is (= "wireframe validation failed: unknown attribute :align" (:message res))))))

;; --- field removal vs a stored layout (BUGS.md item 5) ----------------------
;; RemoveField now guards the removal against the screen's stored layout, so a
;; CLI remove-field that would drop a field a node still names surfaces the
;; rule's :field-referenced error instead of silently stranding the reference.

(deftest remove-field-rejects-a-field-the-layout-names
  (let [[a eid] (screen-app)]
    (cmd/run a "add-field" {:element eid :name "searchTerm" :type "string"})
    (cmd/run a "add-wireframe-node" {:element eid :tag "input" :field-name "searchTerm"})
    (testing "the removal is refused, naming the field and the referring node"
      (let [res (cmd/run a "remove-field" {:element eid :name "searchTerm"})]
        (is (= :field-referenced (:error res)))
        (is (= "searchTerm" (:field res)))
        (is (= ["n2"] (:nodes res)))
        (is (= "field searchTerm is referenced by layout node(s) n2 and cannot be removed"
               (:message res)))))
    (testing "nothing was committed: the field is still declared"
      (is (= ["searchTerm"] (map :name (:fields (m/fetch (app/store a) :element eid))))))
    (testing "a field no node names is still removable"
      (cmd/run a "add-field" {:element eid :name "page" :type "int"})
      (let [res (cmd/run a "remove-field" {:element eid :name "page"})]
        (is (not (r/error? res)))
        (is (= ["searchTerm"] (map :name (:fields (:result res))))))
      (testing "and the layout still names the field it was about"
        (is (= ["n2"] (map :node-id
                           (wf/field-references
                            (:wireframe (m/fetch (app/store a) :element eid))))))))))

;; NameResolution.resolve (event-model.allium): batched name -> candidate
;; lookup, so an LLM never has to pull the whole model to resolve a name.
(deftest resolve-names-test
  (let [a  (app/new-app "M")
        tl (:id (:result (cmd/run a "create-timeline" {:title "Checkout"})))]
    (cmd/run a "add-slice" {:timeline tl :title "Baz" :kind "state_change" :index 0})
    (cmd/run a "create-element" {:name "Snaz" :kind "read_model"})
    (cmd/run a "create-element" {:name "Snazzz" :kind "read_model"})

    (testing "exact match wins outright, carries its breadcrumb"
      (let [[res] (cmd/resolve-names a [{:name "Baz"}])]
        (is (= [:exact] (map :match_type (:candidates res))))
        (is (= "Checkout" (get-in res [:candidates 0 :breadcrumb :timeline_title])))
        (is (= 1 (:total_matches res)))
        (is (false? (:truncated res)))))

    (testing "substring is only tried once exact yields nothing"
      (let [[res] (cmd/resolve-names a [{:name "naz"}])]
        (is (= #{:substring} (set (map :match_type (:candidates res)))))
        (is (= #{"Snaz" "Snazzz"} (set (map :name (:candidates res)))))))

    (testing "near-miss only fires when neither exact nor substring matched anything"
      (let [[res] (cmd/resolve-names a [{:name "Foobar"}])]
        (is (seq (:candidates res)))
        (is (every? #(= :near_miss (:match_type %)) (:candidates res)))
        (is (apply <= (map :distance (:candidates res)))
            "near-miss candidates are ordered nearest-first")))

    (testing "kind_hint ranks matches of the hinted kind first, never filters others out"
      (cmd/run a "create-swimlane" {:name "Snaz" :index 0})
      (let [[res] (cmd/resolve-names a [{:name "Snaz" :kind_hint "swimlane"}])]
        (is (= :swimlane (get-in res [:candidates 0 :kind])))
        (is (= #{:swimlane :element} (set (map :kind (:candidates res))))
            "the element match is still present, just ranked after the hint")))

    (testing "zero queries is a no-op, not an error"
      (is (= [] (cmd/resolve-names a []))))))

;; --- placement reorder/remove keyed on (slice, element) ---------------------
;; ReorderPlacement/RemovePlacement address a placement by the (slice, element)
;; pair, so the CLI takes --slice/--element, and reorder takes a single relative
;; mover: --position front|back, or --before/--after naming a sibling element.

(defn- placed-app
  "An app with one slice holding placements of two events; returns
  [app slice-id {name -> element-id}]."
  []
  (let [a  (app/new-app "M")
        sl (:id (:result (cmd/run a "add-slice"
                                  {:timeline (:id (:result (cmd/run a "create-timeline" {:title "T"})))
                                   :title "S" :kind "state_change" :index 0})))
        a1 (:id (:result (cmd/run a "create-element" {:name "A" :kind "event"})))
        a2 (:id (:result (cmd/run a "create-element" {:name "B" :kind "event"})))]
    (cmd/run a "place-element" {:slice sl :element a1})
    (cmd/run a "place-element" {:slice sl :element a2})
    [a sl {"A" a1 "B" a2}]))

(defn- placement-order
  "The element names of a slice's placements, in display order, in `a`."
  [a sl]
  (mapv #(:name (m/placement-element (app/store a) %)) (m/placements (app/store a) sl)))

(deftest placement-reorder-and-remove-are-keyed-on-slice-and-element
  (let [[a sl ids] (placed-app)]
    (testing "reorder takes --slice/--element plus one move selector"
      (let [res (cmd/run a "reorder-placement" {:slice sl :element (ids "B") :position "front"})]
        (is (not (r/error? res)))
        (is (= (ids "B") (:element (:result res))))
        (is (= ["B" "A"] (placement-order a sl)))))
    (testing "--before names an element to sit before"
      (let [res (cmd/run a "reorder-placement" {:slice sl :element (ids "A") :before (ids "B")})]
        (is (not (r/error? res)))
        (is (= ["A" "B"] (placement-order a sl)))))
    (testing "an out-of-range --position is rejected by the rule"
      (is (= :invalid-value (:error (cmd/run a "reorder-placement"
                                             {:slice sl :element (ids "A") :position "middle"})))))
    (testing "exactly one move selector is required"
      (is (= :invalid-value (:error (cmd/run a "reorder-placement"
                                             {:slice sl :element (ids "A")})))))
    (testing "remove addresses the placement by slice+element"
      (let [res (cmd/run a "remove-placement" {:slice sl :element (ids "A")})]
        (is (not (r/error? res)))
        (is (= ["B"] (placement-order a sl)))))
    (testing "a non-integer --slice is a bad-argument, like any other int param"
      (is (= :bad-argument (:error (cmd/run a "reorder-placement"
                                            {:slice "x" :element (ids "B") :position "front"})))))))

(deftest add-field-carries-optional-and-subfields
  ;; AddField takes a whole Field; the flat flag set addresses one level at a
  ;; time, so a subfield is authored by naming the field to nest it under.
  (let [a  (app/new-app "M")
        el (:id (:result (cmd/run a "create-element" {:name "E" :kind "command"})))
        el-field (fn [] (m/fetch (app/store a) :element el))]
    (testing "optional and cardinality reach the stored field"
      (cmd/run a "add-field" {:element el :name "id" :type "uuid"
                              :optional true :cardinality "list"})
      (is (= {:name "id" :type :uuid :optional true :cardinality :list :subfields []}
             (first (:fields (el-field))))))
    (testing "a subfield is nested into an existing field"
      (is (not (r/error? (cmd/run a "add-field" {:element el :name "amount" :type "double"
                                                 :subfield-of "id"}))))
      (is (= [["amount" :double]]
             (mapv (juxt :name :type) (:subfields (first (:fields (el-field))))))))
    (testing "naming a field the element does not have is rejected"
      (is (= :not-found (:error (cmd/run a "add-field" {:element el :name "x" :type "int"
                                                        :subfield-of "nope"})))))))
