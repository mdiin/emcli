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

1. **Discover tags** using `emcli_wireframe` with the `tags` verb: without args it lists every tag and its purpose. Before adding a node, run `tags` with args `--tag <name>` to get that tag's attributes and an example command. Never guess tags or attributes.
2. **Resolve wireframe node IDs** using `emcli_wireframe` with the `show` verb before any authoring call that needs a node id. Never guess node ids.
3. **Author** with the `emcli_wireframe` tool — one call per command. Supply `verb` + any required `args` as space-separated `--flag value` pairs.


## Notes

- All wireframe node id arguments come from `emcli_wireframe` with the `show` verb, which prints the screen's whole layout tree with each node's `-id`. Every wireframe authoring command returns the **screen element**, not the node, so read the new node id from a `show` — the element's own `id` is the element's integer id and is NOT a node id.
- A tool result on success is the modified screen element. On error it returns an `Error:` prefixed string with the server's message.

## Gotchas

- The tool's error message will always tell you exactly the flag names to use; do not modify them.
- An error ending in `(see: emcli wireframe tags ...)` means the tag or attribute does not exist: run the `tags` verb with the args it names, then retry.
- The full tag reference, for when you need every tag at once, is `${plugin:root}/skills/emcli-wireframing/references/wireframing.md`.
