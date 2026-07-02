---
description: Import an event model from an eventmodelers.com-style backup/export JSON file into emcli via bb run commands, deriving as much as possible from the file so the human answers as few questions as possible. Use when the user provides a backup/export file from another Event Modeling tool (e.g. eventmodelers_out.json) and wants it recreated in emcli.
allowed-tools: Bash(bb *), Bash(python3 *), Read
---

## Command manifest

!`bb run --manifest 2>/dev/null || echo '{"error":"bb not found — run from the project root"}'`

## Current model

!`bb run show 2>/dev/null || echo '{"status":"no-server"}'`

---

You are importing a **concrete backup format** (the one produced by the `eventmodelers_out.json`-style export). This skill targets that exact JSON shape — do not build generic multi-format detection. If you're ever handed a file that doesn't match the shape below, say so and stop rather than guessing.

Goal: read the file, compute the full `bb run` command sequence, run it, and only ask the human when something is genuinely unmappable (never for things derivable from the file).

## Source file shape

Top level: `{"boards": {...}, "nodes": {...}, "metadata": {...}, "images": {...}, "nodeSnapshots": {...}}`, all keyed by the same `boardId` UUID(s). Iterate **every** boardId present — don't assume a single board.

- `boards[boardId].name` — human board name, e.g. `"Test Board"`. Use as the default `bb run serve --name` if no server is running yet.
- `metadata[boardId][nodeId].meta` — **authoritative domain data**. `meta.type` (uppercase) is the real semantic type: `CHAPTER`, `SCREEN`, `COMMAND`, `EVENT`, `READMODEL`, `AUTOMATION`, `SCENARIO`.
- `nodes[boardId].nodes[]` — canvas/visual nodes only (`type` here is lowercase and is a *rendering* category, e.g. `slice_border`, `timeline`, `image`, `sticky_note` — **never** use this `type` for kind inference). The one piece of domain data that lives *only* here is `slice_border` nodes: `data.colId` + `data.title` give a slice's title.

Load the file with a `python3 -c` one-liner (Read is fine too for small files, but the JSON is easiest to walk in Python). Do the parsing/derivation in Python, then emit `bb run` invocations.

## Concept mapping

| Source (`metadata[...].meta`) | emcli entity |
|---|---|
| `CHAPTER` | `timeline` (one per CHAPTER node) |
| `timelineData.columns[]` (in order) | `slice` per column, `--index` = array position |
| canvas `slice_border` node with matching `data.colId` | slice's `--title` (fallback `"Slice N"` if none found) |
| `COMMAND` | `element --kind command` |
| `EVENT` | `element --kind event` |
| `READMODEL` | `element --kind read_model` |
| `SCREEN` | `element --kind screen` |
| `AUTOMATION` | `element --kind automation` |
| `timelineData.rows[]` where `type=="swimlane"` **and** `label` != default `"Swimlane"` | `swimlane` (see below) |
| `SCENARIO` under a `spec`-row cell | `spec` + `step`s (see below) |

Element `--name` = the source `title` verbatim. Don't rename to match the sibling `event-model` skill's PascalCase convention — preserve source naming for traceability. If the user asks for a rename pass afterward, that's a separate step.

## Algorithm

For each `boardId`:

1. **Timelines.** For each metadata node with `meta.type == "CHAPTER"`: `timeline add --title <chapter title, or board name if chapter has none>`.

2. **Slices.** Read `meta.timelineData`. `columns` is already in left-to-right order — column array index **is** the slice index, no extra linkage needed.
   For each column `i` with id `colId`:
   - Find the canvas node in `nodes[boardId].nodes` with `type == "slice_border"` and `data.colId == colId`; its `data.title` is the slice title.
   - Collect every cell in `timelineData.cells` where `cell.colId == colId`. Each cell's `nodeId` + its `metadata[...].meta.type` gives the elements placed in this slice (ignore the cell's `rowId`/row semantics for this step — elements can land in any row).
   - Infer `--kind`, checking in this order (an automation slice also carries a command/event, so automation must be checked first):
     1. an `AUTOMATION` present → `automation`
     2. a `COMMAND` and an `EVENT` both present → `state_change`
     3. a `READMODEL` present (no `COMMAND`) → `state_view`
     4. none of the above (e.g. only a `SCREEN`, or nothing) → create with `--kind state_view` as a placeholder, then immediately `slice status --slice <id> --new-status informational` (the CLI's `--kind` enum has no `informational` value — informational-only slices are expressed via status instead).
   - `slice add --timeline <id> --title <title> --kind <kind> --index <i>`.

3. **Elements.** Keep a `sourceNodeId -> emcli element id` map so an element referenced from multiple slices/steps is only created once.
   For each distinct element node (`COMMAND`/`EVENT`/`READMODEL`/`SCREEN`/`AUTOMATION`) encountered while walking cells:
   - `element add --name <title> --kind <mapped kind>`
   - If `meta.fields` is non-empty: `element fields --element <id> --fields-json '<fields>'` where each field is `{"name": f.name, "type": f.type.lower()}`. If `f.cardinality == "Many"`, use `"<type>[]"` for `type` (verified: server accepts lowercase primitive types and the `"type[]"` suffix for arrays).
   - Field origin: default origin (`user_input`) needs no explicit call — fields with no origin entry are treated as `user_input`. For fields that deviate, batch them into one `element origins --element <id> --origins-json '[{"field": name, "origin": value}, ...]'` call (verified shape) rather than one `element origin` call per field:
     - `f.generated == true` → `"origin": "generated"`
     - `f.technicalAttribute == true` → `"origin": "external"`
   - Known gaps with no current emcli target — don't try to force a mapping, just drop them: `optional`, `showAttributes`, `query`, `edited`, `idAttribute`, `subfields`.
   - `placement add --slice <sliceId> --element <elementId>` for every element found in that slice's cells (dedup: an element shouldn't be placed twice in the same slice even if it appears in multiple cells of that column).

4. **Swimlanes.** For each `CHAPTER`, look at `timelineData.rows[]` where `type == "swimlane"`. Only act if `label != "Swimlane"` (the default) — a customized label means the source user actually named an actor/system there.
   - `swimlane add --name <label> --index <position among swimlane-type rows>`
   - For elements whose cell has `rowId` equal to that row's id: `element swimlane --element <id> --lane <laneId>`.
   - If the label is still the default, skip entirely — don't invent a swimlane for a purely structural row.

5. **Specs.** For cells where the resolved element is a `SCENARIO` (i.e. the `spec`-row cell in a column): look up `meta.givenWhenThenScenario.scenarios[]`. For each scenario:
   - `spec add --slice <sliceId> --title <scenario title>`
   - `stepIndex = 0`; for `clause in [("given_step", scenario.given), ("when_step", scenario.when), ("then_step", scenario.then)]`, for each step entry in that array (each references an element by `id`/`type`/`title` plus `fields[]` with `name`+`example`):
     - Resolve the element id from the `sourceNodeId -> emcli element id` map (it should already exist from step 3).
     - `step add --spec <specId> --clause <clause> --element <elementId> --index <stepIndex>`; increment `stepIndex`.
     - If the step has `fields`, attach them: `step examples --step <stepId> --examples-json '[{"field_name": f.name, "field_value": f.example}, ...]'`. Use exactly these two keys — `field_name`/`field_value` is the canonical shape (see `docs/change-stream.schema.json`); `set-step-examples` stores whatever keys it's given with no validation, so any other key name (e.g. `field`/`value`) silently produces empty `{}` objects on every read path.
   - If `scenario.expectError`: the source format has no observed field carrying the error name. Ask the human once for the error name (scenario title for context), then `step error --spec <specId> --error-name <name> --index <nextIndex>`.
   - If `scenario.expectEmptyList`: `step expect-empty --step <thenStepId> --value true` on the relevant `then` step (typically the read-model step).

6. **Connections.** This backup format has no observed structure encoding element-to-element data derivations (no `connections`/`derivations` key anywhere in the file). Skip `connection add`/`connection derive` entirely — don't fabricate connections that aren't in the source.

## Server setup

If `bb run show` above reports `"no-server"`, start one before running any commands:

```
bb run serve --name "<boards[boardId].name>" --file model.edn
```

Ask the human to run it in a separate terminal, then confirm with `bb run show`.

## Working rhythm

1. Parse the whole file in Python first; build the full command plan before executing anything.
2. Batch independent `bb run` calls together.
3. Track every created id (`timeline`, `slice`, `element`, `swimlane`, `spec`, `step`) in your id map — later steps need them.
4. After the full import, run `bb run validate` and report what's incomplete in plain language.
5. Only pause to ask the human for input on genuinely unmappable data (e.g. a missing error name for `expectError`) — never for anything derivable from the file.
