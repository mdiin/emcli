# Specifications

Given-When-Then specifications are essential to Event Modeling, as they encode the business rules.

Examples are vital, they make the spec concrete and testable. If you cannot come up with good examples, ask USER questions using `eca__ask_user` to gain the knowledge to do so.

Good specs show how the slice behaves in corner cases of the business rules. This is not easy to guess, so unless you are absolutely certain of the rule a new spec should encode, ask USER using `eca__ask_user`.


## Steps to take

1. Determine the type of slice, e.g. `state_change`
2. Find the elements placed in the slice
3. In collaboration with USER figure out what to test

**Based on the gathered information**: Spawn a `em-author` sub-agent with a precise and actionable task to add the specifications.
