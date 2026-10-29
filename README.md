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
(roll-cast! "worlds/claudeworth.edn")                 ; fresh traits + LLM circumstances/prose; $0.20 cap
(roll-cast! "test-runs/x/claudeworth.edn" {:llm :mock})   ; free, with the mock polish
```

Scenarios may opt into an in-world calendar with `:calendar {:lookahead-days 30}` and a clock.
Known arrangements appear before recent events in turns and conversations; due entries receive
one GM review before character turns. Review is not proof the occasion occurred and changes no
money or presence by itself. In such a scenario the operator can use:

```clojure
(add-calendar-entry! {:id "visit" :at "1904-10-21T14:00"
                      :description "Mr Smith is expected." :visible-to #{"hale"}})
(update-calendar-entry! "visit" {:at "1904-10-22T14:00"})
(cancel-calendar-entry! "visit")
```

Audience values are character IDs; omitted visibility means GM-only. Optional `:gm-notes` never
reach characters. See [the calendar contract](docs/scenario-authoring.md#calendar-manormechanicscalendar)
for startup review, lifecycle and world-file export limitations. New `claudeworth` and
`claudeworth-medium` runs share a public calendar of English holidays, quarter days (including
Lady Day and Michaelmas) and seasonal observances for **1904–1905**. Edit
[`resources/settings/england-1904/calendar.edn`](resources/settings/england-1904/calendar.edn) to extend it;
there is no annual generation. The whole list is stored, but characters see only today through
30 days ahead. These are known occasions, not automatic celebrations, days off or payments.

Claudeworth runs also have visits (characters `invite` guests; see
[the visits contract](docs/scenario-authoring.md#visits-manormechanicsvisits)), which the operator
can arrange or cancel too:

```clojure
(schedule-visit! {:who "mrs-bell" :date "1904-10-16" :time "15:00" :stay "to tea" :known-to ["hale"]})
(cancel-visit! "visit-1")
```

`roll-cast!` gives every character in a world file new traits from the people server
(`mcp-servers/people/`) and persona, manner and appearance written from them, with **no old
character descriptions as input** — only the authored `:situation`, sent as the polish's notes,
so the person written fits the scenario. It keeps names, sex, jobs and situations. Each call uses a
fresh seed, printed and recorded for reproduction; pass `{:seed 1}` for repeatable traits.
It writes the result into the file for review with `git diff`; see "People and traits" in
[docs/scenario-authoring.md](docs/scenario-authoring.md).
Positions (a shared catalogue in `resources/settings/england-1904/positions.edn`), posts and situations are described
under "Positions, posts and situations" there.

Whenever a run stops running rounds (it pauses, `auto!`'s rounds are done, a manual step ends,
`pause!` or `stop-run`), the engine writes `<run-dir>/transcript.md`: each round's events with who
saw them, then every model call with its tool calls and the engine's replies.

## Operator scene and panel

The main page (`/`) draws an automatic room/region plan with people and travellers. Click a
person, room or linked item for its inspector; wheel to zoom, drag to pan, or use Fit and the
region buttons. Step, Auto and Pause are the existing run controls. The scene preserves selection
and viewport across updates. Claudeworth also shows construction ghosts, funds, plans and purses.
Room positions stay fixed within their floor panel; growing panels can shift the outer picture.

The original control panel remains at `/panel`, including `?as=<agent-id>` feed filtering and the
human act form. When a human-controlled seat is waiting, choose an action/tool to see its
instructions and schema-generated fields: required/optional labels, constrained numbers,
choice menus, text, and lists (one entry per line). Blank optional fields are omitted. Complex
values have a JSON field with their schema; **Advanced: JSON arguments** lets you supply the
whole argument object instead. Live updates preserve drafts and focus until you send an action.
Engine results appear above the form; free actions and queries keep the turn open, while a final
action ends it. The tool list refreshes after each call. Use **See their view** for visible IDs;
the form does not guess IDs from parameter names. Commands still use normal engine validation.

The roster is kept narrow (who, where, controller, cost); each person's page at `/agent/<id>` has
their seat (role, controller, model, aspects, inner state), view, notes, tools and last model call.
The **→ human** / **→ llm** button, in the roster and on that page, switches a seat between a
model and you during a run (an `:agent/updated` operator event, visible to the GM). It lands
between rounds; a seat already waiting for your input stays yours until you take a turn-ending
action.

**The scene and inspectors are omniscient operator views**, including private
inner state, closed-container contents and recorded reasoning. They are not what agents see.
`/agent/<id>` remains the unchanged agent view. Rendering makes no model calls and changes no
simulation state; a scenario without locations still has controls, inspectors and the feed.

For a free offline preview without starting a run or server, in `clj -M:dev`:

```clojure
(scene-preview! "resources/worlds/claudeworth.edn" "/tmp/manor-scene.svg")
```

See [scene web/inspector contracts and checks](docs/scene-web-api.md),
[layout limits](docs/scene-layout-api.md) and [optional presentation hooks](docs/scenario-authoring.md#scene-operator-presentation-only).
Independent review corrections have regression coverage. Manual visual/touch/cross-browser
acceptance remains; dense connectors may cross and large worlds need region zoom for readable
labels. No simulation commands were added.

## Cache and cost report

`manor.tools.cache-report` reads a run's `llm.edn` (it never calls a model) and shows how well its
prompts hit the provider's prompt cache. Prices, peak hours and the cache block size are in
`resources/pricing.edn`.

```sh
clojure -M:cache report runs/<id> [--top N]
clojure -M:cache simulate runs/<id> [--order system-first|tools-first] [--tier off-peak|peak] [--top N]
clojure -M:cache compare test-runs/<a> test-runs/<b> [--order ...] [--tier ...]
```

- `report`: prompt, cached, uncached, completion and thinking tokens; the cost split into uncached
  input, cached input and output; cost per price tier and what the run would have cost all
  off-peak; calls, prompt tokens, hit % and uncached cost by kind of call (turn-first,
  turn-follow-up, conversation, judge, adjudicator, gm) and by agent; and the top N misses, each
  with where its prompt first differs from the same agent's previous call of that kind (`system >
  ## Your role`, `tools[0] send-for (added)`, `messages[1] user > ## Your view > json location`).
  Cached tokens and the tier come from the log when it has them (`:usage :cached-tokens`, `:at`),
  else they are inferred from `:cost` (shown as "inferred").
- `simulate`: a model of the cache. Each request is the system message, then each tool, then the
  other messages; its cached prefix is predicted as the longest prefix it shares with any earlier
  request, floored to whole 128-token blocks. It prints the same tables with predicted hit % and
  cost and, when the run has real cached numbers, the error per call. On the real run
  `claudeworth-20261018-205714`, `system-first` fits better (mean error 224 tokens a call, 153 of
  238 calls within one block) than `tools-first` (386, 138). It's a model, not the provider: one
  characters-to-tokens ratio per call, tools sized by name unless the log has `:tool-digests`, and
  no cache expiry or write delay. Use it to compare prompt layouts, not to predict a bill.
- `compare`: two runs simulated side by side: hit % and cost per call by kind, and cost per round.
  For a free comparison of two prompt layouts, make a short mock run of the same scenario and seed
  with each:

  ```sh
  clojure -M -e "(require '[manor.engine :as e]) (let [r (e/start-run :claudeworth {:llm :mock :offline? true :test? true :run-id \"layout-a\"})] (e/auto! r 6) (e/wait-idle r) (e/stop-run r)) (System/exit 0)"
  ```

  Mock runs have no usage, so prompt tokens are estimated at 0.2869 tokens per character (from
  the real run above).

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
| `:leave-out` | | Claudeworth: ids of world-file characters to leave out of the run, e.g. `["pugh" "nellie"]` (REPL option) |
pi --session 01a12968-b2b6-7261-b605-398d6a6b43df| `:find-everywhere?` | `true` | Claudeworth: `find` works anywhere; `false` refuses it at shops, the public house and other places of trade (REPL option) |
| `:addressed-as?` | `true` | positions' forms of address in prompts ("People address you as my lord", and how to address each person present); `false` (CLI: `--no-address`) leaves every form of address out, to compare runs with and without |
| `:max-steps` / `:max-queries` | 10 / 2 | model calls and bridge/adjudication queries per turn (a cost guard, not game time) |
| `:event-window` | 30 | recent events in each prompt: at least this many and fewer than twice as many (the window's start jumps forward every this many events, so the provider's prompt cache can reuse it) |
| `:gm-check-in-every` | 0 | rounds between GM check-ins (0 = only when triggered) |
| `:reasoning` / `:max-tokens` | `{:effort "low"}` / 4000 | passed to OpenRouter |
| `:mcp-servers` | | per-server overrides, e.g. `{:pi {:env {"PI_MCP_TOOLS" "read"}}}` |

OpenRouter calls retry network errors, HTTP 429/5xx, and HTTP 400 responses that carry
OpenRouter's upstream-provider metadata (`error.metadata.provider_name`). Each call gets at
most three attempts, with 2- then 4-second waits, resending the same request. Other HTTP 4xx
errors fail immediately. An exhausted conversation call still ends the conversation with an
error and requests a run pause; its error message includes the number of attempts.

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
