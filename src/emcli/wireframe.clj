(ns emcli.wireframe
  "Wireframe DSL for screen elements: schema, validation, navigation, mutation,
  and rendering. Wireframes are hiccup-like EDN vectors embedded in screen
  element maps under the :wireframe key."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Tag schema
;; ---------------------------------------------------------------------------

;; Each entry: {:attrs {attr-kw {:type :kw|:bool|:str|:str-list :required? bool :values #{...}}}
;;              :leaf? bool :text-children? bool}
;; :-id is never in attrs — it is reserved and handled separately everywhere.
(def tag-schema
  {:screen      {:attrs {} :leaf? false :text-children? false}
   :row         {:attrs {:align {:type :kw :values #{:start :center :end :between}}
                         :gap   {:type :kw :values #{:sm :md :lg}}}
                 :leaf? false :text-children? false}
   :col         {:attrs {:align {:type :kw :values #{:start :center :end :between}}
                         :gap   {:type :kw :values #{:sm :md :lg}}
                         :width {:type :kw :values #{:narrow :wide :auto :full}}}
                 :leaf? false :text-children? false}
   :h1          {:attrs {} :leaf? false :text-children? true}
   :h2          {:attrs {} :leaf? false :text-children? true}
   :h3          {:attrs {} :leaf? false :text-children? true}
   :text        {:attrs {:align {:type :kw :values #{:left :center :right}}
                         :tone  {:type :kw :values #{:default :muted :danger :success}}}
                 :leaf? false :text-children? true}
   :span        {:attrs {:tone {:type :kw :values #{:default :muted :danger :success}}}
                 :leaf? false :text-children? true}
   :divider     {:attrs {} :leaf? true :text-children? false}
   :input       {:attrs {:type          {:type :kw :values #{:text :email :password :number :tel :url}}
                         :label         {:type :str}
                         :placeholder   {:type :str}
                         :required      {:type :bool}
                         :field-name    {:type :str}
                         :command-input {:type :bool}}
                 :leaf? true :text-children? false}
   :textarea    {:attrs {:label         {:type :str}
                         :placeholder   {:type :str}
                         :required      {:type :bool}
                         :field-name    {:type :str}
                         :command-input {:type :bool}}
                 :leaf? true :text-children? false}
   :dropdown    {:attrs {:options        {:type :str-list :required? true}
                         :label          {:type :str}
                         :required       {:type :bool}
                         :field-name     {:type :str}
                         :command-input  {:type :bool}}
                 :leaf? true :text-children? false}
   :checkbox    {:attrs {:label         {:type :str}
                         :default       {:type :bool}
                         :field-name    {:type :str}
                         :command-input {:type :bool}}
                 :leaf? true :text-children? false}
   :toggle      {:attrs {:label         {:type :str}
                         :default       {:type :bool}
                         :field-name    {:type :str}
                         :command-input {:type :bool}}
                 :leaf? true :text-children? false}
   :button      {:attrs {:label         {:type :str :required? true}
                         :variant       {:type :kw :values #{:primary :secondary :ghost :danger}}
                         :disabled      {:type :bool}
                         :command-input {:type :bool}}
                 :leaf? true :text-children? false}
   :icon-button {:attrs {:icon          {:type :str :required? true}
                         :aria-label    {:type :str :required? true}
                         :command-input {:type :bool}}
                 :leaf? true :text-children? false}
   :link        {:attrs {:label         {:type :str :required? true}
                         :command-input {:type :bool}}
                 :leaf? true :text-children? false}
   :image       {:attrs {:alt    {:type :str :required? true}
                         :aspect {:type :kw :values #{:square :wide :tall}}}
                 :leaf? true :text-children? false}
   :icon        {:attrs {:name {:type :str :required? true}
                         :size {:type :kw :values #{:sm :md :lg}}}
                 :leaf? true :text-children? false}
   :alert       {:attrs {:text {:type :str :required? true}
                         :type {:type :kw :values #{:info :warning :danger :success}}}
                 :leaf? true :text-children? false}})

(def allowed-tags (set (keys tag-schema)))

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
;; strip-ids
;; ---------------------------------------------------------------------------

(defn strip-ids
  "Remove all :-id keys from a wireframe tree, merging the id-map and
  content-map into one map per node. Preserves the attrs map even when empty
  (so callers can navigate by index). Preserves child order."
  [node]
  (when (vector? node)
    (let [tag      (first node)
          rest-    (rest node)
          maps     (take-while map? rest-)
          children (drop-while map? rest-)
          attrs    (dissoc (apply merge maps) :-id)
          ;; Preserve document order: strings and child vectors interleaved
          kids     (mapv #(if (vector? %) (strip-ids %) %) children)]
      (if (seq maps)
        (into [tag attrs] kids)
        (into [tag] kids)))))

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
  "Return the next node id string ('nN'), one past the highest existing
  numeric suffix in the tree. Allocates monotonically; never reuses ids."
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
        [{:node-id node-id :message (str "unknown tag :" (name tag))}]

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
                          (and vals (keyword? v) (not (contains? vals v)))
                          [{:node-id node-id :message (str (name k) " value " v " not in allowed set "
                                                            (str/join ", " (map name vals)))}]
                          :else []))
                      [{:node-id node-id :message (str "unknown attribute :" (name k))}]))
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
              child-errs
              (when-not (or (:leaf? schema) (:text-children? schema))
                (mapcat #(when (vector? %) (validate-node-with-ids %)) children))]
          (concat attr-errs req-errs leaf-errs text-errs child-errs))))))

(defn validate
  "Structural validation. Works on the original tree (preserving :-id for error
  attribution), checking tags, required attrs, value types/allowed sets,
  leaf/text-child nesting, and :screen root.
  Returns {:valid? true} or {:valid? false :errors [{:node-id str :message str}]}."
  [wireframe]
  (let [root-err (when (not= :screen (first wireframe))
                   [{:node-id nil :message "root element must be :screen"}])
        errs     (concat root-err (validate-node-with-ids wireframe))]
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

(defn validate-semantics
  "Semantic validation: all :field-name values must exist in the screen's
  :fields array. Returns {:valid? true} or {:valid? false :errors [...]}."
  [wireframe screen-element]
  (let [field-set (set (map :name (:fields screen-element)))
        refs      (field-names-in-node wireframe)
        errs      (for [{:keys [node-id field-name]} refs
                        :when (not (contains? field-set field-name))]
                    {:node-id node-id
                     :message (str "Field '" field-name "' does not exist on screen")})]
    (if (seq errs)
      {:valid? false :errors (vec errs)}
      {:valid? true})))

;; ---------------------------------------------------------------------------
;; append-child-at / assoc-attr-at / delete-node-at
;; ---------------------------------------------------------------------------

(defn append-child-at
  "Append `child-vec` as a new child of the node identified by `parent-node-id`.
  Assigns a fresh :-id to the child (inserted as the second element, after tag)."
  [wireframe parent-node-id child-vec]
  (let [new-id (next-node-id wireframe)
        tag    (first child-vec)
        rest-  (vec (rest child-vec))
        child  (into [tag {:-id new-id}] rest-)
        path   (find-node-path wireframe parent-node-id)
        update-node #(conj % child)]
    (if (seq path)
      (update-in wireframe path update-node)
      (update-node wireframe))))

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
                  (if (and vals (not (contains? vals kw)))
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

(defn parse-node-attrs
  "Parse and coerce a flat opts-map (string values from CLI) against the schema
  for `tag-kw`. Returns {:ok attrs-map} or {:error \"message\"}."
  [tag-kw opts-map]
  (let [schema (get tag-schema tag-kw)]
    (if (nil? schema)
      {:error (str "unknown tag :" (name tag-kw))}
      (let [attr-schema (:attrs schema)]
        ;; Check for unknown keys
        (if-let [unknown (first (remove #(contains? attr-schema %) (keys opts-map)))]
          {:error (str "unknown attribute :" (name unknown) " for :" (name tag-kw))}
          ;; Coerce all provided attrs
          (let [result
                (reduce
                  (fn [acc [k v]]
                    (if (:error acc)
                      acc
                      (try
                        (assoc acc k (coerce-attr-value k v (attr-schema k)))
                        (catch Exception e
                          {:error (ex-message e)}))))
                  {}
                  opts-map)]
            (if (:error result)
              result
              ;; Check required attrs
              (if-let [missing (first (for [[k aschema] attr-schema
                                            :when (and (:required? aschema)
                                                       (not (contains? result k)))]
                                        k))]
                {:error (str (name missing) " is required for :" (name tag-kw))}
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
          ;; Content: string children inline, attrs map inline
          content  (cond
                     (seq (filter string? children))
                     (str "  " (pr-str (first (filter string? children))))
                     attrs
                     (str "  " (pr-str (dissoc attrs)))
                     :else "")
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
