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
| `docs/claudeworth-design.md` | design, phases and numbered decisions for the Claudeworth Manor scenario |
| `docs/stores-and-aspects-design.md` | phased plan: stores, keyed aspects, the scene judge, `find`, the steward |
| `docs/people-and-hiring-design.md` | phased plan: the people server and traits, positions, a small test world, hiring |
| `docs/user-todos.md` | the user's own wishlist for later; not agent tasks |
| `docs/archived/` | finished build briefs and old handoff notes: history, not the present state |

## Current state

This section says where the status lives instead of restating it, so it stays true. Don't add
dates, test counts or "phase N is next" here.

- **What was done last, and what's in progress:** `git log --oneline | head -20` and `git status`.
  Uncommitted changes may be the user's own; never discard them.
- **What's left:** `docs/roadmap.md`. The Claudeworth phases are section 10 of
  `docs/claudeworth-design.md`, and its decisions (section 12) say which are done or deferred.
- **What the user wants later:** `docs/user-todos.md`. These aren't tasks until the user picks one.
- **Whether the code works:** `clojure -M:test` passes on every commit; run it first.
- **When you finish something,** record the outcome where it belongs: mark the roadmap item or
  design decision done, and update `docs/scenario-authoring.md` for contract changes. Don't start a
  new handoff file; build briefs and notes go to `docs/archived/` once their work is committed.

## The user's requirements and preferences

These came from the design conversation; respect them unless told otherwise.

- **Clojure**, because the user reads it easily. Code should stay readable: small functions,
  docstrings, immutability when convenient, data over machinery.
- **Simple and engine-managed.** The engine owns state, memory and context.
- **No pi for state or memory.** Models are called directly via OpenRouter (`manor.llm`). pi is
  acceptable at most as an ad-hoc MCP bridge.
- **Hard state determines narrative**, never the reverse.
- **Real-world access only through bridges**, e.g. an in-sim "check the weather" calls a real
  weather API through the engine. **MCP is the most important bridge kind** (done: roadmap B1).
  The user's key use case: an agent sends a message to its own persistent pi instance through
  the pi MCP server and gets the reply in the same turn. The user's decisions: replies are
  instant (within the turn), no approval, safety is the MCP server's job, results go only to the
  calling agent (sharing takes an in-game action), the GM/orchestrator deals with pi state on
  branches later, and pi runs a cheap model (deepseek v4.1 flash) by default.
- **Private information matters.** Never put the full world in an agent prompt. Be careful that
  bridges don't create shared side channels.
- **The GM is an LLM by default but hands-off:** it's only called when triggered, and it is unseen
  by the characters (agents reach it only through the diegetic `pray`, not a named GM tool).
- **A minimal web UI:** a control panel and simple diagrams, nothing graphical.
- **Cost-conscious.** Default model `deepseek/deepseek-v4.1-flash` (about $0.002 per agent per
  round). Always cap real runs with `:max-cost`.
- **No hard-coded phrase or regex checks on what models write.** Don't judge prose with word
  lists or patterns ("contains 'at the gate'", "starts with 'You'", "mentions 'drinks'"): they
  reject good text and miss new wordings. Check structure instead (schemas, lengths, ids that
  exist, values from a list), shape behaviour with instructions, and review generated content at
  the point it's made (`git diff` after `roll-cast!`). Regexes on identifiers and formats are fine.
- The user wants to try **generating other scenarios**. The prerequisites are roadmap section A.

## Layout

```
src/manor/
  world.clj      world value, apply-event multimethod (reducers), visibility rules
  narrate.clj    describe multimethod: event -> text agents read
  actions.clj    action specs -> tools; arg decoding (kebab normalisation, unknown keys dropped)
  builtins.clj   pass/carry-on/note/say/whisper/pray + GM spawn/retire/announce/ask-human/done
  conversation.clj converse: conversations within a turn (rules, speaking-turn tools, events)
  space.clj      locations graph, positions, travel, move + GM route tools, pathfinding
  items.clj      items, carrying, look, work-on jobs, judge requests and checked condition changes
  adjudication.clj  shared checked-tool-response parser (work judge and scenario adjudication queries)
  money.clj      Claudeworth sterling: integer old pence, pounds/shillings/pence formatting
  people.clj     people and traits: the polish (checked persona call), the prompt's "Your nature", roll-cast!
  positions.clj  positions (a shared catalogue plus the world's :roles), posts, capabilities, forms of address,
                 bedroom and routine events
  prompt.clj     builds agent/GM requests; agent-view = observe + identity + location
  llm.clj        LLM protocol; OpenRouter (JDK HTTP) and Mock (scenario policy) implementations
  bridge.clj     bridge executor: timeout, truncation, stubs, MCP tools; never throws
  mcp.clj        minimal MCP client (stdio JSON-RPC 2.0) + per-run lazy server pool
  http.clj       JDK java.net.http helpers (don't use http-kit's client: gzip bug)
  engine.clj     runs, rounds, turns, GM, operator commands, start/load/branch
  store.clj      runs/<id>/{config.edn,events.edn,llm.edn}
  web.clj        control panel (http-kit server, SSE, hiccup)
  main.clj       CLI: clojure -M:run [scenario] [--mock] [--offline] [--port N] [--load id]
  rng.clj        deterministic rolls from seed + key
  clock.clj      optional game time (rounds <-> times); prompts show times when a scenario has one
  worldfile.clj  EDN world files: load + check, export live map (runs/<id>/map.edn)
  transcript.clj readable run record: <run-dir>/transcript.md from events.edn and llm.edn
  cache_report.clj  prompt-cache and cost report from llm.edn: report, simulate (offline cache model), compare
  scenarios/     village.clj (full example), treasure.clj (small example), quiz.clj (MCP/pi),
                 claudeworth.clj + building.clj (Claudeworth Manor: daily life, money, shop, plans,
                 architect and construction; shop.clj adds Mr Briggs, store-profile-grounded
                 adjudication and portable purchases via firm quotes; runtime money is old pence;
                 Nellie the housemaid unless :housemaid? is false, whose
                 work-on jobs exercise the judge)
resources/worlds/ world files on the classpath; claudeworth.edn is Claudeworth's fresh world
resources/positions/ shared catalogues of positions; england-1904.edn is a country house and village
resources/pricing.edn model prices, peak hours and cache block size for manor.cache-report
mcp-servers/pi/server.mjs  pi as an MCP server: send_message {session, message}; Node, no deps
mcp-servers/architect/  the architect MCP server: babashka, stateless, reference/price/draft tools
mcp-servers/people/     the people MCP server: babashka, stateless, generate_people and reputation from data files
config/        mcp.example.edn; mcp.edn is local and gitignored (the user's sets pi-sandbox)
dev/user.clj     REPL helpers (start! step! auto! feed view last-prompt transcript! spawn! change-post! approve! act! roll-cast! ...)
test/            engine, conversation, space, items, MCP, clock, world-file, panel, transcript, cache-report, Claudeworth and scenario smoke tests;
                 test scenarios prize.clj and hamlet.clj (world file test/fixtures/hamlet.edn);
                 household.clj reuses Claudeworth mechanics with fixed household.edn / household-day.edn fixtures;
                 test/fixtures/fake-pi.mjs (fake `pi --mode rpc` for free MCP tests)
runs/            real runs (gitignored; the user's data; never delete). runs/smoke-llm is a real 5-round village run
test-runs/       test and ad-hoc runs ({:test? true} / --test); mock runs are safe to delete
old-test-runs/   kept real-model test runs (gitignored); never delete
```

## Working on it

```sh
clojure -M:test                               # all tests (about 15 seconds, free, offline)
clojure -M:run village --mock --offline --test --port 8091   # panel at http://localhost:8091, free, disposable
clj -M:dev                                    # REPL; see dev/user.clj docstring
clojure -M:cache report runs/<id>             # cache hits and cost by kind and agent; also simulate, compare (README)
```

- **Port 8080 is the user's.** They watch their own runs in the panel there, often while agents
  work. Agents never use 8080: give every `clojure -M:run` a `--port` from 8090–8099 (check it's
  free with `ss -ltn`), stop the process when done, and never send commands to a panel you didn't
  start. For a mock check that needs no panel, run it from `clojure -M:dev` with `e/start-run`,
  `e/auto!`, `e/wait-idle` and `e/stop-run` instead; that opens no port. Pass this rule on to
  sub-agents.

- **Verify with mock and offline runs first** (free, deterministic). A mock run of a whole day takes
  seconds. Real runs use the user's manor-only OpenRouter key from
  `MANOR_OPENROUTER_API_KEY` (no fallback to pi's key; the pi MCP server uses pi's own). Never
  print the key.
- **Real runs are short by default:** at most about 8–12 rounds (4–6 minutes at roughly 27 seconds
  a round) with `:max-rounds` and `:max-cost` 0.20, run in the foreground; report what it cost and
  read its transcript. Most problems show up in a few rounds. To test something that happens
  late (bedtime, a finished building), start near it instead of playing up to it: use a shorter
  delay (`:build-rounds`) or a later clock (`:start "1904-10-15T21:00"`, CLI `--start`). A full day
  (48 half-hour rounds, about 20 minutes) is never the default check. A longer run, or a repeat
  of one, needs the user's go-ahead, and sub-agents never start a real run unless their task
  says they may. If a check seems to need a long run, ask instead.
- To inspect a run, read its transcript first. The engine writes `<run-dir>/transcript.md`
  whenever a run pauses or stops; for older runs, write it with `(transcript! id)` at the REPL
  (`{:test? true}` for `test-runs/`) or `(manor.transcript/write! dir)`. It holds
  each round's events with who saw them, then every model call with its tool calls, the engine's
  replies, and — where the model returned them — the character's private thoughts (a `thoughts:`
  line), plus calls, cost and the share of prompt tokens served from cache per agent. For the raw
  record, read `runs/<id>/llm.edn` (every prompt, response, `:thoughts`, `finish-reason`, cost and
  cached tokens, plus when each call was sent (`:at`), the `:provider` and `:generation-id`
  OpenRouter gave, and a digest of each tool sent (`:tool-digests`)). Count `error:`/`rejected:` tool replies
  and `:action/forfeited` events. That's how the argument-naming and reasoning-token issues were found.
- Every new scenario goes in `bundled` in `test/manor/scenarios_test.clj`.
- **Run directories:** `runs/` holds the user's real runs. **Never delete anything there.**
  Anything you start for testing or checking must use `{:test? true}` (CLI: `--test`), which puts
  it under `test-runs/`. That directory is always safe to delete wholesale (`rm -rf test-runs/`,
  `(clean-test-runs!)` at the REPL, or `manor.store/delete-test-runs!`). The test suite uses it and
  cleans it up. `start-run` refuses to reuse an existing run directory.
- **Keep every real-model run.** A run that called a real model costs money and is evidence the
  user may want to read. When it finishes, move its directory from `test-runs/` to
  `old-test-runs/<descriptive-name>` (`mv test-runs/<id> old-test-runs/<name>`), say where it is
  in your report, and never delete it. Don't clean `test-runs/` while one is still there. Only
  mock and test-suite runs are disposable.

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
  That reply has no content either; the engine doesn't send it back (DeepSeek answers such a
  message with HTTP 400) and its nudge says to think briefly.
- DeepSeek's own endpoint rejects `tool_choice: "required"` while reasoning is on, and OpenRouter
  won't route such requests there. Hence `:tool-choice` defaults to `"auto"`; in a 4-round real run
  every reply still called a tool (`old-test-runs/claudeworth-tool-choice-auto-deepseek`).
- http-kit's HTTP *client* failed on gzip responses from Open-Meteo. Outbound HTTP uses `manor.http`.
- pi 1.1.0 has no MCP *server* mode; `mcp-servers/pi/server.mjs` drives `pi --mode rpc` instead
  (prompt, wait for `agent_settled`, `get_last_assistant_text`). `--session-dir` plus
  `--session-id` give a conversation that survives restarts.
- On this machine `pi` itself is not sandboxed; `pii` (interactive alias) runs
  `~/system-scripts/pi-sandbox`, which makes only `$PWD`, `~/.pi` and caches writable (everything
  stays readable, network open). `config/mcp.edn` points the pi server at that script.
- Actionable `:valid?` reasons ("you must be at Fields (fields) to plant") let models self-correct
  within the step budget.
