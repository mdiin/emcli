(ns emcli.wireframe-test
  (:require [clojure.test :refer [deftest testing is]]
            [emcli.wireframe :as wf]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:private simple-wf
  [:screen {:-id "n1"}
   [:col {:-id "n2"}
    [:h1 {:-id "n3"} "Your orders"]
    [:input {:-id "n4"} {:placeholder "Search..." :field-name "searchTerm"}]
    [:button {:-id "n5"} {:label "Create order" :variant :primary :command-input true}]]])

(def ^:private screen-element
  {:id 42 :kind :screen :name "OrderList"
   :fields [{:name "searchTerm" :type :string}]})

;; ---------------------------------------------------------------------------
;; next-node-id
;; ---------------------------------------------------------------------------

(deftest next-node-id-advances-past-existing
  (testing "starts at n1 when tree is empty"
    (is (= "n1" (wf/next-node-id [:screen {}]))))
  (testing "advances past highest existing id"
    (is (= "n6" (wf/next-node-id simple-wf))))
  (testing "handles gaps in numbering"
    (is (= "n4" (wf/next-node-id [:screen {:-id "n1"} [:col {:-id "n3"}]])))))

;; ---------------------------------------------------------------------------
;; strip-ids
;; ---------------------------------------------------------------------------

(deftest strip-ids-removes-all-internal-ids
  (let [stripped (wf/strip-ids simple-wf)]
    (testing "root attrs stripped"
      (is (not (contains? (second stripped) :-id))))
    (testing "nested node attrs stripped"
      (let [col (nth stripped 2)]
        (is (not (contains? (second col) :-id)))))
    (testing "leaf with merged attrs stripped"
      ;; [:input {:-id "n4"} {:placeholder ...}] -> [:input {:placeholder ...}]
      (let [input (nth (nth stripped 2) 3)]
        (is (not (contains? (second input) :-id)))))
    (testing "tag preserved"
      (is (= :screen (first stripped))))))

;; ---------------------------------------------------------------------------
;; validate — structural
;; ---------------------------------------------------------------------------

(deftest validate-accepts-valid-wireframe
  (is (:valid? (wf/validate simple-wf))))

(deftest validate-rejects-unknown-tag
  (let [wf [:screen {:-id "n1"} [:foobar {:-id "n2"}]]
        result (wf/validate wf)]
    (is (false? (:valid? result)))
    (is (seq (:errors result)))
    (is (some #(re-find #"foobar" (:message %)) (:errors result)))))

(deftest validate-rejects-missing-required-attr
  (testing ":button requires :label"
    (let [wf [:screen {:-id "n1"} [:button {:-id "n2"} {:variant :primary}]]
          result (wf/validate wf)]
      (is (false? (:valid? result)))
      (is (some #(re-find #"label" (:message %)) (:errors result)))))
  (testing ":dropdown requires :options"
    (let [wf [:screen {:-id "n1"} [:dropdown {:-id "n2"} {:disabled false}]]
          result (wf/validate wf)]
      (is (false? (:valid? result)))
      (is (some #(re-find #"options" (:message %)) (:errors result)))))
  (testing ":icon-button requires :icon and :aria-label"
    (let [wf [:screen {:-id "n1"} [:icon-button {:-id "n2"} {:icon :trash}]]
          result (wf/validate wf)]
      (is (false? (:valid? result)))
      (is (some #(re-find #"aria-label" (:message %)) (:errors result)))))
  (testing ":image requires :alt"
    (let [wf [:screen {:-id "n1"} [:image {:-id "n2"} {:aspect :square}]]
          result (wf/validate wf)]
      (is (false? (:valid? result)))
      (is (some #(re-find #"alt" (:message %)) (:errors result)))))
  (testing ":icon requires :name"
    (let [wf [:screen {:-id "n1"} [:icon {:-id "n2"} {:size :lg}]]
          result (wf/validate wf)]
      (is (false? (:valid? result)))
      (is (some #(re-find #"name" (:message %)) (:errors result)))))
  (testing ":link requires :label"
    (let [wf [:screen {:-id "n1"} [:link {:-id "n2"} {:command-input true}]]
          result (wf/validate wf)]
      (is (false? (:valid? result)))
      (is (some #(re-find #"label" (:message %)) (:errors result)))))
  (testing ":alert requires :text"
    (let [wf [:screen {:-id "n1"} [:alert {:-id "n2"} {:type :info}]]
          result (wf/validate wf)]
      (is (false? (:valid? result)))
      (is (some #(re-find #"text" (:message %)) (:errors result))))))

(deftest validate-rejects-wrong-value-type
  (testing "string where keyword expected"
    (let [wf [:screen {:-id "n1"} [:button {:-id "n2"} {:label "OK" :variant "primary"}]]
          result (wf/validate wf)]
      (is (false? (:valid? result)))
      (is (some #(re-find #"variant" (:message %)) (:errors result))))))

(deftest validate-rejects-value-outside-allowed-set
  (let [wf [:screen {:-id "n1"} [:button {:-id "n2"} {:label "OK" :variant :invisible}]]
        result (wf/validate wf)]
    (is (false? (:valid? result)))
    (is (some #(re-find #"variant" (:message %)) (:errors result)))))

(deftest validate-rejects-leaf-with-children
  (let [wf [:screen {:-id "n1"} [:button {:-id "n2"} {:label "OK"} [:span {:-id "n3"}]]]
        result (wf/validate wf)]
    (is (false? (:valid? result)))
    (is (some #(re-find #"leaf\|children" (:message %)) (:errors result)))))

(deftest validate-rejects-text-node-with-vector-child
  (let [wf [:screen {:-id "n1"} [:h1 {:-id "n2"} [:span {:-id "n3"}]]]
        result (wf/validate wf)]
    (is (false? (:valid? result)))
    (is (some #(re-find #"string" (:message %)) (:errors result)))))

(deftest validate-rejects-non-screen-root
  (let [wf [:col {:-id "n1"} [:h1 {:-id "n2"} "Hi"]]
        result (wf/validate wf)]
    (is (false? (:valid? result)))
    (is (some #(re-find #"screen" (:message %)) (:errors result)))))

;; ---------------------------------------------------------------------------
;; validate-semantics
;; ---------------------------------------------------------------------------

(deftest validate-semantics-accepts-known-field-name
  (is (:valid? (wf/validate-semantics simple-wf screen-element))))

(deftest validate-semantics-rejects-unknown-field-name
  (let [wf [:screen {:-id "n1"}
            [:input {:-id "n2"} {:field-name "nonexistent"}]]
        result (wf/validate-semantics wf screen-element)]
    (is (false? (:valid? result)))
    (is (some #(re-find #"nonexistent" (:message %)) (:errors result)))
    (is (some #(= "n2" (:node-id %)) (:errors result)))))

(deftest validate-semantics-accepts-no-field-names
  (let [wf [:screen {:-id "n1"} [:button {:-id "n2"} {:label "OK"}]]]
    (is (:valid? (wf/validate-semantics wf {:fields []})))))

;; ---------------------------------------------------------------------------
;; find-node / find-node-path
;; ---------------------------------------------------------------------------

(deftest find-node-returns-node-for-known-id
  (let [node (wf/find-node simple-wf "n3")]
    (is (= :h1 (first node)))))

(deftest find-node-returns-nil-for-unknown-id
  (is (nil? (wf/find-node simple-wf "n99"))))

(deftest find-node-path-returns-path-for-nested-node
  ;; n3 is [:h1 ...] inside [:col ...] inside [:screen ...]
  ;; path should navigate into children
  (let [path (wf/find-node-path simple-wf "n3")]
    (is (vector? path))
    (is (= :h1 (first (get-in simple-wf path))))))

(deftest find-node-path-returns-nil-for-unknown-id
  (is (nil? (wf/find-node-path simple-wf "n99"))))

;; ---------------------------------------------------------------------------
;; append-child-at
;; ---------------------------------------------------------------------------

(deftest append-child-at-appends-to-root
  (let [wf [:screen {:-id "n1"}]
        result (wf/append-child-at wf "n1" [:button {:label "OK"}])
        added (last result)]
    (is (= :button (first added)))
    (is (contains? (second added) :-id))
    (is (= "n2" (get (second added) :-id)))))

(deftest append-child-at-appends-to-nested-node
  (let [result (wf/append-child-at simple-wf "n2" [:text "Hello"])
        col    (wf/find-node result "n2")
        added  (last col)]
    (is (= :text (first added)))
    (is (contains? (second added) :-id))
    (is (= "n6" (get (second added) :-id)))))

(deftest append-child-at-assigns-fresh-id
  (let [result (wf/append-child-at simple-wf "n2" [:divider {}])
        added  (last (wf/find-node result "n2"))]
    (is (= "n6" (get (second added) :-id)))))

;; ---------------------------------------------------------------------------
;; assoc-attr-at
;; ---------------------------------------------------------------------------

(deftest assoc-attr-at-updates-correct-node
  (let [result (wf/assoc-attr-at simple-wf "n5" :label "New label")
        node   (wf/find-node result "n5")
        attrs  (some #(when (and (map? %) (not (contains? % :-id))) %) (rest node))]
    (is (= "New label" (:label attrs)))))

(deftest assoc-attr-at-leaves-siblings-untouched
  (let [result (wf/assoc-attr-at simple-wf "n5" :label "New label")
        input  (wf/find-node result "n4")
        attrs  (some #(when (and (map? %) (not (contains? % :-id))) %) (rest input))]
    (is (= "Search..." (:placeholder attrs)))))

;; ---------------------------------------------------------------------------
;; delete-node-at
;; ---------------------------------------------------------------------------

(deftest delete-node-at-removes-leaf
  (let [result (wf/delete-node-at simple-wf "n3")]
    (is (nil? (wf/find-node result "n3")))
    (is (some? (wf/find-node result "n4")))))

(deftest delete-node-at-removes-subtree
  (let [result (wf/delete-node-at simple-wf "n2")]
    (is (nil? (wf/find-node result "n2")))
    (is (nil? (wf/find-node result "n3")))
    (is (nil? (wf/find-node result "n5")))))

(deftest delete-node-at-root-returns-nil
  (is (nil? (wf/delete-node-at simple-wf "n1"))))

(deftest delete-node-at-sibling-ids-unaffected
  (let [result (wf/delete-node-at simple-wf "n3")]
    ;; n4 and n5 survive with original ids
    (is (some? (wf/find-node result "n4")))
    (is (some? (wf/find-node result "n5")))))

;; ---------------------------------------------------------------------------
;; parse-node-attrs
;; ---------------------------------------------------------------------------

(deftest parse-node-attrs-coerces-known-attrs
  (let [{:keys [ok error]} (wf/parse-node-attrs :button {:label "Save" :variant "primary" :disabled "true"})]
    (is (nil? error))
    (is (= "Save" (:label ok)))
    (is (= :primary (:variant ok)))
    (is (true? (:disabled ok)))))

(deftest parse-node-attrs-rejects-unknown-attr
  (let [{:keys [error]} (wf/parse-node-attrs :button {:label "Save" :foobar "x"})]
    (is (some? error))
    (is (re-find #"foobar" error))))

(deftest parse-node-attrs-rejects-value-outside-allowed-set
  (let [{:keys [error]} (wf/parse-node-attrs :button {:label "Save" :variant "invisible"})]
    (is (some? error))
    (is (re-find #"variant" error))))

(deftest parse-node-attrs-handles-options-comma-split
  (let [{:keys [ok]} (wf/parse-node-attrs :dropdown {:options "Draft,Published,Archived"})]
    (is (= ["Draft" "Published" "Archived"] (:options ok)))))

(deftest parse-node-attrs-rejects-missing-required-attr
  ;; :icon-button requires :icon and :aria-label
  (let [{:keys [error]} (wf/parse-node-attrs :icon-button {:icon "trash"})]
    (is (some? error))
    (is (re-find #"aria-label" error))))

;; ---------------------------------------------------------------------------
;; format-tree
;; ---------------------------------------------------------------------------

(deftest format-tree-contains-node-ids-and-tags
  (let [output (wf/format-tree simple-wf)]
    (is (string? output))
    (is (re-find #"\[n1\]" output))
    (is (re-find #"\[n2\]" output))
    (is (re-find #"\[n5\]" output))
    (is (re-find #":screen" output))
    (is (re-find #":col" output))
    (is (re-find #":button" output))))

(deftest format-tree-indents-by-depth
  (let [lines (clojure.string/split-lines (wf/format-tree simple-wf))]
    ;; n1 (:screen) should have less leading whitespace than n2 (:col)
    (let [n1-line (first (filter #(re-find #"\[n1\]" %) lines))
          n2-line (first (filter #(re-find #"\[n2\]" %) lines))]
      (is (< (count (re-find #"^\s*" n1-line))
             (count (re-find #"^\s*" n2-line)))))))
