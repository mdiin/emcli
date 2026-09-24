(ns emcli.wireframe-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [emcli.support :as s]
            [emcli.wireframe :as wf]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:private simple-wf
  [:canvas {:-id "n1"}
   [:col {:-id "n2"}
    [:h1 {:-id "n3"} "Your orders"]
    [:input {:-id "n4"} {:placeholder "Search..." :field-name "searchTerm"}]
    [:button {:-id "n5"} {:label "Create order" :variant :primary :command-input true}]]])

(def ^:private screen-element
  {:id 42 :element_type :screen :name "OrderList"
   :fields [{:name "searchTerm" :type :string}]})

;; ---------------------------------------------------------------------------
;; next-node-id
;; ---------------------------------------------------------------------------

(deftest next-node-id-advances-past-existing
  (testing "starts at n1 when tree is empty"
    (is (= "n1" (wf/next-node-id [:canvas {}]))))
  (testing "advances past highest existing id"
    (is (= "n6" (wf/next-node-id simple-wf))))
  (testing "handles gaps in numbering"
    (is (= "n4" (wf/next-node-id [:canvas {:-id "n1"} [:col {:-id "n3"}]]))))
  (testing "the number of a deleted highest node is reused"
    ;; a tree whose highest node was deleted: n1 and n2 survive, so the next node
    ;; added takes n3 again - WireframeNode.node_id says a removed node's number
    ;; may be reused when it was the highest in the tree
    (is (= "n3" (wf/next-node-id [:canvas {:-id "n1"} [:col {:-id "n2"}]])))))

(deftest a-canvas-may-only-be-the-root
  (testing "a canvas nested below the root is malformed"
    (let [{:keys [valid? errors]} (wf/validate [:canvas {:-id "n1"} [:canvas {:-id "n2"}]])]
      (is (false? valid?))
      (is (= ["n2"] (map :node-id errors)))
      (is (= ":canvas is the root of a layout and may not be nested"
             (:message (first errors))))))
  (testing "the root canvas is not itself a violation"
    (is (true? (:valid? (wf/validate [:canvas {:-id "n1"}]))))
    (is (true? (:valid? (wf/validate simple-wf))))))

(deftest a-container-may-not-hold-text
  (testing "a string child on a container tag is malformed"
    (let [{:keys [valid? errors]} (wf/validate [:canvas {:-id "n1"} [:row {:-id "n2"} "text"]])]
      (is (false? valid?))
      (is (= ["n2"] (map :node-id errors)))
      (is (= "container element accepts node children only" (:message (first errors))))))
  (testing "an empty container is fine: a tree is grown one node at a time"
    (is (true? (:valid? (wf/validate [:canvas {:-id "n1"} [:row {:-id "n2"}]]))))))

;; ---------------------------------------------------------------------------
;; validate — structural
;; ---------------------------------------------------------------------------

(deftest validate-accepts-valid-wireframe
  (is (:valid? (wf/validate simple-wf))))

(deftest validate-rejects-unknown-tag
  (let [wf [:canvas {:-id "n1"} [:foobar {:-id "n2"}]]
        result (wf/validate wf)]
    (is (false? (:valid? result)))
    (is (seq (:errors result)))
    (is (some #(= "unknown tag :foobar (see: emcli wireframe tags)" (:message %))
              (:errors result)))))

(deftest validate-names-the-tag-of-an-unknown-attribute
  ;; RejectedLayoutEditNamesRemedy lets the message go on to name the remedy,
  ;; so it must start with the problem rather than equal it.
  (let [wf     [:canvas {:-id "n1"} [:button {:-id "n2"} {:label "Go" :align :center}]]
        errors (:errors (wf/validate wf))]
    (is (= ["n2"] (map :node-id errors)))
    (is (str/starts-with? (:message (first errors))
                          "unknown attribute :align for :button (see: emcli wireframe tags --tag button)"))))

(deftest validate-rejects-missing-required-attr
  (testing ":button requires :label"
    (let [wf [:canvas {:-id "n1"} [:button {:-id "n2"} {:variant :primary}]]
          result (wf/validate wf)]
      (is (false? (:valid? result)))
      (is (some #(re-find #"label" (:message %)) (:errors result)))))
  (testing ":dropdown requires :options"
    (let [wf [:canvas {:-id "n1"} [:dropdown {:-id "n2"} {:disabled false}]]
          result (wf/validate wf)]
      (is (false? (:valid? result)))
      (is (some #(re-find #"options" (:message %)) (:errors result)))))
  (testing ":icon-button requires :icon and :aria-label"
    (let [wf [:canvas {:-id "n1"} [:icon-button {:-id "n2"} {:icon :trash}]]
          result (wf/validate wf)]
      (is (false? (:valid? result)))
      (is (some #(re-find #"aria-label" (:message %)) (:errors result)))))
  (testing ":image requires :alt"
    (let [wf [:canvas {:-id "n1"} [:image {:-id "n2"} {:aspect :square}]]
          result (wf/validate wf)]
      (is (false? (:valid? result)))
      (is (some #(re-find #"alt" (:message %)) (:errors result)))))
  (testing ":icon requires :name"
    (let [wf [:canvas {:-id "n1"} [:icon {:-id "n2"} {:size :lg}]]
          result (wf/validate wf)]
      (is (false? (:valid? result)))
      (is (some #(re-find #"name" (:message %)) (:errors result)))))
  (testing ":link requires :label"
    (let [wf [:canvas {:-id "n1"} [:link {:-id "n2"} {:command-input true}]]
          result (wf/validate wf)]
      (is (false? (:valid? result)))
      (is (some #(re-find #"label" (:message %)) (:errors result)))))
  (testing ":alert requires :text"
    (let [wf [:canvas {:-id "n1"} [:alert {:-id "n2"} {:type :info}]]
          result (wf/validate wf)]
      (is (false? (:valid? result)))
      (is (some #(re-find #"text" (:message %)) (:errors result))))))

(deftest validate-rejects-wrong-value-type
  (testing "string where keyword expected"
    (let [wf [:canvas {:-id "n1"} [:button {:-id "n2"} {:label "OK" :variant "primary"}]]
          result (wf/validate wf)]
      (is (false? (:valid? result)))
      (is (some #(re-find #"variant" (:message %)) (:errors result))))))

(deftest validate-rejects-value-outside-allowed-set
  (let [wf [:canvas {:-id "n1"} [:button {:-id "n2"} {:label "OK" :variant :invisible}]]
        result (wf/validate wf)]
    (is (false? (:valid? result)))
    (is (some #(re-find #"variant" (:message %)) (:errors result)))))

(deftest validate-rejects-leaf-with-children
  (let [wf [:canvas {:-id "n1"} [:button {:-id "n2"} {:label "OK"} [:span {:-id "n3"}]]]
        result (wf/validate wf)]
    (is (false? (:valid? result)))
    (is (some #(re-find #"leaf\|children" (:message %)) (:errors result)))))

(deftest validate-rejects-text-node-with-vector-child
  (let [wf [:canvas {:-id "n1"} [:h1 {:-id "n2"} [:span {:-id "n3"}]]]
        result (wf/validate wf)]
    (is (false? (:valid? result)))
    (is (some #(re-find #"string" (:message %)) (:errors result)))))

(deftest validate-rejects-non-canvas-root
  (let [wf [:col {:-id "n1"} [:h1 {:-id "n2"} "Hi"]]
        result (wf/validate wf)]
    (is (false? (:valid? result)))
    (is (some #(re-find #"canvas" (:message %)) (:errors result)))))

(deftest validate-accepts-field-name-and-command-input-on-any-node
  (testing ":col accepts :field-name and :command-input"
    (let [wf [:canvas {:-id "n1"} [:col {:-id "n2"} {:field-name "x" :command-input true}]]
          result (wf/validate wf)]
      (is (:valid? result))))
  (testing ":row accepts :field-name and :command-input"
    (let [wf [:canvas {:-id "n1"} [:row {:-id "n2"} {:field-name "x" :command-input true}]]
          result (wf/validate wf)]
      (is (:valid? result))))
  (testing ":h1 accepts :field-name and :command-input"
    (let [wf [:canvas {:-id "n1"} [:h1 {:-id "n2"} {:field-name "x" :command-input true} "Title"]]
          result (wf/validate wf)]
      (is (:valid? result))))
  (testing ":h2 accepts :field-name and :command-input"
    (let [wf [:canvas {:-id "n1"} [:h2 {:-id "n2"} {:field-name "x" :command-input true} "Title"]]
          result (wf/validate wf)]
      (is (:valid? result))))
  (testing ":h3 accepts :field-name and :command-input"
    (let [wf [:canvas {:-id "n1"} [:h3 {:-id "n2"} {:field-name "x" :command-input true} "Title"]]
          result (wf/validate wf)]
      (is (:valid? result))))
  (testing ":text accepts :field-name and :command-input"
    (let [wf [:canvas {:-id "n1"} [:text {:-id "n2"} {:field-name "x" :command-input true} "Hello"]]
          result (wf/validate wf)]
      (is (:valid? result))))
  (testing ":span accepts :field-name and :command-input"
    (let [wf [:canvas {:-id "n1"} [:span {:-id "n2"} {:field-name "x" :command-input true} "Hello"]]
          result (wf/validate wf)]
      (is (:valid? result))))
  (testing ":image accepts :field-name and :command-input"
    (let [wf [:canvas {:-id "n1"} [:image {:-id "n2"} {:alt "photo" :field-name "x" :command-input true}]]
          result (wf/validate wf)]
      (is (:valid? result))))
  (testing ":alert accepts :field-name and :command-input"
    (let [wf [:canvas {:-id "n1"} [:alert {:-id "n2"} {:text "hi" :type :info :field-name "x" :command-input true}]]
          result (wf/validate wf)]
      (is (:valid? result))))
  (testing ":icon accepts :field-name and :command-input"
    (let [wf [:canvas {:-id "n1"} [:icon {:-id "n2"} {:name "star" :field-name "x" :command-input true}]]
          result (wf/validate wf)]
      (is (:valid? result)))))

(deftest validate-rejects-field-name-and-command-input-on-screen-and-divider
  (testing ":canvas rejects :field-name"
    (let [wf [:canvas {:-id "n1"} {:field-name "x"}]
          result (wf/validate wf)]
      (is (false? (:valid? result)))))
  (testing ":canvas rejects :command-input"
    (let [wf [:canvas {:-id "n1"} {:command-input true}]
          result (wf/validate wf)]
      (is (false? (:valid? result)))))
  (testing ":divider rejects :field-name"
    (let [wf [:canvas {:-id "n1"} [:divider {:-id "n2"} {:field-name "x"}]]
          result (wf/validate wf)]
      (is (false? (:valid? result)))))
  (testing ":divider rejects :command-input"
    (let [wf [:canvas {:-id "n1"} [:divider {:-id "n2"} {:command-input true}]]
          result (wf/validate wf)]
      (is (false? (:valid? result))))))

;; ---------------------------------------------------------------------------
;; validate-semantics
;; ---------------------------------------------------------------------------

(deftest validate-semantics-accepts-known-field-name
  (is (:valid? (wf/validate-semantics simple-wf screen-element))))

(deftest validate-semantics-rejects-unknown-field-name
  (let [wf [:canvas {:-id "n1"}
            [:input {:-id "n2"} {:field-name "nonexistent"}]]
        result (wf/validate-semantics wf screen-element)]
    (is (false? (:valid? result)))
    (is (some #(re-find #"nonexistent" (:message %)) (:errors result)))
    (is (some #(= "n2" (:node-id %)) (:errors result)))))

(deftest validate-semantics-accepts-no-field-names
  (let [wf [:canvas {:-id "n1"} [:button {:-id "n2"} {:label "OK"}]]]
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
  ;; n3 is [:h1 ...] inside [:col ...] inside [:canvas ...]
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
  (let [wf [:canvas {:-id "n1"}]
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
;; insert-before-at
;; ---------------------------------------------------------------------------

(deftest insert-before-at-root-returns-nil
  (is (nil? (wf/insert-before-at simple-wf "n1" [:divider {}]))))

(deftest insert-before-at-inserts-before-first-child
  ;; Insert before n3 (first child of n2)
  (let [result (wf/insert-before-at simple-wf "n3" [:divider {}])
        col    (wf/find-node result "n2")
        kids   (filter vector? (drop 1 col))]
    (is (= 4 (count kids)))
    (is (= :divider (first (first kids))))
    ;; original n3 is now second
    (is (= "n3" (get (second (second kids)) :-id)))))

(deftest insert-before-at-inserts-before-middle-child
  ;; Insert before n4 (second child of n2)
  (let [result (wf/insert-before-at simple-wf "n4" [:divider {}])
        col    (wf/find-node result "n2")
        kids   (filter vector? (drop 1 col))]
    (is (= 4 (count kids)))
    ;; n3 still first, new divider second, n4 third
    (is (= "n3" (get (second (first kids)) :-id)))
    (is (= :divider (first (second kids))))
    (is (= "n4" (get (second (nth kids 2)) :-id)))))

(deftest insert-before-at-assigns-fresh-id
  (let [result (wf/insert-before-at simple-wf "n3" [:divider {}])
        added  (wf/find-node result "n6")]
    (is (some? added))
    (is (= :divider (first added)))))

(deftest insert-before-at-siblings-unaffected
  (let [result (wf/insert-before-at simple-wf "n3" [:divider {}])]
    (is (some? (wf/find-node result "n3")))
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
;; tag reference (the `wireframe tags` discovery verb and the generated docs)
;; ---------------------------------------------------------------------------

(deftest tag-groups-follow-the-spec-roles
  ;; event-model.allium, the comment on enum WireframeTag
  (is (= [[:layout     [:canvas :row :col :divider]]
          [:typography [:h1 :h2 :h3 :text :span]]
          [:input      [:input :textarea :dropdown :checkbox :toggle]]
          [:action     [:button :icon-button]]
          [:content    [:link :image :icon :alert]]]
         wf/tag-groups))
  (testing "every tag of the schema is in exactly one group"
    (let [grouped (mapcat second wf/tag-groups)]
      (is (= (count grouped) (count (set grouped))))
      (is (= wf/allowed-tags (set grouped))))))

(deftest every-tag-states-its-purpose
  (doseq [[tag schema] wf/tag-schema]
    (is (and (string? (:doc schema)) (not (str/blank? (:doc schema))))
        (str tag " needs a :doc purpose line"))))

(deftest tag-list-names-every-addable-tag-with-its-purpose
  (let [out (wf/tag-list-text)]
    (doseq [[tag schema] (dissoc wf/tag-schema :canvas)]
      (is (re-find (re-pattern (str "(?m)^\\s+" (name tag) "\\s+"
                                    (java.util.regex.Pattern/quote (:doc schema)) "$"))
                   out)
          (str tag " listed with its purpose")))
    (testing "canvas is never added, so the list does not offer it"
      (is (not (re-find #"(?m)^\s+canvas\b" out))))
    (testing "points at the per-tag detail"
      (is (str/includes? out "emcli wireframe tags --tag <name>")))))

(deftest tag-detail-describes-attributes-and-an-example
  (let [out (wf/tag-detail-text :button)]
    (is (str/includes? out (:doc (wf/tag-schema :button))))
    (is (re-find #"--label\s+text\s+required" out))
    (is (re-find #"--variant\s+primary\|secondary\|ghost\|danger" out))
    (is (re-find #"--disabled\s+true\|false" out))
    (is (str/includes? out "emcli wireframe add-node --element <screen id> --tag button --label \"<label>\""))
    (testing "required attributes are listed first"
      (is (< (str/index-of out "--label") (str/index-of out "--variant"))))))

(deftest tag-detail-shows-what-a-node-holds
  (is (re-find #"(?i)children: child nodes" (wf/tag-detail-text :row)))
  (is (re-find #"(?i)children: text" (wf/tag-detail-text :h1)))
  (is (re-find #"(?i)children: none" (wf/tag-detail-text :input)))
  (testing "a text tag's example supplies its content with --text"
    (is (str/includes? (wf/tag-detail-text :h1) "--tag h1 --text \"<text>\""))))

(deftest tag-detail-of-unknown-tag-is-nil
  (is (nil? (wf/tag-detail-text :card))))

(deftest every-example-is-accepted-by-the-schema
  ;; the example is what a small model copies, so it must never be rejected
  (doseq [tag (disj wf/allowed-tags :canvas)]
    (is (:ok (wf/parse-node-attrs tag (wf/example-attrs tag)))
        (str "example for " tag " parses"))))

(deftest tag-reference-markdown-has-a-table-per-group
  (let [md (wf/tag-reference-markdown)]
    (doseq [[group tags] wf/tag-groups]
      (is (re-find (re-pattern (str "(?m)^### " (str/capitalize (name group)))) md))
      (doseq [t tags]
        (is (str/includes? md (str "| `" (name t) "`")))))
    (is (str/includes? md "`variant` (primary, secondary, ghost, danger)"))))

;; ---------------------------------------------------------------------------
;; format-tree
;; ---------------------------------------------------------------------------

(deftest format-tree-contains-node-ids-and-tags
  (let [output (wf/format-tree simple-wf)]
    (is (string? output))
    (is (re-find #"\[n1\]" output))
    (is (re-find #"\[n2\]" output))
    (is (re-find #"\[n5\]" output))
    (is (re-find #":canvas" output))
    (is (re-find #":col" output))
    (is (re-find #":button" output))))

(deftest format-tree-indents-by-depth
  (let [lines (clojure.string/split-lines (wf/format-tree simple-wf))]
    ;; n1 (:canvas) should have less leading whitespace than n2 (:col)
    (let [n1-line (first (filter #(re-find #"\[n1\]" %) lines))
          n2-line (first (filter #(re-find #"\[n2\]" %) lines))]
      (is (< (count (re-find #"^\s*" n1-line))
             (count (re-find #"^\s*" n2-line)))))))

;; ---------------------------------------------------------------------------
;; Tree helpers for the move / replace tests
;; ---------------------------------------------------------------------------

(def ^:private move-node-at wf/move-node-at)
(def ^:private parse-tree   wf/parse-tree)
(def ^:private resolve-ids  wf/resolve-ids)

(defn- doc-ids
  "Every node id of the tree, in document order."
  [node]
  (when (vector? node)
    (cons (get-in node [1 :-id]) (mapcat doc-ids (filter vector? (drop 2 node))))))

(defn- kid-ids
  "The ids of the immediate child nodes of `node-id`."
  [wireframe node-id]
  (map #(get-in % [1 :-id]) (filter vector? (drop 2 (wf/find-node wireframe node-id)))))

;; ---------------------------------------------------------------------------
;; move-node-at (MoveWireframeNode: move_before / move_into)
;; ---------------------------------------------------------------------------

(def ^:private move-wf
  [:canvas {:-id "n1"}
   [:col {:-id "n2"}
    [:h1 {:-id "n3"} "Orders"]
    [:input {:-id "n4"} {:field-name "searchTerm"}]
    [:button {:-id "n5"} {:label "Go"}]]
   [:row {:-id "n6"}
    [:span {:-id "n7"} "Total"]
    [:col {:-id "n8"}]]])

(deftest move-node-at-places-a-node-before-a-sibling
  (let [result (move-node-at move-wf "n5" {:before "n3"})]
    (is (= ["n5" "n3" "n4"] (kid-ids result "n2")))
    (is (= ["n7" "n8"] (kid-ids result "n6")) "other parents keep their children")))

(deftest move-node-at-appends-into-a-parent-as-last-child
  (let [result (move-node-at move-wf "n3" {:parent "n2"})]
    (is (= ["n4" "n5" "n3"] (kid-ids result "n2")))))

(deftest move-node-at-moves-across-parents
  (testing "before a sibling under another parent"
    (let [result (move-node-at move-wf "n4" {:before "n7"})]
      (is (= ["n3" "n5"] (kid-ids result "n2")))
      (is (= ["n4" "n7" "n8"] (kid-ids result "n6")))))
  (testing "into another container"
    (let [result (move-node-at move-wf "n5" {:parent "n8"})]
      (is (= ["n3" "n4"] (kid-ids result "n2")))
      (is (= ["n5"] (kid-ids result "n8"))))))

(deftest move-node-at-carries-the-whole-subtree
  (let [result (move-node-at move-wf "n2" {:parent "n8"})]
    (is (= ["n6"] (kid-ids result "n1")))
    (is (= ["n2"] (kid-ids result "n8")))
    (is (= (wf/find-node move-wf "n2") (wf/find-node result "n2"))
        "the moved node keeps its children, attributes, text and ids")))

(deftest move-node-at-keeps-every-node-id
  (doseq [[node target] [["n5" {:before "n3"}] ["n4" {:parent "n8"}] ["n2" {:before "n7"}]]]
    (let [result (move-node-at move-wf node target)]
      (is (= (set (doc-ids move-wf)) (set (doc-ids result))))
      (is (= (count (doc-ids move-wf)) (count (doc-ids result))))
      ;; the parent a node is moved into gains that child; every other node is
      ;; untouched
      (doseq [id (remove #{(:parent target)} ["n3" "n4" "n5" "n7" "n8"])]
        (is (= (wf/find-node move-wf id) (wf/find-node result id))
            (str id " is unchanged by moving " node)))
      (is (= (wf/next-node-id move-wf) (wf/next-node-id result))
          "nothing is allocated or renumbered")
      (is (:valid? (wf/validate result))))))

(deftest move-node-at-to-where-it-already-sits-is-a-no-op
  (is (= move-wf (move-node-at move-wf "n5" {:parent "n2"})) "already the last child")
  (is (= move-wf (move-node-at move-wf "n4" {:before "n5"})) "already right before"))

;; ---------------------------------------------------------------------------
;; format-tree reveals every node's attributes and text
;; ---------------------------------------------------------------------------

(deftest format-tree-shows-both-attributes-and-text-of-a-text-node
  ;; LayoutEditRevealsResult: every node with its id, tag, attributes and text
  (let [line (->> (wf/format-tree [:canvas {:-id "n1"}
                                   [:h1 {:-id "n2"} {:field-name "title"} "Orders"]])
                  str/split-lines
                  (filter #(str/includes? % "[n2]"))
                  first)]
    (is (str/includes? line ":field-name"))
    (is (str/includes? line "\"title\""))
    (is (str/includes? line "\"Orders\""))))

;; ---------------------------------------------------------------------------
;; parse-tree: the text form of a WireframeTargetNode tree is what
;; format-tree (and so `wireframe show`) prints
;; ---------------------------------------------------------------------------

(deftest parse-tree-round-trips-format-tree
  (doseq [w [simple-wf
             move-wf
             [:canvas {:-id "n1"}]
             [:canvas {:-id "n1"}
              [:row {:-id "n2"} {:gap :md}
               [:h1 {:-id "n3"} {:field-name "title"} "Orders"]
               [:dropdown {:-id "n5"} {:options ["a" "b"] :required true}]]]]]
    (let [text (wf/format-tree w)]
      (is (= {:ok w} (parse-tree text)) (str "round trip of\n" text))
      (is (= (parse-tree text) (parse-tree text))
          "a target tree is a value: the same text parses to equal trees"))))

(deftest parse-tree-marks-unprefixed-lines-as-new-nodes
  ;; A node naming no id has an id map without :-id; the rest of its shape
  ;; (tag, attributes, text, children) is a wireframe node's.
  (is (= {:ok [:canvas {:-id "n1"}
               [:col {}
                [:h1 {:-id "n3"} "Hi"]
                [:input {} {:type :email :label "Email"}]]]}
         (parse-tree (str "[n1] :canvas\n"
                          "  :col\n"
                          "    [n3] :h1  \"Hi\"\n"
                          "    :input  {:type :email, :label \"Email\"}")))))

(deftest parse-tree-ignores-surrounding-blank-lines-and-base-indentation
  ;; a --tree argument written across lines in a shell usually starts with a
  ;; newline and carries the indentation of the command around it
  (is (= {:ok [:canvas {} [:col {} [:divider {}]]]}
         (parse-tree "\n  :canvas\n    :col\n      :divider\n\n"))))

(defn- error-lines [result] (set (map :line (:errors result))))

(deftest parse-tree-errors-carry-a-line-number
  (testing "indentation deeper than one level below its parent"
    (let [res (parse-tree "[n1] :canvas\n  :col\n      :divider")]
      (is (nil? (:ok res)))
      (is (contains? (error-lines res) 3))
      (is (every? #(string? (:message %)) (:errors res)))))
  (testing "a line that is not a node"
    (is (contains? (error-lines (parse-tree "[n1] :canvas\n  \"just text\"")) 2)))
  (testing "content that does not read"
    (is (contains? (error-lines (parse-tree "[n1] :canvas\n  :col  {:gap")) 2)))
  (testing "a second root"
    (is (contains? (error-lines (parse-tree "[n1] :canvas\n[n2] :canvas")) 2)))
  (testing "every bad line is reported, not only the first"
    (is (= #{2 3} (error-lines (parse-tree "[n1] :canvas\n  ???\n  :col  {:gap\n  :divider")))))
  (testing "no node at all"
    (let [res (parse-tree "\n  \n")]
      (is (nil? (:ok res)))
      (is (seq (:errors res))))))

;; ---------------------------------------------------------------------------
;; resolve-ids (ReplaceWireframe: the concrete tree a target describes)
;; ---------------------------------------------------------------------------

(deftest resolve-ids-keeps-named-ids-and-allocates-beyond-the-current-highest
  ;; simple-wf's highest id is n5; the target drops n3 and n5, so the new nodes
  ;; get n6 and n7 in document order - a dropped id is never handed out again
  ;; within the same replacement
  (is (= [:canvas {:-id "n1"}
          [:col {:-id "n2"}
           [:text {:-id "n6"} "new"]
           [:input {:-id "n4"} {:placeholder "Search..." :field-name "searchTerm"}]
           [:divider {:-id "n7"}]]]
         (resolve-ids simple-wf
                      [:canvas {:-id "n1"}
                       [:col {:-id "n2"}
                        [:text {} "new"]
                        [:input {:-id "n4"} {:placeholder "Search..." :field-name "searchTerm"}]
                        [:divider {}]]]))))

(deftest resolve-ids-starts-from-n1-without-a-current-layout
  (is (= [:canvas {:-id "n1"} [:col {:-id "n2"} [:h1 {:-id "n3"} "Hi"]]]
         (resolve-ids nil [:canvas {} [:col {} [:h1 {} "Hi"]]]))))

(deftest resolve-ids-keeps-the-root-as-the-root
  ;; a target root naming no id is the layout's root, not a new node, so it
  ;; takes the current root's id and costs no fresh number
  (is (= [:canvas {:-id "n1"} [:divider {:-id "n6"}]]
         (resolve-ids simple-wf [:canvas {} [:divider {}]]))))

(deftest resolve-ids-takes-the-target's-content-for-a-kept-node
  (is (= [:canvas {:-id "n1"} [:button {:-id "n5"} {:label "Save"}]]
         (resolve-ids simple-wf [:canvas {:-id "n1"} [:button {:-id "n5"} {:label "Save"}]]))))
