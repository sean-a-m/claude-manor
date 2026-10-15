# manor

A turn-based multi-agent simulation engine in Clojure. LLM agents play roles under rules enforced
in code; the engine owns all state, private information, and access to the real world.

| Doc | |
|---|---|
| [AGENTS.md](AGENTS.md) | orientation for agents (and people) picking up the project |
| [DESIGN.md](DESIGN.md) | architecture and decisions |
| [docs/scenario-authoring.md](docs/scenario-authoring.md) | how to write a scenario |
| [docs/roadmap.md](docs/roadmap.md) | what's left, and known issues |

Bundled scenarios: `village` (economy, weather bridge, locations, GM) and `treasure` (small
example).

## Requirements

- Clojure CLI (1.12+) and a JDK
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
| `:max-steps` / `:max-queries` | 4 / 2 | model calls and query bridges per turn |
| `:event-window` | 30 | recent events in each prompt |
| `:gm-check-in-every` | 0 | rounds between GM check-ins (0 = only when triggered) |
| `:reasoning` / `:max-tokens` | `{:effort "low"}` / 4000 | passed to OpenRouter |

## Tests

```sh
clojure -M:test
```
