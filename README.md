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
