# Adding screens

Go over each command which does not have a connection into it from a screen or automation:

1. Create a new screen, named by appending "screen" to the command name; e.g. "Create user" -> "Create user screen"
2. Add the screen to the same slice as the command using `emcli_placement`
3. Add the screen to the "Actor" swimlane using `emcli_element`

This is an initial default, later iterations on the model might see some of these screens turned into automations; but we defer that for later.
