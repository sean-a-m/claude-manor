# Items follow-up: test the judge for real, and curb constant looking

**Status: done.** Nellie the housemaid was added (`:housemaid? true`, off by default; a maid's
room off the kitchen and a `:housemaid` role in the world file). Hale's dusting and duster-buy
were removed; the countess now buys a duster on her way home and gives it to Nellie. The `look`
wording changed in the tool doc, the rules and the authoring guide.

The one authorized real run (`old-test-runs/items-maid-real`, 10 rounds from 09:00, `:test? true`,
`:housemaid? true`, `:max-cost 0.20`) cost **$0.131405** over **169 calls**, capped by
`:max-rounds` rather than cost. **0 forfeits, 3 `error:` replies, 1 `rejected:` reply.** It is the
first real exercise of `work-on` and the judge: **6 jobs, 6 judge calls ($0.0034)**. Every judge
answer kept to conditions and the limits (conditions well under 80 characters; summaries
137–183), gave partial work for short jobs, mishaps on low luck (a chipped Meissen figure at luck
19; abraded varnish at luck 2) and refused the impossible (left the piano out: dusting cannot
tune it). **Looking was not reduced:** 54 `look` calls (32% of calls, against 33% in the items
run); the thoughts show agents looking to *search for people*, not only to inspect conditions.
Per-call judge latency was not captured (bad instrumentation); the judge adds one model call at
the start of the round a job ends in.

Key judge calls (from the transcript; the run is kept in `old-test-runs/items-maid-real`):

| Round | Worker | Doing | Luck | New condition | Cost |
|---|---|---|---|---|---|
| 3 | Nellie | blacking and lighting the range (60 min) | 33 | "blacked and lit, though the fire caught slowly and there are smudges" | $0.000259 |
| 4 | Lady Claudeworth | polishing the candlesticks (30 min) | 77 | "brightly polished, lustre fully restored" | $0.000333 |
| 5 | Nellie | dusting and polishing the drawing room (60 min) | 19 | "dusted; a Meissen figure chipped; glass streaked, not clear" | $0.001520 |
| 5 | Lady Claudeworth | cleaning the portrait (30 min) | 2 | "partly cleaned; varnish unevenly stripped, one patch of paint abraded" | $0.000427 |
| 6 | Nellie | the cabinet glass and figures (30 min) | 70 | "glass cleaned clear; Meissen figures dusted; chipped shepherdess unharmed" | $0.000562 |
| 7 | Nellie | the range's soot and ironwork (30 min) | 40 | "soot smudges wiped off and ironwork polished; fire lit and burning" | $0.000331 |

The character errors were small and self-corrected: two "Pugh draws the plans here; ask him"
(an agent tried to draw plans rather than ask) and one over-long `spend-time` `activity`; one
`rejected:` with "no active participant with id earl".

The run is also the Task B measurement: `look` per turn was about 1.1 (54 across ~50 agent
turns), per move 0.44–1.43. Options 2 (conditions in the view), 3 ("nothing has changed" replies)
and 4 (walk shows what `look` would) were declined or deferred by the user, so nothing further was
built.

---

A brief for the agent who continues the items work. Read `AGENTS.md` first (invariants, run
directories, cost rules: real runs are short and capped at `:max-cost` 0.20). Then read the
completed brief, [archived/items-brief.md](archived/items-brief.md), for how items work and how
they were built. `docs/scenario-authoring.md` (Items section) is the contract.
`src/manor/items.clj` is the code.

## Where things stand

Items are built, tested with mock runs, and committed (`b4c94a4` to `62b7f50`; `clojure -M:test`
passes):

- `manor.items` is an opt-in engine module: items lie in places or are carried, and each has a
  description and a free-text condition. The actions are `take`, `put-down`, `give` and `look`,
  all free, and `work-on`, which takes up the turn and keeps the agent busy for the hours they
  choose.
- **The judge.** When a job ends, a separate model call (the judge) decides the items' new
  conditions from the time actually worked and a luck roll. It's recorded in `llm.edn` as agent
  `"judge"`.
- **Claudeworth** has ten starting items, six carryable shop goods, and a mock day that uses all
  of it.

The one real run (9 rounds from 13:00, $0.106, 98 calls) left two things open:

1. **The judge has never run on a real model.** Nobody used `work-on`: the family has no reason
   to clean, and there's no servant. Everything the judge does has been checked only with the
   mock judge.
2. **Agents look constantly.** `look` was 32 of the 98 calls, a third of all calls and of their
   cost. The run was in `test-runs/` and has been deleted, so there is no transcript left to
   study; the counts above are all that survive.

## Task A: a character who cleans, and a real test of the judge

Add a housemaid to Claudeworth, so that `work-on` has someone whose job it is.

- **Where she sleeps.** She needs a bedroom, and the house has none for servants. Add one to the
  world file, e.g. a maid's room off the kitchen or an attic room up the staircase:
  `:kind :bedroom`, `:domain :house`, joined with `:connects-to`.
- **Who she is.** Add a role, e.g. `:housemaid`, with an `:about` in the world file's `:roles`
  and a persona in `:agents`. Her `:about` gives her daily duties in plain terms: dusting the
  drawing room, polishing the silver, blacking the kitchen range, and doing what the countess
  asks. It shouldn't name tools; the rules text already explains `work-on`. She doesn't spend
  household money: leave her out of `building/spenders`.
- **Make her optional and off by default,** with a run option such as `:housemaid? true`, the way
  `:architect? false` leaves out Mr Pugh. Existing runs, tests and costs then stay as they are. If
  you think she should be in by default, ask the user.
- **Mock day.** Give her a few scripted turns, including one `work-on` on the silver
  candlesticks and one on the china cabinet. Test that it's free of forfeits, that conditions
  change at each job's end, and that replay is equal, both with the option on and with it off.
  Turns not listed pass, so the existing mock day is unaffected while she's off.
- **Real run.** One run, after Task B's changes, so it checks both tasks: `:test? true`,
  `:housemaid? true`, at most 10 rounds, `:max-cost 0.20`, in the foreground, starting at a time
  when her duties make sense (e.g. `--start "1904-10-15T09:00"`). Use the REPL helpers in
  `dev/user.clj`, since the CLI has no `--max-rounds` (the I4 run did the same). Ask the user
  before a second run.

**Report on every judge call** (the `"judge"` entries in `llm.edn`):

- what the judge was told
- its answer
- whether it kept to conditions and the length limits
- whether partial work, mishaps from low luck, and refusals of absurd tasks read sensibly
- the cost of each call
- how much latency it added at round start

Copy the transcript's key lines into the report before deleting `test-runs/`.

If the judge misbehaves, the fixes belong in the judge's instructions (`items/judge-request`) or
its checks (`items/judgement`), with a test for each.

## Task B: discourage constant looking

**First find out why agents look.** In a short mock check, and then in the Task A real run, count
`look` calls per agent turn and per room visit, and read the thoughts around them (`thoughts:`
lines in the transcript). Likely causes:

- **The view lists item names only** (`location.here.items` has `id` and `name`), so an agent who
  cares about the state of things, like the countess inspecting the house, has to `look` to see
  conditions.
- **Looking isn't remembered.** The `look` reply is a tool result, not an event, so it's gone from
  the next turn's prompt and the agent looks again.
- **Nothing tells them it's unnecessary.** The rules and the tool description don't say that
  things only change when someone works on them, or that they'd see that happen.

Options, smallest first:

1. **Wording.** In the rules (`claudeworth/rules`) and in `look`'s tool doc, say that the view
   already lists what's here and what people carry, that things change only when someone works on
   them (and you see it happen), and that one `look` when you arrive somewhere is enough.
2. **Conditions in the view.** Show `{:id :name :condition}` for items here, not only names.
   `look` then adds descriptions, owners and the room's detail. This costs a few tokens a turn,
   and probably removes the main reason to look.
3. **"Nothing has changed" replies.** If nothing at the place has changed since the agent last
   looked there, `look` replies with one short line instead of the whole room. This needs a small
   per-agent record of the last look: a private event, or data kept by the reducers. The call is
   still made, but the prompt stays small.
4. **Look on arrival.** `walk`'s reply includes what `look` would show of the new place, so
   agents rarely need a separate `look`. `travel` ends the turn, so this only helps with walking.

**Recommendation:** do 1 and 2, measure again, and stop there if looking drops to about one per
room visit or less. Options 3 and 4 change engine behaviour or contracts: ask the user before
building them.

Whatever changes, keep village, treasure and quiz prompts byte-identical (the earlier phases
dumped prompts before and after and compared them; see the archived brief). Update the Items
section of the authoring guide and the Claudeworth rules to match.

## Also seen in the real run (not part of this task)

- **Mr Pugh forfeited two turns** because his model wrote its tool call as text markup instead of
  calling the tool. This is roadmap C10's territory: note it there if it recurs.
- **Invented dialogue.** Agents invented a cook in dialogue and talked of paid repairs that never
  happened. They didn't change any state; it's for the briefs or the GM later.

## When you're done

- Record the outcome where it belongs: roadmap B7's follow-ups, the decisions in section 12 of
  `docs/claudeworth-design.md` (the housemaid, and the looking change), and the authoring guide.
- `clojure -M:test` passes on every commit.
- Move this brief to `docs/archived/`.
