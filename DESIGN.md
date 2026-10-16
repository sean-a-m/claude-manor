# Design

A turn-based multi-agent simulation engine in Clojure. LLM agents hold roles in a world whose
**hard state is owned by the engine**; agents can only propose actions, and code decides what
happens. Narrative is a view of that state, never a source of truth.

## Decisions

| Area | Decision |
|---|---|
| Core | A pure world value changed only by events (event sourcing). An imperative shell runs rounds, calls models and bridges, and persists. |
| State vs narrative | State is the truth. Every event type can `describe` itself as text; agents read those lines. Narrative never feeds back into state. |
| Models | Called directly over OpenRouter (OpenAI-compatible chat + tool calling) behind an `LLM` protocol. No pi: the engine builds every prompt from scratch each turn and keeps no chat sessions, so context and cost stay under control. |
| Memory | Engine-owned. Each prompt is assembled from the role brief, persona, the agent's own notes, the scenario's `observe` projection, and a fixed window of events visible to that agent. |
| Private information | Every event has `:visible-to` (`:all`, a set of ids, `:gm`, `:system`); the default is `:gm`, so forgetting to set it can't leak. The full world never enters an agent prompt. |
| Actions | Data: a malli argument schema, a precondition, and an effect that returns events. Each role's actions become its LLM tools automatically. Arguments are decoded (snake/camel case tolerated), checked against closed schemas, and validated in code. |
| Real world | Only through **bridges** run by the engine. *Query* bridges resolve during a turn and return their result to the agent; *act* bridges wait for operator approval and run at the next round boundary. A bridge is a Clojure function or a tool on an **MCP server** (the engine is the MCP client; stdio; one server process per run, started lazily). Safety for MCP tools lives in the server; the engine controls which tools are reachable and sets identity arguments (e.g. the agent's own pi session) itself. Results are recorded as events, so replays never call out. |
| GM | An ordinary seat with privileged tools (spawn, retire, announce, ask-human, scenario GM actions), filled by an LLM, a human, or nobody. Interrupt-driven: called only for requests, scenario triggers, or an optional periodic check-in. |
| Space | Optional engine-level locations graph: locations plus directed routes with a travel time in rounds, which can be opened and closed. Agents have a position; `move` along a route is immediate for travel 1, otherwise the agent is in transit (skips turns) until arrival. `say`, `whisper`, movement and scenario events use `space/here` so only people present hear them. Every agent's view gets a `:location` section (here, exits, people here). |
| Turns | Rounds made of schedule groups. A group of one commits immediately; a larger group acts simultaneously on one snapshot and is resolved in order, so conflicts are rejected. |
| Humans | Operator (commands queued between rounds; approvals) and/or player (a seat with `:controller :human`; the round waits for their input). |
| Run control | Step, step N, auto (optional delay), pause. Auto-pauses on GM `ask-human`, errors, cost limit, max rounds, scenario `:pause-when`, or an empty world. |
| Persistence | `runs/<id>/events.edn` is the truth; `llm.edn` holds every prompt and response. Load = replay. Branch = copy events up to the end of a round. Runs with `:test? true` go to `test-runs/` instead, which is always disposable. |
| UI | REPL first (`dev/user.clj`), plus a minimal web control panel (http-kit, server-sent events, server-rendered HTML). |

## Layout

```
src/manor/
  world.clj      world value, event reducer (multimethod), visibility
  narrate.clj    event -> text (multimethod)
  actions.clj    action specs -> tools; decoding and validation
  builtins.clj   pass/note/say/whisper/request-gm; GM spawn/retire/announce/ask-human/done
  space.clj      locations graph, positions, travel, move + GM route actions, pathfinding
  prompt.clj     builds agent and GM requests
  llm.clj        LLM protocol; OpenRouter and Mock implementations
  bridge.clj     bridge executor (timeouts, truncation, stubs, MCP tools)
  mcp.clj        minimal MCP client (stdio JSON-RPC) and per-run server pool
  http.clj       JDK HTTP client helpers
  engine.clj     runs, rounds, activations, GM, operator commands, load/branch
  store.clj      run directory I/O
  web.clj        control panel
  main.clj       CLI entry point
  rng.clj        deterministic rolls from seed + key
  scenarios/village.clj   full example scenario
  scenarios/treasure.clj  small example scenario
  scenarios/quiz.clj      MCP bridge example: a private pi assistant per agent
mcp-servers/pi/  pi as an MCP server (Node, no dependencies): one persistent pi session per caller
config/          mcp.example.edn; local mcp.edn (gitignored) for machine-specific server settings
dev/user.clj     REPL controls
test/            engine, space and scenario smoke tests + a minimal `prize` scenario
docs/            scenario authoring guide, roadmap
```

## A round

1. `:round/started`; travellers who are due arrive
2. Run act bridges the operator approved
3. World systems tick in order (each may first call a bridge, e.g. weather)
4. For each schedule group, agents take turns (below)
5. The GM is called if anything triggered it
6. `:round/ended`; check pause conditions

### A turn

The agent gets a fresh prompt and its role's tools, then loops for up to `:max-steps` model calls:

- **free** tools (e.g. `note`, `decline`) apply immediately and the turn continues
- **query** tools call a bridge; the result goes back to the model (up to `:max-queries` per turn)
- a **final** or **act** tool ends the turn if valid; if rejected, the model is told why and can retry
- no valid action by the end of the loop means the turn is forfeited

The GM's turn is the same, except it may take up to `:gm-max-actions` actions and ends with `done`.

## Writing a scenario

See [docs/scenario-authoring.md](docs/scenario-authoring.md) for the full contract, conventions and
checklist. `src/manor/scenarios/treasure.clj` is the small example to copy;
`src/manor/scenarios/village.clj` exercises every feature.

## What's left

See [docs/roadmap.md](docs/roadmap.md): prerequisites for generating scenarios (validator, smoke
harness, configurable framing, loading), MCP follow-ups, memory, versioning, timed events, GM
adjudication, cost controls, and known issues.
