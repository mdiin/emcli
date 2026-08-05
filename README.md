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

### Auto-approving emcli_author calls

`emcli_author` is a mutating tool and dirge asks for approval on each call by default. To auto-approve it for a project, create a prompt file with an `allow_tools` frontmatter entry. For example, save this as `.dirge/prompts/em.md` in your project (or `~/.config/dirge/prompts/em.md` for global use):

```markdown
---
allow_tools: [emcli_author, emcli_resolve, emcli_validate]
---
```

Then set it as the default prompt for the project by adding `"default_prompt": "em"` to `.dirge/config.json`:

```json
{
  "default_prompt": "em"
}
```

Or switch to it interactively at the start of a session with `/prompt em`.

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
