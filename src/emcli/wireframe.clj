(ns emcli.wireframe
  "Wireframe DSL for screen elements: schema, validation, navigation, mutation,
  and rendering. Wireframes are hiccup-like EDN vectors embedded in screen
  element maps under the :wireframe key."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [emcli.model :as m]))

;; ---------------------------------------------------------------------------
;; Tag schema
;; ---------------------------------------------------------------------------

;; Each entry: {:doc str
;;              :attrs {attr-kw {:type :kw|:bool|:str|:str-list :required? bool :values [...]}}
;;              :leaf? bool :text-children? bool}
;; :doc is the tag's purpose in one line, shown by `wireframe tags` and in the
;; generated docs. :values is a vector so the allowed set reads in its declared
;; order wherever it is shown. Attributes are listed in display order.
;; :-id is never in attrs — it is reserved and handled separately everywhere.
(def tag-schema
  {:canvas      {:doc "root of every layout; always n1, created automatically, never added"
                 :attrs {} :leaf? false :text-children? false}
   :row         {:doc "lays its child nodes out side by side"
                 :attrs {:align         {:type :kw :values [:start :center :end :between]}
                          :gap           {:type :kw :values [:sm :md :lg]}
                          :field-name    {:type :str}
                          :command-input {:type :bool}}
                  :leaf? false :text-children? false}
   :col         {:doc "stacks its child nodes vertically"
                 :attrs {:align         {:type :kw :values [:start :center :end :between]}
                          :gap           {:type :kw :values [:sm :md :lg]}
                          :width         {:type :kw :values [:narrow :wide :auto :full]}
                          :field-name    {:type :str}
                          :command-input {:type :bool}}
                  :leaf? false :text-children? false}
   :h1          {:doc "page heading"
                 :attrs {:field-name {:type :str} :command-input {:type :bool}} :leaf? false :text-children? true}
   :h2          {:doc "section heading"
                 :attrs {:field-name {:type :str} :command-input {:type :bool}} :leaf? false :text-children? true}
   :h3          {:doc "sub-section heading"
                 :attrs {:field-name {:type :str} :command-input {:type :bool}} :leaf? false :text-children? true}
   :text        {:doc "paragraph of text, e.g. a displayed value"
                 :attrs {:align         {:type :kw :values [:left :center :right]}
                          :tone          {:type :kw :values [:default :muted :danger :success]}
                          :field-name    {:type :str}
                          :command-input {:type :bool}}
                  :leaf? false :text-children? true}
   :span        {:doc "short inline text, e.g. a caption or a value"
                 :attrs {:tone          {:type :kw :values [:default :muted :danger :success]}
                          :field-name    {:type :str}
                          :command-input {:type :bool}}
                  :leaf? false :text-children? true}
   :divider     {:doc "horizontal line separating content"
                 :attrs {} :leaf? true :text-children? false}
   :input       {:doc "single-line entry field"
                 :attrs {:type          {:type :kw :values [:text :email :password :number :tel :url]}
                         :label         {:type :str}
                         :placeholder   {:type :str}
                         :required      {:type :bool}
                         :field-name    {:type :str}
                         :command-input {:type :bool}}
                 :leaf? true :text-children? false}
   :textarea    {:doc "multi-line entry field"
                 :attrs {:label         {:type :str}
                         :placeholder   {:type :str}
                         :required      {:type :bool}
                         :field-name    {:type :str}
                         :command-input {:type :bool}}
                 :leaf? true :text-children? false}
   :dropdown    {:doc "pick one of a fixed set of options"
                 :attrs {:options        {:type :str-list :required? true}
                         :label          {:type :str}
                         :required       {:type :bool}
                         :field-name     {:type :str}
                         :command-input  {:type :bool}}
                 :leaf? true :text-children? false}
   :checkbox    {:doc "tick box for a yes/no value"
                 :attrs {:label         {:type :str}
                         :default       {:type :bool}
                         :field-name    {:type :str}
                         :command-input {:type :bool}}
                 :leaf? true :text-children? false}
   :toggle      {:doc "on/off switch for a yes/no value"
                 :attrs {:label         {:type :str}
                         :default       {:type :bool}
                         :field-name    {:type :str}
                         :command-input {:type :bool}}
                 :leaf? true :text-children? false}
   :button      {:doc "labelled action, e.g. one that triggers a command"
                 :attrs {:label         {:type :str :required? true}
                         :variant       {:type :kw :values [:primary :secondary :ghost :danger]}
                         :disabled      {:type :bool}
                         :command-input {:type :bool}}
                 :leaf? true :text-children? false}
   :icon-button {:doc "action shown as an icon only"
                 :attrs {:icon          {:type :str :required? true}
                         :aria-label    {:type :str :required? true}
                         :command-input {:type :bool}}
                 :leaf? true :text-children? false}
   :link        {:doc "navigation to another screen or page"
                 :attrs {:label         {:type :str :required? true}
                         :command-input {:type :bool}}
                 :leaf? true :text-children? false}
   :image       {:doc "picture or media placeholder"
                 :attrs {:alt           {:type :str :required? true}
                          :aspect        {:type :kw :values [:square :wide :tall]}
                          :field-name    {:type :str}
                          :command-input {:type :bool}}
                  :leaf? true :text-children? false}
   :icon        {:doc "small symbol, e.g. a status indicator"
                 :attrs {:name          {:type :str :required? true}
                          :size          {:type :kw :values [:sm :md :lg]}
                          :field-name    {:type :str}
                          :command-input {:type :bool}}
                  :leaf? true :text-children? false}
   :alert       {:doc "message banner: info, warning, danger or success"
                 :attrs {:text          {:type :str :required? true}
                          :type          {:type :kw :values [:info :warning :danger :success]}
                          :field-name    {:type :str}
                          :command-input {:type :bool}}
                  :leaf? true :text-children? false}})

(def allowed-tags (set (keys tag-schema)))

;; A tag or attribute name that is not in the vocabulary says where the
;; vocabulary is, rather than spelling it out: the hint costs a caller nothing
;; until the mistake is made, and `wireframe tags` answers it in full.
(defn- unknown-tag-message [tag]
  (str "unknown tag :" (name tag) " (see: emcli wireframe tags)"))

(defn- unknown-attr-message [tag attr]
  (str "unknown attribute :" (name attr) " for :" (name tag)
       " (see: emcli wireframe tags --tag " (name tag) ")"))

;; The tags by role, in display order: the roles the comment on enum WireframeTag
;; in event-model.allium names. The single place the grouping and the order live.
(def tag-groups
  [[:layout     [:canvas :row :col :divider]]
   [:typography [:h1 :h2 :h3 :text :span]]
   [:input      [:input :textarea :dropdown :checkbox :toggle]]
   [:action     [:button :icon-button]]
   [:content    [:link :image :icon :alert]]])

;; ---------------------------------------------------------------------------
;; Private tree-navigation helpers
;; ---------------------------------------------------------------------------

(defn- node-id-of
  "Extract the :-id from a node's id-map (first map after tag), or nil."
  [node]
  (when (vector? node)
    (let [second- (second node)]
      (when (map? second-) (:-id second-)))))

(defn- child-indices
  "Indices into `node` that are vector children (elements, not attr maps)."
  [node]
  (keep-indexed (fn [i x] (when (and (pos? i) (vector? x)) i)) node))

;; ---------------------------------------------------------------------------
;; next-node-id
;; ---------------------------------------------------------------------------

(defn- collect-ids
  "Walk wireframe tree collecting all :-id string values."
  [node]
  (when (vector? node)
    (let [maps (take-while map? (rest node))
          id   (some :-id maps)
          kids (filter vector? (drop-while map? (rest node)))]
      (cond-> (mapcat collect-ids kids)
        id (conj id)))))

(defn next-node-id
  "Return the next node id string ('nN'): one past the highest existing numeric
  suffix in the tree. Deleting a node leaves every surviving node's id unchanged;
  the only number that can come back is the highest-numbered node's own, once
  that node is gone (see WireframeNode.node_id in event-model.allium)."
  [wireframe]
  (let [ids (collect-ids wireframe)
        n   (transduce
              (comp (map #(some-> % (subs 1) parse-long)) (filter some?))
              max 0 ids)]
    (str "n" (inc n))))

;; ---------------------------------------------------------------------------
;; find-node / find-node-path
;; ---------------------------------------------------------------------------

(defn find-node
  "Return the raw node vector (with :-id) for `node-id`, or nil."
  [wireframe node-id]
  (when (vector? wireframe)
    (if (= node-id (node-id-of wireframe))
      wireframe
      (some #(find-node % node-id) (map #(nth wireframe %) (child-indices wireframe))))))

(defn find-node-path
  "Return a vector of indices (for use with get-in/assoc-in) pointing to the
  node with `node-id` inside `wireframe`, or nil if not found."
  [wireframe node-id]
  (letfn [(search [node path]
            (when (vector? node)
              (if (= node-id (node-id-of node))
                path
                (some (fn [i] (search (nth node i) (conj path i)))
                      (child-indices node)))))]
    (search wireframe [])))

;; ---------------------------------------------------------------------------
;; validate (structural)
;; ---------------------------------------------------------------------------

(defn- validate-node-with-ids
  "Validate a node, keeping :-id values from the tree for error attribution.
  Node format: [tag {:-id 'nN' ...content-attrs} ...children]"
  [node]
  (when (vector? node)
    (let [tag      (first node)
          rest-    (rest node)
          id-map   (when (and (seq rest-) (map? (first rest-))) (first rest-))
          node-id  (when id-map (:-id id-map))
          after-id (if id-map (rest rest-) rest-)
          attrs    (when (and (seq after-id) (map? (first after-id))) (first after-id))
          children (if attrs (rest after-id) after-id)
          schema   (tag-schema tag)]
      (cond
        (nil? schema)
        [{:node-id node-id :message (unknown-tag-message tag)}]

        :else
        (let [attr-errs
              (when attrs
                (mapcat
                  (fn [[k v]]
                    (if-let [aschema (get (:attrs schema) k)]
                      (let [type (:type aschema)
                            vals (:values aschema)]
                        (cond
                          (and (= type :kw) (not (keyword? v)))
                          [{:node-id node-id :message (str (name k) " must be a keyword")}]
                          (and (= type :bool) (not (boolean? v)))
                          [{:node-id node-id :message (str (name k) " must be a boolean")}]
                          (and (= type :str) (not (string? v)))
                          [{:node-id node-id :message (str (name k) " must be a string")}]
                          (and (= type :str-list) (not (vector? v)))
                          [{:node-id node-id :message (str (name k) " must be a vector of strings")}]
                          (and vals (keyword? v) (not (some #{v} vals)))
                          [{:node-id node-id :message (str (name k) " value " v " not in allowed set "
                                                            (str/join ", " (map name vals)))}]
                          :else []))
                      [{:node-id node-id :message (unknown-attr-message tag k)}]))
                  attrs))
              req-errs
              (mapcat
                (fn [[k aschema]]
                  (when (and (:required? aschema) (nil? (get attrs k)))
                    [{:node-id node-id :message (str (name k) " is required")}]))
                (:attrs schema))
              leaf-errs
              (when (and (:leaf? schema) (seq children))
                [{:node-id node-id :message "leaf|children: leaf element may not have children"}])
              text-errs
              (when (and (:text-children? schema) (not (:leaf? schema))
                         (some vector? children))
                [{:node-id node-id :message "text element accepts string children only"}])
              text-child-errs
              (when (and (not (:leaf? schema)) (not (:text-children? schema))
                         (some #(not (vector? %)) children))
                [{:node-id node-id :message "container element accepts node children only"}])
              child-errs
              (when-not (or (:leaf? schema) (:text-children? schema))
                (mapcat #(when (vector? %) (validate-node-with-ids %)) children))]
          (concat attr-errs req-errs leaf-errs text-errs text-child-errs child-errs))))))

(defn- canvas-node-ids
  "Every :canvas node's id in the tree rooted at `node`, in tree order."
  [node]
  (when (vector? node)
    (concat (when (= :canvas (first node)) [(node-id-of node)])
            (mapcat #(canvas-node-ids (nth node %)) (child-indices node)))))

(defn- nested-canvas-ids
  "The ids of every :canvas node below the tree's root. The root *is* the canvas
  (always n1); a canvas anywhere else is a state the tag's schema does not admit
  (doc/wireframe-dsl.md: ':canvas - the root of every wireframe; always n1'), so
  it is reported rather than accepted."
  [wireframe]
  (when (vector? wireframe)
    (mapcat #(canvas-node-ids (nth wireframe %)) (child-indices wireframe))))

(defn validate
  "Structural validation. Works on the original tree (preserving :-id for error
  attribution), checking tags, required attrs, value types/allowed sets,
  leaf/text-child nesting, the :canvas root, and that no other node is a canvas.
  Returns {:valid? true} or {:valid? false :errors [{:node-id str :message str}]}."
  [wireframe]
  (let [root-err    (when (not= :canvas (first wireframe))
                      [{:node-id nil :message "root element must be :canvas"}])
        nested-errs (for [id (nested-canvas-ids wireframe)]
                      {:node-id id
                       :message ":canvas is the root of a layout and may not be nested"})
        errs        (concat root-err nested-errs (validate-node-with-ids wireframe))]
    (if (seq errs)
      {:valid? false :errors (vec errs)}
      {:valid? true})))

;; ---------------------------------------------------------------------------
;; validate-semantics
;; ---------------------------------------------------------------------------

(defn- field-names-in-node
  "Collect all :field-name values with their node-ids in the tree."
  [node]
  (when (vector? node)
    (let [rest-    (rest node)
          id-map   (when (and (seq rest-) (map? (first rest-))) (first rest-))
          after-id (if id-map (rest rest-) rest-)
          attrs    (when (and (seq after-id) (map? (first after-id))) (first after-id))
          children (if attrs (rest after-id) after-id)
          node-id  (when id-map (:-id id-map))
          own      (when-let [fn- (:field-name attrs)]
                     [{:node-id node-id :field-name fn-}])]
      (concat own (mapcat field-names-in-node (filter vector? children))))))

(defn field-references
  "Every :field-name a layout names, as {:node-id str :field-name str} maps,
  empty when there is no layout. The published form of the traversal
  validate-semantics uses, so callers outside this namespace (e.g. a rule that
  must not strand a reference) need no tree walk of their own."
  [wireframe]
  (vec (field-names-in-node wireframe)))

(defn validate-semantics
  "Semantic validation: all :field-name values must exist in the screen's
  :fields array. Returns {:valid? true} or {:valid? false :errors [...]}."
  [wireframe screen-element]
  (let [field-names (map :name (:fields screen-element))
        refs        (field-references wireframe)
        errs        (for [{:keys [node-id field-name]} refs
                          :when (not (m/same-name-in? field-names field-name))]
                    {:node-id node-id
                     :message (str "Field '" field-name "' does not exist on screen")})]
    (if (seq errs)
      {:valid? false :errors (vec errs)}
      {:valid? true})))

;; ---------------------------------------------------------------------------
;; append-child-at / assoc-attr-at / delete-node-at
;; ---------------------------------------------------------------------------

(declare delete-node-at)

(defn- append-into
  "`wireframe` with the addressed node `child` appended as the last child of the
  node `parent-node-id`."
  [wireframe parent-node-id child]
  (let [path (find-node-path wireframe parent-node-id)]
    (if (seq path)
      (update-in wireframe path conj child)
      (conj wireframe child))))

(defn append-child-at
  "Append `child-vec` as a new child of the node identified by `parent-node-id`.
  Assigns a fresh :-id to the child (inserted as the second element, after tag)."
  [wireframe parent-node-id child-vec]
  (append-into wireframe parent-node-id
               (into [(first child-vec) {:-id (next-node-id wireframe)}] (rest child-vec))))

(defn- splice-before
  "`wireframe` with the addressed node `child` placed immediately before the
  node `sibling-node-id`, under whatever parent that sibling has."
  [wireframe sibling-node-id child]
  (letfn [(splice [node]
            (let [sib-idx (first (filter #(= sibling-node-id (node-id-of (nth node %)))
                                         (child-indices node)))]
              (if sib-idx
                (vec (concat (subvec node 0 sib-idx) [child] (subvec node sib-idx)))
                (into [] (map-indexed (fn [i x] (if (and (pos? i) (vector? x)) (splice x) x)))
                      node))))]
    (splice (vec wireframe))))

(defn insert-before-at
  "Insert `child-vec` as a new sibling immediately before the node identified
  by `sibling-node-id`. Assigns a fresh :-id to the new node. Returns nil if
  `sibling-node-id` is the root node (no parent to insert into)."
  [wireframe sibling-node-id child-vec]
  (when-not (= sibling-node-id (node-id-of wireframe))
    (splice-before wireframe sibling-node-id
                   (into [(first child-vec) {:-id (next-node-id wireframe)}] (rest child-vec)))))

(defn container-tag?
  "The tag carries child nodes (the canvas among them): neither a leaf nor a
  text tag. Only a container can be the parent a node is appended or moved into."
  [tag]
  (let [schema (tag-schema tag)]
    (boolean (and schema (not (:leaf? schema)) (not (:text-children? schema))))))

(defn within-subtree?
  "`node-id` is `ancestor-id` itself or a node anywhere below it."
  [wireframe ancestor-id node-id]
  (some? (some-> (find-node wireframe ancestor-id) (find-node node-id))))

(defn move-node-at
  "Move the node `node-id`, with its whole subtree, either immediately before
  the node `before` or into the container `parent` as its last child. Every node
  - the moved ones included - keeps its id, attributes, text and children.
  Preconditions (see MoveWireframeNode) are the caller's to check."
  [wireframe node-id {:keys [before parent]}]
  (let [node    (find-node wireframe node-id)
        without (delete-node-at wireframe node-id)]
    (if before
      (splice-before without before node)
      (append-into without parent node))))

(defn assoc-attr-at
  "Set attribute `attr-kw` to `value` on the node identified by `node-id`.
  If the node has no content-attrs map (only the id-map), one is inserted."
  [wireframe node-id attr-kw value]
  (let [path (find-node-path wireframe node-id)]
    (update-in wireframe path
               (fn [node]
                 (let [tag       (first node)
                       id-map    (second node)
                       rest-     (drop 2 node)
                       has-attrs (and (seq rest-) (map? (first rest-)))
                       attrs     (if has-attrs (first rest-) {})
                       children  (if has-attrs (rest rest-) rest-)
                       new-attrs (assoc attrs attr-kw value)]
                   (into [tag id-map new-attrs] children))))))

(defn rename-field-references
  "`wireframe` with the value of every :field-name attribute that is the same name
  as `old` set to `new`. The tree's structure, its node ids and every other
  attribute are untouched, and a null tree (a screen carrying no layout) comes
  back null. Renaming a field moves the reference with it, so the layout never has
  to be retargeted by hand (see WireframeReferencesResolve)."
  [wireframe old new]
  (when (some? wireframe)
    (reduce (fn [wf {:keys [node-id field-name]}]
              (if (m/same-name? field-name old)
                (assoc-attr-at wf node-id :field-name new)
                wf))
            wireframe
            (field-references wireframe))))

(defn set-text-child-at
  "Set the string child of the node identified by `node-id` to `text`.
  Replaces any existing string child; leaves vector children untouched."
  [wireframe node-id text]
  (let [path (find-node-path wireframe node-id)]
    (update-in wireframe path
               (fn [node]
                 (let [tag       (first node)
                       id-map    (second node)
                       rest-     (drop 2 node)
                       has-attrs (and (seq rest-) (map? (first rest-)))
                       attrs     (when has-attrs (first rest-))
                       children  (if has-attrs (rest rest-) rest-)
                       new-kids  (conj (vec (remove string? children)) text)]
                   (cond-> [tag id-map]
                     attrs   (conj attrs)
                     :always (into new-kids)))))))

(defn delete-node-at
  "Remove the node identified by `node-id` from the wireframe (along with its
  subtree). Returns nil if `node-id` is the root node."
  [wireframe node-id]
  (if (= node-id (node-id-of wireframe))
    nil
    (letfn [(remove-from [node]
              (let [indices   (child-indices node)
                    to-remove (set (filter #(= node-id (node-id-of (nth node %))) indices))]
                (vec (keep-indexed
                       (fn [i x]
                         (cond
                           (to-remove i)               nil
                           (and (pos? i) (vector? x))  (remove-from x)
                           :else                        x))
                       node))))]
      (remove-from wireframe))))

;; ---------------------------------------------------------------------------
;; parse-node-attrs / coerce-attr-value
;; ---------------------------------------------------------------------------

(defn coerce-attr-value
  "Coerce `raw` (a string) to the typed value declared by `schema-entry`.
  Returns the coerced value, or throws ex-info on invalid input."
  [attr-kw raw schema-entry]
  (let [type (:type schema-entry)
        vals (:values schema-entry)]
    (case type
      :kw       (let [kw (keyword raw)]
                  (if (and vals (not (some #{kw} vals)))
                    (throw (ex-info (str (name attr-kw) " value '" raw "' not in allowed set "
                                        (str/join ", " (map name vals)))
                                    {:attr attr-kw}))
                    kw))
      :bool     (case (str/lower-case (str raw))
                  ("true" "1" "yes") true
                  ("false" "0" "no") false
                  (throw (ex-info (str (name attr-kw) " must be a boolean (true/false)")
                                  {:attr attr-kw})))
      :str      (str raw)
      :str-list (if (string? raw)
                  (mapv str/trim (str/split raw #","))
                  (vec raw))
      raw)))

(defn value-rejection-kind
  "How a value that did not coerce is rejected: a choice (keyword) or a flag
  (true/false) outside its allowed set is :bad-value, anything else :coercion."
  [schema-entry]
  (if (#{:kw :bool} (:type schema-entry)) :bad-value :coercion))

(defn parse-node-attrs
  "Parse and coerce a flat opts-map (string values from CLI) against the schema
  for `tag-kw`. Returns {:ok attrs-map} or {:error \"message\" :kind k :attr a},
  where :kind is :unknown-tag, :unknown-attr, :missing (a required attribute
  left out), :bad-value (a choice outside the allowed set) or :coercion."
  [tag-kw opts-map]
  (let [schema (get tag-schema tag-kw)]
    (if (nil? schema)
      {:error (unknown-tag-message tag-kw) :kind :unknown-tag}
      (let [attr-schema (:attrs schema)]
        ;; Check for unknown keys
        (if-let [unknown (first (remove #(contains? attr-schema %) (keys opts-map)))]
          {:error (unknown-attr-message tag-kw unknown) :kind :unknown-attr :attr unknown}
          ;; Coerce all provided attrs
          (let [result
                (reduce
                  (fn [acc [k v]]
                    (if (:error acc)
                      acc
                      (try
                        (assoc acc k (coerce-attr-value k v (attr-schema k)))
                        (catch Exception e
                          {:error (ex-message e) :attr k
                           :kind  (value-rejection-kind (attr-schema k))}))))
                  {}
                  opts-map)]
            (if (:error result)
              result
              ;; Check required attrs
              (if-let [missing (first (for [[k aschema] attr-schema
                                            :when (and (:required? aschema)
                                                       (not (contains? result k)))]
                                        k))]
                {:error (str (name missing) " is required for :" (name tag-kw))
                 :kind :missing :attr missing}
                {:ok result}))))))))

;; ---------------------------------------------------------------------------
;; format-tree
;; ---------------------------------------------------------------------------

(defn- format-node
  "Render a single node to a string line with [nN] prefix and indentation."
  [node depth]
  (when (vector? node)
    (let [tag      (first node)
          rest-    (rest node)
          id-map   (when (and (seq rest-) (map? (first rest-))) (first rest-))
          node-id  (when id-map (:-id id-map))
          after-id (if id-map (rest rest-) rest-)
          attrs    (when (and (seq after-id) (map? (first after-id))) (first after-id))
          children (if attrs (rest after-id) after-id)
          indent   (str/join (repeat (* 2 depth) " "))
          id-str   (if node-id (str "[" node-id "] ") "")
          tag-str  (str ":" (name tag))
          ;; Content inline: the attribute map, then the text - both when a text
          ;; node carries attributes, so the line is everything `parse-tree`
          ;; needs to read the node back (LayoutEditRevealsResult)
          content  (str/join (map #(str "  " (pr-str %))
                                  (remove nil? [attrs (first (filter string? children))])))
          this-line (str indent id-str tag-str content)
          child-lines (mapcat #(when (vector? %)
                                 [(format-node % (inc depth))])
                              children)]
      (str/join "\n" (cons this-line (remove nil? child-lines))))))

(defn format-tree
  "Render the wireframe tree as an annotated string with [nN] prefixes and
  indentation matching nesting depth."
  [wireframe]
  (format-node wireframe 0))

;; ---------------------------------------------------------------------------
;; parse-tree / resolve-ids (ReplaceWireframe)
;; ---------------------------------------------------------------------------
;; A target layout is stated in the text `format-tree` prints: one node per
;; line, two spaces of indentation per level, an optional `[nX]` prefix naming
;; an existing node, the tag, then optionally the attribute map and the text as
;; EDN. Parsed, it is a WireframeTargetNode tree: a wireframe whose id map is
;; {:-id "nX"} for a named node and {} for a new one.

(def ^:private node-line-re #"^(\s*)(?:\[(n\d+)\]\s+)?:([A-Za-z][\w-]*)(.*)$")

(defn- read-content
  "The EDN forms after a line's tag: an optional attribute map, then an optional
  text. Returns {:ok [attrs-or-nil text-or-nil]} or {:error msg}."
  [s]
  (try
    (let [rdr   (java.io.PushbackReader. (java.io.StringReader. s))
          eof   ::eof
          forms (->> (repeatedly #(edn/read {:eof eof} rdr))
                     (take-while #(not= eof %))
                     vec)
          [attrs more] (if (map? (first forms)) [(first forms) (rest forms)] [nil forms])
          [text extra] (if (string? (first more)) [(first more) (rest more)] [nil more])]
      (if (seq extra)
        {:error (str "unexpected " (pr-str (first extra))
                     " - after the tag, only an attribute map and a text may follow")}
        {:ok [attrs text]}))
    (catch Exception e
      {:error (str "content does not read as EDN: " (ex-message e))})))

(defn- parse-line
  "One non-blank line as {:line n :indent k :node [tag id-map attrs? text?]} or
  {:line n :error msg}."
  [[n text]]
  (if-let [[_ indent id tag content] (re-find node-line-re text)]
    (let [{:keys [ok error]} (read-content content)]
      (if error
        {:line n :error error}
        (let [[attrs txt] ok]
          {:line n :indent (count indent)
           :node (cond-> [(keyword tag) (if id {:-id id} {})]
                   attrs (conj attrs)
                   txt   (conj txt))})))
    {:line n :error (str "not a node line; expected `[nX] :tag {attributes} \"text\"`"
                         " (the [nX] prefix only for an existing node)")}))

(defn- check-depths
  "Assign each parsed node its depth (two spaces per level from `base`),
  reporting a line indented off the two-space grid, deeper than one level below
  the node before it, or a second root."
  [base nodes]
  (:out (reduce (fn [{:keys [prev] :as acc} {:keys [line indent] :as item}]
                  (let [offset (- indent base)
                        depth  (quot offset 2)
                        err    (cond
                                 (or (neg? offset) (odd? offset))
                                 "indentation must be two spaces per level"
                                 (and prev (> depth (inc prev)))
                                 (str "indented more than one level below the line before it")
                                 (and prev (zero? depth))
                                 "a second root - a layout has exactly one :canvas at the top")]
                    (if err
                      (update acc :out conj {:line line :error err})
                      (-> acc
                          (assoc :prev depth)
                          (update :out conj (assoc item :depth depth))))))
                {:prev nil :out []}
                nodes)))

(defn- build-tree
  "The tree from [{:depth d :node v}] in document order, rooted at the first.
  Every item after the root is deeper than it (check-depths sees to that)."
  [items]
  (letfn [(child-groups [items]
            ;; each child with the descendants that follow it
            (when-let [[head & more] (seq items)]
              (let [own (take-while #(> (:depth %) (:depth head)) more)]
                (cons (cons head own) (child-groups (drop (count own) more))))))
          (build [[{:keys [node]} & descendants]]
            (into node (map build) (child-groups descendants)))]
    (build items)))

(defn parse-tree
  "Read a target layout from the text `format-tree` prints. Returns {:ok tree}
  or {:errors [{:line n :message str} ...]} naming every bad line."
  [text]
  (let [lines  (->> (str/split-lines (str text))
                    (map-indexed (fn [i l] [(inc i) l]))
                    (remove (comp str/blank? second)))
        parsed (map parse-line lines)
        base   (some :indent parsed)
        items  (if base (check-depths base (remove :error parsed)) [])
        errors (->> (concat (filter :error parsed) (filter :error items))
                    (sort-by :line)
                    (mapv (fn [{:keys [line error]}] {:line line :message error})))]
    (cond
      (seq errors) {:errors errors}
      (empty? items) {:errors [{:line nil :message "no layout given: expected at least a `:canvas` line"}]}
      :else {:ok (build-tree items)})))

(defn resolve-ids
  "The concrete wireframe a target layout describes (see resolve_ids in
  event-model.allium): the target's root is the layout's root, so naming no id
  it takes `current`'s root id; any other node naming an id keeps it; every
  other node gets a fresh id, in document order, beyond the highest id in
  `current` (from n1 when there is none), so an id the target drops is never
  handed out again within the same replacement."
  [current target]
  (let [start  (parse-long (subs (next-node-id current) 1))
        target (cond-> target
                 (and (nil? (get-in target [1 :-id])) (get-in current [1 :-id]))
                 (assoc-in [1 :-id] (get-in current [1 :-id])))]
    (letfn [(assign [n node]
              (let [[tag id-map & more] node
                    [id n]              (if-let [id (:-id id-map)]
                                          [id n]
                                          [(str "n" n) (inc n)])]
                (reduce (fn [[acc n] x]
                          (if (vector? x)
                            (let [[child n] (assign n x)] [(conj acc child) n])
                            [(conj acc x) n]))
                        [[tag (assoc id-map :-id id)] n]
                        more)))]
      (first (assign start target)))))

;; ---------------------------------------------------------------------------
;; Tag reference: `emcli wireframe tags` and the generated doc tables
;; ---------------------------------------------------------------------------

(defn- ordered-attrs
  "A tag's [attr-kw attr-schema] pairs, required ones first, the rest in their
  declared order."
  [tag]
  (let [attrs (:attrs (tag-schema tag))]
    (concat (filter (comp :required? val) attrs)
            (remove (comp :required? val) attrs))))

(defn- example-pairs
  "[attr-kw text] for each attribute `tag` requires, as an operator would type it."
  [tag]
  (for [[k {:keys [type values required?]}] (ordered-attrs tag)
        :when required?]
    [k (case type
         :kw       (name (first values))
         :bool     "true"
         :str-list "<a,b,c>"
         (str "<" (name k) ">"))]))

(defn example-attrs
  "The attributes the example for `tag` supplies: exactly its required ones,
  with placeholder text."
  [tag]
  (into {} (example-pairs tag)))

(defn- example-command [tag]
  (str/join " " (concat ["emcli wireframe add-node --element <screen id> --tag" (name tag)]
                        (when (:text-children? (tag-schema tag)) ["--text \"<text>\""])
                        (map (fn [[k v]] (str "--" (name k) " \"" v "\"")) (example-pairs tag)))))

(defn- value-hint
  "What an attribute's value looks like on the command line."
  [{:keys [type values]}]
  (case type
    :kw       (str/join "|" (map name values))
    :bool     "true|false"
    :str-list "comma-separated list"
    "text"))

;; What the attributes shared across the vocabulary mean; the others speak for
;; themselves through their name and value hint.
(def ^:private attr-notes
  {:field-name    "name of a field on this screen"
   :command-input "marks the node as input to a command"})

(defn- holds [schema]
  (cond (:leaf? schema)          "none"
        (:text-children? schema) "text (given with --text)"
        :else                    "child nodes (add them with --parent <this node's id>)"))

(defn tag-list-text
  "Every tag an operator can add, by role, each with its purpose in one line.
  The canvas is left out: it is every layout's root and never added."
  []
  (let [width (->> tag-groups (mapcat second) (map (comp count name)) (apply max) (+ 2))]
    (str/join "\n"
              (concat
                (for [[group tags] tag-groups
                      :let [addable (remove #{:canvas} tags)]
                      line (cons (name group)
                                 (for [t addable]
                                   (str "  " (format (str "%-" width "s") (name t))
                                        (:doc (tag-schema t)))))]
                  line)
                ["" "Attributes and an example: emcli wireframe tags --tag <name>"]))))

(defn tag-detail-text
  "One tag's purpose, what it holds, its attributes and a command that adds it,
  or nil when `tag` is not a tag."
  [tag]
  (when-let [schema (tag-schema tag)]
    (let [attrs (ordered-attrs tag)]
      (str/join "\n"
                (concat
                  [(str (name tag) " - " (:doc schema))
                   (str "children: " (holds schema))]
                  (if (seq attrs)
                    (let [width (apply max (map (comp count value-hint val) attrs))]
                      (cons "attributes:"
                            (for [[k a] attrs]
                              (str/trimr (format (str "  %-16s %-" width "s  %s")
                                                 (str "--" (name k)) (value-hint a)
                                                 (str/join ", " (remove nil? [(when (:required? a) "required")
                                                                             (attr-notes k)])))))))
                    ["attributes: none"])
                  (when-not (= :canvas tag)
                    ["example:" (str "  " (example-command tag))]))))))

;; --- remedies (RejectedLayoutEditNamesRemedy) --------------------------------
;; A rejection over a tag's attributes says what the tag admits, and where it
;; can, the corrected command - so the next attempt can succeed.

(defn attr-remedy
  "What `tag` admits, one attribute per line as a flag, required ones first and
  marked, each with the values it takes."
  [tag]
  (let [attrs (ordered-attrs tag)]
    (if (seq attrs)
      (str/join "\n" (cons (str ":" (name tag) " admits:")
                           (for [[k a] attrs]
                             (str "  --" (name k) (when (:required? a) " (required)")
                                  " " (value-hint a)))))
      (str ":" (name tag) " admits no attributes"))))

(def ^:private attr-problem-re
  #"unknown attribute|is required|not in allowed set|must be a (?:keyword|boolean|string|vector)")

(defn remedy-for-errors
  "The attr-remedy of every tag whose node a validation error faults over its
  attributes, or nil when no error concerns attributes."
  [wireframe errors]
  (let [tags (into []
                   (comp (filter #(re-find attr-problem-re (str (:message %))))
                         (keep #(first (find-node wireframe (:node-id %))))
                         (filter tag-schema)
                         (distinct))
                   errors)]
    (when (seq tags)
      (str/join "\n" (map attr-remedy tags)))))

(defn corrected-add-command
  "The add command an operator meant, derived from a rejected one: attributes
  the tag does not admit are dropped, and every required attribute left open is
  supplied - with a dropped attribute's value where there is one (a button's
  --text \"Submit\" becomes --label \"Submit\"), a placeholder otherwise.
  `verb` is \"add-node\" or \"add-node-before\"; `attrs` the operator's
  attribute flags as given."
  [{:keys [verb element before parent tag text attrs]}]
  (let [schema   (:attrs (tag-schema tag))
        kept     (select-keys attrs (keys schema))
        dropped  (keep (fn [[k v]] (when-not (contains? schema k) v)) attrs)
        open     (for [[k a] (ordered-attrs tag)
                       :when (and (:required? a) (not (contains? kept k)))]
                   k)
        required (zipmap open (map #(or %2 (str "<" (name %1) ">"))
                                   open (concat dropped (repeat nil))))
        flags    (for [[k _] (ordered-attrs tag)
                       :let  [v (or (required k) (kept k))]
                       :when (some? v)]
                   [k v])]
    (str/join " "
              (concat ["emcli wireframe" verb "--element" (str element)]
                      (when before ["--before" before])
                      ["--tag" (name tag)]
                      (when parent ["--parent" parent])
                      (when (and text (:text-children? (tag-schema tag))) ["--text" (pr-str (str text))])
                      (mapcat (fn [[k v]] [(str "--" (name k)) (pr-str (str v))]) flags)))))

(defn- md-attr [[k {:keys [type values]}]]
  (str "`" (name k) "`"
       (case type
         :kw       (str " (" (str/join ", " (map name values)) ")")
         :bool     " (true/false)"
         :str-list " (comma-separated list)"
         "")))

(defn tag-reference-markdown
  "The per-tag attribute tables, one per role, as markdown."
  []
  (str/join "\n"
            (for [[group tags] tag-groups]
              (str/join "\n"
                        (concat
                          [(str "### " (str/capitalize (name group)))
                           ""
                           "| tag | purpose | holds | required attributes | optional attributes |"
                           "|-----|---------|-------|---------------------|---------------------|"]
                          (for [t tags
                                :let [schema (tag-schema t)
                                      attrs  (ordered-attrs t)
                                      cell   #(str/join ", " (map md-attr (filter % attrs)))]]
                            (str "| `" (name t) "` | " (:doc schema)
                                 " | " (cond (:leaf? schema) "nothing"
                                             (:text-children? schema) "text"
                                             :else "child nodes")
                                 " | " (cell (comp :required? val))
                                 " | " (cell (complement (comp :required? val))) " |"))
                          [""])))))
