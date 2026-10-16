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
`claudeworth` (an Edwardian household and the village architect, half-hour turns, sleep and
pastimes; its world is `resources/worlds/claudeworth.edn`).

## MCP bridges

Agents can reach MCP servers through in-game actions: the engine is the MCP client, and each
scenario names the exact tools its actions may call. `mcp-servers/pi/server.mjs` exposes pi as an
MCP tool: one persistent pi session per agent. The safety settings live in that server (which
command starts pi, model, allowed pi tools). Put machine-specific settings, such as a sandboxed pi
command, in `config/mcp.edn` (copy `config/mcp.example.edn`).

```sh
clojure -M:run quiz --max-cost 0.10       # contestants consult their pi assistants
clojure -M:run quiz --mock --offline      # free: scripted contestants, stub assistant
```

See "MCP bridges" in [docs/scenario-authoring.md](docs/scenario-authoring.md).

## Requirements

- Clojure CLI (1.12+) and a JDK
- Node.js and pi for the pi MCP server (the `quiz` scenario and the tests)
- An OpenRouter key in `OPENROUTER_API_KEY`. If that's unset, the key stored by pi in
  `~/.pi/agent/auth.json` is used.

## Quick start

Free and offline (scripted agents, synthetic weather):

```sh
clojure -M:run treasure --mock
clojure -M:run village --mock --offline
# open http://localhost:8080
```

With real models (default `deepseek/deepseek-v4.1-flash`, about $0.002 per agent per round) and
real historical weather:

```sh
clojure -M:run village --max-cost 0.50
clojure -M:run village --model openai/gpt-5-mini
clojure -M:run --load smoke-llm            # reopen a saved run
clojure -M:run claudeworth --max-cost 0.25  # one simulated day at Claudeworth Manor
clojure -M:run claudeworth --mock           # free: the scripted day
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
(act! "eva" :plant {:grain 3})
(approve! "req-63")
(branch! 3)
(transcript!)           ; <run-dir>/transcript.md: each round's events and every model call
(transcript! "claudeworth-20261015-101500" {:test? true})   ; any saved run
```

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
| `:model` / `:gm-model` | `deepseek/deepseek-v4.1-flash` | per-agent `:model` overrides |
| `:gm` | `:llm` | who holds the GM seat: `:llm`, `:human`, `:off` |
| `:offline?` | `false` | use the scenario's bridge stubs |
| `:test?` | `false` | store the run under `test-runs/` (disposable) instead of `runs/` |
| `:max-cost` | `1.0` | USD; the run pauses when reached |
| `:max-steps` / `:max-queries` | 10 / 2 | model calls and query bridges per turn (a cost guard, not game time) |
| `:event-window` | 30 | recent events in each prompt |
| `:gm-check-in-every` | 0 | rounds between GM check-ins (0 = only when triggered) |
| `:reasoning` / `:max-tokens` | `{:effort "low"}` / 4000 | passed to OpenRouter |
| `:mcp-servers` | | per-server overrides, e.g. `{:pi {:env {"PI_MCP_TOOLS" "read"}}}` |

## Tests

```sh
clojure -M:test
```
