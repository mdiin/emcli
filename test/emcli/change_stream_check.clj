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
      `event:` name equals its payload's `op`.

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
            [emcli.jsonschema :as js]))

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
                                         :kind "state_change" :index 0})
        cmd  (id! a "create-element"    {:name "PlaceOrder"   :kind "command"})
        evt  (id! a "create-element"    {:name "OrderPlaced"  :kind "event"})
        evt2 (id! a "create-element"    {:name "OrderCancelled" :kind "event"})
        rm   (id! a "create-element"    {:name "OrderSummary" :kind "read_model"})
        scr  (id! a "create-element"    {:name "OrderScreen"  :kind "screen"})]
    (run! a "add-field"       {:element cmd :name "id" :type "uuid"})
    (run! a "add-field"       {:element cmd :name "amount" :type "decimal"})
    (run! a "add-field"       {:element evt :name "id" :type "uuid"})
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
    (fn [{:keys [tl]}] {:timeline tl :title "New slice" :kind "state_view" :index 1})]
   ["ReorderSlice"           "reorder-slice"
    (fn [{:keys [sl]}] {:slice sl :new-index 3})]
   ["SetSliceStatus"         "set-slice-status"
    (fn [{:keys [sl]}] {:slice sl :new-status "done"})]
   ["SetSliceKind"           "set-slice-kind"
    (fn [{:keys [sl]}] {:slice sl :new-kind "automation"})]
   ["DeleteSlice"            "delete-slice"
    (fn [{:keys [sl]}] {:slice sl})]
   ["CreateElement"          "create-element"
    (fn [_] {:name "NewEl" :kind "event"})]
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
  (concat (snapshot-violations) (delta-violations) (op-coverage-violations)))

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
