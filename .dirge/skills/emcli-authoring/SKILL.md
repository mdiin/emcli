---
description: >
  Use this skill when you need to edit an Event Model. Covers the complete command reference for authoring
  an Event Model via the plugin tools (emcli_resolve, emcli_validate, emcli_author) with gotchas.
---

# emcli Authoring Reference

## Prerequisites

`emcli serve` must be running.

## Tool workflow

1. **Resolve names to ids** with `emcli_resolve` before any `emcli_author` call that needs an integer id. Never guess ids.
2. **Author** with `emcli_author` — one call per command.
3. **Validate** with `emcli_validate` after a sequence of mutations to catch inconsistencies.

## emcli_resolve

```
queries: "Name[:kind_hint],..."
```

`kind_hint` filters candidates: `command`, `event`, `read_model`, `screen`, `automation`, `timeline`, `swimlane`.

Returns an array of matches with `id`, `kind`, `swimlane`, and `name`.

Empty name with kind hint finds all elements of kind. Example for finding all timelines: `queries: ":timeline"`

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

| verb                      | required args                                                       | optional args                                            |
|---------------------------|---------------------------------------------------------------------|----------------------------------------------------------|
| add                       | `name` (string), `kind` (keyword)                                   | `id` (int)                                               |
| rename                    | `element` (int), `new-name` (string)                                |                                                          |
| delete                    | `element` (int)                                                     |                                                          |
| context                   | `element` (int), `new-context` (keyword)                            |                                                          |
| swimlane                  | `element` (int), `lane` (int)                                       |                                                          |
| image                     | `element` (int), `url` (string)                                     |                                                          |
| add-field                 | `element` (int), `name` (string), `type` (keyword)                  | `cardinality` (keyword)                                  |
| remove-field              | `element` (int), `name` (string)                                    |                                                          |
| add-origin                | `element` (int), `field` (string), `origin` (keyword)               |                                                          |
| remove-origin             | `element` (int), `field` (string)                                   |                                                          |
| add-wireframe-node        | `element` (int), `tag` (string)                                     | `parent` (string, default `"n1"`), + tag attribute flags |
| delete-wireframe-node     | `element` (int), `node` (string)                                    |                                                          |
| add-wireframe-node-before | `element` (int), `tag` (string), `before` (string)                  | + tag attribute flags                                    |
| set-wireframe-attr        | `element` (int), `node` (string), `attr` (string), `value` (string) |                                                          |
| set-wireframe-text        | `element` (int), `node` (string), `text` (string)                   |                                                          |

Element `kind` values: `command`, `event`, `read_model`, `screen`, `automation`

Field `type` values: `string`, `boolean`, `double`, `decimal`, `long`, `custom`, `date`, `date_time`, `uuid`, `int`

Field `cardinality` values: `single`, `list`

Field `origin` values: `user_input`, `generated`, `external`

Wireframe operations are limited to `screen` elements. Read `references/wireframe-operations.md` for the tag attribute flags reference.

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

**Derivation rule:** Only add a derivation when it carries information — a field **renamed** across the boundary (e.g. `recipientEmail` → `email`), or multiple source fields collapsed into one target (`firstName,lastName` → `displayName`). Never add a derivation where `from` and `target` share the same name — same-name derivations corrupt the information completeness check.
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

## Notes

- All integer id arguments come from `emcli_resolve` (for existing entities) or from the result of a prior `emcli_author` call. Every authoring command returns the created/modified entity as JSON, so you can read the `id` directly from that result — no need to call `emcli_resolve` immediately after creating something.
- The optional `id` arg on create commands lets you pre-assign a stable id; it must not already be in use.
- `index` in slice/placement/swimlane is zero-based and denotes position within the parent's ordered list.
- `emcli_author` result on success is the created/modified entity as JSON. On error it returns an `Error:` prefixed string with the server's message.
- Element and timeline names may contain spaces (e.g. `"User created"`, `"Order Flow"`).

## Gotchas

- Args that refer to other parts of the model by an ID must not be renamed, so do not append `_id` to to the arg when making the tool call
- The `emcli_author` tool's error message will always tell you exactly the argument names to use; do not modify them

## Verification

```
emcli validate
```
