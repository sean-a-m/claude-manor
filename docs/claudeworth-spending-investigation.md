# Investigation: Claudeworth's £3,000 building schemes

## Finding

Lord Claudeworth does **not always attempt to spend the whole £3,000 household account**.
The two conspicuous recent £3,000 tower schemes coincide with an explicitly extravagant
character prompt. The pricing mechanics then turn a choice of the `grand` size into a price
of exactly the entire initial account. Pugh's character and commission incentive reinforce it.

There is also a separate model-behaviour problem: after a £3,000 commission becomes
unaffordable, characters can persist with it, narrating deposits and ledger entries that do
not change the engine-owned balance instead of choosing an affordable plan.

This is an investigation of existing evidence, **not an agreed implementation plan**.
No simulation behaviour was changed and no new real-model runs were made for it.

## Evidence and scope

The investigation compared recent transcripts from October 19–21, 2026, the runs' saved
`world-start.edn` and configuration, actual prompts recorded in `llm.edn`, and the architect
and construction code. Run dates below are directory timestamps, not in-world dates.

All the examples below used `deepseek/deepseek-v4.1-flash`; reasoning settings varied.
The local run evidence lives under `runs/`, which is gitignored, so its links will not work
in a fresh checkout without those runs. Do not delete or modify those run directories.

| Run directory under `runs/` | Observed building choice |
|---|---|
| `claudeworth-20261019-210747` | New drawing-room wing priced at £1,200. |
| `claudeworth-20261019-233942` | Guest bedrooms and drawing-room enlargement; commissioned the £1,200 bedrooms. Spoken estimates did not always match tool prices. |
| `claudeworth-20261020-112904` | Began with a £1,200 wing and tower, then favoured a £3,000 grand alternative. |
| `claudeworth-20261020-134625` | Requested a £1,200 wing. Only three rounds: not evidence of his eventual spending limit. |
| `claudeworth-20261020-150237` | Discussed a modest morning room; Pugh attempted a medium (£500) draft, but it was rejected for an invalid client id. |
| `claudeworth-20261020-200100` | Pursued a £3,000 West Front and Tower and repeatedly attempted to commission it with insufficient funds. |
| `claudeworth-20261021-144231` | Still arranging a consultation after six rounds; no priced scheme chosen. |

Neither of the two £3,000 tower runs contains a `:building/commissioned` event.
They demonstrate an intention or attempt to spend the full account, **not a successful
£3,000 expenditure**. Cheaper initial plans also do not prove a character would never
spend the remainder later.

A clear counterexample to an invariably extravagant choice is Lord Claudeworth's morning-room
proposal in the 15:02 run:

> A small room for you to write your letters in, and for the two of us to breakfast alone
> without the whole parade through the hall. Nothing grand — a fire, a chair by the bay,
> morning light through until eleven.

Source: [20261020-150237 transcript, line 1587](../runs/claudeworth-20261020-150237/transcript.md#L1587).
In the latest run he asks to know the consultation cost before admiring the drawings:
[20261021-144231 transcript, lines 63–66](../runs/claudeworth-20261021-144231/transcript.md#L63).

## 1. The extravagant prompt is the strongest explanation for choosing grand schemes

Both the 11:29 and 20:01 tower runs used this situation text:

> The house is far too small for your station, and you mean to enlarge it into something
> the county will talk of.

Their persona included:

> Grand schemes possess you — a new wing, a taller tower, a park to shame the neighbours —
> and you will talk of them by the hour with a glass in your hand.

They also specified low conscientiousness, large self-esteem and large hope. These instructions
were verified in the **actual recorded model prompts**, not merely in a world file. Nearby
cheaper-scheme runs used the milder enlargement instruction and a more conscientious character.
Do not infer a historical run's character from the current `resources/worlds/claudeworth.edn`:
the character instructions changed between runs.

The 11:29 run's recorded reasoning explicitly connects the decision to the character:

> A grand room is £3000 — the whole fortune. … Funds are £3000, so it'd take everything.
> … But let's be in character: grand schemes.

Source: [20261020-112904 transcript, line 486](../runs/claudeworth-20261020-112904/transcript.md#L486).
This supports the interpretation that the model understood the cost but treated extravagance
as part of the role. Recorded reasoning is supporting evidence, not a controlled causal test.

## 2. The fixed price ladder explains the exact £3,000 amount

The starting household balance is £3,000. The architect's entire size/price ladder is:

| Size | Price | Build days |
|---|---:|---:|
| `small` | £200 | 1 |
| `medium` | £500 | 1 |
| `large` | £1,200 | 1 |
| `grand` | £3,000 | 2 |

Sources: [`resources/worlds/claudeworth.edn`](../resources/worlds/claudeworth.edn),
[`mcp-servers/architect/prices.edn`](../mcp-servers/architect/prices.edn).

The architect MCP server does not ask a model to calculate a bespoke construction price.
Its [drafting code](../mcp-servers/architect/src/architect/drafting.clj) selects the price
solely from the requested size. Dimensions, materials and removing a little ornament do
not reduce a price within that category. There is no £2,500 or £2,998 option.

Consequently, choosing the highest category automatically means choosing the whole initial
account. This is not evidence that the model independently calculated £3,000 as the right
amount to spend. Nor is there an engine rule requiring a full-account expenditure.

The 11:29 run even recognises that trimming a few feet off the grand tower cannot make it
fit the remaining funds, because the available prices are discrete:
[transcript, line 1947](../runs/claudeworth-20261020-112904/transcript.md#L1947).

## 3. Household purchases compete with construction; no reserve is protected

Purchases and commissions use the **same household balance**, not separate operating and
building accounts. There is no protected household reserve. The `commission` validation
checks whether the price exceeds available funds, not whether enough would remain to run
the household. If exactly £3,000 were available, spending all of it would be legal.

Sources: [scenario rules](../src/manor/scenarios/claudeworth.clj),
[`commission` action and household state](../src/manor/mechanics/building.clj).
The GM brief also says that this slice has no rents or other automatic income.

Once ordinary shopping reduces the balance even slightly, the £3,000 tier is unaffordable.
In the 20:01 run, the rejection is:

> the West Front and Tower would cost £3000; the household has £2999 18s 1d

Source: [transcript, line 2350](../runs/claudeworth-20261020-200100/transcript.md#L2350).
The engine correctly refuses this; it is not silently allowing an overdraft.

## 4. Pugh encourages the expensive alternative

Pugh's character admires handsome façades, overlooks details and wants the manor's custom.
The rules tell agents that the architect earns 10% on commissions for plans he drew.
In the 11:29 run, he advocates the grand scheme:

> … twelve hundred for the large — three rooms and the tower — or three thousand for the
> grand, and the grand is what I'd build against that sky.

He later privately notes:

> £300 my share if the grand scheme goes through.

Sources: [transcript, line 589](../runs/claudeworth-20261020-112904/transcript.md#L589),
[line 1411](../runs/claudeworth-20261020-112904/transcript.md#L1411);
[fee and commissioning implementation](../src/manor/mechanics/building.clj).
This is evidence of an in-character upsell, not just the Earl choosing an amount alone.

## 5. Persistence after rejection is a distinct model/mechanics mismatch

In the long 20:01 run, the characters try to cover the missing 1s 11d by talking about coins,
paying into ledgers and entering money at the Manor. Pugh repeatedly retries the commission,
sometimes believing the funds are now whole. The engine still reports the same shortfall.

There is no ordinary implemented deposit action that makes those narrated transactions
increase household funds. Writing or discussing an entry does not change the numeric balance.
An affordable redraft would be possible, but the characters remain attached to the existing
scheme and invent procedural explanations for the failure instead.

Sources: [first rejection and proposed top-up, lines 2349–2352](../runs/claudeworth-20261020-200100/transcript.md#L2349),
[renewed rejection and invented bank-book explanation, lines 5954–5957](../runs/claudeworth-20261020-200100/transcript.md#L5954).
The household reducers in [building](../src/manor/mechanics/building.clj),
[shop](../src/manor/mechanics/shop.clj) and
[deliveries](../src/manor/mechanics/deliveries.clj) do not turn such prose into deposits.

## Assessment and possible follow-up

**Supported diagnosis:** extravagant character instructions encourage a grand scheme; the
price ladder fixes that scheme at the whole starting balance; Pugh reinforces the choice;
shared household spending makes it unaffordable; weak replanning and confusion between
narrated transactions and hard state sustain the resulting loop.

**Not established:** a general DeepSeek tendency to exhaust every available budget, or that
another model would solve this reliably. The same model produced restrained plans nearby,
but these were not controlled comparisons: prompts, run lengths, reasoning settings and
other conditions differed. The initial spending choice and the subsequent refusal loop
should be evaluated separately.

If the user chooses to address it, possible interventions to evaluate are:

- Reconsider whether the top building price should exactly equal all starting household funds.
- Decide whether construction should have an explicit budget/reserve distinct from household
  operating money, and whether that is a social instruction or an engine-enforced constraint.
- Make failed-commission feedback explain the available remedy: choose a cheaper size/redraft;
  spoken deposits and promises cannot change the balance. Do not imply an unavailable deposit
  action exists.
- Compare models only with matched character prompts and mechanics, distinguishing initial
  extravagance from recovery after an unaffordable commission.

These are options, not approved changes. A reserve would not by itself make an extravagant
character prudent, and a more prudent prompt would not by itself repair the price ladder or
provide a missing financial action.
