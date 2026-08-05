## Install

```
bbin install io.github.mdiin/emcli
```

Requires [bbin](https://github.com/babashka/bbin). Or grab a prebuilt binary from [Releases](https://github.com/mdiin/emcli/releases).

## dirge plugin

A [dirge](https://github.com/dirge-code/dirge) plugin is included at `.dirge/plugins/emcli.janet`. It registers three LLM-visible tools (`emcli_resolve`, `emcli_validate`, `emcli_author`) and a `/em-validate` slash command.

To use it in another project, copy the plugin and skill into place:

```
cp /path/to/emcli/.dirge/plugins/emcli.janet ~/.config/dirge/plugins/
cp -r /path/to/emcli/.dirge/skills/emcli-authoring ~/.config/dirge/skills/
```

Or, to keep it project-local, copy both into the project's `.dirge/` directory.

### Auto-approving emcli tool calls

All three plugin tools default to `ask` in dirge's standard permission mode. The reliable way to auto-approve them is a two-step setup:

**Step 1.** Save the following as `~/.config/dirge/prompts/em.md` (or `.dirge/prompts/em.md` for project-local use). It denies all built-in tools so the model only sees the emcli tools, and sets the system prompt for Event Modeling mode:

```markdown
---
deny_tools: [bash, bash_output, kill_shell, read, read_minified, write, edit, edit_lines, edit_minified, apply_patch, grep, find_files, glob, list_dir, repo_overview, lsp, question, webfetch, websearch, task, task_status, memory, skill, list_symbols, get_symbol_body, find_definition, find_callers, find_callees, mcp_tool, write_todo_list, tool_search, session_search, plan_enter, plan_exit, debug, spec]
description: Event Modeling mode — only emcli_author, emcli_resolve, emcli_validate available
---
## Event Modeling Mode

You are in **Event Modeling mode**. Load the `event-model` skill at the start of the session.

Your only tools are `emcli_author`, `emcli_resolve`, and `emcli_validate`. Use them to build and maintain the Event Model.
```

**Step 2.** Start a session with the prompt active, then run `/allow add plugin_tool *` once to grant the session allowlist entry for all plugin tools:

```
dirge --prompt em
/allow add plugin_tool *
```

The `/allow add` grant persists for the session. The `deny_tools` list is enforced at the permission layer even under `--yolo`, so built-in tools are hard-blocked regardless of mode.

> **Why not `allow_tools`?** `allow_tools` in prompt frontmatter only caps built-in tools — plugin tools are not in that list, so they remain callable regardless. Permission rules in `config.json` also cannot target plugin tools. The `deny_tools` + `/allow add plugin_tool *` approach is the only confirmed working solution as of dirge 0.21.5.

## Why?

I don't want Event Modeling to be tied to a visual tool or web platform. This project is one piece of that puzzle, allowing textual input of event model changes. With this in place, other interfaces can be built on top, such as:

- Voice-based UI where the user talks to an LLM, and the LLM calls this CLI
- Text-based LLM UI
- Human-based CLI workflows
- Shell automations

The other pieces, which are separate projects, are:

- UI to view Event Models exposed by the SSE endpoint of this CLI
- LLM skills to use this CLI for building Event Models
- LLM skills to use this CLI for implementing Event Sourced systems based on Event Models
