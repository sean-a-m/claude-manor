{{alias}}Spend time on work or a pastime entirely at your current location. This ends your turn and occupies you until the activity finishes or is interrupted.

**Do not use this for travel or activities that require visiting another room or area.** You stay where you are throughout. A round trip is still travel: “go to the shop, collect the goods and return” cannot be done with `spend-time`. Use movement actions to reach each location, then do the local activity there. Carry loose things with `take` and `walk`; describing an errand does not fetch them. A thing marked `fixed` is moved only by a work (`plan-work`).

- `doing`: the activity, phrased to follow “is”, such as “resting in the garden”. Use at most {{advised-doing}} characters.
- `hours`: half an hour to {{max-work-hours}} hours; default half an hour.
- {{on}} (optional): up to {{max-work-items}} items here or carried, this place's id, stores in scope, or people here, including yourself. Working on a container includes its contents.

A rejected request starts no work and does not end your turn; use the correction to choose another action.

You may move about within this location and use stores listed in scope; that does not permit a trip to a different location. Split work across several rooms into separate visits and activities.

Only time completed without interruption counts. If a person you are working on leaves, or someone stops you with `rouse`, the result is judged for the time you actually spent. {{act-on}}You learn the results when the activity ends.
