# Scene web, inspector and temporal seam (phases 2–4)

Presentation only. No engine, mechanics, prompts, actions, events, persistence or scenario runtime
code changes. `/` is the omniscient operator scene; `/panel` retains the old control panel and
`?as=` feed filtering. Agent/world back links lead to `/panel`. The scene's `?as=` is intentionally
not an agent-view mode. `/agent/<id>` still uses the existing agent-view projection.

## Routes and rendering

- `GET /`: scene toolbar, SVG, inspector sidebar, away/retired strip and compact feed.
- `GET /partial/inspect/{agent,place,item}/<encoded-id>`: escaped operator-only HTML fragment,
  `Cache-Control: no-store`. Unknown entities return a neutral missing-selection fragment (200),
  malformed encoding or unsupported kinds return 404. Removed items show recorded fates/history;
  retired people remain inspectable.
- `GET /panel`, `/partial/status`, `/stream`, `/agent/<id>`, `/world`, `POST /cmd/*`: existing
  behaviour. The panel initially renders 300 events and retains newly received live events,
  unchanged from the original panel. Agent path components are safely decoded once, so encoded
  inspector links resolve without changing the agent-view projection; malformed encodings get 404.
- `GET /partial/scene`: escaped SVG only (`image/svg+xml`).
- `GET /partial/scene-state`: JSON of escaped HTML strings with keys `scene`, `toolbar`,
  `navigation`, `away`, `feed`. All five come from **one snapshot**, not five independent fetches.
  The scene feed contains at most 70 newest events, newest first, including operator-only events.
  Optional `?kind=agent|place|item&id=<encoded-id>` adds `selection` (`{kind,id}`) and `inspector`
  HTML from that same world/event snapshot. Calls are sampled before the world snapshot, so no newer
  completed call is paired with an older world. An in-flight call may appear only on the next refresh.
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
  its routes can get a connector rather than a shared-wall door. Optional event-indexed annotations
  supply historical reservations and size evidence (phase 4 below).
- `presentation snapshot [cache]` calls `place-history` only when historical placement input
  changes, then always calls `present` with current routes. Optional cache is a disposable,
  single-entry atom; cold/load results equal warm results. Web owns one bounded entry, resets it
  on `show!`, and never persists it. Cache races affect work only, not output.
- `svg snapshot result`, `toolbar snapshot`, `navigation result`, `away-strip snapshot result`
  return hiccup. `web/scene-fragments` composes these with the existing event renderer.

The temporal adapter enriches `placement-history` with then-known reservations/size evidence without changing
this read/render contract. It must not pass the current graph into old placement boundaries.
Room geometry is frozen **within panels**; outer region/panel packing may shift. Native SVG titles
include full labels/descriptions/aspects and route direction/time/state. Opposite directions have
separate narrow lanes; shared-wall door arrows are extended to remain visible. Busy tokens have
evidence-based elapsed-time arcs, or a status mark when timing is missing/indefinite (phase 4 below). Sleep, human and acting marks are supported.
Rooms show six tokens; crowded rooms reserve the sixth slot for a selectable `+N` overflow with
all remaining names in its native tooltip.
Traveller fractions use the latest existing departure event and the current travel's arrival;
missing timing means the start of the route, not an invented departure time. An incomplete
traveller with no drawable source/route appears in the strip as location unavailable. Construction
sizes, ghosts, shop closure shading and job progress arcs are supplied by phase 4 below. The generic scene still works without any `:scene` callbacks.

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
- `data-inspect-base="/partial/inspect/"` on `#scene-page` activates guarded selection fetches:
  `base + encodeURIComponent(kind) + '/' + encodeURIComponent(id)`. With no base, the client can
  still show pending selection using `textContent`. Non-OK replies show a neutral missing-selection
  message while retaining the id. Inspector buttons use the same delegated `data-kind`/`data-id`
  handler, including item links. SSE scene-state requests include the selection and commit its
  inspector together with the scene/feed, without a second fragment fetch. An intervening newer
  selection owns its own fragment; the older bundled inspector cannot overwrite it.

SSE `ev` and `status` invalidate prior requests immediately. AbortControllers plus monotonically
increasing generations reject stale refresh/selection responses even if abort is ignored. A
selection change also invalidates an earlier inspector request. The latest viewBox is read when a
refresh commits (pans during fetch are retained), and selection is re-highlighted with its coherent
fragment (or re-fetched when a caller supplies scene-state without a bundled inspector). The
selected region's origin shift is added to the retained viewport after packing changes. Free pan
and ordinary entity selection preserve the viewport but do not establish a region anchor. The
feed is replaced, not appended, so it stays bounded. No polling, model calls or new dependencies.

## Checks

Free/offline Clojure checks:

```sh
clojure -M:test -n manor.web-test -n manor.scene-web-test -n manor.scene-inspect-test
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
SCENE_SCENARIO=quiz bash test/manor/scene_browser_check.sh 8097 # no locations
```

The wrapper verifies the specified 8090–8099 port is free, starts **its own** village (or
`SCENE_SCENARIO`) mock/offline
`:test? true` panel, runs `scene_client_test.mjs` over Chromium's local CDP, and stops that panel
and browser in a trap/finally. Only disposable mock evidence is left under `test-runs/`; it never
cleans runs. Browser assertions cover actual wheel/pointer handlers, drag-vs-tap, fit/zoom/region
navigation, stair partner highlighting when present, viewport/selection preservation and region
anchoring across a simulated outer-packing shift, command forms,
**SSE without a client-triggered
refresh**, bounded feed, escaped/encoded ids, missing selections, and deliberately reordered
refresh/inspector responses (with abort ignored). Phase 3 also checks real inspector loading and
place/item link selection, one-request coherent refresh, and an older bundled inspector arriving
after a newer selection. `CHROMIUM=/path/to/chromium` overrides the binary.
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

## Inspector projection/helper API (phase 3)

`manor.tools.scene-inspect` owns presentation only:

- `read-calls run` reads the run directory's existing `llm.edn`, including after load; no
  `:last-calls` dependency, writes, model calls or logging changes. Missing files return `[]`.
  Only complete newline-terminated entries are sampled; a partial concurrent append waits for a
  later refresh. Every refresh scans the log; no persistent index or unbounded cache is added.
- `person snapshot calls id`, `place snapshot id`, `item snapshot id` return operator data or nil.
  Snapshot is the existing scene snapshot, optionally augmented with `:opts` for the default model.
  These use item, position and space accessors. `:items-gone`, bedrooms, routines and purses have no
  equivalent accessors and are read from their existing recorded representation. Purse amounts are
  explicitly labelled old pence in the generic inspector; Claudeworth adds a sterling overlay.
- `recently snapshot calls id` returns `:round`, `:groups` of call summaries, `:outcomes` and
  `:dialogue`. Groups preserve requests/arguments, per-tool-id replies, other engine context,
  conversation id/pass, model, timestamp, finish reason, returned text and actual `:thoughts` only.
  Only the latest recorded round for that character is shown; earlier rejected attempts within it
  are retained. Dialogue is the last eight visible said/whispered/conversation events, narrated
  using the feed's narrator. Outcomes are that character's non-system events in the recorded round.
- `call-groups calls` groups same-agent/round/conversation-context request continuations by actual
  message-prefix extension. Separate roots stay separate. This also permits legacy reply recovery
  from the next matching continuation when `:replies` was not recorded; a last call with no reply
  evidence says so. Empty model responses (not appended by the engine) are handled explicitly.
- `item-history events id` selects structurally related item events, including made/used-up/
  absorbed/aspect records and job completion via earlier job-focus records. It does not match prose
  or invent an item history from narrative.
- `fragment snapshot calls kind id` returns escaped Hiccup, never raw generated HTML. Selection
  kinds are strings `agent`, `place`, `item`. It is pure given those inputs. `encode-id id` safely
  encodes link path/query components; entity ids never become selectors, script or file paths.

The person fragment includes status/controller/model/cost, position/standing, location/travel/
occupation, Recent, appearance/aspects, private current inner state, carrying trees, post/bedroom/
current routine/purse, notes/last seen and persona/manner/temperament/faculties/circumstances, plus
unchanged-view/feed links. Place and item fragments show descriptions/aspects, linked people,
containers (including closed contents, explicitly operator-only), fixed marks, stores (keeps/stock/
lacking and served places), jobs, open exits, touched round and removed-item fates/history. Long
sections fold; item trees start expanded and guard against incomplete cyclic container records.

### Evidence limits, not new simulation rules

There is no persistent turn id or tool-to-outcome id in existing logs. Nested conversation calls
can be logged before the initiator's enclosing call. The UI therefore labels the **latest recorded
round** and separate request contexts, not an invented single turn chronology. It shows exact
recorded replies alongside narrated event outcomes without claiming that `done` proves final
resolution; later simultaneous `:action/rejected` events remain visible. For older logs lacking
last-call replies, it does not fabricate them. No reasoning is inferred from actions or token counts.
The loaded run's replayed event prefix is the same evidence source as the live event vector.

An in-flight model call has no completed log entry yet, so recent calls can lag current activity;
the UI never waits for a model or changes the run. Unrelated event types without item-id metadata
cannot be attached to item history. Closed routes remain visible on the map, but the inspector's
exits use `space/exits` and explicitly show only open exits. Private state/closed contents are for
the operator only: `/agent/<id>` and `/panel?as=` are intentionally unchanged.

`scene_inspect_test.clj` checks the fixed household, every bundled scenario, loaded durable logs,
multi-call/rejected/simultaneous evidence, legacy and empty/text-only replies, closed contents,
remote stores/jobs/removals, missing/retired/non-LLM selections, escaping/encoded ids and unchanged
world/events/agent projections/feed privacy. Actual Chromium checks passed for village and
Claudeworth. No new manual visual review beyond phase 2's documented screenshot checks; sidebar
legibility, native tooltips, narrow screens, touch and cross-browser polish remain manual checks.


## Temporal seam (phase 4)

Only `src/manor/scenarios/claudeworth.clj` gains optional `:scene` wiring and a pure presentation
namespace require; derived households inherit it. Existing runtime definitions are untouched.
No engine/mechanics/I/O/world/prompt/action changes, new event metadata or persisted UI state.

- `manor.tools.scene-claudeworth/hooks` implements `:history`, `:toolbar`, `:badges`, `:inspect`.
  Funds and purses use the existing old-pence sterling formatter. People show held plans with
  price/status; rooms beside active works have an adjoining-works badge. Ghost selections show
  construction information. All output remains escaped, omniscient operator content only.
- `scene-claudeworth/history events` returns a map of zero-based event index to display annotation:
  `{:reservation normalized-record}` at commissioning, `{:location {:size :large}}` at location
  creation. Plans are captured from **prior plans-drawn evidence at commissioning**, never from
  the current world. Reservation id is the commissioned `:room`, which may differ from plan id.
  Domain is construction's existing `:house` contract, floor/description/size come from the plan,
  and connections/due/name from commissioning. Named standard sizes are converted to layout
  keywords. Custom or missing sizes use generic defaults. Completed works with no commissioning
  record may use the then-known plan to size the later location; no past reservation is invented.
- `manor.tools.scene-history/project events annotations` returns
  `{:history {:initial topology :steps [...]} :reservations [...] :overlays {room-id fields}}`.
  It folds existing space events and display annotations in prefix order. All pre-round-1 topology
  forms the initial layout; each later reservation/location creates a boundary with then-known
  routes and active planned connections. Route-only events update current connectors/styles,
  never local geometry. Completion is the matching location event, so the reservation remains
  through the short interval after `works-completed` but before location creation. This also
  prevents a completed reservation from reappearing at a subsequent placement boundary.
- `scene/placement-history events [annotations]` retains its generic one-argument API.
  `scene/presentation snapshot [cache]` invokes the optional history hook, caches only historical
  placement, and always presents current directed routes and live reservations/size overlays.
  Its cache remains single-entry and disposable. No current graph or future plan data enters
  old boundaries. A browser that misses commissioning **and** completion, a freshly loaded run,
  warm/cold rendering and cache eviction reconstruct identical results. Only **panel-local**
  geometry is invariant; growing panels/regions or changed journeys can shift outer packing.
- `manor.tools.scene-time/closed? world location` marks a location with recorded hours only when
  the clock proves it is closed, matching the existing same-day `[open,close)` convention.
  `busy-progress snapshot agent` returns a clamped fraction from current job `:started`/`:due`,
  otherwise a matching current `agent/occupied` event's round and `:until`. Freed/replaced
  occupations do not lend their starts to new work; interruptions/resumes do not reset starts.
  Indefinite hibernation and missing/invalid timing return nil and render a dashed status mark.
  Arcs/tooltips describe scheduled time elapsed, not success or effects. These functions do not
  participate in scheduling or action checks.

The `:history` hook intentionally replaces the original world-only `:ghosts` sketch: world-only
current works cannot reconstruct completed reservations after reload. The callback contract is
also documented under `:scene` in `scenario-authoring.md`. There are no cancellation/deletion
simulation events to adapt; do not infer either from missing current works. Missing historical
size evidence cannot retroactively resize frozen generic rooms.

Checks: `clojure -M:test -n manor.scene-temporal-test` covers immutable plan evidence, distinct
room/plan ids, every construction boundary prefix, missed refreshes, completion-before-location,
cache eviction, completion-only/missing/custom-size records, route addition/closure/direction,
closed hours, job and occupation arcs, indefinite status marks, escaped/optional hooks, and a
13-round mock/offline test household construction with live/load equivalence. Render checks
confirm unchanged world/events/replay and agent views; simulation location events contain no size.
Existing generic bundled-scenario web/inspector tests also pass. No real models or ports needed.
Manual visual/touch/cross-browser legibility and final independent acceptance remain pending.

## Integration and simulation boundary checks (phase 5)

`scene_integration_test.clj` exercises the component seams together, without a server. Its
base-compatible helper, `scene_boundary_probe.clj`, captures the initial state and every completed
mock round. The head render pass exercises `/`, `/panel`, `/partial/status`, `/world`, SVG/assets,
all agent/place/item inspectors (including missing selections and construction ghosts), filtered
panel feeds, agent pages, coherent scene-state fragments and pure preview SVG. Standalone and
bundled inspectors agree. A separate test writes an offline SVG to a temporary output while
forbidding run creation. Each render pass must leave world/events, existing persisted files and
model-call count unchanged. Hook removal occurs before initialization, not only when rendering.

Run these checks **serially**, not alongside the full suite: existing suite fixtures clean
`test-runs/`. No probe cleans unrelated run directories; it removes only the mock directory it
successfully created and never touches `runs/` or `old-test-runs/`.

```sh
clojure -M:test -n manor.scene-integration-test -n manor.scene-temporal-test -n manor.scene-web-test
bash test/manor/scene_boundary_check.sh 0f7eae9
bash test/manor/scene_browser_check.sh 8097
SCENE_SCENARIO=claudeworth bash test/manor/scene_browser_check.sh 8097
SCENE_SCENARIO=quiz bash test/manor/scene_browser_check.sh 8097
node --check resources/manor/scene.js
node --check test/manor/scene_client_test.mjs
bash -n test/manor/scene_boundary_check.sh test/manor/scene_browser_check.sh
git diff --check
clojure -M:test
```

The boundary script archives `0f7eae9` into an isolated `/tmp` directory (not another worktree),
loads the **same probe** there, then compares with this checkout both with rendering/hooks and
with hooks removed. It uses identical options: mock LLM, offline, test directory, seed 1,
`:build-rounds 6`, `:max-cost 0.20`, fixed per-scenario run ids, one round per auto command.
All seven bundled scenarios run four rounds; the fixed household runs thirteen to cross
commissioning and completion. No real models, bridges, ports or dependencies are needed.
Raw EDN evidence, base source, logs and `comparison.txt` remain at the printed `/tmp` path.
It also fails on protected runtime/dependency/world-file source changes and verifies the only
scenario source additions are the pure presentation require and optional `:scene` key.

Successful comparison (component handoff `e7915a7` plus integration checks):

| Scenario | Compared prefixes | Final events | Complete model requests |
|---|---:|---:|---:|
| village | 5 | 99 | 20 |
| treasure | 5 | 37 | 6 |
| quiz (no space) | 5 | 47 | 12 |
| claudeworth | 5 | 327 | 48 |
| claudeworth-medium | 5 | 378 | 48 |
| lodge | 5 | 132 | 36 |
| dressing | 5 | 50 | 10 |
| household (construction) | 14 | 290 | 83 |

**Nondeterminism is narrow and explicit.** No world/event fields, visible-event audiences,
agent-view data, request messages, tool schemas, model settings, usage, replies or ids are
excluded. Only the durable call envelope's top-level `:at` (wall-clock send instant) is removed.
Requests include even mock-only `:ctx`; tool-schema Java regexes are encoded with their full
source and flags, not discarded. Existing `engine.work/finish-jobs!` runs independent judges in
futures on one pre-batch world, then records/applies results in job order. The round-12 household
judges can dispatch in opposite orders. Only contiguous work-judge requests on an **identical
world** compare grouped by job id; each job's request/retry order and all other call ordering are
retained. Raw dispatch order remains in evidence. Regression assertions prove changes to payload,
multiplicity, world boundary or ordinary call ordering are not hidden by this normalization.

Integration found no substantive component defects or necessary production wiring changes.
Existing temporal tests cover all construction boundary prefixes, fresh-load/missed-refresh/cache
eviction equivalence, frozen geometry and route changes. The full suite passed: 333 tests,
6157 assertions, no failures/errors (the existing non-failing steward transcript warning remains).
Actual Chromium checks passed for village, Claudeworth and no-space quiz, including browser
navigation to `/panel?as=gm` and back; quiz checks away-strip selection, its inspector, zoom/fit,
SSE and retained viewport/selection. Browser cleanup tolerates Chromium helper profile writes
after parent exit. These are interaction checks, not new visual/touch/cross-browser acceptance.
Independent review (phase 6) identified four presentation defects, corrected with no-run
regressions in `manor.scene-review-regressions-test`: later cross-links hidden under room fills,
corridor doors outside their actual shared wall, encoded agent links returning 404, and unintended
pruning of the retained panel feed. Routing now tries bounded edge doglegs; all connections are
painted above fills and below people, with shared-wall door marks. Preview SVG follows the same
visibility rule. Agent links decode once without altering agent-view content, and panel live-feed
retention is restored. The corrective full suite passes 337 tests / 6,232 assertions, and village
and Claudeworth actual Chromium interaction checks pass. The manual checks above still remain.
