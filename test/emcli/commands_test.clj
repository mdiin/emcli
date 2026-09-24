(ns emcli.commands-test
  "Command-layer argument validation: a spec-declared Integer argument that is
  present but not a valid integer is rejected (:bad-argument) rather than
  silently coerced to nil."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
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
                                            {:timeline "nope" :title "t" :slice-type "state_change"}))))
      (is (= :bad-argument (:error (cmd/run a "add-slice"
                                            {:timeline 1 :title "t" :slice-type "state_change" :before "abc"})))
          "non-integer :before is also bad-argument"))
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
      (is (= :bad-argument (:error (cmd/run a "create-element" {:name "E" :element-type "command" :id "nope"})))))))

;; --- add-field flat-flag API ------------------------------------------------

(deftest add-field-accepts-flat-flags
  (let [a   (app/new-app "M")
        eid (:id (:result (cmd/run a "create-element" {:name "Order" :element-type "event"})))]
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
        eid (:id (:result (cmd/run a "create-element" {:name "OrderList" :element-type "screen"})))]
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
        ;; RejectedLayoutEditNamesRemedy: the problem comes first, the remedy after
        (is (str/starts-with? (:message res)
                              "unknown attribute :text for :col (see: emcli wireframe tags --tag col)"))))))

(deftest add-wireframe-node-points-an-unknown-tag-at-the-tag-list
  (let [[a eid] (screen-app)
        res     (cmd/run a "add-wireframe-node" {:element eid :tag "card"})]
    (is (= :invalid-value (:error res)))
    (is (= "unknown tag :card (see: emcli wireframe tags)" (:message res)))))

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
        (is (str/starts-with? (:message res) "required must be a boolean (true/false)"))
        (testing "and names what the tag admits (RejectedLayoutEditNamesRemedy)"
          (is (re-find #"--required true\|false" (:message res))))
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
      ;; RejectedLayoutEditNamesRemedy: the problem comes first, the remedy after
      (is (str/starts-with? (:message res)
                            (str "wireframe validation failed: unknown attribute :align for :button"
                                 " (see: emcli wireframe tags --tag button)"))))))

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
    (cmd/run a "add-slice" {:timeline tl :title "Baz" :slice-type "state_change"})
    (cmd/run a "create-element" {:name "Snaz" :element-type "read_model"})
    (cmd/run a "create-element" {:name "Snazzz" :element-type "read_model"})

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
                                   :title "S" :slice-type "state_change"})))
        a1 (:id (:result (cmd/run a "create-element" {:name "A" :element-type "event"})))
        a2 (:id (:result (cmd/run a "create-element" {:name "B" :element-type "event"})))]
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
        el (:id (:result (cmd/run a "create-element" {:name "E" :element-type "command"})))
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

;; --- a nesting level is a name, so it resolves by name equality ---------------

(deftest add-field-resolves-subfield-of-by-name-equality
  (let [a   (app/new-app "M")
        eid (:id (:result (cmd/run a "create-element" {:name "E" :element-type "command"})))]
    (cmd/run a "add-field" {:element eid :name "orderId" :type "uuid"})
    (testing "a differently-spelled nesting level names the field that exists"
      (let [res (cmd/run a "add-field" {:element eid :name "placedAt" :type "date_time"
                                        :subfield-of "ORDERID"})
            el  (:result res)]
        (is (not (:error res)))
        (is (= ["placedAt"] (mapv :name (:subfields (first (:fields el))))))))
    (testing "while a level the element does not carry is still rejected"
      (is (some? (:error (cmd/run a "add-field" {:element eid :name "x" :type "uuid"
                                                 :subfield-of "nosuchfield"})))))))

(deftest a-padded-name-still-resolves-exactly
  (let [a (app/new-app "M")]
    (cmd/run a "create-element" {:name " Order" :element-type "event"})
    (is (= 1 (count (cmd/query-model a "element:Order")))
        "a name root matches by the model's name equality, surrounding whitespace included")))

;; --- unknown-parameter rejection -------------------------------------------

(deftest unknown-params-rejected-for-registry-command
  (let [a (app/new-app "M")]
    (testing "an extra --slice flag on create-element is rejected before reaching the rule"
      (let [res (cmd/run a "create-element" {:name "Foo" :element-type "command" :slice 15})]
        (is (= :unknown-params (:error res)))
        (is (= ["slice"] (:params res)))
        (is (re-find #"slice" (:message res)))))
    (testing "multiple unknown flags are all listed"
      (let [res (cmd/run a "create-element" {:name "Foo" :element-type "command" :slice 15 :bogus "x"})]
        (is (= :unknown-params (:error res)))
        (is (= ["bogus" "slice"] (sort (:params res))))))
    (testing "nothing was committed on rejection"
      (is (zero? (count (emcli.model/elements (app/store a) (app/model-id a))))))))

(deftest unknown-params-rejected-for-composite-command
  (let [a   (app/new-app "M")
        eid (:id (:result (cmd/run a "create-element" {:name "Order" :element-type "event"})))]
    (testing "an extra --slice flag on add-field is rejected"
      (let [res (cmd/run a "add-field" {:element eid :name "orderId" :type "uuid" :slice 3})]
        (is (= :unknown-params (:error res)))
        (is (= ["slice"] (:params res)))))
    (testing "the field was not added"
      (is (empty? (:fields (emcli.model/fetch (app/store a) :element eid)))))))

(deftest server-key-is-not-flagged-as-unknown
  (let [a (app/new-app "M")]
    (testing ":server is always allowed and does not trigger unknown-params"
      (let [res (cmd/run a "create-element" {:name "Foo" :element-type "command"
                                             :server "http://localhost:8090"})]
        (is (not (r/error? res)))))))

(deftest valid-registry-call-unaffected-by-check
  (let [a (app/new-app "M")]
    (testing "a call with only known params is not affected by the guard"
      (let [res (cmd/run a "create-timeline" {:title "Timeline A"})]
        (is (not (r/error? res)))
        (is (= "Timeline A" (:title (:result res))))))))

(deftest open-wireframe-node-commands-accept-arbitrary-attr-flags
  (let [a   (app/new-app "M")
        eid (:id (:result (cmd/run a "create-element" {:name "Home" :element-type "screen"})))]
    (testing "add-wireframe-node passes extra flags as tag attrs without error"
      (let [res (cmd/run a "add-wireframe-node" {:element eid :tag "button" :label "Click me"})]
        (is (not (r/error? res)))))
    (testing "add-wireframe-node-before also accepts arbitrary flags"
      (let [row-result (cmd/run a "add-wireframe-node" {:element eid :tag "row"})
            ;; The wireframe is [:canvas {:-id "n1"} [:row {:-id "n2"}] ...];
            ;; find the :-id of the last child (the row we just added).
            wf         (:wireframe (:result row-result))
            row-id     (get-in (last wf) [1 :-id])
            res        (cmd/run a "add-wireframe-node-before"
                                {:element eid :before row-id :tag "button" :label "Go"})]
        (is (not (r/error? res)))))))

;; --- wireframe move-node / apply (MoveWireframeNode, ReplaceWireframe) --------
;; The flat commands behind `wireframe move-node` and `wireframe apply`. apply
;; takes the target as the text `wireframe show` prints (wf/format-tree): a line
;; prefixed [nX] keeps that node, an unprefixed line is a new node.

(defn- form-app
  "An app with a screen whose layout reproduces the transcript's misordered
  form: n1 :canvas > n2 :input Password, n3 :button Submit, n4 :input Email.
  Returns [app screen-element-id]."
  []
  (let [[a eid] (screen-app)]
    (cmd/run a "add-wireframe-node" {:element eid :tag "input" :type "password" :label "Password"})
    (cmd/run a "add-wireframe-node" {:element eid :tag "button" :label "Submit"})
    (cmd/run a "add-wireframe-node" {:element eid :tag "input" :type "email" :label "Email"})
    [a eid]))

(defn- stored-layout [a eid] (:wireframe (m/fetch (app/store a) :element eid)))

(deftest move-wireframe-node-command-reorders-in-one-call
  (let [[a eid] (form-app)
        res     (cmd/run a "move-wireframe-node" {:element (str eid) :node "n4" :before "n2"})]
    (is (not (r/error? res)) (pr-str res))
    (is (= ["n4" "n2" "n3"] (canvas-child-ids (:result res) "n1")))
    (is (= "Email" (:label (stored-node-attrs a eid "n4"))) "the moved node keeps its id")
    (testing "--parent appends into a container"
      (cmd/run a "add-wireframe-node" {:element eid :tag "col"})
      (let [res (cmd/run a "move-wireframe-node" {:element eid :node "n3" :parent "n5"})]
        (is (not (r/error? res)) (pr-str res))
        (is (= ["n3"] (canvas-child-ids (:result res) "n5")))))))

(deftest move-wireframe-node-command-checks-its-options
  (let [[a eid] (form-app)]
    (is (= :missing-args (:error (cmd/run a "move-wireframe-node" {:element eid :before "n2"}))))
    (is (= :bad-argument (:error (cmd/run a "move-wireframe-node"
                                          {:element "abc" :node "n4" :before "n2"}))))
    (is (= :unknown-params (:error (cmd/run a "move-wireframe-node"
                                            {:element eid :node "n4" :before "n2" :tag "col"}))))
    (is (= :invalid-value (:error (cmd/run a "move-wireframe-node"
                                           {:element eid :node "n4" :before "n2" :parent "n1"}))))))

(deftest replace-wireframe-command-applies-the-show-text
  (let [[a eid] (form-app)
        res     (cmd/run a "replace-wireframe"
                         {:element (str eid)
                          :tree    (str "[n1] :canvas\n"
                                        "  :col\n"
                                        "    [n4] :input  {:type :email, :label \"Email\"}\n"
                                        "    [n2] :input  {:type :password, :label \"Password\"}\n"
                                        "    [n3] :button  {:label \"Submit\", :variant :primary}")})]
    (is (not (r/error? res)) (pr-str res))
    (is (= ["n5"] (canvas-child-ids (:result res) "n1")) "the new col takes a fresh id")
    (is (= ["n4" "n2" "n3"] (canvas-child-ids (:result res) "n5")))
    (is (= :primary (:variant (stored-node-attrs a eid "n3"))))))

(deftest replace-wireframe-command-round-trips-the-show-text
  ;; reading a layout, handing it back unchanged, changes nothing
  (let [[a eid] (form-app)
        _       (cmd/run a "add-wireframe-node" {:element eid :tag "row" :gap "md"})
        _       (cmd/run a "add-wireframe-node" {:element eid :tag "h1" :parent "n5" :text "Log in"})
        before  (stored-layout a eid)
        res     (cmd/run a "replace-wireframe" {:element eid :tree (wf/format-tree before)})]
    (is (not (r/error? res)) (pr-str res))
    (is (= before (stored-layout a eid)))))

(deftest replace-wireframe-command-accepts-values-as-show-prints-them
  ;; `wireframe show` reads the layout back through JSON, so a keyword value
  ;; arrives as a string; applying that text must still type it
  (let [[a eid] (form-app)
        res     (cmd/run a "replace-wireframe"
                         {:element eid
                          :tree    "[n1] :canvas\n  [n3] :button  {:label \"Submit\", :variant \"primary\"}"})]
    (is (not (r/error? res)) (pr-str res))
    (is (= :primary (:variant (stored-node-attrs a eid "n3"))))))

(deftest replace-wireframe-command-names-the-line-of-a-parse-error
  (let [[a eid] (form-app)
        before  (stored-layout a eid)
        res     (cmd/run a "replace-wireframe"
                         {:element eid :tree "[n1] :canvas\n  [n2] :input\n        :divider"})]
    (is (= :parse-error (:error res)))
    (is (some #(= 3 (:line %)) (:errors res)))
    (is (re-find #"line 3" (:message res)))
    (is (= before (stored-layout a eid)) "nothing was applied")))

(deftest replace-wireframe-command-reports-every-problem
  (let [[a eid] (form-app)
        before  (stored-layout a eid)
        res     (cmd/run a "replace-wireframe"
                         {:element eid
                          :tree    "[n1] :canvas\n  [n99] :divider\n  :button  {:variant :primary}"})]
    (is (= :invalid-wireframe (:error res)))
    (is (str/includes? (:message res) "n99"))
    (is (str/includes? (:message res) "label is required"))
    (is (= before (stored-layout a eid)) "all or nothing: the layout is unchanged")))

(deftest replace-wireframe-command-checks-its-options
  (let [[a eid] (form-app)]
    (is (= :missing-args (:error (cmd/run a "replace-wireframe" {:element eid}))))
    (is (= :bad-argument (:error (cmd/run a "replace-wireframe" {:element "x" :tree "[n1] :canvas"}))))
    (is (= :unknown-params (:error (cmd/run a "replace-wireframe"
                                            {:element eid :tree "[n1] :canvas" :node "n2"}))))))

(deftest rejected-move-changes-nothing
  (let [[a eid] (form-app)
        before  (stored-layout a eid)]
    (doseq [opts [{:node "n1" :before "n2"} {:node "n2" :parent "n3"} {:node "n99" :parent "n1"}]]
      (is (r/error? (cmd/run a "move-wireframe-node" (assoc opts :element eid))) (pr-str opts))
      (is (= before (stored-layout a eid)) (pr-str opts)))))

;; --- LayoutEditRevealsResult: the touched node --------------------------------
;; Every successful node edit names the node it created, moved or changed, so
;; the CLI can print it above the resulting tree.

(deftest wireframe-mutations-name-the-touched-node
  (let [[a eid] (form-app)]
    (is (= "n5" (:node (cmd/run a "add-wireframe-node" {:element eid :tag "h1" :text "Log in"}))))
    (is (= "n6" (:node (cmd/run a "add-wireframe-node-before"
                                {:element eid :before "n2" :tag "divider"}))))
    (is (= "n4" (:node (cmd/run a "move-wireframe-node" {:element eid :node "n4" :before "n2"}))))
    (is (= "n3" (:node (cmd/run a "set-wireframe-attr"
                                {:element eid :node "n3" :attr "variant" :value "primary"}))))
    (is (= "n5" (:node (cmd/run a "set-wireframe-text" {:element eid :node "n5" :text "Sign in"}))))
    (is (= "n6" (:node (cmd/run a "delete-wireframe-node" {:element eid :node "n6"}))))))

;; --- RejectedLayoutEditNamesRemedy --------------------------------------------
;; A rejection for an attribute the tag does not admit, a required attribute left
;; out, or a value outside the allowed set lists what the tag admits (required
;; ones marked, allowed values spelled out) and, where it can be derived, the
;; corrected command.

(defn- try-line
  "The `try:` line of a rejection message, trimmed, or nil."
  [message]
  (some #(when (re-find #"^\s*try:" %) (str/trim %)) (str/split-lines (str message))))

(defn- names-button-remedy? [message]
  (and (string? message)
       (re-find #"--label \(required\)" message)
       (re-find #"--variant primary\|secondary\|ghost\|danger" message)
       (str/includes? message "--disabled")
       (str/includes? message "--command-input")))

(deftest rejected-add-node-names-the-remedy-for-an-unadmitted-attribute
  (let [[a eid] (screen-app)
        res     (cmd/run a "add-wireframe-node" {:element eid :tag "button" :text "Submit"})
        msg     (:message res)
        suggestion (try-line msg)]
    (is (= :invalid-value (:error res)))
    (is (str/starts-with? msg "unknown attribute :text for :button (see: emcli wireframe tags --tag button)"))
    (is (names-button-remedy? msg) msg)
    (is (some? suggestion) msg)
    (is (re-find (re-pattern (str "^try:\\s+emcli wireframe add-node --element " eid " --tag button\\b"))
                 (str suggestion)))
    (is (re-find #"--label\b" (str suggestion)))
    (is (not (str/includes? (str suggestion) "--text")) "the corrected command drops what was wrong")))

(deftest rejected-add-node-names-the-remedy-for-a-missing-required-attribute
  (let [[a eid] (screen-app)]
    (testing "one required attribute"
      (let [msg (:message (cmd/run a "add-wireframe-node" {:element eid :tag "button" :variant "primary"}))
            suggestion (str (try-line msg))]
        (is (str/starts-with? msg "label is required for :button"))
        (is (names-button-remedy? msg) msg)
        (is (re-find (re-pattern (str "^try:\\s+emcli wireframe add-node --element " eid " --tag button\\b")) suggestion))
        (is (re-find #"--label\b" suggestion))))
    (testing "every required flag appears in the corrected command"
      (let [msg (:message (cmd/run a "add-wireframe-node" {:element eid :tag "icon-button" :icon "trash"}))
            suggestion (str (try-line msg))]
        (is (re-find #"--icon \(required\)" msg))
        (is (re-find #"--aria-label \(required\)" msg))
        (is (re-find #"--tag icon-button\b" suggestion))
        (is (re-find #"--icon\b" suggestion))
        (is (re-find #"--aria-label\b" suggestion))))))

(deftest rejected-add-node-names-the-remedy-for-a-value-outside-the-allowed-set
  (let [[a eid] (screen-app)
        res     (cmd/run a "add-wireframe-node" {:element eid :tag "button" :label "Go" :variant "bogus"})]
    (is (= :invalid-value (:error res)))
    (is (str/includes? (:message res) "variant value 'bogus' not in allowed set"))
    (is (names-button-remedy? (:message res)) (:message res))))

(deftest rejected-add-node-before-names-the-remedy
  (let [[a eid] (form-app)
        msg     (:message (cmd/run a "add-wireframe-node-before"
                                   {:element eid :before "n2" :tag "button" :text "Back"}))
        suggestion (str (try-line msg))]
    (is (str/starts-with? msg "unknown attribute :text for :button"))
    (is (names-button-remedy? msg) msg)
    (is (re-find (re-pattern (str "^try:\\s+emcli wireframe add-node-before --element " eid
                                  " --before n2 --tag button\\b"))
                 suggestion))
    (is (re-find #"--label\b" suggestion))
    (is (not (str/includes? suggestion "--text")))))

(deftest rejected-set-attr-names-the-remedy
  (let [[a eid] (form-app)]
    (testing "an attribute the node's tag does not admit"
      (let [msg (:message (cmd/run a "set-wireframe-attr"
                                   {:element eid :node "n3" :attr "align" :value "center"}))]
        (is (str/includes? msg "unknown attribute :align for :button"))
        (is (names-button-remedy? msg) msg)))
    (testing "a value outside the allowed set"
      (let [msg (:message (cmd/run a "set-wireframe-attr"
                                   {:element eid :node "n3" :attr "variant" :value "bogus"}))]
        (is (str/starts-with? msg "variant value 'bogus' not in allowed set"))
        (is (names-button-remedy? msg) msg)))))

(deftest rejected-apply-names-the-remedy-for-every-problem
  (let [[a eid] (form-app)
        msg     (:message (cmd/run a "replace-wireframe"
                                   {:element eid
                                    :tree    (str "[n1] :canvas\n"
                                                  "  [n3] :button  {:text \"Submit\"}\n"
                                                  "  :dropdown  {:label \"Plan\"}")}))]
    (is (str/includes? msg "unknown attribute :text for :button"))
    (is (re-find #"label \(required\)" msg) "the button's admitted attributes")
    (is (re-find #"primary\|secondary\|ghost\|danger" msg))
    (is (str/includes? msg "options is required"))
    (is (re-find #"options \(required\)" msg) "the dropdown's admitted attributes")))
