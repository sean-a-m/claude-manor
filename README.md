# Claude Manor - Large scale multiagent simulation in which agents inhabit an Edwardian manor house.

## Running

```sh
clojure -M:run claudeworth-medium
```

Then open up the web UI and let it rip.


## Requirements

- Clojure CLI (1.12+) and a JDK
- Node.js and pi for the pi MCP server that aren't integrated anywhere at the moment
- Babashka for the architect MCP server (Used by `claudeworth` scenarios to construct manor additions and follies).
  Babashka also runs the people server, which `roll-cast!` uses (and its tests).
- An OpenRouter key in `MANOR_OPENROUTER_API_KEY` for real runs over OpenRouter (the default).
- Or Node.js and pi for real runs through pi (`--pi`, see below), with pi's own logins.

## Models: OpenRouter or pi

By default model calls go straight to OpenRouter with `MANOR_OPENROUTER_API_KEY`. With `--pi`
(run option `:llm :pi`) they go through pi's model layer instead, using the providers you're
logged in to in pi (`pi /login`, `~/.pi/agent/auth.json`) and pi's prompt caching. Models are then
`<pi provider>/<model id>`; a name that isn't one is an OpenRouter id, still sent with manor's key.

```sh
clojure -M:run claudeworth --model deepseek/deepseek-v4.1-flash          # OpenRouter (default)
clojure -M:run claudeworth --pi --model openai/gpt-6-luna                # pi: the OpenAI login
```

A run keeps its choice in `config.edn`, so `--load` uses it again. Per-agent `:model`s in a world
file must name models the run's route knows. Costs for subscription providers are pi's estimates
from catalog prices, so `:max-cost` caps notional dollars there. Providers added by pi extensions
work only when `PI_LLM_EXTENSIONS` (set under `:pi-llm :env` in `config/mcp.edn`) names them.
`pi-claude-code`'s `claude-code` provider does, so `--pi --model claude-code/claude-opus-5-5` runs
on a Claude Code subscription via `claude -p`. `pi-claude-bridge` is not a provider and doesn't
work: see `mcp-servers/pi-llm/server.mjs`.


## Web UI

Experimental and partially functional

## Cache and cost report

`manor.tools.cache-report` reads a run's `llm.edn` (it never calls a model) and shows how well its
prompts hit the provider's prompt cache. Prices, peak hours and the cache block size are in
`resources/pricing.edn`.

```sh
clojure -M:cache report runs/<id> [--top N]
clojure -M:cache simulate runs/<id> [--order system-first|tools-first] [--tier off-peak|peak] [--top N]
clojure -M:cache compare test-runs/<a> test-runs/<b> [--order ...] [--tier ...]
```

## Tests

```sh
clojure -M:test                  # up to four isolated JVM workers
clojure -M:test --workers 1      # serial; useful on a busy machine
clojure -M:test --workers 2
clojure -M:test -n manor.engine-test
clojure -M:test -v manor.engine-test/human-seat
```

Tests use mocks, HTTP stubs and local MCP subprocesses, not paid models. Node.js and Babashka
are needed for the local MCP coverage. Tests stay serial inside each worker because
`with-redefs` changes global vars.

Namespaces are assigned by estimated cost, not round-robin namespace count.
`test/test-costs.edn` holds scheduling hints; unknown namespaces get weight 1. Each invocation
retains `test-runs/tests/<invocation>/worker-N/{output.log,summary.edn,runs/}`. Summaries include
namespace and test-var wall times, and discovery/loading time; the console lists the five
slowest tests per worker. Namespace times include fixtures and test-var times; do not add
both together. Worker startup precedes these measurements. Update the cost hints from
`:timings :namespaces` when the suite changes substantially.

More workers are not necessarily faster: every JVM loads and compiles the application, and
competes for CPU and memory. Benchmark without other suites running. The current suite has
not been demonstrated to finish below a minute; see roadmap issue 15 for the investigation.
Never delete run artifacts automatically; preserve paid runs before manual cleanup.

## Screenshots

The control panel shows the run, the roster and the event feed:

![Control panel: run status, roster and event feed of a claudeworth-medium run](docs/images/control-panel.png)

The scene page draws the house and who is where:

![Scene view: the house's floors and rooms with the household's people placed in them](docs/images/scene-view.png)
