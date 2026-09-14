# Wireframe DSL

Screen elements in emcli can carry an embedded wireframe — a lightweight
description of a UI layout stored under the `:wireframe` key. Wireframes are
built and edited through the `wireframe` command group and rendered as indented
text trees by `emcli wireframe show`.

A wireframe describes *what* a screen presents and accepts — structure, labels
and which of the screen's own fields a node is about — never how it is drawn.
There are no pixels, colours or positions anywhere in the vocabulary.

Wireframes belong to **screen** elements only: every `wireframe` verb takes a
`--element` that must be a screen. A screen may carry a wireframe, an external
mockup reference set with `emcli element image --element N --url ...`, both, or
neither.

Wireframes are authoring metadata: they persist to the model's `.edn` file
alongside the rest of the model, but they are **not** part of the
`eventmodeling.schema.json` export, so they do not survive an export/import
round-trip.

## Storage format

A wireframe is a hiccup-style EDN vector tree. Each node is a vector:

```
[tag {:-id "nN"} {attributes} ...children]
```

The tag is a keyword, the second element is the node's **id map**, and the third
element — present only when the node has attributes — is a separate map of
content attributes. Children follow: nested node vectors, and plain strings for
text tags.

```edn
[:canvas {:-id "n1"}
  [:col {:-id "n2"}
    [:h1 {:-id "n3"} "Your orders"]
    [:input {:-id "n4"} {:placeholder "Search..." :field-name "searchTerm"}]
    [:button {:-id "n5"} {:label "Create order" :variant :primary}]]]
```

The id map and the attribute map are kept apart deliberately: `:-id` is the
node's address, never one of its attributes. `:-id` is the only key the id map
ever holds.

Every node carries a stable `:-id` string (`"n1"`, `"n2"`, …) assigned when the
node is created. Ids are allocated as *one past the highest id currently in the
tree*, and are never reused — deleting a node neither shifts nor recycles any
other node's id. Ids are therefore stable under unrelated edits, but they are
not the document order: inserting a node before a sibling gives it the next
free id, which may be higher than its following siblings'. `emcli wireframe
show` prints the current ids.

Ids address nodes for editing; they carry no meaning for the layout itself.

## Tags

Tags are grouped by their role:

- **Container** tags hold child nodes.
- **Leaf** tags hold no children.
- **Text-children** tags hold a plain string as their content, set with
  `emcli wireframe set-text` (see [CLI commands](#cli-commands)).

Two attributes are shared across most of the vocabulary:

- `command-input` (bool) — marks the node as a command input on the screen, for a
  frontend to emphasise. Accepted by **every tag except `:canvas` and
  `:divider`**; several nodes on one screen may carry it at once, and it is
  accepted as-is (nothing validates it).
- `field-name` (string) — names the screen field this node displays or accepts,
  and **must reference a field that exists on the screen element's `:fields`
  list**. Accepted by every tag except `:canvas`, `:divider` and the action and
  navigation tags `:button`, `:icon-button` and `:link`.

The per-tag lists below give each tag's own attributes.

### Layout — container

- `:canvas` — the root of every wireframe; always `n1`, no attributes
- `:row` — horizontal group; attrs: `align` (start|center|end|between), `gap` (sm|md|lg)
- `:col` — vertical group; attrs: `align` (start|center|end|between), `gap` (sm|md|lg), `width` (narrow|wide|auto|full)
- `:divider` — horizontal rule, no attrs, **leaf**

### Typography — text-children

Content is a string child, added with `wireframe set-text`.

- `:h1`, `:h2`, `:h3` — headings, no other attributes
- `:text` — body text; attrs: `align` (left|center|right), `tone` (default|muted|danger|success)
- `:span` — inline text; attrs: `tone` (default|muted|danger|success)

### Inputs — leaf

- `:input` — single-line text field; attrs: `type` (text|email|password|number|tel|url), `label`, `placeholder`, `required` (bool)
- `:textarea` — multi-line text field; attrs: `label`, `placeholder`, `required` (bool)
- `:dropdown` — select list; attrs: **`options` (required**, comma-separated strings), `label`, `required` (bool)
- `:checkbox` — boolean toggle with label; attrs: `label`, `default` (bool)
- `:toggle` — toggle switch; attrs: `label`, `default` (bool)

An input's `field-name` is what ties the layout to the model: it must name one of
the screen's own fields, so a screen can only lay out data it declares.

### Actions — leaf

- `:button` — **`label` required**; attrs: `variant` (primary|secondary|ghost|danger), `disabled` (bool)
- `:icon-button` — **`icon` and `aria-label` required**; no other attributes

### Content / navigation — leaf

- `:link` — **`label` required**; no other attributes
- `:image` — **`alt` required**; attrs: `aspect` (square|wide|tall)
- `:icon` — **`name` required**; attrs: `size` (sm|md|lg)
- `:alert` — **`text` required**; attrs: `type` (info|warning|danger|success)

Note that `:alert` takes its message as the `text` *attribute*, whereas the
text-children tags take it as a string child — passed as `--text` when the node
is created, or set afterwards with `set-text`. No other tag accepts `text`.

## CLI commands

```
# List the verbs, or get per-verb options
emcli wireframe
emcli wireframe add-node help

# Add a node as the last child of --parent (omit --parent to append at the root)
emcli wireframe add-node --element 42 --tag col
emcli wireframe add-node --element 42 --tag input --parent n2 --placeholder "Search..." --field-name searchTerm
emcli wireframe add-node --element 42 --tag button --parent n2 --label "Create order" --variant primary --command-input true

# Text-children tags take their content up front with --text
emcli wireframe add-node --element 42 --tag h1 --parent n2 --text "Your orders"

# ...or afterwards, once the node has an id
emcli wireframe set-text --element 42 --node n7 --text "Your orders"

# Insert a node before an existing sibling (--before is the existing sibling's id)
emcli wireframe add-node-before --element 42 --before n4 --tag divider

# Show the current wireframe (annotated text tree)
emcli wireframe show --element 42

# Patch one attribute on an existing node
emcli wireframe set-attr --element 42 --node n5 --attr label --value "New order"

# Delete a node (removes its entire subtree; other node ids are unaffected)
emcli wireframe delete-node --element 42 --node n3
```

`--element` must always name a **screen** element that exists; any other kind is
rejected with `element N is not a screen`.

## Output format

`wireframe show` renders the tree as indented text:

```
[n1] :canvas
  [n2] :col
    [n3] :h1  "Your orders"
    [n4] :input  {:placeholder "Search...", :field-name "searchTerm"}
    [n5] :button  {:label "Create order", :variant :primary}
```

Each line shows the stable node id, the tag, and either the string content (for
text nodes) or the attributes map. A node with no content and no attributes
prints as just `[nN] :tag`.

## Authoring tips

- Call `wireframe show` before any `wireframe set-attr`, `set-text` or
  `delete-node` to obtain the current node ids. Ids are stable once read, so one
  call serves a whole editing session.
- New nodes are appended as the last child of their parent. Use `wireframe
  add-node-before` to insert before an existing sibling instead.
- Deleting `n1` removes the entire wireframe — the root is the tree's top, so the
  screen's layout goes with it. The screen element itself is untouched, and
  `show` then reports that it has no wireframe.
- Attribute values on `add-node` are coerced from CLI strings according to the
  tag's schema: `true`/`false` become booleans, choice values (`primary`,
  `center`) become keywords, a list attribute such as `options` is split on
  commas, and everything else stays a string.
- `--text` is not an attribute: on a text-children tag (`h1`, `h2`, `h3`,
  `text`, `span`) it becomes the new node's content, and on `:alert` it is the
  required `text` attribute. Any other tag rejects it with
  `unknown attribute :text for :<tag>`.
- Errors name the node they concern, e.g. `label is required for :button`,
  `unknown attribute :bogus`, `Field 'x' does not exist on screen`, or
  `node nZZ does not exist`.

## Current limitations

- **`set-attr` can only set string-valued attributes.** The value is passed
  through as text, so an attribute whose declared type is a choice or a flag
  (`variant`, `align`, `tone`, `required`, `command-input`, …) is rejected — e.g.
  `variant must be a keyword` — and `options` is rejected because it needs a
  list. Those attributes can only be given a value when the node is created.
- **Removing a field a layout refers to is not blocked.** The layout keeps its
  `field-name` and afterwards names a field that no longer exists; nothing
  reports the dangling reference.

The last is recorded as an open question in `event-model.allium`.
