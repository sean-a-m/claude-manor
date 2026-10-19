# Shop visits in one turn: design

**Status:** proposed, nothing implemented. The empty-shop judge bug (below) is being fixed
separately with world-file data on branch `shop-counter-items`.

Read `AGENTS.md` first, then "Conversations", "Hibernation", "Turn order: `:schedule`" and
"Internal adjudication queries" (the shop's quotes) in `docs/scenario-authoring.md`,
`src/manor/conversation.clj`, `src/manor/scenarios/shop.clj`, and hibernation in
`src/manor/builtins.clj` and `src/manor/scenarios/claudeworth.clj`.

## Why

The evidence is `runs/claudeworth-20261018-222417` (10 rounds, $0.20). Nellie took a five-item
grocery list to Briggs and a message to Hobbs. She arrived in the village at 10:00, bought at
11:30 and was home at 12:30. Lord Claudeworth and Daisy were then sent after the butcher's bill,
which never reached the house because it was never made.

What it costs today to buy something, from the customer's side:

| Turn | Customer | Shopkeeper |
|---|---|---|
| 1 | asks with `say` or `converse`, then `pass` (there is nothing to buy yet) | quotes on his own turn later that round, if awake |
| 2 | `buy` (turn-ending) | |

That's two turns, one of them wasted. When the shopkeeper is hibernating, which is the usual
case, it's three: he can't answer until the next round. The causes:

1. **Hibernation is decided only at round start** (`run-round!`, `engine.clj:980`).
   `company?` counts people already in the place or travelling to it. Nellie travelled to the
   village green and walked into the store mid-round, so Briggs stayed out of play that round.
   `converse` refuses a hibernating agent ("not free to talk", `conversation.clj:49`), and nothing
   tells a customer that a shopkeeper "tending his shop" has to be roused.
2. **A routine agent is put back into their routine on the round their own job ends.**
   `b/releases` and `b/hibernations` run in the same step. Hobbs made up the account from 10:30
   to 11:00, planning to carry it to the house; at 11:00 he was freed and hibernated at once,
   with no turn to act on that plan.
3. **A shopkeeper can only quote on his own turn.** Speaking turns have `reply`,
   `stay-silent`, `leave`, `end-conversation` and `note`, and `max-queries 0`, so a conversation
   at the counter can't produce a quote.
4. **The tool docs point the slow way.** The `buy` doc says "Ask the shopkeeper with `say`
   first". The rules say "you can speak once a turn", and models are unsure whether `converse`
   counts against that. It doesn't: it has its own limit.

Not in scope here but seen in the same run: work in a place with no aspects, items or stores is
never judged (`items/empty-activity?`), so Hobbs's fair copy of the account made nothing. That's
being fixed with data. Hobbs also said "There you are, my lord — the account" in a conversation,
where `give` isn't available, and both listeners believed it had been handed over. That's a
candidate follow-up (`give` in speaking turns); see the last section.

## The goal

One customer turn per shop visit, with `buy` still ending the turn:

1. Nellie walks into the store. Briggs wakes as she arrives.
2. She starts a `converse` with Briggs and reads out the list.
3. On his speaking turn, Briggs calls `quote-goods`, then replies with the total.
4. The conversation ends. Its summary, the tool reply Nellie gets, lists the offers and their
   quote ids, and they are in her `offers`.
5. Nellie calls `buy`, which ends her turn.

Nothing is hoarded: the purchase still costs the customer's turn, and the shopkeeper only does
in a conversation what his job is anyway.

## Phase 1: hibernation fixes (engine, scenario-neutral)

### 1a. Give a released agent one turn before hibernating them

In `run-round!`, collect the agents freed by `b/releases` and leave them out of that round's
`b/hibernations`:

```clojure
(let [released (b/releases @(:world run))]
  (emit! run released)
  (when-let [hibernate (:hibernate (:scenario run))]
    (emit! run (b/hibernations hibernate @(:world run) (set (map :actor released))))))
```

`b/hibernations` gets an optional `skip` set. Next round, if they started nothing and nobody is
near, they hibernate as usual. Cost: one extra turn for a routine agent each time their own
occupation ends, including waking from `sleep` (Pugh, Briggs and Hobbs each get one turn on
waking).

### 1b. Wake hibernating agents when someone arrives mid-round

Add `b/wakings`, the waking half of `b/hibernations`: for each hibernating, uninterrupted agent
for whom `hibernate-fn` now returns nil, emit `free`. It never puts anyone to sleep mid-round.
Putting people to sleep stays a round-start decision, so an agent who was awake at round start
(Hobbs at 10:30, after Nellie had left) still gets the turn they were counting on.

Call it from `resolve!` after an action's events are emitted, when any of them is
`:agent/moved`. That covers `walk`, the GM's route tools and spawns. It's a pure pass over the
agents, so it costs nothing when nobody wakes.

Because `company?` looks only at the clerk's watched places (the shop, and the place their
routine puts them), walking across the village green wakes nobody. Walking through a shop does,
which costs at most one model call for the shopkeeper.

### 1c. Agents woken mid-round get a turn that round

Today `schedule` drops busy agents when the round starts, so a shopkeeper woken in 1b would
still wait until next round. Two changes:

- **`schedule` keeps everyone active.** `run-group!` already re-checks, at the moment each group
  runs, that its agents aren't travelling or busy (unless interrupted). So whoever is busy when
  their slot comes is skipped as before, and whoever was woken earlier in the round acts in
  their normal slot. In Claudeworth the shopkeepers spawn after the whole household, so this
  alone covers every household customer.
- **A trailing pass for late wakers.** After the scheduled groups, give one turn, in spawn
  order, to each agent who is now free or interrupted, isn't travelling and hasn't acted this
  round. This covers a shopkeeper whose slot came before their customer's, and agents roused by
  `rouse` after their slot (Hobbs at 12:30, roused by Lord Claudeworth). `run-round!` tracks who
  acted from `run-group!`'s results. One pass only, not a loop: a late waker who wakes someone
  else gives them a turn next round.

Scenarios with a `:schedule` function keep their own; this changes only the round-robin and
simultaneous defaults. The schedule's docstring and the "Rounds" section of the authoring
guide ("Turn order: `:schedule`" and "Hibernation") need updating.

### Tests

- Walking into a shop mid-round frees its hibernating clerk, and the clerk takes a turn that
  round, both when the clerk's slot is after the customer's and when it's before (trailing pass).
- Walking across the green, or into another shop, wakes nobody.
- Nothing hibernates mid-round: a clerk awake at round start whose customer leaves still gets
  their turn.
- An agent whose job ends isn't hibernated that round; next round, idle and alone, they are.
- `rouse` after the target's slot gives them a turn in the trailing pass.
- Replay still reproduces the live world (the wakings are ordinary `:agent/freed` events).

## Phase 2: quoting inside a conversation

### 2a. Scenario speaking-turn tools

Add a scenario key `:speaking-actions`, a function `(fn [world speaker conv] actions)`, whose
actions are added to a speaking turn after the conversation's own and `note`. In
`speaking-turn!`:

```clojure
actions (cond-> (conversation/speaking-actions conv speaker)
          (action-named run :note) (conj (action-named run :note))
          :always (into ((:speaking-actions scenario (constantly [])) world speaker conv)))
```

Claudeworth returns `quote-goods` when `shop/service-reason` is nil for the speaker (they keep
an open store and are standing in it). Speaking turns take free and query actions only, never
turn-ending ones: a speaking turn isn't the speaker's own turn.

### 2b. Speaking-turn limits

When the extra actions include a query, the speaking turn gets `:max-queries 1` instead of 0,
and `speaking-turn-steps` goes from 3 to 4, leaving room for quote, reply and one retry. The
query runs through the existing `run-query!` path, so the appraisal call, its cost, `llm.edn`
record and `:on-result` events are unchanged. `quote-reason` already requires the customer to be
present at the counter, and everyone in a conversation is.

### 2c. The summary shows what changed hands

`conversation-summary` lists only events tagged with the conversation's id, so the offers are
missing from the reply the initiator reads. Change it to list every event from the
conversation's `:conversation/started` up to its end that the initiator can see, except usage
and system events. That picks up `:claudeworth/goods-quoted` and `goods-refused` now, and a
`give` later, without tagging each kind. Members' later speaking turns already see these events
among their recent events, because they're visible to everyone in the place.

### 2d. Docs and briefs

- `buy`: "Talk to the shopkeeper with `converse`; they can quote during it, and you can buy
  as soon as it ends." The `say` route still works but isn't advertised.
- `quote-goods`: say that it can be used on your speaking turn in a conversation at your
  counter, before your line, and that the line should give the prices.
- The shop-clerk positions in `resources/positions/england-1904.edn` (shopkeeper, butcher):
  the same in their `:about`, e.g. "When a customer talks to you at the counter, use
  `quote-goods` on your speaking turn, then tell them the prices."
- Claudeworth `rules`: "`converse` doesn't count against speaking once a turn: you can start
  one conversation a turn as well."
- `docs/scenario-authoring.md`: `:speaking-actions` and the summary change under
  "Conversations", and the conversation-quote route where the shop's quotes are described
  ("Internal adjudication queries").

### Tests

- In a conversation at the store, the clerk's speaking turn calls `quote-goods`. The quotes
  are recorded, appear in the initiator's summary with their ids, and the initiator buys them in
  the same turn. Script this in the Claudeworth or household `:mock-conversation`, using ctx
  `:used` to choose `quote-goods` then `reply`, as `shop/scripted-clerk` does.
- A speaking turn rejects turn-ending actions (`buy` isn't offered to members in a speaking
  turn).
- `quote-goods` isn't offered to a speaker who isn't a clerk, or who is away from their
  counter or outside opening hours.
- Query limit: a second `quote-goods` in one speaking turn is refused.
- The appraisal cost counts towards the run's cost, and `max-cost` can still cut a
  conversation short.

## Verification

1. `clojure -M:test` passes after each phase.
2. A mock Claudeworth day, checking the transcript for the one-turn shop visit.
3. One short real run, started near the errand: `--start "1904-10-15T09:30"`, at most 8 rounds,
   `:max-cost 0.20`. Read the transcript for: shop visits that take one customer turn, offers in
   the conversation summary, no `pass` turns spent waiting for a quote, and the number of
   clerk calls. Move the run to `old-test-runs/` afterwards.

## Follow-ups, not proposed here

- **`give` and `show` in speaking turns,** so that a handover said in a conversation is also
  done there. This would have settled the Hobbs, Lord Claudeworth and Daisy confusion. It fits
  `:speaking-actions` (2a) and the summary change (2c), plus one instruction to speakers: "saying
  you hand something over doesn't move it; use `give`."
- **`empty-activity?`.** The data fix keeps the shops from being empty scenes. The shortcut
  will still swallow anything made in a bare room elsewhere. Dropping it costs about $0.0007 per
  affected job (the judge's price in the run above).
