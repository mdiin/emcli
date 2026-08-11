---
name: emcli-cookbook
description: >
  Composed multi-step patterns for authoring Event Models with emcli.
  Load alongside emcli-authoring for concrete sequencing guidance.
  Audience: LLMs. Recipes show exact tool-call sequences; ids in comments
  are illustrative — always use ids returned from actual calls.
when_to_use: >
  Load this skill when authoring an Event Model and you need to know how to
  sequence multiple primitive operations to accomplish a complete task — for
  example: building a full state-change step, modelling data flow between
  elements, constructing a wireframe, writing a spec, or refactoring existing
  model elements.
---

# emcli Cookbook

Composed patterns for common Event Model authoring tasks. Each recipe chains primitive operations into a complete sequence. This is not a reference — load `emcli-authoring` for the full verb/args reference.

**Notation:**
- `# result id → N` — read the id from the actual tool response; do not use the illustrative number literally
- `# parallel` — this call is independent of the others in its group; issue all `# parallel` calls in the same batch
- `# sequential` — this call depends on the one above it on the same element; do not batch with it

**Prerequisite:** `emcli serve` must be running before any call.

## Category 1 — Scaffold a timeline

### 1.1 — Create a timeline with ordered slices

Timeline must exist before slices can reference it. Slices are ordered left-to-right by `index`.

```
emcli_author  group="timeline"  verb="add"  args={"title": "Order Placement"}
# result id → 1

emcli_author  group="slice"  verb="add"  args={"timeline": 1, "title": "Browse Catalogue", "kind": "state_view", "index": 0}    # parallel
emcli_author  group="slice"  verb="add"  args={"timeline": 1, "title": "Place Order", "kind": "state_change", "index": 1}       # parallel
emcli_author  group="slice"  verb="add"  args={"timeline": 1, "title": "Payment", "kind": "automation", "index": 2}             # parallel
```

### 1.2 — Add a swimlane structure

Index controls top-to-bottom render order.

```
emcli_author  group="swimlane"  verb="add"  args={"name": "Customer", "index": 0}        # parallel
emcli_author  group="swimlane"  verb="add"  args={"name": "Backend", "index": 1}         # parallel
emcli_author  group="swimlane"  verb="add"  args={"name": "Payment Gateway", "index": 2} # parallel
```

### 1.3 — Insert a slice between two existing slices

Reorder right-to-left (highest index first) to avoid index collisions, then add at the freed index.

```
# Existing: slice id=10 at index 1, slice id=11 at index 2. Insert at index 1.
emcli_author  group="slice"  verb="reorder"  args={"slice": 11, "new-index": 3}
emcli_author  group="slice"  verb="reorder"  args={"slice": 10, "new-index": 2}
emcli_author  group="slice"  verb="add"  args={"timeline": 1, "title": "Verify Identity", "kind": "state_change", "index": 1}
```

## Category 2 — Build a complete state-change step

### 2.1 — Minimal state-change slice

A valid `state_change` requires exactly one command and one event, both placed in the slice.

```
emcli_author  group="slice"  verb="add"  args={"timeline": 1, "title": "Place Order", "kind": "state_change", "index": 0}
# result id → 5

emcli_author  group="element"  verb="add"  args={"name": "PlaceOrder", "kind": "command"}  # parallel
emcli_author  group="element"  verb="add"  args={"name": "OrderPlaced", "kind": "event"}   # parallel
# results: command id → 10, event id → 11

emcli_author  group="placement"  verb="add"  args={"slice": 5, "element": 10}  # parallel
emcli_author  group="placement"  verb="add"  args={"slice": 5, "element": 11}  # parallel
```

### 2.2 — State-change with swimlane assignments

Extend 2.1: resolve swimlane ids by name, then assign each element to its owning actor.

```
emcli_resolve  queries="Customer:swimlane,Backend:swimlane"
# results: Customer id → 20, Backend id → 21

emcli_author  group="element"  verb="swimlane"  args={"element": 10, "lane": 20}  # parallel
emcli_author  group="element"  verb="swimlane"  args={"element": 11, "lane": 21}  # parallel
```

### 2.3 — State-change with full field definitions

`add-field` calls on the same element are sequential (each modifies the element); calls across different elements are parallel. `add-origin` follows the same rule.

```
# Command fields (sequential — same element)
emcli_author  group="element"  verb="add-field"   args={"element": 10, "name": "customerId", "type": "uuid"}
emcli_author  group="element"  verb="add-field"   args={"element": 10, "name": "items", "type": "custom", "cardinality": "list"}
emcli_author  group="element"  verb="add-origin"  args={"element": 10, "field": "customerId", "origin": "user_input"}
emcli_author  group="element"  verb="add-origin"  args={"element": 10, "field": "items", "origin": "user_input"}

# Event fields (sequential — same element; independent of command block above)
emcli_author  group="element"  verb="add-field"   args={"element": 11, "name": "orderId", "type": "uuid"}
emcli_author  group="element"  verb="add-field"   args={"element": 11, "name": "placedAt", "type": "date_time"}
emcli_author  group="element"  verb="add-origin"  args={"element": 11, "field": "orderId", "origin": "generated"}
emcli_author  group="element"  verb="add-origin"  args={"element": 11, "field": "placedAt", "origin": "generated"}
```

## Category 3 — Model data flow between elements

Data flows in two canonical directions: **command → event** and **event → read model**. The connection must be created before derivations can be added to it.

**Critical rule:** only add a derivation when it carries information — a field renamed across the boundary, or multiple source fields collapsed into one target. Never add a derivation where `from` and `target` share the same field name: same-name derivations corrupt the information completeness check.

### 3.1 — Command → event: field renamed across the boundary

The command carries `recipientEmail`; the event records the same data as `email`.

```
emcli_author  group="connection"  verb="add"  args={"from": 10, "to": 11}
# result id → 30

emcli_author  group="connection"  verb="add-derivation"  args={"connection": 30, "target": "email", "from": "recipientEmail"}
```

### 3.2 — Event → read model: field renamed across the boundary

The event records `occurredAt`; the read model surfaces it as `lastUpdated`.

```
emcli_resolve  queries="OrderSummary:read_model"
# result id → 40

emcli_author  group="connection"  verb="add"  args={"from": 11, "to": 40}
# result id → 31

emcli_author  group="connection"  verb="add-derivation"  args={"connection": 31, "target": "lastUpdated", "from": "occurredAt"}
```

### 3.3 — Multi-source derivation: N fields collapsed into one

Pass all source field names as a comma-separated list in `from`.

```
emcli_author  group="connection"  verb="add-derivation"  args={"connection": 31, "target": "displayName", "from": "firstName,lastName"}
```

## Category 4 — Build a state-view step

### 4.1 — Minimal state-view slice

A valid `state_view` requires exactly one read model placed in it.

```
emcli_author  group="slice"  verb="add"  args={"timeline": 1, "title": "View Order", "kind": "state_view", "index": 3}
# result id → 6

emcli_author  group="element"  verb="add"  args={"name": "OrderSummary", "kind": "read_model"}
# result id → 40

emcli_author  group="placement"  verb="add"  args={"slice": 6, "element": 40}
```

### 4.2 — Screen + read model pair with connection

Place both elements in the same `state_view` slice; connect read model → screen to model the display flow.

```
emcli_author  group="element"  verb="add"  args={"name": "OrderDetailPage", "kind": "screen"}
# result id → 41

emcli_author  group="placement"  verb="add"  args={"slice": 6, "element": 40}  # parallel
emcli_author  group="placement"  verb="add"  args={"slice": 6, "element": 41}  # parallel

emcli_author  group="connection"  verb="add"  args={"from": 40, "to": 41}
```

## Category 5 — Build a screen wireframe

Call `emcli_show_wireframe` once at the start of a session to get current node ids. Node ids are stable — do not call it again unless you added new nodes and need their ids. Text-children tags (h1, h2, h3, text, span) do not accept a `text` arg on creation — set text with a separate `set-wireframe-text` call after the node is created.

### 5.1 — Scaffold a form screen

New nodes append as the last child of `n1` (the root) by default; specify `parent` to target a container.

```
emcli_author  group="element"  verb="add"  args={"name": "CheckoutPage", "kind": "screen"}
# result id → 50

emcli_author  group="element"  verb="add-wireframe-node"  args={"element": 50, "tag": "col", "gap": "md"}
# result node id → n2

emcli_author  group="element"  verb="add-wireframe-node"  args={"element": 50, "tag": "h1", "parent": "n2"}
# result node id → n3
emcli_author  group="element"  verb="set-wireframe-text"  args={"element": 50, "node": "n3", "text": "Checkout"}

emcli_author  group="element"  verb="add-wireframe-node"  args={"element": 50, "tag": "input", "parent": "n2", "label": "Email address", "type": "email", "required": true}
# result node id → n4
emcli_author  group="element"  verb="add-wireframe-node"  args={"element": 50, "tag": "input", "parent": "n2", "label": "Delivery address", "type": "text", "required": true}
# result node id → n5

emcli_author  group="element"  verb="add-wireframe-node"  args={"element": 50, "tag": "button", "parent": "n2", "label": "Place Order", "variant": "primary"}
# result node id → n6
```

### 5.2 — Add a row of buttons

Nodes within the same parent are appended in call order — each call must complete before the next to preserve sibling order.

```
emcli_author  group="element"  verb="add-wireframe-node"  args={"element": 50, "tag": "row", "parent": "n2", "gap": "sm"}
# result node id → n7

emcli_author  group="element"  verb="add-wireframe-node"  args={"element": 50, "tag": "button", "parent": "n7", "label": "Cancel", "variant": "secondary"}
# result node id → n8
emcli_author  group="element"  verb="add-wireframe-node"  args={"element": 50, "tag": "button", "parent": "n7", "label": "Confirm", "variant": "primary"}
# result node id → n9
```

### 5.3 — Annotate inputs with field-name and command-input

`field-name` must match a field already added to the element via `element add-field`. `command-input: true` marks the node as contributing to the command this screen triggers. Calls on the same node are sequential; calls on different nodes are parallel.

```
emcli_author  group="element"  verb="set-wireframe-attr"  args={"element": 50, "node": "n4", "attr": "field-name", "value": "email"}       # sequential
emcli_author  group="element"  verb="set-wireframe-attr"  args={"element": 50, "node": "n4", "attr": "command-input", "value": "true"}     # sequential

emcli_author  group="element"  verb="set-wireframe-attr"  args={"element": 50, "node": "n5", "attr": "field-name", "value": "deliveryAddress"}  # sequential (n4 block and n5 block are independent of each other)
emcli_author  group="element"  verb="set-wireframe-attr"  args={"element": 50, "node": "n5", "attr": "command-input", "value": "true"}          # sequential
```

### 5.4 — Move a node to an arbitrary position

There is no move primitive. Moving a node means deleting it and re-adding it before the desired sibling using `add-wireframe-node-before`. Call `emcli_show_wireframe` first to confirm current node ids and the node's attributes (you will need to recreate them).

```
# Goal: move the Cancel button (n8) to appear after the Confirm button (n9).
# Current order: n8 (Cancel), n9 (Confirm). Desired: n9 (Confirm), n8-new (Cancel).

emcli_show_wireframe  element=50
# note n8 attrs: tag=button, label="Cancel", variant=secondary, parent=n7

emcli_author  group="element"  verb="delete-wireframe-node"  args={"element": 50, "node": "n8"}
# n8 removed; n9 id unchanged

# There is no sibling after n9 to insert before, so append to the parent directly:
emcli_author  group="element"  verb="add-wireframe-node"  args={"element": 50, "tag": "button", "parent": "n7", "label": "Cancel", "variant": "secondary"}
# result node id → n10

# If a sibling exists after the target position, use add-wireframe-node-before instead:
# emcli_author  group="element"  verb="add-wireframe-node-before"  args={"element": 50, "before": "n9", "tag": "button", "label": "Cancel", "variant": "secondary"}
```

`add-wireframe-node-before` inserts the new node immediately before `before` in the parent's child list. All other sibling ids are unaffected.

### 5.5 — Delete a subtree

Deleting a container removes its entire descendant tree. Sibling node ids are unaffected.

```
emcli_show_wireframe  element=50
# identify the container to remove, e.g. n7 (the button row)

emcli_author  group="element"  verb="delete-wireframe-node"  args={"element": 50, "node": "n7"}
# n7 and all its children (n8, n9) removed; all other node ids unchanged
```

## Category 6 — Write a Given/When/Then specification

### 6.1 — Minimal spec with examples

Steps are ordered by `index`. Examples on the `when_step` supply command input; examples on the `then_step` assert the event's produced fields. `add-example` calls on the same step are sequential; calls across different steps are parallel.

```
emcli_author  group="spec"  verb="add"  args={"slice": 5, "title": "Customer places an order"}
# result id → 60

emcli_author  group="step"  verb="add"  args={"spec": 60, "clause": "given_step", "element": 40, "index": 0}  # parallel
emcli_author  group="step"  verb="add"  args={"spec": 60, "clause": "when_step",  "element": 10, "index": 1}  # parallel
emcli_author  group="step"  verb="add"  args={"spec": 60, "clause": "then_step",  "element": 11, "index": 2}  # parallel
# results: given id → 70, when id → 71, then id → 72

emcli_author  group="step"  verb="add-example"  args={"step": 71, "field-name": "customerId", "value": "cust-123"}
emcli_author  group="step"  verb="add-example"  args={"step": 71, "field-name": "items", "value": "[{sku: 'ABC', qty: 1}]"}

emcli_author  group="step"  verb="add-example"  args={"step": 72, "field-name": "orderId", "value": "ord-456"}
emcli_author  group="step"  verb="add-example"  args={"step": 72, "field-name": "placedAt", "value": "2024-01-15T10:30:00Z"}
```

### 6.2 — Spec with an error path

`step error` names a business rule violation. It takes no element — it is a named rejection outcome placed at the end of the spec.

```
emcli_author  group="spec"  verb="add"  args={"slice": 5, "title": "Order rejected — empty cart"}
# result id → 61

emcli_author  group="step"  verb="add"  args={"spec": 61, "clause": "given_step", "element": 40, "index": 0}  # parallel
emcli_author  group="step"  verb="add"  args={"spec": 61, "clause": "when_step",  "element": 10, "index": 1}  # parallel

emcli_author  group="step"  verb="error"  args={"spec": 61, "error-name": "Cart must not be empty", "index": 2}
```

### 6.3 — Spec with an empty given (no prior state required)

`expect-empty: true` on a `given_step` signals that the read model is expected to return nothing — the command requires no pre-existing state.

```
emcli_author  group="spec"  verb="add"  args={"slice": 5, "title": "First-time customer places an order"}
# result id → 62

emcli_author  group="step"  verb="add"  args={"spec": 62, "clause": "given_step", "element": 40, "index": 0}
# result id → 75

emcli_author  group="step"  verb="expect-empty"  args={"step": 75, "value": true}

emcli_author  group="step"  verb="add"  args={"spec": 62, "clause": "when_step",  "element": 10, "index": 1}  # parallel
emcli_author  group="step"  verb="add"  args={"spec": 62, "clause": "then_step",  "element": 11, "index": 2}  # parallel
```

## Category 7 — Refactor an existing model

These are deliberate editorial operations. Use `emcli_resolve` to look up ids by name before mutating — never guess ids.

### 7.1 — Rename a chain of related elements

Resolve all elements in one call, then rename in parallel.

```
emcli_resolve  queries="PlaceOrder:command,OrderPlaced:event,OrderSummary:read_model"
# results: PlaceOrder id → 10, OrderPlaced id → 11, OrderSummary id → 40

emcli_author  group="element"  verb="rename"  args={"element": 10, "new-name": "PlacePurchase"}   # parallel
emcli_author  group="element"  verb="rename"  args={"element": 11, "new-name": "PurchasePlaced"}  # parallel
emcli_author  group="element"  verb="rename"  args={"element": 40, "new-name": "PurchaseSummary"} # parallel
```

### 7.2 — Move an element to a different swimlane

```
emcli_resolve  queries="ProcessPayment:automation,Payment Gateway:swimlane"
# results: element id → 80, swimlane id → 22

emcli_author  group="element"  verb="swimlane"  args={"element": 80, "lane": 22}
```

### 7.3 — Replace a slice kind

```
emcli_resolve  queries="Confirm Payment:slice"
# result id → 7

emcli_author  group="slice"  verb="kind"  args={"slice": 7, "new-kind": "informational"}
```

### 7.4 — Remove a connection and all its derivations

All derivations must be removed before the connection itself can be removed. There is no list-derivations call — collect target field names from your knowledge of the model.

```
# connection id=31 links OrderPlaced → OrderSummary with derivations on lastUpdated and displayName
emcli_author  group="connection"  verb="remove-derivation"  args={"connection": 31, "target": "lastUpdated"}   # parallel
emcli_author  group="connection"  verb="remove-derivation"  args={"connection": 31, "target": "displayName"}  # parallel

emcli_author  group="connection"  verb="remove"  args={"connection": 31}
```

## Verification

```
emcli_validate
```
