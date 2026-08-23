# Wireframe DSL

Screen elements in emcli can carry an embedded wireframe — a lightweight
description of a UI layout stored under the `:wireframe` key. Wireframes are
built and edited through the `wireframe` command group and rendered as indented
text trees by `emcli wireframe show`.

## Storage format

A wireframe is a hiccup-style EDN vector tree. Each node is a vector whose
first item is a keyword tag:

```edn
[:screen {:-id "n1"}
  [:col {:-id "n2"}
    [:h1 {:-id "n3"} "Your orders"]
    [:input {:-id "n4" :placeholder "Search..." :field-name "searchTerm"}]
    [:button {:-id "n5" :label "Create order" :variant :primary}]]]
```

Every node carries a stable `:-id` string (`"n1"`, `"n2"`, …) assigned at
creation time. Ids are monotonically allocated and never reused — deleting a
node does not shift or recycle any id.

When a wireframe is rendered or validated the `:-id` keys are stripped from
the output; they are an internal addressing mechanism only.

## Tags

Tags are grouped by their role. **Leaf** tags cannot have child nodes.
**Container** tags can. **Text-children** tags accept a plain string as their
content (passed via the `text` flag on `add-wireframe-node`).

### Layout — container

- `:screen` — implicit root, always `n1`, no attributes
- `:row` — horizontal group; attrs: `align` (start|center|end|between), `gap` (sm|md|lg)
- `:col` — vertical group; attrs: `align` (start|center|end|between), `gap` (sm|md|lg), `width` (narrow|wide|auto|full)
- `:divider` — horizontal rule, no attrs, **leaf**

### Typography — text-children

Pass the display text via the `text` flag on `add-wireframe-node`. Do not use
`:text` as an attribute name.

- `:h1`, `:h2`, `:h3` — headings, no attrs
- `:text` — body text; attrs: `align` (left|center|right), `tone` (default|muted|danger|success)
- `:span` — inline text; attrs: `tone` (default|muted|danger|success)

### Inputs — leaf

All input tags accept `field-name` (string) and `command-input` (bool).
`field-name` must reference a field that exists on the screen element's
`:fields` list.

- `:input` — single-line text field; attrs: `type` (text|email|password|number|tel|url), `label`, `placeholder`, `required`
- `:textarea` — multi-line text field; attrs: `label`, `placeholder`, `required`
- `:dropdown` — select list; attrs: **`options` (required**, comma-separated strings), `label`, `required`
- `:checkbox` — boolean toggle with label; attrs: `label`, `default` (bool)
- `:toggle` — toggle switch; attrs: `label`, `default` (bool)

### Actions — leaf

- `:button` — **`label` required**; attrs: `variant` (primary|secondary|ghost|danger), `disabled` (bool), `command-input` (bool)
- `:icon-button` — **`icon` and `aria-label` required**; attrs: `command-input` (bool)

### Content / navigation — leaf

- `:link` — **`label` required**; attrs: `command-input` (bool)
- `:image` — **`alt` required**; attrs: `aspect` (square|wide|tall), `command-input` (bool)
- `:icon` — **`name` required**; attrs: `size` (sm|md|lg)
- `:alert` — **`text` required**; attrs: `type` (info|warning|danger|success)

## CLI commands

```
# Add a node (tag is the only required positional arg; parent defaults to n1)
emcli wireframe add-node --element 42 --tag col
emcli wireframe add-node --element 42 --tag h1 --text "Your orders" --parent n2
emcli wireframe add-node --element 42 --tag button --label "Create order" --variant primary --command-input true --parent n2

# Insert a node before an existing sibling (--before is the existing sibling node id)
emcli wireframe add-node-before --element 42 --before n4 --tag divider

# Show the current wireframe (returns annotated text tree)
emcli wireframe show --element 42

# Patch one attribute on an existing node
emcli wireframe set-attr --element 42 --node n5 --attr label --value "New order"

# Delete a node (removes its entire subtree; other node ids are unaffected)
emcli wireframe delete-node --element 42 --node n3
```

## Output format

`show-wireframe` renders the tree as indented text:

```
[n1] :screen
  [n2] :col
    [n3] :h1  "Your orders"
    [n4] :input  {:placeholder "Search..." :field-name "searchTerm"}
    [n5] :button  {:label "Create order" :variant :primary}
```

Each line shows the stable node id, the tag, and either the string content
(for text-children nodes) or the attributes map.

## Authoring tips

- Call `wireframe show` before any `wireframe set-attr` or `wireframe delete-node`
  to obtain the current node ids. Ids are stable for the session once read.
- New nodes are appended as the last child of their parent by default. Use `wireframe add-node-before` to insert before an existing sibling instead.
- Deleting `n1` removes the entire wireframe.
- Attribute values are coerced from CLI strings: `true`/`false` become booleans,
  enum values (e.g. `primary`, `center`) become keywords, everything else stays
  a string.
