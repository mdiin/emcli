---
name: emcli-authoring
description: >
  Use this skill when you need to edit an Event Model. Covers the complete command reference for authoring
  an Event Model via the per-group authoring tools with gotchas.
---

# emcli Authoring Reference

## Prerequisites

`emcli serve` must be running.

## Tool workflow

1. **Resolve names to ids** or **query for what you need** before any authoring call that needs integer ids. Never guess ids.
2. **Author** with the matching per-group tool — one call per command. Choose the tool whose name matches the entity you want to mutate, supply `verb` + any required `args` as space-separated `--flag value` pairs.



## Per-group authoring tools

### emcli_slice

Slice `--slice-type` values: `state_change`, `state_view`, `automation`

Slice `--new-status` values: `created`, `in_progress`, `done`, `informational`

### emcli_element

Element `--element-type` values: `command`, `event`, `read_model`, `screen`, `automation`

Field `--type` values: `string`, `boolean`, `double`, `decimal`, `long`, `custom`, `date`, `date_time`, `uuid`, `int`

Field `--cardinality` values: `single`, `list`

Field `--origin` values: `user_input`, `generated`, `external`


### emcli_connection

**Derivation rule:** Only add a derivation when it carries information — a field **renamed** across the boundary (e.g. `recipientEmail` → `email`), or multiple source fields collapsed into one target (`firstName,lastName` → `displayName`). Never add a derivation where `--from` and `--target` share the same name — same-name derivations corrupt the information completeness check.

### emcli_step

Step `--clause` values: `given_step`, `when_step`, `then_step`

## Swimlanes vs. Slices

Elements can be "placement" associated with two different kinds of entities, which require different operations:

- Add element to **swimlane**: Use `emcli_element` with the `swimlane` verb
- Place element in **slice**: Use `emcli_placement`

This distinction is important! USER may use the word "place" for both; disambiguate by looking at "where" it should be placed.


## Notes

- All integer id arguments come from the task you were given, from an `em-explorer`, or from the result of a prior authoring call. Every authoring command returns the created/modified entity as JSON, so you can read the `id` directly from that result.
- The optional `--id` arg on create commands lets you pre-assign a stable id; it must not already be in use.
- `--index` in slice/swimlane is zero-based and denotes position within the parent's ordered list. Placement order is set via `emcli_placement` `reorder` (`--position front|back`, or `--before`/`--after <element>`).
- A per-group tool result on success is the created/modified entity as JSON. On error it returns an `Error:` prefixed string with the server's message.
- Element and timeline names may contain spaces (e.g. `"User created"`, `"Order Flow"`).


## Gotchas

- Args that refer to other parts of the model by an ID must not be renamed, so do not append `_id` to the flag name when making a tool call.
- The tool's error message will always tell you exactly the flag names to use; do not modify them.
