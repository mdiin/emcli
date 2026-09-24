---
name: emcli-wireframing
description: >
  Use this skill when you need to edit wireframes on Event Model screen elements. Covers the complete command
  reference for wireframing with guidelines and gotchas.
---

# emcli Wireframing Reference

Wireframes are layout trees on `screen` elements. Every node has a stable id (`n1`, `n2`, ...); `n1` is always the `:canvas` root.

## Working rhythm

1. **Read the layout** with the `show` verb. It prints one line per node: `[nX] :tag {attributes} "text"`, indented two spaces per level. Never guess node ids.
2. **Discover tags** with the `tags` verb: without args it lists every tag; with `--tag <name>` it gives that tag's attributes and an example command. Never guess tags or attributes.
3. **Make the change**, one call at a time, never in parallel:
   - several nodes at once (a new form, a reorder, a restructure): **one `apply`** with the whole target layout;
   - move one node: `move-node`;
   - one small edit: `add-node`, `add-node-before`, `set-attr`, `set-text`, `delete-node`.
4. **Check the printed tree.** Every successful call prints what it did (`added n4 :input`, `moved n4 :input`, `updated n3 :button`, `deleted n3`, `applied`) followed by the resulting layout. Compare it with the task.

## Verbs

| verb | args | does |
|---|---|---|
| `show` | `--element N` | print the layout |
| `tags` | `[--tag T]` | list tags, or one tag's attributes |
| `apply` | `--element N --tree '<layout>'` | replace the whole layout (see below) |
| `move-node` | `--element N --node nX (--before nY \| --parent nZ)` | move a node and its subtree; every id is kept |
| `add-node` | `--element N --tag T [--parent nX] [--text "..."] [--<attr> value ...]` | append a node (to the root when no `--parent`) |
| `add-node-before` | `--element N --before nX --tag T [--text "..."] [--<attr> value ...]` | insert a node before a sibling |
| `set-attr` | `--element N --node nX --attr A --value V` | change one attribute |
| `set-text` | `--element N --node nX --text "..."` | change a text node's text |
| `delete-node` | `--element N --node nX` | delete a node and its subtree |

## apply

`--tree` is the whole target layout in the format `show` prints. Wrap it in single quotes.

- A line with `[nX]` keeps that existing node (and its id).
- A line without `[nX]` is a new node; it gets a fresh id.
- Existing nodes you leave out are deleted.
- The first line is always the root. Write it as `:canvas`: it always means the layout's root. (`[n1] :canvas` also works, but not on a screen with no layout yet.)
- Two spaces of indentation per level; a node's children are indented one level below it.
- Attributes are an EDN map after the tag, e.g. `{:type :email, :label "Email"}`; a text node's text is a string after it, e.g. `:h1  "Log in"`.

Example - wrap the three nodes in a column and make the button primary:
```
--element 31 --tree '[n1] :canvas
  :col
    [n4] :input  {:type "email", :label "Email"}
    [n2] :input  {:type "password", :label "Password"}
    [n3] :button  {:label "Submit", :variant :primary}'
```

`apply` is all-or-nothing: if anything is wrong nothing changes, and the error lists every problem.

## move-node

- `--before nY`: place the node immediately before `nY` (under `nY`'s parent).
- `--parent nZ`: append the node as the last child of the container `nZ` (`canvas`, `row` or `col`).
- Give exactly one of the two. The root cannot be moved, and nothing can be placed before it.

## Errors

- A rejected call starts with `✗`. When the problem is an attribute, the error lists what the tag admits (`--label (required) text`, `--variant primary|secondary|ghost|danger`, ...) and, for `add-node`, a `try:` line with the corrected command. Use it.
- `--text` is only for text tags (`h1`, `h2`, `h3`, `text`, `span`) and for `alert`. A button's or link's text is its `--label`; an input's is its `--label` or `--placeholder`.
- An error ending in `(see: emcli wireframe tags ...)` means the tag or attribute does not exist: run the `tags` verb with the args it names, then retry.
- The full tag reference is `${plugin:root}/skills/emcli-wireframing/references/wireframing.md`.
