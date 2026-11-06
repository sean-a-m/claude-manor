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
(`claude-bridge`) don't work: see `mcp-servers/pi-llm/server.mjs`.


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

## Screenshots

The control panel shows the run, the roster and the event feed:

![Control panel: run status, roster and event feed of a claudeworth-medium run](docs/images/control-panel.png)

The scene page draws the house and who is where:

![Scene view: the house's floors and rooms with the household's people placed in them](docs/images/scene-view.png)
