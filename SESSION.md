# Session Notes — 2026-08-23

## Overview

A two-part refactoring session:
1. Moved wireframe CLI commands out of the `element` group into a new top-level `wireframe` group
2. Replaced the Dirge Janet plugin with a portable, harness-agnostic tool manifest (`tools.json`) and a Claude Code MCP stdio shim (`emcli-mcp.bb`)

---

## Part 1 — Wireframe Command Group Refactoring

**What changed:**
- Six wireframe subcommands were moved from the `element` CLI group to a new top-level `wireframe` group in `src/emcli/cli.clj`
- Command names were shortened by dropping the `wireframe-` prefix, since the group conveys that context:

| Old (`element` group) | New (`wireframe` group) |
|---|---|
| `element add-wireframe-node` | `wireframe add-node` |
| `element add-wireframe-node-before` | `wireframe add-node-before` |
| `element delete-wireframe-node` | `wireframe delete-node` |
| `element set-wireframe-attr` | `wireframe set-attr` |
| `element set-wireframe-text` | `wireframe set-text` |
| `element show-wireframe` | `wireframe show` |

**What did NOT change:**
- Internal flat command names (e.g. `"add-wireframe-node"`) — these are used by `commands.clj`, `rules.clj`, and the HTTP authoring routes
- HTTP route paths (e.g. `/authoring/add-wireframe-node`) — kept flat intentionally; restructuring to `/authoring/wireframe/...` was considered and deferred as a separate task requiring all groups to be done at once
- `src/emcli/commands.clj`, `src/emcli/rules.clj`, `src/emcli/wireframe.clj` — no changes

**Files modified:** `src/emcli/cli.clj`, `test/emcli/cli_test.clj`, `doc/wireframe-dsl.md`, `README.md`

**Commits:** `49fee6a` (main refactor), `dd29b3c` (add `--export-tools` to help text)  
**Tags:** `v2.0.0` (breaking change — major bump), `v2.0.1` (patch for missing help text)

---

## Part 2 — Tool Manifest and Claude Code Integration

### Context and decisions

**Why:** The Dirge Janet plugin (`/.dirge/plugins/emcli.janet`) is to be dropped. Its tool definitions needed to be migrated to a portable format usable by other harnesses (ECA.dev, Claude Code, Pi, etc.).

**Key decisions made:**

1. **Per-group tools over a single generic tool** — A single `emcli_author` tool (as in Dirge) requires the model to know valid groups/verbs from memory. Per-group tools with `verb` enums provide grounding that helps small local LLMs. Applies equally to large models.

2. **ECA.dev format as canonical** — The Anthropic tool use format (`name` + `description` + `input_schema`) was initially considered but rejected because it has no field for the invocation command — harnesses would have to implement the CLI call themselves. The ECA.dev format adds a `command` field with `{{placeholder}}` substitution, making the tool definition self-contained. Since ECA.dev is the primary target, this became canonical.

3. **Option A for args (single `{{args}}` string)** — ECA.dev's `{{placeholder}}` model doesn't support conditional/optional flags natively. Rather than per-verb tools (exploding count to 42), each group tool uses `verb` (enum) + `args` (string: `"--flag value ..."` pairs). The model picks the group, picks the verb, assembles the flags. The tool description includes a per-verb flag summary (required flags plain, optional in brackets) to guide the model.

4. **MCP stdio shim for Claude Code** — Claude Code has no `customTools` equivalent; it uses MCP. An MCP stdio server is not a deployed service — Claude Code spawns it as an on-demand subprocess. The shim reads `tools.json` at startup and is fully data-driven via `{{placeholder}}` substitution on the `command` field, so future tool additions require no shim changes.

5. **HTTP routes frozen** — Restructuring `/authoring/add-wireframe-node` → `/authoring/wireframe/add-node` etc. was explicitly deferred. Rationale: would require doing all groups at once to avoid a half-migrated API, is a separate contract from CLI grouping, and flat names currently serve as unambiguous route identifiers.

### What was built

**`emcli --export-tools`** — new CLI command in `src/emcli/cli.clj` that generates `tools.json` deterministically from the existing CLI registry. CI drift detection: `emcli --export-tools | diff - tools.json`.

**`tools.json`** — 11 tool definitions in ECA.dev custom tool format:
- 9 per-group authoring tools: `emcli_connection`, `emcli_element`, `emcli_placement`, `emcli_slice`, `emcli_spec`, `emcli_step`, `emcli_swimlane`, `emcli_timeline`, `emcli_wireframe`
- `emcli_resolve` — `emcli resolve --queries {{queries}}`
- `emcli_validate` — `emcli validate`

Each group tool has:
- `description`: group purpose + per-verb flag summary (e.g. `add-node (--element --tag [--parent])`)
- `command`: `emcli <group> {{verb}} {{args}}`
- `schema`: `verb` (enum of valid verbs) + `args` (optional string)

**`emcli-mcp.bb`** — Babashka MCP stdio server. Handles `initialize`, `notifications/initialized`, `tools/list`, `tools/call`. Data-driven: reads `tools.json`, performs `{{placeholder}}` substitution on `command` field, runs via `sh -c`. New tools in `tools.json` are automatically available with no shim changes.

**`.mcp.json`** — project-root MCP config for Claude Code auto-discovery:
```json
{"mcpServers": {"emcli": {"command": "bb", "args": ["emcli-mcp.bb"]}}}
```

**Files modified/created:** `src/emcli/cli.clj`, `tools.json`, `emcli-mcp.bb`, `.mcp.json`, `README.md`

**Commits:** `97a1552` (ECA.dev format), `0d7658d` (MCP shim), `81b0908` (add resolve/validate, data-driven shim)  
**Tags:** `v2.1.0` (minor — new features), `v2.1.1` (patch — add resolve/validate)

---

## Current state

- **Latest tag:** `v2.1.1`
- **Latest commit:** `81b0908` on `main`
- **Dirge plugin:** still present at `.dirge/plugins/emcli.janet` — not yet removed; flagged for future removal. Note: the Dirge plugin still calls `element show-wireframe` (stale — should be `wireframe show`) but this will be moot when the plugin is dropped.
- **Open / deferred work:**
  - Drop the `.dirge/` plugin directory
  - HTTP route restructuring to `/authoring/<group>/...` (requires all groups done at once)
  - The `emcli_resolve` and `emcli_validate` tools are in `tools.json` but the Dirge plugin equivalents had richer descriptions (including the `emcli_show_wireframe` Dirge tool description) — worth reviewing once Dirge is dropped
