# emcli change-stream (SSE) — consumer guide

The `emcli serve` process exposes a read-only **Server-Sent Events** stream that
a frontend consumes to visualise an Event Model as it is edited. This document
describes the exact wire format so a separate program can consume it. The
machine-readable schema for the event payloads is
[`change-stream.schema.json`](./change-stream.schema.json).

## Endpoints

| Method | Path        | Purpose |
|--------|-------------|---------|
| `GET`  | `/stream`   | The SSE change stream: one snapshot, then a delta per mutation. |
| `GET`  | `/snapshot` | A one-shot **SnapshotEvent** as a plain JSON body (no SSE framing). |
| `GET`  | `/model`    | A *different*, richer authoring projection (slice/spec `is_complete`, spec steps, element list). NOT part of the stream — see note below. |
| `GET`  | `/health`   | Liveness check (`{"ok":true}`); carries no model data. |

The stream is **outbound only**: the consumer receives changes and can only
disconnect. All editing happens through `POST /authoring/<command>` on the
separate ModelAuthoring boundary, which shares the same process and the same
in-memory model; `GET /validate`, `POST /resolve`, `GET /export` and
`POST /import` round out that boundary. None of them appear on the stream except
indirectly: an import re-snapshots every connected client rather than sending a
delta.

## Wire framing

`GET /stream` responds with `Content-Type: text/event-stream`. Each event is:

```
event: <name>
data: <one line of JSON>
<blank line>
```

- The `event:` name equals the payload's `op` field.
- The **first** event is always `event: snapshot`: a stream opens with exactly
  one. A further snapshot is sent only when the model is replaced — an import
  re-snapshots every connected client instead of sending a delta (see
  `Endpoints`) — so over a client's lifetime there is one snapshot per
  (re)connection and one per import.
- Between snapshots every other event is a delta; its `event:` name is the
  authoring operation (e.g. `CreateTimeline`). There is no gap: no mutation
  committed after a snapshot is taken is lost or duplicated.

## 1. Snapshot event (first message)

A full snapshot of the canonical model. **Every entity carries its integer
`id`** — the same surrogate identity the deltas use — so you can seed a
normalised store from the snapshot and patch it by id from later deltas.
Denormalised display fields (element / connection names) are nested under their
sub-entity, which also carries its id. It contains timelines and swimlanes
(which the `eventmodeling.schema.json` interchange format does not) because the
stream is for visualisation.

```
event: snapshot
data: {"op":"snapshot","model":{"id":1,"name":"Orders","timelines":[],"swimlanes":[],"connections":[]}}
```

Populated:

```json
{
  "op": "snapshot",
  "model": {
    "id": 1,
    "name": "Orders",
    "timelines": [
      { "id": 5, "title": "Order flow",
        "slices": [
          { "id": 6, "title": "Place order", "kind": "state_change", "status": "created", "index": 0,
            "is_complete": true,
            "placements": [
              { "id": 7, "index": 0,
                "element": { "id": 2, "name": "PlaceOrder", "kind": "command",
                             "swimlane": null, "is_information_complete": true,
                             "image_url": null, "wireframe": null,
                             "fields": [ { "name": "id", "type": "uuid",
                                           "optional": false, "cardinality": "single" } ] } }
            ],
            "specifications": [
              { "id": 9, "title": "Happy path", "is_complete": true,
                "steps": [
                  { "id": 10, "clause": "when_step", "index": 0, "is_error": false, "error_name": null,
                    "element": { "id": 2, "name": "PlaceOrder" },
                    "examples": [ { "field_name": "id", "field_value": "1" } ] }
                ] }
            ] }
        ] }
    ],
    "swimlanes": [ { "id": 4, "name": "Orders", "index": 0 } ],
    "connections": [
      { "id": 8,
        "from": { "id": 2, "name": "PlaceOrder" },
        "to":   { "id": 3, "name": "OrderPlaced" },
        "derivations": [ { "target_field": "total", "source_fields": ["amount"] } ] }
    ]
  }
}
```

Each placed element carries `swimlane` (integer id or null — null when the
element is unassigned, including after the swimlane it pointed at is deleted),
`is_information_complete` (true iff every field on it is sourced — carried,
derived, or introduced), `image_url` (string or null — set via `SetImageUrl` on
screen elements), and `wireframe` (null until the screen's first layout node is
added, and null again once its root node is deleted). Each connection carries
its `derivations` (per-field provenance: `target_field` ← `source_fields`), so a
visualiser can render completeness and field flow directly from the snapshot.

Each slice carries `is_complete` (its pattern's strict composition: one command
for a `state_change` slice, one read model for a `state_view` slice, one command
and one automation for an `automation` slice) and its `specifications[]`: each
one's `is_complete` (one `when_step` naming a command for a
`state_change`/`automation` slice, or one `then_step` naming a read model for a
`state_view` slice) and its `steps[]` in order, each step always carrying its
`element` — a snapshot never omits the key: a nested `{id, name}` ref on a
normal step, or an explicit `null` on an error step (a delta spec-step differs:
there `element` is the element's bare integer id, and an error step omits the
key entirely — see `Delta events`) — and its `examples[]` (`field_name`/
`field_value` pairs). Neither flag accounts for
`status`: a slice tagged `informational` still reports `is_complete`
independently, and it is export (not the stream) that excludes such slices.

Each placed element's `fields` gives the shape only — `name`, `type`,
`optional`, `cardinality`. A field is as the author left it, so `optional` and
`cardinality` are absent from a field that was authored without them (the
interchange export applies `false`/`single` as defaults there). Nested
`subfields` are NOT streamed here: the full recursive Field shape travels only
in a canonical delta `entity.fields` (or in the SchemaCodec export). `GET /model`
does not expose element fields at all.

The ids correlate directly with deltas: the placement `id` matches a
`PlaceElement` delta's entity id, `element.id` matches `CreateElement`,
`connections[].id` matches `Connect`, and so on. Note that an element appears in
the snapshot *as an element* only where it is placed (the stream is
placement-bound): a standalone element with no placement is not in the snapshot
at all, though its `CreateElement` delta still arrives on the stream. Elements
that are wired but never placed appear only as the `{id, name}` ends of a
connection, without their fields or kind.

## 2. Delta events (one per mutation)

Every committed authoring mutation produces exactly one delta, delivered in
commit order. Unlike the snapshot, deltas carry **id-keyed, normalised canonical
entities** with integer foreign-key references.

A delta is `{ "op": <OperationName>, "changes": [ <Change>, ... ] }`. Each change
is one of:

- **created / updated** — `{ "action", "type", "id", "entity": <full entity> }`
- **deleted** — `{ "action": "deleted", "type", "id" }` (no `entity`)

Create:

```
event: CreateElement
data: {"op":"CreateElement","changes":[{"action":"created","type":"element","id":5,"entity":{"model":1,"name":"PlaceOrder","kind":"command","context":"internal","fields":[],"field_origins":[],"is_information_complete":true,"id":5,"type":"element"}}]}
```

Update (full new entity state is sent):

```
event: SetImageUrl
data: {"op":"SetImageUrl","changes":[{"action":"updated","type":"element","id":4,"entity":{"model":1,"name":"OrderScreen","kind":"screen","context":"internal","fields":[{"name":"id","type":"uuid","optional":false,"cardinality":"single","subfields":[]}],"field_origins":[],"is_information_complete":true,"image_url":"http://x/s.png","id":4,"type":"element"}}]}
```

Cascading delete — **one** delta listing every removed entity, leaves first:

```
event: DeleteTimeline
data: {"op":"DeleteTimeline","changes":[{"action":"deleted","type":"placement","id":7},{"action":"deleted","type":"spec-step","id":10},{"action":"deleted","type":"spec-step","id":11},{"action":"deleted","type":"specification","id":9},{"action":"deleted","type":"slice","id":4},{"action":"deleted","type":"timeline","id":3}]}
```

### Operations and the changes they emit

| `op` | change action(s) | entity type(s) |
|------|------------------|----------------|
| `CreateTimeline` | created | timeline |
| `RenameTimeline` | updated | timeline |
| `DeleteTimeline` | deleted | timeline + cascaded slices, placements, specifications, spec-steps |
| `CreateSwimlane` | created | swimlane |
| `RenameSwimlane` / `ReorderSwimlane` | updated | swimlane |
| `DeleteSwimlane` | updated, deleted | element(s) re-stated with `swimlane: null`, then the swimlane |
| `AddSlice` | created | slice |
| `ReorderSlice` / `SetSliceStatus` / `SetSliceKind` | updated | slice |
| `DeleteSlice` | deleted | slice + cascaded placements, specifications, spec-steps |
| `CreateElement` | created | element |
| `SetFields` / `SetElementContext` / `AssignSwimlane` / `SetImageUrl` / `SetFieldOrigins` / `RenameElement` | updated | element |
| `AddWireframeNode` / `AddWireframeNodeBefore` / `SetWireframeAttr` / `SetWireframeText` / `DeleteWireframeNode` | updated | the screen element, restated with its new layout tree |
| `DeleteElement` | deleted, updated | element + cascaded placements, connections, then the surviving `to` element(s) whose completeness the removed connections moved |
| `PlaceElement` | created | placement |
| `ReorderPlacement` | updated (one per placement whose index moved, in new-index order; an empty `changes` list when the move changes nothing) | placement |
| `RemovePlacement` | deleted | placement |
| `Connect` | created, updated | connection, then the `to` element (wiring it may have changed its completeness) |
| `Disconnect` | deleted, updated | connection, then the `to` element |
| `SetConnectionDerivations` | updated, updated | connection, then its `to` element |
| `AddSpecification` | created | specification |
| `DeleteSpecification` | deleted | specification + cascaded spec-steps |
| `AddSpecStep` / `AddErrorStep` | created | spec-step |
| `RemoveSpecStep` | deleted | spec-step |
| `SetStepExamples` / `SetStepExpectEmpty` | updated | spec-step |

Within one delta the `changes` are ordered: for an operation aimed at a single
entity, that entity comes first, followed by any entity whose derived state moved
with it (`Connect`, `Disconnect`, `SetConnectionDerivations` restate their `to`
element, and the `DeleteElement` cascade restates each surviving `to` endpoint
once, after its removals; `DeleteSwimlane` restates the elements it unassigned
before removing the swimlane). `ReorderPlacement` is the exception: it restates
every placement whose index moved, in new-index order — the moved placement is
not necessarily first, and a move that changes nothing emits an empty `changes`
list. A cascade lists removed entities leaves-first, so each entity is gone
before the one that contained it — apply the `changes` in the order given and the
result is well-defined at every step.

### Entity shapes

All entities carry integer `id` and `type`; relationships are integer ids.

| type | fields |
|------|--------|
| `timeline` | `id, type, model, title` |
| `swimlane` | `id, type, model, name, index` |
| `slice` | `id, type, timeline, title, kind, index, status` |
| `element` | `id, type, model, name, kind, context, fields[], field_origins[], is_information_complete, swimlane?, image_url?, wireframe?` |
| `placement` | `id, type, slice, element, index` |
| `connection` | `id, type, model, from, to, derivations[]` |
| `specification` | `id, type, slice, title` |
| `spec-step` | `id, type, spec, clause, index, element?, is_error, error_name?, expect_empty, examples[]` |

`swimlane?`, `image_url?`, `wireframe?`, `element?` and `error_name?` are
conditional rather than merely rare (`is_information_complete` is always present
— it is derived, every declared field sourced, and a fresh element declares no
fields):

- `swimlane` is absent until the element is assigned a swimlane; afterwards it is
  an integer id, or an explicit `null` once the swimlane it pointed at has been
  deleted.
- `image_url` is absent until `SetImageUrl` is invoked, then always a string.
- `wireframe` is absent until the screen's first layout node is added; it carries
  the tree while one exists, and becomes `null` when the root node is deleted.
- `element` holds the element's bare integer id on a normal step (not the nested
  `{id, name}` ref a snapshot carries) and is omitted entirely on an error step,
  never set to `null`; the snapshot by contrast always carries the key and uses
  an explicit `null` there. `error_name` is present only on one.

Embedded value objects:
- **Field**: `{ name, type }` plus `optional`, `cardinality` and `subfields[]` when the field carries them — authoring may omit all three, and the interchange export defaults them to `false`, `single` and `[]`
- **Example**: `{ field_name, field_value }`
- **FieldDerivation** (on `connection.derivations`): `{ target_field, source_fields[] }` — a target field derived from one or more source fields (one with a different name = rename; many = aggregation)
- **FieldOrigin** (on `element.field_origins`): `{ field, origin }` — a field legitimately introduced rather than sourced upstream
- **WireframeNode** (on `element.wireframe`, recursive): a hiccup-like JSON array `[tag, id-map, attrs?, ...children]`. Clojure keywords are serialised as strings, so `[:button {:-id "n2"} {:label "OK" :variant :primary}]` arrives as `["button", {"-id": "n2"}, {"label": "OK", "variant": "primary"}]`, and a vector-valued attribute such as a dropdown's `:options` arrives as a JSON array. The root node's tag is always `"canvas"` (the whole tree *is* the wireframe; there is no wrapper object). The node's id is its own map — the second element, always carrying `"-id"`, a stable string address for incremental edits, allocated one past the highest id in the tree and never reused, so deleting a node neither shifts nor recycles any other node's id — and the node's content attributes, when it has any, follow in an object of their own rather than being merged into that id map. Children are either nested WireframeNodes or plain strings (text-children tags: `h1`, `h2`, `h3`, `text`, `span`). `DeleteWireframeNode` on the root node sets `wireframe` to `null` on the next delta: the root is the tree's top, so the whole layout goes with it. The tag and attribute vocabulary is in [`wireframe-dsl.md`](../doc/wireframe-dsl.md).

### Enum values

- `slice.kind`: `state_change`, `state_view`, `automation`
- `slice.status`: `created`, `in_progress`, `done`, `informational`
- `element.kind`: `command`, `event`, `read_model`, `screen`, `automation`
- `element.context`: `internal`, `external`
- `spec-step.clause`: `given_step`, `when_step`, `then_step`
- `field.type`: `string`, `boolean`, `double`, `decimal`, `long`, `custom`, `date`, `date_time`, `uuid`, `int`
- `field.cardinality`: `single`, `list`
- `field_origin.origin`: `user_input`, `generated`, `external`
- `wireframe` node tags and their attributes: a tag vocabulary of its own, listed
  in [`wireframe-dsl.md`](../doc/wireframe-dsl.md) (`canvas`, `row`, `col`,
  `button`, `input`, … with each tag's allowed attribute values)

## Consuming the stream

Both the snapshot and the deltas are keyed by the same integer entity `id`, so a
single normalised store works end to end:

1. **Seed** your store from the snapshot: index timelines, slices, placements,
   swimlanes and connections by their `id`. Element identity is available via
   `placements[].element.id` and `connections[].from/to.id`.
2. **Patch** by id as deltas arrive: on a `created`/`updated` change, upsert
   `entity` into the collection for its `type`; on a `deleted` change, drop the
   `(type, id)`. A cascading delete arrives as one delta listing every removed
   entity, so apply all of its `changes` atomically.

Three shape differences to keep in mind:

- The snapshot is a **nested projection** (slices under timelines, element name/
  kind under placements), whereas a delta `entity` is the **flat canonical
  record** with foreign-key ids (e.g. a slice entity has `timeline`; a placement
  has `slice` and `element`). Index by id and the two line up.
- Deltas carry canonical fields the snapshot projection omits — an element's
  `context` and `field_origins` appear in a delta `entity` but nowhere in the
  snapshot — and the snapshot's `fields` are a reduced shape. Treat the delta
  `entity` as the authoritative latest state for that id.
- A spec-step's `element` is the same name carrying a different shape in each
  encoding, and neither statement about it is wrong for the other side. The
  snapshot always carries the key: a nested `{id, name}` ref on a normal step,
  an explicit `null` on an error step. A delta holds the element's bare integer
  id on a normal step and omits the key entirely on an error step, rather than
  setting it to `null`. So "always present" describes a snapshot step and
  "absent on an error step" describes a delta step; do not apply one rule to
  both encodings.

## Reconnection

SSE has no replay here: on reconnect you receive a fresh snapshot reflecting the
current state, then deltas from that point. Discard any prior delta-built state
and reseed from the new snapshot on every (re)connection.
