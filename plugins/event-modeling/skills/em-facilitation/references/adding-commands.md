# Adding commands

Go over each event placed in a state_change slice and which does not have a connection into it from a command:

1. Create a new command, named by turning the event name into the imperative; e.g. "User created" -> "Create user"
2. Add that command to the same slice as the event
3. Add the command to the "Interaction" swimlane
4. Add a connection from the command to the event
