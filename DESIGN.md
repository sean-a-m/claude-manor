# Design

A turn-based multi-agent simulation engine in Clojure. LLM agents hold roles in a world whose
**hard state is owned by the engine**; agents can only propose actions, and code decides what
happens. Narrative is a view of that state, never a source of truth.

## Decisions

| Area | Decision |
|---|---|
| Core | A pure world value changed only by events (event sourcing). An imperative shell runs rounds, calls models and bridges, and persists. |
| State vs narrative | State is the truth. Every event type can `describe` itself as text; agents read those lines. Narrative never feeds back into state. |
| Models | Called over OpenRouter (OpenAI-compatible chat + tool calling) behind an `LLM` protocol, or through pi's model layer (`manor.io.pi-llm`) for the providers pi is logged in to. Either way the engine builds every prompt from scratch each turn and keeps no chat sessions, so context and cost stay under control; pi is only the transport. |
| Memory | Engine-owned. Each prompt is assembled from the role brief, persona, the agent's own notes, the scenario's `observe` projection, and a fixed window of events visible to that agent. |
| Private information | Every event has `:visible-to` (`:all`, a set of ids, `:gm`, `:system`); the default is `:gm`, so forgetting to set it can't leak. The full world never enters an agent prompt. |
| Actions | Data: a malli argument schema, a precondition, and an effect that returns events. Each role's actions become its LLM tools automatically. Arguments are decoded (snake/camel case tolerated), checked against closed schemas, and validated in code. |
| Real world | Only through **bridges** run by the engine. *Query* bridges resolve during a turn and return their result to the agent; *act* bridges wait for operator approval and run at the next round boundary. A bridge is a Clojure function or a tool on an **MCP server** (the engine is the MCP client; stdio; one server process per run, started lazily). Safety for MCP tools lives in the server; the engine controls which tools are reachable and sets identity arguments (e.g. the agent's own pi session) itself. Results are recorded as events, so replays never call out. |
| GM | An ordinary seat with privileged tools (spawn, retire, announce, ask-human, scenario GM actions), filled by an LLM, a human, or nobody. Interrupt-driven: called only for requests, scenario triggers, or an optional periodic check-in. |
| Space | Optional engine-level locations graph: locations plus directed routes with a travel time in rounds, which can be opened and closed. Agents have a position; `move` along a route is immediate for travel 1, otherwise the agent is in transit (skips turns) until arrival. `say`, `whisper`, movement and scenario events use `space/here` so only people present hear them. Every agent's view gets a `:location` section (here, exits, people here). |
| Time | Optional clock from the scenario (`{:start .. :minutes-per-round ..}`), stored in the world. Prompts then show times ("Saturday 15 October 1904, 09:30") instead of round numbers. |
| Occupation | An agent can be busy until a round (asleep, reading). Like a traveller, they skip turns at no model cost and are freed at the start of that round; people present see what they are doing. A `rouse` in the same room marks the occupation interrupted, and the agent takes a turn next round: `carry-on` keeps the occupation and its deadline, any other accepted action frees them first. A forfeited or failed turn leaves the occupation interrupted, and the agent is freed when its time is up. A scenario's `:hibernate` can also take an agent nobody needs out of play with no end time (an occupation with `:hibernating? true`), placing them where it says, and free them when someone comes near. |
| Conversations | `converse` draws up to 3 people present into a conversation inside the initiator's turn: round-robin, initiator first, up to 2 passes and a last word, each line a short model call by the speaker (`manor.mechanics.conversation`; the engine runs it). It uses nobody's turn and doesn't touch occupations; lines are ordinary `say` events, heard by everyone present. Not offered in simultaneous groups. |
| World files | A scenario's starting world can be an EDN file (`manor.io.worldfile`): locations with `:connects-to` (`:travel 0` routes), journeys, clock, agents, scenario sections. The file is a template; each run keeps a copy (`world-start.edn`) and the engine writes the live map back as `map.edn`. Replay never reads world files. |
| Turns | Rounds made of schedule groups. A group of one commits immediately; a larger group acts simultaneously on one snapshot and is resolved in order, so conflicts are rejected. |
| Humans | Operator (commands queued between rounds; approvals) and/or player (a seat with `:controller :human`; the round waits for their input). |
| Run control | Step, step N, auto (optional delay), pause. Auto-pauses on GM `ask-human`, errors, cost limit, max rounds, scenario `:pause-when`, or an empty world. |
| Persistence | `runs/<id>/events.edn` is the truth; `llm.edn` holds every prompt and response. Load = replay. Branch = copy events up to the end of a round. Runs with `:test? true` go to `test-runs/` instead, which is always disposable. |
| UI | REPL first (`dev/user.clj`), plus a minimal web control panel (http-kit, server-sent events, server-rendered HTML). |

## Layout

```
src/manor/
  main.clj       CLI entry point
  engine.clj     the engine's interface: start/load/branch runs, run control, operator commands
  engine/        its parts: run (state, emit!), models, judges, resolve, turn, talk, seat, work, round
  world.clj      world value, event reducer (multimethod), visibility
  actions.clj    action specs -> tools; decoding and validation
  narrate.clj    event -> text (multimethod)
  prompt.clj     builds agent and GM requests
  clock.clj      optional game time: rounds <-> times
  rng.clj        deterministic rolls from seed + key
  mechanics/     rules scenarios combine: builtins, conversation, space, items (+ items/: views,
                 actions, work, judge, steward, find), adjudication, people, positions, hiring
  io/            what leaves the process: llm, bridge, mcp, http, store (run directories),
                 worldfile (EDN starting worlds; export the live map)
  tools/         for the operator: web (control panel), transcript, cache_report
  scenarios/     village (full example), treasure (small), quiz (MCP: a private pi per agent),
                 claudeworth (+ claudeworth/: building, shop, deliveries, money), lodge
mcp-servers/pi/  pi as an MCP server (Node, no dependencies): one persistent pi session per caller
config/          mcp.example.edn; local mcp.edn (gitignored) for machine-specific server settings
dev/user.clj     REPL controls
test/            engine, space, clock, world-file, panel and scenario smoke tests + test scenarios `prize` and `hamlet`
docs/            scenario authoring guide, roadmap
```

## A round

1. `:round/started`; travellers who are due arrive; occupied agents who are due are freed; the
   scenario's `:hibernate` puts agents out of play or wakes them
2. Run act bridges the operator approved
3. World systems tick in order (each may first call a bridge, e.g. weather)
4. For each schedule group, agents take turns (below)
5. The GM is called if anything triggered it
6. `:round/ended`; check pause conditions

### A turn

The agent gets a fresh prompt and its role's tools, then loops for up to `:max-steps` model calls:

- **free** tools (e.g. `note`, `decline`, `say`) apply immediately and the turn continues
- an action with `:per-turn` can be used only that many times per turn, counting accepted uses;
  actions with the same `:limit-key` share the limit, so `say` and `whisper` together allow one
  utterance per turn
- **query** tools call a bridge; the result goes back to the model (up to `:max-queries` per turn)
- **converse** runs a whole conversation (other agents' speaking turns included) and returns its
  lines; the turn continues
- a **final** or **act** tool ends the turn if valid; if rejected, the model is told why and can retry
- no valid action by the end of the loop means the turn is forfeited; an agent with no
  turn-ending action yet is reminded after its second call and before its last
- an interrupted agent (roused while busy) takes this turn to decide: `carry-on` keeps the
  occupation, anything else ends it before the action applies

The GM's turn is the same, except it may take up to `:gm-max-actions` actions and ends with `done`.

## Writing a scenario

See [docs/scenario-authoring.md](docs/scenario-authoring.md) for the full contract, conventions and
checklist. `src/manor/scenarios/treasure.clj` is the small example to copy;
`src/manor/scenarios/village.clj` exercises every feature.

## What's left

See [docs/roadmap.md](docs/roadmap.md): prerequisites for generating scenarios (validator, smoke
harness, configurable framing, loading), MCP follow-ups, memory, versioning, timed events, GM
adjudication, cost controls, and known issues.
