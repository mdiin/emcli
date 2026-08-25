# Specifications

Given-When-Then specifications are essential to Event Modeling, as they encode the business rules.

Use the `emcli_spec` tool to create and modify specs.

Examples are vital, they make the spec concrete and testable. If you cannot come up with good examples, ask USER questions using `eca__ask_user` to gain the knowledge to do so.

Good specs show how the slice behaves in corner cases of the business rules. This is not easy to guess, so unless you are absolutely certain of the rule a new spec should encode, ask USER using `eca__ask_user`.
