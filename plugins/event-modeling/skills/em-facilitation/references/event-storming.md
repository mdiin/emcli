# Event Storming

## 1 - Identify core events
Goal of this step: Identify the core events of the system

In collaboration with USER, figure out what the core events of the system are. Based on the domain, you might be able to infer some; suggest adding those with `eca__ask_user`.

Events are always in past tense, e.g. "User added", "Cart submitted", "Order processed".

**Do**:
- Stay in this part of the event storming session until USER is ready to move on.
- Create events as USER provides them
- Add created events to the "Event" swimlane as they are created

## 2 - Identify event groupings
Goal of this step: Identify logical groupings, timelines, of the created events

In collaboration with USER, determine timelines for the events. Each timeline is a full part of the narrative of the system and covers one "feature". Example: User authentication

If you have suggestions for timelines, present your suggestions one at a time using `eca__ask_user`.

Create the identified timelines.

## 3 - Add events to timelines
Goal of this step: Add the events to the relevant timelines

To add an event to a timeline:

1. Identify the timeline on which to add the event
2. Add a new "state_change" slice to that timeline, name it after the event by turning the event name to an imperative, e.g. "User added" -> "Add user"
3. Place the event in the slice

**Indexes do not have to be continuous**: Use this to your advantage by when creating slices. Add a number of unused indices between the last slice and the new one; e.g. the last slice in the timeline has index 10, add the new slice with index 20. This leaves room to reorder slices with fewer operations later.
