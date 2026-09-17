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

(defn- query-error [msg]
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
                    {:kind k :id (parse-long v)}
                    {:kind k :name (str/replace v #"^\"|\"$" "")})))))

(defn- parse-value [v]
  (let [v (str/trim v)]
    (if (and (>= (count v) 2) (str/starts-with? v "\"") (str/ends-with? v "\""))
      (subs v 1 (dec (count v)))
      v)))

(def ^:private filter-re
  #"(?s)^([A-Za-z_][A-Za-z0-9_]*)\s*(!=|=|~|\bin\b)\s*(.*)$")

(defn- parse-filter [s]
  (if-let [[_ f op val] (re-matches filter-re (str/trim s))]
    {:kind       :where
     :field      f
     :comparator ({"=" :equals "!=" :not_equals "~" :matches "in" :in_set} op)
     :operand    (if (= op "in")
                   (->> (str/split (str/replace (str/trim val) #"^\(|\)$" "") #",")
                        (map (comp parse-value str/trim))
                        (remove str/blank?)
                        vec)
                   (parse-value val))}
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
      (re-matches #"limit\s+\d+" lt)  {:kind :limit :limit (parse-long (re-find #"\d+" lt))}
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

(defn- compile-query
  "Walk the pipeline, validating each follow against the registry and tracking
  the current entity kind; attaches the resolved :relation to each follow stage."
  [expr]
  (loop [stages (:stages expr), current (:kind (:root expr)), plan []]
    (if-let [st (first stages)]
      (if (= :follow (:kind st))
        (let [rel (relation current (or (:direction st) (:target st)))]
          (when (and (:projection st) (nil? (:edge-key rel)))
            (throw (query-error
                    (str "relation " (token-label current) " -> " (token-label (:to rel))
                         " is not an association; there is nothing to project with `{...}`"))))
          (recur (rest stages) (:to rel) (conj plan (assoc st :relation rel))))
        (recur (rest stages) current (conj plan st)))
      {:root (:root expr) :plan plan})))

;; ---------------------------------------------------------------------------
;; Execution
;; ---------------------------------------------------------------------------

(defn- field-value [item k]
  (let [kk (keyword k)
        e  (:entity item)]
    (if (and (map? e) (contains? e kk)) (get e kk) (get item kk))))

(defn- text
  "A comparable textual form of a field value. Keynote: in Clojure/Babashka
  `(str :command)` is \":command\", so keywords must go through `name`."
  [v]
  (cond (keyword? v) (name v)
        (nil? v)     ""
        :else        (str v)))

(defn- where-pred [{:keys [field comparator operand]}]
  (fn [item]
    (let [raw (text (field-value item field))
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
        :where    (recur items (comp xf (filter (where-pred st))) (rest stages) selected)
        :distinct (recur items (comp xf (distinct)) (rest stages) selected)
        :limit    (recur items (comp xf (take (:limit st))) (rest stages) selected)
        :order    (let [f (into [] xf items)]
                    (recur (vec (sort-by #(field-value % (:field st)) (order-cmp (:descending st)) f))
                           identity (rest stages) selected))
        :select   (recur items xf (rest stages) (:fields st))
        :count    (count (into [] xf items)))
      (let [rows (mapv #(row store %) (into [] xf items))]
        (if selected
          (mapv #(select-keys % (map keyword selected)) rows)
          rows)))))

(defn- kind-collection [store mid k]
  (case k
    :timeline      (m/timelines store mid)
    :swimlane      (m/swimlanes store mid)
    :slice         (m/model-slices store mid)
    :element       (m/elements store mid)
    :specification (m/model-specs store mid)
    :step          (m/all store :spec-step)))

(defn- root-items [store mid root opts]
  (let [k (:kind root)]
    (cond
      (:id root)   (if-let [e (m/fetch store (model-type k) (:id root))] [{:entity e}] [])
      (:name root) (if-let [c ((:resolve-name opts) k (:name root))]
                     (if-let [e (m/fetch store (model-type (:kind c)) (:id c))] [{:entity e}] [])
                     [])
      :else        (map (fn [e] {:entity e}) (kind-collection store mid k)))))

(defn run-query
  "Evaluate a parsed query `expr` against `store` (one model, id `mid`).
  Returns a vector of rows, or an integer when the pipeline ends in `count`.
  `opts` may carry :resolve-name (fn [kind name] -> candidate-or-nil) for
  name-based roots (which delegate to NameResolution). Throws ex-info
  {:error :invalid-query} on an invalid query. Read-only."
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
     "STAGES:\n"
     "  where <field> = | != | ~ | in <value>   ( ~ = regex, in = set membership )\n"
     "  order <field> | order -<field>          ( - = descending )\n"
     "  select <field,...>\n"
     "  count | limit <n> | distinct\n\n"
     "Examples:\n"
     "  timelines | slice | where status=in_progress | select id,title\n"
     "  element:42 | slice\n"
     "  slice:7 | elements {index} | where kind=command\n"
     "  element:42 | outgoing {derivations}\n\n"
     "An invalid stage returns an error naming the valid alternatives."
     " Run `emcli query --relations` for the live list.")))
