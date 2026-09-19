(ns emcli.query
  "ModelQuery (event-model.allium): a read-only structural query over the model.

  A query is a root plus a '|'-separated pipeline of stages applied left to
  right over a collection. It follows relations outward from a root and returns
  the bounded set of entities the question reaches, optionally carrying the
  association (edge) it traversed - the relational counterpart to name
  resolution. Pure and non-mutating: no rule fires (ReadOnly).

  Entity kinds (roots / landing points): timeline, swimlane, slice, element,
  specification, step. The model is the implicit context, never a root
  (ModelIsImplicitContext). The associations - placement (slice <-> element)
  and connection (element -> element) - are edges, never roots; their own
  attributes are reachable only by explicit projection, e.g.
  `slice:7 | elements {index}` (AssociationsAreNeverRoots,
  EdgeAttributesAreProjected).

  The query layer speaks the spec's QueryKind vocabulary (`:step`, not the
  model's `:spec-step`); model types appear only inside the relation registry."
  (:require [clojure.string :as str]
            [emcli.model :as m]))

;; ---------------------------------------------------------------------------
;; Vocabulary
;; ---------------------------------------------------------------------------

(def ^:private kind-tokens
  "Root / relation-kind token -> QueryKind (QueryKind enum)."
  {"timeline" :timeline, "swimlane" :swimlane, "slice" :slice,
   "element" :element, "specification" :specification, "step" :step})

(def ^:private token-of
  (into {} (map (fn [[t k]] [k t])) kind-tokens))

(def ^:private query-kinds
  [:timeline :swimlane :slice :element :specification :step])

(defn- kind-token
  "A kind token, singular or plural (slice = slices), else nil."
  [s]
  (let [t (str/lower-case (str/trim (str s)))]
    (cond
      (kind-tokens t)        (kind-tokens t)
      (str/ends-with? t "s") (kind-tokens (subs t 0 (dec (count t))))
      :else                  nil)))

(defn- token-label
  "Human label for a relation token (a QueryKind, or outgoing/incoming)."
  [k]
  (if (#{:outgoing :incoming} k) (name k) (token-of k)))

(defn- model-type
  "The model entity type behind a QueryKind (only :step differs)."
  [k] (if (= k :step) :spec-step k))

(defn- kind-of
  "The QueryKind behind a model entity type (only :spec-step differs)."
  [t] (if (= t :spec-step) :step t))

;; The closed field set ModelQuery.ProjectableFieldsAreClosed states, in two
;; halves.
;;
;; The row's own display fields: its identity, the entity CATEGORY, and its
;; location. `kind` is the category (element, slice, ...), NOT what an element is
;; - that is the element's own `element_type` attribute, below. The two words are
;; kept apart deliberately.
(defn- row-fields
  "The row's own display fields. Every row carries its identity, its CATEGORY and
  its breadcrumb; `name` only where the entity has a name or title to show - a
  SpecStep has neither, so `name` is refused on a step row rather than accepted and
  resolving to nil."
  [kind]
  (if (= kind :step)
    #{"id" "kind" "breadcrumb"}
    #{"id" "kind" "name" "breadcrumb"}))

;; The attributes each kind's entity declaration carries (event-model.allium).
;; The model is schemaless - a canonical record IS the stored map, with no list of
;; its keys - so the declarations cannot be read off at run time and are written
;; down here instead, split by where the value comes from. A change to an entity
;; declaration in the spec belongs in this pair: the spec's
;; ProjectableFieldsAreClosed is the authority, and query-test covers the set,
;; including that a name on one entity is refused on another.
(def ^:private stored-fields
  {:timeline      #{"model" "title"}
   :swimlane      #{"model" "name" "index"}
   :slice         #{"timeline" "title" "index" "slice_type" "status"}
   :element       #{"model" "name" "element_type" "context" "fields" "swimlane"
                    "image_url" "wireframe" "field_origins"}
   :specification #{"slice" "title"}
   :step          #{"spec" "clause" "index" "element" "is_error" "error_name"
                    "expect_empty" "examples"}})

;; The declared attributes a record does NOT store: reverse references (a
;; timeline's slices), derived projections (a slice's commands) and derived
;; verdicts (is_complete). They are as nameable as any other declared attribute -
;; the spec returns them whole when asked - so they resolve through the model's
;; navigation rather than by reading a key off the record.
(def ^:private derived-fields
  {:timeline      #{"slices"}
   :swimlane      #{}
   :slice         #{"placements" "specifications" "commands" "events" "read_models"
                    "screens" "automations" "is_complete"}
   :element       #{"placements" "outgoing" "incoming" "is_information_complete"}
   :specification #{"steps" "given_steps" "when_steps" "then_steps" "when_commands"
                    "then_read_models" "is_complete"}
   :step          #{}})

(def ^:private entity-fields (merge-with into stored-fields derived-fields))

(defn- derived-value
  "The value of a DERIVED attribute for an entity of `kind`, or nil when `k` is not
  one of them."
  [store kind entity k]
  (let [id (:id entity)]
    (case k
      "slices"                  (when (= kind :timeline) (m/slices store id))
      "placements"              (case kind
                                  :slice   (m/placements store id)
                                  :element (m/element-placements store id))
      "specifications"          (when (= kind :slice) (m/specs store id))
      "commands"                (when (= kind :slice) (m/slice-commands store id))
      "events"                  (when (= kind :slice) (m/slice-events store id))
      "read_models"             (when (= kind :slice) (m/slice-read-models store id))
      "screens"                 (when (= kind :slice) (m/slice-screens store id))
      "automations"             (when (= kind :slice) (m/slice-automations store id))
      "is_complete"             (case kind
                                  :slice         (m/slice-complete? store entity)
                                  :specification (m/spec-complete? store entity))
      "outgoing"                (when (= kind :element) (m/outgoing store id))
      "incoming"                (when (= kind :element) (m/incoming store id))
      "is_information_complete" (when (= kind :element)
                                  (m/information-complete? store entity))
      "steps"                   (when (= kind :specification) (m/spec-steps store id))
      "given_steps"             (when (= kind :specification)
                                  (filterv #(= :given_step (:clause %)) (m/spec-steps store id)))
      "when_steps"              (when (= kind :specification)
                                  (filterv #(= :when_step (:clause %)) (m/spec-steps store id)))
      "then_steps"              (when (= kind :specification)
                                  (filterv #(= :then_step (:clause %)) (m/spec-steps store id)))
      "when_commands"           (when (= kind :specification) (m/spec-when-commands store id))
      "then_read_models"        (when (= kind :specification) (m/spec-then-read-models store id))
      nil)))

(defn- accepted-fields
  "Every field name a stage may use on a row of `kind`: the row's display fields
  and the entity's declared attributes, plus any edge key a projection upstream
  attached (an association is projected under its own key)."
  [kind projected]
  (into (into (row-fields kind) (get entity-fields kind)) projected))

(defn query-error
  "A rejected query: the one error shape the engine raises, and the shape a
  caller-supplied name resolver raises too, so the boundary maps them all alike."
  [msg]
  (ex-info msg {:error :invalid-query :message msg}))

(defn- normalize-relation
  "A relation token: a QueryKind (slice = slices) or a direction
  (:outgoing / :incoming), else nil."
  [token]
  (case (str/lower-case (str/trim (str token)))
    "outgoing" :outgoing
    "incoming" :incoming
    (kind-token token)))

;; ---------------------------------------------------------------------------
;; Relation registry (RelationVocabulary)
;; ---------------------------------------------------------------------------

;; Each relation maps (from-kind, token) -> {:to kind :navigate fn :edge-key kw?},
;; where kinds are QueryKinds and the token is a QueryKind or a direction. Every
;; containment foreign key is traversable in both directions; only model-scoped
;; foreign keys are excluded, because the model is the implicit context.
;; `:navigate` returns a seq of {:entity e :edge assoc?}; the association, when
;; the relation traverses one, is the edge.
(def ^:private relations
  [;; containment (bidirectional FK)
   {:from :timeline      :to :slice         :token :slice
    :navigate (fn [s e] (map (fn [x] {:entity x}) (m/slices s (:id e))))}
   {:from :slice         :to :timeline      :token :timeline
    :navigate (fn [s e] (when-let [x (m/fetch s :timeline (:timeline e))] [{:entity x}]))}
   {:from :slice         :to :specification :token :specification
    :navigate (fn [s e] (map (fn [x] {:entity x}) (m/specs s (:id e))))}
   {:from :specification :to :slice         :token :slice
    :navigate (fn [s e] (when-let [x (m/fetch s :slice (:slice e))] [{:entity x}]))}
   {:from :specification :to :step          :token :step
    :navigate (fn [s e] (map (fn [x] {:entity x}) (m/spec-steps s (:id e))))}
   {:from :step          :to :specification :token :specification
    :navigate (fn [s e] (when-let [x (m/fetch s :specification (:spec e))] [{:entity x}]))}
   {:from :step          :to :element       :token :element
    :navigate (fn [s e] (when-let [x (m/step-element s e)] [{:entity x}]))}
   {:from :element       :to :step          :token :step
    :navigate (fn [s e] (map (fn [x] {:entity x}) (m/by-field s :spec-step :element (:id e))))}
   {:from :element       :to :swimlane      :token :swimlane
    :navigate (fn [s e] (when-let [x (m/fetch s :swimlane (:swimlane e))] [{:entity x}]))}
   {:from :swimlane      :to :element       :token :element
    :navigate (fn [s e] (map (fn [x] {:entity x}) (m/by-field s :element :swimlane (:id e))))}
   ;; associations (slice <-> element via placement)
   {:from :slice         :to :element       :token :element
    :edge-key :placement :edge-fields [:index]
    :navigate (fn [s e]
                (for [p (m/placements s (:id e))
                      :let [x (m/placement-element s p)] :when x]
                  {:entity x :edge p}))}
   {:from :element       :to :slice         :token :slice
    :edge-key :placement :edge-fields [:index]
    :navigate (fn [s e]
                (for [p (m/element-placements s (:id e))
                      :let [x (m/fetch s :slice (:slice p))] :when x]
                  {:entity x :edge p}))}
   ;; associations (element -> element via connection, directional)
   {:from :element       :to :element       :token :outgoing
    :edge-key :connection :edge-fields [:derivations]
    :navigate (fn [s e]
                (for [c (m/outgoing s (:id e))
                      :let [x (m/fetch s :element (:to c))] :when x]
                  {:entity x :edge c}))}
   {:from :element       :to :element       :token :incoming
    :edge-key :connection :edge-fields [:derivations]
    :navigate (fn [s e]
                (for [c (m/incoming s (:id e))
                      :let [x (m/fetch s :element (:from c))] :when x]
                  {:entity x :edge c}))}])

(defn- relation
  "Resolve a follow token from `from-kw`, or throw naming the alternatives."
  [from-kw token]
  (let [cands (filter #(and (= from-kw (:from %)) (= token (:token %))) relations)]
    (if (= 1 (count cands))
      (first cands)
      (let [alts (->> relations
                      (filter #(= from-kw (:from %)))
                      (map :token)
                      distinct
                      (map token-label)
                      (str/join ", "))]
        (throw (query-error
                (str "no relation from " (token-label from-kw) " to '"
                     (token-label token) "'; from " (token-label from-kw)
                     " you can go to " alts)))))))

;; ---------------------------------------------------------------------------
;; Parsing
;; ---------------------------------------------------------------------------

(defn- parse-root [s]
  (let [[kt nm] (str/split (str/trim s) #":" 2)
        k       (kind-token kt)]
    (when-not k
      (throw (query-error
              (str "unknown root '" (str/trim kt) "'; roots are "
                   (str/join " | " (map token-of query-kinds))
                   " (the model is the implicit context; placements and connections are edges, not roots)"))))
    (cond
      (nil? nm) {:kind k}
      :else     (let [v (str/trim nm)]
                  (if (re-matches #"-?\d+" v)
                    ;; parse-long is nil past Long range, which would leave no id and
                    ;; silently answer the whole kind - a root names ONE entity, and
                    ;; one that names nothing is rejected (see root-items)
                    (let [id (parse-long v)]
                      (when (nil? id)
                        (throw (query-error (str "id is out of range: '" v "'"))))
                      {:kind k :id id})
                    {:kind k :name (str/replace v #"^\"|\"$" "")})))))

(defn- parse-value [v]
  (let [v (str/trim v)]
    (if (and (>= (count v) 2) (str/starts-with? v "\"") (str/ends-with? v "\""))
      (subs v 1 (dec (count v)))
      v)))

(def ^:private filter-re
  ;; The comparator word is matched case-insensitively, like every other keyword
  ;; in the language (`IN` = `in`); a field name and an operand keep their case.
  #"(?si)^([A-Za-z_][A-Za-z0-9_]*)\s*(!=|=|~|\bin\b)\s*(.*)$")

(defn- parse-filter [s]
  (if-let [[_ f op val] (re-matches filter-re (str/trim s))]
    (let [op (str/lower-case op)
          _  (when (= op "~")
               ;; Compiled here so a malformed pattern is REJECTED, like every other
               ;; bad operand, rather than thrown per row once the pipeline runs.
               (try (re-pattern (parse-value val))
                    (catch Exception _
                      (throw (query-error
                              (str "malformed regular expression: " (pr-str val)))))))]
      {:kind       :where
       :field      f
       :comparator ({"=" :equals "!=" :not_equals "~" :matches "in" :in_set} op)
       :operand    (if (= op "in")
                     (->> (str/split (str/replace (str/trim val) #"^\(|\)$" "") #",")
                          (map (comp parse-value str/trim))
                          (remove str/blank?)
                          vec)
                     (parse-value val))})
    (throw (query-error
            (str "malformed where stage: '" s "' (expected `where <field> <op> <value>`)")))))

(defn- parse-order [s]
  (let [t (str/trim s)]
    (when (str/blank? t)
      (throw (query-error "order needs a field: `order <field>` or `order -<field>`")))
    {:kind :order :field (if (str/starts-with? t "-") (subs t 1) t)
     :descending (str/starts-with? t "-")}))

(defn- parse-select [s]
  (let [fs (->> (str/split (str s) #",") (map str/trim) (remove str/blank?) vec)]
    (when (empty? fs)
      (throw (query-error "select needs at least one field: `select id,name`")))
    {:kind :select :fields fs}))

(defn- parse-projection [p]
  (cond
    (nil? p)                 nil
    (#{"" "*"} (str/trim p)) :all
    :else                    (->> (str/split p #",") (map str/trim) (remove str/blank?) vec)))

;; A follow stage names a relation to traverse. `target` is the QueryKind to
;; land on (QueryKind), `direction` is set only for the element -> element
;; connection relation, and `projection` is an explicit edge projection.
(defn- parse-follow [s]
  (if-let [[_ tok proj] (re-matches #"(?s)^([A-Za-z_][A-Za-z0-9_]*)\s*(?:\{([^}]*)\})?$" (str/trim s))]
    (if-let [n (normalize-relation tok)]
      {:kind       :follow
       :target     (if (#{:outgoing :incoming} n) :element n)
       :direction  (when (#{:outgoing :incoming} n) n)
       :projection (parse-projection proj)}
      (throw (query-error
              (str "unknown relation '" tok "'; relations are kinds ("
                   (str/join " | " (map token-of query-kinds))
                   ") or outgoing | incoming"))))
    (throw (query-error (str "malformed stage: '" s "'")))))

(defn- parse-stage [s]
  (let [t  (str/trim s)
        lt (str/lower-case t)]
    (cond
      (str/blank? t)                  (throw (query-error "empty stage in the query pipeline"))
      (= lt "count")                  {:kind :count}
      (= lt "distinct")               {:kind :distinct}
      (re-matches #"limit\s+\d+" lt)  (let [n (parse-long (re-find #"\d+" lt))]
                                         (when (nil? n)
                                           (throw (query-error
                                                   (str "limit is out of range: '" t "'"))))
                                         {:kind :limit :limit n})
      (str/starts-with? lt "where ")  (parse-filter (subs t 6))
      (str/starts-with? lt "order ")  (parse-order (subs t 6))
      (str/starts-with? lt "select ") (parse-select (subs t 7))
      :else                           (parse-follow t))))

(defn parse-query
  "Parse a query string into {:root <root> :stages [<stage> ...]}. Throws
  ex-info {:error :invalid-query} on a malformed root or stage."
  [s]
  (let [parts (str/split (str s) #"\|")]
    (when (or (empty? parts) (str/blank? (first parts)))
      (throw (query-error "a query needs a root, e.g. `elements` or `element:42`")))
    {:root   (parse-root (first parts))
     :stages (mapv parse-stage (rest parts))}))

;; ---------------------------------------------------------------------------
;; Compilation (static kind walk)
;; ---------------------------------------------------------------------------

(defn- check-fields!
  "Reject a stage naming a field its row cannot answer
  (ModelQuery.UnknownFieldRejected), naming what the row does carry. A misspelled
  or unsupported name is a mistake to correct, not an answer: without this the
  stage would silently match nothing, which reads the same as a genuinely empty
  value."
  [st kind projected]
  (let [names    (case (:kind st)
                   :where  [(:field st)]
                   :order  [(:field st)]
                   :select (:fields st)
                   nil)
        accepted (accepted-fields kind projected)]
    (doseq [f names :when (some? f)]
      (when-not (accepted f)
        (throw (query-error
                (str "unknown field " (pr-str f) " on " (token-label kind) " rows; "
                     (name (:kind st)) " accepts "
                     (str/join ", " (sort accepted)))))))))

(defn- check-edge-fields!
  "Reject a projection naming a field the association does not carry. An
  unvalidated name would attach nil and read as an empty edge, which is the same
  failure UnknownFieldRejected closes for the stages themselves."
  [rel projection]
  (when (vector? projection)
    (let [carried (set (map name (:edge-fields rel)))]
      (doseq [f projection]
        (when-not (carried f)
          (throw (query-error
                  (str "unknown edge field " (pr-str f) " on the " (name (:edge-key rel))
                       " projection; project accepts "
                       (str/join ", " (sort carried))))))))))

(defn- compile-query
  "Walk the pipeline, validating each follow against the registry and each
  field-naming stage against the closed field set; tracks the current entity kind
  and any edge key a projection has attached, and attaches the resolved :relation
  to each follow stage."
  [expr]
  (loop [stages (:stages expr), current (:kind (:root expr)), projected #{}, plan []]
    (if-let [st (first stages)]
      (if (= :follow (:kind st))
        (let [rel (relation current (or (:direction st) (:target st)))]
          (when (and (:projection st) (nil? (:edge-key rel)))
            (throw (query-error
                    (str "relation " (token-label current) " -> " (token-label (:to rel))
                         " is not an association; there is nothing to project with `{...}`"))))
          (check-edge-fields! rel (:projection st))
          (recur (rest stages) (:to rel)
                 ;; A follow produces NEW rows: they carry an edge only when
                 ;; THIS follow projected one, so a key an earlier follow
                 ;; attached is no longer on them.
                 (if (:projection st) #{(name (:edge-key rel))} #{})
                 (conj plan (assoc st :relation rel))))
        (do (check-fields! st current projected)
            (when (and (= :count (:kind st)) (seq (rest stages)))
              (throw (query-error
                      (str "count collapses the result to a scalar, so nothing can follow it; "
                           "the stage after it is unreachable"))))
            (recur (rest stages) current projected (conj plan st))))
      {:root (:root expr) :plan plan})))

;; ---------------------------------------------------------------------------
;; Execution
;; ---------------------------------------------------------------------------

(defn- breadcrumb [store type e]
  (case type
    :slice         {:timeline_title (:title (m/fetch store :timeline (:timeline e)))}
    :element       (if-let [l (:swimlane e)]
                     {:swimlane_name (:name (m/fetch store :swimlane l))}
                     {})
    :specification (let [sl (m/fetch store :slice (:slice e))]
                     {:slice_title (:title sl)
                      :timeline_title (:title (m/fetch store :timeline (:timeline sl)))})
    :spec-step     {:spec_title (:title (m/fetch store :specification (:spec e)))}
    {}))

(defn- field-value
  "The value a stage sees for field `k` on a pipeline item: the row's own display
  fields first (the entity CATEGORY as `kind`, its name or title as `name`), then
  the landed entity's declared attributes - a stored one read off the record, a
  derived one resolved through the model - then the item itself, which carries an
  edge key when a projection attached one. Resolving exactly the names
  accepted-fields admits is what keeps an accepted name from resolving to nothing
  - the failure UnknownFieldRejected exists to prevent, since an unresolvable name
  and a genuinely empty value would otherwise read the same."
  [store item k]
  (let [e    (:entity item)
        kind (kind-of (:type e))]
    (cond
      (= (keyword k) (:edge-key item)) (:edge item)
      (= k "id")   (:id e)
      (= k "kind") kind
      (= k "name") (or (:name e) (:title e))
      (= k "breadcrumb") (breadcrumb store (:type e) e)
      (contains? (get derived-fields kind) k) (derived-value store kind e k)
      :else (let [kk (keyword k)]
              (if (and (map? e) (contains? e kk)) (get e kk) (get item kk))))))

(defn- text
  "A comparable textual form of a field value. Keynote: in Clojure/Babashka
  `(str :command)` is \":command\", so keywords must go through `name`."
  [v]
  (cond (keyword? v) (name v)
        (nil? v)     ""
        :else        (str v)))

(defn- order-value
  "A `sort-by` key for a field value. A scalar orders by its own kind - numbers
  numerically, strings lexically, a keyword by name - and a structure (a
  breadcrumb, a field list, a reverse reference) by its printed form. The closed
  field set admits all of those, and a name that is accepted must not break the
  pipeline: ordering by a structure is meaningless, but crashing is worse. The
  leading rank keeps the kinds apart, so a column mixing an assigned value with an
  absent one (an unassigned swimlane, an error step's element) is still totally
  ordered instead of comparing a number to a string."
  [v]
  (cond (nil? v)     [0]
        (number? v)  [1 v]
        (string? v)  [2 v]
        (keyword? v) [3 (name v)]
        (boolean? v) [4 (if v 1 0)]
        :else        [5 (pr-str v)]))

(defn- where-pred [store {:keys [field comparator operand]}]
  (fn [item]
    (let [raw (text (field-value store item field))
          v   (str/lower-case raw)]
      (case comparator
        :equals     (= v (str/lower-case (text operand)))
        :not_equals (not= v (str/lower-case (text operand)))
        :matches    (boolean (re-find (re-pattern (str operand)) raw))
        :in_set     (boolean (some #(= (str/lower-case (text %)) v) operand))))))

(defn- order-cmp [descending] (if descending #(compare %2 %1) compare))

(defn- edge-view [edge proj]
  (if (= :all proj) (dissoc edge :type :id) (select-keys edge (map keyword proj))))

(defn- follow-xf [store {:keys [relation projection]}]
  (let [{:keys [navigate edge-key]} relation]
    (mapcat (fn [item]
              (for [r (navigate store (:entity item))]
                (if projection
                  {:entity (:entity r) :edge-key edge-key :edge (edge-view (:edge r) projection)}
                  {:entity (:entity r)}))))))

(defn- row [store item]
  (let [e (:entity item)
        t (:type e)
        r (cond-> {:id (:id e) :kind (kind-of t)}
            (or (:name e) (:title e)) (assoc :name (or (:name e) (:title e)))
            true (assoc :breadcrumb (breadcrumb store t e)))]
    (if (:edge item)
      (assoc r (:edge-key item) (:edge item))
      r)))

(defn- execute [store items plan]
  (loop [items items, xf identity, stages plan, selected nil]
    (if-let [st (first stages)]
      (case (:kind st)
        :follow   (recur items (comp xf (follow-xf store st)) (rest stages) selected)
        :where    (recur items (comp xf (filter (where-pred store st))) (rest stages) selected)
        :distinct (recur items (comp xf (distinct)) (rest stages) selected)
        :limit    (recur items (comp xf (take (:limit st))) (rest stages) selected)
        :order    (let [f (into [] xf items)]
                    (recur (vec (sort-by #(order-value (field-value store % (:field st)))
                                         (order-cmp (:descending st)) f))
                           identity (rest stages) selected))
        :select   (recur items xf (rest stages) (:fields st))
        :count    (count (into [] xf items)))
      (let [final (into [] xf items)
            rows  (mapv #(row store %) final)]
        (if selected
          ;; Projection reads the same values the filter and sort stages read, so
          ;; a row's display fields (id, kind, name, breadcrumb) come from the row
          ;; and a landed entity's attribute from the entity (see field-value).
          (mapv (fn [item r]
                  (into {}
                        (map (fn [f]
                               (let [k (keyword f)]
                                 [k (if (contains? r k) (get r k) (field-value store item f))])))
                        selected))
                final rows)
          rows)))))

(defn- kind-collection [store mid k]
  (case k
    :timeline      (m/timelines store mid)
    :swimlane      (m/swimlanes store mid)
    :slice         (m/model-slices store mid)
    :element       (m/elements store mid)
    :specification (m/model-specs store mid)
    :step          (m/all store :spec-step)))

(defn- root-items
  "The entities a root stage starts from: every entity of the kind, or the single
  one an id / name names.

  A root that names nothing is rejected rather than yielding an empty result: an
  empty result reads as 'nothing is related to X', while the truth is 'there is
  no X' - the distinction UnknownStepRejected already draws for kinds and
  relations. A name root resolves through the supplied `:resolve-name`, which
  returns the one entity of the declared kind the name denotes, or nil when there
  is none; the kind it returns is the declared kind by construction, so a root can
  never land on another kind."
  [store mid root opts]
  (let [k (:kind root)]
    (cond
      (:id root)   (if-let [e (m/fetch store (model-type k) (:id root))]
                     [{:entity e}]
                     (throw (query-error (str "no " (token-label k) " with id "
                                              (:id root) " in the model"))))
      (:name root) (if-let [e ((:resolve-name opts) k (:name root))]
                     [{:entity e}]
                     (throw (query-error (str "no " (token-label k) " named "
                                              (pr-str (:name root))))))
      :else        (map (fn [e] {:entity e}) (kind-collection store mid k)))))

(defn run-query
  "Evaluate a parsed query `expr` against `store` (one model, id `mid`).
  Returns a vector of rows, or an integer when the pipeline ends in `count`.
  `opts` carries :resolve-name (fn [kind name] -> entity-or-nil) for name-based
  roots, which delegate to NameResolution: it returns the single entity of `kind`
  the name denotes, and nil when there is none. It must never choose among
  several candidates (NameResolution.NoImplicitBestPick), and the error it raises
  for a name that denotes nothing is the query error above. Throws ex-info
  {:error :invalid-query} on a root or stage that names nothing. Read-only."
  [store mid expr opts]
  (let [{:keys [root plan]} (compile-query expr)]
    (execute store (root-items store mid root opts) plan)))

;; ---------------------------------------------------------------------------
;; Introspection
;; ---------------------------------------------------------------------------

(defn relations-doc
  "The query language's vocabulary - roots, relations, stages - as data. Powers
  `emcli query --relations` and the generated emcli_query tool description, so
  the two cannot drift from the engine."
  []
  {:roots (mapv token-of query-kinds)
   :relations
   (mapv (fn [r]
           (cond-> {:from (token-of (:from r)) :to (token-of (:to r))}
             (#{:outgoing :incoming} (:token r)) (assoc :name (name (:token r)))
             (:edge-key r) (assoc :via (name (:edge-key r))
                                  :projection {:key (name (:edge-key r))
                                               :fields (mapv name (:edge-fields r))})))
         relations)
   :stages ["where" "order" "select" "count" "limit" "distinct"]})

(defn tool-description
  "The emcli_query LLM tool description, with the relation vocabulary generated
  from the registry so it cannot diverge from what the engine accepts."
  []
  (let [{:keys [roots relations]} (relations-doc)
        rel-lines (for [r relations]
                    (str "  " (:from r) " -> " (:to r)
                         (when (:name r) (str "   [" (:name r) "]"))
                         (when (:via r)
                           (str "   (via " (:via r) "; project {"
                                (str/join "," (get-in r [:projection :fields])) "})"))))]
    (str
     "Read-only structural query over the Event Model. Answers relational questions"
     " - e.g. which slices an element is placed in, which elements an event derives"
     " from - and returns only the matching part of the model. Use `emcli_resolve`"
     " first if you only have names; this tool never writes.\n\n"
     "A query is a root followed by stages separated by `|`, applied left-to-right"
     " over a collection.\n\n"
     "ROOT (required, first): an entity kind - " (str/join " | " roots)
     " - or a single entity as `kind:id` (e.g. `element:42`).\n\n"
     "RELATION (a target kind, singular or plural accepted; placements and"
     " connections are edges, never roots):\n"
     (str/join "\n" rel-lines) "\n\n"
     "EDGE PROJECTION `{field,...}`: attach the association record under its key"
     " (placement or connection), e.g. `slice:7 | elements {index}` gives each"
     " element plus `:placement {:index n}`.\n\n"
     "STAGES - a pipeline of stages, one per `|`-separated segment of the query:\n"
     "  where <field> <op> <value>\n"
     "  order <field>, or order -<field> to descend\n"
     "  select <field>,<field>,...\n"
     "  count\n"
     "  limit <n>\n"
     "  distinct\n\n"
     "OPERATORS - <op> is exactly one of these, standing alone: write it straight"
     " after the field and before the value, with no `=` of its own:\n"
     "  =    equals\n"
     "  !=   not equals\n"
     "  ~    regex match\n"
     "  in   set membership - a list, as `in (a,b)` or `in a,b` (braces are not"
     " used)\n\n"
     "FIELD: a stage names either a row's display field - id, kind (the entity"
     " CATEGORY, e.g. `element`), name, breadcrumb, or a projected edge's key - or"
     " an attribute the landed entity declares. So `element | select"
     " name,element_type,context,fields` reads an element's own field names, which"
     " is how you learn what the field operations address a field by (`fields` is"
     " the recursive list, subfields included). Beware that `kind` is the CATEGORY:"
     " what an element IS is its `element_type` (command | event | read_model |"
     " screen | automation), and what a slice is, is its `slice_type`. The set is"
     " per kind, and an unknown field name is REJECTED naming what that row does"
     " carry - never silently an empty result.\n\n"
     "Examples:\n"
     "  timelines | slice | where status = done | select id,title\n"
     "  element | where element_type in (command,event) | select id,name\n"
     "  element:42 | slice\n"
     "  slice:7 | elements {index} | where element_type = command\n"
     "  element:42 | outgoing {derivations}\n"
     "  element | select name,fields\n\n"
     "An invalid stage, kind, relation or field name returns an error naming the"
     " valid alternatives. Run `emcli query --relations` for the live list.")))
