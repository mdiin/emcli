(ns emcli.change-stream-check
  "Schema-driven drift check for the change stream.

  Nothing else in the repo guards docs/change-stream.md against
  docs/change-stream.schema.json and against the code. This check holds all
  three together by validating real payloads:

    * the snapshot from the real `emcli.app/snapshot` code path;
    * one delta per authoring operation, driven through the real `emcli.commands/run`
      commit path (so the payloads are the ones the wire actually carries, never
      hand-written expectations — which would make the check circular);
    * every JSON example embedded in docs/change-stream.md (fenced ```json
      blocks, the `event:`/`data:` SSE walks, and the one inline WireframeNode
      example the prose carries), plus the assertion that each SSE walk's
      `event:` name equals its payload's `op`;
    * the two seed-completeness obligations the schema cannot express: the
      snapshot carries every element id it references, and a placement delta
      never names an element the subscriber's seed did not carry.

  A fenced block that is deliberately not a self-contained payload must be named
  in `fragment-allowlist` with a reason — there is no silent skip. The subset of
  JSON Schema used is implemented in emcli.jsonschema; no dependency is added.

  It lives in test/ on purpose: it is a check tool, not runtime behaviour (the
  change stream under src/ must not change).

  `run` prints every violation and exits non-zero when there is any, so
  `bb check` fails. It returns normally on success, so `bb test`, which depends
  on it, proceeds to the suite."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [emcli.app :as app]
            [emcli.commands :as cmd]
            [emcli.jsonschema :as js]
            [emcli.model :as m]
            [emcli.rules :as r]))

(def ^:private schema-path "docs/change-stream.schema.json")
(def ^:private doc-path    "docs/change-stream.md")

(defn- schema [] (json/parse-string (slurp schema-path)))

(defn- tag
  "Attach the reporting source to each violation of one payload."
  [source violations]
  (mapv #(assoc % :source source) violations))

;; ---------------------------------------------------------------------------
;; Code payloads — the real snapshot and one real delta per operation
;; ---------------------------------------------------------------------------

(defn- run!
  "Apply a command through the real commit path, asserting it succeeded."
  [app command opts]
  (let [res (cmd/run app command opts)]
    (when (:error res)
      (throw (ex-info (str "scenario command failed: " command " " (pr-str opts))
                      {:command command :opts opts :res res})))
    (:result res)))

(defn- id! [app command opts] (:id (run! app command opts)))

(defn- scenario
  "A store exercising every entity kind, built only through the real command
  path. Returns the app plus the ids the operation cases need."
  []
  (let [a    (app/new-app "Orders")
        tl   (id! a "create-timeline"   {:title "Order flow"})
        sw   (id! a "create-swimlane"   {:name "Lane A" :index 0})
        sw2  (id! a "create-swimlane"   {:name "Lane B" :index 1})
        sl   (id! a "add-slice"         {:timeline tl :title "Place order"
                                         :slice-type "state_change" :index 0})
        cmd  (id! a "create-element"    {:name "PlaceOrder"   :element-type "command"})
        evt  (id! a "create-element"    {:name "OrderPlaced"  :element-type "event"})
        evt2 (id! a "create-element"    {:name "OrderCancelled" :element-type "event"})
        rm   (id! a "create-element"    {:name "OrderSummary" :element-type "read_model"})
        scr  (id! a "create-element"    {:name "OrderScreen"  :element-type "screen"})]
    (run! a "add-field"       {:element cmd :name "id" :type "uuid"})
    (run! a "add-field"       {:element cmd :name "amount" :type "decimal"})
    (run! a "add-field"       {:element evt :name "id" :type "uuid"})
    ;; A nested field, so the snapshot's element registry is validated against the
    ;; recursive Field shape $defs/Element allows -- the nested placement
    ;; projection deliberately drops subfields, so a snapshot used to carry only
    ;; the flat shape. Driven through the rule a `set-fields` command would call:
    ;; no CLI affordance authors a subfield (add-field takes name and type only),
    ;; and a schema import is the other route in.
    (run! a "add-field-origin" {:element rm :field "total" :origin "user_input"})
    (let [res (app/apply-rule! a r/set-fields
                               {:element rm
                                :fields [{:name "total" :type :decimal
                                          :optional false :cardinality :single
                                          :subfields [{:name "currency" :type :string}]}]})]
      (when (r/error? res)
        (throw (ex-info "scenario rule failed: set-fields" {:res res}))))
    (run! a "assign-swimlane" {:element evt :lane sw2})
    (run! a "place-element"   {:slice sl :element cmd})
    (run! a "place-element"   {:slice sl :element evt})
    (run! a "place-element"   {:slice sl :element scr})
    (let [conn (id! a "connect"           {:from cmd :to evt})
          sp   (id! a "add-specification" {:slice sl :title "Happy path"})
          st1  (id! a "add-spec-step"     {:spec sp :clause "given_step"
                                           :element evt :index 0})]
      (run! a "add-wireframe-node" {:element scr :tag "row"})
      (run! a "add-wireframe-node" {:element scr :tag "button" :parent "n2" :label "OK"})
      (run! a "add-wireframe-node" {:element scr :tag "h1" :text "Title"})
      {:app a :tl tl :sw sw :sw2 sw2 :sl sl :cmd cmd :evt evt :evt2 evt2
       :rm rm :scr scr :conn conn :sp sp :st1 st1})))

(def ^:private op-cases
  "One entry per operation the change stream emits: [op command opts-fn]. The
  opts-fn receives the scenario. Kept in step with $defs/deltaOp by
  `op-coverage-violations`."
  [["CreateTimeline"         "create-timeline"
    (fn [_] {:title "New flow"})]
   ["RenameTimeline"         "rename-timeline"
    (fn [{:keys [tl]}] {:timeline tl :new-title "Renamed"})]
   ["DeleteTimeline"         "delete-timeline"
    (fn [{:keys [tl]}] {:timeline tl})]
   ["CreateSwimlane"         "create-swimlane"
    (fn [_] {:name "New lane" :index 2})]
   ["RenameSwimlane"         "rename-swimlane"
    (fn [{:keys [sw]}] {:lane sw :new-name "Renamed lane"})]
   ["ReorderSwimlane"        "reorder-swimlane"
    (fn [{:keys [sw]}] {:lane sw :new-index 5})]
   ["DeleteSwimlane"         "delete-swimlane"
    (fn [{:keys [sw2]}] {:lane sw2})]
   ["AddSlice"               "add-slice"
    (fn [{:keys [tl]}] {:timeline tl :title "New slice" :slice-type "state_view" :index 1})]
   ["ReorderSlice"           "reorder-slice"
    (fn [{:keys [sl]}] {:slice sl :new-index 3})]
   ["SetSliceStatus"         "set-slice-status"
    (fn [{:keys [sl]}] {:slice sl :new-status "done"})]
   ["SetSliceType"           "set-slice-type"
    (fn [{:keys [sl]}] {:slice sl :new-slice-type "automation"})]
   ["DeleteSlice"            "delete-slice"
    (fn [{:keys [sl]}] {:slice sl})]
   ["CreateElement"          "create-element"
    (fn [_] {:name "NewEl" :element-type "event"})]
   ["SetFields"              "add-field"
    (fn [{:keys [cmd]}] {:element cmd :name "total" :type "decimal"})]
   ["SetElementContext"      "set-element-context"
    (fn [{:keys [cmd]}] {:element cmd :new-context "external"})]
   ["AssignSwimlane"         "assign-swimlane"
    (fn [{:keys [cmd sw]}] {:element cmd :lane sw})]
   ["SetImageUrl"            "set-image-url"
    (fn [{:keys [scr]}] {:element scr :url "https://example.com/s.png"})]
   ["SetFieldOrigins"        "add-field-origin"
    (fn [{:keys [cmd]}] {:element cmd :field "id" :origin "user_input"})]
   ["RenameElement"          "rename-element"
    (fn [{:keys [cmd]}] {:element cmd :new-name "PlaceOrderRenamed"})]
   ["DeleteElement"          "delete-element"
    (fn [{:keys [cmd]}] {:element cmd})]
   ["AddWireframeNode"       "add-wireframe-node"
    (fn [{:keys [scr]}] {:element scr :tag "button" :parent "n2" :label "New"})]
   ["AddWireframeNodeBefore" "add-wireframe-node-before"
    (fn [{:keys [scr]}] {:element scr :before "n3" :tag "col"})]
   ["SetWireframeAttr"       "set-wireframe-attr"
    (fn [{:keys [scr]}] {:element scr :node "n3" :attr "label" :value "Hi"})]
   ["SetWireframeText"       "set-wireframe-text"
    (fn [{:keys [scr]}] {:element scr :node "n4" :text "New title"})]
   ["DeleteWireframeNode"    "delete-wireframe-node"
    (fn [{:keys [scr]}] {:element scr :node "n3"})]
   ["PlaceElement"           "place-element"
    (fn [{:keys [sl evt2]}] {:slice sl :element evt2})]
   ["ReorderPlacement"       "reorder-placement"
    (fn [{:keys [sl evt]}] {:slice sl :element evt :position "front"})]
   ["RemovePlacement"        "remove-placement"
    (fn [{:keys [sl evt]}] {:slice sl :element evt})]
   ["Connect"                "connect"
    (fn [{:keys [evt rm]}] {:from evt :to rm})]
   ["Disconnect"             "disconnect"
    (fn [{:keys [conn]}] {:connection conn})]
   ["SetConnectionDerivations" "add-derivation"
    (fn [{:keys [conn]}] {:connection conn :target "total" :from "amount"})]
   ["AddSpecification"       "add-specification"
    (fn [{:keys [sl]}] {:slice sl :title "Rejected"})]
   ["DeleteSpecification"    "delete-specification"
    (fn [{:keys [sp]}] {:spec sp})]
   ["AddSpecStep"            "add-spec-step"
    (fn [{:keys [sp cmd]}] {:spec sp :clause "when_step" :element cmd :index 1})]
   ["AddErrorStep"           "add-error-step"
    (fn [{:keys [sp]}] {:spec sp :error-name "AlreadyPlaced" :index 2})]
   ["RemoveSpecStep"         "remove-spec-step"
    (fn [{:keys [st1]}] {:step st1})]
   ["SetStepExamples"        "add-step-example"
    (fn [{:keys [st1]}] {:step st1 :field-name "id" :field-value "1"})]
   ["SetStepExpectEmpty"     "set-step-expect-empty"
    (fn [{:keys [st1]}] {:step st1 :value true})]])

(defn- wire
  "The exact JSON representation the server puts on the wire: cheshire
  serialises the payload, and we parse that back to plain string-keyed data."
  [payload]
  (json/parse-string (json/generate-string payload)))

(defn- snapshot-violations []
  (let [{:keys [app]} (scenario)
        snap (app/snapshot app)]
    (tag "code: snapshot (emcli.app/snapshot)"
         (js/validate (schema) (wire snap)))))

(defn- delta-for
  "The delta broadcast for one operation, plus any violation of the
  delta-per-mutation contract itself."
  [[op command opts-fn]]
  (let [{:keys [app] :as sc} (scenario)
        msgs   (atom [])
        _      (app/subscribe! app #(swap! msgs conj %))
        res    (cmd/run app command (opts-fn sc))
        source (str "code: delta " op " (emcli.commands/run \"" command "\")")]
    (if (:error res)
      [{:source source :pointer "" :message (str "operation was rejected: " (pr-str res))
        :expected :success :found (:error res)}]
      (let [delta (last @msgs)]
        (concat
         (when-not (= "snapshot" (name (:op (first @msgs))))
           [{:source source :pointer "" :message "expected the subscription to open with a snapshot"
             :expected "snapshot" :found (some-> (first @msgs) :op name)}])
         (when-not (= 2 (count @msgs))
           [{:source source :pointer "" :message "expected exactly one delta (DeltaPerMutation)"
             :expected 1 :found (dec (count @msgs))}])
         (when-not (= op (name (:op delta)))
           [{:source source :pointer "/op" :message "delta op does not match the expected operation"
             :expected op :found (name (:op delta))}])
         (tag source (js/validate (schema) (wire delta))))))))

(defn- delta-violations []
  (mapcat delta-for op-cases))

;; ---------------------------------------------------------------------------
;; Seed completeness
;;
;; A consumer seeds its state from the snapshot and patches it by id from the
;; deltas it receives. Two payload-level obligations follow from that, and
;; neither is expressible in the schema:
;;
;;   * the snapshot must carry the entity a reference it makes points at;
;;   * a delta must not name, by id alone, an entity the seed did not carry --
;;     which is what a placement does, and what leaves a consumer holding a
;;     reference it cannot resolve (the grey-box report upstream in
;;     em-frontend's session notes).
;; ---------------------------------------------------------------------------

(defn- snapshot-element-references
  "Every element id a snapshot mentions: the elements its placements nest, its
  connections' endpoints, and a normal spec-step's element ref (an error step's
  is nil and skipped)."
  [{:keys [model]}]
  (let [slices (mapcat :slices (:timelines model))
        steps  (mapcat :steps (mapcat :specifications slices))]
    (into #{}
          (concat (keep #(get-in % [:element :id]) (mapcat :placements slices))
                  (keep #(get-in % [:from :id]) (:connections model))
                  (keep #(get-in % [:to :id]) (:connections model))
                  (keep #(get-in % [:element :id]) steps)))))

(defn- snapshot-reference-violations
  "An element id the snapshot mentions but does not carry in `elements` cannot be
  resolved by a consumer seeded from that snapshot alone."
  []
  (let [{:keys [app]} (scenario)
        snap          (app/snapshot app)
        registered    (into #{} (map :id) (get-in snap [:model :elements]))
        missing       (sort (remove registered (snapshot-element-references snap)))]
    (for [id missing]
      {:source "code: snapshot (emcli.app/snapshot)" :pointer "/model/elements"
       :message "an element id the snapshot references is absent from its element registry"
       :expected id :found :absent})))

(def ^:private shared-element-facts
  "The element facts a snapshot carries twice: once in the canonical registry
  entry and once in the reduced projection nested under a placement. Read through
  `get` rather than `select-keys` so key *presence* -- which the wire genuinely
  differs on, the projection always carrying keys the canonical record adds only
  when an operation sets them -- does not read as disagreement. Only a differing
  value is a violation."
  [:id :name :element_type :swimlane :is_information_complete :image_url :wireframe])

(defn- element-facts [m]
  (into {} (map (fn [k] [k (get m k)])) shared-element-facts))

(defn- projected-fields
  "The fields as the placement projection carries them: name/type/optional/
  cardinality, subfields dropped (the projection never nests). Stripping the
  recursive part of the registry entry's fields has to reproduce exactly these,
  which pins the schema's SnapshotField claim from the other side."
  [fields]
  (mapv #(select-keys % [:name :type :optional :cardinality]) fields))

(defn- snapshot-registry-violations
  "The registry must be the model's own element list, and the nested placements
  must agree with it -- obligations the schema cannot express:

    * a consumer seeds its element index from `elements`, so an element the model
      holds but the registry omits is exactly the dangling reference this check
      exists for;
    * the nested placement projection duplicates the same element, so where the
      two carry the same fact they must not disagree, field shapes included (the
      nested copy is never patched by a delta, which is why the registry is the
      authoritative one); and
    * an element's assigned swimlane is a foreign key the registry makes
      reachable for the first time, so it must resolve in the snapshot too."
  []
  (let [{:keys [app]} (scenario)
        snap          (app/snapshot app)
        model         (:model snap)
        registered    (into {} (map (juxt :id identity)) (:elements model))
        model-ids     (into #{} (map :id) (m/elements (app/store app) (app/model-id app)))
        swimlane-ids  (into #{} (map :id) (:swimlanes model))
        nested        (for [p (mapcat :placements (mapcat :slices (:timelines model)))
                            :let [entry (get registered (get-in p [:element :id]))]]
                        {:id (get-in p [:element :id])
                         :nested (element-facts (:element p))
                         :registry (element-facts entry)
                         :nested-fields (vec (:fields (:element p)))
                         :registry-fields (projected-fields (:fields entry))})
        subfields     (->> (vals registered) (mapcat :fields) (mapcat :subfields))]
    (concat
     (for [id (sort (remove (set (keys registered)) model-ids))]
       {:source "code: snapshot (emcli.app/snapshot)" :pointer "/model/elements"
        :message "an element of the model is absent from the snapshot's element registry"
        :expected id :found :absent})
     (for [{:keys [id nested registry]} nested
           :when (not= nested registry)]
       {:source "code: snapshot (emcli.app/snapshot)" :pointer "/model/elements"
        :message (str "the element nested under element " id "'s placement disagrees with its registry entry")
        :expected registry :found nested})
     (for [{:keys [id nested-fields registry-fields]} nested
           :when (not= nested-fields registry-fields)]
       {:source "code: snapshot (emcli.app/snapshot)" :pointer "/model/elements"
        :message (str "the fields nested under element " id "'s placement are not a shape-only projection of its registry entry")
        :expected registry-fields :found nested-fields})
     (for [el (vals registered)
           :let [sw (:swimlane el)]
           :when (and (some? sw) (not (contains? swimlane-ids sw)))]
       {:source "code: snapshot (emcli.app/snapshot)" :pointer "/model/elements"
        :message (str "element " (:id el) " names a swimlane the snapshot does not carry")
        :expected sw :found :absent})
     ;; The recursive Field shape the schema allows is only exercised if a
     ;; scenario element actually carries a nested field: assert it reaches the
     ;; validated payload rather than trusting the scenario to keep doing so.
     (when-not (some #(= "currency" (:name %)) subfields)
       [{:source "check coverage" :pointer "/model/elements"
         :message "no registry entry carries the scenario's nested field, so the recursive Field shape stays unvalidated"
         :expected "currency" :found (mapv :name subfields)}]))))

(defn- seed-then-place-violations
  "The case the registry exists for, driven through the real paths: an element
  created before a client subscribes, then placed while it is listening. The
  client holds only the snapshot it was seeded with, and the placement delta
  names the element by id alone, so the seed has to carry it."
  []
  (let [a      (app/new-app "Orders")
        tl     (id! a "create-timeline" {:title "Order flow"})
        sl     (id! a "add-slice" {:timeline tl :title "Place order" :slice-type "state_change" :index 0})
        el     (id! a "create-element" {:name "OrderCancelled" :element-type "event"}) ; unplaced so far
        msgs   (atom [])
        _      (app/subscribe! a #(swap! msgs conj %))
        seed   (first @msgs)
        _      (run! a "place-element" {:slice sl :element el})
        delta  (last @msgs)
        placed (get-in delta [:changes 0 :entity :element])
        seeded (into #{} (map :id) (get-in seed [:model :elements]))]
    (cond-> []
      (not= 2 (count @msgs))
      (conj {:source "code: seed then place-element" :pointer ""
             :message "expected the subscription to open with the snapshot and then receive exactly one delta"
             :expected 2 :found (count @msgs)})

      (not (integer? placed))
      (conj {:source "code: seed then place-element" :pointer "/changes/0/entity/element"
             :message "the placement delta does not name its element by integer id"
             :expected :integer :found placed})

      (and (integer? placed) (not (contains? seeded placed)))
      (conj {:source "code: seed then place-element" :pointer "/model/elements"
             :message "a placement delta names an element the subscriber's seed did not carry"
             :expected placed :found :absent}))))

(defn- op-coverage-violations
  "The check must exercise exactly the operations the schema declares: a new op
  the check never produces, or a produced op the schema does not declare, is
  itself drift (and a silently unvalidated op would defeat the check)."
  []
  (let [declared (set (get-in (schema) ["$defs" "deltaOp" "enum"]))
        produced (mapv first op-cases)
        missing  (remove (set produced) (sort declared))
        extra    (remove declared (sort produced))
        dups     (->> produced frequencies (filter #(< 1 (val %))) keys sort)]
    (concat
     (for [op missing]
       {:source "check coverage" :pointer "/$defs/deltaOp/enum"
        :message "schema declares an op the check never exercises"
        :expected op :found :absent})
     (for [op extra]
       {:source "check coverage" :pointer "/$defs/deltaOp/enum"
        :message "the check exercises an op the schema does not declare"
        :expected :absent :found op})
     (for [op dups]
       {:source "check coverage" :pointer "/$defs/deltaOp/enum"
        :message "the check exercises an op more than once"
        :expected 1 :found (get (frequencies produced) op)}))))

(defn- code-violations []
  (concat (snapshot-violations)
          (snapshot-reference-violations)
          (snapshot-registry-violations)
          (seed-then-place-violations)
          (delta-violations)
          (op-coverage-violations)))

;; ---------------------------------------------------------------------------
;; Documented examples
;; ---------------------------------------------------------------------------

(def ^:private fragment-allowlist
  "Fenced blocks in docs/change-stream.md that are deliberately NOT
  self-contained payloads, keyed by the block's first non-blank line. Every
  entry must carry a reason; an unnamed fragment is reported as drift rather
  than skipped. Never add an entry without one."
  {"event: <name>"
   "wire-framing template: `event: <name>` / `data: <one line of JSON>` are placeholders, not values"})

(defn- fenced-blocks
  "Every fenced code block in `md`, as {:lang :start :end :text} with 1-based
  line numbers."
  [md]
  (loop [lines (str/split-lines md), n 1, acc [], open nil]
    (if-let [l (first lines)]
      (let [m (re-matches #"\s*```(.*)" l)]
        (cond
          (and m open)  (recur (rest lines) (inc n) (conj acc (assoc open :end n)) nil)
          m             (recur (rest lines) (inc n) acc
                               {:lang (str/trim (second m)) :start n :body []})
          :else         (recur (rest lines) (inc n) acc
                               (when open (update open :body conj l)))))
      (mapv (fn [{:keys [body] :as b}] (assoc b :text (str/join "\n" body))) acc))))

(defn- first-line [text]
  (->> (str/split-lines text) (remove str/blank?) first))

(defn- field-of
  "The value after `prefix` on the first line starting with it, or nil."
  [lines prefix]
  (when-let [line (first (filter #(str/starts-with? % prefix) lines))]
    (str/trim (subs line (count prefix)))))

(defn- sse-walk
  "Parse an SSE framing walk ({:event .. :data ..}) from `text`, or nil when it
  is not one (each non-blank line must be an `event:` or `data:` line)."
  [text]
  (let [lines (remove str/blank? (str/split-lines text))]
    (when (and (seq lines)
               (every? #(or (str/starts-with? % "event: ") (str/starts-with? % "data: ")) lines))
      {:event (field-of lines "event: ")
       :data  (field-of lines "data: ")})))

(defn- parse-json-or-violation
  "Parse `text` as JSON; on failure return the violation to report instead."
  [source pointer text]
  (try
    {:data (json/parse-string text)}
    (catch Exception e
      {:violation {:source source :pointer pointer
                   :message (str "not valid JSON: " (ex-message e))
                   :expected :json :found text}})))

(defn- validate-text
  "Validate the JSON text `text` (either against the whole `schema`, or against a
  `$ref` into it), reporting a parse failure as a violation instead."
  [source pointer text ref]
  (let [parsed (parse-json-or-violation source pointer text)]
    (if-let [v (:violation parsed)]
      [v]
      (tag source (if ref
                    (js/validate-ref (schema) ref (:data parsed))
                    (js/validate (schema) (:data parsed)))))))

(defn- doc-block-violations
  "Violations for one fenced block."
  [{:keys [lang start end text]}]
  (let [source (str doc-path ":" start "-" end " (" (if (str/blank? lang) "framing" lang) " block)")]
    (cond
      (contains? fragment-allowlist (first-line text))
      []

      (= "json" lang)
      (validate-text source "" text nil)

      (sse-walk text)
      (let [{:keys [event data]} (sse-walk text)
            parsed (parse-json-or-violation source "/data" data)]
        (if-let [v (:violation parsed)]
          [v]
          (concat
           (tag source (js/validate (schema) (:data parsed)))
           (when-not (= event (get-in parsed [:data "op"]))
             [{:source source :pointer "/op"
               :message "the SSE `event:` name must equal the payload's `op`"
               :expected event :found (get-in parsed [:data "op"])}]))))

      :else
      [{:source source :pointer ""
        :message (str "unclassified fenced block (first line: " (pr-str (first-line text))
                      "); name it in fragment-allowlist with a reason or make it a real payload")
        :expected :payload-or-named-fragment :found (first-line text)}])))

(defn- inline-wireframe-violations
  "The one inline (non-fenced) JSON example the prose carries: the WireframeNode
  encoding in the Embedded value objects section. It is read from the document
  (not hand-written) so it cannot silently drift, and validated against
  $defs/WireframeNode."
  [md]
  (let [source (str doc-path " (WireframeNode bullet, inline example)")]
    (if-let [example (second (re-find #"arrives as `(\[[^\]]*\])`" md))]
      (validate-text source "" example "#/$defs/WireframeNode")
      [{:source source :pointer ""
        :message "could not locate the inline WireframeNode example (expected a snippet introduced by `arrives as`)"
        :expected :example :found :absent}])))

(defn- doc-violations []
  (let [md (slurp doc-path)]
    (concat (mapcat doc-block-violations (fenced-blocks md))
            (inline-wireframe-violations md))))

;; ---------------------------------------------------------------------------
;; Reporting
;; ---------------------------------------------------------------------------

(defn- format-violation [{:keys [source pointer message expected found]}]
  (str source ": " (if (str/blank? (str pointer)) "/" pointer) ": " message
       " (expected " (pr-str expected) ", found " (pr-str found) ")"))

(defn violations
  "Every drift violation, empty when code, schema and docs agree."
  []
  (vec (concat (code-violations) (doc-violations))))

(defn run [& _]
  (let [vs (violations)]
    (if (seq vs)
      (do (doseq [v vs] (println (format-violation v)))
          (println (str "change-stream drift check FAILED: " (count vs) " violation(s) "
                        "against " schema-path))
          (System/exit 1))
      (println (str "change-stream drift check OK: "
                    "1 snapshot + " (count op-cases) " deltas + doc examples "
                    "conform to " schema-path)))))
