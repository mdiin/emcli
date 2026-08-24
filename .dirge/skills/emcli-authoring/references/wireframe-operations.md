# screen element — wireframe authoring

- Wireframes are built incrementally on `screen` elements
- Use `emcli_wireframe` (verb: `show`, e.g. `emcli wireframe show --element <id>`) to show the node's wireframe tree with stable IDs (e.g. `"n1"`, `"n2"`, ...)

## Nodes and their attributes

All nodes except `:canvas` and `:divider` accept `field-name` (string) and `command-input` (bool) in addition to their own attrs listed below.

### Layout

| node      | required flags | optional flags                                     | accepts children |
|-----------|----------------|----------------------------------------------------|------------------|
| `canvas`  |                |                                                    | true             |
| `row`     |                | `align` (string), `gap` (string)                   | true             |
| `col`     |                | `align` (string), `gap` (string), `width` (string) | true             |
| `divider` |                |                                                    | false            |

Attribute `align` values: `start`, `center`, `end`, `between`

Attribute `gap` values: `sm`, `md`, `lg`

Attribute `width` values: `narrow`, `wide`, `auto`, `full`

### Typography
Set text content via `set-text` after creation.

| node      | required flags | optional flags                    | accepts children |
|-----------|----------------|-----------------------------------|------------------|
| `h1`      |                |                                   | false            |
| `h2`      |                |                                   | false            |
| `h3`      |                |                                   | false            |
| `text`    |                | `align` (string), `tone` (string) | false            |
| `span`    |                | `tone` (string)                   | false            |

Attribute `align` values: `left`, `center`, `right`

Attribute `tone` values: `default`, `muted`, `danger`, `success`

### Inputs

| node      | required flags     | optional flags                                                                                                              | accepts children |
|-----------|--------------------|-----------------------------------------------------------------------------------------------------------------------------|------------------|
| `input`   |                    | `type` (string), `label` (string), `placeholder` (string), `required` (bool), `field-name` (string), `command-input` (bool) | false            |
| `textarea`|                    | `label` (string), `placeholder` (string), `required` (bool), `field-name` (string), `command-input` (bool)                  | false            |
| `dropdown`| `options` (string) | `label` (string), `required` (bool), `field-name` (string), `command-input` (bool)                                          | false            |
| `checkbox`|                    | `label` (string), `default` (bool), `field-name` (string), `command-input` (bool)                                           | false            |
| `toggle`  |                    | `label` (string), `default` (bool), `field-name` (string), `command-input` (bool)                                           | false            |

Attribute `type` values: `text`, `email`, `password`, `number`, `tel`, `url`

Attribute `options` value: comma-separated string of options

### Actions

| node         | required flags                         | optional flags                                                | accepts children |
|--------------|----------------------------------------|---------------------------------------------------------------|------------------|
| `button`     | `label` (string)                       | `variant` (string), `disabled` (bool), `command-input` (bool) | false            |
| `icon-button`| `icon` (string), `aria-label` (string) | `command-input` (bool)                                        | false            |

Attribute `variant` values: `primary`, `secondary`, `ghost`, `danger`

### Content/navigation

| node         | required flags   | optional flags                                                   | accepts children |
|--------------|------------------|------------------------------------------------------------------|------------------|
| `link`       | `label` (string) | `command-input` (bool)                                           | false            |
| `image`      | `alt` (string)   | `aspect` (string), `field-name` (string), `command-input` (bool) | false            |
| `icon`       | `name` (string)  | `size` (string), `field-name` (string), `command-input` (bool)   | false            |
| `alert`      | `text` (string)  | `type` (string), `field-name` (string), `command-input` (bool)   | false            |

Attribute `aspect` values: `square`, `wide`, `tall`

Attribute `size` values: `sm`, `md`, `lg`

Attribute `type` values: `info`, `warning`, `danger`, `success`

## Gotchas

- `field-name` values must reference a field that exists on the screen element's `:fields` list
- Deleting a node removes its entire subtree. Deleting `n1` removes the entire wireframe.
- `:-id` keys are internal — they appear in the stored EDN but are stripped before validation and rendering.
