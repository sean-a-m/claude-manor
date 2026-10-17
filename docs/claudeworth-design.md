# Claudeworth Manor: design for the manor-building vertical slice

Status, 2026-10-16: phases 0-2 (the engine changes, living in the house, and building) are done.
Decisions from the user are recorded in section 12.

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
| The Earl of Claudeworth | `earl` | his bedroom | wants a grander house |
| The Countess | `countess` | her bedroom | is consulted; has her own (possibly strong) opinions |
| Mr Hale, private secretary | `secretary` | his room | is dispatched to the village to see to it |
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
| Talking | `say` (heard by everyone in the room), `whisper`: free, one of them per turn (decision 33); round-robin turns, so each agent sees what the previous agent did this round |
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
| Briefs | **Rules in code, setting and flavour in the world file.** A brief is a function of the world: the setting (`:meta :setting`) and the role's `:about` (`:roles`) come from the world file, and the rules paragraph is written in code with its numbers (minutes per turn, the speech limit) taken from the clock and the actions. Tool descriptions are mechanics only. | One home per fact: if the abbey moved farther away, only the world file's route would change (section 12, decision 12). |
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
        :build-days 1
        :setting "Claudeworth Manor is a modest Edwardian country house ..."}   ; read into every brief

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

 :roles {:earl {:about "You are the Earl of Claudeworth ..."} ...}   ; who each role is, in this world

 :agents [{:name "Lord Claudeworth" :role :earl :location "study" :bedroom "earls-bedroom"
         :persona "..."}
        {:name "Lady Claudeworth" :role :countess :location "drawing-room" :bedroom "countess-bedroom"
         :persona "..."}
        {:name "Hale" :role :secretary :location "study" :bedroom "secretarys-room" :persona "..."}
        {:name "Pugh" :role :architect :location "architects-office" :bedroom "architects-lodgings"
         :optional? :architect? :persona "..."}]

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
- Plans don't go stale: rooms are never removed, so a plan's connections stay valid. Only one
  work is under way at a time: a commission is refused while any room is being built, and the next
  room is commissioned once the present work is finished.
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

The rules of play live in Clojure, beside the code that enforces them: a brief function fills in
their numbers from the world (minutes per turn from the clock, the speech limit from `say`). The
setting (`:meta :setting`), what each role is (`:roles {:earl {:about ..}}`) and the personas
(`:agents`) live in the world file. Tool descriptions say only how a tool works, true for any
map; places, journeys and opening hours reach agents through their view.

| Action | Who | Kind | Where / when |
|---|---|---|---|
| `say`, `whisper`, `note`, `pray`, `pass` | all | built-in | |
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

The GM is hands-off and unseen by the characters: it's called only when an agent prays (`pray`), the diegetic stand-in for reaching it.

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

**Phase 1: living in the house. Done 2026-10-16.** World file, clock, `walk`/`travel`, talk, `spend-time`,
`sleep`, `rouse`, observe, and a mock policy for a normal day: breakfast talk, the earl drives to
the village and back, a walk to the abbey, everyone in bed by 23:00. Done when a 48-round mock
run has no forfeits, replays equal, agents are skipped while asleep, and `(feed 80 "hale")` shows
nothing from rooms Hale wasn't in.

Done 2026-10-15. Built: `resources/worlds/claudeworth.edn`, `src/manor/scenarios/claudeworth.clj`
(`walk`, `travel`, `sleep`, `spend-time`, `rouse`, a `pass` only those present notice, `:observe`
with `places`, `walkable` and `journeys`, and a scripted mock day), and `claudeworth_test` (the
world, the actions, the mock day to 07:00 Sunday, privacy, `:architect? false`, and "move the
abbey"). Then, from the first real run (`docs/claudeworth-phase1-fixes.md`):

- length rejections say how long the text was and how much to cut (`manor.actions`, from the
  schema's `:min`/`:max` and an optional `:advised-max`); `say` and `whisper` allow 1500
  characters but tell models 1000 (decision 13)
- `travel` accepts an area name (a `:domain`) and goes to its nearest place (decision 14);
  unknown ids get a list of ids to use
- briefs are functions of the world, with the setting and roles in the world file (decision 12)
- `manor.transcript` writes a readable `transcript.md` for any run

After review (2026-10-16): `:max-steps` 10 with a reminder to end the turn (decision 15), the
engine's replies to a turn's last call recorded in `llm.edn` and shown in transcripts, no
`maxLength` where a schema has `:advised-max` (decision 13), and a trimmed GM view (decision 16).

Deviations from the phase 1 brief: the first real run was stopped by the user at 15:30 (round
16, $0.11) instead of running a whole day; the fixes above replaced its brief tuning. Everyone but
Mr Pugh starts in their bedroom (section 1 used to say the study and drawing room). `places` also
shows opening hours, so the office hours are known before you get there; the briefs no longer
state them. The setting and role paragraphs moved from Clojure into the world file.

**Phase 2: building (completes the slice). Done 2026-10-16.** Architect server, money, the store,
plans, `show-plans`, commission, construction, `map.edn` export, and the optional architect agent
(`:architect? false` leaves him out). The done-criteria were:

- `claudeworth_test` scripts the whole of section 1 with the mock policy, against both the
  offline stub and the real server. It asserts the new room exists only after the build delay, is
  joined to the right rooms, can be walked into, that money and fee moved correctly, and that
  `map.edn` contains the room.
- The same holds with `:architect? false` (the clerk path).
- An "odd" room goes through: a bathroom joined to the dining room and the gallery.
- The structural refusals have tests: an unknown room in `connects-to`, a bad size, too little
  money.
- A short real run passes: `(start! :claudeworth {:test? true :build-rounds 6 :max-rounds 12
  :max-cost 0.10})`. Then read the transcript for rejections, forfeits and confusion, and tune
  the briefs and `:valid?` reasons.

Built as briefed, with these deviations:

- Tool payloads use kebab-case (`connects-to`), not the brief's and section 6.5's snake_case
  (decision 20).
- The completion event carries the room's `:name` and `:connects-to` as well as the briefed
  `{:plan-id :room}`, so narration can announce the room and its neighbours without hidden state.
  `:observe`'s `:building` is a top-level view key, one entry per work in progress and its finish.
- `draw-plans` normalises `floor`, so "Ground floor" and "ground" are the same; the first real
  run spent three calls on the wording.
- The reference extracts drop whole sentences that state a social rule ("Servants do not use it
  except ...") instead of returning them. The curated reference is still deferred (decision 9).
- `commission` builds one room at a time (decision 18), which the brief's per-plan rule was
  changed to after the first run.

Two short real runs, both `:test? true` with one build day shortened by `:build-rounds 4` (archived
under `old-test-runs/`):

- `phase2-real-1`: 10 rounds, 79 calls, **$0.109590879** (the $0.10 cap was checked at round
  boundaries, so it overshot by one round), 17 error replies (15 query-limit, 2 "Pugh draws the
  plans here; ask him"), 0 rejections, 0 forfeits. The Saloon was built at 12:30 and walked into;
  the Library and a guest bedchamber were still building at the stop.
- `one-build-real-1`, after the one-room change: 10 rounds, 76 calls, $0.0828, 6 errors (3 floor
  wording, 3 query limit), 1 rejection, 0 forfeits. No commission confusion; the floor and query
  wording were fixed in response.

The full-length real run (about two simulated days) is not part of the phase; the full slice is
checked by the mock policy.

**Items: done.** The engine's `manor.items` module supplies carrying, `look` and timed `work-on`
with a judge at the end. Claudeworth's starting items, shop goods and mock day use it; see
[the completed items brief](archived/items-brief.md) for the real-run findings.

**Phase 3: incentives (after the slice, by need). Not started.** These mostly come from the Ashcombe documents:

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

These are estimates for the user's own long runs, not test plans: checks use short real runs
(`AGENTS.md`, "Real runs are short by default").

- Full slice: commissioning on day 1 plus a day of building is about 70 rounds. With four agents,
  roughly 9 hours of sleep and some multi-hour activities, expect about 150–200 agent turns, so
  **$0.30–0.40**.
- With `:build-rounds 6`, the slice is about 20 rounds: **$0.10–0.15**.
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
    phase 2. The build brief is `docs/claudeworth-phase1.md`, and its follow-up
    `docs/claudeworth-phase1-fixes.md`.
11. **The architect shouldn't stay a full-time agent** (done, 2026-10-16). He cost a model
    call every waking half hour even when nobody came near the village. He now hibernates
    when no one else is in the village.

    How: the engine's `:hibernate` hook (`docs/scenario-authoring.md`, Hibernation) occupies
    an agent with no end time and frees them when the scenario says. Claudeworth's rule: an
    agent with a `:routine` in the world file is out of play whenever nobody else (not
    hibernating) is at the place they're in or the place their routine puts them in, or on the
    way to either; when they're away from the routine's area (visiting the house), anyone in the
    area they're in counts too. So a customer at the general store doesn't wake him, and
    villagers added later won't wake each other. His day is summarised by the routine rather than played: the routine moves him
    (office 09:00-18:00, lodgings otherwise, asleep from 22:00), so a client arriving at the
    office finds him at his drawing board. He's freed at the start of the round after someone
    arrives where he is, and the client can `rouse` him. In the evening he's in his lodgings, so
    a visitor to the closed office doesn't wake him. Any arrival wakes him, even when he's "asleep". In
    the mock day he's called in 2 rounds (6 calls) instead of 10 (19 calls); the mock script
    already fills his day with long pastimes, so real models, which made 26 of 82 calls with
    nobody in the village in an earlier run, should save more. Not yet checked in a real run.
12. **One home per fact.** Every fact about the world (places, distances, opening hours,
    bedrooms, names, setting) lives in the world file, and everything agents read is derived from
    it. The rules of play live in code, as a brief function that takes its numbers from the world;
    the setting and each role's flavour live in the world file (`:meta :setting`, `:roles`). Tool
    descriptions are mechanics only. Mock-day scripts are exempt: they're written for one world.
13. **Speech length:** `say` and `whisper` accept up to 1500 characters, but their descriptions
    (and the briefs) tell models 1000. That leaves slack, so a slightly long speech isn't
    rejected; one over 1500 is told its length and advised to get under 1000. The tool schema
    shows no `maxLength`, so 1000 is the only number models see. `note` is the same: it tells
    models 400 characters and accepts 500.
14. **Area names in `travel`:** "travel to the village" goes to the nearest place of that area,
    found by pathfinding (the first place of the area on the quickest path), e.g. the village
    green. No world-file data marks entrances, for now.
15. **Calls per turn:** `:max-steps` is 10, for every scenario. It is a guard against runaway
    cost, not game time: a turn is half an hour however many calls it takes. Free actions
    (`walk`, `note`, `rouse`), queries and retries all use calls, so an agent with no
    turn-ending action yet is reminded after its second call and before its last, with the
    turn-ending actions it has. Walking far in a bigger house is a separate, later question.
16. **What the GM sees:** the default GM view leaves out `:setting` and `:roles :gm` in `:state`,
    which the GM brief already carries; it keeps the other roles' `:about` paragraphs.

Decisions for phase 2 (2026-10-16), detailed in `docs/claudeworth-phase2.md`:

17. **Interruptions, not special cases.** `rouse` interrupts anyone asleep or busy in the same
    room instead of freeing them. The interrupted agent gets a turn and decides: `carry-on`
    ignores it, anything else ends the occupation. This is general (engine and built-ins), so a
    busy character, such as Mr Pugh at his drawing board, needs no rule of its own.
18. **One room built at a time.** Any work in progress blocks a new commission, whatever the
    plan, and the refusal says when the current work finishes; the next room is commissioned once
    it is done. (This replaces an earlier "one commission at a time per plan" rule that allowed
    several works at once: the tool wording confused models, so the engine and the wording now
    agree on a single build.)
19. **The architect's office is marked in the world file** (`:services #{:architect}` on the
    location), not found by id in code.
20. **The price list is a data file of the server** (`mcp-servers/architect/prices.edn`), read by
    the server and by the offline stub, so it has one home. `:meta :build-days` goes. Tool
    payloads use kebab-case keys (`connects-to`), not the snake_case of section 6.5.
21. **Queries may span turns.** `:max-queries` stays 2 a turn. Reference and price queries leave a
    private record for the caller, so what they found is still in their prompt next turn.

Decision after phase 2 (2026-10-16):

22. **The GM is unseen by the characters.** `request-gm` named the game master in an agent's tool
    description, which invited meta-play: in the first phase-2 run the earl used it to ask for a
    rent advance. It is replaced by the diegetic `pray`, which emits the same `:gm/requested` event
    and so still triggers the GM, but reads as an in-world act; the caller sees "X prays: ...",
    never the machinery, and the GM's own narration is unchanged. This is an experiment: later,
    more interventions should be in-game. Only the GM's brief and the `:gm/called` narration still
    speak of the game master, and only the GM sees those.

Decisions for items (done):

23. **Items are an engine module.** `manor.items` is opt-in through a scenario's `:items` key,
    like `manor.space`. Items load from the world file and export to `map.edn`; purchases create
    carried items. Household provisions and coal remain counters, not individual objects.
24. **Conditions are free text.** A short phrase of at most 80 characters describes an item's
    state. Work changes only conditions, never names or descriptions.
25. **A separate judge is asked when work ends.** This is a narrow engine-run model call, not
    the GM or a bridge, and characters never hear of it. It sees the job, the worker, the items'
    current conditions and the time actually worked, including an early stop after `rouse`.
    Nothing changes before then. The engine checks the answer and records condition changes as
    events; replay never calls the judge. Its usage counts towards the run's cost cap.
26. **The agent chooses the hours.** `work-on` takes half an hour to four hours, default half an
    hour. The judge decides what that time achieves, with an engine-supplied luck roll; the work
    may be only partly done. There are no turns while occupied unless interrupted.
27. **Carried items are visible.** Everyone in the same place can see what people carry.
    `take`, `put-down` and `give` are free; ownership is shown, not enforced.
28. **`look` is minimal.** It shows items here in detail, what people here carry, and an optional
    extra paragraph about the room. It is free, changes nothing, and its reply is kept in
    `llm.edn`, not as an event. Broader changing room states are later work.
29. **Read items through accessors.** Only `manor.items` reads the item map directly or writes it
    through its reducers. The accessors scan for now. Future indexes belong inside the module,
    maintained by pure reducers and rebuilt by replay; any database must be a derived read
    store, rebuilt from the event log, never the source of truth. Neither is needed yet.
30. **The housemaid is optional and on by default.** Nellie (`:housemaid`), with a maid's room
    off the kitchen, exists so `work-on` is somebody's job: `:housemaid? false` leaves her out.
    She has no household money. World-file
    agents that may be left out carry `:optional?`, naming the run option that decides them
    (`:architect?` for Mr Pugh, `:housemaid?` for Nellie); `:init` keeps each accordingly.
31. **`look` is for close examination, not routine looking.** Conditions, descriptions and owners
    come only from `look`; the view lists what is here by name. The rules and the tool doc say to
    use it to inspect something, not on arrival. The housemaid run shows the wording alone does
    not cut looking (54 of 169 calls), because agents also use it to search for people. A
    "nothing has changed" reply and a `walk` reply that shows what `look` would are deferred.
32. **Mechanics tests own their starting conditions.** Done: the test-only `:household`
    scenario uses fixed world and mock-day fixtures, with explicit cast defaults, while reusing
    Claudeworth's mechanics. The bundled world and demonstration script can grow independently.
    Validation cases select agents by name and check the intended diagnostic, including after
    reordering. Separate bundled-content tests cover current defaults and smoke/replay behaviour;
    an isolation regression expands and reverses the production cast without changing the fixture.
33. **Speaking doesn't end the turn.** Done, for every scenario: `say` and `whisper` are free
    actions sharing one use per turn (`:per-turn 1`, `:limit-key :speech`), so a character can
    walk in, speak and then work, travel or pass. A second utterance in the same turn is rejected
    with a reason; a rejected one doesn't use up the turn's speech. Speaking turns now need a
    turn-ending action too (often `pass`, reworded to "do nothing (more)"), which costs an extra
    model call unless the model makes both calls in one response.
34. **Containers.** Done: furniture can hold things (`:container :open` or `:closed`, contents
    `:in` it). The glass-fronted china cabinet is open and holds three Meissen figures; the dining
    room's sideboard is closed and holds the silver candlesticks, a canteen of cutlery and the
    table linen; the kitchen dresser is open and holds a kettle, saucepans and the blacking
    brushes. `open` is a free, reply-only look inside; `take` doesn't need it; `put-in` puts
    things away. Nellie's brief gives no hint where the silver is kept, to see whether she finds
    it. Nobody is told where things belong: agents decide. See
    [the containers brief](archived/containers-brief.md).
35. **Finding people and walking (turns stage 1).** `walk` replies with the new place (who and
    what is there, opening hours), so `look` is for detail only. Each agent's view has
    `last-seen`, kept by the engine from partings. `call` raises the voice across the area (the
    whole house): a call naming someone busy but awake interrupts them, as `rouse` does; it
    doesn't wake a sleeper. Who may properly call whom is left to the characters' station, not
    enforced. Reminders no longer count the per-turn calls. See [the turns brief](turns-brief.md).
