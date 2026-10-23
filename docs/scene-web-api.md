# Scene web seam (phase 2)

Presentation only. No engine, mechanics, prompts, actions, events, persistence or scenario runtime
code changes. `/` is the omniscient operator scene; `/panel` retains the old control panel and
`?as=` feed filtering. Agent/world back links lead to `/panel`. The scene's `?as=` is intentionally
not an agent-view mode. `/agent/<id>` still uses the existing agent-view projection.

## Routes and rendering

- `GET /`: scene toolbar, SVG, pending-selection sidebar, away/retired strip and compact feed.
- `GET /panel`, `/partial/status`, `/stream`, `/agent/<id>`, `/world`, `POST /cmd/*`: existing
  behaviour. Panel SSE feed is now bounded to the same 300 entries as its initial render.
- `GET /partial/scene`: escaped SVG only (`image/svg+xml`).
- `GET /partial/scene-state`: JSON of escaped HTML strings with keys `scene`, `toolbar`,
  `navigation`, `away`, `feed`. All five come from **one snapshot**, not five independent fetches.
  The scene feed contains at most 70 newest events, newest first, including operator-only events.
- `GET /static/scene.{js,css}`: fixed classpath assets; no arbitrary file path serving.

`manor.tools.scene` is the generic, read-only projection/render module:

- `snapshot run` captures `:world`, matching immutable `:events`, `:status`, `:input`, `:id`,
  `:scenario` under the engine's existing world/emit lock, then releases it before rendering.
  This does not submit a command or pause the run. Status/input are advisory atom values sampled
  at that instant, not event-sourced history. Inspectors should accept this same snapshot.
- `topology world` returns normalized-API-compatible ordered locations and directed routes.
- `placement-history events` builds generic historical layout boundaries using only existing
  space events. Initial room order is first location-event order (including unknown-floor ties);
  current metadata/routes sort by stable id. All pre-round-1 topology is initial; each later location freezes at its own event,
  with the routes known **then**, not routes appearing later in the prefix. A room added before
  its routes can get a connector rather than a shared-wall door. No construction adapters yet.
- `presentation snapshot [cache]` calls `place-history` only when historical placement input
  changes, then always calls `present` with current routes. Optional cache is a disposable,
  single-entry atom; cold/load results equal warm results. Web owns one bounded entry, resets it
  on `show!`, and never persists it. Cache races affect work only, not output.
- `svg snapshot result`, `toolbar snapshot`, `navigation result`, `away-strip snapshot result`
  return hiccup. `web/scene-fragments` composes these with the existing event renderer.

Phase 4 can enrich `placement-history` with then-known reservations/size evidence without changing
this read/render contract. It must not pass the current graph into old placement boundaries.
Room geometry is frozen **within panels**; outer region/panel packing may shift. Native SVG titles
include full labels/descriptions/aspects and route direction/time/state. Opposite directions have
separate narrow lanes; shared-wall door arrows are extended to remain visible. Busy tokens have a
status ring, not a fabricated progress percentage. Sleep, human and acting marks are supported.
Rooms show six tokens; crowded rooms reserve the sixth slot for a selectable `+N` overflow with
all remaining names in its native tooltip.
Traveller fractions use the latest existing departure event and the current travel's arrival;
missing timing means the start of the route, not an invented departure time. An incomplete
traveller with no drawable source/route appears in the strip as location unavailable. Construction sizes,
ghosts, shop closure shading and job progress arcs remain phase 4. No `:scene` callbacks yet.

## Client and inspector handoff

`resources/manor/scene.js` is vanilla JS, loaded by a fixed URL. All scene entities use escaped
`data-kind`/`data-id` attributes (currently `agent` and `place`; inspector links may also use `item`).
A delegated handler compares dataset strings, never interpolates ids into CSS selectors or code.
Pointer taps and Enter/Space select; Escape clears. Dragging suppresses the following map click.
Native SVG tooltips are used on hover; stair connections also highlight their two rooms.
Wheel zoom uses SVG screen transforms; drag pans the
viewBox. Fit, zoom and region buttons are delegated. Navigation is keyboard accessible.

- `window.ManorScene.selection`: copied `{kind, id}` or null, available even without inspectors.
- `ManorScene.select({kind, id})` / `select(null)`: pending selection, highlight, sidebar refresh.
- `ManorScene.refresh()`: debounced coherent scene/toolbar/navigation/away/feed refresh.
- `ManorScene.refreshSelection()`: refresh selected sidebar only.
- `#scene-page` emits `scene:selection` with `{kind, id}` on selection/selection refresh.
- Phase 3 can set `data-inspect-base="/partial/inspect/"` on `#scene-page` to activate the already
  guarded fragment fetch: `base + encodeURIComponent(kind) + '/' + encodeURIComponent(id)`.
  No inspector endpoints are implemented in phase 2. With no base, the sidebar shows selection
  via `textContent` and makes **no** inspector request. A non-OK inspector reply shows a neutral
  missing-selection message while retaining the pending id. Inspector fragments can contain
  `data-kind`/`data-id` links and use the same delegated selection handler.

SSE `ev` and `status` invalidate prior requests immediately. AbortControllers plus monotonically
increasing generations reject stale refresh/selection responses even if abort is ignored. A
selection change also invalidates an earlier inspector request. The latest viewBox is read when a
refresh commits (pans during fetch are retained), and selection is re-highlighted/re-fetched. The
selected region's origin shift is added to the retained viewport after packing changes. Free pan
and ordinary entity selection preserve the viewport but do not establish a region anchor. The
feed is replaced, not appended, so it stays bounded. No polling, model calls or new dependencies.

## Checks

Free/offline Clojure checks:

```sh
clojure -M:test -n manor.web-test -n manor.scene-web-test
clojure -M:test
```

`scene_web_test.clj` renders routes for every `scenarios-test/bundled` scenario, verifies unchanged
world/events/agent views after rendering, no-space handling, escaping, crowds, travel/status,
bounded feed, matching live event-prefix/world snapshots, loaded/cold/warm equality, and current
route updates that do not recompute or relocate frozen placement.

Actual browser check (requires existing Node with WebSocket and Chromium, no npm install):

```sh
bash test/manor/scene_browser_check.sh 8097
# Optional screenshot (no screenshot is added to the repository):
SCENE_SCREENSHOT=/tmp/manor-scene.png bash test/manor/scene_browser_check.sh 8097
SCENE_SCENARIO=claudeworth bash test/manor/scene_browser_check.sh 8097
```

The wrapper verifies the specified 8090–8099 port is free, starts **its own** village (or
`SCENE_SCENARIO`, use a spatial scenario for these interaction assertions) mock/offline
`:test? true` panel, runs `scene_client_test.mjs` over Chromium's local CDP, and stops that panel
and browser in a trap/finally. Only disposable mock evidence is left under `test-runs/`; it never
cleans runs. Browser assertions cover actual wheel/pointer handlers, drag-vs-tap, fit/zoom/region
navigation, stair partner highlighting when present, viewport/selection preservation and region
anchoring across a simulated outer-packing shift, command forms,
**SSE without a client-triggered
refresh**, bounded feed, escaped/encoded ids, missing selections, and deliberately reordered
refresh/inspector responses (with abort ignored). `CHROMIUM=/path/to/chromium` overrides the binary.
The script does submit Step to its panel; never point the standalone Node check at someone else's.

### Manual-only visual checks

Phase 2 screenshots of village and Claudeworth at 1280×900 were inspected: the toolbar, regions,
floors, room/route marks, tokens and compact feed are present; larger worlds need region zoom for
room-label legibility. This is not an interactive visual-browser acceptance check.

Automated checks do not judge visual legibility, native-tooltip appearance, touch hardware,
cross-browser rendering or very dense graph crossings. For these, verify a free 8090–8099 port
with `ss -ltn`, then start **your own** panel:

```sh
clojure -M:run claudeworth --mock --offline --test --port 8097
```

Open that port, hover room/person/door/road/stair marks, use Fit and each region, zoom out/in, drag
from tokens and empty space, then Step/Auto/Pause. Confirm readable floors/labels, marks and token
crowding at useful zoom levels, pending selection and viewport surviving updates, the away strip,
and `/panel?as=<agent-id>` filtering. Resize to a narrow screen. Stop this process when finished.
Use village and quiz too (quiz has no space). Do not use 8080, real models or another operator's
panel. Dense graphs can still cross connectors; styling and tooltip polish are not layout promises.
