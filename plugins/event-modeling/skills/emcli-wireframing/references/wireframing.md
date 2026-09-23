# screen element — wireframe authoring

## Nodes and their attributes

`emcli wireframe tags` lists the tags; `emcli wireframe tags --tag <name>` prints one tag's attributes and a command that adds it.

A node holds child nodes (add them with `--parent <node id>`), text (given with `--text`) or nothing. `field-name` must name a field of the screen; `command-input` marks a node as input to a command. A list attribute such as `options` is given as comma-separated text.

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

## Gotchas

- `field-name` values must reference a field that exists on the screen element's `:fields` list
- Deleting a node removes its entire subtree. Deleting `n1` removes the entire wireframe.
