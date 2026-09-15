(ns emcli.jsonschema
  "A small, pure, data-driven validator for the subset of JSON Schema that
  docs/change-stream.schema.json actually uses: `type` (including nullable
  unions), `required`, `enum`, `const`, `oneOf`, `properties`,
  `additionalProperties` (false or a schema), `items`, `prefixItems`,
  `minItems` and `$ref`/`$defs`.

  It lives in test/ (not src/) on purpose: it is a check tool, not runtime
  behaviour. It exists because a real JSON-Schema library is out of bounds —
  babashka cannot load arbitrary JVM jars, and the project adds no dependencies
  (AGENTS.md: no new dependencies; single-binary goal).

  `validate` returns a vector of violations, each a plain map
  {:pointer <JSON pointer> :message <str> :expected <data> :found <data>};
  empty means the value conforms. Pure, no side effects."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; JSON types
;; ---------------------------------------------------------------------------

(defn- json-type
  "The JSON type of a value as parsed by cheshire: :null, :boolean, :string,
  :integer, :number, :object or :array."
  [v]
  (cond
    (nil? v)        :null
    (boolean? v)    :boolean
    (string? v)     :string
    (integer? v)    :integer
    (number? v)     :number
    (map? v)        :object
    (vector? v)     :array
    (sequential? v) :array
    :else           :unknown))

(defn- type-ok? [value t]
  (let [jt (json-type value)]
    (case t
      "null"    (= jt :null)
      "boolean" (= jt :boolean)
      "string"  (= jt :string)
      "integer" (= jt :integer)
      "number"  (contains? #{:integer :number} jt)
      "object"  (= jt :object)
      "array"   (= jt :array)
      false)))

(defn- brief
  "A short rendering of a value for a violation's :found slot, so a whole
  payload does not end up in an error line."
  [v]
  (if (coll? v)
    (str "<" (name (json-type v)) " of " (count v) ">")
    v))

;; ---------------------------------------------------------------------------
;; JSON pointer
;; ---------------------------------------------------------------------------

(defn- ptr [pointer k]
  (str pointer "/" (-> (str k) (str/replace "~" "~0") (str/replace "/" "~1"))))

;; ---------------------------------------------------------------------------
;; $ref
;; ---------------------------------------------------------------------------

(defn- resolve-ref
  "Resolve a local `#/...` reference against the root schema. Only the local
  form this schema uses is supported; anything else is a hard error rather than
  a silent pass."
  [root ref]
  (if (str/starts-with? ref "#/")
    (get-in root (str/split (subs ref 2) #"/"))
    (throw (ex-info (str "unsupported $ref: " ref) {:ref ref}))))

(defn- deref-schema
  "The schema with a leading `$ref` resolved against `root`. Sibling keywords,
  which this schema uses only for `description` prose, are ignored."
  [root schema]
  (if (and (map? schema) (contains? schema "$ref"))
    (resolve-ref root (get schema "$ref"))
    schema))

;; ---------------------------------------------------------------------------
;; Keyword checks
;; ---------------------------------------------------------------------------

(declare validate-node)

(defn- check-type [schema value pointer]
  (when-let [t (get schema "type")]
    (let [types (if (string? t) [t] t)]
      (when-not (some #(type-ok? value %) types)
        [{:pointer  pointer
          :message  (str "expected type " (str/join " or " types)
                         ", found " (name (json-type value)))
          :expected types
          :found    (name (json-type value))}]))))

(defn- check-enum [schema value pointer]
  (when-let [allowed (get schema "enum")]
    (when-not (contains? (set allowed) value)
      [{:pointer  pointer
        :message  (str "expected one of " (pr-str allowed) ", found " (pr-str value))
        :expected allowed
        :found    value}])))

(defn- check-const [schema value pointer]
  (when (contains? schema "const")
    (let [c (get schema "const")]
      (when-not (= c value)
        [{:pointer  pointer
          :message  (str "expected " (pr-str c) ", found " (pr-str value))
          :expected c
          :found    value}]))))

(defn- check-one-of
  "Exactly one subschema must match. When none match, the violations of the
  closest branch are surfaced (deep pointers and all) rather than a bare
  'matches no shape', so drift is reported where it actually is."
  [root schema value pointer]
  (when-let [subs (get schema "oneOf")]
    (let [results (mapv #(validate-node root % value pointer) subs)
          matched (count (filter empty? results))]
      (cond
        (= 1 matched) []
        (zero? matched) (let [best (apply min-key count results)]
                          (if (seq best)
                            best
                            [{:pointer  pointer
                              :message  (str "matches none of the " (count subs) " allowed shapes")
                              :expected (mapv #(or (get % "$ref") (get % "description")) subs)
                              :found    (brief value)}]))
        :else
        [{:pointer  pointer
          :message  (str "matches " matched " of the " (count subs)
                         " allowed shapes; exactly one is required")
          :expected "exactly one shape"
          :found    (brief value)}]))))

(defn- check-object [root schema value pointer]
  (when (map? value)
    (let [props (get schema "properties")
          req   (get schema "required")
          addl  (get schema "additionalProperties")]
      (into []
            (concat
             (for [k req :when (not (contains? value k))]
               {:pointer  pointer
                :message  (str "missing required property \"" k "\"")
                :expected k
                :found    :absent})
             (mapcat (fn [[k v]]
                       (cond
                         (and props (contains? props k))
                         (validate-node root (get props k) v (ptr pointer k))
                         (false? addl)
                         [{:pointer  (ptr pointer k)
                           :message  (str "unexpected property \"" k "\"")
                           :expected (or (some-> props keys sort vec) [])
                           :found    k}]
                         (map? addl)
                         (validate-node root addl v (ptr pointer k))
                         :else []))
                     value))))))

(defn- check-array [root schema value pointer]
  (when (sequential? value)
    (let [prefix (get schema "prefixItems")
          items  (get schema "items")
          minit  (get schema "minItems")
          n      (count value)]
      (into []
            (concat
             (when (and minit (< n minit))
               [{:pointer pointer
                 :message (str "expected at least " minit " items, found " n)
                 :expected minit
                 :found    n}])
             (mapcat (fn [i v]
                       (cond
                         (and prefix (< i (count prefix)))
                         (validate-node root (nth prefix i) v (ptr pointer i))
                         (map? items)
                         (validate-node root items v (ptr pointer i))
                         :else []))
                     (range n) value))))))

(defn- validate-node
  "Every violation of `value` against `schema`, with `root` available to resolve
  `$ref`s."
  [root schema value pointer]
  (let [schema (deref-schema root schema)]
    (cond
      (or (nil? schema) (true? schema)) []
      (false? schema) [{:pointer pointer
                        :message "no value allowed here"
                        :expected :nothing
                        :found    (brief value)}]
      :else (into []
                  (concat (check-type schema value pointer)
                          (check-enum schema value pointer)
                          (check-const schema value pointer)
                          (check-one-of root schema value pointer)
                          (check-object root schema value pointer)
                          (check-array root schema value pointer))))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn validate
  "Validate `data` against the root `schema` (a cheshire-parsed map, string
  keys). Returns a vector of violations; empty means valid."
  [schema data]
  (validate-node schema schema data ""))

(defn validate-ref
  "Validate `data` against a `$ref` into `schema`, e.g.
  (validate-ref schema \"#/$defs/WireframeNode\" node). Returns a vector of
  violations; empty means valid."
  [schema ref data]
  (validate-node schema {"$ref" ref} data ""))
