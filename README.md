# manor

A turn-based multi-agent simulation engine in Clojure. LLM agents play roles under rules enforced
in code; the engine owns all state, private information, and access to the real world.

| Doc | |
|---|---|
| [AGENTS.md](AGENTS.md) | orientation for agents (and people) picking up the project |
| [DESIGN.md](DESIGN.md) | architecture and decisions |
| [docs/scenario-authoring.md](docs/scenario-authoring.md) | how to write a scenario |
| [docs/roadmap.md](docs/roadmap.md) | what's left, and known issues |

Bundled scenarios: `village` (economy, weather bridge, locations, GM), `treasure` (small
example), `quiz` (each contestant has a private, persistent pi assistant reached over MCP) and
`claudeworth` (an Edwardian household with a housemaid, cook and scullery maid, plus a village
architect, general store and butcher: half-hour turns, sleep and pastimes, items to carry, inspect,
put away and work on, plans and rooms built over a delay; the architect is a babashka MCP server;
its world is `resources/worlds/claudeworth.edn`). Work on items
changes their aspects (and the room's) when it ends, through a separate judge model call, which may also use up
consumables (food, fuel) and make things from them. The kitchen has a pantry
with container shelves and a scullery; the cook and scullery maid sleep in the upstairs servants'
quarters. Mr Briggs at the general store and Mr Hobbs at the butcher's check requested portable
goods with an adjudicator grounded in their own shop's profile and offer firm quotes, a whole
shopping list in one `quote-goods`; the buyer accepts any number of them with one `buy {quote-ids}`. There is no fixed catalogue or bulk delivery. Claudeworth money
uses old pence internally (£1 = 240d), shown as pounds, shillings and pence. Start a fresh run:
old Claudeworth runs are not compatible with this store/currency change.

`lodge` is Ashby Lodge: a widow, her cook-general and the shopkeeper across the street,
on the same household, stores, shop and work-judge mechanics. All places are a free walk apart;
the maid's room is vacant. Its rolled cast lives in `resources/worlds/lodge.edn`, with a free
scripted day in `lodge-day.edn`. There is no architect or building business; hiring comes later.

`lodge` is Ashby Lodge: a widow, her cook-general and the shopkeeper across the street,
on the same household, stores, shop and work-judge mechanics. All places are a free walk apart;
the maid's room is vacant. Its rolled cast lives in `resources/worlds/lodge.edn`, with a free
scripted day in `lodge-day.edn`. There is no architect or building business; hiring comes later.

## MCP bridges

Agents can reach MCP servers through in-game actions: the engine is the MCP client, and each
scenario names the exact tools its actions may call. `mcp-servers/pi/server.mjs` exposes pi as an
MCP tool: one persistent pi session per agent. The safety settings live in that server (which
command starts pi, model, allowed pi tools). Put machine-specific settings, such as a sandboxed pi
command, in `config/mcp.edn` (copy `config/mcp.example.edn`).

```sh
clojure -M:run quiz --max-cost 0.20       # contestants consult their pi assistants
clojure -M:run quiz --mock --offline      # free: scripted contestants, stub assistant
```

See "MCP bridges" in [docs/scenario-authoring.md](docs/scenario-authoring.md).

## Requirements

- Clojure CLI (1.12+) and a JDK
- Node.js and pi for the pi MCP server (the `quiz` scenario and the tests)
- Babashka for the architect MCP server (the `claudeworth` scenario and its tests). The server is
  started lazily per run; without babashka, run Claudeworth with `--offline` for the stub.
  Babashka also runs the people server, which `roll-cast!` uses (and its tests).
- An OpenRouter key in `MANOR_OPENROUTER_API_KEY` for real runs. Manor reads no other key: it
  doesn't fall back to `OPENROUTER_API_KEY` or pi's `~/.pi/agent/auth.json`, so its runs can have
  their own guardrails and budget. Mock runs need no key. The pi MCP server runs `pi`, which
  uses pi's own credentials.

## Quick start

Free and offline (scripted agents, synthetic weather, stub architect):

```sh
clojure -M:run treasure --mock
clojure -M:run village --mock --offline
clojure -M:run claudeworth --mock --offline   # the scripted Claudeworth day
clojure -M:run lodge --mock --offline --test # a free day: open the panel, Auto 48 rounds
# open http://localhost:8080
```

With real models (default `deepseek/deepseek-v4.1-flash`, about $0.002 per agent per round) and
real historical weather:

```sh
clojure -M:run village --max-cost 0.50
clojure -M:run village --model openai/gpt-5-mini
clojure -M:run --load smoke-llm            # reopen a saved run
clojure -M:run claudeworth --no-address --max-cost 0.20   # no forms of address in any prompt
```

For a short real feature check:

```sh
clojure -M:run lodge --max-rounds 6 --test
```

Open the panel and click **Auto**. Lodge starts at 08:00 with the vacancy already pressing,
and defaults to a $0.10 cost cap. Three characters for six rounds should take roughly 1–2 minutes
and cost about $0.04–$0.08 including modest conversation/judging; this is an estimate, not a
measured real run. Read `test-runs/<id>/transcript.md` afterwards and keep the paid run by moving
its directory to `old-test-runs/<descriptive-name>` before running the tests. Use `--port N` if
8080 is occupied; never send commands to an unrelated panel.

Claudeworth's architect bridge is an MCP server: with `--offline` the engine uses a stub with the
same three tools, so the whole building slice runs free. `:build-rounds` (a run option, REPL only)
shortens the build delay, and `:start` moves the clock:

```clojure
(start! :claudeworth {:test? true :offline? true :build-rounds 4})  ; a room within a few rounds
(start! :claudeworth {:test? true :start "1904-10-15T21:00"})      ; begin at bedtime
```

From the REPL (`clj -M:dev`):

```clojure
(start! :village {:llm :mock :offline? true})   ; or (start!) for real models
(serve!)                ; control panel
(step!) (step! 5) (auto!) (pause!)
(feed) (feed 40 "ana")  ; events, optionally as one agent sees them
(view "ana") (last-prompt "ana")
(spawn! {:role :farmer :name "Eva" :controller :human :location "market"})
(place! "eva" "square")
(change-post! "nellie" :cook)   ; a new position: brief and tools follow (worlds with positions)
(act! "eva" :plant {:grain 3})
(approve! "req-63")
(branch! 3)
(transcript!)           ; rewrite <run-dir>/transcript.md now
(transcript! "claudeworth-20261015-101500" {:test? true})   ; any saved run, e.g. an old one
(roll-cast! "worlds/claudeworth.edn")                 ; roll and polish a world file's cast (a few cents)
(roll-cast! "test-runs/x/claudeworth.edn" {:llm :mock})   ; free, with the mock polish
```

`roll-cast!` gives every character in a world file new traits from the people server
(`mcp-servers/people/`) and a persona written from them, and writes them into the file for review
with `git diff`; see "People and traits" in [docs/scenario-authoring.md](docs/scenario-authoring.md).
Positions (a shared catalogue in `resources/positions/`), posts and situations are described
under "Positions, posts and situations" there.

Whenever a run stops running rounds (it pauses, `auto!`'s rounds are done, a manual step ends,
`pause!` or `stop-run`), the engine writes `<run-dir>/transcript.md`: each round's events with who
saw them, then every model call with its tool calls and the engine's replies.

## Real runs and test runs

Runs are stored under `runs/<id>/`. Pass `:test? true` (CLI: `--test`) to store a run under
`test-runs/` instead. That directory is for tests, checks and experiments, and is always safe to
delete wholesale (`rm -rf test-runs/` or `(clean-test-runs!)` at the REPL). The test suite uses it.
Loading a test run needs `{:test? true}` too: `(load! id {:test? true})`.

## Options

Passed to `start-run` / `start!` (defaults in `manor.engine/default-opts`):

| Option | Default | |
|---|---|---|
| `:llm` | `:openrouter` | or `:mock` (uses the scenario's mock policy) |
| `:model` / `:gm-model` / `:judge-model` | `deepseek/deepseek-v4.1-flash` | per-agent `:model` overrides; `:gm-model` and `:judge-model` (work and shop adjudicators) default to `:model` |
| `:gm` | `:llm` | who holds the GM seat: `:llm`, `:human`, `:off` |
| `:tool-choice` | `"auto"` | or `"required"` (CLI: `--tool-choice required`) to force a tool call on every reply. Not known to help: in a 4-round real run with `"auto"`, every reply called a tool. DeepSeek's own endpoint rejects `"required"` while reasoning is on. Under `"auto"`, a reply without a tool call is nudged once, then the turn ends |
| `:offline?` | `false` | use the scenario's bridge stubs |
| `:test?` | `false` | store the run under `test-runs/` (disposable) instead of `runs/` |
| `:max-cost` | `1.0` | USD; the run pauses when reached |
| `:max-rounds` | | pause at this round (CLI: `--max-rounds N`); use a small cap for real checks |
| `:start` | | start the scenario's clock at this time, e.g. `"1904-10-15T21:00"` (CLI: `--start`); scenarios with a clock only. People still start where the world puts them |
| `:build-rounds` | | override the build delay for one run, in rounds (Claudeworth; default one day per build day) |
| `:housemaid?` / `:architect?` | `true` / `true` | Claudeworth: include Nellie and Mr Pugh; pass `false` to leave either out (REPL options) |
| `:addressed-as?` | `true` | positions' forms of address in prompts ("People address you as my lord", and how to address each person present); `false` (CLI: `--no-address`) leaves every form of address out, to compare runs with and without |
| `:max-steps` / `:max-queries` | 10 / 2 | model calls and bridge/adjudication queries per turn (a cost guard, not game time) |
| `:event-window` | 30 | recent events in each prompt |
| `:gm-check-in-every` | 0 | rounds between GM check-ins (0 = only when triggered) |
| `:reasoning` / `:max-tokens` | `{:effort "low"}` / 4000 | passed to OpenRouter |
| `:mcp-servers` | | per-server overrides, e.g. `{:pi {:env {"PI_MCP_TOOLS" "read"}}}` |

## Tests

```sh
clojure -M:test
```

Mechanics tests use the test-only `:household` scenario with fixed world and mock-day data in
`test/fixtures/household.edn` and `household-day.edn`. These preserve controlled test conditions;
do not update them just because the bundled Claudeworth cast or demonstration day changes.
The test-only `:household-containers` uses its own frozen `household-containers*.edn` world and day.
Bundled scenarios get generic offline smoke/replay checks, not cast or room snapshots.
The household fixture reuses production mechanics, but explicitly defaults the maid off;
work/judge tests turn her on.
An isolation regression adds two dozen residents and reorders the production cast while the
fixed mock day and hibernation checks continue to pass.
