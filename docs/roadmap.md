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
  - `manor.io.llm/random-policy`: picks a random *available* tool for the agent and generates
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
- **Files:** `deps.edn` (test.check), `src/manor/io/llm.clj`, new `src/manor/smoke.clj`, tests, and a
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
  - **Partly done (2026-10-29, `docs/settings-design.md`):** the agent preamble is now the template
    `resources/prompts/agent/preamble.md`, and a setting pack can replace it (or any other shared
    template) with a file at the same path under its `prompts/`. The GM preamble
    (`gm/preamble.md`) and the turn closing (`agent/turn-closing.md`) are templates too
    (2026-10-29); the conversation rules are still code in `prompt.clj`
    (`docs/prompt-extraction-design.md` 3.1).
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

The engine is an MCP client (`manor.io.mcp`, stdio only). A bridge spec `{:mcp {:server :pi :tool
"send_message"}}` calls an MCP tool; servers come from the scenario's `:mcp-servers`,
`config/mcp.edn` and the run option `:mcp-servers`. One server process per run, started lazily and
closed by `stop-run`. Tool costs reported in `_meta "manor/cost"` count towards the run.
`mcp-servers/pi/server.mjs` exposes pi (`send_message {session, message}`; one persistent
`pi --mode rpc` session per caller, sandbox command and tools configurable). The `quiz` scenario
uses it. Tests: `test/manor/mcp_test.clj`, with a fake pi. A real quiz run (3 rounds, two
contestants, pi through a sandbox wrapper) cost $0.003.

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
- **Files:** `src/manor/engine.clj`, `src/manor/io/store.clj`.

### B4. Calendar events and GM review — done

Implemented; see [the calendar design and outcome](events-design.md) and the scenario-authoring
contract. Parent validation and independent review passed, including the incomplete-review regression.
Focused offline calendar tests and the full suite pass; no real-model run was made.

- **Why:** an in-world schedule of holidays, expected visitors, rent dates and private GM events,
  distinct from the engine's event log and from confirmation that an outcome happened.
- **Agreed scope:** one-off specific dates/times; public, explicit selected-agent or GM-only
  knowledge; optional GM-only notes; scenario/operator/GM management. No recurrence, approximate
  dates, character scheduling or judge scheduling in the first version.
- **Context:** a date-refreshed calendar before recent events, defaulting to today through 30 days
  ahead, with only permitted descriptions/dates/times. Date-only entries never display 05:00.
- **Review:** batch due entries into a pre-turn GM intervention; date-only entries use 05:00
  internally, with today's missed review queued by fresh-run initialization. Doing nothing counts
  as reviewed. Preserve lifecycle state through replay/load/branch. Existing requests stay after
  turns. Calendar entries do not directly emit arbitrary state-changing payloads.
- **Bundled holiday seed:** both Claudeworth scenarios initialize the shared, explicitly dated
  `resources/settings/england-1904/calendar.edn` catalogue: English holidays, quarter days and seasonal
  observances, public to all characters. The normal rolling view and GM review apply; no annual
  generation or automatic holiday effects. Other worlds and the fixed household fixture opt out.
- **Separate work:** household income/account credits (B7 below). Existing travel, delivery and
  work timing remain on their current paths, rather than migrating to a universal scheduler.
- **Files:** `mechanics/calendar.clj`, `engine.clj`, `engine/round.clj`, `prompt.clj`, world-file
  support, operator/REPL helpers and focused fixtures/tests; scenario-authoring and README updated.
- **Export limitation:** audiences remain exact IDs. Existing map export excludes spawned people;
  supply their cast records before starting that map, or use saved load/branch for full continuation.

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
  `cache_control` on content blocks. Through pi (`--pi`, `manor.io.pi-llm`) pi-ai adds its own
  cache hints, so Anthropic caching needs no manor code there. Done: each request carries a
  per-caller `:cache-key` (run and agent), which pi sends as `sessionId` (OpenAI's
  `prompt_cache_key`, session affinity on OpenRouter and Anthropic); the direct OpenRouter client
  ignores it. In 10-round `openai/gpt-6-luna` runs it raised cached prompt tokens from 37%
  (`old-test-runs/pi-bridge-gpt-6-luna-10-rounds`) to 69% (`...-cache-key`).
- Per-agent token or cost budgets (pause or switch to a cheaper model when exceeded).
- A fallback model on provider errors.

### B7. State held by locations: largely done

`manor.mechanics.items` is an opt-in engine module: items at places, carried by people or inside
containers, keyed aspects on items and places (stores design phase 1), `take`, `put-down`,
`give`, `look`, `open`, `put-in`, and timed `work-on` jobs. A separate judge decides
aspect changes when work ends, using the time actually worked. Claudeworth has starting
items, shop purchases and a mock day that uses them. Resources and prices remain the scenario's
job (`:state` keyed by location id).

Claudeworth's general store now has a shopkeeper and adjudicated, buyer-accepted quotes rather
than a fixed inventory. Counter purchases create portable items only; larger orders can be
delivered from shops with a `:delivery-profile` ([deliveries design](deliveries-design.md):
done, with per-shop couriers, and checked in a real run). **Hand-over and `send-up`: done**
(branch `shop-hand-over`, mock-checked): `buy` is free and usable on a line, and takes or leaves
the goods; goods left at a shop carry a `:waiting` marker until collected, and a delivering
shop's clerk sends them up within 1–3 hours. Next, with the user's go-ahead: a short real run
to count goods left and never collected or sent, and whether clerks still promise deliveries
they don't make (the design's Not addressed). **Order books: done** (mock-checked): every
purchase is recorded with where its goods are now, the household sees `household.orders` and a
shopkeeper `shop-orders`, each even when empty, and the work, finder and conversation judges see
the same, so a promise or a wrong summary can be checked against what was bought (deliveries
design, Order books). Next: a short real run to see whether characters read the book and correct
themselves. Internal appraisal queries share the work judge's bounded LLM-call machinery.
Sterling uses integer old pence. Store mechanics tests own a tiny cast; household choreography
and exact container contents use fixed test-owned worlds and scripts. Bundled content gets generic
smoke/replay checks. Claudeworth now also has a cook, scullery maid, pantry shelves, servants'
quarters and a butcher reusing the profile-grounded shop service (design decision 38).
See [the store brief](archived/general-store-brief.md) and Claudeworth design decision 36. Old-run compatibility is intentionally not maintained.

**Village trades content: done** (Claudeworth design decision 44). A draper, public house,
sub-post office, forge and sweep's, barber's and laundress's cottages have resident agents,
bedrooms and quiet routines. Portable counter goods reuse quotes/buy; the draper joins the
general store and butcher in sending larger orders up to houses, with the default errand-boy
courier. Service work reuses `spend-time` on actual items, places or people. No new mechanics.
Tradespeople, phases 1 and 2 done (2026-10-31, mock-tested; [trades-design.md](trades-design.md)):
`recommend` with a `trade` makes a contact, on the map or from elsewhere, and `send-for` with the
contact and a job is a visit expected 3 hours later. Next: a short real run; then merchants by
letter (phase 3).
Remaining, only if requested: paid-service quotes/payments, appointments and automatic trade
visits, collection/return of washing, postal transport, postal orders/savings/telegrams, and
public-house guest rooms or paid hospitality. Buying stamps is not sending mail, and discussing
a job does not schedule or perform it. No service charges or bills are implemented.

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
  Since 2026-10-30 a shopkeeper can charge the household for goods they send up without a sale at
  the counter (`send-up` with `goods`; deliveries-design), still paid at once.

- **Household income / account credits.** The account has starting funds and spending reducers,
  but no supported action for crediting rent, royalties or other income. Add an engine-validated,
  event-recorded way to credit it, with an amount and reason, and decide who may invoke it.
  Calendar entries may describe expected payments before this exists; neither a due date nor
  a GM announcement should be treated as money received. Income mechanics remain separate
  from the calendar/timed-events work.

**[Stores, aspects and the scene judge](stores-and-aspects-design.md): phases 0–7 done**
(phase 7 mock-tested; its next real-model check needs the user's go-ahead). It answers the meals, room-conditions and GM-tools
follow-ups below, replaces item conditions with keyed aspects (done), and removes the unused
provisions counter (done).

- **Concealment and knowledge — investigation only:**
  [discussion proposal](concealment-and-knowledge-investigation.md) explores hidden aspects,
  concealed items, confirmed discoveries and stale knowledge across household and palace
  scenarios. It identifies interactions with judges, event privacy, finding, access and item
  lifecycle. No implementation or design decisions approved yet.
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
- **Deferred, low priority: expose item restrictions to the scene judge.** Project existing
  fixed-item and identity-preservation restrictions so the judge can avoid invalid removals.
  No mechanic changes; review the shared steward projection too. The user prefers leaving this
  until needed. Proposal and separate open questions about fixed furniture are in
  [the stores design](stores-and-aspects-design.md#deferred-expose-item-restrictions-in-the-judges-scene).
- **Promoting things outside the `made` route.** `made` is the only way the work judge creates
  an item, and the instructions scope it to things really produced by the activity or fetched
  from a store; a particular thing that is neither in the scene nor carried "only such a thing
  doesn't exist". So a plausible household object a job assumes — Hale's day-book, which ended up
  as prose in the study's state aspect (`runs/claudeworth-20261022-212429`) — can never become
  real, and `find` is left hunting a phantom. Code would accept such a `made` entry
  (`made-problem` checks only `from`, `in` and aspect bounds); it is the instructions that forbid
  it. Decide later whether work may promote a named, plausible object, and, more generally, let a
  thing described in a room's, item's or person's aspects be promoted to an item (and demoted
  back), now that promotion/demotion may become frequent. Related: known issue 16, and
  [the find-context design](find-context-design.md).
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

A graphical scene page as the new main page (an automatic plan of rooms and regions, clickable
people, rooms and items) is tracked in [scene-view-design.md](scene-view-design.md). Phases 1–5
are implemented and integration-checked: pure historical layout and offline preview, the scene at `/` and retained panel at
`/panel`, and linked, omniscient person/place/item inspectors. The scene has directed routes,
people/travellers, run controls, bounded feed, coherent SSE refresh and navigation. Inspectors show
private inner state and durable recorded calls/replies, including rejected attempts on loaded runs;
requested tools are distinguished from event outcomes. See [scene-layout-api.md](scene-layout-api.md)
and [scene-web-api.md](scene-web-api.md) for APIs, checks and evidence limits. Presentation-only
hooks now supply Claudeworth funds/plans/purses, replayable construction ghosts and built-room
size evidence; generic hours shading and evidence-based busy arcs are implemented. Cross-seam
regressions and a same-options mock/offline comparison against `0f7eae9` verify unchanged worlds,
events, agent views and complete model requests, with rendering and optional hooks enabled/removed.
Actual Chromium checks cover village, Claudeworth, no-space quiz and both pages. Manual visual,
touch/cross-browser checks remain. Independent review confirmed the simulation boundary and
identified four presentation defects, corrected with regression tests: connection visibility,
shared-wall doors, encoded agent links and retained-panel live-feed behaviour.

- Charts of chosen state values over rounds. A scenario could declare `:metrics (fn [world] {..})`,
  sampled at round end.
- Run switcher (list `runs/`, load, branch from the UI).
- Automatic scene layout is implemented at `/` (domains, floors and domainless components);
  `/panel` retains its original grid/circle map. Dense-graph connector/tooltip polish and manual
  legibility checks remain, rather than a promise that all graphs can share walls.
- **Done:** schema-generated human-seat action forms at `/panel`, with tool instructions,
  required/optional fields, numeric bounds, enums/booleans, text and string lists, plus JSON for
  complex values and an advanced whole-object editor. Drafts/focus survive live refreshes; engine
  replies and stale-form errors are visible. Fixed submission to the wrong URL (the `action`
  dropdown shadowed the form URL), and human tool availability now refreshes after each call.
  Mock/offline engine/HTTP tests and actual Chromium commands cover free/query/final actions.
- **Done:** a roster button switches a seat between `:llm` and `:human` mid-run (`e/update-agent!`).
  The roster was cut to four narrow columns so it fits the panel's 420px column; aspects, inner
  state and model moved to a Seat section on `/agent/<id>`, which has the switch too.
- **Done:** human seats converse: they can start a conversation and be drawn into one, and each
  of their lines waits for panel input, headed by the line, the others in it and what's been said.
- Releasing a seat that's already waiting for human input straight back to the model: the switch
  queues behind the blocked round, so today the human must end that turn first.
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
  **Candidate waiting/home fix implemented:** a full 24-hour grace since arrival or latest
  conversation (clockless worlds: 24 rounds), with briefs to wait locally while hiring is
  pending. Potential hires get a dedicated generated off-map `:candidate-home`; no internal
  area ids are presented as their addresses and `:origin` is unchanged. Explicit departure
  remains possible, and courier/other visitor timing and homes are unchanged.
  **Descriptor-free generation implemented:** the server rolls basic traits/definitions only;
  the writer supplies one or two external circumstances with persona, manner and appearance.
  Raw readings stay in private prompts under **Faculties**; reputation selects readings and
  written facts. See design section 3.7. Clearer period wording remains future work, without
  dropping or replacing the basic data.
  Real simulation acceptance runs remain with the user; outdoor housing is deferred.
- **Changing posts (built, mock-tested):** `docs/role-change-design.md`. Appointers (a
  position capability, `:appoints`) can `appoint` and `dismiss` through a light judge that
  refuses only the absurd, and `assign-bedroom` without one; the GM has `change-post`; the
  dismissed `take-leave` from anywhere; everyone's rules say the mechanism exists. The short
  real run of the design's phase 4 waits for the user's go-ahead.
- **Dismissal's edge cases (later):** dismissal is supported simply: post `:none`, household
  and bedroom cleared, and `take-leave` from anywhere. Not handled: a dismissed person with
  nowhere to sleep who never leaves, a dismissed villager's shop, workplace and routine left
  behind, bringing an authored character back once they've left (they have no candidate
  record), and any notice period. Come back to these if dismissals happen in real runs.
- **Hire existing villagers (deferred):** allow an established village character to take a manor
  job only once in-world profession changes and filling the village jobs they leave vacant are
  supported. Recruitment must keep the same agent, identity, memories and relationships, not
  generate or summon a second person. Review the old post's workplace, routine and services
  when someone leaves, and provide a way to recruit a replacement for the village vacancy.
  Until then, the shared hiring prompt and `recommend` tool description tell characters not to
  recruit or recommend established villagers. New candidates may still live locally, and
  characters may describe knowing them through local acquaintances; `recommend` supplies
  their actual names and details before anyone is named or promised.
- **Recommending in conversations: done.** In `runs/claudeworth-medium-20261025-134639` the
  people asked for a kitchen girl (Mrs Dobbins, Briggs) were asked in conversations, where they
  had no `recommend`, so they made up "young Jenny at the labourer's cottage" and "my niece in
  Norfolk". Claudeworth's speaking turns now offer `recommend` (to someone in the conversation),
  and the hiring rules are steps for each side: looking for staff, being asked, looking out on
  another's behalf, bringing, engaging, waiting.
- **Leaving with a return time; bargains struck in conversation: done.** In
  `runs/claudeworth-medium-20261025-145921` Levi Baker, agreed with but never engaged, took leave
  to fetch his things meaning to be back at six, and as an away agent he never could. `take-leave`
  now takes an optional `return-date` and `return-time`: a candidate comes back to the meeting
  place then (status `:returning`), a guest from elsewhere through a new visit, with calendar
  entries for them and those expecting them. `engage`, `accept-place` and `decline-place` are free
  and offered on speaking turns, and the hiring rules say talk engages nobody.
- **Hiring as an errand (proposed):** in that run the Claudeworths asked Hollis to "send her
  up" while they were away. `send-for` could take an optional person to ask for, who gets a
  calendar entry for the arrival at the meeting place, so a go-between can send someone up and
  the household expects them.
- **Candidates by letter (proposed, lower priority):** let `invite` carry an optional position
  applied for, so a letter to "my niece in Norfolk" brings a real candidate through the
  invitation judge. It overlaps with `send-for`.
- **Making invented people real (if invention persists):** the conversation judge could report a
  named outsider put forward for a post, and the engine would create that candidate under the
  given name. Needs the people server to accept a name; a stated home on the map clashes with
  the candidate's off-map home.

### B12. Person aspects

- **Why:** characters' state never changes. A judge's summary said Lady Claudeworth was "evening
  gown on, hair done", but only the room's aspects could be recorded
  (`old-test-runs/claudeworth-stores-phase6`), and a cook sweeping the flue stays clean.
- **Design:** [person-aspects-design.md](person-aspects-design.md), with the user's decisions of
  2026-10-20. People get open, keyed aspects that everyone present sees; `spend-time` changes
  the worker and the people named in `on` who are there at the end; the nightly steward removes
  sleepers' aspects that wouldn't last the night. Three phases, tested with a fixture world.
  **Done (all three phases), mock-tested only:** the fixture is `test/fixtures/dressing.edn`
  with `test/manor/scenarios/dressing.clj` and `test/manor/person_aspects_test.clj`. The design's
  defaults were taken as written (the name aspects, the cap of 4, only the judge, steward, views
  and GM see them, people made during a run start with none). Every activity with time worked is
  now judged, even at an empty place, because the worker can change. Not yet seen: how a real
  judge and steward handle people (a short real run of the fixture or Claudeworth, with the
  user's go-ahead).
- Follow-ups, from the same decisions:
  - **Private states:** moved to B13 (inner state), with the user's decisions of 2026-10-22.
  - **Fixed keys alongside open ones:** some keys always present for everyone (`:dress`, say),
    with the rest open as now. It would give the views a stable order and let the steward or
    code treat known keys predictably.
  - **Appearance worded as habit:** the people writer (`manor.mechanics.people` polish and appearance)
    may need to write changeable things as what's usual ("usually in a print frock and
    apron"), so the fixed baseline doesn't contradict aspects. Check what it writes for
    clothing first.
  - **Bystanders:** whether the judge may change people present who weren't named (soot over
    everyone in the kitchen while the flue is swept), and how to keep that from becoming a
    licence to change everyone in the room.
  - **Quick changes:** a free or short way to change your own state ("taking off my apron")
    instead of half an hour of `spend-time`, perhaps like quick put-away jobs. Deferred as too
    complex for the first build.

### B13. Inner state

- **Why:** characters have no inner state. Nothing records that a rebuke stung, that a day at
  the copper left someone aching, or that a worry kept them up.
- **Design:** [inner-state-design.md](inner-state-design.md), with the user's decisions of
  2026-10-22. A private `inner` state per person, deliberately generic (moods, preoccupations,
  physical things that don't show): a few fixed keys (`mood`, `body`, `mind` for Claudeworth)
  plus up to 2 open ones, as short judged phrases with no meters, levels or automatic fading.
  The work judge, a new conversation judge (one call per conversation) and the nightly steward
  change it; the person only reads it. About 5% more per run. Three phases. **All three
  built** (data, views, the work judge, Claudeworth's `:inner` and rules; the conversation
  judge; the nightly steward, which now sees everyone asleep or hibernating and updates their inner
  state for the night that passes),
  first checked in a 13-round real evening run (`old-test-runs/claudeworth-inner-state-evening`:
  no failures or leaks; the steward's instructions and hibernators were revised after it). Next:
  a morning run, to see the conversation judge on a full day's talk and whether characters act
  on their inner state. The defaults in its section 4 were taken as written.
- Follow-ups, from the same decisions:
  - **Do characters act to improve their state?** The hoped-for result is that they do,
    through ordinary actions (resting when tired, seeking company when low, keeping away from
    someone who upset them), with no "improve my state" action and no instruction to manage
    it. Investigate after the first real runs with inner state: have a model read the
    transcripts' thoughts and actions beside the inner state shown at the time (no phrase
    matching), and report examples and whether the rules' wording needs a nudge.
  - **Periodic reflection** (the design's option C), only if the judges leave gaps: overheard
    remarks, news, long waits, states that never change, people who never sleep. A few times a
    day, one cheap call per agent over only their own visible events since the last pass sets
    their inner state. It has the same shape as B2's rolling summary, so one call could do
    both, and later update situations (people open question 7) or suggest small goals. About
    4% more per run at once every 6 rounds per agent.
  - **How people regard each other:** B18 (relations).
  - **Overhearing:** whether the conversation judge should also change people present who
    weren't members.
  - **Serious harm** (plots, fights, injury, illness, death): no special guidance or limits for
    now. If a judge decides something grave happened, it stands. Revisit only if it causes
    problems in the simulation.

### B14. Visits and guests

- **Why:** nobody could come to the manor except candidates and couriers. The household couldn't
  have anyone to dine or to stay, and the medium household's son had no way to come home
  (household proposal, decision 6).
- **Design:** [visits-design.md](visits-design.md), written and revised with the user's
  answers on 2026-10-23. **Phase 1 is done** (guests in the world: `invite` with `who`, GM and
  operator visits, managed calendar entries, the all-day hibernation guard), and so is **phase 2
  part A** (guests from elsewhere: world-file `:away?` characters and the son, the GM's new people,
  arrival at the meeting place, `you.visit`, the shared `take-leave`, `away-acquaintances`) and
  **part B** (`invite`'s `person` and the invitation judge), and **phase 3** (authored visits: a
  world file's `:visits`, made at a fresh run's start; the medium household's son comes home from
  Eton on 20 December). **All three phases are done, mock-tested only:** a short real-model check,
  including the design's one look at a judge-made guest, waits for the user's go-ahead. It covers:
  - an `invite` action for anyone (in person, by post from the post office, or by any
    messenger present) and a GM `schedule-visit` for secret visits, making one visit record,
    scheduled by code with configurable lead times;
  - an invitation judge that settles who a description means ("my father, Baron von
    Visitsworth"): an existing person, or a new one written and spawned `:away`;
  - away characters in world files (the son), never evicted, and authored visits in world files
    (the son's homecoming); valid past visits are skipped after the final `:start` override,
    with no catch-up, and `:leave-out` drops omitted guests and trims their audiences;
  - villagers kept awake all day when invited, coming by themselves;
  - guests from elsewhere appearing at the meeting place, seen only there and by the host;
  - guests leaving when they choose with `take-leave` (no timeout, no `send-away`).
  Final user fixes are implemented and covered offline: scheduling a guest already staying from
  elsewhere sends no guest calendar entry; a three-round medium-household homecoming replays
  exactly. Bedroom lists intentionally show all active assigned sleepers, even secret guests;
  invitation/calendar privacy otherwise stays unchanged.
  Physical-status integration is mock-tested: disabled movement/unconsciousness blocks automatic
  arrival and departure, death ends/lapses visits without a return, sight filters arrivals and
  departure memories, and in-person invitations cannot transmit muffled speech.
  It shares hiring's arrival and departure code but keeps separate tools. Three phases; section
  13 records the decisions; none are open.
- **Deferred, by the user's decision:**
  - **Coherent comings and goings:** a railway station, a hired fly or the guest's own
    carriage, timetabled arrivals, luggage and attendants. Until then guests from elsewhere
    appear at the meeting place and vanish when they leave.
  - **Replies and refusals:** an invitee who declines, proposes another day or doesn't answer.
    Until then every invitation is accepted, whoever is invited, and anyone may invite.
  - **A larger postal or remote communication system:** letters as items, postal transport
    and delivery, telegrams, correspondence with people elsewhere, with invitations as one kind
    of message.
- **Also deferred (design section 14):**
  - cancelling invitations in-world;
  - invitations to places other than the manor;
  - a `recommend`-like query for guests;
  - postage costs;
  - per-place travel distances for people from elsewhere.

### B15. Physical status

- **Why:** nothing physical holds anyone. A bound butler can walk away, a gagged one can still
  talk, nobody can die, and an act on someone (`spend-time` with `on`) lands only when the job
  ends, so a fleeing burglar can't be stopped. Murders, burglaries and the like need it.
- **Design:** [physical-status-design.md](physical-status-design.md), written with the user's
  decisions of 2026-10-24. **Phases 1–2 built and mock/offline-tested:** statuses, death,
  impediments, faculty enforcement and perception, the work judge, immediate `act-on` with
  held-item transfers, and nightly recovery. The burglary mock night covers binding/gagging,
  silver theft, a footman's seizure and recovery of jewels, coming to, an ankle easing without
  ropes untying, replay equality and no muffled-word/blind-view leaks. The contract is in
  `scenario-authoring.md`. It covers:
  - fixed faculties with levels that code enforces: movement, grasp, speech (impeded or
    disabled), sight, hearing and consciousness (disabled only), each with an optional cause;
  - death as `:status :dead`;
  - open impediments as a new subcategory of person aspects;
  - `act-on`, an immediate act on other people, judged within the turn, which may move held
    items;
  - the work judge setting all of it, and the steward clearing or easing it overnight;
  - one perception hook in `emit!` for sight, hearing and the unconscious.

  **Real-model compatibility smoke check passed:** the user's requested four ordinary
  Claudeworth rounds, 121 calls ($0.04536), nine work judgements and seven conversation
  judgements, no engine errors/forfeits/failed judgements, exact replay. Bridge calls were
  stubbed; ordinary argument/limit rejections were handled and play continued. Preserved in
  the physical-status worktree at `old-test-runs/physical-status-4round-real-1792872662451/`.
  **Optional follow-up, requiring fresh go-ahead:** a targeted real-model burglary/overnight
  check for sensible physical outcomes, immediate versus timed actions, steward recovery and
  behaviour/privacy. Those were covered by mock tests, not this ordinary morning run.
- **Deferred (design section 13):**
  - dead bodies as items that can be carried, hidden and found;
  - GM tools for statuses;
  - arrests (a constable, a lockup);
  - impeded sight and hearing;
  - the posts of the dead;
  - dragging people;
  - combat resolution beyond the judge.

### B16. Following: done

- **Why:** someone told "follow me" couldn't. Turns run one after another, so by the time they
  acted the other had gone, and they chased each other round the house (Emily and Dorothy Smith
  in `runs/claudeworth-medium-20261024-235343`, 19 Dec 09:00-10:00).
- **Built:** `follow {who}` and `follow-stop`, free, also offered in conversations' speaking
  turns, so someone asked to come along can follow in the same line. Followers go with the
  person they follow on every `walk` and `travel`, at once and within that person's turn; a
  busy follower stops their work to go, and one who can't (asleep, unable to move, no longer
  with them) is left behind and stops following. Contract under Locations in
  `scenario-authoring.md` (`manor.mechanics.following`). Mock-tested only so far.
- **Not done:** the village scenario's `move` doesn't take followers along; a real-model check
  of whether agents use it when asked to come along.

### B17. The rest of the prompts in templates

- **Why:** to read and tune every prompt in one place, and let a setting pack reword any of it.
- **Now:** every block of instructions and every tool description of the household mechanics
  and builtins is a template (`docs/prompts.md`). Request framing (section headings, the
  conversation rules), replies, rejection reasons, narration and view text are still code.
- **Approach:** `docs/prompt-extraction-design.md`: the framing as templates first, then
  message catalogues for short strings if wanted. It has open questions for the user.

### B18. Relations

- **Why:** nothing records how one person feels toward another. A rebuke can set someone's
  `mood`, but not who it was toward or why, and that fades from view once the events window
  moves on.
- **Design:** [relations-design.md](relations-design.md), with the user's decisions of
  2026-10-29. Each person keeps, per other person, a queue of the last 20 judged feelings
  (`{feeling, because}`), added (never edited) by the conversation judge and the work judge
  among the people they already judge. The view shows the latest 3 toward each person present,
  under `you.feelings-toward`. No new calls. **Built;** first real run
  `old-test-runs/claudeworth-relations-morning` (6 rounds): no leaks or failures, about 80
  tokens a view. Open from it: the judges add an entry for nearly every civil exchange, and
  `because` sometimes calls the holder "her" instead of "you" (3 of 26) or mixes the two up
  (1 of 26). See the design's "The first real run".
- Follow-ups:
  - **`recall`:** a tool for looking up more about someone: at first every entry toward them,
    later more (where they were last seen, what was said). It would cover people who aren't
    present, whom the view doesn't show.
  - **Cleaning up descriptions:** once starting relations exist, move feelings toward
    particular people out of personas and situations into them.
  - **Third parties and overhearing:** feelings toward people only talked about, or from
    witnessing others (a quarrel in the next room), are out of scope for now (B13's periodic
    reflection would be the natural place).

### B19. Finding and recording inferred things (possible additions)

- **Why:** `runs/claudeworth-medium-20261107-132710`. Mr Merrick's timed work in the pantry used
  "the household book", which wasn't in the scene; the work judge accepted it as an inferred
  everyday object but recorded nothing ("it doesn't seem to be listed, so I can't change it"), so
  the copied plate tally lived only in narration, and later finds matched the only tracked
  household book, carried off by Lord Claudeworth elsewhere. The finder's `inspect`
  (`docs/find-context-design.md` phase 3) now lets it see that; these would go further.
- **Work-judge rule: done (2026-11-07).** `scene.md` has a fourth `made` source: an everyday
  object the work used that had no record, when the work leaves something in or on it that
  someone will come back for. Checked on Merrick's two jobs, 3 samples each
  (`old-test-runs/scene-b19-*`): counting against the book recorded nothing (right); copying the
  tally into it gave Haiku a pantry aspect 2/3 and DeepSeek a made "Household book" 3/3. The
  judge can't see things elsewhere, so a made item may duplicate one (DeepSeek's would, beside
  the account book Lord Claudeworth carried): next, a duplicate check (below).
- **Duplicate check for made items (next):** when a work answer makes an item with no `from` or
  `taken` (about 8 of 104 jobs in that run), send it back once with the finder's candidates and
  one `inspect`; the judge confirms the new thing (fine for things that can have many, a loaf)
  or names the existing one, and if that's not on hand nothing is made and the summary says so.
  Never change aspects of things not on hand.
- **Finder candidate search:** a lookup tool (`ask-structured!` `:lookups`) that searches all
  items by keywords the finder proposes, to build its candidate list instead of the 60 most
  recently touched.

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

    *Done (2026-10-23):* `:housemaid?`, `:architect?` and the `:optional?` marker are gone.
    Instead the run option `:leave-out` drops any world-file characters by id, with what they
    hold; `remove-absent-cast` stays, so the mock day skips calls to anyone left out, and the
    earl's script skips commissioning when no plan was drawn. The clerk path is gone:
    `draw-plans` needs the `:architect` capability. The wider "cast changes that don't break
    things" check below is still open.

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
    (`src/manor/mechanics/people.clj`): the phrenology jargon list on persona and manner (which also
    works against the user's wish to keep period jargon), the third-person check (subject He, She
    or the name; must begin "You"), and `appearance-problem` (begins "You", contains the name,
    trait words like "drinks" or "honest"). Remove them, keeping the schema and length checks;
    the instructions and review at roll time remain. Note that the appearance's trait-word check
    is the only automatic guard against leaking hidden traits into public text.
16. **Talk treated as action, and how salient `carrying` has become (follow up).** In
    `runs/claudeworth-20261019-233942` (round 14) Dobbs told Nellie "take them and welcome, here,
    all seven" of sugar packets he never held (his thoughts noticed his `carrying` had only a
    letter), Nellie took them as received, and the judge's summary of her "counting the seven
    packets against Daisy's seven" said the tally came square. She then searched for them, the
    find failed, and "seven packets came in and vanished" ran through the house for a day.
    Briggs, never told his order went out, insisted his "packets under the ledge" were still
    there. Separately, the order's engine id (`order-312`) reached his notes and then everyone's
    talk.

    *Done (branch `talk-is-not-action`):* Claudeworth's rules say talking never changes
    anything (things change hands only with the item actions and change only by `spend-time`;
    you hold what's under `carrying`; never speak of views, tools or ids); the speaking turn's
    "How to answer" and `give`'s doc say the same briefly; the agent's own `:you :carrying` is
    always in the view, `[]` when empty; the scene judge is told what the worker carries and
    that an assumed particular thing (brought, handed over, set aside) that isn't there didn't
    happen; order ids are no longer shown.

    *Evidence (prompt replay, `dev/replay.clj`, 5–10 samples per moment, about $0.10):* Dobbs
    asked for the packets "hands them over" 5/5 before, 0/5 after; Briggs insists on his
    packets 3/5 before, 0/5 after; Nellie claims to hold them 5/5 before, 0–1/5 after (the 1 in her
    next turn, once she already believed it), but only once `carrying: []` was shown (without it her thoughts said "my view doesn't show
    `carrying`"); the judge says the packets weren't there 0/5 before, 9/15 after, and wrote
    the phantom packets into the pantry's stock aspect 2/5 before, 0/15 after. No replayed line
    spoke of views, tools or ids. A replay can't show what happens over many turns.

    *Follow-up research:* does this make "carrying" too salient? `carrying` lists notable items
    only, so it is fuzzy at the edges, and the new texts lean on it. Watch for:
    - worn clothes and everyday things treated as absent: a character saying she isn't carrying
      a cap, or refusing to use an apron, needle or basket because it isn't listed. An earlier
      judge wording refused Nellie's apron mending ("her torn apron and Mrs Finch's work basket
      were not there") once in 5; the shipped wording, which names clothes and everyday things,
      didn't in 10, though one summary still remarked that "the work basket and apron were not
      among the things to hand";
    - things inside a carried container, which don't show in `carrying` (a basket's contents);
    - the judge denying a store's supplies: 4–5 of 10 summaries said "neither the packets nor the
      sugar" were in the pantry, when the pantry keeps sugar and the stock text happened not to
      mention it (it had been rewritten by an unrelated stocktake);
    - entrenched beliefs: given `carrying: []` after already believing she held the packets,
      Nellie once decided she had "dropped them on the way up", a new vanishing story;
    - more `look` and `find` calls spent checking what they hold, or "carrying" turning up in
      speech in a mechanical sense.

    Method: read a real run's transcript for these, count `look`/`find` per turn against older
    runs, and replay the moments that go wrong with `dev/replay.clj` (it rebuilds the tools and
    checks them against the recorded digests). Fixes to consider if it's a problem: describe
    worn and everyday things in the rules rather than leaning on `carrying`; give the judge the
    worker's carried things only when the activity names one; or drop the judge rule and keep
    only the agent-side texts.

    *Follow-up (the lantern, `runs/claudeworth-20261020-200100`):* at the Crown, Turner and Polly
    offered the Earl "the good lantern" in a conversation. No lantern existed, and both sides
    believed it had been lent for a whole night. Turner couldn't have made it real: Claudeworth
    refused `find` at every place with `:services`, the keepers' own premises included. *Done:*
    `find` now works everywhere unless the option `:find-everywhere? false` (the old refusal);
    the rules say goods for sale are bought, never found, and that to hand over or lend something
    you don't carry you `find` it, then `give` it; the speaking-turn rules say being told you were
    given something doesn't put it under `carrying`. A conversation's end now says, to everyone
    present, when nothing changed hands in it, and the initiator's summary adds how to hand
    something over. Not yet checked in a real run: watch for promised things being found and
    given after the talk, and for `find` used for stock at a shop or the bar.

    *Follow-up (Mrs Thorne's groceries, `runs/claudeworth-medium-20261107-125427`, Haiku):* she
    bought tea, candles and meat to carry, told Bessie in two conversations to take the meat to the
    meat safe, and never handed it over or put it away; Bessie kept asking. Lines had no `give`, and
    the hand-over hint went only to the initiator, who here held nothing. *Done:* every world with
    items now offers `give` on a line to a speaker who carries something, limited to the
    conversation's members, and the speaking-turn rules say to `give` on the line. The ending's
    "nothing changed hands" sentence and the initiator's hand-over hint are gone: with buying,
    sending up and giving all possible inside a conversation, counting hand-overs said little.
    The scene judge is also told to weigh what the worker carries: whether it helps, hinders or
    makes no difference to the activity. Not yet checked in a real run.

15. **Tests no longer wipe `roll-cast!` records or other runs.** Done: the wholesale deletion
    fixtures are removed. The test runner retains each invocation and JVM worker under
    `test-runs/tests/<invocation>/worker-N/`; engine fixtures wait for final writes and stop
    forgotten runs, without deleting evidence. Cleanup is manual, after stopping active runs
    and preserving paid records. This removes the race with asynchronous shutdown as well as
    the earlier loss of two paid cast-generation records. The suite uses up to four isolated
    JVMs, not in-process parallelism (`with-redefs` mocks are global). Generated world files
    now use plain EDN serialization, with semantic round-trip/export tests rather than
    formatting assertions; repeated live-map pretty printing was the largest serialization
    bottleneck. Concurrent model-call log writes are also serialized per run, preventing
    multi-buffer EDN entries from interleaving when seats or judges finish together; a
    concurrent-writer regression covers it. See README's Tests section for serial/parallel
    commands and retained logs.

    **Speed investigation:** the runner now records namespace/test-var timings and balances
    namespaces using the checked-in estimates in `test/test-costs.edn`. Production fixes avoid
    compiling identical tool argument schemas on every step, rescanning the entire world
    for each cast field, and reading model logs for non-agent inspectors. Agent inspectors
    reuse parsed calls for an unchanged log (one-entry cache, invalidated by file identity,
    size or high-resolution modification time); completed-line/partial-append semantics remain.

    Measurements in the `test-speed` worktree on JDK 27, including CLI startup:
    - Original four-worker suite: 193s; a second instrumented/JFR run: 152s.
      Original worker times were 73/175/97/101s: a substantial imbalance.
    - Updated four-worker suite: 120s, workers 103/95/103/96s; serial: 113s.
      Both final checks passed with 552 tests and 10,299 assertions.
    - Eight workers regressed (156s in one intermediate configuration). C1-only compilation
      reduced startup but slowed the heavier tests. No JVM tuning flags or higher worker
      default were retained.
    - Other concurrent suites made later comparisons noisy and triggered the existing
      layout performance ceiling in two experimental runs. A final two-worker attempt
      timed out at 180s under competing load; it is not a usable benchmark.

    Retained evidence under this worktree's `test-runs/tests/`: original
    `60f4e688-2592-485f-9a8e-3cecb2dea749`, profiled
    `6dc60bc1-b17f-4020-9379-dbdb32fedfe2`, final four-worker
    `ec344d73-2fea-417c-a422-2e259c729e92`, final serial
    `45ad367e-3950-4a5f-a378-2882df05a283`. All runs were free/offline.

    **Under one minute remains open.** The major costs are JVM loading/compilation, full-day
    simulations and scene boundary/rendering checks, not external network waits. Explicit
    test sleeps are 300ms, 200ms and a few 2ms timestamp checks; `wait-idle` polls every 50ms,
    and timeout/termination waits normally return immediately on completion. Retry backoff
    tests stub the sleep. MCP coverage starts local Node/Babashka processes and deliberately
    exercises one 500ms cancellation. No sleeps or timeout coverage were removed.
    Next: benchmark on an otherwise idle machine, profile the remaining scene/layout work
    and persistence, and investigate reusable isolated worker JVMs or AOT loading to avoid
    paying repeated cold-start costs. Do not parallelize global mocks within one JVM or
    skip integration assertions to reach the target.

16. **A world file with more than one form is now refused.** Done with the `claudeworth-medium`
    work: `read-file` reads a second form and throws, instead of letting `edn/read-string`
    silently ignore everything after the first. Before, a missing or stray bracket dropped
    `:items` and `:agents` from a file that still looked as though it loaded. `worldfile_test`
    covers both a trailing map and a stray bracket.

17. **Provider HTTP 400s no longer pause on the first failure.** Done: OpenRouter responses
    with `error.metadata.provider_name` now use the existing bounded retry policy (three
    attempts, 2- then 4-second waits), alongside network errors, 429 and 5xx. Plain request
    validation 400s and other 4xx still fail immediately. This covers the reported Baidu
    `invalid_argument` response without trying to interpret provider prose. Exhaustion still
    follows the existing error/pause path, now with the attempt count in the error. Offline
    HTTP-stub tests cover recovery, unchanged requests, exhaustion and non-retryable errors;
    no paid calls were needed.
