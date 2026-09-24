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
tree*: deleting a node neither shifts nor changes any surviving node's id, so an
id read once stays valid across unrelated edits. The single exception is the
highest-numbered node itself — deleting it frees its number, which the next node
added may take (nothing else's id moves). Ids are therefore stable under
unrelated edits, but they are
not the document order: inserting a node before a sibling gives it the next
free id, which may be higher than its following siblings'. `emcli wireframe
show` prints the current ids.

Ids address nodes for editing; they carry no meaning for the layout itself.

## Tags

Tags are grouped by their role:

- **Container** tags hold child nodes.
- **Leaf** tags hold no children.
- **Text-children** tags hold a plain string as their content, given with
  `--text` when the node is added or set later with `emcli wireframe set-text`
  (see [CLI commands](#cli-commands)).

Two attributes are shared across most of the vocabulary:

- `command-input` (bool) — marks the node as a command input on the screen, for a
  frontend to emphasise. Accepted by **every tag except `:canvas` and
  `:divider`**; several nodes on one screen may carry it at once, and it is
  accepted as-is (nothing validates it).
- `field-name` (string) — names the screen field this node displays or accepts,
  and **must reference a field that exists on the screen element's `:fields`
  list**. Accepted by every tag except `:canvas`, `:divider` and the action and
  navigation tags `:button`, `:icon-button` and `:link`.

The tables below give every tag's attributes, by the tag's role. They are
generated from the tag schema in `src/emcli/wireframe.clj` (`bb gen-docs`), so
they are exactly what the CLI accepts; `emcli wireframe tags --tag <name>` prints
the same for one tag, with a command that adds it.

<!-- BEGIN GENERATED: wireframe tag tables (bb gen-docs) -->
### Layout

| tag | purpose | holds | required attributes | optional attributes |
|-----|---------|-------|---------------------|---------------------|
| `canvas` | root of every layout; always n1, created automatically, never added | child nodes |  |  |
| `row` | lays its child nodes out side by side | child nodes |  | `align` (start, center, end, between), `gap` (sm, md, lg), `field-name`, `command-input` (true/false) |
| `col` | stacks its child nodes vertically | child nodes |  | `align` (start, center, end, between), `gap` (sm, md, lg), `width` (narrow, wide, auto, full), `field-name`, `command-input` (true/false) |
| `divider` | horizontal line separating content | nothing |  |  |

### Typography

| tag | purpose | holds | required attributes | optional attributes |
|-----|---------|-------|---------------------|---------------------|
| `h1` | page heading | text |  | `field-name`, `command-input` (true/false) |
| `h2` | section heading | text |  | `field-name`, `command-input` (true/false) |
| `h3` | sub-section heading | text |  | `field-name`, `command-input` (true/false) |
| `text` | paragraph of text, e.g. a displayed value | text |  | `align` (left, center, right), `tone` (default, muted, danger, success), `field-name`, `command-input` (true/false) |
| `span` | short inline text, e.g. a caption or a value | text |  | `tone` (default, muted, danger, success), `field-name`, `command-input` (true/false) |

### Input

| tag | purpose | holds | required attributes | optional attributes |
|-----|---------|-------|---------------------|---------------------|
| `input` | single-line entry field | nothing |  | `type` (text, email, password, number, tel, url), `label`, `placeholder`, `required` (true/false), `field-name`, `command-input` (true/false) |
| `textarea` | multi-line entry field | nothing |  | `label`, `placeholder`, `required` (true/false), `field-name`, `command-input` (true/false) |
| `dropdown` | pick one of a fixed set of options | nothing | `options` (comma-separated list) | `label`, `required` (true/false), `field-name`, `command-input` (true/false) |
| `checkbox` | tick box for a yes/no value | nothing |  | `label`, `default` (true/false), `field-name`, `command-input` (true/false) |
| `toggle` | on/off switch for a yes/no value | nothing |  | `label`, `default` (true/false), `field-name`, `command-input` (true/false) |

### Action

| tag | purpose | holds | required attributes | optional attributes |
|-----|---------|-------|---------------------|---------------------|
| `button` | labelled action, e.g. one that triggers a command | nothing | `label` | `variant` (primary, secondary, ghost, danger), `disabled` (true/false), `command-input` (true/false) |
| `icon-button` | action shown as an icon only | nothing | `icon`, `aria-label` | `command-input` (true/false) |

### Content

| tag | purpose | holds | required attributes | optional attributes |
|-----|---------|-------|---------------------|---------------------|
| `link` | navigation to another screen or page | nothing | `label` | `command-input` (true/false) |
| `image` | picture or media placeholder | nothing | `alt` | `aspect` (square, wide, tall), `field-name`, `command-input` (true/false) |
| `icon` | small symbol, e.g. a status indicator | nothing | `name` | `size` (sm, md, lg), `field-name`, `command-input` (true/false) |
| `alert` | message banner: info, warning, danger or success | nothing | `text` | `type` (info, warning, danger, success), `field-name`, `command-input` (true/false) |
<!-- END GENERATED -->

An input's `field-name` is what ties the layout to the model: it must name one of
the screen's own fields, so a screen can only lay out data it declares.

Note that `:alert` takes its message as the `text` *attribute*, whereas the
text-children tags take it as a string child — passed as `--text` when the node
is created, or set afterwards with `set-text`. No other tag accepts `text`.

## CLI commands

```
# List the verbs, or get per-verb options
emcli wireframe
emcli wireframe add-node help

# List the tags, or get one tag's attributes and an example command
emcli wireframe tags
emcli wireframe tags --tag button

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

# Move an existing node (with its subtree): before a sibling, or to the end of a container
emcli wireframe move-node --element 42 --node n5 --before n3
emcli wireframe move-node --element 42 --node n5 --parent n2

# State the whole layout at once, in the format `show` prints: [nX] keeps an
# existing node, a line without it is a new node, nodes left out are deleted
emcli wireframe apply --element 42 --tree '
[n1] :canvas
  [n2] :col
    [n3] :h1  "Your orders"
    :input  {:type :email, :label "Email"}
    [n5] :button  {:label "Create order", :variant :primary}'

# Show the current wireframe (annotated text tree)
emcli wireframe show --element 42

# Patch one attribute on an existing node
emcli wireframe set-attr --element 42 --node n5 --attr label --value "New order"

# Delete a node (removes its entire subtree; other node ids are unaffected)
emcli wireframe delete-node --element 42 --node n3
```

`--element` must always name an element that exists. `add-node`,
`add-node-before` and `apply` additionally require it to be a **screen**, rejecting
any other kind with `element N is not a screen`; the verbs that edit existing
nodes (`set-attr`, `set-text`, `delete-node`, `move-node`) need a layout to edit
instead, and answer
`element N has no wireframe` when the element carries none — which a non-screen
never does, since only screens may carry a layout.

## Output format

`wireframe show` renders the tree as indented text:

```
[n1] :canvas
  [n2] :col
    [n3] :h1  "Your orders"
    [n4] :input  {:placeholder "Search...", :field-name "searchTerm"}
    [n5] :button  {:label "Create order", :variant :primary}
```

Each line shows the stable node id, the tag, then the attributes map and the
string content (a text node with attributes shows both, map first). A node with
no content and no attributes prints as just `[nN] :tag`.

The same format is the input of `wireframe apply`, so a layout can be read with
`show`, edited as text and handed back; handing it back unchanged changes
nothing. On input, attribute values may be written as `show` prints them
(`"primary"`) or as keywords (`:primary`) — either is typed by the tag's
schema. Indentation is two spaces per level; blank lines and a common leading
indentation are ignored.

Every successful edit prints what it did and the resulting tree, so its outcome
can be checked without another `show`:

```
moved n4 :input
[n1] :canvas
  [n4] :input  {:type "email", :label "Email"}
  [n2] :input  {:type "password", :label "Password"}
  [n3] :button  {:label "Submit"}
```

The first line is `added nX :tag`, `moved nX :tag`, `updated nX :tag`,
`deleted nX` or `applied`. Pass `--json` to get the edited screen element as
JSON instead, as scripts did before.

## Authoring tips

- Call `wireframe show` before any `wireframe set-attr`, `set-text` or
  `delete-node` to obtain the current node ids. Ids are stable once read, so one
  call serves a whole editing session.
- New nodes are appended as the last child of their parent. Use `wireframe
  add-node-before` to insert before an existing sibling instead.
- To reorder, use `move-node` rather than deleting and re-adding: a move keeps
  every node id. `--before` places the node before a sibling (under that
  sibling's parent); `--parent` appends it to a container (`canvas`, `row`,
  `col`). The root cannot move, nothing goes before the root, and a node cannot
  move into its own subtree.
- For several changes at once - a new form, a reorder, wrapping nodes in a
  `col` - prefer one `apply` over a sequence of edits. It is all-or-nothing:
  if any line is wrong nothing changes, and every problem is reported (with its
  line number when the text itself does not read). New nodes get ids above the
  highest id the layout had, in document order.
- A rejection over a tag's attributes (one the tag does not admit, a required
  one left out, a value outside the allowed set) lists what the tag admits and,
  for `add-node` / `add-node-before`, a corrected command to run:
  ```
  ✗ wireframe add-node: unknown attribute :text for :button (see: emcli wireframe tags --tag button)
  :button admits:
    --label (required) text
    --variant primary|secondary|ghost|danger
    --disabled true|false
    --command-input true|false
  try: emcli wireframe add-node --element 31 --tag button --label "Submit"
  ```
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
  `unknown attribute :text for :<tag> (see: emcli wireframe tags --tag <tag>)`.
- Errors name the node they concern, e.g. `label is required for :button`,
  `Field 'x' does not exist on screen`, or `node nZZ does not exist`. An unknown
  tag or attribute points at `emcli wireframe tags`, which lists the vocabulary:
  `unknown attribute :bogus for :button (see: emcli wireframe tags --tag button)`.

## Current limitations

- **`set-attr` coerces the value through the node's tag schema, and refuses text
  it cannot coerce.** The value still arrives as text: `true`/`false` become
  booleans (a flag such as `command-input` can be set), a choice value (`variant`,
  `align`, `tone`, …) becomes a keyword, and a list attribute such as `options` is
  split on commas. Text outside the set the tag allows for the attribute is
  rejected — e.g. `variant value 'bogus' not in allowed set …` — and the node is
  left unchanged. Text content is still not an attribute: on a text-children tag
  it is set with `set-text` and `set-attr --attr text` is refused, while `:alert`
  takes its message as the real `text` attribute and refuses `set-text` in turn.
- **Removing a field a layout refers to is refused, not repaired.** `remove-field`
  (and any field edit that drops a name) is rejected when the screen's stored
  layout still names that field, with an error naming the field and the node ids
  that refer to it, so a field edit cannot strand a reference. A model that
  already holds a stranded reference — written before the guard existed — is not
  left editable: `WireframeReferencesResolve` is always on, so the store fails
  validation at load (`invalid store in <file>: N invariant violation(s) remain
  after repair`) and is refused outright. Repair it in the file itself, by editing
  the referring node's `field-name` or deleting that node.
