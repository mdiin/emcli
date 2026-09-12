# Wireframing

Wireframes in event modeling must be very simple, serving only to illustrate the use of data from read models and the triggers of commands. A guideline is that it should take no longer than 2 minutes for a human to draw the wireframe by hand; this obviously does not make sense for you as an agent, but use it to understand that simplicity trumps detail.

## Steps to take

1. Ask user what should be visible
2. Ask user which fields must map to which parts of the wireframe, if any
3. Ask user which parts of the wireframe trigger an action, if any

**Based on the gathered information**: Spawn a `wireframer` sub-agent with a precise and actionable task.
