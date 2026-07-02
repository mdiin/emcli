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

The stream is **outbound only**: the consumer receives changes and can only
disconnect. All editing happens through `POST /authoring/<command>` on the
separate ModelAuthoring boundary.

## Wire framing

`GET /stream` responds with `Content-Type: text/event-stream`. Each event is:

```
event: <name>
data: <one line of JSON>
<blank line>
```

- The `event:` name equals the payload's `op` field.
- The **first** event is always `event: snapshot` (exactly one).
- Every **subsequent** event is a delta; its `event:` name is the authoring
  operation (e.g. `CreateTimeline`). There is no gap: no mutation committed
  after the snapshot is taken is lost or duplicated.

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
            "placements": [
              { "id": 7, "element": { "id": 2, "name": "PlaceOrder", "kind": "command",
                                      "is_information_complete": true,
                                      "fields": [ { "name": "id", "type": "uuid",
                                                    "optional": false, "cardinality": "single" } ] } }
            ],
            "specifications": [
              { "id": 9, "title": "Happy path", "is_complete": true,
                "steps": [
                  { "id": 10, "clause": "when_step", "index": 0, "is_error": false, "error_name": null,
                    "examples": [ { "field_name": "id", "field_value": "1" } ] }
                ] }
            ] }
        ] }
    ],
    "swimlanes": [ { "id": 4, "name": "Orders" } ],
    "connections": [
      { "id": 8,
        "from": { "id": 2, "name": "PlaceOrder" },
        "to":   { "id": 3, "name": "OrderPlaced" },
        "derivations": [ { "target_field": "total", "source_fields": ["amount"] } ] }
    ]
  }
}
```

Each placed element carries `is_information_complete` (true iff every field on
it is sourced — carried, derived, or introduced), and each connection carries
its `derivations` (per-field provenance: `target_field` ← `source_fields`), so a
visualiser can render completeness and field flow directly from the snapshot.

Each slice also carries its `specifications[]`: each one's `is_complete` (see
`SpecificationCompleteness` — one `when_step` naming a command for a
`state_change`/`automation` slice, or one `then_step` naming a read model for a
`state_view` slice) and its `steps[]` in order, each step carrying its
`examples[]` (`field_name`/`field_value` pairs). This is the same shape
`GET /model` exposes per step, so a visualiser can render given/when/then rows
with example data straight from either source.

Each placed element's `fields` gives the shape only — `name`, `type`,
`optional`, `cardinality` — so a visualiser can render an element's fields
directly on the canvas. Nested `subfields` are NOT streamed here (only the
canonical delta `entity.fields` carries the full recursive Field shape); fetch
`GET /model` or the SchemaCodec export for that.

The ids correlate directly with deltas: the placement `id` matches a
`PlaceElement` delta's entity id, `element.id` matches `CreateElement`,
`connections[].id` matches `Connect`, and so on. Note that an element appears in
the snapshot only where it is placed or connected (the stream is
placement-bound); a standalone element with no placement is not in the snapshot,
though its `CreateElement` delta still arrives on the stream.

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
data: {"op":"CreateElement","changes":[{"action":"created","type":"element","id":5,"entity":{"model":1,"name":"PlaceOrder","kind":"command","context":"internal","fields":[],"id":5,"type":"element"}}]}
```

Update (full new entity state is sent):

```
event: SetImageUrl
data: {"op":"SetImageUrl","changes":[{"action":"updated","type":"element","id":4,"entity":{"model":1,"name":"OrderScreen","kind":"screen","context":"internal","fields":[{"name":"id","type":"uuid","optional":false,"cardinality":"single","subfields":[]}],"image_url":"http://x/s.png","id":4,"type":"element"}}]}
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
| `DeleteTimeline` | deleted | timeline (+ cascaded slices, placements, specifications, spec-steps) |
| `CreateSwimlane` | created | swimlane |
| `RenameSwimlane` | updated | swimlane |
| `DeleteSwimlane` | updated, deleted | element(s) un-assigned, then swimlane |
| `AddSlice` | created | slice |
| `ReorderSlice` / `SetSliceStatus` / `SetSliceKind` | updated | slice |
| `DeleteSlice` | deleted | slice (+ cascaded placements, specifications, spec-steps) |
| `CreateElement` | created | element |
| `SetFields` / `SetElementContext` / `AssignSwimlane` / `SetImageUrl` / `SetFieldOrigins` / `RenameElement` | updated | element |
| `DeleteElement` | deleted | element (+ cascaded placements, connections) |
| `PlaceElement` | created | placement |
| `RemovePlacement` | deleted | placement |
| `Connect` | created | connection |
| `Disconnect` | deleted | connection |
| `SetConnectionDerivations` | updated | connection |
| `AddSpecification` | created | specification |
| `DeleteSpecification` | deleted | specification (+ cascaded spec-steps) |
| `AddSpecStep` / `AddErrorStep` | created | spec-step |
| `RemoveSpecStep` | deleted | spec-step |
| `SetStepExamples` / `SetStepExpectEmpty` | updated | spec-step |

### Entity shapes

All entities carry integer `id` and `type`; relationships are integer ids.

| type | fields |
|------|--------|
| `timeline` | `id, type, model, title` |
| `swimlane` | `id, type, model, name` |
| `slice` | `id, type, timeline, title, kind, index, status` |
| `element` | `id, type, model, name, kind, context, fields[], field_origins[], swimlane?, image_url?` |
| `placement` | `id, type, slice, element` |
| `connection` | `id, type, model, from, to, derivations[]` |
| `specification` | `id, type, slice, title` |
| `spec-step` | `id, type, spec, clause, index, element?, is_error, error_name?, expect_empty, examples[]` |

`element?`/`swimlane?`/`image_url?`/`error_name?` are optional: `swimlane` and
`image_url` are absent until set; `element` is absent on an error step and
`error_name` is present only then.

Embedded value objects:
- **Field**: `{ name, type, optional, cardinality, subfields[] }`
- **Example**: `{ field_name, field_value }`
- **FieldDerivation** (on `connection.derivations`): `{ target_field, source_fields[] }` — a target field derived from one or more source fields (one with a different name = rename; many = aggregation)
- **FieldOrigin** (on `element.field_origins`): `{ field, origin }` — a field legitimately introduced rather than sourced upstream

### Enum values

- `slice.kind`: `state_change`, `state_view`, `automation`
- `slice.status`: `created`, `in_progress`, `done`, `informational`
- `element.kind`: `command`, `event`, `read_model`, `screen`, `automation`
- `element.context`: `internal`, `external`
- `spec-step.clause`: `given_step`, `when_step`, `then_step`
- `field.type`: `string`, `boolean`, `double`, `decimal`, `long`, `custom`, `date`, `date_time`, `uuid`, `int`
- `field.cardinality`: `single`, `list`
- `field_origin.origin`: `user_input`, `generated`, `external`

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

Two shape differences to keep in mind:

- The snapshot is a **nested projection** (slices under timelines, element name/
  kind under placements), whereas a delta `entity` is the **flat canonical
  record** with foreign-key ids (e.g. a slice entity has `timeline`; a placement
  has `slice` and `element`). Index by id and the two line up.
- Deltas may carry canonical fields the snapshot omits (e.g. an element delta
  includes `context`, `fields`, `image_url`). Treat the delta `entity` as the
  authoritative latest state for that id.

## Reconnection

SSE has no replay here: on reconnect you receive a fresh snapshot reflecting the
current state, then deltas from that point. Discard any prior delta-built state
and reseed from the new snapshot on every (re)connection.
