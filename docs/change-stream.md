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

A full snapshot of the canonical model. It is **name-keyed and denormalised: it
carries no entity ids**. It contains timelines and swimlanes (which the
`eventmodeling.schema.json` interchange format does not) because the stream is
for visualisation.

```
event: snapshot
data: {"op":"snapshot","model":{"name":"Orders","timelines":[],"swimlanes":[],"connections":[]}}
```

Populated:

```json
{
  "op": "snapshot",
  "model": {
    "name": "Orders",
    "timelines": [
      { "title": "Order flow",
        "slices": [
          { "title": "Place order", "kind": "state_change", "status": "created", "index": 0,
            "placements": [ { "name": "PlaceOrder", "kind": "command" } ] }
        ] }
    ],
    "swimlanes": ["Orders"],
    "connections": [ { "from": "PlaceOrder", "to": "OrderPlaced" } ]
  }
}
```

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
| `SetFields` / `SetElementContext` / `AssignSwimlane` / `SetImageUrl` / `RenameElement` | updated | element |
| `DeleteElement` | deleted | element (+ cascaded placements, connections) |
| `PlaceElement` | created | placement |
| `RemovePlacement` | deleted | placement |
| `Connect` | created | connection |
| `Disconnect` | deleted | connection |
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
| `element` | `id, type, model, name, kind, context, fields[], swimlane?, image_url?` |
| `placement` | `id, type, slice, element` |
| `connection` | `id, type, model, from, to` |
| `specification` | `id, type, slice, title` |
| `spec-step` | `id, type, spec, clause, index, element?, is_error, error_name?, expect_empty, examples[]` |

`element?`/`swimlane?`/`image_url?`/`error_name?` are optional: `swimlane` and
`image_url` are absent until set; `element` is absent on an error step and
`error_name` is present only then.

Embedded value objects:
- **Field**: `{ name, type, optional, cardinality, subfields[] }`
- **Example**: `{ field_name, field_value }`

### Enum values

- `slice.kind`: `state_change`, `state_view`, `automation`
- `slice.status`: `created`, `in_progress`, `done`, `informational`
- `element.kind`: `command`, `event`, `read_model`, `screen`, `automation`
- `element.context`: `internal`, `external`
- `spec-step.clause`: `given_step`, `when_step`, `then_step`
- `field.type`: `string`, `boolean`, `double`, `decimal`, `long`, `custom`, `date`, `date_time`, `uuid`, `int`
- `field.cardinality`: `single`, `list`

## Important: snapshot and deltas use different shapes

The snapshot is **name-keyed without ids**; deltas are **id-keyed**. You cannot
directly map a delta's `id` onto a snapshot node. Two practical consumption
strategies:

1. **Render-then-rebuild (recommended):** use the snapshot for the initial
   picture, but maintain your own normalised store keyed by entity id that you
   build up purely from deltas going forward. Resolve names via the entities you
   receive in `created`/`updated` changes.
2. **Snapshot-only refresh:** ignore deltas for state and re-`GET /snapshot`
   (or `/model`) whenever a delta arrives, treating deltas as change *signals*.
   Simpler, but loses the per-mutation granularity.

> If you would prefer the snapshot to include entity ids (so a single store can
> be seeded from the snapshot and then patched by deltas), that is a small
> change to `emcli.app/snapshot` — ask and it can be added.

## Reconnection

SSE has no replay here: on reconnect you receive a fresh snapshot reflecting the
current state, then deltas from that point. Discard any prior delta-built state
and reseed from the new snapshot on every (re)connection.
