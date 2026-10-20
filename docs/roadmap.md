# Roadmap and known issues

Each item says why it matters, what exists now, a proposed approach, which files to touch, and
how to tell it's done. Proposals are suggestions; check open questions with the user before making
big decisions. When an item is finished, mark it done here (or remove it) in the same commit.

Claudeworth Manor's work is tracked in `docs/claudeworth-design.md`: its phases (section 10) and
decisions (section 12), each marked done or deferred. For scenario generation the recommended
order is **A1 → A2 → A3 → A4 → A6**, then the rest by need (B1a lists MCP follow-ups). Known
issues (section C) are small and can be fixed opportunistically.

---

## A. Prerequisites for generating scenarios

The scenario boundary is already clean: the engine only reads the keys listed in
`docs/scenario-authoring.md`, and three scenarios run on it (`village`, `treasure`, test-only
`prize`). What's missing is the safety rails for scenarios that weren't written by hand.

### A1. Scenario validator

- **Why:** a malformed scenario currently fails mid-round with an obscure error.
- **Now:** no validation at all; `engine/load-scenario` just derefs the var.
- **Approach:** a new `manor.scenario` namespace with `(validate scenario) => [problems]`, called
  from `load-scenario`. Throw with a readable report if any problems are errors.
  - A malli schema for the scenario map (key types, function-valued keys are `fn?`).
  - Cross-reference checks:
    - every role named in `:actions` exists in `:roles`
    - every `:bridge` named by an action or system exists in `:bridges`
    - query/act actions have a `:bridge`
    - tool names match `^[a-z0-9-]{1,64}$`, are unique, and are not namespaced keywords
    - action `:args` schemas convert with `malli.json-schema/transform`
    - `:start-location`s and agent `:location`s exist in `:init`'s `:space`
    - routes reference known locations
    - a `:gm` role exists (warning)
  - Run `:init` with default options and check the result's shape.
  - Warnings (not errors): the scenario overrides a built-in action; no `:mock-policy`; no
    `:observe`.
- **Files:** new `src/manor/scenario.clj`, `src/manor/engine.clj` (`load-scenario`), tests.
- **Done when:** a deliberately broken scenario (one fixture per check) produces a clear message
  naming the problem, and `village` and `treasure` validate cleanly.

### A2. Generic random policy and smoke-test harness

- **Why:** generated scenarios won't come with a hand-written mock policy. We want to exercise any
  scenario for free.
- **Now:** `:mock-policy` is per scenario. `test/manor/scenarios_test.clj` smoke-tests bundled
  scenarios using their own policies.
- **Approach:**
  - `manor.llm/random-policy`: picks a random *available* tool for the agent and generates
    arguments from its malli schema with `malli.generator`. That needs the
    `org.clojure/test.check` dependency; seed it from the run seed so runs are reproducible.
    Prefer turn-ending tools after a free or query call. For the GM, choose `done` most of the
    time.
  - Random arguments will mostly be rejected by `:valid?`. That's fine: the purpose is to find
    exceptions, not to play well. Optionally bias string fields toward known ids (agent ids,
    location ids, ids found in the observation).
  - `manor.smoke/check`: run a scenario N rounds offline and report:
    - exceptions (turn failures show up as `:run/pause-requested` events with "error during")
    - replay equality
    - event types emitted with no `describe` method (use `(get-method narrate/describe t)` vs
      `:default`)
    - events emitted without an explicit `:visible-to` (needs a flag: `world/stamp` currently
      applies the default silently; record that it was defaulted, e.g. in metadata, or check in
      `emit!`)
    - state-changing event types with no reducer (hard to detect in general; skip, or compare
      world before/after for events with no reducer)
    - observation size per agent (characters)
- **Files:** `deps.edn` (test.check), `src/manor/llm.clj`, new `src/manor/smoke.clj`, tests, and a
  REPL helper in `dev/user.clj` (`(smoke! :name)`).
- **Done when:** `(smoke! :treasure)` and `(smoke! :village)` produce a clean report, and a
  scenario with a throwing effect or a missing describe is flagged.

### A3. Configurable framing

- **Why:** some scenarios need different framing (a debate, a game without speech, a non-human
  world). Today the preamble and built-ins are fixed.
- **Now:**
  - `prompt/agent-preamble`, `prompt/gm-preamble`, `prompt/turn-closing` and the conversation
    rules are constants. The agent preamble is shared by turns and conversations so they share the
    prompt cache; keep it so with any override.
  - Built-ins (`builtins/agent-actions`, `space/actions`, `builtins/gm-actions`) are always merged
    in. Scenarios can override them by name, and remove them with `:exclude-actions` (done
    2026-10-15).
  - The GM is always named "GM" with an empty persona.
- **Approach:** optional scenario keys:
  - `:prompts {:agent-preamble "..." :gm-preamble "..." :turn-instruction "..."}`
  - `:gm {:name "Narrator" :persona "..."}` (used by `engine/init!`)

  Keep the defaults identical to today.
- **Files:** `src/manor/prompt.clj`, `src/manor/engine.clj`, the authoring guide, the validator.
- **Done when:** a test scenario with `:exclude-actions #{:say}` produces agent tools without
  `say`, and a custom preamble appears in the recorded prompt.

### A4. Loading scenarios from a path, with reload

- **Why:** generated scenarios shouldn't have to live in `src/`, and iterating needs quick reloads.
- **Now:** `load-scenario` only `require`s `manor.scenarios.<name>` from the classpath.
- **Approach:** add a `scenarios/` directory to `:paths` in `deps.edn` for user and generated
  scenarios (same namespace convention), or support `(load-scenario "path/to/file.clj")` via
  `load-file`. Add `(reload-scenario! :name)` to `dev/user.clj`: `require :reload`, re-validate,
  and offer to restart or reload the current run.
- **Files:** `deps.edn`, `src/manor/engine.clj`, `dev/user.clj`, docs.
- **Done when:** a scenario file in `scenarios/` can be started, edited, and reloaded without
  restarting the JVM.

### A5. Event-type namespacing check

- **Why:** reducers and descriptions are global multimethods, so two scenarios defining
  `:moved` would clash.
- **Approach:** a validator or smoke warning when a scenario emits non-namespaced event types or
  types namespaced with another scenario's name. Engine types (`agent/`, `round/`, `gm/`,
  `bridge/`, `space/`, `item/`, `run/`, `llm/`, `action/`, `world/`) are allowed. Fold this into
  A1/A2.

### A6. LLM-facing authoring prompt and generation workflow

- **Why:** to actually generate scenarios.
- **Now:** `docs/scenario-authoring.md` is complete enough to serve as context. There is no
  generation tooling.
- **Approach:** a prompt or pi skill that gives a model the authoring guide, `treasure.clj` and
  `village.clj` as examples, and the user's scenario description. It writes
  `scenarios/<name>.clj`, then runs validate (A1), smoke (A2) and the tests, iterating on
  failures, and finally runs a short real-model check with `:max-cost 0.20`.
- **Open question (A7):** see below. Generated scenarios are code that runs with full permissions.

### A7. Decision: code scenarios or a data-only format

- **Code scenarios (current):** fully flexible, but generated code must be reviewed before running
  (bridges and effects can do anything the JVM can).
- **A data-only format:** scenarios written as EDN, using a fixed set of generic effects (transfer,
  set, inc, move, spawn, emit with visibility) and declarative checks. Safe to generate and run
  without review, but much less expressive.
- **Recommendation given to the user:** keep code scenarios, gated by validator, smoke harness and
  human review. Revisit a data format if generation becomes routine. **Not decided by the user
  yet.**

---

## B. Engine features

### B1. MCP bridges: done (2026-10-14)

The engine is an MCP client (`manor.mcp`, stdio only). A bridge spec `{:mcp {:server :pi :tool
"send_message"}}` calls an MCP tool; servers come from the scenario's `:mcp-servers`,
`config/mcp.edn` and the run option `:mcp-servers`. One server process per run, started lazily and
closed by `stop-run`. Tool costs reported in `_meta "manor/cost"` count towards the run.
`mcp-servers/pi/server.mjs` exposes pi (`send_message {session, message}`; one persistent
`pi --mode rpc` session per caller, sandbox command and tools configurable). The `quiz` scenario
uses it. Tests: `test/manor/mcp_test.clj`, with a fake pi. A real quiz run (3 rounds, two
contestants, pi through `pi-sandbox`) cost $0.003.

Decisions from the user: replies come back instantly (within the turn); no approval (safety is
the MCP server's job); results go to the calling agent only (sharing takes an in-game action);
pi state on branches is for the GM/orchestrator to handle later; no cost limits for pi yet.

### B1a. MCP follow-ups (by need)

- **Streamable HTTP transport** for remote MCP servers (`{:url ...}` in a server config).
- **Deferred replies.** All MCP calls block the agent's turn (and so the round) until the reply
  or `:timeout-ms`. If slow tools become a problem, add a bridge mode whose reply arrives as an
  event in a later round. The user explicitly deferred this.
- **pi state on branch and load.** pi sessions live in `<run-dir>/pi/`, outside the event log, so
  they can't be rewound: `load-run` resumes them as they are, and a branch starts fresh sessions
  (new run directory). Options: copy or fork session files on branch (pi has `--fork`), or a GM
  or orchestrator tool to reset or seed a session.
- **Validator (A1):** check that every `:mcp` bridge names a configured server, and optionally
  that the tool exists (`tools/list`) when a run starts.
- **Per-server cost limits**, if pi spending needs a cap separate from `:max-cost`.
- **More pi tools in the server**, e.g. `reset_session`, or a per-call model choice. Keep anything
  identity-bearing set by the engine through `:bridge-args`.

### B2. Memory beyond notes

- **Why:** long runs. Agents currently see the last 15 notes and the last 30 visible events
  (`:event-window`); older context is lost.
- **Approach:** a per-agent rolling summary. Every N rounds (or when the visible event count since
  the last summary exceeds K), a cheap model summarizes **only that agent's own visible events,
  notes and previous summary**. Store it as an event `{:type :agent/summarized :visible-to #{id}}`
  with a reducer setting `[:agents id :summary]`, and show it in the prompt above the events.
  Never feed one agent's summary into another's prompt.
- **Files:** `src/manor/engine.clj` (when to summarize), `src/manor/prompt.clj`,
  `src/manor/world.clj` (reducer), options (`:summary-every`, `:summary-model`).
- **Done when:** a 30-round mock run with a scripted summarizer shows summaries in the prompts,
  and a privacy test shows no cross-agent leakage.

### B3. Scenario versioning

- **Why:** event logs replay through the *current* reducers. Changing scenario code silently
  changes old runs on load or branch.
- **Approach:** record `{:scenario-version (:version scenario) :scenario-hash <sha of source file>}`
  in `config.edn` at `start-run`. Warn on mismatch in `load-run` and `branch-run`. Optionally add a
  `:version` key to scenarios.
- **Files:** `src/manor/engine.clj`, `src/manor/store.clj`.

### B4. Timed events

- **Why:** "the festival is in 5 rounds", deliveries, deadlines. Today only travel arrivals are
  timed (a special case in `space/arrivals`).
- **Approach:** an engine event `{:type :schedule/added :data {:at round :event {...}}}`, kept in
  the world under `:scheduled`. At round start (next to arrivals) the engine emits due events and
  a `:schedule/fired`. Effects can schedule future events. Travel arrivals could be reimplemented
  on top of this.
- **Files:** `src/manor/world.clj`, `src/manor/engine.clj`, docs.

### B5. GM adjudication of actions

- **Why:** some outcomes are fuzzy ("persuade the guard").
- **Done so far (2026-10-16):** the GM is no longer named to the characters. The built-in
  `request-gm` (whose description said "the game master, who runs the world") became the
  diegetic `pray`, which emits the same `:gm/requested` event; only the GM's own brief and the
  GM-only `:gm/called` narration speak of a game master (`docs/claudeworth-design.md` decision
  22). Any general adjudication tool should keep the GM out of the characters' view.
- **Approach:** an action flag `:resolve :gm` plus `:outcomes {:success (fn ...) :failure (fn ...)}`.
  The action is checked as usual. Then, instead of `:effect`, the engine asks the GM (one
  structured call: choose an outcome key plus a one-line reason) and applies the chosen outcome's
  events. Code still defines every possible state change.

### B6. Cost and context controls

- Prompt caching hints for providers that support them. Requests are laid out for prefix caching
  (stable system message with `:reference`, fixed tool order, a jumping event window, the volatile
  view last), which DeepSeek's automatic cache uses; Anthropic models via OpenRouter would also need
  `cache_control` on content blocks.
- Per-agent token or cost budgets (pause or switch to a cheaper model when exceeded).
- A fallback model on provider errors.

### B7. State held by locations: largely done

`manor.items` is an opt-in engine module: items at places, carried by people or inside
containers, keyed aspects on items and places (stores design phase 1), `take`, `put-down`,
`give`, `look`, `open`, `put-in`, and timed `work-on` jobs. A separate judge decides
aspect changes when work ends, using the time actually worked. Claudeworth has starting
items, shop purchases and a mock day that uses them. Resources and prices remain the scenario's
job (`:state` keyed by location id).

Claudeworth's general store now has a shopkeeper and adjudicated, buyer-accepted quotes rather
than a fixed inventory. Counter purchases create portable items only; larger orders can be
delivered from shops with a `:delivery-profile` ([deliveries design](deliveries-design.md):
done, with per-shop couriers, and checked in a real run). Internal appraisal queries share the work judge's bounded LLM-call machinery.
Sterling uses integer old pence. Store mechanics tests own a tiny cast; household choreography
and exact container contents use fixed test-owned worlds and scripts. Bundled content gets generic
smoke/replay checks. Claudeworth now also has a cook, scullery maid, pantry shelves, servants'
quarters and a butcher reusing the profile-grounded shop service (design decision 38).
See [the store brief](archived/general-store-brief.md) and Claudeworth design decision 36. Old-run compatibility is intentionally not maintained.

Consumables: done (Claudeworth design decision 39). A purchase is one parcel with its amount as
an aspect; items may be `:consumable?`; the work judge may use them up and make at most two
things from them.

Follow-ups, by need:

- **Bills and tradesmen's accounts** (the user, 2026-10-19: come back to this later, in a more
  realistic manner). Today every purchase and order is paid in full from the household account
  at `buy`, and the rules say there are no bills to settle later, but the characters keep asking
  for things "put on the account" and making out reckonings
  (`runs/claudeworth-20261019-210747`), and Hale, who sees only the balance, hunted a "missing
  pound" that was an order (`old-test-runs/deliveries-first-check`). Candidates: a household
  account book listing each purchase and order for the household's members; tradesmen's
  accounts that run up and are settled weekly or quarterly, with a bill the shop sends; credit
  for some customers and not others.

**[Stores, aspects and the scene judge](stores-and-aspects-design.md): phases 0–7 done**
(phase 7 mock-tested; its next real-model check needs the user's go-ahead). It answers the meals, room-conditions and GM-tools
follow-ups below, replaces item conditions with keyed aspects (done), and removes the unused
provisions counter (done).

- **Meals aren't linked to items.** Food served at table and eaten during a meal occupation
  ("dining on the rib of beef") is never used up, because a meal is an occupation, not work on
  items, so leftovers can stay in the world. Candidates: a deterministic `use-up`/`eat` action
  for consumables in reach, and a nightly steward pass (a narrow judge call) that retires spent
  consumables and rewrites what's left of the rest.
- The shop appraiser's `consumable` flag isn't yet seen against a real model; check its choices
  in the next real run with purchases.
- Items getting dirty or wearing out over time: a GM job later, not automatic decay yet.
- Room conditions: places have `:aspects` and work on the room is judged (stores design phase 1,
  done); Claudeworth's rooms get starting aspects in phase 5. The earlier
  [room-conditions brief](archived/room-conditions-brief.md) is superseded.
- Containers: done (see [the containers brief](archived/containers-brief.md)). Follow-ups:
  locked containers, and perhaps needing to `open` before taking (today a guessed id inside a
  closed container can be taken); carried containers (a basket) aren't forbidden but are
  unsupported and untested (their contents don't show in `carrying`); views list every open
  container's contents, which may need a count instead if shelves fill up; narration always says
  "puts X in the Y", which reads oddly for shelves.
- GM tools to create or alter items.
- Judge `error:` replies currently count in the transcript's overall error total; distinguish
  them from character errors.
- The panel's per-agent usage table has no judge row, although judge cost is in the run total.
- Judging at round start adds latency before agents can act (not yet measured per call).
- `look` is still about a third of all model calls: the 2026-10 wording change did not reduce it.

The completed briefs are [Items](archived/items-brief.md) and its follow-up
[items-followup.md](archived/items-followup.md). The follow-up added Nellie the housemaid to
Claudeworth (now on by default; `:housemaid? false` leaves her out) and rewrote the `look` guidance. Its
real run (10 rounds, $0.131, 169 calls) put `work-on` and the judge in front of a real model for
the first time: six jobs, sensible partial work and low-luck mishaps, $0.0034 of judge calls, no
forfeits. The wording alone did **not** cut looking: 54 of 169 calls (32%), against 33% before.
Agents look to search for people, not only to inspect conditions. The untried, engine-changing
options are a "nothing has changed" reply to a repeat `look` and a `walk` reply that shows what
`look` would; the user has deferred both.

### B8. Control panel improvements

- Charts of chosen state values over rounds. A scenario could declare `:metrics (fn [world] {..})`,
  sampled at round end.
- Run switcher (list `runs/`, load, branch from the UI).
- A better map layout for more than about 12 locations. Partly done: worlds whose locations have a
  `:domain` are drawn as one grid per domain. Worlds without domains still use the circle.
- Showing a human player their own view inline, not only via the agent page link.

### B9. Snapshots

`load-run` replays the whole log. Add periodic snapshots (`snapshot-<round>.edn`), loading from the
latest snapshot plus subsequent events. Only needed once logs get long.

A snapshot must be the whole world value plus the id of the last event it includes, not the
world-file form of `map.edn`. `map.edn` only knows the starting agents. It names item holders by
name, so an agent the GM spawned (who may carry items), or a name that got a `-2` id, doesn't
round-trip through it. `map.edn` stays an output for reading; nothing resumes from it (the user
confirmed on 2026-10-16 that runs are never started fresh from exported world data for now).

### B10. Turn simulation

- **Why:** in the full-day run (`runs/claudeworth-20261016-194011`) 45% of agent calls only
  walked or looked; agents searched room by room for each other, and counted their per-turn call
  budget. Rounds run one agent after another, so time per round grows with the number of calls;
  the aim is about 200 agents.
- **Stage 1 (fewer wasted calls): done.** See [the turns brief](archived/turns-brief.md): last
  seen in the view, `walk` replying with the new place, `call` across the area, reminders that
  don't count calls. Over the same 10 rounds, calls fell from 163 to 116, walk-or-look-only calls
  from 64% to 33%, `look` from 54 to 8, and call counting in thoughts from 21 to 0. Follow-ups:
  `last-seen` is empty at the start, so the first turn still searches; Nellie walks room to room
  looking for work; `call` hasn't yet been seen answered.
- **Conversations within a turn: done.** `converse`, separately from stage 2 and for today's
  one-after-another rounds; see [the converse brief](archived/converse-brief.md). It doesn't work in
  simultaneous groups, which stage 2 would have to address.
- **Stage 2 (simultaneous rounds and conversations), not started:** every free agent plans in
  parallel on one snapshot, engine code resolves the plans in a fixed order, and only agents
  whose situation changed get a short follow-up call; conversations happen as exchanges within a
  round. The research is in [turn-resolution-research.md](turn-resolution-research.md), with its
  recommended design and open questions. The user wants to see how far stage 1 goes first.

### B11. People, positions and hiring

- **Why:** in `runs/claudeworth-20261018-111357` the earl had Hale find a driver; Hobbs named
  a man who doesn't exist and wrote him a character, and nobody could be met or engaged.
- **Design:** [people-and-hiring-design.md](people-and-hiring-design.md), with the user's
  decisions of 2026-10-18. Four phases: (1) a people server that rolls Big Five temperament and
  faculties and a model polish, tried on the existing casts; (2) positions
  separated from people, a shared catalogue, situations and a higher agent cap (C13 stays separate); (3) a small
  test world (Ashby Lodge) for short real runs; (4) hiring: `recommend`, `send-for`, `engage`,
  and an away status. **Phases 1–4 implemented**; see the design's section 6.6 for hiring.
  Lodge's mock/offline day completes an interview and hire with conditions and a bedroom;
  tests cover privacy, pool bounds, refusal/return, departure timing, caps, failures and replay.
  **Descriptor-free generation implemented:** the server rolls basic traits/definitions only;
  the writer supplies one or two external circumstances with persona, manner and appearance.
  Raw readings stay in private prompts under **Faculties**; reputation selects readings and
  written facts. See design section 3.7. Clearer period wording remains future work, without
  dropping or replacing the basic data.
  Real simulation acceptance runs remain with the user; outdoor housing and dismissal are deferred.

### B12. Person aspects

- **Why:** characters' state never changes. A judge's summary said Lady Claudeworth was "evening
  gown on, hair done", but only the room's aspects could be recorded
  (`old-test-runs/claudeworth-stores-phase6`), and a cook sweeping the flue stays clean.
- **Design:** [person-aspects-design.md](person-aspects-design.md), with the user's decisions of
  2026-10-20. People get open, keyed aspects that everyone present sees; `spend-time` changes
  the worker and the people named in `on` who are there at the end; the nightly steward removes
  sleepers' aspects that wouldn't last the night. Three phases, tested with a fixture world.
  **Not started.**
- Follow-ups, from the same decisions:
  - **Private states** (the user may take this up next): inner conditions only the person
    sees, such as hunger, tiredness, moods and physical feelings. They need a visibility rule
    of their own, and an answer to who changes them (the judge after work, sleep and meals, or
    the person themselves).
  - **Fixed keys alongside open ones:** some keys always present for everyone (`:dress`, say),
    with the rest open as now. It would give the views a stable order and let the steward or
    code treat known keys predictably.
  - **Appearance worded as habit:** the people writer (`manor.people` polish and appearance)
    may need to write changeable things as what's usual ("usually in a print frock and
    apron"), so the fixed baseline doesn't contradict aspects. Check what it writes for
    clothing first.
  - **Bystanders:** whether the judge may change people present who weren't named (soot over
    everyone in the kitchen while the flue is swept), and how to keep that from becoming a
    licence to change everyone in the room.
  - **Quick changes:** a free or short way to change your own state ("taking off my apron")
    instead of half an hour of `spend-time`, perhaps like quick put-away jobs. Deferred as too
    complex for the first build.

---

## C. Known issues and small fixes

1. **Act bridges aren't re-checked when executed.** `:valid?` runs when the request is made; the
   bridge runs after approval at a later round start, when the world may have changed.
   *Fix:* re-run the action's `:valid?` against the current world in
   `engine/run-approved-bridges!` and emit `:bridge/completed` with an error if it now fails.
2. **Operator `spawn!` ignores `:limits :max-agents`.** Only the GM's spawn tool checks it
   (default 50 active agents since people phase 2; retired ones don't count).
   Probably fine (the operator is god), but document or enforce it.
3. **GM triggers fire every round while true.** Each firing costs a GM call. A built-in "edge
   trigger" helper (fire once until the condition clears) would help.
4. **Simultaneous arrivals don't see each other.** `space/arrivals` computes everyone's visibility
   from the same world, so two agents arriving at the same place in the same round don't see each
   other's arrival events (they do see each other in `:people-here` afterwards). *Fix:* compute
   arrivals sequentially, applying each before the next.
5. **Tool order isn't stable.** `actions/merge-actions` builds an `array-map` that becomes a hash
   map beyond 8 entries. Harmless, but tool order in prompts varies. Use a vector plus an index
   instead.
6. **Pausing waits for the round to finish.** `pause!` stops auto mode after the current round,
   and a waiting human seat blocks the round until `act!`. There's no mid-round abort.
7. **Travellers can't do anything,** not even notes, while in transit. This is by design for now;
   a scenario wanting "act while travelling" would need a change in `engine/schedule`. The same
   holds for occupied agents (asleep, busy) until they are freed or roused.
8. **`:on-result` events from act bridges** don't get `:actor` filled in automatically (query
   bridge results do). Make them consistent.
9. **The web panel shows one run at a time,** binds to 127.0.0.1, and has no authentication or
   CSRF protection. Fine on localhost only.
10. **Reasoning models can hit `:max-tokens`** (`finish_reason "length"`) with no tool call. The
    engine nudges once, then the step budget runs out. Watch `:finish-reason` in `llm.edn`; tune
    `:max-tokens` or `:reasoning` per model.
11. **Cost tracking relies on the provider reporting `usage.cost`** (OpenRouter does); other
    providers would report 0.
12. **The control panel's map** draws routes between grid cells as straight lines, which can
    cross labels in a busy area. Fine for now; a real layout algorithm is B8.
13. **No optional characters, and cast changes that don't break things.** Nellie and Mr Pugh are
    always in Claudeworth. Remove `:housemaid?` and `:architect?`: the `:optional?` marker, the
    `:init` filtering (which also drops the items an absent agent holds), `remove-absent-cast` in
    the mock day, the household fixture's defaults, the tests that use them, and the docs. The
    architect is required, so the "clerk path" (the earl drawing plans himself when there's no
    architect) goes too.

    The bigger concern is that adding or removing a character like the housemaid in the world
    file risks breaking things. Find what depends on the exact cast and make it safe. Known
    places:
    - the mock day, keyed by agent id, with calls naming other agents;
    - ~~`building/spenders`, a fixed list of roles~~ — removed: all characters can buy and
      commission from the household account; social authority is left to the agents;
    - Pugh's hibernation, which depends on who else is around;
    - tests that assume exactly who is in the house.

    Store follow-up: the new clerk has no optional-character flag. Store mechanics use a
    small test-owned world and explicitly cover extra/reordered/removed unrelated agents and
    multiple routine-driven villagers. The bundled store smoke test has no exact cast snapshot.
    The broader optional-character cleanup and old mock-script coupling above remain open.

    Done when a character can be added to or removed from the world file with no code change,
    and the tests prove it. Part 1 of B11 does this work.

    More evidence for removing the clerk path: in `runs/claudeworth-20261018-111357` the earl
    drew four plans and the countess two at the empty office while Pugh was at the house. That
    left duplicates (`morning-room` and `morning-room-2`, `dining-room-for-fourteen` and `-2`),
    Pugh truthfully denied holding a morning room plan ("your office has been drawing behind
    your back"), and the earl and countess argued over whose plan stands.
14. **Shop quotes inside conversations.** A shopkeeper's speaking turns in a `converse` run with
    `:max-queries 0` (`engine.clj`, the speaking-turn call options), so Briggs can name prices in
    a conversation but can't `quote-goods` until his own turn; customers then wait a turn for
    the offers (seen in `runs/claudeworth-20261018-100848`, rounds 13–14). Now that one
    `quote-goods` prices a whole list, consider letting a shopkeeper's speaking turn make one
    query (a per-role or per-action exemption), or having the conversation's end prompt the
    clerk to quote what was asked for. Watch the cost: each quote is a judge call.

    The same limit stops handovers: in `runs/claudeworth-20261018-111357` Hobbs said "Here it
    is" of the sealed character he'd written and Hale said "I have it safe", but speaking turns
    can't `give`, and the item stayed with Hobbs.
15. **Phrase and regex checks on model prose: done.** Removed from the polish, with the
    instruction against period jargon; the instructions now welcome it. These broke the user's guideline (AGENTS.md: no
    hard-coded phrase or regex checks on what models write). All are in the polish
    (`src/manor/people.clj`): the phrenology jargon list on persona and manner (which also
    works against the user's wish to keep period jargon), the third-person check (subject He, She
    or the name; must begin "You"), and `appearance-problem` (begins "You", contains the name,
    trait words like "drinks" or "honest"). Remove them, keeping the schema and length checks;
    the instructions and review at roll time remain. Note that the appearance's trait-word check
    is the only automatic guard against leaking hidden traits into public text.
