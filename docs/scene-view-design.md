# The scene view: a graphical main page for the panel

A plan for a more graphical main page in the control panel: the world drawn as a plan of boxes,
people you can click for their full status, and rooms and items you can inspect. The pure layout, offline SVG preview, scene web page, detailed inspectors and temporal adapters are built (phases 1–4);
cross-seam integration and base-behaviour comparison are complete (phase 5); independent acceptance review remains. The implementation contract in section 9 records the approved constraints and phased
handoffs; where it narrows an earlier sketch, section 9 takes precedence.

The user's decisions (2026-10-20):

- **Layout is automatic.** Houses will eventually be custom and possibly implausible, so the
  layout must cope with any graph of rooms. There are no hand-placed coordinates.
- **The scene becomes the main page** (`/`). The current panel stays, unchanged, at `/panel`.
- **No round slider** for now.
- **No sim commands** in this plan. The page must leave room for them (section 8), but none are
  designed or built here.

## 1. What exists now

`manor.tools.web` is server-rendered hiccup with a little vanilla JS. The index page re-fetches
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

**Candidates.** Prefer positions where the room's rectangle overlaps nothing and touches at least
one placed neighbour along a wall. If those candidates are exhausted, use a deterministic free
position near its neighbours, with connector lines instead of doors. A room with no placed
neighbour gets positions along the edge of what's been placed so far. Candidate enumeration must
be finite and bounded; every room must still get a position.

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

The input is the ordered event history, projected into display-only topology and reservation
records. Initial layout uses the starting graph; later placement uses only information available
at its specified historical placement boundary, never the current graph to reinterpret the past.
Later routes update connection rendering without moving frozen room geometry. Construction
reservations carry their geometry forward to completion, including on a fresh load. Generic later
rooms without initial connection metadata can use free placement and acquire connector lines.

The web layer must distinguish cached placement from current route styling and connections.
Invalidation includes relevant topology, reservation and completion changes, not only the last
location-added id. Layout must be a deterministic function of the event prefix, independent of
whether a browser was open, and of cache hits or eviction.

No engine change is permitted for this addition. Built-room sizes are derived in the presentation
layer from existing plan and completion records; locations with no size evidence use the generic
kind/default sizing. The simulation's location events remain unchanged.

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
- Door coverage is a measured quality target, not a universal guarantee of the beam search.
  Representative household fixtures should get good door coverage; every missed door must still
  have a visible connector.
- Stability: adding rooms one at a time never moves a room that was already placed. A mock
  Claudeworth run that builds a room checks that the room appears in its ghost's cell (3.8).
- A speed bound: the few-hundred-room fixture lays out in under a couple of seconds.

### 3.8 Things under construction

Commissioning becomes a **ghost box**: dashed, labelled "Morning room — done Tue 16:00", and
placed by the incremental rule as if it existed. The presentation adapter captures the plan's
floor, size and connections from existing evidence at commissioning, keyed by the work's `:room`
id (not the plan id). The finished room takes the same cell at its `:space/location-added` event.
The adapter folds the entire event prefix, including completed works, so a fresh load or missed
refresh reconstructs the same geometry. It never relies only on current `[:state :works]`.
Between the completion and location events the reservation remains visible; later route events
change connectors without relocating it. Construction is Claudeworth's, via the history hook below.

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
  - *Requested tools and recorded replies:* actual calls in their latest recorded round, grouped
    by request continuation and conversation context, including rejected attempts. Read from existing
    `llm.edn`, not the ephemeral `:last-calls`. Separate narrated event outcomes show later resolution;
    a recorded "done" alone does not establish that a simultaneous action succeeded. Logs have no
    persistent turn id, so separate roots are not falsely collapsed into one turn.
  - *Dialogue:* the last few things they said, whispered or heard (`:agent/said`,
    `:agent/whispered` and conversation events they could see), narrated as the feed narrates
    them.
  - *Thoughts* (folded per call): only the private reasoning text actually returned with each
    recorded reply. The characters never see it. It's the `thoughts:` line in transcripts, and the most
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
`items/jobs`, `space/present`...), never raw `(:items world)`, as `manor.mechanics.items` asks.

## 6. Scenario hook

The scene must work for village, treasure and the test scenarios as well as Claudeworth.
Scenario-specific extras come through one optional scenario key:

```clojure
:scene {:history (fn [events] {event-index {:reservation normalized-room}
                              location-event-index {:location {:size :large}}})
        :badges  (fn [world loc-id] ["extra badge"])
        :toolbar (fn [world] hiccup)
        :inspect (fn [world kind id] hiccup)}
```

Every key is optional. These callbacks are pure operator presentation only; a scenario without
`:scene` gets the generic view. `:history` replaces the original world-only `:ghosts` sketch:
current works alone cannot reconstruct a completed reservation after load. Its event-indexed
annotations are display data, not simulation events. Full contracts and limitations are in
[scenario-authoring.md](scenario-authoring.md#scene-operator-presentation-only) and
[scene-web-api.md](scene-web-api.md#temporal-seam-phase-4).

Claudeworth supplies replayable works, household funds, held plans, sterling purses and adjoining
works badges via `manor.tools.scene-claudeworth/hooks`; its derived scenarios inherit the hook.
Closed-hours shading and evidence-based busy arcs are generic presentation features.

## 7. Phases

1. **Layout — implemented.** `manor.scene-layout`: sizing, bounded scored beam search, separate
   directed connection projection, regions/panels, frozen incremental geometry, generic reservation
   boundaries and quality metrics. `manor.tools.scene-preview/scene-preview!` (also in the dev REPL)
   writes an offline SVG. Awkward graphs, bundled scenarios and 320 rooms are tested. No web or
   simulation changes. See [scene-layout-api.md](scene-layout-api.md) for the normalized contract,
   examples, search caps, floor rules and limits. Phase 4 supplies Claudeworth history extraction.
2. **The scene page — implemented.** Today's panel remains at `/panel`, with its feed filtering;
   `/` has coherent toolbar/map/feed refresh, directed connections, tokens and travellers, native
   tooltips, navigation, pan/zoom, and pending selection. `scene_web_test` covers both pages for
   every bundled scenario, live snapshots and loaded runs. Actual Chromium client checks cover
   controls/SSE, pointer taps/drags, viewport/selection preservation and stale-response guards.
   See [scene-web-api.md](scene-web-api.md) for the seam, runnable checks and manual visual checks.
   Phase 4 subsequently adds construction adapters and evidence-based progress arcs.
3. **The inspector — implemented.** Escaped person/place/item fragments, linked selection, current
   private inner state, carrying/containers/stores/jobs and recorded item history. Recently reads
   durable calls on live and loaded runs, preserves rejected attempts and conversation contexts,
   and separates requested tools from event outcomes. Selected inspector/map/feed refresh from one
   snapshot, with stale-response guards. Household, generic, loaded-run, privacy and actual Chromium
   interaction checks pass. The projection/helper API and evidence limits are in
   [scene-web-api.md](scene-web-api.md).
4. **Change over time — implemented.** The presentation-only `:scene` hook, replayable ghosts for works in
   progress, built-room sizes derived from existing plans, closed-shop shading and job progress
   arcs. Event-indexed reservations capture immutable commissioning-time plans and survive missed
   refreshes, load and cache eviction. Completion-only evidence sizes later rooms without inventing
   past ghosts; missing/custom size evidence uses generic sizing. Indefinite hibernation has only a
   status mark. Local room geometry stays frozen across route changes; outer packing may shift.
   No simulation events or state are changed. Focused temporal and mock construction tests cover
   these invariants. Independent review and manual visual checks remain.
5. **Integration — checked, independent review pending.** Cross-seam tests render both pages,
   inspectors (including construction ghosts), coherent refreshes and previews at every compared
   mock round boundary without changing world/events, persisted files or making model calls.
   The isolated archived-base harness compares all seven bundled scenarios plus the fixed
   household's construction through completion against `0f7eae9`, with hooks present/removed.
   All world/event prefixes, agent-visible events/views, durable calls and complete model requests
   match. Only call-envelope wall-clock `:at` is excluded; existing concurrent work-judge dispatch
   order is normalized within an identical pre-batch world, retaining all payloads and job-local
   ordering. Actual Chromium checks cover spatial and no-space scenarios and both pages.
   See [integration evidence and exact commands](scene-web-api.md#integration-and-simulation-boundary-checks-phase-5).
   No simulation source changes or component redesign were necessary. Manual visual/touch/
   cross-browser acceptance and phase 6's independent review are not claimed complete.

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

## 9. Approved implementation contract and ownership

The operator approved implementation on the condition that **nothing changes what happens in the
simulation or what agents see**. This is a presentation-only feature. Do not change engine,
mechanics, reducers, event payloads, world initialization, action definitions, model requests,
agent views or prompts, bridge behaviour, scheduling or persistence formats. Reading existing
records and adding pure presentation projections is allowed. A scenario may gain only optional
`:scene` callbacks consumed by the web layer; its existing runtime definitions stay unchanged.
No model calls are needed for rendering or validation.

Implementation is multi-seam, delivered serially on `feature/scene-view` in
`~/development/git-worktrees/claude-manor/scene-view/`. Each writer finishes and commits its
handoff before the next writer starts. The main checkout and port 8080 are not used.

| Phase / owner | Exclusive delivery seam | Next gate / durable handoff |
|---|---|---|
| 1 / layout writer | Pure layout/history projection, awkward-graph tests, preview helper; design amendments | Deterministic, complete, non-overlapping layouts; history/cache-independent placement; committed API and examples |
| 2 / scene writer | Web routing, scene SVG/page and browser interactions; existing panel retained | Both pages render across bundled scenarios; controls, SSE, viewport and selection checks; committed web seam |
| 3 / inspector writer | Read-only person/place/item projections and fragments; existing call-log reading | Loaded/live inspectors, missing entities, multi-call turns and privacy boundaries; committed inspector seam |
| 4 / change-over-time writer | Display-only scenario hooks/adapters, reservation history, ghosts, size evidence and progress | Ghost-to-room geometry survives load and cache eviction; route changes do not relocate rooms; committed temporal seam |
| 5 / integration owner | Compatibility verification, cross-seam tests and final user documentation only | Full offline suite and exact-head evidence that simulation and agent-facing behaviour are unchanged |
| 6 / independent reviewer | Read-only review of the complete branch, especially the presentation-only boundary | Evidence-backed findings and merge recommendation for parent acceptance; no merge/push |

### Decisions to make explicit in code and tests

- Visual grouping uses undirected zero-travel topology regardless of open/closed state, not
  `space/area`'s directed open-route reachability. Explicit domains are authoritative; domainless
  components do not absorb explicitly domained rooms. Known floors use the listed order; unknown
  floors and ties use stable event/id ordering. Missing-floor inference is deterministic and
  does not drift when routes subsequently close or open.
- Preserve room geometry within its panel. Region/panel packing may shift outer boxes when they
  grow to prevent overlap; document this distinction from local placement stability. Browser
  viewport and selection survive refresh, with selected-region anchoring where practical.
- Specify a normalized pure layout input/output contract in phase 1. Include display-only
  reservations with stable ids, domain, floor, size and connections. Phase 4 supplies historical
  Claudeworth reservations by reading existing commissioning, plan and completion records. A
  loaded run must reconstruct reservations that have already completed, not just current works.
- Placement must not depend on a browser observing intermediate events. Read a coherent event
  prefix and derive matching world data without modifying the engine. Keep current connection
  rendering separate from historical placement; preserve direction and asymmetric route values.
- `:last-calls` is not a persistent turn summary and is empty after load. Derive Recent sections
  from existing `llm.edn` and event records, grouping actual calls/replies without claiming that
  a requested simultaneous action necessarily succeeded. Do not add logging or change turns.
  Display only reasoning actually returned by the model. Missing logs are an ordinary empty
  display case, not a reason to make model calls.
- Busy progress needs a start and end: use existing job `:started`/`:due` or occupation events;
  indefinite hibernation gets a status mark, not a fabricated percentage. Show current private
  inner state in the operator inspector as well as appearance/aspects. The scene and inspectors
  are explicitly omniscient operator surfaces; `/panel?as=` retains its existing feed semantics
  and `/agent/<id>` retains the unchanged agent-view projection.
- Refresh toolbar, SVG, selected inspector and compact feed coherently. Preserve viewBox and
  selection, suppress click-after-drag, discard out-of-order selection/refresh responses, encode
  ids safely, and handle deleted/retired/missing selections without errors. Keep rendering escaped.
- Tests include no-space scenarios, closed/one-way/asymmetric routes, route additions after room
  creation, missing-floor ties, crowded rooms, incomplete records, loaded runs, and ghost
  completion. Browser-interaction checks should exercise actual client code, using available
  local tooling without installing a new frontend stack. Record any unautomated visual checks.
- Compare a deterministic mock run's events/world/agent-facing projections with the base branch
  under the same options, excluding run identity and genuinely nondeterministic accounting data
  only where necessary and documented. Never hide simulation differences behind broad exclusions.

Run focused free/offline checks during each phase and `clojure -M:test` before each commit.
No real-model runs, no pushes, no merges and no new dependencies without parent approval.
Update this design and the scenario-authoring contract to match what is actually delivered;
record any deferred visual polish honestly rather than silently reducing the scope.
