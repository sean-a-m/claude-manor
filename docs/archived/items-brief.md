# Items: build brief

Self-contained instructions for adding **items** to the engine and to Claudeworth: objects with a
description and a condition that can be placed, carried, given, bought, looked at and worked on.
Read `AGENTS.md` first (invariants, run directories, cost rules). The idea comes from the user's
wishlist ("Items", and the minimal part of "look"). This brief is the
authority on scope and details. The user approved it on 2026-10-16, including the proposals
below.

## Status: I1-I4 done

| Phase | Commit | Tests after |
|---|---|---|
| I1. Items core | `b4c94a4` | 71 |
| I2. look | `46f75d9` | 73 |
| I3. Work and the judge | `804608b` | 83 (1093 assertions) |
| I4. Claudeworth mock day | `a4e9379` | 83 (1108 assertions) |
| I4. Real run, docs and archive | this archive | 83 (1108 assertions) |

I1-I3 were built and reviewed separately. In each, village, treasure and quiz prompts and tool
lists stayed byte-identical (including a read-only replay of `runs/smoke-llm`).

**I4 is done.** Hale buys a duster and dusts the cabinet from 14:00 to 15:30; the countess moves
the candlesticks; Hale uses `look`. The mock day has no errors, rejections or forfeits and replays
identically. The rules and authoring guide cover items; the layout, roadmap and design decisions
are updated, and this brief is archived.

The single real check stopped after 9 rounds at $0.106296718 (98 calls): 6 errors, 0 rejections,
2 forfeits. Agents used `look` 32 times, `take` once and `give` once, but no `work-on` or judge;
no briefs were tuned to force use. Full findings are below, under I4 real-run findings.

**How it was built, where the brief left room:**

- **Wiring.** Items are on when the scenario has `:items` (Claudeworth's is
  `{:judge-brief ...}`). The world has `:items` once the first item is added. Jobs are in
  `(:item-jobs world)`, read through `items/jobs` and `items/job`.
- **Item names in narration.** `narrate/names` gets them with `requiring-resolve`, because
  `manor.items` requires `manor.narrate`.
- **Shop goods.** Goods you carry away show `:carried true` in the shop's `sells` view, and
  `:claudeworth/bought` records `:kind :item`.
- **`look` while travelling** is refused through `:valid?`, not hidden. `:people` in its reply
  has no ids; ids are in the view's `:people-here`.
- **Which jobs end** is decided *before* occupations are released at round start
  (`items/ending-jobs`), so a stopped worker isn't credited the full time. The order of the
  checks is: retired, then stopped early, then due.
- **The engine** finishes jobs with `finish-job!` and `ask-judge` in `engine.clj`. The model call
  is outside the world lock.
- **Failures.** A bad judge answer gets one retry. A second failure, or an exception, gives
  `:item/work-done` with no changes plus a `:system` event. It never pauses the run.
- **Mock runs.** The mock LLM routes role `:judge` to the scenario's `:mock-judge` or to
  `items/mock-judge` ("just seen to").
- **Narration.** The neutral summary is the narration's opening line ("Hale finishes dusting the
  china cabinet."), and the narration leaves out a summary that only repeats it.

**Known gaps (tracked in the roadmap):**

- The judge's `error:` replies are counted in the transcript's overall error total.
- The control panel's per-agent usage table has no judge row. Its cost is in the run total.
- Judging at round start adds latency to the round.
- `map.edn` can't carry items held by GM-spawned agents. That's fine, because nothing resumes
  from it (roadmap B9).

**What to look at in the real run:**

- whether agents use `look` and `work-on` without being pushed
- whether the judge keeps to conditions, how long its answers are, and what each call costs
  (llm.edn agent `"judge"`)

The judge prompt as built is in `items/judge-request`. To see it, start a mock run and read
`llm.edn`.

## Phases

The work is four phases. Each ends at a **stopping point**: `clojure -M:test` passes, the
authoring guide covers what the phase added, and the phase is one commit. The world is usable at
every stopping point, so the work can pause after any of them.

| Phase | Brief sections | Done when |
|---|---|---|
| **I1. Items core** | 1, plus the Claudeworth starting items, shop items and the `take`/`put-down`/`give` sentence of the rules text (section 4) | items load from world files, can be taken, put down, given and bought, show in views, export to `map.edn`; tests for these pass; other scenarios' prompts unchanged |
| **I2. look** | 2 | `look` replies as specified; free actions' `:reply` is documented and tested; a few Claudeworth rooms have `:detail` |
| **I3. Work and the judge** | 3 | `work-on`, the job table, finishing jobs at round start, the judge call with its checks, retry and mock; all the `work-on` tests in section 5 pass |
| **I4. Claudeworth and wrap-up** | 4 (mock day, the rest of the rules text), 5 (anything left), 6 | the mock day passes with items in it; one short real run, read and reported; docs done; this brief archived |

The implementer of a phase doesn't start the next one, and doesn't start a real run unless
its task says it may.

## Scope

**In:** an opt-in engine module `manor.items`; items in world files and in `map.edn`; free
`take`, `put-down`, `give` and `look`; items in agent views; buying items at the general store;
`work-on`, a turn-ending action whose effect on items is decided by a judge model call **when the
work is over**; Claudeworth's starting items, shop items, rules text and mock day.

**Out (later, don't build or design):** items getting dirty over time on their own (the GM's job
later), containers, GM tools to create or alter items, any change to an item's description or
name, carrying limits, ownership rules, the full "look" (broad and detailed room descriptions,
changing room states such as a closed shop). Fungible household stores (provisions, coal) stay
counters.

## Decisions already made (don't revisit)

1. **Engine module**, opt-in like `manor.space`: a scenario turns it on with the key `:items`.
   Scenarios without it are unchanged; village, treasure and quiz prompts must stay
   byte-identical (check against `runs/smoke-llm` as the phase 2 brief did; never write under
   `runs/`).
2. **Condition is free text**, a short phrase (at most 80 characters): "dusty", "tarnished",
   "chipped at the rim, otherwise clean". Descriptions and names never change in this work.
3. **The judge is a separate, narrow model call**, not the GM. It sees only the job and its items.
   Characters never hear of it.
4. **Work takes the time the agent sets** (`hours`, optional, default half an hour, at most 4).
   The judge decides what that much time achieves; it may be only part of the job.
5. **Nothing changes until the work is over.** While the agent is busy, the items keep their
   old condition. The result is applied at the start of the round the work ends, the same moment
   the agent is freed. Two jobs on the same item can conflict; whichever is applied last wins.
6. **What people carry is visible** to everyone in the same place.
7. **`look` is the minimal version:** items here in detail, what people here carry, and an
   optional extra paragraph about the room.

### Proposed with this brief (approved)

- **The judge is asked when the work ends, not when it starts.** The job table holds only what
  was asked for (who, which items, doing what, from when to when). At the end the judge sees the
  items' *current* conditions and the time actually spent. So:
  - an agent interrupted (`rouse`) and stopping early is judged on the time they really worked;
  - if someone else changed an item meanwhile (the conflicting case), the judge sees that;
  - nothing about the outcome exists before it happens, so it can't leak;
  - one judge call per job, as before.
- **The judge is an engine-run model call, like the GM, not a bridge.** Bridges are for the real
  world; the judge is internal machinery. Its calls are recorded in `llm.edn` (agent `"judge"`)
  and as `:llm/usage`, so they count towards `:max-cost`. Replay uses the recorded events and
  never calls it.
- **The action is called `work-on`**, not `tend`: it says plainly what it's for.
- **Luck:** each judge call gets a roll from `manor.rng` (1-100, 50 is ordinary), so slips and
  lucky results don't depend on the model's idea of randomness.

## 1. Items core

### Data

An item in the world, under `(:items world)`, keyed by id:

```clojure
{:id "china-cabinet" :name "China cabinet"
 :desc "A glass-fronted walnut cabinet of Meissen figures."   ; 1-400 chars, never changes
 :condition "dusty, the glass smeared"                        ; 1-80 chars
 :place "drawing-room"      ; exactly one of :place (a location id)
 ;; :holder "hale"          ;   or :holder (an agent id)
 :owner "household"         ; optional: an agent id or any label; recorded and shown, not enforced
 :fixed? true}              ; optional: furniture, can't be taken
```

Ids are kebab-case and unique among items. New items get a fresh id from their name (`umbrella`,
`umbrella-2`), like plans do. A carried item goes wherever its holder goes, with no extra events.

### Storage and lookups

In the world, items are a **map keyed by id**; the vector form exists only in world files. Code
outside `manor.items` never reads `(:items world)` directly. It goes through `manor.items`
accessors (`item`, `items-at` a place, `held-by` an agent, `items-where` a predicate, and any
others needed), and only the module's reducers write items. For now the accessors scan the map;
with tens of items that's trivial.

This keeps faster lookups a change inside one namespace later:

- **Indexes** (by place, by holder, by a future `:category` or tag) can be kept by the same
  reducers beside the map (e.g. `(:item-index world)`). That's allowed by the invariants: they're
  pure functions of the events, so replay rebuilds them. A test then checks each index against a
  full scan.
- **A database** (SQLite or similar) could serve as a *derived read model*, rebuilt from the
  event log, if queries ever outgrow memory. The event log stays the truth, which keeps replay
  and branching working. Making the database the truth would break both, so it isn't an option
  without redesigning the engine.

Nothing in this brief builds either. The accessor rule is the only requirement now, and the
reviews check it.

### World files

`manor.worldfile` accepts an optional top-level `:items` vector:

```clojure
:items [{:id "china-cabinet" :name "China cabinet" :place "drawing-room" :fixed? true
         :condition "dusty, the glass smeared" :desc "..."}
        {:id "pocket-watch" :name "Pocket watch" :holder "Lord Claudeworth"
         :owner "Lord Claudeworth" :condition "keeping good time" :desc "..."}]
```

`:holder` and `:owner` use agent **names** from `:agents`, as the rest of the file does. Load
turns them into ids; anything else in `:owner` (e.g. `"household"`) is kept as is. Checks, with
messages that name the item: the malli schema; unique ids; exactly one of `:place` and `:holder`;
the place is a location; the holder is an agent of the file; the length limits. `load-world`
returns `:items` (holders and owners resolved) beside `:space` and `:agents`. `export` writes the
live items back (place or holder, holder and owner as names again), and `->edn-string` prints one
item per line like locations. The engine rewrites `map.edn` when item events are emitted, as it
does for space events. Work in progress isn't exported.

A scenario that leaves out an optional agent (Claudeworth's `:architect? false`) drops the items
that agent holds.

### Engine wiring

- Scenario key `:items`, a map (possibly empty) that turns the module on. Keys:
  `:judge-brief (fn [world] text)` (step 3). `:init` may return `:items` (a vector as above, ids
  resolved). The engine emits one `:item/added` per item (visible `:gm`) after spawning agents.
- `all-actions` merges `items/actions` when the scenario has `:items` (before the scenario's own
  actions, so a scenario can override or exclude them).
- `prompt/agent-view` adds the item parts of the view (below) when the world has items enabled.
- `default-gm-view` adds `:items` and the job table when present.
- Event namespace `item/`; add it to the engine namespaces wherever they're listed (roadmap A5).

### Events (reducers pure, a `describe` for every visible one)

| Event | Data | Visible to | Reads |
|---|---|---|---|
| `:item/added` | `{:item {...}}` | `:gm` at init; the room when bought | "Hale now has an umbrella." (bought) |
| `:item/taken` | `{:item :from-place}` | everyone here | "Lady Claudeworth picks up the silver candlesticks." |
| `:item/put-down` | `{:item :place}` | everyone here | "Hale puts down the feather duster." |
| `:item/given` | `{:item :to}` | everyone here | "Hale gives the pocket watch to Lord Claudeworth." |

Narration uses the item's name, looked up in the world at describe time. `narrate/names` may need
item names added; keep it simple.

### Actions (all free, all roles)

- `take {item}`: the item is in your place, not fixed, not held by anyone. Reasons are
  actionable: "the piano is fixed in place", "the umbrella is not here; items here: ...", "Hale
  is holding it".
- `put-down {item}`: you hold it; it is placed where you are.
- `give {item to}`: you hold it; `to` is an active agent here, not you.

Taking something you don't own is allowed (it's social, and everyone here sees it).

### Views

- `:you :carrying [{:id :name :condition}]` (only when the agent holds anything).
- `:location :here :items [{:id :name}]`: the items in your place, names only (details through
  `look`), left out when empty.
- each entry of `:location :people-here` gains `:carrying ["Umbrella" ...]` when non-empty.

### Buying (Claudeworth)

A shop entry with `:kind :item` is a carryable object: it needs `:desc` and `:condition`
(`building/problems` checks this). `buy` of such an entry pays as now and creates `qty` items held
by the buyer, owned by `"household"`, with fresh ids: the `:claudeworth/bought` event (no change
to the stores for item kinds) and an `:item/added` per item, visible to the room. Other entries
work as now. `items` exposes a helper that builds a new-item event (fresh id, validated fields)
so scenarios never build item maps by hand.

## 2. `look`

**Engine change:** a free action may have `:reply (fn [world actor args] data)`. After its
effect, the engine sends `data` (as JSON, or as is if a string) as the tool result instead of
`"ok"`. Document it in the `manor.actions` docstring and the authoring guide.

`look {}` (free, all roles, no arguments) returns:

```clojure
{:place {:name "Drawing room" :detail "..."}             ; :detail only if the location has one
 :items [{:id :name :desc :condition :owner :fixed?}]    ; items here, not carried
 :people [{:name :doing :carrying [{:id :name :desc :condition}]}]}
```

Nothing is recorded as an event: looking changes nothing, and the reply is in `llm.edn`. Agents
can look as often as they like (each call uses one of the turn's `:max-steps` calls).

`:detail` is an optional location key, shown only by `look`, never in the view. Locations
already keep unknown keys, so only the export key order and the docs need it. Give two or three
Claudeworth rooms a `:detail`.

## 3. Work and the judge

### `work-on {items doing hours}` (turn-ending, all roles, worlds with a clock only)

- `items`: 1-6 item ids, each in your place or carried by you (fixed items are fine).
- `doing`: 3-120 characters, reads after "is", like `spend-time`'s activity: "dusting the china
  cabinet", "polishing the candlesticks".
- `hours`: optional, 0.5 to 4, default 0.5. Tool doc: "how long you'll spend; if the job needs
  longer, only part of it gets done. You get no turns until it's over, and the results show when
  you finish."
- Effect: `:item/work-begun` (visible `:gm`; data: the job) and an occupation
  (`b/occupy`, visible to the room: "Hale is dusting the china cabinet until 15:30") that carries
  the job id. **Engine change:** the `:agent/occupied` reducer keeps a `:job` key in `:busy`.

The job table, `(:item-jobs world)`, keyed by job id:
`{:id :actor :items [...] :doing :place :started round :due round}`.

### Finishing jobs (engine, at round start)

A new step right after releases and hibernation, before approved bridges and systems. It takes
each job in order of `:due`, then id:

| Situation | What happens |
|---|---|
| `:due` has come | finished; minutes worked = (due − started) × minutes per round |
| the actor's `:busy :job` is no longer this job (they stopped after `rouse`, or something else replaced the occupation) | stopped early; minutes worked = (this round − 1 − started) × minutes per round |
| the actor has been retired | dropped: `:item/work-ended`, visible `:gm` |
| otherwise | still in progress |

For a finished or stopped job:

- The items **still in reach** are the job's items still at the job's place (not held by someone
  else) or held by the actor. If none are left, or no time was worked, emit `:item/work-ended`
  (visible to the actor and the room: "Hale leaves off dusting the china cabinet before getting
  anywhere.") and make no model call.
- Otherwise call the judge (below), check its answer, and emit `:item/work-done` (visible to
  everyone at the place, and the actor): `{:job :actor :place :doing :minutes :stopped-early?
  :luck :summary :changes [{:item :from :to}]}`. The reducer sets each `:to` condition and
  removes the job. Jobs are applied one after another, so a later job's judge sees the earlier
  one's result; on the same item, the last applied wins.
- Make the model call outside the world lock, as `run-approved-bridges!` does.

Reads: "Hale finishes dusting the china cabinet. The glass is clean, though one figure's arm was
already chipped. (China cabinet: clean, one figure chipped)."

### The judge call

`manor.items` builds the request; the engine sends it through the run's `LLM`, with model
`:judge-model` (a new run option, default nil, meaning `:model`) and the run's `:reasoning` and
`:max-tokens`. One tool, required:

```
judge {changes: [{item, condition}], summary}
```

The prompt contains only:

- the scenario's `:judge-brief` (Claudeworth: the setting paragraph)
- the time of day
- the worker's name and persona
- the place's name
- what they were doing
- the minutes worked, and whether they stopped early
- the luck roll
- each item in reach: id, name, description and current condition

Instructions, in substance:

- Decide what that much work, by this person, realistically did to these things.
- Change only conditions, as short phrases of at most 80 characters.
- Leave an item out if it's unchanged.
- An item's nature can't change: work can't turn a vase into gold. If the task is impossible or
  absurd, change nothing and say so.
- Low luck means mishaps (a slip, a breakage); high luck means it went unusually well.
- The summary is one or two sentences, past tense, third person, for the people present, and no
  more than 300 characters.

**Checks on the answer** (untrusted, like the architect's drafts):

- only items in reach
- conditions 1-80 characters
- summary 1-300 characters
- anything else ignored

If the answer fails, the judge is told why and asked once more. A second failure, or an
exception, gives `:item/work-done` with no changes and a neutral summary ("Hale finishes dusting
the china cabinet."), plus a `:system` event recording the reason. It doesn't pause the run.

Each call is recorded in `llm.edn` as agent `"judge"`, with the round, and its usage as
`:llm/usage`. The transcript lists it like any other agent's calls; check that works.

**Mock:** with `--mock`, a call with role `:judge` goes to the scenario's `:mock-judge` if it has
one, else to `items/mock-judge`: deterministic, every item in reach gets the condition `"just
seen to"` (or similar), and the summary is "X finishes <doing>." The engine routes it, so
scenario mock policies needn't know about the judge.

## 4. Claudeworth

**Starting items** (world file; adjust wording freely):

| Where | Item | Fixed | Condition |
|---|---|---|---|
| entrance hall | stag's head | yes | dusty, one glass eye loose |
| drawing room | piano (upright, rosewood) | yes | a little out of tune |
| drawing room | china cabinet (Meissen figures) | yes | dusty, the glass smeared |
| study | estate ledgers | no | in good order |
| dining room | family portrait (the third earl) | yes | darkened with old varnish |
| dining room | silver candlesticks (a pair) | no | tarnished |
| kitchen | kitchen range | yes | cold and unlit |
| carriage sweep | motor car | yes | polished |
| held by Lord Claudeworth | pocket watch (gold half-hunter) | no | keeping good time |
| held by Hale | pocket notebook | no | half full |

Owners: `"household"`, except the watch (Lord Claudeworth) and the notebook (Hale).

**One home per fact:** remove from the locations' `:desc` what has become an item (the piano, the
stag's head, the portrait, the motor car, the desk if it becomes one).

**Shop items** (`:kind :item`, £1 each unless noted): umbrella, walking stick, feather duster,
silver polish, a novel, a bunch of chrysanthemums.

**Rules text** (`rules` in `claudeworth.clj`), one short addition:

- Things around you are under `items` in your view, and `look` shows them in detail, along with
  what the people here carry.
- `take`, `put-down` and `give` don't end your turn.
- `work-on` spends time doing something to things here or that you carry. Its results show when
  you finish.

Tool docs stay mechanics only.

**Mock day:**

- Hale buys a feather duster on his village errand (one more turn; shift his script as needed).
- In the afternoon, Hale works on the china cabinet for 1.5 hours ("dusting the china cabinet").
- The countess takes the silver candlesticks to the drawing room and puts them down.
- One `look`.

Keep the day free of forfeits.

## 5. Tests

- **World files:** each item check gives a readable error; holders and owners resolve to ids;
  export round-trips (start a run from an exported `map.edn` and get the same items).
- **take, put-down, give:** validity and reasons (fixed, not here, held by someone, recipient
  absent); visibility (only the room); a carried item travels with its holder.
- **Views:** item names here, your carried items with condition, `:carrying` on people here; no
  items from other rooms.
- **look:** the reply's contents (including `:detail`); no event emitted.
- **Buying:** a `:kind :item` buy creates carried items owned by the household, with fresh ids;
  stores are unchanged; other buys work as before.
- **work-on:**
  - the condition is unchanged at round `due − 1` and changed at `due`
  - interrupted and stopping: judged with fewer minutes, `:stopped-early?` true; with `carry-on`,
    the job runs to its end
  - the actor retired: dropped with no call
  - an item taken away mid-job: only the rest are judged; all taken: no call
  - two overlapping jobs on one item: the last applied wins, and the second judge sees the first
    result
  - an invalid judge answer: one retry, then no changes and a `:system` event
  - a judge answer that touches `:desc` or an item out of reach: ignored or refused
  - judge usage counts towards cost, and an `llm.edn` entry with agent `"judge"` exists
- **Replay:** a run with items and finished jobs replays to an equal world.
- **Privacy:** `(feed ...)` for an agent shows no item events from rooms they weren't in.
- **Unchanged scenarios:** village, treasure and quiz prompts are byte-identical.
- **Claudeworth:** the mock day passes; the cabinet's condition changes at Hale's finishing time.

## 6. Real run and docs

One short real run, `:test? true`, at most 10 rounds and `:max-cost 0.10`, in the foreground,
e.g. `--start "1904-10-15T13:00"`. Read the transcript and count:

- `error:` and `rejected:` replies and forfeits
- `look` and `work-on` calls
- the judge's answers: are they sensible, do they keep to conditions, how long are they, and what
  do they cost?

If nobody uses items unprompted, report that rather than pushing the briefs into it.

Docs:

- `docs/scenario-authoring.md`: a new "Items (`manor.items`)" section; free actions' `:reply`;
  `:judge-model`; the `:items` scenario key; world-file `:items` and `:detail`.
- `docs/roadmap.md`: B7 mostly done; note the follow-ups (decay, containers, GM item tools,
  judge at round start adds latency).
- `docs/claudeworth-design.md`: decisions for items in section 12.
- `AGENTS.md`: layout, with `items.clj`.
- `README.md`, if it lists actions.

Move this brief to `docs/archived/` once its work is committed.

## I4 real-run findings

One real run, `test-runs/items-i4-real`, used `deepseek/deepseek-v4.1-flash`, starting at
13:00 with `:test? true`, `:max-rounds 10` and `:max-cost 0.10`. The CLI does not accept
`--max-rounds` or run rounds itself, so it used `dev/user.clj`'s `start!` and `step! 10` in the
foreground. It paused after round 9 (17:00) at the cost limit: **$0.106296718**, **98 model
calls**. The cap is checked at round boundaries, hence the overshoot. It was not repeated.
The transcript was read in full and the counts checked against `llm.edn` and `events.edn`
before cleaning up `test-runs/`.

| Agent | Calls | Cost |
|---|---:|---:|
| Hale | 30 | $0.034449759 |
| Lady Claudeworth | 27 | $0.023004346 |
| Lord Claudeworth | 27 | $0.029548317 |
| Pugh | 14 | $0.019294296 |

- **6 `error:` replies, 0 `rejected:` replies, 2 `:action/forfeited` events.** Three errors
  were the earl's `commission` calls using `planid`; `plan_id` then worked. Two reference
  queries exceeded 200 characters and one speech exceeded the accepted length; all were
  corrected after the existing actionable replies. Pugh forfeited rounds 7 and 8 after
  returning DSML tool markup as ordinary text twice in each turn (`finish: stop`), not actual
  tool calls. He recovered in round 9.
- **Item calls:** `look` 32, `take` 1, `put-down` 0, `give` 1, `work-on` 0. Hale fetched the
  estate ledgers and gave them to the earl without prompting from the operator. The countess
  inspected the house and discussed its worn and dirty things, but the household focused on
  commissioning rooms rather than doing item work. They repeatedly looked while searching
  for people. No item purchase was made.
- **Judge calls: none.** There is no real judge prompt, answer, condition-only result, answer
  length or per-call cost to assess. Mock and unit tests cover the judge; this check does not
  validate its real-model behaviour. No extra run or brief tuning was used to force it.
- **Other observations:** Hale later reported a conversation with the nonexistent cook and
  misstated the household balance. Pugh spoke of paid repairs between commissions, which are
  not an implemented service. These were agent claims, not engine state changes.
- **Wording fixes: none.** Existing validation replies sufficed for the argument errors. The
  DSML output and unsupported narrative claims need separate investigation, not an items
  design change in this phase.

After this check the user raised the default cap for **future** short real checks to
`:max-cost 0.20`. Current guidance was updated; this run retained its authorized $0.10 cap.
