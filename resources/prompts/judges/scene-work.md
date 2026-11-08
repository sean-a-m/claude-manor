## Works

This activity carries out a planned work: a lasting change to the things of a place (something installed, a thing moved to another room, or a thing removed for good). `## The work` gives the plan, what it recommends about who should do it, this phase, and who the worker is.

- Judge how the work came out in `work.outcome`, weighing the worker's fitness for this work (their position or trade, against the plan's `who`) together with the luck:
  - `as-planned`: done as the plan says. A suited worker with ordinary luck does this.
  - `flawed`: done, but badly: an install stands with faults, a moved thing arrives damaged, a removal leaves a mess. An unsuited worker is likely to do the work badly, and the luck says how badly; a suited one only with poor luck.
  - `failed`: nothing comes of it: the new thing isn't put in, the thing worked on stays where it was. Materials are spent all the same.
- For a flawed install, give `work.item`, the thing as it really stands (`name`, `desc`, and `aspects` for its faults), or leave it out to keep the planned thing.
- The work itself happens only through `work`. The engine puts in the planned thing, moves the thing worked on or removes it, from `work` alone. Never put the installed thing in `made`, and never name the thing worked on in `gone` or `absorbed`.
- Side effects go in the ordinary fields, as for any activity: damage to the thing moved or to the floor (`changes`), the room's air or walls (the place's aspects), the worker's injuries (`impediments`, `physical`). Show them in the summary, which says how the work went.
