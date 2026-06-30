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

(defn- ->int [v] (cond (integer? v) v (string? v) (parse-long (str/trim v)) :else v))
(defn- ->kw  [v] (when v (keyword (str/replace (name v) "-" "_"))))
(defn- ->bool [v] (cond (boolean? v) v (string? v) (contains? #{"true" "1" "yes"} (str/lower-case v)) :else (boolean v)))

(defn- coerce [type v]
  (case type :int (->int v) :kw (->kw v) :bool (->bool v) v))

;; Each command: rule fn, and params as [option-key rule-arg-key coerce-type required?].
;; :model? injects {:model <app model id>}.
(def registry
  {;; Timelines
   "create-timeline"     {:rule r/create-timeline    :model? true  :params [[:title :title :str true]]}
   "rename-timeline"     {:rule r/rename-timeline     :params [[:timeline :timeline :int true] [:new-title :new-title :str true]]}
   "delete-timeline"     {:rule r/delete-timeline     :params [[:timeline :timeline :int true]]}
   ;; Swimlanes
   "create-swimlane"     {:rule r/create-swimlane     :model? true  :params [[:name :name :str true] [:index :index :int true]]}
   "rename-swimlane"     {:rule r/rename-swimlane     :params [[:lane :lane :int true] [:new-name :new-name :str true]]}
   "reorder-swimlane"    {:rule r/reorder-swimlane    :params [[:lane :lane :int true] [:new-index :new-index :int true]]}
   "delete-swimlane"     {:rule r/delete-swimlane     :params [[:lane :lane :int true]]}
   ;; Slices
   "add-slice"           {:rule r/add-slice           :params [[:timeline :timeline :int true] [:title :title :str true] [:kind :kind :kw true] [:index :index :int true]]}
   "reorder-slice"       {:rule r/reorder-slice       :params [[:slice :slice :int true] [:new-index :new-index :int true]]}
   "set-slice-status"    {:rule r/set-slice-status    :params [[:slice :slice :int true] [:new-status :new-status :kw true]]}
   "set-slice-kind"      {:rule r/set-slice-kind      :params [[:slice :slice :int true] [:new-kind :new-kind :kw true]]}
   "delete-slice"        {:rule r/delete-slice        :params [[:slice :slice :int true]]}
   ;; Elements
   "create-element"      {:rule r/create-element      :model? true  :params [[:name :name :str true] [:kind :kind :kw true]]}
   "set-element-context" {:rule r/set-element-context :params [[:element :element :int true] [:new-context :new-context :kw true]]}
   "assign-swimlane"     {:rule r/assign-swimlane     :params [[:element :element :int true] [:lane :lane :int true]]}
   "set-image-url"       {:rule r/set-image-url       :params [[:element :element :int true] [:url :url :str true]]}
   "rename-element"      {:rule r/rename-element      :params [[:element :element :int true] [:new-name :new-name :str true]]}
   "delete-element"      {:rule r/delete-element      :params [[:element :element :int true]]}
   ;; Placements
   "place-element"       {:rule r/place-element       :params [[:slice :slice :int true] [:element :element :int true]]}
   "remove-placement"    {:rule r/remove-placement    :params [[:placement :placement :int true]]}
   ;; Connections
   "connect"             {:rule r/connect             :params [[:from :from :int true] [:to :to :int true]]}
   "disconnect"          {:rule r/disconnect          :params [[:connection :connection :int true]]}
   ;; Specifications
   "add-specification"   {:rule r/add-specification   :params [[:slice :slice :int true] [:title :title :str true]]}
   "delete-specification"{:rule r/delete-specification :params [[:spec :spec :int true]]}
   "add-spec-step"       {:rule r/add-spec-step       :params [[:spec :spec :int true] [:clause :clause :kw true] [:element :element :int true] [:index :index :int true]]}
   "add-error-step"      {:rule r/add-error-step      :params [[:spec :spec :int true] [:error-name :error-name :str true] [:index :index :int true]]}
   "remove-spec-step"    {:rule r/remove-spec-step    :params [[:step :step :int true]]}
   "set-step-expect-empty" {:rule r/set-step-expect-empty :params [[:step :step :int true] [:value :value :bool true]]}})

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
  a map of option-key -> value. Structured rules (set-fields, set-step-examples)
  accept their list argument directly under :fields / :examples."
  [app command opts]
  (cond
    (= command "set-fields")
    (if (and (get opts :element) (contains? opts :fields))
      (app/apply-rule! app r/set-fields {:element (->int (get opts :element))
                                         :fields (vec (get opts :fields))})
      {:error :missing-args :message "set-fields requires :element and :fields"})

    (= command "set-step-examples")
    (if (and (get opts :step) (contains? opts :examples))
      (app/apply-rule! app r/set-step-examples {:step (->int (get opts :step))
                                                :examples (vec (get opts :examples))})
      {:error :missing-args :message "set-step-examples requires :step and :examples"})

    (= command "set-field-origins")
    (if (and (get opts :element) (contains? opts :origins))
      (app/apply-rule! app r/set-field-origins {:element (->int (get opts :element))
                                                :origins (vec (get opts :origins))})
      {:error :missing-args :message "set-field-origins requires :element and :origins"})

    (= command "set-connection-derivations")
    (if (and (get opts :connection) (contains? opts :derivations))
      (app/apply-rule! app r/set-connection-derivations {:connection (->int (get opts :connection))
                                                         :derivations (vec (get opts :derivations))})
      {:error :missing-args :message "set-connection-derivations requires :connection and :derivations"})

    ;; Convenience composites (append one entry, preserving the rest). They emit
    ;; the same SetFieldOrigins / SetConnectionDerivations deltas as the
    ;; replace-style operations they decompose into.
    (= command "add-field-origin")
    (if (and (get opts :element) (get opts :field) (get opts :origin))
      (app/apply-rule! app r/add-field-origin {:element (->int (get opts :element))
                                               :field (str (get opts :field))
                                               :origin (->kw (get opts :origin))})
      {:error :missing-args :message "add-field-origin requires :element, :field and :origin"})

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

    :else
    (if-let [{:keys [rule] :as entry} (registry command)]
      (let [args (build-args entry app opts)]
        (if (r/error? args)
          args
          (app/apply-rule! app rule args)))
      {:error :unknown-command :command command
       :message (str "unknown command: " command)})))

(def commands
  "All authoring command names: the registry, the structured (list-valued)
  operations, and the convenience composites."
  (sort (concat (keys registry)
                ["set-fields" "set-step-examples" "set-field-origins" "set-connection-derivations"
                 "add-field-origin" "add-derivation"])))

(defn authoring-view
  "The ModelAuthoring `exposes:` read projection (event-model.allium:598-635):
  the full authoring view, richer than the ChangeStream snapshot — it carries
  slice/spec is_complete, the spec-step subtree, the events/screens projections,
  the element list and connection names."
  [app]
  (let [s   (app/store app)
        mid (app/model-id app)]
    {:name (:name (m/fetch s :event-model mid))
     :timelines (for [t (m/timelines s mid)]
                  {:title (:title t)
                   :slices (for [sl (m/slices s (:id t))
                                 :let [sid (:id sl)]]
                             {:title (:title sl) :kind (:kind sl) :status (:status sl)
                              :index (:index sl) :is_complete (m/slice-complete? s sl)
                              :timeline_title (:title t)
                              :placements (map #(:name (m/placement-element s %)) (m/placements s sid))
                              :events (map #(:name (m/placement-element s %)) (m/slice-events s sid))
                              :screens (map #(:name (m/placement-element s %)) (m/slice-screens s sid))
                              :specifications (for [sp (m/specs s sid)]
                                                {:title (:title sp)
                                                 :is_complete (m/spec-complete? s sp)
                                                 :steps (for [st (m/spec-steps s (:id sp))]
                                                          {:clause (:clause st) :index (:index st)
                                                           :is_error (:is_error st)
                                                           :error_name (:error_name st)
                                                           :spec_title (:title sp)})})})})
     :swimlanes   (for [sw (m/swimlanes s mid)] {:name (:name sw) :index (:index sw)})
     :elements    (for [e (m/elements s mid)]
                    {:name (:name e) :kind (:kind e)
                     :is_information_complete (m/information-complete? s e)})
     :connections (for [c (m/connections s mid)]
                    {:from (:name (m/fetch s :element (:from c)))
                     :to   (:name (m/fetch s :element (:to c)))})}))

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
