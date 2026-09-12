---
description: Add wireframes to screen elements
mode: subagent
systemPrompt: ${file:../prompts/wireframer.md}
spawnableBy:
  - event-modeler
disabledTools:
  - eca__grep
  - eca__directory_tree
  - eca__editor_references
  - eca__editor_definition
  - eca__preview_file_change
  - eca__write_file
  - eca__edit_file
  - eca__move_file
  - eca__shell_command
  - eca__git
  - eca__view_image
  - eca__task
  - eca__spawn_agent
  - eca__ask_user
  - eca__bg_job
  - emcli_timeline
  - emcli_swimlane
  - emcli_slice
  - emcli_element
  - emcli_connection
  - emcli_spec
  - emcli_step
  - emcli_validate
tools:
  byDefault: allow
---
