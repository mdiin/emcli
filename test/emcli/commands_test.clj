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
