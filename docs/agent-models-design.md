# Per-agent models

Proposal: let a run put chosen starting agents on a model other than the run's `:model`.
Not started. The user's decisions of 2026-10-31 are in section 5; caching (2.2) is still open.

## 1. What already exists

Most of the mechanism is in place:

- An agent record may hold `:model`. `b/spawn-events` copies it from the spec
  (`src/manor/mechanics/builtins.clj`), and `e/spawn!` and the panel's spawn form accept one.
- The agent's own turn uses it first: `(or (:model agent) (when gm? (:gm-model opts)) (:model opts))`
  (`src/manor/engine/seat.clj`), and so do their speaking turns in conversations
  (`src/manor/engine/talk.clj`). These are the only two places an agent's model calls are made.
- Judges, the steward and adjudicators don't use it: they go through `judges/ask-structured`
  with `:judge-model` or `:model`, so the referee stays the same whoever the actor is.
- `e/update-agent!` (and `update-agent!` in `dev/user.clj`) emits `:agent/updated` with
  `{:model ...}`, so the model can change mid-run, and replay and branching follow it.
- The panel's agent page shows the model ("run default (...)" when there isn't one), and
  `llm.edn` and the transcript record each call's `:model`.

A world file's agent can already carry `:model` today without being told to. The `Agent` schema
is an open map and the ruleset passes agent maps through to `spawn-events`. A mock check on
`claudeworth.edn` with `:model "test/alt-model"` on Lord Claudeworth sent all five of his calls
with that model, everyone else's with the default, and every judge's with the default too. It isn't
documented, it isn't checked (a non-string would get through), and `--model` doesn't override it.

## 2. What's missing

1. **A run-level way to choose.** Proposed: an option `:agent-models {"hale" "anthropic/claude-sonnet-5-5"}`
   keyed by agent id, and a repeatable CLI flag `--agent-model hale=anthropic/...`. `init!` in
   `src/manor/engine.clj` assocs `:model` onto the matching starting specs before spawning and throws
   on an id that isn't in the starting cast after `:leave-out`. It's saved in `config.edn` along with
   the other options. It applies only at the start, so the GM, hires and guests who arrive during the run
   keep their defaults (the GM already has `:gm-model`). Starting guests marked `:away?` are spawned
   at the start, so they're covered.
2. **Prompt caching for Anthropic models.** DeepSeek, OpenAI and Gemini cache on their own; on
   OpenRouter Anthropic models cache only with `cache_control`. A single top-level
   `"cache_control": {"type": "ephemeral"}` turns on automatic caching (OpenRouter's prompt-caching
   guide). `manor.io.llm` sends none. Agent prompts average about 12k tokens and about 85% of them
   come from the cache (`old-test-runs/claudeworth-relations-morning`), so without this a Claude agent
   pays full input price on every step. Small change in `OpenRouter/complete`, limited to `anthropic/`
   models.
3. **The cost report assumes one model.** `run-pricing` in `src/manor/tools/cache_report.clj` takes the
   first call's model for `simulate` and `compare`, and `resources/pricing.edn` only knows DeepSeek.
   `report` already prices each call by its own model but shows `:unknown` for models it has no
   prices for. Needs per-call pricing in simulate/compare, plus entries for the models in use.
   Real cost is unaffected: it comes from OpenRouter's usage per call, so `:max-cost` still works.
4. **Visibility.** The transcript header could list the agents not on the default model. Optional:
   a model field on the panel's agent page that calls `update-agent!`, next to the controller switch.
5. **Tests** (mock, free): the chosen agent's turn and conversation calls carry the model, others
   and the judges don't, an unknown id throws, and replay reproduces the world.
6. **Docs:** README options, `docs/scenario-authoring.md` if world files get a documented `:model`.

Rough size: items 1, 5 and 6 are a small change (tens of lines). Item 2 is a few lines. Item 3 is
the largest and can wait unless the report gets used on mixed runs.

## 3. Things that stay global, and why that might matter

`:reasoning {:effort "none"}`, `:max-tokens 16000` and `:tool-choice "auto"` are set per run
and were tuned on DeepSeek. The engine's DeepSeek workarounds (empty replies after reasoning use up
the tokens, the brief-thinking nudge, `text-as` reply recovery) are generic enough not to hurt other
models, but another model may want different reasoning settings. Per-agent overrides would mean
letting the value be a map, e.g. `{"hale" {:model "..." :reasoning {:effort "low"}}}`, with the seat
and conversation calls merging it in.

Separately, scenario adjudications made for an actor's query (`:adjudicate` in
`src/manor/engine/resolve.clj`) pass `{:model (:model opts)}` explicitly, which bypasses
`:judge-model`, although `default-opts` says `:judge-model` covers scenario queries. That's an
existing inconsistency, not part of this feature, but worth fixing while nearby.

## 4. Open questions

1. **Where the choice lives:** a run option only (recommended: a model is run configuration, and the
   same world file gets played on different models), a world-file `:model` only, or both with the run
   option winning? If world files stay out of it, should `:model` there be rejected rather than silently
   working?
2. **Mid-run changes:** is `update-agent!` at the REPL enough, or do you want it in the panel?
3. **Per-agent reasoning and max-tokens:** needed now, or a plain model id for now?
4. **Which models:** knowing them decides whether Anthropic caching (2.2) and pricing entries (2.3)
   are needed up front.
5. **Bad model ids:** an unknown id now fails at the agent's first call (OpenRouter rejects it and
   the run pauses on the error). Check ids against OpenRouter's model list when a real run starts, or
   leave it?
6. **On load:** `--agent-model` given to `--load` or a branch has nothing to apply to, since the
   cast was spawned at the start. Ignore it, warn, or turn it into `:agent/updated` events?

## 5. Decisions (2026-10-31)

1. The model is set only in the world file: an optional `:model` string on an agent. No run option and
   no CLI flag (`--agent-model` was only proposed; it isn't added). `--model` keeps changing everyone
   without a `:model` of their own, which the engine already does.
2. No mid-run switching in any form; nothing is added for it.
3. ~~Reasoning, max-tokens and tool-choice stay the run's settings for every agent.~~ Superseded by section 6.
4. Any OpenRouter model may be named; nothing is assumed about which.
5. Model ids aren't checked.
6. Nothing is applied on load or branch.

Still open: how to turn on caching for models that need `cache_control` (Anthropic).

## 6. Proposal: `:model` as a map of call settings

An agent's `:model` in the world file is either a model id or a map that overrides the run's call
settings for that agent's own calls (turns and conversation lines):

```clojure
:model "anthropic/claude-sonnet-5-5"                     ; as now: just the model
:model {:reasoning {:effort "high"}}                     ; the run's model, thinking harder
:model {:id "anthropic/claude-sonnet-5-5"
        :cache-control {:type "ephemeral"}
        :tool-choice "required"}
```

- Keys: `:id`, `:reasoning`, `:tool-choice`, `:cache-control` and `:max-tokens` (the user's decision).
  Each one given replaces the run's value whole (`:reasoning` isn't deep-merged); each one left out
  is the run's. Without `:id` the model is the run's `:model`, so `--model` still changes it.
- Checked by the world-file schema for shape only: a string, or a closed map of those keys with
  `:reasoning` and `:cache-control` maps, `:tool-choice` a string. No check of ids or of combinations
  (e.g. `"required"` with DeepSeek reasoning, which DeepSeek's endpoint rejects).
- Resolved in one place: a function of (opts, agent, gm?) returning
  `{:model :reasoning :tool-choice :cache-control :max-tokens}`, used by `seat.clj` and `talk.clj`.
  `turn/run-calls` takes that map instead of building those fields from `opts`
  (`src/manor/engine/turn.clj`, the `request` binding). The request's `:model` stays a string, so
  `llm.edn`, usage events and the cost report keep working; the other settings are already in
  each logged request.
- `manor.io.llm/OpenRouter` sends `:cache-control` as the top-level `cache_control` field, as given
  (so `{:type "ephemeral" :ttl "1h"}` works). The mock ignores it.
- The panel's agent page and `scene_inspect` show the id (or "run default") and the overrides.
- Judges, the steward and adjudicators keep the run's settings.
- Tests (mock): overrides reach the request for that agent's turn and conversation calls and no one
  else's, an `:id`-less map uses `--model`, the schema rejects a bad shape, replay reproduces the world.

Open:

1. (Decided: `:max-tokens` is overridable.)
2. Should the run get a `:cache-control` option too (default nil), so a whole run on `--model
   anthropic/...` caches? Agents would override it like the others.
3. Is the top-level field enough? Automatic caching writes only at the end of the request, and the
   end of an agent's prompt changes every turn, so it hits within a turn's tool calls but not from one
   turn to the next. Caching across turns needs an explicit breakpoint at the end of the system
   message, which means sending that message as a content-block array.

What caching does now, without `cache_control` (`old-test-runs/claudeworth-relations-morning`,
all 298 calls served by DeepSeek): DeepSeek caches every request's prefix by itself and reuses the
longest start a new request shares with an earlier one, wherever it ends. An agent's first call of a
round had 67% of its prompt from the cache (in round 1 only the shared ~1,900-token preamble; from
round 2 most of the system message and events window, e.g. 11,008 of 15,030 tokens); later calls in
the same turn had 91%. `cache_control` adds nothing for DeepSeek: it's a request for providers that
cache only where told (Anthropic; Alibaba's endpoints; Gemini's explicit caching). Breakpoints
(question 3) would be for those models only, and sent only when `:cache-control` is set. With
Anthropic the tools come before the system message in the cached order, so a turn whose tool list
differs from the last one's misses the whole cache.
