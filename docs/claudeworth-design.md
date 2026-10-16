# Claudeworth Manor: design for the manor-building vertical slice

Status, 2026-10-15: phase 0 (the engine changes) is done; phases 1 and 2 are next. Decisions
from the user are recorded in section 12.

The long-term target is the world in `external-resources/ashcombe_spec.md` and
`ashcombe_world.yaml`: an Edwardian great house kept at late-Victorian staffing, with an abbey and
a village beside it. This document covers only the first **vertical slice**: a small house that
the family grows room by room through an architect in the village. The scenario is
`:claudeworth`, the house is Claudeworth Manor, and the village and abbey are Claudeworth village
and Hollin Abbey. These names live in the data file and are easy to change.

## 1. The slice, and when it's done

Four agents:

| Agent | Role | Starts in | Purpose in the slice |
|---|---|---|---|
| The Earl of Claudeworth | `earl` | study | wants a grander house |
| The Countess | `countess` | drawing room | is consulted; has her own (possibly strong) opinions |
| Mr Hale, private secretary | `secretary` | study | is dispatched to the village to see to it |
| Mr Pugh, architect (optional) | `architect` | architect's office (village) | consults the reference books, draws plans, gives advice, earns a fee |

The design is done when a run supports this sequence, first with the mock policy (free,
scripted) and then with real models:

1. The earl and countess talk in the house about a new room.
2. The earl goes to the village himself, or sends the secretary (half an hour).
3. At the architect's office the client describes what they want. The architect looks things up
   in his reference books, may push back, and draws plans. If there is no architect agent, the
   office clerk drafts exactly what the client asks for.
4. The client commissions the plans. The money leaves the household account and building begins.
5. Everyone passes the time (activities, talk, a walk to the abbey) and sleeps at night.
6. **One simulated day later** the new room appears on the map, joined to the rooms it was built
   onto. Anyone can walk into it.

Every action takes half an hour of game time (one round = 30 minutes).

**Architecture is free-form.** The design of the room is the agents' business. If someone wants
an open-plan bathroom off the dining room, it gets built. The only resistance is social: the
architect's advice, the countess's objections, the price. Code enforces only what keeps the world
consistent (section 6.4).

## 2. What the engine already provides

| Need | Existing mechanism |
|---|---|
| A map that changes at runtime | `:space/location-added` and `:space/route-opened` are ordinary events, so a new room is just more events. Replay and branching keep working. |
| Trips to the village | routes with a travel time in rounds; `:agent/moved`, `:agent/departed`, `:agent/arrived` |
| Talking | `say` (heard by everyone in the room), `whisper`; round-robin turns, so each agent sees what the previous agent did this round |
| An external MCP server | query bridges with `{:mcp {:server .. :tool ..}}`, arguments chosen by the engine (`:bridge-args`), results recorded through `:on-result` |
| Delayed effects | a scenario `:system` that runs every round (enough for construction) |
| Money, stores, plans | scenario `:state` with reducers, as in the village |
| Validating data files | malli (already a dependency) |

## 3. Key decisions

| Area | Decision | Why |
|---|---|---|
| World file | **EDN** at `resources/worlds/claudeworth.edn`, read with `clojure.edn`. Keys are kebab-case keywords; ids are kebab-case strings, as elsewhere in the engine (`"entrance-hall"`). It uses the same concepts as `ashcombe_world.yaml` (domain, floor, zone, connects-to) so the big world can be imported later; an importer just converts `_` to `-`. | EDN is the Clojure convention for data files, it needs no new dependency, and it can be checked with malli. |
| Who writes the map | The engine only. The file in `resources/` is the **fresh world**, the starting map, and is never modified (section 4.1). The live map is in the world and changes only by events. After each change, the engine writes the current map to `<run-dir>/map.edn` in the same format (output only; nothing reads it during the run). To continue from a grown house, start a new run with `:world-file "runs/<id>/map.edn"`. | This keeps invariants 1, 2 and 4: replay, branching and loading all work, and no external process becomes a shared side channel. |
| Architect server | A **babashka** script in `mcp-servers/architect/` (its own `bb.edn`), started by the engine as `bb -m architect.server`. It consults a **reference library** (the Ashcombe YAML and spec), quotes prices, and drafts the room. It refuses only structurally impossible requests (section 6.5). | It's Clojure, starts in milliseconds, and has JSON and YAML built in. The architectural judgment comes from agents, not rules. |
| Plans, then commission | `draw-plans` is a *query* (calls the server; the drafted plan is stored in state as an event). `commission` is a *final* action with no bridge. | No engine change is needed for "final action that calls a bridge", the steps match the real-world process, and the optional architect agent can do the drawing. |
| Money | One household account. **The earl, countess and secretary** can all spend from it freely. | The user decided anyone in the family spends for now. The secretary is included, acting for the family, so that he can be dispatched. That's one line to change for more drama later. |
| Time | One round is 30 minutes, from a clock stored in the world. Prompts show "Saturday 15 October 1904, 09:30", not round numbers. | Agents need to reason about mornings, office hours and bedtime. |
| Moving inside a building | A **free** `walk` action to anywhere reachable without a journey; journeys use a separate **final** `travel` action. | Walking from the study to the drawing room shouldn't cost a 30-minute turn. |
| Sleep and pastimes | An engine-level **occupation**: an agent who is asleep or busy skips turns until a set round, at no model cost. | Overnight rounds cost nothing, and "pass the time doing whatever" becomes one cheap action. |
| Model | `deepseek/deepseek-v4.1-flash` for every seat, including the GM. | The user's decision. |

## 4. The world file

`resources/worlds/claudeworth.edn`. The run option `:world-file` points at another file (an
edited copy, or `map.edn` exported from an earlier run).

```clojure
{:meta {:name "Claudeworth Manor"
        :start "1904-10-15T08:00"        ; round 1 starts here
        :minutes-per-round 30
        :starting-funds 3000             ; pounds, the household account
        :build-days 1}

 :locations
 [;; ------------------------------------------------ the manor (small to begin with)
  {:id "carriage-sweep" :name "Carriage sweep" :domain :house :floor :outside
   :connects-to ["entrance-hall"]
   :desc "Gravel turning circle before the front door. The motor car stands here."}
  {:id "entrance-hall" :name "Entrance hall" :domain :house :floor :ground :kind :hall
   :connects-to ["carriage-sweep" "drawing-room" "study" "dining-room" "kitchen" "staircase"]
   :desc "A modest hall with a fireplace. Every door in the house seems to open off it."}
  ;; drawing-room, study, dining-room (ground floor)
  ;; kitchen (ground): "A cold kitchen. There is no cook."
  ;; staircase (ground) <-> gallery (first floor)
  ;; earls-bedroom, countess-bedroom, secretarys-room (first floor, :kind :bedroom)

  ;; ------------------------------------------------ Claudeworth village
  ;; village-green, general-store (:hours ["08:00" "18:00"]),
  ;; architects-office (:hours ["09:00" "18:00"]), architects-lodgings (the architect's bedroom)
  ;; the inn comes in phase 3

  ;; ------------------------------------------------ Hollin Abbey (does nothing yet)
  ;; abbey-gatehouse, abbey-church
  ]

 ;; journeys between areas: [from to rounds], two-way, like a scenario's :space :routes
 :routes [["carriage-sweep" "village-green" 1]
          ["carriage-sweep" "abbey-gatehouse" 1]
          ["abbey-gatehouse" "village-green" 1]]

 :agents [{:name "Lord Claudeworth" :role :earl :location "study" :bedroom "earls-bedroom"
         :persona "..."}
        {:name "Lady Claudeworth" :role :countess :location "drawing-room" :bedroom "countess-bedroom"
         :persona "..."}
        {:name "Hale" :role :secretary :location "study" :bedroom "secretarys-room" :persona "..."}
        {:name "Pugh" :role :architect :location "architects-office" :bedroom "architects-lodgings"
         :optional? true :persona "..."}]

 :shop {"general-store" [{:item "provisions" :price 1 :desc "a week's groceries for the household"}
                         {:item "candles" :price 1}
                         {:item "coal" :price 2 :desc "a ton"}]}}
```

Field rules:

- `:connects-to` is an undirected adjacency list of places with negligible travel time
  (`:travel 0` routes): rooms in the same building or area.
- `:routes` are journeys, with rounds >= 1.
- `:domain` groups places into areas (`:house`, `:village`, `:abbey`).
- `:hours` restricts when a place's services work.
- `:floor`, `:kind` and `:zone` are descriptive. In the slice they are shown to agents and kept
  on built rooms, but no rule depends on them.

**Loading** (`manor.worldfile`, engine-level and reusable):

- read the file, check it against a malli schema, then cross-check it: unique ids; every
  `:connects-to`, route endpoint and agent `:location` exists; the clock parses. The scenario
  checks its own keys (`:bedroom`, `:hours`).
- errors name the entry, e.g. `location "kitchen": :connects-to names unknown location "sculery"`
- return `{:space :clock :agents :world-file}`; the scenario reads its own sections (`:shop`,
  `:meta`) from `:world-file`, and its own keys on agents (`:bedroom`)

**Exporting:** `(manor.worldfile/export world start)` turns the live world back into the shape of
the starting file (`->edn-string` prints it readably). The engine writes it to
`<run-dir>/map.edn` whenever the map changes. There's also a REPL helper `(export-map! path)`.

### 4.1 Fresh worlds and saved worlds

The world file is a **template**: every run starts from it, and nothing ever writes to it.

| What | Where | Role |
|---|---|---|
| Fresh world | `resources/worlds/claudeworth.edn` (in git) | where every new run starts; edited only by hand |
| A run's starting world | `runs/<id>/world-start.edn` | a copy of the template taken at `start-run`, so you can always see what a run began from, even after the template is edited |
| A run's live world | the event log, `runs/<id>/events.edn` | the truth. The starting map is recorded as events (`:space/location-added`, ...), so loading and branching replay events and never re-read any world file. |
| A run's current map | `runs/<id>/map.edn` | written by the engine after each change; output only |
| Other starting worlds | any EDN file in the same format | e.g. a grown house: `(start! :claudeworth {:world-file "runs/<id>/map.edn"})` starts a fresh run from it |

So "start over" is just a new run, and nothing needs resetting. To keep a grown house as a
starting point, copy its `map.edn` into `resources/worlds/` under a new name.

## 5. Time, turns, sleep and activities

**Clock.** `:init` may return `:clock {:start "1904-10-15T08:00" :minutes-per-round 30}`. The
engine stores it in the world through `:world/initialized`, so it's replayed like everything else.
`manor.clock` converts between rounds and times: round 1 is 08:00, round 2 is 08:30, and 48 rounds
make a day. Prompts use it:

- the header reads `# Saturday 15 October 1904, 09:30`
- event lines are prefixed `[09:30]`, or `[Sun 07:00]` when the day differs from today
- `:round/started` and `:round/ended` lines are left out of agent prompts. With 48 rounds a day
  they would fill the event window. They are still shown to the GM and the operator.

**Occupation** (engine). An agent can be *occupied* until a round, which works like travel: no
turns and no model calls.

- event `{:type :agent/occupied :data {:activity "asleep" :until 47}}` sets `:busy` on the agent
- at round start, due agents get `{:type :agent/freed}` (visible where they are: "Lord
  Claudeworth wakes.")
- `:people-here` in every view shows what present people are doing (`"asleep"`, `"reading"`)
- occupied agents are still in the room and still see events there, in their window when they
  wake
- `rouse {who}` (scenario action, free) frees someone in the same room early

Scenario actions built on this:

| Action | Rule |
|---|---|
| `sleep {until "07:00"}` | in a bedroom (`:kind :bedroom`); until the next time it's that time, at most 12 hours. "Go to a bedroom first (earls-bedroom)" otherwise. |
| `spend-time {activity "reading the newspapers" hours 2}` | anywhere; 0.5 to 4 hours; visible to the room ("Lord Claudeworth settles down to read the newspapers, until 11:30.") |
| `pass` (built-in) | wait half an hour |

When everyone is asleep, rounds run with no model calls at all, so nights cost nothing and pass
in a fraction of a second.

## 6. Movement, money, shopping, building

### 6.1 Movement

- `walk {to}`: **free**. Goes to any place reachable over `:travel 0` routes from where you are
  (the house and its sweep; the village green and its shops). It happens at once, others in both
  rooms see it, and the agent can then act there in the same turn (`walk` then `say`).
- `travel {to}`: **final**. Goes to any place in another area by the quickest path
  (`space/path`). A path totalling 1 round arrives this turn: the half hour is spent on the
  journey. Longer paths use the engine's in-transit mechanism. You can travel straight to
  `architects-office`.
- The built-in `move` is removed through a new scenario key `:exclude-actions #{:move}` (engine
  change E2).
- `:observe` lists every place by area (id and name), so agents know what they can walk or travel
  to.

### 6.2 Money

- `:state :household {:funds 3000 :stores {"provisions" 2}}`
- The earl, countess and secretary see it and can spend from it.
- The architect has a private purse and earns a fee (10% of each commission) on plans he drew:
  an in-world reason to encourage bigger, grander works.
- Integer pounds throughout.

### 6.3 The store

`buy {item qty}` at the general store during opening hours. It's paid from the household account,
and the goods go to the household stores. In the slice it gives the village a second purpose and
the secretary an errand. Eating provisions only matters once needs exist (phase 3).

### 6.4 Building

```
consult-references (query, at the office)  ── MCP consult_references ──> reference excerpts
draw-plans (query, at the office, open hours)
   └─ bridge :architect/draft ── MCP draft_plans ──> drafted room + price
        └─ on-result: :claudeworth/plans-drawn   (plan stored, id "plan-123", visible to drawer and client)
show-plans {plan-id} (free)        shares a plan with everyone in the room
commission {plan-id} (final, at the office, open hours)
   └─ checks: plan exists, you are family or secretary, funds cover the price
   └─ :claudeworth/commissioned   (money moves; works complete at round + 48 × build-days)
construction system (every round)
   └─ when due: :claudeworth/works-completed + :space/location-added + :space/route-opened (travel 0)
```

- **`draw-plans {for name floor desc connects-to size}`**. `desc` is free text: what the room is
  and what it's like. `connects-to` is one or more existing rooms of the house; doors to several
  rooms are fine. `size` is `small`, `medium`, `large` or `grand` and sets the price. The engine
  adds the current house map (`:domain :house` places: id, name, floor, connects-to) to the bridge
  args; the model never supplies it.
- **Who draws:** the architect agent draws for a client (`for`). If no architect agent is in the
  office, a client may draw for themselves ("the clerk takes down your instructions").
- **What code enforces** (everything else is up to the agents):
  - the connected rooms exist and belong to the house
  - the name and description are non-empty and short enough for prompts (name ≤ 60, desc ≤ 600)
  - the size is one of the four
  - there's enough money at commission
  - the room id is unique when it is built (`"library"`, then `"library-2"`)

  The server's draft is treated as untrusted input and checked against the same list before the
  plan is stored.
- Plans don't go stale: rooms are never removed, so a plan's connections stay valid. Several works
  may be under way at once.
- **The delay** is one day by default (48 rounds × `build-days`). The run option `:build-rounds`
  overrides it so tests and cheap real checks don't need a whole simulated day.
- While works are in progress, `:observe` shows "builders at work beside the entrance hall,
  finishing Sunday at 10:00". Completion is announced to everyone (`:visible-to :all`; the
  village talks).

### 6.5 The architect MCP server

`mcp-servers/architect/`: a small babashka project (`bb.edn` with `:paths ["src"]`;
`src/architect/`). It speaks MCP over stdio: newline-delimited JSON-RPC, `initialize`,
`tools/list`, `tools/call`, about 80 lines using babashka's built-in JSON. It's stateless:
everything it needs comes in the arguments or from its reference directory. The engine starts it
with `{:command ["bb" "-m" "architect.server"] :cwd "mcp-servers/architect"}`.

Tools:

- **`consult_references {query}`** searches the reference library and returns the best few
  matches, capped at about 2500 characters. The library is:
  - the locations in `ashcombe_world.yaml`, parsed with babashka's built-in `clj-yaml`. Only the
    architectural fields are returned (name, floor, zone, connects-to, description). Staff
    counts, precedence and the like are dropped, which keeps the irrelevant parts of the
    document away from the model.
  - the sections of `ashcombe_spec.md` that are about the buildings (§6, §10.2 and §10.4),
    split by heading

  Search is keyword scoring. The directory comes from `ARCHITECT_REFERENCE_DIR` (default
  `../../external-resources/`, relative to the server). This is how the architect "knows how
  great houses are laid out" without the engine imposing it. A curated reference document can
  replace the Ashcombe files later (a separate cleanup task); only this tool changes.
- **`price_list {}`** returns the sizes, prices (for example £200 / £500 / £1200 / £3000) and
  build days.
- **`draft_plans {house, request: {name, floor, desc, connects_to, size}}`** returns `{plan: {id,
  name, floor, desc, connects_to}, price, build_days}`. It normalizes the request (derives the id
  and tidies the strings) and refuses only structural impossibilities: unknown or non-house
  rooms in `connects_to`, empty names, unknown sizes. Its errors say how to fix the request.

Possible later additions: an optional model-written "architect's remarks" on a draft, floor-plan
rendering, or a stricter rule set if the user wants more drama.

**Offline** (`--offline`): a Clojure stub inside the scenario with the same signatures (a canned
excerpt, the price list, a straightforward draft). Tests use the **real** server: it's
deterministic and free, and starts in milliseconds.

## 7. Roles and actions

Briefs live in Clojure, beside the code whose rules they restate. Personas live in the world file's `:agents`.

| Action | Who | Kind | Where / when |
|---|---|---|---|
| `say`, `whisper`, `note`, `request-gm`, `pass` | all | built-in | |
| `walk {to}` | all | free | within an area |
| `travel {to}` | all | final | to another area |
| `spend-time {activity hours}` | all | final | anywhere |
| `sleep {until}` | all | final | in a bedroom |
| `rouse {who}` | all | free | same room, target occupied |
| `buy {item qty}` | earl, countess, secretary | final | store, open hours |
| `consult-references {query}` | all | query | architect's office |
| `draw-plans {...}` | architect, or a client when no architect is present | query | architect's office, open hours |
| `show-plans {plan-id}` | all | free | anywhere; shares a plan you can see with the room |
| `commission {plan-id}` | earl, countess, secretary | final | architect's office, open hours |

The briefs set up the social dynamics the rules leave out:

- the architect's professional pride (he'll advise against folly but serves the client)
- the countess's say over her house
- the secretary reporting back

The GM is hands-off: it's called only on `request-gm`.

**`:observe`** (private per agent):

- `:time`, `:you` (bedroom)
- `:household` (funds, stores, works in progress), for the family and secretary only
- `:places` by area
- `:here` details: open now? shop prices?
- `:plans`, for the plans you drew, that were drawn for you, or that were shown to you

The architect sees his own purse and his clients' plans, not the household account.

## 8. Engine changes (phase 0, done)

Each is small and general, with its own tests. The village, treasure and quiz scenarios behave
exactly as before.

| # | Change | As built |
|---|---|---|
| E1 | **Occupation** | `:agent/occupied` and `:agent/freed` events (`world.clj`); helpers `occupy`, `free` (early, naming who interrupted) and `releases` in `builtins.clj`; the engine frees due agents at round start, after arrivals, and skips busy agents in the schedule; `:people-here` shows `:doing`; narration ("Ada is asleep until Sun 07:00.") |
| E2 | **`:exclude-actions`** | scenario key, applied in `engine/all-actions` |
| E3 | **Clock** | `manor.clock` (labels, `round-at` for "until 07:00", `rounds-for`); `:init` may return `:clock`, stored by `:world/initialized`; prompts, notes and the GM inbox use times; agents' prompts leave out round markers; the view has `:time` and exits in `:travel-minutes`; `narrate/at` for descriptions; the panel shows the time |
| E4 | **`:travel 0` routes** | tests; GM `open-route` accepts 0; new `space/area` (everything next door, for `walk`) and `space/distance` (journey length, for `travel`) |
| E5 | **World files** | `manor.worldfile`: `load-world` (path or classpath resource; malli schema plus cross-checks with readable errors), `export` and `->edn-string`; the engine saves `world-start.edn` at start (and on branch) and rewrites `map.edn` from `emit!` whenever space events are emitted; REPL `(export-map! path)` |
| E6 | **Grouped map** | worlds whose locations have `:domain` are drawn as one labelled grid per domain, largest first; busy people show their activity; journeys are labelled in minutes |

Not needed: a "final action that calls a bridge" kind, and general timed events (roadmap B4).
The plan/commission split and a construction system cover the slice.

## 9. New files

```
resources/worlds/claudeworth.edn          starting map, agents, shop
src/manor/scenarios/claudeworth.clj       the scenario (split out building.clj if it passes ~600 lines)
src/manor/clock.clj, src/manor/worldfile.clj
mcp-servers/architect/bb.edn
mcp-servers/architect/src/architect/server.clj      MCP stdio loop and tool dispatch
mcp-servers/architect/src/architect/references.clj  reference library and search
mcp-servers/architect/src/architect/drafting.clj    price list and drafting
test/manor/clock_test.clj, worldfile_test.clj, occupation tests in engine_test.clj
test/manor/claudeworth_test.clj           the slice, scripted, offline and with the real server
```

`:claudeworth` is added to `bundled` in `scenarios_test.clj`. Docs are updated: the authoring
guide (clock, occupation, `:exclude-actions`, world files, travel 0), the roadmap, AGENTS.md
(layout), and README (scenario list).

## 10. Phases

**Phase 0: engine groundwork (E1–E6). Done 2026-10-15.** Each change has tests (`clock_test`,
`worldfile_test`, `web_test`, and occupation, clock and `:exclude-actions` tests in `engine_test`
and `space_test`, using the test scenario `hamlet`), and `clojure -M:test` passes. The saved real
village run replays identically, and its prompts are byte-for-byte unchanged.

**Phase 1: living in the house.** World file, clock, `walk`/`travel`, talk, `spend-time`,
`sleep`, `rouse`, observe, and a mock policy for a normal day: breakfast talk, the earl drives to
the village and back, a walk to the abbey, everyone in bed by 23:00. Done when a 48-round mock
run has no forfeits, replays equal, agents are skipped while asleep, and `(feed 80 "hale")` shows
nothing from rooms Hale wasn't in.

**Phase 2: building (completes the slice).** Architect server, money, the store, plans,
`show-plans`, commission, construction, `map.edn` export, and the optional architect agent
(`:architect? false` leaves him out). Done when:

- `claudeworth_test` scripts the whole of section 1 with the mock policy, against both the
  offline stub and the real server. It asserts the new room exists only after the build delay, is
  joined to the right rooms, can be walked into, that money and fee moved correctly, and that
  `map.edn` contains the room.
- The same holds with `:architect? false` (the clerk path).
- An "odd" room goes through: a bathroom joined to the dining room and the gallery.
- The structural refusals have tests: an unknown room in `connects-to`, a bad size, too little
  money.
- A short real run passes: `(start! :claudeworth {:test? true :build-rounds 6 :max-cost 0.20})`.
  Then read `llm.edn` for rejections, forfeits and confusion, and tune the briefs and `:valid?`
  reasons.
- Then, with the user's OK, one full-length real run (about two simulated days) capped at $0.60.

**Phase 3: incentives (after the slice, by need).** These mostly come from the Ashcombe documents:

- an inn (dine for money; a reason to go out)
- hunger and fatigue (provisions get eaten; with no cook, a bigger kitchen and staff start to
  matter)
- quarterly rents as income
- hiring servants as spawnable roles, which needs rooms for them to sleep in, so building feeds
  hiring
- gongs and abbey bells as world systems
- the access matrix and the green baize door
- spending rules for more drama

## 11. Cost

The real village run cost about $0.0018 per agent turn on `deepseek-v4.1-flash`.

- Full slice: commissioning on day 1 plus a day of building is about 70 rounds. With four agents,
  roughly 9 hours of sleep and some multi-hour activities, expect about 150–200 agent turns, so
  **$0.30–0.40**.
- With `:build-rounds 6`, about 20 rounds: **$0.10–0.15**.
- The architect server costs nothing; it calls no model.

## 12. Decisions (2026-10-15)

1. **World file:** EDN (the Clojure convention), kebab-case keys and ids, no new dependency.
2. **The map file:** only the engine writes it. The starting file is never modified; the live map
   is event-sourced, and the engine exports it to `<run-dir>/map.edn` after each change. A new
   run can start from an exported map.
3. **Very light rules:** the architect consults a reference library (the Ashcombe documents) and
   argues with the client. Anything structurally possible gets built. Pushback comes from the
   architect and the countess, not code.
4. **Spending:** anyone in the family spends freely. The secretary is included, acting for the
   family, so that he can be dispatched.
5. **Model:** `deepseek/deepseek-v4.1-flash` everywhere for now.
6. **Name:** scenario `:claudeworth`, Claudeworth Manor.
7. **Babashka** runs the architect server (installed: v1.13.226).
8. **Fresh world:** the template in `resources/worlds/` is never written. Each run keeps a copy of
   what it started from, and its live state is the event log (section 4.1).
9. **Reference cleanup is a later task.** The Ashcombe documents contain much that's irrelevant
   to architecture (staff counts, precedence, etiquette). For now the server returns only
   architectural fields and building-related sections. Later, a curated reference document
   replaces them.
10. **Phase 1 details:** the names and personas above, the start at 08:00 on Saturday 15 October
    1904, and a real-run cap of $0.25 for one simulated day. The architect is in the scenario
    from phase 1, where he can only talk and pass the time; he gets his building role in
    phase 2. The build brief is `docs/claudeworth-phase1.md`.
11. **The architect shouldn't stay a full-time agent** (future work, not now). He costs a model
    call every waking half hour even when nobody comes near the village. He should hibernate
    when no one else is interacting in the village.

    One approach uses occupation, which already exists: when no other agent is in the village,
    he is occupied ("at his drawing board") until someone arrives. A scenario system, or a hook
    on `:agent/arrived`/`:agent/moved` into the village, frees him (`b/free`). His day-to-day
    life would then be summarised rather than played.
