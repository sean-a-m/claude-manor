# Agent handoff: manor

Read this first. It's the orientation for any agent continuing work on this project.

## What this is

A turn-based multi-agent simulation engine in Clojure. LLM agents hold roles in a world whose
**hard state is owned and changed only by the engine** (event sourcing). Agents propose actions as
tool calls; code validates them and applies their effects. Narrative is derived from state. Private
information is enforced per event. The real world is reachable only through engine-run "bridges".

## Documents

| File | Purpose |
|---|---|
| `README.md` | quick start, options, commands |
| `DESIGN.md` | architecture and the decisions behind it |
| `docs/scenario-authoring.md` | the complete scenario contract: how to write a scenario |
| `docs/roadmap.md` | what's left: prioritized tasks with approaches, plus known issues |

## The user's requirements and preferences

These came from the design conversation; respect them unless told otherwise.

- **Clojure**, because the user reads it easily. Code should stay readable: small functions,
  docstrings, data over machinery.
- **Simple and engine-managed.** The engine owns state, memory and context.
- **No pi for state or memory.** Models are called directly via OpenRouter (`manor.llm`). pi is
  acceptable at most as an ad-hoc MCP bridge.
- **Hard state determines narrative**, never the reverse.
- **Real-world access only through bridges**, e.g. an in-sim "check the weather" calls a real
  weather API through the engine. MCP-backed bridges are the next major feature (roadmap B1).
- **Private information matters.** Never put the full world in an agent prompt. Be careful that
  bridges don't create shared side channels.
- **The GM is an LLM by default but hands-off:** it's only called when triggered.
- **A minimal web UI:** a control panel and simple diagrams, nothing graphical.
- **Cost-conscious.** Default model `deepseek/deepseek-v4.1-flash` (about $0.002 per agent per
  round). Always cap real runs with `:max-cost`.
- The user wants to try **generating other scenarios**. The prerequisites are roadmap section A.

## Layout

```
src/manor/
  world.clj      world value, apply-event multimethod (reducers), visibility rules
  narrate.clj    describe multimethod: event -> text agents read
  actions.clj    action specs -> tools; arg decoding (kebab normalisation, unknown keys dropped)
  builtins.clj   pass/note/say/whisper/request-gm + GM spawn/retire/announce/ask-human/done
  space.clj      locations graph, positions, travel, move + GM route tools, pathfinding
  prompt.clj     builds agent/GM requests; agent-view = observe + identity + location
  llm.clj        LLM protocol; OpenRouter (JDK HTTP) and Mock (scenario policy) implementations
  bridge.clj     bridge executor: timeout, truncation, stubs; never throws
  http.clj       JDK java.net.http helpers (don't use http-kit's client: gzip bug)
  engine.clj     runs, rounds, turns, GM, operator commands, start/load/branch
  store.clj      runs/<id>/{config.edn,events.edn,llm.edn}
  web.clj        control panel (http-kit server, SSE, hiccup)
  main.clj       CLI: clojure -M:run [scenario] [--mock] [--offline] [--port N] [--load id]
  rng.clj        deterministic rolls from seed + key
  scenarios/     village.clj (full example), treasure.clj (small example)
dev/user.clj     REPL helpers (start! step! auto! feed view last-prompt spawn! approve! act! ...)
test/            engine, space and scenario smoke tests; test/manor/scenarios/prize.clj
runs/            real runs (gitignored; the user's data; never delete). runs/smoke-llm is a real 5-round village run
test-runs/       test and ad-hoc runs ({:test? true} / --test); always safe to delete
```

## Working on it

```sh
clojure -M:test                               # all tests (about 1 minute, free, offline)
clojure -M:run village --mock --offline --test   # panel at http://localhost:8080, free, disposable
clj -M:dev                                    # REPL; see dev/user.clj docstring
```

- **Verify with mock and offline runs first** (free, deterministic). Then do a short real run with
  `:max-cost` of $0.10–0.20 and report what it cost. Real runs use the user's OpenRouter key: from
  `OPENROUTER_API_KEY`, or else read from `~/.pi/agent/auth.json`. Never print the key.
- To inspect a real run, read `runs/<id>/llm.edn` (every prompt, response, `finish-reason` and
  cost). Count `error:`/`rejected:` tool replies and `:action/forfeited` events. That's how the
  argument-naming and reasoning-token issues were found.
- Every new scenario goes in `bundled` in `test/manor/scenarios_test.clj`.
- **Run directories:** `runs/` holds the user's real runs. **Never delete anything there.**
  Anything you start for testing or checking must use `{:test? true}` (CLI: `--test`), which puts
  it under `test-runs/`. That directory is always safe to delete wholesale (`rm -rf test-runs/`,
  `(clean-test-runs!)` at the REPL, or `manor.store/delete-test-runs!`). The test suite uses it and
  cleans it up. `start-run` refuses to reuse an existing run directory.

## Invariants: don't break these

1. The world changes only via `engine/emit!` → `world/apply-stamped` → `apply-event` reducers.
   Reducers are pure (no rng, clock or I/O).
2. Replaying `events.edn` must reproduce the live world exactly. The tests check this.
3. Events default to `:visible-to :gm`. Agent prompts are built only from `prompt/agent-view` plus
   events where `world/visible-to?` is true. `:llm/usage` and `:system` events never reach prompts.
4. Bridge results are recorded as events; replay never calls out.
5. Operator commands go through the run's single-thread executor, so they land between rounds.
6. Action arguments are always decoded and validated (`actions/decode-args`, `:valid?`) before an
   effect runs.

## Lessons from real-model runs

- Models rewrite kebab-case parameter names as snake_case. The decoder normalises them; keep it.
- With closed or empty schemas, models invent junk keys. Unknown keys are dropped silently.
- `deepseek-v4.1-flash` with reasoning can use up `:max-tokens` (now 4000) and return no tool call.
- http-kit's HTTP *client* failed on gzip responses from Open-Meteo. Outbound HTTP uses `manor.http`.
- Actionable `:valid?` reasons ("you must be at Fields (fields) to plant") let models self-correct
  within the step budget.
