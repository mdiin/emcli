---
description: Complete command reference for authoring an Event Model via the emcli dirge plugin tools (emcli_resolve, emcli_validate, emcli_author).
triggers:
  - emcli_author
  - emcli_resolve
  - emcli_validate
  - event model authoring
  - create timeline
  - add timeline
  - rename timeline
  - delete timeline
  - add swimlane
  - reorder swimlane
  - add slice
  - reorder slice
  - add element
  - rename element
  - delete element
  - add field
  - remove field
  - place element
  - reorder placement
  - connect element
  - add connection
  - add specification
  - add spec
  - add step
  - add example
  - remove example
  - add derivation
---

# emcli Authoring Reference

## Prerequisites

`emcli serve` must be running.

## Tool workflow

1. **Resolve names to ids** with `emcli_resolve` before any `emcli_author` call that needs an integer id. Never guess ids.
2. **Author** with `emcli_author` — one call per command.
3. **Validate** with `emcli_validate` after a sequence of mutations to catch inconsistencies.
4. **Show wireframe** with `emcli_show_wireframe` to get current node ids before patching or deleting wireframe nodes. Node ids are stable — one call per editing session suffices unless new nodes were added.

## emcli_resolve

```
queries: "Name[:kind_hint],..."
```

`kind_hint` filters candidates: `command`, `event`, `read_model`, `screen`, `automation`, `timeline`, `swimlane`.

Returns an array of matches with `id`, `kind`, `swimlane`, and `name`.

## emcli_author: group / verb / args reference

### timeline

| verb   | required args                          | optional args |
|--------|----------------------------------------|---------------|
| add    | `title` (string)                       | `id` (int)    |
| rename | `timeline` (int), `new-title` (string) |               |
| delete | `timeline` (int)                       |               |

### swimlane

| verb    | required args                          | optional args |
|---------|----------------------------------------|---------------|
| add     | `name` (string), `index` (int)         | `id` (int)    |
| rename  | `lane` (int), `new-name` (string)      |               |
| reorder | `lane` (int), `new-index` (int)        |               |
| delete  | `lane` (int)                           |               |

### slice

| verb    | required args                                                        | optional args |
|---------|----------------------------------------------------------------------|---------------|
| add     | `timeline` (int), `title` (string), `kind` (keyword), `index` (int) | `id` (int)    |
| reorder | `slice` (int), `new-index` (int)                                     |               |
| status  | `slice` (int), `new-status` (keyword)                                |               |
| kind    | `slice` (int), `new-kind` (keyword)                                  |               |
| delete  | `slice` (int)                                                        |               |

Slice `kind` values: `state_change`, `state_view`, `automation`

Slice `new-status` values: `created`, `in_progress`, `done`, `informational`

### element

| verb          | required args                                      | optional args           |
|---------------|----------------------------------------------------|-------------------------|
| add           | `name` (string), `kind` (keyword)                  | `id` (int)              |
| rename        | `element` (int), `new-name` (string)               |                         |
| delete        | `element` (int)                                    |                         |
| context       | `element` (int), `new-context` (keyword)           |                         |
| swimlane      | `element` (int), `lane` (int)                      |                         |
| image         | `element` (int), `url` (string)                    |                         |
| add-field     | `element` (int), `name` (string), `type` (keyword) | `cardinality` (keyword) |
| remove-field  | `element` (int), `name` (string)                   |                         |
| add-origin    | `element` (int), `field` (string), `origin` (keyword) |                      |
| remove-origin | `element` (int), `field` (string)                  |                         |

Element `kind` values: `command`, `event`, `read_model`, `screen`, `automation`

Field `type` values: `string`, `boolean`, `double`, `decimal`, `long`, `custom`, `date`, `date_time`, `uuid`, `int`

Field `cardinality` values: `single`, `list`

Field `origin` values: `user_input`, `generated`, `external`

### placement

| verb    | required args                        | optional args |
|---------|--------------------------------------|---------------|
| add     | `slice` (int), `element` (int)       | `id` (int)    |
| reorder | `placement` (int), `new-index` (int) |               |
| remove  | `placement` (int)                    |               |

### connection

| verb              | required args                                                            | optional args |
|-------------------|--------------------------------------------------------------------------|---------------|
| add               | `from` (int), `to` (int)                                                 | `id` (int)    |
| remove            | `connection` (int)                                                       |               |
| add-derivation    | `connection` (int), `target` (string), `from` (string, comma-separated) |               |
| remove-derivation | `connection` (int), `target` (string)                                    |               |

### spec

| verb   | required args                   | optional args |
|--------|---------------------------------|---------------|
| add    | `slice` (int), `title` (string) | `id` (int)    |
| delete | `spec` (int)                    |               |

### step

| verb           | required args                                                     | optional args |
|----------------|-------------------------------------------------------------------|---------------|
| add            | `spec` (int), `clause` (keyword), `element` (int), `index` (int) | `id` (int)    |
| error          | `spec` (int), `error-name` (string), `index` (int)               | `id` (int)    |
| remove         | `step` (int)                                                      |               |
| add-example    | `step` (int), `field-name` (string), `value` (string)            |               |
| remove-example | `step` (int), `field-name` (string)                              |               |
| expect-empty   | `step` (int), `value` (boolean: true/false)                      |               |

Step `clause` values: `given_step`, `when_step`, `then_step`

## Example sequence

```
# 1. Resolve an existing timeline to get its id
emcli_resolve  queries="Order Flow"

# 2. Add a slice to it (assuming resolve returned id 1)
emcli_author  group="slice"  verb="add"
              args={"timeline": 1, "title": "Place Order", "kind": "state_change", "index": 0}

# 3. Add an element
emcli_author  group="element"  verb="add"
              args={"name": "Place Order", "kind": "command"}

# 4. The emcli_author result already contains the new element's id.
#    No resolve needed -- use the id from step 3's result directly.

# 5. Place it (assuming slice id=5, element id=10 from step 3 result)
emcli_author  group="placement"  verb="add"
              args={"slice": 5, "element": 10}

# 6. Validate
emcli_validate
```

## Notes

- All integer id arguments come from `emcli_resolve` (for existing entities) or from the result of a prior `emcli_author` call. Every authoring command returns the created/modified entity as JSON, so you can read the `id` directly from that result — no need to call `emcli_resolve` immediately after creating something.
- The optional `id` arg on create commands lets you pre-assign a stable id; it must not already be in use.
- `index` in slice/placement/swimlane is zero-based and denotes position within the parent's ordered list.
- `emcli_author` result on success is the created/modified entity as JSON. On error it returns an `Error:` prefixed string with the server's message.
- Element and timeline names may contain spaces (e.g. `"User created"`, `"Order Flow"`).

### element — wireframe authoring

Wireframes are built incrementally on `:screen` elements. The `:wireframe` field holds an element tree where every node carries a stable `:-id` string (e.g. `"n1"`, `"n2"`, ...) assigned at append time. Node ids never change regardless of insertions or deletions — resolve once with `emcli_show_wireframe`, reuse ids for the whole editing session.

**Tools:**
- `emcli_show_wireframe` — call before `set-wireframe-attr` or `delete-wireframe-node` to get current node ids. Not available via `emcli_author` — use the dedicated plugin tool.
- `emcli_author` — use for `add-wireframe-node`, `set-wireframe-attr`, `delete-wireframe-node`.

**Verbs:**

| verb | required args | optional args |
|------|--------------|---------------|
| `add-wireframe-node` | `element` (int), `tag` (string) | `parent` (node-id string, default `"n1"`), + tag attribute flags |
| `set-wireframe-attr` | `element` (int), `node` (node-id string), `attr` (string), `value` (string) | |
| `delete-wireframe-node` | `element` (int), `node` (node-id string) | |

**Tags and their attribute flags** (authoritative — matches `tag-schema` in `wireframe.clj`):

Layout (accept element children):
- `:screen` — root node, always `n1`, no attrs
- `:row` — `align` (start\|center\|end\|between), `gap` (sm\|md\|lg)
- `:col` — `align` (start\|center\|end\|between), `gap` (sm\|md\|lg), `width` (narrow\|wide\|auto\|full)
- `:divider` — no attrs, leaf (no children)

Typography (string children via `text` key in `add-wireframe-node` args):
- `:h1` `:h2` `:h3` — no attrs, string children only
- `:text` — `align` (left\|center\|right), `tone` (default\|muted\|danger\|success), string children only
- `:span` — `tone` (default\|muted\|danger\|success), string children only

Inputs (leaf — no children):
- `:input` — `type` (text\|email\|password\|number\|tel\|url), `label` (string), `placeholder` (string), `required` (bool), `field-name` (string), `command-input` (bool)
- `:textarea` — `label` (string), `placeholder` (string), `required` (bool), `field-name` (string), `command-input` (bool)
- `:dropdown` — **`options` (required**, comma-separated strings), `label` (string), `required` (bool), `field-name` (string), `command-input` (bool)
- `:checkbox` — `label` (string), `default` (bool), `field-name` (string), `command-input` (bool)
- `:toggle` — `label` (string), `default` (bool), `field-name` (string), `command-input` (bool)

Actions (leaf):
- `:button` — **`label` (string, required)**, `variant` (primary\|secondary\|ghost\|danger), `disabled` (bool), `command-input` (bool)
- `:icon-button` — **`icon` (string, required)**, **`aria-label` (string, required)**, `command-input` (bool)

Content/navigation (leaf):
- `:link` — **`label` (string, required)**, `command-input` (bool)
- `:image` — **`alt` (string, required)**, `aspect` (square\|wide\|tall), `command-input` (bool)
- `:icon` — **`name` (string, required)**, `size` (sm\|md\|lg)
- `:alert` — **`text` (string, required)**, `type` (info\|warning\|danger\|success)

**Workflow example** — build the Order List screen wireframe (element id 42):

```
# 1. Add root container
emcli_author {group: "element", verb: "add-wireframe-node", args: {element: 42, tag: "col"}}
# → n2 created under n1 (:screen)

# 2. Add children to n2
emcli_author {group: "element", verb: "add-wireframe-node", args: {element: 42, tag: "h1", text: "Your orders", parent: "n2"}}
emcli_author {group: "element", verb: "add-wireframe-node", args: {element: 42, tag: "input", placeholder: "Search...", field-name: "searchTerm", parent: "n2"}}
emcli_author {group: "element", verb: "add-wireframe-node", args: {element: 42, tag: "button", label: "Create order", variant: "primary", command-input: true, parent: "n2"}}

# 3. Inspect to get node ids for patching
emcli_show_wireframe {element: 42}
# → [n1] :screen
#   [n2]   :col
#   [n3]     :h1  "Your orders"
#   [n4]     :input  {:placeholder "Search..." :field-name "searchTerm"}
#   [n5]     :button  {:label "Create order" :variant :primary :command-input true}

# 4. Patch one attribute
emcli_author {group: "element", verb: "set-wireframe-attr", args: {element: 42, node: "n5", attr: "label", value: "New order"}}

# 5. Delete a node (n3 removed, n4/n5 ids unaffected)
emcli_author {group: "element", verb: "delete-wireframe-node", args: {element: 42, node: "n3"}}
```

**Notes:**
- `field-name` values on inputs must reference a field that exists on the screen element's `:fields` list. Use `emcli_resolve` to confirm field names before adding inputs.
- Deleting a node removes its entire subtree. Deleting `n1` removes the entire wireframe.
- `:-id` keys are internal — they appear in the stored EDN but are stripped before validation and rendering.

## Verification

```
emcli validate
```
