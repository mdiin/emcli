---
name: emcli-wireframing
description: >
  Use this skill when you need to edit wireframes on Event Model screen elements. Covers the complete command
  reference for wireframing with guidelines and gotchas.
---

# emcli Wireframing Reference

Wireframes are build incrementally on `screen` elements.

Wireframe nodes have stable ID's (e.g. `"n1"`, `"n2"`, ...).

## Working rhythm

1. **Read** reference document `${plugin:root}/skills/emcli-wireframing/references/wireframing.md`.
1. **Resolve wireframe node IDs** using `emcli_wireframe` with the `show` verb before any authoring call that needs a node id. Never guess node ids.
2. **Author** with the `emcli_wireframe` tool — one call per command. Supply `verb` + any required `args` as space-separated `--flag value` pairs.


## Notes

- All wireframe node id arguments come from `emcli_wireframe` with the `show` verb or from the result of a prior authoring call. Every wireframe authoring command returns the created/modified node, so you can read the node id directly from that result — no need to call `emcli_wireframe` with `show` verb immediately after creating something.
- A tool result on success is the created/modified node. On error it returns an `Error:` prefixed string with the server's message.

## Gotchas

- The tool's error message will always tell you exactly the flag names to use; do not modify them.
