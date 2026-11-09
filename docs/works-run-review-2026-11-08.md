# Works: problems found in the first real runs (2026-11-08)

**Status:** a list of observations for planning fixes. Nothing here is decided or scheduled.
Where something was fixed, it says so.

Sources:

- `old-test-runs/works-trial-piano-fireplace-r8`: a contrived 8-round run of the small
  Claudeworth world, where the earl wanted a grand piano and the countess a new fireplace
  ($0.21).
- `runs/claudeworth-small-20261108-121046`: the user's run of `claudeworth-small`, reviewed
  at round 13 and again at round 27 (21:00 on the first day). It ran on the code from before
  the fixes in section A.

Design: `docs/fixtures-design.md`. Roadmap: B20.

## A. Fixed and merged (not yet seen in a run)

1. **Installs go where the planner stands, and nothing said so.** The earl planned a kitchen
   range while standing in the front yard, so the plan put it there (work-1). He cancelled it
   and planned again in the kitchen. Plans, offers and the `works` view now say where the work
   goes, and `plan-work`'s doc says an install goes in the room where you stand.
2. **Prices agreed in talk weren't given to the tools.** `order-work` offers at the estimate
   unless a price is given. A price agreed in conversation (£17 10s against a £12 estimate)
   would have been lost if the orderer hadn't given it, and the butler held off ordering
   because he thought `order-work` would commit the worker to the estimate. The plan's text and
   the `order-work` and `revise-work` docs now say to give the agreed price. In the run the earl
   gave it himself.
3. **The operator couldn't see works.** The scene view now has a works panel in the toolbar,
   badges on rooms with open works, and works in the place and person inspectors.
4. **Speaking turns offered hiring's own `recommend` and no `send-for`** (items 11 and 14).
   A line's `recommend` only took `position`, so every wrong `recommend {position:
   "estate-carpenter"}` in the run was made on a speaking turn. Speaking turns now take
   `recommend`, `send-for` and the place tools from the scenario's installed actions (trades'
   `recommend` with `trade`), and `send-for` is free, so it's offered there too (sending-for
   phase 0).

## B. Works

4. **The worker isn't told the materials are there.** The design supplies materials as soon
   as the work is paid for, but only `order-work`'s doc says so. After accepting work-2,
   Gilbert Thorne searched the yards, called `find` for "bricks and mortar" ("You don't find
   it"), and kept wondering in his thoughts where the brick and lime would come from.
5. **Building is done with `spend-time` outside works.** Pugh the architect pegged out and
   levelled a chimney breast on the old hall footings and "laid the first course of
   brickwork… square and plumb"; the scene judge accepted it as aspects of the footings. The
   earl and Thorne laid a run of flat stones as a sill at the stable door. No fixed things
   were made this way, only aspects, but the record and the story drift apart and the effort
   doesn't lead anywhere.
6. **The planning judge's prices are inconsistent, and an install's materials leave out the
   thing installed.** The grand piano was estimated at £47 10s with £2 of materials: the
   piano itself cost nothing. Three plans for the same kitchen range came out at £115, £12 and
   £21.
7. **Duplicate plans for the same job.** Plans are private, so Pugh planned work-4, the same
   range as the earl's work-2, and offered it to the same worker. Thorne declined it ("one job,
   one price, charged once"). Here the worker caught it.
8. **One phase of two hours is short for large jobs.** The planning judge noted in its
   thoughts that two hours was "tight" for building a chimney breast and setting a range. As
   designed for now (decision 7); several phases come later.
9. **One worker per work.** Thorne talks of "my mate and the lad from Ely", who don't exist
   in the world. As designed for now; several workers come later.
10. **No `carry-out` or judged outcome on a real model yet.** By round 27 work-2 was accepted
    and paid, but Thorne had chosen to start "at seven tomorrow". Whether the scene judge
    weighs the worker's fitness against the luck sensibly is untested.

## C. Neighbouring mechanics that works brought up

Items 11–14 are taken up by [sending-for-design.md](sending-for-design.md) (roadmap B21).

11. **`recommend` with `position` used when a job was wanted.** Three times, someone asked for
    a joiner to mend the split stair tread, and the person asked recommended an
    estate-carpenter *candidate* (`recommend {position: "estate-carpenter"}`) rather than a
    tradesperson (`trade`). The candidates were announced as seeking a post, arrived "to talk
    about a place" carrying written characters, and were hired: Stanley Rudd and Horace Barker
    as estate carpenter, and Levi Moss as odd man. Gilbert Thorne, found with `trade`, arrived
    correctly as a visitor with no reference and no post. Addressed: one `recommend {wanted}`,
    and whatever was asked, the person sent for is settled at arrival with `engage` or
    `order-work`; see roadmap B21.
12. **The same post filled twice by different employers.** The earl hired Rudd and the
    countess hired Barker, both as estate carpenter, within two hours of each other. By design:
    duplicates are allowed (sending-for-design decision 3); see roadmap B21.
13. **Visitors who stay overnight have nowhere to sleep.** Open work keeps a tradesperson from
    elsewhere from going home, so Thorne stayed. His `sleep` was refused three times ("go to a
    bedroom first"). He used `spend-time` for "sleeping in the lumber room" (a room assigned to
    the cook and the maids) and asked the butler to "put it in the house's book". Fixed: lodging
    (`:lodging true`, the Crown's back chamber), named in `sleep`'s refusal and the rules; see
    roadmap B21.
14. **`send-for` isn't offered on speaking turns.** Pugh and the earl both noted in their
    thoughts that they couldn't send for Thorne during the conversation that settled it, and
    did it on their next turn. It cost only a short delay. Fixed: see A4.
15. **What someone is given in a conversation may not show in their view until later
    (unconfirmed).** The earl was handed Rudd's written character during a conversation and
    thought "my carrying list isn't refreshed"; he also had no way to read it.

## D. Pre-existing, unrelated to works

16. **Judge answers over their length limits.** Several scene and conversation judge answers
    went over a limit (a summary over 300 characters, a feeling over 40) and needed the retry.
    Each costs a call.
17. **The activity check refuses work spanning several rooms.** This is correct behaviour, and
    agents recover from it, but it accounts for many of the run's rejections.
