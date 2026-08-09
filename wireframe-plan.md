# Wireframe DSL — Specification & Implementation Plan (consolidated)

Version 2.0 — supersedes `wireframe-dsl-spec.md` and the earlier draft sections of this
file. Single source of truth going forward.

**Design deltas from the original draft (`wireframe-dsl-spec.md`), per explicit decision:**
  • `:select` → `:dropdown` (tag renamed)
  • No numeric attribute values anywhere — `:min`/`:max` dropped, no number-typed attrs at all
  • `:width` uses keyword hints (`:narrow`/`:wide`/`:auto`/`:full`), not `"50%"` strings
  • Node-id scheme (`:-id`) from the later planning session is authoritative
  • `:field-name` / `:command-input` semantic attributes from the later planning session are kept

────────────────────────────────────────

## 1. Overview

Wireframes are hiccup-like EDN structures embedded in screen elements. They describe how a
screen presents its own data, with optional metadata for frontend highlighting.

Key principles:
  • Semantic, not visual — describes meaning and structure, not pixels/colors/positions
  • Wireframes are stateless snapshots (one screen = one layout)
  • Wireframes reference only the screen element's own fields (not external data)
  • Actions, inputs, and content/navigation elements can be marked with `:command-input true`
    for frontend emphasis; multiple elements on a screen may carry this attribute simultaneously
  • Wireframes persist to the model's `.edn` file alongside other screen metadata
  • Tool-agnostic — the tree can be rendered to HTML, SVG, or docs by a separate consumer;
    that rendering is out of scope here

────────────────────────────────────────

## 2. Wireframe Structure

### Root Element

The `:wireframe` field on a screen element **is** the `:screen` element tree itself — there is
no wrapping map with separate `:id`/`:title` keys. The screen element's existing `:name` field
already serves as the human-readable title; the wireframe doesn't duplicate it.

Every node in the tree carries a `:-id` key (a string like `"n1"`, `"n2"`, ...) assigned at
append time. The id is internal — stripped before structural validation and rendering, invisible
to the DSL consumer, but stable across an editing session so an LLM (or human) can reference
nodes by id without re-navigating the tree after every edit.

```clojure
{:id 42 :kind :screen :name "OrderList"
 :fields [{:name "searchTerm" :type :string :origin :user_input}]
 :wireframe
 [:screen {:-id "n1"}
  [:col {:-id "n2"}
   [:h1 {:-id "n3"} "Your orders"]
   [:input {:-id "n4"} {:placeholder "Search..." :field-name "searchTerm"}]
   [:button {:-id "n5"} {:label "Create order" :variant :primary :command-input true}]]]}
```

`show-wireframe` renders this as:
```
[n1] :screen
[n2]   :col
[n3]     :h1  "Your orders"
[n4]     :input  {:placeholder "Search..." :field-name "searchTerm"}
[n5]     :button  {:label "Create order" :variant :primary :command-input true}
```

### Element Syntax (Hiccup-like)

  `[tag]`
  `[tag & children]`
  `[tag attrs-map & children]`

  • `tag` — a keyword (first item)
  • `attrs-map` — optional map (second item if present, detected by `map?`); at authoring time
    this is `{:-id "nN"} {content-attrs}` — two consecutive maps, id map first
  • `children` — zero or more strings or nested element vectors

Attribute value constraints:
  • Values must be keywords, booleans, or strings — **no numbers, pixel values, or color codes**
  • Content attributes: `:label`, `:placeholder`, `:text`, `:alt`, `:aria-label` accept strings
  • `:options` on `:dropdown` accepts a vector of strings (CLI authoring accepts a comma-separated
    string, e.g. `"Draft,Published,Archived"`, which is split into the vector)

A screen can have:
  • Neither `:wireframe` nor `:image_url` (text-only)
  • Only `:wireframe` (structured diagram)
  • Only `:image_url` (external mockup reference)
  • Both (wireframe + reference image)

────────────────────────────────────────

## 3. Element Reference

| Tag | Kind | Required attrs | Optional attrs | Children |
|---|---|---|---|---|
| `:screen` | root container | — | — | any (exactly one, must be tree root) |
| `:row` | layout | — | `:align` (`:start`/`:center`/`:end`/`:between`), `:gap` (`:sm`/`:md`/`:lg`) | elements |
| `:col` | layout | — | `:align`, `:gap`, `:width` (`:narrow`/`:wide`/`:auto`/`:full`) | elements |
| `:h1`/`:h2`/`:h3` | heading | — | — | string only |
| `:text` | typography | — | `:align` (`:left`/`:center`/`:right`), `:tone` (`:default`/`:muted`/`:danger`/`:success`) | string only |
| `:span` | inline text | — | `:tone` | string only |
| `:divider` | separator | — | — | none (leaf) |
| `:input` | input | — | `:type` (`:text`/`:email`/`:password`/`:number`/`:tel`/`:url`), `:label`, `:placeholder`, `:required` (bool), `:field-name`, `:command-input` (bool) | none (leaf) |
| `:textarea` | input | — | `:label`, `:placeholder`, `:required`, `:field-name`, `:command-input` | none (leaf) |
| `:dropdown` | input | `:options` (vector of strings) | `:label`, `:required`, `:field-name`, `:command-input` | none (leaf) |
| `:checkbox` | input | — | `:label`, `:default` (bool), `:field-name`, `:command-input` | none (leaf) |
| `:toggle` | input | — | `:label`, `:default` (bool), `:field-name`, `:command-input` | none (leaf) |
| `:button` | action | `:label` | `:variant` (`:primary`/`:secondary`/`:ghost`/`:danger`), `:disabled` (bool), `:command-input` | none (leaf) |
| `:icon-button` | action | `:icon`, `:aria-label` | `:command-input` | none (leaf) |
| `:link` | navigation | `:label` | `:command-input` | none (leaf) |
| `:image` | content | `:alt` | `:aspect` (`:square`/`:wide`/`:tall`) | none (leaf) |
| `:icon` | content | `:name` | `:size` (`:sm`/`:md`/`:lg`) | none (leaf) |
| `:alert` | content | `:text` | `:type` (`:info`/`:warning`/`:danger`/`:success`) | none (leaf) |

Nesting rules:
  • Text-only elements (`:h1`/`:h2`/`:h3`/`:text`/`:span`) accept string children only, never
    nested element vectors
  • Leaf elements (all input/action/content elements above) accept no children at all
  • Exactly one root `:screen` element per wireframe
  • No orphaned elements

**Deferred, not in current tag schema** (carried from the original draft, not scheduled):
`:card` (grouped content box), `:radio` (radio group). Add if/when a concrete screen needs them —
no reason to schema them speculatively.

────────────────────────────────────────

## 4. Semantic Attributes

Action elements (`:button`, `:icon-button`):
  • `:command-input` (boolean, optional, default `false`) — marks this element as a command
    input on the screen. Multiple elements may carry this simultaneously. Frontends can use this
    to highlight them visually.

Input elements (`:input`, `:textarea`, `:dropdown`, `:checkbox`, `:toggle`):
  • `:field-name` (string, optional) — references a field from the screen element's own
    `:fields` array. Must exist on the screen for semantic validation to pass.
  • `:command-input` (boolean, optional, default `false`) — same as above; may be combined with
    `:field-name` on the same element.

Content/navigation elements (`:link`, `:image`):
  • `:command-input` (boolean, optional, default `false`) — same as above.

────────────────────────────────────────

## 5. Validation

### Structural Validation

Strips `:-id` keys first (the validator never sees them), then enforces the tag/attribute
schema from §3:
  • All tags are recognized (from the allowed set)
  • All attribute values match their declared type (keyword, boolean, string)
  • Required attrs present (e.g. `:button` requires `:label`, `:dropdown` requires `:options`,
    `:icon-button` requires `:icon` + `:aria-label`, `:image` requires `:alt`, `:icon` requires
    `:name`, `:link` requires `:label`, `:alert` requires `:text`)
  • Attribute values within their allowed set, where one is declared
  • Nesting rules satisfied (leaf elements have no children; text elements have string children
    only)
  • Exactly one root `:screen` element

Returns: `{:valid? true}` or `{:valid? false :errors [{:node-id "nN" :message "..."}]}`

### Semantic Validation

Checks wireframe references against the owning screen element:
  • All `:field-name` values must exist in the screen's `:fields` array
  • A screen is information-complete when every `:field-name` referenced in the wireframe exists
    in the screen's `:fields` array. Extra fields on the screen not referenced in the wireframe
    do not affect completeness.
  • `:command-input` is accepted as-is on any element type — purely presentational, no
    validation needed. Multiple elements carrying `:command-input true` on the same screen is
    valid.

Takes the wireframe and the owning screen element as input. Returns the same error format as
structural validation.

No cross-model validation — wireframes do not validate against commands, events, or read
models. That's the event model's concern.

────────────────────────────────────────

## 6. Node ID Scheme

  • `next-node-id [wireframe]` → string `"nN"`, one past the highest existing `:-id` suffix in
    the tree; allocates monotonically, never reuses even across deletions
  • Ids are stable across the whole editing session regardless of insertions/deletions elsewhere
    in the tree — an LLM resolves ids once (via `show-wireframe`) and reuses them for subsequent
    edits without re-fetching, unless it needs to see newly added nodes
  • `strip-ids [wireframe]` → wireframe with all `:-id` keys removed; used before validation

────────────────────────────────────────

## 7. Authoring Commands

Three mutating + one read-only, under the `element` group.

### `add-wireframe-node`
```
emcli element add-wireframe-node --element 42 --tag col
emcli element add-wireframe-node --element 42 --tag h1 --text "Your orders" --parent n2
emcli element add-wireframe-node --element 42 --tag input --placeholder "Search..." --field-name searchTerm --parent n2
emcli element add-wireframe-node --element 42 --tag button --label "Create order" --variant primary --command-input true --parent n2
```
Flags: `--element` (int), `--tag` (string), `--parent` (node id string, default `"n1"` = root
`:screen`), plus any attribute flags valid for the chosen tag. Returns the updated element
including the new node's `:-id`.

### `set-wireframe-attr`
```
emcli element set-wireframe-attr --element 42 --node n4 --attr label --value "Updated label"
```
Flags: `--element` (int), `--node` (node id string), `--attr` (string), `--value` (string,
coerced per schema).

### `delete-wireframe-node`
```
emcli element delete-wireframe-node --element 42 --node n4
```
Flags: `--element` (int), `--node` (node id string). Deleting `n1` removes the entire wireframe.

### `show-wireframe` — read-only, CLI-only
```
emcli element show-wireframe --element 42
```
Fetches `GET /model`, finds the element, calls `wireframe/format-tree` locally. Prints annotated
tree with `[nN]` ids. Dies with a useful message if the element doesn't exist or has no
`:wireframe`.

────────────────────────────────────────

## 8. Files to Create/Modify

### New: `src/emcli/wireframe.clj`
  • `next-node-id [wireframe]` — see §6
  • `tag-schema` — map of `tag-kw → {:attrs {attr-kw {:type :kw|:bool|:str :required? bool
    :values #{...}}} :leaf? bool :text-children? bool}` per §3. `:-id` is not in any attr
    schema — reserved and handled separately everywhere.
  • `allowed-tags` — derived set
  • `strip-ids [wireframe]` — see §6
  • `validate [wireframe]` — strips ids, then checks tags, required attrs, value types/allowed
    sets, leaf/text-child nesting, `:screen` root. Returns `{:valid? true}` or `{:valid? false
    :errors [{:node-id str :message str}]}`
  • `validate-semantics [wireframe screen-element]` — checks `:field-name` against the screen's
    `:fields`. Same error shape.
  • `find-node [wireframe node-id]` — raw node vector (with `:-id`) or nil
  • `find-node-path [wireframe node-id]` — vector of indices for `assoc-in`/`update-in`; nil if
    not found
  • `append-child-at [wireframe parent-node-id child-vec]` — assigns a fresh `:-id`, appends to
    the parent's children, returns updated wireframe
  • `assoc-attr-at [wireframe node-id attr-kw value]` — updates one attribute on the node
  • `delete-node-at [wireframe node-id]` — removes the node (and subtree); returns nil if
    `node-id` is the root
  • `parse-node-attrs [tag-kw opts-map]` — `{:ok attrs-map}` or `{:error msg}`; coerces and
    validates flat CLI opts against schema, rejects unknown keys, checks required attrs
  • `coerce-attr-value [attr-kw raw schema-entry]` — typed value; handles `:kw`, `:bool`, `:str`,
    `:str-list` (comma-split for `:options`)
  • `format-tree [wireframe]` — annotated string with `[nN]` prefix and indentation per depth

### New: `test/emcli/wireframe_test.clj` (already written)
  • `validate`: valid tree, unknown tag, missing required attr (one case per tag with a required
    attr, using `:dropdown`), wrong value type, leaf with children, heading with
    vector child, non-`:screen` root — all via `strip-ids`-clean input
  • `validate-semantics`: field-name present → ok, absent → error with node-id, no field-names →
    ok
  • `find-node`/`find-node-path`: known id, unknown id → nil, nested node
  • `append-child-at`: appends to root, appends to nested node, assigns fresh `:-id` that doesn't
    collide with existing ones
  • `assoc-attr-at`: correct node updated, siblings untouched
  • `delete-node-at`: leaf removed, subtree removed, root returns nil
  • `parse-node-attrs`: known attrs coerce correctly, unknown attr rejected, required missing
    rejected, value outside allowed set rejected, `:options` comma-splits correctly (against
    `:dropdown`)
  • `next-node-id`: monotonically advances past existing highest N
  • `format-tree`: `[nN]` prefixes present, indentation matches depth

### Modified: `src/emcli/model.clj`
  • `screen-has-field? [screen field-name]` → `(boolean (some #(= field-name (:name %)) (:fields screen)))`

### Modified: `src/emcli/rules.clj` — three new rules

`add-wireframe-node [store {:keys [element tag parent attrs]}]`:
 1. `require-entity`; check `:kind :screen`
 2. If no `:wireframe`, seed `[:screen {:-id "n1"}]`
 3. `wireframe/append-child-at` with `[tag attrs]` (attrs may be empty map or omitted)
 4. `wireframe/validate` + `wireframe/validate-semantics`; return `{:error :invalid-wireframe
    :errors [...]}` on failure
 5. `m/set-field :wireframe`; `commit :AddWireframeNode`

`set-wireframe-attr [store {:keys [element node attr value]}]`:
 1. `require-entity`; check wireframe exists; check node exists (`find-node`)
 2. `wireframe/assoc-attr-at`; validate; commit `:SetWireframeAttr`

`delete-wireframe-node [store {:keys [element node]}]`:
 1. `require-entity`; check wireframe exists; check node exists
 2. `wireframe/delete-node-at`; if nil, `m/set-field` dissocs `:wireframe`; else sets updated;
    if non-nil, validate; commit `:DeleteWireframeNode`

### Modified: `src/emcli/commands.clj`

Registry entry for `delete-wireframe-node` (all scalars — node id is a string, not int):
```
"delete-wireframe-node" {:rule r/delete-wireframe-node
                          :params [[:element :element :int true] [:node :node :str true]]}
```

Composite cases in `run` cond for `add-wireframe-node` and `set-wireframe-attr`:
  • `add-wireframe-node`: extract `:element` (int), `:tag` (keyword), `:parent` (string,
    default `"n1"`); call `wireframe/parse-node-attrs` on remaining opts for attrs; pass to rule
  • `set-wireframe-attr`: extract `:element` (int), `:node` (string), `:attr` (string), `:value`
    (string); look up the node's tag via `wireframe/find-node` on current store to get schema
    entry; call `wireframe/coerce-attr-value`; pass to rule

Both added to `structured-int-params` (`:element` only — `:node` is a string) and
`structured-manifest-params`.

`show-wireframe` is not in the registry — CLI-only.

### Modified: `src/emcli/cli.clj`

Add to `command-groups` under `"element"`:
```
"add-wireframe-node"     "add-wireframe-node"
"set-wireframe-attr"     "set-wireframe-attr"
"delete-wireframe-node"  "delete-wireframe-node"
"show-wireframe"         "show-wireframe"
```

`do-show-wireframe [opts server]`: fetches `GET /model`, finds element in `(:elements body)` by
`--element` id, calls `wireframe/format-tree`, prints. Dies with useful message if element not
found or has no `:wireframe`.

Special-case `"show-wireframe"` in the dispatch cond before `do-authoring`:
```
(= verb "show-wireframe") (do-show-wireframe opts (server-url opts))
```

Add to `structured-manifest-params`:
```
"show-wireframe"         [{:flag "element" :type "int" :required true :ref "elements[].id"}]
"add-wireframe-node"     [{:flag "element" ...} {:flag "tag" ...}
                          {:flag "parent" :type "string" :required false
                           :note "node id of parent (default: n1 = root :screen)"}
                          {:flag "<tag-attrs>" :note "any attribute flag valid for the chosen tag"}]
"set-wireframe-attr"     [{:flag "element" ...}
                          {:flag "node" :type "string" :required true :note "node id from show-wireframe"}
                          {:flag "attr" ...} {:flag "value" ...}]
"delete-wireframe-node"  [{:flag "element" ...}
                          {:flag "node" :type "string" :required true :note "node id from show-wireframe"}]
```

### Modified: `src/emcli/server.clj`

Three new POST routes: `/authoring/add-wireframe-node`, `/authoring/set-wireframe-attr`,
`/authoring/delete-wireframe-node`. Same boilerplate as existing authoring routes.

### Modified: `test/emcli/rules_test.clj`
  • `add-wireframe-node`: seeds `:screen` with `n1`, appends col → gets `n2`, appends h1 under n2
    → gets `n3`; verifies ids stable after further appends; rejects unknown tag; rejects unknown
    field-name
  • `set-wireframe-attr`: updates button label via node id, node id unaffected; rejects unknown
    node id
  • `delete-wireframe-node`: removes leaf, sibling ids unaffected; removes subtree; removing `n1`
    clears `:wireframe` field entirely

### Modified: `.dirge/plugins/emcli.janet`

Register `emcli_show_wireframe` as a fourth LLM tool. Handler extracts the numeric element id
from args-json using the numeric capture pattern (same as `kv-pat` num-val), calls `run-emcli
["element" "show-wireframe" "--element" (string element-id)]`:
```janet
(defn emcli-show-wireframe-handler [args-json]
  (def pat
    (peg/compile
      ~(any
         (+ (sequence "\"element\"" :s* ":" :s*
                      (capture (some (+ :d (set "-.")))))
            1))))
  (def result (peg/match pat args-json))
  (if (and result (> (length result) 0))
    (run-emcli ["element" "show-wireframe" "--element" (get result 0)])
    "Error: missing 'element' argument"))
```
Registered as `:parallel`.

### Modified: `emcli-authoring` skill
  • Add `emcli_show_wireframe` to the tool workflow section: call it before `set-wireframe-attr`
    or `delete-wireframe-node` to get current node ids. Node ids are stable across insertions and
    deletions, so one call suffices for a whole editing session unless new nodes were just added.
  • Add a wireframe subsection under `element` with the full tag/attr reference table (§3) and a
    workflow example.

────────────────────────────────────────

## 9. Persistence & Export

No changes to `schema.clj` needed. The existing serialization already handles nested
structures:
  • Screen elements serialize to JSON with all their fields (including `:wireframe`)
  • Wireframes persist to the model's `.edn` file as nested vectors/maps
  • Round-trip (save → load) preserves wireframe structure exactly, including `:-id`s

Export to external systems (Figma, docs, server-side HTML/SVG rendering, etc.) is a separate
concern and deferred.

────────────────────────────────────────

## 10. Error Shapes

  • `{:error :invalid-wireframe :errors [{:node-id str :message str}]}` — validation failure
  • `{:error :not-found :type :wireframe-node :id "nN" :message "node nN does not exist"}` —
    unknown node id
  • `{:error :not-found :type :wireframe :message "element N has no wireframe"}` — set/delete on
    an element with no wireframe
  • `{:error :invalid-value :message "element N is not a screen"}` — wrong element kind
  • `{:error :invalid-value :message "..."}` — unknown attr, bad value, missing required attr

────────────────────────────────────────

## 11. Examples

### Valid wireframe (with command input)
```clojure
[:screen {:-id "n1"}
 [:col {:-id "n2"}
  [:h1 {:-id "n3"} "Your orders"]
  [:input {:-id "n4"} {:placeholder "Search..." :field-name "searchTerm"}]
  [:button {:-id "n5"} {:label "Create order" :variant :primary :command-input true}]
  [:button {:-id "n6"} {:label "Refresh" :variant :secondary}]]]
```
Screen element must have field `searchTerm` for this to pass semantic validation.

### Valid wireframe (confirmation, no field references)
```clojure
[:screen {:-id "n1"}
 [:col {:-id "n2"} {:align :center :width :narrow}
  [:h2 {:-id "n3"} "Delete order?"]
  [:text {:-id "n4"} {:tone :danger} "This cannot be undone."]
  [:row {:-id "n5"} {:align :end :gap :sm}
   [:button {:-id "n6"} {:label "Cancel" :variant :ghost}]
   [:button {:-id "n7"} {:label "Delete" :variant :danger :command-input true}]]]]
```
No field references — screen can have any fields or none.

### Invalid wireframe (field doesn't exist)
```clojure
[:screen {:-id "n1"}
 [:input {:-id "n2"} {:field-name "nonexistent"}]]
```
Error: `{:valid? false :errors [{:node-id "n2" :message "Field 'nonexistent' does not exist on screen"}]}`

────────────────────────────────────────

## 12. Implementation Order

 1. `src/emcli/wireframe.clj`
 2. `test/emcli/wireframe_test.clj`
 3. `src/emcli/model.clj`
 4. `src/emcli/rules.clj`
 5. `src/emcli/commands.clj`
 6. `src/emcli/server.clj`
 7. `src/emcli/cli.clj`
 8. `test/emcli/rules_test.clj`
 9. `.dirge/plugins/emcli.janet`
 10. `emcli-authoring` skill

## 13. Future Considerations

  • Server-side rendering: wireframe → HTML/SVG. Handled in a separate project.
  • `:card` and `:radio` tags — add to `tag-schema` (§3) if a concrete screen needs grouped
    content boxes or single-select radio groups; not scheduled now.
