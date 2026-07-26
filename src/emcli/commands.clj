(ns emcli.commands
  "The ModelAuthoring `provides` surface as a data-driven command registry,
  shared by the CLI and the server's authoring API. Each entry maps a command
  to its rule and declares how to coerce operator-supplied options into the
  rule's arguments. `model`-scoped operations take the app's single model
  implicitly, matching the surface's `context model: EventModel`."
  (:require [clojure.string :as str]
            [emcli.app :as app]
            [emcli.model :as m]
            [emcli.rules :as r]))

;; Sentinel for an :int option that is present but not a valid integer. It must
;; never reach a rule (the spec types these args as Integer, non-optional): the
;; `run` gate rejects any command carrying one with a :bad-argument error.
(def ^:private parse-failure ::parse-failure)

(defn- ->int [v]
  (cond (integer? v) v
        (nil? v)      nil
        (string? v)   (or (parse-long (str/trim v)) parse-failure)
        :else         parse-failure))
(defn- ->kw  [v] (when v (keyword (str/replace (name v) "-" "_"))))
(defn- ->bool [v] (cond (boolean? v) v (string? v) (contains? #{"true" "1" "yes"} (str/lower-case v)) :else (boolean v)))

(defn- coerce [type v]
  (case type :int (->int v) :kw (->kw v) :bool (->bool v) v))

;; add-field carries its field as JSON, parsed with keywordize-keys true —
;; that only keywordizes map *keys*, not values, so a field's "type": "uuid"
;; arrives as the string "uuid", never :uuid. This is the one choke point
;; every caller (HTTP server, direct cmd/run) passes through before the rule,
;; so it's coerced here rather than at the transport edge, where it would
;; just be re-flattened to a string on the wire.
(defn- coerce-field [f]
  (cond-> f
    (:type f)        (update :type ->kw)
    (:cardinality f) (update :cardinality ->kw)
    (seq (:subfields f)) (update :subfields #(mapv coerce-field %))))

;; Each command: rule fn, and params as [option-key rule-arg-key coerce-type required?].
;; :model? injects {:model <app model id>}.
(def registry
  {;; Timelines
   "create-timeline"     {:rule r/create-timeline    :model? true  :params [[:title :title :str true] [:id :id :int false]]}
   "rename-timeline"     {:rule r/rename-timeline     :params [[:timeline :timeline :int true] [:new-title :new-title :str true]]}
   "delete-timeline"     {:rule r/delete-timeline     :params [[:timeline :timeline :int true]]}
   ;; Swimlanes
   "create-swimlane"     {:rule r/create-swimlane     :model? true  :params [[:name :name :str true] [:index :index :int true] [:id :id :int false]]}
   "rename-swimlane"     {:rule r/rename-swimlane     :params [[:lane :lane :int true] [:new-name :new-name :str true]]}
   "reorder-swimlane"    {:rule r/reorder-swimlane    :params [[:lane :lane :int true] [:new-index :new-index :int true]]}
   "delete-swimlane"     {:rule r/delete-swimlane     :params [[:lane :lane :int true]]}
   ;; Slices
   "add-slice"           {:rule r/add-slice           :params [[:timeline :timeline :int true] [:title :title :str true] [:kind :kind :kw true] [:index :index :int true] [:id :id :int false]]}
   "reorder-slice"       {:rule r/reorder-slice       :params [[:slice :slice :int true] [:new-index :new-index :int true]]}
   "set-slice-status"    {:rule r/set-slice-status    :params [[:slice :slice :int true] [:new-status :new-status :kw true]]}
   "set-slice-kind"      {:rule r/set-slice-kind      :params [[:slice :slice :int true] [:new-kind :new-kind :kw true]]}
   "delete-slice"        {:rule r/delete-slice        :params [[:slice :slice :int true]]}
   ;; Elements
   "create-element"      {:rule r/create-element      :model? true  :params [[:name :name :str true] [:kind :kind :kw true] [:id :id :int false]]}
   "set-element-context" {:rule r/set-element-context :params [[:element :element :int true] [:new-context :new-context :kw true]]}
   "assign-swimlane"     {:rule r/assign-swimlane     :params [[:element :element :int true] [:lane :lane :int true]]}
   "set-image-url"       {:rule r/set-image-url       :params [[:element :element :int true] [:url :url :str true]]}
   "rename-element"      {:rule r/rename-element      :params [[:element :element :int true] [:new-name :new-name :str true]]}
   "delete-element"      {:rule r/delete-element      :params [[:element :element :int true]]}
   ;; Placements
   "place-element"       {:rule r/place-element       :params [[:slice :slice :int true] [:element :element :int true] [:id :id :int false]]}
   "reorder-placement"   {:rule r/reorder-placement   :params [[:placement :placement :int true] [:new-index :new-index :int true]]}
   "remove-placement"    {:rule r/remove-placement    :params [[:placement :placement :int true]]}
   ;; Connections
   "connect"             {:rule r/connect             :params [[:from :from :int true] [:to :to :int true] [:id :id :int false]]}
   "disconnect"          {:rule r/disconnect          :params [[:connection :connection :int true]]}
   ;; Specifications
   "add-specification"   {:rule r/add-specification   :params [[:slice :slice :int true] [:title :title :str true] [:id :id :int false]]}
   "delete-specification"{:rule r/delete-specification :params [[:spec :spec :int true]]}
   "add-spec-step"       {:rule r/add-spec-step       :params [[:spec :spec :int true] [:clause :clause :kw true] [:element :element :int true] [:index :index :int true] [:id :id :int false]]}
   "add-error-step"      {:rule r/add-error-step      :params [[:spec :spec :int true] [:error-name :error-name :str true] [:index :index :int true] [:id :id :int false]]}
   "remove-spec-step"    {:rule r/remove-spec-step    :params [[:step :step :int true]]}
   "set-step-expect-empty" {:rule r/set-step-expect-empty :params [[:step :step :int true] [:value :value :bool true]]}})

;; The :int option keys per command — from the registry params for registered
;; commands, and declared here for the structured/composite ones (whose int args
;; are coerced by hand in `run`). Used to reject non-integer values up front.
(def ^:private structured-int-params
  {"add-field" [:element] "remove-field" [:element]
   "add-field-origin" [:element] "remove-field-origin" [:element]
   "add-derivation" [:connection] "remove-derivation" [:connection]
   "add-step-example" [:step] "remove-step-example" [:step]})

(defn- int-opt-keys [command]
  (if-let [params (:params (registry command))]
    (->> params (filter #(= :int (nth % 2))) (map first))
    (structured-int-params command [])))

(defn- bad-int-opts
  "Option keys that are present but not a valid integer — so a spec-declared
  Integer arg would otherwise silently become nil."
  [command opts]
  (->> (int-opt-keys command)
       (filter #(and (some? (get opts %)) (= parse-failure (->int (get opts %)))))
       (mapv name)))

(defn- build-args [{:keys [params model?]} app opts]
  (let [missing (for [[opt _ _ req?] params :when (and req? (nil? (get opts opt)))] opt)]
    (if (seq missing)
      {:error :missing-args :missing (vec missing)
       :message (str "missing required option(s): " (str/join ", " (map name missing)))}
      (cond-> (into {} (for [[opt k type _] params] [k (coerce type (get opts opt))]))
        model? (assoc :model (app/model-id app))))))

(defn run
  "Run a registered authoring command against `app`, applying the rule through
  app/apply-rule! so the mutation is committed and a delta broadcast. `opts` is
  a map of option-key -> value. The convenience composites (add-field,
  add-field-origin, add-derivation, add-step-example and their remove-*
  counterparts) decompose into the underlying SetFields / SetFieldOrigins /
  SetConnectionDerivations / SetStepExamples rules, appending/removing one
  entry while preserving the rest.

  Any spec-declared Integer argument that is present but not a valid integer is
  rejected up front with :bad-argument, so it can never reach a rule as nil."
  [app command opts]
  (let [bad (bad-int-opts command opts)]
   (if (seq bad)
     {:error :bad-argument :args bad
      :message (str "expected an integer for: " (str/join ", " bad))}
     (cond
    ;; Convenience composites (append/remove one entry, preserving the rest).
    ;; They emit the same SetFields / SetFieldOrigins / SetConnectionDerivations
    ;; / SetStepExamples deltas as the replace-style operations they decompose
    ;; into.
    (= command "add-field")
    (if (and (get opts :element) (get opts :field))
      (app/apply-rule! app r/add-field {:element (->int (get opts :element))
                                        :field (coerce-field (get opts :field))})
      {:error :missing-args :message "add-field requires :element and :field"})

    (= command "remove-field")
    (if (and (get opts :element) (get opts :name))
      (app/apply-rule! app r/remove-field {:element (->int (get opts :element))
                                           :name (str (get opts :name))})
      {:error :missing-args :message "remove-field requires :element and :name"})

    (= command "add-field-origin")
    (if (and (get opts :element) (get opts :field) (get opts :origin))
      (app/apply-rule! app r/add-field-origin {:element (->int (get opts :element))
                                               :field (str (get opts :field))
                                               :origin (->kw (get opts :origin))})
      {:error :missing-args :message "add-field-origin requires :element, :field and :origin"})

    (= command "remove-field-origin")
    (if (and (get opts :element) (get opts :field))
      (app/apply-rule! app r/remove-field-origin {:element (->int (get opts :element))
                                                  :field (str (get opts :field))})
      {:error :missing-args :message "remove-field-origin requires :element and :field"})

    (= command "add-derivation")
    (if (and (get opts :connection) (get opts :target) (contains? opts :from))
      (app/apply-rule! app r/add-derivation
                       {:connection (->int (get opts :connection))
                        :target (str (get opts :target))
                        :from (let [f (get opts :from)]
                                (if (string? f)
                                  (vec (remove str/blank? (map str/trim (str/split f #","))))
                                  (vec f)))})
      {:error :missing-args :message "add-derivation requires :connection, :target and :from"})

    (= command "remove-derivation")
    (if (and (get opts :connection) (get opts :target))
      (app/apply-rule! app r/remove-derivation {:connection (->int (get opts :connection))
                                                :target (str (get opts :target))})
      {:error :missing-args :message "remove-derivation requires :connection and :target"})

    (= command "add-step-example")
    (if (and (get opts :step) (get opts :field-name) (contains? opts :field-value))
      (app/apply-rule! app r/add-step-example {:step (->int (get opts :step))
                                               :field-name (str (get opts :field-name))
                                               :field-value (str (get opts :field-value))})
      {:error :missing-args :message "add-step-example requires :step, :field-name and :field-value"})

    (= command "remove-step-example")
    (if (and (get opts :step) (get opts :field-name))
      (app/apply-rule! app r/remove-step-example {:step (->int (get opts :step))
                                                  :field-name (str (get opts :field-name))})
      {:error :missing-args :message "remove-step-example requires :step and :field-name"})

    :else
    (if-let [{:keys [rule] :as entry} (registry command)]
      (let [args (build-args entry app opts)]
        (if (r/error? args)
          args
          (app/apply-rule! app rule args)))
      {:error :unknown-command :command command
       :message (str "unknown command: " command)})))))

(def commands
  "All authoring command names: the registry, and the convenience composites."
  (sort (concat (keys registry)
                ["add-field" "remove-field" "add-field-origin" "remove-field-origin"
                 "add-derivation" "remove-derivation" "add-step-example" "remove-step-example"])))

(defn authoring-view
  "The ModelAuthoring `exposes:` read projection (event-model.allium:598-635):
  the full authoring view, richer than the ChangeStream snapshot — it carries
  the events/screens projections, timeline_title and spec_title denormalised
  onto their child slices/steps, and the element list and connection names."
  [app]
  (let [s   (app/store app)
        mid (app/model-id app)]
    {:name (:name (m/fetch s :event-model mid))
     :timelines (for [t (m/timelines s mid)]
                  {:id (:id t) :title (:title t)
                   :slices (for [sl (m/slices s (:id t))
                                 :let [sid (:id sl)]]
                             {:id sid :title (:title sl) :kind (:kind sl) :status (:status sl)
                              :index (:index sl) :is_complete (m/slice-complete? s sl)
                              :timeline_title (:title t)
                              :placements (for [p (m/placements s sid)]
                                            {:id (:id p) :index (:index p)
                                             :element_id (:element p)
                                             :element_name (:name (m/placement-element s p))})
                              :events (map #(:name (m/placement-element s %)) (m/slice-events s sid))
                              :screens (map #(:name (m/placement-element s %)) (m/slice-screens s sid))
                              :specifications (for [sp (m/specs s sid)]
                                                {:id (:id sp) :title (:title sp)
                                                 :is_complete (m/spec-complete? s sp)
                                                 :steps (for [st (m/spec-steps s (:id sp))]
                                                          {:id (:id st) :clause (:clause st) :index (:index st)
                                                           :is_error (:is_error st)
                                                           :error_name (:error_name st)
                                                           :element_id (:element st)
                                                           :element_name (some-> (m/step-element s st) :name)
                                                           :spec_title (:title sp)
                                                           :examples (for [e (:examples st)]
                                                                       (select-keys e [:field_name :field_value]))})})})})
     :swimlanes   (for [sw (m/swimlanes s mid)] {:id (:id sw) :name (:name sw) :index (:index sw)})
     :elements    (for [e (m/elements s mid)]
                    {:id (:id e) :name (:name e) :kind (:kind e)
                     :swimlane (:swimlane e)
                     :is_information_complete (m/information-complete? s e)})
     :connections (for [c (m/connections s mid)]
                    {:id (:id c)
                     :from_id (:from c) :from_name (:name (m/fetch s :element (:from c)))
                     :to_id   (:to c)   :to_name   (:name (m/fetch s :element (:to c)))})}))

;; NameResolution.resolve (event-model.allium): resolve a batch of human-readable
;; names to candidate entities, without ever listing the whole model. Matching
;; is a fixed ladder per query — exact, then substring as fallback, then a
;; bounded near-miss (edit-distance) suggestion list only when neither matched
;; anything — so response size scales with the number of names queried, not
;; with model size (BoundedByQueryCount).
(def ^:private resolve-candidate-cap 5)

(defn- levenshtein
  "Edit distance between two strings, for the near-miss ('did you mean') tier."
  [s1 s2]
  (let [s1 (vec s1)
        s2 (vec s2)
        n  (count s2)]
    (loop [i 0 prev (vec (range (inc n)))]
      (if (= i (count s1))
        (peek prev)
        (let [ci  (nth s1 i)
              cur (reduce
                   (fn [row j]
                     (let [cost (if (= ci (nth s2 j)) 0 1)]
                       (conj row (min (inc (peek row))
                                      (inc (nth prev (inc j)))
                                      (+ (nth prev j) cost)))))
                   [(inc i)]
                   (range n))]
          (recur (inc i) cur))))))

;; The human-nameable entities in scope (ResolvableKind), each carrying enough
;; breadcrumb context to disambiguate same-named siblings (CandidateDisambiguation).
(defn- resolvable-entities [s mid]
  (concat
   (for [t (m/timelines s mid)]
     {:kind :timeline :id (:id t) :name (:title t) :breadcrumb {}})
   (for [sw (m/swimlanes s mid)]
     {:kind :swimlane :id (:id sw) :name (:name sw) :breadcrumb {}})
   (for [sl (m/model-slices s mid)]
     {:kind :slice :id (:id sl) :name (:title sl)
      :breadcrumb {:timeline_title (:title (m/fetch s :timeline (:timeline sl)))}})
   (for [e (m/elements s mid)]
     {:kind :element :id (:id e) :name (:name e)
      :breadcrumb (if-let [lane (:swimlane e)]
                    {:swimlane_name (:name (m/fetch s :swimlane lane))}
                    {})})
   (for [sp (m/model-specs s mid)
         :let [sl (m/fetch s :slice (:slice sp))]]
     {:kind :specification :id (:id sp) :name (:title sp)
      :breadcrumb {:slice_title (:title sl)
                   :timeline_title (:title (m/fetch s :timeline (:timeline sl)))}})))

(defn- name-matches? [pred entity name] (pred (str/lower-case (:name entity)) (str/lower-case name)))
(defn- exact-matches [entities name] (filter #(name-matches? = % name) entities))
(defn- substring-matches [entities name] (filter #(name-matches? str/includes? % name) entities))

(defn- near-miss-matches [entities name cap]
  (let [n (str/lower-case name)]
    (->> entities
         (map #(assoc % :distance (levenshtein n (str/lower-case (:name %)))))
         (sort-by :distance)
         (take cap))))

;; HintRanksNeverFilters: kind_hint only reorders an already-computed match set,
;; never excludes from it.
(defn- rank-by-hint [candidates kind-hint]
  (if kind-hint
    (let [hinted? #(= kind-hint (:kind %))]
      (concat (filter hinted? candidates) (remove hinted? candidates)))
    candidates))

(defn- resolve-one [entities {:keys [name kind_hint]}]
  (let [kind-hint (->kw kind_hint)
        exact     (exact-matches entities name)
        substr    (when (empty? exact) (substring-matches entities name))]
    (if (or (seq exact) (seq substr))
      (let [tier    (if (seq exact) :exact :substring)
            matched (if (seq exact) exact substr)
            total   (count matched)]
        {:name name :kind_hint kind-hint
         :candidates (mapv #(assoc % :match_type tier) (take resolve-candidate-cap (rank-by-hint matched kind-hint)))
         :total_matches total
         :truncated (> total resolve-candidate-cap)})
      (let [near (rank-by-hint (near-miss-matches entities name resolve-candidate-cap) kind-hint)]
        {:name name :kind_hint kind-hint
         :candidates (mapv #(assoc % :match_type :near_miss) near)
         :total_matches (count near)
         :truncated false}))))

(defn resolve-names
  "NameResolution.resolve — resolve a batch of human-readable names to candidate
  model entities in one round trip. `queries` is a seq of {:name str :kind_hint
  kw-or-nil}; returns one result per query, in the same order."
  [app queries]
  (let [entities (resolvable-entities (app/store app) (app/model-id app))]
    (mapv #(resolve-one entities %) queries)))

;; ValidateModel (the surface @guidance operation): report slices/specs that are
;; not is_complete, elements that are not is_information_complete, and orphaned
;; derivations whose target/source field names do not exist on the relevant
;; element (a derivation that silently fails to source its target).
(defn validate [app]
  (let [s   (app/store app)
        mid (app/model-id app)]
    {:incomplete-slices (vec (for [t (m/timelines s mid)
                                   sl (m/slices s (:id t))
                                   :when (and (not= :informational (:status sl))
                                              (not (m/slice-complete? s sl)))]
                               {:slice (:id sl) :title (:title sl)}))
     :incomplete-specs (vec (for [t (m/timelines s mid)
                                  sl (m/slices s (:id t))
                                  sp (m/specs s (:id sl))
                                  :when (not (m/spec-complete? s sp))]
                              {:specification (:id sp) :title (:title sp)}))
     :incomplete-elements (vec (for [e (m/elements s mid)
                                     :when (not (m/information-complete? s e))]
                                 {:element (:id e) :name (:name e)
                                  :unsourced (m/unsourced-fields s e)}))
     :orphaned-derivations (vec (for [c (m/connections s mid)
                                      :let [to-names   (set (map :name (:fields (m/fetch s :element (:to c)))))
                                            from-names (set (map :name (:fields (m/fetch s :element (:from c)))))]
                                      d (:derivations c)
                                      :let [missing-sources (vec (remove from-names (:source_fields d)))
                                            target-missing  (not (contains? to-names (:target_field d)))]
                                      :when (or target-missing (seq missing-sources))]
                                  {:connection (:id c) :target_field (:target_field d)
                                   :target_exists (not target-missing)
                                   :missing_source_fields missing-sources}))}))
