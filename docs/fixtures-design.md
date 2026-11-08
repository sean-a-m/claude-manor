# Fixtures and work orders: design proposal

**Status:** phase 1 built and mock-tested (section 7); phase 2 next. Written 2026-11-07 from the user's brief and revised the same day with their
answers (section 2). Agents should be able to add, change, move and remove fixtures and fixed items
(a chandelier, a new dining table, a brick oven) in every scenario. Section 6 lists the awkward
cases still to settle.

Read `AGENTS.md`, `docs/trades-design.md` (contacts, `send-for`), `docs/deliveries-design.md`,
`src/manor/mechanics/building.clj` (the architect: the nearest precedent) and
`src/manor/mechanics/shop.clj` (the appraiser) first.

## 1. What exists

**`:fixed? true`** marks an item as not portable, part of the room. Code uses it in five places:

| Where | Effect |
|---|---|
| `items/actions` `take` | refused: "the piano is fixed in place" |
| `items/judge` `gone`/`on` | can't be used up or kept only as an aspect of something else |
| `items/absorbable?` | can't be absorbed into a store's stock |
| `items/find` | never a candidate (nobody needs to find the range) |
| `items/problems` | a store must be fixed |

Its own aspects still change ("dusty", "a leg loose"). It covers furniture as well as things
built in; `docs/stores-and-aspects-design.md` calls this a gameplay classification. It **is still
useful**: without it agents pick up dining tables and the judge dissolves sideboards into
aspects. The trouble is that it can never change. No action adds a fixed item, moves one to
another room, alters what it is (`:desc` never changes) or takes one away.

**Things that come close:**

- The **architect** (`building.clj`): a priced draft (a query), `commission` paid from an account,
  a due round, and a tick that adds the room. It's engine-applied and deterministic, and it's the
  model for this design.
- **Shops** (`quote-goods`, `buy`, deliveries): portable stock from shops on the map, appraised
  against a store profile. They don't make things to order, don't set anything in place, and
  don't reach merchants off the map.
- **Tradespeople** (`recommend` with `trade`, `send-for` with a job): someone comes, but
  "discussing a job does not schedule or perform it". They work with `spend-time`, which can
  only change aspects or make at most two loose, unfixed things.

## 2. Decisions (the user's, 2026-11-07)

1. **One call starts it all.** A process spread over many tool calls and days won't be found
   without a lot of documentation, so `plan-work` comes first. The character says what they
   want ("a grand piano in the drawing room", "a new fireplace in the library"). The works judge
   gives back a private **work plan**: what will be done, what it will cost, and who is best
   placed to do it. That tells the character whom to ask for, through `recommend` or `send-for`,
   or whom to tell, if it's household work.
2. **`order-work` gives a plan to someone to carry out.** That's where the price is agreed
   (`revise-work`). Household work is planned at no pay, but may cost money for materials.
3. **Materials are supplied as soon as it's paid for.** There's no waiting for goods, and no
   second visit.
4. **Paid in full when agreed**, as `buy` and `commission` are. If deposits or bills come later,
   they'll be one feature shared by every kind of payment.
5. **Anyone may plan, order, pay and carry out.** The judge *recommends* a trade or the
   household, and nothing enforces it.
6. **The outcome is judged with a luck roll**, as `spend-time` is, weighing the worker against
   the work. Someone unsuited, or unlucky, may botch it. The mad count has the kitchen maid
   redo the gas fittings and gets a gas leak, or has the village carpenter build a pipe organ
   and gets a barely playable one.
7. **Work is a list of phases**, each with a time. Later, a long project may take many phases,
   and a phase that goes wrong may need more work than planned. **For now the judge plans
   exactly one phase of 2 hours,** with one worker. The judge is told one worker is always
   enough. Several workers on one work come later, since they need coordination.
8. **Kinds: `install`, `move` and `remove`, and `alter` if it stays simple** (section 4).
   `remove` makes something vanish; moving something elsewhere is a `move`. No `supply`:
   small things come from shops.
9. **Plans are private** to whoever planned them, until offered with `order-work`.
10. **`spend-time` keeps making temporary changes:** aspects, as now. Lasting changes to what a
    thing is, or where a fixed thing stands, are works.
11. **A cap on open plans** per person, like the other caps on records.
12. **Lapsing and refunds** for work that's never finished: not dealt with yet.

## 3. For the characters

**Household work.** Lady C, in the drawing room, calls
`plan-work {kind: "move", target: "piano", to: "morning-room", request: "the piano moved to the morning room"}`.
Only she sees:

> Work plan work-3: move the piano to the Morning room. Best done by a strong manservant of the
> household, such as a footman; no tradesman needed. One stage, 2 hours: "carrying the piano
> through to the morning room". Materials: none. Use `order-work` to give it to someone.

She calls `order-work {id: "work-3", worker: "james"}` to James, the footman, who is with her.
He sees the plan and the price (nothing) and calls `accept-work`. It's his now: it's under
`you.works` in his view, with a calendar entry. He calls `carry-out`. Two hours later the scene
judge, with his luck, says how it went. The piano is in the morning room, perhaps with a new
scratch on its side.

**A tradesperson's work.** Lady C plans `install` with "a three-light gas chandelier over the
dining table". The plan says it's work for a gas fitter, with a warning that anyone else
risks a leak. It estimates £14, including £9 of materials. She has Mr Pratt, a gas fitter,
recommended and sent for. When he comes she `order-work`s it to him at the estimate, he
`revise-work`s it to £15 10s, and she `accept-work`s. £15 10s leaves the household account,
and £6 10s of it goes to his purse; the materials are there at once. He `carry-out`s. With
ordinary luck the dining room has a new fixed item, a brass gasolier.

**The mad count.** The count `order-work`s the same plan to Ellen, the kitchen maid, at no pay
but the materials, and she accepts. The judge weighs a kitchen maid doing a gas fitter's work
against her luck. The gasolier goes up "hanging a little crooked, one tap stiff", and the
dining room's air is "smelling faintly of gas". Or, with worse luck, nothing is fitted and the
pipe is left open. Whatever happens comes from the ordinary scene verdict. Nothing about gas is
coded.

**Upkeep stays as it is.** Tuning the piano or regluing a chair leg is `spend-time`, judged into
aspects. If someone plans upkeep, the judge refuses it and points them to `spend-time`.

## 4. Kinds of work

| `kind` | Arguments | When it's done |
|---|---|---|
| `install` | the planner's place | a new **fixed** item there, owned by the paying account |
| `move` | `target`, an item here, and `to`, a location in the home area | the item goes there, with whatever is in it, fixed if it was |
| `remove` | `target` | the item is gone, with a fate ("broken up and carted away"); what was in it is left at its place |
| `alter` (optional) | `target` | the target is **replaced**: it goes to `:items-gone` ("made over into the billiard table"), and a new fixed item with a fresh id takes its place; whatever it held goes into the new one if that's a container, otherwise it's left at the place |

**Why `alter` as replacement.** Changing an item where it stands raises awkward cases: an id
like `dining-table` naming a billiard table, `:desc` (which never changes today) changing,
stores whose `keeps` no longer fit, containers that stop being containers, goods some mechanic
tracks. Replacement is just `remove` followed by `install`, using their reducers. The edge cases
that are left are refused by code: the target must lie at the place (not carried), mustn't be
a store, and mustn't be `items/identity-bound?`. It also covers setting a loose thing in place
(the table someone bought loose becomes a fixed one). If even this proves awkward, phase 1
leaves `alter` out.

`:fixed?` keeps its meaning. `install` always makes a fixed thing (loose things come from
shops), and the rules and `take`'s refusal ("the piano is fixed in place; plan a work to move
it") point to works. Works may target loose things too (moving a heavy chest).

## 5. The engine

### 5.1 The record

Stored under `[:state :work-orders]` (`[:state :works]` is the architect's), keyed by id:

```clojure
{:id "work-5"
 :planner "lady-c"
 :kind :install :place "dining-room" :target nil :to nil
 :request "a three-light gas chandelier over the dining table"   ; the planner's words
 :summary "Install a brass gasolier over the dining table."       ; the judge's
 :who "A gas fitter. Anyone else risks a leak."                   ; the recommendation: text only, never enforced
 :item {:name "Brass gasolier" :desc "..." :aspects {...}}        ; install/alter: the thing as planned
 :fate nil                                                        ; remove/alter: the old thing's fate
 :estimate 3360 :materials 2160                                   ; old pence (£14, £9); materials included
 :phases [{:doing "fixing the gasolier and running the pipe to it" :minutes 120 :done 0}]
 :known-to #{"lady-c"}
 ;; once offered and accepted:
 :orderer "lady-c" :worker "pratt" :price 3720 :priced-by "pratt" :account "household"
 :status :planned}   ; :planned | :offered | :accepted | :done | :failed | :cancelled
```

### 5.2 The works judge: planning

One checked narrow call per `plan-work`, built like the shop appraiser and the trade judge: a
template `resources/prompts/judges/works.md` (overridable per setting pack), a tool with a
schema, and code that checks only the answer's structure. It sees the setting's period and money
guidance, the planner (name, position), the place (name, desc, aspects, items there by name),
the target in full and the destination's name, the household's positions, and the request as
untrusted data.

It answers `{refused: reason}` (an anachronism, the impossible, or upkeep that should be
`spend-time`), or a plan: `summary`, `who` (a recommendation, including what goes wrong with
the wrong hands), `item?`, `fate?`, `estimate`, `materials`, `phases [{doing, minutes}]`. The
template says: exactly one phase of 120 minutes, for one worker. For household work, the
estimate is the materials alone.

Code checks `materials ≤ estimate`, money within a bound, the item's lengths against
`items/Item`, an item present for install/alter and a fate for remove/alter, and exactly
`max-phases` (1 for now) phases with minutes within bounds. A failed check gets one retry with
the reason.

### 5.3 Actions

| Action | Who | Kind | Code checks | Effect |
|---|---|---|---|---|
| `plan-work {kind request target? to?}` | anyone | query | at the place; target here, and fit for the kind (4); `to` a home location; cap on open plans | judge; `:works/planned`, seen by the planner only |
| `order-work {id worker price?}` | anyone who knows the plan | free | planned; worker present (or themselves); `price` ≥ materials, defaults to the estimate | `:works/offered`: the worker learns the plan and the price |
| `revise-work {id price}` | orderer or worker | free | offered; `materials ≤ price ≤` a bound | `:works/revised`, seen by both |
| `accept-work {id from?}` | whoever didn't set the current price | free | offered; together; `money/spend-reason` on the orderer's account | `:works/accepted`: the price paid, the labour (price − materials) to the worker's purse, the materials supplied |
| `decline-work {id}` | the worker | free | offered | back to planned; the orderer can offer it to someone else |
| `cancel-work {id}` | planner or orderer | free | not yet accepted | `:works/cancelled` |
| `carry-out {id}` | the worker | timed | accepted; at the work's place (where the target is); the target still there | the next phase's job |

Ordering is never refused for the wrong worker (decision 5). The plan's `who` is in the
worker's offer too, so a kitchen maid can see she's being asked to do a gas fitter's job.
Haggling is the price going back and forth with `revise-work` until one side accepts. It's a
rule about who moved last, so nobody's words need judging. `order-work`, `revise-work` and
`accept-work` are offered on speaking turns, so the bargain can be made in conversation.
Ordering a plan to yourself is allowed (the count does it himself) and skips the offer.

### 5.4 Carrying out, and the outcome (decision 6)

`carry-out` starts an ordinary timed job (`item-jobs`, `:busy`), its `doing` the phase's text,
with a `:work-order` reference. It can be stopped early and resumed: the minutes worked add up
on the phase. When a phase is complete, the **scene judge** runs on it as on any `spend-time`
job, with its luck roll (`rng/roll` keyed on the job), the worker's persona and position, and
the scene. Two additions:

- **A `## The work` section** in its request: the plan's summary and `who`, this phase's text,
  "phase 1 of 1", the planned item, and the worker's position or contact trade. The brief tells
  it to weigh the worker's fitness for the work together with the luck. A suited worker with
  ordinary luck does it as planned. An unsuited one is likely to do it badly, and the luck
  decides how badly.
- **A `work` entry in its verdict**, required when a work's phase is judged:
  `{outcome: "as-planned" | "flawed" | "failed", item?: {name desc aspects}}`.

| outcome | `install` / `alter` | `move` | `remove` |
|---|---|---|---|
| as-planned | the planned item | moved | gone |
| flawed | the judge's `item` (the barely playable organ), or the planned one with flaw aspects | moved; damage through ordinary `changes` | gone |
| failed | nothing is made; for `alter` the old thing stays; materials spent | stays | stays |

Side effects come from the rest of the same verdict, as they already can: room aspects (air
"smelling of gas", floor "scored where the piano was dragged"), aspects of things there, and
the worker's physical status or impediments (a burnt hand). The scene judge still can't `made`
a fixed thing. Only `work` applies the work. So `:works/done` (or `:works/failed`) follows the
`:item/work-done` of the same job, and its reducer applies the structural change: a new item,
a place changed, an item to `:items-gone`.

This costs **no extra model call**: the job would have been judged anyway. It's also the hook
for decision 7 later. With several phases, each phase's verdict has an outcome, and a flawed one
can add a phase ("more work than planned").

If the target has gone before the work is done, `:works/failed` (no refund: decision 12).

### 5.5 Staying on task

All of this uses what the engine already has:

- **The view.** Accepted work is under `you.works` in the worker's view every turn ("use
  carry-out with work-5 in the Dining room"). The planner and the orderer see its status.
- **A calendar entry** for the worker, managed by the work like visits' entries.
- **The early reminder** (`agent/reminder-early.md`) mentions accepted work not yet begun.
- **Departure.** A tradesperson from elsewhere doesn't leave idle while they have accepted work
  unfinished. Hibernation's `keeps-awake?` counts it too.

### 5.6 Views and records

- `works` in the views of the planner, the orderer and the worker.
- The household's order book (`purchases`) gets an entry for each work paid from the household
  account, so the housekeeper sees where the £15 10s went.
- The panel's scene view and inspector list works. GM and operator tools for placing and
  removing works directly come in phase 3.

## 6. Settled and remaining cases

Settled with the user: plans are private (1). One worker per work, the judge being told one is
enough (2). Paying a servant something for household work is just a price (3). Nothing forces a
worker who's been paid to do the work, and no refunds yet (4). `alter` sets a bought loose
thing in place, if anyone needs that (5). `move` stays within the home area (6). The cap on
open plans (7). `spend-time` makes only temporary changes, and the rules point lasting ones to
`plan-work` (8).

Still to watch in the build:

- **The scene judge's prompt grows** by a section only when it judges a work. Its rules on
  `made` must say plainly that the work itself comes only through `work`, so it doesn't make a
  second gasolier.
- **Who sees the outcome:** everyone at the place, as with `spend-time`. The planner and
  orderer, if elsewhere, see the work's status change in their views.

## 7. Phases

1. **Plans and household work (done).** The record, the planning judge (template and an offline mock),
   `plan-work`, `order-work`, `accept-work`, `decline-work`, `cancel-work`, `carry-out` with one
   phase, the scene judge's `work` section and entry, `:works/done`/`:works/failed` for install,
   move and remove, views, the reminder, calendar entries, the rules and `take`'s pointer.
   Mock-tested with scripted verdicts for each outcome: the piano moved, a dresser removed, a
   range installed, and a failed install. Replay and privacy tests.
   Built in these steps, each small enough for one session and each leaving the tests passing:

   - **1a. The record and `plan-work`.** A new namespace `manor.mechanics.works`: the record in
     `[:state :work-orders]`, `:works/planned` (pure reducer, narration), the planning judge
     (template `judges/works.md`, tool schema, structural checks, one retry, a deterministic
     mock adjudicator for mock runs and tests), `plan-work` as a query with its checks and the
     cap on open plans, and `works` in the planner's view. Installed in the ruleset. Tests:
     a plan made and private to the planner, a refusal, the checks, the cap, replay.
     *Done:* `manor.mechanics.works` and `works/judge.clj`, the templates `judges/works.md`,
     `judges/tools/work-plan.md` and `docs/works/plan-work.md`, `test/manor/works_test.clj`.
     Choices where this design was silent: a target inside a container is refused (plan the
     work on what holds it); `to` may not be the place itself; an install takes no target and
     only a move takes `to`; the estimate is bounded at £500 (500 authored units); the record
     keeps a counter `:n` and `[:state :work-order-count]`; the plan's `who` and money show
     in its narration; the mock judge plans everything as household work, costs nothing but
     an install's materials (480 in the smallest unit), and refuses a request starting with
     "refuse". The rules text doesn't mention works yet (step 1e).
   - **1b. Offering.** `order-work`, `accept-work`, `decline-work`, `cancel-work`, with events,
     reducers, narration and views for the orderer and the worker. No money: a price is
     recorded but nothing is paid. Ordering to yourself. Offered on speaking turns. Tests.
     *Done:* in `manor.mechanics.works`, with the tool docs `docs/works/order-work.md`,
     `accept-work.md`, `decline-work.md` and `cancel-work.md`; `works/speaking-actions` adds
     `order-work` (to someone in the conversation, or oneself), `accept-work` and `decline-work`
     to the ruleset's speaking turns. Choices where this design was silent: a plan the actor
     doesn't know of is refused as unknown, like one that doesn't exist; the price is bounded
     by the same £500 as estimates; ordering to yourself emits `:works/accepted` straight away
     (no `:works/offered`); `decline-work` doesn't need the orderer present (only `accept-work`
     does), and a decliner keeps knowing the plan; `cancel-work` is seen by everyone who knows
     the plan, and cancelled works leave the views; the offer's and acceptance's events carry
     the plan's text for their narration, while the reducers take only the terms
     (`:orderer :worker :price :priced-by`); `order-work`, `accept-work`/`decline-work` and
     `cancel-work` are only in the tools while there's something for them to act on.
   - **1c. `carry-out`.** A timed job through the existing item-jobs machinery with a
     `:work-order` reference; minutes worked add up on the phase when stopped early, and
     `carry-out` resumes. Tests.
     *Done:* `carry-out` in `manor.mechanics.works` (tool doc `docs/works/carry-out.md`): an
     `:item/work-begun` with `:work-order {:id :phase}`, its `doing` the phase's text, due after
     the phase's minutes not yet worked, plus the occupation, as `spend-time` makes them. The
     scene judge runs on the job as on any other for now (its `work` entry is 1d). A new
     scenario hook, `:after-job` (fns `[world job work result]` run by `manor.engine.work`
     after a job's ending is emitted), records the work: stopped early with time worked,
     `:works/progressed` sets the phase's `:done`; finished, `:works/phase-done` completes the
     phase and sets the status to `:finishing` (1d applies the outcome there). Both are seen by
     the worker only. Choices where this design was silent: the job's item is the target, or for
     an install the place itself; `carry-out` is refused while a job for the work is going on
     (which is how "nobody else on that phase" is checked; with one worker it also catches the
     worker himself); a carried target is refused (set it down first); `works` entries of
     accepted and finishing works show `phase` ("1 of 1: …") and `progress` ("60 of 120
     minutes done"), and the worker's has `next`, the carry-out hint with the place.
   - **1d. The outcome.** The scene judge's "## The work" section and its required `work` entry
     when it judges a work's job; `:works/done` and `:works/failed` applying install, move and
     remove; the scene judge's instructions saying the work happens only through `work`.
     Tests with scripted verdicts: a range installed, the piano moved, a dresser removed, a
     failed install, a target gone.
     *Done:* the scene judge (`items.judge`) asks for `work` only when the job has a
     `:work-order` (`answer-schema`'s `work?`), and a missing or malformed one gets the usual
     retry with the reason; the scenario's new `:items :work-brief` (set by `works/install` to
     `works/work-brief`) adds `judges/scene-work.md` to its instructions and a `## The work`
     section to its request, and `judges/tools/judge-work-order.md` to its tool text. On the last
     phase `works/after-job` emits `:works/done` or `:works/failed` after `:works/phase-done`,
     seen by those at the place, the worker, the planner and the orderer; the work's status
     becomes `:done` or `:failed` with its `:outcome` (and `:made`, an install's new item id), and
     `works` entries of done and failed works show `result`. Tests in `works_test.clj`.
     Choices where this design was silent: **a failed judge call (no verdict) counts as
     as-planned**, matching the neutral summary the job already gets, so the household isn't
     punished by a bad answer; the default mock scene judge also says as-planned; an install's
     item is fixed and owned by `"household"` (the paying account comes in phase 2) and gets its
     id from `items/new-item` on the world before the event, so the reducer stays pure; a
     flawed install without a judge's `item` keeps the planned one as it is (no flaw aspects are
     invented by code); a removal leaves the things directly inside the target at its place (the
     things inside those stay inside them); a move or removal whose target has gone fails
     whatever the judge said; "the worker is" in the section is the trade of a contact whose
     person is the worker, else their position's title.
   - **1e. Staying on task and the rules.** The early reminder, a tradesperson from elsewhere
     not leaving idle with accepted work, `keeps-awake?`, a calendar entry if it's simple; the
     rules text and `take`'s refusal pointing to `plan-work`. Tests.
     *Done:* in `manor.mechanics.works`: `open-work?` (accepted or finishing, for the worker)
     holds back a tradesperson's idle departure (`trades/idle-departure`; `take-leave` stays
     allowed) and keeps the worker out of hibernation (`ruleset.hibernation/hibernate`, beside
     `visits/keeps-awake?`, which stays about visits). A new scenario key `:early-reminder`
     (`(fn [world agent-id] text-or-nil)`, called by `engine.seat`) adds to the early reminder;
     works set it to `works/early-reminder`, one `agent/reminder-work.md` sentence per accepted
     work the agent isn't carrying out now. Works own calendar entries with ids `works-<id>`
     (`calendar-owner`, under `:calendar :managed :works`): on acceptance the worker gets an
     all-day entry for today ("Carry out work work-1: … (in the Drawing room)"), cancelled with the
     `:works/done` or `:works/failed` of the last phase. The rules have a `rules/sections/works.md`
     section after "Things"; `take`'s and `put-in`'s refusal of a fixed thing ends with the
     scenario's `:items :fixed-hint`, which works set to "to move it, plan a work with plan-work".
     Choices where this design was silent: the reminder says nothing while the work's job is going
     on or once it's finishing; the calendar entry is dated the day of acceptance (works have no
     appointed time), and a cancelled work needs no removal since only accepted works have one
     (accepted works can't be cancelled); the works section is always in the ruleset's rules,
     since the ruleset always installs works; a work given to a GM-spawned or away agent works the
     same way.
   - **1f. Mock day and docs.** The scripted mock policy plans and carries out a move;
     `docs/scenario-authoring.md`, `docs/prompts.md`; the full suite.
     *Done:* the scripted mock policy needed no change: work ids are deterministic, so a day
     scripts `plan-work`, `order-work {:id "work-1"}`, `accept-work` and `carry-out` as plain
     tool entries. `works_test/a-scripted-day-moves-the-cabinet` runs one on the test household
     (with Nellie and a calendar): the countess plans the china cabinet moved to the dining room
     and orders it to Nellie, who accepts, gets the early reminder once, carries it out over
     four rounds, and the cabinet ends in the dining room; her calendar entry is made and
     cancelled; no forfeits or refused calls; replay matches. The bundled mock days are left
     alone (Claudeworth's day checks the china cabinet's dusting and Nellie's round). The
     contract is under Works in `scenario-authoring.md`; roadmap B20.

   **Phase 1 is done**, mock-tested only; no real-model check yet. Phase 2 is next.

2. **Money and trades.** Prices, `revise-work`, payment and the labour to the worker's purse, the
   order book, a tradesperson staying until the work's done; `alter` if it's still wanted. A
   mock day with a gasolier fitted by a sent-for contact, and one botched by a maid. Then a short
   real run with the user's go-ahead, started with the gas fitter already sent for.
3. **Polish.** GM and operator tools, the panel's scene view.
4. **Later, when needed:** several phases, with a flawed phase adding work; several workers;
   lead times for goods made off site; merchants by letter; shared deposits and bills; refunds
   and lapsing.

## 8. Why not other ways

- **Let the scene judge make fixed things in `spend-time`.** It's cheap, but the judge would
  make chandeliers out of nothing, with no plan, price or record. The plan makes the change hard
  state first; the judge only says how well it was done.
- **A separate outcome judge.** It would be another call and another prompt, and would duplicate
  the scene judge's side effects. A section and an entry in the existing verdict do it.
- **Enforce the recommended trade.** It would stop the mad count, and it would need a judge to
  compare trades. A recommendation, plus the outcome roll, is both simpler and more fun.
- **Drop `:fixed?`.** It would let agents carry off the piano and let the judge use up furniture.
  The flag isn't the problem: the problem is that nothing could change a fixed thing.
