# Making the wireframing tool reliable for small models

Findings and ideas from a session where Gemma 4 E4B (via the `event-modeler` →
`wireframer` agents) built a login form on the "Registration Form" screen. The
transcript is in `creating-login-form-on-registration-form.md`.

Most failures were caused by the tool surface, not the model: the model largely
did what the tool description and examples suggested, and several of those
were misleading.

Status legend: **[planned]** is scheduled in the current work, **[idea]** is
parked for later.

## What went wrong

| # | Symptom | Root cause |
|---|---|---|
| 1 | Passed `--text` to `input`/`button` → rejected | The tool description (`tools.json`) advertises `add-node (--element --tag [--text] [--parent])`. `--text` is the only content flag mentioned, and it is wrong for most tags. The error's `Usage:` line repeats `[--text <string>]`, contradicting the error itself. |
| 2 | Invented `--value "type: email, label: Email"` | Attribute flags are per-tag and invisible in the tool schema. Each fresh subagent has to rediscover them. |
| 3 | Fired 3 `add-node` calls in parallel → nodes landed Password, Submit, Email | Concurrent calls; nothing tells the model that mutation order matters. Intermediate outputs show divergent snapshots. |
| 4 | "Reorder" = delete all + re-add (again in parallel), most likely reproducing the same wrong order | No move verb. The re-add outputs are byte-identical to the first session's. |
| 5 | Model reported "done, in the specified order" both times, and was wrong | Mutations return a big nested-JSON element the model could not read; nothing forces a final check; the wireframer returned **no output**, which the orchestrator read as success. |
| 6 | The `tags --tag input` example is `add-node --element <screen id> --tag input` | The single example teaches the model to omit attributes. |

## Ideas

### A. Fix the traps in the tool surface

1. **[idea] Fix the tool description** so it stops advertising `[--text]` as the
   generic content flag, e.g.
   `add-node --element N --tag T [--parent nX] [--<attr> <value> ...]  (attrs depend on tag; --text only for h1/h2/h3/text/span/alert)`.
2. **[planned] Self-correcting errors.** Put the valid attributes and a
   ready-to-run corrected command in the error itself:
   ```
   ✗ unknown attribute :text for :button
     valid: --label (required) --variant primary|secondary|ghost|danger --disabled --command-input
     try:   emcli wireframe add-node --element 31 --tag button --label "<text>"
   ```
   Turns a two-call recovery into one. Drop or make tag-specific the generic
   `Usage:` line.
3. **[idea] "Did you mean" aliases:** `--text` → `--label` on button/input,
   `--value` → suggestion. Small models make predictable mistakes; absorb them.
4. **[idea] Put attributes in the examples.** Each `tags --tag X` example should
   be a realistic full command (`--tag input --type email --label "Email"`).
   Small models copy examples very closely.
5. **[idea] Inline attributes in the `tags` overview:**
   `input  [type label placeholder required field-name command-input]`, so one
   call covers discovery.

### B. Make mutation output easy to read and verify

6. **[planned] Every mutation returns the compact `show` tree** (not the element
   JSON), plus the id of the node it touched:
   ```
   added n4 :input {:type "email", :label "Email"}
   [n1] :canvas
     [n2] :input {...Password}
     [n3] :button {...Submit}
     [n4] :input {...Email}
   ```
   A small model can check order in an indented tree; it could not in nested
   hiccup-in-JSON.

### C. Remove order-sensitive multi-step work

7. **[planned] `move-node`**: `--node n4 --before n2`, or into a parent as last
   child. Reordering becomes one call and preserves node ids and field bindings.
8. **[idea] `--before`/`--position` on `add-node`**, retiring the separate
   `add-node-before` verb the model misunderstood.
9. **[idea] Serialize mutations per element server-side.** Even then, parallel
   calls from the model have no defined order, so also:
10. **[planned] A declarative `apply` verb** that takes a whole (sub)tree in the
    same indented format `show` prints:
    ```
    emcli wireframe apply --element 31 --tree '
    col
      input type=email label=Email
      input type=password label=Password
      button label=Submit variant=primary'
    ```
    Read → edit text → write back. "Reorder", "add three fields" and
    "restructure" each become one call with one validation error list. Small
    models are much better at emitting a full target state than at planning an
    imperative sequence. Node ids are preserved where a line keeps its `[nX]`
    prefix.

### D. Structured tool schema instead of a free-form `args` string

11. **[idea]** Replace `args: string` with typed properties (`element` int,
    `tag` enum, `parent`/`before`/`node` string, `attrs` object), or split into
    per-verb tools. Small models fill in JSON schemas with enums far more
    reliably than they compose CLI flag strings with correct quoting.

### E. Agent and skill prompting

12. **[idea] Inline the key rules in the wireframer's system prompt** rather than
    relying on a skill load (saves a step; small models follow the system prompt
    more closely than tool output). Short, imperative:
    - "Run authoring calls **one at a time**, never in parallel."
    - "Finish with `show` and return its output verbatim."
13. **[idea] 2–3 worked examples** (build a form, reorder, change a label).
14. **[planned] Handoff contract.** The wireframer must return the final `show`
    tree; the orchestrator treats an empty result as failure, not success, and
    checks the tree against the request before telling the user.
15. **[idea] Orchestrator passes a concrete plan**, or even the target `apply`
    tree, so the wireframer mostly executes.
16. **[idea] Disable parallel tool calls** for the wireframer if ECA supports it,
    as a backstop.

## Decisions

Confirmed before specifying 2, 6, 7, 10 and 14:

- **`move-node` targets:** `--before <sibling>` (placed immediately before it)
  or `--parent <container>` (appended as its last child). Moving across parents
  is allowed. Every node id is preserved. Rejected: moving the root, moving a
  node before the root, moving a node into its own subtree (including before a
  node inside it), and a `--parent` that is not a container tag.
- **`apply` format:** the same indented text format `show` prints, so the model
  reads and writes one form.
- **`apply` scope:** whole tree only for now; subtree replacement (`--node nX`)
  is deferred.
- **`apply` ids:** a line prefixed `[nX]` keeps that id and must name an
  existing node; an unprefixed line gets a fresh id; existing nodes absent from
  the input are deleted. The first line is always the layout's root: written
  as `:canvas` it keeps the root's id, and naming any other node there is
  rejected (added after the spec/code review found the root could otherwise
  silently get a new id). All-or-nothing: on any invalid line nothing changes
  and every error is reported.
- **Boolean values:** a flag value other than true/false counts as "outside
  the allowed set", so its rejection also lists what the tag admits.
- **`--json` output** is the raw edited element; it does not carry the touched
  node id (the server response does).
- **Output (#6):** wireframe mutations print the touched node and the `show`
  tree by default; a `--json` flag keeps the previous element-JSON output for
  scripts.
- **Spec scope:** #2 and #6 enter `event-model.allium` only as prose
  `@guarantee`s on `ModelAuthoring` (terminal rendering is out of spec scope);
  #14 is prompt-only (tool manifests and harnesses are out of spec scope).

## Why 2, 6, 7, 10 and 14 first

Together they would have prevented every failure in the transcript: errors that
fix themselves (2), output the model can verify (6), a direct way to reorder
(7), a single-call way to state the whole target (10), and a handoff that cannot
claim success silently (14).
