---
name: emcli-authoring
description: >
  Use this skill when you need to edit an Event Model. Covers the complete command reference for authoring
  an Event Model via the tools (emcli_resolve, emcli_validate, and the per-group authoring tools) with gotchas.
---

# emcli Authoring Reference

## Prerequisites

`emcli serve` must be running.

## Tool workflow

1. **Resolve names to ids** with `emcli_resolve` before any authoring call that needs an integer id. Never guess ids.
2. **Author** with the matching per-group tool — one call per command. Choose the tool whose name matches the entity you want to mutate, supply `verb` + any required `args` as space-separated `--flag value` pairs.

## emcli_resolve

```
queries: "Name[:kind_hint],..."
```

`kind_hint` only ranks candidates, never filters them. Values: `timeline`, `swimlane`, `slice`, `element`, `specification`.

Returns an array of matches with `id`, `kind`, `swimlane`, and `name`.

Empty name with kind hint finds all entities of that kind. Example for finding all timelines: `queries: ":timeline"`

## emcli_validate

Runs Event Model validation and returns all warnings and errors. Use when the user requests a model check.

## Per-group authoring tools

### emcli_slice

Slice `--kind` values: `state_change`, `state_view`, `automation`

Slice `--new-status` values: `created`, `in_progress`, `done`, `informational`

### emcli_element

Element `--kind` values: `command`, `event`, `read_model`, `screen`, `automation`

Field `--type` values: `string`, `boolean`, `double`, `decimal`, `long`, `custom`, `date`, `date_time`, `uuid`, `int`

Field `--cardinality` values: `single`, `list`

Field `--origin` values: `user_input`, `generated`, `external`

### emcli_connection

**Derivation rule:** Only add a derivation when it carries information — a field **renamed** across the boundary (e.g. `recipientEmail` → `email`), or multiple source fields collapsed into one target (`firstName,lastName` → `displayName`). Never add a derivation where `--from` and `--target` share the same name — same-name derivations corrupt the information completeness check.

### emcli_step

Step `--clause` values: `given_step`, `when_step`, `then_step`

### emcli_wireframe

Wireframe operations are limited to `screen` elements. Read `references/wireframe-operations.md` for the tag attribute flags reference.

## Notes

- All integer id arguments come from `emcli_resolve` (for existing entities) or from the result of a prior authoring call. Every authoring command returns the created/modified entity as JSON, so you can read the `id` directly from that result — no need to call `emcli_resolve` immediately after creating something.
- The optional `--id` arg on create commands lets you pre-assign a stable id; it must not already be in use.
- `--index` in slice/placement/swimlane is zero-based and denotes position within the parent's ordered list.
- A per-group tool result on success is the created/modified entity as JSON. On error it returns an `Error:` prefixed string with the server's message.
- Element and timeline names may contain spaces (e.g. `"User created"`, `"Order Flow"`).

## Gotchas

- Args that refer to other parts of the model by an ID must not be renamed, so do not append `_id` to the flag name when making a tool call.
- The tool's error message will always tell you exactly the flag names to use; do not modify them.
