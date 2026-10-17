# Room conditions: build brief

**Status: not started.** R3 needs the user's decision before anyone builds it.

Self-contained instructions for giving **rooms (locations) a condition** that `work-on` can
change, as items have. Read `AGENTS.md` first (invariants, run directories, cost rules), then
"Work and the judge" in `docs/scenario-authoring.md` and `src/manor/items.clj`.

## Why

In a real run (`old-test-runs/claudeworth-20261017-002138`), Nellie the housemaid tried
`work-on ["drawing-room" "piano" "china-cabinet"]` and `work-on ["morning-room"]` to dust and
sweep rooms. Commit `fcf51d1` stopped those calls being rejected:

- `work-on` accepts the id of the worker's own place among its `items` (`work-reason`).
- The id stays in the job's `:items`; `items/place-worked?` says whether a job named its place.
- `in-reach` ignores it, so only items go to the judge.
- A job with only the room (nothing in reach, `place-worked?`) finishes with `:item/work-done`,
  no changes, a neutral summary and **no judge call** (`finish-job!` in `engine.clj`).

So sweeping a room is accepted but leaves no trace in the world. This brief adds that trace.

## Scope

### R1 Engine: a condition on locations, changed by work

1. **Data.** A location may have an optional `:condition`, a phrase of 1-80 characters
   (`items/max-condition`), e.g. `"dusty, the hearth unswept"`. Locations are open maps in
   `:space :locations`, so nothing stops the key today. Add the check to the world-file checks
   (`worldfile.clj`, the `Location` schema or its checks) and mention it in the Locations (`manor.space`) section
   of `docs/scenario-authoring.md`. `worldfile/export` already copies whole location maps, so
   the live map keeps conditions; check the round trip in a test.
2. **The judge sees the room.** When `place-worked?` is true, the room goes to the judge as one
   more thing, with its id, name, `desc` and `condition` (left out if it has none), and something
   marking it as the room, e.g. `"room": true`. A suggested shape: a function giving the "things
   judged" (items in reach plus the room entry), used by `judge-request`, `verdict` (whose
   `by-id` decides which ids may change) and the mock judge's ctx. Keep `in-reach` as items only:
   the container code and its tests depend on it. Add a line to `judge-instructions` saying that
   the room may be listed and that its condition describes the room as a whole (floor, hearth,
   air), not the things in it.
3. **The room-only shortcut goes.** In `finish-job!`, a room-only job is now judged like any
   other. Nothing in reach and no room still ends with `:nothing-in-reach`.
4. **Event and reducer.** The judge's tool keeps one `item` key for every change (it shouldn't
   need to know the difference). In the checked verdict, a change to the job's place is recorded
   as `{:place id :from :to}` and an item's change as `{:item id :from :to}`, as now. The
   `:item/work-done` reducer writes `[:space :locations id :condition]` for `:place` changes.
   Old `events.edn` files have only `:item` changes and must still replay.
5. **Narration.** `n/describe :item/work-done` names a room change by `[:loc id]` from `n/names`:
   "(Drawing room: swept, the hearth clean; Piano: dusted)".
6. **Views.** `look` shows the room's condition in its `:place` block, next to `:detail`. Leave
   the per-turn `here` view (`space.clj`, `[:id :name :desc]`) alone unless the user asks, which
   matches items: conditions only through `look`.
7. **Mock judge.** `items/mock-judge` gives the room "just seen to" too, like every item.
8. **Tests** (in `test/manor/items_test.clj`, hamlet world, beside `work-on-the-room`): a room
   with a condition worked on alone and with items, the judge's request containing the room,
   a verdict changing it, a judge answer naming another room refused, narration, `look`, replay
   equal to the live world, an old-shaped `:item/work-done` still replaying, a world-file
   condition too long refused, and the export round trip. Replace the existing room-only test,
   which expects no judge call.
9. **Docs.** Update "Work and the judge" in `docs/scenario-authoring.md` (written for the
   commit above) and the B7 entry in `docs/roadmap.md`.

### R2 Claudeworth: starting conditions

- Give each room in `resources/worlds/claudeworth.edn` a short period condition (the drawing
  room dusty, the kitchen range unlit, and so on). Keep `test/fixtures/household*.edn`
  unchanged unless a test needs them.
- New rooms from construction (`building.clj`, the `:space/location-added` in `construction`)
  start as e.g. `"new, smelling of plaster and paint"`.
- Nellie's mock day already works on `entrance-hall`; check the mock day's room condition changes
  and still has no `error:`/`rejected:` replies (`claudeworth_test.clj`).
- Say in the rules text (`claudeworth/rules`) that `look` shows how the room itself is kept.

### R3 Rooms getting dirty again: needs the user's decision

Without this, a room swept once stays clean for the rest of the run, which makes R1 mostly
flavour. Items have the same gap (roadmap B7: "a GM job later, not automatic decay yet"). Options
to put to the user, not to choose alone:

- **A morning reset** in Claudeworth: a system (`:systems`, like `construction`) that at a set
  hour gives every room its "needs doing" condition again. Simple and predictable.
- **Use makes mess:** spend-time activities in a room (dining, smoking) set a condition. More
  natural, more code, and needs a phrase per activity or a judge call.
- **The GM** changes conditions when asked or triggered. Fits "a GM job later", but needs GM
  tools to alter conditions, which don't exist yet (B7 follow-up "GM tools to create or alter items").

Whatever is chosen, condition changes must be events (invariant 1), with pure reducers.

### R4 One short real run (only with the user's go-ahead)

Claudeworth with Nellie, starting at a morning hour (`--start`), at most 10 rounds and
`:max-cost` 0.20. Look for: rooms named in `work-on`, sensible room conditions from the judge,
no new `error:`/`rejected:` replies, and whether `look` calls go up. Move the run to
`old-test-runs/` and report its cost.

## Not in scope

- Conditions on outdoor or village locations being treated differently: any location may have
  one; the scenario decides which do.
- Showing conditions on the panel's map.
- The separate issue from the same run where Nellie lost track of which room she was in.

## Done when

`clojure -M:test` passes, R1 and R2 are committed, the docs above are updated, this brief's status
is updated, and it moves to `docs/archived/` once its work is committed.
