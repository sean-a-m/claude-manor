# The scene view: a graphical main page for the panel

A plan for a more graphical main page in the control panel: the world drawn as a plan of boxes,
people you can click for their full status, and rooms and items you can inspect. Nothing here is
built yet.

The user's decisions (2026-10-20):

- **Layout is automatic.** Houses will eventually be custom and possibly implausible, so the
  layout must cope with any graph of rooms. There are no hand-placed coordinates.
- **The scene becomes the main page** (`/`). The current panel stays, unchanged, at `/panel`.
- **No round slider** for now.
- **No sim commands** in this plan. The page must leave room for them (section 8), but none are
  designed or built here.

## 1. What exists now

`manor.web` is server-rendered hiccup with a little vanilla JS. The index page re-fetches
`/partial/status` whenever the SSE stream reports an event or a status change. Its map
(`map-svg`) draws each location as a dot with a label. Worlds whose locations have a `:domain` get
one three-column grid per domain, and the others get a circle. Routes are lines (dashed when
closed). People are listed as text under their location.

The data a better picture needs is mostly there:

- Locations carry `:domain`, `:floor`, `:kind`, `:aspects`, `:hours` and `:services`.
  `worldfile/->space` passes every key through. Rooms joined by `:travel 0` routes form an area;
  longer routes are journeys.
- Built rooms arrive as an ordinary `:space/location-added` event (from `building/construction`)
  with `:domain :house`, the plan's `:floor` and `:kind :room`, followed by `:space/route-opened`
  events to their `:connects-to` rooms. Commissioned work waits in `[:state :works]`.
- People: `:location` or `:travel`, `:busy`, `:status`, `:aspects`, `:appearance`, notes, posts,
  bedrooms, routines, purses and usage. `space/last-seen` exists. `(:last-calls run)` holds each
  agent's last model call.
- Items: `items/items-at`, `contents`, `held-by`, `jobs`, `stores-for`, plus `:items-gone`.

## 2. Pages and rendering

- `/` becomes the scene. `/panel` serves today's index page unchanged, with its `?as=` feed
  selector. Each page links to the other in its header, and the agent and world pages' "back"
  links go to `/panel`, where they came from. `web_test` covers both.
- Clojure computes the layout and renders the SVG with hiccup. A small amount of vanilla JS (no
  build step) handles clicks, hovers, pan and zoom, and refreshing on SSE, as the panel does now.
  The scene is small (tens of locations, a dozen people), so re-rendering the whole SVG on each
  update is cheap. If the browser side ever needs much more, Scittle (ClojureScript interpreted
  in the browser, vendored, no build) keeps it in Clojure. This plan doesn't need it.
- Routes:
  - `GET /`: the scene page. It has a toolbar (round, time, run state, cost, and the existing
    Step, Auto and Pause run controls), the map, an inspector sidebar and a compact event feed.
  - `GET /partial/scene`: the SVG alone, re-fetched on SSE events.
  - `GET /partial/inspect/{agent,place,item}/<id>`: server-rendered HTML for the inspector.
- SVG elements carry `data-kind` and `data-id`. One delegated click handler fetches the matching
  inspector fragment. The selection survives refreshes because the JS re-fetches the selected
  fragment after each scene refresh.
- Hover uses SVG `<title>` tooltips (native, free). Styled tooltips can come later.
- Pan and zoom change the SVG `viewBox` (wheel and drag), plus "fit" and one button per region.
- When a human-controlled agent is waiting, a banner on the scene links to the act form on
  `/panel`. Run controls aren't sim commands: they already exist and stay in the toolbar.

## 3. Layout

This is the hard part. It lives in its own pure namespace, `manor.scene-layout`, with no
knowledge of the web, so it can be tested and inspected on its own.

### 3.1 What it has to cope with

Real and plausible houses are easy. The layout must also degrade gracefully for these:

- A hub with many doors: a hall with 8 rooms off it, or a green with 12 shops round it. A 1×1 box
  has only 4 sides.
- Graphs that can't be drawn flat with every connection a shared wall: five rooms that all connect
  to each other, or rings of rooms with cross-links.
- Floors that are unknown keywords (`:mezzanine`, `:tower-top`), rooms with no `:floor` in a domain
  that has floors, and rooms with no domain at all.
- A room connected to rooms on two or three different floors, or to a room in another domain.
- One-way and closed routes.
- Disconnected rooms, long chains of 30 rooms, a few hundred locations.
- Rooms added at any time: by construction, by the GM's `add-location`, or by a future scenario.

The rule throughout: **every room is drawn, nothing overlaps, and every connection is shown**,
either as a door in a shared wall or, when that isn't possible, as a connector line. Plausible
houses get mostly doors. Implausible ones get more lines, but they're still readable.

### 3.2 Structure

The picture has three levels, and the same placement algorithm (3.4) works at the room level.

1. **Regions**: one box per `:domain`, titled. Locations without a domain each form a region with
   their `:travel 0` area (the area's first location names it). Regions are joined by journeys,
   drawn as roads labelled with their time ("30 min"). Closed roads are dashed red.
2. **Levels**: within a region, one panel per floor, side by side. Known floors sort in an
   order: `:basement`, `:ground`, `:first`, `:second`, `:third`, `:attic`. Unknown floors go after
   them in the order they first appear. `:outside` locations are laid out with the ground floor in
   the same panel and drawn as open ground (no walls, a different fill), so the carriage sweep sits
   in front of the hall. A room with no `:floor` in a region that has floors takes its neighbours'
   most common floor. A region without floors has one panel.
3. **Rooms**: rectangles on an integer grid within their panel. One cell is about 90×60 px.

### 3.3 Sizing rooms

A room's size in cells comes from two things:

- **What it is:** a built room's plan `:size` (small 1×1, medium 2×1, large 2×2, grand 3×2), else
  a size by `:kind` (halls, greens and galleries 2×1), else 1×1.
- **How many doors it needs.** A w×h box has 2(w+h) cell edges on its sides. If a room has more
  same-panel neighbours than that, it grows until they fit, keeping its aspect ratio at most 3:1.
  A hub with mostly dead-end neighbours grows long and thin instead (1×n), like a corridor or a
  village street. A hub whose neighbours connect to each other grows square, so they can wrap
  round it.

This one rule makes "hall with eight doors" and "green with twelve shops" look natural, without
special cases.

### 3.4 Placing rooms in a panel: scored search

Rooms are placed one at a time onto the grid. Each candidate position is scored, and a small beam
search keeps the best few partial layouts rather than committing greedily.

**Order.** Start with the room that has the most same-panel connections (ties: a room linked to
another floor or region, since that's an entrance or a stair; then the order the rooms were added).
Then repeatedly take the unplaced room with the most already-placed neighbours ("most constrained
first"), with ties broken by number of connections and then by order added. Rooms with no placed
neighbours start a new cluster.

**Candidates.** Every position where the room's rectangle overlaps nothing and touches at least
one placed neighbour along a wall. A room with no placed neighbour gets positions along the edge
of what's been placed so far.

**Score** (higher is better):

| Term | Weight | Why |
|---|---|---|
| each placed neighbour sharing a wall (at least one cell of overlap) | +10 | a door, the goal |
| each placed neighbour not touching: minus the gap in cells | −2 per cell | keeps connector lines short |
| growth of the panel's bounding box | −1 per cell of new area | compact, house-shaped |
| a cross-floor partner already placed on another floor at the same grid spot | +6 | stacks stairs and stairwells |
| touching a room it doesn't connect to | −1 | a little, so walls mostly mean doors |

**Beam.** Keep the 8 best partial layouts at each step (score so far plus this step), and finish
with the best complete one. For 20–40 rooms with about 100 candidates each, that's tens of
thousands of cheap evaluations: well under a second. It's computed only when locations change.

**Determinism.** No randomness: every tie breaks by a fixed order (score, then position top-left
first, then room order). The same world always gives the same picture.

**Floors in order.** Panels are laid out ground floor first, then going up and down from it, so
the stair bonus lines up each floor's stairwell under the one above.

**Connections that didn't become doors** become connector lines: drawn behind the boxes, from door
marks on each room's wall, routed round boxes where that's simple (an L-shaped path along the grid
gaps), and straight otherwise. Connections to another floor are a stair mark on the room ("↕
First-floor gallery"), and hovering one highlights its partner. Connections to another region are
the start of a road.

**Closed and one-way routes** are still drawn: a closed door is a bar across the gap, and a
one-way door has a small arrow. Both still count as connections for layout, so closing a door
never moves a room.

### 3.5 Keeping the picture stable as the house changes

A house that rearranges itself whenever a room is built would be unreadable, so placement has two
modes.

- **The starting world** (every location added before round 1) gets the full search in 3.4.
- **Each later room** is placed into the frozen layout: the same candidates and score, but
  existing rooms never move. If none of its neighbours has free wall space, the room goes in the
  best free spot nearby and its connections become connector lines. Panels and regions grow to
  fit.

The input is the ordered sequence of `:space/location-added` events from the run's log, so the
layout is a fold over those events. A loaded run looks exactly as it did live. When the fold
reaches a built room, it looks the room's neighbours up in the **current** world's routes, since
the `:space/route-opened` events come just after the room is added.

The web layer caches the layout per run, keyed by the id of the last location-adding event.

One small engine change helps: `building/construction` should put the plan's `:size` on the
location it adds. Older logs without it fall back to 1×1, and hub sizing still applies.

### 3.6 Placing regions

Regions are few, so this is simple. Treat each region as a box of its laid-out size, start with
the one with the most journeys, and place each next region beside the placed region it has the
shortest journey to, on the side facing the fewest boxes. Journeys aren't to scale. The road
labels give the time.

### 3.7 Checking the layout

The layout gets a quality report: the share of connections that became doors, the total length of
connector lines, and the bounding-box area. It's used by tests and by a REPL helper,
`(scene-preview! "path/to/world.edn")`, which writes the SVG to a file for eyeballing. The helper
is how the layout is tuned, and how a strange generated house gets looked at before it's run.

Tests (all free and offline):

- Properties over every bundled scenario and a set of awkward fixtures (a star of 20 rooms, five
  rooms all connected to each other, a ring with chords, a 30-room chain, unknown floors, rooms
  with no floor or domain, a room connected to three floors, one-way and closed routes, a few
  hundred random rooms): every location is drawn, no rectangles overlap, every connection appears
  as a door, a line, a stair mark or a road, and the same input gives the same output.
- For trees whose rooms all fit their doors after sizing (Claudeworth's house is one), every
  connection is a door.
- Stability: adding rooms one at a time never moves a room that was already placed. A mock
  Claudeworth run that builds a room checks that the room appears in its ghost's cell (3.8).
- A speed bound: the few-hundred-room fixture lays out in under a couple of seconds.

### 3.8 Things under construction

Each entry of `[:state :works]` becomes a **ghost box**: dashed, labelled "Morning room — done Tue
16:00", and placed by the incremental rule as if it existed. The finished room takes the same
cell, because the ghost's placement is reused when its `:space/location-added` arrives (keyed by
the work's `:room` id). Construction is Claudeworth's, so ghosts come through the scenario hook in
section 6.

## 4. People

- **Tokens**: a circle with initials, coloured by standing (family, upper servant, servant,
  tradesman, villager...) when the world has positions, otherwise by role. Up to about six tokens
  sit in a room box, and the rest collapse into "+3".
- **Marks** on a token:
  - busy, as an arc for how far through the job they are (from `:busy :until`)
  - asleep (dimmed, with "z")
  - human-controlled (a square instead of a circle)
  - acting now (highlighted, from the run status's `:acting`)
- **Travellers** are drawn on their road, at the fraction of the journey done. The departure round
  comes from the `:agent/departed` event.
- **People who are away or retired** are listed in a strip under the map, not on it.

### 4.1 The person inspector

Clicking a token opens these sections, the long ones folded:

- **Header:** name, position title, standing, status, controller and model, cost so far.
- **Where and what:** location (clickable), current activity and when it ends, travel.
- **Recently:**
  - *Last action:* their last tool calls in their last turn, each with the engine's reply ("move
    to drawing-room: done", "take silver-candlesticks: rejected: ..."). From `:last-calls`, so
    rejected attempts show too.
  - *Dialogue:* the last few things they said, whispered or heard (`:agent/said`,
    `:agent/whispered` and conversation events they could see), narrated as the feed narrates
    them.
  - *Thoughts* (folded): the private reasoning text the model returned with its last reply, before
    it acted. The characters never see it. It's the `thoughts:` line in transcripts, and the most
    direct view of why an agent did something.
- **Appearance and aspects:** their fixed appearance and how they differ from it now.
- **Carrying:** items, with containers expandable and each item clickable.
- **Post, bedroom, routine** (the current entry highlighted) **and purse.**
- **Notes and last seen.**
- **Persona, manner, temperament and circumstances.**
- **Links:** "their view" (`/agent/<id>`) and "feed as them" (`/panel?as=<id>`).

## 5. Rooms and items

- **Room boxes** show the name (clipped, with the full name in the tooltip) and small badges: the
  number of items, a mark if the room has aspects, a hammer for a job in progress, and "closed"
  shading when a shop's `:hours` say it's shut now. Hovering shows the description and aspects.
- **The place inspector** shows:
  - name, kind, floor and domain
  - the description and `:detail`, aspects, opening hours and services
  - who's here (clickable)
  - the items as a tree: containers expanded, closed containers marked "closed", fixed items
    marked, and stores with their `:keeps`, `:stock` and `:lacking`
  - jobs in progress here (who, doing what, until when)
  - exits with travel times
  - items used up here (from `:items-gone`)
- **The item inspector** shows the description, aspects, owner, where it is (place, holder or
  container, each clickable), contents, store details, the round it was last touched, and any job
  on it.
- Items aren't drawn on the map one by one, which would clutter it. The badge and the inspector
  are enough.

Everything reads the world through the existing accessors (`items/items-at`, `items/contents`,
`items/jobs`, `space/present`...), never raw `(:items world)`, as `manor.items` asks.

## 6. Scenario hook

The scene must work for village, treasure and the test scenarios as well as Claudeworth.
Scenario-specific extras come through one optional scenario key:

```clojure
:scene {:ghosts  (fn [world] [{:id "morning-room" :label "..." :floor :ground :connects-to [...]}])
        :badges  (fn [world loc-id] [...])          ; extra badges for a room
        :toolbar (fn [world] hiccup)                ; e.g. household funds
        :inspect (fn [world kind id] hiccup)}        ; extra inspector sections: plans held, purse
```

Claudeworth supplies the works in progress, household funds, plans and purses. Every key is
optional, and a scenario without `:scene` gets the generic view. `docs/scenario-authoring.md`
documents the key when it's built.

## 7. Phases

1. **Layout.** `manor.scene-layout`: sizing, the scored beam search, connector lines, stair marks,
   regions, the incremental mode, the quality report and `scene-preview!`, with the tests in 3.7.
   No web changes yet. This phase stands alone and is where most of the effort and tuning goes.
2. **The scene page.** Move today's index to `/panel` and put the scene at `/`: the toolbar with
   run controls, regions, panels, rooms, doors, lines, roads, people tokens and travellers, native
   tooltips, pan and zoom, and refresh on SSE. `web_test` renders both pages for every bundled
   scenario.
3. **The inspector.** Selection, and the person, place and item fragments, including "Recently".
   Rendering tests against the household fixture.
4. **Change over time.** The `:scene` hook, ghosts for works in progress, `:size` on built rooms,
   closed-shop shading and job progress arcs.

## 8. Room for commands later

Sim commands are out of scope. These choices keep the door open without building anything for
them:

- The page has a **selection** (an agent, a place, an item or nothing), and every inspector
  fragment is built by a function of the run and the selection. A later Operator section can be
  one more function of the same.
- Map elements carry `data-kind` and `data-id`, so a later "pick a target on the map" interaction
  only has to fill a form field.
- Any later command would POST to the existing `/cmd/<name>` route, so it goes through the run's
  executor and lands between rounds (invariant 5). The web layer never changes the world itself.
